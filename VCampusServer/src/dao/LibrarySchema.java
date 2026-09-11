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
}
