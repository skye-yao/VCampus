package dao;

import entity.Book;
import exception.BusinessException;
import java.sql.*;
import java.util.*;
import java.time.LocalDateTime;

/** 书目管理与按册分配。tblBook 保留为实体册表，旧借阅外键和电子书编号不变。 */
public final class BookCatalogDAO {
    private final BookDAO.ConnectionFactory connections;
    public BookCatalogDAO() { this(util.DBUtil::getConnection); }
    BookCatalogDAO(BookDAO.ConnectionFactory connections) { this.connections = connections; }
    private interface Work<T> { T run(Connection conn) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try { T result = work.run(conn); conn.commit(); return result; }
            catch (SQLException | RuntimeException error) { conn.rollback(); throw error; }
        }
    }
    private static Book map(ResultSet row) throws SQLException {
        Book book = new Book(); book.setId(row.getInt("id"));
        book.setIsbn(row.getString("isbn")); book.setName(row.getString("name"));
        book.setAuthor(row.getString("author")); book.setPublisher(row.getString("publisher"));
        book.setPrice(row.getBigDecimal("price")); return book;
    }
    private static Book lockCatalog(Connection conn, int id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tblBookCatalog WHERE id=? FOR UPDATE")) {
            stmt.setInt(1,id);
            try (ResultSet row = stmt.executeQuery()) {
                if (!row.next()) throw new BusinessException("书目不存在，请刷新");
                return map(row);
            }
        }
    }
    private static void lockCopy(Connection conn, int id) throws SQLException {
        try (PreparedStatement stmt=conn.prepareStatement("SELECT id FROM tblBook WHERE id=? FOR UPDATE")) {
            stmt.setInt(1,id);
            try (ResultSet row=stmt.executeQuery()) { if(!row.next())throw new BusinessException("馆藏册不存在"); }
        }
    }
    private static List<Integer> copyIds(Connection conn, int id) throws SQLException {
        List<Integer> ids=new ArrayList<>();
        try (PreparedStatement stmt=conn.prepareStatement("SELECT id FROM tblBook WHERE catalogId=? ORDER BY id")) {
            stmt.setInt(1,id); try (ResultSet rows=stmt.executeQuery()) { while(rows.next())ids.add(rows.getInt(1)); }
        }
        return ids;
    }
    public List<Book> search(String keyword) throws SQLException {
        return read("name LIKE ? OR author LIKE ? OR isbn LIKE ?", "%"+(keyword==null?"":keyword)+"%", true);
    }
    public Book findById(int id) throws SQLException {
        List<Book> books=read("id=?",id,false); return books.isEmpty()?null:books.get(0);
    }
    public Book findByIsbn(String isbn) throws SQLException {
        List<Book> books=read("isbn=?",isbn,false); return books.isEmpty()?null:books.get(0);
    }
    private List<Book> read(String condition,Object value,boolean search) throws SQLException {
        return transaction(conn -> {
            Map<Integer,Book> books=new LinkedHashMap<>();
            try (PreparedStatement stmt=conn.prepareStatement("SELECT * FROM tblBookCatalog WHERE "+condition+" ORDER BY id")) {
                stmt.setObject(1,value); if(search){stmt.setObject(2,value);stmt.setObject(3,value);}
                try(ResultSet rows=stmt.executeQuery()) { while(rows.next()) {Book book=map(rows);book.setCatalogId(book.getId());book.setStatus(3);books.put(book.getId(),book);} }
            }
            if(books.isEmpty())return new ArrayList<>();
            // 使用与实际预约相同的有效状态计算可借数，避免状态字段陈旧导致超借。
            try (Statement stmt=conn.createStatement(); ResultSet rows=stmt.executeQuery(
                    BookDAO.BOOK_SELECT.replace("SELECT id,", "SELECT catalogId,id,")+"ORDER BY id")) {
                while(rows.next()) {
                    Book book=books.get(rows.getInt("catalogId"));if(book==null)continue;
                    int status=rows.getInt("status");
                    book.getCopyIds().add(rows.getInt("id")); book.setTotalCopies(book.getTotalCopies()+1);
                    if(status==0)book.setAvailableCopies(book.getAvailableCopies()+1);
                    book.setStatus(Math.min(book.getStatus(),status));
                }
            }
            return new ArrayList<>(books.values());
        });
    }
    public List<Book> copies(int catalogId) throws SQLException {
        try(Connection conn=connections.open(); PreparedStatement stmt=conn.prepareStatement(BookDAO.BOOK_SELECT+"WHERE catalogId=? ORDER BY id")) {
            stmt.setInt(1,catalogId);List<Book> result=new ArrayList<>();
            try(ResultSet rows=stmt.executeQuery()) {while(rows.next()){Book book=map(rows);book.setCatalogId(catalogId);book.setStatus(rows.getInt("status"));result.add(book);}}
            return result;
        }
    }
    private static void validateCount(int count) {
        if(count<1||count>1000)throw new IllegalArgumentException("每次入库册数须为 1 到 1000 的整数");
    }
    private static int insertCopy(Connection conn,Book book,Integer catalogId) throws SQLException {
        try(PreparedStatement stmt=conn.prepareStatement(
                "INSERT INTO tblBook(isbn,name,author,publisher,price,status,catalogId) VALUES(?,?,?,?,?,0,?)",Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1,book.getIsbn());stmt.setString(2,book.getName());stmt.setString(3,book.getAuthor());
            stmt.setString(4,book.getPublisher());stmt.setBigDecimal(5,book.getPrice());stmt.setObject(6,catalogId);stmt.executeUpdate();
            try(ResultSet keys=stmt.getGeneratedKeys()){if(!keys.next())throw new SQLException("未生成册号");return keys.getInt(1);}
        }
    }
    public boolean insert(Book book) throws SQLException {
        validateCount(book.getInitialCopies());
        return transaction(conn -> {
            int id=insertCopy(conn,book,null);
            try(PreparedStatement stmt=conn.prepareStatement("INSERT INTO tblBookCatalog(id,isbn,name,author,publisher,price) VALUES(?,?,?,?,?,?)")) {
                stmt.setInt(1,id);stmt.setString(2,book.getIsbn());stmt.setString(3,book.getName());stmt.setString(4,book.getAuthor());
                stmt.setString(5,book.getPublisher());stmt.setBigDecimal(6,book.getPrice());stmt.executeUpdate();
            }
            LibraryCirculationDAO.update(conn,"UPDATE tblBook SET catalogId=? WHERE id=?",id,id);
            for(int i=1;i<book.getInitialCopies();i++)insertCopy(conn,book,id);
            book.setId(id);return true;
        });
    }
    public void addCopies(int catalogId,int count) throws SQLException {
        validateCount(count);
        transaction(conn -> {Book book=lockCatalog(conn,catalogId);for(int i=0;i<count;i++)insertCopy(conn,book,catalogId);return null;});
    }
    public boolean update(Book book) throws SQLException {
        return transaction(conn -> {
            lockCatalog(conn,book.getId());
            for(int id:copyIds(conn,book.getId()))lockCopy(conn,id);
            // 编辑书目不会改变任何实体册的借阅、预约或挂失状态。
            for(String table:List.of("tblBookCatalog","tblBook")) {
                try(PreparedStatement stmt=conn.prepareStatement("UPDATE "+table+" SET isbn=?,name=?,author=?,publisher=?,price=? WHERE "+(table.equals("tblBook")?"catalogId":"id")+"=?")) {
                    stmt.setString(1,book.getIsbn());stmt.setString(2,book.getName());stmt.setString(3,book.getAuthor());
                    stmt.setString(4,book.getPublisher());stmt.setBigDecimal(5,book.getPrice());stmt.setInt(6,book.getId());stmt.executeUpdate();
                }
            }
            return true;
        });
    }
    public boolean reserve(String userId,int catalogId) throws SQLException {
        return transaction(conn -> {
            lockCatalog(conn,catalogId);
            for(int id:copyIds(conn,catalogId)) {
                lockCopy(conn,id);
                LibraryCirculationDAO.expireBookReservations(conn,id,LocalDateTime.now());
                try(PreparedStatement stmt=conn.prepareStatement(BookDAO.BOOK_SELECT+"WHERE id=? FOR UPDATE")) {
                    stmt.setInt(1,id);
                    try(ResultSet row=stmt.executeQuery()){if(!row.next()||row.getInt("status")!=0)continue;}
                }
                LibraryCirculationDAO.update(conn,"INSERT INTO tblReservation(userid,bookid,reserveTime,status) VALUES(?,?,CURRENT_TIMESTAMP,0)",userId,id);
                LibraryCirculationDAO.update(conn,"UPDATE tblBook SET status=2 WHERE id=?",id);
                return true;
            }
            return false;
        });
    }
    public boolean delete(int catalogId) throws SQLException {
        return transaction(conn -> {
            lockCatalog(conn,catalogId);
            for(int id:copyIds(conn,catalogId)) {
                lockCopy(conn,id);
                for(String table:List.of("tblBorrowRecord","tblReservation","tblLossRecord","tblBookReview")) {
                    try(PreparedStatement stmt=conn.prepareStatement("SELECT id FROM "+table+" WHERE bookid=? LIMIT 1")) {
                        stmt.setInt(1,id);try(ResultSet row=stmt.executeQuery()){if(row.next())throw new BusinessException("该书目存在借阅、预约、挂失或评价记录，不能下架删除");}
                    }
                }
            }
            LibraryCirculationDAO.update(conn,"DELETE FROM tblBook WHERE catalogId=?",catalogId);
            return LibraryCirculationDAO.update(conn,"DELETE FROM tblBookCatalog WHERE id=?",catalogId)>0;
        });
    }
}
