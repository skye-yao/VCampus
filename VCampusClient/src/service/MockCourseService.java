package service;

import dto.course.CourseCalendarDateDTO;
import dto.course.CoursePeriodDTO;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import model.course.CourseMeetingView;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseSelectionItemView;
import model.course.CourseTeacherView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeRecordView;
import model.course.GradeSummaryView;
import model.course.ScheduleDisplayKind;
import model.course.ScheduleEntryView;
import model.course.ScheduleWeekView;
import model.course.SelectionStatus;
import model.course.TrainingPlanCourseView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;

public final class MockCourseService implements CourseService {
    private static final String DEFAULT_TERM_NAME = "2026-2027 秋学期";
    private static final CourseTermView DEFAULT_TERM =
            new CourseTermView(2026, 1, DEFAULT_TERM_NAME);
    private static final String OFFERED_AT = "2099-09-10T01:00:00Z";
    private static final String OFFER_EXPIRES_AT = "2099-09-10T01:05:00Z";
    /** 教学日历夹具的第 1 周周一，与演示种子的 week1_start_date 同一天。 */
    private static final LocalDate WEEK_ONE_MONDAY = LocalDate.of(2026, 9, 7);
    /**
     * 周次夹具：教学周 1..16（与 {@code scheduleTemplates} 的起止周一致），"当前周"取第 3 周——
     * 一个确定性常量，离线界面因此也能拿到范围与初值，且不会因为跑测试的日期不同而漂移。
     */
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 16;
    private static final int CURRENT_WEEK = 3;
    /**
     * 节次夹具：一天 8 节，时刻照演示种子唯一的 {@code day_template} 3101。学生端网格的行数
     * 就是它的长度，因此这里同时是"行数不再写死 13"的离线证据。
     */
    private static final String[][] PERIOD_TIMES = {
            {"08:00:00", "08:45:00"}, {"08:50:00", "09:35:00"},
            {"10:00:00", "10:45:00"}, {"10:50:00", "11:35:00"},
            {"13:30:00", "14:15:00"}, {"14:20:00", "15:05:00"},
            {"15:30:00", "16:15:00"}, {"16:20:00", "17:05:00"}};

    private final Map<Long, CourseView> courses = new LinkedHashMap<>();
    private final Map<Long, CourseOfferingView> offerings = new LinkedHashMap<>();
    private final Map<String, CourseMutationResultView> operationResults =
            new LinkedHashMap<>();
    private final Map<Long, ScheduleEntryView> scheduleTemplates = new LinkedHashMap<>();
    private final List<CourseNoticeView> notices = new ArrayList<>();
    private final List<ScheduleAdjustment> adjustments = new ArrayList<>();

