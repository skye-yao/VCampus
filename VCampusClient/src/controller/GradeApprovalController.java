package controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import model.course.admin.AdminOperationResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 成绩审批页：作为审批控制台的第二个标签页，负责成绩提交列表、统计指标、成绩明细与审批决策。
 *
 * <p>外壳页 {@link AdminApprovalController} 通过 {@code fx:include} 注入本控制器，并只在成绩
 * 标签页处于激活状态时把共享的状态筛选交给本控制器刷新；隐藏时不会发起任何请求。所有用户
 * 可见的文本都由本类的纯函数生成，因此无需 JavaFX 运行时即可测试。
 */
public final class GradeApprovalController {
    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> infoReporter;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private final Function<AdminApprovalController.ReviewPrompt, String> textPrompt;

    private ApprovalStatusDTO status = ApprovalStatusDTO.PENDING;
    private int page = 1;
    private long totalCount;
    private List<GradeSubmissionSummaryDTO> submissions = List.of();
    private GradeSubmissionDetailDTO detail;
    private boolean loading;
    private boolean inFlight;
    private String errorText;
    private long listGeneration;
    private long detailGeneration;
    private DecisionKey pendingKey;
    private String pendingOperationId;

    @FXML private VBox submissionList;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private Label detailTitleLabel;
    @FXML private Label detailPlaceholder;
    @FXML private HBox metricCardBox;
    @FXML private VBox distributionRows;
    @FXML private TableView<GradeSubmissionItemDTO> itemTable;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> studentColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> nameColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> dailyColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> midtermColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> experimentColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> finaltermColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> totalColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> levelColumn;
    @FXML private TableColumn<GradeSubmissionItemDTO, String> pointColumn;
    @FXML private VBox detailBody;
    @FXML private Button approveButton;
    @FXML private Button rejectButton;

