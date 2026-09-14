package service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;

/**
 * 无服务器预览用的确定性教师课程实现。
 *
 * <p>数据固定可复现：两个学期、一个超过一页的名单、超长姓名、一个空班和若干退课行，并返回与
 * 真实服务相同的能力字段（{@code canEditGrades}/{@code canRequestAdjustment}），因此界面可以在
 * 没有服务端与数据库的情况下被完整驱动。失败与真实 Socket 实现同形：{@code CompletableFuture}
 * 以 {@link TeacherCourseServiceException} 异常完成，而不是同步抛出。
 *
 * <p>仅供单一线程（预览界面线程）调用，内部状态不做并发保护。
 */
public final class MockTeacherCourseService implements TeacherCourseService {
    /** 排课方案状态：`course_schedule_arrangement.status` 的真实取值。 */
    private static final String ACTIVE = "ACTIVE";
    /**
     * 教学班状态：`course_offering.status` 的真实取值，由 {@code AdminOfferingDAO.statusLabel}
     * 把 TINYINT 1..4 映射而来。mock 只能用这四个之一，否则预览会显示服务端不可能发出的英文原值。
     */
    private static final String OFFERING_STATUS_OPEN = "OPEN";
    private static final String OFFERING_STATUS_NOT_OPEN = "NOT_OPEN";
    private static final String OFFERING_STATUS_STOPPED = "STOPPED";
    private static final String ENROLLED = "ENROLLED";
    private static final String DROPPED = "DROPPED";
    private static final int ENROLLED_CODE = 2;
    private static final int DROPPED_CODE = 3;
    private static final int MAX_PAGE_SIZE = 100;
    private static final String FULL_ROSTER_OFFERING = "9007199254740993";
    /** 超过一页（size=20）的名单长度。 */
    private static final int ROSTER_LENGTH = 27;

