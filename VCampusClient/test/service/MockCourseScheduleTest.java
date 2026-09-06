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
    }

    private static boolean hasOffering(List<ScheduleEntryView> entries, long id) {
        return entries.stream().anyMatch(entry -> entry.getOfferingId() == id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
