package controller;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.ButtonType;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.GradeSummaryView;
import model.course.ScheduleEntryView;
import model.course.SelectionStatus;
import model.course.TrainingPlanGroupView;
import service.CourseService;
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

        testRenderedPendingState();
        testLoadGenerationCallbacks();
        testConfirmationCancelRendersCurrentAction();
        testTransitionFailureRendersCurrentAction();
    }

    private static void testRenderedPendingState() {
        CourseSelectionController controller = testController(new ControlledCourseService());
        AtomicBoolean detachedButtonDisabled = new AtomicBoolean();
        AtomicBoolean replacementButtonDisabled = new AtomicBoolean();
        AtomicInteger transitionCalls = new AtomicInteger();
        CompletableFuture<Void> pendingTransition = new CompletableFuture<>();
        controller.executeTransition(
                2001L,
                detachedButtonDisabled::set,
                () -> { },
                () -> {
                    transitionCalls.incrementAndGet();
                    return pendingTransition;
                });
        controller.applyOfferingOperationState(2001L, replacementButtonDisabled::set);
        require(replacementButtonDisabled.get(),
                "rendered offering action must be disabled from controller pending state");
        controller.executeTransition(
                2001L,
                replacementButtonDisabled::set,
                () -> { },
                () -> {
                    transitionCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });
        require(transitionCalls.get() == 1,
                "replacement offering action must not start a duplicate transition");
        controller.finishOfferingOperation(2001L);
        controller.applyOfferingOperationState(2001L, replacementButtonDisabled::set);
        require(!replacementButtonDisabled.get(),
                "rendered offering action must enable after pending state clears");

        AtomicBoolean confirmationDisabled = new AtomicBoolean();
        controller.tryBeginPlanConfirmation();
        controller.applyPlanConfirmationState(true, confirmationDisabled::set);
        require(confirmationDisabled.get(),
                "rendered plan action must be disabled from controller pending state");
        controller.finishPlanConfirmation();
        controller.applyPlanConfirmationState(true, confirmationDisabled::set);
        require(!confirmationDisabled.get(),
                "rendered plan action must enable after pending state clears");
    }

    private static void testLoadGenerationCallbacks() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicReference<List<CourseOfferingView>> rendered = new AtomicReference<>();
        AtomicInteger errors = new AtomicInteger();

        CompletableFuture<List<CourseOfferingView>> older = new CompletableFuture<>();
        CompletableFuture<List<CourseOfferingView>> latest = new CompletableFuture<>();
        service.loadResults.add(older);
        service.loadResults.add(latest);
        controller.requestOfferings(rendered::set, error -> errors.incrementAndGet());
        controller.requestOfferings(rendered::set, error -> errors.incrementAndGet());

        latest.complete(Collections.singletonList(
                offering(2002L, "最新课程", SelectionStatus.AVAILABLE)));
        require("最新课程".equals(rendered.get().get(0).getCourseName()),
                "latest load completion must invoke the render callback");
        older.complete(Collections.singletonList(
                offering(2003L, "过期课程", SelectionStatus.AVAILABLE)));
        require("最新课程".equals(rendered.get().get(0).getCourseName()),
                "stale load success must not replace latest callback data");

        CompletableFuture<List<CourseOfferingView>> staleFailure = new CompletableFuture<>();
        CompletableFuture<List<CourseOfferingView>> newerSuccess = new CompletableFuture<>();
        service.loadResults.add(staleFailure);
        service.loadResults.add(newerSuccess);
        controller.requestOfferings(rendered::set, error -> errors.incrementAndGet());
        controller.requestOfferings(rendered::set, error -> errors.incrementAndGet());
        newerSuccess.complete(Collections.singletonList(
                offering(2004L, "更新课程", SelectionStatus.AVAILABLE)));
        staleFailure.completeExceptionally(new IllegalStateException("stale failure"));
        require(errors.get() == 0, "stale load failure must not invoke the error callback");
        require("更新课程".equals(rendered.get().get(0).getCourseName()),
                "stale load failure must preserve latest callback data");
    }

    private static void testConfirmationCancelRendersCurrentAction() {
        long offeringId = 2005L;
        AtomicBoolean detachedButtonDisabled = new AtomicBoolean();
        AtomicBoolean liveButtonDisabled = new AtomicBoolean();
        AtomicInteger transitionCalls = new AtomicInteger();
        AtomicReference<CourseSelectionController> controllerRef = new AtomicReference<>();
        CourseSelectionController controller = new CourseSelectionController(
                new ControlledCourseService(),
                (title, message) -> {
                    controllerRef.get().applyOfferingOperationState(
                            offeringId, liveButtonDisabled::set);
                    return ButtonType.CANCEL;
                },
                (title, message) -> { },
                Runnable::run);
        controllerRef.set(controller);

        controller.confirmAndExecute(
                offeringId,
                "退出候补",
                "确认退出候补？",
                detachedButtonDisabled::set,
                () -> controller.applyOfferingOperationState(
                        offeringId, liveButtonDisabled::set),
                () -> {
                    transitionCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                });

        require(!liveButtonDisabled.get(),
                "cancel must rerender the live action after pending state clears");
        require(transitionCalls.get() == 0,
                "cancelled confirmation must not invoke the transition");
    }

    private static void testTransitionFailureRendersCurrentAction() {
        long offeringId = 2006L;
        AtomicBoolean detachedButtonDisabled = new AtomicBoolean();
        AtomicBoolean liveButtonDisabled = new AtomicBoolean();
        AtomicInteger errors = new AtomicInteger();
        CourseSelectionController controller = new CourseSelectionController(
                new ControlledCourseService(),
                (title, message) -> ButtonType.OK,
                (title, message) -> errors.incrementAndGet(),
                Runnable::run);

        controller.executeTransition(
                offeringId,
                detachedButtonDisabled::set,
                () -> controller.applyOfferingOperationState(
                        offeringId, liveButtonDisabled::set),
                () -> {
                    controller.applyOfferingOperationState(
                            offeringId, liveButtonDisabled::set);
                    throw new IllegalStateException("transition failed");
                });

        require(!liveButtonDisabled.get(),
                "transition failure must rerender the live action after pending clears");
        require(errors.get() == 1,
                "transition failure must report exactly one error");
    }

    private static CourseSelectionController testController(CourseService service) {
        return new CourseSelectionController(
                service,
                (title, message) -> ButtonType.OK,
                (title, message) -> { },
                Runnable::run);
    }

    private static CourseOfferingView offering(
            long id, String name, SelectionStatus status) {
        return new CourseOfferingView(
                id, "CS" + id, name, "必修", 3.0, 48,
                "测试教师", "周一 1-2节", "测试教室", "测试简介", "无",
                10, 30, status);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<List<CourseOfferingView>>> loadResults =
                new ArrayDeque<>();

        @Override
        public CompletableFuture<List<CourseOfferingView>> loadOfferings() {
            return loadResults.removeFirst();
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
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
