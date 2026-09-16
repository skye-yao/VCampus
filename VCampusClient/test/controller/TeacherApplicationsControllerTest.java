package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import javafx.event.Event;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的「我的申请」控制器测试：合并列表的类型/状态筛选、未读角标与标记已读、
 * 恰好一个类型化详情（调课安排与实时冲突，或成绩批次的只读快照）、两步确认撤销，以及
 * NOT_FOUND/CONFLICT 的呈现。
 *
 * <p>它同时是 FXML 的契约测试：结构化解析 {@link #VIEW}，确认 {@code fx:id}/{@code onAction}
 * 都能在控制器上解析、样式类都在 {@code teacher-course.css} 里。
 */
public final class TeacherApplicationsControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherApplicationsView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String INTERACTION_OFFERING = "9007199254740997";
    private static final String ADJUSTMENT = TeacherApplicationDTO.SCHEDULE_ADJUSTMENT;
    private static final String GRADE = TeacherApplicationDTO.GRADE_SUBMISSION;

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        defaultFilterLoadsPendingApplicationsAndShowsTheUnreadBadge();
        typeFilterNarrowsToOneFactTableAndDropsItsUndefinedStatus();
        statusFilterMapsAllFourStates();
        emptyResultRendersTheEmptyState();
        selectingAnUnreadRowLoadsTheTypedDetailAndMarksItRead();
        aStaleReadConfirmationReloadsInsteadOfGuessing();
        adjustmentDetailKeepsTheLiveConflictSnapshotAndTargetDates();
        legacyTargetsWithoutADateKeepTheOldDisplay();
        targetLineFallsBackPerFieldWhenOnlyOnePersonChanges();
        gradeDetailIsAReadOnlySnapshotWithoutAWithdrawEntry();
        pendingCanWithdrawButTerminalCannot();
        withdrawSendsTheDetailVersionAndRefreshes();
        withdrawCannotBeSentTwiceWhileInFlightOrAfterCancelling();
        withdrawAfterApprovalConflictsAndRefreshes();
        withdrawNotFoundIsNotRenderedAsASystemError();
        staleListCannotReplaceNewerResult();
        listFailureSurfacesTheError();

        viewWiresEveryIdAndAction(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        System.out.println("TeacherApplicationsControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 列表

    private static void defaultFilterLoadsPendingApplicationsAndShowsTheUnreadBadge() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        TeacherApplicationsController controller = controller(service);

        controller.activate();

        require(service.listCalls.equals(List.of("null|PENDING|1|20")),
                "the first page must query every type in pending, saw " + service.listCalls);
        require(controller.applications().size() == 1 && controller.totalCount() == 1
                        && !controller.loading() && controller.errorText() == null,
                "the loaded page must be rendered without an error state");
        require(controller.unreadCount() == 1,
                "the unread badge count must come from the server's rows, saw "
                        + controller.unreadCount());
        require("人机交互导论　CS352-01"
                        .equals(TeacherApplicationsController.summaryTitle(
                                controller.applications().get(0))),
                "a row must name the course and the offering");
        String meta = TeacherApplicationsController.summaryMeta(controller.applications().get(0));
        require(meta.contains("9405") && meta.contains("待审批") && meta.contains("调课申请")
                        && meta.contains("2026-09-14T07:00:00Z"),
                "a row must show its type, id, status and submitted time, saw " + meta);
        require(!controller.canWithdraw(),
                "without a selected request no withdrawal may be offered");
    }

    private static void typeFilterNarrowsToOneFactTableAndDropsItsUndefinedStatus() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);

        require(TeacherApplicationsController.typeLabels()
                        .equals(List.of("全部", "调课申请", "成绩提交")),
                "the page must offer both fact tables plus an unfiltered option");
        require(TeacherApplicationsController.statusLabels(ADJUSTMENT)
                        .equals(List.of("待审批", "已通过", "已驳回", "已撤销"))
                        && TeacherApplicationsController.statusLabels(GRADE)
                        .equals(List.of("待审批", "已通过", "已驳回")),
                "the status options must follow the type's own alphabet");
        require(TeacherApplicationDTO.isStatus(ADJUSTMENT, "WITHDRAWN")
                        && !TeacherApplicationDTO.isStatus(GRADE, "WITHDRAWN"),
                "WITHDRAWN belongs to the adjustment alphabet only, otherwise the two branches"
                        + " below would be indistinguishable");

        controller.applyStatus("已撤销");
        require(service.listCalls.get(0).equals("null|WITHDRAWN|1|20"),
                "the unfiltered type still allows the adjustment-only status, saw "
                        + service.listCalls);
        controller.applyType("成绩提交");
        require(service.listCalls.get(1).equals(GRADE + "|PENDING|1|20"),
                "switching to grades must reset the status that type does not have, saw "
                        + service.listCalls);
        require("PENDING".equals(controller.status()) && GRADE.equals(controller.type()),
                "the controller must remember both filters");
        controller.applyType("调课申请");
        require(service.listCalls.get(2).equals(ADJUSTMENT + "|PENDING|1|20"),
                "switching back must query the adjustment branch, saw " + service.listCalls);
    }

    private static void statusFilterMapsAllFourStates() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);

        controller.applyStatus("已撤销");
        require("null|WITHDRAWN|1|20".equals(service.listCalls.get(0)),
                "choosing 已撤销 must query that status, saw " + service.listCalls);
        controller.applyStatus("已撤销");
        require(service.listCalls.size() == 1,
                "re-choosing the active status must not reload");
        controller.applyStatus("已通过");
        controller.applyStatus("已驳回");
        require("null|APPROVED|1|20".equals(service.listCalls.get(1))
                        && "null|REJECTED|1|20".equals(service.listCalls.get(2)),
                "every terminal filter must reach the service, saw " + service.listCalls);
        require("REJECTED".equals(controller.status()),
                "the controller must remember the active filter");
    }

    private static void emptyResultRendersTheEmptyState() {
        ControlledService service = new ControlledService();
        service.summary = null;
        TeacherApplicationsController controller = controller(service);

        controller.activate();

        require(controller.applications().isEmpty() && controller.totalCount() == 0
                        && controller.totalPages() == 1 && controller.errorText() == null
                        && !controller.loading() && controller.unreadCount() == 0,
                "an account with no application must render an empty page, not an error");
    }

    // ------------------------------------------------------------------ 详情与已读

    private static void selectingAnUnreadRowLoadsTheTypedDetailAndMarksItRead() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1,
                List.of(new ScheduleConflictDTO("ADJUSTMENT_TARGET_ADJUSTED",
                        ScheduleConflictSeverityDTO.BLOCKING, "9202", INTERACTION_OFFERING, 8, 2, 5,
                        6, "该课程实例已有待审批的调课申请")));
        TeacherApplicationsController controller = controller(service);
        controller.activate();

        controller.select(ADJUSTMENT, "9405");

        require(service.detailCalls.equals(List.of(ADJUSTMENT + "|9405")),
                "selecting a row must load exactly that typed application, saw "
                        + service.detailCalls);
        require(controller.detail() != null && controller.detail().getAdjustment() != null
                        && controller.detail().getGrade() == null,
                "the selected detail must be the adjustment variant");
        require(!controller.detail().getSummary().isUnread(),
                "reading the result must leave the page holding the server's read row");

        require(service.sentReads.size() == 1,
                "reading an unread result must mark exactly that row read, saw " + service.sentReads);
        MarkTeacherApplicationReadDTO read = service.sentReads.get(0);
        require(ADJUSTMENT.equals(read.getType()) && "9405".equals(read.getId())
                        && "PENDING:2026-09-14T07:00:00Z".equals(read.getExpectedStateKey()),
                "the confirmation must carry the state key the page actually saw, saw "
                        + read.getType() + "|" + read.getId() + "|" + read.getExpectedStateKey());
        require(controller.unreadCount() == 0,
                "the refreshed list must no longer report the row as unread, saw "
                        + controller.unreadCount());
    }

    private static void aStaleReadConfirmationReloadsInsteadOfGuessing() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();

        // 期间管理员通过了这条申请：服务端拒绝过期确认，并带回当前行。确认在途时先把
        // 重新加载要用的详情与列表排好，再让那次确认以冲突结束——顺序才与真实交错一致。
        TeacherApplicationDTO approved = adjustmentRow("9405", "APPROVED",
                "2026-09-14T07:00:00Z", "2026-09-14T09:00:00Z");
        CompletableFuture<TeacherApplicationDTO> inFlight = new CompletableFuture<>();
        service.reads.addLast(inFlight);
        controller.select(ADJUSTMENT, "9405");
        require(controller.detail() != null && controller.detail().getSummary().isUnread(),
                "the unread detail stays on screen while the confirmation is in flight");

        service.details.addLast(CompletableFuture.completedFuture(
                adjustmentDetail(approved, 2, List.of())));
        service.summary = read(approved);
        inFlight.completeExceptionally(new TeacherCourseServiceException(MessageCode.CONFLICT,
                "申请结果已更新，请刷新后重试", List.of(), null, null, approved));

        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("申请结果已更新"),
                "a stale confirmation must report the server message, saw "
                        + controller.feedbackText());
        require(controller.detail() != null
                        && "APPROVED".equals(controller.detail().getSummary().getStatus()),
                "the page must reload the authoritative state instead of guessing it, saw "
                        + (controller.detail() == null ? null
                                : controller.detail().getSummary().getStatus()));
        require(service.sentReads.size() == 2
                        && "APPROVED:2026-09-14T09:00:00Z"
                        .equals(service.sentReads.get(1).getExpectedStateKey()),
                "the retry must use the fresh key the server sent, saw "
                        + service.sentReads.stream()
                        .map(MarkTeacherApplicationReadDTO::getExpectedStateKey).toList());
        require(controller.unreadCount() == 0,
                "the reloaded row must be read, saw " + controller.unreadCount());
    }

    // ------------------------------------------------------------------ 调课详情

    private static void adjustmentDetailKeepsTheLiveConflictSnapshotAndTargetDates() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1,
                List.of(new ScheduleConflictDTO("ADJUSTMENT_TARGET_ADJUSTED",
                        ScheduleConflictSeverityDTO.BLOCKING, "9202", INTERACTION_OFFERING, 8, 2, 5,
                        6, "该课程实例已有待审批的调课申请")));
        TeacherApplicationsController controller = controller(service);
        controller.activate();

        controller.select(ADJUSTMENT, "9405");

        List<String> lines = controller.renderedDetailLines();
        require(lines.stream().anyMatch(line -> line.startsWith(
                        "目标安排：2026-11-06 周五 第 5-6 节")),
                "the target arrangement must show the real ISO date instead of only the weekday, saw "
                        + lines);
        require(lines.stream().anyMatch(line -> line.startsWith(
                        "目标安排：2026-11-06 周五 第 5-6 节")
                        && line.contains("陈老师, 王助教") && line.contains("A-101")),
                "a teacher request has no new teacher/classroom, so the target snapshot must be "
                        + "shown instead, saw " + lines);
        require(lines.stream().anyMatch(line -> line.startsWith("原安排（第 8 周）")
                        && line.contains("2026-10-27T00:00:00Z~2026-10-27T01:35:00Z")),
                "the original arrangement must name its week and snapshot, saw " + lines);
        require(lines.stream().anyMatch(
                        line -> line.contains("该课程实例已有待审批的调课申请")),
                "the live PENDING conflict snapshot must be rendered, saw " + lines);
        require(lines.stream().anyMatch(line -> line.contains("申请原因：临时调课")),
                "the adjustment reason must survive the reunion of the two fact tables, saw "
                        + lines);
    }

    private static void legacyTargetsWithoutADateKeepTheOldDisplay() {
        ControlledService service = new ControlledService();
        service.summary = rejectedAdjustment();
        service.detail = new TeacherApplicationDetailDTO(rejectedAdjustment(),
                legacyDetail(), null);
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9403");

        List<String> lines = controller.renderedDetailLines();
        String target = lines.stream().filter(line -> line.startsWith("目标安排：")).findFirst()
                .orElseThrow(() -> new AssertionError("missing target line, saw " + lines));
        require(target.contains("周五 第 7-8 节") && !target.contains("20"),
                "a legacy target without a date must keep the weekday-only display, saw " + target);
        require(lines.stream().anyMatch(line -> line.contains("材料不全")),
                "the review comment must be rendered for a terminal request, saw " + lines);
    }

    /**
     * 终审修复：服务端按字段写入替换资源，我的申请页也必须逐字段回落——只换教师时保留原助教、
     * 只换助教时保留原教师、两个都换时都显示新资源、都不换时保持原快照。
     */
    private static void targetLineFallsBackPerFieldWhenOnlyOnePersonChanges() {
        AdjustmentTargetDTO target = new AdjustmentTargetDTO("9203", 8, "2026-10-27T00:00:00Z",
                "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06");

        String teacherOnly = TeacherApplicationsController.targetLine(
                mixedDetail(resource("T2001", "李老师", "teacher"), null, null), target);
        require(teacherOnly.contains("李老师, 王助教"),
                "a teacher-only replacement must keep the original assistant, saw " + teacherOnly);

        String assistantOnly = TeacherApplicationsController.targetLine(
                mixedDetail(null, resource("T3001", "赵助教", "teacher"), null), target);
        require(assistantOnly.contains("陈老师, 赵助教"),
                "an assistant-only replacement must keep the original teacher, saw "
                        + assistantOnly);

        String both = TeacherApplicationsController.targetLine(
                mixedDetail(resource("T2001", "李老师", "teacher"),
                        resource("T3001", "赵助教", "teacher"), null), target);
        require(both.contains("李老师, 赵助教"),
                "a full replacement must show both new persons, saw " + both);

        String unchanged = TeacherApplicationsController.targetLine(
                mixedDetail(null, null, null), target);
        require(unchanged.contains("陈老师, 王助教") && unchanged.contains("A-101"),
                "an unreplaced request must keep the target snapshot, saw " + unchanged);
    }

    // ------------------------------------------------------------------ 成绩快照

    private static void gradeDetailIsAReadOnlySnapshotWithoutAWithdrawEntry() {
        ControlledService service = new ControlledService();
        service.summary = approvedSubmission();
        service.detail = new TeacherApplicationDetailDTO(approvedSubmission(), null,
                gradeSubmission());
        TeacherApplicationsController controller = controller(service);
        controller.activate();

        controller.select(GRADE, "9601");

        require(service.detailCalls.equals(List.of(GRADE + "|9601")),
                "the grade row must load the grade variant, saw " + service.detailCalls);
        require(controller.detail() != null && controller.detail().getGrade() != null
                        && controller.detail().getAdjustment() == null,
                "a grade detail must carry exactly the immutable batch snapshot");
        List<String> lines = controller.renderedDetailLines();
        require(lines.stream().anyMatch(line -> line.contains("人数：27")
                        && line.contains("平均分：78.42") && line.contains("不及格：3")),
                "the batch statistics must come from the frozen batch header, saw " + lines);
        require(lines.stream().anyMatch(line -> line.contains("只读快照")),
                "the grade detail must say it is a read-only snapshot, saw " + lines);
        require(lines.stream().anyMatch(line -> line.contains("学号 00005600")
                        && line.contains("学生00") && line.contains("总评：84.2")),
                "the snapshot items must be rendered from the frozen batch, saw " + lines);
        require(!controller.canWithdraw(),
                "a grade submission must never offer the adjustment-only withdrawal");
        require(!controller.detail().getSummary().isCanWithdraw(),
                "the server already said this row cannot be withdrawn");
    }

    // ------------------------------------------------------------------ 撤销

    private static void pendingCanWithdrawButTerminalCannot() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9405");
        require(controller.canWithdraw(),
                "a PENDING application must offer the withdrawal");

        service.details.addLast(CompletableFuture.completedFuture(
                adjustmentDetail(approvedAdjustment(), 2, List.of())));
        controller.select(ADJUSTMENT, "9405");
        require(!controller.canWithdraw(),
                "an approved application is read-only and must not offer the withdrawal");

        service.details.addLast(CompletableFuture.completedFuture(
                adjustmentDetail(withdrawnAdjustment(), 2, List.of())));
        controller.select(ADJUSTMENT, "9405");
        require(!controller.canWithdraw(),
                "an already withdrawn application must not offer the withdrawal again");
    }

    private static void withdrawSendsTheDetailVersionAndRefreshes() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9405");
        int listsBefore = service.listCalls.size();

        controller.requestWithdraw();
        require(controller.confirmingWithdraw(),
                "the first click must only arm the confirmation");
        require(service.withdrawals.isEmpty(), "arming must not send anything");
        CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>> inFlight =
                new CompletableFuture<>();
        service.withdrawals.addLast(inFlight);
        service.details.addLast(CompletableFuture.completedFuture(
                adjustmentDetail(withdrawnAdjustment(), 2, List.of())));
        controller.confirmWithdraw();

        require(service.withdraws.size() == 1,
                "confirming must send exactly one withdrawal, saw " + service.withdraws.size());
        WithdrawTeacherAdjustmentRequestDTO request = service.withdraws.get(0);
        require("9405".equals(request.getRequestId()) && request.getExpectedVersion() == 1,
                "the withdrawal must carry the detail identity and version, saw "
                        + request.getRequestId() + " v" + request.getExpectedVersion());
        require(request.getOperationId() != null && request.getOperationId().length() == 36,
                "the withdrawal must mint a canonical operation id, saw "
                        + request.getOperationId());
        require(controller.submitting() && !controller.canWithdraw(),
                "the withdrawal must disable itself while in flight");

        inFlight.complete(new TeacherOperationResultDTO<>(
                request.getOperationId(), "调课申请已撤销",
                adjustmentEntity("9405", "WITHDRAWN", 2, "2026-09-14T09:00:00Z"), false));

        require(controller.detail() != null
                        && "WITHDRAWN".equals(controller.detail().getSummary().getStatus()),
                "a successful withdrawal must render the refreshed detail, saw "
                        + (controller.detail() == null ? null
                                : controller.detail().getSummary().getStatus()));
        require(!controller.submitting() && !controller.confirmingWithdraw(),
                "the withdrawal must be finished");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已撤销"),
                "the page must report the withdrawal, saw " + controller.feedbackText());
        require(service.listCalls.size() > listsBefore,
                "a successful write must re-query the list, saw " + service.listCalls);
    }

    private static void withdrawCannotBeSentTwiceWhileInFlightOrAfterCancelling() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9405");

        controller.requestWithdraw();
        controller.cancelWithdraw();
        require(!controller.confirmingWithdraw() && service.withdrawals.isEmpty(),
                "cancelling the confirmation must not send anything");

        service.withdrawals.addLast(new CompletableFuture<>());
        controller.requestWithdraw();
        controller.confirmWithdraw();
        controller.confirmWithdraw();
        require(service.withdraws.size() == 1,
                "a second confirmation while in flight must not send another withdrawal, saw "
                        + service.withdraws.size());
    }

    private static void withdrawAfterApprovalConflictsAndRefreshes() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9405");
        int listsBefore = service.listCalls.size();

        service.withdrawals.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.CONFLICT,
                        "调课申请已被处理，请刷新后重试", List.of(),
                        adjustmentEntity("9405", "APPROVED", 2, "2026-09-14T09:00:00Z"))));
        service.details.addLast(CompletableFuture.completedFuture(
                adjustmentDetail(approvedAdjustment(), 2, List.of())));
        service.summary = approvedAdjustment();
        controller.requestWithdraw();
        controller.confirmWithdraw();

        require(controller.detail() != null
                        && "APPROVED".equals(controller.detail().getSummary().getStatus()),
                "a conflict must reload the latest server state, saw "
                        + (controller.detail() == null ? null
                                : controller.detail().getSummary().getStatus()));
        require(!controller.canWithdraw(),
                "the approval is terminal, so the withdrawal must be gone");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("调课申请已被处理"),
                "a conflict must report the server message, saw " + controller.feedbackText());
        require(service.listCalls.size() > listsBefore,
                "a conflict must also re-query the list, saw " + service.listCalls);
    }

    private static void withdrawNotFoundIsNotRenderedAsASystemError() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        service.detail = adjustmentDetail(pendingAdjustment(), 1, List.of());
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select(ADJUSTMENT, "9405");
        int listsBefore = service.listCalls.size();

        service.withdrawals.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.NOT_FOUND, "调课申请不存在")));
        controller.requestWithdraw();
        controller.confirmWithdraw();

        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("不存在"),
                "a missing request must read as a friendly state, saw "
                        + controller.feedbackText());
        require(!controller.feedbackText().contains("系统错误")
                        && !controller.feedbackText().contains("ERROR"),
                "NOT_FOUND must not be rendered as a system error, saw "
                        + controller.feedbackText());
        require(controller.detail() == null && controller.errorText() == null,
                "a missing request must clear the selection without an error banner");
        require(service.listCalls.size() > listsBefore,
                "the list must be re-queried after a vanished request, saw " + service.listCalls);
    }

    // ------------------------------------------------------------------ 失败与竞态

    private static void staleListCannotReplaceNewerResult() {
        ControlledService service = new ControlledService();
        service.summary = pendingAdjustment();
        TeacherApplicationsController controller = controller(service);

        CompletableFuture<TeacherPageDTO<TeacherApplicationDTO>> older = new CompletableFuture<>();
        CompletableFuture<TeacherPageDTO<TeacherApplicationDTO>> newer = new CompletableFuture<>();
        service.pages.addLast(older);
        service.pages.addLast(newer);
        controller.activate();
        controller.applyType("成绩提交");

        older.complete(page(List.of(pendingAdjustment())));
        newer.complete(page(List.of(pendingSubmission())));

        require(controller.applications().size() == 1
                        && "9601".equals(controller.applications().get(0).getId()),
                "a stale page must not replace the newer result, saw " + controller.applications());
    }

    private static void listFailureSurfacesTheError() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);
        service.pages.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.BAD_REQUEST, "size 必须为 1 至 100")));

        controller.activate();

        require("size 必须为 1 至 100".equals(controller.errorText())
                        && controller.applications().isEmpty() && !controller.loading(),
                "a business rejection must surface the server message, saw "
                        + controller.errorText());

        service.pages.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.ERROR, "缺少响应字段: applications")));
        controller.refresh();
        require(TeacherApplicationsController.LOAD_FAILURE_TEXT.equals(controller.errorText()),
                "a technical failure must show the retry text instead of internal details, saw "
                        + controller.errorText());
    }

    // ------------------------------------------------------------------ 夹具

    private static TeacherApplicationsController controller(ControlledService service) {
        return new TeacherApplicationsController(service, Runnable::run);
    }

    private static TeacherPageDTO<TeacherApplicationDTO> page(List<TeacherApplicationDTO> items) {
        return new TeacherPageDTO<>(items, items.size(), 1, 20);
    }

    // 两类事实表的行夹具：stateKey 一律按 status + ':' + (handledAt ?: submittedAt) 拼，与服务端同一条。

    private static TeacherApplicationDTO adjustmentRow(String id, String status, String submittedAt,
            String handledAt) {
        String stateKey = handledAt == null ? status + ":" + submittedAt
                : status + ":" + handledAt;
        boolean terminal = "APPROVED".equals(status) || "REJECTED".equals(status);
        return new TeacherApplicationDTO(ADJUSTMENT, id, INTERACTION_OFFERING,
                "人机交互导论　CS352-01", status, submittedAt, handledAt,
                terminal ? "同意" : null, "PENDING".equals(status), stateKey, true);
    }

    private static TeacherApplicationDTO pendingAdjustment() {
        return adjustmentRow("9405", "PENDING", "2026-09-14T07:00:00Z", null);
    }

    private static TeacherApplicationDTO approvedAdjustment() {
        return read(adjustmentRow("9405", "APPROVED", "2026-09-14T07:00:00Z",
                "2026-09-14T09:00:00Z"));
    }

    private static TeacherApplicationDTO withdrawnAdjustment() {
        return read(adjustmentRow("9405", "WITHDRAWN", "2026-09-14T07:00:00Z",
                "2026-09-14T10:00:00Z"));
    }

    private static TeacherApplicationDTO rejectedAdjustment() {
        return read(adjustmentRow("9403", "REJECTED", "2026-09-12T06:00:00Z",
                "2026-09-14T09:00:00Z"));
    }

    private static TeacherApplicationDTO pendingSubmission() {
        return new TeacherApplicationDTO(GRADE, "9601", INTERACTION_OFFERING,
                "人机交互导论　CS352-01", "PENDING", "2026-09-14T08:00:00Z", null, null, false,
                "PENDING:2026-09-14T08:00:00Z", true);
    }

    private static TeacherApplicationDTO approvedSubmission() {
        return new TeacherApplicationDTO(GRADE, "9601", INTERACTION_OFFERING,
                "人机交互导论　CS352-01", "APPROVED", "2026-09-14T08:00:00Z",
                "2026-09-14T09:30:00Z", "同意，成绩已发布", false,
                "APPROVED:2026-09-14T09:30:00Z", true);
    }

    /** 服务端算出的「已读」版本：同一行、同一个键，只有 unread 变了。 */
    private static TeacherApplicationDTO read(TeacherApplicationDTO row) {
        return new TeacherApplicationDTO(row.getType(), row.getId(), row.getOfferingId(),
                row.getTitle(), row.getStatus(), row.getSubmittedAt(), row.getHandledAt(),
                row.getReviewComment(), row.isCanWithdraw(), row.getStateKey(), false);
    }

    private static TeacherApplicationDetailDTO adjustmentDetail(TeacherApplicationDTO summary,
            int version, List<ScheduleConflictDTO> conflicts) {
        return new TeacherApplicationDetailDTO(summary,
                new AdjustmentRequestDetailDTO(summary.getId(), INTERACTION_OFFERING, "00001234",
                        "临时调课",
                        AdjustmentRequestStatusDTO.valueOf(summary.getStatus()), version, 5,
                        5, 6, null, null, null,
                        List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                                "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                        conflicts, summary.getSubmittedAt(),
                        summary.getHandledAt() == null ? null : "admin-alpha",
                        summary.getHandledAt(), summary.getReviewComment()),
                null);
    }

    /** 撤销接口返回的实体：与详情里的那一个同源，测试用它构造成功响应。 */
    private static AdjustmentRequestDetailDTO adjustmentEntity(String id, String status, int version,
            String reviewedAt) {
        return new AdjustmentRequestDetailDTO(id, INTERACTION_OFFERING, "00001234", "临时调课",
                AdjustmentRequestStatusDTO.valueOf(status), version, 5, 5, 6, null, null,
                null,
                List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                List.of(), "2026-09-14T07:00:00Z",
                "PENDING".equals(status) ? null : "admin-alpha",
                "PENDING".equals(status) ? null : reviewedAt,
                "PENDING".equals(status) ? null : "同意");
    }

    private static AdjustmentRequestDetailDTO legacyDetail() {
        return new AdjustmentRequestDetailDTO("9403", INTERACTION_OFFERING, "00001234", "材料不全的申请",
                AdjustmentRequestStatusDTO.REJECTED, 2, 5, 7, 8, null, null, null,
                List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", null, "A-101")),
                List.of(), "2026-09-12T06:00:00Z", "admin-alpha", "2026-09-14T09:00:00Z", "材料不足");
    }

    /** 按字段回落用例的详情夹具：目标原快照固定为 陈老师/王助教/A-101。 */
    private static AdjustmentRequestDetailDTO mixedDetail(ScheduleResourceDTO newTeacher,
            ScheduleResourceDTO newAssistant, ScheduleResourceDTO newClassroom) {
        return new AdjustmentRequestDetailDTO("9407", INTERACTION_OFFERING, "00001234", "临时调课",
                AdjustmentRequestStatusDTO.PENDING, 1, 5, 5, 6, newTeacher, newAssistant,
                newClassroom,
                List.of(new AdjustmentTargetDTO("9203", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                List.of(), "2026-09-14T07:00:00Z", null, null, null);
    }

    private static ScheduleResourceDTO resource(String id, String name, String type) {
        return new ScheduleResourceDTO(id, id, name, type, 0);
    }

    /** 一个已通过的成绩批次快照：只读、有统计、有明细，且没有撤销入口。 */
    private static GradeSubmissionDetailDTO gradeSubmission() {
        return new GradeSubmissionDetailDTO(new GradeSubmissionSummaryDTO("9601",
                INTERACTION_OFFERING, "人机交互导论", "CS352-01", 2, "00001234", "陈老师", 27, 78.42,
                96.0, 41.5, 3, ApprovalStatusDTO.APPROVED, "2026-09-14T08:00:00Z"),
                List.of(),
                List.of(new GradeSubmissionItemDTO("50031", "00005600", "学生00", 88.0, 80.0, 79.0,
                        85.0, 84.2, 4, 3.5)),
                "admin-alpha", "2026-09-14T09:30:00Z", "同意，成绩已发布");
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewWiresEveryIdAndAction(Document view) {
        Class<?> controller = TeacherApplicationsController.class;
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);

            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(controller, id) != null,
                        "<" + element.getTagName() + "> fx:id=\"" + id
                                + "\" has no field on " + controller.getSimpleName());
            }

            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasActionMethod(controller, action),
                        "<" + element.getTagName() + "> onAction=\"#" + action
                                + "\" has no handler on " + controller.getSimpleName());
            }
        }
        require(ids > 0 && actions > 0,
                "the view must actually declare fx:id and onAction bindings, saw "
                        + ids + " ids and " + actions + " actions");
    }

    private static void everyStyleClassExistsInTheStylesheet(Document view, String css) {
        Set<String> styleClasses = new LinkedHashSet<>();
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            String value = ((Element) elements.item(index)).getAttribute("styleClass");
            if (value.isEmpty()) continue;
            for (String token : value.split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) styleClasses.add(name);
            }
        }
        require(!styleClasses.isEmpty(), "the view must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
        // 结果角标是运行时加在行上的样式类，不在 FXML 里，所以它必须单独钉住。
        require(css.contains("." + "teacher-course-application-badge"),
                "the unread badge style class must exist in teacher-course.css");
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherApplicationsControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherApplicationsControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
        }
        return null;
    }

    private static boolean hasActionMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return true;
                if (method.getParameterCount() == 1
                        && Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 记录调用并按队列发放响应的教师课程服务替身；队列为空时返回确定性的已完成响应。 */
    private static final class ControlledService implements TeacherCourseService {
        private final Deque<CompletableFuture<TeacherPageDTO<TeacherApplicationDTO>>> pages =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<TeacherApplicationDetailDTO>> details =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<TeacherApplicationDTO>> reads = new ArrayDeque<>();
        private final Deque<CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>>
                withdrawals = new ArrayDeque<>();
        private final List<String> listCalls = new ArrayList<>();
        private final List<String> detailCalls = new ArrayList<>();
        private final List<MarkTeacherApplicationReadDTO> sentReads = new ArrayList<>();
        private final List<WithdrawTeacherAdjustmentRequestDTO> withdraws = new ArrayList<>();
        private TeacherApplicationDTO summary;
        private TeacherApplicationDetailDTO detail;

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherApplicationDTO>> listMyApplications(
                String type, String status, int pageNumber, int size) {
            listCalls.add(type + "|" + status + "|" + pageNumber + "|" + size);
            if (!pages.isEmpty()) return pages.removeFirst();
            List<TeacherApplicationDTO> items = summary == null ? List.of() : List.of(summary);
            return CompletableFuture.completedFuture(page(items));
        }

        @Override
        public CompletableFuture<TeacherApplicationDetailDTO> getMyApplication(String type,
                String id) {
            detailCalls.add(type + "|" + id);
            if (!details.isEmpty()) return details.removeFirst();
            return CompletableFuture.completedFuture(detail);
        }

        @Override
        public CompletableFuture<TeacherApplicationDTO> markApplicationRead(
                MarkTeacherApplicationReadDTO request) {
            sentReads.add(request);
            if (!reads.isEmpty()) return reads.removeFirst();
            // 与真实服务同形：写入回执之后这一行就是已读的，随后的列表查询也这样回答。
            summary = summary == null ? null : read(summary);
            return CompletableFuture.completedFuture(summary);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
                withdrawAdjustment(WithdrawTeacherAdjustmentRequestDTO request) {
            withdraws.add(request);
            if (!withdrawals.isEmpty()) return withdrawals.removeFirst();
            return CompletableFuture.completedFuture(new TeacherOperationResultDTO<>(
                    request.getOperationId(), "调课申请已撤销", detail.getAdjustment(), false));
        }

        @Override
        public CompletableFuture<List<dto.course.CourseTermDTO>> listTerms() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherPageDTO<
                dto.course.teacher.TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherOfferingDetailDTO> getOffering(
                String offeringId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherPageDTO<
                dto.course.teacher.TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<dto.course.admin.schedule.ScheduleArrangementDTO>>
                listOfferingSchedules(String offeringId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            throw new UnsupportedOperationException();
        }
    }
}
