package dao;

import java.sql.*;
import util.LocalTimeConnection;

/** 仅升级现有图书馆表，不创建数据库或覆盖业务数据。 */
public final class LibrarySchema {
    private static volatile boolean ready;
    public static void ensure() throws SQLException {
        if (!ready) try (Connection conn = util.LocalTimeConnection.getConnection()) { ensure(conn); }
    }
    public static synchronized void ensure(Connection conn) throws SQLException {
        if (ready) return;
        add(conn, "tblBook", "price", "DECIMAL(10,2) NULL");
        ensureCategories(conn);
        ensureCopies(conn);
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
    private static void add(Connection conn, String table, String column, String definition) throws SQLException {
        try (ResultSet columns = conn.getMetaData().getColumns(conn.getCatalog(), null, table, column)) {
            if (columns.next()) return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    /** 仅为升级前的书目分类一次，管理员后续选择“其他”也不会被重启覆盖。 */
    static void ensureCategories(Connection conn) throws SQLException {
        add(conn, "tblBook", "category", "VARCHAR(32) NOT NULL DEFAULT '其他'");
        add(conn, "tblBook", "categoryInitialized", "BOOLEAN NOT NULL DEFAULT FALSE");
        initializeCategories(conn);
    }

    static void initializeCategories(Connection conn) throws SQLException {
        // 只匹配项目样例中的确切 ISBN 和书名，不猜测自建书目或测试数据的内容。
        String[][] computerBooks = {
                {"978-7-302-12345-6", "数据库系统概论"},
                {"978-7-111-67890-1", "深入理解计算机系统"},
                {"978-7-121-34567-8", "算法导论"},
                {"978-7-302-98765-4", "软件工程"},
                {"978-7-111-54321-0", "计算机网络：自顶向下方法"},
                {"978-7-302-11111-1", "操作系统概念"},
                {"978-7-121-22222-2", "Python编程从入门到实践"},
                {"978-7-111-33333-3", "数据结构与算法分析"}
        };
        StringBuilder sql = new StringBuilder("UPDATE tblBook SET category=CASE");
        for (String[] ignored : computerBooks) sql.append(" WHEN isbn=? AND name=? THEN '计算机'");
        sql.append(" ELSE '其他' END,categoryInitialized=TRUE WHERE categoryInitialized=FALSE");
        // 分类与迁移标记在同一条语句内落库，失败时重试不会留下半迁移状态。
        try (PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            int index = 1;
            for (String[] book : computerBooks) {
                stmt.setString(index++, book[0]);
                stmt.setString(index++, book[1]);
            }
            stmt.executeUpdate();
        }
    }

    /** 原记录作为首册；一次性补齐，重启不会重新补入已删除的馆藏。 */
    static void ensureCopies(Connection conn) throws SQLException {
        add(conn, "tblBook", "titleId", "INT NULL");
        add(conn, "tblBook", "copyNumber", "INT NOT NULL DEFAULT 1");
        add(conn, "tblBook", "copiesInitialized", "BOOLEAN NOT NULL DEFAULT FALSE");
        boolean oldIndex = false, copyIndex = false;
        try (ResultSet indexes = conn.getMetaData().getIndexInfo(conn.getCatalog(), null, "tblBook", true, false)) {
            while (indexes.next()) {
                oldIndex |= "uk_isbn".equalsIgnoreCase(indexes.getString("INDEX_NAME"));
                copyIndex |= "uk_isbn_copy".equalsIgnoreCase(indexes.getString("INDEX_NAME"));
            }
        }
        try (Statement stmt = conn.createStatement()) {
            if (!copyIndex) stmt.execute("ALTER TABLE tblBook ADD UNIQUE KEY uk_isbn_copy(isbn,copyNumber)");
            if (oldIndex) stmt.execute("ALTER TABLE tblBook DROP INDEX uk_isbn");
        }
        java.util.List<Integer> titles = new java.util.ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rows = stmt.executeQuery(
                "SELECT id FROM tblBook WHERE titleId IS NULL AND copiesInitialized=FALSE ORDER BY id")) {
            while (rows.next()) titles.add(rows.getInt(1));
        }
        boolean autoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            for (int id : titles) initializeCopies(conn, id);
            conn.commit();
        } catch (SQLException | RuntimeException e) { conn.rollback(); throw e; }
        finally { conn.setAutoCommit(autoCommit); }
    }

    static void initializeCopies(Connection conn, int titleId) throws SQLException {
        String isbn, name, author, publisher, category;
        java.math.BigDecimal price;
        try (PreparedStatement lock = conn.prepareStatement("SELECT * FROM tblBook WHERE id=? FOR UPDATE")) {
            lock.setInt(1, titleId);
            try (ResultSet rows = lock.executeQuery()) {
                if (!rows.next() || rows.getBoolean("copiesInitialized")) return;
                isbn=rows.getString("isbn"); name=rows.getString("name");
                author=rows.getString("author"); publisher=rows.getString("publisher"); price=rows.getBigDecimal("price");
                category=rows.getString("category");
            }
        }
        for (int number = 2; number <= 10; number++) {
            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO tblBook(isbn,name,author,publisher,price,status,titleId,copyNumber,copiesInitialized,category,categoryInitialized) " +
                    "VALUES(?,?,?,?,?,0,?,?,TRUE,?,TRUE)")) {
                insert.setString(1,isbn); insert.setString(2,name); insert.setString(3,author);
                insert.setString(4,publisher); insert.setBigDecimal(5,price);
                insert.setInt(6,titleId); insert.setInt(7,number); insert.setString(8,category); insert.executeUpdate();
            }
        }
        try (PreparedStatement stmt = conn.prepareStatement("UPDATE tblBook SET copiesInitialized=TRUE WHERE id=?")) {
            stmt.setInt(1, titleId); stmt.executeUpdate();
        }
    }
}
