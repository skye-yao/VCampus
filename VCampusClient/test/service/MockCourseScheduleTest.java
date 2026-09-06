package service;

import java.util.List;
import model.course.CourseNoticeView;
import model.course.ScheduleEntryView;

public final class MockCourseScheduleTest {
    public static void main(String[] args) throws Exception {
        MockCourseService service = new MockCourseService();
        List<ScheduleEntryView> initial = service.loadSchedule("2026-2027 秋学期", 3).get();
        require(hasOffering(initial, 1005L) && hasOffering(initial, 1006L),
                "initial enrolled courses must appear");
        require(service.loadSchedule("2025-2026 秋学期", 3).get().isEmpty(),
                "schedule from another term must not appear");
        require(service.loadSchedule("2026-2027 秋学期", 17).get().isEmpty(),
                "schedule outside the active week range must not appear");
        requireImmutable(initial, "schedule results must be immutable");
        service.addToPlan(1001L).get();
        service.confirmPlan().get();
        require(hasOffering(service.loadSchedule("2026-2027 秋学期", 3).get(), 1001L),
                "new enrollment must appear");
        service.dropCourse(1005L).get();
        require(!hasOffering(service.loadSchedule("2026-2027 秋学期", 3).get(), 1005L),
                "dropped course must disappear");
        List<CourseNoticeView> notices =
                service.loadNotices("2026-2027 秋学期", 13).get();
        require(notices.stream().anyMatch(n -> n.getTitle().contains("数据结构")),
                "week 13 adjustment notice expected");
        require(service.loadNotices("2025-2026 秋学期", 13).get().isEmpty(),
                "notices from another term must not appear");
        require(service.loadNotices("2026-2027 秋学期", 12).get().isEmpty(),
                "notices from another week must not appear");
        requireImmutable(notices, "notice results must be immutable");
        System.out.println("MockCourseScheduleTest: PASS");
    }

    private static boolean hasOffering(List<ScheduleEntryView> entries, long id) {
        return entries.stream().anyMatch(entry -> entry.getOfferingId() == id);
    }

    private static void requireImmutable(List<?> values, String message) {
        try {
            values.clear();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected immutable service snapshot.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
