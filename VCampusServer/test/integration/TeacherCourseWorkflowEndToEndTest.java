package integration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dto.course.CourseActions;
import dto.course.CourseNoticeDTO;
import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeCorrectionChangeDTO;
import dto.course.admin.approval.GradeCorrectionComparisonDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import handler.AdminCourseHandler;
import handler.CourseHandler;
import handler.TeacherCourseHandler;
import network.CourseFileServer;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import service.TeacherFileTicketService;
import service.TeacherGradeBookService;
import service.TeacherGradeImportService;
import service.TeacherGradeImportStore;
import util.DBUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * 任务六的<b>完整验收闭环</b>：一条真实 TCP/MySQL 链路把教师、管理员、学生三种真实登录会话串起来，
 * 外加一个真实文件端口上的短连接上传。
 *
 * <p>为什么必须是一条链路而不是若干条各自成立的片段：本计划前面的每个套件都只对着自己那一半的
 * 夹具断言——教师侧的 JSON 契约、管理员侧的审批快照、学生侧的读取投影，各自都能在对方漂移之后
 * 继续通过。这里的三段闭环各自跨过接缝：
 *
 * <ul>
 *   <li><b>查询 → 跨周申请 → 撤销/审批竞争 → 学生两周课表</b>：教师提交的申请经管理员审批后写出的
 *       调课，就是学生 {@code course.loadSchedule} 在<b>两个</b>教学周里读到的同一行；撤销与审批
 *       各只有一条方向能赢，输掉的一方必须一条调整都不写。</li>
 *   <li><b>手工草稿 → Excel 导入 → 提交 → 驳回 → 重提批准 → 更正 → 学生 GPA</b>：管理员审批的
 *       批次是教师这一趟上传的表格经确认写进草稿后提交的；更正批次的管理员详情里那份「更正对比」
 *       的旧值来自被冻结的上一批，学生最终读到的总评与按学分加权 GPA 由这一次更正真的推动。</li>
 *   <li><b>我的申请与未读同步</b>：两张事实表（调课申请与成绩批次）在服务端 SQL 里合并分页，
 *       类型/状态筛选走各自的白名单；一行结果在读过之后变已读，状态一变（撤销/审批）重新变未读，
 *       过期的那次已读确认被拒并带回当前行。</li>
 * </ul>
 *
 * <p>夹具用 986xxx 区间与 {@code twe986-} UID 前缀（已占用：947xxx、948xxx、949xxx、965xxx、
 * 966xxx、983xxx、984xxx、985xxx、{@code gra974-}），只允许 {@code virtual_campus_course_test}。
 * 它会重建受保护的测试架构（{@code tbl_user → V001/V002/V003 → 旧 seed → V004 → V005/V006/V007}），
 * 因此必须串行运行，本类不做任何并行；结束时只删自己的行，并断言共用的 seed 行完好。
 */
public final class TeacherCourseWorkflowEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final String PREFIX = "twe986-";
    private static final String TEACHER = PREFIX + "teacher";
    private static final String TEACHER_NAME = "Twe986 Teacher";
    private static final String ADMIN = PREFIX + "admin";
    private static final String STUDENT_ONE = PREFIX + "s1";
    private static final String STUDENT_TWO = PREFIX + "s2";
    private static final String PASSWORD = "course-test-only";
    /** seed-course-test.sql 的测试口令哈希/盐：本测试的账号沿用它们登录。 */
    private static final String SEEDED_PASSWORD_HASH =
            "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String SEEDED_PASSWORD_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";

    private static final int YEAR = 2027;
    private static final int SEMESTER = 1;
    private static final String ZONE = "Asia/Shanghai";
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2027, 2, 22);
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 10;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final int PERIOD_LENGTH_MINUTES = 45;

    private static final long COURSE_ADJ = 986101L;
    private static final long COURSE_GRADES = 986102L;
    private static final long COURSE_SECOND = 986103L;
    private static final long OFFERING_ADJ = 986201L;
    private static final long OFFERING_GRADES = 986202L;
    private static final long OFFERING_SECOND = 986203L;
    private static final long ENROLL_ADJ = 986301L;
    private static final long ENROLL_GRADES_ONE = 986401L;
    private static final long ENROLL_GRADES_TWO = 986402L;
    private static final long ENROLL_SECOND = 986501L;

    private static final long CALENDAR = 986601L;
    private static final long DAY_TEMPLATE = 986602L;
    private static final long PLAN = 986603L;
    private static final long ROOM_A = 986701L;
    private static final long ROOM_B = 986702L;
    private static final long RESOURCE_A = 986711L;
    private static final long RESOURCE_B = 986712L;
    private static final long ARRANGEMENT = 986801L;
    private static final long RULE_CROSS = 986811L;
    private static final long RULE_RACE_WITHDRAWN = 986812L;
    private static final long RULE_RACE_APPROVED = 986813L;
    private static final long OCC_CROSS = 986821L;
    private static final long OCC_RACE_WITHDRAWN = 986822L;
    private static final long OCC_RACE_APPROVED = 986823L;
    /** 教学日历日期行：一周 7 天，从 986850 起。 */
    private static final long CALENDAR_DATE_FIRST = 986850L;

    /** 第 8 周周二（跨周原位置）、第 9 周周三（跨周目标）、第 8 周周三 / 周六（同周竞争的落点）。 */
    private static final String CROSS_TARGET_DATE = "2027-04-21";
    private static final String SAME_WEEK_TARGET_DATE = "2027-04-14";
    private static final String SATURDAY_TARGET_DATE = "2027-04-17";
    private static final String CROSS_ORIGINAL_DATE = "2027-04-13";

    /** 40/20/–/40：四项里实验被禁用，总评 = 0.4·平时 + 0.2·期中 + 0.4·期末。 */
    private static final GradeSchemeDTO SCHEME = new GradeSchemeDTO(List.of(
            new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 4000),
            new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
            new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
            new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 4000)));

    private static final String CORRECTION_REASON = "期末成绩登分错误，申请更正";

    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type OFFERING_ITEMS =
            new TypeToken<List<TeacherOfferingDTO>>() { }.getType();
    private static final java.lang.reflect.Type APPLICATION_ROWS =
            new TypeToken<List<TeacherApplicationDTO>>() { }.getType();
    private static final java.lang.reflect.Type TERMS =
            new TypeToken<List<CourseTermDTO>>() { }.getType();
    private static final java.lang.reflect.Type ARRANGEMENTS =
            new TypeToken<List<ScheduleArrangementDTO>>() { }.getType();
    private static final java.lang.reflect.Type ROSTER_ROWS =
            new TypeToken<List<TeacherRosterRowDTO>>() { }.getType();
    private static final java.lang.reflect.Type SCHEDULE_ENTRIES =
            new TypeToken<List<ScheduleEntryDTO>>() { }.getType();
    private static final java.lang.reflect.Type NOTICES =
            new TypeToken<List<CourseNoticeDTO>>() { }.getType();

    private static Path workDirectory;

    private TeacherCourseWorkflowEndToEndTest() {
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
            System.out.println("SKIP: no `mysql` argument, so the complete teacher workflow chain was"
                    + " not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String jdbc = withTestAuthentication(url);
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");

        System.out.println("[E2E] fresh install order (tbl_user -> V001 -> V002 -> V003 -> seed ->"
                + " V004 -> V005 -> V006 -> V007)");
        resetAndInstall(jdbc, username, password, root);
        pointDBUtilAt(jdbc, properties);
        insertFixtures(jdbc, username, password);
        LoginSchemaTestBridge.ensureBankAccountTable();
        workDirectory = Files.createTempDirectory("teacher-workflow-e2e-");

        Server server = null;
        Thread serverThread = null;
        TeacherFileTicketService tickets = null;
        try {
            TeacherGradeBookService grades = new TeacherGradeBookService();
            tickets = new TeacherFileTicketService(0);
            CourseFileServer fileServer = new CourseFileServer(tickets, 0);
            TeacherGradeImportService imports = new TeacherGradeImportService(tickets, grades,
                    new TeacherGradeImportStore());
            MessageDispatcher dispatcher = new MessageDispatcher(new CourseHandler(),
                    new AdminCourseHandler(),
                    new TeacherCourseHandler(new TeacherCourseQueryService(),
                            new TeacherAdjustmentApplicationService(), grades, tickets, imports));
            server = new Server(0, new OnlineConnectionRegistry(), dispatcher, null, null,
                    fileServer);
            serverThread = new Thread(server::start, "teacher-workflow-e2e-server");
            serverThread.setDaemon(true);
            serverThread.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.isRunning() && System.currentTimeMillis() < deadline) Thread.sleep(20);
            require(server.isRunning(), "the business server must start listening");
            int filePort = fileServer.getPort();
            require(filePort > 0 && filePort != server.getPort(),
                    "the file listener must bind its own port");

            runScenarios(server.getPort(), filePort, tickets.getTempDirectory());

            server.stop();
            serverThread.join(5_000);
            require(!serverThread.isAlive(), "the business server must stop");
        } finally {
            if (tickets != null) tickets.close();
            if (server != null) server.stop();
            if (serverThread != null) serverThread.join(5_000);
            if (workDirectory != null) deleteRecursively(workDirectory);
            cleanFixtures(jdbc, username, password);
        }
        require(number(jdbc, username, password, "SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '"
                        + PREFIX + "%'") == 0
                        && number(jdbc, username, password, "SELECT COUNT(*) FROM course_offering"
                        + " WHERE offering_id BETWEEN 986200 AND 986299") == 0
                        && number(jdbc, username, password, "SELECT COUNT(*) FROM grade WHERE"
                        + " enrollment_id BETWEEN 986300 AND 986599") == 0,
                "cleanup must leave no fixture row behind");
        require(number(jdbc, username, password, "SELECT COUNT(*) FROM course_offering WHERE"
                        + " offering_id IN (2001,2002)") == 2,
                "the shared seeded rows must survive the whole chain");
        System.out.println("Teacher course workflow end-to-end test passed.");
    }

    // ---------------------------------------------------------------- scenarios

    private static void runScenarios(int businessPort, int filePort, Path tempDirectory)
            throws Exception {
        try (JsonLineClient teacher = new JsonLineClient(businessPort);
             JsonLineClient admin = new JsonLineClient(businessPort);
             JsonLineClient studentOne = new JsonLineClient(businessPort);
             JsonLineClient studentTwo = new JsonLineClient(businessPort)) {
            String teacherToken = teacher.login(TEACHER, "教师");
            String adminToken = admin.login(ADMIN, "管理员");
            String studentOneToken = studentOne.login(STUDENT_ONE, "学生");
            String studentTwoToken = studentTwo.login(STUDENT_TWO, "学生");

            teacherQueries(teacher, teacherToken, studentOne, studentOneToken);

            long crossRequest = crossWeekRequest(teacher, teacherToken, admin, adminToken);
            verifyCrossWeekReachesBothWeeks(teacher, teacherToken, studentOne, studentOneToken,
                    crossRequest);

            raceForThePendingRequest(teacher, teacherToken, admin, adminToken);
            verifyUnreadStateSync(teacher, teacherToken, crossRequest);

            long correctedBatch = gradeChain(teacher, teacherToken, admin, adminToken, studentOne,
                    studentOneToken, studentTwo, studentTwoToken, filePort, tempDirectory);
            verifyStudentGpaAfterCorrection(teacher, teacherToken, admin, adminToken, studentOne,
                    studentOneToken, correctedBatch);
            verifyMergedApplications(teacher, teacherToken);
        }
    }

    // ------------------------------------------------------ 教师查询（真实登录会话）

    /** 教师本人的学期、教学班、教学班详情、名单与本周课表：一条只读链路，全部经真实会话。 */
    private static void teacherQueries(JsonLineClient teacher, String token, JsonLineClient student,
            String studentToken) throws Exception {
        List<CourseTermDTO> terms = GSON.fromJson(GSON.toJsonTree(teacher.request("courseTeacher",
                TeacherCourseActions.LIST_TERMS, token, Map.of()).getData("terms")), TERMS);
        require(terms.stream().anyMatch(term -> term.getAcademicYear() == YEAR
                        && term.getSemester() == SEMESTER),
                "the teacher's own term list must contain the fixture term, saw " + terms.size()
                        + " terms");

        List<TeacherOfferingDTO> offerings = listOfferings(teacher, token);
        require(offerings.size() == 3,
                "the teacher must see exactly the three fixture offerings, saw " + offerings.size());
        require(offerings.stream().allMatch(item -> item.getAcademicYear() == YEAR
                        && item.getSemester() == SEMESTER),
                "every listed offering must sit in the fixture term");
        require(offering(offerings, OFFERING_ADJ).getOfferingCode().equals("TWEA986-01")
                        && offering(offerings, OFFERING_GRADES).getOfferingCode().equals(
                                "TWEB986-01"),
                "the offering codes must be the fixture codes, saw "
                        + offerings.stream().map(TeacherOfferingDTO::getOfferingCode).toList());

        TeacherOfferingDetailDTO detail = GSON.fromJson(GSON.toJsonTree(teacher.request(
                "courseTeacher", TeacherCourseActions.GET_OFFERING, token,
                Map.of("offeringId", Long.toString(OFFERING_ADJ))).getData("offering")),
                TeacherOfferingDetailDTO.class);
        require(detail.getOffering() != null
                        && Long.toString(OFFERING_ADJ).equals(detail.getOffering().getOfferingId())
                        && detail.getOffering().isCanRequestAdjustment(),
                "the teaching-class detail must report the class as adjustable");
        require(detail.getTeachers() != null && detail.getTeachers().size() == 1
                        && TEACHER.equals(detail.getTeachers().get(0).getBusinessId()),
                "the detail must name the fixture teacher as the class's only teacher");

        JsonObject roster = GSON.toJsonTree(teacher.request("courseTeacher",
                TeacherCourseActions.LIST_OFFERING_STUDENTS, token,
                Map.of("offeringId", Long.toString(OFFERING_ADJ), "page", 1, "size", 20))
                .getData("students")).getAsJsonObject();
        List<TeacherRosterRowDTO> rows = GSON.fromJson(roster.get("items"), ROSTER_ROWS);
        require(rows.size() == 1 && STUDENT_ONE.equals(rows.get(0).getStudentUid()),
                "the roster must list the enrolled fixture student, saw " + rows.size() + " rows");

        List<ScheduleArrangementDTO> arrangements = GSON.fromJson(GSON.toJsonTree(teacher.request(
                "courseTeacher", TeacherCourseActions.LIST_OFFERING_SCHEDULES, token,
                Map.of("offeringId", Long.toString(OFFERING_ADJ))).getData("schedules")),
                ARRANGEMENTS);
        require(arrangements.size() == 1
                        && Long.toString(ARRANGEMENT).equals(arrangements.get(0).getArrangementId()),
                "the class must expose its one published arrangement, saw " + arrangements.size());

        TeacherScheduleWeekDTO week = teacherSchedule(teacher, token, 8);
        require(week.getEntries().size() == 3 && kinds(week).equals(List.of(
                        "986821:NORMAL", "986822:NORMAL", "986823:NORMAL")),
                "before any move the teacher's week 8 must hold the three fixture occurrences, saw "
                        + kinds(week));
        // 周次范围来自已发布的教学日历；currentWeek 由服务端时钟决定，夹具学期不在今天，
        // 因此这里只钉范围，不伪造一个「本周」。
        require(week.getMinWeek() == MIN_WEEK && week.getMaxWeek() == MAX_WEEK,
                "the week range must come from the published calendar, saw "
                        + week.getMinWeek() + ".." + week.getMaxWeek());
        System.out.println("[E2E] teacher queries: terms, offerings, detail, roster, one published"
                + " arrangement and week 8 read over the real session");
    }

    // ------------------------------------------------- 跨周申请 → 两周课表

    /** 第 8 周周二第 1-2 节申请调到第 9 周周三第 3-4 节，管理员批准后两周各自只留自己那一半。 */
    private static long crossWeekRequest(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken) throws Exception {
        TeacherAdjustmentOptionsDTO options = GSON.fromJson(GSON.toJsonTree(teacher.request(
                "courseTeacher", TeacherCourseActions.GET_ADJUSTMENT_OPTIONS, teacherToken,
                Map.of("offeringId", Long.toString(OFFERING_ADJ), "originalOccurrenceId",
                        Long.toString(OCC_CROSS))).getData("options")),
                TeacherAdjustmentOptionsDTO.class);
        require(options.getDates().stream().anyMatch(date -> CROSS_TARGET_DATE.equals(
                        date.getDate()) && date.isTeachingDay()),
                "the adjustment options must offer " + CROSS_TARGET_DATE + " as a teaching day");

        Map<String, Object> payload = writePayload(op(1), OFFERING_ADJ, OCC_CROSS,
                CROSS_TARGET_DATE, 3, 4, ROOM_B, "twe986 跨周调课");
        Message preview = teacher.request("courseTeacher", TeacherCourseActions.PREVIEW_ADJUSTMENT,
                teacherToken, Map.of("request", payload));
        requireCode(preview, MessageCode.SUCCESS, "preview the cross-week target");
        TeacherAdjustmentPreviewDTO clean = GSON.fromJson(
                GSON.toJsonTree(preview.getData("conflicts")),
                TeacherAdjustmentPreviewDTO.class);
        require(clean.isCanSubmit() && clean.getConflicts().isEmpty(),
                "the cross-week target must be free for both teacher and student, saw "
                        + clean.getConflicts());

        Message unchanged = teacher.request("courseTeacher", TeacherCourseActions.PREVIEW_ADJUSTMENT,
                teacherToken, Map.of("request", writePayload(op(1), OFFERING_ADJ, OCC_CROSS,
                        CROSS_ORIGINAL_DATE, 1, 2, null, "twe986 原样目标")));
        requireCode(unchanged, MessageCode.BAD_REQUEST,
                "a target identical to the original arrangement must be refused");

        long requestId = submit(teacher, teacherToken, payload, false);
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment") == 0
                        && count("SELECT COUNT(*) FROM course_notice WHERE adjustment_request_id="
                        + requestId) == 0,
                "a submitted request must write neither an adjustment nor a notice");

        review(admin, adminToken, op(2), requestId, 1, true, false, null, "同意");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id=" + requestId)
                        == 1,
                "the approval must write exactly one adjustment for the request");
        return requestId;
    }

    /** 教师侧与学生侧各自读第 8 周与第 9 周：原位置只留提示块，新位置只在目标周出现。 */
    private static void verifyCrossWeekReachesBothWeeks(JsonLineClient teacher, String teacherToken,
            JsonLineClient student, String studentToken, long requestId) throws Exception {
        long adjustmentId = adjustmentOf(requestId);
        TeacherScheduleWeekDTO origin = teacherSchedule(teacher, teacherToken, 8);
        require(kinds(origin).equals(List.of(
                        "986821:ADJUSTED_ORIGINAL", "986822:NORMAL", "986823:NORMAL")),
                "the teacher's origin week must keep only the original marker, saw " + kinds(origin));
        TeacherScheduleEntryDTO original = entryOf(origin, OCC_CROSS);
        require(CROSS_ORIGINAL_DATE.equals(original.getLocalDate())
                        && original.getDayOfWeek() == 2 && original.getStartPeriod() == 1
                        && Long.toString(adjustmentId).equals(original.getAdjustmentId())
                        && !original.isCanRequestAdjustment(),
                "the original marker must stay on Tuesday week 8 periods 1-2 and stop being"
                        + " adjustable, saw " + original.getLocalDate());

        TeacherScheduleWeekDTO target = teacherSchedule(teacher, teacherToken, 9);
        require(kinds(target).equals(List.of("986821:ADJUSTED_TARGET")),
                "the teacher's target week must hold only the moved block, saw " + kinds(target));
        TeacherScheduleEntryDTO moved = target.getEntries().get(0);
        require(CROSS_TARGET_DATE.equals(moved.getLocalDate()) && moved.getStartPeriod() == 3
                        && moved.getEndPeriod() == 4 && "TWE986 Room B".equals(moved.getLocation()),
                "the moved block must sit on " + CROSS_TARGET_DATE + " periods 3-4 in Room B, saw "
                        + moved.getLocalDate() + " " + moved.getLocation());

        // 学生端走的是既有的 course.loadSchedule：两周各自读一次，形状与教师侧的同源。
        List<ScheduleEntryDTO> studentOrigin = studentSchedule(student, studentToken, 8);
        require(studentOrigin.size() == 3
                        && studentOrigin.stream().filter(entry -> entry.getDisplayKind()
                        == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL).count() == 1
                        && studentOrigin.stream().anyMatch(entry -> entry.getDisplayKind()
                        == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL && entry.getDayOfWeek() == 2
                        && entry.getStartPeriod() == 1
                        && Long.toString(adjustmentId).equals(entry.getAdjustmentId())),
                "the student's origin week must keep only the original marker, saw "
                        + describeSchedule(studentOrigin));
        List<ScheduleEntryDTO> studentTarget = studentSchedule(student, studentToken, 9);
        require(studentTarget.size() == 1
                        && studentTarget.get(0).getDisplayKind()
                        == ScheduleDisplayKindDTO.ADJUSTED_TARGET
                        && Long.toString(adjustmentId).equals(studentTarget.get(0).getAdjustmentId())
                        && studentTarget.get(0).getDayOfWeek() == 3
                        && studentTarget.get(0).getStartPeriod() == 3
                        && studentTarget.get(0).getPeriodCount() == 2
                        && "TWE986 Room B".equals(studentTarget.get(0).getLocation()),
                "the student's target week must hold only the moved block, saw "
                        + describeSchedule(studentTarget));
        require(noticeIds(student, studentToken, 8).size() == 1
                        && noticeIds(student, studentToken, 9).size() == 1,
                "the rescheduled notice must appear once in each of the two weeks, saw "
                        + noticeIds(student, studentToken, 8) + " / "
                        + noticeIds(student, studentToken, 9));
        System.out.println("[E2E] cross-week move: teacher and student each read the original"
                + " marker in week 8 and the moved block in week 9");
    }

    // ------------------------------------------------- 撤销 / 审批竞争

    /** 先撤销的申请不能被审批，先审批的申请不能被撤销，两个方向输掉的一方都不得写调整。 */
    private static void raceForThePendingRequest(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken) throws Exception {
        long withdrawnRequest = submit(teacher, teacherToken, writePayload(op(3), OFFERING_ADJ,
                OCC_RACE_WITHDRAWN, SAME_WEEK_TARGET_DATE, 11, 12, null, "twe986 先撤销"), false);
        Message withdrawn = teacher.request("courseTeacher", TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                teacherToken, Map.of("request", Map.of(
                        "operationId", op(4),
                        "requestId", Long.toString(withdrawnRequest),
                        "expectedVersion", 1)));
        requireCode(withdrawn, MessageCode.SUCCESS, "withdraw the pending request");
        require("WITHDRAWN".equals(teacherEntityOf(withdrawn).getStatus().name())
                        && teacherEntityOf(withdrawn).getVersion() == 2,
                "the withdrawal must settle the request at WITHDRAWN v2");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id="
                        + withdrawnRequest + " AND withdrawn_at IS NOT NULL AND reviewed_at IS NULL"
                        + " AND reviewed_by IS NULL") == 1,
                "a withdrawal is the applicant's own terminal state, not a reviewer's decision");

        Message refusedApproval = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST,
                adminToken, Map.of("request", reviewPayload(op(5), withdrawnRequest, 2, true, false,
                        null, null)));
        requireCode(refusedApproval, MessageCode.CONFLICT,
                "an already withdrawn request must not be approved");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + withdrawnRequest) == 0,
                "the losing approval must write no adjustment");

        long approvedRequest = submit(teacher, teacherToken, writePayload(op(6), OFFERING_ADJ,
                OCC_RACE_APPROVED, SATURDAY_TARGET_DATE, 7, 8, null, "twe986 先审批"), false);
        review(admin, adminToken, op(7), approvedRequest, 1, true, false, null, null);
        Message refusedWithdrawal = teacher.request("courseTeacher",
                TeacherCourseActions.WITHDRAW_ADJUSTMENT, teacherToken, Map.of("request", Map.of(
                        "operationId", op(8),
                        "requestId", Long.toString(approvedRequest),
                        "expectedVersion", 1)));
        requireCode(refusedWithdrawal, MessageCode.CONFLICT,
                "an approved request must not be withdrawn, even with a stale version");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE request_id="
                        + approvedRequest) == 1,
                "the losing withdrawal must not touch the committed adjustment");
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment") == 2,
                "exactly the two approved requests may have written an adjustment");
        System.out.println("[E2E] withdraw vs approval: exactly one state transition wins per"
                + " direction and the loser writes nothing");
    }

    // ------------------------------------------------- 我的申请与未读同步

    /** 已读是 compare-and-set：读过的行变已读，状态一变重新未读，过期的确认被拒并带回当前行。 */
    private static void verifyUnreadStateSync(JsonLineClient teacher, String teacherToken,
            long crossRequest) throws Exception {
        List<TeacherApplicationDTO> rows = applications(teacher, teacherToken, null, null);
        require(rows.size() == 3,
                "the merged list must hold the three adjustment applications, saw " + rows.size());
        require(rows.stream().allMatch(TeacherApplicationDTO::isUnread),
                "every freshly submitted or decided row starts unread");
        require(rows.stream().allMatch(row -> TeacherApplicationDTO.SCHEDULE_ADJUSTMENT.equals(
                        row.getType())),
                "before any grade batch exists only adjustment rows may appear");

        TeacherApplicationDTO cancelled = only(rows, Long.toString(crossRequest));
        require("APPROVED".equals(cancelled.getStatus()) && !cancelled.isCanWithdraw()
                        && cancelled.getHandledAt() != null,
                "the cross-week request must now read back as a decided, non-withdrawable row");
        require(applications(teacher, teacherToken, null, "APPROVED").size() == 2
                        && applications(teacher, teacherToken, null, "PENDING").isEmpty()
                        && applications(teacher, teacherToken, null, "WITHDRAWN").size() == 1,
                "the status filter must select from the union of both status alphabets");
        require(applications(teacher, teacherToken, TeacherApplicationDTO.GRADE_SUBMISSION, null)
                        .isEmpty(),
                "the grade-submission filter must be empty before the grade chain runs");
        expectFailure(teacher, teacherToken, TeacherCourseActions.LIST_MY_APPLICATIONS,
                MessageCode.BAD_REQUEST,
                Map.of("type", "NOT_A_TYPE", "page", 1, "size", 20),
                "an unknown application type must be refused, not silently ignored");
        expectFailure(teacher, teacherToken, TeacherCourseActions.LIST_MY_APPLICATIONS,
                MessageCode.BAD_REQUEST,
                Map.of("type", TeacherApplicationDTO.GRADE_SUBMISSION, "status", "WITHDRAWN",
                        "page", 1, "size", 20),
                "WITHDRAWN must be refused for grade submissions, which have no such status");

        TeacherApplicationDetailDTO detail = GSON.fromJson(GSON.toJsonTree(teacher.request(
                "courseTeacher", TeacherCourseActions.GET_MY_APPLICATION, teacherToken,
                Map.of("type", TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "id",
                        Long.toString(crossRequest))).getData("application")),
                TeacherApplicationDetailDTO.class);
        require(detail.getSummary() != null && detail.getAdjustment() != null
                        && detail.getGrade() == null
                        && Long.toString(crossRequest).equals(
                                detail.getAdjustment().getRequestId()),
                "the detail must carry exactly the one typed variant the summary names");

        String staleKey = cancelled.getStateKey();
        Message read = teacher.request("courseTeacher", TeacherCourseActions.MARK_APPLICATION_READ,
                teacherToken, Map.of("request", new MarkTeacherApplicationReadDTO(
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, Long.toString(crossRequest),
                        staleKey)));
        requireCode(read, MessageCode.SUCCESS, "mark the decided application read");
        TeacherApplicationDTO seen = applicationEntity(read);
        require(!seen.isUnread() && staleKey.equals(seen.getStateKey()),
                "reading a decided row must clear its unread flag without moving its state key");
        require(only(applications(teacher, teacherToken, null, null), Long.toString(crossRequest))
                        .isUnread() == false,
                "the list must reflect the receipt the server just wrote");

        // 过期的确认：拿另一行的状态键去确认这一行 —— 服务端必须什么都不写地拒绝并带回当前行。
        expectFailure(teacher, teacherToken, TeacherCourseActions.MARK_APPLICATION_READ,
                MessageCode.CONFLICT,
                Map.of("type", TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "id",
                        Long.toString(crossRequest), "expectedStateKey",
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT + ":stale"),
                "a stale read confirmation must be refused instead of swallowing a new result");

        // 状态一变重新未读：撤销那条申请，已经读过的 APPROVED 行的状态键不变，但被撤销的这条
        // 从未读过 —— 撤销之后它带着 WITHDRAWN 的状态键出现，仍然是未读。
        Message withdrawn = teacher.request("courseTeacher", TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                teacherToken, Map.of("request", Map.of(
                        "operationId", op(9),
                        "requestId", Long.toString(crossRequest),
                        "expectedVersion", 2)));
        requireCode(withdrawn, MessageCode.CONFLICT,
                "the cross-week request is already approved and must stay that way");
        require(only(applications(teacher, teacherToken, null, null),
                        Long.toString(crossRequest)).isUnread() == false,
                "a refused transition must not resurrect the unread flag");
        System.out.println("[E2E] unread sync: compare-and-set receipts, a stale confirmation"
                + " refused with CONFLICT, and a refused transition writing nothing");
    }

    // ------------------------------------------------- 成绩链：手工 → Excel → 提交 → 驳回 → 重提 → 批准

    /**
     * 第二条闭环：手工草稿 → 模板下载 / 真实短连接上传 / 预览 / 修正 / 确认 → 提交 → 管理员驳回 →
     * 教师读回意见后重提 → 批准 → 发起更正 → 再批准。返回最终被批准的更正批次号。
     */
    private static long gradeChain(JsonLineClient teacher, String teacherToken, JsonLineClient admin,
            String adminToken, JsonLineClient studentOne, String studentOneToken,
            JsonLineClient studentTwo, String studentTwoToken, int filePort, Path tempDirectory)
            throws Exception {
        // 另起一门已发布的课，让学生最终拿到按学分加权的 GPA（只有一门课时它退化成那门课自己的绩点）。
        submitAndApprove(teacher, teacherToken, admin, adminToken, OFFERING_SECOND,
                List.of(row(ENROLL_SECOND, "60.00", "60.00", null, "60.00")), op(10), op(11));
        require(close(recordFor(grades(studentOne, studentOneToken), "TWEC986").getGradePoint(), 1.0),
                "the second course must publish its own point before the correction runs");

        TeacherGradeBookDTO virtual = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(OFFERING_GRADES))));
        require(virtual.getRevision() == 0 && "DRAFT".equals(virtual.getState())
                        && virtual.getRows().size() == 2,
                "an untouched class reads as the revision-0 virtual draft");
        String digest = virtual.getRosterDigest();

        // 1. 手工录入一份残缺草稿：导入要保留的正是这份值，缺的整列不能被擦成 0。
        Message saved = save(teacher, teacherToken, TeacherCourseActions.SAVE_GRADE_DRAFT,
                Map.of("request", write(op(12), OFFERING_GRADES, 0, digest,
                        List.of(row(ENROLL_GRADES_ONE, "70.00", null, null, null),
                                row(ENROLL_GRADES_TWO, "95.00", "91.00", null, "73.00")))));
        requireCode(saved, MessageCode.SUCCESS, "save the hand-entered draft");
        require(book(saved).getRevision() == 1, "the hand-entered draft must be revision 1");
        String draftBeforeImport = draftSnapshot();

        // 2. 模板下载 → 填表 → 文件端口上传 → 预览（不写库）→ 修正 → 确认。
        TeacherFileTicketDTO templateTicket = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.REQUEST_GRADE_TEMPLATE, teacherToken,
                Map.of("offeringId", Long.toString(OFFERING_GRADES))));
        Path template = workDirectory.resolve("workflow-template.xlsx");
        Files.write(template, fileDownload(templateTicket.getPort(),
                fileMetadata(templateTicket, teacherToken), templateTicket.getByteLength()));
        Path workbook = filledWorkbook(template);
        TeacherFileTicketDTO upload = ticket(teacher.request("courseTeacher",
                TeacherCourseActions.BEGIN_GRADE_UPLOAD, teacherToken,
                Map.of("request", new TeacherFileUploadRequestDTO(Long.toString(OFFERING_GRADES), 1,
                        "成绩导入.xlsx", Files.size(workbook),
                        TeacherFileTicketService.sha256(workbook)))));
        require(upload.getPort() == filePort && upload.getByteLength() == Files.size(workbook),
                "the upload ticket must bind this run's file port and the exact byte length");
        fileUpload(upload.getPort(), fileMetadata(upload, teacherToken), workbook);

        GradeImportPreviewDTO preview = preview(teacher, teacherToken, upload.getTicket(),
                content(OFFERING_GRADES, 1, digest,
                        List.of(row(ENROLL_GRADES_ONE, "70.00", null, null, null),
                                row(ENROLL_GRADES_TWO, "95.00", "91.00", null, "73.00"))));
        require(preview.getErrorRows() == 1 && preview.getValidRows() == 1,
                "the fixture workbook must produce one clean row and one error row, saw valid "
                        + preview.getValidRows() + " / error " + preview.getErrorRows());
        // 行号是「数据行」编号：表头之后第一个学生是 1，本夹具的第二位学生因此是 3。
        GradeImportRowIssueDTO outOfRange = issueWithField(issuesByRow(preview), 3,
                GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE);
        require("105".equals(outOfRange.getRawValue()),
                "the out-of-range cell must keep the teacher's original text, saw "
                        + outOfRange.getRawValue());
        require(draftBeforeImport.equals(draftSnapshot())
                        && count("SELECT COUNT(*) FROM grade_submission WHERE offering_id="
                        + OFFERING_GRADES) == 0,
                "a preview must write neither the draft nor a batch");

        Message revised = teacher.request("courseTeacher", TeacherCourseActions.REVISE_GRADE_IMPORT,
                teacherToken, Map.of("request", new ReviseGradeImportRequestDTO(
                        preview.getImportToken(), 1,
                        List.of(new GradeImportCorrectionDTO(3,
                                Map.of(GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE, "91"))),
                        List.of())));
        requireCode(revised, MessageCode.SUCCESS, "correct the out-of-range cell");
        GradeImportPreviewDTO resolved = previewOf(revised);
        require(resolved.getErrorRows() == 0,
                "after the correction no unresolved issue may remain");

        Message confirmed = save(teacher, teacherToken, TeacherCourseActions.CONFIRM_GRADE_IMPORT,
                Map.of("request", new ConfirmGradeImportRequestDTO(op(13), resolved.getImportToken(),
                        resolved.getPreviewRevision(), 1)));
        requireCode(confirmed, MessageCode.SUCCESS, "confirm the import into the draft");
        TeacherGradeBookDTO imported = book(confirmed);
        require(imported.getRevision() == 2 && "DRAFT".equals(imported.getState()),
                "the confirmed import must leave an editable revision-2 draft, saw "
                        + imported.getRevision() + "/" + imported.getState());
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_GRADES + " AND enrollment_id=" + ENROLL_GRADES_ONE
                        + " AND daily_score=80.00 AND midterm_score=80.00"
                        + " AND experiment_score IS NULL AND finalterm_score=80.00") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_GRADES + " AND enrollment_id=" + ENROLL_GRADES_TWO
                        + " AND daily_score=95.00 AND midterm_score=91.00"
                        + " AND finalterm_score=73.00") == 1,
                "the import must fill the missing components and keep the disabled one NULL");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_GRADES)
                        == 0,
                "confirming an import must publish nothing");

        // 3. 提交 → 管理员驳回 → 教师读回意见。
        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(op(14), OFFERING_GRADES, 2, digest,
                        List.of(row(ENROLL_GRADES_ONE, "70.00", "80.00", null, "80.00"),
                                row(ENROLL_GRADES_TWO, "95.00", "91.00", null, "73.00")))));
        requireCode(submitted, MessageCode.SUCCESS, "submit the imported draft");
        long firstBatch = Long.parseLong(book(submitted).getLastSubmissionId());
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + firstBatch
                        + " AND version=1 AND status='PENDING' AND submission_kind='INITIAL'"
                        + " AND base_submission_id IS NULL") == 1,
                "the first submission must be an INITIAL PENDING batch");
        require(recordFor(grades(studentOne, studentOneToken), "TWEB986") == null,
                "a pending batch must stay invisible to the student");

        GradeSubmissionDetailDTO frozen = submissionDetail(admin, adminToken, firstBatch);
        require(frozen.getItems().size() == 2
                        && close(frozen.getItems().get(0).getScore(), 76.0)
                        && close(frozen.getItems().get(0).getGradePoint(), 2.8)
                        && frozen.getCorrectionComparison() == null,
                "the administrator must read the frozen batch this run submitted");
        Message rejected = review(admin, adminToken, op(15), firstBatch, 1, false,
                "  平时成绩与总评不一致，请核对  ");
        requireCode(rejected, MessageCode.SUCCESS, "reject the first batch");
        require("平时成绩与总评不一致，请核对".equals(
                        entity(rejected).getReviewComment()),
                "the rejection must record the trimmed instruction");
        TeacherGradeBookDTO afterRejection = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(OFFERING_GRADES))));
        require("REJECTED".equals(afterRejection.getState()) && afterRejection.isCanEdit()
                        && "平时成绩与总评不一致，请核对".equals(afterRejection.getReviewComment()),
                "the teacher must read the rejected batch and its instruction back over TCP");
        require(recordFor(grades(studentOne, studentOneToken), "TWEB986") == null,
                "a rejection must publish nothing");

        // 4. 重提并批准：这一次真的发布到学生端。
        Message resubmitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(op(16), OFFERING_GRADES, 3, digest,
                        List.of(row(ENROLL_GRADES_ONE, "80.00", "80.00", null, "80.00"),
                                row(ENROLL_GRADES_TWO, "95.00", "91.00", null, "73.00")))));
        requireCode(resubmitted, MessageCode.SUCCESS, "resubmit the corrected draft");
        long secondBatch = Long.parseLong(book(resubmitted).getLastSubmissionId());
        require(secondBatch != firstBatch
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + secondBatch + " AND version=2 AND submission_kind='RESUBMISSION'"
                        + " AND base_submission_id=" + firstBatch) == 1,
                "the resubmission must be a new batch carrying its base");
        review(admin, adminToken, op(17), secondBatch, 2, true, null);
        require(close(recordFor(grades(studentOne, studentOneToken), "TWEB986").getScore(), 80.0)
                        && close(recordFor(grades(studentOne, studentOneToken), "TWEB986")
                                .getGradePoint(), 3.0),
                "the approved resubmission must publish 80.00 / 3.0 to the student");
        System.out.println("[E2E] grade chain: hand draft -> imported draft -> submit -> reject"
                + " -> resubmit -> approved");

        // 5. 更正：以最后一次已批准的批次为来源建立更正草稿，改回期末分后再提交。
        TeacherGradeBookDTO booked = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(OFFERING_GRADES))));
        require("APPROVED".equals(booked.getState()) && !booked.isCanEdit()
                        && booked.getRevision() == 4,
                "the approved batch must be read-only at revision 4, saw " + booked.getState()
                        + "/" + booked.getRevision());
        Message started = save(teacher, teacherToken, TeacherCourseActions.BEGIN_GRADE_CORRECTION,
                Map.of("request", new StartGradeRevisionRequestDTO(op(18),
                        Long.toString(OFFERING_GRADES), Long.toString(secondBatch), 4,
                        CORRECTION_REASON)));
        requireCode(started, MessageCode.SUCCESS, "start the correction on the approved batch");
        TeacherGradeBookDTO correctionDraft = book(started);
        require("DRAFT".equals(correctionDraft.getState()) && correctionDraft.isCanEdit()
                        && Long.toString(secondBatch).equals(
                                correctionDraft.getBaseSubmissionId())
                        && CORRECTION_REASON.equals(correctionDraft.getCorrectionReason()),
                "the correction must open an editable draft based on the approved batch");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFFERING_GRADES + " AND draft_open=1 AND draft_kind='CORRECTION'"
                        + " AND base_submission_id=" + secondBatch + " AND correction_reason='"
                        + CORRECTION_REASON + "'") == 1,
                "the correction draft must record its kind, base and reason in one write");
        require(close(recordFor(grades(studentOne, studentOneToken), "TWEB986").getScore(), 80.0),
                "opening a correction draft alone must not move the published grade");

        Message corrected = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(op(19), OFFERING_GRADES, 4, digest,
                        List.of(row(ENROLL_GRADES_ONE, "80.00", "80.00", null, "100.00"),
                                row(ENROLL_GRADES_TWO, "95.00", "91.00", null, "73.00")))));
        requireCode(corrected, MessageCode.SUCCESS, "submit the correction");
        long correctionBatch = Long.parseLong(book(corrected).getLastSubmissionId());
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + correctionBatch + " AND version=3 AND submission_kind='CORRECTION'"
                        + " AND base_submission_id=" + secondBatch + " AND correction_reason='"
                        + CORRECTION_REASON + "'") == 1,
                "the correction batch must carry its kind, base and reason");
        require(close(recordFor(grades(studentOne, studentOneToken), "TWEB986").getScore(), 80.0),
                "a pending correction must still leave the student on the approved version");

        // 6. 管理员侧的「更正对比」：旧值来自被冻结的上一批，不是教师此刻正在改的草稿。
        GradeSubmissionDetailDTO comparison = submissionDetail(admin, adminToken, correctionBatch);
        GradeCorrectionComparisonDTO diff = comparison.getCorrectionComparison();
        require(diff != null && diff.getBaseVersion() == 2
                        && diff.getBaseStatus() == ApprovalStatusDTO.APPROVED
                        && CORRECTION_REASON.equals(diff.getReason()),
                "the correction detail must name its approved base version and its reason");
        require(diff.getChanges().size() == 1,
                "exactly one student really changed, saw " + diff.getChanges().size());
        GradeCorrectionChangeDTO change = diff.getChanges().get(0);
        require(STUDENT_ONE.equals(change.getStudentUid()) && !change.isAdded()
                        && !change.isRemoved()
                        && close(change.getPrevious().getScore(), 80.0)
                        && close(change.getCurrent().getScore(), 88.0)
                        && close(change.getPrevious().getFinaltermScore(), 80.0)
                        && close(change.getCurrent().getFinaltermScore(), 100.0),
                "the comparison must show the frozen 80.00 against the corrected 88.00 for the"
                        + " one student who really moved");
        require(count("SELECT COUNT(DISTINCT book_revision) FROM teacher_grade_change_log WHERE"
                        + " offering_id=" + OFFERING_GRADES + " AND action='submitGradeBook'") == 3
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_GRADES + " AND action='submitGradeBook' AND enrollment_id="
                        + ENROLL_GRADES_ONE + " AND before_json IS NOT NULL"
                        + " AND after_json IS NOT NULL") >= 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_GRADES + " AND action='confirmGradeImport' AND book_revision=2")
                        >= 1,
                "the import and each of the three submissions must leave their own audit rows"
                        + " (three distinct submit revisions)");
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + TEACHER + "' AND result_code='OK' AND ("
                        + "operation_id='" + op(12) + "' OR operation_id='" + op(13) + "'"
                        + " OR operation_id='" + op(14) + "' OR operation_id='" + op(16) + "'"
                        + " OR operation_id='" + op(18) + "' OR operation_id='" + op(19)
                        + "' OR operation_id='" + op(10) + "')") == 7,
                "every idempotent grade write of this run must be logged exactly once");

        review(admin, adminToken, op(20), correctionBatch, 3, true, null);
        require(close(recordFor(grades(studentOne, studentOneToken), "TWEB986").getScore(), 88.0)
                        && close(recordFor(grades(studentOne, studentOneToken), "TWEB986")
                                .getGradePoint(), 3.8)
                        && recordFor(grades(studentOne, studentOneToken), "TWEB986")
                                .getExperimentScore() == null,
                "approving the correction must publish 88.00 / 3.8 and keep the disabled"
                        + " component NULL");
        require(close(recordFor(grades(studentTwo, studentTwoToken), "TWEB986").getScore(), 85.4),
                "the other student must keep the value the frozen batch published");
        return correctionBatch;
    }

    /** 更正批准后的学生投影：一门课的绩点变了，按学分加权的学期 GPA 与总评必须跟着走。 */
    private static void verifyStudentGpaAfterCorrection(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken, JsonLineClient student, String studentToken,
            long correctionBatch) throws Exception {
        dto.course.GradeSummaryDTO summary = grades(student, studentToken);
        require(summary.getRecords().size() == 2,
                "the student must see exactly the two published courses, saw "
                        + summary.getRecords().size());
        // (1.0*1.5 + 3.8*3.0) / 4.5 = 12.9/4.5 与 (60*1.5 + 88*3.0) / 4.5 = 354/4.5。
        require(close(summary.getTermGpa(), 12.9 / 4.5)
                        && close(summary.getTermAverage(), 354.0 / 4.5),
                "the term GPA must be credit-weighted over the corrected point, saw "
                        + summary.getTermGpa() + "/" + summary.getTermAverage());
        require(close(summary.getCumulativeGpa(), 12.9 / 4.5)
                        && close(summary.getCumulativeAverage(), 354.0 / 4.5),
                "the cumulative figures must match the only two published courses");

        TeacherGradeBookDTO decided = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(OFFERING_GRADES))));
        require("APPROVED".equals(decided.getState())
                        && Long.toString(correctionBatch).equals(decided.getLastSubmissionId())
                        && !decided.isCanEdit(),
                "the teacher must read the approved correction as the decided batch");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_GRADES)
                        == 3
                        && count("SELECT COUNT(*) FROM grade_submission WHERE offering_id="
                        + OFFERING_GRADES + " AND status='REJECTED'") == 1,
                "all three batches must stay queryable, including the rejected one");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                        + "' AND action='reviewAdjustmentRequest' AND result_code='OK'"
                        + " AND operation_id IN ('" + op(2) + "','" + op(7) + "')") == 2
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                        + ADMIN + "' AND action='reviewGradeSubmission' AND result_code='OK'"
                        + " AND operation_id IN ('" + op(11) + "','" + op(15) + "','" + op(17)
                        + "','" + op(20) + "')") == 4
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE"
                        + " operation_id='" + op(5) + "'") == 0,
                "every accepted administrator decision must be audited exactly once and the"
                        + " refused one must write nothing");
    }

    /** 成绩批次出现之后，合并列表必须按类型与状态各自过滤，且两类的状态字母表不互相污染。 */
    private static void verifyMergedApplications(JsonLineClient teacher, String teacherToken)
            throws Exception {
        List<TeacherApplicationDTO> all = applications(teacher, teacherToken, null, null);
        require(all.size() == 7,
                "the merged list must hold three adjustments plus the four grade batches"
                        + " (two courses, a resubmission and a correction), saw " + all.size());
        require(all.stream().filter(row -> TeacherApplicationDTO.GRADE_SUBMISSION.equals(
                        row.getType())).count() == 4,
                "all four grade batches must appear in the merged list");
        List<TeacherApplicationDTO> gradeOnly = applications(teacher, teacherToken,
                TeacherApplicationDTO.GRADE_SUBMISSION, null);
        require(gradeOnly.size() == 4 && gradeOnly.stream().allMatch(row ->
                        !row.isCanWithdraw()),
                "grade batches must never offer a withdrawal the backend does not define");
        require(applications(teacher, teacherToken, TeacherApplicationDTO.GRADE_SUBMISSION,
                        "APPROVED").size() == 3
                        && applications(teacher, teacherToken,
                                TeacherApplicationDTO.GRADE_SUBMISSION, "REJECTED").size() == 1,
                "grade batches must filter on their own three-state alphabet");
        require(gradeOnly.stream().map(TeacherApplicationDTO::getStatus)
                        .noneMatch("WITHDRAWN"::equals),
                "no grade batch may ever carry the withdrawal status only adjustments have");
        require(applications(teacher, teacherToken, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                        null).size() == 3,
                "adjustment applications must filter independently of grade batches");
        require(gradeOnly.stream().allMatch(TeacherApplicationDTO::isUnread),
                "batches decided outside the read path must still be unread");
        System.out.println("[E2E] merged applications: adjustments and grade batches paginate"
                + " together with per-type filters and independent unread flags");
    }

    // ---------------------------------------------------------------- 教师请求助手

    private static List<TeacherOfferingDTO> listOfferings(JsonLineClient teacher, String token)
            throws IOException {
        Message response = teacher.request("courseTeacher", TeacherCourseActions.LIST_OFFERINGS,
                token, Map.of("academicYear", YEAR, "semester", SEMESTER, "page", 1, "size", 20));
        requireCode(response, MessageCode.SUCCESS, "list the teacher's offerings");
        JsonObject page = GSON.toJsonTree(response.getData("offerings")).getAsJsonObject();
        return GSON.fromJson(page.get("items"), OFFERING_ITEMS);
    }

    private static TeacherOfferingDTO offering(List<TeacherOfferingDTO> offerings, long offeringId) {
        for (TeacherOfferingDTO item : offerings) {
            if (Long.toString(offeringId).equals(item.getOfferingId())) {
                return item;
            }
        }
        throw new AssertionError("offering " + offeringId + " is missing from the teacher's list");
    }

    private static List<TeacherApplicationDTO> applications(JsonLineClient teacher, String token,
            String type, String status) throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        if (type != null) data.put("type", type);
        if (status != null) data.put("status", status);
        data.put("page", 1);
        data.put("size", 20);
        Message response = teacher.request("courseTeacher", TeacherCourseActions.LIST_MY_APPLICATIONS,
                token, data);
        requireCode(response, MessageCode.SUCCESS, "list my applications");
        JsonObject page = GSON.toJsonTree(response.getData("applications")).getAsJsonObject();
        return GSON.fromJson(page.get("items"), APPLICATION_ROWS);
    }

    private static TeacherApplicationDTO only(List<TeacherApplicationDTO> rows, String id) {
        for (TeacherApplicationDTO row : rows) {
            if (id.equals(row.getId())) {
                return row;
            }
        }
        throw new AssertionError("application " + id + " is missing from "
                + rows.stream().map(TeacherApplicationDTO::getId).toList());
    }

    private static TeacherApplicationDTO applicationEntity(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("value"), TeacherApplicationDTO.class);
    }

    private static TeacherScheduleWeekDTO teacherSchedule(JsonLineClient client, String token,
            int week) throws IOException {
        Message response = client.request("courseTeacher", TeacherCourseActions.LOAD_TEACHING_SCHEDULE,
                token, scheduleQuery(week));
        requireCode(response, MessageCode.SUCCESS, "read the teacher week " + week);
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule")),
                TeacherScheduleWeekDTO.class);
    }

    private static List<ScheduleEntryDTO> studentSchedule(JsonLineClient client, String token,
            int week) throws IOException {
        Message response = client.request("course", CourseActions.LOAD_SCHEDULE, token,
                scheduleQuery(week));
        requireCode(response, MessageCode.SUCCESS, "read the student week " + week);
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule"))
                .getAsJsonObject().get("entries"), SCHEDULE_ENTRIES);
    }

    private static dto.course.GradeSummaryDTO grades(JsonLineClient student, String token)
            throws IOException {
        Message response = student.request("course", CourseActions.LOAD_GRADES, token,
                Map.of("academicYear", YEAR, "semester", SEMESTER));
        requireCode(response, MessageCode.SUCCESS, "read the student grades");
        return GSON.fromJson(GSON.toJsonTree(response.getData("grades")),
                dto.course.GradeSummaryDTO.class);
    }

    private static List<String> noticeIds(JsonLineClient client, String token, int week)
            throws IOException {
        Message response = client.request("course", CourseActions.LOAD_NOTICES, token,
                scheduleQuery(week));
        requireCode(response, MessageCode.SUCCESS, "read the student notices of week " + week);
        List<CourseNoticeDTO> notices = GSON.fromJson(GSON.toJsonTree(response.getData("notices")),
                NOTICES);
        List<String> ids = new ArrayList<>();
        for (CourseNoticeDTO notice : notices) {
            ids.add(notice.getNoticeId());
        }
        return List.copyOf(ids);
    }

    private static Map<String, Object> scheduleQuery(int week) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicYear", YEAR);
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
                    + entry.getDayOfWeek() + "/" + entry.getStartPeriod());
        }
        described.sort(null);
        return described.toString();
    }

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
        if (classroomId != null) payload.put("newClassroomId", Long.toString(classroomId));
        payload.put("reason", reason);
        return payload;
    }

    private static long submit(JsonLineClient teacher, String token, Map<String, Object> payload,
            boolean expectReplay) throws IOException {
        Message response = teacher.request("courseTeacher", TeacherCourseActions.SUBMIT_ADJUSTMENT,
                token, Map.of("request", payload));
        requireCode(response, MessageCode.SUCCESS, "submit the adjustment");
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        require(result.get("replayed").getAsBoolean() == expectReplay,
                "the submit replay flag must be " + expectReplay);
        AdjustmentRequestDetailDTO value = GSON.fromJson(result.get("value"),
                AdjustmentRequestDetailDTO.class);
        require(value != null && "PENDING".equals(value.getStatus().name()),
                "a submitted request must be PENDING");
        return Long.parseLong(value.getRequestId());
    }

    private static Map<String, Object> reviewPayload(String operationId, long requestId,
            int version, boolean approved, boolean force, String overrideReason,
            String reviewComment) {
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

    private static void review(JsonLineClient admin, String token, String operationId,
            long requestId, int version, boolean approved, boolean force, String overrideReason,
            String reviewComment) throws IOException {
        Message response = admin.admin(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token,
                Map.of("request", reviewPayload(operationId, requestId, version, approved, force,
                        overrideReason, reviewComment)));
        requireCode(response, MessageCode.SUCCESS, "review the adjustment request " + requestId);
        AdjustmentRequestDetailDTO entity = adjustmentEntity(response);
        require(entity.getStatus().name().equals(approved ? "APPROVED" : "REJECTED")
                        && entity.getVersion() == version + 1 && ADMIN.equals(entity.getReviewedBy()),
                "the decision must settle the request at version " + (version + 1) + " by the"
                        + " authenticated administrator, saw " + entity.getStatus() + " v"
                        + entity.getVersion() + "/" + entity.getReviewedBy());
    }

    private static AdjustmentRequestDetailDTO adjustmentEntity(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("entity"), AdjustmentRequestDetailDTO.class);
    }

    private static AdjustmentRequestDetailDTO teacherEntityOf(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("value"), AdjustmentRequestDetailDTO.class);
    }

    private static void expectFailure(JsonLineClient client, String token, String action,
            MessageCode expected, Map<String, Object> body, String what) throws IOException {
        Message response = client.request("courseTeacher", action, token, Map.of("request", body));
        require(response.getCode() == expected, what + " must be " + expected + ", saw "
                + response.getCode() + " (" + response.getMessage() + ")");
    }

    // ---------------------------------------------------------------- 成绩请求助手

    private static Message save(JsonLineClient teacher, String token, String action,
            Map<String, Object> data) throws IOException {
        return teacher.request("courseTeacher", action, token, data);
    }

    private static TeacherGradeBookDTO book(Message response) {
        requireCode(response, MessageCode.SUCCESS, "grade book request");
        Object source = response.getData("gradeBook");
        if (source == null) {
            JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
            source = result.get("value");
        }
        require(source != null, "the response must carry a grade book");
        return GSON.fromJson(GSON.toJsonTree(source), TeacherGradeBookDTO.class);
    }

    private static WriteGradeBookRequestDTO write(String operationId, long offeringId,
            int expectedRevision, String digest, List<GradeRowInputDTO> rows) {
        return new WriteGradeBookRequestDTO(operationId, content(offeringId, expectedRevision,
                digest, rows));
    }

    private static GradeBookContentDTO content(long offeringId, int expectedRevision, String digest,
            List<GradeRowInputDTO> rows) {
        return new GradeBookContentDTO(Long.toString(offeringId), expectedRevision, digest, SCHEME,
                rows);
    }

    private static GradeRowInputDTO row(long enrollmentId, String daily, String midterm,
            String experiment, String finalterm) {
        return new GradeRowInputDTO(Long.toString(enrollmentId), new GradeScoresDTO(
                decimal(daily), decimal(midterm), decimal(experiment), decimal(finalterm)));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    /** 单学生教学班的一次提交 + 批准，用于先把第二门课发布出去，让学期 GPA 真的按学分加权。 */
    private static void submitAndApprove(JsonLineClient teacher, String teacherToken,
            JsonLineClient admin, String adminToken, long offeringId, List<GradeRowInputDTO> rows,
            String submitOperation, String reviewOperation) throws Exception {
        TeacherGradeBookDTO virtual = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(offeringId))));
        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(submitOperation, offeringId, virtual.getRevision(),
                        virtual.getRosterDigest(), rows)));
        requireCode(submitted, MessageCode.SUCCESS, "submit offering " + offeringId);
        long batch = Long.parseLong(book(submitted).getLastSubmissionId());
        review(admin, adminToken, reviewOperation, batch, 1, true, null);
    }

    private static GradeSubmissionDetailDTO submissionDetail(JsonLineClient admin, String token,
            long submissionId) throws IOException {
        Message response = admin.admin(AdminCourseActions.GET_GRADE_SUBMISSION, token,
                Map.of("submissionId", Long.toString(submissionId)));
        requireCode(response, MessageCode.SUCCESS, "read submission " + submissionId);
        return GSON.fromJson(GSON.toJsonTree(response.getData("gradeSubmission")),
                GradeSubmissionDetailDTO.class);
    }

    private static Message review(JsonLineClient admin, String token, String operationId,
            long submissionId, int expectedVersion, boolean approved, String comment)
            throws IOException {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("operationId", operationId);
        decision.put("requestId", Long.toString(submissionId));
        decision.put("expectedVersion", expectedVersion);
        decision.put("approved", approved);
        if (comment != null) decision.put("reviewComment", comment);
        return admin.admin(AdminCourseActions.REVIEW_GRADE_SUBMISSION, token,
                Map.of("request", decision));
    }

    private static GradeSubmissionDetailDTO entity(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("entity"), GradeSubmissionDetailDTO.class);
    }

    private static GradeImportPreviewDTO preview(JsonLineClient teacher, String token,
            String uploadTicket, GradeBookContentDTO baseDraft) throws IOException {
        Message response = teacher.request("courseTeacher", TeacherCourseActions.PREVIEW_GRADE_IMPORT,
                token, Map.of("request", Map.of("uploadTicket", uploadTicket, "baseDraft",
                        baseDraft)));
        requireCode(response, MessageCode.SUCCESS, "preview the uploaded workbook");
        return previewOf(response);
    }

    private static GradeImportPreviewDTO previewOf(Message response) {
        Object value = response.getData("preview");
        require(value != null, "the response must carry the preview");
        return GSON.fromJson(GSON.toJsonTree(value), GradeImportPreviewDTO.class);
    }

    private static Map<Integer, List<GradeImportRowIssueDTO>> issuesByRow(
            GradeImportPreviewDTO preview) {
        Map<Integer, List<GradeImportRowIssueDTO>> byRow = new LinkedHashMap<>();
        for (GradeImportRowIssueDTO issue : preview.getIssues()) {
            byRow.computeIfAbsent(issue.getRowNumber(), key -> new ArrayList<>()).add(issue);
        }
        return byRow;
    }

    private static GradeImportRowIssueDTO issueWithField(
            Map<Integer, List<GradeImportRowIssueDTO>> issues, int rowNumber, String field) {
        for (GradeImportRowIssueDTO issue : issues.getOrDefault(rowNumber, List.of())) {
            if (field.equals(issue.getField())) return issue;
        }
        throw new AssertionError("row " + rowNumber + " has no issue about " + field + ", saw "
                + issues);
    }

    private static dto.course.GradeRecordDTO recordFor(dto.course.GradeSummaryDTO summary,
            String courseCode) {
        for (dto.course.GradeRecordDTO record : summary.getRecords()) {
            if (courseCode.equals(record.getCourseCode())) return record;
        }
        return null;
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.005;
    }

    // ---------------------------------------------------------------- 工作簿与文件端口

    /** 在下载来的模板上按列序填分：一位学生整列补齐，另一位写一个越界值等着被修正。 */
    private static Path filledWorkbook(Path template) throws IOException {
        Path target = workDirectory.resolve("workflow-import.xlsx");
        try (Workbook workbook = WorkbookFactory.create(template.toFile())) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> rowByUid = new LinkedHashMap<>();
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) continue;
                String uid = text(row.getCell(0));
                if (!uid.isBlank()) rowByUid.put(uid, index);
            }
            require(rowByUid.size() == 2,
                    "the template must list the whole fixture roster, saw " + rowByUid.keySet());
            // S1：把手工草稿里留空的期中/期末补齐（模板列序：2=平时、3=期中、4=实验、5=期末）。
            put(sheet, rowByUid.get(STUDENT_ONE), 2, "80");
            put(sheet, rowByUid.get(STUDENT_ONE), 3, "80");
            put(sheet, rowByUid.get(STUDENT_ONE), 5, "80");
            // S2：期中 105 越界 —— 这一整行不会进入候选，由教师修正后再确认。
            put(sheet, rowByUid.get(STUDENT_TWO), 2, "95");
            put(sheet, rowByUid.get(STUDENT_TWO), 3, "105");
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
        }
        return target;
    }

    private static void put(Sheet sheet, Integer rowIndex, int column, String value) {
        require(rowIndex != null, "the sheet must contain the edited row");
        Row row = sheet.getRow(rowIndex);
        require(row != null, "the sheet must contain row " + rowIndex);
        row.createCell(column).setCellValue(value);
    }

    private static String text(Cell cell) {
        return cell == null ? "" : cell.toString().strip();
    }

    private static String fileMetadata(TeacherFileTicketDTO ticket, String token) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ticket", ticket.getTicket());
        fields.put("token", token);
        fields.put("direction", ticket.getDirection());
        fields.put("size", ticket.getByteLength());
        fields.put("sha256", ticket.getSha256());
        return GSON.toJson(fields);
    }

    private static void fileUpload(int port, String metadata, Path workbook) throws IOException {
        Response response = fileExchange(port, metadata, Files.readAllBytes(workbook));
        require(response.ok(), "a valid upload must be acknowledged, saw " + response.message());
    }

    private static byte[] fileDownload(int port, String metadata, long expectedLength)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            out.flush();
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream()));
            byte[] payload = new byte[(int) expectedLength];
            in.readFully(payload);
            Response response = readResponse(in);
            require(response.ok(), "a valid download must be acknowledged, saw "
                    + response.message());
            return payload;
        }
    }

    private static Response fileExchange(int port, String metadata, byte[] payload)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            byte[] bytes = metadata.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream()));
            out.writeInt(bytes.length);
            out.write(bytes);
            if (payload != null) out.write(payload);
            out.flush();
            return readResponse(socket);
        }
    }

    private static Response readResponse(Socket socket) throws IOException {
        return readResponse(new DataInputStream(new BufferedInputStream(socket.getInputStream())));
    }

    private static Response readResponse(DataInputStream in) throws IOException {
        int length = in.readInt();
        byte[] body = new byte[length];
        in.readFully(body);
        JsonObject json = GSON.fromJson(new String(body, StandardCharsets.UTF_8), JsonObject.class);
        return new Response(json.get("status").getAsString(),
                json.get("message") == null ? "" : json.get("message").getAsString());
    }

    private record Response(String status, String message) {
        boolean ok() {
            return "OK".equals(status);
        }
    }

    private static TeacherFileTicketDTO ticket(Message response) {
        requireCode(response, MessageCode.SUCCESS, "file ticket request");
        Object value = response.getData("ticket");
        require(value != null, "the response must carry a file ticket");
        return GSON.fromJson(GSON.toJsonTree(value), TeacherFileTicketDTO.class);
    }

    // ---------------------------------------------------------------- 真实 JSON 行客户端

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

        JsonLineClient(int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(30_000);
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(this::readLoop, "teacher-workflow-e2e-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message message = GSON.fromJson(line, Message.class);
                    if (message == null || message.getType() == MessageType.PUSH) continue;
                    CompletableFuture<Message> future = pending.remove(message.getUID());
                    if (future != null) future.complete(message);
                }
            } catch (IOException closed) {
                // the test closed the socket
            }
        }

        String login(String uid, String role) {
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireCode(captcha, MessageCode.SUCCESS, "get captcha for " + uid);
            // 先落到 Object 再 String.valueOf：Message#getData 是泛型取值，直接把泛型实参推断成
            // char[] 会走错 String.valueOf 的重载（与既有集成测试同一写法）。
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            Message response = request("user", "login", null, Map.of(
                    "cardNo", uid, "password", PASSWORD, "role", role,
                    "captchaId", captchaId, "captchaCode", CaptchaTestBridge.codeFor(captchaId)));
            requireCode(response, MessageCode.SUCCESS, "login " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(),
                    "login must return a token for " + uid);
            require(role.equals(response.getData("role")),
                    "login must report the requested role " + role);
            return (String) token;
        }

        Message admin(String action, String token, Map<String, Object> data) {
            return request("courseAdmin", action, token, data);
        }

        Message request(String module, String action, String token, Map<String, Object> data) {
            Message message = new Message(MessageType.REQUEST, module, action);
            if (token != null) message.setToken(token);
            if (data != null) data.forEach(message::putData);
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
                return future.get(30, TimeUnit.SECONDS);
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

    // ---------------------------------------------------------------- 夹具

    private static void insertFixtures(String jdbc, String user, String pass) throws Exception {
        execute(jdbc, user, pass, "INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                + " VALUES('" + ADMIN + "','Twe986 Admin','" + SEEDED_PASSWORD_HASH + "','"
                + SEEDED_PASSWORD_SALT + "',0,'Workflow College','Registrar'),('" + TEACHER
                + "','" + TEACHER_NAME + "','" + SEEDED_PASSWORD_HASH + "','"
                + SEEDED_PASSWORD_SALT + "',1,'Workflow College','Professor'),('" + STUDENT_ONE
                + "','Twe986 Student One','" + SEEDED_PASSWORD_HASH + "','"
                + SEEDED_PASSWORD_SALT + "',2,'Workflow College','CS'),('" + STUDENT_TWO
                + "','Twe986 Student Two','" + SEEDED_PASSWORD_HASH + "','"
                + SEEDED_PASSWORD_SALT + "',2,'Workflow College','CS')");
        execute(jdbc, user, pass, "INSERT INTO course(course_id,course_code,course_name,credit,"
                + "credit_hours,course_type,status) VALUES(" + COURSE_ADJ + ",'TWEA986','Twe986"
                + " Adjustable',2.00,32,1,'ACTIVE'),(" + COURSE_GRADES + ",'TWEB986','Twe986"
                + " Graded',3.00,48,1,'ACTIVE'),(" + COURSE_SECOND + ",'TWEC986','Twe986"
                + " Second',1.50,24,1,'ACTIVE')");
        execute(jdbc, user, pass, "INSERT INTO course_offering(offering_id,offering_code,course_id,"
                + "academic_year,semester,capacity,enrolled_count,status) VALUES(" + OFFERING_ADJ
                + ",'TWEA986-01'," + COURSE_ADJ + "," + YEAR + "," + SEMESTER + ",30,1,2),("
                + OFFERING_GRADES + ",'TWEB986-01'," + COURSE_GRADES + "," + YEAR + "," + SEMESTER
                + ",30,2,2),(" + OFFERING_SECOND + ",'TWEC986-01'," + COURSE_SECOND + "," + YEAR
                + "," + SEMESTER + ",30,1,2)");
        execute(jdbc, user, pass, "INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES("
                + OFFERING_ADJ + ",'" + TEACHER + "',0),(" + OFFERING_GRADES + ",'" + TEACHER
                + "',0),(" + OFFERING_SECOND + ",'" + TEACHER + "',0)");
        execute(jdbc, user, pass, "INSERT INTO enrollment(enrollment_id,offering_id,course_id,"
                + "academic_year,semester,uid,status,select_time) VALUES(" + ENROLL_ADJ + ","
                + OFFERING_ADJ + "," + COURSE_ADJ + "," + YEAR + "," + SEMESTER + ",'" + STUDENT_ONE
                + "',2,'2027-01-05 00:00:00'),(" + ENROLL_GRADES_ONE + "," + OFFERING_GRADES + ","
                + COURSE_GRADES + "," + YEAR + "," + SEMESTER + ",'" + STUDENT_ONE
                + "',2,'2027-01-05 00:00:00'),(" + ENROLL_GRADES_TWO + "," + OFFERING_GRADES + ","
                + COURSE_GRADES + "," + YEAR + "," + SEMESTER + ",'" + STUDENT_TWO
                + "',2,'2027-01-05 00:00:00'),(" + ENROLL_SECOND + "," + OFFERING_SECOND + ","
                + COURSE_SECOND + "," + YEAR + "," + SEMESTER + ",'" + STUDENT_ONE
                + "',2,'2027-01-05 00:00:00')");
        insertScheduleFixtures(jdbc, user, pass);
    }

    /** 一份自有的已发布教学日历：10 个教学周、周末照常、第 7 天不是教学日。 */
    private static void insertScheduleFixtures(String jdbc, String user, String pass)
            throws Exception {
        execute(jdbc, user, pass, "INSERT INTO day_template(id,name,version) VALUES(" + DAY_TEMPLATE
                + ",'TWE986 full day',1)");
        List<String> periods = new ArrayList<>();
        for (int period = 1; period <= 13; period++) {
            LocalTime start = LocalTime.of(8, 0).plusMinutes((period - 1) * PERIOD_INTERVAL_MINUTES);
            periods.add("(" + DAY_TEMPLATE + "," + period + ",'" + start + ":00','"
                    + start.plusMinutes(PERIOD_LENGTH_MINUTES) + ":00')");
        }
        execute(jdbc, user, pass, "INSERT INTO period_definition(day_template_id,period_no,"
                + "start_time,end_time) VALUES" + String.join(",", periods));
        execute(jdbc, user, pass, "INSERT INTO teaching_calendar(id,name,academic_year,semester,"
                + "week1_start_date,timezone,version,status) VALUES(" + CALENDAR
                + ",'TWE986 calendar 2027-1'," + YEAR + "," + SEMESTER + ",'" + WEEK_ONE_START
                + "','" + ZONE + "',1,'PUBLISHED')");
        List<String> dates = new ArrayList<>();
        for (int week = MIN_WEEK; week <= MAX_WEEK; week++) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                dates.add("(" + (CALENDAR_DATE_FIRST + (week - 1) * 7L + weekday - 1) + ","
                        + CALENDAR + ",'" + localDate(week, weekday) + "'," + week + "," + weekday
                        + "," + DAY_TEMPLATE + "," + (weekday == 7 ? 0 : 1) + ")");
            }
        }
        execute(jdbc, user, pass, "INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                + "teaching_weekday,day_template_id,is_teaching_day) VALUES"
                + String.join(",", dates));
        execute(jdbc, user, pass, "INSERT INTO schedule_plan(id,name,calendar_id,revision,status,"
                + "created_at,updated_at) VALUES(" + PLAN + ",'TWE986 published plan'," + CALENDAR
                + ",1,'PUBLISHED','2027-01-01 00:00:00','2027-01-01 00:00:00')");
        execute(jdbc, user, pass, "UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN
                + " WHERE id=" + CALENDAR);
        execute(jdbc, user, pass, "INSERT INTO classroom(id,name,capacity,electric) VALUES("
                + ROOM_A + ",'TWE986 Room A',60,1),(" + ROOM_B + ",'TWE986 Room B',60,1)");
        execute(jdbc, user, pass, "INSERT INTO schedule_resource(id,resource_type,business_id,"
                + "conflict_mode) VALUES(" + RESOURCE_A + ",'classroom','" + ROOM_A
                + "','EXCLUSIVE'),(" + RESOURCE_B + ",'classroom','" + ROOM_B
                + "','EXCLUSIVE')");
        execute(jdbc, user, pass, "INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,"
                + "offering_id,teacher_uid,classroom_id,status,version) VALUES(" + ARRANGEMENT + ","
                + PLAN + "," + OFFERING_ADJ + ",'" + TEACHER + "'," + ROOM_A + ",'ACTIVE',1)");
        execute(jdbc, user, pass, "INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,"
                + "arrangement_id,weekday,start_period,end_period,status) VALUES(" + RULE_CROSS + ","
                + PLAN + "," + OFFERING_ADJ + "," + ARRANGEMENT + ",2,1,2,'ACTIVE'),("
                + RULE_RACE_WITHDRAWN + "," + PLAN + "," + OFFERING_ADJ + "," + ARRANGEMENT
                + ",1,11,12,'ACTIVE'),(" + RULE_RACE_APPROVED + "," + PLAN + "," + OFFERING_ADJ + ","
                + ARRANGEMENT + ",4,7,8,'ACTIVE')");
        execute(jdbc, user, pass, "INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES("
                + RULE_CROSS + ",8),(" + RULE_RACE_WITHDRAWN + ",8),(" + RULE_RACE_APPROVED
                + ",8)");
        execute(jdbc, user, pass, "INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,"
                + "end_at,week_no,teaching_weekday) VALUES(" + OCC_CROSS + "," + RULE_CROSS + ","
                + PLAN + ",'" + utcWindow(8, 2, 1, true) + "','" + utcWindow(8, 2, 2, false)
                + "',8,2),(" + OCC_RACE_WITHDRAWN + "," + RULE_RACE_WITHDRAWN + "," + PLAN + ",'"
                + utcWindow(8, 1, 11, true) + "','" + utcWindow(8, 1, 12, false) + "',8,1),("
                + OCC_RACE_APPROVED + "," + RULE_RACE_APPROVED + "," + PLAN + ",'"
                + utcWindow(8, 4, 7, true) + "','" + utcWindow(8, 4, 8, false) + "',8,4)");
        execute(jdbc, user, pass, "INSERT INTO resource_booking(plan_id,occurrence_id,resource_id,"
                + "resource_role) VALUES(" + PLAN + "," + OCC_CROSS + "," + RESOURCE_A
                + ",'CLASSROOM'),(" + PLAN + "," + OCC_RACE_WITHDRAWN + "," + RESOURCE_A
                + ",'CLASSROOM'),(" + PLAN + "," + OCC_RACE_APPROVED + "," + RESOURCE_A
                + ",'CLASSROOM')");
    }

    /** 删除只按本测试自己的 UID 前缀、教室/课程与 id 区间进行。 */
    private static void cleanFixtures(String jdbc, String user, String pass) throws Exception {
        execute(jdbc, user, pass, "DELETE FROM course_notice WHERE adjustment_request_id IN"
                + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                + " LIKE '" + PREFIX + "%')");
        execute(jdbc, user, pass, "DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                + "'");
        execute(jdbc, user, pass, "DELETE FROM teacher_course_operation_log WHERE teacher_uid='"
                + TEACHER + "'");
        execute(jdbc, user, pass, "DELETE FROM teacher_grade_change_log WHERE teacher_uid='"
                + TEACHER + "'");
        execute(jdbc, user, pass, "DELETE FROM teacher_application_read WHERE teacher_uid='"
                + TEACHER + "'");
        execute(jdbc, user, pass, "DELETE FROM course_schedule_adjustment WHERE request_id IN"
                + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                + " LIKE '" + PREFIX + "%')");
        execute(jdbc, user, pass, "DELETE FROM course_schedule_adjustment_target WHERE request_id IN"
                + " (SELECT request_id FROM course_schedule_adjustment_request WHERE requested_by"
                + " LIKE '" + PREFIX + "%')");
        execute(jdbc, user, pass, "DELETE FROM course_schedule_adjustment_request WHERE requested_by"
                + " LIKE '" + PREFIX + "%'");
        execute(jdbc, user, pass, "DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN"
                + " 986200 AND 986299");
        execute(jdbc, user, pass, "DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 986200"
                + " AND 986299");
        execute(jdbc, user, pass, "DELETE FROM grade WHERE enrollment_id BETWEEN 986300 AND 986599");
        execute(jdbc, user, pass, "DELETE FROM grade_submission_item WHERE submission_id IN"
                + " (SELECT submission_id FROM grade_submission WHERE offering_id BETWEEN 986200"
                + " AND 986299)");
        execute(jdbc, user, pass, "DELETE FROM grade_submission WHERE offering_id BETWEEN 986200"
                + " AND 986299");
        execute(jdbc, user, pass, "DELETE FROM enrollment WHERE offering_id BETWEEN 986200 AND"
                + " 986299");
        execute(jdbc, user, pass, "DELETE FROM resource_booking WHERE plan_id=" + PLAN);
        execute(jdbc, user, pass, "DELETE FROM course_occurrence WHERE plan_id=" + PLAN);
        execute(jdbc, user, pass, "DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN "
                + RULE_CROSS + " AND " + RULE_RACE_APPROVED);
        execute(jdbc, user, pass, "DELETE FROM course_schedule_rule WHERE plan_id=" + PLAN);
        execute(jdbc, user, pass, "DELETE FROM course_schedule_arrangement WHERE plan_id=" + PLAN);
        execute(jdbc, user, pass, "UPDATE teaching_calendar SET current_schedule_plan_id=NULL"
                + " WHERE id=" + CALENDAR);
        execute(jdbc, user, pass, "DELETE FROM schedule_plan WHERE id=" + PLAN);
        execute(jdbc, user, pass, "DELETE FROM calendar_date WHERE calendar_id=" + CALENDAR);
        execute(jdbc, user, pass, "DELETE FROM period_definition WHERE day_template_id="
                + DAY_TEMPLATE);
        execute(jdbc, user, pass, "DELETE FROM day_template WHERE id=" + DAY_TEMPLATE);
        execute(jdbc, user, pass, "DELETE FROM course_offering_teacher WHERE offering_id BETWEEN"
                + " 986200 AND 986299");
        execute(jdbc, user, pass, "DELETE FROM course_offering WHERE offering_id BETWEEN 986200"
                + " AND 986299");
        execute(jdbc, user, pass, "DELETE FROM course WHERE course_id BETWEEN 986100 AND 986199");
        execute(jdbc, user, pass, "DELETE FROM schedule_resource WHERE id IN (" + RESOURCE_A + ","
                + RESOURCE_B + ")");
        execute(jdbc, user, pass, "DELETE FROM classroom WHERE id IN (" + ROOM_A + "," + ROOM_B
                + ")");
        execute(jdbc, user, pass, "DELETE FROM teaching_calendar WHERE id=" + CALENDAR);
        execute(jdbc, user, pass, "DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    // ---------------------------------------------------------------- 数据库 helper

    private static void resetAndInstall(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (java.sql.Connection connection = connect(jdbc, user, pass)) {
            requireTestSchema(connection);
            List<String> tables = new ArrayList<>();
            try (java.sql.Statement statement = connection.createStatement();
                 java.sql.ResultSet result = statement.executeQuery(
                         "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA ="
                                 + " DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
                while (result.next()) tables.add(result.getString(1));
            }
            execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
            try {
                for (String table : tables) {
                    requireTestSchema(connection);
                    execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
                }
            } finally {
                execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
            }
            applyTblUser(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            applyScript(connection, root.resolve("VCampusServer/src/resources/seed-course-test.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V005_teacher_course_foundation.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V006_teacher_adjustment_requests.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V007_teacher_gradebook.sql"));
        }
    }

    private static void applyTblUser(java.sql.Connection connection, Path initSql) throws Exception {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB"
                        + ".*?;").matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "the authoritative tbl_user definition was not found");
        execute(connection, matcher.group());
    }

    private static void applyScript(java.sql.Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int index = 0; index < statements.size(); index++) {
            try {
                execute(connection, statements.get(index));
            } catch (java.sql.SQLException failure) {
                throw new java.sql.SQLException("failed applying " + path.getFileName()
                        + " statement " + (index + 1), failure);
            }
        }
    }

    private static java.sql.Connection connect(String jdbc, String user, String pass)
            throws Exception {
        java.sql.Connection connection = java.sql.DriverManager.getConnection(jdbc, user, pass);
        execute(connection, "SET time_zone = '+00:00'");
        return connection;
    }

    private static void execute(String jdbc, String user, String pass, String sql)
            throws Exception {
        try (java.sql.Connection connection = connect(jdbc, user, pass);
             java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void execute(java.sql.Connection connection, String sql) throws Exception {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 任何重写库的操作之前，先核验这条连接真的落在受保护的测试库上。 */
    private static void requireTestSchema(java.sql.Connection connection) throws Exception {
        try (java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            require(rows.next(), "the connection must report its database");
            String database = rows.getString(1);
            require(TEST_DATABASE.equals(database), "refusing to reset outside the guarded schema:"
                    + " connected database was " + database);
        }
    }

    private static int number(String jdbc, String user, String pass, String sql) throws Exception {
        try (java.sql.Connection connection = connect(jdbc, user, pass);
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static int count(String sql) throws Exception {
        try (java.sql.Connection connection = DBUtil.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static long adjustmentOf(long requestId) throws Exception {
        try (java.sql.Connection connection = DBUtil.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT adjustment_id FROM course_schedule_adjustment WHERE request_id="
                             + requestId)) {
            require(rows.next(), "the approval must have written one adjustment");
            return rows.getLong(1);
        }
    }

    private static String draftSnapshot() throws Exception {
        try (java.sql.Connection connection = DBUtil.getConnection();
             java.sql.Statement statement = connection.createStatement();
             java.sql.ResultSet rows = statement.executeQuery(
                     "SELECT COALESCE(GROUP_CONCAT(CONCAT(enrollment_id,'|',"
                             + "COALESCE(daily_score,'-'),'|',COALESCE(midterm_score,'-'),'|',"
                             + "COALESCE(experiment_score,'-'),'|',COALESCE(finalterm_score,'-'))"
                             + " ORDER BY enrollment_id SEPARATOR ','),'') FROM"
                             + " teacher_grade_draft_item WHERE offering_id=" + OFFERING_GRADES)) {
            require(rows.next(), "query returned no row");
            return rows.getString(1);
        }
    }

    private static LocalDate localDateValue(int week, int weekday) {
        return WEEK_ONE_START.plusDays((week - 1) * 7L + weekday - 1L);
    }

    private static String localDate(int week, int weekday) {
        return localDateValue(week, weekday).toString();
    }

    /** 某个 fixture 时间窗在 UTC 墙钟上的字符串（DATETIME 列按 UTC 存）。 */
    private static String utcWindow(int week, int weekday, int period, boolean start) {
        LocalTime time = LocalTime.of(8, 0).plusMinutes((period - 1) * PERIOD_INTERVAL_MINUTES);
        if (!start) time = time.plusMinutes(PERIOD_LENGTH_MINUTES);
        Instant instant = ZonedDateTime.of(localDateValue(week, weekday), time, ZoneId.of(ZONE))
                .toInstant();
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC).toString().replace('T', ' ');
    }

    /** 本进程内的 JSON 行客户端不走 DBUtil，所以这里显式把它指向本次解析出来的配置。 */
    private static void pointDBUtilAt(String testUrl, Properties properties) throws Exception {
        setStatic(DBUtil.class, "url", testUrl);
        setStatic(DBUtil.class, "username", requiredProperty(properties, "db.username"));
        setStatic(DBUtil.class, "password", requiredProperty(properties, "db.password"));
    }

    private static void setStatic(Class<?> type, String name, String value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
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
                if (!statement.isEmpty()) statements.add(statement);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) statements.add(trailing);
        return statements;
    }

    private static void deleteRecursively(Path directory) {
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort: the temp directory is outside the repository
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "Refusing the workflow chain: JDBC database must be"
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

    /** 64 位十六进制操作 ID，避免与其它测试的固定 ID 相撞。 */
    private static String op(int value) {
        return String.format(Locale.ROOT, "98600000-0000-0000-0000-%012d", value);
    }

    private static void requireCode(Message response, MessageCode expected, String what) {
        require(response != null, what + " must return a response");
        require(response.getCode() == expected, what + " must be " + expected + ", saw "
                + (response == null ? "no response" : response.getCode() + " ("
                + response.getMessage() + ")"));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
