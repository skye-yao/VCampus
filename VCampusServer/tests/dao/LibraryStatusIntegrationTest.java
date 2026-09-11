package dao;

import entity.Book;
import util.DBUtil;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.UUID;

/** Uses a disposable schema; never writes to the application's tables. */
public class LibraryStatusIntegrationTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void sql(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) { stmt.execute(sql); }
    }

    private static int value(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rows = stmt.executeQuery(sql)) {
            rows.next(); return rows.getInt(1);
        }
    }

    private static Book book(int status) { return new Book(1, "test-isbn", "Test", "Author", "Publisher", status); }

    /** Run the shipped fixture in the disposable schema, ignoring only its USE directive. */
    private static void importFixture(Connection conn) throws Exception {
        String delimiter = ";";
        StringBuilder statement = new StringBuilder();
        boolean recoverySection = false;
        for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(
                "VCampusServer/src/resources/sample_library_data.sql"))) {
            String trimmed = line.trim();
            if (trimmed.equals("-- BEGIN LIBRARY RECOVERY TEST DATA")) recoverySection = true;
            if (!recoverySection) continue;
            if (trimmed.isEmpty() || trimmed.startsWith("--") || trimmed.startsWith("USE ")) continue;
            if (trimmed.startsWith("DELIMITER ")) { delimiter = trimmed.substring(10).trim(); continue; }
            statement.append(line).append('\n');
            if (trimmed.endsWith(delimiter)) {
                String command = statement.toString().trim();
                sql(conn, command.substring(0, command.length() - delimiter.length()));
                statement.setLength(0);
            }
        }
    }

    private static String snapshot(Connection conn) throws SQLException {
        StringBuilder result = new StringBuilder();
        for (String table : new String[]{"tblBorrowRecord", "tblLossRecord", "tblReservation"}) {
            try (Statement stmt = conn.createStatement(); ResultSet rows = stmt.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                result.append(table);
                while (rows.next()) for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) result.append('|').append(rows.getString(i));
            }
        }
        return result.toString();
    }

    private static void seed(Connection conn) throws SQLException {
        sql(conn, "DELETE FROM tblLossRecord");
        sql(conn, "DELETE FROM tblBorrowRecord");
        sql(conn, "DELETE FROM tblReservation");
        sql(conn, "DELETE FROM tblBook");
        sql(conn, "INSERT INTO tblBook VALUES(1,'test-isbn','Test','Author','Publisher',3)");
        sql(conn, "INSERT INTO tblBorrowRecord VALUES(1,'reader',1,NOW(),NULL,NOW(),2),(2,'reader',1,NOW(),NOW(),NOW(),1)");
        sql(conn, "INSERT INTO tblLossRecord VALUES(1,'reader',1,NOW(),0),(2,'reader',1,NOW(),1)");
        sql(conn, "INSERT INTO tblReservation VALUES(1,'reader',1,NOW(),0)");
        conn.commit();
    }

    public static void main(String[] args) throws Exception {
        String schema = "vcampus_status_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection conn = DBUtil.getConnection()) {
            sql(conn, "CREATE DATABASE " + schema);
            try {
                conn.setCatalog(schema);
                sql(conn, "CREATE TABLE tblBook(id INT PRIMARY KEY AUTO_INCREMENT,isbn VARCHAR(20) UNIQUE,name VARCHAR(100),author VARCHAR(100),publisher VARCHAR(100),status INT) ENGINE=InnoDB");
                sql(conn, "CREATE TABLE tblBorrowRecord(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,borrowTime DATETIME,returnTime DATETIME,dueTime DATETIME,status INT) ENGINE=InnoDB");
                sql(conn, "CREATE TABLE tblLossRecord(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,lossTime DATETIME,status INT) ENGINE=InnoDB");
                sql(conn, "CREATE TABLE tblReservation(id INT PRIMARY KEY AUTO_INCREMENT,userid VARCHAR(32),bookid INT,reserveTime DATETIME,status INT) ENGINE=InnoDB");
                // Keep the isolated connection open while exercising production commit/rollback paths.
                Connection borrowed = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("close")) return null;
                            try { return method.invoke(conn, arguments); }
                            catch (InvocationTargetException e) { throw e.getCause(); }
                        });
                BookDAO dao = new BookDAO(() -> borrowed);
                conn.setAutoCommit(false);
                seed(conn);
                check(dao.update(book(0)), "lost -> available");
                check(value(conn, "SELECT status FROM tblBook WHERE id=1") == 0, "available book");
                check(value(conn, "SELECT COUNT(*) FROM tblLossRecord WHERE status=0") == 0, "public notices cleared");
                check(new LossRecordDAO().findPublicNotices(conn).isEmpty(), "public notice query cleared");
                check(value(conn, "SELECT COUNT(*) FROM tblLossRecord") == 2, "loss history retained");
                check(value(conn, "SELECT COUNT(*) FROM tblBorrowRecord WHERE status=1 AND returnTime IS NOT NULL") == 2, "returned with timestamp");
                check(value(conn, "SELECT status FROM tblReservation WHERE id=1") == 1, "reservation cancelled");
                check(!dao.changeLoss("reader",1,true), "stale report cannot undo return");
                check(!dao.changeLoss("reader",1,false), "stale cancellation cannot undo return");

                // Every admin transition is checked, including transitions with real owners.
                for (int from = 0; from < 4; from++) {
                    for (int to = 0; to < 4; to++) {
                        seed(conn);
                        if (from != 3) sql(conn, "DELETE FROM tblLossRecord");
                        if (from == 0 || from == 2) sql(conn, "DELETE FROM tblBorrowRecord");
                        if (from == 0 || from == 1) sql(conn, "DELETE FROM tblReservation");
                        sql(conn, "UPDATE tblBook SET status=" + from + " WHERE id=1");
                        conn.commit();
                        String before = snapshot(conn);
                        Book edit = book(to); edit.setName("Edited");
                        if (from == to || (from == 3 && to == 0)) {
                            check(dao.update(edit), "allowed " + from + " -> " + to);
                            if (from == to) check(before.equals(snapshot(conn)), "metadata edit preserves all records");
                        } else {
                            try { dao.update(edit); throw new AssertionError("illegal transition accepted: " + from + " -> " + to); }
                            catch (IllegalArgumentException expected) { }
                            check(before.equals(snapshot(conn)), "illegal transition preserves records");
                            check(value(conn, "SELECT COUNT(*) FROM tblBook WHERE name='Test' AND status=" + from) == 1, "illegal transition preserves book");
                        }
                    }
                }
                seed(conn);
                check(dao.changeLoss("reader",1,false), "reader may cancel own loss");
                check(value(conn, "SELECT status FROM tblBorrowRecord WHERE id=1") == 2, "overdue preserved");
                check(dao.changeLoss("reader",1,true), "reader reports loss");
                check(!dao.changeLoss("reader",1,true), "duplicate report rejected");
                check(!dao.changeLoss("other",1,false), "other reader cannot cancel loss");
                check(new LossRecordDAO().findPublicNotices(conn).size() == 1, "one active notice");
                seed(conn);
                sql(conn, "INSERT INTO tblBook VALUES(2,'duplicate','Other','Author','Publisher',0)");
                conn.commit();
                Book invalid = book(0); invalid.setIsbn("duplicate");
                try { dao.update(invalid); throw new AssertionError("expected duplicate ISBN failure"); }
                catch (SQLException expected) { /* entire transaction must roll back */ }
                check(value(conn, "SELECT status FROM tblBook WHERE id=1") == 3, "book rollback");
                check(value(conn, "SELECT status FROM tblLossRecord WHERE id=1") == 0, "loss rollback");
                check(value(conn, "SELECT status FROM tblBorrowRecord WHERE id=1") == 2, "borrow rollback");
                check(value(conn, "SELECT status FROM tblReservation WHERE id=1") == 0, "reservation rollback");
                check(!dao.update(book(9)), "invalid status rejected");
                seed(conn);
                BookDAO concurrentDao = new BookDAO(() -> {
                    Connection fresh = DBUtil.getConnection(); fresh.setCatalog(schema); return fresh;
                });
                var start = new java.util.concurrent.CountDownLatch(1);
                var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
                try {
                    var returned = pool.submit(() -> { start.await(); try { return concurrentDao.update(book(0)); } catch (IllegalArgumentException expected) { return false; } });
                    var cancelled = pool.submit(() -> { start.await(); return concurrentDao.changeLoss("reader",1,false); });
                    start.countDown();
                    boolean recovered = returned.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    cancelled.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    conn.commit();
                    check(value(conn, "SELECT status FROM tblBook WHERE id=1") == (recovered ? 0 : 1), "serialized recovery or rejection after cancellation");
                    check(new LossRecordDAO().findPublicNotices(conn).isEmpty(), "concurrent notices cleared");
                } finally { pool.shutdownNow(); }
                sql(conn, "CREATE TABLE tbl_user(uid VARCHAR(32) PRIMARY KEY,role INT)");
                sql(conn, "INSERT INTO tbl_user VALUES('213242789',2)"); conn.commit();
                importFixture(conn);
                importFixture(conn);
                check(value(conn, "SELECT COUNT(*) FROM tblBook WHERE name LIKE '[找回测试]%' ") == 10, "fixture repeat creates new batch");
                check(value(conn, "SELECT COUNT(*) FROM tblLossRecord WHERE status=0") == 4, "two lost books per batch");
                check(value(conn, "SELECT COUNT(*) FROM tblBook WHERE id=1") == 1, "fixture keeps existing data");
                sql(conn, "DELETE FROM tbl_user"); conn.commit();
                try { importFixture(conn); throw new AssertionError("fixture requires existing student"); }
                catch (SQLException expected) { }
                check(value(conn, "SELECT COUNT(*) FROM tblBook") == 11, "invalid fixture leaves data intact");
                System.out.println("PASS: all 16 admin state combinations, notices, history, ownership, concurrency, rollback and repeatable SQL fixtures");
            } finally {
                if (!conn.getAutoCommit()) conn.rollback();
                conn.setAutoCommit(true);
                sql(conn, "DROP DATABASE " + schema);
            }
        }
    }
}