    public GradeApprovalController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm, AlertUtil::showInfo,
                AlertUtil::showError, Platform::runLater, GradeApprovalController::askForReason);
    }

    GradeApprovalController(AdminCourseService service,
            BiFunction<String, String, ButtonType> confirmation,
            BiConsumer<String, String> infoReporter,
            BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor,
            Function<AdminApprovalController.ReviewPrompt, String> textPrompt) {
        this.service = Objects.requireNonNull(service, "Admin course service is required");
        this.confirmation = Objects.requireNonNull(confirmation, "Confirmation is required");
        this.infoReporter = Objects.requireNonNull(infoReporter, "Info reporter is required");
        this.errorReporter = Objects.requireNonNull(errorReporter, "Error reporter is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.textPrompt = Objects.requireNonNull(textPrompt, "Text prompt is required");
    }

    @FXML
    private void initialize() {
        bindColumn(studentColumn, 0);
        bindColumn(nameColumn, 1);
        bindColumn(dailyColumn, 2);
        bindColumn(midtermColumn, 3);
        bindColumn(experimentColumn, 4);
        bindColumn(finaltermColumn, 5);
        bindColumn(totalColumn, 6);
        bindColumn(levelColumn, 7);
        bindColumn(pointColumn, 8);
    }

    /** 外壳页在切换到成绩标签页时调用：套用当前共享的状态筛选并加载第一页。 */
    void activate(ApprovalStatusDTO sharedStatus) {
        loadPage(sharedStatus == null ? ApprovalStatusDTO.PENDING : sharedStatus, 1);
    }

    @FXML
    void refresh() {
        loadPage(status, page);
    }

    @FXML
    void approveSelected() {
        if (!confirm("通过成绩批次", "确认通过该成绩批次？")) return;
        decide(true, null);
    }

    @FXML
    void rejectSelected() {
        String comment = textPrompt.apply(
                new AdminApprovalController.ReviewPrompt("驳回成绩批次", "请填写驳回意见"));
        if (comment == null) return; // 用户取消
        if (!confirm("驳回成绩批次", "确认驳回该成绩批次？")) return;
        decide(false, comment);
    }

    /**
     * 提交一次审批。决策负载一旦确定就只生成一次 operationId；只有完全相同的传输重试才会
     * 复用它。成绩审批没有可覆盖的冲突，因此永不发送 force。成功后列表与详情都从服务端刷新。
     */
    void decide(boolean approved, String reviewComment) {
        GradeSubmissionDetailDTO current = detail;
        if (current == null || current.getSummary().getStatus() != ApprovalStatusDTO.PENDING) {
            return;
        }
        if (inFlight) {
            return;
        }
        String comment = trimmed(reviewComment);
        if (!approved && isBlank(comment)) {
            errorReporter.accept("驳回失败", "驳回必须填写审批意见");
            return;
        }
        GradeSubmissionSummaryDTO summary = current.getSummary();
        String submissionId = summary.getSubmissionId();
        int version = summary.getVersion();
        DecisionKey key = new DecisionKey(submissionId, version, approved, comment);
        String operationId;
        if (key.equals(pendingKey) && pendingOperationId != null) {
            operationId = pendingOperationId; // 相同负载的传输重试沿用同一个幂等标识
        } else {
            operationId = UUID.randomUUID().toString();
            pendingKey = key;
            pendingOperationId = operationId;
        }
        ApprovalDecisionRequestDTO decision = new ApprovalDecisionRequestDTO(operationId, submissionId,
                version, approved, false, null, comment);
        inFlight = true;
        renderDecisionState();
        service.reviewGradeSubmission(decision).whenComplete((result, error) ->
                fxExecutor.accept(() -> {
                    inFlight = false;
                    if (error != null) {
                        handleReviewFailure(error, submissionId);
                        renderDecisionState();
                        return;
                    }
                    pendingKey = null;
                    pendingOperationId = null;
                    infoReporter.accept("审批完成", result.getMessage());
                    loadDetail(submissionId);
                    loadPage(status, page);
                    renderDecisionState();
                }));
    }

    void select(String submissionId) {
        if (submissionId == null || submissionId.isBlank()) return;
        loadDetail(submissionId);
    }

    void loadPage(ApprovalStatusDTO nextStatus, int nextPage) {
        this.status = nextStatus == null ? ApprovalStatusDTO.PENDING : nextStatus;
        this.page = Math.max(1, nextPage);
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listGradeSubmissionsPage(status, page, AdminApprovalController.PAGE_SIZE)
                .whenComplete((result, error) -> fxExecutor.accept(() -> {
                    if (generation != listGeneration) return; // 忽略过期请求
                    loading = false;
                    if (error != null) {
                        errorText = messageOf(error);
                        submissions = List.of();
                    } else {
                        submissions = result.getItems();
                        totalCount = result.getTotalCount();
                        errorText = null;
                    }
                    render();
                }));
    }

    void loadDetail(String submissionId) {
        long generation = ++detailGeneration;
        service.getGradeSubmission(submissionId).whenComplete((result, error) ->
                fxExecutor.accept(() -> {
                    if (generation != detailGeneration) return;
                    if (error != null) {
                        errorReporter.accept("加载详情失败", messageOf(error));
                        return;
                    }
                    renderDetail(result);
                }));
    }

    private void handleReviewFailure(Throwable error, String submissionId) {
        Throwable cause = rootCause(error);
        if (cause instanceof AdminCourseServiceException failure
                && failure.getCode() == MessageCode.CONFLICT) {
            if (failure.getLatest() instanceof GradeSubmissionDetailDTO latest) {
                renderDetail(latest);
            }
            errorReporter.accept("审批冲突", failure.getMessage());
            loadPage(status, page);
            return;
        }
        errorReporter.accept("审批失败", messageOf(error));
        if (detail == null) loadDetail(submissionId);
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        if (loadingLabel != null) setActive(loadingLabel, loading);
        if (emptyLabel != null) setActive(emptyLabel, !loading && errorText == null
                && submissions.isEmpty());
        if (errorLabel != null) {
            setActive(errorLabel, errorText != null);
            errorLabel.setText(errorText == null ? "" : errorText);
        }
        if (errorRetryButton != null) setActive(errorRetryButton, errorText != null);
        if (submissionList == null) return;
        submissionList.getChildren().clear();
        for (GradeSubmissionSummaryDTO submission : submissions) {
            Label title = new Label(summaryTitle(submission));
            title.getStyleClass().add("course-approval-row-title");
            title.setWrapText(true);
            Label meta = new Label(summaryMeta(submission));
            meta.getStyleClass().add("course-approval-row-meta");
            meta.setWrapText(true);
            VBox row = new VBox(4.0, title, meta);
            row.getStyleClass().add("course-approval-row");
            row.setOnMouseClicked(event -> select(submission.getSubmissionId()));
            submissionList.getChildren().add(row);
        }
    }

    private void renderDetail(GradeSubmissionDetailDTO value) {
        detail = value;
        GradeSubmissionSummaryDTO summary = value.getSummary();
        if (detailTitleLabel != null) {
            detailTitleLabel.setText("成绩提交 " + summary.getSubmissionId() + "（"
                    + AdminApprovalController.statusLabel(summary.getStatus()) + "）");
        }
        if (detailPlaceholder != null) setActive(detailPlaceholder, false);
        if (metricCardBox != null) {
            metricCardBox.getChildren().clear();
            for (MetricCard card : metricCards(value)) {
                Label title = new Label(card.title());
                title.getStyleClass().add("course-approval-metric-title");
                Label number = new Label(card.value());
                number.getStyleClass().add("course-approval-metric-value");
                VBox box = new VBox(2.0, title, number);
                box.getStyleClass().add("course-approval-metric-card");
                metricCardBox.getChildren().add(box);
            }
        }
        if (distributionRows != null) {
            distributionRows.getChildren().clear();
            for (String line : distributionLines(value.getDistribution())) {
                Label label = new Label(line);
                label.getStyleClass().add("course-approval-distribution-row");
                distributionRows.getChildren().add(label);
            }
        }
        if (itemTable != null) itemTable.getItems().setAll(value.getItems());
        if (detailBody != null) {
            detailBody.getChildren().clear();
            for (String line : detailLines(value)) {
                Label label = new Label(line);
                label.getStyleClass().add("course-approval-detail-line");
                label.setWrapText(true);
                detailBody.getChildren().add(label);
            }
        }
        renderDecisionState();
    }

    private void renderDecisionState() {
        boolean actionable = detail != null
                && detail.getSummary().getStatus() == ApprovalStatusDTO.PENDING && !inFlight;
        enable(approveButton, actionable);
        enable(rejectButton, actionable);
    }

    private void bindColumn(TableColumn<GradeSubmissionItemDTO, String> column, int index) {
        if (column == null) return;
        column.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(itemCells(cell.getValue()).get(index)));
    }

    private boolean confirm(String title, String message) {
        return ButtonType.OK.equals(confirmation.apply(title, message));
    }

    private static void enable(Button button, boolean enabled) {
        if (button == null) return;
        button.setDisable(!enabled);
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    private static String askForReason(AdminApprovalController.ReviewPrompt prompt) {
        TextArea area = new TextArea();
        area.setWrapText(true);
        area.setPrefRowCount(4);
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle(prompt.title());
        dialog.setHeaderText(prompt.header());
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(button -> ButtonType.OK.equals(button) ? area.getText() : null);
        return dialog.showAndWait().orElse(null);
    }

    // ------------------------------------------------------------------ 纯文本

    /** 列表行标题：课程名与教学班代码。 */
    static String summaryTitle(GradeSubmissionSummaryDTO submission) {
        return submission.getCourseName() + "　" + submission.getOfferingCode();
    }

    /** 列表行副标题：任课教师、学生人数、平均分、状态与提交时间。 */
    static String summaryMeta(GradeSubmissionSummaryDTO submission) {
        return "教师：" + submission.getTeacherName() + "（" + submission.getTeacherUid() + "）"
                + "　学生：" + submission.getStudentCount() + " 人"
                + "　平均：" + formatScore(submission.getAverage())
                + "　状态：" + AdminApprovalController.statusLabel(submission.getStatus())
                + "　提交：" + submission.getSubmittedAt();
    }

    /** 详情顶部的统计指标卡：顺序稳定以便断言。 */
    static List<MetricCard> metricCards(GradeSubmissionDetailDTO detail) {
        GradeSubmissionSummaryDTO summary = detail.getSummary();
        List<MetricCard> cards = new ArrayList<>();
        cards.add(new MetricCard("平均分", formatScore(summary.getAverage())));
        cards.add(new MetricCard("最高分", formatScore(summary.getHighest())));
        cards.add(new MetricCard("最低分", formatScore(summary.getLowest())));
        cards.add(new MetricCard("不及格", summary.getFailCount() + " 人"));
        return List.copyOf(cards);
    }

    /** 成绩分布：每个分段一行。 */
    static List<String> distributionLines(List<GradeDistributionBucketDTO> buckets) {
        List<String> lines = new ArrayList<>();
        for (GradeDistributionBucketDTO bucket : buckets) {
            lines.add(bucket.getLabel() + "：" + bucket.getCount() + " 人");
        }
        if (lines.isEmpty()) {
            lines.add("暂无成绩分布");
        }
        return List.copyOf(lines);
    }

    /** 详情面板的固定文本行，顺序稳定以便断言；已完成的批次附带审批信息。 */
    static List<String> detailLines(GradeSubmissionDetailDTO detail) {
        GradeSubmissionSummaryDTO summary = detail.getSummary();
        List<String> lines = new ArrayList<>();
        lines.add("教学班：" + summary.getOfferingId() + "　" + summary.getCourseName() + "　"
                + summary.getOfferingCode());
        lines.add("任课教师：" + summary.getTeacherName() + "（" + summary.getTeacherUid() + "）");
        lines.add("提交人数：" + summary.getStudentCount() + " 人　不及格："
                + summary.getFailCount() + " 人");
        lines.add("提交版本：v" + summary.getVersion() + "　提交：" + summary.getSubmittedAt());
        lines.add(schemeLine(detail.getSchemeSnapshot()));
        if (detail.getBaseSubmissionId() != null) {
            lines.add("基础批次：" + detail.getBaseSubmissionId());
        }
        if (detail.getUncoveredCount() > 0) {
            lines.add("未纳入批次的新成员：" + detail.getUncoveredCount()
                    + " 人（尚未纳入已提交批次，待该批结束后补录）");
        }
        if (detail.getReviewedBy() != null) {
            lines.add("审批人：" + detail.getReviewedBy() + "　审批时间：" + detail.getReviewedAt());
        }
        if (detail.getReviewComment() != null) {
            lines.add("审批意见：" + detail.getReviewComment());
        }
        return List.copyOf(lines);
    }

    /** 组成与权重一行显示：启用项按万分比给出百分比，禁用项写明未启用；历史批次没有快照。 */
    static String schemeLine(GradeSchemeDTO scheme) {
        if (scheme == null || scheme.getComponents() == null || scheme.getComponents().isEmpty()) {
            return "成绩组成：历史批次未记录方案快照，按旧验证规则审批";
        }
        StringBuilder line = new StringBuilder("成绩组成：");
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (line.length() > "成绩组成：".length()) line.append('　');
            if (component == null) {
                // Gson 直接写字段、可以绕过构造器：空组成按“未知”显示，绝不当作合法权重。
                line.append("未知 未启用");
                continue;
            }
            line.append(componentLabel(component.getCode())).append(' ');
            line.append(component.isEnabled() ? weightText(component.getWeightBasisPoints())
                    : "未启用");
        }
        return line.toString();
    }

    private static String componentLabel(GradeComponentCodeDTO code) {
        if (code == null) return "未知";
        return switch (code) {
            case DAILY -> "平时";
            case MIDTERM -> "期中";
            case EXPERIMENT -> "实验";
            case FINALTERM -> "期末";
        };
    }

    /** 权重是整数万分比：3000 → 30.00%。 */
    private static String weightText(int weightBasisPoints) {
        return BigDecimal.valueOf(weightBasisPoints, 2).toPlainString() + "%";
    }

    /** 一名学生的整行单元格文本，与成绩表列顺序一一对应。 */
    static List<String> itemCells(GradeSubmissionItemDTO item) {
        return List.of(
                orDash(item.getStudentUid()),
                orDash(item.getStudentName()),
                scoreText(item.getDailyScore()),
                scoreText(item.getMidtermScore()),
                scoreText(item.getExperimentScore()),
                scoreText(item.getFinaltermScore()),
                scoreText(item.getScore()),
                levelText(item.getGradeLevel()),
                scoreText(item.getGradePoint()));
    }

    /** 空成绩必须显示为占位符，绝不显示为 0 或空字符串。 */
    static String scoreText(Double value) {
        return value == null ? "--" : formatScore(value);
    }

    static String levelText(Integer value) {
        return value == null ? "--" : value.toString();
    }

    static String formatScore(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "--";
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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

    // -------------------------------------------------------------------- 值对象

    /** 统计指标卡：标题与取值。 */
    record MetricCard(String title, String value) {
    }

    /** 一次决策的幂等键：完全相同的键才允许复用 operationId。 */
    private record DecisionKey(String submissionId, int version, boolean approved, String comment) {
    }

    // -------------------------------------------------------------- 测试访问器

    List<GradeSubmissionSummaryDTO> submissions() {
        return submissions;
    }

    GradeSubmissionDetailDTO detail() {
        return detail;
    }

    ApprovalStatusDTO status() {
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

    boolean inFlight() {
        return inFlight;
    }

    String errorText() {
        return errorText;
    }
}
