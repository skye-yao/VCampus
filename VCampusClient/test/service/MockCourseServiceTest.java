package service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import model.course.CourseOfferingView;
import model.course.GradeSummaryView;
import model.course.SelectionStatus;

public final class MockCourseServiceTest {
    public static void main(String[] args) throws Exception {
        testInitialStates();
        testConfirmPlanRoutesByCapacity();
        testIllegalTransition();
        testReturnedListIsUnmodifiable();
        testJoinWaitlistRequiresFullAvailableOffering();
        testEnrollmentAndWaitlistTransitionsUpdateCounts();
        testUnknownTermAncillaryDataIsEmpty();
    }

    private static void testInitialStates() throws Exception {
        MockCourseService service = new MockCourseService();
        List<CourseOfferingView> courses = service.loadOfferings().get();
        require(courses.size() == 6, "six deterministic courses expected");
        require(statusOf(courses, 1002L) == SelectionStatus.PLANNED, "OS starts in plan");
        require(statusOf(courses, 1003L) == SelectionStatus.WAITLISTED, "HCI starts waitlisted");
    }

    private static void testConfirmPlanRoutesByCapacity() throws Exception {
        MockCourseService service = new MockCourseService();
        service.addToPlan(1001L).get();
        service.confirmPlan().get();
        List<CourseOfferingView> courses = service.loadOfferings().get();
        require(statusOf(courses, 1001L) == SelectionStatus.ENROLLED, "course with space enrolls");
        require(statusOf(courses, 1002L) == SelectionStatus.WAITLISTED, "full course waitlists");
    }

    private static void testIllegalTransition() throws Exception {
        MockCourseService service = new MockCourseService();
        try {
            service.dropCourse(1001L).get();
            throw new AssertionError("dropping an available course must fail");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof IllegalStateException, "state error expected");
        }
    }

    private static void testReturnedListIsUnmodifiable() throws Exception {
        List<CourseOfferingView> courses = new MockCourseService().loadOfferings().get();
        try {
            courses.clear();
            throw new AssertionError("service result must be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void testJoinWaitlistRequiresFullAvailableOffering() throws Exception {
        MockCourseService service = new MockCourseService();
        try {
            service.joinWaitlist(1001L).get();
            throw new AssertionError("available course with spare capacity must be planned");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof IllegalStateException, "state error expected");
        }

        service.leaveWaitlist(1003L).get();
        CourseOfferingView waitlisted = service.joinWaitlist(1003L).get();
        require(waitlisted.getSelectionStatus() == SelectionStatus.WAITLISTED,
                "full available course may join waitlist");
        require(waitlisted.getEnrolledCount() == 60, "joining waitlist must not change enrollment count");
    }

    private static void testEnrollmentAndWaitlistTransitionsUpdateCounts() throws Exception {
        MockCourseService service = new MockCourseService();
        service.addToPlan(1001L).get();
        service.confirmPlan().get();
        CourseOfferingView enrolled = offeringOf(service.loadOfferings().get(), 1001L);
        require(enrolled.getEnrolledCount() == 97, "confirming plan with space increments enrollment count");

        CourseOfferingView dropped = service.dropCourse(1001L).get();
        require(dropped.getEnrolledCount() == 96, "dropping enrolled course decrements enrollment count");

        service.confirmPlan().get();
        CourseOfferingView fullWaitlisted = offeringOf(service.loadOfferings().get(), 1002L);
        require(fullWaitlisted.getEnrolledCount() == 100, "waitlisting full plan must not change enrollment count");
        CourseOfferingView available = service.leaveWaitlist(1002L).get();
        require(available.getEnrolledCount() == 100, "leaving waitlist must not change enrollment count");
    }

    private static void testUnknownTermAncillaryDataIsEmpty() throws Exception {
        MockCourseService service = new MockCourseService();
        String term = "2024-2025-2";

        require(service.loadSchedule(term, 5).get().isEmpty(),
                "schedule fixtures belong to a later task");
        require(service.loadNotices(term, 5).get().isEmpty(),
                "notice fixtures belong to a later task");

        GradeSummaryView grades = service.loadGrades(term).get();
        require(grades.getTermGpa() == 0.0, "term GPA must be zero before grade fixtures exist");
        require(grades.getTermAverage() == 0.0,
                "term average must be zero before grade fixtures exist");
        require(grades.getCumulativeAverage() == 0.0,
                "cumulative average must be zero before grade fixtures exist");
        require(grades.getCumulativeGpa() == 0.0,
                "cumulative GPA must be zero before grade fixtures exist");
        require(grades.getRecords().isEmpty(), "unknown term grade records must be empty");
    }

    private static SelectionStatus statusOf(List<CourseOfferingView> courses, long id) {
        return offeringOf(courses, id).getSelectionStatus();
    }

    private static CourseOfferingView offeringOf(List<CourseOfferingView> courses, long id) {
        return courses.stream().filter(c -> c.getOfferingId() == id).findFirst().orElseThrow();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
