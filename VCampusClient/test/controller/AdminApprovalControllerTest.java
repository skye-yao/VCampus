package controller;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import javafx.scene.control.ButtonType;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.SocketAdminCourseService.AdminCourseServiceException;

/**
 * 无 JavaFX 依赖的审批控制器测试：注入假服务、{@code Runnable::run} 的 FX 执行器与文本输入。
 */
public final class AdminApprovalControllerTest {
    public static void main(String[] args) {
        testDefaultFilterAndRendering();
        testStatusFilterAndConflictsReachTheService();
        testStaleListCannotReplaceNewerResult();
        testListFailureSurfacesTheError();
        testApproveSendsTokenDerivedReviewerData();
        testRejectionRequiresACommentAndConfirmation();
        testCancelledConfirmationDoesNotCallTheService();
        testForceApprovalRequiresAReason();
        testConflictKeepsTheLatestDetail();
        System.out.println("AdminApprovalControllerTest: PASS");
    }

    private static void testDefaultFilterAndRendering() {
        ControlledService service = new ControlledService();
        service.page = page(ApprovalStatusDTO.PENDING, 2, 20, summary("970701"), summary("970702"));
        AdminApprovalController controller = controller(service, new Recorder(), "驳回意见");

        controller.loadPage(ApprovalStatusDTO.PENDING, 1);

        require(service.listCalls.size() == 1 && "PENDING|1|20".equals(service.listCalls.get(0)),
                "the pending filter must load page 1 at the shared page size, saw "
                        + service.listCalls);
        require(controller.requests().size() == 2 && controller.totalCount() == 2
                        && !controller.loading() && controller.errorText() == null,
                "the loaded page must be rendered without an error state");
        require(controller.status() == ApprovalStatusDTO.PENDING && controller.page() == 1,
                "the controller must remember the active filter");
        require(controller.detail() == null,
                "no request is selected until a row is clicked");
    }

    private static void testStatusFilterAndConflictsReachTheService() {
        ControlledService service = new ControlledService();
        service.page = page(ApprovalStatusDTO.APPROVED, 1, 20, summary("970701"));
        AdminApprovalController controller = controller(service, new Recorder(), null);

        controller.applyStatus(AdminApprovalController.APPROVED_LABEL);

        require("APPROVED|1|20".equals(service.listCalls.get(0)),
                "choosing 已通过 must query that status, saw " + service.listCalls);
        controller.applyStatus(AdminApprovalController.APPROVED_LABEL);
        require(service.listCalls.size() == 1,
                "re-choosing the active status must not reload");
        controller.applyStatus("未知状态");
        require("PENDING|1|20".equals(service.listCalls.get(1)),
                "an unknown label must fall back to PENDING, saw " + service.listCalls);
    }

    private static void testStaleListCannotReplaceNewerResult() {
        ControlledService service = new ControlledService();
        AdminApprovalController controller = controller(service, new Recorder(), null);

        CompletableFuture<AdjustmentRequestPageDTO> older = new CompletableFuture<>();
        CompletableFuture<AdjustmentRequestPageDTO> newer = new CompletableFuture<>();
        service.pages.addLast(older);
        service.pages.addLast(newer);
        controller.loadPage(ApprovalStatusDTO.PENDING, 1);
        controller.loadPage(ApprovalStatusDTO.REJECTED, 1);
        older.complete(page(ApprovalStatusDTO.PENDING, 1, 20, summary("970701")));
        newer.complete(page(ApprovalStatusDTO.REJECTED, 1, 20, summary("970702"),
                summary("970703")));

        require(controller.requests().size() == 2
                        && "970702".equals(controller.requests().get(0).getRequestId()),
                "a stale page must not replace the newer result");
    }

