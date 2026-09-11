package model.course;

import java.time.Instant;
import java.util.ArrayList;
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
                1001L, 101L, teachers, meetings, 96, 120,
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

        System.out.println("CourseModelTest: PASS");
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
