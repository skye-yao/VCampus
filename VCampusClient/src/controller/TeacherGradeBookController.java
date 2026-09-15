package controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.util.StringConverter;
import model.course.teacher.GradeBookEditorModel;
import model.course.teacher.GradeBookEditorModel.Column;
import model.course.teacher.GradeBookEditorModel.Row;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import util.AlertUtil;
import util.PageLeaveGuard;

/**
 * 一个教学班的成绩编辑表（设计 §5.4）：四列成绩 + 列名旁的启用开关与百分比权重，
 * 保存草稿、二次确认提交，以及离开保护。
 *
 * <p>状态分离：{@link GradeBookEditorModel} 保存原文与解析结果，本控制器只做渲染与请求编排。
 * 界面上能看到的每个数字都来自模型：总评/绩点是模型用与服务端相同的纯规则对当前编辑内容做的预览，
 * 权重未配齐或缺启用项分数时显示占位符，绝不伪造一个总评。
 *
 * <p>写请求的失败语义（设计 §5.4）：成功才用服务端快照覆盖编辑内容（{@code applyServerSnapshot}），
 * 失败一律保留用户已经输入的内容，只在提示区显示原因——保存失败把表格回滚成旧数据是明确禁止的。
 * 冲突（版本过期/名单变化）同样保留编辑，提示用户手动重新加载后重试。
 *
 * <p>幂等：一次“确认提交”流程共用一个 operationId，重复点击确认只会重放同一个操作而不是产生
 * 第二个批次；切班、重新加载或提交成功后作废该 ID，下一次写操作一定是新的 UUID。保存同理——
 * 成功后才换新 ID，失败重试仍然复用同一个（响应丢失时服务端重放已提交的结果）。
 *
 * <p>离开保护（{@link PageLeaveGuard}）：本页成为当前页时注册自己，离开时注销。有未保存修改时
 * {@link #requestLeave()} 询问用户，拒绝返回 false 让调用方 {@code consume} 关闭事件或保持页面；
 * 允许离开后 {@link #onClosed()} 取消在途请求并保证之后的响应不再写界面。
 *
 * <p>所有节点都可能为 {@code null}，控制器测试因此无需 JavaFX 工具包；离开确认函数可注入，
 * 测试不会真的弹对话框。
 */
public final class TeacherGradeBookController implements PageLeaveGuard {
    static final String LOAD_FAILURE_TEXT = "成绩表加载失败，请重试";
    static final String SAVE_SUCCESS_TEXT = "成绩草稿已保存";
    static final String SAVE_FAILURE_TEXT = "保存失败，已保留你的修改，请重试";
    static final String SAVE_CONFLICT_PREFIX = "成绩表已被其他操作更新，已保留你的修改；请重新加载后再保存：";
    static final String SUBMIT_SUCCESS_TEXT = "成绩批次已提交，等待管理员审核";
    static final String SUBMIT_FAILURE_TEXT = "提交失败，已保留你的修改，请重试";
    static final String SUBMIT_CONFLICT_PREFIX = "提交被拒绝，已保留你的修改；请重新加载后再提交：";
    static final String SUBMIT_PROMPT_TEXT = "提交后成绩表将被锁定并进入审核，确定提交吗？";
    static final String SAVING_TEXT = "正在保存...";
    static final String SUBMITTING_TEXT = "正在提交...";
    static final String DIRTY_TEXT = "有未保存的修改";
    static final String CLEAN_TEXT = "没有未保存的修改";
    static final String LEAVE_PROMPT_TEXT = "成绩表有未保存的修改，离开将丢失这些修改。确定离开吗？";
    static final String RELOAD_PROMPT_TEXT = "重新加载会丢弃未保存的修改，确定重新加载吗？";
    static final String PLACEHOLDER = "—";

    private static final String DISABLED_CELL_CLASS = "teacher-course-grade-cell-disabled";
    private static final String ERROR_CELL_CLASS = "teacher-course-grade-cell-error";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;
    /** 离开/重新加载确认：消息 → 是否同意。默认弹对话框，测试注入固定回答。 */
    private final Function<String, Boolean> confirmation;

    private Runnable onBack = () -> { };
    private GradeBookEditorModel model;
    private String offeringId;
    private boolean active;
    private boolean loading;
    private boolean saving;
    private boolean submitting;
    private boolean confirmingSubmit;
    private boolean syncingScheme;
    private String feedbackText;
    private String errorText;
    private String pendingSaveOperationId;
    private String pendingSubmitOperationId;
    private long generation;

