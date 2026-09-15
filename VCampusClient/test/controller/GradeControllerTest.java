package controller;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import model.course.CourseNoticeView;
import model.course.CourseMutationResultView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import service.CoursePushListener;
import service.CourseService;
import service.CourseSubscription;

public final class GradeControllerTest {
    public static void main(String[] args) {
        require("--".equals(GradeController.formatScore(null)),
                "null score component must render as --");
        require("92".equals(GradeController.formatScore(92.0)),
                "whole score component must render without a decimal");
        require("92.5".equals(GradeController.formatScore(92.5)),
                "fractional score component must retain one decimal");

        testCompletionsReturnThroughFxExecutor();
        testStaleCompletionsAreIgnored();
        // 学生成绩页在 T6 之前不属于任何套件，静默通过很难与“没跑”区分开，所以补一行成功输出。
        System.out.println("Student grade controller test passed.");
    }

    private static void testCompletionsReturnThroughFxExecutor() {
        ControlledCourseService service = new ControlledCourseService();
        Deque<Runnable> fxActions = new ArrayDeque<>();
        GradeController controller = new GradeController(
                service, (title, message) -> { }, fxActions::addLast);
        AtomicReference<GradeSummaryView> rendered = new AtomicReference<>();

        CompletableFuture<GradeSummaryView> pending = new CompletableFuture<>();
        service.gradeResults.addLast(pending);
        controller.requestGrades(term("fx-term"), rendered::set, error -> { });
        pending.complete(summary("fx-term"));

        require(rendered.get() == null,
                "completion must not mutate view state before the FX executor runs");
        fxActions.removeFirst().run();
        require("fx-term".equals(rendered.get().getTerm()),
                "completion must be delivered by the FX executor");
    }

    private static void testStaleCompletionsAreIgnored() {
        ControlledCourseService service = new ControlledCourseService();
        AtomicReference<String> renderedTerm = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        GradeController controller = new GradeController(
                service, (title, message) -> { }, Runnable::run);

        CompletableFuture<GradeSummaryView> older = new CompletableFuture<>();
        CompletableFuture<GradeSummaryView> latest = new CompletableFuture<>();
        service.gradeResults.addLast(older);
        service.gradeResults.addLast(latest);
        controller.requestGrades(term("older"), summary -> renderedTerm.set(summary.getTerm()),
                error -> errors.incrementAndGet());
        controller.requestGrades(term("latest"), summary -> renderedTerm.set(summary.getTerm()),
                error -> errors.incrementAndGet());

        latest.complete(summary("latest"));
        older.complete(summary("older"));
        require("latest".equals(renderedTerm.get()),
                "stale success must not replace the latest grades");

        CompletableFuture<GradeSummaryView> staleFailure = new CompletableFuture<>();
        CompletableFuture<GradeSummaryView> newerSuccess = new CompletableFuture<>();
        service.gradeResults.addLast(staleFailure);
        service.gradeResults.addLast(newerSuccess);
        controller.requestGrades(term("stale"), summary -> renderedTerm.set(summary.getTerm()),
                error -> errors.incrementAndGet());
        controller.requestGrades(term("newer"), summary -> renderedTerm.set(summary.getTerm()),
                error -> errors.incrementAndGet());
        newerSuccess.complete(summary("newer"));
        staleFailure.completeExceptionally(new IllegalStateException("stale failure"));
        require(errors.get() == 0, "stale failure must not report an error");
        require("newer".equals(renderedTerm.get()),
                "stale failure must preserve the latest grades");

        CompletableFuture<GradeSummaryView> currentFailure = new CompletableFuture<>();
        service.gradeResults.addLast(currentFailure);
        controller.requestGrades(term("current"), summary -> renderedTerm.set(summary.getTerm()),
                error -> {
                    renderedTerm.set(null);
                    errors.incrementAndGet();
                });
        currentFailure.completeExceptionally(new IllegalStateException("current failure"));
        require(errors.get() == 1, "current failure must report exactly one error");
        require(renderedTerm.get() == null,
                "current failure callback must be able to clear stale grades");
    }

    private static CourseTermView term(String displayName) {
        return new CourseTermView(2026, 1, displayName);
    }

    private static GradeSummaryView summary(String term) {
        return new GradeSummaryView(term, 0.0, 0.0, 0.0, 0.0, Collections.emptyList());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<GradeSummaryView>> gradeResults =
                new ArrayDeque<>();

        @Override
        public CompletableFuture<List<CourseTermView>> loadTerms() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
                CourseTermView term, long courseId) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
                CourseTermView term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> addToPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> removeFromPlan(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> selectOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> joinWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> cancelWaitlist(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> resolveWaitlistOffer(
                CourseTermView term, long offeringId, String operationId,
                WaitlistDecision decision) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseMutationResultView> dropOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> ackCourseEvent(String eventId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CourseSubscription subscribe(CoursePushListener listener) {
            return () -> { };
        }

        @Override
        public CompletableFuture<List<ScheduleEntryView>> loadSchedule(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<List<CourseNoticeView>> loadNotices(
                CourseTermView term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<GradeSummaryView> loadGrades(CourseTermView term) {
            return gradeResults.removeFirst();
        }

        @Override
        public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
