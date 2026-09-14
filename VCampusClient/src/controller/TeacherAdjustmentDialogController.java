package controller;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师调课表单（设计 §5.3）：从一个具体课次打开，选择新日期、开始/结束节次、教室并填写原因。
 *
 * <p>写请求只包含服务端允许的字段：教师不修改任课教师与助教，界面没有这两个输入，也不替用户
 * 生成 {@code newTeacher}/{@code newAssistant}。教室选项来自 {@code getAdjustmentOptions}——那是
 * 教师受限查询，绝不调用需要管理员身份的资源接口；没有选择时以 null 表示沿用原教室。
 *
 * <p>预检查语义：只有目标完整（选了日期、开始/结束节次且开始不大于结束）才触发——残缺表单会被
 * 服务端以 400 拒绝，因此客户端不发送半成品。日期/节次/教室任何变化都立即使上一次结果失效
 * （generation 递增并清空预览），迟到的旧响应直接被丢弃：旧的“无冲突”永远不能再启用提交。
 * 原因不属于被预检查的目标内容，编辑它只重新判定“原因非空”这一个提交前提，不会为每个按键都
 * 发一次预检查；提交时服务端仍会重新检查全部内容。
 *
 * <p>提交语义：一份表单内容对应且只对应一个 operationId——日期/节次/教室/原因任一变化都会换新 id，
 * 只有失败后原样重试同一份内容才复用它（幂等重放）；提交在途时按钮禁用且重复触发直接返回，
 * 因此双击只发送一次；取消/关闭不发送任何写请求。成功后关闭弹窗
 * 并把新申请交给 {@link #setOnSubmitted} 的接收方，由调用方决定如何提示（不弹模态框，冒烟测试
 * 才能在无人值守下走完整流程）。
 *
 * <p>所有节点都可能为 {@code null}：控制器测试按仓库约定在无工具包、无 FXML 节点的环境下运行，
 * 状态机与纯文本函数因此与控件渲染分开。
 */
public final class TeacherAdjustmentDialogController {
    /** 弹窗 Stage 的标题；GUI 冒烟测试靠它在窗口列表里认出弹窗，因此不做成动态标题。 */
    static final String TITLE = "申请调课";

    static final String LOADING_OPTIONS_TEXT = "正在加载可选日期与教室...";
    static final String OPTIONS_FAILURE_TEXT = "调课选项加载失败，请重试";
    static final String PREVIEW_IDLE_TEXT = "选择完整的新日期与节次后自动预检查";
    static final String PREVIEWING_TEXT = "正在预检查...";
    static final String PREVIEW_CLEAR_TEXT = "预检查通过：没有冲突，可以提交";
    static final String PREVIEW_CONFLICT_PREFIX = "预检查发现冲突，不能提交：";
    static final String REASON_REQUIRED_TEXT = "请填写调课原因";
    static final String SUBMIT_FAILURE_TEXT = "提交失败，请重试";
    static final String PREVIEW_FAILURE_TEXT = "预检查失败，请重试";
    static final String KEEP_ORIGINAL_LABEL = "沿用原教室";
    /** 服务端只接受 ≤500 字符的原因，超长输入在界面层就截断。 */
    static final int MAX_REASON_LENGTH = 500;

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;
    private final Supplier<LocalDate> today;
    private Consumer<AdjustmentRequestDetailDTO> onSubmitted = value -> { };

    private TeacherScheduleEntryDTO entry;
    private TeacherAdjustmentOptionsDTO options;
    private boolean loadingOptions;
    private String optionsErrorText;

    private String selectedDate;
    private int startPeriod;
    private int endPeriod;
    private String classroomId;
    private String reason = "";

    private TeacherAdjustmentPreviewDTO preview;
    private boolean previewing;
    private boolean submitting;
    private String errorText;
    private String operationId;
    private boolean closed;
    /** 表单版本：任何影响预检查的字段变化都会递增，用于丢弃迟到的旧响应。 */
    private long formVersion;
    /** 渲染时同步控件值会触发值监听器；同步期间跳过它们，避免把程序化回填当成用户选择。 */
    private boolean syncing;

    @FXML private Node dialogRoot;
    @FXML private Label dialogTitleLabel;
    @FXML private Label originalLine;
    @FXML private Label targetWeekLine;
    @FXML private ComboBox<String> dateCombo;
    @FXML private ComboBox<Integer> startPeriodCombo;
    @FXML private ComboBox<Integer> endPeriodCombo;
    @FXML private ComboBox<String> classroomCombo;
    @FXML private TextArea reasonArea;
    @FXML private Label previewStatusLabel;
    @FXML private VBox previewConflictRows;
    @FXML private Label errorLabel;
    @FXML private Button retryOptionsButton;
    @FXML private Button submitButton;
    @FXML private Button cancelButton;

    public TeacherAdjustmentDialogController() {
        this(TeacherCourseServices.current(), Platform::runLater, LocalDate::now);
    }

    TeacherAdjustmentDialogController(TeacherCourseService service, Consumer<Runnable> fxExecutor,
            Supplier<LocalDate> today) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.today = Objects.requireNonNull(today, "Today supplier is required");
    }

    @FXML
    public void initialize() {
        if (dateCombo != null) {
            dateCombo.setCellFactory(list -> new PastDateCell());
            dateCombo.setButtonCell(new PastDateCell());
            dateCombo.valueProperty().addListener((observable, previous, next) -> {
                if (syncing) return;
                DateChoice choice = dateChoice(next);
                if (choice != null) selectDate(choice.date());
            });
        }
        if (startPeriodCombo != null) {
            startPeriodCombo.valueProperty().addListener((observable, previous, next) -> {
                if (syncing || next == null) return;
                selectPeriods(next, Math.max(next, endPeriod));
            });
        }
        if (endPeriodCombo != null) {
            endPeriodCombo.valueProperty().addListener((observable, previous, next) -> {
                if (syncing || next == null) return;
                selectPeriods(startPeriod, next);
            });
        }
        if (classroomCombo != null) {
            classroomCombo.valueProperty().addListener((observable, previous, next) -> {
                if (syncing) return;
                ClassroomChoice choice = classroomChoice(next);
                if (choice != null) selectClassroom(choice.classroomId());
            });
        }
        if (reasonArea != null) {
            reasonArea.textProperty().addListener((observable, previous, next) -> {
                if (syncing) return;
                setReason(next);
            });
        }
        render();
    }

    /** 由打开方注入：目标课次（含原周、原日期、原节次与地点）。 */
    void prepare(TeacherScheduleEntryDTO entry) {
        this.entry = entry;
        this.options = null;
        this.loadingOptions = false;
        this.optionsErrorText = null;
        this.selectedDate = null;
        this.startPeriod = 0;
        this.endPeriod = 0;
        this.classroomId = null;
        this.reason = "";
        this.preview = null;
        this.previewing = false;
        this.submitting = false;
        this.errorText = null;
        this.operationId = null;
        this.closed = false;
        this.formVersion++;
        render();
        loadOptions();
    }

    /** 提交成功后收到新申请的一方（课次详情弹窗用它显示内联提示）。 */
    void setOnSubmitted(Consumer<AdjustmentRequestDetailDTO> onSubmitted) {
        this.onSubmitted = onSubmitted == null ? value -> { } : onSubmitted;
    }

    void loadOptions() {
        if (closed || entry == null || entry.getOfferingId() == null
                || entry.getOccurrenceId() == null) {
            return;
        }
        long current = ++formVersion;
        loadingOptions = true;
        optionsErrorText = null;
        render();
        service.getAdjustmentOptions(entry.getOfferingId(), entry.getOccurrenceId())
                .whenComplete((value, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrent(current)) return;
                    loadingOptions = false;
                    if (failure != null) {
                        options = null;
                        optionsErrorText = failureText(failure, OPTIONS_FAILURE_TEXT);
                        render();
                        return;
                    }
                    options = value;
                    optionsErrorText = null;
                    render();
                }));
    }

    void retryOptions() {
        loadOptions();
    }

    @FXML
    void retryOptions(Event event) {
        retryOptions();
    }

    // ------------------------------------------------------------ 表单字段

    /**
     * 选择目标日期：新日期使旧预览立即失效；节次若不在该日期的节次表里则清空（不同教学日模板
     * 可以不同），然后按完整性决定是否触发预检查。
     */
    void selectDate(String isoDate) {
        selectedDate = blankToNull(isoDate);
        if (!availablePeriods().contains(startPeriod)) startPeriod = 0;
        if (!availablePeriods().contains(endPeriod)) endPeriod = 0;
        invalidatePreview();
        render();
        maybePreview();
    }

    void selectPeriods(int start, int end) {
        if (startPeriod != start || endPeriod != end) {
            startPeriod = start;
            endPeriod = end;
            invalidatePreview();
            render();
        }
        maybePreview();
    }

    /** 教室变化同样使旧预览失效；null 表示沿用原教室（服务端保持原教室）。 */
    void selectClassroom(String newClassroomId) {
        String next = blankToNull(newClassroomId);
        if (!Objects.equals(classroomId, next)) {
            classroomId = next;
            invalidatePreview();
            render();
        }
        maybePreview();
    }

    /**
     * 原因不属于预检查内容，编辑它只重新判定“原因非空”这一提交前提，因此不使预览失效，
     * 也不会为每个按键都发一次请求；提交时服务端仍会校验原因。但原因是请求摘要的一部分，
     * 变化时必须换一个新的 operationId（见 {@link #invalidatePreview()} 的说明）。
     */
    void setReason(String value) {
        String next = value == null ? "" : value;
        if (next.length() > MAX_REASON_LENGTH) next = next.substring(0, MAX_REASON_LENGTH);
        if (!reason.equals(next)) {
            reason = next;
            operationId = null;
            render();
        }
    }

    // ------------------------------------------------------------ 预检查

    /**
     * 字段变化后旧结果立即失效：清空预览、丢弃 operationId 并递增值，让在途的旧响应再也写不进来。
     *
     * <p>operationId 标识且仅标识一个请求内容（服务端的幂等摘要含目标、节次、教室与原因），因此
     * 任何影响内容的编辑都必须换一个新 id；否则“提交已落库但响应丢失 → 编辑字段 → 重提”会用旧
     * id 提交新内容，被服务端以摘要冲突（operationId 已用于不同的业务请求）永久拒绝。只有原样重试
     * 同一份内容才复用同一个 id（失败后不改字段直接重提）。
     */
    private void invalidatePreview() {
        formVersion++;
        preview = null;
        previewing = false;
        errorText = null;
        operationId = null;
    }

    private void maybePreview() {
        if (!formComplete() || loadingOptions || options == null) return;
        long current = formVersion;
        previewing = true;
        render();
        service.previewAdjustment(write(null)).whenComplete((value, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrent(current)) return;
                    previewing = false;
                    if (failure != null) {
                        preview = null;
                        errorText = failureText(failure, PREVIEW_FAILURE_TEXT);
                        render();
                        return;
                    }
                    preview = value;
                    errorText = null;
                    render();
                }));
    }

    boolean formComplete() {
        return selectedDate != null && startPeriod >= 1 && endPeriod >= startPeriod;
    }

    /** 只有“最新一次预检查通过 + 原因非空 + 不在提交中”才允许提交。 */
    boolean canSubmit() {
        return !closed && !submitting && formComplete() && preview != null
                && preview.isCanSubmit() && !reason.isBlank();
    }

    // ------------------------------------------------------------ 提交

    void submit() {
        if (!canSubmit()) return;
        submitting = true;
        errorText = null;
        if (operationId == null) operationId = UUID.randomUUID().toString();
        render();
        service.submitAdjustment(write(operationId)).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (closed) return;
                    submitting = false;
                    if (failure != null) {
                        errorText = failureText(failure, SUBMIT_FAILURE_TEXT);
                        render();
                        return;
                    }
                    onSubmitted.accept(result == null ? null : result.getValue());
                    dispose();
                }));
    }

    @FXML
    void handleSubmit(Event event) {
        submit();
    }

    @FXML
    void handleCancel(Event event) {
        dispose();
    }

    /** 关闭表单：置 closed，此后任何在途响应都不再写控件，也不会有新的写请求。 */
    void dispose() {
        if (closed) return;
        closed = true;
        formVersion++;
        hideWindow();
    }

    private boolean isCurrent(long current) {
        return !closed && current == formVersion;
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    /** 提交/预检查请求体：目标永远是单个原课次（GUI 首版一次申请一个课次），后台保持列表形态。 */
    TeacherAdjustmentWriteDTO write(String operationId) {
        List<TeacherAdjustmentTargetInputDTO> targets = entry == null
                ? List.of()
                : List.of(new TeacherAdjustmentTargetInputDTO(entry.getOccurrenceId(),
                        selectedDate));
        return new TeacherAdjustmentWriteDTO(operationId,
                entry == null ? null : entry.getOfferingId(), targets, startPeriod, endPeriod,
                classroomId, reason.isBlank() ? null : reason.trim());
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        if (dialogTitleLabel != null) dialogTitleLabel.setText(TITLE);
        if (originalLine != null) originalLine.setText(originalText());
        if (targetWeekLine != null) targetWeekLine.setText(targetWeekText());
        renderOptions();
        if (reasonArea != null && !reasonArea.getText().equals(reason)) {
            reasonArea.setText(reason);
        }
        if (previewStatusLabel != null) previewStatusLabel.setText(previewText());
        renderPreviewRows();
        setActive(previewStatusLabel, optionsErrorText == null);
        if (errorLabel != null) {
            errorLabel.setText(errorText == null ? "" : errorText);
        }
        setActive(errorLabel, errorText != null);
        setActive(retryOptionsButton, optionsErrorText != null);
        if (submitButton != null) {
            submitButton.setDisable(!canSubmit());
        }
        setActive(submitButton, optionsErrorText == null);
    }

    /**
     * 三个下拉的选项同时重建：日期（过去日期灰显）、节次与教室都来自选项域。
     *
     * <p>回填控件值会触发值监听器，因此整段同步都在 {@code syncing} 下进行——程序化同步不是
     * 用户选择，不能反过来再次触发预检查或失效逻辑。
     */
    private void renderOptions() {
        syncing = true;
        try {
            if (dateCombo != null) {
                List<String> labels = dateChoices().stream()
                        .map(TeacherAdjustmentDialogController.DateChoice::label).toList();
                if (!dateCombo.getItems().equals(labels)) {
                    dateCombo.getItems().setAll(labels);
                }
                String label = labelForDate(selectedDate);
                if (!Objects.equals(dateCombo.getValue(), label)) {
                    dateCombo.setValue(label);
                }
            }
            List<Integer> periods = availablePeriods();
            if (startPeriodCombo != null) {
                if (!startPeriodCombo.getItems().equals(periods)) {
                    startPeriodCombo.getItems().setAll(periods);
                }
                Integer start = periods.contains(startPeriod) ? startPeriod : null;
                if (!Objects.equals(startPeriodCombo.getValue(), start)) {
                    startPeriodCombo.setValue(start);
                }
            }
            if (endPeriodCombo != null) {
                List<Integer> ends = startPeriod >= 1
                        ? periods.stream().filter(period -> period >= startPeriod).toList()
                        : periods;
                if (!endPeriodCombo.getItems().equals(ends)) {
                    endPeriodCombo.getItems().setAll(ends);
                }
                Integer end = ends.contains(endPeriod) ? endPeriod : null;
                if (!Objects.equals(endPeriodCombo.getValue(), end)) {
                    endPeriodCombo.setValue(end);
                }
            }
            if (classroomCombo != null) {
                List<String> labels = classroomChoices().stream()
                        .map(TeacherAdjustmentDialogController.ClassroomChoice::label).toList();
                if (!classroomCombo.getItems().equals(labels)) {
                    classroomCombo.getItems().setAll(labels);
                }
                if (classroomCombo.getValue() == null && !labels.isEmpty()) {
                    classroomCombo.setValue(labels.get(0));
                }
            }
        } finally {
            syncing = false;
        }
    }

    private void renderPreviewRows() {
        if (previewConflictRows == null) return;
        previewConflictRows.getChildren().clear();
        for (String line : previewLines()) {
            Label label = new Label(line);
            label.getStyleClass().add("teacher-schedule-dialog-adjustment-line");
            label.setWrapText(true);
            previewConflictRows.getChildren().add(label);
        }
    }

    /** 过去日期在下拉里保持可见但被禁用（灰显），客户端绝不替服务端“过滤”日期。 */
    private final class PastDateCell extends ListCell<String> {
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item);
            DateChoice choice = empty ? null : dateChoice(item);
            boolean past = choice != null && choice.past();
            setDisable(past);
            getStyleClass().remove("teacher-adjustment-past-date");
            if (past) getStyleClass().add("teacher-adjustment-past-date");
        }
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------ 纯文本

    /** 原安排：周次、星期、日期、节次与地点全部来自课次 DTO，不重新推导。 */
    String originalText() {
        if (entry == null) return "原安排：—";
        return "原安排：第 " + entry.getWeek() + " 周 "
                + TeacherScheduleController.weekdayText(entry.getDayOfWeek()) + " "
                + orDash(entry.getLocalDate()) + " 第 " + entry.getStartPeriod() + "-"
                + entry.getEndPeriod() + " 节　" + orDash(entry.getLocation());
    }

    /** 目标周行：明确写出原周与目标周，并标出同周/跨周。 */
    String targetWeekText() {
        String original = entry == null ? "—" : "第 " + entry.getWeek() + " 周";
        DateChoice choice = choiceForDate(selectedDate);
        if (choice == null) {
            return "目标周：未选择（原周：" + original + "）";
        }
        String relation = entry != null && entry.getWeek() == choice.week() ? "同周" : "跨周";
        return "目标周：" + choice.label() + "（原周：" + original + "，" + relation + "）";
    }

    String previewText() {
        if (optionsErrorText != null) return OPTIONS_FAILURE_TEXT;
        if (loadingOptions || options == null) return LOADING_OPTIONS_TEXT;
        if (previewing) return PREVIEWING_TEXT;
        if (preview == null) return PREVIEW_IDLE_TEXT;
        if (!preview.isCanSubmit()) {
            return PREVIEW_CONFLICT_PREFIX + previewLines().size() + " 条";
        }
        return reason.isBlank() ? PREVIEW_CLEAR_TEXT + "；" + REASON_REQUIRED_TEXT
                : PREVIEW_CLEAR_TEXT;
    }

    /** 冲突行以服务端 message 为主要信息（类型字符串会被不同类型复用），类型与级别只做补充。 */
    static String conflictLine(ScheduleConflictDTO conflict) {
        return "第 " + conflict.getWeek() + " 周 第 " + conflict.getStartPeriod() + "-"
                + conflict.getEndPeriod() + " 节：" + orDash(conflict.getMessage()) + "　["
                + conflict.getType() + "/" + conflict.getSeverity() + "]";
    }

    static boolean isPastDate(String isoDate, LocalDate today) {
        if (isoDate == null || today == null) return false;
        try {
            return LocalDate.parse(isoDate).isBefore(today);
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 加载/预检查/提交失败的提示：业务拒绝原样显示服务端的话，其余只给可重试的通用文案。 */
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

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    // ------------------------------------------------------------ 选项域

    /** 日期选项：标签自带周次与星期；过去日期保留但会被灰显，提交由服务端拒绝。 */
    List<DateChoice> dateChoices() {
        List<DateChoice> choices = new ArrayList<>();
        if (options == null) return List.copyOf(choices);
        for (TeacherCalendarDateDTO date : options.getDates()) {
            if (date == null || date.getDate() == null) continue;
            choices.add(new DateChoice(dateLabel(date), date.getDate(), date.getWeek(),
                    date.getTeachingWeekday(), isPastDate(date.getDate(), today.get())));
        }
        return List.copyOf(choices);
    }

    private static String dateLabel(TeacherCalendarDateDTO date) {
        return "第 " + date.getWeek() + " 周 "
                + TeacherScheduleController.weekdayText(date.getTeachingWeekday()) + " "
                + date.getDate();
    }

    DateChoice dateChoice(String label) {
        if (label == null) return null;
        for (DateChoice choice : dateChoices()) {
            if (choice.label().equals(label)) return choice;
        }
        return null;
    }

    DateChoice choiceForDate(String isoDate) {
        if (isoDate == null) return null;
        for (DateChoice choice : dateChoices()) {
            if (choice.date().equals(isoDate)) return choice;
        }
        return null;
    }

    String labelForDate(String isoDate) {
        DateChoice choice = choiceForDate(isoDate);
        return choice == null ? null : choice.label();
    }

    /** 选中日期可用的节次编号（升序、去重）；没有日期时为空——节次与日期必须同域。 */
    List<Integer> availablePeriods() {
        List<Integer> periods = new ArrayList<>();
        if (options == null || selectedDate == null) return List.copyOf(periods);
        for (TeacherPeriodDTO period : options.getPeriods()) {
            if (period != null && selectedDate.equals(period.getDate())
                    && !periods.contains(period.getPeriod())) {
                periods.add(period.getPeriod());
            }
        }
        periods.sort(Integer::compareTo);
        return List.copyOf(periods);
    }

    /** 教室选项：第一项是“沿用原教室”（id 为 null），其余全部来自教师受限查询。 */
    List<ClassroomChoice> classroomChoices() {
        List<ClassroomChoice> choices = new ArrayList<>();
        String original = entry == null ? null : entry.getLocation();
        choices.add(new ClassroomChoice(
                KEEP_ORIGINAL_LABEL + (original == null || original.isBlank()
                        ? "" : "（" + original + "）"),
                null));
        Map<String, ClassroomChoice> byLabel = new LinkedHashMap<>();
        if (options != null) {
            for (ScheduleResourceDTO classroom : options.getClassrooms()) {
                if (classroom == null || classroom.getResourceId() == null) continue;
                byLabel.put(classroom.getName(), new ClassroomChoice(
                        orDash(classroom.getName()), classroom.getResourceId()));
            }
        }
        choices.addAll(byLabel.values());
        return List.copyOf(choices);
    }

    private ClassroomChoice classroomChoice(String label) {
        if (label == null) return null;
        for (ClassroomChoice choice : classroomChoices()) {
            if (choice.label().equals(label)) return choice;
        }
        return null;
    }

    /** 一个可选目标日期；{@code past} 表示已过去，下拉里灰显不可选。 */
    record DateChoice(String label, String date, int week, int weekday, boolean past) {
    }

    /** 一个教室选项；{@code classroomId} 为 null 表示沿用原教室。 */
    record ClassroomChoice(String label, String classroomId) {
    }

    // -------------------------------------------------------------- 测试访问器

    TeacherAdjustmentOptionsDTO options() {
        return options;
    }

    String optionsErrorText() {
        return optionsErrorText;
    }

    String selectedDate() {
        return selectedDate;
    }

    int startPeriod() {
        return startPeriod;
    }

    int endPeriod() {
        return endPeriod;
    }

    String classroomId() {
        return classroomId;
    }

    TeacherAdjustmentPreviewDTO preview() {
        return preview;
    }

    boolean previewing() {
        return previewing;
    }

    boolean submitting() {
        return submitting;
    }

    boolean closed() {
        return closed;
    }

    String errorText() {
        return errorText;
    }

    List<String> previewLines() {
        if (preview == null) return List.of();
        List<String> lines = new ArrayList<>();
        for (ScheduleConflictDTO conflict : preview.getConflicts()) {
            lines.add(conflictLine(conflict));
        }
        return List.copyOf(lines);
    }
}
