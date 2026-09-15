package service;

import dao.AdminScheduleConflictDAO;
import dao.AdminScheduleDAO;
import dao.TeacherAdjustmentConflictDAO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import service.ScheduleAdjustmentConflictService.Candidate;
import util.DBUtil;

import java.io.InputStream;
import java.net.URI;
import java.sql.Connection;
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
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * {@link ScheduleAdjustmentConflictService} 的真实 MySQL 测试，跑在受保护的
 * {@code virtual_campus_course_test} 上：fixture 直接用 SQL 插入，候选永远是内存里的
 * {@link Candidate}，被检查的有效课表完全由数据库行决定。
 *
 * <p>覆盖调课简报要求的七个场景（教师/助教跨角色、教室、本班另一课次、正常学生的其他有效课、
 * 已调出原位置不占用、目标占用、相邻区间不冲突），外加日期越界/自身重叠/重复目标为 BLOCKING、
 * 空排除集合、只排除目标原 occurrence 而不排除整个 arrangement、教室容量与未知教学班。
 *
 * <p>未传 {@code mysql} 时只打印 SKIP 并返回，绝不把 SKIP 当成 PASS。DATETIME 列存的是 UTC
 * 墙钟、JVM 默认时区不参与换算，所以 fixture 与候选窗口都用 {@link #ZONE} 显式换算后写成
 * UTC 文本，和 {@code CourseConflictMySqlTest} 的既有约定一致。
 */
public final class TeacherAdjustmentConflictMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final int YEAR = 2031;
    private static final int SEMESTER = 1;

    // 全部 fixture 走 900300-900899 私有段（CourseConflictMySqlTest 用 900001-900103/910xxx/
    // 911xxx/912xxx/920xxx，管理员审批测试用 970xxx），不与种子行或既有测试重叠。
    private static final long CALENDAR = 900301L;
    private static final long TEMPLATE = 900302L;
    private static final long PLAN = 900303L;
    private static final long ROOM_BIG = 900304L;
    private static final long ROOM_SMALL = 900305L;
    private static final long ROOM_FREE = 900306L;
    private static final long ROOM_SPARE = 900307L;
    private static final long COURSE_MAIN = 900401L;
    private static final long COURSE_OTHER = 900402L;
    private static final long OFFERING_MAIN = 900411L;
    private static final long OFFERING_OTHER = 900412L;
    private static final long OFFERING_STUDENT = 900413L;
    private static final long ARRANGEMENT_MAIN = 900501L;
    private static final long ARRANGEMENT_OTHER = 900502L;
    private static final long ARRANGEMENT_STUDENT = 900503L;
    private static final long RULE_MAIN_W1D2 = 900601L;
    private static final long RULE_MAIN_W3D3 = 900602L;
    private static final long RULE_OTHER_W1D1 = 900611L;
    private static final long RULE_OTHER_W2D4 = 900612L;
    private static final long RULE_MOVED_ORIGINAL = 900613L;
    private static final long RULE_STUDENT_W1D5 = 900621L;
    private static final long OCC_MAIN_W1D2 = 900701L;
    private static final long OCC_MAIN_W3D3 = 900702L;
    private static final long OCC_OTHER_W1D1 = 900711L;
    private static final long OCC_OTHER_W2D4 = 900712L;
    private static final long OCC_MOVED_ORIGINAL = 900713L;
    private static final long OCC_STUDENT_W1D5 = 900721L;
    private static final long REQUEST_MOVED = 900801L;
    private static final long ADJUSTMENT_MOVED = 900802L;
    /** 第 2 周星期六，明确标成非教学日。 */
    private static final long NON_TEACHING_DATE = 900350L;
    /** 任何 fixture 都不使用的日期 ID，用来证明候选必须落在本方案日历域内。 */
    private static final long UNKNOWN_DATE = 900999L;

    private static final String TEACHER_MAIN = "tac900-teacher-a";
    private static final String TEACHER_OTHER = "tac900-teacher-b";
    private static final String TEACHER_SUBSTITUTE = "tac900-teacher-c";
    private static final String ASSISTANT = "tac900-assistant";
    private static final String STUDENT_BOTH = "tac900-student-1";
    private static final String STUDENT_DROPPED = "tac900-student-2";
    private static final String STUDENT_OTHER_ONLY = "tac900-student-3";

    private static final String WEEK1_START = "2031-09-01";

    private TeacherAdjustmentConflictMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher adjustment conflict test "
                    + "was not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            ScheduleAdjustmentConflictService conflicts = new ScheduleAdjustmentConflictService(
                    new AdminScheduleDAO(), new AdminScheduleConflictDAO(),
                    new TeacherAdjustmentConflictDAO());
            verifyCrossRolePersonnel(conflicts);
            verifyClassroomOverlap(conflicts);
            verifyAnotherOccurrenceOfTheSameOffering(conflicts);
            verifyOnlyTargetedOriginalsAreExcluded(conflicts);
            verifyActiveAdjustmentLeavesTheOriginalWindowFree(conflicts);
            verifyStudentRiskSummary(conflicts);
            verifyAdjacentIntervalsDoNotOverlap(conflicts);
            verifyOutOfRangeDatesAreBlocking(conflicts);
            verifyDuplicateAndSelfOverlappingTargetsAreBlocking(conflicts);
            verifyClassroomCapacity(conflicts);
            verifyUnknownOfferingIsRejected(conflicts);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher adjustment conflict MySQL test passed.");
    }

    // ------------------------------------------------------------------ scenarios

    /**
     * 教师/助教是同一人员资源：候选任课教师撞上其他课程的助教、候选助教撞上其他课程的任课教师，
     * 都要按候选人在本课程的原始角色报告。
     */
    private static void verifyCrossRolePersonnel(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        List<ScheduleConflictDTO> result = check(conflicts, List.of(candidate(dateId(1, 1), 1, 1,
                1, 2, ASSISTANT, TEACHER_OTHER, ROOM_SPARE)), Set.of());
        requireTypesExactly(result, "TEACHER_OVERLAP", "ASSISTANT_OVERLAP");
        requireConflict(result, "TEACHER_OVERLAP", OVERRIDABLE, ASSISTANT,
                Long.toString(OFFERING_OTHER));
        requireConflict(result, "ASSISTANT_OVERLAP", OVERRIDABLE, TEACHER_OTHER,
                Long.toString(OFFERING_OTHER));
        requirePosition(result, 1, 1, 1, 2);
    }

    private static void verifyClassroomOverlap(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        List<ScheduleConflictDTO> result = check(conflicts, List.of(candidate(dateId(1, 1), 1, 1,
                1, 2, TEACHER_SUBSTITUTE, null, ROOM_BIG)), Set.of());
        requireTypesExactly(result, "CLASSROOM_OVERLAP");
        requireConflict(result, "CLASSROOM_OVERLAP", OVERRIDABLE, Long.toString(ROOM_BIG),
                Long.toString(OFFERING_OTHER));
        requirePosition(result, 1, 1, 1, 2);
    }

    /** 本教学班另一课次是 BLOCKING 的 OFFERING_OVERLAP，而不是可绕过的资源冲突。 */
    private static void verifyAnotherOccurrenceOfTheSameOffering(
            ScheduleAdjustmentConflictService conflicts) throws SQLException {
        List<ScheduleConflictDTO> result = check(conflicts, List.of(candidate(dateId(3, 3), 3, 3,
                3, 4, TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
        requireTypesExactly(result, "OFFERING_OVERLAP");
        requireConflict(result, "OFFERING_OVERLAP", BLOCKING, Long.toString(OFFERING_MAIN),
                Long.toString(OFFERING_MAIN));
        requirePosition(result, 3, 3, 3, 4);
    }

    /**
     * 排除的只是本申请目标的原 occurrence：同一 arrangement 的另一个课次仍然参与检查。目标本身
     * 被排除后不再报告；空集合不生成 {@code NOT IN ()} 也不排除任何行。
     */
    private static void verifyOnlyTargetedOriginalsAreExcluded(
            ScheduleAdjustmentConflictService conflicts) throws SQLException {
        Candidate candidate = candidate(dateId(3, 3), 3, 3, 3, 4, TEACHER_SUBSTITUTE, null,
                ROOM_SPARE);

        List<ScheduleConflictDTO> siblingExcluded =
                check(conflicts, List.of(candidate), Set.of(OCC_MAIN_W1D2));
        requireTypesExactly(siblingExcluded, "OFFERING_OVERLAP");

        List<ScheduleConflictDTO> emptyExcluded = check(conflicts, List.of(candidate), Set.of());
        requireTypesExactly(emptyExcluded, "OFFERING_OVERLAP");

        List<ScheduleConflictDTO> targetExcluded =
                check(conflicts, List.of(candidate), Set.of(OCC_MAIN_W3D3));
        require(targetExcluded.isEmpty(),
                "the excluded target occurrence must not occupy its window, got "
                        + describe(targetExcluded));
    }

    /**
     * 已生效调课把原 occurrence 从原位置挪走、在目标位置生效：原位置对任何候选都不再占用，目标
     * 位置参与检查，而且排除该目标原 occurrence 时连调课结果一起排除。
     */
    private static void verifyActiveAdjustmentLeavesTheOriginalWindowFree(
            ScheduleAdjustmentConflictService conflicts) throws SQLException {
        List<ScheduleConflictDTO> movedAway = check(conflicts, List.of(candidate(dateId(4, 1), 4, 1,
                3, 4, TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
        require(movedAway.isEmpty(),
                "an ACTIVE adjustment must remove its original occurrence from the effective "
                        + "schedule, got " + describe(movedAway));

        Candidate target = candidate(dateId(4, 5), 4, 5, 1, 2, TEACHER_OTHER, null, ROOM_BIG);
        List<ScheduleConflictDTO> occupied = check(conflicts, List.of(target), Set.of());
        requireTypesExactly(occupied, "TEACHER_OVERLAP", "CLASSROOM_OVERLAP");
        requirePosition(occupied, 4, 5, 1, 2);

        List<ScheduleConflictDTO> owningOriginalExcluded =
                check(conflicts, List.of(target), Set.of(OCC_MOVED_ORIGINAL));
        require(owningOriginalExcluded.isEmpty(),
                "excluding the target original must also exclude its ACTIVE adjustment, got "
                        + describe(owningOriginalExcluded));
    }

    /**
     * 学生风险只包含本教学班的正常学生：本班学生同时在另一门已选课程正常选课时报告
     * STUDENT_SCHEDULE；另一班已退课的学生、只属于另一班的学生都不出现。
     */
    private static void verifyStudentRiskSummary(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        List<ScheduleConflictDTO> result = check(conflicts, List.of(candidate(dateId(1, 5), 1, 5,
                1, 2, TEACHER_MAIN, null, ROOM_SPARE)), Set.of());
        requireTypesExactly(result, "STUDENT_SCHEDULE");
        requireConflict(result, "STUDENT_SCHEDULE", OVERRIDABLE, STUDENT_BOTH,
                Long.toString(OFFERING_STUDENT));
        requirePosition(result, 1, 5, 1, 2);
        List<String> subjects = subjects(result);
        require(!subjects.contains(STUDENT_DROPPED) && !subjects.contains(STUDENT_OTHER_ONLY),
                "only normally enrolled students of this offering may be reported, got " + subjects);
    }

    /** 首尾相接的窗口不冲突：候选在另一课次结束的瞬间开始，或在它开始的瞬间结束。 */
    private static void verifyAdjacentIntervalsDoNotOverlap(
            ScheduleAdjustmentConflictService conflicts) throws SQLException {
        List<ScheduleConflictDTO> startsWhenOtherEnds = check(conflicts, List.of(candidate(
                dateId(2, 4), 2, 4, 4, 4, TEACHER_OTHER, null, ROOM_BIG)), Set.of());
        require(startsWhenOtherEnds.isEmpty(),
                "[start,end) must not report an overlap at a touching boundary, got "
                        + describe(startsWhenOtherEnds));

        List<ScheduleConflictDTO> endsWhenOtherStarts = check(conflicts, List.of(candidate(
                dateId(2, 4), 2, 4, 2, 2, TEACHER_OTHER, null, ROOM_BIG)), Set.of());
        require(endsWhenOtherStarts.isEmpty(),
                "[start,end) must not report an overlap at a touching boundary, got "
                        + describe(endsWhenOtherStarts));
    }

    /** 日期越界 / 节次越界 / 非教学日 / 不在本方案日历域的日期都是 BLOCKING。 */
    private static void verifyOutOfRangeDatesAreBlocking(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        List<ScheduleConflictDTO> weekBeyondCalendar = check(conflicts, List.of(candidate(
                dateId(1, 1), 9, 1, 1, 2, TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
        requireSingleBlocking(weekBeyondCalendar, ScheduleAdjustmentConflictService.SLOT_INVALID,
                "a teaching week outside the calendar must be blocking");

        List<ScheduleConflictDTO> unknownPeriod = check(conflicts, List.of(outOfTemplate(
                dateId(1, 1), 1, 1, 9, 9, TEACHER_SUBSTITUTE, ROOM_SPARE)), Set.of());
        requireSingleBlocking(unknownPeriod, ScheduleAdjustmentConflictService.SLOT_INVALID,
                "a period undefined for the day template must be blocking");

        List<ScheduleConflictDTO> nonTeachingDay = check(conflicts, List.of(candidate(
                NON_TEACHING_DATE, 2, 6, 1, 2, TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
        requireSingleBlocking(nonTeachingDay, ScheduleAdjustmentConflictService.SLOT_INVALID,
                "a non-teaching day must be blocking");

        List<ScheduleConflictDTO> foreignDate = check(conflicts, List.of(candidate(
                UNKNOWN_DATE, 1, 2, 1, 2, TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
        requireSingleBlocking(foreignDate, ScheduleAdjustmentConflictService.SLOT_INVALID,
                "a target date outside the plan's calendar must be blocking");
    }

    /**
     * 同一申请重复指定相同目标日期，或多个目标的新时间段相互重叠，都是 BLOCKING。第二个场景由
     * 调用方传入相同 instants 的两个不同目标日期构成：单槽位的正常请求不会出现，但服务对候选
     * 区间的自身重叠必须照样拦截。
     */
    private static void verifyDuplicateAndSelfOverlappingTargetsAreBlocking(
            ScheduleAdjustmentConflictService conflicts) throws SQLException {
        Candidate duplicate = candidate(dateId(2, 1), 2, 1, 3, 4, TEACHER_SUBSTITUTE, null,
                ROOM_SPARE);
        List<ScheduleConflictDTO> duplicated = check(conflicts,
                List.of(duplicate, duplicate), Set.of());
        requireTypesExactly(duplicated, "OFFERING_OVERLAP");
        requireConflict(duplicated, "OFFERING_OVERLAP", BLOCKING, Long.toString(OFFERING_MAIN),
                Long.toString(OFFERING_MAIN));

        Candidate second = claiming(duplicate, candidate(dateId(2, 2), 2, 2, 3, 4,
                TEACHER_SUBSTITUTE, null, ROOM_SPARE));
        List<ScheduleConflictDTO> selfOverlapping = check(conflicts,
                List.of(duplicate, second), Set.of());
        requireTypesExactly(selfOverlapping, "OFFERING_OVERLAP");
        requireConflict(selfOverlapping, "OFFERING_OVERLAP", BLOCKING,
                Long.toString(OFFERING_MAIN), Long.toString(OFFERING_MAIN));
    }

    private static void verifyClassroomCapacity(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        List<ScheduleConflictDTO> result = check(conflicts, List.of(candidate(dateId(2, 1), 2, 1,
                3, 4, TEACHER_SUBSTITUTE, null, ROOM_SMALL)), Set.of());
        requireTypesExactly(result, "CLASSROOM_CAPACITY");
        requireConflict(result, "CLASSROOM_CAPACITY", OVERRIDABLE, Long.toString(ROOM_SMALL), null);
    }

    private static void verifyUnknownOfferingIsRejected(ScheduleAdjustmentConflictService conflicts)
            throws SQLException {
        expect(IllegalArgumentException.class, () -> {
            try (Connection connection = DBUtil.getConnection()) {
                conflicts.check(connection, 999999L, List.of(candidate(dateId(1, 1), 1, 1, 1, 2,
                        TEACHER_SUBSTITUTE, null, ROOM_SPARE)), Set.of());
            }
        }, "an unknown offering must be rejected instead of silently reporting no conflicts");
    }

    // ------------------------------------------------------------------- harness

    private static List<ScheduleConflictDTO> check(ScheduleAdjustmentConflictService conflicts,
            List<Candidate> candidates, Set<Long> excluded) throws SQLException {
        try (Connection connection = DBUtil.getConnection()) {
            return conflicts.check(connection, OFFERING_MAIN, candidates, excluded);
        }
    }

    private static void requireSingleBlocking(List<ScheduleConflictDTO> conflicts, String type,
                                              String message) {
        requireTypesExactly(conflicts, type);
        require(conflicts.get(0).getSeverity() == BLOCKING,
                message + " (severity must be BLOCKING)");
    }

    private static void requireTypesExactly(List<ScheduleConflictDTO> conflicts,
                                            String... expectedTypes) {
        List<String> actual = types(conflicts);
        List<String> expected = new ArrayList<>(List.of(expectedTypes));
        actual.sort(String::compareTo);
        expected.sort(String::compareTo);
        require(actual.equals(expected),
                "expected conflicts " + expected + " but got " + describe(conflicts));
    }

    private static void requireConflict(List<ScheduleConflictDTO> conflicts, String type,
            ScheduleConflictSeverityDTO severity, String subject, String relatedOffering) {
        List<ScheduleConflictDTO> matching = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            if (type.equals(conflict.getType())) matching.add(conflict);
        }
        require(matching.size() == 1,
                "expected exactly one " + type + ", got " + describe(conflicts));
        ScheduleConflictDTO conflict = matching.get(0);
        require(conflict.getSeverity() == severity,
                type + " must be " + severity + ", got " + conflict.getSeverity());
        boolean sameSubject = subject == null ? conflict.getSubjectId() == null
                : subject.equals(conflict.getSubjectId());
        boolean sameRelated = relatedOffering == null ? conflict.getRelatedOfferingId() == null
                : relatedOffering.equals(conflict.getRelatedOfferingId());
        require(sameSubject && sameRelated, type + " must name subject " + subject
                + " and related offering " + relatedOffering + ", got "
                + conflict.getSubjectId() + "/" + conflict.getRelatedOfferingId());
    }

    private static void requirePosition(List<ScheduleConflictDTO> conflicts, int week,
            int weekday, int startPeriod, int endPeriod) {
        for (ScheduleConflictDTO conflict : conflicts) {
            require(conflict.getWeek() == week && conflict.getDayOfWeek() == weekday
                            && conflict.getStartPeriod() == startPeriod
                            && conflict.getEndPeriod() == endPeriod,
                    "every conflict must carry the candidate position, got " + describe(conflict));
        }
    }

    private static List<String> types(List<ScheduleConflictDTO> conflicts) {
        List<String> names = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) names.add(conflict.getType());
        return names;
    }

    private static List<String> subjects(List<ScheduleConflictDTO> conflicts) {
        List<String> subjects = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) subjects.add(conflict.getSubjectId());
        return subjects;
    }

    private static String describe(List<ScheduleConflictDTO> conflicts) {
        StringBuilder text = new StringBuilder("[");
        for (ScheduleConflictDTO conflict : conflicts) {
            if (text.length() > 1) text.append(", ");
            text.append(describe(conflict));
        }
        return text.append(']').toString();
    }

    private static String describe(ScheduleConflictDTO conflict) {
        return conflict.getType() + ":" + conflict.getSeverity()
                + "(" + conflict.getSubjectId() + "/" + conflict.getRelatedOfferingId() + ")@"
                + conflict.getWeek() + "-" + conflict.getDayOfWeek() + "-"
                + conflict.getStartPeriod() + "-" + conflict.getEndPeriod();
    }

    private static <X extends Throwable> void expect(Class<X> type, ThrowingRun action,
                                                     String message) throws SQLException {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    // ------------------------------------------------------------------ candidates

    private static Candidate candidate(long calendarDateId, int week, int weekday, int startPeriod,
            int endPeriod, String teacher, String assistant, Long classroomId) {
        LocalDate date = weekDate(week, weekday);
        return candidate(calendarDateId, week, weekday, startPeriod, endPeriod,
                instant(date, periodText(startPeriod, true)),
                instant(date, periodText(endPeriod, false)), teacher, assistant, classroomId);
    }

    /** 节次在模板里不存在时 instants 只是占位符：服务必须先报日期/节次越界。 */
    private static Candidate outOfTemplate(long calendarDateId, int week, int weekday,
            int startPeriod, int endPeriod, String teacher, Long classroomId) {
        return candidate(calendarDateId, week, weekday, startPeriod, endPeriod,
                instant(weekDate(week, weekday), periodText(1, true)),
                instant(weekDate(week, weekday), periodText(1, false)), teacher, null, classroomId);
    }

    private static Candidate candidate(long calendarDateId, int week, int weekday, int startPeriod,
            int endPeriod, Instant startAt, Instant endAt, String teacher, String assistant,
            Long classroomId) {
        return new Candidate(calendarDateId, week, weekday, startPeriod, endPeriod, startAt, endAt,
                teacher, assistant, classroomId);
    }

    /** 保留 {@code instants} 的 UTC 窗口但改用 {@code position} 的教学日：验证候选自身重叠守卫。 */
    private static Candidate claiming(Candidate instants, Candidate position) {
        return new Candidate(position.calendarDateId(), position.week(), position.teachingWeekday(),
                position.startPeriod(), position.endPeriod(), instants.startAt(), instants.endAt(),
                position.teacherUid(), position.assistantUid(), position.classroomId());
    }

    private static long dateId(int week, int weekday) {
        return 900320L + (week - 1) * 5L + weekday - 1;
    }

    private static LocalDate weekDate(int week, int weekday) {
        return LocalDate.parse(WEEK1_START).plusDays((week - 1) * 7L + weekday - 1);
    }

    private static String periodText(int periodNo, boolean start) {
        return switch (periodNo) {
            case 1 -> start ? "08:00:00" : "08:45:00";
            case 2 -> start ? "08:45:00" : "09:30:00";
            case 3 -> start ? "09:30:00" : "10:15:00";
            case 4 -> start ? "10:15:00" : "11:00:00";
            default -> throw new IllegalArgumentException("no fixture period " + periodNo);
        };
    }

    private static Instant instant(LocalDate date, String time) {
        return ZonedDateTime.of(date, LocalTime.parse(time), ZONE).toInstant();
    }

    private static String utcText(LocalDate date, String time) {
        return LocalDateTime.ofInstant(instant(date, time), ZoneOffset.UTC).toString()
                .replace('T', ' ');
    }

    // ------------------------------------------------------------------- fixtures

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_MAIN + "','Tac Main','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_OTHER + "','Tac Other','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_SUBSTITUTE + "','Tac Sub','x','x',1,'Engineering','Lecturer'),"
                + "('" + ASSISTANT + "','Tac Assistant','x','x',1,'Engineering','Assistant'),"
                + "('" + STUDENT_BOTH + "','Tac Student Both','x','x',2,'Engineering','Student'),"
                + "('" + STUDENT_DROPPED + "','Tac Student Dropped','x','x',2,'Engineering',"
                + "'Student'),"
                + "('" + STUDENT_OTHER_ONLY + "','Tac Student Other','x','x',2,'Engineering',"
                + "'Student')");

        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES"
                + "(" + COURSE_MAIN + ",'TAC900-A','Adjustment Conflict Course A',3.00,48,1,"
                + "'ACTIVE'),(" + COURSE_OTHER + ",'TAC900-B','Adjustment Conflict Course B',"
                + "3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES"
                + "(" + OFFERING_MAIN + ",'TAC900-A-01'," + COURSE_MAIN + "," + YEAR + ","
                + SEMESTER + ",30,2),"
                + "(" + OFFERING_OTHER + ",'TAC900-B-01'," + COURSE_OTHER + "," + YEAR + ","
                + SEMESTER + ",30,2),"
                + "(" + OFFERING_STUDENT + ",'TAC900-B-02'," + COURSE_OTHER + "," + YEAR + ","
                + SEMESTER + ",30,2)");

        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(" + CALENDAR
                + ",'Adjustment conflict calendar'," + YEAR + "," + SEMESTER + ",'" + WEEK1_START
                + "','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO day_template(id,name,version) VALUES(" + TEMPLATE
                + ",'Adjustment conflict template',1)");
        execute("INSERT INTO period_definition(id,day_template_id,period_no,start_time,end_time)"
                + " VALUES(900310," + TEMPLATE + ",1,'08:00:00','08:45:00'),"
                + "(900311," + TEMPLATE + ",2,'08:45:00','09:30:00'),"
                + "(900312," + TEMPLATE + ",3,'09:30:00','10:15:00'),"
                + "(900313," + TEMPLATE + ",4,'10:15:00','11:00:00')");
        int dateId = 900320;
        for (int week = 1; week <= 4; week++) {
            for (int day = 1; day <= 5; day++) {
                execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,"
                        + "teaching_weekday,day_template_id,is_teaching_day) VALUES(" + dateId++
                        + "," + CALENDAR + ",'" + weekDate(week, day) + "'," + week + "," + day + ","
                        + TEMPLATE + ",1)");
            }
        }
        execute("INSERT INTO calendar_date(id,calendar_id,local_date,week_no,teaching_weekday,"
                + "day_template_id,is_teaching_day) VALUES(" + NON_TEACHING_DATE + "," + CALENDAR
                + ",'" + weekDate(2, 6) + "',2,6," + TEMPLATE + ",0)");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status,created_at,"
                + "updated_at) VALUES(" + PLAN + ",'Adjustment conflict plan'," + CALENDAR
                + ",1,'PUBLISHED','2031-08-01 00:00:00','2031-08-01 00:00:00')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=" + PLAN + " WHERE id="
                + CALENDAR);

        execute("INSERT INTO classroom(id,name,capacity,electric) VALUES"
                + "(" + ROOM_BIG + ",'Tac Room Big',60,1),"
                + "(" + ROOM_SMALL + ",'Tac Room Small',5,1),"
                + "(" + ROOM_FREE + ",'Tac Room Free',60,1),"
                + "(" + ROOM_SPARE + ",'Tac Room Spare',60,1)");

        arrangement(ARRANGEMENT_MAIN, OFFERING_MAIN, TEACHER_MAIN, null, ROOM_BIG);
        arrangement(ARRANGEMENT_OTHER, OFFERING_OTHER, TEACHER_OTHER, ASSISTANT, ROOM_BIG);
        arrangement(ARRANGEMENT_STUDENT, OFFERING_STUDENT, TEACHER_SUBSTITUTE, null, ROOM_FREE);

        rule(RULE_MAIN_W1D2, OFFERING_MAIN, ARRANGEMENT_MAIN, 2, 1, 2);
        rule(RULE_MAIN_W3D3, OFFERING_MAIN, ARRANGEMENT_MAIN, 3, 3, 4);
        rule(RULE_OTHER_W1D1, OFFERING_OTHER, ARRANGEMENT_OTHER, 1, 1, 2);
        // 单节第 3 节：候选的第 4 节正好在它结束时开始，候选的第 2 节正好在它开始时结束。
        rule(RULE_OTHER_W2D4, OFFERING_OTHER, ARRANGEMENT_OTHER, 4, 3, 3);
        rule(RULE_MOVED_ORIGINAL, OFFERING_OTHER, ARRANGEMENT_OTHER, 1, 3, 4);
        rule(RULE_STUDENT_W1D5, OFFERING_STUDENT, ARRANGEMENT_STUDENT, 5, 1, 2);

        occurrence(OCC_MAIN_W1D2, RULE_MAIN_W1D2, 1, 2, 1, 2);
        occurrence(OCC_MAIN_W3D3, RULE_MAIN_W3D3, 3, 3, 3, 4);
        occurrence(OCC_OTHER_W1D1, RULE_OTHER_W1D1, 1, 1, 1, 2);
        occurrence(OCC_OTHER_W2D4, RULE_OTHER_W2D4, 2, 4, 3, 3);
        occurrence(OCC_MOVED_ORIGINAL, RULE_MOVED_ORIGINAL, 4, 1, 3, 4);
        occurrence(OCC_STUDENT_W1D5, RULE_STUDENT_W1D5, 1, 5, 1, 2);

        // ACTIVE 调课：第 4 周星期一的第 3-4 节挪到第 4 周星期五的第 1-2 节。
        execute("INSERT INTO course_schedule_adjustment_request(request_id,offering_id,"
                + "requested_by,reason,version,status,new_weekday,new_start_period,new_end_period,"
                + "new_teacher_uid,new_assistant_uid,new_classroom_id,reviewed_by,reviewed_at)"
                + " VALUES(" + REQUEST_MOVED + "," + OFFERING_OTHER + ",'" + TEACHER_OTHER
                + "','conflict fixture',1,'APPROVED',5,1,2,'" + TEACHER_OTHER + "',NULL," + ROOM_BIG
                + ",'" + TEACHER_MAIN + "',NOW(6))");
        LocalDate movedDate = weekDate(4, 5);
        execute("INSERT INTO course_schedule_adjustment(adjustment_id,request_id,"
                + "original_occurrence_id,start_at_utc,end_at_utc,teacher_uid,assistant_uid,"
                + "classroom_id,status) VALUES(" + ADJUSTMENT_MOVED + "," + REQUEST_MOVED + ","
                + OCC_MOVED_ORIGINAL + ",'" + utcText(movedDate, periodText(1, true)) + "','"
                + utcText(movedDate, periodText(2, false)) + "','" + TEACHER_OTHER + "',NULL,"
                + ROOM_BIG + ",'ACTIVE')");

        enrollment(900901L, OFFERING_MAIN, COURSE_MAIN, STUDENT_BOTH, 2, false);
        enrollment(900902L, OFFERING_STUDENT, COURSE_OTHER, STUDENT_BOTH, 2, false);
        enrollment(900903L, OFFERING_MAIN, COURSE_MAIN, STUDENT_DROPPED, 2, false);
        enrollment(900904L, OFFERING_STUDENT, COURSE_OTHER, STUDENT_DROPPED, 3, true);
        enrollment(900905L, OFFERING_STUDENT, COURSE_OTHER, STUDENT_OTHER_ONLY, 2, false);
    }

    private static void arrangement(long arrangementId, long offeringId, String teacher,
            String assistant, Long classroomId) throws SQLException {
        execute("INSERT INTO course_schedule_arrangement(arrangement_id,plan_id,offering_id,"
                + "teacher_uid,assistant_uid,classroom_id,status,version) VALUES(" + arrangementId
                + "," + PLAN + "," + offeringId + "," + sql(teacher) + "," + sql(assistant) + ","
                + (classroomId == null ? "NULL" : classroomId.toString()) + ",'ACTIVE',1)");
    }

    private static void rule(long ruleId, long offeringId, long arrangementId, int weekday,
            int startPeriod, int endPeriod) throws SQLException {
        execute("INSERT INTO course_schedule_rule(id,plan_id,course_offering_id,arrangement_id,"
                + "weekday,start_period,end_period,status) VALUES(" + ruleId + "," + PLAN + ","
                + offeringId + "," + arrangementId + "," + weekday + "," + startPeriod + ","
                + endPeriod + ",'ACTIVE')");
    }

    private static void occurrence(long occurrenceId, long ruleId, int week, int weekday,
            int startPeriod, int endPeriod) throws SQLException {
        LocalDate date = weekDate(week, weekday);
        // (rule_id, week_no) is an FK target, so the rule week must exist before the occurrence.
        execute("INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(" + ruleId + ","
                + week + ")");
        execute("INSERT INTO course_occurrence(id,rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(" + occurrenceId + "," + ruleId + "," + PLAN + ",'"
                + utcText(date, periodText(startPeriod, true)) + "','"
                + utcText(date, periodText(endPeriod, false)) + "'," + week + "," + weekday + ")");
    }

    private static void enrollment(long enrollmentId, long offeringId, long courseId, String uid,
            int status, boolean dropped) throws SQLException {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,"
                + "semester,uid,status,select_time,drop_time) VALUES(" + enrollmentId + ","
                + offeringId + "," + courseId + "," + YEAR + "," + SEMESTER + ",'" + uid + "',"
                + status + ",'2031-01-01 00:00:00',"
                + (dropped ? "'2031-02-01 00:00:00'" : "NULL") + ")");
    }

    private static String sql(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    // ---------------------------------------------------------------- maintenance

    private static void cleanup() throws SQLException {
        execute("DELETE FROM course_schedule_adjustment WHERE adjustment_id BETWEEN 900800 AND 900899");
        execute("DELETE FROM course_schedule_adjustment_request WHERE request_id BETWEEN 900800"
                + " AND 900899");
        execute("DELETE FROM course_occurrence WHERE plan_id BETWEEN 900300 AND 900399");
        execute("DELETE FROM course_schedule_rule_week WHERE rule_id BETWEEN 900600 AND 900699");
        execute("DELETE FROM course_schedule_rule WHERE plan_id BETWEEN 900300 AND 900399");
        execute("DELETE FROM course_schedule_arrangement WHERE plan_id BETWEEN 900300 AND 900399");
        execute("DELETE FROM schedule_plan WHERE id BETWEEN 900300 AND 900399");
        execute("DELETE FROM enrollment WHERE enrollment_id BETWEEN 900900 AND 900999");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 900400 AND 900499");
        execute("DELETE FROM course WHERE course_id BETWEEN 900400 AND 900499");
        execute("DELETE FROM classroom WHERE id BETWEEN 900300 AND 900399");
        execute("DELETE FROM calendar_date WHERE calendar_id BETWEEN 900300 AND 900399");
        execute("DELETE FROM period_definition WHERE id BETWEEN 900300 AND 900399");
        execute("DELETE FROM day_template WHERE id BETWEEN 900300 AND 900399");
        execute("DELETE FROM teaching_calendar WHERE id BETWEEN 900300 AND 900399");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'tac900-%'");
    }

    /** Proves cleanup leaves nothing behind, so a rerun starts from the same state. */
    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teaching_calendar WHERE id BETWEEN 900300 AND 900399") == 0
                && count("SELECT COUNT(*) FROM calendar_date WHERE calendar_id BETWEEN 900300"
                + " AND 900399") == 0
                && count("SELECT COUNT(*) FROM schedule_plan WHERE id BETWEEN 900300"
                + " AND 900399") == 0
                && count("SELECT COUNT(*) FROM course_occurrence WHERE plan_id BETWEEN 900300"
                + " AND 900399") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment WHERE adjustment_id"
                + " BETWEEN 900800 AND 900899") == 0
                && count("SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE request_id"
                + " BETWEEN 900800 AND 900899") == 0
                && count("SELECT COUNT(*) FROM enrollment WHERE enrollment_id BETWEEN 900900"
                + " AND 900999") == 0
                && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN 900400"
                + " AND 900499") == 0
                && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 900400"
                + " AND 900499") == 0
                && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'tac900-%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = DBUtil.class.getClassLoader()
                .getResourceAsStream("resources/db.properties")) {
            require(stream != null, "db.properties is unavailable on the runtime classpath");
            properties.load(stream);
        }
        String url = properties.getProperty("db.url");
        require(url != null && !url.isBlank(), "db.url is not configured");
        String raw = url.startsWith("jdbc:") ? url.substring(5) : url;
        String path = URI.create(raw).getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(GUARDED_DATABASE.equals(database),
                "Refusing teacher adjustment conflict test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher adjustment conflict test outside the guarded schema");
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int count(String sql) throws SQLException {
        return Integer.parseInt(text(sql));
    }
}
