package service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;

/**
 * 无服务器预览用教师课表 fixture 的契约回归。
 *
 * <p>固定断言：两个学期的日历元数据（其中 2025/2 的 {@code currentWeek} 为 null，用于驱动“今天不在
 * 该学期”的分支）、跨周调整在周 8 只给原位置、周 9 只给新位置，同周调整在同一周给两块并共享
 * adjustmentId、无课周仍有 7 行日期与节次模板、周末与第 13 节不被硬编码裁掉，以及三个列表的
 * 不可变性。entry 的日期必须能在同一响应的 dates 中按 (week, teachingWeekday) 找到。
 *
 * <p>全程 headless：不连数据库、不开 socket、不启动 JavaFX。
 */
public final class MockTeacherScheduleTest {
    private static final int ACADEMIC_YEAR = 2025;
    private static final int SPRING = 3;
    private static final int AUTUMN = 2;
    private static final String SPRING_CALENDAR_ID = "9007199254740001";
    private static final String AUTUMN_CALENDAR_ID = "9007199254740002";
    private static final String TIMEZONE = "Asia/Shanghai";
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 16;
    private static final int SPRING_CURRENT_WEEK = 8;
    private static final int CALENDAR_DAYS_PER_WEEK = 7;
    private static final int TEACHING_DAYS_PER_WEEK = 6;
    private static final int PERIODS_PER_TEACHING_DAY = 13;

    private static final String CROSS_WEEK_OCCURRENCE = "9201";
    private static final String SAME_WEEK_OCCURRENCE = "9202";
    private static final String PLAIN_OCCURRENCE = "9203";
    private static final String CROSS_WEEK_ADJUSTMENT = "9301";
    private static final String SAME_WEEK_ADJUSTMENT = "9302";

    private static final String CROSS_WEEK_ORIGINAL_TEXT = "周一 第1-2节 A-101";
    private static final String CROSS_WEEK_ADJUSTED_TEXT = "周三 第3-4节 B-203";
    private static final String TEACHER_WITH_ASSISTANT = "陈老师, 王助教";
    private static final String SAME_WEEK_ORIGINAL_TEXT = "周二 第1-2节 A-101";
    private static final String SAME_WEEK_ADJUSTED_TEXT = "周五 第5-6节 B-203";

    private MockTeacherScheduleTest() {
    }

    public static void main(String[] args) {
        MockTeacherCourseService service = new MockTeacherCourseService();

        calendarMetadataMatchesTheFixtureTable(service);
        sameWeekAdjustmentReturnsBothBlocksInOneWeek(service);
        crossWeekAdjustmentSplitsOriginalAndTargetAcrossWeeks(service);
        everyEntryLandsOnAPublishedCalendarDate(service);
        periodsCoverThirteenAndEntriesCoverTheWeekend(service);
        emptyWeekStillCarriesDatesAndPeriods(service);
        nullWeekResolvesToCurrentWeekOrMinimum(service);
        invalidWeeksAreRejectedAndUnknownTermsAreNotFound(service);
        scheduleListsAreImmutable(service);
        System.out.println("MockTeacherScheduleTest: PASS");
    }

    private static void calendarMetadataMatchesTheFixtureTable(MockTeacherCourseService service) {
        TeacherScheduleWeekDTO spring = week(service, SPRING, SPRING_CURRENT_WEEK);
        require(SPRING_CALENDAR_ID.equals(spring.getCalendarId()),
                "the spring calendar ID must stay the exact decimal string, saw "
                        + spring.getCalendarId());
        require(TIMEZONE.equals(spring.getTimezone()), "the calendar timezone must be IANA");
        require(spring.getMinWeek() == MIN_WEEK && spring.getMaxWeek() == MAX_WEEK,
                "the teaching week bounds must be 1..16");
        require(Integer.valueOf(SPRING_CURRENT_WEEK).equals(spring.getCurrentWeek()),
                "the spring term must report its current week");

        TeacherScheduleWeekDTO autumn = week(service, AUTUMN, SPRING_CURRENT_WEEK);
        require(AUTUMN_CALENDAR_ID.equals(autumn.getCalendarId()),
                "the autumn term must use its own calendar ID, saw " + autumn.getCalendarId());
        require(autumn.getCurrentWeek() == null,
                "the autumn term must reproduce 'today is outside the term' with a null"
                        + " currentWeek, saw " + autumn.getCurrentWeek());
        require(TIMEZONE.equals(autumn.getTimezone()) && autumn.getMinWeek() == MIN_WEEK
                        && autumn.getMaxWeek() == MAX_WEEK,
                "the autumn calendar must keep the same timezone and week bounds");
    }