    private static void testListFailureSurfacesTheError() {
        ControlledService service = new ControlledService();
        AdminApprovalController controller = controller(service, new Recorder(), null);
        service.pages.addLast(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.ERROR, "审批服务暂不可用")));

        controller.loadPage(ApprovalStatusDTO.PENDING, 1);

        require("审批服务暂不可用".equals(controller.errorText())
                        && controller.requests().isEmpty() && !controller.loading(),
                "a failed page load must surface the message and clear the rows, saw "
                        + controller.errorText());
    }

    private static void testApproveSendsTokenDerivedReviewerData() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminApprovalController controller = controller(service, recorder, null);
        controller.loadDetail("970701");

        controller.approveSelected();

        ApprovalDecisionRequestDTO decision = service.decisions.get(0);
        require("970701".equals(decision.getRequestId()) && decision.getExpectedVersion() == 3
                        && decision.isApproved() && !decision.isForce()
                        && decision.getReviewComment() == null
                        && decision.getOverrideReason() == null,
                "approving must send the current request identity and version");
        require(decision.getOperationId() != null && decision.getOperationId().length() == 36,
                "approving must mint a canonical operation id, saw " + decision.getOperationId());
        require(recorder.lastInfo() != null && recorder.lastInfo().contains("调课申请已通过"),
                "a successful approval must report the server message");
        require(service.detailCalls.size() == 2,
                "a successful approval must reload the detail and the page, saw "
                        + service.detailCalls);
    }

    private static void testRejectionRequiresACommentAndConfirmation() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminApprovalController controller = controller(service, recorder, "  材料不足  ");
        controller.loadDetail("970701");

        controller.rejectSelected();

        require(service.decisions.size() == 1
                        && !service.decisions.get(0).isApproved()
                        && "材料不足".equals(service.decisions.get(0).getReviewComment()),
                "rejecting must send the trimmed comment");
    }

    private static void testCancelledConfirmationDoesNotCallTheService() {
        ControlledService service = new ControlledService();
        AdminApprovalController controller = new AdminApprovalController(service,
                (title, message) -> ButtonType.CANCEL, (title, message) -> { },
                (title, message) -> { }, Runnable::run, prompt -> "意见");
        controller.loadDetail("970701");
        controller.rejectSelected();

        require(service.decisions.isEmpty(),
                "a cancelled confirmation must not reach the service");
    }

    private static void testForceApprovalRequiresAReason() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminApprovalController controller = controller(service, recorder, "   ");
        controller.loadDetail("970701");

        controller.forceApproveSelected();

        require(service.decisions.isEmpty()
                        && recorder.lastError() != null
                        && recorder.lastError().contains("强制通过必须填写原因"),
                "a blank force reason must be reported and must not call the service, saw "
                        + recorder.lastError());
    }

    private static void testConflictKeepsTheLatestDetail() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminApprovalController controller = controller(service, recorder, null);
        controller.loadDetail("970701");

        AdjustmentRequestDetailDTO approved = detail(ApprovalStatusDTO.APPROVED, List.of());
        service.reviews.addLast(CompletableFuture.failedFuture(new AdminCourseServiceException(
                MessageCode.CONFLICT, "调课申请已被处理，请刷新后重试", approved, List.of())));
        controller.approveSelected();

        require(controller.detail() != null
                        && controller.detail().getStatus() == ApprovalStatusDTO.APPROVED,
                "a conflict must render the latest server state");
        require(recorder.lastError() != null
                        && recorder.lastError().contains("调课申请已被处理"),
                "a conflict must report the server message, saw " + recorder.lastError());
        require(service.listCalls.size() == 1,
                "a conflict must also refresh the list");
    }

    // ------------------------------------------------------------------ 夹具

    private static AdminApprovalController controller(ControlledService service, Recorder recorder,
                                                      String promptAnswer) {
        return new AdminApprovalController(service, (title, message) -> ButtonType.OK,
                recorder::info, recorder::error, Runnable::run, prompt -> promptAnswer);
    }

    private static AdjustmentRequestSummaryDTO summary(String requestId) {
        return new AdjustmentRequestSummaryDTO(requestId, "数据结构", "OFF-1001", "T1001", "张老师",
                2, ApprovalStatusDTO.PENDING, "2026-09-10T02:00:00Z");
    }

    private static AdjustmentRequestPageDTO page(ApprovalStatusDTO status, int number, int size,
            AdjustmentRequestSummaryDTO... items) {
        return new AdjustmentRequestPageDTO(List.of(items), items.length, number, size);
    }

    private static AdjustmentRequestDetailDTO detail(ApprovalStatusDTO status,
                                                     List<ScheduleConflictDTO> conflicts) {
        return new AdjustmentRequestDetailDTO("970701", "2001", "T1001", "教师出差", status, 3, 5, 3, 4,
                new ScheduleResourceDTO("T2001", "T2001", "李老师", "teacher", 0), null,
                new ScheduleResourceDTO("3002", "3002", "教二-305", "classroom", 120),
                List.of(new AdjustmentTargetDTO("8001", 1, "2026-09-08T00:00:00Z",
                        "2026-09-08T01:35:00Z", "张老师", null, "教四-201")),
                conflicts, "2026-09-10T02:00:00Z", null, null, null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Recorder {
        private final List<String> infos = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        private void info(String title, String message) {
            infos.add(message);
        }

        private void error(String title, String message) {
            errors.add(message);
        }

        private String lastInfo() {
            return infos.isEmpty() ? null : infos.get(infos.size() - 1);
        }

        private String lastError() {
            return errors.isEmpty() ? null : errors.get(errors.size() - 1);
        }
    }

    private static final class ControlledService implements AdminCourseService {
        private final Deque<CompletableFuture<AdjustmentRequestPageDTO>> pages = new ArrayDeque<>();
        private final Deque<CompletableFuture<AdjustmentRequestDetailDTO>> details =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>>>
                reviews = new ArrayDeque<>();
        private final List<String> listCalls = new ArrayList<>();
        private final List<String> detailCalls = new ArrayList<>();
        private final List<ApprovalDecisionRequestDTO> decisions = new ArrayList<>();
        private AdjustmentRequestPageDTO page;
        private AdjustmentRequestDetailDTO detail;

        @Override
        public CompletableFuture<List<model.course.admin.AdminCourseView>> listCourses(
                String query, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminCourseView>>
                createCourse(CourseEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminCourseView>>
                updateCourse(CourseEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminCourseView>>
                archiveCourse(String courseId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminCourseView>>
                restoreCourse(String courseId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<model.course.admin.AdminOfferingView>> listOfferings(
                String courseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminOfferingView>>
                createOffering(OfferingEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminOfferingView>>
                updateOffering(OfferingEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<model.course.admin.AdminOfferingView>>
                cancelOffering(String offeringId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsPage(
                ApprovalStatusDTO status, int pageNumber, int size) {
            listCalls.add(status + "|" + pageNumber + "|" + size);
            if (!pages.isEmpty()) return pages.removeFirst();
            return CompletableFuture.completedFuture(page == null
                    ? new AdjustmentRequestPageDTO(List.of(), 0, pageNumber, size) : page);
        }

        @Override
        public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
            detailCalls.add(requestId);
            if (!details.isEmpty()) return details.removeFirst();
            return CompletableFuture.completedFuture(
                    detail == null ? AdminApprovalControllerTest.detail(ApprovalStatusDTO.PENDING,
                            List.of()) : detail);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>>
                reviewAdjustmentRequest(ApprovalDecisionRequestDTO request) {
            decisions.add(request);
            if (!reviews.isEmpty()) return reviews.removeFirst();
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    request.getOperationId(), "OK", "调课申请已通过",
                    AdminApprovalControllerTest.detail(ApprovalStatusDTO.APPROVED, List.of())));
        }
    }
}
