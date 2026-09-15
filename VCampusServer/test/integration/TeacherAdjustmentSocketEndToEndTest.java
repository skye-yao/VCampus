package integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dto.course.CourseActions;
import dto.course.CourseNoticeDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * T6 的真实 TCP/MySQL 闭环验证：教师第 8 周申请调到第 9 周 → 管理员审批 → 教师与学生分别查两周。
 *
 * <p>只有 JDBC 数据库正好是 {@code virtual_campus_course_test} 时才继续（未传 {@code mysql} 时只
 * 打印 SKIP 并返回，绝不把 SKIP 当成 PASS）。本类自己重建受保护的架构
 * （完整 init.sql → V001 → V002 → V003 → seed-course-test.sql → V004 → V005 → V006），
 * 再插入私有 ID 段（日历 965001、模板 965002、方案 965003、教室 965101/965102、课程
 * 965201/965202、教学班 965301/965302、安排 965401/965402、规则 965501..965508、课次
 * 965601..965608、日历日期 966001..966070、UID 前缀 {@code tadje2e-}），结束时只删除自己的行，
 * 并断言共享 seed（教学班 2001/2002、课次 4201）完好。写入测试必须串行运行，本类不做任何并行。
 *
 * <p>断言全部打在具体数据上：跨周移动后原周只返回原标记、目标周只返回目标标记；同周移动在同一周
 * 返回两块；通知按关联申请的原/目标周查询去重（每周至多一条，跨周通知两侧各一条）；发布方案的
 * 安排/规则/周次/课次/教室预约校验和在整段闭环前后字节一致；提交后新增的学生在审批时必须再次
 * 触发冲突（先被拒绝、管理员 force 后通过）；撤销与审批竞争只有一个方向能成功；同一 operationId
 * 的逐字节重放不产生第二条申请。
 */
