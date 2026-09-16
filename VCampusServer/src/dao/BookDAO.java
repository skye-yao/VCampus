package dao;

import entity.Book;
import util.DBUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import util.LocalTimeConnection;

/**
 * 图书数据访问对象
 *
 * 负责 tblBook 表的数据访问。
 */
public class BookDAO {
    @FunctionalInterface
    interface ConnectionFactory { Connection open() throws SQLException; }
    private final ConnectionFactory connections;

    public BookDAO() { this(LocalTimeConnection::getConnection); }
    BookDAO(ConnectionFactory connections) { this.connections = connections; }

    private boolean lockBook(Connection conn, int bookId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
            stmt.setInt(1, bookId);
            try (ResultSet rows = stmt.executeQuery()) { return rows.next(); }
        }
    }

    private int executeForBook(Connection conn, String sql, int bookId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, bookId);
            return stmt.executeUpdate();
        }
    }

    /** 用户挂失与管理员修改共用图书行锁，检查和写入不可分开提交。 */
    public boolean changeLoss(String userId, int bookId, boolean report) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                if (!lockBook(conn, bookId)) { conn.rollback(); return false; }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM tblBorrowRecord WHERE bookid=? AND userid=? AND status IN(0,2) AND returnTime IS NULL")) {
                    stmt.setInt(1, bookId); stmt.setString(2, userId);
                    try (ResultSet rows = stmt.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                    }
                }
                boolean active;
                try (PreparedStatement stmt = conn.prepareStatement(
                        "SELECT id FROM tblLossRecord WHERE bookid=? AND userid=? AND status=0")) {
                    stmt.setInt(1, bookId); stmt.setString(2, userId);
                    try (ResultSet rows = stmt.executeQuery()) { active = rows.next(); }
                }
                if (active == report) { conn.rollback(); return false; }
                if (!report) LibraryCirculationDAO.syncBook(conn,bookId,java.time.LocalDateTime.now());
                try (PreparedStatement stmt = conn.prepareStatement(report
                        ? "INSERT INTO tblLossRecord(bookid,userid,lossTime,status) VALUES(?,?,CURRENT_TIMESTAMP,0)"
                        : "UPDATE tblLossRecord SET status=1 WHERE bookid=? AND userid=? AND status=0")) {
                    stmt.setInt(1, bookId); stmt.setString(2, userId); stmt.executeUpdate();
                }
                if (report) {
                    try (PreparedStatement stmt = conn.prepareStatement("UPDATE tblBorrowRecord SET feeStopTime=COALESCE(feeStopTime,CURRENT_TIMESTAMP) " +
                            "WHERE bookid=? AND userid=? AND status IN(0,2) AND returnTime IS NULL")) {
                        stmt.setInt(1,bookId); stmt.setString(2,userId); stmt.executeUpdate();
                    }
                }
                executeForBook(conn, "UPDATE tblBook SET status=CASE WHEN EXISTS " +
                        "(SELECT 1 FROM tblLossRecord l WHERE l.bookid=tblBook.id AND l.status=0) " +
                        "THEN 3 ELSE 1 END WHERE id=?", bookId);
                LibraryCirculationDAO.syncBook(conn,bookId,java.time.LocalDateTime.now());
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    public boolean cancelReservation(String userId, int reservationId) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                int bookId;
                try (PreparedStatement query = conn.prepareStatement("SELECT bookid FROM tblReservation WHERE id=? AND userid=? AND status=0")) {
                    query.setInt(1, reservationId); query.setString(2, userId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                        bookId = rows.getInt(1);
                    }
                }
                try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
                    lock.setInt(1, bookId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                    }
                }
                try (PreparedStatement update = conn.prepareStatement("UPDATE tblReservation SET status=1 WHERE id=? AND userid=? AND status=0")) {
                    update.setInt(1, reservationId); update.setString(2, userId);
                    if (update.executeUpdate()!=1) { conn.rollback(); return false; }
                }
                try (PreparedStatement update = conn.prepareStatement(
                        "UPDATE tblBook b SET status=CASE " +
                        "WHEN EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.bookid=b.id AND l.status=0) THEN 3 " +
                        "WHEN EXISTS(SELECT 1 FROM tblBorrowRecord r WHERE r.bookid=b.id AND r.status IN(0,2) AND r.returnTime IS NULL) THEN 1 " +
                        "WHEN EXISTS(SELECT 1 FROM tblReservation r WHERE r.bookid=b.id AND r.status=0) THEN 2 ELSE 0 END " +
                        "WHERE id=? AND status=2")) {
                    update.setInt(1, bookId); update.executeUpdate();
                }
                conn.commit(); return true;
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }
    // 列表、详情与预约共用同一可用性判定，避免只看陈旧的 tblBook.status。
    private static final String EFFECTIVE_STATUS = "CASE " +
            "WHEN status=3 OR EXISTS (SELECT 1 FROM tblLossRecord l WHERE l.bookid=tblBook.id AND l.status=0) THEN 3 " +
            "WHEN status=1 OR EXISTS (SELECT 1 FROM tblBorrowRecord b WHERE b.bookid=tblBook.id AND b.status IN (0,2) AND b.returnTime IS NULL) THEN 1 " +
            "WHEN status=2 OR EXISTS (SELECT 1 FROM tblReservation r WHERE r.bookid=tblBook.id AND r.status=0) THEN 2 " +
            "ELSE status END";
    private static final String BOOK_SELECT = "SELECT id,isbn,name,author,publisher,category,price,COALESCE(titleId,id) AS catalogId," + EFFECTIVE_STATUS + " AS status FROM tblBook ";

    /** 锁住图书行后再次检查，并在一个事务内完成预约和状态更新。 */
    public boolean reserveAvailableBook(String userId, int bookId) throws SQLException {
        try (Connection conn = connections.open()) {
            // 等待书目锁之后必须看到前一个预约事务已提交的分配结果。
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            conn.setAutoCommit(false);
            try {
                int titleId;
                try (PreparedStatement query = conn.prepareStatement("SELECT COALESCE(titleId,id) FROM tblBook WHERE id=?")) {
                    query.setInt(1, bookId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                        titleId = rows.getInt(1);
                    }
                }
                // 同一书目的分配串行化，避免同一读者并发预约不同册。
                if (!lockBook(conn, titleId)) { conn.rollback(); return false; }
                List<Integer> copies = new ArrayList<>();
                try (PreparedStatement query = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? OR titleId=? ORDER BY id FOR UPDATE")) {
                    query.setInt(1,titleId); query.setInt(2,titleId);
                    try (ResultSet rows = query.executeQuery()) { while (rows.next()) copies.add(rows.getInt(1)); }
                }
                for (int copy : copies) LibraryCirculationDAO.expireBookReservations(conn,copy,java.time.LocalDateTime.now());
                try (PreparedStatement query = conn.prepareStatement(
                        "SELECT id FROM tblBook WHERE (id=? OR titleId=?) AND (" +
                        "EXISTS(SELECT 1 FROM tblReservation r WHERE r.bookid=tblBook.id AND r.userid=? AND r.status=0) OR " +
                        "EXISTS(SELECT 1 FROM tblBorrowRecord r WHERE r.bookid=tblBook.id AND r.userid=? AND r.status IN(0,2) AND r.returnTime IS NULL))")) {
                    query.setInt(1,titleId); query.setInt(2,titleId); query.setString(3,userId); query.setString(4,userId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (rows.next()) { conn.commit(); throw new exception.BusinessException("您已预约或借阅该书，每种书同时限借一册"); }
                    }
                }
                bookId = 0;
                try (PreparedStatement query = conn.prepareStatement(BOOK_SELECT + "WHERE id=? OR titleId=? ORDER BY id")) {
                    query.setInt(1,titleId); query.setInt(2,titleId);
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) if (rows.getInt("status")==0) { bookId=rows.getInt("id"); break; }
                    }
                }
                if (bookId==0) { conn.commit(); return false; }
                try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
                    lock.setInt(1, bookId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                    }
                }
                LibraryCirculationDAO.expireBookReservations(conn,bookId,java.time.LocalDateTime.now());
                try (PreparedStatement query = conn.prepareStatement(BOOK_SELECT + "WHERE id=?")) {
                    query.setInt(1, bookId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next() || rows.getInt("status") != 0) { conn.commit(); return false; }
                    }
                }
                try (PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO tblReservation(userid,bookid,reserveTime,status) VALUES (?,?,CURRENT_TIMESTAMP,0)");
                     PreparedStatement update = conn.prepareStatement("UPDATE tblBook SET status=2 WHERE id=?")) {
                    insert.setString(1, userId);
                    insert.setInt(2, bookId);
                    insert.executeUpdate();
                    update.setInt(1, bookId);
                    update.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    /**
     * 根据图书编号查询图书。
     *
     * @param id 图书编号
     * @return 图书，不存在返回 null
     */
    public Book findById(Integer id) throws SQLException {

        String sql =
                BOOK_SELECT + "WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = connections.open();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapBook(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据 ISBN 精确查询图书。
     *
     * @param isbn ISBN
     * @return 图书，不存在返回 null
     */
    public Book findByIsbn(String isbn) throws SQLException {

        String sql =
                BOOK_SELECT + "WHERE isbn = ? AND titleId IS NULL";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = connections.open();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, isbn);

            rs = stmt.executeQuery();

            if (rs.next()) {
                return mapBook(rs);
            }

            return null;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 根据关键字检索图书。
     *
     * 支持：
     * 1. 书名
     * 2. 作者
     * 3. ISBN
     *
     * @param keyword 检索关键字
     * @return 匹配的图书列表
     */
    public List<Book> findBooks(String keyword) throws SQLException {

        String sql =
                BOOK_SELECT +
                        "WHERE name LIKE ? " +
                        "OR author LIKE ? " +
                        "OR isbn LIKE ? " +
                        "ORDER BY id";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        List<Book> books = new ArrayList<>();

        try {
            conn = connections.open();
            stmt = conn.prepareStatement(sql);

            String key = "%" + keyword + "%";

            stmt.setString(1, key);
            stmt.setString(2, key);
            stmt.setString(3, key);

            rs = stmt.executeQuery();

            while (rs.next()) {
                books.add(mapBook(rs));
            }
            return aggregateCopies(books);

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 新增图书。
     *
     * @param book 图书
     * @return 是否新增成功
     */
    public boolean insert(Book book) throws SQLException {
        Book.validateInitialCopies(book.getTotalCopies());

        String sql =
                "INSERT INTO tblBook " +
                        "(isbn, name, author, publisher, status, price, category, categoryInitialized) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = connections.open();
            conn.setAutoCommit(false);

            stmt = conn.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            stmt.setString(1, book.getIsbn());
            stmt.setString(2, book.getName());
            stmt.setString(3, book.getAuthor());
            stmt.setString(4, book.getPublisher());
            stmt.setInt(5, book.getStatus());
            stmt.setBigDecimal(6, book.getPrice());
            stmt.setString(7, book.getCategory());

            int rows = stmt.executeUpdate();

            if (rows > 0) {

                rs = stmt.getGeneratedKeys();

                if (rs.next()) {
                    book.setId(rs.getInt(1));
                }
                LibrarySchema.initializeCopies(conn, book.getId(), book.getTotalCopies());
                conn.commit();
                return true;
            }

            conn.rollback();
            return false;
        } catch (SQLException | RuntimeException e) {
            if (conn != null) conn.rollback();
            throw e;
        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 管理员只能将遗失图书找回入库；状态不变时仅修改基本信息。
     *
     * @param book 图书
     * @return 是否修改成功
     */
    public boolean update(Book book) throws SQLException {
        return update(book, false);
    }

    public boolean updateCatalog(Book book) throws SQLException {
        return update(book, true);
    }

    private boolean update(Book book, boolean metadataOnly) throws SQLException {
        if (book == null || book.getId() <= 0 || book.getStatus() < 0 || book.getStatus() > 3) return false;
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                int id = book.getId();
                if (!lockBook(conn, id)) { conn.rollback(); return false; }
                int currentStatus;
                try (PreparedStatement query = conn.prepareStatement(BOOK_SELECT + "WHERE id=?")) {
                    query.setInt(1, id);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                        currentStatus = rows.getInt("status");
                    }
                }
                int status = book.getStatus();
                boolean recovered = !metadataOnly && currentStatus == 3 && status == 0;
                if (!metadataOnly && status != currentStatus && !recovered) {
                    throw new IllegalArgumentException("不合法的状态转换：管理员只能将遗失图书改为可借（找回入库），请刷新后重试");
                }
                if (recovered) {
                    LibraryCirculationDAO.recoverBook(conn,id,java.time.LocalDateTime.now());
                }
                try (PreparedStatement stmt = conn.prepareStatement(
                        "UPDATE tblBook SET isbn=?,name=?,author=?,publisher=?,price=?,category=?,categoryInitialized=TRUE WHERE id=? OR titleId=?")) {
                    stmt.setString(1, book.getIsbn()); stmt.setString(2, book.getName());
                    stmt.setString(3, book.getAuthor()); stmt.setString(4, book.getPublisher());
                    stmt.setBigDecimal(5,book.getPrice()); stmt.setString(6,book.getCategory());
                    stmt.setInt(7, id); stmt.setInt(8,id); stmt.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        }
    }

    /**
     * 删除图书。
     *
     * @param id 图书编号
     * @return 是否删除成功
     */
    public boolean delete(Integer id) throws SQLException {

        String sql = "DELETE FROM tblBook WHERE id = ? OR titleId = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = connections.open();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);
            stmt.setInt(2, id);

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 修改图书状态。
     *
     * @param id 图书编号
     * @param status 状态
     * @return 是否修改成功
     */
    public boolean updateStatus(
            Integer id,
            Integer status
    ) throws SQLException {

        String sql =
                "UPDATE tblBook SET status = ? WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = connections.open();
            stmt = conn.prepareStatement(sql);

            stmt.setInt(1, status);
            stmt.setInt(2, id);

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * ResultSet 映射为 Book 实体。
     */
    private Book mapBook(ResultSet rs)
            throws SQLException {

        Book book = new Book();

        book.setId(rs.getInt("id"));
        book.setTitleId(rs.getInt("catalogId"));
        book.setIsbn(rs.getString("isbn"));
        book.setName(rs.getString("name"));
        book.setAuthor(rs.getString("author"));
        book.setPublisher(rs.getString("publisher"));
        book.setCategory(rs.getString("category"));
        book.setPrice(rs.getBigDecimal("price"));
        book.setStatus(rs.getInt("status"));
        book.setAvailableCopies(book.getStatus()==0 ? 1 : 0);
        book.getCopyIds().add(book.getId());

        return book;
    }

    static List<Book> aggregateCopies(List<Book> copies) {
        java.util.Map<Integer,Book> titles = new java.util.LinkedHashMap<>();
        for (Book copy : copies) {
            Book title = titles.get(copy.getTitleId());
            if (title == null) {
                title = new Book(copy.getTitleId(),copy.getIsbn(),copy.getName(),copy.getAuthor(),copy.getPublisher(),copy.getStatus());
                title.setPrice(copy.getPrice()); title.setTitleId(copy.getTitleId()); title.setTotalCopies(0);
                title.setCategory(copy.getCategory());
                titles.put(title.getId(),title);
            }
            title.setTotalCopies(title.getTotalCopies()+1);
            title.setAvailableCopies(title.getAvailableCopies()+(copy.getStatus()==0?1:0));
            title.getCopyIds().add(copy.getId());
        }
        for (Book title : titles.values()) title.setStatus(title.getAvailableCopies()>0 ? 0 : 1);
        return new ArrayList<>(titles.values());
    }
}
