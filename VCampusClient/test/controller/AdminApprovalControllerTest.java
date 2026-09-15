package controller;

import java.lang.reflect.Field;
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
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.AdjustmentRequestStatusDTO;
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
    public static void main(String[] args) throws Exception {
        testDefaultFilterAndRendering();
        testStatusFilterAndConflictsReachTheService();
        testStaleListCannotReplaceNewerResult();
        testListFailureSurfacesTheError();
        testApproveSendsTokenDerivedReviewerData();
        testRejectionRequiresACommentAndConfirmation();
        testCancelledConfirmationDoesNotCallTheService();
        testForceApprovalRequiresAReason();
        testConflictKeepsTheLatestDetail();
        testWithdrawnFilterIsQueryableAndReadOnly();
        testDetailShowsTheRealTargetDateAndFallsBackForLegacyTargets();
        testAdjustedResourcesFallBackPerField();
        testGradeTabFallsBackInsteadOfThrowingOnWithdrawn();
        System.out.println("AdminApprovalControllerTest: PASS");
    }

    private static void testDefaultFilterAndRendering() {
        ControlledService service = new ControlledService();
        service.page = page(AdjustmentRequestStatusDTO.PENDING, 2, 20, summary("970701"), summary("970702"));
        AdminApprovalController controller = controller(service, new Recorder(), "驳回意见");

        controller.loadPage(AdjustmentRequestStatusDTO.PENDING, 1);

        require(service.listCalls.size() == 1 && "PENDING|1|20".equals(service.listCalls.get(0)),
                "the pending filter must load page 1 at the shared page size, saw "
                        + service.listCalls);
        require(controller.requests().size() == 2 && controller.totalCount() == 2
                        && !controller.loading() && controller.errorText() == null,
                "the loaded page must be rendered without an error state");
        require(controller.status() == AdjustmentRequestStatusDTO.PENDING && controller.page() == 1,
                "the controller must remember the active filter");
        require(controller.detail() == null,
                "no request is selected until a row is clicked");
    }

    private static void testStatusFilterAndConflictsReachTheService() {
        ControlledService service = new ControlledService();
        service.page = page(AdjustmentRequestStatusDTO.APPROVED, 1, 20, summary("970701"));
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
        controller.loadPage(AdjustmentRequestStatusDTO.PENDING, 1);
        controller.loadPage(AdjustmentRequestStatusDTO.REJECTED, 1);
        older.complete(page(AdjustmentRequestStatusDTO.PENDING, 1, 20, summary("970701")));
        newer.complete(page(AdjustmentRequestStatusDTO.REJECTED, 1, 20, summary("970702"),
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

        controller.loadPage(AdjustmentRequestStatusDTO.PENDING, 1);

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

        AdjustmentRequestDetailDTO approved = detail(AdjustmentRequestStatusDTO.APPROVED, List.of());
        service.reviews.addLast(CompletableFuture.failedFuture(new AdminCourseServiceException(
                MessageCode.CONFLICT, "调课申请已被处理，请刷新后重试", approved, List.of())));
        controller.approveSelected();

        require(controller.detail() != null
                        && controller.detail().getStatus() == AdjustmentRequestStatusDTO.APPROVED,
                "a conflict must render the latest server state");
        require(recorder.lastError() != null
                        && recorder.lastError().contains("调课申请已被处理"),
                "a conflict must report the server message, saw " + recorder.lastError());
        require(service.listCalls.size() == 1,
                "a conflict must also refresh the list");
    }

    /**
     * T5：调课筛选必须能查 WITHDRAWN（教师撤销是终态、不是管理员驳回），且终态只读。
     */
    private static void testWithdrawnFilterIsQueryableAndReadOnly() {
        ControlledService service = new ControlledService();
        service.page = page(AdjustmentRequestStatusDTO.WITHDRAWN, 1, 20, summary("9005"));
        AdminApprovalController controller = controller(service, new Recorder(), null);

        controller.applyStatus(AdminApprovalController.WITHDRAWN_LABEL);

        require("WITHDRAWN|1|20".equals(service.listCalls.get(0)),
                "choosing 已撤销 must query the withdraw status, saw " + service.listCalls);
        require(controller.status() == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the controller must remember the withdraw filter");
        require(!AdminApprovalController.actionable(detail(AdjustmentRequestStatusDTO.WITHDRAWN,
                        List.of())),
                "a withdrawn request is read-only and must not offer approval buttons");
        require(AdminApprovalController.actionable(detail(AdjustmentRequestStatusDTO.PENDING,
                        List.of())),
                "only a pending request may offer approval buttons");
    }

    /**
     * T5：详情必须显示实际目标日期；历史 NULL 目标日期保持旧显示，教师申请（无新教师/教室）
     * 回落到目标原快照。
     */
    private static void testDetailShowsTheRealTargetDateAndFallsBackForLegacyTargets() {
        AdjustmentRequestDetailDTO dated = new AdjustmentRequestDetailDTO("970703", "2001", "T1001",
                "教师出差", AdjustmentRequestStatusDTO.PENDING, 1, 5, 5, 6, null, null, null,
                List.of(new AdjustmentTargetDTO("8005", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-10-30")),
                List.of(), "2026-09-10T02:00:00Z", null, null, null);

        List<AdminApprovalController.ArrangementRow> rows = AdminApprovalController.arrangementRows(
                dated);
        String adjusted = rows.get(0).adjusted();
        require(adjusted.startsWith("2026-10-30 周五 第 5-6 节"),
                "the adjusted column must start with the real target date, saw " + adjusted);
        require(adjusted.contains("陈老师, 王助教") && adjusted.contains("A-101"),
                "a teacher request carries no new resources, so the target snapshot must be "
                        + "shown, saw " + adjusted);
        require(AdminApprovalController.detailLines(dated).stream()
                        .anyMatch(line -> line.startsWith("新安排：")
                                && line.contains("目标日期：2026-10-30")),
                "the detail lines must carry the target date, saw "
                        + AdminApprovalController.detailLines(dated));

        List<AdminApprovalController.ArrangementRow> legacy = AdminApprovalController
                .arrangementRows(detail(AdjustmentRequestStatusDTO.PENDING, List.of()));
        require(legacy.get(0).adjusted().startsWith("周五 第 3-4 节"),
                "a legacy target without a date must keep the weekday-first display, saw "
                        + legacy.get(0).adjusted());
    }

    /**
     * 终审修复：服务端按字段写入替换资源（{@code ScheduleAdjustmentApprovalService} 只替换请求
     * 里给出的资源，其余保留目标原值），所以教师与助教必须各自独立回落到目标原快照：只换教师时
     * 保留原助教、只换助教时保留原教师、都不换（教师申请）时显示原快照。请求级与目标级两个
     * 渲染器共用同一套回落。
     */
    private static void testAdjustedResourcesFallBackPerField() {
        // 只换教师：未替换的助教保留原值。
        AdjustmentRequestDetailDTO teacherOnly = mixedDetail(
                resource("T2001", "李老师", "teacher"), null, null);
        require(AdminApprovalController.arrangementRows(teacherOnly).get(0).adjusted()
                        .contains("李老师, 王助教"),
                "a teacher-only replacement must keep the original assistant, saw "
                        + AdminApprovalController.arrangementRows(teacherOnly).get(0).adjusted());
        require(requestLine(teacherOnly).contains("李老师, 王助教"),
                "the request-level line must keep the original assistant too, saw "
                        + requestLine(teacherOnly));

        // 只换助教：未替换的教师保留原值。
        AdjustmentRequestDetailDTO assistantOnly = mixedDetail(null,
                resource("T3001", "赵助教", "teacher"), null);
        require(AdminApprovalController.arrangementRows(assistantOnly).get(0).adjusted()
                        .contains("张老师, 赵助教"),
                "an assistant-only replacement must keep the original teacher, saw "
                        + AdminApprovalController.arrangementRows(assistantOnly).get(0).adjusted());
        require(requestLine(assistantOnly).contains("张老师, 赵助教"),
                "the request-level line must keep the original teacher too, saw "
                        + requestLine(assistantOnly));

        // 教师与助教同时替换：两个新资源都要出现。
        AdjustmentRequestDetailDTO both = mixedDetail(resource("T2001", "李老师", "teacher"),
                resource("T3001", "赵助教", "teacher"), null);
        require(AdminApprovalController.arrangementRows(both).get(0).adjusted()
                        .contains("李老师, 赵助教"),
                "a full replacement must show both new persons, saw "
                        + AdminApprovalController.arrangementRows(both).get(0).adjusted());
        require(requestLine(both).contains("李老师, 赵助教"),
                "the request-level line must show both new persons, saw " + requestLine(both));

        // 都不替换（教师申请）：目标级与请求级都显示目标原快照，而不是占位符。
        AdjustmentRequestDetailDTO unchanged = mixedDetail(null, null, null);
        String unchangedRow = AdminApprovalController.arrangementRows(unchanged).get(0).adjusted();
        require(unchangedRow.contains("张老师, 王助教") && unchangedRow.contains("教四-201"),
                "an unreplaced request must show the target snapshot, saw " + unchangedRow);
        require(requestLine(unchanged).contains("张老师, 王助教")
                        && requestLine(unchanged).contains("教四-201"),
                "the request-level line must show the target snapshot too, saw "
                        + requestLine(unchanged));

        // 只换教室：人员仍是原快照，教室显示新资源。
        AdjustmentRequestDetailDTO classroomOnly = mixedDetail(null, null,
                resource("3002", "教二-305", "classroom"));
        String classroomRow = AdminApprovalController.arrangementRows(classroomOnly)
                .get(0).adjusted();
        require(classroomRow.contains("张老师, 王助教") && classroomRow.contains("教二-305"),
                "a classroom-only replacement must keep the original persons, saw " + classroomRow);
        require(requestLine(classroomOnly).contains("教二-305"),
                "the request-level line must show the new classroom, saw "
                        + requestLine(classroomOnly));
        require(requestLine(classroomOnly).contains("张老师, 王助教"),
                "the request-level line must keep the original persons too, saw "
                        + requestLine(classroomOnly));
    }

    /**
     * T5：共享筛选切到成绩页时 WITHDRAWN 没有三态对应项，必须回落而不是抛异常。终审修复：
     * 回落后组合框标签必须与成绩页实际加载的行一致（此前标签仍显示 已撤销、行却是待审批）。
     */
    private static void testGradeTabFallsBackInsteadOfThrowingOnWithdrawn() throws Exception {
        require(AdminApprovalController.gradeStatus(AdjustmentRequestStatusDTO.WITHDRAWN) == null,
                "the grade tab has no withdraw state; it must fall back to its own default");
        require(AdminApprovalController.gradeStatus(AdjustmentRequestStatusDTO.APPROVED)
                        == ApprovalStatusDTO.APPROVED,
                "the shared three states must still map by name");

        ControlledService service = new ControlledService();
        Recorder recorder = new Recorder();
        AdminApprovalController shell = controller(service, recorder, null);
        GradeApprovalController child = new GradeApprovalController(service,
                (title, message) -> ButtonType.OK, recorder::info, recorder::error,
                Runnable::run, prompt -> null);
        Field field = AdminApprovalController.class.getDeclaredField("gradePageController");
        field.setAccessible(true);
        field.set(shell, child);

        shell.applyStatus(AdminApprovalController.WITHDRAWN_LABEL);
        shell.showGrades();

        require("PENDING|1|20".equals(service.gradeListCalls.get(0)),
                "the grade tab must load the fallback PENDING rows, saw " + service.gradeListCalls);
        require(AdminApprovalController.PENDING_LABEL.equals(
                        AdminApprovalController.statusLabel(shell.status())),
                "the filter label must match the rows the grade tab loaded, saw "
                        + AdminApprovalController.statusLabel(shell.status()));

        // 停在成绩页上直接改共享筛选也一样：已撤销没有三态对应项，状态与标签必须一起回落，
        // 否则组合框又会和已加载的行不符。
        shell.applyStatus(AdminApprovalController.WITHDRAWN_LABEL);
        require("PENDING|1|20".equals(service.gradeListCalls.get(1))
                        && AdminApprovalController.PENDING_LABEL.equals(
                                AdminApprovalController.statusLabel(shell.status())),
                "changing the filter on the grade tab must keep the label and the rows aligned,"
                        + " saw " + service.gradeListCalls + " / "
                        + AdminApprovalController.statusLabel(shell.status()));
    }

    // ------------------------------------------------------------------ 夹具

    private static AdminApprovalController controller(ControlledService service, Recorder recorder,
                                                      String promptAnswer) {
        return new AdminApprovalController(service, (title, message) -> ButtonType.OK,
                recorder::info, recorder::error, Runnable::run, prompt -> promptAnswer);
    }

    private static AdjustmentRequestSummaryDTO summary(String requestId) {
        return new AdjustmentRequestSummaryDTO(requestId, "数据结构", "OFF-1001", "T1001", "张老师",
                2, AdjustmentRequestStatusDTO.PENDING, "2026-09-10T02:00:00Z");
    }

    private static AdjustmentRequestPageDTO page(AdjustmentRequestStatusDTO status, int number, int size,
            AdjustmentRequestSummaryDTO... items) {
        return new AdjustmentRequestPageDTO(List.of(items), items.length, number, size);
    }

    private static AdjustmentRequestDetailDTO detail(AdjustmentRequestStatusDTO status,
                                                     List<ScheduleConflictDTO> conflicts) {
        return new AdjustmentRequestDetailDTO("970701", "2001", "T1001", "教师出差", status, 3, 5, 3, 4,
                new ScheduleResourceDTO("T2001", "T2001", "李老师", "teacher", 0), null,
                new ScheduleResourceDTO("3002", "3002", "教二-305", "classroom", 120),
                List.of(new AdjustmentTargetDTO("8001", 1, "2026-09-08T00:00:00Z",
                        "2026-09-08T01:35:00Z", "张老师", null, "教四-201")),
                conflicts, "2026-09-10T02:00:00Z", null, null, null);
    }

    /** 按字段回落用例的详情夹具：目标原快照固定为 张老师/王助教/教四-201。 */
    private static AdjustmentRequestDetailDTO mixedDetail(ScheduleResourceDTO newTeacher,
            ScheduleResourceDTO newAssistant, ScheduleResourceDTO newClassroom) {
        return new AdjustmentRequestDetailDTO("970704", "2001", "T1001", "教师出差",
                AdjustmentRequestStatusDTO.PENDING, 2, 4, 5, 6, newTeacher, newAssistant,
                newClassroom,
                List.of(new AdjustmentTargetDTO("8006", 2, "2026-09-15T00:00:00Z",
                        "2026-09-15T01:35:00Z", "张老师", "王助教", "教四-201", "2026-09-18")),
                List.of(), "2026-09-10T02:00:00Z", null, null, null);
    }

    private static ScheduleResourceDTO resource(String id, String name, String type) {
        return new ScheduleResourceDTO(id, id, name, type, 0);
    }

    /** 详情里的请求级“新安排”行；缺失即夹具失效，直接失败而不是静默跳过。 */
    private static String requestLine(AdjustmentRequestDetailDTO detail) {
        return AdminApprovalController.detailLines(detail).stream()
                .filter(line -> line.startsWith("新安排：")).findFirst()
                .orElseThrow(() -> new AssertionError("missing 新安排 line in "
                        + AdminApprovalController.detailLines(detail)));
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
        private final List<String> gradeListCalls = new ArrayList<>();
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

        /**
         * T5 起审批页调用四态主名 {@code listAdjustmentRequestsByStatus}；替身必须覆写主名，
         * 只覆写旧别名会让调用落到默认实现上直接抛 {@code UnsupportedOperationException}。
         */
        @Override
        public CompletableFuture<AdjustmentRequestPageDTO> listAdjustmentRequestsByStatus(
                AdjustmentRequestStatusDTO status, int pageNumber, int size) {
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
                    detail == null ? AdminApprovalControllerTest.detail(AdjustmentRequestStatusDTO.PENDING,
                            List.of()) : detail);
        }

        /**
         * 成绩子页的替身入口：外壳切换标签页时会驱动子页加载，缺少覆写会落到默认实现上
         * 直接抛 {@code UnsupportedOperationException}。
         */
        @Override
        public CompletableFuture<GradeSubmissionPageDTO> listGradeSubmissionsPage(
                ApprovalStatusDTO status, int pageNumber, int size) {
            gradeListCalls.add(status + "|" + pageNumber + "|" + size);
            return CompletableFuture.completedFuture(
                    new GradeSubmissionPageDTO(List.of(), 0, pageNumber, size));
        }

        @Override
        public CompletableFuture<AdminOperationResultView<AdjustmentRequestDetailDTO>>
                reviewAdjustmentRequest(ApprovalDecisionRequestDTO request) {
            decisions.add(request);
            if (!reviews.isEmpty()) return reviews.removeFirst();
            return CompletableFuture.completedFuture(new AdminOperationResultView<>(
                    request.getOperationId(), "OK", "调课申请已通过",
                    AdminApprovalControllerTest.detail(AdjustmentRequestStatusDTO.APPROVED, List.of())));
        }
    }
}