    private static void sameWeekAdjustmentReturnsBothBlocksInOneWeek(
            MockTeacherCourseService service) {
        TeacherScheduleWeekDTO week8 = week(service, SPRING, 8);

        List<TeacherScheduleEntryDTO> blocks = week8.getEntries().stream()
                .filter(entry -> SAME_WEEK_OCCURRENCE.equals(entry.getOccurrenceId()))
                .toList();
        require(blocks.size() == 2,
                "a same-week adjustment must return both blocks in one week, saw "
                        + blocks.size());
        require(blocks.stream().anyMatch(
                        entry -> entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL),
                "the same-week pair must keep its original marker");
        require(blocks.stream().anyMatch(
                        entry -> entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET),
                "the same-week pair must keep its target marker");
        require(blocks.stream().allMatch(
                        entry -> SAME_WEEK_ADJUSTMENT.equals(entry.getAdjustmentId())),
                "both blocks must share one adjustment ID");
        require(blocks.stream().map(TeacherScheduleEntryDTO::getLocalDate).distinct().count() == 2,
                "the two blocks must sit on different dates");
        require(blocks.stream().noneMatch(TeacherScheduleEntryDTO::isCanRequestAdjustment),
                "an already adjusted occurrence must not offer another request");
        require(week8.getEntries().size() == 4,
                "week 8 must expose exactly the four fixture blocks, saw "
                        + week8.getEntries().size());
    }

