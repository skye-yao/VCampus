package controller;

import java.util.List;
import model.course.CourseOfferingView;
import model.course.SelectionStatus;
import service.MockCourseService;

public final class CourseSelectionControllerTest {
    public static void main(String[] args) throws Exception {
        CourseSelectionController controller = new CourseSelectionController();
        List<CourseOfferingView> source = new MockCourseService().loadOfferings().get();
        require(controller.filterCourses(source, null, "数据", "全部").size() == 1,
                "keyword must match 数据结构 only");
        require(controller.filterCourses(source, null, "", "必修").stream()
                        .allMatch(course -> "必修".equals(course.getCourseType())),
                "type filter must contain required courses only");
        List<CourseOfferingView> planned =
                controller.filterCourses(source, SelectionStatus.PLANNED, "", "全部");
        require(planned.size() == 1 && "操作系统".equals(planned.get(0).getCourseName()),
                "planned tab must contain 操作系统 only");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
