package controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import javafx.scene.control.ButtonType;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import service.AdminCourseService;

/**
 * 无 JavaFX 依赖的删除学生对话框控制器测试：注入假服务、确认框与 {@code Runnable::run} FX 执行器。
 */
public final class RemoveOfferingStudentDialogControllerTest {

    public static void main(String[] args) {
        testStaleStudentListResultIsIgnored();
        testFilterTrimsAndReloadsFirstPage();
        testBlockedRowExposesReasonAndDisablesRemoval();
        testCancelledConfirmationSendsNoMutation();
        testConfirmationTextIncludesStudentAndOffering();
        testDoubleSubmitSendsOnce();
        testSuccessfulRemovalReloadsListAndNotifies();
        testClosedDialogStillRefreshesAfterPendingWrite();
        System.out.println("RemoveOfferingStudentDialogControllerTest: PASS");
    }

    private static void testStaleStudentListResultIsIgnored() {
        FakeService service = new FakeService();
        RemoveOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());

        CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> older = new CompletableFuture<>();
        CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> newer = new CompletableFuture<>();
        service.enqueueList(older);
        service.enqueueList(newer);
        controller.loadStudents();
        controller.loadStudents();

        newer.complete(page(List.of(enrolled("50002", "20240002", "李静")), 1, 1, 10));
        older.complete(page(List.of(enrolled("50001", "20240001", "张明")), 1, 1, 10));

