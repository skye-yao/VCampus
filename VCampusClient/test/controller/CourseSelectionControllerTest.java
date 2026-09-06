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

        require(controller.tryBeginOfferingOperation(1001L),
                "first offering operation must acquire the lock");
        require(controller.isOfferingOperationPending(1001L),
                "offering lock must survive independently of a rendered button");
        require(!controller.tryBeginOfferingOperation(1001L),
                "duplicate offering operation must not acquire the lock");
        controller.finishOfferingOperation(1001L);
        require(!controller.isOfferingOperationPending(1001L),
                "offering lock must clear when the operation completes");

        require(controller.tryBeginPlanConfirmation(),
                "first plan confirmation must acquire the lock");
        require(controller.isPlanConfirmationPending(),
                "plan confirmation lock must survive independent rerenders");
        require(!controller.tryBeginPlanConfirmation(),
                "duplicate plan confirmation must not acquire the lock");
        controller.finishPlanConfirmation();
        require(!controller.isPlanConfirmationPending(),
                "plan confirmation lock must clear when the operation completes");

        long firstLoad = controller.nextLoadGeneration();
        long secondLoad = controller.nextLoadGeneration();
        require(!controller.isCurrentLoadGeneration(firstLoad),
                "older load completion must be superseded");
        require(controller.isCurrentLoadGeneration(secondLoad),
                "latest load completion must remain current");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
