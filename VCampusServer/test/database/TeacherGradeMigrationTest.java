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
 * V007 教师成绩草稿/方案/快照/审计迁移的唯一测试入口。
 *
 * <p>无参数运行时只校验迁移文件契约，不连接数据库。只有在 {@code args} 中出现
 * {@code mysql} 或 {@code --mysql} 时才在受保护的测试库上执行真实迁移，
 * 避免默认把 SKIP 当成 PASS。配置路径由 {@code --config=<path>} 传入。
 *
 * <p>真实库部分先造一批“V007 之前”的提交与明细，再执行两遍迁移：一遍证明新表/新列可用，
 * 一遍证明守卫让迁移可以安全重复执行；旧批次必须保持 NULL 快照与 INITIAL 类型，
 * 绝不为了可重算而回填虚构权重。
 */
public final class TeacherGradeMigrationTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String MIGRATION =
            "VCampusServer/src/resources/migrations/V007_teacher_gradebook.sql";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");
    /** 复合外键：草稿行必须用 (enrollment_id, offering_id) 引用 enrollment，不能只引用选课记录。 */
    private static final Pattern COMPOSITE_ENROLLMENT_FK = Pattern.compile(
            "(?is)FOREIGN\\s+KEY\\s*\\(\\s*`enrollment_id`\\s*,\\s*`offering_id`\\s*\\)\\s*"
                    + "REFERENCES\\s+`enrollment`\\s*\\(\\s*`enrollment_id`\\s*,\\s*`offering_id`\\s*\\)");
    /** 被复合外键引用的唯一键只能是新增的，V001 的 enrollment 没有它。 */
    private static final Pattern ENROLLMENT_CLASS_KEY = Pattern.compile(
            "(?is)ALTER\\s+TABLE\\s+`enrollment`\\s+ADD\\s+UNIQUE\\s+KEY\\s+`uk_enrollment_id_offering`"
                    + "\\s*\\(\\s*`enrollment_id`\\s*,\\s*`offering_id`\\s*\\)");
    /** 动态 SQL 里的字符串字面量会写成两个单引号，这里两种写法都接受。 */
    private static final Pattern SUBMISSION_KIND_DEFAULT = Pattern.compile(
            "(?is)`submission_kind`\\s+VARCHAR\\(16\\)\\s+NOT NULL\\s+DEFAULT\\s+''?INITIAL''?");
    private static final Pattern SUBMISSION_KIND_CHECK = Pattern.compile(
            "(?is)CHECK\\s*\\(\\s*`submission_kind`\\s+IN\\s*\\(\\s*''?INITIAL''?\\s*,"
                    + "\\s*''?RESUBMISSION''?\\s*,\\s*''?CORRECTION''?\\s*\\)\\s*\\)");

    /** V007 之前的提交，用来证明历史批次不会被回填虚构快照。 */
    private static final long HISTORICAL_SUBMISSION_ID = 880101L;
    private static final long HISTORICAL_ITEM_ID = 880201L;

    private TeacherGradeMigrationTest() {
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
        System.out.println("V007 static file contract passed.");

        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the live MySQL migration section "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        verifyMySqlMigration(root, config);
        System.out.println("V007 live MySQL migration passed in " + TEST_DATABASE + ".");
    }

    private static void verifyMigrationFileContract(Path root) throws Exception {
        Path file = root.resolve(MIGRATION);
        require(Files.isRegularFile(file), "Missing V007 migration: " + MIGRATION);
        String sql = Files.readString(file, StandardCharsets.UTF_8);

        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_grade_book`"),
                "teacher_grade_book table required");
        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_grade_draft_item`"),
                "teacher_grade_draft_item table required");
        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_grade_change_log`"),
                "teacher_grade_change_log table required");

        // 每班一份工作副本：主键就是 offering_id，revision 从 1 起（0 只属于虚拟草稿）。
        require(sql.contains("PRIMARY KEY (`offering_id`)"),
                "teacher_grade_book must be keyed by offering_id alone");
        require(sql.contains("`revision` INT NOT NULL")
                        && sql.contains("CHECK (`revision` > 0)"),
                "the working-copy revision must be a positive integer");
        require(sql.contains("`draft_open` TINYINT(1) NOT NULL"),
                "draft_open must be a NOT NULL flag");
        require(sql.contains("`draft_kind` VARCHAR(16) NOT NULL")
                        && sql.contains("CHECK (`draft_kind` IN ('INITIAL', 'RESUBMISSION', 'CORRECTION'))"),
                "draft_kind must be limited to INITIAL/RESUBMISSION/CORRECTION");
        require(sql.contains("`scheme_json` JSON NOT NULL"),
                "a working copy always carries its scheme snapshot");
        require(sql.contains("`correction_reason` VARCHAR(500) NULL"),
                "a normal working copy keeps its correction reason NULL");
        require(sql.contains("`updated_by` VARCHAR(32) NOT NULL")
                        && sql.contains("`updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"),
                "the working copy must record who changed it and when");
        require(sql.contains("`base_submission_id` BIGINT NULL")
                        && sql.contains("`last_submission_id` BIGINT NULL")
                        && countOccurrences(sql, "REFERENCES `grade_submission` (`submission_id`)") >= 2,
                "base_submission_id and last_submission_id must be nullable keys into grade_submission");

        // 草稿明细：主键 (offering_id, enrollment_id)，复合外键把行钉在本教学班的选课记录上。
        require(sql.contains("PRIMARY KEY (`offering_id`, `enrollment_id`)"),
                "teacher_grade_draft_item must be keyed by (offering_id, enrollment_id)");
        require(ENROLLMENT_CLASS_KEY.matcher(sql).find(),
                "V007 must add the (enrollment_id, offering_id) unique key that the composite "
                        + "foreign key references");
        require(COMPOSITE_ENROLLMENT_FK.matcher(sql).find(),
                "a draft row must reference enrollment by (enrollment_id, offering_id), so it can "
                        + "never be stored under another teaching class");
        for (String token : List.of("`daily_score` DECIMAL(5,2) NULL", "`midterm_score` DECIMAL(5,2) NULL",
                "`experiment_score` DECIMAL(5,2) NULL", "`finalterm_score` DECIMAL(5,2) NULL")) {
            require(sql.contains(token), "missing draft-item column contract: " + token);
        }
        for (String column : List.of("daily", "midterm", "experiment", "finalterm")) {
            require(sql.contains("CONSTRAINT `chk_teacher_grade_draft_item_" + column + "_score`"),
                    "a score range CHECK is required for the " + column + " draft score");
        }
        require(countOccurrences(sql, "IS NULL OR (`") >= 4,
                "every draft score CHECK must keep NULL legal and bound the score to 0..100");
        require(!sql.contains("DELETE FROM `enrollment`") && !sql.contains("DROP TABLE `enrollment`"),
                "the migration must never delete enrollment history");

        // 审计：权重改变记班级级日志（enrollment_id 为 NULL），分数改变记学生级日志。
        require(sql.contains("CREATE TABLE IF NOT EXISTS `teacher_grade_change_log`")
                        && sql.contains("`log_id` BIGINT NOT NULL AUTO_INCREMENT")
                        && sql.contains("`teacher_uid` VARCHAR(32) NOT NULL")
                        && sql.contains("`offering_id` BIGINT NOT NULL")
                        && sql.contains("`enrollment_id` BIGINT NULL")
                        && sql.contains("`operation_id` CHAR(36) NOT NULL")
                        && sql.contains("`book_revision` INT NOT NULL")
                        && sql.contains("`action` VARCHAR(32) NOT NULL")
                        && sql.contains("`before_json` JSON NULL")
                        && sql.contains("`after_json` JSON NULL")
                        && sql.contains("`reason` VARCHAR(500) NULL")
                        && sql.contains("`created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)"),
                "teacher_grade_change_log must record the full change audit contract");
        require(sql.contains("CHECK (`book_revision` > 0)"),
                "an audit row must belong to a positive working-copy revision");
        require(sql.contains("REFERENCES `tbl_user` (`UID`)")
                        && sql.contains("REFERENCES `course_offering` (`offering_id`)"),
                "the audit log must reference real teachers and offerings");

        // submission 快照扩充：全部可空，历史批次保持 NULL；submission_kind 默认 INITIAL。
        require(sql.contains("INFORMATION_SCHEMA.COLUMNS") || sql.contains("information_schema.COLUMNS"),
                "the ADD COLUMNs must be guarded by an information_schema existence check");
        require(sql.contains("FROM information_schema.STATISTICS"),
                "the enrollment unique key must be guarded by an information_schema existence check");
        require(sql.contains("`scheme_snapshot_json` JSON NULL")
                        && sql.contains("`roster_digest` CHAR(64) NULL")
                        && sql.contains("`base_submission_id` BIGINT NULL")
                        && sql.contains("`correction_reason` VARCHAR(500) NULL")
                        && sql.contains("`student_uid_snapshot` VARCHAR(32) NULL")
                        && sql.contains("`student_name_snapshot` VARCHAR(50) NULL"),
                "every new snapshot column must be nullable so historical batches stay readable");
        require(SUBMISSION_KIND_DEFAULT.matcher(sql).find(),
                "grade_submission.submission_kind must default to INITIAL");
        require(SUBMISSION_KIND_CHECK.matcher(sql).find(),
                "submission_kind must be limited to INITIAL/RESUBMISSION/CORRECTION");
        require(!sql.contains("AFTER `"),
                "new columns must be appended so the migration stays re-appliable");
        for (String unsupported : List.of(
                "ADD COLUMN IF NOT EXISTS", "ADD KEY IF NOT EXISTS",
                "ADD CONSTRAINT IF NOT EXISTS", "DROP CHECK IF EXISTS")) {
            require(!sql.contains(unsupported),
                    "this MySQL 8.0 rejects " + unsupported
                            + "; conditional DDL must be guarded by information_schema instead");
        }

        // 历史 NULL 方案不回填虚构权重：迁移里不允许任何写回。
        require(sql.contains("不回填虚构权重"),
                "the migration must state that a historical batch without a scheme snapshot keeps "
                        + "NULL instead of a fabricated weight snapshot");
        for (String forbidden : List.of(
                "UPDATE `grade_submission", "UPDATE `grade_submission_item", "INSERT INTO ",
                "DELETE FROM ", "TRUNCATE ", "DROP TABLE", "DROP COLUMN", "DROP CHECK")) {
            require(!sql.contains(forbidden),
                    "V007 only adds tables and columns but contains: " + forbidden);
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
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V006_teacher_adjustment_requests.sql"));

            // A batch submitted before V007: it has no weight snapshot and must stay that way.
            insertHistoricalBatch(connection);
            try {
                applyScript(connection, root.resolve(MIGRATION));
                // Re-applying proves the guarded columns and keys are idempotent, not only that a
                // fresh install works once.
                applyScript(connection, root.resolve(MIGRATION));

                verifyEnrollmentClassKey(connection);
                verifyBookTable(connection);
                verifyDraftItemTable(connection);
                verifyChangeLogTable(connection);
                verifySubmissionSnapshotColumns(connection);
                verifyHistoricalBatchKeepsNullSnapshots(connection);
            } finally {
                removeHistoricalBatch(connection);
            }
        }
    }

    private static void insertHistoricalBatch(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO grade_submission"
                + " (submission_id, offering_id, version, submitted_by, submitted_at, status,"
                + " average_score, max_score, min_score, failed_count, total_count)"
                + " VALUES (" + HISTORICAL_SUBMISSION_ID + ", 2001, 1, 'teacher-alpha',"
                + " '2026-09-10 00:00:00.123456', 'PENDING', 88.50, 88.50, 88.50, 0, 1)");
        execute(connection, "INSERT INTO grade_submission_item"
                + " (item_id, submission_id, enrollment_id, daily_score, score, grade_point)"
                + " VALUES (" + HISTORICAL_ITEM_ID + ", " + HISTORICAL_SUBMISSION_ID
                + ", 6001, 88.50, 88.50, 3.5)");
    }

    private static void removeHistoricalBatch(Connection connection) {
        try {
            execute(connection, "DELETE FROM grade_submission_item WHERE item_id = "
                    + HISTORICAL_ITEM_ID);
            execute(connection, "DELETE FROM grade_submission WHERE submission_id = "
                    + HISTORICAL_SUBMISSION_ID);
        } catch (SQLException cleanupFailure) {
            System.out.println("WARNING: migration fixtures were not cleaned up: "
                    + cleanupFailure.getMessage());
        }
    }

    /** V001 没有 (enrollment_id, offering_id) 唯一键，复合外键必须先把它补上。 */
    private static void verifyEnrollmentClassKey(Connection connection) throws SQLException {
        assertUniqueIndex(connection, "enrollment", "uk_enrollment_id_offering",
                "enrollment_id", "offering_id");
        require(queryInt(connection, "SELECT COUNT(*) FROM enrollment") == 2,
                "the legacy seed must provide enrollments for the composite foreign key");
    }

    private static void verifyBookTable(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_grade_book");
        assertIndexColumns(connection, "teacher_grade_book", "PRIMARY", "offering_id");
        assertColumn(connection, "teacher_grade_book", "revision", "int", null, null);
        assertColumn(connection, "teacher_grade_book", "draft_open", "tinyint", null, null);
        assertColumnNullable(connection, "teacher_grade_book", "draft_open", false);
        assertColumn(connection, "teacher_grade_book", "draft_kind", "varchar", 16L, null);
        assertColumnNullable(connection, "teacher_grade_book", "draft_kind", false);
        assertColumn(connection, "teacher_grade_book", "base_submission_id", "bigint", null, null);
        assertColumnNullable(connection, "teacher_grade_book", "base_submission_id", true);
        assertColumn(connection, "teacher_grade_book", "last_submission_id", "bigint", null, null);
        assertColumnNullable(connection, "teacher_grade_book", "last_submission_id", true);
        assertColumn(connection, "teacher_grade_book", "scheme_json", "json", null, null);
        assertColumn(connection, "teacher_grade_book", "correction_reason", "varchar", 500L, null);
        assertColumnNullable(connection, "teacher_grade_book", "correction_reason", true);
        assertColumn(connection, "teacher_grade_book", "updated_by", "varchar", 32L, null);
        assertDatetimePrecision(connection, "teacher_grade_book", "updated_at");
        assertForeignKey(connection, "teacher_grade_book", "fk_teacher_grade_book_offering",
                "course_offering", "CASCADE");
        assertForeignKey(connection, "teacher_grade_book", "fk_teacher_grade_book_updated_by",
                "tbl_user", "RESTRICT");
        assertForeignKey(connection, "teacher_grade_book", "fk_teacher_grade_book_base_submission",
                "grade_submission", "SET NULL");
        assertForeignKey(connection, "teacher_grade_book", "fk_teacher_grade_book_last_submission",
                "grade_submission", "SET NULL");
        assertCheckConstraint(connection, "teacher_grade_book", "chk_teacher_grade_book_revision",
                "`revision` > 0");
        assertCheckConstraint(connection, "teacher_grade_book", "chk_teacher_grade_book_draft_kind",
                "INITIAL", "RESUBMISSION", "CORRECTION");

        String insert = "INSERT INTO teacher_grade_book"
                + " (offering_id, revision, draft_open, draft_kind, scheme_json, updated_by) VALUES (";
        execute(connection, "SET autocommit = 0");
        try {
            execute(connection, insert + "2001, 1, 1, 'INITIAL', JSON_OBJECT('components',"
                    + " JSON_ARRAY()), 'teacher-alpha')");
            expectSqlRejected(connection, insert + "2001, 1, 1, 'INITIAL',"
                            + " JSON_OBJECT('components', JSON_ARRAY()), 'teacher-alpha')",
                    "23", "PRIMARY",
                    "a teaching class must have exactly one grade working copy");
            expectSqlRejected(connection, insert + "2002, 0, 1, 'INITIAL',"
                            + " JSON_OBJECT('components', JSON_ARRAY()), 'teacher-beta')",
                    "HY", "chk_teacher_grade_book_revision",
                    "a working copy revision must be positive, 0 is only the virtual draft");
            expectSqlRejected(connection, insert + "2002, 1, 1, 'DRAFT',"
                            + " JSON_OBJECT('components', JSON_ARRAY()), 'teacher-beta')",
                    "HY", "chk_teacher_grade_book_draft_kind",
                    "an unknown draft kind must be rejected");
            expectSqlRejected(connection, insert + "2002, 1, 1, 'INITIAL',"
                            + " JSON_OBJECT('components', JSON_ARRAY()), 'missingteacher01')",
                    "23", "fk_teacher_grade_book_updated_by",
                    "a working copy must be owned by a real teacher");
            expectSqlRejected(connection, "INSERT INTO teacher_grade_book (offering_id, revision,"
                            + " draft_open, draft_kind, last_submission_id, scheme_json, updated_by)"
                            + " VALUES (2002, 1, 1, 'INITIAL', 999999,"
                            + " JSON_OBJECT('components', JSON_ARRAY()), 'teacher-beta')",
                    "23", "fk_teacher_grade_book_last_submission",
                    "last_submission_id must point at a real submission");
            execute(connection, "INSERT INTO teacher_grade_book (offering_id, revision, draft_open,"
                    + " draft_kind, base_submission_id, last_submission_id, scheme_json,"
                    + " correction_reason, updated_by) VALUES (2002, 2, 1, 'CORRECTION', "
                    + HISTORICAL_SUBMISSION_ID + ", " + HISTORICAL_SUBMISSION_ID
                    + ", JSON_OBJECT('components', JSON_ARRAY()), '学生申诉复核', 'teacher-beta')");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_book"
                            + " WHERE offering_id = 2002 AND draft_kind = 'CORRECTION'"
                            + " AND base_submission_id = " + HISTORICAL_SUBMISSION_ID
                            + " AND correction_reason = '学生申诉复核'") == 1,
                    "a correction draft must be able to keep its base batch and reason");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
    }

    private static void verifyDraftItemTable(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_grade_draft_item");
        assertIndexColumns(connection, "teacher_grade_draft_item", "PRIMARY",
                "offering_id", "enrollment_id");
        for (String column : List.of("daily_score", "midterm_score", "experiment_score",
                "finalterm_score")) {
            assertDecimal(connection, "teacher_grade_draft_item", column, 5, 2);
            assertColumnNullable(connection, "teacher_grade_draft_item", column, true);
        }
        assertForeignKey(connection, "teacher_grade_draft_item",
                "fk_teacher_grade_draft_item_enrollment", "enrollment", "RESTRICT");
        assertForeignKeyColumns(connection, "teacher_grade_draft_item",
                "fk_teacher_grade_draft_item_enrollment", "enrollment",
                "enrollment_id", "offering_id");
        assertCheckConstraint(connection, "teacher_grade_draft_item",
                "chk_teacher_grade_draft_item_daily_score", "daily_score", "between 0 and 100");
        assertCheckConstraint(connection, "teacher_grade_draft_item",
                "chk_teacher_grade_draft_item_midterm_score", "midterm_score", "between 0 and 100");
        assertCheckConstraint(connection, "teacher_grade_draft_item",
                "chk_teacher_grade_draft_item_experiment_score", "experiment_score",
                "between 0 and 100");
        assertCheckConstraint(connection, "teacher_grade_draft_item",
                "chk_teacher_grade_draft_item_finalterm_score", "finalterm_score",
                "between 0 and 100");

        String insert = "INSERT INTO teacher_grade_draft_item"
                + " (offering_id, enrollment_id, daily_score) VALUES (";
        execute(connection, "SET autocommit = 0");
        try {
            // 越界由 CHECK 拒绝，先跑拒绝用例再插入合法行。
            expectSqlRejected(connection, insert + "2001, 6001, 100.01)",
                    "HY", "chk_teacher_grade_draft_item_daily_score",
                    "a score above 100 must be rejected");
            expectSqlRejected(connection, insert + "2001, 6001, -0.01)",
                    "HY", "chk_teacher_grade_draft_item_daily_score",
                    "a negative score must be rejected");

            // 6001 属于教学班 2001，6002 属于 2004（见 seed-course-test.sql）：跨班与伪造 ID 都行不通。
            expectSqlRejected(connection, insert + "2004, 6001, 88.50)",
                    "23", "fk_teacher_grade_draft_item_enrollment",
                    "a draft row must not be stored under another teaching class");
            expectSqlRejected(connection, insert + "2001, 6002, 88.50)",
                    "23", "fk_teacher_grade_draft_item_enrollment",
                    "an enrollment from another teaching class must be rejected");
            expectSqlRejected(connection, insert + "2001, 999999, 88.50)",
                    "23", "fk_teacher_grade_draft_item_enrollment",
                    "a draft row must reference a real enrollment");

            // 0.00 是合法草稿值，未写入的项保持 NULL：空分数与零分必须是两件事。
            execute(connection, insert + "2001, 6001, 0.00)");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_draft_item"
                            + " WHERE offering_id = 2001 AND enrollment_id = 6001"
                            + " AND daily_score = 0.00 AND midterm_score IS NULL"
                            + " AND experiment_score IS NULL AND finalterm_score IS NULL") == 1,
                    "0.00 must stay a legal draft score and an unwritten score must stay NULL");

            // 三位小数落不进 DECIMAL(5,2)：列把值舍入到两位，库里不会出现 88.555。
            execute(connection, "UPDATE teacher_grade_draft_item SET midterm_score = 88.555"
                    + " WHERE offering_id = 2001 AND enrollment_id = 6001");
            require("88.56".equals(queryString(connection, "SELECT CAST(midterm_score AS CHAR)"
                            + " FROM teacher_grade_draft_item"
                            + " WHERE offering_id = 2001 AND enrollment_id = 6001")),
                    "a draft score column must keep at most two decimals");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
    }

    private static void verifyChangeLogTable(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_grade_change_log");
        assertIndexColumns(connection, "teacher_grade_change_log", "PRIMARY", "log_id");
        assertColumn(connection, "teacher_grade_change_log", "teacher_uid", "varchar", 32L, null);
        assertColumn(connection, "teacher_grade_change_log", "offering_id", "bigint", null, null);
        assertColumn(connection, "teacher_grade_change_log", "enrollment_id", "bigint", null, null);
        assertColumnNullable(connection, "teacher_grade_change_log", "enrollment_id", true);
        assertColumn(connection, "teacher_grade_change_log", "operation_id", "char", 36L, null);
        assertColumn(connection, "teacher_grade_change_log", "book_revision", "int", null, null);
        assertColumn(connection, "teacher_grade_change_log", "action", "varchar", 32L, null);
        assertColumn(connection, "teacher_grade_change_log", "before_json", "json", null, null);
        assertColumn(connection, "teacher_grade_change_log", "after_json", "json", null, null);
        assertColumn(connection, "teacher_grade_change_log", "reason", "varchar", 500L, null);
        assertDatetimePrecision(connection, "teacher_grade_change_log", "created_at");
        assertForeignKey(connection, "teacher_grade_change_log", "fk_teacher_grade_change_log_teacher",
                "tbl_user", "RESTRICT");
        assertForeignKey(connection, "teacher_grade_change_log", "fk_teacher_grade_change_log_offering",
                "course_offering", "CASCADE");
        assertForeignKey(connection, "teacher_grade_change_log", "fk_teacher_grade_change_log_enrollment",
                "enrollment", "RESTRICT");
        assertCheckConstraint(connection, "teacher_grade_change_log",
                "chk_teacher_grade_change_log_revision", "book_revision", "> 0");
        assertCheckConstraint(connection, "teacher_grade_change_log",
                "chk_teacher_grade_change_log_action", "action", "<>");

        String insert = "INSERT INTO teacher_grade_change_log (teacher_uid, offering_id,"
                + " enrollment_id, operation_id, book_revision, action, before_json, after_json)"
                + " VALUES (";
        execute(connection, "SET autocommit = 0");
        try {
            // 权重改变是班级级日志：enrollment_id 为 NULL。
            execute(connection, insert + "'teacher-alpha', 2001, NULL,"
                    + " '00000000-0000-0000-0000-0000000000B1', 1, 'SCHEME_UPDATED',"
                    + " JSON_OBJECT('weightBasisPoints', 3000), JSON_OBJECT('weightBasisPoints', 4000))");
            // 分数改变是学生级日志：带上 enrollment_id 与旧/新总评。
            execute(connection, insert + "'teacher-alpha', 2001, 6001,"
                    + " '00000000-0000-0000-0000-0000000000B2', 2, 'SCORES_SAVED',"
                    + " JSON_OBJECT('totalScore', 85.00), JSON_OBJECT('totalScore', 87.00))");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_change_log"
                            + " WHERE offering_id = 2001 AND enrollment_id IS NULL") == 1
                            && queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_change_log"
                            + " WHERE offering_id = 2001 AND enrollment_id = 6001") == 1,
                    "class-level and student-level changes must both be storable");
            expectSqlRejected(connection, insert + "'teacher-alpha', 2001, 6001,"
                            + " '00000000-0000-0000-0000-0000000000B3', 0, 'SCORES_SAVED',"
                            + " JSON_OBJECT(), JSON_OBJECT())",
                    "HY", "chk_teacher_grade_change_log_revision",
                    "an audit row must name a positive working-copy revision");
            expectSqlRejected(connection, insert + "'teacher-alpha', 2001, 6001,"
                            + " '00000000-0000-0000-0000-0000000000B4', 1, '',"
                            + " JSON_OBJECT(), JSON_OBJECT())",
                    "HY", "chk_teacher_grade_change_log_action",
                    "an empty audit action must be rejected");
            expectSqlRejected(connection, insert + "'teacher-alpha', 2001, 999999,"
                            + " '00000000-0000-0000-0000-0000000000B5', 1, 'SCORES_SAVED',"
                            + " JSON_OBJECT(), JSON_OBJECT())",
                    "23", "fk_teacher_grade_change_log_enrollment",
                    "an audit row must reference a real enrollment");
            expectSqlRejected(connection, insert + "'missingteacher01', 2001, 6001,"
                            + " '00000000-0000-0000-0000-0000000000B6', 1, 'SCORES_SAVED',"
                            + " JSON_OBJECT(), JSON_OBJECT())",
                    "23", "fk_teacher_grade_change_log_teacher",
                    "an audit row must reference a real teacher");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
    }

    private static void verifySubmissionSnapshotColumns(Connection connection) throws SQLException {
        assertColumn(connection, "grade_submission", "scheme_snapshot_json", "json", null, null);
        assertColumnNullable(connection, "grade_submission", "scheme_snapshot_json", true);
        assertColumn(connection, "grade_submission", "roster_digest", "char", 64L, null);
        assertColumnNullable(connection, "grade_submission", "roster_digest", true);
        assertColumn(connection, "grade_submission", "base_submission_id", "bigint", null, null);
        assertColumnNullable(connection, "grade_submission", "base_submission_id", true);
        assertColumn(connection, "grade_submission", "correction_reason", "varchar", 500L, null);
        assertColumnNullable(connection, "grade_submission", "correction_reason", true);
        assertColumn(connection, "grade_submission", "submission_kind", "varchar", 16L, null);
        assertColumnNullable(connection, "grade_submission", "submission_kind", false);
        assertForeignKey(connection, "grade_submission", "fk_grade_submission_base",
                "grade_submission", "SET NULL");
        assertCheckConstraint(connection, "grade_submission", "chk_grade_submission_kind",
                "INITIAL", "RESUBMISSION", "CORRECTION");

        assertColumn(connection, "grade_submission_item", "student_uid_snapshot", "varchar", 32L, null);
        assertColumnNullable(connection, "grade_submission_item", "student_uid_snapshot", true);
        assertColumn(connection, "grade_submission_item", "student_name_snapshot", "varchar", 50L, null);
        assertColumnNullable(connection, "grade_submission_item", "student_name_snapshot", true);

        String insert = "INSERT INTO grade_submission (submission_id, offering_id, version,"
                + " submitted_by, status, submission_kind, scheme_snapshot_json, roster_digest,"
                + " base_submission_id, correction_reason) VALUES (";
        execute(connection, "SET autocommit = 0");
        try {
            execute(connection, insert + "880102, 2004, 1, 'teacher-alpha', 'PENDING', 'CORRECTION',"
                    + " JSON_OBJECT('components', JSON_ARRAY()), REPEAT('b', 64), "
                    + HISTORICAL_SUBMISSION_ID + ", '学生申诉复核')");
            expectSqlRejected(connection, insert + "880103, 2002, 1, 'teacher-beta', 'PENDING',"
                            + " 'SUBMITTED', JSON_OBJECT('components', JSON_ARRAY()),"
                            + " REPEAT('c', 64), NULL, NULL)",
                    "HY", "chk_grade_submission_kind",
                    "the GUI-only SUBMITTED state must not become a database submission kind");
            expectSqlRejected(connection, insert + "880104, 2002, 1, 'teacher-beta', 'PENDING',"
                            + " 'INITIAL', JSON_OBJECT('components', JSON_ARRAY()),"
                            + " REPEAT('d', 64), 999999, NULL)",
                    "23", "fk_grade_submission_base",
                    "a correction batch must reference a real base submission");
            execute(connection, "INSERT INTO grade_submission_item (item_id, submission_id,"
                    + " enrollment_id, daily_score, score, student_uid_snapshot,"
                    + " student_name_snapshot) VALUES (880202, 880102, 6002, 91.25, 91.25,"
                    + " '00005678', '张三')");
            require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission_item"
                            + " WHERE item_id = 880202 AND student_uid_snapshot = '00005678'"
                            + " AND student_name_snapshot = '张三'") == 1,
                    "a new batch must be able to capture the student identity at submit time");
            require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission"
                            + " WHERE submission_id = 880102 AND submission_kind = 'CORRECTION'"
                            + " AND scheme_snapshot_json IS NOT NULL"
                            + " AND base_submission_id = " + HISTORICAL_SUBMISSION_ID) == 1,
                    "a new batch must be able to store its scheme snapshot and base batch");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
    }

    /**
     * 历史批次在 V007 之前没有权重快照，升级后必须保持 NULL 与 INITIAL：
     * 不能为了“可重算”补一份当时的方案，也不能把旧提交标成更正或重提。
     */
    private static void verifyHistoricalBatchKeepsNullSnapshots(Connection connection)
            throws SQLException {
        require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission"
                        + " WHERE submission_id = " + HISTORICAL_SUBMISSION_ID
                        + " AND scheme_snapshot_json IS NULL AND roster_digest IS NULL"
                        + " AND base_submission_id IS NULL AND correction_reason IS NULL"
                        + " AND submission_kind = 'INITIAL'") == 1,
                "a batch submitted before V007 must keep NULL snapshots and the INITIAL kind");
        require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission_item"
                        + " WHERE item_id = " + HISTORICAL_ITEM_ID
                        + " AND student_uid_snapshot IS NULL"
                        + " AND student_name_snapshot IS NULL") == 1,
                "historical items must keep NULL identity snapshots and fall back to the legacy join");
    }

    private static void assertTableExists(Connection connection, String table) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES"
                + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND TABLE_TYPE = 'BASE TABLE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getInt(1) == 1, "Missing table " + table);
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
                        "Unexpected type for " + table + "." + column + ": " + result.getString(1));
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

    private static void assertDecimal(Connection connection, String table, String column,
                                      int precision, int scale) throws SQLException {
        String sql = "SELECT DATA_TYPE, NUMERIC_PRECISION, NUMERIC_SCALE "
                + "FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                require("decimal".equalsIgnoreCase(result.getString(1))
                                && precision == result.getInt(2) && scale == result.getInt(3),
                        "Unexpected decimal shape for " + table + "." + column);
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
        List<String> actual = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? "
                + "ORDER BY SEQ_IN_INDEX";
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

    private static void assertUniqueIndex(Connection connection, String table, String index,
                                          String... expected) throws SQLException {
        assertIndexColumns(connection, table, index, expected);
        String sql = "SELECT COUNT(*) FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? "
                + "AND NON_UNIQUE = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next() && result.getInt(1) == 0,
                        table + "." + index + " must be a UNIQUE key, not a plain index");
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

    /** 复合外键必须按声明顺序引用父表的同名列，顺序错了就不是“跨班防护”。 */
    private static void assertForeignKeyColumns(Connection connection, String table,
                                                String constraint, String referencedTable,
                                                String... expectedColumns) throws SQLException {
        List<String> actual = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME"
                + " FROM information_schema.KEY_COLUMN_USAGE"
                + " WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                + " AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1) + "->" + result.getString(2) + "."
                            + result.getString(3));
                }
            }
        }
        List<String> expected = new ArrayList<>();
        for (String column : expectedColumns) {
            expected.add(column + "->" + referencedTable + "." + column);
        }
        require(actual.equals(expected),
                "Unexpected columns for foreign key " + table + "." + constraint + ": " + actual);
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

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
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
