import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 默认只预览；--apply <备份路径> 才执行完全重复记录清理。 */
public class LibraryDataRepair {
    static final String[] TABLES = {"tblBook", "tblBorrowRecord", "tblReservation", "tblBookReview", "tblLossRecord", "tblFineRecord"};
    static String duplicateSql(Connection c, String table) throws Exception {
        List<String> predicates = new ArrayList<>();
        try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM " + table + " LIMIT 0")) {
            ResultSetMetaData m=r.getMetaData();
            for (int i=1;i<=m.getColumnCount();i++) {
                String name=m.getColumnName(i);
                if (!name.equalsIgnoreCase("id")) predicates.add("BINARY a.`"+name+"` <=> BINARY b.`"+name+"`");
            }
        }
        return " FROM "+table+" a JOIN "+table+" b ON a.id>b.id AND "+String.join(" AND ",predicates);
    }
    static String literal(Object value) {
        if (value==null) return "NULL";
        if (value instanceof Number) return value.toString();
        return "CONVERT(X'"+HexFormat.of().formatHex(value.toString().getBytes(StandardCharsets.UTF_8))+"' USING utf8mb4)";
    }
    public static void main(String[] args) throws Exception {
        boolean apply=args.length==2 && args[0].equals("--apply");
        try (Connection c=util.DBUtil.getConnection()) {
            // 记录表若被其他模块引用，拒绝自动删除，避免破坏外部关系。
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery(
                "SELECT TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE REFERENCED_TABLE_SCHEMA=DATABASE() " +
                "AND REFERENCED_TABLE_NAME IN ('tblBorrowRecord','tblReservation','tblBookReview','tblLossRecord','tblFineRecord')")) {
                if(r.next()) throw new IllegalStateException("Referenced record table; manual migration required");
            }
            for(String table:TABLES) {
                if(table.equals("tblBook")) continue;
                try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT COUNT(DISTINCT a.id)"+duplicateSql(c,table))) {
                    r.next(); System.out.println(table+" duplicate rows="+r.getInt(1));
                }
            }
            if(!apply) return;
            c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            c.setAutoCommit(false);
            try {
                StringBuilder backup=new StringBuilder("-- Library backup before exact-duplicate repair. Restore into the same database.\nSTART TRANSACTION;\n");
                for(String table:TABLES) {
                    try(Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT * FROM "+table+" ORDER BY id FOR UPDATE")) {
                        ResultSetMetaData m=r.getMetaData();
                        List<String> cols=new ArrayList<>(), updates=new ArrayList<>();
                        for(int i=1;i<=m.getColumnCount();i++) {
                            String col="`"+m.getColumnName(i)+"`"; cols.add(col); updates.add(col+"=VALUES("+col+")");
                        }
                        while(r.next()) {
                            List<String> values=new ArrayList<>();
                            for(int i=1;i<=m.getColumnCount();i++) values.add(literal(r.getObject(i)));
                            backup.append("INSERT INTO ").append(table).append(" (").append(String.join(",",cols))
                                .append(") VALUES (").append(String.join(",",values)).append(") ON DUPLICATE KEY UPDATE ")
                                .append(String.join(",",updates)).append(";\n");
                        }
                    }
                }
                backup.append("COMMIT;\n");
                Path target=Path.of(args[1]);
                Files.writeString(target,backup.toString(),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
                System.out.println("Backup saved: "+target.toAbsolutePath());
                for(String table:TABLES) {
                    if(table.equals("tblBook")) continue;
                    try(Statement s=c.createStatement()) {
                        System.out.println(table+" removed="+s.executeUpdate("DELETE a"+duplicateSql(c,table)));
                    }
                }
                try(Statement s=c.createStatement()) {
                    System.out.println("Book statuses reconciled="+s.executeUpdate("UPDATE tblBook b SET status=CASE " +
                        "WHEN EXISTS(SELECT 1 FROM tblLossRecord l WHERE l.bookid=b.id AND l.status=0) THEN 3 " +
                        "WHEN EXISTS(SELECT 1 FROM tblBorrowRecord r WHERE r.bookid=b.id AND r.status IN(0,2) AND r.returnTime IS NULL) THEN 1 " +
                        "WHEN EXISTS(SELECT 1 FROM tblReservation r WHERE r.bookid=b.id AND r.status=0) THEN 2 ELSE b.status END"));
                }
                c.commit();
                System.out.println("COMMITTED; existing book IDs and AUTO_INCREMENT unchanged");
            } catch(Exception e) { c.rollback(); throw e; }
        }
    }
}