    public MockCourseService() {
        addCourse(new CourseView(101L, "CS203", "数据结构", "必修", 4.0, 64,
                "线性表、树和图", "程序设计基础"));
        addCourse(new CourseView(201L, "CS301", "操作系统", "必修", 3.5, 56,
                "进程、内存与文件系统", "数据结构"));
        addCourse(new CourseView(301L, "CS352", "人机交互", "限选", 2.0, 32,
                "交互设计与可用性评估", "无"));
        addCourse(new CourseView(401L, "AR101", "音乐鉴赏", "通选", 2.0, 32,
                "中外经典音乐作品赏析", "无"));
        addCourse(new CourseView(501L, "MA202", "离散数学", "必修", 3.0, 48,
                "集合、图论与数理逻辑", "高等数学"));
        addCourse(new CourseView(601L, "CS305", "计算机网络", "选修", 3.5, 56,
                "网络体系结构与协议", "操作系统"));

        addOffering(offering(1001L, 101L, "张老师", 2, 3, 4,
                "教四-201", 96, 120, SelectionStatus.AVAILABLE, null));
        addOffering(offering(1007L, 101L, "周老师", 4, 1, 2,
                "教四-203", 88, 120, SelectionStatus.PLANNED, null));
        addOffering(offering(1002L, 201L, "李老师", 1, 5, 6,
                "教二-305", 100, 100, SelectionStatus.AVAILABLE, null));
        addOffering(offering(1008L, 201L, "孙老师", 3, 5, 6,
                "教二-307", 100, 100, SelectionStatus.FULL, null));
        addOffering(offering(1003L, 301L, "王老师", 4, 7, 8,
                "教一-408", 60, 60, SelectionStatus.WAITLISTED, null));
        addOffering(offering(1009L, 301L, "郑老师", 2, 7, 8,
                "教一-410", 59, 60, SelectionStatus.WAITLIST_OFFERED,
                OFFER_EXPIRES_AT));
        addOffering(offering(1004L, 401L, "陈老师", 5, 9, 10,
                "艺术楼-101", 47, 80, SelectionStatus.AVAILABLE, null));
        addOffering(offering(1010L, 401L, "钱老师", 3, 9, 10,
                "艺术楼-103", 35, 80, SelectionStatus.AVAILABLE, null));
        addOffering(offering(1005L, 501L, "赵老师", 3, 1, 2,
                "教三-202", 86, 120, SelectionStatus.ENROLLED, null));
        addOffering(offering(1011L, 501L, "吴老师", 5, 1, 2,
                "教三-204", 72, 120, SelectionStatus.AVAILABLE, null));
        addOffering(offering(1006L, 601L, "刘老师", 4, 3, 4,
                "教四-305", 79, 100, SelectionStatus.ENROLLED, null));
        addOffering(offering(1012L, 601L, "冯老师", 1, 3, 4,
                "教四-307", 68, 100, SelectionStatus.AVAILABLE, null));

        addScheduleTemplate(new ScheduleEntryView(
                1001L, DEFAULT_TERM_NAME, "CS203", "数据结构", "张老师", "教四-201",
                2, 3, 2, 1, 16));
        addScheduleTemplate(new ScheduleEntryView(
                1002L, DEFAULT_TERM_NAME, "CS301", "操作系统", "李老师", "教二-305",
                1, 5, 2, 1, 16));
        addScheduleTemplate(new ScheduleEntryView(
                1003L, DEFAULT_TERM_NAME, "CS352", "人机交互", "王老师", "教一-408",
                4, 7, 2, 1, 16));
        addScheduleTemplate(new ScheduleEntryView(
                1004L, DEFAULT_TERM_NAME, "AR101", "音乐鉴赏", "陈老师", "艺术楼-101",
                5, 9, 2, 1, 16));
        addScheduleTemplate(new ScheduleEntryView(
                1005L, DEFAULT_TERM_NAME, "MA202", "离散数学", "赵老师", "教三-202",
                3, 1, 2, 1, 16));
        addScheduleTemplate(new ScheduleEntryView(
                1006L, DEFAULT_TERM_NAME, "CS305", "计算机网络", "刘老师", "教四-305",
                4, 3, 2, 1, 16));

        notices.add(new CourseNoticeView(
                DEFAULT_TERM_NAME, 8, "计算机网络停课通知",
                "第 8 周周四课程暂停一次，补课时间另行通知。"));
        notices.add(new CourseNoticeView(
                DEFAULT_TERM_NAME, 13, "数据结构调课通知",
                "第 13 周课程调整至周五 3-4 节，地点为教四-201。"));
        // The paired timetable state behind that notice: the week-13 meeting of 数据结构 moves.
        adjustments.add(new ScheduleAdjustment(1001L, 13, 5, 3, 2, "张老师", "教四-201",
                "教师出差，第 13 周课程调整", "ADJ-1001-13"));
    }

    @Override
    public CompletableFuture<List<CourseTermView>> loadTerms() {
        return CompletableFuture.completedFuture(List.of(DEFAULT_TERM));
    }