    @FXML private Label gradeBookTitleLabel;
    @FXML private Label gradeBookStateLabel;
    @FXML private Label gradeBookNoticeLabel;
    @FXML private Label gradeBookSchemeLabel;
    @FXML private Label gradeBookFeedbackLabel;
    @FXML private Label gradeBookErrorLabel;
    @FXML private Button gradeBookReloadButton;
    @FXML private Button gradeBookSaveButton;
    @FXML private Button gradeBookSubmitButton;
    @FXML private Button gradeBookConfirmSubmitButton;
    @FXML private Button gradeBookCancelSubmitButton;
    @FXML private Button gradeBookBackButton;
    @FXML private Label gradeBookLoadingLabel;
    @FXML private Label gradeBookEmptyLabel;
    @FXML private TableView<Row> gradeBookTable;
    @FXML private TableColumn<Row, String> gradeBookUidColumn;
    @FXML private TableColumn<Row, String> gradeBookNameColumn;
    @FXML private TableColumn<Row, String> gradeBookDailyColumn;
    @FXML private TableColumn<Row, String> gradeBookMidtermColumn;
    @FXML private TableColumn<Row, String> gradeBookExperimentColumn;
    @FXML private TableColumn<Row, String> gradeBookFinaltermColumn;
    @FXML private TableColumn<Row, String> gradeBookTotalColumn;
    @FXML private TableColumn<Row, String> gradeBookPointColumn;
    @FXML private TableColumn<Row, String> gradeBookErrorColumn;
    @FXML private CheckBox gradeBookDailyEnabled;
    @FXML private TextField gradeBookDailyWeight;
    @FXML private CheckBox gradeBookMidtermEnabled;
    @FXML private TextField gradeBookMidtermWeight;
    @FXML private CheckBox gradeBookExperimentEnabled;
    @FXML private TextField gradeBookExperimentWeight;
    @FXML private CheckBox gradeBookFinaltermEnabled;
    @FXML private TextField gradeBookFinaltermWeight;

    public TeacherGradeBookController() {
        this(TeacherCourseServices.current(), Platform::runLater, TeacherGradeBookController::confirm);
    }

