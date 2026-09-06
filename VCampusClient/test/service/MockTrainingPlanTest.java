package service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import model.course.TrainingPlanCourseView;
import model.course.TrainingPlanGroupView;

public final class MockTrainingPlanTest {
    public static void main(String[] args) throws Exception {
        List<TrainingPlanGroupView> groups =
                new MockCourseService().loadTrainingPlan().get();
        Set<String> names = groups.stream()
                .map(TrainingPlanGroupView::getName)
                .collect(Collectors.toSet());
        require(groups.size() == 4, "four plan groups expected");
        require(names.containsAll(Set.of("必修课程", "限选课程", "选修课程", "通选课程")),
                "all plan categories expected");
        require(groups.stream().allMatch(group ->
                        group.getEarnedCredits() <= group.getRequiredCredits()),
                "earned credits cannot exceed required credits");
        require(groups.stream().allMatch(group ->
                        group.getEarnedCredits() == group.getCourses().stream()
                                .filter(course -> "已修".equals(course.getCompletionStatus()))
                                .mapToDouble(course -> course.getCredit()).sum()),
                "group totals must equal completed course credits");
        require(groups.stream().allMatch(group -> group.getCourses().size() >= 2),
                "each group must contain at least two courses");
        require(groups.stream().flatMap(group -> group.getCourses().stream())
                        .allMatch(MockTrainingPlanTest::isCompleteCourse),
                "every course must provide valid deterministic display data");

        expectUnsupported(groups::clear, "training plan list must be immutable");
        expectUnsupported(() -> groups.get(0).getCourses().clear(),
                "training plan group courses must be immutable");
        System.out.println("MockTrainingPlanTest: PASS");
    }

    private static boolean isCompleteCourse(TrainingPlanCourseView course) {
        return course.getCourseCode() != null && !course.getCourseCode().isBlank()
                && course.getCourseName() != null && !course.getCourseName().isBlank()
                && course.getCredit() > 0.0
                && Set.of("已修", "在修", "未修").contains(course.getCompletionStatus());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected immutable service snapshot.
        }
    }
}
