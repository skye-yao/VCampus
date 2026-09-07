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

/**
 * 图书数据访问对象
 *
 * 负责 tblBook 表的数据访问。
 */
public class BookDAO {
    public boolean cancelReservation(String userId, int reservationId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
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
    private static final String BOOK_SELECT = "SELECT id,isbn,name,author,publisher," + EFFECTIVE_STATUS + " AS status FROM tblBook ";

    /** 锁住图书行后再次检查，并在一个事务内完成预约和状态更新。 */
    public boolean reserveAvailableBook(String userId, int bookId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement lock = conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
                    lock.setInt(1, bookId);
                    try (ResultSet rows = lock.executeQuery()) {
                        if (!rows.next()) { conn.rollback(); return false; }
                    }
                }
                try (PreparedStatement query = conn.prepareStatement(BOOK_SELECT + "WHERE id=?")) {
                    query.setInt(1, bookId);
                    try (ResultSet rows = query.executeQuery()) {
                        if (!rows.next() || rows.getInt("status") != 0) { conn.rollback(); return false; }
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
            conn = DBUtil.getConnection();
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
                BOOK_SELECT + "WHERE isbn = ?";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();
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
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            String key = "%" + keyword + "%";

            stmt.setString(1, key);
            stmt.setString(2, key);
            stmt.setString(3, key);

            rs = stmt.executeQuery();

            while (rs.next()) {
                books.add(mapBook(rs));
            }

            return books;

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

        String sql =
                "INSERT INTO tblBook " +
                        "(isbn, name, author, publisher, status) " +
                        "VALUES (?, ?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement stmt = null;
        ResultSet rs = null;

        try {
            conn = DBUtil.getConnection();

            stmt = conn.prepareStatement(
                    sql,
                    Statement.RETURN_GENERATED_KEYS
            );

            stmt.setString(1, book.getIsbn());
            stmt.setString(2, book.getName());
            stmt.setString(3, book.getAuthor());
            stmt.setString(4, book.getPublisher());
            stmt.setInt(5, book.getStatus());

            int rows = stmt.executeUpdate();

            if (rows > 0) {

                rs = stmt.getGeneratedKeys();

                if (rs.next()) {
                    book.setId(rs.getInt(1));
                }

                return true;
            }

            return false;

        } finally {
            DBUtil.close(conn, stmt, rs);
        }
    }

    /**
     * 修改图书基本信息。
     *
     * @param book 图书
     * @return 是否修改成功
     */
    public boolean update(Book book) throws SQLException {

        String sql =
                "UPDATE tblBook " +
                        "SET isbn = ?, name = ?, author = ?, publisher = ? " +
                        "WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);

            stmt.setString(1, book.getIsbn());
            stmt.setString(2, book.getName());
            stmt.setString(3, book.getAuthor());
            stmt.setString(4, book.getPublisher());
            stmt.setInt(5, book.getId());

            return stmt.executeUpdate() > 0;

        } finally {
            DBUtil.close(conn, stmt, null);
        }
    }

    /**
     * 删除图书。
     *
     * @param id 图书编号
     * @return 是否删除成功
     */
    public boolean delete(Integer id) throws SQLException {

        String sql = "DELETE FROM tblBook WHERE id = ?";

        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            conn = DBUtil.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, id);

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
            conn = DBUtil.getConnection();
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
        book.setIsbn(rs.getString("isbn"));
        book.setName(rs.getString("name"));
        book.setAuthor(rs.getString("author"));
        book.setPublisher(rs.getString("publisher"));
        book.setStatus(rs.getInt("status"));

        return book;
    }
}
