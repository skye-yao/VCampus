package database;

import java.io.InputStream;
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

/**
 * V005 教师端基础迁移的唯一测试入口。
 *
 * <p>无参数运行时只校验迁移文件契约，不连接数据库。只有在 {@code args} 中出现
 * {@code mysql} 或 {@code --mysql} 时才在受保护的测试库上执行真实迁移，
 * 避免默认把 SKIP 当成 PASS。配置路径由 {@code --config=<path>} 传入。
 */
public final class TeacherFoundationMigrationTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String MIGRATION =
            "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private TeacherFoundationMigrationTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        boolean withMySql = false;
        Path config = root.resolve(DEFAULT_CONFIG);
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) {
                withMySql = true;
            } else if (argument.startsWith("--config=")) {
                config = resolveConfig(root, argument.substring("--config=".length()));
            } else {
                throw new AssertionError("Unsupported argument: " + argument);
            }
        }

        verifyMigrationFileContract(root);
        System.out.println("V005 static file contract passed.");

        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the live MySQL migration section "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        verifyMySqlMigration(root, config);
        System.out.println("V005 live MySQL migration passed in " + TEST_DATABASE + ".");
    }

    private static void verifyMigrationFileContract(Path root) throws Exception {
        Path file = root.resolve(MIGRATION);
        require(Files.isRegularFile(file), "Missing V005 migration: " + MIGRATION);
        String sql = Files.readString(file, StandardCharsets.UTF_8);

        require(sql.contains("ADD COLUMN `offering_college` VARCHAR(100) NULL"),
                "course.offering_college must be an additive nullable VARCHAR(100) column");
        require(countOccurrences(sql, "ALTER TABLE") == 1,
                "V005 must only alter `course`; it must not rewrite V001-V004 structures");
        require(sql.contains("information_schema.COLUMNS"),
                "the ADD COLUMN must be guarded by an information_schema existence check");

        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_course_operation_log`"),
                "teacher_course_operation_log table required");
        require(sql.contains("PRIMARY KEY (`teacher_uid`, `operation_id`)"),
                "teacher operation log must be keyed by (teacher_uid, operation_id)");
        for (String token : List.of(
                "`action` VARCHAR(64) NOT NULL",
                "`target_type` VARCHAR(32) NOT NULL",
                "`target_id` VARCHAR(64) NOT NULL",
                "`request_digest` CHAR(64) NOT NULL",
                "`request_json` JSON NOT NULL",
                "`response_json` JSON NOT NULL",
                "`result_code` VARCHAR(48) NOT NULL",
                "`created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)")) {
            require(sql.contains(token), "missing operation-log column contract: " + token);
        }
        require(sql.contains("SHA-256"), "the request digest must be documented as SHA-256");
        require(sql.contains("CONSTRAINT `fk_teacher_course_operation_teacher`"),
                "operation log must constrain teacher_uid");
        require(sql.contains("REFERENCES `tbl_user` (`UID`)"),
                "operation log must reference tbl_user");

        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_application_read`"),
                "teacher_application_read table required");
        require(sql.contains(
                "PRIMARY KEY (`teacher_uid`, `application_type`, `application_id`)"),
                "read markers must be keyed by (teacher_uid, application_type, application_id)");
        require(sql.contains("`seen_state_key` VARCHAR(128) NOT NULL"),
                "teacher_application_read.seen_state_key required");
        require(sql.contains("`read_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"),
                "teacher_application_read.read_at must be DATETIME(6)");
        require(sql.contains(
                "CHECK (`application_type` IN ('SCHEDULE_ADJUSTMENT', 'GRADE_SUBMISSION'))"),
                "application_type must be limited to the two teacher application kinds");

        for (String forbidden : List.of(
                "DROP TABLE", "DROP CHECK", "DELETE FROM", "INSERT INTO", "TRUNCATE ", "UPDATE `")) {
            require(!sql.contains(forbidden),
                    "V005 is additive only but contains a write-back statement: " + forbidden);
        }
    }

    private static void verifyMySqlMigration(Path root, Path config) throws Exception {
        require(Files.isRegularFile(config), "Missing ignored local db.properties: " + config);
        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String testUrl = withTestAuthentication(url);
        ensureTestSchema(testUrl, properties);

        try (Connection connection = DriverManager.getConnection(testUrl,
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
            // V004 backfills course_schedule_rule.arrangement_id before tightening it, so the
            // legacy seed must be loaded before the migration.
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/seed-course-test.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
            applyScript(connection, root.resolve(MIGRATION));
            // Re-applying proves the guarded ADD COLUMN and IF NOT EXISTS tables are idempotent.
            applyScript(connection, root.resolve(MIGRATION));

            verifyOfferingCollegeColumn(connection);
            verifyOperationLog(connection);
            verifyApplicationRead(connection);
        }
    }

    private static void verifyOfferingCollegeColumn(Connection connection) throws SQLException {
        assertColumn(connection, "course", "offering_college", "varchar", 100L, null);
        assertColumnNullable(connection, "course", "offering_college", true);
        require(queryInt(connection, "SELECT COUNT(*) FROM course") > 0,
                "the legacy seed must provide course rows");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course WHERE offering_college IS NOT NULL") == 0,
                "legacy course rows must read back with offering_college NULL, not a fake college");
    }

    private static void verifyOperationLog(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_course_operation_log");
        assertIndexColumns(connection, "teacher_course_operation_log",
                "PRIMARY", "teacher_uid", "operation_id");
        assertColumn(connection, "teacher_course_operation_log", "operation_id", "char", 36L, null);
        assertColumn(connection, "teacher_course_operation_log", "request_digest", "char", 64L, null);
        assertColumn(connection, "teacher_course_operation_log", "action", "varchar", 64L, null);
        assertColumn(connection, "teacher_course_operation_log", "target_type", "varchar", 32L, null);
        assertColumn(connection, "teacher_course_operation_log", "target_id", "varchar", 64L, null);
        assertColumn(connection, "teacher_course_operation_log", "result_code", "varchar", 48L, null);
        assertDatetimePrecision(connection, "teacher_course_operation_log", "created_at");

        String insert = "INSERT INTO teacher_course_operation_log "
                + "(teacher_uid, operation_id, action, target_type, target_id, request_digest, "
                + "request_json, response_json, result_code) VALUES (";
        expectSqlRejected(connection,
                insert + "'missingteacher01', '00000000-0000-0000-0000-0000000000A1', "
                        + "'submitGradeBook', 'OFFERING', '2001', REPEAT('a', 64), "
                        + "JSON_OBJECT(), JSON_OBJECT(), 'OK')",
                "23", "fk_teacher_course_operation_teacher",
                "operation log teacher_uid foreign key must be enforced");

        execute(connection, insert + "'teacher-alpha', "
                + "'00000000-0000-0000-0000-0000000000A1', 'submitGradeBook', 'OFFERING', "
                + "'2001', REPEAT('a', 64), JSON_OBJECT(), JSON_OBJECT(), 'OK')");
        expectSqlRejected(connection, insert + "'teacher-alpha', "
                        + "'00000000-0000-0000-0000-0000000000A1', 'submitGradeBook', 'OFFERING', "
                        + "'2001', REPEAT('a', 64), JSON_OBJECT(), JSON_OBJECT(), 'OK')",
                "23", "PRIMARY",
                "operation IDs must be unique per teacher");
        execute(connection, insert + "'teacher-beta', "
                + "'00000000-0000-0000-0000-0000000000A1', 'submitGradeBook', 'OFFERING', "
                + "'2001', REPEAT('a', 64), JSON_OBJECT(), JSON_OBJECT(), 'OK')");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM teacher_course_operation_log") == 2,
                "the same operation ID must be reusable by a different teacher");

        expectSqlRejected(connection, insert + "'teacher-alpha', "
                        + "'00000000-0000-0000-0000-0000000000A2', 'submitGradeBook', 'OFFERING', "
                        + "'2001', '', JSON_OBJECT(), JSON_OBJECT(), 'OK')",
                "HY", "chk_teacher_course_operation_digest",
                "an empty request digest must be rejected");
        expectSqlRejected(connection, insert + "'teacher-alpha', "
                        + "'00000000-0000-0000-0000-0000000000A3', '', 'OFFERING', "
                        + "'2001', REPEAT('a', 64), JSON_OBJECT(), JSON_OBJECT(), 'OK')",
                "HY", "chk_teacher_course_operation_action",
                "an empty action must be rejected");

        execute(connection, insert + "'teacher-alpha', "
                + "'00000000-0000-0000-0000-0000000000A4', 'saveGradeDraft', 'OFFERING', "
                + "'2001', REPEAT('b', 64), JSON_OBJECT(), JSON_OBJECT(), 'OK')");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM teacher_course_operation_log "
                        + "WHERE created_at IS NOT NULL") == 3,
                "operation log timestamps must default to a UTC instant");
    }

    private static void verifyApplicationRead(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_application_read");
        assertIndexColumns(connection, "teacher_application_read",
                "PRIMARY", "teacher_uid", "application_type", "application_id");
        assertColumn(connection, "teacher_application_read", "application_id", "bigint", null, null);
        assertColumn(connection, "teacher_application_read", "seen_state_key", "varchar", 128L, null);
        assertDatetimePrecision(connection, "teacher_application_read", "read_at");

        String insert = "INSERT INTO teacher_application_read "
                + "(teacher_uid, application_type, application_id, seen_state_key) VALUES (";
        expectSqlRejected(connection,
                insert + "'teacher-alpha', 'OTHER', 9001, 'APPROVED:2026-09-13T03:00:00Z')",
                "HY", "chk_teacher_application_read_type",
                "application_type must be limited to SCHEDULE_ADJUSTMENT/GRADE_SUBMISSION");
        expectSqlRejected(connection,
                insert + "'missingteacher01', 'SCHEDULE_ADJUSTMENT', 9001, "
                        + "'APPROVED:2026-09-13T03:00:00Z')",
                "23", "fk_teacher_application_read_teacher",
                "read markers must belong to a real teacher");

        execute(connection, insert + "'teacher-alpha', 'SCHEDULE_ADJUSTMENT', 9001, "
                + "'APPROVED:2026-09-13T03:00:00Z')");
        expectSqlRejected(connection, insert + "'teacher-alpha', 'SCHEDULE_ADJUSTMENT', 9001, "
                        + "'REJECTED:2026-09-13T04:00:00Z')",
                "23", "PRIMARY",
                "one read marker per teacher, application kind and application");
        execute(connection, insert + "'teacher-alpha', 'GRADE_SUBMISSION', 9001, "
                + "'PENDING:2026-09-13T03:00:00Z')");
        require(queryInt(connection, "SELECT COUNT(*) FROM teacher_application_read") == 2,
                "the same application id in another application kind must be storable");

        expectSqlRejected(connection, insert + "'teacher-beta', 'SCHEDULE_ADJUSTMENT', 9001, '')",
                "HY", "chk_teacher_application_read_state_key",
                "an empty read state key must be rejected");
    }

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    private static void assertTableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getInt(1) == 1, "Missing V005 table: " + table);
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

    private static void assertColumnNullable(Connection connection, String table, String column,
                                             boolean nullable) throws SQLException {
        String sql = "SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                boolean actual = "YES".equalsIgnoreCase(result.getString(1));
                require(actual == nullable, "Unexpected nullability for " + table + "." + column);
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
                        + " statement " + (i + 1) + ": " + readWarnings(connection), failure);
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
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing live migration test: JDBC database must be exactly " + TEST_DATABASE);
    }

    private static Properties loadProperties(Path path) throws Exception {
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

    private static void expectSqlRejected(Connection connection, String sql,
                                          String expectedSqlStateClass,
                                          String expectedDatabaseObject,
                                          String message) throws SQLException {
        try {
            execute(connection, sql);
        } catch (SQLException failure) {
            for (SQLException candidate = failure; candidate != null;
                 candidate = candidate.getNextException()) {
                String sqlState = candidate.getSQLState();
                String errorMessage = candidate.getMessage();
                boolean expectedState = sqlState != null
                        && sqlState.startsWith(expectedSqlStateClass);
                boolean expectedObject = errorMessage != null
                        && errorMessage.toLowerCase().contains(
                        expectedDatabaseObject.toLowerCase());
                if (expectedState && expectedObject) {
                    return;
                }
            }
            throw new AssertionError(message + ": expected SQLState class "
                    + expectedSqlStateClass + " naming " + expectedDatabaseObject
                    + ", but got SQLState " + failure.getSQLState()
                    + " with message: " + failure.getMessage(), failure);
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
