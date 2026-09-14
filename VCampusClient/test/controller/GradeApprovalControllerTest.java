package controller;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import javafx.scene.control.ButtonType;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.SocketAdminCourseService.AdminCourseServiceException;

/**
 * 无 JavaFX 依赖的成绩审批控制器测试：注入假服务、{@code Runnable::run} 的 FX 执行器与文本输入。
 *
 * <p>所有用户可见的渲染决策都由纯函数给出（行标题、指标卡、分布行、逐项成绩），因此可以在
 * 没有 JavaFX 运行时的环境里断言；同时验证外壳页共享状态筛选、隐藏标签页不发请求等交互约束。
 */
public final class GradeApprovalControllerTest {
    public static void main(String[] args) throws Exception {
        testDefaultFilterIsPendingAndLoadsPage();
        testStatusFilterReachesTheServiceWithoutReloadingTheSameFilter();
        testStaleListResponseCannotReplaceTheNewerResult();
        testListFailureSurfacesTheError();
        testSelectingARowLoadsTheDetail();
        testNullComponentScoreRendersAsDash();
        testApproveRequiresConfirmation();
        testRejectRequiresATrimmedReason();
        testNoDoubleSubmissionWhileADecisionIsInFlight();
        testSuccessfulDecisionReloadsListAndDetail();
        testOperationIdIsReusedOnlyForAnIdenticalRetry();
        testConflictRendersTheLatestServerState();
        testCompletedSubmissionRendersReadOnlyWithReview();
        testShellSharesFilterAndOnlyLoadsTheActiveTab();
        System.out.println("GradeApprovalControllerTest: PASS");
    }

    private static void testDefaultFilterIsPendingAndLoadsPage() {
        ControlledService service = new ControlledService();
        service.gradePage = gradePage(ApprovalStatusDTO.PENDING, 2, 20,
                summary("9001", ApprovalStatusDTO.PENDING), summary("9002", ApprovalStatusDTO.PENDING));
        GradeApprovalController controller = controller(service, new Recorder(), null);

        controller.loadPage(ApprovalStatusDTO.PENDING, 1);

        require(service.gradeListCalls.size() == 1
                        && "PENDING|1|20".equals(service.gradeListCalls.get(0)),
                "the pending filter must load page 1 at the shared page size, saw "
                        + service.gradeListCalls);
        require(controller.submissions().size() == 2 && controller.totalCount() == 2
                        && !controller.loading() && controller.errorText() == null,
                "the loaded page must be rendered without an error state");
        require(controller.status() == ApprovalStatusDTO.PENDING && controller.page() == 1,
                "the controller must remember the active filter");
        require(controller.detail() == null, "no submission is selected until a row is clicked");
    }

    private static void testStatusFilterReachesTheServiceWithoutReloadingTheSameFilter() {
        ControlledService service = new ControlledService();
        service.gradePage = gradePage(ApprovalStatusDTO.APPROVED, 1, 20,
                summary("9001", ApprovalStatusDTO.APPROVED));
        GradeApprovalController controller = controller(service, new Recorder(), null);

        controller.loadPage(ApprovalStatusDTO.APPROVED, 1);

        require(service.gradeListCalls.size() == 1
                        && "APPROVED|1|20".equals(service.gradeListCalls.get(0))
                        && controller.status() == ApprovalStatusDTO.APPROVED,
                "an explicit load must query the requested status, saw " + service.gradeListCalls);
    }

    private static void testStaleListResponseCannotReplaceTheNewerResult() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = controller(service, new Recorder(), null);

        CompletableFuture<GradeSubmissionPageDTO> older = new CompletableFuture<>();
        CompletableFuture<GradeSubmissionPageDTO> newer = new CompletableFuture<>();
        service.gradePages.addLast(older);
        service.gradePages.addLast(newer);
        controller.loadPage(ApprovalStatusDTO.PENDING, 1);
        controller.loadPage(ApprovalStatusDTO.REJECTED, 1);
        older.complete(gradePage(ApprovalStatusDTO.PENDING, 1, 20,
                summary("9001", ApprovalStatusDTO.PENDING)));
        newer.complete(gradePage(ApprovalStatusDTO.REJECTED, 1, 20,
                summary("9002", ApprovalStatusDTO.REJECTED),
                summary("9003", ApprovalStatusDTO.REJECTED)));