    // 教师周课表的固定教学日历：mock 不连数据库、不读系统时钟，每个学期一份可复现日历。
    // 2025/2 的 currentWeek 为 null，用来驱动界面“今天不在该学期”的分支。
    private static final String SPRING_CALENDAR_ID = "9007199254740001";
    private static final String AUTUMN_CALENDAR_ID = "9007199254740002";
    private static final String SCHEDULE_TIMEZONE = "Asia/Shanghai";
    private static final int MIN_TEACHING_WEEK = 1;
    private static final int MAX_TEACHING_WEEK = 16;
    private static final Integer SPRING_CURRENT_WEEK = 8;
    /** “今天”不在 2025/2 学期内，因此该学期没有当前周。 */
    private static final Integer AUTUMN_CURRENT_WEEK = null;
    /** 第 1 周周一；每个 ISO 本地日期都由它按 (week, teachingWeekday) 推导。 */
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2026, 9, 7);
    private static final int CALENDAR_DAYS_PER_WEEK = 7;
    /** 日历序号 1..6 为教学日，7 不是；周末课因此仍然可见，界面不能只画五天。 */
    private static final int LAST_TEACHING_WEEKDAY = 6;
    /** 节次模板覆盖到第 13 节，不硬编码十节。 */
    private static final int PERIOD_COUNT = 13;
    private static final LocalTime FIRST_PERIOD_START = LocalTime.of(8, 0);
    private static final int PERIOD_LENGTH_MINUTES = 45;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final DateTimeFormatter PERIOD_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 课表 fixture 用到的教学班；与 {@code seedOfferings}/{@code seedEmptyOffering} 保持一致。 */
    private static final String INTERACTION_OFFERING = "9007199254740997";
    private static final String OPERATING_SYSTEM_OFFERING = "9007199254740995";
    private static final String CROSS_WEEK_ADJUSTMENT_ID = "9301";
    private static final String SAME_WEEK_ADJUSTMENT_ID = "9302";
    private static final String TEACHER_WITH_ASSISTANT = "陈老师, 王助教";
    /**
     * 调整文案必须与服务端 {@code CourseScheduleDAO.scheduleText} 逐字符一致：
     * {@code 周X 第a-b节 地点}（地点为空时省略）。界面原样渲染，Task 5 按此断言。
     */
    private static final String CROSS_WEEK_ORIGINAL_TEXT = "周一 第1-2节 A-101";
    private static final String CROSS_WEEK_ADJUSTED_TEXT = "周三 第3-4节 B-203";
    private static final String SAME_WEEK_ORIGINAL_TEXT = "周二 第1-2节 A-101";
    private static final String SAME_WEEK_ADJUSTED_TEXT = "周五 第5-6节 B-203";
    private static final String CROSS_WEEK_REASON = "教师出差";
    private static final String SAME_WEEK_REASON = "临时调课";

    private final List<CourseTermDTO> terms = List.of(
            new CourseTermDTO(2025, 3, "2025-2026 春学期"),
            new CourseTermDTO(2025, 2, "2025-2026 秋学期"));

    private final Map<String, TeacherOfferingDTO> offerings = new LinkedHashMap<>();
    private final Map<String, TeacherOfferingDetailDTO> details = new LinkedHashMap<>();
    private final Map<String, List<TeacherRosterRowDTO>> rosters = new LinkedHashMap<>();
    private final Map<String, List<ScheduleArrangementDTO>> schedules = new LinkedHashMap<>();

    public MockTeacherCourseService() {
        seedOfferings();
        seedFullRoster();
        seedEmptyOffering();
        seedAutumnOffering();
        seedSchedules();
    }

    @Override
    public CompletableFuture<List<CourseTermDTO>> listTerms() {
        return CompletableFuture.completedFuture(terms);
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
            int academicYear, int semester, String query, int page, int size) {
        try {
            String keyword = blankToNull(query);
            List<TeacherOfferingDTO> matched = new ArrayList<>();
            for (TeacherOfferingDTO offering : offerings.values()) {
                if (offering.getAcademicYear() != academicYear
                        || offering.getSemester() != semester) {
                    continue;
                }
                if (keyword != null && !matches(offering.getOfferingName(), keyword)
                        && !matches(offering.getOfferingCode(), keyword)
                        && !matches(offering.getCourseCode(), keyword)
                        && !matches(offering.getCourseName(), keyword)) {
                    continue;
                }
                matched.add(offering);
            }
            matched.sort(Comparator.comparing(TeacherOfferingDTO::getOfferingCode)
                    .thenComparing(TeacherOfferingDTO::getOfferingId));
            return CompletableFuture.completedFuture(page(matched, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
        try {
            TeacherOfferingDetailDTO detail = offeringId == null ? null : details.get(offeringId);
            if (detail == null) throw notFound("教学班不存在");
            return CompletableFuture.completedFuture(detail);
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
            String offeringId, String query, Integer enrollmentStatus, int page, int size) {
        try {
            if (enrollmentStatus != null && enrollmentStatus != ENROLLED_CODE
                    && enrollmentStatus != DROPPED_CODE) {
                throw badRequest("enrollmentStatus 只接受 2（正常）或 3（退课）");
            }
            if (offeringId == null || !offerings.containsKey(offeringId)) {
                throw notFound("教学班不存在");
            }
            List<TeacherRosterRowDTO> roster = rosters.getOrDefault(offeringId, List.of());
            String keyword = blankToNull(query);
            String wanted = enrollmentStatus == null ? null
                    : enrollmentStatus == DROPPED_CODE ? DROPPED : ENROLLED;
            List<TeacherRosterRowDTO> matched = new ArrayList<>();
            for (TeacherRosterRowDTO row : roster) {
                if (wanted != null && !wanted.equals(row.getEnrollmentStatus())) {
                    continue;
                }
                if (keyword != null && !matches(row.getStudentName(), keyword)
                        && !matches(row.getStudentUid(), keyword)) {
                    continue;
                }
                matched.add(row);
            }
            matched.sort(Comparator.comparing(TeacherRosterRowDTO::getStudentUid)
                    .thenComparing(TeacherRosterRowDTO::getEnrollmentId));
            return CompletableFuture.completedFuture(page(matched, page, size));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    @Override
    public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(String offeringId) {
        try {
            if (offeringId == null || !offerings.containsKey(offeringId)) {
                throw notFound("教学班不存在");
            }
            // 没有正式排课方案的教学班返回空列表，与真实服务“无 PUBLISHED 方案即空”一致。
            List<ScheduleArrangementDTO> arrangements = schedules.get(offeringId);
            return CompletableFuture.completedFuture(
                    arrangements == null ? List.of() : List.copyOf(arrangements));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    /**
     * 教师在某个教学日历周的课表。
     *
     * <p>{@code week} 缺省时按 mock 的固定“当前周”决定：在学期内用 currentWeek，不在学期内用
     * minWeek。未知学期与越界 week 分别以 NOT_FOUND / BAD_REQUEST 表达，与真实服务的映射一致。
     */
    @Override
    public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
            int academicYear, int semester, Integer week) {
        try {
            CalendarSpec calendar = calendarFor(academicYear, semester);
            if (calendar == null) {
                throw notFound("该学期暂无已发布的教学日历");
            }
            Integer selected = week != null ? week
                    : calendar.currentWeek != null ? calendar.currentWeek : MIN_TEACHING_WEEK;
            if (selected < MIN_TEACHING_WEEK || selected > MAX_TEACHING_WEEK) {
                throw badRequest("week 必须在 " + MIN_TEACHING_WEEK + ".." + MAX_TEACHING_WEEK
                        + " 之间");
            }
            return CompletableFuture.completedFuture(buildWeek(calendar, selected));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    // ------------------------------------------------------------------ 固定数据

    /** 一个学期的教学日历：id 与“今天”所在周；currentWeek 为 null 表示今天不在此学期。 */
    private record CalendarSpec(String calendarId, Integer currentWeek) {
    }

    /** 学期 → 日历；未登记的学期表示“该学期暂无已发布的教学日历”。 */
    private static CalendarSpec calendarFor(int academicYear, int semester) {
        if (academicYear == 2025 && semester == 3) {
            return new CalendarSpec(SPRING_CALENDAR_ID, SPRING_CURRENT_WEEK);
        }
        if (academicYear == 2025 && semester == 2) {
            return new CalendarSpec(AUTUMN_CALENDAR_ID, AUTUMN_CURRENT_WEEK);
        }
        return null;
    }

    /** 日期、节次与课次自洽的固定周：7 行日期、教学日 13 节、按周给出固定课次。 */
    private static TeacherScheduleWeekDTO buildWeek(CalendarSpec calendar, int week) {
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        for (int weekday = 1; weekday <= CALENDAR_DAYS_PER_WEEK; weekday++) {
            String date = localDate(week, weekday);
            boolean teachingDay = weekday <= LAST_TEACHING_WEEKDAY;
            dates.add(new TeacherCalendarDateDTO(date, week, weekday, teachingDay));
            if (!teachingDay) {
                continue;
            }
            for (int period = 1; period <= PERIOD_COUNT; period++) {
                LocalTime start = FIRST_PERIOD_START
                        .plusMinutes((long) (period - 1) * PERIOD_INTERVAL_MINUTES);
                periods.add(new TeacherPeriodDTO(date, period, start.format(PERIOD_TIME),
                        start.plusMinutes(PERIOD_LENGTH_MINUTES).format(PERIOD_TIME)));
            }
        }
        return new TeacherScheduleWeekDTO(calendar.calendarId(), SCHEDULE_TIMEZONE, week,
                MIN_TEACHING_WEEK, MAX_TEACHING_WEEK, calendar.currentWeek(), dates, periods,
                entriesOf(week));
    }

    /** 教学日历里 (week, teachingWeekday) 对应的 ISO 本地日期。 */
    private static String localDate(int week, int teachingWeekday) {
        return WEEK_ONE_START.plusDays((week - 1) * 7L + (teachingWeekday - 1L)).toString();
    }

    /**
     * 固定课次：周 8 两块同周调整 + 一块跨周原位置 + 一节未调整的周末第 13 节课；周 9 只有跨周新位置。
     * 其余所有周（含 week 5 这一无课周）为空，但 dates/periods 仍完整。
     */
    private static List<TeacherScheduleEntryDTO> entriesOf(int week) {
        if (week == 8) {
            return List.of(
                    entry("9201", FULL_ROSTER_OFFERING, "CS203", "数据结构与算法基础",
                            TEACHER_WITH_ASSISTANT, "A-101", 8, 1, 1, 2,
                            ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, CROSS_WEEK_ADJUSTMENT_ID,
                            CROSS_WEEK_ORIGINAL_TEXT, CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON),
                    entry("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                            "陈老师", "A-101", 8, 2, 1, 2,
                            ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, SAME_WEEK_ADJUSTMENT_ID,
                            SAME_WEEK_ORIGINAL_TEXT, SAME_WEEK_ADJUSTED_TEXT, SAME_WEEK_REASON),
                    entry("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                            "陈老师", "B-203", 8, 5, 5, 6,
                            ScheduleDisplayKindDTO.ADJUSTED_TARGET, SAME_WEEK_ADJUSTMENT_ID,
                            SAME_WEEK_ORIGINAL_TEXT, SAME_WEEK_ADJUSTED_TEXT, SAME_WEEK_REASON),
                    entry("9203", OPERATING_SYSTEM_OFFERING, "CS301", "操作系统原理",
                            "陈老师", "C-301", 8, 6, 12, 13,
                            ScheduleDisplayKindDTO.NORMAL, null, null, null, null));
        }
        if (week == 9) {
            return List.of(entry("9201", FULL_ROSTER_OFFERING, "CS203", "数据结构与算法基础",
                    TEACHER_WITH_ASSISTANT, "B-203", 9, 3, 3, 4,
                    ScheduleDisplayKindDTO.ADJUSTED_TARGET, CROSS_WEEK_ADJUSTMENT_ID,
                    CROSS_WEEK_ORIGINAL_TEXT, CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON));
        }
        return List.of();
    }

    private static TeacherScheduleEntryDTO entry(String occurrenceId, String offeringId,
            String courseCode, String courseName, String teacher, String location, int week,
            int weekday, int startPeriod, int endPeriod, ScheduleDisplayKindDTO displayKind,
            String adjustmentId, String originalText, String adjustedText, String reason) {
        // 只有未调整的课次还能再申请调课；ADJUSTED_* 两块都是既成事实。
        boolean canRequestAdjustment = adjustmentId == null;
        return new TeacherScheduleEntryDTO(occurrenceId, offeringId, courseCode, courseName,
                teacher, location, localDate(week, weekday), week, weekday, startPeriod, endPeriod,
                displayKind, adjustmentId, originalText, adjustedText, reason,
                canRequestAdjustment);
    }

    private void seedOfferings() {
        add(new TeacherOfferingDTO(FULL_ROSTER_OFFERING, "CS203-01", "数据结构 CS203-01",
                "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3, ROSTER_LENGTH, 30,
                OFFERING_STATUS_OPEN, true, true));
        add(new TeacherOfferingDTO(INTERACTION_OFFERING, "CS352-01", "人机交互 CS352-01",
                "2003", "CS352", "人机交互导论", 2.0, 2025, 2, 12, 30,
                OFFERING_STATUS_OPEN, false, true));
    }

    private void seedEmptyOffering() {
        // 空班的叙事是“尚未开放选课”，因此用真实状态 NOT_OPEN 而不是 OPEN。
        add(new TeacherOfferingDTO(OPERATING_SYSTEM_OFFERING, "CS301-01", "操作系统 CS301-01",
                "2002", "CS301", "操作系统原理", 3.5, 2025, 3, 0, 40,
                OFFERING_STATUS_NOT_OPEN, true, true));
    }

    private void seedAutumnOffering() {
        add(new TeacherOfferingDTO("9007199254740999", "CS204-01", "离散数学 CS204-01",
                "2004", "CS204", "离散数学", 3.0, 2025, 2, 12, 60,
                OFFERING_STATUS_STOPPED, true, true));
    }

    private void seedFullRoster() {
        List<TeacherRosterRowDTO> roster = new ArrayList<>();
        for (int index = 0; index < ROSTER_LENGTH; index++) {
            String uid = String.format("%08d", 5600 + index);
            String name = index == 0
                    ? "欧阳阿依古丽·买买提江·吐尔逊超长姓名测试"
                    : "学生" + String.format("%02d", index);
            boolean dropped = index % 8 == 3;
            roster.add(new TeacherRosterRowDTO(String.valueOf(50031 + index), uid, name,
                    index % 3 == 0 ? "计算机科学与技术（人工智能方向）实验班" : "计算机科学与技术",
                    dropped ? DROPPED : ENROLLED,
                    "2026-09-01T01:00:00Z",
                    dropped ? "2026-09-10T02:30:00Z" : null));
        }
        rosters.put(FULL_ROSTER_OFFERING, roster);
    }

    private void seedSchedules() {
        schedules.put(FULL_ROSTER_OFFERING, List.of(new ScheduleArrangementDTO(
                "9503", "7001", FULL_ROSTER_OFFERING,
                new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                1, 16, ACTIVE, 1)));
        schedules.put(INTERACTION_OFFERING, List.of(new ScheduleArrangementDTO(
                "9507", "7002", INTERACTION_OFFERING,
                new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60),
                List.of(new ScheduleSlotDTO(2, 5, 6)), 1, 16, ACTIVE, 1)));
    }

    private void add(TeacherOfferingDTO offering) {
        offerings.put(offering.getOfferingId(), offering);
        details.put(offering.getOfferingId(), new TeacherOfferingDetailDTO(offering,
                List.of(new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0),
                        new ScheduleResourceDTO("8002", "00009012", "王助教", "teacher", 0)),
                // 历史数据没有维护开课学院时保持 NULL，界面显示“未维护”。
                "CS352-01".equals(offering.getOfferingCode()) ? null : "计算机科学与工程学院",
                offering.getCourseName() + " 的课程简介"));
        rosters.putIfAbsent(offering.getOfferingId(), List.of());
    }

    private static <T> TeacherPageDTO<T> page(List<T> items, int page, int size) {
        if (page < 1) throw badRequest("page 必须大于 0");
        if (size < 1 || size > MAX_PAGE_SIZE) throw badRequest("size 必须为 1 至 100");
        int from = (int) Math.min((long) (page - 1) * size, items.size());
        int to = (int) Math.min((long) from + size, items.size());
        return new TeacherPageDTO<>(new ArrayList<>(items.subList(from, to)),
                items.size(), page, size);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean matches(String value, String keyword) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private static TeacherCourseServiceException badRequest(String message) {
        return new TeacherCourseServiceException(MessageCode.BAD_REQUEST, message);
    }

    private static TeacherCourseServiceException notFound(String message) {
        return new TeacherCourseServiceException(MessageCode.NOT_FOUND, message);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
