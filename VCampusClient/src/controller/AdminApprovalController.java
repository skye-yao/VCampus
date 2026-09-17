package controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 管理员审批页面外壳：调课审批（本类）与成绩审批（{@link GradeApprovalController}）两个标签页。
 *
 * <p>状态筛选由外壳共享：切换标签页时新激活的子页拿到当前筛选并刷新，隐藏的子页不会发起
 * 请求。FXML 只提供稳定骨架；列表行、详情文本与冲突说明都由控制器按服务端结果生成，且这些
 * 生成逻辑都是纯函数，因此无需 JavaFX 运行时即可测试。
 */
public final class AdminApprovalController {
    static final String PENDING_LABEL = "待审批";
    static final String APPROVED_LABEL = "已通过";
    static final String REJECTED_LABEL = "已驳回";
    /** 教师撤销是调课独有的终态，成绩审批没有这个状态。 */
    static final String WITHDRAWN_LABEL = "已撤销";
    static final int PAGE_SIZE = 20;

    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> infoReporter;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private final Function<ReviewPrompt, String> textPrompt;

    private AdjustmentRequestStatusDTO status = AdjustmentRequestStatusDTO.PENDING;
    private int page = 1;
    private long totalCount;
    private List<AdjustmentRequestSummaryDTO> requests = List.of();
    private AdjustmentRequestDetailDTO detail;
    private boolean loading;
    private boolean gradeActive;
    private String errorText;
    private long listGeneration;
    private long detailGeneration;

    @FXML private ToggleButton adjustmentTabButton;
    @FXML private ToggleButton gradeTabButton;
    @FXML private ComboBox<String> statusFilter;
    @FXML private Button refreshButton;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private VBox adjustmentPanel;
    @FXML private VBox requestList;
    @FXML private Label detailTitleLabel;
    @FXML private VBox detailBody;
    @FXML private Label detailPlaceholder;
    @FXML private Node gradePage;
    @FXML private GradeApprovalController gradePageController;
    @FXML private Button approveButton;
    @FXML private Button rejectButton;
    @FXML private Button forceApproveButton;

