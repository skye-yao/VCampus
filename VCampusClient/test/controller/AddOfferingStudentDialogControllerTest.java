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
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import model.course.admin.StudentSearchResultView;
import service.AdminCourseService;

/**
 * 无 JavaFX 依赖的添加学生对话框控制器测试：注入假服务、确认框与 {@code Runnable::run} FX 执行器。
 */
public final class AddOfferingStudentDialogControllerTest {

    public static void main(String[] args) {
        testStaleSearchResultForAnotherQueryIsIgnored();
        testEditingQueryInvalidatesPendingSearchAndSelection();
        testSelectionWithoutCompletedPreviewCannotAdd();
        testStalePreviewForPreviousStudentCannotEnableAdd();
        testBlockingRisksRemoveConfirmationAndNeverMutate();
        testOverridableRisksRequireTrimmedReasonAndExplicitConfirmation();
        testCancelSendsNoMutation();
        testDoubleSubmitSendsOnce();
        testSuccessfulAddClosesAndNotifiesOnce();
        testClosedDialogStillRefreshesAfterPendingWrite();
        System.out.println("AddOfferingStudentDialogControllerTest: PASS");
    }

    private static void testStaleSearchResultForAnotherQueryIsIgnored() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());

        CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> older =
                new CompletableFuture<>();
        CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> newer =
                new CompletableFuture<>();
        service.enqueueSearch(older);
        service.enqueueSearch(newer);

        controller.search("张");
        controller.search("陈");
        require(service.searchCalls.size() == 2,
                "each explicit search must reach the service, saw " + service.searchCalls);

        newer.complete(page(List.of(student("20240031", "陈晨")), 1, 1, 10));
        require(controller.results().size() == 1
                        && "20240031".equals(controller.results().get(0).getUid()),
                "the newest search must be displayed");
        require(!controller.isSearching(), "the newest search must clear the loading state");

        older.complete(page(List.of(student("20240001", "张明")), 1, 1, 10));
        require(controller.results().size() == 1
                        && "20240031".equals(controller.results().get(0).getUid()),
                "a stale search result must not replace the newest one, saw " + controller.results());
        require(!controller.isSearching(), "a stale result must not keep the loading state");
    }

    private static void testEditingQueryInvalidatesPendingSearchAndSelection() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());

        CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> pending =
                new CompletableFuture<>();
        service.enqueueSearch(pending);
        controller.search("张");
        controller.selectStudent("20240001");

        controller.onQueryEdited("陈");
        pending.complete(page(List.of(student("20240001", "张明")), 1, 1, 10));

        require(controller.results().isEmpty(),
                "an edited query must discard the in-flight result, saw " + controller.results());
        require(controller.selectedStudent() == null,
                "an edited query must clear the previous selection");
        require(!controller.canAdd(),
                "an edited query must not leave a stale add path enabled");
    }

    private static void testSelectionWithoutCompletedPreviewCannotAdd() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());

        service.enqueueSearch(CompletableFuture.completedFuture(
                page(List.of(student("20240031", "陈晨")), 1, 1, 10)));
        controller.search("陈");
        CompletableFuture<AdminEnrollmentPreviewDTO> pendingPreview = new CompletableFuture<>();
        service.enqueuePreview(pendingPreview);
        controller.selectStudent("20240031");

        require(controller.isPreviewPending(), "selecting a student must load a risk preview");
        require(!controller.canAdd(), "a pending preview must not enable adding");
        controller.addStudent();
        require(service.addCalls.isEmpty(),
                "adding before a completed preview must not mutate, saw " + service.addCalls);
        require(controller.message() != null && controller.message().contains("预览"),
                "adding without a preview must explain the requirement, saw " + controller.message());

        pendingPreview.complete(new AdminEnrollmentPreviewDTO("1001", "20240031", List.of()));
        require(controller.isPreviewCurrent(), "the completed preview must become current");
        require(controller.canAdd(), "a current conflict-free preview must enable adding");
    }

    private static void testStalePreviewForPreviousStudentCannotEnableAdd() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());
        service.enqueueSearch(CompletableFuture.completedFuture(page(
                List.of(student("20240031", "陈晨"), student("20240032", "王晓雨")), 2, 1, 10)));
        controller.search("同学");

        CompletableFuture<AdminEnrollmentPreviewDTO> first = new CompletableFuture<>();
        CompletableFuture<AdminEnrollmentPreviewDTO> second = new CompletableFuture<>();
        service.enqueuePreview(first);
        service.enqueuePreview(second);
        controller.selectStudent("20240031");
        controller.selectStudent("20240032");
        second.complete(new AdminEnrollmentPreviewDTO("1001", "20240032", List.of()));
        require(controller.canAdd(), "the newest preview must enable adding");

        first.complete(new AdminEnrollmentPreviewDTO("1001", "20240031", List.of()));
        require("20240032".equals(controller.selectedStudent().getUid()),
                "a stale preview must not change the current selection");
        require(controller.canAdd(), "a stale preview must not revoke the current preview");
    }

    private static void testBlockingRisksRemoveConfirmationAndNeverMutate() {
        FakeService service = new FakeService();
        AtomicInteger confirmations = new AtomicInteger();
        AddOfferingStudentDialogController controller = new AddOfferingStudentDialogController(
                service, (title, message) -> {
                    confirmations.incrementAndGet();
                    return ButtonType.OK;
                }, (title, message) -> { }, Runnable::run);
        controller.prepareForOffering(offering());
        service.enqueueSearch(CompletableFuture.completedFuture(
                page(List.of(student("20240001", "张明")), 1, 1, 10)));
        controller.search("张");
        service.enqueuePreview(CompletableFuture.completedFuture(new AdminEnrollmentPreviewDTO(
                "1001", "20240001",
                List.of(risk(ScheduleConflictSeverityDTO.BLOCKING, "该学生已进入成绩审批")))));
        controller.selectStudent("20240001");

        require(controller.hasBlockingRisks(), "a BLOCKING risk must be classified as blocking");
        require(!controller.canAdd(), "a BLOCKING risk must not leave adding enabled");
        require(!controller.canForceAdd(), "a BLOCKING risk must remove the force path");

        controller.addStudent();
        controller.forceAddStudent("已确认");
        require(service.addCalls.isEmpty(),
                "a BLOCKING risk must never reach the service, saw " + service.addCalls);
        require(confirmations.get() == 0,
                "a BLOCKING risk must not offer confirmation, saw " + confirmations.get());
    }

    private static void testOverridableRisksRequireTrimmedReasonAndExplicitConfirmation() {
        FakeService service = new FakeService();
        List<String> confirmations = new ArrayList<>();
        ButtonType[] answer = { ButtonType.CANCEL };
        AddOfferingStudentDialogController controller = new AddOfferingStudentDialogController(
                service, (title, message) -> {
                    confirmations.add(title + "|" + message);
                    return answer[0];
                }, (title, message) -> { }, Runnable::run);
        controller.prepareForOffering(offering());
        service.enqueueSearch(CompletableFuture.completedFuture(
                page(List.of(student("20240033", "刘洋")), 1, 1, 10)));
        controller.search("刘");
        service.enqueuePreview(CompletableFuture.completedFuture(new AdminEnrollmentPreviewDTO(
                "1001", "20240033",
                List.of(risk(ScheduleConflictSeverityDTO.OVERRIDABLE, "需要管理员确认先修要求")))));
        controller.selectStudent("20240033");

        require(!controller.canAdd(),
                "an OVERRIDABLE risk must not use the ordinary add path");
        require(controller.canForceAdd(), "an OVERRIDABLE risk must expose the force path");
        controller.addStudent();
        require(service.addCalls.isEmpty(),
                "the ordinary add path must not mutate with OVERRIDABLE risks");

        controller.forceAddStudent("   ");
        require(service.addCalls.isEmpty(),
                "a blank force reason must not mutate, saw " + service.addCalls);
        require(controller.message() != null && controller.message().contains("原因"),
                "a blank force reason must be explained, saw " + controller.message());

        controller.forceAddStudent("  已核实课程安排  ");
        require(service.addCalls.isEmpty(),
                "a cancelled confirmation must not mutate, saw " + service.addCalls);
        require(confirmations.size() == 1,
                "an OVERRIDABLE force add must ask for confirmation once, saw " + confirmations);

        answer[0] = ButtonType.OK;
        controller.forceAddStudent("  已核实课程安排  ");
        require(service.addCalls.size() == 1,
                "a confirmed force add must reach the service once, saw " + service.addCalls);
        AdminEnrollmentRequestDTO request = service.addCalls.get(0);
        require(request.isForce() && "已核实课程安排".equals(request.getOverrideReason()),
                "the forced request must trim and carry the reason, saw " + request.getOverrideReason());
        require("1001".equals(request.getOfferingId()) && "20240033".equals(request.getStudentUid()),
                "the forced request must carry the offering and student");
    }

    private static void testCancelSendsNoMutation() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());
        controller.dispose();
        require(service.addCalls.isEmpty(), "cancelling the dialog must not mutate");
        require(!controller.hasMutated(), "cancelling the dialog must not report a mutation");
    }

    private static void testDoubleSubmitSendsOnce() {
        FakeService service = new FakeService();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.prepareForOffering(offering());
        prepareAddable(service, controller, "20240031", "陈晨");

        CompletableFuture<AdminOperationResultView<OfferingStudentView>> pending =
                new CompletableFuture<>();
        service.enqueueAdd(pending);
        controller.addStudent();
        controller.addStudent();
        require(service.addCalls.size() == 1,
                "a double submit must send exactly one request, saw " + service.addCalls);
        pending.complete(result("20240031", "陈晨"));
        require(controller.hasMutated(), "the completed add must report success");
    }

    private static void testSuccessfulAddClosesAndNotifiesOnce() {
        FakeService service = new FakeService();
        AtomicInteger refreshes = new AtomicInteger();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.prepareForOffering(offering());
        prepareAddable(service, controller, "20240031", "陈晨");

        controller.addStudent();
        require(service.addCalls.size() == 1, "a successful add must reach the service");
        require(controller.hasMutated(), "a successful add must report a mutation");
        require(controller.isClosed(), "a successful add must close the dialog");
        require(refreshes.get() == 1,
                "a successful add must refresh the offering row exactly once, saw " + refreshes.get());
    }

    private static void testClosedDialogStillRefreshesAfterPendingWrite() {
        FakeService service = new FakeService();
        AtomicInteger refreshes = new AtomicInteger();
        AddOfferingStudentDialogController controller = controller(service, alwaysConfirm());
        controller.setOnChanged(refreshes::incrementAndGet);
        controller.prepareForOffering(offering());
        prepareAddable(service, controller, "20240031", "陈晨");

        CompletableFuture<AdminOperationResultView<OfferingStudentView>> pending =
                new CompletableFuture<>();
        service.enqueueAdd(pending);
        controller.addStudent();
        controller.dispose();
        require(refreshes.get() == 0,
                "closing before completion must not refresh with no authoritative result");
        pending.complete(result("20240031", "陈晨"));
        require(refreshes.get() == 1,
                "a write that completes after close must still refresh the offering row");
    }

    private static void prepareAddable(FakeService service,
            AddOfferingStudentDialogController controller, String uid, String name) {
        service.enqueueSearch(CompletableFuture.completedFuture(
                page(List.of(student(uid, name)), 1, 1, 10)));
        controller.search(name);
        service.enqueuePreview(CompletableFuture.completedFuture(
                new AdminEnrollmentPreviewDTO("1001", uid, List.of())));
        controller.selectStudent(uid);
    }

    private static BiFunction<String, String, ButtonType> alwaysConfirm() {
        return (title, message) -> ButtonType.OK;
    }

    private static AddOfferingStudentDialogController controller(FakeService service,
            BiFunction<String, String, ButtonType> confirmation) {
        return new AddOfferingStudentDialogController(
                service, confirmation, (title, message) -> { }, Runnable::run);
    }

    private static AdminEnrollmentPageView<StudentSearchResultView> page(
            List<StudentSearchResultView> items, long total, int page, int size) {
        return new AdminEnrollmentPageView<>(items, total, page, size);
    }

    private static StudentSearchResultView student(String uid, String name) {
        return new StudentSearchResultView(uid, name, "软件工程", 2024, "ACTIVE");
    }

    private static ScheduleConflictDTO risk(ScheduleConflictSeverityDTO severity, String message) {
        return new ScheduleConflictDTO("CAPACITY", severity, "20240031", "1001", 0, 0, 0, 0, message);
    }

    private static AdminOperationResultView<OfferingStudentView> result(String uid, String name) {
        return new AdminOperationResultView<>(null, "OK", "已将学生加入教学班",
                new OfferingStudentView("50031", uid, name, "软件工程", 2024, "ENROLLED", true, null));
    }

    private static AdminOfferingView offering() {
        return new AdminOfferingView("1001", "OFF-1001", "101", 2026, 1, 120, 30,
                "OPEN", "T1001", "张老师", null, null, "SCHEDULED", 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * 可编排搜索、预览与写入结果的假服务，其余目录/排课接口返回空结果。
     */
    static final class FakeService implements AdminCourseService {
        private final Deque<CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>>>
                searchResults = new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminEnrollmentPreviewDTO>> previewResults =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminOperationResultView<OfferingStudentView>>>
                addResults = new ArrayDeque<>();
        final List<String> searchCalls = new ArrayList<>();
        final List<String> previewCalls = new ArrayList<>();
        final List<AdminEnrollmentRequestDTO> addCalls = new ArrayList<>();

        void enqueueSearch(
                CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> result) {
            searchResults.addLast(result);
        }

        void enqueuePreview(CompletableFuture<AdminEnrollmentPreviewDTO> result) {
            previewResults.addLast(result);
        }

        void enqueueAdd(CompletableFuture<AdminOperationResultView<OfferingStudentView>> result) {
            addResults.addLast(result);
        }

        @Override
        public CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> searchStudentsPage(
                String query, int page, int size) {
            searchCalls.add(query + "|" + page + "|" + size);
            if (!searchResults.isEmpty()) return searchResults.removeFirst();
            return CompletableFuture.completedFuture(
                    new AdminEnrollmentPageView<>(List.of(), 0, page, size));
        }

        @Override
        public CompletableFuture<AdminEnrollmentPreviewDTO> previewAdminEnrollment(
                String offeringId, String studentUid) {
            previewCalls.add(offeringId + "|" + studentUid);
            if (!previewResults.isEmpty()) return previewResults.removeFirst();
            return CompletableFuture.completedFuture(
                    new AdminEnrollmentPreviewDTO(offeringId, studentUid, List.of()));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<OfferingStudentView>> addStudentToOffering(
                AdminEnrollmentRequestDTO request) {
            addCalls.add(request);
            if (!addResults.isEmpty()) return addResults.removeFirst();
            return CompletableFuture.completedFuture(new AdminOperationResultView<>
                    (request.getOperationId(), "OK", "已将学生加入教学班", null));
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
