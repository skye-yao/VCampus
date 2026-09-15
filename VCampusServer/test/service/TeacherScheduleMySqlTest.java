package service;

import dao.AdminScheduleDAO;
import dao.TeacherCourseQueryDAO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import util.DBUtil;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 教师有效课次与日期查询的真实 MySQL 测试。
 *
 * <p>本类自己重建受保护的 {@code virtual_campus_course_test} 架构（init.sql 的 tbl_user → V001 →
 * V002 → V003 → seed-course-test.sql → V004 → V005），再插入私有 ID/UID 范围的 fixture，只清理
 * 自己的行。未传入 {@code mysql} 时只打印 SKIP 并返回，绝不把 SKIP 当成 PASS。
 *
 * <p>fixture 覆盖：跨周调课（周 8 → 周 9）、同周调课、未调整课次、周末课、第 13 节、按日期不同的
 * 日课表模板、无选课窗口、被替换教师、DISABLED 安排与 DISABLED 规则、CANCELLED 调课。所有断言都
 * 用固定 Clock，因此不依赖运行当天的真实日期。
 */
public final class TeacherScheduleMySqlTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DEFAULT_CONFIG = "VCampusServer/src/resources/db.properties";
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    /** 命中第 8 教学周（2026-10-26..2026-11-01，Asia/Shanghai）的固定时刻。 */
    private static final String WEEK_EIGHT_INSTANT = "2026-10-27T02:00:00Z";
    /** 落在本学期（第 1..10 周，2026-09-07..2026-11-15）之外的固定时刻。 */
    private static final String OUTSIDE_TERM_INSTANT = "2027-02-01T00:00:00Z";

    private TeacherScheduleMySqlTest() {
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
            System.out.println("SKIP: no `mysql` argument, so the teacher schedule query test was "
                    + "not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }

        Properties properties = loadProperties(config);
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        Class.forName(requiredProperty(properties, "db.driver"));
        String testUrl = withTestAuthentication(url);
        ensureTestSchema(testUrl, properties);
        pointDBUtilAt(testUrl, properties);

        try (Connection connection = DBUtil.getConnection()) {
            require(TEST_DATABASE.equals(currentDatabase(connection)),
                    "Connected schema changed after URL validation");
            // DBUtil.getConnection() is the very factory the DAO reads through, so asserting the
            // session time zone here says something about the connection the DAO actually gets.
            setUtc(connection);
            rebuildSchema(connection, root);
            insertFixtures();
            try {
                verifySharedRowsSurvive();
                verifyWeekEight();
                verifyCrossWeekTarget();
                verifyReplacedTeacher();
                verifyOtherWeekAndUnrelatedTeacher();
                verifyDefaultWeekAndCurrentWeek();
                verifyRejections();
            } finally {
                cleanFixtures();
            }
            require(count("SELECT COUNT(*) FROM teaching_calendar WHERE id IN (8301,8302)") == 0,
                    "cleanup must remove the fixture calendars");
            require(count("SELECT COUNT(*) FROM calendar_date WHERE calendar_id IN (8301,8302)") == 0,
                    "cleanup must remove every fixture calendar date");
            require(count("SELECT COUNT(*) FROM period_definition"
                    + " WHERE day_template_id IN (8851,8852,8853)") == 0,
                    "cleanup must remove every fixture period definition");
            require(count("SELECT COUNT(*) FROM course_schedule_adjustment"
                    + " WHERE adjustment_id BETWEEN 8751 AND 8756") == 0,
                    "cleanup must remove every fixture adjustment");
            require(count("SELECT COUNT(*) FROM course_schedule_adjustment_request"
                    + " WHERE request_id BETWEEN 8701 AND 8706") == 0,
                    "cleanup must remove every fixture adjustment request");
            require(count("SELECT COUNT(*) FROM course_occurrence"
                    + " WHERE id BETWEEN 8601 AND 8611") == 0,
                    "cleanup must remove every fixture occurrence");
            require(count("SELECT COUNT(*) FROM course_schedule_arrangement"
                    + " WHERE arrangement_id BETWEEN 8501 AND 8504") == 0,
                    "cleanup must remove every fixture arrangement");
            require(count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 8401 AND 8403") == 0,
                    "cleanup must remove every fixture plan");
            require(count("SELECT COUNT(*) FROM course_offering"
                    + " WHERE offering_id IN (8201,8202)") == 0,
                    "cleanup must remove every fixture offering");
            require(count("SELECT COUNT(*) FROM classroom WHERE id IN (8801,8802)") == 0,
                    "cleanup must remove every fixture classroom");
            // LEFT(...) rather than LIKE 'ts-%': in LIKE the `_` is a single-character wildcard,
            // so the deletion predicate must stay exactly as narrow as the fixture UID prefix.
            require(count("SELECT COUNT(*) FROM tbl_user WHERE LEFT(UID, 3) = 'ts-'") == 0,
                    "cleanup must remove every fixture teacher");
            require(count("SELECT COUNT(*) FROM course_occurrence"
                            + " WHERE id IN (4201,4202,4203,4204)") == 4
                            && count("SELECT COUNT(*) FROM course_offering"
                            + " WHERE offering_id IN (2001,2002)") == 2,
                    "cleanup must never touch the shared seeded rows");
        }
        System.out.println("Teacher schedule MySQL test passed in " + TEST_DATABASE + ".");
    }

    // ------------------------------------------------------------------ checks

    /** The shared seed and the stray dev rows must survive this suite's reads and cleanup. */
    private static void verifySharedRowsSurvive() throws Exception {
        require(count("SELECT COUNT(*) FROM course_occurrence WHERE id=4201") == 1,
                "the pre-existing seeded occurrence must still be there");
        require(count("SELECT COUNT(*) FROM course_offering"
                        + " WHERE offering_id IN (2001,2002)") == 2,
                "the pre-existing seeded offerings must still be there");
        require(count("SELECT COUNT(*) FROM course_selection_window"
                        + " WHERE academic_year=2026 AND semester=3") == 0,
                "this fixture term must have no selection window, so the query cannot depend on one");
    }

    private static void verifyWeekEight() throws Exception {
        TeacherScheduleWeekDTO week = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, 8);

        require("8301".equals(week.getCalendarId()) && "Asia/Shanghai".equals(week.getTimezone()),
                "the response must carry the teaching calendar identity");
        require(week.getWeek() == 8 && week.getMinWeek() == 1 && week.getMaxWeek() == 10,
                "minWeek/maxWeek must span every calendar week, observed "
                        + week.getMinWeek() + ".." + week.getMaxWeek());
        require(week.getCurrentWeek() != null && week.getCurrentWeek() == 8,
                "the injected clock must land in week eight, observed " + week.getCurrentWeek());

        // dates: seven rows, Monday..Sunday, weekend flag preserved.
        List<TeacherCalendarDateDTO> dates = week.getDates();
        require(dates.size() == 7, "week eight must expose seven dates, observed " + dates.size());
        TeacherCalendarDateDTO monday = dates.get(0);
        require("2026-10-26".equals(monday.getDate()) && monday.getWeek() == 8
                        && monday.getTeachingWeekday() == 1 && monday.isTeachingDay(),
                "the first date must be the teaching-week Monday");
        TeacherCalendarDateDTO sunday = dates.get(6);
        require("2026-11-01".equals(sunday.getDate()) && sunday.getTeachingWeekday() == 7
                        && !sunday.isTeachingDay(),
                "the last date must be Sunday with its teaching-day flag preserved");
        for (int index = 0; index < dates.size(); index++) {
            require(dates.get(index).getTeachingWeekday() == index + 1,
                    "dates must be ordered by teaching_weekday");
        }

        // periods: per date, so a different day template shrinks Sunday.
        List<TeacherPeriodDTO> periods = week.getPeriods();
        require(periods.size() == 80,
                "six 13-period weekdays plus a two-period Sunday must yield 80 rows, observed "
                        + periods.size());
        require(period("2026-10-26", 1, periods).getStartTime().equals("08:00:00")
                        && period("2026-10-26", 1, periods).getEndTime().equals("08:45:00"),
                "the first period must be read as a local wall clock in HH:mm:ss, observed "
                        + period("2026-10-26", 1, periods).getStartTime());
        require(period("2026-10-31", 13, periods) != null,
                "the 13th period must exist, so nothing may hardcode a ten-period day");
        require(period("2026-11-01", 1, periods).getStartTime().equals("09:00:00")
                        && period("2026-11-01", 3, periods) == null,
                "Sunday must use its own day template, observed "
                        + period("2026-11-01", 1, periods).getStartTime());

        // entries: the whole week, ordered, with the right owner on every block.
        List<TeacherScheduleEntryDTO> entries = week.getEntries();
        require(summary(entries).equals(List.of(
                        "8608:NORMAL:1:7-8:2026-10-26:w8",
                        "8601:ADJUSTED_ORIGINAL:2:1-2:2026-10-27:w8",
                        "8610:ADJUSTED_ORIGINAL:2:5-6:2026-10-27:w8",
                        "8605:ADJUSTED_ORIGINAL:3:3-4:2026-10-28:w8",
                        "8605:ADJUSTED_TARGET:4:5-6:2026-10-29:w8",
                        "8610:ADJUSTED_TARGET:5:3-4:2026-10-30:w8",
                        "8604:NORMAL:5:13-13:2026-10-30:w8",
                        "8603:NORMAL:6:5-6:2026-10-31:w8")),
                "week eight must return the effective blocks in a stable order, observed "
                        + summary(entries));

        TeacherScheduleEntryDTO plain = entry(entries, "8608", ScheduleDisplayKindDTO.NORMAL);
        require("8201".equals(plain.getOfferingId()) && "TS101".equals(plain.getCourseCode())
                        && "Teacher Timetable One".equals(plain.getCourseName()),
                "a normal block must carry the teaching-class identity of the rule");
        require("TS Main Teacher, TS Assistant Teacher".equals(plain.getTeacher()),
                "a normal block must name the teacher and the assistant, observed "
                        + plain.getTeacher());
        require("TS Room A".equals(plain.getLocation()),
                "a normal block must resolve the arrangement classroom");
        require(plain.getAdjustmentId() == null && plain.getOriginalScheduleText() == null
                        && plain.getAdjustedScheduleText() == null
                        && plain.getAdjustmentReason() == null && plain.isCanRequestAdjustment(),
                "a normal block must be requestable and carry no adjustment text");

        TeacherScheduleEntryDTO moved = entry(entries, "8601", ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL);
        require(!moved.isCanRequestAdjustment() && "8751".equals(moved.getAdjustmentId()),
                "the original half of an adjustment must be display-only");
        require("周二 第1-2节 TS Room A".equals(moved.getOriginalScheduleText())
                        && "周二 第1-2节 TS Room B".equals(moved.getAdjustedScheduleText())
                        && "TS cross-week move".equals(moved.getAdjustmentReason()),
                "the original half must describe both positions, observed "
                        + moved.getOriginalScheduleText() + " -> " + moved.getAdjustedScheduleText());

        TeacherScheduleEntryDTO sameWeekTarget =
                entry(entries, "8605", ScheduleDisplayKindDTO.ADJUSTED_TARGET);
        require("TS Main Teacher".equals(sameWeekTarget.getTeacher())
                        && "TS Room B".equals(sameWeekTarget.getLocation())
                        && "周四 第5-6节 TS Room B".equals(sameWeekTarget.getAdjustedScheduleText()),
                "the target half of a same-week move must use the adjusted position and room");
        require(!sameWeekTarget.isCanRequestAdjustment(),
                "an occurrence with an effective adjustment must not be requestable again");

        TeacherScheduleEntryDTO late = entry(entries, "8604", ScheduleDisplayKindDTO.NORMAL);
        require(late.getStartPeriod() == 13 && late.getEndPeriod() == 13,
                "the 13th period block must keep its coordinates");
        require(entry(entries, "8603", ScheduleDisplayKindDTO.NORMAL).getDayOfWeek() == 6,
                "a weekend block must appear in the week grid");

        // An ACTIVE arrangement with no classroom must stay room-less, and the shared schedule
        // text helper must then omit the location instead of printing "null".
        TeacherScheduleEntryDTO roomless =
                entry(entries, "8610", ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL);
        require(roomless.getLocation() == null
                        && "TS Main Teacher".equals(roomless.getTeacher())
                        && "周二 第5-6节".equals(roomless.getOriginalScheduleText())
                        && "周五 第3-4节".equals(roomless.getAdjustedScheduleText()),
                "a room-less arrangement must keep a null location and location-less text, observed "
                        + roomless.getLocation() + "/" + roomless.getOriginalScheduleText());
        TeacherScheduleEntryDTO roomlessTarget =
                entry(entries, "8610", ScheduleDisplayKindDTO.ADJUSTED_TARGET);
        require(roomlessTarget.getLocation() == null
                        && "周五 第3-4节".equals(roomlessTarget.getAdjustedScheduleText()),
                "the target half of a room-less adjustment must also stay room-less, observed "
                        + roomlessTarget.getLocation());
    }

    private static void verifyCrossWeekTarget() throws Exception {
        TeacherScheduleWeekDTO target = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, 9);
        require(target.getWeek() == 9 && target.getCurrentWeek() == 8,
                "an explicit week must win over the clock's week");
        require(summary(target.getEntries()).equals(List.of(
                        "8601:ADJUSTED_TARGET:2:1-2:2026-11-03:w9")),
                "a cross-week move must show up as a target in the destination week, observed "
                        + summary(target.getEntries()));
        TeacherScheduleEntryDTO entry = target.getEntries().get(0);
        require("TS Room B".equals(entry.getLocation())
                        && "TS Main Teacher".equals(entry.getTeacher())
                        && "周二 第1-2节 TS Room A".equals(entry.getOriginalScheduleText()),
                "the target must describe where it came from");

        TeacherScheduleWeekDTO origin = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, 8);
        require(countKind(origin.getEntries(), "8601", ScheduleDisplayKindDTO.NORMAL) == 0,
                "a replaced occurrence must never also be reported as normal");
    }

    private static void verifyReplacedTeacher() throws Exception {
        TeacherScheduleWeekDTO original = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-original", 2026, 3, 8);
        require(summary(original.getEntries()).equals(List.of(
                        "8607:ADJUSTED_ORIGINAL:2:3-4:2026-10-27:w8")),
                "the replaced teacher must only see the original half, observed "
                        + summary(original.getEntries()));
        TeacherScheduleEntryDTO originalBlock = original.getEntries().get(0);
        require("TS Original Teacher".equals(originalBlock.getTeacher())
                        && "周二 第3-4节 TS Room B".equals(originalBlock.getOriginalScheduleText())
                        && "周五 第7-8节 TS Room A".equals(originalBlock.getAdjustedScheduleText()),
                "the replaced teacher must see the published arrangement's position");

        TeacherScheduleWeekDTO substitute = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-substitute", 2026, 3, 8);
        require(summary(substitute.getEntries()).equals(List.of(
                        "8607:ADJUSTED_TARGET:5:7-8:2026-10-30:w8")),
                "the substitute teacher must only see the target half, observed "
                        + summary(substitute.getEntries()));
        TeacherScheduleEntryDTO substituteBlock = substitute.getEntries().get(0);
        require("TS Substitute Teacher".equals(substituteBlock.getTeacher())
                        && "TS Room A".equals(substituteBlock.getLocation())
                        && "8202".equals(substituteBlock.getOfferingId())
                        && "TS102".equals(substituteBlock.getCourseCode()),
                "a substitute must be given the effective course and room");

        require(countKind(original.getEntries(), "8607", ScheduleDisplayKindDTO.ADJUSTED_TARGET) == 0
                        && countKind(substitute.getEntries(), "8607",
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL) == 0,
                "neither teacher may see the other half of a replacement");
    }

    private static void verifyOtherWeekAndUnrelatedTeacher() throws Exception {
        // Week three uses a day template with no period_definition rows, so its UTC window is the
        // empty set: the NORMAL and ADJUSTED_ORIGINAL paths still answer, while adjustedTargets
        // must return early instead of widening the window or falling back to another week.
        TeacherScheduleWeekDTO weekThree = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, 3);
        require(summary(weekThree.getEntries()).equals(List.of(
                        "8611:ADJUSTED_ORIGINAL:2:3-4:2026-09-22:w3",
                        "8602:NORMAL:4:3-4:2026-09-24:w3")),
                "another week must be filtered by its own occurrences, observed "
                        + summary(weekThree.getEntries()));
        require(weekThree.getPeriods().isEmpty() && weekThree.getDates().size() == 7,
                "a template without period rows must yield no periods yet keep seven dates, observed "
                        + weekThree.getPeriods().size());
        // The suppressed adjustment really does target a date inside week three, so its absence
        // above can only be the empty UTC window, not a misplaced fixture.
        require(count("SELECT COUNT(*) FROM course_schedule_adjustment j"
                        + " JOIN calendar_date cd ON cd.calendar_id = 8301"
                        + " AND cd.local_date = DATE(j.start_at_utc)"
                        + " WHERE j.adjustment_id = 8756 AND j.status = 'ACTIVE'"
                        + " AND cd.week_no = 3") == 1,
                "the suppressed adjustment must really land inside week three");
        require(weekThree.getCurrentWeek() == 8,
                "currentWeek must not change with the requested week");

        TeacherScheduleWeekDTO unrelated = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-other", 2026, 3, 8);
        require(unrelated.getEntries().isEmpty(),
                "an unrelated teacher must have no block, observed "
                        + summary(unrelated.getEntries()));
        require(unrelated.getDates().size() == 7 && !unrelated.getPeriods().isEmpty(),
                "an unrelated teacher must still get the navigable calendar");

        // 8606 (DISABLED arrangement), 8609 (DISABLED rule) and 8754 (CANCELLED adjustment)
        // must never leak into any of the assertions above.
        require(count("SELECT COUNT(*) FROM course_occurrence WHERE id IN (8606,8609)") == 2,
                "the disabled fixtures must still exist, so their absence from entries is meaningful");
    }

    private static void verifyDefaultWeekAndCurrentWeek() throws Exception {
        TeacherScheduleWeekDTO inWeek = serviceAt(WEEK_EIGHT_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, null);
        require(inWeek.getWeek() == 8 && inWeek.getCurrentWeek() == 8
                        && inWeek.getEntries().size() == 8,
                "a null week must fall back to the clock's teaching week, observed "
                        + inWeek.getWeek());

        TeacherScheduleWeekDTO outside = serviceAt(OUTSIDE_TERM_INSTANT)
                .loadTeachingSchedule("ts-owner", 2026, 3, null);
        require(outside.getCurrentWeek() == null && outside.getWeek() == 1,
                "outside the term the week must fall back to minWeek with a null currentWeek, "
                        + "observed week " + outside.getWeek() + "/current "
                        + outside.getCurrentWeek());
        require(outside.getEntries().isEmpty() && outside.getDates().size() == 7,
                "the fallback week must still be a navigable, empty week");
    }

    private static void verifyRejections() throws Exception {
        TeacherCourseQueryService service = serviceAt(WEEK_EIGHT_INSTANT);
        expectInvalidMessage(() -> service.loadTeachingSchedule("ts-owner", -1, 3, 1),
                "学年无效",
                "a non-positive year must be rejected as an invalid term");
        expectInvalidMessage(() -> service.loadTeachingSchedule("ts-owner", 2026, 9, 1),
                "学期无效",
                "an out-of-range semester must be rejected as an invalid term");
        expectInvalid(() -> service.loadTeachingSchedule("ts-owner", 2026, 3, 0),
                "week zero must be rejected");
        expectInvalid(() -> service.loadTeachingSchedule("ts-owner", 2026, 3, 11),
                "a week past maxWeek must be rejected");
        expectInvalid(() -> service.loadTeachingSchedule("ts-owner", 2026, 3, -1),
                "a negative week must be rejected");
        expectInvalidMessage(() -> service.loadTeachingSchedule("ts-owner", 2025, 1, 1),
                "该学期暂无已发布的教学日历",
                "a term with no calendar must be rejected with a client-facing message");
        // 2026/1 is a fixture-owned calendar whose current pointer names a DRAFT plan, so this
        // rejection does not depend on any shared seed row keeping a NULL pointer.
        expectInvalidMessage(() -> service.loadTeachingSchedule("ts-owner", 2026, 1, 1),
                "该学期暂无已发布的教学日历",
                "a calendar whose current plan is not published must be rejected, not fall back");
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('ts-owner','TS Main Teacher','x','x',1,'TS College','Professor'),"
                + "('ts-assistant','TS Assistant Teacher','x','x',1,'TS College','Assistant'),"
                + "('ts-original','TS Original Teacher','x','x',1,'TS College','Professor'),"
                + "('ts-substitute','TS Substitute Teacher','x','x',1,'TS College','Professor'),"
                + "('ts-other','TS Other Teacher','x','x',1,'TS College','Professor')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,description,status) VALUES"
                + "(8101,'TS101','Teacher Timetable One',3.00,48,1,'TS One','ACTIVE'),"
                + "(8102,'TS102','Teacher Timetable Two',2.00,32,1,'TS Two','ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,enrolled_count,status) VALUES"
                + "(8201,'TS101-2026-3-A',8101,2026,3,30,0,2),"
                + "(8202,'TS102-2026-3-A',8102,2026,3,30,0,2)");
        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES"
                + "(8801,'TS Room A',40,1),(8802,'TS Room B',30,1)");
        execute("INSERT INTO day_template(id,name,version) VALUES"
                + "(8851,'TS weekday',1),(8852,'TS short day',1),(8853,'TS unconfigured day',1)");
        insertPeriods();
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES"
                + "(8301,'TS calendar 2026-3',2026,3,'2026-09-07','Asia/Shanghai',1,'PUBLISHED'),"
                + "(8302,'TS draft-only calendar 2026-1',2026,1,'2026-02-23','Asia/Shanghai',1,"
                + "'PUBLISHED')");
        insertCalendarDates();
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES"
                + "(8401,'TS published plan',8301,1,'PUBLISHED','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00'),"
                + "(8402,'TS draft plan',8301,1,'DRAFT','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00'),"
                + "(8403,'TS unpublished plan',8302,1,'DRAFT','2026-08-01 00:00:00',"
                + "'2026-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=8401 WHERE id=8301");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=8403 WHERE id=8302");
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,assistant_uid,classroom_id,status,version) VALUES"
                + "(8501,8401,8201,'ts-owner','ts-assistant',8801,'ACTIVE',1),"
                + "(8502,8401,8202,'ts-original',NULL,8802,'ACTIVE',1),"
                + "(8503,8401,8201,'ts-owner',NULL,NULL,'DISABLED',1),"
                + "(8504,8401,8201,'ts-owner',NULL,NULL,'ACTIVE',1)");
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES"
                + "(8551,8401,8201,8501,2,1,2,'ACTIVE'),"
                + "(8552,8401,8201,8501,4,3,4,'ACTIVE'),"
                + "(8553,8401,8201,8501,6,5,6,'ACTIVE'),"
                + "(8554,8401,8201,8501,5,13,13,'ACTIVE'),"
                + "(8555,8401,8201,8501,3,3,4,'ACTIVE'),"
                + "(8556,8401,8201,8503,1,1,2,'ACTIVE'),"
                + "(8557,8401,8202,8502,2,3,4,'ACTIVE'),"
                + "(8558,8401,8201,8501,1,7,8,'ACTIVE'),"
                + "(8559,8401,8202,8502,4,9,10,'DISABLED'),"
                + "(8560,8401,8201,8504,2,5,6,'ACTIVE'),"
                + "(8561,8401,8201,8501,2,3,4,'ACTIVE')");
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES"
                + "(8551,8),(8552,3),(8553,8),(8554,8),(8555,8),(8556,8),(8557,8),(8558,8),(8559,8),"
                + "(8560,8),(8561,3)");
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES"
                + "(8601,8551,8401,'2026-10-27 00:00:00','2026-10-27 01:40:00',8,2),"
                + "(8602,8552,8401,'2026-09-24 02:00:00','2026-09-24 03:40:00',3,4),"
                + "(8603,8553,8401,'2026-10-31 05:00:00','2026-10-31 06:40:00',8,6),"
                + "(8604,8554,8401,'2026-10-30 11:00:00','2026-10-30 11:45:00',8,5),"
                + "(8605,8555,8401,'2026-10-28 02:00:00','2026-10-28 03:40:00',8,3),"
                + "(8606,8556,8401,'2026-10-26 00:00:00','2026-10-26 01:40:00',8,1),"
                + "(8607,8557,8401,'2026-10-27 02:00:00','2026-10-27 03:40:00',8,2),"
                + "(8608,8558,8401,'2026-10-26 07:00:00','2026-10-26 08:40:00',8,1),"
                + "(8609,8559,8401,'2026-10-29 03:00:00','2026-10-29 04:40:00',8,4),"
                + "(8610,8560,8401,'2026-10-27 04:00:00','2026-10-27 05:40:00',8,2),"
                + "(8611,8561,8401,'2026-09-22 02:00:00','2026-09-22 03:40:00',3,2)");
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,submitted_at,reviewed_by,"
                + "reviewed_at,review_comment) VALUES"
                + "(8701,8201,'ts-owner','TS cross-week move',1,'APPROVED',2,1,2,'ts-owner',"
                + "NULL,8802,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                + "(8702,8201,'ts-owner','TS same-week move',1,'APPROVED',4,5,6,'ts-owner',"
                + "NULL,8802,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                + "(8703,8202,'ts-original','TS substitute',1,'APPROVED',5,7,8,'ts-substitute',"
                + "NULL,8801,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                + "(8704,8201,'ts-owner','TS cancelled',1,'APPROVED',1,9,10,'ts-owner',"
                + "NULL,8802,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                + "(8705,8201,'ts-owner','TS room-less move',1,'APPROVED',5,3,4,'ts-owner',"
                + "NULL,NULL,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok'),"
                + "(8706,8201,'ts-owner','TS suppressed target',1,'APPROVED',3,5,6,'ts-owner',"
                + "NULL,NULL,'2026-10-20 00:00:00','admin-alpha','2026-10-21 00:00:00','ok')");
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES"
                // 周 8 周二 → 周 9 周二（跨周）。
                + "(8751,8701,8601,'2026-11-03 00:00:00','2026-11-03 01:40:00','ts-owner',"
                + "NULL,8802,'ACTIVE'),"
                // 周 8 周三 → 周 8 周四（同周）。
                + "(8752,8702,8605,'2026-10-29 06:00:00','2026-10-29 07:40:00','ts-owner',"
                + "NULL,8802,'ACTIVE'),"
                // 周 8 周二 → 周 8 周五，生效教师换成 B。
                + "(8753,8703,8607,'2026-10-30 05:30:00','2026-10-30 07:10:00','ts-substitute',"
                + "NULL,8801,'ACTIVE'),"
                + "(8754,8704,8608,'2026-10-26 09:00:00','2026-10-26 10:40:00','ts-owner',"
                + "NULL,8802,'CANCELLED'),"
                // 周 8 周二 → 周 8 周五，原教室与目标教室都为空。
                + "(8755,8705,8610,'2026-10-30 02:00:00','2026-10-30 03:40:00','ts-owner',"
                + "NULL,NULL,'ACTIVE'),"
                // 目标确实落在周 3，但周 3 没有 period 行，窗口为空，因此必须被丢弃。
                + "(8756,8706,8611,'2026-09-23 06:00:00','2026-09-23 07:40:00','ts-owner',"
                + "NULL,NULL,'ACTIVE')");
    }

    private static void insertPeriods() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int period = 1; period <= 13; period++) {
            LocalTime start = LocalTime.of(8, 0).plusMinutes((period - 1) * 55L);
            LocalTime end = start.plusMinutes(45);
            rows.add("(8851," + period + ",'" + start + ":00','" + end + ":00')");
        }
        rows.add("(8852,1,'09:00:00','09:45:00')");
        rows.add("(8852,2,'09:55:00','10:40:00')");
        execute("INSERT INTO period_definition(day_template_id,period_no,start_time,end_time) VALUES"
                + String.join(",", rows));
    }

    private static void insertCalendarDates() throws Exception {
        LocalDate weekOne = LocalDate.of(2026, 9, 7);
        List<String> rows = new ArrayList<>();
        for (int week = 1; week <= 10; week++) {
            for (int weekday = 1; weekday <= 7; weekday++) {
                LocalDate date = weekOne.plusDays((long) (week - 1) * 7 + (weekday - 1));
                // 第 8 周周日与整个第 10 周不是教学日；第 8 周周日另用短日模板；
                // 第 3 周整周使用没有任何 period_definition 行的模板，用来构造空 UTC 窗口。
                int teachingDay = week == 10 || (week == 8 && weekday == 7) ? 0 : 1;
                long template;
                if (week == 3) template = 8853;
                else if (week == 8 && weekday == 7) template = 8852;
                else template = 8851;
                rows.add("(8301,'" + date + "'," + week + "," + weekday + "," + template + ","
                        + teachingDay + ")");
            }
        }
        execute("INSERT INTO calendar_date(calendar_id,local_date,week_no,teaching_weekday,"
                + "day_template_id,is_teaching_day) VALUES" + String.join(",", rows));
    }

    private static void cleanFixtures() throws Exception {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 8751 AND 8756");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 8701 AND 8706");
        execute("DELETE FROM course_occurrence WHERE id BETWEEN 8601 AND 8611");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 8551 AND 8561");
        execute("DELETE FROM course_schedule_rule WHERE id BETWEEN 8551 AND 8561");
        execute("DELETE FROM course_schedule_arrangement WHERE arrangement_id BETWEEN 8501 AND 8504");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id IN (8301,8302)");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 8401 AND 8403");
        execute("DELETE FROM calendar_date WHERE calendar_id IN (8301,8302)");
        execute("DELETE FROM period_definition WHERE day_template_id IN (8851,8852,8853)");
        execute("DELETE FROM day_template WHERE id IN (8851,8852,8853)");
        execute("DELETE FROM course_offering WHERE offering_id IN (8201,8202)");
        execute("DELETE FROM course WHERE course_id IN (8101,8102)");
        execute("DELETE FROM classroom WHERE id IN (8801,8802)");
        execute("DELETE FROM teaching_calendar WHERE id IN (8301,8302)");
        // LEFT(...) instead of LIKE 'ts-%': `_` is a LIKE wildcard, so the deletion must not be
        // able to reach a UID such as 'tsx-...'.
        execute("DELETE FROM tbl_user WHERE LEFT(UID, 3) = 'ts-'");
    }

    // ------------------------------------------------------------------ checks

    private static TeacherCourseQueryService serviceAt(String instant) {
        return new TeacherCourseQueryService(new TeacherCourseQueryDAO(), new AdminScheduleDAO(),
                new TeacherAccessPolicy(), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static List<String> summary(List<TeacherScheduleEntryDTO> entries) {
        List<String> lines = new ArrayList<>();
        for (TeacherScheduleEntryDTO entry : entries) {
            lines.add(entry.getOccurrenceId() + ":" + entry.getDisplayKind() + ":"
                    + entry.getDayOfWeek() + ":" + entry.getStartPeriod() + "-"
                    + entry.getEndPeriod() + ":" + entry.getLocalDate() + ":w" + entry.getWeek());
        }
        return lines;
    }

    private static TeacherScheduleEntryDTO entry(List<TeacherScheduleEntryDTO> entries,
                                                 String occurrenceId, ScheduleDisplayKindDTO kind) {
        for (TeacherScheduleEntryDTO entry : entries) {
            if (occurrenceId.equals(entry.getOccurrenceId()) && entry.getDisplayKind() == kind) {
                return entry;
            }
        }
        throw new AssertionError("No " + kind + " block for occurrence " + occurrenceId
                + " in " + summary(entries));
    }

    private static int countKind(List<TeacherScheduleEntryDTO> entries, String occurrenceId,
                                 ScheduleDisplayKindDTO kind) {
        int total = 0;
        for (TeacherScheduleEntryDTO entry : entries) {
            if (occurrenceId.equals(entry.getOccurrenceId()) && entry.getDisplayKind() == kind) {
                total++;
            }
        }
        return total;
    }

    private static TeacherPeriodDTO period(String date, int period, List<TeacherPeriodDTO> periods) {
        for (TeacherPeriodDTO candidate : periods) {
            if (date.equals(candidate.getDate()) && candidate.getPeriod() == period) return candidate;
        }
        return null;
    }

    private static void expectInvalid(Action action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectInvalidMessage(Action action, String fragment, String message)
            throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            require(expected.getMessage() != null && expected.getMessage().contains(fragment),
                    message + ", observed: " + expected.getMessage());
            return;
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    // ----------------------------------------------------------------- helpers

    private static void rebuildSchema(Connection connection, Path root) throws Exception {
        resetTestSchema(connection);
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
    }

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

    private static Path resolveConfig(Path root, String value) {
        Path candidate = Path.of(value);
        return candidate.isAbsolute() ? candidate : root.resolve(candidate);
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
                "Refusing teacher schedule test: JDBC database must be exactly " + TEST_DATABASE);
    }

    private static Properties loadProperties(Path path) throws Exception {
        require(Files.isRegularFile(path), "Missing ignored local db.properties: " + path);
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
        require("+00:00".equals(queryString(connection, "SELECT @@session.time_zone")),
                "a DBUtil connection must already use UTC");
    }

    private static String currentDatabase(Connection connection) throws SQLException {
        return queryString(connection, "SELECT DATABASE()");
    }

    private static int count(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            require(result.next(), "Query returned no row");
            return result.getString(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
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
