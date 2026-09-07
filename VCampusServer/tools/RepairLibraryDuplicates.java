import util.DBUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** One-off repair for duplicate sample imports. Defaults to read-only preview. */
public class RepairLibraryDuplicates {
    private static final List<String> TABLES = List.of(
            "tblBorrowRecord", "tblReservation", "tblBookReview", "tblLossRecord", "tblFineRecord");

    public static void main(String[] args) throws Exception {
        boolean apply = args.length == 2 && "--apply".equals(args[0]);
        if (args.length != 0 && !apply) {
            throw new IllegalArgumentException("Usage: RepairLibraryDuplicates [--apply backup.sql]");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try {
                Map<String, List<Integer>> duplicates = new LinkedHashMap<>();
                List<String> restore = new ArrayList<>();
                restore.add("-- Restore removed exact duplicates; execute only if rollback is needed.");
                restore.add("START TRANSACTION;");
                for (String table : TABLES) {
                    // Do not remove records if another table references their IDs.
                    try (ResultSet references = connection.getMetaData().getExportedKeys(connection.getCatalog(), null, table)) {
                        if (references.next()) throw new SQLException("Referenced table requires manual review: " + table);
                    }
                    List<String> columns = new ArrayList<>();
                    List<Integer> types = new ArrayList<>();
                    try (Statement statement = connection.createStatement();
                         ResultSet rows = statement.executeQuery("SELECT * FROM `" + table + "` WHERE 1=0")) {
                        ResultSetMetaData meta = rows.getMetaData();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            columns.add(meta.getColumnName(i));
                            types.add(meta.getColumnType(i));
                        }
                    }
                    List<String> comparisons = new ArrayList<>();
                    for (int i = 0; i < columns.size(); i++) {
                        String column = columns.get(i);
                        if ("id".equalsIgnoreCase(column)) continue;
                        boolean text = Set.of(Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
                                Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR).contains(types.get(i));
                        String prefix = text ? "BINARY " : "";
                        comparisons.add(prefix + "a.`" + column + "` <=> " + prefix + "b.`" + column + "`");
                    }
                    String sql = "SELECT a.* FROM `" + table + "` a WHERE EXISTS (SELECT 1 FROM `" + table
                            + "` b WHERE b.id<a.id AND " + String.join(" AND ", comparisons) + ") ORDER BY a.id";
                    List<Integer> ids = new ArrayList<>();
                    try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                        while (rows.next()) {
                            ids.add(rows.getInt("id"));
                            List<String> values = new ArrayList<>();
                            for (int i = 1; i <= columns.size(); i++) {
                                String value = rows.getString(i);
                                values.add(value == null ? "NULL" : "CONVERT(X'"
                                        + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)");
                            }
                            restore.add("INSERT INTO `" + table + "` (" + columns.stream()
                                    .map(c -> "`" + c + "`").collect(Collectors.joining(","))
                                    + ") VALUES (" + String.join(",", values) + ");");
                        }
                    }
                    duplicates.put(table, ids);
                    System.out.println(table + " redundant IDs: " + ids);
                }
                restore.add("COMMIT;");
                if (apply) {
                    // Refuse to overwrite an earlier backup. Backup must succeed before any DELETE.
                    Files.write(Path.of(args[1]), restore, StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE_NEW);
                    for (var entry : duplicates.entrySet()) {
                        try (PreparedStatement delete = connection.prepareStatement(
                                "DELETE FROM `" + entry.getKey() + "` WHERE id=?")) {
                            for (int id : entry.getValue()) {
                                delete.setInt(1, id);
                                if (delete.executeUpdate() != 1) throw new SQLException("Record changed during repair");
                            }
                        }
                    }
                    connection.commit();
                    System.out.println("Repair committed. Restore SQL: " + Path.of(args[1]).toAbsolutePath());
                } else {
                    connection.rollback();
                    System.out.println("Preview only; no records changed.");
                }
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }
}