    private static void crossWeekAdjustmentSplitsOriginalAndTargetAcrossWeeks(
            MockTeacherCourseService service) {
        TeacherScheduleWeekDTO week8 = week(service, SPRING, 8);
        TeacherScheduleWeekDTO week9 = week(service, SPRING, 9);

        // 计划要求：必须比较两个周，不能只断言 entries 非空。
        if (week8.getEntries().stream()
                .noneMatch(e -> e.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL)) {
            throw new AssertionError("original marker missing");
        }
        if (week9.getEntries().stream()
                .noneMatch(e -> e.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET)) {
            throw new AssertionError("target marker missing");
        }

        require(week9.getEntries().size() == 1,
                "week 9 must hold only the moved target, saw " + week9.getEntries().size());
        TeacherScheduleEntryDTO target = week9.getEntries().get(0);
        require(CROSS_WEEK_OCCURRENCE.equals(target.getOccurrenceId()),
                "week 9 must hold the moved occurrence");
        require(target.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_TARGET,
                "the target week must carry the target marker");
        require(target.getWeek() == 9, "the target must land in week 9");
        require(target.getDayOfWeek() == 3 && target.getStartPeriod() == 3
                        && target.getEndPeriod() == 4,
                "the target must move to weekday 3, periods 3-4");
        require("B-203".equals(target.getLocation()), "the target must use the new room");

        TeacherScheduleEntryDTO original = week8.getEntries().stream()
                .filter(entry -> CROSS_WEEK_OCCURRENCE.equals(entry.getOccurrenceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("week 8 must hold the original block"));
        require(original.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL,
                "the source week must carry the original marker");
        require(original.getWeek() == 8, "the original must stay in week 8");
        require(original.getDayOfWeek() == 1 && original.getStartPeriod() == 1
                        && original.getEndPeriod() == 2,
                "the original must stay on weekday 1, periods 1-2");
        require("A-101".equals(original.getLocation()), "the original must keep its room");
        require(TEACHER_WITH_ASSISTANT.equals(original.getTeacher()),
                "an entry with an assistant must render '主教师, 助教' like the server, saw "
                        + original.getTeacher());
        require(TEACHER_WITH_ASSISTANT.equals(target.getTeacher()),
                "both cross-week blocks must carry the same teacher string");
        require(CROSS_WEEK_ADJUSTMENT.equals(original.getAdjustmentId())
                        && CROSS_WEEK_ADJUSTMENT.equals(target.getAdjustmentId()),
                "the two cross-week blocks must share one adjustment ID");
        require(!original.getLocalDate().equals(target.getLocalDate()),
                "cross-week blocks must not collapse onto one date");
        require(original.getWeek() != target.getWeek(),
                "cross-week blocks must not collapse onto one week");

        require(CROSS_WEEK_ORIGINAL_TEXT.equals(original.getOriginalScheduleText()),
                "the original schedule text must use the server wording, saw "
                        + original.getOriginalScheduleText());
        require(CROSS_WEEK_ADJUSTED_TEXT.equals(original.getAdjustedScheduleText()),
                "the adjusted schedule text must use the server wording, saw "
                        + original.getAdjustedScheduleText());
        require(CROSS_WEEK_ADJUSTED_TEXT.equals(target.getAdjustedScheduleText()),
                "both blocks must render the same adjusted text");
        require(original.getAdjustmentReason() != null
                        && original.getAdjustmentReason().equals(target.getAdjustmentReason()),
                "both blocks must carry the same request reason");
        require(!original.isCanRequestAdjustment() && !target.isCanRequestAdjustment(),
                "a moved occurrence must not offer another request");

        TeacherScheduleEntryDTO sameWeek = week8.getEntries().stream()
                .filter(entry -> SAME_WEEK_OCCURRENCE.equals(entry.getOccurrenceId())
                        && entry.getDisplayKind() == ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the same-week original is missing"));
        require(SAME_WEEK_ORIGINAL_TEXT.equals(sameWeek.getOriginalScheduleText()),
                "a same-week original must use the server wording, saw "
                        + sameWeek.getOriginalScheduleText());
        require(SAME_WEEK_ADJUSTED_TEXT.equals(sameWeek.getAdjustedScheduleText()),
                "a same-week adjusted text must use the server wording, saw "
                        + sameWeek.getAdjustedScheduleText());
    }

    /** 每条 entry 的 localDate 必须能在同一响应的 dates 里按 (week, teachingWeekday) 找到。 */
    private static void everyEntryLandsOnAPublishedCalendarDate(MockTeacherCourseService service) {
        for (int week = MIN_WEEK; week <= MAX_WEEK; week++) {
            int checkedWeek = week;
            TeacherScheduleWeekDTO view = week(service, SPRING, checkedWeek);
            require(view.getDates().size() == CALENDAR_DAYS_PER_WEEK,
                    "week " + checkedWeek + " must publish exactly seven calendar dates, saw "
                            + view.getDates().size());
            for (int index = 1; index < view.getDates().size(); index++) {
                require(view.getDates().get(index).getTeachingWeekday()
                                == view.getDates().get(index - 1).getTeachingWeekday() + 1,
                        "week " + checkedWeek
                                + " dates must be ordered by ascending teachingWeekday");
            }
            for (TeacherScheduleEntryDTO entry : view.getEntries()) {
                require(entry.getWeek() == checkedWeek,
                        "an entry must stay inside the requested week");
                TeacherCalendarDateDTO date = view.getDates().stream()
                        .filter(candidate -> candidate.getDate().equals(entry.getLocalDate()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("entry " + entry.getOccurrenceId()
                                + " on " + entry.getLocalDate()
                                + " is absent from the week " + checkedWeek + " dates"));
                require(date.getWeek() == checkedWeek,
                        "the matching calendar date must belong to the same week");
                require(date.getTeachingWeekday() == entry.getDayOfWeek(),
                        "an entry weekday must match the calendar date's teachingWeekday");
            }
        }
    }

    private static void periodsCoverThirteenAndEntriesCoverTheWeekend(
            MockTeacherCourseService service) {
        TeacherScheduleWeekDTO week8 = week(service, SPRING, 8);

        require(week8.getPeriods().size() == TEACHING_DAYS_PER_WEEK * PERIODS_PER_TEACHING_DAY,
                "six teaching days must publish thirteen periods each, saw "
                        + week8.getPeriods().size());
        for (TeacherPeriodDTO period : week8.getPeriods()) {
            require(period.getStartTime().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}")
                            && period.getEndTime().matches("[0-9]{2}:[0-9]{2}:[0-9]{2}"),
                    "period times must use the fixed-width HH:mm:ss shape, saw "
                            + period.getStartTime() + "-" + period.getEndTime());
        }

        require(week8.getEntries().stream().anyMatch(entry -> entry.getDayOfWeek() == 6),
                "the fixture must include a weekend (teachingWeekday 6) occurrence");
        TeacherScheduleEntryDTO weekend = week8.getEntries().stream()
                .filter(entry -> PLAIN_OCCURRENCE.equals(entry.getOccurrenceId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the unadjusted occurrence is missing"));
        require(weekend.getDisplayKind() == ScheduleDisplayKindDTO.NORMAL,
                "the unadjusted occurrence must carry the NORMAL marker");
        require(weekend.getDayOfWeek() == 6 && weekend.getStartPeriod() == 12
                        && weekend.getEndPeriod() == 13,
                "the unadjusted occurrence must exercise weekday 6, periods 12-13");
        require("C-301".equals(weekend.getLocation()),
                "the unadjusted occurrence must keep its room");
        require(weekend.getAdjustmentId() == null,
                "an unadjusted occurrence must keep a null adjustment ID");
        require(weekend.isCanRequestAdjustment(),
                "an unadjusted occurrence must be requestable");
        require(weekend.getOriginalScheduleText() == null
                        && weekend.getAdjustedScheduleText() == null
                        && weekend.getAdjustmentReason() == null,
                "an unadjusted occurrence must not invent adjustment text");
        require(weekend.getTeacher() != null && !weekend.getTeacher().isBlank(),
                "an entry must always carry its teacher");

        List<TeacherPeriodDTO> saturday = week8.getPeriods().stream()
                .filter(period -> period.getDate().equals(weekend.getLocalDate()))
                .toList();
        require(saturday.size() == PERIODS_PER_TEACHING_DAY,
                "the weekend teaching day must still publish thirteen periods");
        require(saturday.get(0).getPeriod() == 1
                        && "08:00:00".equals(saturday.get(0).getStartTime())
                        && "08:45:00".equals(saturday.get(0).getEndTime()),
                "period 1 must start at 08:00:00 and end at 08:45:00");
        require(saturday.get(PERIODS_PER_TEACHING_DAY - 1).getPeriod() == 13
                        && "18:00:00".equals(saturday.get(12).getStartTime())
                        && "18:45:00".equals(saturday.get(12).getEndTime()),
                "period 13 must exist and run 18:00:00-18:45:00");

        TeacherCalendarDateDTO sunday = week8.getDates().stream()
                .filter(date -> date.getTeachingWeekday() == 7)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the seventh calendar day is missing"));
        require(!sunday.isTeachingDay(), "the seventh calendar day must not be a teaching day");
        require(week8.getPeriods().stream()
                        .noneMatch(period -> period.getDate().equals(sunday.getDate())),
                "a non-teaching day must not publish period rows");
    }

    private static void emptyWeekStillCarriesDatesAndPeriods(MockTeacherCourseService service) {
        TeacherScheduleWeekDTO week5 = week(service, SPRING, 5);

        require(week5.getEntries().isEmpty(), "week 5 must be the no-class week");
        require(week5.getDates().size() == CALENDAR_DAYS_PER_WEEK,
                "a no-class week must still publish seven dates");
        require(!week5.getPeriods().isEmpty(),
                "a no-class week must still publish the period template");
        require(week(service, SPRING, 10).getEntries().isEmpty(),
                "every week other than 8 and 9 must be empty");
        require(week(service, SPRING, 9).getEntries().size() == 1,
                "week 9 must hold exactly the moved target");
    }

    private static void nullWeekResolvesToCurrentWeekOrMinimum(MockTeacherCourseService service) {
        TeacherScheduleWeekDTO spring = week(service, SPRING, null);
        require(spring.getWeek() == SPRING_CURRENT_WEEK,
                "a null week inside the term must resolve to currentWeek, saw " + spring.getWeek());
        require(Integer.valueOf(SPRING_CURRENT_WEEK).equals(spring.getCurrentWeek()),
                "the resolved week must still report currentWeek");

        TeacherScheduleWeekDTO autumn = week(service, AUTUMN, null);
        require(autumn.getWeek() == MIN_WEEK,
                "a null week outside the term must resolve to minWeek, saw " + autumn.getWeek());
        require(autumn.getCurrentWeek() == null,
                "the outside-term resolution must keep currentWeek null");
    }

    private static void invalidWeeksAreRejectedAndUnknownTermsAreNotFound(
            MockTeacherCourseService service) {
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, SPRING, 0),
                "week 0 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, SPRING, 17),
                "week 17 must be BAD_REQUEST");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, SPRING, -3),
                "a negative week must be BAD_REQUEST");
        requireMessageContains(MessageCode.BAD_REQUEST,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, SPRING, 17), "week");

