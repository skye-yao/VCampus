package integration;

import com.google.gson.Gson;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherPeriodDTO;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * T2 课表阶段的端到端验证：真实 TCP 登录 → {@code courseTeacher/loadTeachingSchedule} → 客户端 DTO。
 *
 * <p>只有 JDBC 数据库正好是 {@code virtual_campus_course_test} 时才继续（未传 {@code mysql} 时只打印
 * SKIP 并返回，绝不把 SKIP 当成 PASS）。本类自己重建受保护的架构（完整 init.sql →
 * V001 → V002 → V003 → seed-course-test.sql → V004 → V005），再插入私有 ID 段（日历 8391、模板 8841、
 * 课程 8191/8192、教学班 8291/8292、教室 8891/8892、方案 8491、安排 8581..8583、规则 8651..8655、
 * 课次 8621..8625、申请 8771..8773、调课 8791..8793、UID 前缀 {@code tt-}），结束时只删除自己的行，
 * 并断言共享 seed（教学班 2001/2002、课次 4201）完好。写入测试必须串行运行，本类不做任何并行。
 *
 * <p>断言全部打在具体数据上：teacher A 第 8 周恰好是 fixture 设定的五个 (课次, 标记)，同周调整同时给
 * 原位置与目标位置、跨周调整只给目标周的新位置，代课后生效教师只看到新位置而原教师只看到旧位置，
 * 周末（教学日序号 6/7）与第 12/13 节不被硬编码裁掉，以及越界/非法输入、身份与伪造请求体的失败码。
 *
 * <p>{@code displayKind} 另外在 Gson 尚未把未知枚举吞成 null 的原始 Map 上复查：任一层出现计划外
 * 的字符串都必须失败，而不是被反序列化悄悄抹平。
 */
public final class TeacherScheduleSocketEndToEndTest {

    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String LOGIN_PASSWORD = "course-test-only";
    private static final String TEACHER_ROLE = "教师";
    private static final String STUDENT_ROLE = "学生";
    private static final String ADMIN_ROLE = "管理员";

    private static final int ACADEMIC_YEAR = 2026;
    private static final int SEMESTER = 3;
    /** 没有已发布教学日历的学期：用它验证客户端可读的拒绝文案。 */
    private static final int MISSING_CALENDAR_YEAR = 2025;
    private static final int MISSING_CALENDAR_SEMESTER = 1;

    private static final String TEACHER_A = "tt-owner";
    private static final String TEACHER_ORIGINAL = "tt-original";
    private static final String TEACHER_SUBSTITUTE = "tt-substitute";
    private static final String TEACHER_OTHER = "tt-other";
    private static final String STUDENT_SEEDED = "student-alpha";
    private static final String ADMIN_SEEDED = "admin-alpha";
    /** Seed-course-test.sql 的测试口令哈希/盐；四个教师账号复用它们登录。 */
    private static final String SEEDED_PASSWORD_HASH =
            "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";
    private static final String SEEDED_PASSWORD_SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";

