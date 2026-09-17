package controller;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.ButtonType;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleWeekView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import service.CoursePushListener;
import service.CourseService;
import service.CourseSubscription;

public final class CourseManagementControllerTest {
    public static void main(String[] args) throws Exception {
        navigatingBackReleasesSelectionCoordinator();
        System.out.println("CourseManagementControllerTest: PASS");
    }

    private static void navigatingBackReleasesSelectionCoordinator() throws Exception {
        RecordingCourseService service = new RecordingCourseService();
        CourseSelectionController selection = new CourseSelectionController(
                service, (title, message) -> ButtonType.OK,
                (title, message) -> null, (title, message) -> { }, Runnable::run);
        CourseManagementController management = new CourseManagementController();
        setField(management, "selectionPageController", selection);

        AtomicInteger backCalls = new AtomicInteger();
        AtomicInteger closesWhenNavigating = new AtomicInteger(-1);
        setField(management, "backAction", (Runnable) () -> {
            closesWhenNavigating.set(service.closes.get());
            backCalls.incrementAndGet();
        });

        require(service.subscriptions.get() == 1,
                "selection controller must subscribe to course push exactly once");
        require(service.closes.get() == 0, "subscription must start open");

        management.handleBack();
        require(service.closes.get() == 1,
                "returning to the main menu must release the selection coordinator");
        require(closesWhenNavigating.get() == 1,
                "the coordinator must be released before leaving the course area");
        require(backCalls.get() == 1, "back navigation must still run");

        management.handleBack();
        require(service.closes.get() == 1, "releasing course pages must be idempotent");
        require(backCalls.get() == 2, "back navigation must still run on repeat");
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class RecordingCourseService implements CourseService {
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        @Override public CompletableFuture<List<CourseTermView>> loadTerms() {
            return CompletableFuture.completedFuture(List.of(
                    new CourseTermView(2026, 1, "2026-2027 秋学期")));
        }

        @Override public CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
                CourseTermView term, long courseId) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
                CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> addToPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> removeFromPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> selectOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> joinWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> cancelWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
                CourseTermView term, long offeringId, String operationId,
                WaitlistDecision decision) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<CourseMutationResultView> dropOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> ackCourseEvent(String eventId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CourseSubscription subscribe(CoursePushListener listener) {
            subscriptions.incrementAndGet();
            return closes::incrementAndGet;
        }

        @Override public CompletableFuture<ScheduleWeekView> loadSchedule(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(
                    new ScheduleWeekView(1, List.of(), List.of(), List.of()));
        }

        @Override public CompletableFuture<List<CourseNoticeView>> loadNotices(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
