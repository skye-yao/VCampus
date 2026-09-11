package database;

import util.DBUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CourseMigrationMySqlTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private CourseMigrationMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path seed = root.resolve("VCampusServer/src/resources/seed-course-test.sql");
        require(Files.isRegularFile(seed), "Missing deterministic seed: " + seed.getFileName());

        Properties properties = loadProperties(root.resolve("VCampusServer/src/resources/db.properties"));
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String testUrl = withTestAuthentication(url);
        ensureTestSchema(testUrl, properties);

        try (Connection connection = DriverManager.getConnection(
                testUrl,
                requiredProperty(properties, "db.username"),
                requiredProperty(properties, "db.password"))) {
            setUtc(connection);
            require(TEST_DATABASE.equals(currentDatabase(connection)),
                    "Connected schema changed after URL validation");
            resetTestSchema(connection);
            applyTblUser(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            applyScript(connection, seed);

            verifySeedCoverage(connection);
            verifyMetadataContracts(connection);
            verifyGeneratedConstraints(connection);
        }

        verifyDbUtilUtcConnections(testUrl);
        System.out.println("Course migration MySQL test passed in dedicated test schema.");
    }

    private static void verifySeedCoverage(Connection connection) throws SQLException {
        require(queryInt(connection,
                "SELECT COUNT(*) FROM tbl_user WHERE role = 2") >= 2,
                "seed requires at least two students");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM tbl_user WHERE role = 1") >= 2,
                "seed requires at least two teachers");
        require(queryInt(connection, "SELECT COUNT(*) FROM course") >= 2,
                "seed requires at least two courses");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT course_id FROM course_offering "
                        + "GROUP BY course_id HAVING COUNT(*) > 1) offerings") >= 1,
                "seed requires multiple offerings for one course");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM (SELECT course_offering_id FROM course_schedule_rule "
                        + "GROUP BY course_offering_id HAVING COUNT(*) > 1) meetings") >= 1,
                "seed requires a multi-meeting offering");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM schedule_plan WHERE status = 'PUBLISHED'") >= 1,
                "seed requires a published schedule plan");
        require(queryInt(connection, "SELECT COUNT(*) FROM course_offering_conflict") >= 1,
                "seed requires a conflict matrix row");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_selection_window "
                        + "WHERE selection_open_at < UTC_TIMESTAMP(6) AND drop_deadline > UTC_TIMESTAMP(6)") >= 1,
                "seed requires open selection and drop windows");
        require(queryInt(connection, "SELECT COUNT(*) FROM grade") >= 1,
                "seed requires grades");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM training_plan WHERE status = 'PUBLISHED'") >= 1,
                "seed requires a published training plan");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_notice WHERE status = 'PUBLISHED'") >= 1,
                "seed requires a published notice");
    }

    private static void verifyMetadataContracts(Connection connection) throws SQLException {
        assertColumn(connection, "course_offering_teacher", "uid", "varchar", 32L, null);
        assertColumn(connection, "enrollment", "uid", "varchar", 32L, null);
        assertColumn(connection, "student_academic_profile", "uid", "varchar", 32L, null);
        assertColumn(connection, "course_plan_item", "uid", "varchar", 32L, null);
        assertColumn(connection, "course_waitlist", "uid", "varchar", 32L, null);
        assertColumn(connection, "course_operation_log", "uid", "varchar", 32L, null);
        assertColumn(connection, "course_event_outbox", "uid", "varchar", 32L, null);

        assertIndexColumns(connection, "course_event_outbox",
                "idx_course_event_outbox_delivery", "uid", "acked_at", "event_id");
        assertIndexColumns(connection, "enrollment",
                "uk_enrollment_active_course", "uid", "academic_year", "semester", "active_course_id");

        assertDatetimePrecision(connection, "course_selection_window",
                "plan_open_at", "plan_close_at", "selection_open_at", "selection_close_at", "drop_deadline");
        assertDatetimePrecision(connection, "course_waitlist",
                "queue_time", "offered_at", "expires_at", "updated_at");
        assertDatetimePrecision(connection, "course_operation_log", "created_at", "completed_at");
        assertDatetimePrecision(connection, "course_event_outbox",
                "created_at", "last_sent_at", "acked_at");
    }

    private static void verifyGeneratedConstraints(Connection connection) throws SQLException {
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-alpha', 2)",
                "same-term same-course enrollment must be rejected");

        execute(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2003, 1001, 2027, 3, 'student-alpha', 2)");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM enrollment WHERE uid = 'student-alpha' AND status = 2") == 2,
                "cross-term enrollment must be accepted");

        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 1)",
                "enrollment status 1 must be rejected");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 4)",
                "enrollment status 4 must be rejected");

        execute(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status, drop_time) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 3, UTC_TIMESTAMP(6))");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status, drop_time) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 3, UTC_TIMESTAMP(6))",
                "student and offering enrollment key must be unique");

        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2003, 1002, 2027, 3, 'student-beta', 2)",
                "enrollment course and term must match its offering");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2003, 1001, 2027, 3, 'missing-user', 2)",
                "enrollment UID foreign key must be enforced");

        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_offering_teacher WHERE uid LIKE 'teacher-%'") == 2,
                "alphanumeric teacher UIDs must satisfy foreign keys");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM enrollment WHERE uid LIKE 'student-%'") >= 2,
                "alphanumeric student UIDs must satisfy foreign keys");

        expectSqlRejected(connection,
                "INSERT INTO course_plan_item (uid, offering_id, status) "
                        + "VALUES ('student-alpha', 2002, 'PLANNED')",
                "student and offering plan key must be unique");
        expectSqlRejected(connection,
                "INSERT INTO course_plan_item (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2003, 'UNKNOWN')",
                "plan status must be PLANNED or FULL");
        expectSqlRejected(connection,
                "INSERT INTO course_waitlist (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2002, 'WAITING')",
                "student and offering waitlist key must be unique");
        expectSqlRejected(connection,
                "INSERT INTO course_waitlist (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2003, 'UNKNOWN')",
                "waitlist status domain must be enforced");

        expectSqlRejected(connection,
                "INSERT INTO course_operation_log "
                        + "(uid, operation_id, action, request_digest, result_code, response_json) "
                        + "VALUES ('student-alpha', '00000000-0000-0000-0000-000000000001', "
                        + "'addToPlan', REPEAT('b', 64), 'OK', JSON_OBJECT())",
                "operation IDs must be unique per student");
        execute(connection,
                "INSERT INTO course_operation_log "
                        + "(uid, operation_id, action, request_digest, result_code, response_json) "
                        + "VALUES ('student-beta', '00000000-0000-0000-0000-000000000001', "
                        + "'addToPlan', REPEAT('b', 64), 'OK', JSON_OBJECT())");

        expectSqlRejected(connection,
                "INSERT INTO course_selection_window "
                        + "(academic_year, semester, schedule_plan_id, plan_open_at, plan_close_at, "
                        + "selection_open_at, selection_close_at, drop_deadline) VALUES "
                        + "(2099, 1, 999999, '2098-01-01', '2098-02-01', "
                        + "'2098-03-01', '2098-04-01', '2098-05-01')",
                "selection window must reference a schedule plan");

        execute(connection,
                "INSERT INTO course_event_outbox "
                        + "(uid, event_type, academic_year, semester, payload, created_at) VALUES "
                        + "('student-alpha', 'WAITLIST_OFFERED', 2026, 2, JSON_OBJECT(), "
                        + "'2026-09-11 12:34:56.123456')");
        require("2026-09-11 12:34:56.123456".equals(queryString(connection,
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f') "
                        + "FROM course_event_outbox ORDER BY event_id DESC LIMIT 1")),
                "outbox timestamps must retain microseconds");
    }

    private static void verifyDbUtilUtcConnections(String testUrl) throws Exception {
        Field urlField = DBUtil.class.getDeclaredField("url");
        urlField.setAccessible(true);
        urlField.set(null, testUrl);
        for (int i = 0; i < 2; i++) {
            try (Connection connection = DBUtil.getConnection()) {
                require(TEST_DATABASE.equals(currentDatabase(connection)),
                        "DBUtil must remain connected to the dedicated test schema");
                require("+00:00".equals(queryString(connection, "SELECT @@session.time_zone")),
                        "DBUtil connection must use UTC");
            }
        }
    }

    private static void assertColumn(Connection connection, String table, String column,
                                     String dataType, Long maxLength, Integer datetimePrecision)
            throws SQLException {
        String sql = "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, DATETIME_PRECISION "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                require(dataType.equalsIgnoreCase(result.getString(1)),
                        "Unexpected type for " + table + "." + column);
                if (maxLength != null) {
                    require(maxLength == result.getLong(2),
                            "Unexpected length for " + table + "." + column);
                }
                if (datetimePrecision != null) {
                    require(datetimePrecision == result.getInt(3),
                            "Unexpected datetime precision for " + table + "." + column);
                }
            }
        }
    }

    private static void assertDatetimePrecision(Connection connection, String table,
                                                String... columns) throws SQLException {
        for (String column : columns) {
            assertColumn(connection, table, column, "datetime", null, 6);
        }
    }

    private static void assertIndexColumns(Connection connection, String table,
                                           String index, String... expected) throws SQLException {
        String sql = "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? "
                + "ORDER BY SEQ_IN_INDEX";
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        require(actual.equals(List.of(expected)),
                "Unexpected columns for index " + table + "." + index + ": " + actual);
    }

    private static void applyTblUser(Connection connection, Path initSql) throws Exception {
        Matcher matcher = TBL_USER.matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "Authoritative tbl_user definition not found");
        execute(connection, matcher.group());
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "Missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            try {
                execute(connection, statements.get(i));
            } catch (SQLException failure) {
                throw new SQLException("Failed applying " + path.getFileName()
                        + " statement " + (i + 1) + ": " + readWarnings(connection)
                        + "; " + readForeignKeyDiagnostic(connection), failure);
            }
        }
    }

    private static String readWarnings(Connection connection) {
        List<String> warnings = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW WARNINGS")) {
            while (result.next()) {
                warnings.add(result.getInt("Code") + " " + result.getString("Message"));
            }
        } catch (SQLException ignored) {
            return "warning details unavailable";
        }
        return warnings.toString();
    }

    private static String readForeignKeyDiagnostic(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SHOW ENGINE INNODB STATUS")) {
            if (!result.next()) {
                return "InnoDB status unavailable";
            }
            String status = result.getString("Status");
            int start = status.indexOf("LATEST FOREIGN KEY ERROR");
            if (start < 0) {
                return "no InnoDB foreign-key detail";
            }
            int end = Math.min(status.length(), start + 1200);
            return status.substring(start, end).replaceAll("\\s+", " ");
        } catch (SQLException ignored) {
            return "InnoDB status unavailable";
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;

        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    current.append(' ');
                }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!single && !quotedIdentifier && c == '#') {
                lineComment = true;
                continue;
            }
            if (!single && !quotedIdentifier && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (!quotedIdentifier && c == '\'') {
                current.append(c);
                if (single && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    single = !single;
                }
                continue;
            }
            if (!single && c == '`') {
                quotedIdentifier = !quotedIdentifier;
                current.append(c);
                continue;
            }
            if (!single && !quotedIdentifier && c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES "
                             + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void ensureTestSchema(String testUrl, Properties properties) throws SQLException {
        int queryIndex = testUrl.indexOf('?');
        String base = queryIndex >= 0 ? testUrl.substring(0, queryIndex) : testUrl;
        String query = queryIndex >= 0 ? testUrl.substring(queryIndex) : "";
        int databaseSlash = base.lastIndexOf('/');
        require(databaseSlash > "jdbc:mysql://".length(), "Invalid MySQL JDBC URL");
        String serverUrl = base.substring(0, databaseSlash + 1) + query;

        try (Connection connection = DriverManager.getConnection(
                serverUrl,
                requiredProperty(properties, "db.username"),
                requiredProperty(properties, "db.password"))) {
            setUtc(connection);
            execute(connection, "CREATE DATABASE IF NOT EXISTS `" + TEST_DATABASE
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?")
                + "allowPublicKeyRetrieval=true";
    }

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing live migration test: JDBC database must be exactly " + TEST_DATABASE);
    }

    private static Properties loadProperties(Path path) throws IOException {
        require(Files.isRegularFile(path), "Missing ignored local db.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "Missing database property: " + key);
        return value;
    }

    private static void setUtc(Connection connection) throws SQLException {
        execute(connection, "SET time_zone = '+00:00'");
        require("+00:00".equals(queryString(connection, "SELECT @@session.time_zone")),
                "Migration connection must use UTC");
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return queryString(connection, "SELECT DATABASE()");
    }

    private static void expectSqlRejected(Connection connection, String sql, String message)
            throws SQLException {
        try {
            execute(connection, sql);
        } catch (SQLException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("Repository root was not found");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