        require(controller.students().size() == 1
                        && "20240002".equals(controller.students().get(0).getUid()),
                "a stale roster result must not replace the newest one, saw " + controller.students());
        require(!controller.isLoading(), "a stale result must not keep the loading state");
    }

    private static void testFilterTrimsAndReloadsFirstPage() {
        FakeService service = new FakeService();
        RemoveOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(), 0, 1, 10)));

        controller.applyFilter("  李静  ");
        require(service.listCalls.size() == 1, "applying a filter must trigger one load");
        require(service.listCalls.get(0).startsWith("1001|李静|1|"),
                "the trimmed filter must be sent from page 1, saw " + service.listCalls);
    }

    private static void testBlockedRowExposesReasonAndDisablesRemoval() {
        FakeService service = new FakeService();
        AtomicInteger confirmations = new AtomicInteger();
        RemoveOfferingStudentDialogController controller = new RemoveOfferingStudentDialogController(
                service, (title, message) -> {
                    confirmations.incrementAndGet();
                    return ButtonType.OK;
                }, (title, message) -> { }, Runnable::run);
        controller.prepareForOffering(offering());
        OfferingStudentView locked = blocked("50001", "20240001", "张明",
                "该学生已进入成绩审批或已有发布成绩，不能移除");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(locked), 1, 1, 10)));
        controller.loadStudents();

        require(!controller.canRemove(locked), "a grade-locked row must disable removal");
        require(controller.blockedReason("20240001") != null
                        && controller.blockedReason("20240001").contains("成绩审批"),
                "a blocked row must expose its blocked reason, saw "
                        + controller.blockedReason("20240001"));

        controller.requestRemove(locked);
        require(confirmations.get() == 0,
                "a blocked row must not offer confirmation, saw " + confirmations.get());
        require(service.removeCalls.isEmpty(),
                "a blocked row must never reach the service, saw " + service.removeCalls);
        require(controller.message() != null && controller.message().contains("成绩审批"),
                "a blocked removal must display the blocked reason, saw " + controller.message());
    }

    private static void testCancelledConfirmationSendsNoMutation() {
        FakeService service = new FakeService();
        RemoveOfferingStudentDialogController controller = controller(service,
                (title, message) -> ButtonType.CANCEL);
        controller.prepareForOffering(offering());
        OfferingStudentView row = enrolled("50002", "20240002", "李静");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(row), 1, 1, 10)));
        controller.loadStudents();

        controller.requestRemove(row);
        require(service.removeCalls.isEmpty(),
                "a cancelled confirmation must not mutate, saw " + service.removeCalls);
        require(!controller.hasMutated(), "a cancelled confirmation must not report a mutation");
    }

    private static void testConfirmationTextIncludesStudentAndOffering() {
        FakeService service = new FakeService();
        List<String> confirmations = new ArrayList<>();
        RemoveOfferingStudentDialogController controller = new RemoveOfferingStudentDialogController(
                service, (title, message) -> {
                    confirmations.add(title + "|" + message);
                    return ButtonType.CANCEL;
                }, (title, message) -> { }, Runnable::run);
        controller.prepareForOffering(offering());
        OfferingStudentView row = enrolled("50002", "20240002", "李静");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(row), 1, 1, 10)));
        controller.loadStudents();

        controller.requestRemove(row);
        require(confirmations.size() == 1, "an eligible removal must ask for confirmation");
        String confirmation = confirmations.get(0);
        require(confirmation.contains("李静") && confirmation.contains("20240002"),
                "the confirmation must name the student and UID, saw " + confirmation);
        require(confirmation.contains("OFF-1001"),
                "the confirmation must name the offering, saw " + confirmation);
    }

    private static void testDoubleSubmitSendsOnce() {
        FakeService service = new FakeService();
        RemoveOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());
        OfferingStudentView row = enrolled("50002", "20240002", "李静");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(row), 1, 1, 10)));
        controller.loadStudents();

        CompletableFuture<AdminOperationResultView<OfferingStudentView>> pending =
                new CompletableFuture<>();
        service.enqueueRemove(pending);
        controller.requestRemove(row);
        controller.requestRemove(row);
        require(service.removeCalls.size() == 1,
                "a double submit must send exactly one removal, saw " + service.removeCalls);
        require("50002".equals(service.removeCalls.get(0).getOfferingId())
                        || "20240002".equals(service.removeCalls.get(0).getStudentUid()),
                "the removal request must carry the selected student");
        pending.complete(null);
        require(controller.hasMutated(), "a completed removal must report a mutation");
    }

    private static void testSuccessfulRemovalReloadsListAndNotifies() {
        FakeService service = new FakeService();
        AtomicInteger refreshes = new AtomicInteger();
        RemoveOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.prepareForOffering(offering());
        OfferingStudentView row = enrolled("50002", "20240002", "李静");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(row), 1, 1, 10)));
        controller.loadStudents();

        service.enqueueList(CompletableFuture.completedFuture(page(List.of(), 0, 1, 10)));
        controller.requestRemove(row);

        require(service.listCalls.size() == 2,
                "a successful removal must reload the authoritative roster, saw " + service.listCalls);
        require(controller.students().isEmpty(),
                "the reloaded roster must drop the removed student, saw " + controller.students());
        require(refreshes.get() == 1,
                "a successful removal must refresh the offering row once, saw " + refreshes.get());
        require(controller.hasMutated(), "a successful removal must report a mutation");
    }

    private static void testClosedDialogStillRefreshesAfterPendingWrite() {
        FakeService service = new FakeService();
        AtomicInteger refreshes = new AtomicInteger();
        RemoveOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.prepareForOffering(offering());
        OfferingStudentView row = enrolled("50002", "20240002", "李静");
        service.enqueueList(CompletableFuture.completedFuture(page(List.of(row), 1, 1, 10)));
        controller.loadStudents();

        CompletableFuture<AdminOperationResultView<OfferingStudentView>> pending =
                new CompletableFuture<>();
        service.enqueueRemove(pending);
        controller.requestRemove(row);
        controller.dispose();
        require(refreshes.get() == 0,
                "closing before completion must not refresh with no authoritative result");
        pending.complete(null);
        require(refreshes.get() == 1,
                "a removal that completes after close must still refresh the offering row");
    }

    private static BiFunction<String, String, ButtonType> alwaysConfirm() {
        return (title, message) -> ButtonType.OK;
    }

    private static RemoveOfferingStudentDialogController controller(FakeService service,
            BiFunction<String, String, ButtonType> confirmation) {
        return new RemoveOfferingStudentDialogController(
                service, confirmation, (title, message) -> { }, Runnable::run);
    }

    private static AdminEnrollmentPageView<OfferingStudentView> page(
            List<OfferingStudentView> items, long total, int page, int size) {
        return new AdminEnrollmentPageView<>(items, total, page, size);
    }

    private static OfferingStudentView enrolled(String enrollmentId, String uid, String name) {
        return new OfferingStudentView(enrollmentId, uid, name, "软件工程", 2024,
                "ENROLLED", true, null);
    }

    private static OfferingStudentView blocked(String enrollmentId, String uid, String name,
            String reason) {
        return new OfferingStudentView(enrollmentId, uid, name, "软件工程", 2024,
                "ENROLLED", false, reason);
    }

    private static AdminOfferingView offering() {
        return new AdminOfferingView("1001", "OFF-1001", "101", 2026, 1, 120, 30,
                "OPEN", "T1001", "张老师", null, null, "SCHEDULED", 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * 可编排花名册与删除结果的假服务，其余目录/排课接口返回空结果。
     */
    static final class FakeService implements AdminCourseService {
        private final Deque<CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>>>
                listResults = new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminOperationResultView<OfferingStudentView>>>
                removeResults = new ArrayDeque<>();
        final List<String> listCalls = new ArrayList<>();
        final List<AdminEnrollmentRequestDTO> removeCalls = new ArrayList<>();

        void enqueueList(CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> result) {
            listResults.addLast(result);
        }

        void enqueueRemove(CompletableFuture<AdminOperationResultView<OfferingStudentView>> result) {
            removeResults.addLast(result);
        }

        @Override
        public CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> listOfferingStudentsPage(
                String offeringId, String query, int page, int size) {
            listCalls.add(offeringId + "|" + query + "|" + page + "|" + size);
            if (!listResults.isEmpty()) return listResults.removeFirst();
            return CompletableFuture.completedFuture(
                    new AdminEnrollmentPageView<>(List.of(), 0, page, size));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<OfferingStudentView>> removeStudentFromOffering(
                AdminEnrollmentRequestDTO request) {
            removeCalls.add(request);
            if (!removeResults.isEmpty()) return removeResults.removeFirst();
            return CompletableFuture.completedFuture(new AdminOperationResultView<>
                    (request.getOperationId(), "OK", "已从教学班移除学生", null));
        }

        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
                CourseEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
                CourseEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
                String courseId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
                String courseId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
                OfferingEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
                OfferingEditorRequestDTO request) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
                String offeringId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
