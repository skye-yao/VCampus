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
 * V006 教师调课申请迁移的唯一测试入口。
 *
 * <p>无参数运行时只校验迁移文件契约，不连接数据库。只有在 {@code args} 中出现
 * {@code mysql} 或 {@code --mysql} 时才在受保护的测试库上执行真实迁移，
 * 避免默认把 SKIP 当成 PASS。配置路径由 {@code --config=<path>} 传入。
 */
public final class TeacherAdjustmentMigrationTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String MIGRATION =
            "VCampusServer/src/resources/migrations/V006_teacher_adjustment_requests.sql";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");
    /**
     * 迁移文件里的升级前审计查询：逐行报告已终结却缺少 reviewed_at/reviewed_by 的历史申请。
     * 测试把它当成一段可执行 SQL 复用，既证明文件里确实有审计，也在脏数据上证明它真的会报出行。
     */
    private static final Pattern HISTORICAL_AUDIT = Pattern.compile(
            "(?is)SELECT\\s+`request_id`\\s*,\\s*`status`.*?ORDER\\s+BY\\s+`request_id`\\s*;");

    static {
        System.setProperty("user.language", "en");
        System.setProperty("user.country", "US");
    }

    private TeacherAdjustmentMigrationTest() {
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
        System.out.println("V006 static file contract passed.");

        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the live MySQL migration section "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        verifyMySqlMigration(root, config);
        System.out.println("V006 live MySQL migration passed in " + TEST_DATABASE + ".");
    }

    private static void verifyMigrationFileContract(Path root) throws Exception {
        Path file = root.resolve(MIGRATION);
        require(Files.isRegularFile(file), "Missing V006 migration: " + MIGRATION);
        String sql = Files.readString(file, StandardCharsets.UTF_8);

        require(sql.contains("`withdrawn_at` DATETIME(6) NULL"),
                "adjustment_request.withdrawn_at must be an additive nullable DATETIME(6) column");
        require(sql.contains("information_schema.COLUMNS"),
                "the ADD COLUMN must be guarded by an information_schema existence check");
        require(sql.contains("'PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'"),
                "the status CHECK must accept exactly the four adjustment states");
        // Counts keep the intent while tolerating the parenthesis/backtick normalisation MySQL
        // applies when it stores a CHECK clause.
        require(countOccurrences(sql, "'PENDING'") >= 2
                        && countOccurrences(sql, "'WITHDRAWN'") >= 2,
                "the review CHECK must name every state it distinguishes");
        require(countOccurrences(sql, "`withdrawn_at` IS NULL") >= 2
                        && countOccurrences(sql, "`withdrawn_at` IS NOT NULL") >= 1,
                "a withdrawal instant must be required for WITHDRAWN and forbidden otherwise");
        require(countOccurrences(sql, "`reviewed_at` IS NULL") >= 2
                        && countOccurrences(sql, "`reviewed_at` IS NOT NULL") >= 1,
                "reviewed_at must be required for the two decided states and forbidden otherwise");
        require(countOccurrences(sql, "`status` = 'WITHDRAWN'") >= 1
                        && sql.contains("`withdrawn_at` IS NOT NULL AND `reviewed_at` IS NULL"),
                "a teacher withdrawal must carry its own instant and never masquerade as an "
                        + "administrator decision");
        require(sql.contains("DROP CHECK `chk_course_schedule_adjustment_request_status`")
                        && sql.contains("DROP CHECK `chk_course_schedule_adjustment_request_review`")
                        && countOccurrences(sql, "DROP CHECK") == 2,
                "V006 must replace the V004 status and review checks instead of stacking new ones");
        require(sql.contains("`target_calendar_date_id` BIGINT NULL"),
                "adjustment_target.target_calendar_date_id must be a nullable BIGINT, so historical "
                        + "rows keep resolving their date from original_week_no + new_weekday");
        require(sql.contains("REFERENCES `calendar_date` (`id`)"),
                "the target date must be constrained to a real calendar date");
        require(sql.contains("KEY `idx_course_schedule_adjustment_target_calendar_date`"),
                "the target date needs an index for the cross-week lookups");
        require(HISTORICAL_AUDIT.matcher(sql).find(),
                "V006 must audit historical decided requests for a reviewed_by/reviewed_at identity "
                        + "before tightening the review constraint");
        require(sql.contains("CASE WHEN `reviewed_by` IS NULL THEN 'reviewed_by' ELSE '' END")
                        && sql.contains("CASE WHEN `reviewed_at` IS NULL THEN 'reviewed_at' ELSE '' END")
                        && sql.contains("WHERE `status` IN ('APPROVED', 'REJECTED')")
                        && sql.contains("(`reviewed_at` IS NULL OR `reviewed_by` IS NULL)"),
                "the audit must report every decided request without a reviewed_by/reviewed_at "
                        + "identity, row by row");
        require(sql.contains("绝不伪造审核人"),
                "the migration must state that a non-compliant historical row is reported and "
                        + "never repaired with a fabricated reviewer");
        require(countOccurrences(sql, "FROM information_schema.TABLE_CONSTRAINTS") >= 2
                        && countOccurrences(sql, "FROM information_schema.COLUMNS") >= 2,
                "every DDL must be guarded by an information_schema existence check, because this "
                        + "MySQL 8.0 rejects ADD COLUMN IF NOT EXISTS / DROP CHECK IF EXISTS");
        require(!sql.contains("UPDATE `course_schedule_adjustment`")
                        && !sql.contains("UPDATE `course_occurrence`")
                        && !sql.contains("UPDATE `enrollment`"),
                "V006 must not rewrite the V004 adjustment results or the base schedule");

        for (String forbidden : List.of(
                "DROP TABLE", "DELETE FROM", "TRUNCATE ",
                "UPDATE `course_schedule_adjustment_request`")) {
            require(!sql.contains(forbidden),
                    "V006 only adds columns and constraints plus the guarded audit, but contains: "
                            + forbidden);
        }
        require(!sql.contains("AFTER `withdrawn_at`"),
                "new columns must be appended so the migration stays re-appliable");
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
            // The live V006 upgrade always runs on a schema that already carries V005 (the teacher
            // operation log), so the rebuilt fixture schema must apply it too; otherwise every
            // teacher write test in this suite would run against a schema production never sees.
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
            insertRequestFixtures(connection);
            applyScript(connection, root.resolve(MIGRATION));
            // Re-applying proves the guarded columns and the replace-then-add constraint
            // replacement are idempotent, not only that a fresh install works once.
            applyScript(connection, root.resolve(MIGRATION));

            // Both applications above must succeed on compliant data; the audit query inside
            // the migration is then read back and re-run by verifyHistoricalAudit.
            try {
                verifyWithdrawnState(connection, root);
                verifyTargetDateColumn(connection);
                verifyHistoricalAudit(connection, root);
            } finally {
                removeRequestFixtures(connection);
            }
        }
    }

    /** One pending and one decided request, both satisfying the tightened review constraint. */
    private static void insertRequestFixtures(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO course_schedule_adjustment_request"
                + " (request_id, offering_id, requested_by, reason, status, new_weekday,"
                + " new_start_period, new_end_period, submitted_at)"
                + " VALUES (880001, 2001, 'teacher-alpha', 'migration fixture PENDING',"
                + " 'PENDING', 2, 1, 2, '2026-09-10 00:00:00')");
        execute(connection, "INSERT INTO course_schedule_adjustment_request"
                + " (request_id, offering_id, requested_by, reason, status, new_weekday,"
                + " new_start_period, new_end_period, submitted_at, reviewed_at, reviewed_by)"
                + " VALUES (880002, 2001, 'teacher-alpha', 'migration fixture APPROVED',"
                + " 'APPROVED', 2, 1, 2, '2026-09-10 00:00:00', '2026-09-11 00:00:00',"
                + " 'admin-alpha')");
    }

    private static void removeRequestFixtures(Connection connection) {
        try {
            execute(connection, "DELETE FROM course_schedule_adjustment_target"
                    + " WHERE request_id BETWEEN 880001 AND 880099");
            execute(connection, "DELETE FROM course_schedule_adjustment_request"
                    + " WHERE request_id BETWEEN 880001 AND 880099");
        } catch (SQLException cleanupFailure) {
            System.out.println("WARNING: migration fixtures were not cleaned up: "
                    + cleanupFailure.getMessage());
        }
    }

    private static void verifyWithdrawnState(Connection connection, Path root) throws Exception {
        assertColumn(connection, "course_schedule_adjustment_request", "withdrawn_at", "datetime",
                null, 6);
        assertColumnNullable(connection, "course_schedule_adjustment_request", "withdrawn_at", true);
        assertCheckConstraint(connection, "course_schedule_adjustment_request",
                "chk_course_schedule_adjustment_request_status", "WITHDRAWN");
        assertCheckConstraint(connection, "course_schedule_adjustment_request",
                "chk_course_schedule_adjustment_request_review", "withdrawn_at",
                "WITHDRAWN", "reviewed_at` is null");

        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE request_id = 880001 AND status = 'PENDING'"
                        + " AND withdrawn_at IS NULL AND reviewed_at IS NULL") == 1
                        && queryInt(connection,
                                "SELECT COUNT(*) FROM course_schedule_adjustment_request"
                                        + " WHERE request_id = 880002 AND status = 'APPROVED'"
                                        + " AND reviewed_at IS NOT NULL"
                                        + " AND reviewed_by = 'admin-alpha'") == 1,
                "the migration must keep compliant historical rows readable as they were");

        execute(connection, "SET autocommit = 0");
        try {
            // 1) 撤销是申请人自己的终态：只有 withdrawn_at，没有管理员审核身份。
            execute(connection, "UPDATE course_schedule_adjustment_request"
                    + " SET status='WITHDRAWN', withdrawn_at=UTC_TIMESTAMP(6), reviewed_at=NULL,"
                    + " version=version+1 WHERE request_id=880001");
            require("WITHDRAWN".equals(queryString(connection,
                            "SELECT status FROM course_schedule_adjustment_request"
                                    + " WHERE request_id=880001")),
                    "a withdrawal must be storable without administrator review fields");

            // 2) 撤销后不能退回未处理/已终结，也不能是未知状态。
            expectSqlRejected(connection,
                    "UPDATE course_schedule_adjustment_request SET status='PENDING',"
                            + " reason='back to pending', version=version+1 WHERE request_id=880001",
                    "HY", "chk_course_schedule_adjustment_request_review",
                    "a withdrawn request must not return to PENDING while it still carries a"
                            + " withdrawal instant");
            expectSqlRejected(connection,
                    "UPDATE course_schedule_adjustment_request SET status='APPROVED',"
                            + " reviewed_at=UTC_TIMESTAMP(6), reviewed_by='admin-alpha',"
                            + " version=version+1 WHERE request_id=880001",
                    "HY", "chk_course_schedule_adjustment_request_review",
                    "an approval must clear the withdrawal instant");
            // 未带 withdrawn_at 的撤销同样被拒：行里 reviewed_at 仍是 NULL，但撤销时刻缺失。
            execute(connection, "UPDATE course_schedule_adjustment_request"
                    + " SET status='PENDING', withdrawn_at=NULL, reason='pending again',"
                    + " version=version+1 WHERE request_id=880001");
            expectSqlRejected(connection,
                    "UPDATE course_schedule_adjustment_request SET status='WITHDRAWN',"
                            + " reason='withdraw without instant', version=version+1"
                            + " WHERE request_id=880001",
                    "HY", "chk_course_schedule_adjustment_request_review",
                    "a withdrawal must carry its own withdrawn_at instant");

            // 3) 未处理、未撤销的行状态改成未知值：状态值只会被状态 CHECK 拦住。
            expectSqlRejectedByConstraint(connection,
                    "UPDATE course_schedule_adjustment_request SET status='CANCELLED'"
                            + " WHERE request_id=880001",
                    "chk_course_schedule_adjustment_request_",
                    "an unknown status must be rejected by the four-state CHECKs");
            require("PENDING".equals(queryString(connection,
                            "SELECT status FROM course_schedule_adjustment_request"
                                    + " WHERE request_id=880001")),
                    "a rejected status update must leave the request unchanged");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }

        // A decided request keeps its review identity; the withdrawal column must stay unused.
        execute(connection, "SET autocommit = 0");
        try {
            execute(connection, "UPDATE course_schedule_adjustment_request SET status='APPROVED',"
                    + " reviewed_at=UTC_TIMESTAMP(6), version=version+1 WHERE request_id=880001");
            expectSqlRejected(connection,
                    "UPDATE course_schedule_adjustment_request SET withdrawn_at=UTC_TIMESTAMP(6)"
                            + " WHERE request_id=880001",
                    "HY", "chk_course_schedule_adjustment_request_review",
                    "a decided request must not also carry a withdrawal instant");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
    }

    private static void verifyTargetDateColumn(Connection connection) throws Exception {
        assertColumn(connection, "course_schedule_adjustment_target", "target_calendar_date_id",
                "bigint", null, null);
        assertColumnNullable(connection, "course_schedule_adjustment_target",
                "target_calendar_date_id", true);
        assertForeignKey(connection, "course_schedule_adjustment_target",
                "fk_course_schedule_adjustment_target_calendar_date", "calendar_date", "SET NULL");

        // Historical targets have no explicit date and must stay readable with NULL.
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_schedule_adjustment_target"
                        + " WHERE target_calendar_date_id IS NOT NULL") == 0,
                "propagated legacy targets must keep a NULL target date, not a guessed one");

        execute(connection, "SET autocommit = 0");
        try {
            // 4201 is a seeded occurrence and 3301 a seeded calendar date.
            execute(connection, "INSERT INTO course_schedule_adjustment_target"
                    + " (request_id, original_occurrence_id, original_week_no, original_start_at,"
                    + " original_end_at, target_calendar_date_id) VALUES (880001, 4201, 1,"
                    + " '2026-09-08 00:00:00', '2026-09-08 01:35:00', 3301)");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment_target"
                            + " WHERE request_id = 880001"
                            + " AND target_calendar_date_id = 3301") == 1,
                    "a new target must be able to name its real teaching date");
            expectSqlRejected(connection,
                    "INSERT INTO course_schedule_adjustment_target"
                            + " (request_id, original_occurrence_id, original_week_no,"
                            + " original_start_at, original_end_at, target_calendar_date_id)"
                            + " VALUES (880001, 4202, 1, '2026-09-10 02:00:00',"
                            + " '2026-09-10 03:35:00', 880004)",
                    "23", "fk_course_schedule_adjustment_target_calendar_date",
                    "a target date must reference a real calendar date");
            // Historical rows keep today's behaviour: no explicit date is still storable.
            execute(connection, "INSERT INTO course_schedule_adjustment_target"
                    + " (request_id, original_occurrence_id, original_week_no, original_start_at,"
                    + " original_end_at, target_calendar_date_id) VALUES (880001, 4203, 1,"
                    + " '2026-09-08 00:00:00', '2026-09-08 01:35:00', NULL)");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment_target"
                            + " WHERE request_id = 880001 AND original_occurrence_id = 4203"
                            + " AND target_calendar_date_id IS NULL") == 1,
                    "a historical target without a date must keep its NULL target date");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment_target"
                + " WHERE request_id = 880001") == 0,
                "the target fixtures must be rolled back");
    }

    /**
     * The pre-upgrade audit must be silent for an upgrade whose decided requests all carry their
     * review identity, and its rule is pinned in the static contract: a decided request without
     * reviewed_by/reviewed_at is a violation. On a freshly migrated schema the tightened review
     * constraint already forbids such a row, so the dirty-row report cannot be reproduced by an
     * INSERT here without first dismantling the constraint under test.
     */
    private static void verifyHistoricalAudit(Connection connection, Path root) throws Exception {
        String audit = historicalAuditStatement(root.resolve(MIGRATION));
        require(auditRows(connection, audit).isEmpty(),
                "the audit must be silent for the compliant request fixtures");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE status IN ('APPROVED', 'REJECTED')") == 1,
                "the compliant fixture must include a decided request for the audit to inspect");
    }

    /** Runs the migration's audit query and renders every violating row with what is missing. */
    private static List<String> auditRows(Connection connection, String audit) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(audit)) {
            while (result.next()) {
                rows.add("request_id=" + result.getLong("request_id")
                        + "|status=" + result.getString("status")
                        + "|missing_reviewed_by=" + result.getString("missing_reviewed_by")
                        + "|missing_reviewed_at=" + result.getString("missing_reviewed_at"));
            }
        }
        return rows;
    }

    private static String historicalAuditStatement(Path migration) throws Exception {
        Matcher matcher = HISTORICAL_AUDIT.matcher(
                Files.readString(migration, StandardCharsets.UTF_8));
        require(matcher.find(), "the historical audit statement was not found in " + migration);
        return matcher.group();
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

    private static void assertCheckConstraint(Connection connection, String table,
                                              String constraint, String... fragments)
            throws SQLException {
        String sql = "SELECT c.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS c"
                + " JOIN information_schema.TABLE_CONSTRAINTS t"
                + " ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA"
                + " AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME"
                + " WHERE c.CONSTRAINT_SCHEMA = DATABASE() AND t.TABLE_NAME = ?"
                + " AND c.CONSTRAINT_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing check constraint " + table + "." + constraint);
                String clause = result.getString(1) == null
                        ? "" : result.getString(1).toLowerCase();
                for (String fragment : fragments) {
                    require(clause.contains(fragment.toLowerCase()),
                            "Check constraint " + constraint + " must mention " + fragment
                                    + ", saw " + clause);
                }
            }
        }
    }

    private static void assertForeignKey(Connection connection, String table, String constraint,
                                         String referencedTable, String deleteRule)
            throws SQLException {
        String sql = "SELECT u.REFERENCED_TABLE_NAME, r.DELETE_RULE"
                + " FROM information_schema.KEY_COLUMN_USAGE u"
                + " JOIN information_schema.REFERENTIAL_CONSTRAINTS r"
                + " ON r.CONSTRAINT_SCHEMA=u.CONSTRAINT_SCHEMA"
                + " AND r.CONSTRAINT_NAME=u.CONSTRAINT_NAME"
                + " AND r.TABLE_NAME=u.TABLE_NAME"
                + " WHERE u.CONSTRAINT_SCHEMA=DATABASE() AND u.TABLE_NAME=?"
                + " AND u.CONSTRAINT_NAME=? AND u.REFERENCED_TABLE_NAME IS NOT NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing foreign key " + table + "." + constraint);
                require(referencedTable.equalsIgnoreCase(result.getString(1)),
                        "Unexpected parent table for " + constraint + ": " + result.getString(1));
                require(deleteRule.equalsIgnoreCase(result.getString(2)),
                        "Unexpected delete rule for " + constraint + ": " + result.getString(2));
            }
        }
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

    /** 接受任一以给定前缀命名的 CHECK 拦截该语句，用于不保证求值顺序的多约束写入。 */
    private static void expectSqlRejectedByConstraint(Connection connection, String sql,
                                                      String constraintPrefix, String message)
            throws SQLException {
        try {
            execute(connection, sql);
        } catch (SQLException failure) {
            for (SQLException candidate = failure; candidate != null;
                 candidate = candidate.getNextException()) {
                String sqlState = candidate.getSQLState();
                String errorMessage = candidate.getMessage();
                boolean expectedState = sqlState != null && sqlState.startsWith("HY");
                boolean expectedObject = errorMessage != null
                        && errorMessage.toLowerCase().contains(constraintPrefix.toLowerCase());
                if (expectedState && expectedObject) {
                    return;
                }
            }
            throw new AssertionError(message + ": expected a check-constraint rejection naming "
                    + constraintPrefix + ", but got SQLState " + failure.getSQLState()
                    + " with message: " + failure.getMessage(), failure);
        }
        throw new AssertionError(message);
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