        require(controller.submissions().size() == 2
                        && "9002".equals(controller.submissions().get(0).getSubmissionId()),
                "a stale page must not replace the newer result");
    }

    private static void testListFailureSurfacesTheError() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = controller(service, new Recorder(), null);
        service.gradePages.addLast(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.ERROR, "成绩审批服务暂不可用")));

        controller.loadPage(ApprovalStatusDTO.PENDING, 1);

        require("成绩审批服务暂不可用".equals(controller.errorText())
                        && controller.submissions().isEmpty() && !controller.loading(),
                "a failed page load must surface the message and clear the rows, saw "
                        + controller.errorText());
    }

    private static void testSelectingARowLoadsTheDetail() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = controller(service, new Recorder(), null);

        controller.select("9001");

        require(List.of("9001").equals(service.gradeDetailCalls),
                "selecting a row must load that submission, saw " + service.gradeDetailCalls);
        require(controller.detail() != null
                        && "9001".equals(controller.detail().getSummary().getSubmissionId()),
                "the loaded detail must be rendered");
    }

    private static void testNullComponentScoreRendersAsDash() {
        require("--".equals(GradeApprovalController.scoreText(null)),
                "a null score must render as the dash placeholder");
        require(!"--".equals(GradeApprovalController.scoreText(0.0)),
                "a genuine zero must not be mistaken for a missing score");

        GradeSubmissionItemDTO sparse = item("8101", "S1001", "张三",
                null, 78.0, null, null, null, null, null);
        List<String> cells = GradeApprovalController.itemCells(sparse);

        require(cells.size() == 9, "one cell per score column, saw " + cells.size());
        require("--".equals(cells.get(2)) && "--".equals(cells.get(4))
                        && "--".equals(cells.get(7)) && "--".equals(cells.get(8)),
                "every missing component, level and point must read as a dash, saw " + cells);
        require("78.0".equals(cells.get(3)),
                "a present score must keep its value, saw " + cells.get(3));
    }

    private static void testApproveRequiresConfirmation() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = new GradeApprovalController(service,
                (title, message) -> ButtonType.CANCEL, (title, message) -> { },
                (title, message) -> { }, Runnable::run, prompt -> "意见");
        controller.loadDetail("9001");

        controller.approveSelected();

        require(service.gradeDecisions.isEmpty(),
                "a cancelled confirmation must not reach the service");
    }

    private static void testRejectRequiresATrimmedReason() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        GradeApprovalController blank = controller(service, recorder, "   ");
        blank.loadDetail("9001");
        blank.rejectSelected();

        require(service.gradeDecisions.isEmpty()
                        && recorder.lastError() != null
                        && recorder.lastError().contains("驳回必须填写审批意见"),
                "a blank reject reason must be refused client-side, saw " + recorder.lastError());

        ControlledService second = new ControlledService();
        GradeApprovalController trimmed = controller(second, new Recorder(), "  材料不足  ");
        trimmed.loadDetail("9001");
        trimmed.rejectSelected();

        require(second.gradeDecisions.size() == 1
                        && !second.gradeDecisions.get(0).isApproved()
                        && "材料不足".equals(second.gradeDecisions.get(0).getReviewComment()),
                "rejecting must send the trimmed comment");
        require(!second.gradeDecisions.get(0).isForce(),
                "grade approval has no overridable conflict and must never send force");
    }

    private static void testNoDoubleSubmissionWhileADecisionIsInFlight() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = controller(service, new Recorder(), null);
        controller.loadDetail("9001");
        service.gradeReviews.addLast(new CompletableFuture<>());

        controller.approveSelected();
        controller.approveSelected();

        require(service.gradeDecisions.size() == 1 && controller.inFlight(),
                "decision controls must be locked while a decision is in flight, saw "
                        + service.gradeDecisions.size());
    }

    private static void testSuccessfulDecisionReloadsListAndDetail() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        GradeApprovalController controller = controller(service, recorder, null);
        controller.loadDetail("9001");

        controller.approveSelected();

        require(service.gradeDecisions.size() == 1
                        && service.gradeDecisions.get(0).isApproved()
                        && "9001".equals(service.gradeDecisions.get(0).getRequestId())
                        && service.gradeDecisions.get(0).getExpectedVersion() == 3,
                "approving must send the current submission identity and version");
        require(recorder.lastInfo() != null && recorder.lastInfo().contains("成绩提交已通过"),
                "a successful decision must report the server message, saw " + recorder.lastInfo());
        require(service.gradeDetailCalls.size() == 2 && service.gradeListCalls.size() == 1,
                "a successful decision must reload both the detail and the page, saw "
                        + service.gradeDetailCalls + " / " + service.gradeListCalls);
        require(!controller.inFlight(), "the in-flight lock must clear after the decision");
    }

    private static void testOperationIdIsReusedOnlyForAnIdenticalRetry() {
        ControlledService service = new ControlledService();
        GradeApprovalController controller = controller(service, new Recorder(), null);
        controller.loadDetail("9001");
        service.gradeReviews.addLast(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.ERROR, "网络中断")));
        service.gradeReviews.addLast(CompletableFuture.failedFuture(
                new AdminCourseServiceException(MessageCode.ERROR, "网络中断")));

        controller.approveSelected();
        controller.approveSelected();

        require(service.gradeDecisions.size() == 2
                        && service.gradeDecisions.get(0).getOperationId()
                        .equals(service.gradeDecisions.get(1).getOperationId()),
                "an identical transport retry must reuse the operation id");
    }

    private static void testConflictRendersTheLatestServerState() {
        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        GradeApprovalController controller = controller(service, recorder, null);
        controller.loadDetail("9001");
        service.gradeReviews.addLast(CompletableFuture.failedFuture(new AdminCourseServiceException(
                MessageCode.CONFLICT, "成绩提交已被处理，请刷新后重试",
                detail("9001", ApprovalStatusDTO.APPROVED, "审核员", "2026-09-11T03:00:00Z", "已通过"))));

        controller.approveSelected();

        require(controller.detail() != null
                        && controller.detail().getSummary().getStatus() == ApprovalStatusDTO.APPROVED,
                "a conflict must render the latest server state");
        require(recorder.lastError() != null
                        && recorder.lastError().contains("成绩提交已被处理"),
                "a conflict must report the server message, saw " + recorder.lastError());
        require(service.gradeListCalls.size() == 1, "a conflict must also refresh the list");
    }

    private static void testCompletedSubmissionRendersReadOnlyWithReview() {
        GradeSubmissionDetailDTO completed = detail("9001", ApprovalStatusDTO.APPROVED,
                "审核员", "2026-09-11T03:00:00Z", "成绩无误");

        List<String> lines = GradeApprovalController.detailLines(completed);

        require(lines.stream().anyMatch(line -> line.contains("审批人：审核员")),
                "a completed batch must show the reviewer, saw " + lines);
        require(lines.stream().anyMatch(line -> line.contains("审批时间：2026-09-11T03:00:00Z")),
                "a completed batch must show the review time, saw " + lines);
        require(lines.stream().anyMatch(line -> line.contains("审批意见：成绩无误")),
                "a completed batch must show the review comment, saw " + lines);

        ControlledService service = new ControlledService();
        service.gradeDetail = detail("9001", ApprovalStatusDTO.APPROVED, "审核员",
                "2026-09-11T03:00:00Z", "成绩无误");
        GradeApprovalController controller = controller(service, new Recorder(), null);
        controller.loadDetail("9001");
        controller.decide(true, null);

        require(service.gradeDecisions.isEmpty(),
                "a completed batch must stay read-only and never submit a decision");
    }

    private static void testShellSharesFilterAndOnlyLoadsTheActiveTab() throws Exception {
        ControlledService service = new ControlledService();
        GradeApprovalController child = controller(service, new Recorder(), null);
        AdminApprovalController shell = new AdminApprovalController(service,
                (title, message) -> ButtonType.OK, (title, message) -> { },
                (title, message) -> { }, Runnable::run, prompt -> "意见");
        Field field = AdminApprovalController.class.getDeclaredField("gradePageController");
        field.setAccessible(true);
        field.set(shell, child);

        shell.showGrades();

        require("PENDING|1|20".equals(service.gradeListCalls.get(0))
                        && service.listCalls.isEmpty(),
                "showing the grade tab must load the child with the shared filter and must not "
                        + "touch the hidden adjustment tab, saw " + service.gradeListCalls
                        + " / " + service.listCalls);

        shell.applyStatus(AdminApprovalController.APPROVED_LABEL);

        require("APPROVED|1|20".equals(service.gradeListCalls.get(1))
                        && service.listCalls.isEmpty(),
                "changing the shared filter while the grade tab is active must refresh the child "
                        + "only, saw " + service.gradeListCalls + " / " + service.listCalls);

        shell.showAdjustments();

        require("APPROVED|1|20".equals(service.listCalls.get(0)),
                "switching back must hand the preserved filter to the adjustment tab, saw "
                        + service.listCalls);
    }

    // ------------------------------------------------------------------ 夹具

    private static GradeApprovalController controller(ControlledService service, Recorder recorder,
            String promptAnswer) {
        return new GradeApprovalController(service, (title, message) -> ButtonType.OK,
                recorder::info, recorder::error, Runnable::run, prompt -> promptAnswer);
    }

    private static GradeSubmissionSummaryDTO summary(String submissionId, ApprovalStatusDTO status) {
        return new GradeSubmissionSummaryDTO(submissionId, "1001", "数据结构", "OFF-1001", 3,
                "T1001", "张老师", 42, 86.5, 98.0, 55.0, 4, status, "2026-09-10T02:00:00Z");
    }

    private static GradeSubmissionPageDTO gradePage(ApprovalStatusDTO status, int number, int size,
            GradeSubmissionSummaryDTO... items) {
        return new GradeSubmissionPageDTO(List.of(items), items.length, number, size);
    }

    private static GradeSubmissionDetailDTO detail(String submissionId, ApprovalStatusDTO status,
            String reviewedBy, String reviewedAt, String reviewComment) {
        GradeSubmissionSummaryDTO summary = new GradeSubmissionSummaryDTO(submissionId, "1001",
                "数据结构", "OFF-1001", 3, "T1001", "张老师", 2, 86.5, 98.0, 55.0, 1, status,
                "2026-09-10T02:00:00Z");
        return new GradeSubmissionDetailDTO(summary,
                List.of(new GradeDistributionBucketDTO("90-100", 1),
                        new GradeDistributionBucketDTO("0-59", 1)),
                List.of(item("8101", "S1001", "张三", 88.0, 78.0, 90.0, 92.0, 87.0, 3, 3.7),
                        item("8102", "S1002", "李四", null, null, null, null, null, null, null)),
                reviewedBy, reviewedAt, reviewComment);
    }

    private static GradeSubmissionItemDTO item(String enrollmentId, String studentUid,
            String studentName, Double daily, Double midterm, Double experiment, Double finalterm,
            Double score, Integer level, Double point) {
        return new GradeSubmissionItemDTO(enrollmentId, studentUid, studentName, daily, midterm,
                experiment, finalterm, score, level, point);
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
        private final Deque<CompletableFuture<GradeSubmissionPageDTO>> gradePages = new ArrayDeque<>();
        private final Deque<CompletableFuture<GradeSubmissionDetailDTO>> gradeDetails =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<AdminOperationResultView<GradeSubmissionDetailDTO>>>
                gradeReviews = new ArrayDeque<>();
        private final List<String> gradeListCalls = new ArrayList<>();
        private final List<String> gradeDetailCalls = new ArrayList<>();
        private final List<ApprovalDecisionRequestDTO> gradeDecisions = new ArrayList<>();
        // Adjustment side, exercised only by the shared-shell wiring test.
        private final List<String> listCalls = new ArrayList<>();
        private GradeSubmissionPageDTO gradePage;
        private GradeSubmissionDetailDTO gradeDetail;

        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> createCourse(
                CourseEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> updateCourse(
                CourseEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> archiveCourse(
                String courseId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminCourseView>> restoreCourse(
                String courseId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> createOffering(
                OfferingEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> updateOffering(
                OfferingEditorRequestDTO request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdminOfferingView>> cancelOffering(
                String offeringId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<AdminOperationResultView<Void>> deleteDraftOffering(
                String offeringId, int expectedVersion, String operationId) {
            throw new UnsupportedOperationException();
        }

        /**
         * T5 起审批外壳调用四态主名 {@code listAdjustmentRequestsByStatus}；替身必须覆写主名，
         * 只覆写旧别名会让调用落到默认实现上直接抛 {@code UnsupportedOperationException}
         * （共享筛选切回调课页就会踩到）。旧别名由接口默认实现委托到主名，不需要再覆写。
         */
        @Override
        public CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsByStatus(
                AdjustmentRequestStatusDTO status, int pageNumber, int size) {
            listCalls.add(status + "|" + pageNumber + "|" + size);
            return CompletableFuture.completedFuture(
                    new AdjustmentRequestPageDTO(List.of(), 0, pageNumber, size));
        }

        @Override
        public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<GradeSubmissionPageDTO> listGradeSubmissionsPage(
                ApprovalStatusDTO status, int pageNumber, int size) {
            gradeListCalls.add(status + "|" + pageNumber + "|" + size);
            if (!gradePages.isEmpty()) return gradePages.removeFirst();
            return CompletableFuture.completedFuture(gradePage == null
                    ? new GradeSubmissionPageDTO(List.of(), 0, pageNumber, size) : gradePage);
        }

        @Override
        public CompletableFuture<GradeSubmissionDetailDTO> getGradeSubmission(String submissionId) {
            gradeDetailCalls.add(submissionId);
            if (!gradeDetails.isEmpty()) return gradeDetails.removeFirst();
            return CompletableFuture.completedFuture(gradeDetail == null
                    ? GradeApprovalControllerTest.detail(submissionId, ApprovalStatusDTO.PENDING,
                            null, null, null)
                    : gradeDetail);
        }

        @Override
        public CompletableFuture<AdminOperationResultView<GradeSubmissionDetailDTO>>
                reviewGradeSubmission(ApprovalDecisionRequestDTO request) {
            gradeDecisions.add(request);
            if (!gradeReviews.isEmpty()) return gradeReviews.removeFirst();
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    request.getOperationId(), "OK", "成绩提交已通过",
                    GradeApprovalControllerTest.detail(request.getRequestId(),
                            ApprovalStatusDTO.APPROVED, "审核员", "2026-09-11T03:00:00Z", "通过")));
        }
    }
}
