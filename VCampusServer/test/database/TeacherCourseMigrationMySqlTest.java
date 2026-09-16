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
 * 教师端课程模块的<b>完整迁移链</b>验收（任务六 T4）：唯一一处把七个版本按文档顺序跑完的测试。
 *
 * <p>只查文件的那一半不连数据库；只有在 {@code args} 里出现 {@code mysql}/{@code --mysql} 时才动
 * 真实的受保护测试库，所以未传开关时打印 SKIP 而不是假装通过。脚本的 MySQL 列会把本类登记为
 * MySQL 门控测试，没传 {@code -WithMySql} 时套件在选择阶段就报错，SKIP 永远不会被当成通过。
 *
 * <p>两条安装路径，正是文档要求的两条：
 *
 * <ol>
 *   <li><b>全新安装</b>：{@code tbl_user}（取自 init.sql 的权威定义）→ V001 → V002 → V003 →
 *       <b>旧 seed</b> → V004 回填 → V005 → V006 → V007，每一步之后都断言上一步的产物真的到位。
 *       seed 必须在 V004 <em>之前</em>：V004 先给 {@code course_schedule_rule} 加一个可空的
 *       {@code arrangement_id}，再把旧规则逐条迁成一个 arrangement 并回填、最后收紧为 NOT NULL——
 *       把 seed 放在 V004 之后，回填的那一步就无事可做，「升级路径」也就没有被验证过。</li>
 *   <li><b>既有 V004 数据升级</b>：先停在 V004（含 legacy seed），再写入一批“V004 时代”的真实业务
 *       数据（教学班、选课、提交批次、V004 级的 arrangement/rule/occurrence），然后才跑
 *       V005/V006/V007，逐条断言老数据一字未动、新结构能引用它们、V006 的 WITHDRAWN 状态字母表
 *       与 V007 的可空快照列都按契约生效。最后把三个迁移各再执行一遍，证明守卫让它们可以重复运行。</li>
 * </ol>
 *
 * <p>第二条路径跑完会<b>再</b>做一次全新安装，所以本类结束时受保护的测试库回到标准安装状态
 * （tbl_user + V001..V007 + seed），后续套件与下一次运行拿到的是同一个基线；即使中途断言失败，
 * 这条收尾在 {@code finally} 里照样执行。
 *
 * <p>夹具用 987xxx 区间与 {@code tcm987-} UID 前缀，与已占用的 947xxx/948xxx/949xxx/965xxx/
 * 966xxx/983xxx/984xxx/985xxx 以及 {@code gra974-} 都不相交。
 */
public final class TeacherCourseMigrationMySqlTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";

    private static final String INIT_SQL = "VCampusServer/src/resources/init.sql";
    private static final String SEED_SQL = "VCampusServer/src/resources/seed-course-test.sql";
    private static final String V001 = "VCampusServer/src/resources/migrations/V001_create_course_tables.sql";
    private static final String V002 = "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql";
    private static final String V003 = "VCampusServer/src/resources/migrations/V003_extend_course_management.sql";
    private static final String V004 = "VCampusServer/src/resources/migrations/V004_admin_course_management.sql";
    private static final String V005 = "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql";
    private static final String V006 = "VCampusServer/src/resources/migrations/V006_teacher_adjustment_requests.sql";
    private static final String V007 = "VCampusServer/src/resources/migrations/V007_teacher_gradebook.sql";

    /** V007 之前的提交与明细：升级到 V007 后必须保持 NULL 快照与 INITIAL 类型。 */
    private static final long SUBMISSION = 987401L;
    private static final long SUBMISSION_ITEM = 987402L;
    private static final long ENROLLMENT_KEPT = 987301L;
    private static final long ENROLLMENT_KEPT_SECOND = 987302L;
    private static final long ENROLLMENT_DROPPED = 987303L;
    private static final long COURSE = 987101L;
    private static final long OFFERING = 987201L;
    private static final long ARRANGEMENT = 987601L;
    private static final long RULE = 987701L;
    private static final long OCCURRENCE = 987801L;
    /** 旧 seed 里的教室与教学日：V004 时代的数据可以直接引用它们。 */
    private static final long SEEDED_CLASSROOM = 4301L;
    private static final long SEEDED_CALENDAR_DATE = 3301L;

    private static final String ADMIN = "tcm987-admin";
    private static final String TEACHER = "tcm987-teacher";
    private static final String STUDENT = "tcm987-student";
    private static final String STUDENT_DROPPED = "tcm987-student-dropped";
    private static final String SEEDED_PASSWORD_HASH =
            "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String SEEDED_PASSWORD_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";

    /** init.sql 里的权威 tbl_user 定义；本链只从 init.sql 取这一张表。 */
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private TeacherCourseMigrationMySqlTest() {
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
        System.out.println("Teacher course migration file contract passed (V001..V007 present).");

        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the live migration chain was not run"
                    + " and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        require(Files.isRegularFile(config), "Missing ignored local db.properties: " + config);
        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String jdbc = withTestAuthentication(url);
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");
        ensureTestSchema(jdbc, properties);

        try {
            // 先跑升级路径，再跑全新安装：全新安装最后执行，库里留下的就是标准基线。
            verifyUpgradeOfExistingV004Data(jdbc, username, password, root);
            System.out.println("Teacher course migration chain: existing V004 data upgraded by"
                    + " V005/V006/V007.");
        } finally {
            installFresh(jdbc, username, password, root);
            System.out.println("Teacher course migration chain: test schema restored to a fresh"
                    + " install (tbl_user -> V001/V002/V003 -> seed -> V004 -> V005/V006/V007).");
        }
        System.out.println("Teacher course migration chain passed in " + TEST_DATABASE + ".");
    }

    // ------------------------------------------------------------------ 文件契约

    /**
     * 不连库的那一半：七个迁移都在、编号连续，且 V006 确实带上了任务三依赖的 WITHDRAWN 状态字母表、
     * V005 确实建立了「我的申请」已读回执表。控制台缺文件时这里先失败，不会走到连库分支。
     */
    private static void verifyMigrationFileContract(Path root) throws Exception {
        for (String relative : List.of(V001, V002, V003, V004, V005, V006, V007)) {
            require(Files.isRegularFile(root.resolve(relative)),
                    "Missing migration file required by the documented order: " + relative);
        }
        String v005 = read(root, V005);
        require(v005.contains("CREATE TABLE IF NOT EXISTS `teacher_application_read`"),
                "V005 must create the read-receipt table the merged application list depends on");

        String v006 = read(root, V006);
        require(v006.contains("WITHDRAWN"),
                "V006 must introduce the WITHDRAWN adjustment status the teacher status alphabet"
                        + " depends on");
        require(v006.contains("`withdrawn_at` DATETIME(6) NULL"),
                "V006 must add the nullable withdrawn_at column for the fourth status");
        require(v006.contains("`target_calendar_date_id` BIGINT NULL"),
                "V006 must add the cross-week target calendar date as a nullable column so"
                        + " historical target rows stay readable");

        String v007 = read(root, V007);
        require(v007.contains("`scheme_snapshot_json` JSON NULL")
                        && v007.contains("`submission_kind` VARCHAR(16) NOT NULL"),
                "V007 must add the nullable scheme snapshot and the submission kind");
    }

    // ------------------------------------------------------------------ 路径二：既有 V004 数据升级

    /**
     * 停在 V004 写入真实业务数据，再升级到 V007，逐条验证老数据与新增结构的关系。
     */
    private static void verifyUpgradeOfExistingV004Data(String jdbc, String user, String pass,
            Path root) throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            requireTestDatabaseOn(connection);
            resetTestSchema(connection);

            // 文档顺序的前半段：tbl_user → V001/V002/V003 → 旧 seed → V004。
            applyTblUser(connection, root.resolve(INIT_SQL));
            applyScript(connection, root.resolve(V001), true);
            applyScript(connection, root.resolve(V002), true);
            applyScript(connection, root.resolve(V003), true);
            applyScript(connection, root.resolve(SEED_SQL), false);
            // V004 之前 arrangement_id 还不存在：seed 走的正是那条「旧列集」分支。
            require(queryInt(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                            + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_schedule_rule'"
                            + " AND COLUMN_NAME = 'arrangement_id'") == 0,
                    "the seed must load before V004, otherwise the V004 backfill is never exercised");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_rule WHERE id BETWEEN"
                            + " 4101 AND 4104") == 4,
                    "the legacy seed must provide its four V003-era scheduling rules");

            applyScript(connection, root.resolve(V004), true);
            verifyV004Backfill(connection);

            insertV004EraData(connection);
            String before = upgradeSnapshot(connection);

            // 升级：V005 → V006 → V007，再做一遍证明守卫可重复执行。
            applyScript(connection, root.resolve(V005), true);
            applyScript(connection, root.resolve(V006), true);
            applyScript(connection, root.resolve(V007), true);
            verifyV005Structures(connection);
            verifyV006StatusAlphabet(connection);
            verifyV007SnapshotColumns(connection);
            verifyV004EraDataSurvives(connection, before);
            verifyNewTablesAcceptV004EraRows(connection);

            applyScript(connection, root.resolve(V005), true);
            applyScript(connection, root.resolve(V006), true);
            applyScript(connection, root.resolve(V007), true);
            require(before.equals(upgradeSnapshot(connection)),
                    "re-applying V005/V006/V007 must not rewrite a single V004-era row");
            System.out.println("[migration] V004-era data upgraded by V005/V006/V007 and left"
                    + " byte-identical across a re-apply");
        }
    }

    /**
     * V004 的回填必须真的发生过：每条旧 rule 迁成一个 arrangement 并按 id 回填，
     * 之后 {@code arrangement_id} 收紧为 NOT NULL 且带复合外键。
     */
    private static void verifyV004Backfill(Connection connection) throws SQLException {
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_rule WHERE"
                        + " arrangement_id IS NULL") == 0,
                "V004 must backfill every legacy rule's arrangement_id, not leave NULLs");
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_rule r JOIN"
                        + " course_schedule_arrangement a ON a.arrangement_id = r.arrangement_id"
                        + " AND a.plan_id = r.plan_id AND a.offering_id = r.course_offering_id") == 4,
                "V004 must migrate each legacy rule into its own arrangement row");
        require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_rule WHERE id BETWEEN"
                        + " 4101 AND 4104 AND arrangement_id = id") == 4,
                "the backfill is one arrangement per rule, keyed by the rule id");
        assertColumnNullable(connection, "course_schedule_rule", "arrangement_id", false);
    }

    /**
     * 一批“V004 时代”的数据：教学班与选课、一条已提交的批次与明细、V004 级的
     * arrangement/rule/occurrence，外加一条引用了教学日历日期的目标行。
     */
    private static void insertV004EraData(Connection connection) throws SQLException {
        execute(connection, "INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                + " VALUES('" + ADMIN + "','TCM987 Admin','" + SEEDED_PASSWORD_HASH + "','"
                + SEEDED_PASSWORD_SALT + "',0,'Migration College','Registrar'),('" + TEACHER
                + "','TCM987 Teacher','" + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                + "',1,'Migration College','Professor'),('" + STUDENT + "','TCM987 Student','"
                + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                + "',2,'Migration College','CS'),('" + STUDENT_DROPPED + "','TCM987 Dropped','"
                + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                + "',2,'Migration College','CS')");
        execute(connection, "INSERT INTO course(course_id,course_code,course_name,credit,"
                + "credit_hours,course_type,status) VALUES (" + COURSE + ",'TCM987','TCM987"
                + " Migration Course',3.00,48,1,'ACTIVE')");
        execute(connection, "INSERT INTO course_offering(offering_id,offering_code,course_id,"
                + "academic_year,semester,capacity,enrolled_count,status) VALUES (" + OFFERING
                + ",'TCM987-2027-1-A'," + COURSE + ",2027,1,40,2,2)");
        execute(connection, "INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES ("
                + OFFERING + ",'" + TEACHER + "',0)");
        // uk_enrollment_uid_offering 是 (uid, offering_id)：同一名学生在同一教学班只有一行历史，
        // 所以「已退课」的那一行必须挂在另一名学生上。
        execute(connection, "INSERT INTO enrollment(enrollment_id,offering_id,course_id,"
                + "academic_year,semester,uid,status,select_time,drop_time) VALUES ("
                + ENROLLMENT_KEPT + "," + OFFERING + "," + COURSE + ",2027,1,'" + STUDENT
                + "',2,'2027-02-01 00:00:00',NULL),(" + ENROLLMENT_KEPT_SECOND + "," + OFFERING
                + "," + COURSE + ",2027,1,'" + STUDENT_DROPPED + "',3,'2027-02-01 00:00:00',"
                + "'2027-03-01 00:00:00')");
        // V004 就有的提交批次：没有快照，也没有 INITIAL 之外的类型信息。
        execute(connection, "INSERT INTO grade_submission(submission_id,offering_id,version,"
                + "submitted_by,submitted_at,status,average_score,max_score,min_score,"
                + "failed_count,total_count) VALUES (" + SUBMISSION + "," + OFFERING + ",1,'"
                + TEACHER + "','2027-03-10 00:00:00','PENDING',88.50,88.50,88.50,0,1)");
        execute(connection, "INSERT INTO grade_submission_item(item_id,submission_id,"
                + "enrollment_id,daily_score,score,grade_point) VALUES (" + SUBMISSION_ITEM + ","
                + SUBMISSION + "," + ENROLLMENT_KEPT + ",88.50,88.50,3.5)");
        // V004 时代的排课：直接写自己的 arrangement / rule / occurrence（rule 带 arrangement_id，
        // 这正是 V004 之后才可能出现的形状）。
        execute(connection, "INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,"
                + "offering_id,teacher_uid,classroom_id,status,version) VALUES (" + ARRANGEMENT
                + ",4001," + OFFERING + ",'" + TEACHER + "'," + SEEDED_CLASSROOM + ",'ACTIVE',1)");
        execute(connection, "INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,"
                + "arrangement_id,weekday,start_period,end_period,status) VALUES (" + RULE
                + ",4001," + OFFERING + "," + ARRANGEMENT + ",3,3,4,'ACTIVE')");
        execute(connection, "INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES ("
                + RULE + ",2)");
        execute(connection, "INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,"
                + "week_no,teaching_weekday) VALUES (" + OCCURRENCE + "," + RULE + ",4001,"
                + "'2027-03-01 00:00:00','2027-03-01 01:35:00',2,3)");
    }

    /** 老数据的规范文本：升级前后逐字比较。 */
    private static String upgradeSnapshot(Connection connection) throws SQLException {
        StringBuilder text = new StringBuilder();
        text.append(row(connection, "SELECT CONCAT(course_id,'|',course_code,'|',credit,'|',status,"
                + "'|',version) FROM course WHERE course_id=" + COURSE));
        text.append(row(connection, "SELECT CONCAT(offering_id,'|',offering_code,'|',capacity,'|',"
                + "enrolled_count,'|',status,'|',version) FROM course_offering WHERE offering_id="
                + OFFERING));
        text.append(rows(connection, "SELECT CONCAT(enrollment_id,'|',uid,'|',status,'|',"
                + "select_time,'|',COALESCE(drop_time,'-')) FROM enrollment WHERE offering_id="
                + OFFERING + " ORDER BY enrollment_id"));
        text.append(row(connection, "SELECT CONCAT(submission_id,'|',version,'|',status,'|',"
                + "average_score,'|',total_count) FROM grade_submission WHERE submission_id="
                + SUBMISSION));
        text.append(row(connection, "SELECT CONCAT(item_id,'|',enrollment_id,'|',daily_score,'|',"
                + "score,'|',grade_point) FROM grade_submission_item WHERE item_id="
                + SUBMISSION_ITEM));
        text.append(row(connection, "SELECT CONCAT(arrangement_id,'|',plan_id,'|',offering_id,'|',"
                + "teacher_uid,'|',classroom_id,'|',status,'|',version) FROM"
                + " course_schedule_arrangement WHERE arrangement_id=" + ARRANGEMENT));
        text.append(row(connection, "SELECT CONCAT(id,'|',plan_id,'|',course_offering_id,'|',"
                + "arrangement_id,'|',weekday,'|',start_period,'|',end_period,'|',status) FROM"
                + " course_schedule_rule WHERE id=" + RULE));
        text.append(rows(connection, "SELECT CONCAT(rule_id,'|',week_no) FROM"
                + " course_schedule_rule_week WHERE rule_id=" + RULE));
        text.append(row(connection, "SELECT CONCAT(id,'|',rule_id,'|',plan_id,'|',start_at,'|',"
                + "end_at,'|',week_no,'|',teaching_weekday) FROM course_occurrence WHERE id="
                + OCCURRENCE));
        text.append("legacyRules=" + queryInt(connection, "SELECT COUNT(*) FROM"
                + " course_schedule_rule WHERE id BETWEEN 4101 AND 4104"));
        return text.toString();
    }

    private static void verifyV004EraDataSurvives(Connection connection, String before)
            throws SQLException {
        require(before.equals(upgradeSnapshot(connection)),
                "V005/V006/V007 must leave every V004-era row exactly as it was");
        require(queryInt(connection, "SELECT COUNT(*) FROM grade WHERE enrollment_id IN ("
                        + ENROLLMENT_KEPT + "," + ENROLLMENT_KEPT_SECOND + ")") == 0,
                "the migration chain must not invent any published grade for the fixture");
        require(queryInt(connection, "SELECT COUNT(*) FROM teaching_calendar WHERE id=3001") == 1
                        && queryInt(connection, "SELECT COUNT(*) FROM schedule_plan WHERE id=4001")
                        == 1,
                "the shared seeded calendar and plan must survive the whole chain");
    }

    // ------------------------------------------------------------------ 各版本的结构断言

    private static void verifyV005Structures(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_course_operation_log");
        assertTableExists(connection, "teacher_application_read");
        assertIndexColumns(connection, "teacher_application_read", "PRIMARY", "teacher_uid",
                "application_type", "application_id");
        assertColumn(connection, "course", "offering_college", "varchar", 100L);
        assertColumnNullable(connection, "course", "offering_college", true);
        assertCheckConstraint(connection, "teacher_application_read",
                "chk_teacher_application_read_type", "SCHEDULE_ADJUSTMENT", "GRADE_SUBMISSION");
    }

    /**
     * V006 引入的四态字母表：WITHDRAWN 必须能被写进去，而且只有它有 withdrawn_at、没有审核人；
     * 形状不对的行由 CHECK 拒绝。
     */
    private static void verifyV006StatusAlphabet(Connection connection) throws SQLException {
        assertColumn(connection, "course_schedule_adjustment_request", "withdrawn_at", "datetime",
                null, 6);
        assertColumnNullable(connection, "course_schedule_adjustment_request", "withdrawn_at", true);
        assertCheckConstraint(connection, "course_schedule_adjustment_request",
                "chk_course_schedule_adjustment_request_status", "WITHDRAWN");
        assertCheckConstraint(connection, "course_schedule_adjustment_request",
                "chk_course_schedule_adjustment_request_review", "withdrawn_at", "reviewed_at");
        assertForeignKey(connection, "course_schedule_adjustment_target",
                "fk_course_schedule_adjustment_target_calendar_date", "calendar_date");
        assertColumn(connection, "course_schedule_adjustment_target", "target_calendar_date_id",
                "bigint", null);
        assertColumnNullable(connection, "course_schedule_adjustment_target",
                "target_calendar_date_id", true);

        execute(connection, "SET autocommit = 0");
        try {
            String insert = "INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                    + "requested_by,reason,version,new_weekday,new_start_period,new_end_period,"
                    + "submitted_at,withdrawn_at,status) VALUES (987501," + OFFERING + ",'" + TEACHER
                    + "','tcm987 撤销',2,3,3,4,'2027-04-01 00:00:00', ";
            execute(connection, insert + "'2027-04-02 00:00:00','WITHDRAWN')");
            require(queryInt(connection, "SELECT COUNT(*) FROM"
                            + " course_schedule_adjustment_request WHERE request_id = 987501"
                            + " AND status = 'WITHDRAWN' AND withdrawn_at IS NOT NULL"
                            + " AND reviewed_at IS NULL") == 1,
                    "V006 must accept a WITHDRAWN request, the fourth status the teacher UI needs");
            expectSqlRejected(connection, insert + "NULL,'WITHDRAWN')",
                    "HY", "chk_course_schedule_adjustment_request_review",
                    "WITHDRAWN without withdrawn_at must be refused");
            execute(connection, "INSERT INTO course_schedule_adjustment_target(request_id,"
                    + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                    + "target_calendar_date_id) VALUES (987501," + OCCURRENCE
                    + ",2,'2027-03-01 00:00:00','2027-03-01 01:35:00',"
                    + SEEDED_CALENDAR_DATE + ")");
            require(queryInt(connection, "SELECT COUNT(*) FROM"
                            + " course_schedule_adjustment_target WHERE request_id = 987501"
                            + " AND target_calendar_date_id = " + SEEDED_CALENDAR_DATE) == 1,
                    "V006 must let a target row point at the teaching-calendar date it resolves to");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
        require(queryInt(connection, "SELECT COUNT(*) FROM"
                        + " course_schedule_adjustment_request WHERE request_id = 987501") == 0,
                "the V006 probe rows must be rolled back, not left in the fixture range");
    }

    private static void verifyV007SnapshotColumns(Connection connection) throws SQLException {
        assertTableExists(connection, "teacher_grade_book");
        assertTableExists(connection, "teacher_grade_draft_item");
        assertTableExists(connection, "teacher_grade_change_log");
        for (String column : List.of("scheme_snapshot_json", "roster_digest", "base_submission_id",
                "correction_reason", "submission_kind")) {
            require(columnExists(connection, "grade_submission", column),
                    "V007 must add grade_submission." + column);
        }
        assertColumnNullable(connection, "grade_submission", "scheme_snapshot_json", true);
        assertColumnNullable(connection, "grade_submission", "base_submission_id", true);
        assertColumnNullable(connection, "grade_submission_item", "student_uid_snapshot", true);
        assertColumnNullable(connection, "grade_submission_item", "student_name_snapshot", true);
        // 升级前的批次必须保持 NULL 快照 + INITIAL 类型，绝不为了“可重算”回填虚构权重。
        require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission WHERE submission_id = "
                        + SUBMISSION + " AND scheme_snapshot_json IS NULL AND roster_digest IS NULL"
                        + " AND base_submission_id IS NULL AND correction_reason IS NULL"
                        + " AND submission_kind = 'INITIAL'") == 1,
                "a batch written before V007 must keep NULL snapshots and the INITIAL kind");
        require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission_item WHERE item_id = "
                        + SUBMISSION_ITEM + " AND student_uid_snapshot IS NULL"
                        + " AND student_name_snapshot IS NULL") == 1,
                "pre-V007 submission items must keep NULL identity snapshots");
    }

    /**
     * 新表必须能引用 V004 时代的老对象：工作副挂在老教学班上、草稿行挂在老选课记录上、
     * 已读回执与审计行挂在老教师上。
     */
    private static void verifyNewTablesAcceptV004EraRows(Connection connection) throws SQLException {
        execute(connection, "SET autocommit = 0");
        try {
            execute(connection, "INSERT INTO teacher_grade_book(offering_id,revision,draft_open,"
                    + "draft_kind,scheme_json,base_submission_id,updated_by) VALUES (" + OFFERING
                    + ",1,1,'CORRECTION',JSON_OBJECT('components',JSON_ARRAY())," + SUBMISSION
                    + ",'" + TEACHER + "')");
            execute(connection, "INSERT INTO teacher_grade_draft_item(offering_id,enrollment_id,"
                    + "daily_score) VALUES (" + OFFERING + "," + ENROLLMENT_KEPT + ",88.50)");
            execute(connection, "INSERT INTO teacher_application_read(teacher_uid,"
                    + "application_type,application_id,seen_state_key) VALUES ('" + TEACHER
                    + "','GRADE_SUBMISSION',987401,'PENDING:2027-03-10 00:00:00')");
            execute(connection, "INSERT INTO teacher_course_operation_log(teacher_uid,operation_id,"
                    + "action,target_type,target_id,request_digest,request_json,response_json,"
                    + "result_code) VALUES ('" + TEACHER + "',"
                    + "'98700000-0000-0000-0000-000000000001','beginGradeCorrection','GRADE_BOOK',"
                    + "'" + OFFERING + "','tcm987-digest',JSON_OBJECT('reason','tcm987'),"
                    + "JSON_OBJECT('state','DRAFT'),'OK')");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                            + OFFERING + " AND base_submission_id=" + SUBMISSION
                            + " AND correction_reason IS NULL") == 1,
                    "a V007 working copy must be able to reference a pre-V007 batch as its base");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_draft_item WHERE"
                            + " offering_id=" + OFFERING + " AND enrollment_id=" + ENROLLMENT_KEPT
                            + " AND daily_score=88.50") == 1,
                    "a V007 draft row must attach to a V004-era enrollment");
            require(queryInt(connection, "SELECT COUNT(*) FROM teacher_application_read WHERE"
                            + " teacher_uid='" + TEACHER + "' AND application_id=987401") == 1,
                    "the read receipt must accept an application id from a pre-V007 batch");
            // 复合外键真的在生效：草稿行不能挂在别的教学班的选课记录上。
            expectSqlRejected(connection, "INSERT INTO teacher_grade_draft_item(offering_id,"
                            + "enrollment_id,daily_score) VALUES (2001," + ENROLLMENT_KEPT + ",60)",
                    "23", "fk_teacher_grade_draft_item_enrollment",
                    "a draft row must not be stored under another teaching class");
        } finally {
            execute(connection, "ROLLBACK");
            execute(connection, "SET autocommit = 1");
        }
        require(queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFFERING) == 0,
                "the V007 probe rows must be rolled back, not left in the fixture range");
    }

    // ------------------------------------------------------------------ 路径一：全新安装

    /** 文档顺序的全新安装：tbl_user → V001/V002/V003 → 旧 seed → V004 → V005 → V006 → V007。 */
    private static void installFresh(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            requireTestDatabaseOn(connection);
            resetTestSchema(connection);
            applyTblUser(connection, root.resolve(INIT_SQL));
            applyScript(connection, root.resolve(V001), true);
            applyScript(connection, root.resolve(V002), true);
            applyScript(connection, root.resolve(V003), true);
            applyScript(connection, root.resolve(SEED_SQL), false);
            applyScript(connection, root.resolve(V004), true);
            applyScript(connection, root.resolve(V005), true);
            applyScript(connection, root.resolve(V006), true);
            applyScript(connection, root.resolve(V007), true);

            requireTestDatabaseOn(connection);
            require(queryInt(connection, "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                            + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('teacher_grade_book',"
                            + " 'teacher_grade_draft_item', 'teacher_grade_change_log',"
                            + " 'teacher_application_read', 'teacher_course_operation_log',"
                            + " 'course_schedule_adjustment_request')") == 6,
                    "a fresh install must end with every table the six teacher suites read");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_rule WHERE"
                            + " arrangement_id IS NULL") == 0,
                    "the fresh order must let V004 backfill the seeded rules");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering WHERE offering_id"
                            + " IN (2001,2002)") == 2
                            && queryInt(connection, "SELECT COUNT(*) FROM course_occurrence WHERE"
                            + " id=4201") == 1,
                    "the fresh order must leave the shared seeded rows in place");
            require(queryInt(connection, "SELECT COUNT(*) FROM grade_submission") == 0
                            && queryInt(connection, "SELECT COUNT(*) FROM teacher_grade_book") == 0
                            && queryInt(connection, "SELECT COUNT(*) FROM"
                            + " course_schedule_adjustment_request") == 0,
                    "a fresh install must not carry any leftover fixture row from the upgrade path");
        }
    }

    // ------------------------------------------------------------------ 连接与断言助手

    private static Connection connect(String jdbc, String user, String pass) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbc, user, pass);
        execute(connection, "SET time_zone = '+00:00'");
        return connection;
    }

    /** 任何重写库的操作之前，先核验这条连接真的落在受保护的测试库上。 */
    private static void requireTestDatabaseOn(Connection connection) throws SQLException {
        String database = queryString(connection, "SELECT DATABASE()");
        require(TEST_DATABASE.equals(database),
                "Refusing to reset: connected database must be exactly " + TEST_DATABASE
                        + " but was " + database);
    }

    private static void applyTblUser(Connection connection, Path initSql) throws Exception {
        Matcher matcher = TBL_USER.matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "the authoritative tbl_user definition was not found in init.sql");
        execute(connection, matcher.group());
    }

    /** {@code verifySchema} 为真时每条语句前后都复核库名，避免任何一条 DDL 逸出受保护的库。 */
    private static void applyScript(Connection connection, Path path, boolean verifySchema)
            throws Exception {
        require(Files.isRegularFile(path), "Missing SQL file: " + path);
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int index = 0; index < statements.size(); index++) {
            if (verifySchema) requireTestDatabaseOn(connection);
            try {
                execute(connection, statements.get(index));
            } catch (SQLException failure) {
                throw new SQLException("Failed applying " + path.getFileName() + " statement "
                        + (index + 1) + " (" + statements.get(index).substring(0, Math.min(60,
                        statements.get(index).length())) + "...)", failure);
            }
        }
    }

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                requireTestDatabaseOn(connection);
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
        try (Connection connection = DriverManager.getConnection(serverUrl,
                requiredProperty(properties, "db.username"),
                requiredProperty(properties, "db.password"))) {
            execute(connection, "CREATE DATABASE IF NOT EXISTS `" + TEST_DATABASE
                    + "` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private static void assertTableExists(Connection connection, String table) throws SQLException {
        require(columnCount(connection, "SELECT COUNT(*) FROM information_schema.TABLES WHERE"
                + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND TABLE_TYPE ="
                + " 'BASE TABLE'") == 1, "Missing table " + table);
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        return columnCount(connection, "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE"
                + " TABLE_SCHEMA = DATABASE() AND TABLE_NAME = '" + table + "' AND COLUMN_NAME = '"
                + column + "'") == 1;
    }

    private static void assertColumn(Connection connection, String table, String column,
            String dataType, Long maxLength) throws SQLException {
        assertColumn(connection, table, column, dataType, maxLength, null);
    }

    private static void assertColumn(Connection connection, String table, String column,
            String dataType, Long maxLength, Integer datetimePrecision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, DATETIME_PRECISION"
                        + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                        + " AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                require(dataType.equalsIgnoreCase(result.getString(1)),
                        "Unexpected type for " + table + "." + column + ": "
                                + result.getString(1));
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE()"
                        + " AND TABLE_NAME = ? AND COLUMN_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing column " + table + "." + column);
                boolean actual = "YES".equalsIgnoreCase(result.getString(1));
                require(actual == nullable,
                        "Unexpected nullability for " + table + "." + column + ": " + actual);
            }
        }
    }

    private static void assertIndexColumns(Connection connection, String table, String index,
            String... expected) throws SQLException {
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA ="
                        + " DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX")) {
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

    private static void assertCheckConstraint(Connection connection, String table,
            String constraint, String... fragments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.CHECK_CLAUSE FROM information_schema.CHECK_CONSTRAINTS c"
                        + " JOIN information_schema.TABLE_CONSTRAINTS t"
                        + " ON t.CONSTRAINT_SCHEMA = c.CONSTRAINT_SCHEMA"
                        + " AND t.CONSTRAINT_NAME = c.CONSTRAINT_NAME"
                        + " WHERE c.CONSTRAINT_SCHEMA = DATABASE() AND t.TABLE_NAME = ?"
                        + " AND c.CONSTRAINT_NAME = ?")) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing check constraint " + table + "." + constraint);
                String clause = result.getString(1) == null ? "" : result.getString(1).toLowerCase();
                for (String fragment : fragments) {
                    require(clause.contains(fragment.toLowerCase()),
                            "Check constraint " + constraint + " must mention " + fragment
                                    + ", saw " + clause);
                }
            }
        }
    }

    /** V006 目标日期的外键必须存在且指向 calendar_date。 */
    private static void assertForeignKey(Connection connection, String table, String constraint,
            String referencedTable) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT REFERENCED_TABLE_NAME FROM information_schema.KEY_COLUMN_USAGE"
                        + " WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                        + " AND CONSTRAINT_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL")) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                require(result.next(), "Missing foreign key " + table + "." + constraint);
                require(referencedTable.equalsIgnoreCase(result.getString(1)),
                        "Unexpected parent table for " + constraint + ": " + result.getString(1));
            }
        }
    }

    private static void expectSqlRejected(Connection connection, String sql,
            String expectedSqlStateClass, String expectedDatabaseObject, String message)
            throws SQLException {
        try {
            execute(connection, sql);
        } catch (SQLException failure) {
            for (SQLException candidate = failure; candidate != null;
                 candidate = candidate.getNextException()) {
                String sqlState = candidate.getSQLState();
                String errorMessage = candidate.getMessage();
                if (sqlState != null && sqlState.startsWith(expectedSqlStateClass)
                        && errorMessage != null && errorMessage.toLowerCase()
                        .contains(expectedDatabaseObject.toLowerCase())) {
                    return;
                }
            }
            throw new AssertionError(message + ": expected SQLState class " + expectedSqlStateClass
                    + " naming " + expectedDatabaseObject + ", but got SQLState "
                    + failure.getSQLState() + " with message: " + failure.getMessage(), failure);
        }
        throw new AssertionError(message);
    }

    private static int columnCount(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getInt(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        return columnCount(connection, sql);
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getString(1);
        }
    }

    private static String row(Connection connection, String sql) throws SQLException {
        return queryString(connection, sql) + "|";
    }

    private static String rows(Connection connection, String sql) throws SQLException {
        StringBuilder text = new StringBuilder();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                text.append(result.getString(1)).append(',');
            }
        }
        return text.toString();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String read(Path root, String relative) throws Exception {
        return Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
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

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "Refusing live migration chain: JDBC database must"
                + " be exactly " + TEST_DATABASE + " but was " + database);
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
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

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve(INIT_SQL))) {
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