    @Override
    public synchronized CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
        if (!DEFAULT_TERM.equals(term)) {
            return CompletableFuture.completedFuture(List.of());
        }
        return CompletableFuture.completedFuture(List.copyOf(courses.values()));
    }

    @Override
    public synchronized CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
            CourseTermView term, long courseId) {
        if (!DEFAULT_TERM.equals(term)) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CourseOfferingView> matching = new ArrayList<>();
        for (CourseOfferingView offering : offerings.values()) {
            if (offering.getCourseId() == courseId) matching.add(offering);
        }
        return CompletableFuture.completedFuture(List.copyOf(matching));
    }

    @Override
    public synchronized CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
            CourseTermView term) {
        if (!DEFAULT_TERM.equals(term)) {
            return CompletableFuture.completedFuture(
                    new CoursePlanSnapshotView(term, List.of(), List.of(), List.of()));
        }
        return CompletableFuture.completedFuture(snapshot());
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> addToPlan(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering ->
                requireAndCopy(offering, SelectionStatus.AVAILABLE,
                        SelectionStatus.PLANNED, offering.getEnrolledCount(),
                        null, null, null));
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> removeFromPlan(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering -> {
            SelectionStatus status = offering.getSelectionStatus();
            if (status != SelectionStatus.PLANNED && status != SelectionStatus.FULL) {
                throw stateError(offering, "PLANNED or FULL");
            }
            return copyWith(offering, SelectionStatus.AVAILABLE,
                    offering.getEnrolledCount(), null, null, null);
        });
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> selectOffering(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering -> {
            if (offering.getSelectionStatus() != SelectionStatus.PLANNED) {
                throw stateError(offering, "PLANNED");
            }
            if (offering.getEnrolledCount() >= offering.getCapacity()) {
                return copyWith(offering, SelectionStatus.FULL,
                        offering.getEnrolledCount(), "教学班已满", null, null);
            }
            return copyWith(offering, SelectionStatus.ENROLLED,
                    offering.getEnrolledCount() + 1, null, null, null);
        });
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> joinWaitlist(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering ->
                requireAndCopy(offering, SelectionStatus.FULL,
                        SelectionStatus.WAITLISTED, offering.getEnrolledCount(),
                        null, null, null));
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> cancelWaitlist(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering ->
                requireAndCopy(offering, SelectionStatus.WAITLISTED,
                        SelectionStatus.FULL, offering.getEnrolledCount(),
                        "教学班已满", null, null));
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
            CourseTermView term, long offeringId, String operationId,
            WaitlistDecision decision) {
        return mutate(term, offeringId, operationId, offering -> {
            if (offering.getSelectionStatus() != SelectionStatus.WAITLIST_OFFERED) {
                throw stateError(offering, "WAITLIST_OFFERED");
            }
            if (decision == WaitlistDecision.ACCEPT) {
                return copyWith(offering, SelectionStatus.ENROLLED,
                        Math.min(offering.getCapacity(), offering.getEnrolledCount() + 1),
                        null, null, null);
            }
            if (decision == WaitlistDecision.ABANDON) {
                return copyWith(offering, SelectionStatus.FULL,
                        offering.getEnrolledCount(), "已放弃候补席位", null, null);
            }
            throw new IllegalArgumentException("Waitlist decision is required");
        });
    }

    @Override
    public synchronized CompletableFuture<CourseMutationResultView> dropOffering(
            CourseTermView term, long offeringId, String operationId) {
        return mutate(term, offeringId, operationId, offering ->
                requireAndCopy(offering, SelectionStatus.ENROLLED,
                        SelectionStatus.AVAILABLE,
                        Math.max(0, offering.getEnrolledCount() - 1),
                        null, null, null));
    }

    @Override
    public CompletableFuture<Void> ackCourseEvent(String eventId) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CourseSubscription subscribe(CoursePushListener listener) {
        return new CourseSubscription() {
            private boolean closed;

            @Override
            public void close() {
                closed = true;
            }

            @Override
            public String toString() {
                return closed ? "closed mock course subscription"
                        : "open mock course subscription";
            }
        };
    }

    /**
     * 与真服务同构：课次连同该周的日期与节次字典一起返回，学生端网格的行列因此由"教学日历"
     * 决定而不是客户端常量。夹具的日历对任何周都成立，只按选课状态过滤课次。
     *
     * <p>{@code week} 为 null（或非正）时取夹具的固定当前周，与真服务"缺省即当前周"同义；
     * 响应同样带上周范围与当前周，周次控件因此离线也能从服务端取值。
     */
    @Override
    public synchronized CompletableFuture<ScheduleWeekView> loadSchedule(
            CourseTermView term, Integer week) {
        int effectiveWeek = week == null || week <= 0 ? CURRENT_WEEK : week;
        List<ScheduleEntryView> entries = new ArrayList<>();
        for (Map.Entry<Long, ScheduleEntryView> template : scheduleTemplates.entrySet()) {
            CourseOfferingView offering = offerings.get(template.getKey());
            ScheduleEntryView scheduleEntry = template.getValue();
            if (offering != null
                    && offering.getSelectionStatus() == SelectionStatus.ENROLLED
                    && scheduleEntry.getTerm().equals(term == null ? null : term.getDisplayName())
                    && scheduleEntry.isActiveInWeek(effectiveWeek)) {
                ScheduleAdjustment adjustment = adjustmentIn(template.getKey(), effectiveWeek);
                if (adjustment == null) {
                    entries.add(scheduleEntry);
                } else {
                    entries.add(adjustedOriginal(scheduleEntry, adjustment));
                    entries.add(adjustedTarget(scheduleEntry, adjustment));
                }
            }
        }
        return CompletableFuture.completedFuture(new ScheduleWeekView(
                effectiveWeek, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, weekDates(effectiveWeek),
                weekPeriods(effectiveWeek), List.copyOf(entries)));
    }

    /** 某一周的教学日夹具：周一至周五，全部是教学日。 */
    private static List<CourseCalendarDateDTO> weekDates(int week) {
        LocalDate monday = WEEK_ONE_MONDAY.plusWeeks(week - 1L);
        List<CourseCalendarDateDTO> dates = new ArrayList<>();
        for (int weekday = 1; weekday <= 5; weekday++) {
            dates.add(new CourseCalendarDateDTO(monday.plusDays(weekday - 1L).toString(),
                    week, weekday, true));
        }
        return List.copyOf(dates);
    }

    /** 某一周的节次夹具：每个教学日各一份 1..8 节的定义（两个日期可用不同天模板，故按日期成行）。 */
    private static List<CoursePeriodDTO> weekPeriods(int week) {
        List<CoursePeriodDTO> periods = new ArrayList<>();
        for (CourseCalendarDateDTO date : weekDates(week)) {
            for (int period = 1; period <= PERIOD_TIMES.length; period++) {
                periods.add(new CoursePeriodDTO(date.getDate(), period,
                        PERIOD_TIMES[period - 1][0], PERIOD_TIMES[period - 1][1]));
            }
        }
        return List.copyOf(periods);
    }

    private ScheduleAdjustment adjustmentIn(long offeringId, int week) {
        for (ScheduleAdjustment adjustment : adjustments) {
            if (adjustment.offeringId() == offeringId && adjustment.week() == week) {
                return adjustment;
            }
        }
        return null;
    }

    /** The display-only half: the published slot, which no longer occupies it. */
    private static ScheduleEntryView adjustedOriginal(ScheduleEntryView base,
                                                     ScheduleAdjustment adjustment) {
        return new ScheduleEntryView(base.getOfferingId(), base.getTerm(), base.getCourseCode(),
                base.getCourseName(), base.getTeacher(), base.getLocation(), base.getDayOfWeek(),
                base.getStartPeriod(), base.getPeriodCount(), base.getStartWeek(), base.getEndWeek(),
                ScheduleDisplayKind.ADJUSTED_ORIGINAL, adjustment.adjustmentId(),
                originalText(base), adjustedText(adjustment), adjustment.reason());
    }

    /** The effective half: where and when the lesson actually happens in that week. */
    private static ScheduleEntryView adjustedTarget(ScheduleEntryView base,
                                                    ScheduleAdjustment adjustment) {
        return new ScheduleEntryView(base.getOfferingId(), base.getTerm(), base.getCourseCode(),
                base.getCourseName(), adjustment.teacher(), adjustment.location(),
                adjustment.dayOfWeek(), adjustment.startPeriod(), adjustment.periodCount(),
                base.getStartWeek(), base.getEndWeek(), ScheduleDisplayKind.ADJUSTED_TARGET,
                adjustment.adjustmentId(), originalText(base), adjustedText(adjustment),
                adjustment.reason());
    }

    private static String originalText(ScheduleEntryView base) {
        return scheduleText(base.getDayOfWeek(), base.getStartPeriod(),
                base.getStartPeriod() + base.getPeriodCount() - 1, base.getLocation());
    }

    private static String adjustedText(ScheduleAdjustment adjustment) {
        return scheduleText(adjustment.dayOfWeek(), adjustment.startPeriod(),
                adjustment.startPeriod() + adjustment.periodCount() - 1, adjustment.location());
    }

    private static String scheduleText(int dayOfWeek, int startPeriod, int endPeriod,
                                       String location) {
        String day = switch (dayOfWeek) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "周" + dayOfWeek;
        };
        return day + " 第" + startPeriod + "-" + endPeriod + "节"
                + (location == null || location.isBlank() ? "" : " " + location);
    }

    private record ScheduleAdjustment(long offeringId, int week, int dayOfWeek, int startPeriod,
                                      int periodCount, String teacher, String location,
                                      String reason, String adjustmentId) { }

    @Override
    public CompletableFuture<List<CourseNoticeView>> loadNotices(
            CourseTermView term, int week) {
        String displayName = term == null ? null : term.getDisplayName();
        List<CourseNoticeView> matching = new ArrayList<>();
        for (CourseNoticeView notice : notices) {
            if (notice.getTerm().equals(displayName) && notice.getWeek() == week) {
                matching.add(notice);
            }
        }
        return CompletableFuture.completedFuture(List.copyOf(matching));
    }

    @Override
    public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
        String displayName = term == null ? null : term.getDisplayName();
        if (DEFAULT_TERM_NAME.equals(displayName)) {
            List<GradeRecordView> records = List.of(
                    new GradeRecordView(
                            displayName, "CS101", "程序设计基础", 4.0, 94.0, 4.0,
                            95.0, 92.0, null, 95.0),
                    new GradeRecordView(
                            displayName, "MA101", "高等数学", 5.0, 89.0, 3.7,
                            90.0, 88.0, 87.0, 90.0));
            return CompletableFuture.completedFuture(
                    new GradeSummaryView(displayName, 3.85, 91.5, 90.8, 3.78, records));
        }
        return CompletableFuture.completedFuture(
                new GradeSummaryView(displayName, 0.0, 0.0, 0.0, 0.0, List.of()));
    }

    @Override
    public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
        List<TrainingPlanGroupView> groups = List.of(
                new TrainingPlanGroupView("必修课程", 80.0, 9.0, List.of(
                        new TrainingPlanCourseView("CS101", "程序设计基础", 4.0, "已修"),
                        new TrainingPlanCourseView("MA101", "高等数学", 5.0, "已修"),
                        new TrainingPlanCourseView("CS203", "数据结构", 4.0, "在修"),
                        new TrainingPlanCourseView("CS301", "操作系统", 3.5, "未修"))),
                new TrainingPlanGroupView("限选课程", 20.0, 3.5, List.of(
                        new TrainingPlanCourseView("CS250", "数据库原理", 3.5, "已修"),
                        new TrainingPlanCourseView("CS305", "计算机网络", 3.5, "在修"),
                        new TrainingPlanCourseView("CS330", "编译原理", 2.5, "未修"))),
                new TrainingPlanGroupView("选修课程", 12.0, 2.0, List.of(
                        new TrainingPlanCourseView("CS410", "人工智能导论", 2.0, "已修"),
                        new TrainingPlanCourseView("CS352", "人机交互", 2.0, "在修"),
                        new TrainingPlanCourseView("CS430", "云计算基础", 2.0, "未修"))),
                new TrainingPlanGroupView("通选课程", 10.0, 2.0, List.of(
                        new TrainingPlanCourseView("GE101", "大学生心理健康", 2.0, "已修"),
                        new TrainingPlanCourseView("AR101", "音乐鉴赏", 2.0, "在修"),
                        new TrainingPlanCourseView("PE103", "羽毛球", 1.0, "未修"))));
        return CompletableFuture.completedFuture(groups);
    }

    private CompletableFuture<CourseMutationResultView> mutate(CourseTermView term,
            long offeringId, String operationId,
            Function<CourseOfferingView, CourseOfferingView> transition) {
        CourseMutationResultView replay = operationResults.get(operationId);
        if (replay != null) return CompletableFuture.completedFuture(replay);
        if (!DEFAULT_TERM.equals(term)) return failed("Unknown term: " + term);
        if (operationId == null || operationId.isBlank()) {
            return failed("Operation ID is required");
        }
        CourseOfferingView offering = offerings.get(offeringId);
        if (offering == null) return failed("Unknown offering: " + offeringId);

        try {
            CourseOfferingView updated = transition.apply(offering);
            offerings.put(offeringId, updated);
            CoursePlanSnapshotView snapshot = snapshot();
            CourseSelectionItemView item = selectionItem(updated);
            CourseMutationResultView result = new CourseMutationResultView(
                    operationId, item, updated.getSelectionStatus(),
                    updated.getSelectionStatus().name(), outcomeMessage(updated), snapshot);
            operationResults.put(operationId, result);
            return CompletableFuture.completedFuture(result);
        } catch (RuntimeException error) {
            return failed(error);
        }
    }

    private CoursePlanSnapshotView snapshot() {
        List<CourseSelectionItemView> plan = new ArrayList<>();
        List<CourseSelectionItemView> waitlist = new ArrayList<>();
        List<CourseSelectionItemView> enrolled = new ArrayList<>();
        for (CourseOfferingView offering : offerings.values()) {
            CourseSelectionItemView item = selectionItem(offering);
            switch (offering.getSelectionStatus()) {
                case PLANNED:
                case FULL:
                    plan.add(item);
                    break;
                case WAITLISTED:
                case WAITLIST_OFFERED:
                    waitlist.add(item);
                    break;
                case ENROLLED:
                    enrolled.add(item);
                    break;
                default:
                    break;
            }
        }
        return new CoursePlanSnapshotView(DEFAULT_TERM, plan, waitlist, enrolled);
    }

    private CourseSelectionItemView selectionItem(CourseOfferingView offering) {
        return new CourseSelectionItemView(courses.get(offering.getCourseId()), offering);
    }

    private static CourseOfferingView requireAndCopy(CourseOfferingView offering,
            SelectionStatus expected, SelectionStatus target, int enrolledCount,
            String failureReason, String offeredAt, String expiresAt) {
        if (offering.getSelectionStatus() != expected) {
            throw stateError(offering, expected.name());
        }
        return copyWith(offering, target, enrolledCount,
                failureReason, offeredAt, expiresAt);
    }

    private static CourseOfferingView copyWith(CourseOfferingView offering,
            SelectionStatus status, int enrolledCount, String failureReason,
            String offeredAt, String expiresAt) {
        return new CourseOfferingView(
                offering.getOfferingId(), offering.getOfferingCode(), offering.getCourseId(),
                offering.getTeachers(), offering.getMeetings(),
                enrolledCount, offering.getCapacity(), status, failureReason,
                offeredAt, expiresAt);
    }

    private CourseOfferingView offering(long offeringId, long courseId,
            String teacher, int day, int startPeriod, int endPeriod,
            String location, int enrolledCount, int capacity,
            SelectionStatus status, String expiresAt) {
        String offeredAt = status == SelectionStatus.WAITLIST_OFFERED
                ? OFFERED_AT : null;
        return new CourseOfferingView(
                offeringId, nextOfferingCode(courseId), courseId,
                List.of(new CourseTeacherView("T" + offeringId, teacher)),
                List.of(new CourseMeetingView(
                        day, startPeriod, endPeriod, 1, 16, "ALL", location,
                        null, null)),
                enrolledCount, capacity, status,
                status == SelectionStatus.FULL ? "教学班已满" : null,
                offeredAt, expiresAt);
    }

    /**
     * 教学班代码的夹具形态与演示种子一致（{@code CS101-2026-2-A}）：课程代码 + 学年 + 学期 + 班号。
     * 班号按该课程已有的教学班数依次取 A、B…，因此同一课程下的代码唯一，且从代码能反推出课程——
     * 这正是"全部页签的教学班行标题 = 教学班代码"要求的那条信息。
     */
    private String nextOfferingCode(long courseId) {
        CourseView course = courses.get(courseId);
        int section = 0;
        for (CourseOfferingView existing : offerings.values()) {
            if (existing.getCourseId() == courseId) section++;
        }
        return (course == null ? "COURSE" : course.getCourseCode()) + "-"
                + DEFAULT_TERM.getAcademicYear() + "-" + DEFAULT_TERM.getSemester() + "-"
                + (char) ('A' + section);
    }

    private static IllegalStateException stateError(
            CourseOfferingView offering, String expected) {
        return new IllegalStateException("Expected " + expected + " but was "
                + offering.getSelectionStatus() + " for offering: "
                + offering.getOfferingId());
    }

    private static String outcomeMessage(CourseOfferingView offering) {
        switch (offering.getSelectionStatus()) {
            case AVAILABLE: return "已移出";
            case PLANNED: return "已加入计划";
            case FULL: return "教学班已满";
            case WAITLISTED: return "已加入候补";
            case WAITLIST_OFFERED: return "候补席位待处理";
            case ENROLLED: return "选课成功";
            default: throw new IllegalArgumentException(
                    "Unknown status: " + offering.getSelectionStatus());
        }
    }

    private void addCourse(CourseView course) {
        courses.put(course.getCourseId(), course);
    }

    private void addOffering(CourseOfferingView offering) {
        offerings.put(offering.getOfferingId(), offering);
    }

    private void addScheduleTemplate(ScheduleEntryView scheduleEntry) {
        scheduleTemplates.put(scheduleEntry.getOfferingId(), scheduleEntry);
    }

    private static <T> CompletableFuture<T> failed(String message) {
        return failed(new IllegalStateException(message));
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }
}
