package controller;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.TrainingPlanGroupView;
import service.CourseService;

public final class TrainingPlanControllerTest {
    public static void main(String[] args) {
        aggregatesCreditsAndHandlesZeroRequiredCredits();
        completionsReturnThroughFxExecutor();
        staleCompletionsAreIgnored();
        currentFailureClearsVisibleStateThroughRefresh();
        System.out.println("TrainingPlanControllerTest: PASS");
    }

    private static void aggregatesCreditsAndHandlesZeroRequiredCredits() {
        TrainingPlanController.PlanTotals totals = TrainingPlanController.aggregate(List.of(
                group("A", 10.0, 4.0),
                group("B", 6.0, 2.0)));
        require(totals.getEarnedCredits() == 6.0,
                "aggregate earned credits must be summed");
        require(totals.getRequiredCredits() == 16.0,
                "aggregate required credits must be summed");
        require(totals.getProgress() == 0.375,
                "aggregate progress must use earned divided by required");

        TrainingPlanController.PlanTotals empty =
                TrainingPlanController.aggregate(List.of(group("Zero", 0.0, 0.0)));
        require(empty.getProgress() == 0.0,
                "zero required credits must produce zero progress");
    }

    private static void completionsReturnThroughFxExecutor() {
        ControlledCourseService service = new ControlledCourseService();
        Deque<Runnable> fxActions = new ArrayDeque<>();
        TrainingPlanController controller = new TrainingPlanController(
                service, (title, message) -> { }, fxActions::addLast);
        AtomicReference<List<TrainingPlanGroupView>> rendered = new AtomicReference<>();

        CompletableFuture<List<TrainingPlanGroupView>> pending = new CompletableFuture<>();
        service.planResults.addLast(pending);
        controller.requestTrainingPlan(rendered::set, error -> { });
        pending.complete(List.of(group("FX", 2.0, 1.0)));

        require(rendered.get() == null,
                "completion must not mutate view state before the FX executor runs");
        fxActions.removeFirst().run();
        require("FX".equals(rendered.get().get(0).getName()),
                "completion must be delivered by the FX executor");
    }

    private static void staleCompletionsAreIgnored() {
        ControlledCourseService service = new ControlledCourseService();
        AtomicReference<String> renderedName = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();
        TrainingPlanController controller = new TrainingPlanController(
                service, (title, message) -> { }, Runnable::run);

        CompletableFuture<List<TrainingPlanGroupView>> older = new CompletableFuture<>();
        CompletableFuture<List<TrainingPlanGroupView>> latest = new CompletableFuture<>();
        service.planResults.addLast(older);
        service.planResults.addLast(latest);
        controller.requestTrainingPlan(
                groups -> renderedName.set(groups.get(0).getName()),
                error -> errors.incrementAndGet());
        controller.requestTrainingPlan(
                groups -> renderedName.set(groups.get(0).getName()),
                error -> errors.incrementAndGet());

        latest.complete(List.of(group("Latest", 2.0, 1.0)));
        older.complete(List.of(group("Older", 2.0, 1.0)));
        require("Latest".equals(renderedName.get()),
                "stale success must not replace the latest plan");

        CompletableFuture<List<TrainingPlanGroupView>> staleFailure =
                new CompletableFuture<>();
        CompletableFuture<List<TrainingPlanGroupView>> newerSuccess =
                new CompletableFuture<>();
        service.planResults.addLast(staleFailure);
        service.planResults.addLast(newerSuccess);
        controller.requestTrainingPlan(
                groups -> renderedName.set(groups.get(0).getName()),
                error -> errors.incrementAndGet());
        controller.requestTrainingPlan(
                groups -> renderedName.set(groups.get(0).getName()),
                error -> errors.incrementAndGet());

        newerSuccess.complete(List.of(group("Newer", 2.0, 1.0)));
        staleFailure.completeExceptionally(new IllegalStateException("stale failure"));
        require(errors.get() == 0, "stale failure must not report an error");
        require("Newer".equals(renderedName.get()),
                "stale failure must preserve the latest plan");
    }

    private static void currentFailureClearsVisibleStateThroughRefresh() {
        ControlledCourseService service = new ControlledCourseService();
        CompletableFuture<List<TrainingPlanGroupView>> currentFailure =
                new CompletableFuture<>();
        service.planResults.addLast(currentFailure);

        RecordingPlanDisplay display = new RecordingPlanDisplay();
        display.earnedCredits = "18";
        display.requiredCredits = "20";
        display.progress = 0.9;
        display.groups = List.of(group("Stale", 20.0, 18.0));
        display.stateText = "旧状态";
        AtomicInteger errors = new AtomicInteger();
        AtomicReference<String> errorTitle = new AtomicReference<>();
        TrainingPlanController controller = new TrainingPlanController(
                service,
                (title, message) -> {
                    errorTitle.set(title);
                    errors.incrementAndGet();
                },
                Runnable::run,
                display);

        controller.refresh();
        currentFailure.completeExceptionally(new IllegalStateException("current failure"));

        require("--".equals(display.earnedCredits),
                "current failure must clear earned-credit text");
        require("--".equals(display.requiredCredits),
                "current failure must clear required-credit text");
        require(display.progress == 0.0,
                "current failure must reset overall progress");
        require(display.groups.isEmpty(),
                "current failure must clear rendered group rows");
        require("培养方案加载失败，请刷新重试".equals(display.stateText),
                "current failure must replace stale state text with the error state");
        require(errors.get() == 1, "current failure must report exactly one error");
        require("培养方案加载失败".equals(errorTitle.get()),
                "current failure must use the training-plan error title");
    }

    private static TrainingPlanGroupView group(String name,
            double requiredCredits, double earnedCredits) {
        return new TrainingPlanGroupView(
                name, requiredCredits, earnedCredits, Collections.emptyList());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<List<TrainingPlanGroupView>>> planResults =
                new ArrayDeque<>();

        @Override
        public CompletableFuture<List<CourseOfferingView>> loadOfferings() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<CourseOfferingView> addToPlan(long offeringId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseOfferingView> removeFromPlan(long offeringId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<CourseOfferingView>> confirmPlan() {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<CourseOfferingView> joinWaitlist(long offeringId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseOfferingView> leaveWaitlist(long offeringId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<CourseOfferingView> dropCourse(long offeringId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<ScheduleEntryView>> loadSchedule(String term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<List<CourseNoticeView>> loadNotices(String term, int week) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        @Override
        public CompletableFuture<GradeSummaryView> loadGrades(String term) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<TrainingPlanGroupView>> loadTrainingPlan() {
            return planResults.removeFirst();
        }
    }

    private static final class RecordingPlanDisplay
            implements TrainingPlanController.PlanDisplay {
        private String earnedCredits;
        private String requiredCredits;
        private double progress;
        private List<TrainingPlanGroupView> groups = Collections.emptyList();
        private String stateText;

        @Override
        public void apply(TrainingPlanController.PlanPresentation presentation) {
            earnedCredits = presentation.getEarnedCredits();
            requiredCredits = presentation.getRequiredCredits();
            progress = presentation.getProgress();
            groups = presentation.getGroups();
            stateText = presentation.getStateText();
        }
    }
}