    /** 第 1 周周一；每个 ISO 本地日期都由它按 (week, teaching_weekday) 推导。 */
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2026, 9, 7);
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 10;
    private static final int FIRST_PERIOD_MINUTES = 8 * 60;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final int PERIOD_LENGTH_MINUTES = 45;

    /** Own fixture range: nothing outside 8191..8793 and the {@code tt-} UID prefix. */
    private static final long CALENDAR = 8391L;
    private static final long DAY_TEMPLATE = 8841L;
    private static final long COURSE_A = 8191L;
    private static final long COURSE_B = 8192L;
    private static final long OFFERING_A = 8291L;
    private static final long OFFERING_B = 8292L;
    private static final long ROOM_A = 8891L;
    private static final long ROOM_B = 8892L;
    private static final long PLAN = 8491L;
    private static final long ARRANGEMENT_A = 8581L;
    private static final long ARRANGEMENT_B = 8582L;
    private static final long ARRANGEMENT_REPLACED = 8583L;
    private static final long RULE_CROSS = 8651L;
    private static final long RULE_SAME = 8652L;
    private static final long RULE_SATURDAY = 8653L;
    private static final long RULE_SUNDAY = 8654L;
    private static final long RULE_REPLACED = 8655L;
    private static final long OCC_CROSS = 8621L;
    private static final long OCC_SAME = 8622L;
    private static final long OCC_SATURDAY = 8623L;
    private static final long OCC_SUNDAY = 8624L;
    private static final long OCC_REPLACED = 8625L;
    private static final long REQUEST_CROSS = 8771L;
    private static final long REQUEST_SAME = 8772L;
    private static final long REQUEST_REPLACED = 8773L;
    private static final long ADJUSTMENT_CROSS = 8791L;
    private static final long ADJUSTMENT_SAME = 8792L;
    private static final long ADJUSTMENT_REPLACED = 8793L;

    /** 跨周调整 (8621) 的目标：第 9 周周三 2026-11-04 的第 3-4 节。 */
    private static final String CROSS_TARGET_DATE = "2026-11-04";
    /** 同周调整 (8622) 与代课 (8625) 的目标：第 8 周周五 2026-10-30。 */
    private static final String SAME_WEEK_TARGET_DATE = "2026-10-30";

    private static final Set<String> DISPLAY_KINDS =
            Set.of("NORMAL", "ADJUSTED_ORIGINAL", "ADJUSTED_TARGET");
    private static final Pattern CLOCK = Pattern.compile("[0-9]{2}:[0-9]{2}:[0-9]{2}");
    private static final Gson GSON = new Gson();

    private TeacherScheduleSocketEndToEndTest() {
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
            System.out.println("SKIP: no `mysql` argument, so the teacher schedule end-to-end test was "
                    + "not run and is NOT reported as passing. Pass -WithMySql to run it.");
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
                + " (init.sql -> V001 -> V002 -> V003 -> seed -> V004 -> V005)");
        resetAndSeed(jdbc, username, password, root);
        insertFixtures(jdbc, username, password);
        pointDBUtilAt(jdbc, properties);
        System.out.println("[E2E] fresh post-V005 schema with the timetable fixtures in place");

        Harness harness = new Harness();
        try {
            harness.start();
            System.out.println("[E2E] server listening on 127.0.0.1:" + harness.port());
            runScenarios(harness, jdbc, username, password);
        } finally {
            harness.stop();
        }
        cleanFixtures(jdbc, username, password);
        System.out.println("Teacher schedule socket end-to-end test passed.");
    }

    // ------------------------------------------------------------------
    // Scenarios
    // ------------------------------------------------------------------

    private static void runScenarios(Harness harness, String jdbc, String user, String pass)
            throws Exception {
        try (JsonLineClient teacherA = harness.client();
             JsonLineClient original = harness.client();
             JsonLineClient substitute = harness.client();
             JsonLineClient other = harness.client();
             JsonLineClient student = harness.client();
             JsonLineClient admin = harness.client()) {
            String tokenA = teacherA.login(TEACHER_A, TEACHER_ROLE);
            String tokenOriginal = original.login(TEACHER_ORIGINAL, TEACHER_ROLE);
            String tokenSubstitute = substitute.login(TEACHER_SUBSTITUTE, TEACHER_ROLE);
            String tokenOther = other.login(TEACHER_OTHER, TEACHER_ROLE);
            String studentToken = student.login(STUDENT_SEEDED, STUDENT_ROLE);
            String adminToken = admin.login(ADMIN_SEEDED, ADMIN_ROLE);

            missingOrInvalidTokenIsUnauthorized(teacherA);
            nonTeacherTokenIsForbidden(teacherA, studentToken, adminToken);
            teacherAWeekEightIsExactlyTheFixtureBlocks(teacherA, tokenA, jdbc, user, pass);
            crossWeekAdjustmentOnlyAppearsInTheTargetWeek(teacherA, tokenA);
            adjustmentTextsAreAllPresent(teacherA, tokenA);
            substituteOwnershipIsSplitBetweenTheTwoTeachers(
                    teacherA, tokenA, original, tokenOriginal, substitute, tokenSubstitute,
                    other, tokenOther);
            weekendAndThirteenthPeriodSurvive(teacherA, tokenA);
            omittedWeekKeepsTheDateIndependentInvariants(teacherA, tokenA);
            rawJsonShapeAndEnumValuesAreGuarded(teacherA, tokenA);
            invalidInputIsRejected(teacherA, tokenA);
            forgedIdentityCannotReplaceTheSession(teacherA, tokenA);
        }
    }

    private static void missingOrInvalidTokenIsUnauthorized(JsonLineClient client) {
        Message withoutToken = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, null,
                scheduleQuery(8));
        require(withoutToken.getCode() == MessageCode.UNAUTHORIZED,
                "a request without a token must be UNAUTHORIZED but was " + withoutToken.getCode());

        Message forgedToken = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE,
                "not-a-real-token", scheduleQuery(8));
        require(forgedToken.getCode() == MessageCode.UNAUTHORIZED,
                "an unknown token must be UNAUTHORIZED but was " + forgedToken.getCode());
        System.out.println("[E2E] missing/unknown token -> UNAUTHORIZED");
    }

    private static void nonTeacherTokenIsForbidden(JsonLineClient client, String studentToken,
                                                   String adminToken) {
        Message asStudent = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE,
                studentToken, scheduleQuery(8));
        require(asStudent.getCode() == MessageCode.FORBIDDEN,
                "a student token must be FORBIDDEN but was " + asStudent.getCode());
        Message asAdmin = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE,
                adminToken, scheduleQuery(8));
        require(asAdmin.getCode() == MessageCode.FORBIDDEN,
                "an administrator token must be FORBIDDEN but was " + asAdmin.getCode());
        System.out.println("[E2E] student/admin token -> FORBIDDEN");
    }

    private static void teacherAWeekEightIsExactlyTheFixtureBlocks(JsonLineClient client,
            String token, String jdbc, String user, String pass) throws SQLException {
        TeacherScheduleWeekDTO week = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(8)),
                "teacher A week 8");

        require("8391".equals(week.getCalendarId()) && "Asia/Shanghai".equals(week.getTimezone()),
                "the response must carry the teaching calendar identity but was "
                        + week.getCalendarId() + "/" + week.getTimezone());
        require(week.getWeek() == 8 && week.getMinWeek() == MIN_WEEK
                        && week.getMaxWeek() == MAX_WEEK,
                "week eight of weeks 1..10 expected but was " + week.getWeek() + " of "
                        + week.getMinWeek() + ".." + week.getMaxWeek());
        require(week.getCurrentWeek() == null || week.getCurrentWeek() >= MIN_WEEK
                        && week.getCurrentWeek() <= MAX_WEEK,
                "currentWeek must be null or inside the calendar but was " + week.getCurrentWeek());

        require(kinds(week).equals(List.of(
                        "8621:ADJUSTED_ORIGINAL",
                        "8622:ADJUSTED_ORIGINAL",
                        "8622:ADJUSTED_TARGET",
                        "8623:NORMAL",
                        "8624:NORMAL")),
                "week eight must be exactly the fixture blocks but was " + kinds(week));

        for (TeacherScheduleEntryDTO entry : week.getEntries()) {
            require(entry.getWeek() == 8,
                    "every week-eight block must report week 8 but " + entry.getOccurrenceId()
                            + " reported " + entry.getWeek());
            TeacherCalendarDateDTO date = dateOf(week, entry.getDayOfWeek());
            require(date != null && date.getWeek() == 8,
                    "the block " + entry.getOccurrenceId() + " must land on a published calendar"
                            + " date of week eight, weekday " + entry.getDayOfWeek());
            require(entry.getLocalDate().equals(date.getDate()),
                    "the block " + entry.getOccurrenceId() + " must use the calendar's local date "
                            + date.getDate() + " but was " + entry.getLocalDate());
        }

        require(count(jdbc, user, pass, "SELECT COUNT(*) FROM course_selection_window"
                        + " WHERE academic_year=" + ACADEMIC_YEAR + " AND semester=" + SEMESTER) == 0,
                "the fixture term must have no selection window, so the query cannot depend on one");
        System.out.println("[E2E] teacher A week 8 -> " + kinds(week)
                + " (no selection window needed)");
    }

    private static void crossWeekAdjustmentOnlyAppearsInTheTargetWeek(JsonLineClient client,
                                                                      String token) {
        TeacherScheduleWeekDTO origin = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(8)),
                "teacher A week 8");
        require(kinds(origin).stream().noneMatch(value -> value.equals("8621:ADJUSTED_TARGET")),
                "the origin week must never also show the cross-week target");

        TeacherScheduleWeekDTO target = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(9)),
                "teacher A week 9");
        require(target.getWeek() == 9 && target.getMinWeek() == MIN_WEEK
                        && target.getMaxWeek() == MAX_WEEK,
                "week nine expected but was " + target.getWeek());
        require(kinds(target).equals(List.of("8621:ADJUSTED_TARGET")),
                "week nine must hold only the cross-week target but was " + kinds(target));
        TeacherScheduleEntryDTO moved = target.getEntries().get(0);
        require(CROSS_TARGET_DATE.equals(moved.getLocalDate()) && moved.getDayOfWeek() == 3
                        && moved.getStartPeriod() == 3 && moved.getEndPeriod() == 4,
                "the cross-week target must sit on " + CROSS_TARGET_DATE
                        + " 第 3-4 节 but was " + moved.getLocalDate() + " 第 "
                        + moved.getStartPeriod() + "-" + moved.getEndPeriod() + " 节");
        require(target.getDates().size() == 7,
                "week nine must keep seven calendar dates but had " + target.getDates().size());
        System.out.println("[E2E] cross-week adjustment -> only week 9 holds the target at "
                + CROSS_TARGET_DATE);
    }

    private static void adjustmentTextsAreAllPresent(JsonLineClient client, String token) {
        TeacherScheduleWeekDTO week = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(8)),
                "teacher A week 8");
        List<TeacherScheduleEntryDTO> pair = week.getEntries().stream()
                .filter(entry -> Long.toString(OCC_SAME).equals(entry.getOccurrenceId()))
                .toList();
        require(pair.size() == 2,
                "a same-week adjustment must return both halves in one week but had "
                        + pair.size());
        require(pair.stream().allMatch(entry ->
                        Long.toString(ADJUSTMENT_SAME).equals(entry.getAdjustmentId())),
                "both halves must share the same adjustment id");
        require(pair.stream().anyMatch(entry ->
                        entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL)
                        && pair.stream().anyMatch(entry ->
                        entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET),
                "the same-week pair must keep both the original and the target marker");
        for (TeacherScheduleEntryDTO entry : pair) {
            require(entry.getOriginalScheduleText() != null
                            && !entry.getOriginalScheduleText().isBlank()
                            && entry.getAdjustedScheduleText() != null
                            && !entry.getAdjustedScheduleText().isBlank()
                            && entry.getAdjustmentReason() != null
                            && !entry.getAdjustmentReason().isBlank(),
                    "both halves must describe the original position, the adjusted position and"
                            + " the reason but were " + entry.getOriginalScheduleText() + " / "
                            + entry.getAdjustedScheduleText() + " / " + entry.getAdjustmentReason());
            require(!entry.isCanRequestAdjustment(),
                    "a block that already has an effective adjustment must not be requestable");
        }
        System.out.println("[E2E] same-week adjustment -> two texted halves sharing adjustment "
                + ADJUSTMENT_SAME);
    }

    private static void substituteOwnershipIsSplitBetweenTheTwoTeachers(JsonLineClient teacherA,
            String tokenA, JsonLineClient original, String tokenOriginal,
            JsonLineClient substitute, String tokenSubstitute, JsonLineClient other,
            String tokenOther) {
        TeacherScheduleWeekDTO ownerWeek = schedule(teacherA.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, tokenA, scheduleQuery(8)),
                "owner week 8");
        require(ownerWeek.getEntries().stream().noneMatch(entry ->
                        Long.toString(OCC_REPLACED).equals(entry.getOccurrenceId())),
                "the original teacher's occurrence must not leak into the timetable of a teacher"
                        + " who neither owns it nor received it");

        TeacherScheduleWeekDTO originalWeek = schedule(original.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, tokenOriginal, scheduleQuery(8)),
                "replaced teacher week 8");
        require(kinds(originalWeek).equals(List.of("8625:ADJUSTED_ORIGINAL")),
                "the replaced teacher must only see the original half but saw "
                        + kinds(originalWeek));

        TeacherScheduleWeekDTO substituteWeek = schedule(substitute.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, tokenSubstitute, scheduleQuery(8)),
                "substitute week 8");
        require(kinds(substituteWeek).equals(List.of("8625:ADJUSTED_TARGET")),
                "the substitute must only see the target half but saw " + kinds(substituteWeek));
        TeacherScheduleEntryDTO handedOver = substituteWeek.getEntries().get(0);
        require(SAME_WEEK_TARGET_DATE.equals(handedOver.getLocalDate())
                        && handedOver.getDayOfWeek() == 5,
                "the substitute's target must sit on " + SAME_WEEK_TARGET_DATE + " 第 5 天 but was "
                        + handedOver.getLocalDate() + " 第 " + handedOver.getDayOfWeek() + " 天");

        require(schedule(other.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, tokenOther,
                        scheduleQuery(8)), "unrelated week 8").getEntries().isEmpty()
                        && schedule(other.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE,
                        tokenOther, scheduleQuery(9)), "unrelated week 9").getEntries().isEmpty(),
                "a teacher who neither owns the occurrence nor appears on the adjustment must see"
                        + " no block in either week");
        System.out.println("[E2E] substitute split -> original sees only the original half,"
                + " substitute only the target half, unrelated teacher none");
    }

    private static void weekendAndThirteenthPeriodSurvive(JsonLineClient client, String token) {
        TeacherScheduleWeekDTO week = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(8)),
                "teacher A week 8");
        require(week.getDates().size() == 7,
                "a full teaching week must expose seven dates, never a hardcoded five, but had "
                        + week.getDates().size());
        require(week.getDates().get(6).getTeachingWeekday() == 7
                        && !week.getDates().get(6).isTeachingDay(),
                "the seventh date must keep its non-teaching flag");
        require(week.getEntries().stream().anyMatch(entry -> entry.getDayOfWeek() == 6),
                "the Saturday block must appear in the week grid");
        require(week.getEntries().stream().anyMatch(entry -> entry.getDayOfWeek() == 7),
                "the Sunday block must appear in the week grid");
        TeacherScheduleEntryDTO late = week.getEntries().stream()
                .filter(entry -> Long.toString(OCC_SATURDAY).equals(entry.getOccurrenceId()))
                .findFirst().orElseThrow(() -> new AssertionError("the 12-13 block is missing"));
        require(late.getStartPeriod() == 12 && late.getEndPeriod() == 13,
                "the twelfth/thirteenth period block must keep its coordinates but was 第 "
                        + late.getStartPeriod() + "-" + late.getEndPeriod() + " 节");
        require(week.getEntries().stream().noneMatch(entry -> entry.getStartPeriod() > 13),
                "no block may exceed the published period template");
        System.out.println("[E2E] weekend (weekday 6/7) and 第 12-13 节 blocks survive");
    }

    private static void omittedWeekKeepsTheDateIndependentInvariants(JsonLineClient client,
                                                                    String token) {
        TeacherScheduleWeekDTO fallback = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, scheduleQuery(null)),
                "teacher A default week");
        require(fallback.getWeek() >= fallback.getMinWeek()
                        && fallback.getWeek() <= fallback.getMaxWeek(),
                "a defaulted week must still fall inside the calendar but was "
                        + fallback.getWeek());
        require(fallback.getCurrentWeek() == null
                        || fallback.getCurrentWeek() == fallback.getWeek(),
                "when the server reports a current week it must be the returned week but was "
                        + fallback.getCurrentWeek() + " vs " + fallback.getWeek());
        require(fallback.getCurrentWeek() != null || fallback.getWeek() == fallback.getMinWeek(),
                "outside the term a defaulted week must fall back to minWeek");
        System.out.println("[E2E] omitted week -> week " + fallback.getWeek() + " of "
                + fallback.getMinWeek() + ".." + fallback.getMaxWeek() + " (currentWeek "
                + fallback.getCurrentWeek() + ")");
    }

    private static void rawJsonShapeAndEnumValuesAreGuarded(JsonLineClient client, String token) {
        Message response = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                scheduleQuery(8));
        requireSuccess(response, "teacher A week 8");
        Map<String, Object> data = response.getData();
        require(data != null && data.keySet().equals(Set.of("schedule")),
                "the response data must hold only the schedule key but held "
                        + (data == null ? null : data.keySet()));

        List<String> rawKinds = new ArrayList<>();
        collectDisplayKinds(data.get("schedule"), rawKinds);
        require(!rawKinds.isEmpty(), "the raw payload must really carry displayKind values");
        for (String kind : rawKinds) {
            require(DISPLAY_KINDS.contains(kind),
                    "an unknown displayKind must never reach the client, saw " + kind);
        }

        TeacherScheduleWeekDTO mapped = GSON.fromJson(GSON.toJson(data.get("schedule")),
                TeacherScheduleWeekDTO.class);
        require(mapped.getDates().size() == 7, "the client DTO must map all seven calendar dates");
        require(mapped.getDates().get(0).getDate().equals("2026-10-26")
                        && mapped.getDates().get(0).getTeachingWeekday() == 1
                        && mapped.getDates().get(0).isTeachingDay(),
                "the client DTO must map the first calendar date");
        require(mapped.getPeriods().stream().anyMatch(period ->
                        period.getPeriod() == 13 && "18:00:00".equals(period.getStartTime())
                                && "18:45:00".equals(period.getEndTime())),
                "the client DTO must map the 13th period with fixed-width local clock strings");
        require(mapped.getPeriods().stream().allMatch(period ->
                        CLOCK.matcher(period.getStartTime()).matches()
                                && CLOCK.matcher(period.getEndTime()).matches()),
                "every period clock must stay in HH:mm:ss");
        TeacherScheduleEntryDTO cross = mapped.getEntries().stream()
                .filter(entry -> Long.toString(OCC_CROSS).equals(entry.getOccurrenceId()))
                .findFirst().orElseThrow(() -> new AssertionError("the cross-week block is missing"));
        require(cross.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL
                        && cross.getOfferingId().equals(Long.toString(OFFERING_A))
                        && cross.getCourseCode() != null && cross.getCourseName() != null
                        && cross.getTeacher() != null && cross.getLocation() != null
                        && cross.getLocalDate() != null,
                "the client DTO must map every entry field, saw " + cross.getCourseCode() + "/"
                        + cross.getCourseName() + "/" + cross.getTeacher() + "/"
                        + cross.getLocation() + "/" + cross.getLocalDate());
        System.out.println("[E2E] raw payload -> only the schedule key, " + rawKinds.size()
                + " in-vocabulary displayKind values; DTO maps all three layers");
    }

    private static void invalidInputIsRejected(JsonLineClient client, String token) {
        requireBadRequest(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                        scheduleQuery(0)),
                "week 0");
        requireBadRequest(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                        scheduleQuery(MAX_WEEK + 1)),
                "week " + (MAX_WEEK + 1));
        Map<String, Object> nonNumericYear = new LinkedHashMap<>();
        nonNumericYear.put("academicYear", "abc");
        nonNumericYear.put("semester", SEMESTER);
        nonNumericYear.put("week", 8);
        requireBadRequest(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                nonNumericYear), "a non-numeric academicYear");
        Map<String, Object> nonNumericSemester = new LinkedHashMap<>();
        nonNumericSemester.put("academicYear", ACADEMIC_YEAR);
        nonNumericSemester.put("semester", "x");
        nonNumericSemester.put("week", 8);
        requireBadRequest(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                nonNumericSemester), "a non-numeric semester");
        requireBadRequest(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                        scheduleQuery(-1)),
                "a negative week");

        Map<String, Object> noCalendar = new LinkedHashMap<>();
        noCalendar.put("academicYear", MISSING_CALENDAR_YEAR);
        noCalendar.put("semester", MISSING_CALENDAR_SEMESTER);
        noCalendar.put("week", 1);
        Message response = client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                noCalendar);
        require(response.getCode() == MessageCode.BAD_REQUEST,
                "a term without a published calendar must be BAD_REQUEST but was "
                        + response.getCode());
        require("该学期暂无已发布的教学日历".equals(response.getMessage()),
                "the client-facing rejection must be preserved verbatim but was "
                        + response.getMessage());
        System.out.println("[E2E] out-of-range and malformed input -> BAD_REQUEST with the"
                + " client-facing message preserved");
    }

    /**
     * 请求体声称别的身份（其他教师、其他 uid）时，服务端必须只认 token 的会话身份。若它信任请求体，
     * 后半段的断言会从“仍是 teacher A 的五块”变成替身的课表。
     */
    private static void forgedIdentityCannotReplaceTheSession(JsonLineClient client, String token) {
        Map<String, Object> forged = scheduleQuery(8);
        forged.put("uid", TEACHER_SUBSTITUTE);
        forged.put("teacherId", TEACHER_SUBSTITUTE);
        forged.put("sender", TEACHER_SUBSTITUTE);
        TeacherScheduleWeekDTO week = schedule(client.teacher(
                TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token, forged), "forged week 8");
        require(kinds(week).equals(List.of(
                        "8621:ADJUSTED_ORIGINAL",
                        "8622:ADJUSTED_ORIGINAL",
                        "8622:ADJUSTED_TARGET",
                        "8623:NORMAL",
                        "8624:NORMAL")),
                "a body claiming the substitute must still be served as teacher A but returned "
                        + kinds(week));

        Map<String, Object> forgedStudent = scheduleQuery(8);
        forgedStudent.put("uid", STUDENT_SEEDED);
        require(schedule(client.teacher(TeacherCourseActions.LOAD_TEACHING_SCHEDULE, token,
                        forgedStudent), "forged student week 8").getEntries().size() == 5,
                "a teacher token claiming a student uid must still be served as the teacher");
        System.out.println("[E2E] forged body identity (substitute / student) -> session identity"
                + " wins");
    }

    private static void requireBadRequest(Message response, String label) {
        require(response.getCode() == MessageCode.BAD_REQUEST,
                label + " must be BAD_REQUEST but was " + response.getCode() + "/"
                        + response.getMessage());
        String message = response.getMessage() == null ? "" : response.getMessage();
        require(!message.toLowerCase().contains("select")
                        && !message.toLowerCase().contains("sql")
                        && !message.toLowerCase().contains("exception"),
                label + " must not leak SQL or a stack trace but said " + message);
    }

    // ------------------------------------------------------------------
    // Response helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> scheduleQuery(Integer week) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("academicYear", ACADEMIC_YEAR);
        data.put("semester", SEMESTER);
        if (week != null) {
            data.put("week", week);
        }
        return data;
    }

    private static TeacherScheduleWeekDTO schedule(Message response, String label) {
        requireSuccess(response, label);
        Object raw = response.getData("schedule");
        require(raw != null, label + " must carry the response key schedule");
        TeacherScheduleWeekDTO parsed = GSON.fromJson(GSON.toJson(raw),
                TeacherScheduleWeekDTO.class);
        require(parsed != null, label + " must deserialize the schedule payload");
        return parsed;
    }

    private static List<String> kinds(TeacherScheduleWeekDTO week) {
        List<String> values = new ArrayList<>();
        for (TeacherScheduleEntryDTO entry : week.getEntries()) {
            values.add(entry.getOccurrenceId() + ":" + entry.getDisplayKind());
        }
        return values;
    }

    private static TeacherCalendarDateDTO dateOf(TeacherScheduleWeekDTO week, int weekday) {
        for (TeacherCalendarDateDTO date : week.getDates()) {
            if (date.getTeachingWeekday() == weekday) return date;
        }
        return null;
    }

    /** Collect every displayKind string from the untyped payload, before Gson can swallow it. */
    private static void collectDisplayKinds(Object node, List<String> kinds) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if ("displayKind".equals(entry.getKey())) {
                    kinds.add(String.valueOf(entry.getValue()));
                } else {
                    collectDisplayKinds(entry.getValue(), kinds);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectDisplayKinds(item, kinds);
            }
        }
    }

    private static void requireSuccess(Message response, String label) {
        require(response != null, label + " must return a response");
        require(response.getCode() == MessageCode.SUCCESS,
                label + " must succeed but was " + response.getCode() + ": "
                        + response.getMessage());
    }

    // ------------------------------------------------------------------
    // Server harness
    // ------------------------------------------------------------------

    private static final class Harness {
        private final OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        private final Server server;
        private final Thread thread;

        Harness() {
            this.server = new Server(0, registry, new MessageDispatcher(), null, null);
            this.thread = new Thread(server::start, "teacher-schedule-e2e-server");
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

    // ------------------------------------------------------------------
    // Real JSON-line client
    // ------------------------------------------------------------------

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
            Thread readerThread = new Thread(this::readLoop, "teacher-schedule-e2e-client-reader");
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

    private static void insertFixtures(String jdbc, String user, String pass) throws SQLException {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                    + " VALUES"
                    + " ('" + TEACHER_A + "','Schedule E2E Teacher A','" + SEEDED_PASSWORD_HASH
                    + "','" + SEEDED_PASSWORD_SALT + "',1,'TSE College','Professor'),"
                    + " ('" + TEACHER_ORIGINAL + "','Schedule E2E Original','"
                    + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                    + "',1,'TSE College','Professor'),"
                    + " ('" + TEACHER_SUBSTITUTE + "','Schedule E2E Substitute','"
                    + SEEDED_PASSWORD_HASH + "','" + SEEDED_PASSWORD_SALT
                    + "',1,'TSE College','Professor'),"
                    + " ('" + TEACHER_OTHER + "','Schedule E2E Other','" + SEEDED_PASSWORD_HASH
                    + "','" + SEEDED_PASSWORD_SALT + "',1,'TSE College','Professor')");
            execute(connection, "INSERT INTO course(course_id,course_code,course_name,credit,"
                    + "credit_hours,course_type,description,status) VALUES"
                    + " (" + COURSE_A + ",'TSE101','Timetable E2E One',3.00,48,1,'TSE One',"
                    + "'ACTIVE'),"
                    + " (" + COURSE_B + ",'TSE102','Timetable E2E Two',2.00,32,1,'TSE Two',"
                    + "'ACTIVE')");
            execute(connection, "INSERT INTO course_offering(offering_id,offering_code,"
                    + "course_id,academic_year,semester,capacity,enrolled_count,status) VALUES"
                    + " (" + OFFERING_A + ",'TSE101-2026-3-A'," + COURSE_A + "," + ACADEMIC_YEAR
                    + "," + SEMESTER + ",30,0,2),"
                    + " (" + OFFERING_B + ",'TSE102-2026-3-A'," + COURSE_B + "," + ACADEMIC_YEAR
                    + "," + SEMESTER + ",30,0,2)");
            execute(connection, "INSERT INTO classroom(id,name,capacity,electric) VALUES"
                    + " (" + ROOM_A + ",'TSE Room A',40,1),"
                    + " (" + ROOM_B + ",'TSE Room B',30,1)");
            execute(connection, "INSERT INTO day_template(id,name,version) VALUES"
                    + " (" + DAY_TEMPLATE + ",'TSE full day',1)");
            insertPeriods(connection);
            execute(connection, "INSERT INTO teaching_calendar(id,name,academic_year,semester,"
                    + "week1_start_date,timezone,version,status) VALUES"
                    + " (" + CALENDAR + ",'TSE calendar 2026-3'," + ACADEMIC_YEAR + ","
                    + SEMESTER + ",'2026-09-07','Asia/Shanghai',1,'PUBLISHED')");
            insertCalendarDates(connection);
            execute(connection, "INSERT INTO schedule_plan(id,name,calendar_id,revision,status,"
                    + "created_at,updated_at) VALUES"
                    + " (" + PLAN + ",'TSE published plan'," + CALENDAR
                    + ",1,'PUBLISHED','2026-08-01 00:00:00','2026-08-01 00:00:00')");
            execute(connection, "UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN
                    + " WHERE id=" + CALENDAR);
            execute(connection, "INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,"
                    + "offering_id,teacher_uid,assistant_uid,classroom_id,status,version) VALUES"
                    + " (" + ARRANGEMENT_A + "," + PLAN + "," + OFFERING_A + ",'" + TEACHER_A
                    + "',NULL," + ROOM_A + ",'ACTIVE',1),"
                    + " (" + ARRANGEMENT_B + "," + PLAN + "," + OFFERING_B + ",'" + TEACHER_A
                    + "',NULL," + ROOM_A + ",'ACTIVE',1),"
                    + " (" + ARRANGEMENT_REPLACED + "," + PLAN + "," + OFFERING_A + ",'"
                    + TEACHER_ORIGINAL + "',NULL," + ROOM_A + ",'ACTIVE',1)");
            execute(connection, "INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,"
                    + "arrangement_id,weekday,start_period,end_period,status) VALUES"
                    + " (" + RULE_CROSS + "," + PLAN + "," + OFFERING_A + "," + ARRANGEMENT_A
                    + ",2,1,2,'ACTIVE'),"
                    + " (" + RULE_SAME + "," + PLAN + "," + OFFERING_A + "," + ARRANGEMENT_A
                    + ",3,5,6,'ACTIVE'),"
                    + " (" + RULE_SATURDAY + "," + PLAN + "," + OFFERING_A + "," + ARRANGEMENT_A
                    + ",6,12,13,'ACTIVE'),"
                    + " (" + RULE_SUNDAY + "," + PLAN + "," + OFFERING_A + "," + ARRANGEMENT_A
                    + ",7,1,2,'ACTIVE'),"
                    + " (" + RULE_REPLACED + "," + PLAN + "," + OFFERING_A + ","
                    + ARRANGEMENT_REPLACED + ",4,7,8,'ACTIVE')");
            execute(connection, "INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES"
                    + " (" + RULE_CROSS + ",8),(" + RULE_SAME + ",8),(" + RULE_SATURDAY
                    + ",8),(" + RULE_SUNDAY + ",8),(" + RULE_REPLACED + ",8)");
            execute(connection, "INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,"
                    + "week_no,teaching_weekday) VALUES"
                    + " (" + OCC_CROSS + "," + RULE_CROSS + "," + PLAN
                    + ",'2026-10-27 00:00:00','2026-10-27 01:40:00',8,2),"
                    + " (" + OCC_SAME + "," + RULE_SAME + "," + PLAN
                    + ",'2026-10-28 04:00:00','2026-10-28 05:40:00',8,3),"
                    + " (" + OCC_SATURDAY + "," + RULE_SATURDAY + "," + PLAN
                    + ",'2026-10-31 09:10:00','2026-10-31 10:45:00',8,6),"
                    + " (" + OCC_SUNDAY + "," + RULE_SUNDAY + "," + PLAN
                    + ",'2026-11-01 00:00:00','2026-11-01 01:40:00',8,7),"
                    + " (" + OCC_REPLACED + "," + RULE_REPLACED + "," + PLAN
                    + ",'2026-10-29 06:00:00','2026-10-29 07:40:00',8,4)");
            execute(connection, "INSERT INTO course_schedule_adjustment_request(request_id,"
                    + "offering_id,requested_by,reason,version,status,new_weekday,"
                    + "new_start_period,new_end_period,new_teacher_uid,new_assistant_uid,"
                    + "new_classroom_id,submitted_at,reviewed_by,reviewed_at,review_comment) VALUES"
                    + " (" + REQUEST_CROSS + "," + OFFERING_A + ",'" + TEACHER_A
                    + "','TSE cross-week move',1,'APPROVED',3,3,4,'" + TEACHER_A + "',NULL,"
                    + ROOM_B + ",'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                    + " (" + REQUEST_SAME + "," + OFFERING_A + ",'" + TEACHER_A
                    + "','TSE same-week move',1,'APPROVED',5,5,6,'" + TEACHER_A + "',NULL,"
                    + ROOM_B + ",'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                    + " (" + REQUEST_REPLACED + "," + OFFERING_A + ",'" + TEACHER_ORIGINAL
                    + "','TSE substitute',1,'APPROVED',5,7,8,'" + TEACHER_SUBSTITUTE
                    + "',NULL," + ROOM_B
                    + ",'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok')");
            // DATETIME 保存 UTC 墙钟：2026-11-04 02:00 UTC == 第 9 周周三 10:00 Asia/Shanghai，
            // 2026-10-30 02:00 / 05:00 UTC == 第 8 周周五。
            execute(connection, "INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                    + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                    + "classroom_id,status) VALUES"
                    + " (" + ADJUSTMENT_CROSS + "," + REQUEST_CROSS + "," + OCC_CROSS
                    + ",'2026-11-04 02:00:00','2026-11-04 03:40:00','" + TEACHER_A + "',NULL,"
                    + ROOM_B + ",'ACTIVE'),"
                    + " (" + ADJUSTMENT_SAME + "," + REQUEST_SAME + "," + OCC_SAME
                    + ",'2026-10-30 02:00:00','2026-10-30 03:40:00','" + TEACHER_A + "',NULL,"
                    + ROOM_B + ",'ACTIVE'),"
                    + " (" + ADJUSTMENT_REPLACED + "," + REQUEST_REPLACED + "," + OCC_REPLACED
                    + ",'2026-10-30 05:00:00','2026-10-30 06:40:00','" + TEACHER_SUBSTITUTE
                    + "',NULL," + ROOM_B + ",'ACTIVE')");
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
                LocalDate date = WEEK_ONE_START.plusDays((long) (week - 1) * 7 + (weekday - 1));
                int teachingDay = weekday == 7 ? 0 : 1;
                rows.add("(" + CALENDAR + ",'" + date + "'," + week + "," + weekday + ","
                        + DAY_TEMPLATE + "," + teachingDay + ")");
            }
        }
        execute(connection, "INSERT INTO calendar_date(calendar_id,local_date,week_no,"
                + "teaching_weekday,day_template_id,is_teaching_day) VALUES"
                + String.join(",", rows));
    }

    private static void cleanFixtures(String jdbc, String user, String pass) throws SQLException {
        assertDatabase(jdbc, user, pass, connection -> {
            execute(connection, "DELETE FROM course_schedule_adjustment WHERE adjustment_id"
                    + " BETWEEN " + ADJUSTMENT_CROSS + " AND " + ADJUSTMENT_REPLACED);
            execute(connection, "DELETE FROM course_schedule_adjustment_request WHERE request_id"
                    + " BETWEEN " + REQUEST_CROSS + " AND " + REQUEST_REPLACED);
            execute(connection, "DELETE FROM course_occurrence WHERE id BETWEEN " + OCC_CROSS
                    + " AND " + OCC_REPLACED);
            execute(connection, "DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN "
                    + RULE_CROSS + " AND " + RULE_REPLACED);
            execute(connection, "DELETE FROM course_schedule_rule WHERE id BETWEEN " + RULE_CROSS
                    + " AND " + RULE_REPLACED);
            execute(connection, "DELETE FROM course_schedule_arrangement WHERE arrangement_id"
                    + " BETWEEN " + ARRANGEMENT_A + " AND " + ARRANGEMENT_REPLACED);
            execute(connection, "UPDATE teaching_calendar SET current_schedule_plan_id=NULL"
                    + " WHERE id=" + CALENDAR);
            execute(connection, "DELETE FROM schedule_plan WHERE id=" + PLAN);
            execute(connection, "DELETE FROM calendar_date WHERE calendar_id=" + CALENDAR);
            execute(connection, "DELETE FROM period_definition WHERE day_template_id="
                    + DAY_TEMPLATE);
            execute(connection, "DELETE FROM day_template WHERE id=" + DAY_TEMPLATE);
            execute(connection, "DELETE FROM course_offering WHERE offering_id IN ("
                    + OFFERING_A + "," + OFFERING_B + ")");
            execute(connection, "DELETE FROM course WHERE course_id IN (" + COURSE_A + ","
                    + COURSE_B + ")");
            execute(connection, "DELETE FROM classroom WHERE id IN (" + ROOM_A + "," + ROOM_B + ")");
            execute(connection, "DELETE FROM teaching_calendar WHERE id=" + CALENDAR);
            // LEFT(...) instead of LIKE 'tt-%': `_` is a LIKE wildcard, so the deletion predicate
            // must stay exactly as narrow as the fixture UID prefix.
            execute(connection, "DELETE FROM tbl_user WHERE LEFT(UID, 3) = 'tt-'");
        });
        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT COUNT(*) FROM teaching_calendar WHERE id="
                    + CALENDAR) == 0, "cleanup must remove the fixture calendar");
            require(queryInt(connection, "SELECT COUNT(*) FROM calendar_date WHERE calendar_id="
                    + CALENDAR) == 0, "cleanup must remove every fixture calendar date");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_occurrence WHERE id BETWEEN "
                    + OCC_CROSS + " AND " + OCC_REPLACED) == 0,
                    "cleanup must remove every fixture occurrence");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_schedule_adjustment"
                    + " WHERE adjustment_id BETWEEN " + ADJUSTMENT_CROSS + " AND "
                    + ADJUSTMENT_REPLACED) == 0, "cleanup must remove every fixture adjustment");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering WHERE offering_id"
                    + " IN (" + OFFERING_A + "," + OFFERING_B + ")") == 0,
                    "cleanup must remove every fixture offering");
            require(queryInt(connection, "SELECT COUNT(*) FROM tbl_user WHERE LEFT(UID, 3) = 'tt-'")
                    == 0, "cleanup must remove every fixture teacher");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering WHERE offering_id"
                    + " IN (2001,2002)") == 2
                    && queryInt(connection, "SELECT COUNT(*) FROM course_occurrence WHERE id=4201")
                    == 1,
                    "cleanup must never touch the shared seeded rows");
        });
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
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row: " + sql);
            return rows.getInt(1);
        }
    }

    private static Object queryScalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row: " + sql);
            return rows.getObject(1);
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
            Object liveDatabase = queryScalar(connection, "SELECT DATABASE()");
            require(TEST_DATABASE.equals(liveDatabase),
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
        Object database = queryScalar(connection, "SELECT DATABASE()");
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
