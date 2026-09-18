package service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import model.course.CourseMutationResultView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.SelectionStatus;

public final class MockCourseServiceTest {
    public static void main(String[] args) throws Exception {
        testTwoLevelCatalogAndSixStates();
        testExplicitFullAndWaitlistTransitions();
        testSelectingOneOfferingPreservesSiblingPlan();
        testOperationReplayIsIdempotent();
        testIllegalTransition();
        testReturnedCollectionsAreImmutable();
        testSubscriptionAndAckStayOffline();
        testUnknownTermAncillaryDataIsEmpty();
        System.out.println("MockCourseServiceTest: PASS");
    }

    private static void testTwoLevelCatalogAndSixStates() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        List<CourseView> courses = service.loadCourses(term).get();
        require(courses.size() >= 2, "course catalog required");
        require(service.loadCourseOfferings(term, courses.get(0).getCourseId()).get().size() >= 2,
                "one course must expose multiple offerings");

        Set<SelectionStatus> states = new HashSet<>();
        Set<String> offeringCodes = new HashSet<>();
        int offeringsSeen = 0;
        for (CourseView course : courses) {
            for (CourseOfferingView offering :
                    service.loadCourseOfferings(term, course.getCourseId()).get()) {
                states.add(offering.getSelectionStatus());
                offeringsSeen++;
                offeringCodes.add(offering.getOfferingCode());
                // 全部页签的教学班行标题用教学班代码，因此每个假体教学班都得有一个：形态与演示种子
                // 一致（课程代码 + 学年 + 学期 + 班号），且从代码能反推出课程。
                require(offering.getOfferingCode() != null
                                && offering.getOfferingCode()
                                .startsWith(course.getCourseCode() + "-"),
                        "mock offering must carry a code derived from its course code, observed "
                                + offering.getOfferingCode() + " for " + course.getCourseCode());
            }
        }
        require(states.containsAll(Set.of(SelectionStatus.values())),
                "mock catalog must expose all six selection states");
        require(offeringCodes.size() == offeringsSeen,
                "mock offering codes must be unique, observed " + offeringCodes.size()
                        + " distinct codes for " + offeringsSeen + " offerings");
    }

    private static void testExplicitFullAndWaitlistTransitions() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        long fullOffering = 1002L;

        CourseMutationResultView planned = service.addToPlan(
                term, fullOffering, "plan-full").get();
        require(planned.getSnapshot().find(fullOffering).getStatus() == SelectionStatus.PLANNED,
                "full offering first enters the plan");
        CourseMutationResultView full = service.selectOffering(
                term, fullOffering, "select-full").get();
        require(full.getFinalState() == SelectionStatus.FULL,
                "selecting a full offering must return FULL");
        require(full.getSnapshot().find(fullOffering).getStatus() == SelectionStatus.FULL,
                "full selection must remain in plan rather than auto-waitlist");
        CourseMutationResultView waitlisted = service.joinWaitlist(
                term, fullOffering, "join-full").get();
        require(waitlisted.getSnapshot().find(fullOffering).getStatus()
                        == SelectionStatus.WAITLISTED,
                "joining the waitlist must be explicit");
    }

    private static void testSelectingOneOfferingPreservesSiblingPlan() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        service.addToPlan(term, 1001L, "plan-sibling-a").get();
        CourseMutationResultView selected = service.selectOffering(
                term, 1001L, "select-sibling-a").get();
        require(selected.getSnapshot().find(1001L).getStatus() == SelectionStatus.ENROLLED,
                "selected offering must enroll when capacity exists");
        require(selected.getSnapshot().find(1007L).getStatus() == SelectionStatus.PLANNED,
                "selecting one offering must preserve another plan for the same course");
    }

    private static void testOperationReplayIsIdempotent() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        CourseMutationResultView first = service.addToPlan(term, 1001L, "same-operation").get();
        CourseMutationResultView replay = service.addToPlan(term, 1001L, "same-operation").get();
        require(first == replay, "duplicate operation ID must return its stored result");

        CourseMutationResultView selected = service.selectOffering(
                term, 1001L, "same-select").get();
        int enrolledCount = selected.getItem().getOffering().getEnrolledCount();
        CourseMutationResultView selectedReplay = service.selectOffering(
                term, 1001L, "same-select").get();
        require(selected == selectedReplay, "selection replay must return the stored result");
        require(selectedReplay.getItem().getOffering().getEnrolledCount() == enrolledCount,
                "selection replay must not increment enrollment again");
    }

    private static void testIllegalTransition() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        try {
            service.dropOffering(term, 1001L, "invalid-drop").get();
            throw new AssertionError("dropping an available offering must fail");
        } catch (ExecutionException expected) {
            require(expected.getCause() instanceof IllegalStateException,
                    "state error expected");
        }
    }

    private static void testReturnedCollectionsAreImmutable() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = defaultTerm(service);
        requireImmutable(service.loadTerms().get(), "term list must be immutable");
        requireImmutable(service.loadCourses(term).get(), "course list must be immutable");
        requireImmutable(service.loadCourseOfferings(term, 101L).get(),
                "offering list must be immutable");
        CoursePlanSnapshotView snapshot = service.loadSelectionSnapshot(term).get();
        requireImmutable(snapshot.getPlanItems(), "snapshot plan must be immutable");
    }

    private static void testSubscriptionAndAckStayOffline() throws Exception {
        MockCourseService service = new MockCourseService();
        service.ackCourseEvent("event-1").get();
        CourseSubscription subscription = service.subscribe(event -> { });
        subscription.close();
        subscription.close();
    }

    private static void testUnknownTermAncillaryDataIsEmpty() throws Exception {
        MockCourseService service = new MockCourseService();
        CourseTermView term = new CourseTermView(2024, 2, "2024-2025 春学期");
        require(service.loadSchedule(term, 5).get().getEntries().isEmpty(),
                "schedule fixtures belong to another term");
        require(service.loadNotices(term, 5).get().isEmpty(),
                "notice fixtures belong to another term");
        GradeSummaryView grades = service.loadGrades(term).get();
        require(grades.getRecords().isEmpty(), "unknown term grade records must be empty");
    }

    private static CourseTermView defaultTerm(MockCourseService service) throws Exception {
        return service.loadTerms().get().get(0);
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
