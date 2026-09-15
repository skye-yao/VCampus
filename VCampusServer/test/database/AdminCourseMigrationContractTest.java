package database;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class AdminCourseMigrationContractTest {
    public static void main(String[] args) throws Exception {
        String sql = Files.readString(Path.of(
                "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"),
                StandardCharsets.UTF_8);
        for (String token : List.of(
                "ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'",
                "ADD COLUMN `version` INT NOT NULL DEFAULT 1",
                "DROP CHECK `chk_course_offering_enrolled_count`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_arrangement`",
                "ADD COLUMN `arrangement_id` BIGINT",
                "ADD COLUMN `current_schedule_plan_id` BIGINT",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_request`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_target`",
                "CREATE TABLE IF NOT EXISTS `course_schedule_adjustment`",
                "CREATE TABLE IF NOT EXISTS `grade_submission`",
                "CREATE TABLE IF NOT EXISTS `grade_submission_item`",
                "CREATE TABLE IF NOT EXISTS `admin_course_operation_log`")) {
            require(sql.contains(token), "missing V004 contract: " + token);
        }
        require(sql.contains("CHECK (`status` IN ('ACTIVE', 'ARCHIVED'))"),
                "course archive states required");
        require(sql.contains("CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED'))"),
                "approval states required");
        require(sql.contains("UNIQUE KEY `uk_active_adjustment_occurrence`"),
                "one effective adjustment per occurrence required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
