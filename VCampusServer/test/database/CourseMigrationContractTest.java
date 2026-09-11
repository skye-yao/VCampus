package database;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CourseMigrationContractTest {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`([^`]+)`");

    private CourseMigrationContractTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path migrations = root
                .resolve("VCampusServer/src/resources/migrations");
        String v001 = readRequired(migrations.resolve("V001_create_course_tables.sql"));
        String v002 = readRequired(migrations.resolve("V002_create_schedule_tables.sql"));
        String v003 = readRequired(migrations.resolve("V003_extend_course_management.sql"));

        verifyMigrationOwnership(v001, v002, v003);
        verifyCoreContracts(v001);
        verifyUidContract(v001, v003);
        verifyV003Contracts(v003);
        verifyUtcConfiguration(root);

        System.out.println("Course migration contract test passed.");
    }

    private static void verifyMigrationOwnership(String v001, String v002, String v003) {
        require(countCreate(v001, "course_offering") == 1, "V001 owns course_offering");
        require(countCreate(v001, "schedule_plan") == 0, "V001 must not own schedule tables");
        require(countCreate(v002, "schedule_plan") == 1, "V002 owns schedule tables");

        String[] coreTables = {
                "course", "course_major", "course_year", "course_offering",
                "course_offering_teacher", "enrollment", "grade"
        };
        for (String table : coreTables) {
            require(countCreate(v001, table) == 1, "V001 must create " + table + " exactly once");
            require(countCreate(v002, table) == 0, "V002 must not create " + table);
            require(countCreate(v003, table) == 0, "V003 must not create " + table);
        }

        String[] scheduleTables = {
                "teaching_calendar", "day_template", "period_definition", "calendar_date",
                "schedule_plan", "course_schedule_rule", "course_schedule_rule_week",
                "schedule_resource", "course_occurrence", "resource_booking",
                "schedule_conflict", "classroom", "course_offering_conflict"
        };
        for (String table : scheduleTables) {
            require(countCreate(v001, table) == 0, "V001 must not create " + table);
            require(countCreate(v002, table) == 1, "V002 must create " + table + " exactly once");
            require(countCreate(v003, table) == 0, "V003 must not create " + table);
        }

        String[] extensionTables = {
                "major", "student_academic_profile", "course_selection_window",
                "course_plan_item", "course_waitlist", "training_plan",
                "training_plan_group", "training_plan_course", "course_notice",
                "course_operation_log", "course_event_outbox"
        };
        for (String table : extensionTables) {
            require(countCreate(v001, table) == 0, "V001 must not create " + table);
            require(countCreate(v002, table) == 0, "V002 must not create " + table);
            require(countCreate(v003, table) == 1, "V003 must create " + table + " exactly once");
        }

        String conflict = tableDefinition(v002, "course_offering_conflict");
        require(count(conflict, "ON UPDATE RESTRICT") == 2,
                "offering-conflict foreign keys must both use ON UPDATE RESTRICT");

        require(!compact(v001 + v002 + v003).contains("USE `virtual_campus`"),
                "authoritative migrations must use the JDBC-selected schema");
    }

    private static void verifyCoreContracts(String v001) {
        String offering = tableDefinition(v001, "course_offering");
        requireContains(offering,
                "UNIQUE KEY `uk_course_offering_identity` (`offering_id`, `course_id`, `academic_year`, `semester`)",
                "offering composite identity required by enrollment");
        require(!offering.contains("`enroll_policy`"), "lottery policy is outside the active scope");

        String enrollment = tableDefinition(v001, "enrollment");
        requireContains(enrollment,
                "UNIQUE KEY `uk_enrollment_uid_offering` (`uid`, `offering_id`)",
                "one enrollment row per student and offering");
        requireContains(enrollment,
                "UNIQUE KEY `uk_enrollment_active_course` (`uid`, `academic_year`, `semester`, `active_course_id`)",
                "same-term same-course enrollment must be unique");
        requireContains(enrollment,
                "`active_course_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 2 THEN `course_id` ELSE NULL END) STORED",
                "active-course generated column required");
        requireContains(enrollment,
                "FOREIGN KEY (`offering_id`, `course_id`, `academic_year`, `semester`) REFERENCES `course_offering` (`offering_id`, `course_id`, `academic_year`, `semester`)",
                "enrollment term and course must match its offering");
        requireContains(enrollment, "CHECK (`status` IN (2, 3))",
                "enrollment status permits only ENROLLED and DROPPED");
    }

    private static void verifyUidContract(String v001, String v003) {
        requireContains(tableDefinition(v001, "course_offering_teacher"), "`uid` VARCHAR(32) NOT NULL",
                "teacher UID must match tbl_user.UID");
        requireContains(tableDefinition(v001, "enrollment"), "`uid` VARCHAR(32) NOT NULL",
                "student UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "student_academic_profile"), "`uid` VARCHAR(32) NOT NULL",
                "academic-profile UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "course_waitlist"), "`uid` VARCHAR(32) NOT NULL",
                "waitlist UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "course_plan_item"), "`uid` VARCHAR(32) NOT NULL",
                "plan UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "course_notice"), "`created_by` VARCHAR(32) NOT NULL",
                "notice creator UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "course_operation_log"), "`uid` VARCHAR(32) NOT NULL",
                "operation UID must match tbl_user.UID");
        requireContains(tableDefinition(v003, "course_event_outbox"), "`uid` VARCHAR(32) NOT NULL",
                "outbox UID must match tbl_user.UID");
    }

    private static void verifyV003Contracts(String v003) {
        String compact = compact(v003);
        String[] requiredContracts = {
                "UNIQUE KEY `uk_major_code` (`major_code`)",
                "UNIQUE KEY `uk_student_academic_profile_uid` (`uid`)",
                "UNIQUE KEY `uk_selection_window_term` (`academic_year`, `semester`)",
                "UNIQUE KEY `uk_course_plan_item_uid_offering` (`uid`, `offering_id`)",
                "UNIQUE KEY `uk_course_waitlist_uid_offering` (`uid`, `offering_id`)",
                "KEY `idx_course_waitlist_queue` (`offering_id`, `status`, `queue_time`, `waitlist_id`)",
                "UNIQUE KEY `uk_training_plan_identity` (`major_id`, `cohort_year`, `version`)",
                "UNIQUE KEY `uk_training_plan_group_name` (`plan_id`, `group_name`)",
                "PRIMARY KEY (`group_id`, `course_id`)",
                "KEY `idx_course_notice_offering_week` (`offering_id`, `week_no`, `status`)",
                "PRIMARY KEY (`uid`, `operation_id`)",
                "KEY `idx_course_event_outbox_delivery` (`uid`, `acked_at`, `event_id`)",
                "KEY `idx_course_major_major` (`major_id`, `course_id`)",
                "KEY `idx_course_year_year` (`year`, `course_id`)",
                "UNIQUE KEY `uk_teaching_calendar_term_version` (`academic_year`, `semester`, `version`)"
        };
        for (String contract : requiredContracts) {
            require(compact.contains(contract), "missing SQL contract: " + contract);
        }

        require(compact.contains("ADD COLUMN `academic_year` INT NULL"),
                "teaching_calendar.academic_year must be nullable");
        require(compact.contains("ADD COLUMN `semester` TINYINT NULL"),
                "teaching_calendar.semester must be nullable");
        require(compact.contains("`semester` IS NULL OR `semester` IN (1, 2, 3)"),
                "nullable teaching-calendar semester must be constrained to 1..3");
        require(compact.contains("`status` IN ('DRAFT', 'PUBLISHED')"),
                "training-plan and notice statuses must be constrained");
        require(count(compact, "`status` IN ('DRAFT', 'PUBLISHED')") >= 2,
                "both training-plan and notice statuses must be constrained");
        require(compact.contains("`notice_type` IN ('GENERAL', 'CANCELLED', 'RESCHEDULED')"),
                "notice type must be constrained");

        String selectionWindow = tableDefinition(v003, "course_selection_window");
        requireContains(selectionWindow, "`schedule_plan_id` BIGINT NOT NULL",
                "selection window must identify the published schedule plan");
        requireContains(selectionWindow,
                "FOREIGN KEY (`schedule_plan_id`) REFERENCES `schedule_plan` (`id`)",
                "selection window schedule-plan foreign key required");
        requireDatetime6(selectionWindow, "plan_open_at");
        requireDatetime6(selectionWindow, "plan_close_at");
        requireDatetime6(selectionWindow, "selection_open_at");
        requireDatetime6(selectionWindow, "selection_close_at");
        requireDatetime6(selectionWindow, "drop_deadline");

        String plan = tableDefinition(v003, "course_plan_item");
        requireContains(plan, "CHECK (`status` IN ('PLANNED', 'FULL'))",
                "plan status permits only PLANNED and FULL");

        String waitlist = tableDefinition(v003, "course_waitlist");
        requireContains(waitlist,
                "CHECK (`status` IN ('WAITING', 'OFFERED', 'CANCELLED', 'EXPIRED', 'ENROLLED'))",
                "waitlist status domain must match the approved lifecycle");
        requireDatetime6(waitlist, "queue_time");
        requireDatetime6(waitlist, "offered_at");
        requireDatetime6(waitlist, "expires_at");
        requireDatetime6(waitlist, "updated_at");

        String operation = tableDefinition(v003, "course_operation_log");
        requireDatetime6(operation, "created_at");
        requireDatetime6(operation, "completed_at");

        String outbox = tableDefinition(v003, "course_event_outbox");
        requireDatetime6(outbox, "created_at");
        requireDatetime6(outbox, "last_sent_at");
        requireDatetime6(outbox, "acked_at");

        String courseMajorAlter = statementContaining(v003, "ALTER TABLE `course_major`");
        requireContains(courseMajorAlter, "KEY `idx_course_major_major` (`major_id`, `course_id`)",
                "course_major query index required");
        requireContains(courseMajorAlter,
                "FOREIGN KEY (`major_id`) REFERENCES `major` (`major_id`)",
                "course_major major foreign key must be added after major exists");
    }

    private static void verifyUtcConfiguration(Path root) throws IOException {
        String example = compact(readRequired(root.resolve(
                "VCampusServer/src/resources/db.properties.example")));
        require(example.contains("serverTimezone=UTC"),
                "example JDBC URL must use UTC");

        String dbUtil = compact(readRequired(root.resolve(
                "VCampusServer/src/util/DBUtil.java")));
        require(dbUtil.contains("SET time_zone = '+00:00'"),
                "DBUtil must initialize every new connection to UTC");
        require(dbUtil.contains("connection.close()"),
                "DBUtil must close a connection when UTC setup fails");
    }

    private static void requireDatetime6(String tableDefinition, String column) {
        requireContains(tableDefinition, "`" + column + "` DATETIME(6)",
                column + " must preserve microseconds");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("Repository root containing VCampusServer/src/resources/init.sql was not found");
    }

    private static String readRequired(Path path) throws IOException {
        require(Files.isRegularFile(path), "Missing migration file: " + path.getFileName());
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int countCreate(String sql, String table) {
        int count = 0;
        Matcher matcher = CREATE_TABLE.matcher(sql);
        while (matcher.find()) {
            if (matcher.group(1).equalsIgnoreCase(table)) {
                count++;
            }
        }
        return count;
    }

    private static String tableDefinition(String sql, String table) {
        Pattern pattern = Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`"
                        + Pattern.quote(table) + "`.*?ENGINE\\s*=\\s*InnoDB.*?;");
        Matcher matcher = pattern.matcher(sql);
        require(matcher.find(), "Missing table definition: " + table);
        return compact(matcher.group());
    }

    private static String statementContaining(String sql, String marker) {
        for (String statement : sql.split(";")) {
            if (statement.contains(marker)) {
                return compact(statement);
            }
        }
        throw new AssertionError("Missing SQL statement containing: " + marker);
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static int count(String value, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static void requireContains(String value, String fragment, String message) {
        require(value.contains(fragment), message + ": " + fragment);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
