package controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 我的调课申请页（设计 §5.3、§10 的调课部分）：按四态筛选本人的申请，右侧显示原/新时间地点、
 * 提交时间、状态与处理意见，PENDING 可以撤销。
 *
 * <p>展示语义（T3 报告 §6.2）：教师申请的 {@code newTeacher}/{@code newAssistant} 恒为 null——
 * 教师不修改任课教师与助教，因此新安排的人员显示该目标的原快照；{@code newClassroom} 为 null
 * 表示沿用原教室；{@code targetDate} 是 ISO 本地日期，历史行没有日期时保留旧的星期显示。
 * PENDING 详情带一次实时冲突快照，终态没有冲突。
 *
 * <p>撤销：两步确认（第一次点击只是进入确认态，第二次才发送），发送时带详情里的 {@code version}
 * 作为 {@code expectedVersion}。审批通过后撤销会拿到 CONFLICT 与最新实体，页面渲染最新状态并
 * 刷新列表；申请不可见时服务端返回 NOT_FOUND，页面按“已不存在”的普通提示处理并刷新列表，
 * 绝不把它渲染成系统错误。成功与失败都在页面内联提示，不弹模态框（冒烟测试因此能无人值守地
 * 走完整个撤销流程）。
 *
 * <p>分页与竞态沿用仓库约定：列表/详情各有 generation，页面被 {@link #unload()} 卸下或已经发出
 * 新请求时，迟到的旧响应一律丢弃。所有节点都可能为 {@code null}，控制器测试因此无需工具包。
 */
public final class TeacherApplicationsController {
    static final int PAGE_SIZE = 20;
    static final String LOAD_FAILURE_TEXT = "调课申请加载失败，请重试";
    static final String DETAIL_FAILURE_TEXT = "调课申请详情加载失败，请重试";
    static final String VANISHED_TEXT = "该调课申请不存在或已不再显示，列表已刷新";
    static final String WITHDRAW_SUCCESS_TEXT = "调课申请已撤销，列表已刷新";
    static final String WITHDRAW_CONFLICT_PREFIX = "该申请已被处理，已刷新最新状态：";
    static final String WITHDRAW_FAILURE_TEXT = "撤销失败，请重试";
    static final String WITHDRAW_PROMPT_TEXT = "撤销后申请立即失效，确定要撤销吗？";
    static final String WITHDRAWING_TEXT = "正在撤销...";
    static final String DETAIL_PLACEHOLDER_TEXT = "左侧选择一条申请后可查看原安排与处理结果";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private AdjustmentRequestStatusDTO status = AdjustmentRequestStatusDTO.PENDING;
    private int page = 1;
    private int loadedPage = 1;
    private long totalCount;
    private List<AdjustmentRequestSummaryDTO> applications = List.of();
    private AdjustmentRequestSummaryDTO selectedSummary;
    private AdjustmentRequestDetailDTO detail;
    private boolean loading;
    private boolean loadingDetail;
    private boolean submitting;
    private boolean confirmingWithdraw;
    private boolean active;
    private String errorText;
    private String feedbackText;
    private long listGeneration;
    private long detailGeneration;
    private List<String> detailLines = List.of();

    @FXML private ComboBox<String> applicationStatusFilter;
    @FXML private Button applicationRefreshButton;
    @FXML private Label applicationSummaryLabel;
    @FXML private VBox applicationList;
    @FXML private Label applicationLoadingLabel;
    @FXML private Label applicationEmptyLabel;
    @FXML private Label applicationErrorLabel;
    @FXML private Button applicationErrorRetryButton;
    @FXML private Label applicationPageLabel;
    @FXML private Button applicationPreviousPageButton;
    @FXML private Button applicationNextPageButton;
    @FXML private Label applicationDetailTitleLabel;
    @FXML private Label applicationDetailPlaceholder;
    @FXML private VBox applicationDetailBody;
    @FXML private Label applicationWithdrawHintLabel;
    @FXML private Button applicationWithdrawButton;
    @FXML private Button applicationConfirmWithdrawButton;
    @FXML private Button applicationCancelWithdrawButton;
    @FXML private Label applicationFeedbackLabel;

    public TeacherApplicationsController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherApplicationsController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        if (applicationStatusFilter != null) {
            applicationStatusFilter.getItems().setAll(statusLabels());
            applicationStatusFilter.setValue(AdminApprovalController.PENDING_LABEL);
            applicationStatusFilter.valueProperty().addListener(
                    (observable, previous, next) -> applyStatus(next));
        }
        render();
    }

    /** 工作台切换到本页时调用：每次进入都重新查询，保证看到写操作后的最新状态。 */
    void activate() {
        active = true;
        loadPage(page);
    }

    /** 工作台离开本页时调用：保留界面状态，但不再接受任何在途响应。 */
    void unload() {
        active = false;
        listGeneration++;
        detailGeneration++;
    }

    void refresh() {
        loadPage(page);
    }

    @FXML
    void refresh(Event event) {
        refresh();
    }

    @FXML
    void handleNextPage(Event event) {
        goToPage(page + 1);
    }

    @FXML
    void handlePreviousPage(Event event) {
        goToPage(page - 1);
    }

    /** 四态筛选；重新选择当前状态不会重复加载。 */
    void applyStatus(String label) {
        AdjustmentRequestStatusDTO next = AdminApprovalController.toStatus(label);
        if (next == status) return;
        status = next;
        goToPage(1);
    }

    void goToPage(int nextPage) {
        loadPage(Math.max(1, nextPage));
    }

    /** 选中一条申请：加载详情（含 PENDING 的实时冲突快照与撤销所需的 version）。 */
    void select(String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        selectedSummary = summaryOf(requestId);
        confirmingWithdraw = false;
        loadDetail(requestId);
    }

    // ------------------------------------------------------------ 撤销

    /** 第一步：只是进入确认态，不发送任何请求。 */
    void requestWithdraw() {
        if (!canWithdraw() || confirmingWithdraw) return;
        confirmingWithdraw = true;
        feedbackText = null;
        render();
    }

    @FXML
    void requestWithdraw(Event event) {
        requestWithdraw();
    }

    void cancelWithdraw() {
        if (!confirmingWithdraw) return;
        confirmingWithdraw = false;
        render();
    }

    @FXML
    void cancelWithdraw(Event event) {
        cancelWithdraw();
    }

    /** 第二步：带详情里的 version 发送撤销；两步确认让一次误点不会直接撤销申请。 */
    void confirmWithdraw() {
        if (!canWithdraw()) return;
        confirmingWithdraw = false;
        submitting = true;
        feedbackText = null;
        render();
        AdjustmentRequestDetailDTO current = detail;
        WithdrawTeacherAdjustmentRequestDTO request = new WithdrawTeacherAdjustmentRequestDTO(
                UUID.randomUUID().toString(), current.getRequestId(), current.getVersion());
        long generation = ++detailGeneration;
        service.withdrawAdjustment(request).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrentDetail(generation)) return;
                    submitting = false;
                    if (failure != null) {
                        handleWithdrawFailure(failure, current.getRequestId());
                        return;
                    }
                    if (result != null && result.getValue() != null) {
                        renderDetail(result.getValue());
                    }
                    feedbackText = WITHDRAW_SUCCESS_TEXT;
                    render();
                    // 写操作之后重新查询列表：PENDING 列表里不再包含这条申请。
                    loadPage(page);
                }));
    }

    @FXML
    void confirmWithdraw(Event event) {
        confirmWithdraw();
    }

    /**
     * 撤销失败分类：CONFLICT 渲染服务端带来的最新实体并刷新列表（审批通过后撤销就是这条路）；
     * NOT_FOUND 是“申请不可见”，按普通提示处理，绝不显示成系统错误。
     */
    private void handleWithdrawFailure(Throwable failure, String requestId) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException serviceFailure) {
            MessageCode code = serviceFailure.getCode();
            if (code == MessageCode.CONFLICT) {
                if (serviceFailure.getLatest() != null) {
                    renderDetail(serviceFailure.getLatest());
                }
                feedbackText = WITHDRAW_CONFLICT_PREFIX + messageOf(cause);
                render();
                loadPage(page);
                return;
            }
            if (code == MessageCode.NOT_FOUND) {
                clearDetail();
                feedbackText = VANISHED_TEXT;
                render();
                loadPage(page);
                return;
            }
        }
        feedbackText = failureText(failure, WITHDRAW_FAILURE_TEXT);
        render();
    }

    // ------------------------------------------------------------ 加载

    void loadPage(int nextPage) {
        page = Math.max(1, nextPage);
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listMyAdjustmentRequests(status, page, PAGE_SIZE)
                .whenComplete((result, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrentList(generation)) return;
                    loading = false;
                    if (failure != null) {
                        page = loadedPage;
                        applications = List.of();
                        errorText = failureText(failure, LOAD_FAILURE_TEXT);
                        render();
                        return;
                    }
                    applications = result == null ? List.of() : List.copyOf(result.getItems());
                    totalCount = result == null ? 0 : result.getTotalCount();
                    loadedPage = page;
                    errorText = null;
                    render();
                }));
    }

    void loadDetail(String requestId) {
        long generation = ++detailGeneration;
        loadingDetail = true;
        render();
        service.getAdjustmentRequest(requestId).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrentDetail(generation)) return;
                    loadingDetail = false;
                    if (failure != null) {
                        if (isNotFound(failure)) {
                            clearDetail();
                            feedbackText = VANISHED_TEXT;
                            render();
                            loadPage(page);
                            return;
                        }
                        feedbackText = failureText(failure, DETAIL_FAILURE_TEXT);
                        clearDetail();
                        render();
                        return;
                    }
                    renderDetail(result);
                    feedbackText = null;
                    render();
                }));
    }

    /** 列表与详情各有 generation：新的请求、或页面被卸下之后，迟到的旧响应一律丢弃。 */
    private boolean isCurrentList(long generation) {
        return active && generation == listGeneration;
    }

    private boolean isCurrentDetail(long generation) {
        return active && generation == detailGeneration;
    }

    private void clearDetail() {
        detail = null;
        selectedSummary = null;
        detailLines = List.of();
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        setActive(applicationLoadingLabel, loading);
        setActive(applicationEmptyLabel, !loading && errorText == null && applications.isEmpty());
        if (applicationErrorLabel != null) {
            applicationErrorLabel.setText(errorText == null ? "" : errorText);
        }
        setActive(applicationErrorLabel, errorText != null);
        setActive(applicationErrorRetryButton, errorText != null);
        if (applicationSummaryLabel != null) {
            applicationSummaryLabel.setText("共 " + totalCount + " 条" + statusLabel() + "申请");
        }
        if (applicationPageLabel != null) applicationPageLabel.setText(pageText());
        if (applicationPreviousPageButton != null) applicationPreviousPageButton.setDisable(!hasPreviousPage());
        if (applicationNextPageButton != null) applicationNextPageButton.setDisable(!hasNextPage());
        renderList();
        renderDetailPanel();
        if (applicationFeedbackLabel != null) {
            applicationFeedbackLabel.setText(feedbackText == null ? "" : feedbackText);
        }
        setActive(applicationFeedbackLabel, feedbackText != null);
    }

    private void renderList() {
        if (applicationList == null) return;
        applicationList.getChildren().clear();
        for (AdjustmentRequestSummaryDTO application : applications) {
            applicationList.getChildren().add(row(application));
        }
    }

    /**
     * 一行申请：两行文本加一颗“查看”按钮（与教学班列表的行内按钮同形）。整行可点，按钮供
     * 键盘/冒烟测试真实触发。
     */
    private HBox row(AdjustmentRequestSummaryDTO application) {
        Label title = new Label(summaryTitle(application));
        title.getStyleClass().add("teacher-course-application-row-title");
        title.setWrapText(true);
        Label meta = new Label(summaryMeta(application));
        meta.getStyleClass().add("teacher-course-application-row-meta");
        meta.setWrapText(true);
        VBox text = new VBox(2.0, title, meta);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);

        Button open = new Button("查看");
        open.getStyleClass().add("teacher-course-row-detail-button");
        // HBox 会压缩能压缩的子节点；按钮必须保持自己的首选宽度，否则文本被裁成省略号。
        open.setMinWidth(Region.USE_PREF_SIZE);
        open.setOnAction(event -> select(application.getRequestId()));

        HBox row = new HBox(8.0, text, open);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("teacher-course-application-row");
        row.setOnMouseClicked(event -> select(application.getRequestId()));
        return row;
    }

    private void renderDetailPanel() {
        if (applicationDetailTitleLabel != null) {
            applicationDetailTitleLabel.setText(detail == null ? "请选择调课申请"
                    : "调课申请 " + detail.getRequestId() + "（"
                            + AdminApprovalController.statusLabel(detail.getStatus()) + "）");
        }
        setActive(applicationDetailPlaceholder, detail == null && !loadingDetail);
        if (applicationDetailPlaceholder != null) applicationDetailPlaceholder.setText(DETAIL_PLACEHOLDER_TEXT);
        detailLines = detail == null ? List.of() : detailLines(detail, selectedSummary);
        if (applicationDetailBody != null) {
            applicationDetailBody.getChildren().clear();
            for (String line : detailLines) {
                Label label = new Label(line);
                label.getStyleClass().add("teacher-course-detail-line");
                label.setWrapText(true);
                applicationDetailBody.getChildren().add(label);
            }
        }
        // 终态申请只读：撤销入口整个隐藏；提交在途时保留按钮但禁用。
        boolean withdrawable = canWithdraw();
        setActive(applicationWithdrawButton, detail != null && (withdrawable || submitting));
        if (applicationWithdrawButton != null) {
            applicationWithdrawButton.setDisable(!withdrawable);
        }
        setActive(applicationConfirmWithdrawButton, confirmingWithdraw);
        setActive(applicationCancelWithdrawButton, confirmingWithdraw);
        if (applicationWithdrawHintLabel != null) {
            applicationWithdrawHintLabel.setText(withdrawHintText());
        }
        setActive(applicationWithdrawHintLabel, !confirmingWithdraw && withdrawHintText() != null);
    }

    private String withdrawHintText() {
        if (submitting) return WITHDRAWING_TEXT;
        if (detail == null || detail.getStatus() != AdjustmentRequestStatusDTO.PENDING) return null;
        return WITHDRAW_PROMPT_TEXT;
    }

    private void renderDetail(AdjustmentRequestDetailDTO value) {
        detail = value;
        detailLines = value == null ? List.of() : detailLines(value, selectedSummary);
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------ 纯文本

    /** 行标题：课程名与教学班代码。 */
    static String summaryTitle(AdjustmentRequestSummaryDTO request) {
        return orDash(request.getCourseName()) + "　" + orDash(request.getOfferingCode());
    }

    /** 行副标题：申请编号、状态、提交时间与目标周数。 */
    static String summaryMeta(AdjustmentRequestSummaryDTO request) {
        return "编号：" + orDash(request.getRequestId())
                + "　状态：" + AdminApprovalController.statusLabel(request.getStatus())
                + "　提交：" + orDash(request.getSubmittedAt())
                + "　目标周数：" + request.getTargetWeekCount();
    }

    /**
     * 详情行：课程、原因、提交时间、状态、审批信息、每个目标的原/新安排与（PENDING 的）实时冲突。
     * 新安排的人员回落到目标原快照（教师不修改任课教师/助教），教室为 null 时显示原教室；
     * 历史目标没有明确日期时保留“星期 + 节次”的旧显示。
     */
    static List<String> detailLines(AdjustmentRequestDetailDTO detail,
            AdjustmentRequestSummaryDTO summary) {
        List<String> lines = new ArrayList<>();
        if (summary != null) {
            lines.add("课程：" + orDash(summary.getCourseName()) + "　教学班："
                    + orDash(summary.getOfferingCode()));
        }
        lines.add("申请编号：" + orDash(detail.getRequestId()) + "　状态："
                + AdminApprovalController.statusLabel(detail.getStatus())
                + "　版本：v" + detail.getVersion());
        lines.add("提交时间：" + orDash(detail.getSubmittedAt()));
        lines.add("申请原因：" + orDash(detail.getReason()));
        for (AdjustmentTargetDTO target : detail.getTargets()) {
            lines.add(originalLine(target));
            lines.add(targetLine(detail, target));
        }
        for (ScheduleConflictDTO conflict : detail.getConflicts()) {
            lines.add(conflictLine(conflict));
        }
        if (detail.getConflicts().isEmpty()) {
            lines.add("冲突：无");
        }
        if (detail.getReviewedBy() != null || detail.getReviewedAt() != null
                || detail.getReviewComment() != null) {
            lines.add("处理时间：" + orDash(detail.getReviewedAt()));
            lines.add("处理意见：" + orDash(detail.getReviewComment()));
        }
        return List.copyOf(lines);
    }

    /** 原安排：目标周与原课次快照，全部原样来自不可变 DTO。 */
    static String originalLine(AdjustmentTargetDTO target) {
        return "原安排（第 " + target.getWeek() + " 周）："
                + orDash(target.getOriginalStartAt()) + "~" + orDash(target.getOriginalEndAt())
                + "　" + personText(null, null, target) + "　"
                + orDash(target.getOriginalClassroom());
    }

    /** 新安排：明确目标日期（历史行为 null 时退回星期），人员与教室回落到原快照。 */
    static String targetLine(AdjustmentRequestDetailDTO detail, AdjustmentTargetDTO target) {
        String when = orDash(target.getTargetDate()) + " "
                + AdminApprovalController.weekdayName(detail.getNewDayOfWeek());
        if (target.getTargetDate() == null) {
            when = AdminApprovalController.weekdayName(detail.getNewDayOfWeek());
        }
        String classroom = detail.getNewClassroom() == null
                ? orDash(target.getOriginalClassroom())
                : orDash(detail.getNewClassroom().getName());
        return "目标安排：" + when + " 第 " + detail.getNewStartPeriod() + "-"
                + detail.getNewEndPeriod() + " 节　"
                + personText(detail.getNewTeacher(), detail.getNewAssistant(), target) + "　"
                + classroom;
    }

    /** 教师申请没有新教师/助教字段，显示该目标的原快照；管理员替换过人员时才显示新资源。 */
    static String personText(ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
            AdjustmentTargetDTO target) {
        if (newTeacher != null || newAssistant != null) {
            String teacher = newTeacher == null ? "—" : orDash(newTeacher.getName());
            return newAssistant == null ? teacher : teacher + ", " + orDash(newAssistant.getName());
        }
        String teacher = orDash(target.getOriginalTeacher());
        return target.getOriginalAssistant() == null || target.getOriginalAssistant().isBlank()
                ? teacher
                : teacher + ", " + target.getOriginalAssistant();
    }

    /** 冲突行：message 是主要信息（同一类型字符串会被不同场景复用），类型/级别只做补充。 */
    static String conflictLine(ScheduleConflictDTO conflict) {
        return "冲突（第 " + conflict.getWeek() + " 周）：" + orDash(conflict.getMessage()) + "　["
                + conflict.getType() + "/" + conflict.getSeverity() + "]";
    }

    static List<String> statusLabels() {
        return List.of(AdminApprovalController.PENDING_LABEL, AdminApprovalController.APPROVED_LABEL,
                AdminApprovalController.REJECTED_LABEL, AdminApprovalController.WITHDRAWN_LABEL);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String statusLabel() {
        return AdminApprovalController.statusLabel(status);
    }

    private String pageText() {
        if (totalCount <= 0) return "共 0 条";
        return "第 " + page + "/" + totalPages() + " 页　共 " + totalCount + " 条";
    }

    private AdjustmentRequestSummaryDTO summaryOf(String requestId) {
        for (AdjustmentRequestSummaryDTO application : applications) {
            if (requestId.equals(application.getRequestId())) return application;
        }
        return selectedSummary != null && requestId.equals(selectedSummary.getRequestId())
                ? selectedSummary : null;
    }

    /** 业务拒绝原样显示服务端的话；其余只给可重试的通用文案，避免把内部细节当成用户提示。 */
    static String failureText(Throwable failure, String fallback) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException failureInfo) {
            MessageCode code = failureInfo.getCode();
            String message = failureInfo.getMessage();
            if (code != MessageCode.ERROR && message != null && !message.isBlank()) {
                return message;
            }
        }
        return fallback;
    }

    private static boolean isNotFound(Throwable failure) {
        return rootCause(failure) instanceof TeacherCourseServiceException failureInfo
                && failureInfo.getCode() == MessageCode.NOT_FOUND;
    }

    private static String messageOf(Throwable cause) {
        return cause.getMessage() == null ? "未知错误" : cause.getMessage();
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // -------------------------------------------------------------- 测试访问器

    List<AdjustmentRequestSummaryDTO> applications() {
        return applications;
    }

    AdjustmentRequestDetailDTO detail() {
        return detail;
    }

    AdjustmentRequestStatusDTO status() {
        return status;
    }

    int page() {
        return page;
    }

    long totalCount() {
        return totalCount;
    }

    int totalPages() {
        return totalCount <= 0 ? 1 : (int) ((totalCount + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    boolean loading() {
        return loading;
    }

    boolean submitting() {
        return submitting;
    }

    boolean confirmingWithdraw() {
        return confirmingWithdraw;
    }

    boolean active() {
        return active;
    }

    boolean canWithdraw() {
        return detail != null && detail.getStatus() == AdjustmentRequestStatusDTO.PENDING
                && !submitting;
    }

    String feedbackText() {
        return feedbackText;
    }

    String errorText() {
        return errorText;
    }

    boolean hasPreviousPage() {
        return !loading && page > 1;
    }

    boolean hasNextPage() {
        return !loading && page < totalPages();
    }

    /** 最后一次渲染到详情面板的行；节点缺失时同样可用。 */
    List<String> renderedDetailLines() {
        return detailLines;
    }
}
