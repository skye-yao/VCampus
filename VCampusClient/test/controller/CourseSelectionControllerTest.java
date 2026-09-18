package controller;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.scene.control.ButtonType;
import model.course.CourseMeetingView;
import model.course.CourseMutationResultView;
import model.course.CourseNoticeView;
import model.course.CourseOfferingView;
import model.course.CoursePlanSnapshotView;
import model.course.CourseSelectionItemView;
import model.course.CourseTeacherView;
import model.course.CourseTermView;
import model.course.CourseView;
import model.course.GradeSummaryView;
import model.course.ScheduleWeekView;
import model.course.SelectionStatus;
import model.course.TrainingPlanGroupView;
import model.course.WaitlistDecision;
import service.CoursePushListener;
import service.CourseService;
import service.CourseSubscription;

public final class CourseSelectionControllerTest {
    private static final CourseTermView TERM =
            new CourseTermView(2026, 1, "2026-2027 秋学期");

    public static void main(String[] args) throws Exception {
        testAllTabUsesCoursesAndLazyOfferingCache();
        testOfferingRowTitleUsesTheOfferingCodeOnlyInTheAllTab();
        testSelectionTabMembership();
        testNoBatchConfirmationInFxml();
        testSameOfferingDisablesEveryCopyAndStartsOneMutation();
        testFailureReconcilesBeforeReenable();
        testFailedReconciliationStaysDisabledUntilLaterSnapshot();
        testAddToPlanPatchesOfferingRowInsteadOfRebuildingPage();
        testRemoveFromPlanPatchesRowAndCacheInsteadOfRebuildingPage();
        testPlanTabRemovalStillRebuildsWithoutPatching();
        testWaitlistOfferSupportsAcceptAbandonAndDismiss();
        testStaleMutationCannotReplaceCurrentTermSnapshot();
        testOutOfOrderMutationResultsUseAuthoritativeSnapshot();
        testOfferingLoadFailureCanRetryOnNextExpansion();
        testLoadGenerationCallbacks();
        System.out.println("CourseSelectionControllerTest: PASS");
    }

    private static void testAllTabUsesCoursesAndLazyOfferingCache() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        List<CourseView> courses = List.of(course(101L, "数据结构", "必修"),
                course(201L, "操作系统", "必修"));
        require(controller.filterCourses(courses, "", "全部").size() == 2,
                "all tab must render one outer row per course");

