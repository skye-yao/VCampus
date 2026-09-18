package model.course;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CourseModelTest {
    public static void main(String[] args) {
        CourseTermView term = new CourseTermView(2026, 1, "2026-2027 秋学期");
        require("2026-2027 秋学期".equals(term.toString()),
                "term text must use its display name");

        List<CourseTeacherView> teachers = new ArrayList<>();
        teachers.add(new CourseTeacherView("T001", "张老师"));
        List<CourseMeetingView> meetings = new ArrayList<>();
        meetings.add(new CourseMeetingView(
                2, 3, 4, 1, 16, "ALL", "教四-201",
                "2026-09-08T02:00:00Z", "2026-09-08T03:40:00Z"));
        CourseOfferingView offering = new CourseOfferingView(
                1001L, "CS203-2026-2-A", 101L, teachers, meetings, 96, 120,
                SelectionStatus.PLANNED, null, null, null);
        teachers.clear();
        meetings.clear();

        require(offering.getTeachers().size() == 1,
                "offering must defensively copy teachers");
        require(offering.getMeetings().size() == 1,
                "offering must defensively copy meetings");
        require(Instant.parse("2026-09-08T02:00:00Z").equals(
                        offering.getMeetings().get(0).getStartsAt()),
                "meeting UTC boundary must parse to Instant");
        requireImmutable(offering.getTeachers(), "teacher list must be immutable");
        requireImmutable(offering.getMeetings(), "meeting list must be immutable");

        CourseView course = new CourseView(
                101L, "CS203", "数据结构", "必修", 4.0, 64,
                "线性表、树和图", "程序设计基础");
        CourseSelectionItemView item = new CourseSelectionItemView(course, offering);
        require(item.getStatus() == SelectionStatus.PLANNED,
                "selection item status must delegate to offering");

        CoursePlanSnapshotView snapshot = new CoursePlanSnapshotView(
                term, List.of(item), List.of(), List.of());
        require(snapshot.find(1001L) == item,
                "snapshot find must search all state lists");
        require(snapshot.find(9999L) == null,
                "snapshot find must return null for an unknown offering");
        requireImmutable(snapshot.getPlanItems(), "plan list must be immutable");
        requireImmutable(snapshot.getWaitlistItems(), "waitlist list must be immutable");
        requireImmutable(snapshot.getEnrolledItems(), "enrolled list must be immutable");

        CourseMutationResultView result = new CourseMutationResultView(
                "operation-1", item, SelectionStatus.PLANNED,
                "PLANNED", "已加入计划", snapshot);
        require(result.getItem() == item && result.getSnapshot() == snapshot,
                "mutation result must expose affected item and complete snapshot");
        require(result.getFinalState() == SelectionStatus.PLANNED,
                "mutation result must expose explicit final state");

        CoursePushEventView event = new CoursePushEventView(
                "event-1", CoursePushEventType.WAITLIST_OFFERED, term, 1001L,
                "2026-09-10T01:00:00Z", "2026-09-10T01:05:00Z", "候补到位");
        require(event.getOccurredAt().equals(Instant.parse("2026-09-10T01:00:00Z")),
                "push event occurrence time must parse as UTC");
        require(event.getExpiresAt().equals(Instant.parse("2026-09-10T01:05:00Z")),
                "push event expiry must parse as UTC");

        legacyScheduleEntryKeepsNormalDisplayDefaults();
        scheduleEntryCarriesAdjustmentDisplay();

        System.out.println("CourseModelTest: PASS");
    }

    private static void legacyScheduleEntryKeepsNormalDisplayDefaults() {
        ScheduleEntryView legacy = new ScheduleEntryView(
                1001L, "2026-2027 秋学期", "CS203", "数据结构", "张老师", "教四-201",
                2, 3, 2, 1, 16);

        require(legacy.getDisplayKind() == ScheduleDisplayKind.NORMAL,
                "the 11-argument schedule entry must default to the NORMAL display kind");
        require(legacy.getAdjustmentId() == null,
                "the 11-argument schedule entry must leave the adjustment ID null");
        require(legacy.getOriginalScheduleText() == null,
                "the 11-argument schedule entry must leave the original schedule text null");
        require(legacy.getAdjustedScheduleText() == null,
                "the 11-argument schedule entry must leave the adjusted schedule text null");
        require(legacy.getAdjustmentReason() == null,
                "the 11-argument schedule entry must leave the adjustment reason null");
        require(legacy.getOfferingId() == 1001L && legacy.getLocation().equals("教四-201"),
                "the 11-argument schedule entry must keep its original slot fields");
        require(legacy.isActiveInWeek(1) && !legacy.isActiveInWeek(17),
                "the 11-argument schedule entry must keep its week activity window");
    }

    private static void scheduleEntryCarriesAdjustmentDisplay() {
        ScheduleDisplayKind[] expectedKinds = {
                ScheduleDisplayKind.NORMAL,
                ScheduleDisplayKind.ADJUSTED_ORIGINAL,
                ScheduleDisplayKind.ADJUSTED_TARGET};
        require(Arrays.equals(expectedKinds, ScheduleDisplayKind.values()),
                "schedule display kind must expose exactly NORMAL, ADJUSTED_ORIGINAL, ADJUSTED_TARGET");

        ScheduleEntryView target = new ScheduleEntryView(
                1001L, "2026-2027 秋学期", "CS203", "数据结构", "张老师", "教四-305",
                5, 5, 2, 6, 6, ScheduleDisplayKind.ADJUSTED_TARGET,
                "9007199254740993", "第6周 星期五 第3-4节 教四-201",
                "第6周 星期五 第5-6节 教四-305", "教师出差调课");

        require(target.getDisplayKind() == ScheduleDisplayKind.ADJUSTED_TARGET,
                "an adjusted target entry must expose the target display kind");
        require("9007199254740993".equals(target.getAdjustmentId()),
                "an adjusted entry must keep the adjustment ID as exact text");
        require("第6周 星期五 第3-4节 教四-201".equals(target.getOriginalScheduleText()),
                "an adjusted entry must expose the original schedule text");
        require("第6周 星期五 第5-6节 教四-305".equals(target.getAdjustedScheduleText()),
                "an adjusted entry must expose the adjusted schedule text");
        require("教师出差调课".equals(target.getAdjustmentReason()),
                "an adjusted entry must expose the adjustment reason");
        require(target.getLocation().equals("教四-305") && target.getDayOfWeek() == 5
                        && target.getStartPeriod() == 5,
                "an adjusted target entry must expose the new slot fields");

        ScheduleEntryView original = new ScheduleEntryView(
                1001L, "2026-2027 秋学期", "CS203", "数据结构", "张老师", "教四-201",
                2, 3, 2, 6, 6, ScheduleDisplayKind.ADJUSTED_ORIGINAL,
                "9007199254740993", "第6周 星期五 第3-4节 教四-201",
                "第6周 星期五 第5-6节 教四-305", "教师出差调课");

        require(original.getDisplayKind() == ScheduleDisplayKind.ADJUSTED_ORIGINAL,
                "an original occurrence entry must keep its own display kind");
        require(original.getLocation().equals("教四-201") && original.getDayOfWeek() == 2,
                "an original occurrence entry must keep the original slot fields");
    }

    private static void requireImmutable(List<?> values, String message) {
        try {
            values.clear();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected immutable view collection.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