    public AdminApprovalController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm, AlertUtil::showInfo,
                AlertUtil::showError, Platform::runLater, AdminApprovalController::askForText);
    }

    AdminApprovalController(AdminCourseService service,
            BiFunction<String, String, ButtonType> confirmation,
            BiConsumer<String, String> infoReporter,
            BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor,
            Function<ReviewPrompt, String> textPrompt) {
        this.service = Objects.requireNonNull(service, "Admin course service is required");
        this.confirmation = Objects.requireNonNull(confirmation, "Confirmation is required");
        this.infoReporter = Objects.requireNonNull(infoReporter, "Info reporter is required");
        this.errorReporter = Objects.requireNonNull(errorReporter, "Error reporter is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.textPrompt = Objects.requireNonNull(textPrompt, "Text prompt is required");
    }

    @FXML
    public void initialize() {
        statusFilter.getItems().setAll(PENDING_LABEL, APPROVED_LABEL, REJECTED_LABEL,
                WITHDRAWN_LABEL);
        statusFilter.setValue(PENDING_LABEL);
        statusFilter.valueProperty().addListener(
                (observable, oldValue, newValue) -> applyStatus(newValue));
        showAdjustments();
    }

    @FXML
    public void refresh() {
        if (gradeActive) {
            if (gradePageController != null) gradePageController.refresh();
            return;
        }
        loadPage(status, page);
    }

    @FXML
    void showAdjustments() {
        setTab(true);
        loadPage(status, 1);
    }

    @FXML
    void showGrades() {
        setTab(false);
        ApprovalStatusDTO gradeFilter = gradeFallback();
        if (gradePageController != null) gradePageController.activate(gradeFilter);
    }

    public void openAdjustmentsFromDashboard() { showAdjustments(); }

    public void openGradesFromDashboard() { showGrades(); }

    /**
     * 成绩页只有三态。共享筛选是它没有的已撤销时，把共享状态与组合框一起回落到成绩页实际加载的
     * PENDING，避免标签（已撤销）与已加载的行（待审批）不符；setValue 会经监听器回到
     * {@link #applyStatus}，而那时状态已经一致，不会重复请求。
     */
    private ApprovalStatusDTO gradeFallback() {
        ApprovalStatusDTO mapped = gradeStatus(status);
        if (mapped != null) return mapped;
        status = AdjustmentRequestStatusDTO.PENDING;
        if (statusFilter != null && !PENDING_LABEL.equals(statusFilter.getValue())) {
            statusFilter.setValue(PENDING_LABEL);
        }
        return ApprovalStatusDTO.PENDING;
    }

    @FXML
    void approveSelected() {
        if (!confirm("通过调课申请", "确认通过该调课申请？")) return;
        review(true, false, null, null);
    }

    @FXML
    void rejectSelected() {
        String comment = textPrompt.apply(new ReviewPrompt("驳回调课申请", "请填写驳回意见"));
        if (comment == null) return; // 用户取消
        if (!confirm("驳回调课申请", "确认驳回该调课申请？")) return;
        review(false, false, trimmed(comment), null);
    }

    @FXML
    void forceApproveSelected() {
        String reason = textPrompt.apply(new ReviewPrompt("强制通过调课申请", "请填写强制通过的原因"));
        if (reason == null) return;
        if (!confirm("强制通过调课申请", "确认忽略冲突并强制通过？")) return;
        review(true, true, null, trimmed(reason));
    }

    private boolean confirm(String title, String message) {
        return ButtonType.OK.equals(confirmation.apply(title, message));
    }

    void applyStatus(String label) {
        AdjustmentRequestStatusDTO next = toStatus(label);
        if (next == status) {
            return;
        }
        status = next; // 共享筛选；切换标签页时新激活的子页会拿到同一个值
        if (gradeActive) {
            // 成绩页里直接改共享筛选也一样：没有三态对应项（已撤销）时状态与标签一起回落。
            ApprovalStatusDTO gradeFilter = gradeFallback();
            if (gradePageController != null) gradePageController.loadPage(gradeFilter, 1);
            return;
        }
        loadPage(next, 1);
    }

    void select(String requestId) {
        if (requestId == null || requestId.isBlank()) return;
        loadDetail(requestId);
    }

    /**
     * 提交一次审批。冲突不会中断界面：最新的服务端状态会被渲染出来，列表同时刷新。
     */
    void review(boolean approved, boolean force, String reviewComment, String overrideReason) {
        AdjustmentRequestDetailDTO current = detail;
        if (current == null || current.getStatus() != AdjustmentRequestStatusDTO.PENDING) {
            return;
        }
        if (!approved && isBlank(reviewComment)) {
            errorReporter.accept("驳回失败", "驳回必须填写审批意见");
            return;
        }
        if (force && isBlank(overrideReason)) {
            errorReporter.accept("强制通过失败", "强制通过必须填写原因");
            return;
        }
        ApprovalDecisionRequestDTO decision = new ApprovalDecisionRequestDTO(
                UUID.randomUUID().toString(), current.getRequestId(), current.getVersion(),
                approved, force, trimmed(overrideReason), trimmed(reviewComment));
        service.reviewAdjustmentRequest(decision).whenComplete((result, error) ->
                fxExecutor.accept(() -> {
                    if (error != null) {
                        handleReviewFailure(error, current.getRequestId());
                        return;
                    }
                    infoReporter.accept("审批完成", result.getMessage());
                    loadDetail(current.getRequestId());
                    loadPage(status, page);
                }));
    }

    void loadPage(AdjustmentRequestStatusDTO nextStatus, int nextPage) {
        this.status = nextStatus;
        this.page = Math.max(1, nextPage);
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listAdjustmentRequestsByStatus(status, page, PAGE_SIZE)
                .whenComplete((result, error) -> fxExecutor.accept(() -> {
                    if (generation != listGeneration) return; // 忽略过期请求
                    loading = false;
                    if (error != null) {
                        errorText = messageOf(error);
                        requests = List.of();
                    } else {
                        requests = result.getItems();
                        totalCount = result.getTotalCount();
                        errorText = null;
                    }
                    render();
                }));
    }

    void loadDetail(String requestId) {
        long generation = ++detailGeneration;
        service.getAdjustmentRequest(requestId).whenComplete((result, error) ->
                fxExecutor.accept(() -> {
                    if (generation != detailGeneration) return;
                    if (error != null) {
                        errorReporter.accept("加载详情失败", messageOf(error));
                        return;
                    }
                    renderDetail(result);
                }));
    }

    private void handleReviewFailure(Throwable error, String requestId) {
        Throwable cause = rootCause(error);
        if (cause instanceof AdminCourseServiceException failure
                && failure.getCode() == MessageCode.CONFLICT) {
            if (failure.getLatest() instanceof AdjustmentRequestDetailDTO latest) {
                renderDetail(latest);
            }
            errorReporter.accept("审批冲突", conflictMessage(failure));
            loadPage(status, page);
            return;
        }
        errorReporter.accept("审批失败", cause.getMessage() == null ? "未知错误" : cause.getMessage());
        if (detail == null) loadDetail(requestId);
    }

    private void setTab(boolean adjustments) {
        gradeActive = !adjustments;
        setActive(adjustmentPanel, adjustments);
        setActive(gradePage, !adjustments);
        if (adjustmentTabButton != null) adjustmentTabButton.setSelected(adjustments);
        if (gradeTabButton != null) gradeTabButton.setSelected(!adjustments);
    }

    private void render() {
        if (loadingLabel != null) setActive(loadingLabel, loading);
        if (emptyLabel != null) setActive(emptyLabel, !loading && errorText == null
                && requests.isEmpty());
        if (errorLabel != null) {
            setActive(errorLabel, errorText != null);
            errorLabel.setText(errorText == null ? "" : errorText);
        }
        if (errorRetryButton != null) setActive(errorRetryButton, errorText != null);
        if (requestList == null) return;
        requestList.getChildren().clear();
        for (AdjustmentRequestSummaryDTO request : requests) {
            Label title = new Label(summaryTitle(request));
            title.getStyleClass().add("course-approval-row-title");
            title.setWrapText(true);
            Label meta = new Label(summaryMeta(request));
            meta.getStyleClass().add("course-approval-row-meta");
            meta.setWrapText(true);
            VBox row = new VBox(4.0, title, meta);
            row.getStyleClass().add("course-approval-row");
            row.setOnMouseClicked(event -> select(request.getRequestId()));
            requestList.getChildren().add(row);
        }
    }

    private void renderDetail(AdjustmentRequestDetailDTO value) {
        detail = value;
        if (detailTitleLabel != null) {
            detailTitleLabel.setText("调课申请 " + value.getRequestId() + "（"
                    + statusLabel(value.getStatus()) + "）");
        }
        if (detailPlaceholder != null) setActive(detailPlaceholder, false);
        if (detailBody != null) {
            detailBody.getChildren().clear();
            for (String line : detailLines(value)) {
                Label label = new Label(line);
                label.getStyleClass().add("course-approval-detail-line");
                label.setWrapText(true);
                detailBody.getChildren().add(label);
            }
            for (ArrangementRow row : arrangementRows(value)) {
                Label label = new Label(row.week() + "　原：" + row.original() + "　→　新："
                        + row.adjusted());
                label.getStyleClass().add("course-approval-arrangement-line");
                label.setWrapText(true);
                detailBody.getChildren().add(label);
            }
        }
        boolean actionable = actionable(value);
        enable(approveButton, actionable);
        enable(rejectButton, actionable);
        enable(forceApproveButton, actionable);
    }

    /** 只有 PENDING 可以审批；APPROVED/REJECTED/WITHDRAWN 都是终态，详情只读。 */
    static boolean actionable(AdjustmentRequestDetailDTO detail) {
        return detail != null && detail.getStatus() == AdjustmentRequestStatusDTO.PENDING;
    }

    private static void enable(Button button, boolean enabled) {
        if (button == null) return;
        button.setDisable(!enabled);
    }

    private static void setActive(javafx.scene.Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    private static String askForText(ReviewPrompt prompt) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(prompt.title());
        dialog.setHeaderText(prompt.header());
        return dialog.showAndWait().orElse(null);
    }

    // ------------------------------------------------------------------ 纯文本

    /** 列表行标题：课程名与教学班代码。 */
    static String summaryTitle(AdjustmentRequestSummaryDTO request) {
        return request.getCourseName() + "　" + request.getOfferingCode();
    }

    /** 列表行副标题：申请人、目标周数、状态与提交时间。 */
    static String summaryMeta(AdjustmentRequestSummaryDTO request) {
        return "申请人：" + request.getApplicantName() + "（" + request.getApplicantUid() + "）"
                + "　目标周数：" + request.getTargetWeekCount()
                + "　状态：" + statusLabel(request.getStatus())
                + "　提交：" + request.getSubmittedAt();
    }

    /** 详情面板的固定文本行，顺序稳定以便断言。 */
    static List<String> detailLines(AdjustmentRequestDetailDTO detail) {
        List<String> lines = new ArrayList<>();
        lines.add("教学班：" + detail.getOfferingId());
        lines.add("申请人：" + detail.getApplicantUid());
        lines.add("申请原因：" + detail.getReason());
        lines.add("申请版本：v" + detail.getVersion() + "　提交：" + detail.getSubmittedAt());
        if (detail.getReviewedBy() != null) {
            lines.add("审批人：" + detail.getReviewedBy() + "　审批时间：" + detail.getReviewedAt());
        }
        if (detail.getReviewComment() != null) {
            lines.add("审批意见：" + detail.getReviewComment());
        }
        lines.add("新安排：" + describeAdjusted(detail));
        for (ScheduleConflictDTO conflict : detail.getConflicts()) {
            lines.add(conflictLine(conflict));
        }
        if (detail.getConflicts().isEmpty()) {
            lines.add("冲突：无");
        }
        return List.copyOf(lines);
    }

    /**
     * 每个目标一行：左侧原安排、右侧新安排。新安排显示该目标自己的实际日期（V006 之前的旧行
     * 没有日期，退回原来的“星期 + 节次”显示），人员与教室按字段回落到该目标的原快照。
     */
    static List<ArrangementRow> arrangementRows(AdjustmentRequestDetailDTO detail) {
        List<ArrangementRow> rows = new ArrayList<>();
        for (AdjustmentTargetDTO target : detail.getTargets()) {
            rows.add(new ArrangementRow("第 " + target.getWeek() + " 周",
                    target.getOriginalStartAt() + "~" + target.getOriginalEndAt() + "　"
                            + personsText(null, null, target) + "　"
                            + orDash(target.getOriginalClassroom()),
                    describeAdjusted(detail, target)));
        }
        return List.copyOf(rows);
    }

    static List<String> conflictLines(List<ScheduleConflictDTO> conflicts) {
        List<String> lines = new ArrayList<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            lines.add(conflictLine(conflict));
        }
        return List.copyOf(lines);
    }

    private static String conflictLine(ScheduleConflictDTO conflict) {
        return "冲突（第 " + conflict.getWeek() + " 周）：" + conflict.getMessage()
                + "　[" + conflict.getType() + "/" + conflict.getSeverity() + "]";
    }

    /**
     * 请求级的新安排说明：人员与教室同样按字段回落到目标原快照（多目标申请以首个目标的快照为
     * 代表，逐目标的精确值以每个目标行显示为准）；目标日期能确定时一并列出。
     */
    private static String describeAdjusted(AdjustmentRequestDetailDTO detail) {
        AdjustmentTargetDTO snapshot = detail.getTargets().isEmpty()
                ? null : detail.getTargets().get(0);
        String text = weekdayName(detail.getNewDayOfWeek()) + " 第 " + detail.getNewStartPeriod()
                + "-" + detail.getNewEndPeriod() + " 节　"
                + personsText(detail.getNewTeacher(), detail.getNewAssistant(), snapshot)
                + "　" + classroomText(detail.getNewClassroom(), snapshot);
        List<String> dates = targetDates(detail);
        return dates.isEmpty() ? text : text + "　目标日期：" + String.join("、", dates);
    }

    /**
     * 单个目标的新安排：目标日期是显式的 ISO 本地日期。服务端按字段写入替换资源
     * （{@code ScheduleAdjustmentApprovalService} 只替换请求里给出的资源，其余保留目标原值），
     * 因此这里也逐字段回落：只换教师时保留原助教，只换助教时保留原教师。
     */
    static String describeAdjusted(AdjustmentRequestDetailDTO detail, AdjustmentTargetDTO target) {
        String date = target.getTargetDate() == null ? "" : target.getTargetDate() + " ";
        return date + weekdayName(detail.getNewDayOfWeek()) + " 第 " + detail.getNewStartPeriod()
                + "-" + detail.getNewEndPeriod() + " 节　"
                + personsText(detail.getNewTeacher(), detail.getNewAssistant(), target)
                + "　" + classroomText(detail.getNewClassroom(), target);
    }

    /**
     * 新安排的人员：教师与助教各自独立回落 —— 只换教师时保留原助教，只换助教时保留原教师，
     * 两侧都不替换时整体保持原快照。快照缺失或为空的一侧给占位符。
     */
    static String personsText(ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
            AdjustmentTargetDTO snapshot) {
        String teacher = newTeacher != null
                ? orDash(newTeacher.getName())
                : orDash(snapshot == null ? null : snapshot.getOriginalTeacher());
        String assistant = newAssistant != null
                ? orDash(newAssistant.getName())
                : blankToNull(snapshot == null ? null : snapshot.getOriginalAssistant());
        return assistant == null ? teacher : teacher + ", " + assistant;
    }

    /** 教室：请求给出新教室时显示新资源，否则回落到该目标的原教室；没有快照时给占位符。 */
    private static String classroomText(ScheduleResourceDTO newClassroom,
            AdjustmentTargetDTO snapshot) {
        if (newClassroom != null) return orDash(newClassroom.getName());
        return orDash(snapshot == null ? null : snapshot.getOriginalClassroom());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 请求里出现过的目标日期（去重、保持目标顺序）；旧行没有日期时为空。 */
    static List<String> targetDates(AdjustmentRequestDetailDTO detail) {
        List<String> dates = new ArrayList<>();
        for (AdjustmentTargetDTO target : detail.getTargets()) {
            String date = target.getTargetDate();
            if (date != null && !date.isBlank() && !dates.contains(date)) {
                dates.add(date);
            }
        }
        return List.copyOf(dates);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    static AdjustmentRequestStatusDTO toStatus(String label) {
        if (APPROVED_LABEL.equals(label)) return AdjustmentRequestStatusDTO.APPROVED;
        if (REJECTED_LABEL.equals(label)) return AdjustmentRequestStatusDTO.REJECTED;
        if (WITHDRAWN_LABEL.equals(label)) return AdjustmentRequestStatusDTO.WITHDRAWN;
        return AdjustmentRequestStatusDTO.PENDING;
    }

    /**
     * 调课状态是四态：教师撤销也必须能显示，不能被当成管理员驳回。
     */
    static String statusLabel(AdjustmentRequestStatusDTO status) {
        return switch (status) {
            case APPROVED -> APPROVED_LABEL;
            case REJECTED -> REJECTED_LABEL;
            case WITHDRAWN -> WITHDRAWN_LABEL;
            case PENDING -> PENDING_LABEL;
        };
    }

    /** 成绩审批沿用三态枚举，按枚举名复用同一套标签，三态里没有 WITHDRAWN。 */
    static String statusLabel(ApprovalStatusDTO status) {
        return statusLabel(status == null ? null : AdjustmentRequestStatusDTO.valueOf(status.name()));
    }

    /**
     * 共享筛选交给成绩页：三态枚举没有 WITHDRAWN，教师撤销只影响调课页，切到成绩页时回落到它的
     * 默认状态（{@code activate(null)} 按 PENDING 处理），而不是在 {@code valueOf} 上抛异常。
     */
    static ApprovalStatusDTO gradeStatus(AdjustmentRequestStatusDTO status) {
        if (status == null || status == AdjustmentRequestStatusDTO.WITHDRAWN) return null;
        return ApprovalStatusDTO.valueOf(status.name());
    }

    static String weekdayName(int dayOfWeek) {
        String[] weekdays = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? weekdays[dayOfWeek] : "周" + dayOfWeek;
    }

    private static String conflictMessage(AdminCourseServiceException failure) {
        if (failure.getConflicts().isEmpty()) {
            return failure.getMessage();
        }
        return failure.getMessage() + "（" + conflictLines(failure.getConflicts()).size()
                + " 条冲突）";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String messageOf(Throwable error) {
        Throwable cause = rootCause(error);
        return cause.getMessage() == null ? "未知错误" : cause.getMessage();
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // -------------------------------------------------------------------- 值对象

    /** 需要用户补填的文本输入。 */
    record ReviewPrompt(String title, String header) {
    }

    /** 详情弹窗的一行：同一目标周的原安排与新安排并列。 */
    record ArrangementRow(String week, String original, String adjusted) {
    }

    // -------------------------------------------------------------- 测试访问器

    List<AdjustmentRequestSummaryDTO> requests() {
        return requests;
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

    boolean loading() {
        return loading;
    }

    String errorText() {
        return errorText;
    }
}