        requireCode(MessageCode.NOT_FOUND,
                () -> service.loadTeachingSchedule(2024, SPRING, 8),
                "an unknown academic year must be NOT_FOUND");
        requireCode(MessageCode.NOT_FOUND,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, 1, 8),
                "an unknown semester must be NOT_FOUND");
        requireCode(MessageCode.NOT_FOUND,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, 99, 8),
                "a semester outside 1..3 must be NOT_FOUND, never a crash");
        requireCode(MessageCode.BAD_REQUEST,
                () -> service.loadTeachingSchedule(ACADEMIC_YEAR, SPRING, 99),
                "an out-of-range week on a known term must be BAD_REQUEST, never NOT_FOUND");
    }

    private static void scheduleListsAreImmutable(MockTeacherCourseService service) {
        TeacherScheduleWeekDTO week = week(service, SPRING, 8);

        requireUnmodifiable(() -> week.getDates().add(null), "schedule dates");
        requireUnmodifiable(() -> week.getPeriods().add(null), "schedule periods");
        requireUnmodifiable(() -> week.getEntries().add(null), "schedule entries");
        requireUnmodifiable(() -> week.getEntries().set(0, week.getEntries().get(0)),
                "schedule entries replacement");
        requireUnmodifiable(() -> week.getPeriods().set(0, week.getPeriods().get(0)),
                "schedule periods replacement");
    }

    private static TeacherScheduleWeekDTO week(MockTeacherCourseService service, int semester,
            Integer week) {
        return service.loadTeachingSchedule(ACADEMIC_YEAR, semester, week).join();
    }

    private static void requireCode(MessageCode expected, Supplier<CompletableFuture<?>> call,
            String message) {
        TeacherCourseServiceException failure = failureOf(call, message);
        require(failure.getCode() == expected,
                message + "; expected " + expected + " but was " + failure.getCode());
    }

    private static void requireMessageContains(MessageCode expected,
            Supplier<CompletableFuture<?>> call, String fragment) {
        TeacherCourseServiceException failure =
                failureOf(call, "a rejection must carry a message about " + fragment);
        require(failure.getCode() == expected,
                "the rejection must use " + expected + " but was " + failure.getCode());
        require(failure.getMessage() != null && failure.getMessage().contains(fragment),
                "the rejection must mention " + fragment + ", saw " + failure.getMessage());
    }

    /** 失败必须由异常完成的 Future 表达：同步抛出会在这里被单独识别。 */
    private static TeacherCourseServiceException failureOf(Supplier<CompletableFuture<?>> call,
            String message) {
        CompletableFuture<?> future;
        try {
            future = call.get();
        } catch (RuntimeException synchronous) {
            throw new AssertionError(
                    message + "; the mock must fail the future, not throw synchronously",
                    synchronous);
        }
        try {
            future.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof TeacherCourseServiceException error) {
                return error;
            }
            throw new AssertionError(message + "; unexpected cause " + failure.getCause(),
                    failure.getCause());
        }
        throw new AssertionError(message + "; expected a failure");
    }

    private static void requireUnmodifiable(Runnable mutation, String label) {
        try {
            mutation.run();
            throw new AssertionError(label + " must reject mutation");
        } catch (UnsupportedOperationException expected) {
            // Expected immutable contract.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
