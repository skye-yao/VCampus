package controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
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
 * 我的申请页（设计 §10、§11）：把本人的<b>调课申请</b>与<b>成绩提交批次</b>合成一条时间线，
 * 按类型与状态筛选、分页，右侧显示该条申请恰好一个类型化详情，未读结果带角标。
 *
 * <p>为什么是「一条时间线」而不是两张表拼在一起：两类事实表的状态字母表不同（调课四态含教师撤销，
 * 成绩提交只有三态），如果各拉一页再由界面合并，页码与「共 N 条」就再也说不清了。合并、排序与
 * 分页都发生在服务端 SQL 里，本页只负责把一行渲染成一行。
 *
 * <p>已读模型：{@code stateKey = status + ':' + (handledAt ?: submittedAt)}，服务端按
 * 「已存回执 != 当前键」算出 {@code unread}，因此这里的角标完全由 DTO 驱动，不做任何客户端猜测。
 * 详情加载成功后，如果那一行仍未读，页面发出一次 {@code markApplicationRead}（带**当时看到的**
 * stateKey）；服务端比对不一致就以 CONFLICT 结束——那说明期间管理员处理了这条申请，页面据此
 * 重新查询，绝不把更新后的结果当成已读。
 *
 * <p>撤销只对 PENDING 的调课申请开放（{@code canWithdraw}）；成绩提交没有撤销状态，服务端也没有
 * {@code withdrawn_at} 列，所以成绩详情页不会出现一个没有后端定义的入口。撤销仍是两步确认。
 *
 * <p>分页与竞态沿用仓库约定：列表/详情各有 generation，页面被 {@link #unload()} 卸下或已经发出
 * 新请求时，迟到的旧响应一律丢弃。所有节点都可能为 {@code null}，控制器测试因此无需工具包。
 */
/** 教师查看调课申请与成绩提交处理结果的 JavaFX 页面控制器。 */
public final class TeacherApplicationsController {
    static final int PAGE_SIZE = 20;
    static final String LOAD_FAILURE_TEXT = "我的申请加载失败，请重试";
    static final String DETAIL_FAILURE_TEXT = "申请详情加载失败，请重试";
    static final String VANISHED_TEXT = "该申请不存在或已不再显示，列表已刷新";
    static final String WITHDRAW_SUCCESS_TEXT = "调课申请已撤销，列表已刷新";
    static final String WITHDRAW_CONFLICT_PREFIX = "该申请已被处理，已刷新最新状态：";
    static final String WITHDRAW_FAILURE_TEXT = "撤销失败，请重试";
    static final String WITHDRAW_PROMPT_TEXT = "撤销后申请立即失效，确定要撤销吗？";
    static final String WITHDRAWING_TEXT = "正在撤销...";
    static final String READ_CONFLICT_PREFIX = "该申请结果已更新，已刷新最新状态：";
    static final String READ_FAILURE_TEXT = "标记已读失败，请刷新后重试";
    static final String DETAIL_PLACEHOLDER_TEXT = "左侧选择一条申请后可查看提交内容与处理结果";
    static final String UNREAD_BADGE_TEXT = "未读";
    static final String NO_DETAIL_TEXT = "该申请没有可显示的详情";
    /** 类型筛选：全部（不限类型）、调课申请、成绩提交。 */
    static final String TYPE_ALL_LABEL = "全部";
    static final String TYPE_ADJUSTMENT_LABEL = "调课申请";
    static final String TYPE_GRADE_LABEL = "成绩提交";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    /** 当前类型筛选；null 表示不限类型（「全部」）。 */
    private String type;
    private String status = "PENDING";
    private int page = 1;
    private int loadedPage = 1;
    private long totalCount;
    private List<TeacherApplicationDTO> applications = List.of();
    private TeacherApplicationDTO selectedSummary;
    private TeacherApplicationDetailDTO detail;
    private boolean loading;
    private boolean loadingDetail;
    private boolean submitting;
    /** 正在标记已读的那一行（{@code 类型|编号}）；同一行的重复确认不重发，另一行不受影响。 */
    private String markingRead;
    private boolean confirmingWithdraw;
    private boolean active;
    private String errorText;
    private String feedbackText;
    private long listGeneration;
    private long detailGeneration;
    private List<String> detailLines = List.of();
    /** 最近一次成功加载的列表里未读的行数；工作台入口的结果角标据此显示。 */
    private int unreadCount;
    private Consumer<Integer> unreadListener = count -> { };

    @FXML private ComboBox<String> applicationTypeFilter;
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
        if (applicationTypeFilter != null) {
            applicationTypeFilter.getItems().setAll(typeLabels());
            applicationTypeFilter.setValue(TYPE_ALL_LABEL);
            applicationTypeFilter.valueProperty().addListener(
                    (observable, previous, next) -> applyType(next));
        }
        if (applicationStatusFilter != null) {
            applicationStatusFilter.getItems().setAll(statusLabels());
            applicationStatusFilter.setValue(AdminApprovalController.PENDING_LABEL);
            applicationStatusFilter.valueProperty().addListener(
                    (observable, previous, next) -> applyStatus(next));
        }
        render();
    }

    /** 工作台可用它把自己的入口角标接到本页的未读计数上。 */
    void setUnreadListener(Consumer<Integer> listener) {
        this.unreadListener = listener == null ? count -> { } : listener;
        unreadListener.accept(unreadCount);
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

    /** 类型筛选；切换类型时把该类型没有的状态（例如成绩提交没有「已撤销」）从选项里去掉。 */
    void applyType(String label) {
        String next = toType(label);
        if (Objects.equals(next, type)) return;
        type = next;
        if (!TeacherApplicationDTO.isStatus(next, status)) {
            status = "PENDING";
        }
        if (applicationStatusFilter != null) {
            applicationStatusFilter.getItems().setAll(statusLabels(next));
            applicationStatusFilter.setValue(statusLabel(status));
        }
        goToPage(1);
    }

    /** 状态筛选；重新选择当前状态不会重复加载。 */
    void applyStatus(String label) {
        String next = toStatus(label);
        if (next == null || next.equals(status)) return;
        status = next;
        goToPage(1);
    }

    void goToPage(int nextPage) {
        loadPage(Math.max(1, nextPage));
    }

    /** 选中一条申请：加载详情（调课含 PENDING 的实时冲突快照与撤销所需的 version）。 */
    void select(String type, String id) {
        if (type == null || id == null || id.isBlank()) return;
        selectedSummary = summaryOf(type, id);
        confirmingWithdraw = false;
        // 换一条申请是用户的新动作，先把上一条留下的提示清掉；写操作自己触发的重新加载
        // （撤销后、已读冲突后）必须保留它刚刚写下的那句话，所以清在这里而不是在响应回来时。
        feedbackText = null;
        loadDetail(type, id);
    }

    // ------------------------------------------------------------ 标记已读

    /**
     * 详情读到了，就把这一行结果标成已读：带的是**列表里看到的那一个** stateKey。
     * 服务端比对不一致时以 CONFLICT 结束（说明期间结果变了），页面据此重新查询。
     */
    private void markRead(String type, String id, String expectedStateKey) {
        String key = type + "|" + id;
        if (expectedStateKey == null || key.equals(markingRead)) return;
        markingRead = key;
        service.markApplicationRead(new MarkTeacherApplicationReadDTO(type, id, expectedStateKey))
                .whenComplete((updated, failure) -> fxExecutor.accept(() -> {
                    markingRead = null;
                    if (!active) return;
                    if (failure != null) {
                        handleReadFailure(failure, type, id);
                        return;
                    }
                    if (updated != null && detail != null && detail.getSummary() != null
                            && type.equals(detail.getSummary().getType())
                            && id.equals(detail.getSummary().getId())) {
                        detail = new TeacherApplicationDetailDTO(updated, detail.getAdjustment(),
                                detail.getGrade());
                    }
                    // 写操作之后重新查询列表：角标与未读状态才是服务端的真实状态。
                    loadPage(page);
                    render();
                }));
    }

    private void handleReadFailure(Throwable failure, String type, String id) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException serviceFailure
                && serviceFailure.getCode() == MessageCode.CONFLICT) {
            // 结果在处理期间变了：拿服务端的权威状态重来一次，不在这里猜任何字段。
            feedbackText = READ_CONFLICT_PREFIX + messageOf(cause);
            render();
            loadPage(page);
            loadDetail(type, id);
            return;
        }
        feedbackText = failureText(failure, READ_FAILURE_TEXT);
        render();
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
        AdjustmentRequestDetailDTO adjustment = adjustmentOf();
        if (!canWithdraw() || adjustment == null) return;
        confirmingWithdraw = false;
        submitting = true;
        feedbackText = null;
        render();
        WithdrawTeacherAdjustmentRequestDTO request = new WithdrawTeacherAdjustmentRequestDTO(
                UUID.randomUUID().toString(), adjustment.getRequestId(), adjustment.getVersion());
        long generation = ++detailGeneration;
        service.withdrawAdjustment(request).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrentDetail(generation)) return;
                    submitting = false;
                    if (failure != null) {
                        handleWithdrawFailure(failure, adjustment.getRequestId());
                        return;
                    }
                    feedbackText = WITHDRAW_SUCCESS_TEXT;
                    render();
                    // 写操作之后重新查询列表，并重新读一次详情：撤销带来的状态键变化必须由
                    // 服务端重新算，页面不自己改写 status/stateKey。
                    loadPage(page);
                    loadDetail(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                            adjustment.getRequestId());
                }));
    }

    @FXML
    void confirmWithdraw(Event event) {
        confirmWithdraw();
    }

    /**
     * 撤销失败分类：CONFLICT 说明这条申请已经被处理（例如管理员先通过了），页面按服务端的权威
     * 状态重新加载；NOT_FOUND 是「申请不可见」，按普通提示处理，绝不显示成系统错误。
     */
    private void handleWithdrawFailure(Throwable failure, String requestId) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException serviceFailure) {
            MessageCode code = serviceFailure.getCode();
            if (code == MessageCode.CONFLICT) {
                feedbackText = WITHDRAW_CONFLICT_PREFIX + messageOf(cause);
                render();
                loadPage(page);
                loadDetail(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, requestId);
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
        service.listMyApplications(type, status, page, PAGE_SIZE)
                .whenComplete((result, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrentList(generation)) return;
                    loading = false;
                    if (failure != null) {
                        page = loadedPage;
                        applications = List.of();
                        updateUnreadCount(0);
                        errorText = failureText(failure, LOAD_FAILURE_TEXT);
                        render();
                        return;
                    }
                    applications = result == null ? List.of() : List.copyOf(result.getItems());
                    totalCount = result == null ? 0 : result.getTotalCount();
                    loadedPage = page;
                    errorText = null;
                    updateUnreadCount(countUnread(applications));
                    render();
                }));
    }

    void loadDetail(String type, String id) {
        long generation = ++detailGeneration;
        loadingDetail = true;
        render();
        service.getMyApplication(type, id).whenComplete((result, failure) ->
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
                    detail = result;
                    detailLines = result == null ? List.of() : detailLines(result);
                    render();
                    if (result != null && result.getSummary() != null
                            && result.getSummary().isUnread()) {
                        // 详情已经读到了，把这一次看到的结果标成已读；旧的 stateKey 由服务端比对。
                        markRead(result.getSummary().getType(), result.getSummary().getId(),
                                result.getSummary().getStateKey());
                    }
                }));
    }

    /** 列表与详情各有 generation：新的请求、或页面被卸下之后，迟到的旧响应一律丢弃。 */
    private boolean isCurrentList(long generation) {
        return active && generation == listGeneration;
    }

    private boolean isCurrentDetail(long generation) {
        return active && generation == detailGeneration;
    }

    private int countUnread(List<TeacherApplicationDTO> rows) {
        int unread = 0;
        for (TeacherApplicationDTO row : rows) {
            if (row != null && row.isUnread()) unread++;
        }
        return unread;
    }

    private void updateUnreadCount(int unread) {
        if (unread == unreadCount) return;
        unreadCount = unread;
        unreadListener.accept(unread);
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
            applicationSummaryLabel.setText("共 " + totalCount + " 条" + typeLabelText()
                    + statusLabelText() + "申请");
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
        for (TeacherApplicationDTO application : applications) {
            applicationList.getChildren().add(row(application));
        }
    }

    /**
     * 一行申请：两行文本加一颗「查看」按钮（与教学班列表的行内按钮同形）。未读的行在「查看」左边
     * 多一颗结果角标——它是服务端算出来的 {@code unread}，不是界面自己推的。整行可点，按钮供
     * 键盘/冒烟测试真实触发。
     */
    private HBox row(TeacherApplicationDTO application) {
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
        open.setOnAction(event -> select(application.getType(), application.getId()));

        HBox row = new HBox(8.0);
        row.setAlignment(Pos.CENTER_LEFT);
        if (application.isUnread()) {
            Label badge = new Label(UNREAD_BADGE_TEXT);
            badge.getStyleClass().add("teacher-course-application-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(badge);
        }
        row.getChildren().addAll(text, open);
        row.getStyleClass().add("teacher-course-application-row");
        row.setOnMouseClicked(event -> select(application.getType(), application.getId()));
        return row;
    }

    private void renderDetailPanel() {
        TeacherApplicationDTO summary = detail == null ? null : detail.getSummary();
        if (applicationDetailTitleLabel != null) {
            applicationDetailTitleLabel.setText(summary == null ? "请选择申请"
                    : typeLabel(summary.getType()) + " " + summary.getId() + "（"
                            + statusLabel(summary.getStatus()) + "）");
        }
        setActive(applicationDetailPlaceholder, detail == null && !loadingDetail);
        if (applicationDetailPlaceholder != null) applicationDetailPlaceholder.setText(DETAIL_PLACEHOLDER_TEXT);
        if (applicationDetailBody != null) {
            applicationDetailBody.getChildren().clear();
            for (String line : detailLines) {
                Label label = new Label(line);
                label.getStyleClass().add("teacher-course-detail-line");
                label.setWrapText(true);
                applicationDetailBody.getChildren().add(label);
            }
        }
        // 终态申请只读：撤销入口整个隐藏；提交在途时保留按钮但禁用。成绩提交永远没有撤销。
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
        if (detail == null || !canWithdraw()) return null;
        return WITHDRAW_PROMPT_TEXT;
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------ 纯文本

    /** 行标题：课程名与教学班代码。两类事实表的标题口径一致（都在服务端拼好）。 */
    static String summaryTitle(TeacherApplicationDTO application) {
        return orDash(application.getTitle());
    }

    /** 行副标题：类型、编号、状态、提交时间与未读角标的文字形态。 */
    static String summaryMeta(TeacherApplicationDTO application) {
        return typeLabel(application.getType()) + "　编号：" + orDash(application.getId())
                + "　状态：" + statusLabel(application.getStatus())
                + "　提交：" + orDash(application.getSubmittedAt());
    }

    /**
     * 详情行：公共头部（课程/编号/类型/状态/提交时间）加**恰好一个**类型化变体。
     * 调课详情给出原/新安排、原因与（PENDING 的）实时冲突；成绩详情给出批次统计与逐行快照，
     * 通篇只读——成绩批次是提交那一刻的冻结事实，这里没有任何编辑入口。
     */
    static List<String> detailLines(TeacherApplicationDetailDTO detail) {
        List<String> lines = new ArrayList<>();
        TeacherApplicationDTO summary = detail.getSummary();
        if (summary != null) {
            lines.add("课程：" + orDash(summary.getTitle()));
            lines.add("编号：" + orDash(summary.getId()) + "　类型："
                    + typeLabel(summary.getType()) + "　状态："
                    + statusLabel(summary.getStatus()));
            lines.add("提交时间：" + orDash(summary.getSubmittedAt()));
        }
        if (detail.getAdjustment() != null) {
            lines.addAll(adjustmentLines(detail.getAdjustment()));
        } else if (detail.getGrade() != null) {
            lines.addAll(gradeLines(detail.getGrade()));
        } else {
            lines.add(NO_DETAIL_TEXT);
        }
        if (summary != null && (summary.getHandledAt() != null
                || summary.getReviewComment() != null)) {
            lines.add("处理时间：" + orDash(summary.getHandledAt()));
            lines.add("处理意见：" + orDash(summary.getReviewComment()));
        }
        return List.copyOf(lines);
    }

    /** 调课申请：原因、版本、每个目标的原/新安排与 PENDING 的实时冲突快照。 */
    static List<String> adjustmentLines(AdjustmentRequestDetailDTO adjustment) {
        List<String> lines = new ArrayList<>();
        lines.add("申请原因：" + orDash(adjustment.getReason()) + "　版本：v"
                + adjustment.getVersion());
        for (AdjustmentTargetDTO target : adjustment.getTargets()) {
            lines.add(originalLine(target));
            lines.add(targetLine(adjustment, target));
        }
        for (ScheduleConflictDTO conflict : adjustment.getConflicts()) {
            lines.add(conflictLine(conflict));
        }
        if (adjustment.getConflicts().isEmpty()) {
            lines.add("冲突：无");
        }
        return lines;
    }

    /**
     * 成绩提交：只读快照。批次的人数与三项统计来自不可变批次头，逐行来自批次的明细——
     * 之后教师怎么改工作副本都不会回写这里，所以这页显示的永远是提交时的样子。
     */
    static List<String> gradeLines(GradeSubmissionDetailDTO grade) {
        List<String> lines = new ArrayList<>();
        if (grade.getSummary() != null) {
            lines.add("批次：v" + grade.getSummary().getVersion() + "　人数："
                    + grade.getSummary().getStudentCount()
                    + "　平均分：" + grade.getSummary().getAverage()
                    + "　最高分：" + grade.getSummary().getHighest()
                    + "　最低分：" + grade.getSummary().getLowest()
                    + "　不及格：" + grade.getSummary().getFailCount());
        }
        lines.add("成绩明细为提交时的只读快照，共 " + grade.getItems().size() + " 条");
        for (GradeSubmissionItemDTO item : grade.getItems()) {
            lines.add("学号 " + orDash(item.getStudentUid()) + "　" + orDash(item.getStudentName())
                    + "　总评：" + score(item.getScore()) + "　绩点：" + score(item.getGradePoint()));
        }
        if (grade.getCorrectionComparison() != null) {
            lines.add("本次更正相对基础批次的变化：" + grade.getCorrectionComparison().getChanges().size()
                    + " 名学生");
        }
        return lines;
    }

    private static String score(Double value) {
        return value == null ? "—" : String.valueOf(value);
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

    /**
     * 教师申请没有新教师/助教字段，显示该目标的原快照；管理员替换过人员时逐字段回落——只换教师
     * 时保留原助教，只换助教时保留原教师。实现与审批页共用 {@code AdminApprovalController} 的
     * 纯文本函数，避免两个页面出现两套回落语义。
     */
    static String personText(ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
            AdjustmentTargetDTO target) {
        return AdminApprovalController.personsText(newTeacher, newAssistant, target);
    }

    /** 冲突行：message 是主要信息（同一类型字符串会被不同场景复用），类型/级别只做补充。 */
    static String conflictLine(ScheduleConflictDTO conflict) {
        return "冲突（第 " + conflict.getWeek() + " 周）：" + orDash(conflict.getMessage()) + "　["
                + conflict.getType() + "/" + conflict.getSeverity() + "]";
    }

    static List<String> typeLabels() {
        return List.of(TYPE_ALL_LABEL, TYPE_ADJUSTMENT_LABEL, TYPE_GRADE_LABEL);
    }

    /** 状态选项跟随类型：成绩提交没有「已撤销」，就不给它一个必然返回空的选项。 */
    static List<String> statusLabels() {
        return statusLabels(null);
    }

    static List<String> statusLabels(String type) {
        if (TeacherApplicationDTO.GRADE_SUBMISSION.equals(type)) {
            return List.of(AdminApprovalController.PENDING_LABEL, AdminApprovalController.APPROVED_LABEL,
                    AdminApprovalController.REJECTED_LABEL);
        }
        return List.of(AdminApprovalController.PENDING_LABEL, AdminApprovalController.APPROVED_LABEL,
                AdminApprovalController.REJECTED_LABEL, AdminApprovalController.WITHDRAWN_LABEL);
    }

    /** 类型标签 → 服务端的类型常量；「全部」是 null（不限类型）。 */
    static String toType(String label) {
        if (TYPE_ADJUSTMENT_LABEL.equals(label)) return TeacherApplicationDTO.SCHEDULE_ADJUSTMENT;
        if (TYPE_GRADE_LABEL.equals(label)) return TeacherApplicationDTO.GRADE_SUBMISSION;
        return null;
    }

    /** 类型常量 → 界面标签。 */
    static String typeLabel(String type) {
        if (TeacherApplicationDTO.SCHEDULE_ADJUSTMENT.equals(type)) return TYPE_ADJUSTMENT_LABEL;
        if (TeacherApplicationDTO.GRADE_SUBMISSION.equals(type)) return TYPE_GRADE_LABEL;
        return TYPE_ALL_LABEL;
    }

    /** 状态标签 → 服务端的状态字符串（两张表共用的三个名字加调课独有的「已撤销」）。 */
    static String toStatus(String label) {
        if (AdminApprovalController.PENDING_LABEL.equals(label)) return "PENDING";
        if (AdminApprovalController.APPROVED_LABEL.equals(label)) return "APPROVED";
        if (AdminApprovalController.REJECTED_LABEL.equals(label)) return "REJECTED";
        if (AdminApprovalController.WITHDRAWN_LABEL.equals(label)) return "WITHDRAWN";
        return null;
    }

    /** 状态字符串 → 界面标签；未知状态原样显示，不猜一个更好看的名字。 */
    static String statusLabel(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "PENDING" -> AdminApprovalController.PENDING_LABEL;
            case "APPROVED" -> AdminApprovalController.APPROVED_LABEL;
            case "REJECTED" -> AdminApprovalController.REJECTED_LABEL;
            case "WITHDRAWN" -> AdminApprovalController.WITHDRAWN_LABEL;
            default -> status;
        };
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String typeLabelText() {
        return type == null ? "" : typeLabel(type);
    }

    private String statusLabelText() {
        return status == null ? "" : statusLabel(status);
    }

    private String pageText() {
        if (totalCount <= 0) return "共 0 条";
        return "第 " + page + "/" + totalPages() + " 页　共 " + totalCount + " 条";
    }

    private TeacherApplicationDTO summaryOf(String type, String id) {
        for (TeacherApplicationDTO application : applications) {
            if (type.equals(application.getType()) && id.equals(application.getId())) {
                return application;
            }
        }
        return selectedSummary != null && type.equals(selectedSummary.getType())
                && id.equals(selectedSummary.getId()) ? selectedSummary : null;
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

    List<TeacherApplicationDTO> applications() {
        return applications;
    }

    TeacherApplicationDetailDTO detail() {
        return detail;
    }

    /** 撤销可用的调课详情；成绩提交没有撤销入口，这里必然为 null。 */
    private AdjustmentRequestDetailDTO adjustmentOf() {
        return detail == null ? null : detail.getAdjustment();
    }

    String type() {
        return type;
    }

    String status() {
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

    int unreadCount() {
        return unreadCount;
    }

    /** 只有 PENDING 的调课申请可撤销：成绩提交没有撤销状态，服务端也没有 withdrawn_at 列。 */
    boolean canWithdraw() {
        TeacherApplicationDTO summary = detail == null ? null : detail.getSummary();
        return adjustmentOf() != null && summary != null
                && TeacherApplicationDTO.SCHEDULE_ADJUSTMENT.equals(summary.getType())
                && "PENDING".equals(summary.getStatus())
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
