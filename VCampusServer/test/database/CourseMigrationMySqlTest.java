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
            // V004 backfills course_schedule_rule.arrangement_id for existing rules and then
            // tightens it to NOT NULL, so the legacy seed must be loaded before the migration.
            applyScript(connection, seed);
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));

            verifySeedCoverage(connection);
            verifyMetadataContracts(connection);
            verifyGeneratedConstraints(connection);
            verifyV004AdminCourseContracts(connection);
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
                        + "WHERE selection_open_at < UTC_TIMESTAMP(6) "
                        + "AND selection_close_at > UTC_TIMESTAMP(6) "
                        + "AND drop_deadline > UTC_TIMESTAMP(6)") >= 1,
                "seed requires open selection and drop windows, including an unclosed selection window");
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
        assertColumn(connection, "course_notice", "created_by", "varchar", 32L, null);
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
                "23", "uk_enrollment_active_course",
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
                "HY", "chk_enrollment_drop_time",
                "enrollment status 1 must be rejected");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 4)",
                "HY", "chk_enrollment_drop_time",
                "enrollment status 4 must be rejected");

        execute(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status, drop_time) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 3, UTC_TIMESTAMP(6))");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status, drop_time) "
                        + "VALUES (2002, 1001, 2026, 2, 'student-beta', 3, UTC_TIMESTAMP(6))",
                "23", "uk_enrollment_uid_offering",
                "student and offering enrollment key must be unique");

        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2003, 1002, 2027, 3, 'student-beta', 2)",
                "23", "fk_enrollment_offering_identity",
                "enrollment course and term must match its offering");
        expectSqlRejected(connection,
                "INSERT INTO enrollment "
                        + "(offering_id, course_id, academic_year, semester, uid, status) "
                        + "VALUES (2003, 1001, 2027, 3, 'missingenrollment01', 2)",
                "23", "fk_enrollment_uid",
                "enrollment UID foreign key must be enforced");

        expectSqlRejected(connection,
                "INSERT INTO student_academic_profile (uid, major_id, cohort_year) "
                        + "VALUES ('missingprofile01', 10, 2026)",
                "23", "fk_student_academic_profile_user",
                "academic-profile UID foreign key must be enforced");
        expectSqlRejected(connection,
                "INSERT INTO course_plan_item (uid, offering_id, status) "
                        + "VALUES ('missingplan01', 2003, 'PLANNED')",
                "23", "fk_course_plan_item_user",
                "plan UID foreign key must be enforced");
        expectSqlRejected(connection,
                "INSERT INTO course_waitlist (uid, offering_id, status) "
                        + "VALUES ('missingwaitlist01', 2003, 'WAITING')",
                "23", "fk_course_waitlist_user",
                "waitlist UID foreign key must be enforced");
        expectSqlRejected(connection,
                "INSERT INTO course_notice "
                        + "(offering_id, title, content, notice_type, status, created_by) "
                        + "VALUES (2003, 'Missing creator', 'FK contract test', 'GENERAL', "
                        + "'DRAFT', 'missingnotice01')",
                "23", "fk_course_notice_creator",
                "notice creator UID foreign key must be enforced");
        expectSqlRejected(connection,
                "INSERT INTO course_operation_log "
                        + "(uid, operation_id, action, request_digest, result_code, response_json) "
                        + "VALUES ('missingoperation01', '00000000-0000-0000-0000-000000000002', "
                        + "'addToPlan', REPEAT('c', 64), 'OK', JSON_OBJECT())",
                "23", "fk_course_operation_user",
                "operation UID foreign key must be enforced");
        expectSqlRejected(connection,
                "INSERT INTO course_event_outbox "
                        + "(uid, event_type, academic_year, semester, payload) "
                        + "VALUES ('missingoutbox01', 'WAITLIST_OFFERED', 2026, 2, JSON_OBJECT())",
                "23", "fk_course_event_outbox_user",
                "outbox UID foreign key must be enforced");

        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_offering_teacher WHERE uid LIKE 'teacher-%'") == 2,
                "alphanumeric teacher UIDs must satisfy foreign keys");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM enrollment WHERE uid LIKE 'student-%'") >= 2,
                "alphanumeric student UIDs must satisfy foreign keys");

        expectSqlRejected(connection,
                "INSERT INTO course_plan_item (uid, offering_id, status) "
                        + "VALUES ('student-alpha', 2002, 'PLANNED')",
                "23", "uk_course_plan_item_uid_offering",
                "student and offering plan key must be unique");
        expectSqlRejected(connection,
                "INSERT INTO course_plan_item (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2003, 'UNKNOWN')",
                "HY", "chk_course_plan_item_status",
                "plan status must be PLANNED or FULL");
        expectSqlRejected(connection,
                "INSERT INTO course_waitlist (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2002, 'WAITING')",
                "23", "uk_course_waitlist_uid_offering",
                "student and offering waitlist key must be unique");
        expectSqlRejected(connection,
                "INSERT INTO course_waitlist (uid, offering_id, status) "
                        + "VALUES ('student-beta', 2003, 'UNKNOWN')",
                "HY", "chk_course_waitlist_status",
                "waitlist status domain must be enforced");

        expectSqlRejected(connection,
                "INSERT INTO course_operation_log "
                        + "(uid, operation_id, action, request_digest, result_code, response_json) "
                        + "VALUES ('student-alpha', '00000000-0000-0000-0000-000000000001', "
                        + "'addToPlan', REPEAT('b', 64), 'OK', JSON_OBJECT())",
                "23", "PRIMARY",
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
                "23", "fk_selection_window_schedule_plan",
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

    private static void verifyV004AdminCourseContracts(Connection connection) throws SQLException {
        assertColumn(connection, "course", "status", "varchar", 16L, null);
        assertColumn(connection, "course", "version", "int", null, null);
        assertColumn(connection, "course", "archived_by", "varchar", 32L, null);
        assertDatetimePrecision(connection, "course", "archived_at");
        assertColumnDefault(connection, "course", "status", "ACTIVE");
        assertColumnDefault(connection, "course", "version", "1");
        require("ACTIVE".equals(queryString(connection,
                "SELECT `status` FROM course WHERE course_id = 1001")),
                "existing courses must default to ACTIVE");
        require(queryInt(connection, "SELECT `version` FROM course WHERE course_id = 1001") == 1,
                "existing courses must default to version 1");

        assertColumn(connection, "course_offering", "version", "int", null, null);
        assertColumn(connection, "course_offering", "created_by", "varchar", 32L, null);
        assertColumn(connection, "course_offering", "cancelled_by", "varchar", 32L, null);
        assertDatetimePrecision(connection, "course_offering", "cancelled_at");
        assertColumnDefault(connection, "course_offering", "version", "1");
        require(queryInt(connection,
                "SELECT `version` FROM course_offering WHERE offering_id = 2001") == 1,
                "existing offerings must default to version 1");

        assertCheckExists(connection, "chk_course_offering_enrolled_count", false);
        assertCheckExists(connection, "chk_course_offering_enrolled_nonnegative", true);
        int capacity = queryInt(connection,
                "SELECT capacity FROM course_offering WHERE offering_id = 2003");
        execute(connection, "UPDATE course_offering SET enrolled_count = " + (capacity + 5)
                + " WHERE offering_id = 2003");
        require(queryInt(connection,
                "SELECT enrolled_count FROM course_offering WHERE offering_id = 2003") == capacity + 5,
                "over-capacity enrollment must be accepted after the check is relaxed");
        execute(connection, "UPDATE course_offering SET enrolled_count = 0 WHERE offering_id = 2003");
        expectSqlRejected(connection,
                "UPDATE course_offering SET enrolled_count = -1 WHERE offering_id = 2003",
                "HY", "chk_course_offering_enrolled_nonnegative",
                "negative enrollment counts must still be rejected");

        assertColumn(connection, "schedule_plan", "created_by", "varchar", 32L, null);
        assertColumn(connection, "schedule_plan", "published_by", "varchar", 32L, null);
        assertDatetimePrecision(connection, "schedule_plan", "published_at");
        assertColumn(connection, "teaching_calendar", "current_schedule_plan_id", "bigint", null, null);
        assertColumn(connection, "course_notice", "adjustment_request_id", "bigint", null, null);

        String[] newTables = {
                "course_schedule_arrangement", "course_schedule_adjustment_request",
                "course_schedule_adjustment_target", "course_schedule_adjustment",
                "grade_submission", "grade_submission_item", "admin_course_operation_log"
        };
        for (String table : newTables) {
            assertTableExists(connection, table);
        }
        assertDatetimePrecision(connection, "course_schedule_arrangement", "created_at", "updated_at");
        assertIndexColumns(connection, "course_schedule_arrangement",
                "uk_course_schedule_arrangement_plan", "arrangement_id", "plan_id");
        assertIndexColumns(connection, "course_schedule_adjustment_target",
                "uk_course_schedule_adjustment_target", "request_id", "original_occurrence_id");
        assertIndexColumns(connection, "course_schedule_adjustment",
                "uk_active_adjustment_occurrence", "active_original_occurrence_id");
        assertIndexColumns(connection, "grade_submission",
                "uk_grade_submission_offering_version", "offering_id", "version");
        assertIndexColumns(connection, "grade_submission_item",
                "uk_grade_submission_item", "submission_id", "enrollment_id");
        assertIndexColumns(connection, "admin_course_operation_log", "PRIMARY",
                "admin_uid", "operation_id");

        assertColumnNullable(connection, "course_schedule_rule", "arrangement_id", false);
        require(queryInt(connection,
                "SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_schedule_rule' "
                        + "AND REFERENCED_TABLE_NAME = 'course_schedule_arrangement'") == 2,
                "course_schedule_rule must reference course_schedule_arrangement");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_schedule_rule WHERE arrangement_id = id") == 4,
                "legacy rules must backfill arrangement_id = rule id");
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_arrangement") == 4,
                "legacy backfill must create exactly one arrangement per rule");
        require(queryInt(connection,
                "SELECT COUNT(*) FROM course_schedule_rule r "
                        + "JOIN course_schedule_arrangement a ON a.arrangement_id = r.arrangement_id "
                        + "WHERE a.plan_id = r.plan_id AND a.offering_id = r.course_offering_id") == 4,
                "backfilled arrangements must mirror their rule plan and offering");

        execute(connection, "INSERT INTO course_schedule_adjustment_request "
                + "(request_id, offering_id, requested_by, reason, new_weekday, "
                + "new_start_period, new_end_period, new_teacher_uid) "
                + "VALUES (7001, 2001, 'teacher-alpha', 'V004 contract', 3, 5, 6, 'teacher-beta')");
        execute(connection, "INSERT INTO course_schedule_adjustment "
                + "(request_id, original_occurrence_id, start_at_utc, end_at_utc, teacher_uid) "
                + "VALUES (7001, 4201, '2026-09-09 00:00:00', '2026-09-09 01:35:00', 'teacher-beta')");
        expectSqlRejected(connection,
                "INSERT INTO course_schedule_adjustment "
                        + "(request_id, original_occurrence_id, start_at_utc, end_at_utc, teacher_uid) "
                        + "VALUES (7001, 4201, '2026-09-09 02:00:00', '2026-09-09 03:35:00', "
                        + "'teacher-beta')",
                "23", "uk_active_adjustment_occurrence",
                "at most one active adjustment per original occurrence");
        execute(connection, "INSERT INTO course_schedule_adjustment "
                + "(request_id, original_occurrence_id, start_at_utc, end_at_utc, teacher_uid, status) "
                + "VALUES (7001, 4201, '2026-09-09 04:00:00', '2026-09-09 05:35:00', "
                + "'teacher-beta', 'CANCELLED')");
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment "
                + "WHERE status = 'ACTIVE'") == 1,
                "cancelled adjustment history must remain insertable");
    }

    private static void assertTableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getInt(1) == 1, "Missing V004 table: " + table);
            }
        }
    }

    private static void assertCheckExists(Connection connection, String name, boolean expected)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS "
                + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_TYPE = 'CHECK' "
                + "AND CONSTRAINT_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Check-constraint probe returned no row");
                boolean present = result.getInt(1) > 0;
                require(present == expected, (expected ? "Missing" : "Unexpected")
                        + " check constraint: " + name);
            }
        }
    }

    private static void assertColumnDefault(Connection connection, String table, String column,
                                            String expectedDefault) throws SQLException {
        String sql = "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                require(expectedDefault.equals(result.getString(1)),
                        "Unexpected default for " + table + "." + column);
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