    TeacherGradeBookController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this(service, fxExecutor, TeacherGradeBookController::confirm);
    }

    TeacherGradeBookController(TeacherCourseService service, Consumer<Runnable> fxExecutor,
            Function<String, Boolean> confirmation) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.confirmation = Objects.requireNonNull(confirmation, "Confirmation is required");
    }

    /** 生产路径的确认对话框：在 FX 线程弹模态框，等待用户选择。 */
    private static boolean confirm(String message) {
        return AlertUtil.showConfirm("未保存的成绩", message) == ButtonType.OK;
    }

    @FXML
    public void initialize() {
        if (gradeBookTable != null) {
            gradeBookTable.setEditable(true);
            // 九列在 860 宽的窗口里放不下：保留列宽并横向滚动，而不是把文字压成省略号。
            gradeBookTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        }
        bindTextColumn(gradeBookUidColumn, row -> row.studentUid());
        bindTextColumn(gradeBookNameColumn, row -> row.studentName());
        bindScoreColumn(gradeBookDailyColumn, GradeComponentCodeDTO.DAILY);
        bindScoreColumn(gradeBookMidtermColumn, GradeComponentCodeDTO.MIDTERM);
        bindScoreColumn(gradeBookExperimentColumn, GradeComponentCodeDTO.EXPERIMENT);
        bindScoreColumn(gradeBookFinaltermColumn, GradeComponentCodeDTO.FINALTERM);
        bindTextColumn(gradeBookTotalColumn, this::totalText);
        bindTextColumn(gradeBookPointColumn, this::pointText);
        bindTextColumn(gradeBookErrorColumn, row -> String.join("；", row.serverErrors()));
        wireScheme(GradeComponentCodeDTO.DAILY, gradeBookDailyEnabled, gradeBookDailyWeight);
        wireScheme(GradeComponentCodeDTO.MIDTERM, gradeBookMidtermEnabled, gradeBookMidtermWeight);
        wireScheme(GradeComponentCodeDTO.EXPERIMENT, gradeBookExperimentEnabled,
                gradeBookExperimentWeight);
        wireScheme(GradeComponentCodeDTO.FINALTERM, gradeBookFinaltermEnabled,
                gradeBookFinaltermWeight);
        render();
    }

    // ------------------------------------------------------------------ 生命周期

    /** 工作台打开某个教学班的成绩表：注册离开守卫并加载最新草稿。 */
    void showOffering(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        this.offeringId = offeringId;
        // 换班即丢弃上一个班的编辑内容与幂等 ID：新班必须拿到自己的 revision 与名单摘要。
        this.model = null;
        this.pendingSaveOperationId = null;
        this.pendingSubmitOperationId = null;
        this.confirmingSubmit = false;
        this.feedbackText = null;
        this.errorText = null;
        this.active = true;
        PageLeaveGuard.install(this);
        render();
        loadBook();
    }

    /**
     * 工作台离开本页：取消在途请求、注销离开守卫。页面被卸下之后响应不再写界面，
     * 因此不需要（也不允许）在 {@code release()} 之后继续更新控件。
     */
    void release() {
        active = false;
        loading = false;
        saving = false;
        submitting = false;
        confirmingSubmit = false;
        generation++;
        PageLeaveGuard.clear(this);
    }

    @Override
    public boolean requestLeave() {
        if (!dirty()) return true;
        return confirmation.apply(LEAVE_PROMPT_TEXT);
    }

    @Override
    public void onClosed() {
        release();
    }

    /** 返回成绩列表：工作台会先检查离开守卫，所以这里只负责把请求转出去。 */
    @FXML
    void handleBack(Event event) {
        onBack.run();
    }

    void setOnBack(Runnable onBack) {
        this.onBack = onBack == null ? () -> { } : onBack;
    }

    // ------------------------------------------------------------------ 加载

    @FXML
    void handleReload(Event event) {
        reload();
    }

    /** 重新加载：会丢弃未保存的修改，因此先走与离开页面同一个确认；被拒绝时保持当前内容不动。 */
    void reload() {
        if (model != null && !requestLeave()) return;
        pendingSaveOperationId = null;
        pendingSubmitOperationId = null;
        loadBook();
    }

    private void loadBook() {
        if (offeringId == null) return;
        long current = ++generation;
        loading = true;
        errorText = null;
        // 重新加载作废在途写请求的响应（generation 已经变了）；写入标志复位，按钮不会永久禁用。
        saving = false;
        submitting = false;
        render();
        service.getGradeBook(offeringId).whenComplete((book, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            loading = false;
            if (failure != null) {
                errorText = failureText(failure, LOAD_FAILURE_TEXT);
                render();
                return;
            }
            model = new GradeBookEditorModel(book);
            errorText = null;
            render();
        }));
    }

    // ------------------------------------------------------------------ 保存与提交

    @FXML
    void handleSave(Event event) {
        save();
    }

    /** 保存草稿：非法输入与只读状态在本地就被挡住，绝不发一个注定被拒绝的请求。 */
    void save() {
        if (model == null || saving || submitting) return;
        String blocked = model.saveBlockReason();
        if (blocked != null) {
            feedbackText = blocked;
            render();
            return;
        }
        if (pendingSaveOperationId == null) pendingSaveOperationId = UUID.randomUUID().toString();
        WriteGradeBookRequestDTO request;
        try {
            request = model.writeRequest(pendingSaveOperationId);
        } catch (IllegalStateException refused) {
            feedbackText = refused.getMessage();
            render();
            return;
        }
        saving = true;
        feedbackText = SAVING_TEXT;
        errorText = null;
        render();
        long current = ++generation;
        service.saveGradeDraft(request).whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            saving = false;
            if (failure != null) {
                // 失败保留编辑内容：模型一个字段都不动，只把原因显示出来。
                feedbackText = conflictPrefix(failure, SAVE_CONFLICT_PREFIX)
                        + failureText(failure, SAVE_FAILURE_TEXT);
                render();
                return;
            }
            applySnapshot(result);
            pendingSaveOperationId = null;
            feedbackText = SAVE_SUCCESS_TEXT;
            errorText = null;
            render();
        }));
    }

    /** 第一步：只是进入确认态，不发送任何请求；重复点击不会叠加确认次数。 */
    @FXML
    void handleSubmit(Event event) {
        requestSubmit();
    }

    void requestSubmit() {
        if (model == null || submitting || confirmingSubmit) return;
        confirmingSubmit = true;
        feedbackText = null;
        render();
    }

    @FXML
    void handleCancelSubmit(Event event) {
        cancelSubmit();
    }

    void cancelSubmit() {
        if (!confirmingSubmit) return;
        confirmingSubmit = false;
        // 用户明确取消：作废这次确认的幂等 ID，下次确认是一次新的提交意图。
        pendingSubmitOperationId = null;
        render();
    }

    /** 第二步：发送提交。在途期间的重复点击被忽略，同一流程共用同一个 operationId。 */
    @FXML
    void handleConfirmSubmit(Event event) {
        confirmSubmit();
    }

    void confirmSubmit() {
        if (model == null || submitting) return;
        String blocked = model.submitBlockReason();
        if (blocked != null) {
            confirmingSubmit = false;
            feedbackText = blocked;
            render();
            return;
        }
        confirmingSubmit = false;
        if (pendingSubmitOperationId == null) pendingSubmitOperationId = UUID.randomUUID().toString();
        WriteGradeBookRequestDTO request;
        try {
            request = model.writeRequest(pendingSubmitOperationId);
        } catch (IllegalStateException refused) {
            feedbackText = refused.getMessage();
            render();
            return;
        }
        submitting = true;
        feedbackText = SUBMITTING_TEXT;
        errorText = null;
        render();
        long current = ++generation;
        service.submitGradeBook(request).whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            submitting = false;
            if (failure != null) {
                feedbackText = conflictPrefix(failure, SUBMIT_CONFLICT_PREFIX)
                        + failureText(failure, SUBMIT_FAILURE_TEXT);
                render();
                return;
            }
            applySnapshot(result);
            pendingSubmitOperationId = null;
            feedbackText = SUBMIT_SUCCESS_TEXT;
            errorText = null;
            render();
        }));
    }

    /** 成功路径：用服务端快照替换编辑内容（dirty 随之清零）。失败路径绝不调用它。 */
    private void applySnapshot(TeacherOperationResultDTO<TeacherGradeBookDTO> result) {
        if (result != null && result.getValue() != null) {
            model.applyServerSnapshot(result.getValue());
        }
    }

    // ------------------------------------------------------------------ 编辑

    private void applyScore(Row row, GradeComponentCodeDTO code, String text) {
        if (model == null || !model.canEdit() || row == null) {
            refreshTable();
            return;
        }
        model.setScore(row.enrollmentId(), code, text);
        refreshTable();
    }

    /**
     * 开关/权重的监听器：{@code syncingScheme} 期间的回写（{@link #syncSchemeControls()} 把模型状态
     * 写进控件时触发的 change）必须被忽略，否则渲染会被当成用户输入而把页面标成 dirty；这类回调
     * 也不重新渲染——发起回写的那次渲染还在进行，模型已经是最新状态。
     * 只读页面上的手势则相反：什么都不改，但要渲染一次把控件拨回模型状态。
     */
    private void applyEnabled(GradeComponentCodeDTO code, boolean enabled) {
        if (syncingScheme) return;
        if (model == null || !model.canEdit()) {
            render();
            return;
        }
        model.setEnabled(code, enabled);
        render();
    }

    private void applyWeight(GradeComponentCodeDTO code, String text) {
        if (syncingScheme) return;
        if (model == null || !model.canEdit()) {
            render();
            return;
        }
        model.setWeightText(code, text);
        render();
    }

    private void wireScheme(GradeComponentCodeDTO code, CheckBox toggle, TextField weightField) {
        if (toggle != null) {
            toggle.selectedProperty().addListener(
                    (observable, previous, next) -> applyEnabled(code, Boolean.TRUE.equals(next)));
        }
        if (weightField != null) {
            weightField.textProperty().addListener(
                    (observable, previous, next) -> applyWeight(code, next));
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        boolean hasModel = model != null;
        setActive(gradeBookLoadingLabel, loading);
        setActive(gradeBookEmptyLabel, hasModel && !loading && model.rows().isEmpty());
        if (gradeBookErrorLabel != null) {
            gradeBookErrorLabel.setText(errorText == null ? "" : errorText);
        }
        setActive(gradeBookErrorLabel, errorText != null);
        if (gradeBookTitleLabel != null) {
            gradeBookTitleLabel.setText(hasModel
                    ? "成绩录入　教学班 " + offeringId : "成绩录入");
        }
        if (gradeBookStateLabel != null) {
            gradeBookStateLabel.setText(hasModel
                    ? "状态：" + GradeBookEditorModel.stateLabel(model.state())
                            + "　版本：v" + model.revision() : "");
        }
        if (gradeBookNoticeLabel != null) {
            gradeBookNoticeLabel.setText(hasModel && model.readOnlyNotice() != null
                    ? model.readOnlyNotice() : "");
        }
        setActive(gradeBookNoticeLabel, hasModel && model.readOnlyNotice() != null);
        if (gradeBookSchemeLabel != null) gradeBookSchemeLabel.setText(schemeText());
        if (gradeBookFeedbackLabel != null) {
            gradeBookFeedbackLabel.setText(feedbackText == null ? "" : feedbackText);
        }
        setActive(gradeBookFeedbackLabel, feedbackText != null);

        boolean editable = hasModel && model.canEdit();
        if (gradeBookReloadButton != null) {
            // 写请求在途时不许重新加载：否则会用旧快照覆盖刚提交的结果，写入标志也会被复位。
            gradeBookReloadButton.setDisable(!hasModel || loading || saving || submitting);
        }
        if (gradeBookSaveButton != null) {
            gradeBookSaveButton.setDisable(!editable || saving || submitting);
        }
        if (gradeBookSubmitButton != null) {
            gradeBookSubmitButton.setDisable(!editable || saving || submitting || confirmingSubmit);
        }
        setActive(gradeBookConfirmSubmitButton, confirmingSubmit);
        setActive(gradeBookCancelSubmitButton, confirmingSubmit);
        if (gradeBookConfirmSubmitButton != null) {
            gradeBookConfirmSubmitButton.setDisable(submitting);
        }
        syncSchemeControls();
        renderTable();
    }

    /** 方案区：开关与权重输入回写模型状态；回写期间禁止监听器把渲染当成用户输入。 */
    private void syncSchemeControls() {
        syncingScheme = true;
        try {
            if (model != null) {
                for (Column column : model.columns()) {
                    CheckBox toggle = toggleOf(column.code());
                    TextField weight = weightFieldOf(column.code());
                    boolean enabled = column.enabled();
                    if (toggle != null) {
                        toggle.setSelected(enabled);
                        // 只读状态（已提交/已通过）下开关不能再动；禁用项本身仍然可以重新启用。
                        toggle.setDisable(!model.canEdit());
                    }
                    if (weight != null) {
                        if (!column.weightText().equals(weight.getText())) {
                            weight.setText(column.weightText());
                        }
                        weight.setDisable(!enabled || !model.canEdit());
                    }
                }
            }
        } finally {
            syncingScheme = false;
        }
    }

    private void renderTable() {
        if (gradeBookTable == null) return;
        List<Row> items = model == null ? List.of() : model.rows();
        gradeBookTable.getItems().setAll(items);
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            TableColumn<Row, String> column = scoreColumnOf(code);
            if (column != null) {
                column.setEditable(model != null && model.canEdit() && model.column(code).enabled());
            }
        }
        gradeBookTable.refresh();
    }

    private void refreshTable() {
        render();
    }

    /** 权重区提示：已配齐时给出合计，未配齐时说明还差多少（不阻塞草稿保存）。 */
    private String schemeText() {
        if (model == null) return "";
        long total = 0;
        StringBuilder enabledText = new StringBuilder();
        for (Column column : model.columns()) {
            if (!column.enabled()) continue;
            total += column.weightBasisPoints();
            if (enabledText.length() > 0) enabledText.append("/");
            enabledText.append(column.code());
        }
        String enabled = enabledText.length() == 0 ? "无" : enabledText.toString();
        String state = total == GradeBookEditorModel.TOTAL_WEIGHT_BASIS_POINTS
                && enabledText.length() > 0 ? "已配齐" : "未配齐";
        return "启用组成：" + enabled + "　权重合计：" + total + "/"
                + GradeBookEditorModel.TOTAL_WEIGHT_BASIS_POINTS + "（万分比，" + state + "）";
    }

    private void bindTextColumn(TableColumn<Row, String> column, Function<Row, String> text) {
        if (column == null) return;
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue() == null ? "" : orPlaceholder(text.apply(cell.getValue()))));
    }

    private void bindScoreColumn(TableColumn<Row, String> column, GradeComponentCodeDTO code) {
        if (column == null) return;
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue() == null ? "" : cell.getValue().cell(code).text()));
        column.setCellFactory(ignored -> scoreCell(code));
        column.setOnEditCommit(event -> applyScore(event.getRowValue(), code, event.getNewValue()));
    }

    /**
     * 一格的编辑控件：只保留用户输入的原文本，解析留给模型。禁用列与非法格分别用样式类标灰/标红，
     * 让“不能输入”和“输入有误”在界面上是两件事。
     */
    private TextFieldTableCell<Row, String> scoreCell(GradeComponentCodeDTO code) {
        return new TextFieldTableCell<>(new StringConverter<String>() {
            @Override
            public String toString(String value) {
                return value == null ? "" : value;
            }

            @Override
            public String fromString(String text) {
                return text;
            }
        }) {
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().remove(DISABLED_CELL_CLASS);
                getStyleClass().remove(ERROR_CELL_CLASS);
                Row row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null || model == null) return;
                if (!model.column(code).enabled()) {
                    getStyleClass().add(DISABLED_CELL_CLASS);
                } else if (row.cell(code).error() != null) {
                    getStyleClass().add(ERROR_CELL_CLASS);
                }
            }
        };
    }

    private String totalText(Row row) {
        BigDecimal total = model == null ? null : model.rowTotal(row);
        return total == null ? PLACEHOLDER : total.toPlainString();
    }

    private String pointText(Row row) {
        BigDecimal point = model == null ? null : model.rowGradePoint(row);
        return point == null ? PLACEHOLDER : point.toPlainString();
    }

    private CheckBox toggleOf(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> gradeBookDailyEnabled;
            case MIDTERM -> gradeBookMidtermEnabled;
            case EXPERIMENT -> gradeBookExperimentEnabled;
            case FINALTERM -> gradeBookFinaltermEnabled;
        };
    }

    private TextField weightFieldOf(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> gradeBookDailyWeight;
            case MIDTERM -> gradeBookMidtermWeight;
            case EXPERIMENT -> gradeBookExperimentWeight;
            case FINALTERM -> gradeBookFinaltermWeight;
        };
    }

    private TableColumn<Row, String> scoreColumnOf(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> gradeBookDailyColumn;
            case MIDTERM -> gradeBookMidtermColumn;
            case EXPERIMENT -> gradeBookExperimentColumn;
            case FINALTERM -> gradeBookFinaltermColumn;
        };
    }

    private boolean isCurrent(long current) {
        return active && current == generation;
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    private static String orPlaceholder(String value) {
        return value == null || value.isEmpty() ? PLACEHOLDER : value;
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

    /**
     * 冲突要额外说明“已保留你的修改”，并带上服务端当前版本（冲突载荷里的最新成绩表），
     * 避免用户以为失败等于回滚，也让他知道重新加载后会拿到哪一版。
     */
    private static String conflictPrefix(Throwable failure, String prefix) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException failureInfo
                && failureInfo.getCode() == MessageCode.CONFLICT) {
            TeacherGradeBookDTO latest = failureInfo.getLatestGradeBook();
            return latest == null ? prefix : prefix + "（服务端版本 v" + latest.getRevision() + "）";
        }
        return "";
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // -------------------------------------------------------------- 测试访问器

    GradeBookEditorModel model() {
        return model;
    }

    String offeringId() {
        return offeringId;
    }

    boolean active() {
        return active;
    }

    boolean loading() {
        return loading;
    }

    boolean saving() {
        return saving;
    }

    boolean submitting() {
        return submitting;
    }

    boolean confirmingSubmit() {
        return confirmingSubmit;
    }

    boolean dirty() {
        return model != null && model.dirty();
    }

    String feedbackText() {
        return feedbackText;
    }

    String errorText() {
        return errorText;
    }

    String pendingSubmitOperationId() {
        return pendingSubmitOperationId;
    }

    List<Row> rows() {
        return model == null ? List.of() : model.rows();
    }

    /** 表格当前绑定的行数；节点缺失时与模型行数一致，便于断言渲染结果。 */
    int tableRowCount() {
        return gradeBookTable == null ? rows().size() : gradeBookTable.getItems().size();
    }
}