        service.offeringResults.addLast(CompletableFuture.completedFuture(List.of(
                offering(1001L, 101L, SelectionStatus.AVAILABLE, 10, 30),
                offering(1002L, 101L, SelectionStatus.PLANNED, 30, 30))));
        AtomicInteger renderedChildren = new AtomicInteger();
        controller.requestCourseOfferings(
                TERM, 101L, rows -> renderedChildren.set(rows.size()), error -> { });
        controller.requestCourseOfferings(
                TERM, 101L, rows -> renderedChildren.set(rows.size()), error -> { });
        require(service.offeringLoads.get() == 1,
                "expanding an already-loaded course must reuse its offerings");
        require(renderedChildren.get() == 2,
                "expanded course must render multiple teaching-class rows");
    }

    /**
     * 需求 1：全部页签里课程名下面的教学班行标题 = **教学班代码**（用户按教学班代码认班），已选等
     * 页签的整行代表"我正在上的这门课"，标题仍是课程名。行标题由 {@code offeringRowTitle} 计算——
     * 真正的行要建 JavaFX 控件，而本套件是无工具包运行的。
     */
    private static void testOfferingRowTitleUsesTheOfferingCodeOnlyInTheAllTab() {
        CourseView course = course(101L, "数据结构", "必修");
        CourseOfferingView offering = offering(1001L, 101L, SelectionStatus.AVAILABLE, 10, 30);
        require("CS101-2026-2-A".equals(offering.getOfferingCode()),
                "test fixture must carry the offering code");
        require(CourseSelectionController.offeringRowTitle(course, offering, true)
                        .equals("CS101-2026-2-A"),
                "the expanded teaching-class row must be titled with the offering code");
        require(CourseSelectionController.offeringRowTitle(course, offering, false)
                        .equals("数据结构"),
                "the selected tab must keep the course name");
        require(CourseSelectionController.offeringRowTitle(
                        course, codeLessOffering(1002L, 101L), true).equals("数据结构"),
                "a missing offering code must fall back to the course name instead of a blank title");
    }

    private static void testSelectionTabMembership() {
        CourseSelectionController controller = testController(new ControlledCourseService());
        CoursePlanSnapshotView snapshot = snapshot(
                item(1001L, SelectionStatus.PLANNED),
                item(1002L, SelectionStatus.FULL),
                item(2001L, SelectionStatus.WAITLISTED),
                item(2002L, SelectionStatus.WAITLIST_OFFERED),
                item(3001L, SelectionStatus.ENROLLED));
        require(controller.filterSelectionItems(
                snapshot, CourseSelectionController.SelectionTab.PLAN, "", "全部").size() == 4,
                "plan tab must include planned, full, waiting and offered items");
        require(controller.filterSelectionItems(
                snapshot, CourseSelectionController.SelectionTab.WAITLIST, "", "全部").size() == 2,
                "waitlist tab must include waiting and offered items");
        List<CourseSelectionItemView> enrolled = controller.filterSelectionItems(
                snapshot, CourseSelectionController.SelectionTab.ENROLLED, "", "全部");
        require(enrolled.size() == 1 && enrolled.get(0).getStatus() == SelectionStatus.ENROLLED,
                "enrolled tab must include only enrolled items");
    }

    private static void testNoBatchConfirmationInFxml() throws IOException {
        String fxml;
        try (var stream = CourseSelectionControllerTest.class.getResourceAsStream(
                "/resources/fxml/CourseSelectionView.fxml")) {
            require(stream != null, "course selection FXML must exist");
            fxml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        require(!fxml.contains("确认选课") && !fxml.contains("course-plan-action"),
                "selection page must not expose a batch-confirm button");
        require(fxml.contains("text=\"搜索\""), "user search-button text must be preserved");
    }

    private static void testSameOfferingDisablesEveryCopyAndStartsOneMutation() {
        CourseSelectionController controller = testController(new ControlledCourseService());
        AtomicBoolean catalogCopyDisabled = new AtomicBoolean();
        AtomicBoolean planCopyDisabled = new AtomicBoolean();
        controller.registerOfferingAction(1001L, catalogCopyDisabled::set);
        controller.registerOfferingAction(1001L, planCopyDisabled::set);

        AtomicInteger mutations = new AtomicInteger();
        AtomicReference<String> operationId = new AtomicReference<>();
        CompletableFuture<CourseMutationResultView> pending = new CompletableFuture<>();
        controller.executeMutation(TERM, 1001L, () -> { }, id -> {
            operationId.set(id);
            mutations.incrementAndGet();
            return pending;
        });
        controller.executeMutation(TERM, 1001L, () -> { }, id -> {
            mutations.incrementAndGet();
            return pending;
        });

        require(mutations.get() == 1,
                "two clicks for one offering must start only one mutation");
        require(catalogCopyDisabled.get() && planCopyDisabled.get(),
                "every rendered copy of a pending offering must be disabled");
        UUID.fromString(operationId.get());
    }

    private static void testFailureReconcilesBeforeReenable() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicBoolean disabled = new AtomicBoolean();
        controller.registerOfferingAction(1001L, disabled::set);

        CompletableFuture<CoursePlanSnapshotView> reconciliation = new CompletableFuture<>();
        service.snapshotResults.addLast(reconciliation);
        CompletableFuture<CourseMutationResultView> failure = new CompletableFuture<>();
        controller.executeMutation(TERM, 1001L, () -> { }, operationId -> failure);
        failure.completeExceptionally(new IllegalStateException("request timeout"));

        require(service.snapshotLoads.get() == 1,
                "failed mutation must reload the authoritative snapshot");
        require(disabled.get(),
                "offering must remain disabled until failure reconciliation completes");
        reconciliation.complete(snapshot(item(1001L, SelectionStatus.PLANNED)));
        require(!disabled.get(),
                "offering may re-enable only after snapshot reconciliation");
    }

    private static void testFailedReconciliationStaysDisabledUntilLaterSnapshot() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicBoolean disabled = new AtomicBoolean();
        controller.registerOfferingAction(1001L, disabled::set);

        CompletableFuture<CoursePlanSnapshotView> failedRefresh = new CompletableFuture<>();
        service.snapshotResults.addLast(failedRefresh);
        CompletableFuture<CourseMutationResultView> failure = new CompletableFuture<>();
        controller.executeMutation(TERM, 1001L, () -> { }, operationId -> failure);
        failure.completeExceptionally(new IllegalStateException("request timeout"));
        failedRefresh.completeExceptionally(new IllegalStateException("refresh failed"));

        require(disabled.get(),
                "offering must stay disabled while its authoritative state is unknown");
        controller.acceptAuthoritativeSnapshot(
                TERM, snapshot(item(1001L, SelectionStatus.PLANNED)));
        require(!disabled.get(),
                "a later authoritative snapshot must release the pending offering");
    }

    private static void testAddToPlanPatchesOfferingRowInsteadOfRebuildingPage()
            throws ReflectiveOperationException {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicReference<CourseOfferingView> patched = new AtomicReference<>();
        renderedRow(controller, 1001L, patched::set);
        AtomicInteger rerenders = new AtomicInteger();

        service.snapshotResults.addLast(CompletableFuture.completedFuture(
                snapshot(item(1001L, SelectionStatus.PLANNED))));
        controller.executeMutation(TERM, 1001L, rerenders::incrementAndGet,
                operationId -> CompletableFuture.completedFuture(mutationResult(
                        item(1001L, SelectionStatus.PLANNED), snapshot())));

        require(patched.get() != null
                        && patched.get().getSelectionStatus() == SelectionStatus.PLANNED,
                "a succeeded plan change must refresh the rendered teaching-class row");
        require(rerenders.get() == 0,
                "adding to the plan in the all tab must not rebuild the page: a rebuild "
                        + "collapses the expanded teaching-class list");
    }

    private static void testRemoveFromPlanPatchesRowAndCacheInsteadOfRebuildingPage()
            throws ReflectiveOperationException {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicReference<CourseOfferingView> patched = new AtomicReference<>();
        renderedRow(controller, 1001L, patched::set);
        AtomicInteger rerenders = new AtomicInteger();

        Map<Long, List<CourseOfferingView>> cache = getField(controller, "offeringCache");
        cache.put(101L, List.of(offering(1001L, 101L, SelectionStatus.PLANNED, 10, 30)));

        // 移出计划后该教学班不再属于任何集合，快照里查不到，只能取变更结果的最终状态
        service.snapshotResults.addLast(
                CompletableFuture.completedFuture(snapshot()));
        controller.executeMutation(TERM, 1001L, rerenders::incrementAndGet,
                operationId -> CompletableFuture.completedFuture(mutationResult(
                        item(1001L, SelectionStatus.AVAILABLE), snapshot())));

        require(patched.get() != null
                        && patched.get().getSelectionStatus() == SelectionStatus.AVAILABLE,
                "removing from the plan must refresh the row with the final state");
        require(cache.get(101L).get(0).getSelectionStatus() == SelectionStatus.AVAILABLE,
                "the authoritative state must be written back into the offering cache");
        require(rerenders.get() == 0,
                "removing from the plan in the all tab must not rebuild the page");
    }

    private static void testPlanTabRemovalStillRebuildsWithoutPatching()
            throws ReflectiveOperationException {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicReference<CourseOfferingView> patched = new AtomicReference<>();
        renderedRow(controller, 1001L, patched::set);
        setField(controller, "selectedTab", CourseSelectionController.SelectionTab.PLAN);
        AtomicInteger rerenders = new AtomicInteger();

        service.snapshotResults.addLast(
                CompletableFuture.completedFuture(snapshot()));
        controller.executeMutation(TERM, 1001L, rerenders::incrementAndGet,
                operationId -> CompletableFuture.completedFuture(mutationResult(
                        item(1001L, SelectionStatus.AVAILABLE), snapshot())));

        require(rerenders.get() == 1,
                "the plan tab row must disappear, so that mutation still rebuilds the list");
        require(patched.get() == null,
                "a plan tab row must be dropped by the rebuild, never patched in place");
    }

    /** 模拟“该教学班行已经渲染在页面上”，登记它的就地刷新回调。 */
    private static void renderedRow(CourseSelectionController controller, long offeringId,
            Consumer<CourseOfferingView> patch) throws ReflectiveOperationException {
        Map<Long, Consumer<CourseOfferingView>> rows =
                getField(controller, "renderedOfferingRows");
        rows.put(offeringId, patch);
    }

    private static void testWaitlistOfferSupportsAcceptAbandonAndDismiss() {
        ControlledCourseService abandonService = new ControlledCourseService();
        CourseSelectionController abandonController = testController(abandonService);
        abandonController.executeWaitlistOfferDecision(
                TERM, 1001L, null, () -> { });
        require(abandonService.waitlistDecision.get() == null,
                "dismissing the decision dialog must not mutate the offered seat");

        abandonController.executeWaitlistOfferDecision(
                TERM, 1001L, WaitlistDecision.ABANDON, () -> { });
        require(abandonService.waitlistDecision.get() == WaitlistDecision.ABANDON,
                "the explicit abandon choice must be sent to the service");

        ControlledCourseService acceptService = new ControlledCourseService();
        CourseSelectionController acceptController = testController(acceptService);
        acceptController.executeWaitlistOfferDecision(
                TERM, 1002L, WaitlistDecision.ACCEPT, () -> { });
        require(acceptService.waitlistDecision.get() == WaitlistDecision.ACCEPT,
                "the explicit accept choice must be sent to the service");
    }

    private static void testStaleMutationCannotReplaceCurrentTermSnapshot()
            throws ReflectiveOperationException {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        CoursePlanSnapshotView current = snapshot(item(1001L, SelectionStatus.PLANNED));
        CourseTermView oldTerm = new CourseTermView(2025, 2, "2025-2026 春学期");
        setField(controller, "currentTerm", TERM);
        setField(controller, "snapshot", current);

        CoursePlanSnapshotView stale = snapshot(
                oldTerm, item(2001L, SelectionStatus.ENROLLED));
        CourseMutationResultView staleResult = mutationResult(
                item(2001L, SelectionStatus.ENROLLED), stale);
        controller.executeMutation(oldTerm, 2001L, () -> { },
                operationId -> CompletableFuture.completedFuture(staleResult));

        require(getField(controller, "snapshot") == current,
                "a previous-term mutation must not replace the active-term snapshot");
    }

    private static void testOutOfOrderMutationResultsUseAuthoritativeSnapshot()
            throws ReflectiveOperationException {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        setField(controller, "currentTerm", TERM);
        setField(controller, "snapshot", snapshot());

        CoursePlanSnapshotView authoritative = snapshot(
                item(1001L, SelectionStatus.PLANNED),
                item(2001L, SelectionStatus.PLANNED));
        service.snapshotResults.addLast(CompletableFuture.completedFuture(authoritative));
        service.snapshotResults.addLast(CompletableFuture.completedFuture(authoritative));

        CompletableFuture<CourseMutationResultView> older = new CompletableFuture<>();
        CompletableFuture<CourseMutationResultView> newer = new CompletableFuture<>();
        controller.executeMutation(TERM, 1001L, () -> { }, operationId -> older);
        controller.executeMutation(TERM, 2001L, () -> { }, operationId -> newer);
        newer.complete(mutationResult(
                item(2001L, SelectionStatus.PLANNED), authoritative));
        older.complete(mutationResult(
                item(1001L, SelectionStatus.PLANNED),
                snapshot(item(1001L, SelectionStatus.PLANNED))));

        CoursePlanSnapshotView actual = getField(controller, "snapshot");
        require(actual.getPlanItems().size() == 2,
                "late mutation results must not replace a newer authoritative snapshot");
    }

    private static void testOfferingLoadFailureCanRetryOnNextExpansion() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        CompletableFuture<List<CourseOfferingView>> failure = new CompletableFuture<>();
        service.offeringResults.addLast(failure);
        controller.requestCourseOfferings(TERM, 101L, rows -> { }, error -> { });
        failure.completeExceptionally(new IllegalStateException("load failed"));

        require(controller.shouldRequestOfferings(101L, true, false),
                "a failed teaching-class load must retry on the next expansion");
    }

    private static void testLoadGenerationCallbacks() {
        ControlledCourseService service = new ControlledCourseService();
        CourseSelectionController controller = testController(service);
        AtomicReference<List<CourseView>> rendered = new AtomicReference<>();
        CompletableFuture<List<CourseView>> older = new CompletableFuture<>();
        CompletableFuture<List<CourseView>> latest = new CompletableFuture<>();
        service.courseResults.addLast(older);
        service.courseResults.addLast(latest);
        controller.requestCourses(TERM, rendered::set, error -> { });
        controller.requestCourses(TERM, rendered::set, error -> { });

        latest.complete(List.of(course(201L, "最新课程", "必修")));
        older.complete(List.of(course(101L, "过期课程", "必修")));
        require("最新课程".equals(rendered.get().get(0).getCourseName()),
                "stale course load must not replace the latest result");
    }

    private static CourseSelectionController testController(CourseService service) {
        return new CourseSelectionController(
                service, (title, message) -> ButtonType.OK,
                (title, message) -> { }, Runnable::run);
    }

    private static CourseView course(long id, String name, String type) {
        return new CourseView(id, "CS" + id, name, type, 3.0, 48, "简介", "无");
    }

    private static CourseOfferingView offering(long offeringId, long courseId,
            SelectionStatus status, int enrolled, int capacity) {
        return new CourseOfferingView(
                offeringId, "CS" + courseId + "-2026-2-A", courseId,
                List.of(new CourseTeacherView("T1", "测试教师")),
                List.of(new CourseMeetingView(
                        1, 1, 2, 1, 16, "ALL", "测试教室", null, null)),
                enrolled, capacity, status, null, null, null);
    }

    /** 教学班代码缺省（老教学班/老响应）时行标题必须回退课程名，而不是显示空白。 */
    private static CourseOfferingView codeLessOffering(long offeringId, long courseId) {
        return new CourseOfferingView(
                offeringId, null, courseId,
                List.of(new CourseTeacherView("T1", "测试教师")),
                List.of(new CourseMeetingView(
                        1, 1, 2, 1, 16, "ALL", "测试教室", null, null)),
                10, 30, SelectionStatus.AVAILABLE, null, null, null);
    }

    private static CourseSelectionItemView item(long offeringId, SelectionStatus status) {
        long courseId = offeringId / 1000L + 100L;
        return new CourseSelectionItemView(
                course(courseId, "课程" + courseId, "必修"),
                offering(offeringId, courseId, status, 10, 30));
    }

    private static CoursePlanSnapshotView snapshot(CourseSelectionItemView... items) {
        return snapshot(TERM, items);
    }

    private static CoursePlanSnapshotView snapshot(
            CourseTermView term, CourseSelectionItemView... items) {
        List<CourseSelectionItemView> plan = new java.util.ArrayList<>();
        List<CourseSelectionItemView> waitlist = new java.util.ArrayList<>();
        List<CourseSelectionItemView> enrolled = new java.util.ArrayList<>();
        for (CourseSelectionItemView item : items) {
            if (item.getStatus() == SelectionStatus.ENROLLED) {
                enrolled.add(item);
            } else if (item.getStatus() == SelectionStatus.WAITLISTED
                    || item.getStatus() == SelectionStatus.WAITLIST_OFFERED) {
                waitlist.add(item);
            } else {
                plan.add(item);
            }
        }
        return new CoursePlanSnapshotView(term, plan, waitlist, enrolled);
    }

    private static CourseMutationResultView mutationResult(
            CourseSelectionItemView item, CoursePlanSnapshotView snapshot) {
        return new CourseMutationResultView(
                "operation", item, item.getStatus(), item.getStatus().name(),
                "result", snapshot);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String name)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class ControlledCourseService implements CourseService {
        private final Deque<CompletableFuture<List<CourseView>>> courseResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<CourseOfferingView>>> offeringResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<CoursePlanSnapshotView>> snapshotResults =
                new ArrayDeque<>();
        private final AtomicInteger offeringLoads = new AtomicInteger();
        private final AtomicInteger snapshotLoads = new AtomicInteger();
        private final AtomicReference<WaitlistDecision> waitlistDecision =
                new AtomicReference<>();
        private final CompletableFuture<CourseMutationResultView> pendingWaitlistDecision =
                new CompletableFuture<>();

        @Override public CompletableFuture<List<CourseTermView>> loadTerms() {
            return CompletableFuture.completedFuture(List.of(TERM));
        }
        @Override public CompletableFuture<List<CourseView>> loadCourses(CourseTermView term) {
            return courseResults.removeFirst();
        }
        @Override public CompletableFuture<List<CourseOfferingView>> loadCourseOfferings(
                CourseTermView term, long courseId) {
            offeringLoads.incrementAndGet();
            return offeringResults.removeFirst();
        }
        @Override public CompletableFuture<CoursePlanSnapshotView> loadSelectionSnapshot(
                CourseTermView term) {
            snapshotLoads.incrementAndGet();
            return snapshotResults.removeFirst();
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
            waitlistDecision.set(decision);
            return pendingWaitlistDecision;
        }
        @Override public CompletableFuture<CourseMutationResultView> dropOffering(
                CourseTermView term, long offeringId, String operationId) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> ackCourseEvent(String eventId) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CourseSubscription subscribe(CoursePushListener listener) {
            return () -> { };
        }
        @Override public CompletableFuture<ScheduleWeekView> loadSchedule(
                CourseTermView term, Integer week) {
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
