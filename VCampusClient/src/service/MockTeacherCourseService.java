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

    // 教师周课表的固定教学日历：mock 不连数据库，任何学年学期都返回同一份可复现日历。
    private static final String SCHEDULE_CALENDAR_ID = "9007199254740991";
    private static final String SCHEDULE_TIMEZONE = "Asia/Shanghai";
    private static final int MIN_TEACHING_WEEK = 1;
    private static final int MAX_TEACHING_WEEK = 16;
    /** mock 的“服务器时钟”当前教学周；week=null 时选它。 */
    private static final int CURRENT_TEACHING_WEEK = 8;
    /** 第 8 周周一，用于按周推导每个 ISO 本地日期。 */
    private static final LocalDate WEEK_EIGHT_MONDAY = LocalDate.of(2026, 9, 14);
    /** 节次模板覆盖到第 13 节，不硬编码十节。 */
    private static final int PERIOD_COUNT = 13;
    private static final LocalTime FIRST_PERIOD_START = LocalTime.of(8, 0);
    private static final int PERIOD_LENGTH_MINUTES = 45;
    private static final int PERIOD_INTERVAL_MINUTES = 50;
    private static final DateTimeFormatter PERIOD_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss");

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

    @Override
    public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
            int academicYear, int semester, Integer week) {
        try {
            int selectedWeek = week == null ? CURRENT_TEACHING_WEEK : week;
            if (selectedWeek < MIN_TEACHING_WEEK || selectedWeek > MAX_TEACHING_WEEK) {
                throw badRequest("week 必须落在 " + MIN_TEACHING_WEEK + ".." + MAX_TEACHING_WEEK);
            }
            return CompletableFuture.completedFuture(buildWeek(selectedWeek));
        } catch (RuntimeException failure) {
            return failed(failure);
        }
    }

    // ------------------------------------------------------------------ 固定数据

    /** 构造一份日期、节次与课次自洽的固定周：周一到周日、13 节、周一有一节 NORMAL 课。 */
    private static TeacherScheduleWeekDTO buildWeek(int week) {
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            String date = WEEK_EIGHT_MONDAY.plusWeeks(week - 8L)
                    .plusDays(weekday - 1L).toString();
            boolean teachingDay = weekday <= 5;
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

        String monday = WEEK_EIGHT_MONDAY.plusWeeks(week - 8L).toString();
        List<TeacherScheduleEntryDTO> entries = List.of(new TeacherScheduleEntryDTO(
                Long.toString(9007199254740000L + week), FULL_ROSTER_OFFERING,
                "CS203", "数据结构与算法基础", "陈老师", "A-101", monday, week, 1, 1, 2,
                ScheduleDisplayKindDTO.NORMAL, null, null, null, null, true));

        return new TeacherScheduleWeekDTO(SCHEDULE_CALENDAR_ID, SCHEDULE_TIMEZONE, week,
                MIN_TEACHING_WEEK, MAX_TEACHING_WEEK, CURRENT_TEACHING_WEEK,
                dates, periods, entries);
    }


    private void seedOfferings() {
        add(new TeacherOfferingDTO(FULL_ROSTER_OFFERING, "CS203-01", "数据结构 CS203-01",
                "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3, ROSTER_LENGTH, 30,
                OFFERING_STATUS_OPEN, true, true));
        add(new TeacherOfferingDTO("9007199254740997", "CS352-01", "人机交互 CS352-01",
                "2003", "CS352", "人机交互导论", 2.0, 2025, 2, 12, 30,
                OFFERING_STATUS_OPEN, false, true));
    }

    private void seedEmptyOffering() {
        // 空班的叙事是“尚未开放选课”，因此用真实状态 NOT_OPEN 而不是 OPEN。
        add(new TeacherOfferingDTO("9007199254740995", "CS301-01", "操作系统 CS301-01",
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
        schedules.put("9007199254740997", List.of(new ScheduleArrangementDTO(
                "9507", "7002", "9007199254740997",
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
