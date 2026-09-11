package service;

import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.GradeSummaryDTO;
import dto.course.TrainingPlanGroupDTO;
import util.DBUtil;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

public final class CourseQueryMySqlTest {
    private CourseQueryMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
            require(result.next() && "virtual_campus_course_test".equals(result.getString(1)),
                    "Refusing query test outside virtual_campus_course_test");
        }

        CourseQueryService service = new CourseQueryService();
        require(service.listTerms("student-alpha").stream()
                        .anyMatch(term -> term.getAcademicYear() == 2026 && term.getSemester() == 2),
                "student must see seeded term");
        require(service.listCourses("student-alpha", 2026, 2).size() == 1,
                "catalog must enforce seeded major and cohort visibility");

        java.util.List<CourseOfferingDTO> offerings = service.listCourseOfferings(
                "student-alpha", 2026, 2, 1001L);
        require(offerings.size() == 2, "one course must expose two offerings");
        require(offerings.stream().allMatch(item -> "1001".equals(item.getCourseId())),
                "offering course IDs must be decimal strings");
        require(offerings.stream().filter(item -> "2001".equals(item.getOfferingId()))
                        .findFirst().orElseThrow().getMeetings().size() == 2,
                "published plan must expose both meetings");

        CoursePlanSnapshotDTO snapshot = service.loadSelectionSnapshot("student-alpha", 2026, 2);
        require(snapshot.getEnrolledItems().size() == 1
                        && snapshot.getPlanItems().size() == 1
                        && snapshot.getWaitlistItems().isEmpty(),
                "snapshot must partition seeded student state");
        require(service.loadSchedule("student-alpha", 2026, 2, 1).size() == 2,
                "schedule must use the selected published plan");
        require(service.loadNotices("student-alpha", 2026, 2, 1).size() == 1,
                "notices must be published and enrollment-scoped");

        GradeSummaryDTO grades = service.loadGrades("student-beta", 2026, 2);
        require(grades.getRecords().size() == 1 && grades.getTermGpa() == 4.0,
                "published seeded grade must be summarized");
        java.util.List<TrainingPlanGroupDTO> plan = service.loadTrainingPlan("student-beta");
        require(plan.size() == 1 && plan.get(0).getCourses().size() == 2
                        && plan.get(0).getEarnedCredits() == 3.0,
                "training plan must use profile and published passing grades");

        boolean notFound = false;
        try {
            service.listCourseOfferings("student-alpha", 2026, 2, 999999L);
        } catch (CourseQueryService.NotFoundException expected) {
            notFound = true;
        }
        require(notFound, "unknown course must be distinguished from an empty offering list");
        System.out.println("Course query MySQL test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