public final class TeacherAdjustmentSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final String LOGIN_PASSWORD = "course-test-only";
    private static final String TEACHER_ROLE = "教师";
    private static final String STUDENT_ROLE = "学生";
    private static final String ADMIN_ROLE = "管理员";
    /** Seed-course-test.sql 的测试口令哈希/盐；本测试的教师与学生账号复用它们登录。 */
    private static final String SEEDED_PASSWORD_HASH =
            "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String SEEDED_PASSWORD_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";

    private static final int ACADEMIC_YEAR = 2027;
    private static final int SEMESTER = 1;
    /** 第 1 教学周周一；每个日期都由它按 (week, weekday) 推导。 */
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2027, 2, 22);
    private static final String ZONE = "Asia/Shanghai";
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 10;
    private static final int FIRST_PERIOD_MINUTES = 8 * 60;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final int PERIOD_LENGTH_MINUTES = 45;

    private static final String TEACHER = "tadje2e-teacher";
    private static final String TEACHER_OTHER = "tadje2e-teacher-other";
    private static final String STUDENT = "tadje2e-student";
    private static final String STUDENT_CONFLICT = "tadje2e-student-conflict";
    private static final String ADMIN = "tadje2e-admin";

    /** Own fixture range: nothing outside 965000..966999 and the {@code tadje2e-} UID prefix. */
    private static final long CALENDAR = 965001L;
    private static final long DAY_TEMPLATE = 965002L;
    private static final long PLAN = 965003L;
    private static final long ROOM_A = 965101L;
    private static final long ROOM_B = 965102L;
    private static final long RESOURCE_A = 965111L;
    private static final long RESOURCE_B = 965112L;
    private static final long COURSE_MAIN = 965201L;
    private static final long COURSE_OTHER = 965202L;
    private static final long OFFERING_MAIN = 965301L;
    private static final long OFFERING_OTHER = 965302L;
    private static final long ARRANGEMENT_MAIN = 965401L;
    private static final long ARRANGEMENT_OTHER = 965402L;
    private static final long RULE_CROSS = 965501L;
    private static final long RULE_SAME = 965502L;
    private static final long RULE_STUDENT = 965503L;
    private static final long RULE_WITHDRAWN = 965504L;
    private static final long RULE_AFTER = 965505L;
    private static final long RULE_OTHER = 965506L;
    private static final long RULE_PARTIAL_W5 = 965507L;
    private static final long RULE_PARTIAL_W6 = 965508L;
    private static final long OCC_CROSS = 965601L;
    private static final long OCC_SAME = 965602L;
    private static final long OCC_STUDENT = 965603L;
    private static final long OCC_WITHDRAWN = 965604L;
    private static final long OCC_AFTER = 965605L;
    private static final long OCC_OTHER = 965606L;
    private static final long OCC_PARTIAL_W5 = 965607L;
    private static final long OCC_PARTIAL_W6 = 965608L;
    private static final long ENROLLMENT_STUDENT = 965701L;

    /** 目标教学日与目标时间：第 9 周周三 2027-04-21 第 3-4 节、第 8 周周三 2027-04-14 等。 */
    private static final String CROSS_TARGET_DATE = "2027-04-21";
    private static final String SAME_WEEK_TARGET_DATE = "2027-04-14";
    private static final String AFTER_TARGET_DATE = "2027-04-15";
    /** 多目标申请的两个目标教学日：第 5 周周三 2027-03-24 与第 6 周周三 2027-03-31。 */
    private static final String PARTIAL_FIRST_DATE = "2027-03-24";
    private static final String PARTIAL_SECOND_DATE = "2027-03-31";

    private static final Gson GSON = new Gson();
    private static final Pattern CLOCK = Pattern.compile("[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private TeacherAdjustmentSocketEndToEndTest() {
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
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher adjustment end-to-end test"
                    + " was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String jdbc = withTestAuthentication(url);
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");

        System.out.println("[E2E] reset + fresh install order"
                + " (init.sql -> V001 -> V002 -> V003 -> seed -> V004 -> V005 -> V006)");
        resetAndSeed(jdbc, username, password, root);
        insertFixtures(jdbc, username, password);
        pointDBUtilAt(jdbc, properties);
        String baseBefore = baseChecksum(jdbc, username, password);
        System.out.println("[E2E] fresh post-V006 schema with the adjustment fixtures in place");

        Harness harness = new Harness();
        try {
            harness.start();
            System.out.println("[E2E] server listening on 127.0.0.1:" + harness.port());
            runScenarios(harness, jdbc, username, password, baseBefore);
        } finally {
            harness.stop();
        }
        cleanFixtures(jdbc, username, password);
        System.out.println("Teacher adjustment socket end-to-end test passed.");
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    private static void runScenarios(Harness harness, String jdbc, String user, String pass,
            String baseBefore) throws Exception {
        try (JsonLineClient teacher = harness.client();
             JsonLineClient student = harness.client();
             JsonLineClient admin = harness.client()) {
            String teacherToken = teacher.login(TEACHER, TEACHER_ROLE);
            String studentToken = student.login(STUDENT, STUDENT_ROLE);
            String adminToken = admin.login(ADMIN, ADMIN_ROLE);

            verifyOptionsExposeTheTargetWeek(teacher, teacherToken);

            // 1. 跨周：教师第 8 周周二调到第 9 周周三，同 operationId 逐字节重放不得产生第二条申请。
            Map<String, Object> crossPayload = writePayload(
                    "96500000-0000-0000-0000-000000000001", OFFERING_MAIN, OCC_CROSS,
                    CROSS_TARGET_DATE, 3, 4, ROOM_B, "tadje2e 跨周调课");
            long requestCross = submit(teacher, teacherToken, crossPayload, false);
            long replayedRequest = submit(teacher, teacherToken, crossPayload, true);
            require(requestCross == replayedRequest,
                    "the replayed submit must return the committed request " + requestCross
                            + " but returned " + replayedRequest);
            require(count(jdbc, user, pass, "SELECT COUNT(*) FROM"
                            + " course_schedule_adjustment_request WHERE requested_by='" + TEACHER
                            + "'") == 1
                            && count(jdbc, user, pass, "SELECT COUNT(*) FROM"
                            + " teacher_course_operation_log WHERE teacher_uid='" + TEACHER + "'") == 1
                            && count(jdbc, user, pass, "SELECT COUNT(*) FROM"
                            + " course_schedule_adjustment_target WHERE request_id=" + requestCross)
                            == 1,
                    "an identical replay must neither duplicate the request, its target, nor the"
                            + " operation log");
            review(admin, adminToken, "96500000-0000-0000-0000-000000000002", requestCross, 1, true,
                    false, null, null);
            verifyCrossWeekVisibility(teacher, teacherToken, student, studentToken, jdbc, user, pass,
                    requestCross);

            // 2. 同周：周五第 5-6 节调到同一周的周三第 3-4 节，同一周要返回原/新两块。
            long requestSame = submit(teacher, teacherToken, writePayload(
                    "96500000-0000-0000-0000-000000000003", OFFERING_MAIN, OCC_SAME,
                    SAME_WEEK_TARGET_DATE, 3, 4, ROOM_B, "tadje2e 同周调课"), false);
            review(admin, adminToken, "96500000-0000-0000-0000-000000000004", requestSame, 1, true,
                    false, null, null);
            verifySameWeekPair(teacher, teacherToken, student, studentToken, requestSame, requestCross);

            // 3. 提交之后新增的学生必须在审批时再次触发冲突：先被拒绝，force 后才通过。
            long requestStudent = submit(teacher, teacherToken, writePayload(
                    "96500000-0000-0000-0000-000000000005", OFFERING_MAIN, OCC_STUDENT,
                    SAME_WEEK_TARGET_DATE, 5, 6, null, "tadje2e 新增学生冲突"), false);
            verifyStudentConflictAtApproval(admin, adminToken, student, studentToken, jdbc, user, pass,
                    requestStudent);

            // 3.5 部分目标失败：多目标申请里一个目标快照失效时，审批必须整体回滚。
            verifyPartialTargetFailureRollsBack(teacher, teacherToken, admin, adminToken, jdbc,
                    user, pass);

            // 4. 撤销与审批竞争：撤销后的申请不能被审批，已通过的申请不能再撤销。
            verifyWithdrawApprovalCompetition(teacher, teacherToken, admin, adminToken, student,
                    studentToken, jdbc, user, pass);

            require(baseBefore.equals(baseChecksum(jdbc, user, pass)),
                    "the whole closed loop must never rewrite the published plan, its rules, weeks,"
                            + " occurrences, bookings or arrangements");
            System.out.println("[E2E] published base plan checksum unchanged across the loop");
        }
    }

    /** 教师受限的调课选项必须直接给出目标教学周（不调用管理员资源接口）。 */
    private static void verifyOptionsExposeTheTargetWeek(JsonLineClient teacher, String token) {
        Message response = teacher.teacher(TeacherCourseActions.GET_ADJUSTMENT_OPTIONS, token,
                Map.of("offeringId", Long.toString(OFFERING_MAIN),
                        "originalOccurrenceId", Long.toString(OCC_CROSS)));
        requireSuccess(response, "read adjustment options");
        require(response.getData().keySet().equals(java.util.Set.of("options")),
                "the options response must expose exactly the options key, saw "
                        + response.getData().keySet());
        TeacherAdjustmentOptionsDTO options = GSON.fromJson(
                GSON.toJsonTree(response.getData("options")), TeacherAdjustmentOptionsDTO.class);
        require(options.getDates().stream().anyMatch(date ->
                        CROSS_TARGET_DATE.equals(date.getDate()) && date.getWeek() == 9
                                && date.isTeachingDay()),
                "week 9 must offer " + CROSS_TARGET_DATE + " as a teaching day");
        require(options.getPeriods().stream().anyMatch(period ->
                        CROSS_TARGET_DATE.equals(period.getDate()) && period.getPeriod() == 3
                                && CLOCK.matcher(period.getStartTime()).matches()
                                && CLOCK.matcher(period.getEndTime()).matches()),
                "the third period of " + CROSS_TARGET_DATE + " must carry local clock strings");
        System.out.println("[E2E] adjustment options expose the week-9 target date "
                + CROSS_TARGET_DATE);
    }

    /**
     * 跨周闭环：教师原周只看到原标记，目标周只看到目标标记；两条通知分别落在原周和目标周各自
     * 恰好一条，其它周零条；基础表校验和不变。
     */
    private static void verifyCrossWeekVisibility(JsonLineClient teacher, String teacherToken,
            JsonLineClient student, String studentToken, String jdbc, String user, String pass,
            long requestCross) throws Exception {
        long adjustmentId = adjustmentOf(jdbc, user, pass, requestCross);

        TeacherScheduleWeekDTO originWeek = teacherSchedule(teacher, teacherToken, 8);
        require(kinds(originWeek).equals(List.of(
                        "965601:ADJUSTED_ORIGINAL",
                        "965602:NORMAL",
                        "965603:NORMAL",
                        "965604:NORMAL",
                        "965605:NORMAL")),
                "the teacher's origin week must keep only the original marker for the moved"
                        + " occurrence, saw " + kinds(originWeek));
        TeacherScheduleEntryDTO original = entryOf(originWeek, OCC_CROSS);
        require(original.getLocalDate().equals("2027-04-13") && original.getDayOfWeek() == 2
                        && original.getStartPeriod() == 1 && original.getEndPeriod() == 2
                        && Long.toString(adjustmentId).equals(original.getAdjustmentId())
                        && !original.isCanRequestAdjustment(),
                "the original marker must stay on 2027-04-13 第 1-2 节 with the adjustment id, saw "
                        + original.getLocalDate() + " 第 " + original.getStartPeriod() + "-"
                        + original.getEndPeriod() + " 节");

        TeacherScheduleWeekDTO targetWeek = teacherSchedule(teacher, teacherToken, 9);
        require(kinds(targetWeek).equals(List.of("965601:ADJUSTED_TARGET")),
                "the teacher's target week must hold only the moved block, saw " + kinds(targetWeek));
        TeacherScheduleEntryDTO moved = targetWeek.getEntries().get(0);
        require(moved.getLocalDate().equals(CROSS_TARGET_DATE) && moved.getWeek() == 9
                        && moved.getDayOfWeek() == 3 && moved.getStartPeriod() == 3
                        && moved.getEndPeriod() == 4 && "TADJ-E2E Room B".equals(moved.getLocation()),
                "the target block must sit on " + CROSS_TARGET_DATE + " 周三 第 3-4 节 in Room B, saw "
                        + moved.getLocalDate() + " 第 " + moved.getStartPeriod() + "-"
                        + moved.getEndPeriod() + " 节 " + moved.getLocation());
        require(teacherSchedule(teacher, teacherToken, 7).getEntries().isEmpty()
                        && teacherSchedule(teacher, teacherToken, 10).getEntries().isEmpty(),
                "the weeks around the move must stay empty for the teacher");

        // 学生入口仍是既有 course.loadSchedule / loadNotices。
        List<ScheduleEntryDTO> studentOrigin = studentSchedule(student, studentToken, 8);
        require(studentOrigin.size() == 5
                        && studentOrigin.stream().filter(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == entry.getDisplayKind())
                        .count() == 1
                        && studentOrigin.stream().filter(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind()).count() == 0
                        && studentOrigin.stream().anyMatch(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == entry.getDisplayKind()
                                && entry.getDayOfWeek() == 2 && entry.getStartPeriod() == 1
                                && Long.toString(adjustmentId).equals(entry.getAdjustmentId())),
                "the student's origin week must keep only the original marker, saw "
                        + describeSchedule(studentOrigin));
        List<ScheduleEntryDTO> studentTarget = studentSchedule(student, studentToken, 9);
        require(studentTarget.size() == 1
                        && ScheduleDisplayKindDTO.ADJUSTED_TARGET
                        == studentTarget.get(0).getDisplayKind()
                        && Long.toString(adjustmentId).equals(studentTarget.get(0).getAdjustmentId())
                        && studentTarget.get(0).getDayOfWeek() == 3
                        && studentTarget.get(0).getStartPeriod() == 3
                        && studentTarget.get(0).getPeriodCount() == 2
                        && "TADJ-E2E Room B".equals(studentTarget.get(0).getLocation())
                        && "TADJ E2E Teacher".equals(studentTarget.get(0).getTeacher())
                        && "周二 第1-2节 TADJ-E2E Room A".equals(
                        studentTarget.get(0).getOriginalScheduleText())
                        && "周三 第3-4节 TADJ-E2E Room B".equals(
                        studentTarget.get(0).getAdjustedScheduleText()),
                "the student's target week must hold only the target marker, saw "
                        + describeSchedule(studentTarget));
        require(studentSchedule(student, studentToken, 7).isEmpty()
                        && studentSchedule(student, studentToken, 10).isEmpty(),
                "the student's neighbouring weeks must stay empty");

        require(noticeIds(student, studentToken, 8).size() == 1
                        && noticeIds(student, studentToken, 9).size() == 1,
                "the rescheduled notice must appear exactly once in the origin and target weeks,"
                        + " saw " + noticeIds(student, studentToken, 8) + " / "
                        + noticeIds(student, studentToken, 9));
        require(noticeIds(student, studentToken, 7).isEmpty()
                        && noticeIds(student, studentToken, 10).isEmpty(),
                "the rescheduled notice must not leak into unrelated weeks, saw "
                        + noticeIds(student, studentToken, 7) + " / "
                        + noticeIds(student, studentToken, 10));
        CourseNoticeDTO notice = studentNotices(student, studentToken, 9).get(0);
        require("RESCHEDULED".equals(notice.getNoticeType()) && notice.getWeek() == 0
                        && notice.getContent().contains("第8周"),
                "the notice must be a week-less RESCHEDULED summary naming the original week, saw "
                        + notice.getNoticeType() + "/" + notice.getWeek() + "/"
                        + notice.getContent());
        System.out.println("[E2E] cross-week move -> origin week only the original marker, target"
                + " week only the target marker, one notice per side");
    }

    /** 同周移动必须在同一周返回原/新两块，并只多出一条通知（每周至多一条）。 */
    private static void verifySameWeekPair(JsonLineClient teacher, String teacherToken,
            JsonLineClient student, String studentToken, long requestSame, long requestCross)
            throws Exception {
        TeacherScheduleWeekDTO week = teacherSchedule(teacher, teacherToken, 8);
        require(kinds(week).equals(List.of(
                        "965601:ADJUSTED_ORIGINAL",
                        "965602:ADJUSTED_ORIGINAL",
                        "965602:ADJUSTED_TARGET",
                        "965603:NORMAL",
                        "965604:NORMAL",
                        "965605:NORMAL")),
                "a same-week move must return both halves in one week, saw " + kinds(week));
        TeacherScheduleEntryDTO target = entryOf(week, OCC_SAME);
        require(target.getLocalDate().equals(SAME_WEEK_TARGET_DATE)
                        && target.getDayOfWeek() == 3 && target.getStartPeriod() == 3,
                "the same-week target must sit on " + SAME_WEEK_TARGET_DATE + " 周三 第 3-4 节, saw "
                        + target.getLocalDate());

        List<ScheduleEntryDTO> studentWeek = studentSchedule(student, studentToken, 8);
        require(studentWeek.size() == 6
                        && studentWeek.stream().filter(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL == entry.getDisplayKind()).count()
                        == 2
                        && studentWeek.stream().filter(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind()).count() == 1
                        && studentWeek.stream().anyMatch(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind()
                                && entry.getDayOfWeek() == 3 && entry.getStartPeriod() == 3
                                && "TADJ-E2E Room B".equals(entry.getLocation())),
                "the student's same-week view must hold both halves, saw "
                        + describeSchedule(studentWeek));
        require(noticeIds(student, studentToken, 8).size() == 2
                        && noticeIds(student, studentToken, 9).size() == 1,
                "each approved request must add exactly one notice to week 8 and only the"
                        + " cross-week one reaches week 9, saw "
                        + noticeIds(student, studentToken, 8) + " / "
                        + noticeIds(student, studentToken, 9));
        System.out.println("[E2E] same-week move -> two halves in week 8, one extra notice only");
    }

    /**
     * 提交时干净、提交后新增的学生在审批时必须再次触发冲突：默认审批被 OVERRIDABLE 冲突拒绝且
     * 不写任何调整，管理员 force 后才通过并让新学生也看到目标周次。
     */
    private static void verifyStudentConflictAtApproval(JsonLineClient admin, String adminToken,
            JsonLineClient student, String studentToken, String jdbc, String user, String pass,
            long requestStudent) throws Exception {
        require(count(jdbc, user, pass,
                        "SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                                + requestStudent) == 0
                        && count(jdbc, user, pass,
                        "SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                                + requestStudent) == 0,
                "a submitted request must not write any adjustment or notice");
        AdjustmentRequestDetailDTO pending = adminDetail(admin, adminToken, requestStudent);
        require(pending.getStatus().name().equals("PENDING") && pending.getConflicts().isEmpty(),
                "the request must start clean but saw status " + pending.getStatus() + " with "
                        + describeConflicts(pending.getConflicts()));

        enrollConflictStudent(jdbc, user, pass);
        AdjustmentRequestDetailDTO conflicted = adminDetail(admin, adminToken, requestStudent);
        require(conflicted.getConflicts().stream().anyMatch(conflict ->
                        "STUDENT_SCHEDULE".equals(conflict.getType())
                                && ScheduleConflictSeverityDTO.OVERRIDABLE == conflict.getSeverity()
                                && STUDENT_CONFLICT.equals(conflict.getSubjectId())),
                "the newly added student must raise a STUDENT_SCHEDULE conflict, saw "
                        + describeConflicts(conflicted.getConflicts()));

        Message refused = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, adminToken,
                Map.of("request", reviewPayload("96500000-0000-0000-0000-000000000006",
                        requestStudent, 1, true, false, null, null)));
        requireCode(refused, MessageCode.CONFLICT, "a post-submit student conflict must refuse the"
                + " default approval");
        require(refused.getData("conflicts") instanceof List<?> conflicts && conflicts.size() >= 1,
                "the refusal must carry the typed conflict list");
        require(count(jdbc, user, pass,
                        "SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                                + requestStudent) == 0
                        && adminDetail(admin, adminToken, requestStudent).getStatus().name()
                        .equals("PENDING"),
                "a refused approval must roll back to a PENDING request without adjustments");

        Message forced = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, adminToken,
                Map.of("request", reviewPayload("96500000-0000-0000-0000-000000000007",
                        requestStudent, 1, true, true, "TADJ 强制通过新增学生冲突", "TADJ 强制原因")));
        requireCode(forced, MessageCode.SUCCESS, "an administrator force approval must succeed");
        require(count(jdbc, user, pass,
                        "SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                                + requestStudent) == 1
                        && count(jdbc, user, pass,
                        "SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                                + requestStudent) == 1,
                "the forced approval must write exactly one adjustment and one notice");

        List<ScheduleEntryDTO> week = studentSchedule(student, studentToken, 8);
        require(week.stream().anyMatch(entry ->
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind()
                                && entry.getDayOfWeek() == 3 && entry.getStartPeriod() == 5
                                && "TADJ-E2E Room A".equals(entry.getLocation())),
                "the forced target must reach the enrolled student, saw " + describeSchedule(week));
        require(noticeIds(student, studentToken, 8).size() == 3,
                "week 8 must now hold exactly three notices (one per approved request), saw "
                        + noticeIds(student, studentToken, 8));
        System.out.println("[E2E] post-submit student -> approval refused with STUDENT_SCHEDULE,"
                + " force approves and the target reaches the student");
    }

    /**
     * 多目标申请里只要一个目标与生效课表不一致，审批必须整体失败：另一个目标也不得写入，
     * 申请保持 PENDING v1；恢复被破坏的快照后发布方案校验和必须回到原值。
     */
    private static void verifyPartialTargetFailureRollsBack(JsonLineClient teacher,
            String teacherToken, JsonLineClient admin, String adminToken, String jdbc, String user,
            String pass) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", "96500000-0000-0000-0000-000000000014");
        payload.put("offeringId", Long.toString(OFFERING_MAIN));
        payload.put("targets", List.of(targetPayload(OCC_PARTIAL_W5, PARTIAL_FIRST_DATE),
                targetPayload(OCC_PARTIAL_W6, PARTIAL_SECOND_DATE)));
        payload.put("newStartPeriod", 13);
        payload.put("newEndPeriod", 13);
        payload.put("reason", "tadje2e 部分目标失败");
        long requestPartial = submit(teacher, teacherToken, payload, false);

        String beforeBreak = baseChecksum(jdbc, user, pass);
        // 只把其中一个原课次的开始时刻挪一分钟，目标快照立即失效。
        executeUpdate(jdbc, user, pass, "UPDATE course_occurrence SET start_at='2027-03-23 10:01:00'"
                + " WHERE id=" + OCC_PARTIAL_W5);
        Message refused = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, adminToken,
                Map.of("request", reviewPayload("96500000-0000-0000-0000-000000000015",
                        requestPartial, 1, true, false, null, null)));
        requireCode(refused, MessageCode.CONFLICT, "a stale target must refuse the approval");
        List<ScheduleConflictDTO> conflicts = GSON.fromJson(
                GSON.toJsonTree(refused.getData("conflicts")),
                new TypeToken<List<ScheduleConflictDTO>>() { }.getType());
        require(conflicts.stream().anyMatch(conflict ->
                        "ADJUSTMENT_TARGET_INVALID".equals(conflict.getType())
                                && ScheduleConflictSeverityDTO.BLOCKING == conflict.getSeverity()),
                "the refusal must name the stale target as a BLOCKING conflict, saw "
                        + describeConflicts(conflicts));
        require(count(jdbc, user, pass, "SELECT COUNT(*) FROM course_schedule_adjustment"
                        + " WHERE request_id=" + requestPartial) == 0
                        && count(jdbc, user, pass, "SELECT COUNT(*) FROM course_notice"
                        + " WHERE adjustment_request_id=" + requestPartial) == 0,
                "a partially failing approval must roll every target back, not only the broken one");
        AdjustmentRequestDetailDTO stayed = adminDetail(admin, adminToken, requestPartial);
        require(stayed.getStatus().name().equals("PENDING") && stayed.getVersion() == 1,
                "the refused request must stay PENDING v1 but was " + stayed.getStatus() + " v"
                        + stayed.getVersion());
        executeUpdate(jdbc, user, pass, "UPDATE course_occurrence SET start_at='2027-03-23 10:00:00'"
                + " WHERE id=" + OCC_PARTIAL_W5);
        require(beforeBreak.equals(baseChecksum(jdbc, user, pass)),
                "the refused approval must leave the published base plan exactly as it was");
        System.out.println("[E2E] partial target failure -> the approval rolls back every target");
    }

    private static Map<String, Object> targetPayload(long occurrenceId, String targetDate) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("originalOccurrenceId", Long.toString(occurrenceId));
        target.put("targetDate", targetDate);
        return target;
    }

    private static void executeUpdate(String jdbc, String user, String pass, String sql)
            throws SQLException {
        assertDatabase(jdbc, user, pass, connection -> execute(connection, sql));
    }

    /** 撤销后的申请不能被审批；已通过的申请不能再撤销；两个方向都不得产生第二条调整。 */
    private static void verifyWithdrawApprovalCompetition(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken, JsonLineClient student, String studentToken,
            String jdbc, String user, String pass) throws Exception {
        long requestWithdrawn = submit(teacher, teacherToken, writePayload(
                "96500000-0000-0000-0000-000000000008", OFFERING_MAIN, OCC_WITHDRAWN,
                SAME_WEEK_TARGET_DATE, 11, 12, null, "tadje2e 先撤销"), false);
        Message withdrawn = teacher.teacher(TeacherCourseActions.WITHDRAW_ADJUSTMENT, teacherToken,
                Map.of("request", Map.of(
                        "operationId", "96500000-0000-0000-0000-000000000009",
                        "requestId", Long.toString(requestWithdrawn),
                        "expectedVersion", 1)));
        requireSuccess(withdrawn, "withdraw the pending request");
        require("WITHDRAWN".equals(teacherEntityOf(withdrawn).getStatus().name())
                        && teacherEntityOf(withdrawn).getVersion() == 2,
                "the withdrawal must settle the request at WITHDRAWN v2");

        Message refusedApproval = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST,
                adminToken, Map.of("request", reviewPayload(
                        "96500000-0000-0000-0000-000000000010", requestWithdrawn, 1, true, false,
                        null, null)));
        requireCode(refusedApproval, MessageCode.CONFLICT,
                "a withdrawn request must not be approved");
        require(count(jdbc, user, pass, "SELECT COUNT(*) FROM course_schedule_adjustment"
                        + " WHERE request_id=" + requestWithdrawn) == 0,
                "the refused approval of a withdrawn request must write no adjustment");

        long requestApproved = submit(teacher, teacherToken, writePayload(
                "96500000-0000-0000-0000-000000000011", OFFERING_MAIN, OCC_AFTER,
                AFTER_TARGET_DATE, 9, 10, null, "tadje2e 先审批"), false);
        review(admin, adminToken, "96500000-0000-0000-0000-000000000012", requestApproved, 1, true,
                false, null, null);
        Message refusedWithdrawal = teacher.teacher(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                teacherToken, Map.of("request", Map.of(
                        "operationId", "96500000-0000-0000-0000-000000000013",
                        "requestId", Long.toString(requestApproved),
                        "expectedVersion", 1)));
        requireCode(refusedWithdrawal, MessageCode.CONFLICT,
                "an approved request must not be withdrawn");
        require(count(jdbc, user, pass, "SELECT COUNT(*) FROM course_schedule_adjustment"
                        + " WHERE request_id=" + requestApproved) == 1,
                "the losing withdrawal must not touch the committed adjustment");
        require(noticeIds(student, studentToken, 8).size() == 4,
                "only the four approved requests may publish a notice, saw "
                        + noticeIds(student, studentToken, 8));
        System.out.println("[E2E] withdraw vs approval -> exactly one state transition wins per"
                + " direction");
    }

    private static List<String> noticeIds(JsonLineClient client, String token, int week) {
        List<String> ids = new ArrayList<>();
        for (CourseNoticeDTO notice : studentNotices(client, token, week)) {
            ids.add(notice.getNoticeId());
        }
        return List.copyOf(ids);
    }

    // ------------------------------------------------------------------
    // Request helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> writePayload(String operationId, long offeringId,
            long occurrenceId, String targetDate, int startPeriod, int endPeriod, Long classroomId,
            String reason) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("originalOccurrenceId", Long.toString(occurrenceId));
        target.put("targetDate", targetDate);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationId", operationId);
        payload.put("offeringId", Long.toString(offeringId));
        payload.put("targets", List.of(target));
        payload.put("newStartPeriod", startPeriod);
        payload.put("newEndPeriod", endPeriod);
        if (classroomId != null) {
            payload.put("newClassroomId", Long.toString(classroomId));
        }
        payload.put("reason", reason);
        return payload;
    }

    /** 提交调课申请；重放时发送同一个 Map，报文逐字节相同。 */
    private static long submit(JsonLineClient teacher, String token, Map<String, Object> payload,
            boolean expectReplay) {
        Message response = teacher.teacher(TeacherCourseActions.SUBMIT_ADJUSTMENT, token,
                Map.of("request", payload));
        requireSuccess(response, "submit adjustment");
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result = GSON.fromJson(
                GSON.toJsonTree(response.getData("result")),
                new TypeToken<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>() { }.getType());
        require(result.isReplayed() == expectReplay,
                "submit replayed flag must be " + expectReplay + " but was " + result.isReplayed());
        require(result.getValue() != null && result.getValue().getStatus().name().equals("PENDING"),
                "a submitted request must be PENDING");
        return Long.parseLong(result.getValue().getRequestId());
    }

    private static Map<String, Object> reviewPayload(String operationId, long requestId, int version,
            boolean approved, boolean force, String overrideReason, String reviewComment) {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("operationId", operationId);
        decision.put("requestId", Long.toString(requestId));
        decision.put("expectedVersion", version);
        decision.put("approved", approved);
        decision.put("force", force);
        if (overrideReason != null) decision.put("overrideReason", overrideReason);
        if (reviewComment != null) decision.put("reviewComment", reviewComment);
        return decision;
    }

    /** 管理员审批结果：AdminOperationResultDTO 的实体字段名是 entity。 */
    private static AdjustmentRequestDetailDTO entityOf(Message response) {
        Object raw = response.getData("result");
        return GSON.fromJson(GSON.toJsonTree(raw).getAsJsonObject().get("entity"),
                AdjustmentRequestDetailDTO.class);
    }

    /** 教师写结果：TeacherOperationResultDTO 的实体字段名是 value。 */
    private static AdjustmentRequestDetailDTO teacherEntityOf(Message response) {
        Object raw = response.getData("result");
        return GSON.fromJson(GSON.toJsonTree(raw).getAsJsonObject().get("value"),
                AdjustmentRequestDetailDTO.class);
    }

    private static void review(JsonLineClient admin, String token, String operationId, long requestId,
            int version, boolean approved, boolean force, String overrideReason,
            String reviewComment) {
        Message response = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token,
                Map.of("request", reviewPayload(operationId, requestId, version, approved, force,
                        overrideReason, reviewComment)));
        requireSuccess(response, "review adjustment " + requestId);
        AdjustmentRequestDetailDTO entity = entityOf(response);
        require(entity.getStatus().name().equals(approved ? "APPROVED" : "REJECTED")
                        && entity.getVersion() == version + 1,
                "the decision must settle the request at version " + (version + 1) + " but was "
                        + entity.getStatus() + " v" + entity.getVersion());
        require(ADMIN.equals(entity.getReviewedBy()),
                "the reviewer must be the authenticated administrator, saw " + entity.getReviewedBy());
    }

    private static AdjustmentRequestDetailDTO adminDetail(JsonLineClient admin, String token,
            long requestId) {
        Message response = admin.admin(AdminCourseActions.GET_ADJUSTMENT_REQUEST, token,
                Map.of("requestId", Long.toString(requestId)));
        requireSuccess(response, "read adjustment " + requestId);
        return GSON.fromJson(GSON.toJsonTree(response.getData("adjustmentRequest")),
                AdjustmentRequestDetailDTO.class);
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private static TeacherScheduleWeekDTO teacherSchedule(JsonLineClient client, String token,
            int week) {
        Message response = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                scheduleQuery(week));
        requireSuccess(response, "teacher week " + week);
        Object raw = response.getData("schedule");
        require(raw != null, "the teacher schedule must carry the schedule key");
        return GSON.fromJson(GSON.toJsonTree(raw), TeacherScheduleWeekDTO.class);
    }

    private static List<ScheduleEntryDTO> studentSchedule(JsonLineClient client, String token,
            int week) {
        Message response = client.request("course", CourseActions.LOAD_SCHEDULE, token,
                scheduleQuery(week));
        requireSuccess(response, "student schedule week " + week);
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule")),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
    }

    private static List<CourseNoticeDTO> studentNotices(JsonLineClient client, String token,
            int week) {
        Message response = client.request("course", CourseActions.LOAD_NOTICES, token,
                scheduleQuery(week));
        requireSuccess(response, "student notices week " + week);
        return GSON.fromJson(GSON.toJsonTree(response.getData("notices")),
                new TypeToken<List<CourseNoticeDTO>>() { }.getType());
    }

    private static Map<String, Object> scheduleQuery(int week) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicYear", ACADEMIC_YEAR);
        data.put("semester", SEMESTER);
        data.put("week", week);
        return data;
    }

    private static List<String> kinds(TeacherScheduleWeekDTO week) {
        List<String> values = new ArrayList<>();
        for (TeacherScheduleEntryDTO entry : week.getEntries()) {
            values.add(entry.getOccurrenceId() + ":" + entry.getDisplayKind());
        }
        values.sort(null);
        return values;
    }

    private static TeacherScheduleEntryDTO entryOf(TeacherScheduleWeekDTO week, long occurrenceId) {
        for (TeacherScheduleEntryDTO entry : week.getEntries()) {
            if (Long.toString(occurrenceId).equals(entry.getOccurrenceId())) return entry;
        }
        throw new AssertionError("missing occurrence " + occurrenceId + " in " + kinds(week));
    }

    private static String describeSchedule(List<ScheduleEntryDTO> entries) {
        List<String> described = new ArrayList<>();
        for (ScheduleEntryDTO entry : entries) {
            described.add(entry.getAdjustmentId() + ":" + entry.getDisplayKind() + "@"
                    + entry.getDayOfWeek() + "/" + entry.getStartPeriod() + "-"
                    + (entry.getStartPeriod() + entry.getPeriodCount() - 1));
        }
        described.sort(null);
        return described.toString();
    }

    private static String describeConflicts(List<ScheduleConflictDTO> conflicts) {
        List<String> described = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            described.add(conflict.getType() + "/" + conflict.getSeverity() + "/"
                    + conflict.getSubjectId());
        }
        return described.toString();
    }

    private static void requireSuccess(Message response, String label) {
        require(response != null, label + " must return a response");
        require(response.getCode() == MessageCode.SUCCESS,
                label + " must succeed but was " + response.getCode() + ": "
                        + response.getMessage());
    }

    private static void requireCode(Message response, MessageCode expected, String what) {
        require(response != null && response.getCode() == expected,
                what + " but got " + (response == null ? "no response"
                        : response.getCode() + " (" + response.getMessage() + ")"));
    }

    // ------------------------------------------------------------------
    // Server harness and real JSON-line client
    // ------------------------------------------------------------------

    private static final class Harness {
        private final OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        private final Server server;
        private final Thread thread;

        Harness() {
            this.server = new Server(0, registry, new MessageDispatcher(), null, null);
            this.thread = new Thread(server::start, "teacher-adjustment-e2e-server");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.isRunning() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            require(server.isRunning(), "server must start listening");
        }

        void stop() {
            server.stop();
        }

        int port() {
            return server.getPort();
        }

        JsonLineClient client() throws IOException {
            return new JsonLineClient("127.0.0.1", port());
        }
    }

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

        JsonLineClient(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(this::readLoop, "teacher-adjustment-e2e-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message message = GSON.fromJson(line, Message.class);
                    if (message == null || message.getType() == MessageType.PUSH) {
                        continue;
                    }
                    CompletableFuture<Message> future = pending.remove(message.getUID());
                    if (future != null) {
                        future.complete(message);
                    }
                }
            } catch (IOException closed) {
                // socket closed by the test
            }
        }

        String login(String uid, String role) {
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireSuccess(captcha, "get captcha for " + uid);
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            Message response = request("user", "login", null, Map.of(
                    "cardNo", uid, "password", LOGIN_PASSWORD, "role", role,
                    "captchaId", captchaId, "captchaCode", CaptchaTestBridge.codeFor(captchaId)));
            requireSuccess(response, "login " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must return a token");
            require(role.equals(response.getData("role")),
                    "login must report the requested role " + role);
            return (String) token;
        }

        Message teacher(String action, String token, Map<String, Object> data) {
            return request("courseTeacher", action, token, data);
        }

        Message admin(String action, String token, Map<String, Object> data) {
            return request("courseAdmin", action, token, data);
        }

        Message request(String module, String action, String token, Map<String, Object> data) {
            Message message = new Message(MessageType.REQUEST, module, action);
            if (token != null) {
                message.setToken(token);
            }
            if (data != null) {
                data.forEach(message::putData);
            }
            CompletableFuture<Message> future = new CompletableFuture<>();
            pending.put(message.getUID(), future);
            try {
                synchronized (writer) {
                    writer.write(GSON.toJson(message));
                    writer.write("\n");
                    writer.flush();
                }
            } catch (IOException failure) {
                pending.remove(message.getUID());
                throw new IllegalStateException("send failed", failure);
            }
            try {
                return future.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("no response for " + module + "/" + action, failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ------------------------------------------------------------------
    // Fixtures and database helpers
    // ------------------------------------------------------------------

    private static void insertFixtures(String jdbc, String user, String pass) throws Exception {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                    + " VALUES"
                    + " ('" + TEACHER + "','TADJ E2E Teacher','" + SEEDED_PASSWORD_HASH + "','"
                    + SEEDED_PASSWORD_SALT + "',1,'TADJ College','Professor'),"
                    + " ('" + TEACHER_OTHER + "','TADJ E2E Teacher Other','"
                    + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                    + "',1,'TADJ College','Professor'),"
                    + " ('" + STUDENT + "','TADJ E2E Student','" + SEEDED_PASSWORD_HASH + "','"
                    + SEEDED_PASSWORD_SALT + "',2,'TADJ College','CS'),"
                    + " ('" + ADMIN + "','TADJ E2E Admin','" + SEEDED_PASSWORD_HASH + "','"
                    + SEEDED_PASSWORD_SALT + "',0,'TADJ College','Registrar')");
            execute(connection, "INSERT INTO course(course_id,course_code,course_name,credit,"
                    + "credit_hours,course_type,description,status) VALUES"
                    + " (" + COURSE_MAIN + ",'TADJE101','TADJ E2E Main',3.00,48,1,'main','ACTIVE'),"
                    + " (" + COURSE_OTHER + ",'TADJE102','TADJ E2E Other',2.00,32,1,'other',"
                    + "'ACTIVE')");
            execute(connection, "INSERT INTO course_offering(offering_id,offering_code,course_id,"
                    + "academic_year,semester,capacity,enrolled_count,status) VALUES"
                    + " (" + OFFERING_MAIN + ",'TADJE101-2027-1-A'," + COURSE_MAIN + ","
                    + ACADEMIC_YEAR + "," + SEMESTER + ",30,0,2),"
                    + " (" + OFFERING_OTHER + ",'TADJE102-2027-1-A'," + COURSE_OTHER + ","
                    + ACADEMIC_YEAR + "," + SEMESTER + ",30,0,2)");
            execute(connection, "INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES"
                    + " (" + OFFERING_MAIN + ",'" + TEACHER + "',0),"
                    + " (" + OFFERING_OTHER + ",'" + TEACHER_OTHER + "',0)");
            execute(connection, "INSERT INTO enrollment(enrollment_id,offering_id,course_id,"
                    + "academic_year,semester,uid,status,select_time) VALUES"
                    + " (" + ENROLLMENT_STUDENT + "," + OFFERING_MAIN + "," + COURSE_MAIN + ","
                    + ACADEMIC_YEAR + "," + SEMESTER + ",'" + STUDENT + "',2,'2027-01-05 00:00:00')");
            execute(connection, "INSERT INTO classroom(id,name,capacity,electric) VALUES"
                    + " (" + ROOM_A + ",'TADJ-E2E Room A',60,1),"
                    + " (" + ROOM_B + ",'TADJ-E2E Room B',60,1)");
            execute(connection, "INSERT INTO schedule_resource(id,resource_type,business_id,"
                    + "conflict_mode) VALUES"
                    + " (" + RESOURCE_A + ",'classroom','" + ROOM_A + "','EXCLUSIVE'),"
                    + " (" + RESOURCE_B + ",'classroom','" + ROOM_B + "','EXCLUSIVE')");
            execute(connection, "INSERT INTO day_template(id,name,version) VALUES"
                    + " (" + DAY_TEMPLATE + ",'TADJ full day',1)");
            insertPeriods(connection);
            execute(connection, "INSERT INTO teaching_calendar(id,name,academic_year,semester,"
                    + "week1_start_date,timezone,version,status) VALUES"
                    + " (" + CALENDAR + ",'TADJ calendar 2027-1'," + ACADEMIC_YEAR + ","
                    + SEMESTER + ",'" + WEEK_ONE_START + "','" + ZONE + "',1,'PUBLISHED')");
            insertCalendarDates(connection);
            execute(connection, "INSERT INTO schedule_plan(id,name,calendar_id,revision,status,"
                    + "created_at,updated_at) VALUES"
                    + " (" + PLAN + ",'TADJ published plan'," + CALENDAR
                    + ",1,'PUBLISHED','2027-01-01 00:00:00','2027-01-01 00:00:00')");
            execute(connection, "UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN
                    + " WHERE id=" + CALENDAR);
            execute(connection, "INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,"
                    + "offering_id,teacher_uid,assistant_uid,classroom_id,status,version) VALUES"
                    + " (" + ARRANGEMENT_MAIN + "," + PLAN + "," + OFFERING_MAIN + ",'" + TEACHER
                    + "',NULL," + ROOM_A + ",'ACTIVE',1),"
                    + " (" + ARRANGEMENT_OTHER + "," + PLAN + "," + OFFERING_OTHER + ",'"
                    + TEACHER_OTHER + "',NULL," + ROOM_B + ",'ACTIVE',1)");
            execute(connection, "INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,"
                    + "arrangement_id,weekday,start_period,end_period,status) VALUES"
                    + " (" + RULE_CROSS + "," + PLAN + "," + OFFERING_MAIN + "," + ARRANGEMENT_MAIN
                    + ",2,1,2,'ACTIVE'),"
                    + " (" + RULE_SAME + "," + PLAN + "," + OFFERING_MAIN + "," + ARRANGEMENT_MAIN
                    + ",5,5,6,'ACTIVE'),"
                    + " (" + RULE_STUDENT + "," + PLAN + "," + OFFERING_MAIN + ","
                    + ARRANGEMENT_MAIN + ",4,7,8,'ACTIVE'),"
                    + " (" + RULE_WITHDRAWN + "," + PLAN + "," + OFFERING_MAIN + ","
                    + ARRANGEMENT_MAIN + ",1,9,10,'ACTIVE'),"
                    + " (" + RULE_AFTER + "," + PLAN + "," + OFFERING_MAIN + "," + ARRANGEMENT_MAIN
                    + ",1,11,12,'ACTIVE'),"
                    + " (" + RULE_OTHER + "," + PLAN + "," + OFFERING_OTHER + ","
                    + ARRANGEMENT_OTHER + ",3,5,6,'ACTIVE'),"
                    + " (" + RULE_PARTIAL_W5 + "," + PLAN + "," + OFFERING_MAIN + ","
                    + ARRANGEMENT_MAIN + ",2,13,13,'ACTIVE'),"
                    + " (" + RULE_PARTIAL_W6 + "," + PLAN + "," + OFFERING_MAIN + ","
                    + ARRANGEMENT_MAIN + ",4,13,13,'ACTIVE')");
            execute(connection, "INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES"
                    + " (" + RULE_CROSS + ",8),(" + RULE_SAME + ",8),(" + RULE_STUDENT
                    + ",8),(" + RULE_WITHDRAWN + ",8),(" + RULE_AFTER + ",8),(" + RULE_OTHER
                    + ",8),(" + RULE_PARTIAL_W5 + ",5),(" + RULE_PARTIAL_W6 + ",6)");
            execute(connection, "INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,"
                    + "week_no,teaching_weekday) VALUES"
                    + " (" + OCC_CROSS + "," + RULE_CROSS + "," + PLAN + ",'"
                    + utcWindow(8, 2, 1, true) + "','" + utcWindow(8, 2, 2, false) + "',8,2),"
                    + " (" + OCC_SAME + "," + RULE_SAME + "," + PLAN + ",'"
                    + utcWindow(8, 5, 5, true) + "','" + utcWindow(8, 5, 6, false) + "',8,5),"
                    + " (" + OCC_STUDENT + "," + RULE_STUDENT + "," + PLAN + ",'"
                    + utcWindow(8, 4, 7, true) + "','" + utcWindow(8, 4, 8, false) + "',8,4),"
                    + " (" + OCC_WITHDRAWN + "," + RULE_WITHDRAWN + "," + PLAN + ",'"
                    + utcWindow(8, 1, 9, true) + "','" + utcWindow(8, 1, 10, false) + "',8,1),"
                    + " (" + OCC_AFTER + "," + RULE_AFTER + "," + PLAN + ",'"
                    + utcWindow(8, 1, 11, true) + "','" + utcWindow(8, 1, 12, false) + "',8,1),"
                    + " (" + OCC_OTHER + "," + RULE_OTHER + "," + PLAN + ",'"
                    + utcWindow(8, 3, 5, true) + "','" + utcWindow(8, 3, 6, false) + "',8,3),"
                    + " (" + OCC_PARTIAL_W5 + "," + RULE_PARTIAL_W5 + "," + PLAN + ",'"
                    + utcWindow(5, 2, 13, true) + "','" + utcWindow(5, 2, 13, false) + "',5,2),"
                    + " (" + OCC_PARTIAL_W6 + "," + RULE_PARTIAL_W6 + "," + PLAN + ",'"
                    + utcWindow(6, 4, 13, true) + "','" + utcWindow(6, 4, 13, false) + "',6,4)");
            execute(connection, "INSERT INTO resource_booking(plan_id,occurrence_id,resource_id,"
                    + "resource_role) VALUES"
                    + " (" + PLAN + "," + OCC_CROSS + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_SAME + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_STUDENT + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_WITHDRAWN + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_AFTER + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_OTHER + "," + RESOURCE_B + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_PARTIAL_W5 + "," + RESOURCE_A + ",'CLASSROOM'),"
                    + " (" + PLAN + "," + OCC_PARTIAL_W6 + "," + RESOURCE_A + ",'CLASSROOM')");
        });
    }

    /** 提交之后才加入的学生：同时进入被调课的教学班和冲突时段的另一个教学班。 */
    private static void enrollConflictStudent(String jdbc, String user, String pass)
            throws Exception {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                    + " VALUES('" + STUDENT_CONFLICT + "','TADJ E2E Conflict Student','"
                    + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                    + "',2,'TADJ College','CS')");
            execute(connection, "INSERT INTO enrollment(enrollment_id,offering_id,course_id,"
                    + "academic_year,semester,uid,status,select_time) VALUES"
                    + " (965702," + OFFERING_MAIN + "," + COURSE_MAIN + "," + ACADEMIC_YEAR + ","
                    + SEMESTER + ",'" + STUDENT_CONFLICT + "',2,'2027-01-06 00:00:00'),"
                    + " (965703," + OFFERING_OTHER + "," + COURSE_OTHER + "," + ACADEMIC_YEAR + ","
                    + SEMESTER + ",'" + STUDENT_CONFLICT + "',2,'2027-01-06 00:00:00')");
        });
    }

    private static void insertPeriods(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        for (int period = 1; period <= 13; period++) {
            LocalTime start = LocalTime.of(8, 0).plusMinutes((period - 1) * PERIOD_INTERVAL_MINUTES);
            LocalTime end = start.plusMinutes(PERIOD_LENGTH_MINUTES);
            rows.add("(" + DAY_TEMPLATE + "," + period + ",'" + start + ":00','" + end + ":00')");
        }
        execute(connection, "INSERT INTO period_definition(day_template_id,period_no,start_time,"
                + "end_time) VALUES" + String.join(",", rows));
    }

    private static void insertCalendarDates(Connection connection) throws SQLException {
        List<String> rows = new ArrayList<>();
        for (int week = MIN_WEEK; week <= MAX_WEEK; week++) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                long id = 966000L + (week - 1) * 7L + weekday;
                LocalDate date = localDate(week, weekday);
                int teachingDay = weekday == 7 ? 0 : 1;
                rows.add("(" + id + "," + CALENDAR + ",'" + date + "'," + week + "," + weekday
                        + "," + DAY_TEMPLATE + "," + teachingDay + ")");
            }
        }
        execute(connection, "INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                + "teaching_weekday,day_template_id,is_teaching_day) VALUES"
                + String.join(",", rows));
    }

    /** The fixture window of one period pair on one teaching day, as a UTC wall clock string. */
    private static String utcWindow(int week, int weekday, int period, boolean start) {
        LocalTime time = LocalTime.of(8, 0).plusMinutes((period - 1) * PERIOD_INTERVAL_MINUTES);
        if (!start) {
            time = time.plusMinutes(PERIOD_LENGTH_MINUTES);
        }
        Instant instant = ZonedDateTime.of(localDate(week, weekday), time, ZoneId.of(ZONE))
                .toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace('T', ' ');
    }

    private static LocalDate localDate(int week, int weekday) {
        return WEEK_ONE_START.plusDays((week - 1) * 7L + weekday - 1L);
    }

    private static void cleanFixtures(String jdbc, String user, String pass) throws Exception {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "DELETE FROM course_notice WHERE adjustment_request_id IN"
                    + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                    + " LIKE 'tadje2e-%')");
            execute(connection, "DELETE FROM admin_course_operation_log WHERE admin_uid LIKE"
                    + " 'tadje2e-%'");
            execute(connection, "DELETE FROM course_schedule_adjustment WHERE request_id IN"
                    + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                    + " LIKE 'tadje2e-%')");
            execute(connection, "DELETE FROM course_schedule_adjustment_target WHERE request_id IN"
                    + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                    + " LIKE 'tadje2e-%')");
            execute(connection, "DELETE FROM course_schedule_adjustment_request WHERE requested_by"
                    + " LIKE 'tadje2e-%'");
            execute(connection, "DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE"
                    + " 'tadje2e-%'");
            execute(connection, "DELETE FROM resource_booking WHERE plan_id=" + PLAN);
            execute(connection, "DELETE FROM enrollment WHERE offering_id IN (" + OFFERING_MAIN
                    + "," + OFFERING_OTHER + ")");
            execute(connection, "DELETE FROM course_occurrence WHERE plan_id=" + PLAN);
            execute(connection, "DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN "
                    + RULE_CROSS + " AND " + RULE_PARTIAL_W6);
            execute(connection, "DELETE FROM course_schedule_rule WHERE plan_id=" + PLAN);
            execute(connection, "DELETE FROM course_schedule_arrangement WHERE plan_id=" + PLAN);
            execute(connection, "UPDATE teaching_calendar SET current_schedule_plan_id=NULL"
                    + " WHERE id=" + CALENDAR);
            execute(connection, "DELETE FROM schedule_plan WHERE id=" + PLAN);
            execute(connection, "DELETE FROM calendar_date WHERE calendar_id=" + CALENDAR);
            execute(connection, "DELETE FROM period_definition WHERE day_template_id="
                    + DAY_TEMPLATE);
            execute(connection, "DELETE FROM day_template WHERE id=" + DAY_TEMPLATE);
            execute(connection, "DELETE FROM course_offering_teacher WHERE offering_id IN ("
                    + OFFERING_MAIN + "," + OFFERING_OTHER + ")");
            execute(connection, "DELETE FROM course_offering WHERE offering_id IN (" + OFFERING_MAIN
                    + "," + OFFERING_OTHER + ")");
            execute(connection, "DELETE FROM course WHERE course_id IN (" + COURSE_MAIN + ","
                    + COURSE_OTHER + ")");
            execute(connection, "DELETE FROM schedule_resource WHERE id IN (" + RESOURCE_A + ","
                    + RESOURCE_B + ")");
            execute(connection, "DELETE FROM classroom WHERE id IN (" + ROOM_A + "," + ROOM_B + ")");
            execute(connection, "DELETE FROM teaching_calendar WHERE id=" + CALENDAR);
            // LIKE 'tadje2e-%' 与 LEFT(...) 等价：前缀里没有 `_` 通配符，删除谓词保持同样窄。
            execute(connection, "DELETE FROM tbl_user WHERE LEFT(UID, 8) = 'tadje2e-'");
        });
        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT COUNT(*) FROM teaching_calendar WHERE id="
                    + CALENDAR) == 0, "cleanup must remove the fixture calendar");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_occurrence WHERE plan_id="
                    + PLAN) == 0, "cleanup must remove every fixture occurrence");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment WHERE"
                    + " request_id IN (SELECT request_id FROM course_schedule_adjustment_request"
                    + " WHERE requested_by LIKE 'tadje2e-%')") == 0,
                    "cleanup must remove every fixture adjustment");
            require(queryInt(connection, "SELECT COUNT(*) FROM tbl_user WHERE LEFT(UID, 8)"
                    + " = 'tadje2e-'") == 0, "cleanup must remove every fixture user");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering WHERE offering_id"
                    + " IN (2001,2002)") == 2
                    && queryInt(connection, "SELECT COUNT(*) FROM course_occurrence WHERE id=4201")
                    == 1,
                    "cleanup must never touch the shared seeded rows");
        });
    }

    /** Canonical text of the published base plan the whole closed loop must never mutate. */
    private static String baseChecksum(String jdbc, String user, String pass) throws Exception {
        StringBuilder checksum = new StringBuilder();
        assertDatabase(jdbc, user, pass, connection -> {
            checksum.append(text(connection, "SELECT CONCAT(id,'|',status,'|',revision) FROM"
                    + " schedule_plan WHERE id=" + PLAN));
            checksum.append(text(connection, "SELECT GROUP_CONCAT(CONCAT(id,'|',weekday,'|',"
                    + "start_period,'|',end_period,'|',status) ORDER BY id SEPARATOR ',') FROM"
                    + " course_schedule_rule WHERE plan_id=" + PLAN));
            checksum.append(text(connection, "SELECT GROUP_CONCAT(CONCAT(rule_id,'|',week_no)"
                    + " ORDER BY rule_id,week_no SEPARATOR ',') FROM course_schedule_rule_week"
                    + " WHERE rule_id BETWEEN " + RULE_CROSS + " AND " + RULE_PARTIAL_W6));
            checksum.append(text(connection, "SELECT GROUP_CONCAT(CONCAT(id,'|',rule_id,'|',"
                    + "start_at,'|',end_at,'|',week_no,'|',teaching_weekday) ORDER BY id SEPARATOR"
                    + " ',') FROM course_occurrence WHERE plan_id=" + PLAN));
            checksum.append(text(connection, "SELECT GROUP_CONCAT(CONCAT(id,'|',occurrence_id,"
                    + "'|',resource_id,'|',resource_role) ORDER BY id SEPARATOR ',') FROM"
                    + " resource_booking WHERE plan_id=" + PLAN));
            checksum.append(text(connection, "SELECT GROUP_CONCAT(CONCAT(arrangement_id,'|',"
                    + "offering_id,'|',teacher_uid,'|',classroom_id,'|',status,'|',version)"
                    + " ORDER BY arrangement_id SEPARATOR ',') FROM course_schedule_arrangement"
                    + " WHERE plan_id=" + PLAN));
        });
        return checksum.toString();
    }

    /** The adjustment id the approval wrote for one request (exactly one per target). */
    private static long adjustmentOf(String jdbc, String user, String pass, long requestId)
            throws Exception {
        final long[] found = {-1};
        assertDatabase(jdbc, user, pass, connection -> found[0] = queryLong(connection,
                "SELECT adjustment_id FROM course_schedule_adjustment WHERE request_id=" + requestId));
        require(found[0] > 0, "the approval of request " + requestId + " must write one adjustment");
        return found[0];
    }

    @FunctionalInterface
    private interface SqlAssertion {
        void run(Connection connection) throws SQLException;
    }

    private static void assertDatabase(String jdbc, String user, String pass, SqlAssertion assertion)
            throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            assertion.run(connection);
        }
    }

    private static Connection connect(String jdbc, String user, String pass) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbc, user, pass);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

    private static int count(String jdbc, String user, String pass, String sql) throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            return queryInt(connection, sql);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        return (int) queryLong(connection, sql);
    }

    private static long queryLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row: " + sql);
            return rows.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row: " + sql);
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // Schema reset and fresh-order seed
    // ------------------------------------------------------------------

    private static void resetAndSeed(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            Object liveDatabase = queryTextOrNull(connection, "SELECT DATABASE()");
            require(liveDatabase != null, "the connection must report its database");
            require(TEST_DATABASE.equals(liveDatabase.toString()),
                    "refusing live end-to-end test: connected database must be exactly "
                            + TEST_DATABASE + " but was " + liveDatabase);
            requireTestDatabase(jdbc);
            resetTestSchema(connection);
            applyScript(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/seed-course-test.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V006_teacher_adjustment_requests.sql"));
        }
    }

    private static Object queryTextOrNull(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
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
                execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            String normalized = statements.get(i).replaceAll("(?m)^\\s*--.*$", "")
                    .trim().toUpperCase(Locale.ROOT);
            if ("init.sql".equals(path.getFileName().toString())
                    && (normalized.startsWith("CREATE DATABASE") || normalized.startsWith("USE "))) {
                continue;
            }
            try {
                requireTestSchema(connection);
                execute(connection, statements.get(i));
                requireTestSchema(connection);
            } catch (SQLException failure) {
                throw new SQLException("failed applying " + path.getFileName()
                        + " statement " + (i + 1), failure);
            }
        }
    }

    private static void requireTestSchema(Connection connection) throws SQLException {
        Object database = connection.getCatalog();
        require(TEST_DATABASE.equals(database),
                "SQL fixture escaped the protected test schema: " + database);
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

    // ------------------------------------------------------------------
    // Guard, config and misc helpers
    // ------------------------------------------------------------------

    private static void pointDBUtilAt(String testUrl, Properties properties) throws Exception {
        setStatic(util.DBUtil.class, "url", testUrl);
        setStatic(util.DBUtil.class, "username", requiredProperty(properties, "db.username"));
        setStatic(util.DBUtil.class, "password", requiredProperty(properties, "db.password"));
    }

    private static void setStatic(Class<?> type, String name, String value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "refusing live end-to-end test: JDBC database must be"
                + " exactly " + TEST_DATABASE + " but was " + database);
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
    }

    private static Properties loadProperties(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing ignored local db.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "missing database property: " + key);
        return value;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("repository root was not found");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
