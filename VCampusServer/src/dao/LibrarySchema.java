package dao;

import java.sql.*;

/** 仅升级现有图书馆表，不创建数据库或覆盖业务数据。 */
public final class LibrarySchema {
    private static volatile boolean ready;
    public static void ensure() throws SQLException {
        if (!ready) try (Connection conn = util.DBUtil.getConnection()) { ensure(conn); }
    }
    public static synchronized void ensure(Connection conn) throws SQLException {
        if (ready) return;
        add(conn, "tblBook", "price", "DECIMAL(10,2) NULL");
        add(conn, "tblBook", "catalogId", "INT NULL");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS tblBookCatalog (id INT PRIMARY KEY,isbn VARCHAR(20) NOT NULL UNIQUE," +
                    "name VARCHAR(100) NOT NULL,author VARCHAR(100) NOT NULL,publisher VARCHAR(100),price DECIMAL(10,2)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
        migrateCatalogs(conn);
        // ISBN 唯一性属于书目，实体册可以共享 ISBN。保留所有旧册号及外键。
        java.util.Map<String,java.util.List<String>> uniqueIndexes = new java.util.HashMap<>();
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, "tblBook", true, false)) {
            while (indexes.next()) {
                String name = indexes.getString("INDEX_NAME"), column = indexes.getString("COLUMN_NAME");
                if (name != null && column != null) uniqueIndexes.computeIfAbsent(name, key -> new java.util.ArrayList<>()).add(column);
            }
        }
        for (var index : uniqueIndexes.entrySet()) {
            if (index.getValue().size() == 1 && "isbn".equalsIgnoreCase(index.getValue().get(0))) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ALTER TABLE tblBook DROP INDEX `" + index.getKey().replace("`", "``") + "`");
                }
            }
        }
        add(conn, "tblBorrowRecord", "bookPrice", "DECIMAL(10,2) NULL");
        add(conn, "tblBorrowRecord", "feeStopTime", "DATETIME NULL");
        add(conn, "tblBorrowRecord", "settledTime", "DATETIME NULL");
        add(conn, "tblFineRecord", "borrowId", "INT NULL");
        add(conn, "tblFineRecord", "overdueAmount", "DECIMAL(10,2) NOT NULL DEFAULT 0");
        add(conn, "tblFineRecord", "lossAmount", "DECIMAL(10,2) NOT NULL DEFAULT 0");
        add(conn, "tblFineRecord", "paidAmount", "DECIMAL(10,2) NOT NULL DEFAULT 0");
        add(conn, "tblFineRecord", "refundedAmount", "DECIMAL(10,2) NOT NULL DEFAULT 0");
        add(conn, "tblFineRecord", "transactionNo", "VARCHAR(64) NULL");
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, "tblFineRecord", true, false)) {
            boolean found = false;
            while (indexes.next()) if ("uk_library_fine_borrow".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) found = true;
            if (!found) try (Statement stmt = conn.createStatement()) {
                stmt.execute("ALTER TABLE tblFineRecord ADD UNIQUE KEY uk_library_fine_borrow(borrowId)");
            }
        }
        // 兼容升级前的有效挂失：冻结在实际挂失时间，不能冻结在升级时刻。
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE tblBorrowRecord r JOIN (SELECT userid,bookid,MIN(lossTime) lossTime " +
                    "FROM tblLossRecord WHERE status=0 GROUP BY userid,bookid) l ON l.userid=r.userid AND l.bookid=r.bookid " +
                    "SET r.feeStopTime=l.lossTime WHERE r.feeStopTime IS NULL AND r.status IN(0,2) AND r.returnTime IS NULL");
        }
        ready = true;
    }

    /** 可重复执行；也兼容升级后手工导入的旧格式图书，只补充未关联书目。 */
    static void migrateCatalogs(Connection conn) throws SQLException {
        java.util.List<entity.Book> legacy = new java.util.ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rows=stmt.executeQuery("SELECT id,isbn,name,author,publisher,price FROM tblBook WHERE catalogId IS NULL ORDER BY id")) {
            while(rows.next()) {
                entity.Book book=new entity.Book(rows.getInt("id"),rows.getString("isbn"),rows.getString("name"),rows.getString("author"),rows.getString("publisher"),0);
                book.setPrice(rows.getBigDecimal("price"));legacy.add(book);
            }
        }
        for(entity.Book book:legacy) {
            try(PreparedStatement stmt=conn.prepareStatement("INSERT INTO tblBookCatalog(id,isbn,name,author,publisher,price) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id")) {
                stmt.setInt(1,book.getId());stmt.setString(2,book.getIsbn());stmt.setString(3,book.getName());stmt.setString(4,book.getAuthor());
                stmt.setString(5,book.getPublisher());stmt.setBigDecimal(6,book.getPrice());stmt.executeUpdate();
            }
            try(PreparedStatement stmt=conn.prepareStatement("UPDATE tblBook SET catalogId=(SELECT id FROM tblBookCatalog WHERE isbn=?) WHERE id=? AND catalogId IS NULL")) {
                stmt.setString(1,book.getIsbn());stmt.setInt(2,book.getId());stmt.executeUpdate();
            }
        }
    }
    private static void add(Connection conn, String table, String column, String definition) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            if (columns.next()) return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
