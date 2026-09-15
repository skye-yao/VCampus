package controller;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyEvent;
import model.course.teacher.GradeBookEditorModel;
import model.course.teacher.GradeBookEditorModel.Column;
import model.course.teacher.GradeBookEditorModel.Row;
import model.course.teacher.GradeClipboardParser;
import model.course.teacher.GradeBookNavigator;
import model.course.teacher.GradeBookNavigator.Move;
import model.course.teacher.GradeBookNavigator.Position;
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
 * <p>录入交互是“Excel 式”的（见 {@link ScoreEditCell}）：单击选中即编辑、输入即写入模型、
 * 方向键即导航、离开单元格即完成、只有非法输入才在本格标红打断。所有导航与剪贴板的判定都放在
 * 无工具包的 {@link GradeBookNavigator}/{@link GradeClipboardParser} 里，本类只负责接线与渲染。
 *
 * <p>写库仍然是批量的（保存草稿 / 提交成绩两个按钮）：实时写入只落在内存模型上，
 * 因此既没有“按回车才算数”的二次确认，也不会为每敲一个键发一次网络请求。以后要加防抖自动保存，
 * 只需在 {@link #liveScoreEdit} 之后挂一个定时器即可，模型与幂等 ID 的设计都不用动。
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

    /** 导航到一个还没渲染出来的行时，等布局把它带进视口的重试次数上限。 */
    private static final int PENDING_EDIT_RETRIES = 5;
    /** 权重输入框的非法样式：与单元格标红同一套视觉，用户一眼能找到是哪一列。 */
    private static final String ERROR_FIELD_CLASS = "teacher-course-weight-field-error";

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
    /** 当前打开着编辑器的单元格；键盘事件据此决定“交给编辑器”还是“由表格处理”。 */
    private ScoreEditCell activeCell;
    /** 尚未落到某个已渲染单元格上的编辑请求（目标行还在视口之外时排队，滚动到位后由单元格自己接手）。 */
    private int pendingEditRow = -1;
    private TableColumn<Row, String> pendingEditColumn;
    private String pendingEditSeed;
    private int pendingEditRetries;
    /** 总评/绩点两列的可观察值，按行对象身份缓存（逐格录入时单元格订阅它自动更新）。 */
    private final Map<Row, RowDisplay> displays = new IdentityHashMap<>();

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
    @FXML private Node gradeBookSchemeBar;
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
            bindTableKeyboard();
        }
        bindSchemeBarToTableScroll();
        bindTextColumn(gradeBookUidColumn, row -> row.studentUid());
        bindTextColumn(gradeBookNameColumn, row -> row.studentName());
        bindScoreColumn(gradeBookDailyColumn, GradeComponentCodeDTO.DAILY);
        bindScoreColumn(gradeBookMidtermColumn, GradeComponentCodeDTO.MIDTERM);
        bindScoreColumn(gradeBookExperimentColumn, GradeComponentCodeDTO.EXPERIMENT);
        bindScoreColumn(gradeBookFinaltermColumn, GradeComponentCodeDTO.FINALTERM);
        bindDerivedColumn(gradeBookTotalColumn, true);
        bindDerivedColumn(gradeBookPointColumn, false);
        bindTextColumn(gradeBookErrorColumn, row -> String.join("；", row.serverErrors()));
        wireScheme(GradeComponentCodeDTO.DAILY, gradeBookDailyEnabled, gradeBookDailyWeight);
        wireScheme(GradeComponentCodeDTO.MIDTERM, gradeBookMidtermEnabled, gradeBookMidtermWeight);
        wireScheme(GradeComponentCodeDTO.EXPERIMENT, gradeBookExperimentEnabled,
                gradeBookExperimentWeight);
        wireScheme(GradeComponentCodeDTO.FINALTERM, gradeBookFinaltermEnabled,
                gradeBookFinaltermWeight);
        render();
    }

    /**
     * 方案条跟着表格的横向滚动一起移动。
     *
     * <p>方案条是表格外的同级节点，而九列比窗口宽：如果不跟随，向右滚动后四个成绩列会滑到方案条
     * 左边，启用开关与权重输入就落在了别的列下面。表格皮肤创建时（{@code skinProperty} 触发、
     * 此时才有 {@code VirtualFlow}）取到它自己的横向滚动条，把方案条的 {@code translateX} 绑成
     * 滚动量的相反数——两者因此始终对齐。取不到滚动条（无工具包/节点缺失/皮肤未建）时什么都不做，
     * 页面照常工作，只是退回“不跟随”的旧行为。
     */
    private void bindSchemeBarToTableScroll() {
        if (gradeBookTable == null || gradeBookSchemeBar == null) return;
        gradeBookTable.skinProperty().addListener((observable, previous, skin) -> {
            Node bar = gradeBookTable.lookup(".scroll-bar:horizontal");
            if (bar instanceof ScrollBar scrollBar
                    && !gradeBookSchemeBar.translateXProperty().isBound()) {
                gradeBookSchemeBar.translateXProperty().bind(scrollBar.valueProperty().negate());
            }
        });
    }

    /**
     * 表格级的键盘处理，覆盖“还没有打开编辑器”的那一半交互：焦点停在某个成绩格上直接敲数字，
     * 应当就地开始编辑并把这一位作为新值的开头（“选中即输入”）；方向键/Tab/回车则从当前格出发导航。
     *
     * <p>用<b>事件过滤器</b>（捕获阶段）而不是处理器：过滤器的消费会阻止 TableView 自己的行为
     * （方向键改选择、Tab 把焦点带出表格），这正是我们要接管的那部分。事件目标是已打开的编辑器时
     * 直接放行，交给 {@link ScoreEditCell} 自己的过滤器处理。
     */
    private void bindTableKeyboard() {
        gradeBookTable.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (eventTargetsActiveEditor(event)) return;
            Move move = moveOf(event);
            if (move == null) return;
            // 焦点不在可编辑的成绩列上（学号/姓名/总评列、或只读页面、或禁用的组成）时，
            // 一个键都不消费：Tab 仍然能把焦点带出表格，方向键仍然是表格自己的选择移动。
            if (!focusedCellIsEditable()) return;
            event.consume();
            navigateFrom(focusedRowIndex(), focusedCode(), move);
        });
        gradeBookTable.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (eventTargetsActiveEditor(event)) return;
            String typed = event.getCharacter();
            // 只认可打印字符：控制字符（退格、方向键产生的空串等）不触发编辑。
            if (typed == null || typed.isEmpty() || typed.charAt(0) < ' ') return;
            if (!focusedCellIsEditable()) return;
            event.consume();
            requestCellEdit(focusedRowIndex(), scoreColumnOf(focusedCode()), typed);
        });
    }

    private boolean focusedCellIsEditable() {
        GradeComponentCodeDTO code = focusedCode();
        return focusedRowIndex() >= 0 && code != null && isColumnEditable(code);
    }

    /** 事件目标是不是当前打开的那个编辑器（含其内部节点）。 */
    private boolean eventTargetsActiveEditor(Event event) {
        if (activeCell == null) return false;
        return event.getTarget() instanceof Node node && activeCell.owns(node);
    }

    private static Move moveOf(KeyEvent event) {
        return switch (event.getCode()) {
            case UP -> Move.UP;
            case DOWN -> Move.DOWN;
            case LEFT -> Move.LEFT;
            case RIGHT -> Move.RIGHT;
            case TAB -> event.isShiftDown() ? Move.PREVIOUS : Move.NEXT;
            case ENTER -> Move.DOWN;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ 生命周期

    /** 工作台打开某个教学班的成绩表：注册离开守卫并加载最新草稿。 */
    void showOffering(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        this.offeringId = offeringId;
        // 换班即丢弃上一个班的编辑内容与幂等 ID：新班必须拿到自己的 revision 与名单摘要。
        closeActiveCell();
        clearPendingEdit();
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
        // 页面被卸下：把打开的编辑器收起来，也别让排队的“落到某一格”在页面之外生效。
        closeActiveCell();
        clearPendingEdit();
        PageLeaveGuard.clear(this);
    }

    private void clearPendingEdit() {
        pendingEditRow = -1;
        pendingEditColumn = null;
        pendingEditSeed = null;
        pendingEditRetries = 0;
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

    /**
     * 重新加载：会丢弃未保存的修改，因此先问同一个确认函数——但用的是“重新加载”的措辞，
     * 而不是“离开页面”的措辞（同一个机制，两种说法）。被拒绝时保持当前内容不动。
     */
    void reload() {
        if (model != null && dirty() && !confirmation.apply(RELOAD_PROMPT_TEXT)) return;
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
        // 下一行的 generation 会作废在途加载的响应（它再也走不到自己的复位分支）：加载标志在这里
        // 一并复位，否则“正在加载成绩表...”会一直挂着、重新加载按钮永久禁用——与 loadBook 在开头
        // 复位写入标志是对称的同一件事。
        loading = false;
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
        // 同 save()：提交同样作废在途加载的响应，加载标志必须一起复位，不能让它悬空。
        loading = false;
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

    /**
     * 请求把某一格打开成编辑器。已经开着的那一格直接返回——否则点一下输入框就会重开编辑，
     * 光标与选中范围都会被重置（用户会觉得“点进去就全选没了”）。
     *
     * <p>只读页面与禁用列在这里就被拒绝：宁可不打开，也不要打开一个接收不了输入的输入框。
     * 目标行可能还在视口之外，因此先把请求记成“待落点”，滚动之后再交给那个单元格。
     */
    void requestCellEdit(int rowIndex, TableColumn<Row, String> column) {
        requestCellEdit(rowIndex, column, null);
    }

    void requestCellEdit(int rowIndex, TableColumn<Row, String> column, String seedText) {
        if (model == null || gradeBookTable == null || column == null) return;
        if (rowIndex < 0 || rowIndex >= model.rows().size()) return;
        GradeComponentCodeDTO code = codeOf(column);
        if (code == null || !isColumnEditable(code)) return;
        if (activeCell != null && activeCell.getIndex() == rowIndex
                && activeCell.column() == column) {
            activeCell.typeIn(seedText);
            return;
        }
        boolean rendered = scoreCellAt(rowIndex, column) != null;
        closeActiveCell();
        pendingEditRow = rowIndex;
        pendingEditColumn = column;
        pendingEditSeed = seedText;
        gradeBookTable.getFocusModel().focus(rowIndex, column);
        // 目标格已经看得见就不要动视口：连续录入时视线跟着光标走，而不是每按一次方向键表格就跳一下。
        if (!rendered) gradeBookTable.scrollTo(rowIndex);
        applyPendingEdit();
    }

    /**
     * 把待落点的编辑请求交给目标单元格。目标行可能刚被 {@code scrollTo} 到视口里、单元格还没建出来，
     * 所以没找到就排到下一轮布局之后再试（有次数上限，避免死死排队）。
     */
    private void applyPendingEdit() {
        if (pendingEditRow < 0 || pendingEditColumn == null) return;
        ScoreEditCell cell = scoreCellAt(pendingEditRow, pendingEditColumn);
        if (cell == null) {
            if (pendingEditRetries++ < PENDING_EDIT_RETRIES) runLater(this::applyPendingEdit);
            return;
        }
        String seed = pendingEditSeed;
        pendingEditRow = -1;
        pendingEditColumn = null;
        pendingEditSeed = null;
        pendingEditRetries = 0;
        cell.beginEdit(seed);
        if (cell.editorOpen()) activeCell = cell;
    }

    /** 关闭当前编辑器（保留已经实时写入的值）。 */
    private void closeActiveCell() {
        ScoreEditCell cell = activeCell;
        activeCell = null;
        if (cell != null) cell.endEdit(false);
    }

    void editorClosed(ScoreEditCell cell) {
        if (activeCell == cell) activeCell = null;
    }

    void focusTable() {
        if (gradeBookTable != null) gradeBookTable.requestFocus();
    }

    void runLater(Runnable action) {
        fxExecutor.accept(action);
    }

    /**
     * 输入实时生效：每敲一个键就把原文写进模型，派生列（总评/绩点）、非法标红与按钮/提示状态
     * 立刻跟着更新。这里不重建表格行（见 {@link #renderTable}），所以正在输入的编辑器不会被拆掉，
     * 也就不需要“再按一次回车确认”。以后要加防抖自动保存，接在这句话后面即可。
     */
    void liveScoreEdit(Row row, GradeComponentCodeDTO code, String text) {
        if (model == null || !model.canEdit() || row == null) return;
        model.setScore(row.enrollmentId(), code, text);
        refreshRowDisplay(row);
        render(false);
    }

    /** {@code Esc}：把这一格恢复成编辑开始时的原文。 */
    void revertScore(Row row, GradeComponentCodeDTO code, String text) {
        if (model == null || !model.canEdit() || row == null) return;
        model.setScore(row.enrollmentId(), code, text);
        refreshRowDisplay(row);
        render(false);
    }

    /** 从某一格出发导航；边界与禁用列的判定全在无工具包的 {@link GradeBookNavigator} 里。 */
    void navigateFrom(int rowIndex, GradeComponentCodeDTO code, Move move) {
        if (model == null || code == null || move == null) return;
        int columnIndex = code.ordinal();
        Optional<Position> target = GradeBookNavigator.resolve(new Position(rowIndex, columnIndex),
                move, model.rows().size(), editableColumns());
        if (target.isEmpty()) return;
        Position position = target.get();
        requestCellEdit(position.row(), scoreColumnOf(GradeComponentCodeDTO.values()[position.column()]));
    }

    /** 复制：把当前格的原文（模型里保存的那份）放进系统剪贴板。 */
    void copyScoreText(Row row, GradeComponentCodeDTO code) {
        if (row == null || code == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(row.cell(code).text());
        Clipboard.getSystemClipboard().setContent(content);
    }

    /**
     * 粘贴：从当前格开始向右下铺开，每一格都走 {@link GradeBookEditorModel#setScore}，
     * 因此非法值会原样留下并标红，绝不会被静默丢掉；空白格表示“清空这一格”。
     */
    void pasteScoreBlock(Row startRow, GradeComponentCodeDTO startCode, String clipboardText) {
        if (model == null || !model.canEdit() || startRow == null || startCode == null) return;
        List<List<String>> block = GradeClipboardParser.parse(clipboardText);
        if (block.isEmpty()) return;
        int startRowIndex = indexOfRow(startRow);
        if (startRowIndex < 0) return;
        List<PasteTarget> targets = planPaste(startRowIndex, startCode.ordinal(), block,
                model.rows().size(), editableColumns());
        for (PasteTarget target : targets) {
            Row row = model.rows().get(target.row());
            model.setScore(row.enrollmentId(), target.code(), target.text());
        }
        // 被改到的格子（起点之外的那些）不会收到输入事件，必须就地重画一次。刻意不重建整张表：
        // refresh() 会把单元格连编辑器一起拆掉，而这里要保住起点格上打开的编辑器与光标。
        for (PasteTarget target : targets) {
            ScoreEditCell cell = scoreCellAt(target.row(), scoreColumnOf(target.code()));
            if (cell != null) cell.refreshFromModel();
        }
        render();
    }

    /**
     * 粘贴落点：行数夹在名单长度内（多出来的行直接丢弃，而不是溢出到别的班），列只会落在四个成绩列
     * 中<b>可编辑</b>的那些上——禁用列跳过不写，与被禁用的列本来就不参与录入保持一致。
     */
    static List<PasteTarget> planPaste(int startRow, int startColumn, List<List<String>> block,
            int rowCount, List<Boolean> editableColumns) {
        List<PasteTarget> targets = new ArrayList<>();
        if (block == null || editableColumns == null) return targets;
        for (int rowOffset = 0; rowOffset < block.size(); rowOffset++) {
            int row = startRow + rowOffset;
            if (row < 0 || row >= rowCount) break;
            List<String> cells = block.get(rowOffset);
            for (int columnOffset = 0; columnOffset < cells.size(); columnOffset++) {
                int column = startColumn + columnOffset;
                if (column < 0 || column >= editableColumns.size()) continue;
                if (!Boolean.TRUE.equals(editableColumns.get(column))) continue;
                targets.add(new PasteTarget(row, GradeComponentCodeDTO.values()[column],
                        cells.get(columnOffset)));
            }
        }
        return List.copyOf(targets);
    }

    /** 一格粘贴落点：第几行、哪个成绩组成、写什么原文。 */
    record PasteTarget(int row, GradeComponentCodeDTO code, String text) {
    }

    /** 四个成绩列当前是否可编辑（只读页面全为 false，禁用列为 false）。 */
    List<Boolean> editableColumns() {
        List<Boolean> editable = new ArrayList<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            editable.add(isColumnEditable(code));
        }
        return List.copyOf(editable);
    }

    boolean isColumnEditable(GradeComponentCodeDTO code) {
        return model != null && model.canEdit() && code != null && model.column(code).enabled();
    }

    private int indexOfRow(Row row) {
        if (model == null) return -1;
        List<Row> rows = model.rows();
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index) == row) return index;
        }
        return -1;
    }

    private int focusedRowIndex() {
        if (gradeBookTable == null) return -1;
        TablePosition<Row, ?> focused = gradeBookTable.getFocusModel().getFocusedCell();
        return focused == null ? -1 : focused.getRow();
    }

    private GradeComponentCodeDTO focusedCode() {
        if (gradeBookTable == null) return null;
        TablePosition<Row, ?> focused = gradeBookTable.getFocusModel().getFocusedCell();
        return focused == null ? null : codeOf(focused.getTableColumn());
    }

    private GradeComponentCodeDTO codeOf(TableColumn<Row, ?> column) {
        if (column == null) return null;
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            if (scoreColumnOf(code) == column) return code;
        }
        return null;
    }

    private ScoreEditCell scoreCellAt(int rowIndex, TableColumn<Row, String> column) {
        if (gradeBookTable == null) return null;
        for (Node node : gradeBookTable.lookupAll(".table-cell")) {
            if (node instanceof ScoreEditCell cell && cell.getIndex() == rowIndex
                    && cell.column() == column) {
                return cell;
            }
        }
        return null;
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
        render(true);
    }

    /**
     * @param refreshTable 是否连带刷新表格显示。逐格录入走 {@code false}：那一次刷新由
     *                     模型的可观察派生值加单元格自己的样式更新完成，重建表格反而会打断输入。
     */
    private void render(boolean refreshTable) {
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
        String notice = hasModel ? model.stateNotice() : null;
        if (gradeBookNoticeLabel != null) {
            gradeBookNoticeLabel.setText(notice == null ? "" : notice);
        }
        setActive(gradeBookNoticeLabel, notice != null);
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
        if (refreshTable) renderTable();
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
                        // 非法权重与非法单元格一样标红：否则“请修正标红的单元格”会把用户引到
                        // 四个长得一模一样的输入框前面，却没有任何一个被标出来。
                        weight.getStyleClass().remove(ERROR_FIELD_CLASS);
                        if (weightFieldHasError(column)) {
                            weight.getStyleClass().add(ERROR_FIELD_CLASS);
                        }
                    }
                }
            }
        } finally {
            syncingScheme = false;
        }
    }

    /**
     * 刷新表格。
     *
     * <p><b>只有底层行对象真的换了才重建 items</b>（首次加载、保存/提交后的服务端快照）。
     * {@code setAll} 会重建每一行与每一个单元格，把用户正在输入的那个编辑器一起拆掉——这正是旧交互
     * 里“回车之后还得再按一次”的结构性原因。行对象没换时只做一次 {@code refresh()}，让单元格重新
     * 取一次值（禁用列置灰、权重变化后的派生态）。
     *
     * <p>逐格录入的刷新走的是另一条更轻的路（{@link #refreshRowDisplay}）：派生列的值是每行一个
     * 可观察对象，单元格订阅它，所以总评/绩点能在敲键的同时更新，而不用重建表格、也不会打断输入。
     */
    private void renderTable() {
        if (gradeBookTable == null) return;
        List<Row> items = model == null ? List.of() : model.rows();
        if (!itemsMatchTable(items)) {
            // 行对象换了：编辑器指向的行已经不存在，先收起来（值早就实时写进模型，不会丢）。
            closeActiveCell();
            displays.clear();
            gradeBookTable.getItems().setAll(items);
        } else if (activeCell == null) {
            // 编辑器开着时不重建单元格：那种整体性刷新（开关/权重/加载）本来就不会和输入同时发生。
            gradeBookTable.refresh();
        }
        refreshAllDisplays();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            TableColumn<Row, String> column = scoreColumnOf(code);
            if (column != null) {
                column.setEditable(isColumnEditable(code));
            }
        }
        applyPendingEdit();
    }

    /** 表格里当前绑定的行与模型里的行是不是同一批对象（逐位比较，行数很少，代价可以忽略）。 */
    private boolean itemsMatchTable(List<Row> items) {
        if (gradeBookTable.getItems().size() != items.size()) return false;
        for (int index = 0; index < items.size(); index++) {
            if (gradeBookTable.getItems().get(index) != items.get(index)) return false;
        }
        return true;
    }

    /** 派生列（总评/绩点）的可观察值：每行一份，单元格订阅它，逐格录入时不需要重建表格。 */
    private static final class RowDisplay {
        private final ReadOnlyStringWrapper total = new ReadOnlyStringWrapper();
        private final ReadOnlyStringWrapper point = new ReadOnlyStringWrapper();
    }

    /**
     * 权重区提示：已配齐时给出合计，未配齐时说明还差多少（草稿允许未配齐，只有提交要求 10000）。
     * 有非法权重时把原因也写在这里——标红的输入框配合这句才找得到问题。
     */
    private String schemeText() {
        if (model == null) return "";
        long total = 0;
        StringBuilder enabledText = new StringBuilder();
        String weightError = null;
        for (Column column : model.columns()) {
            if (!column.enabled()) continue;
            total += column.weightBasisPoints();
            if (enabledText.length() > 0) enabledText.append("/");
            enabledText.append(GradeBookEditorModel.componentLabel(column.code()));
            if (weightError == null && column.weightError() != null) {
                weightError = column.weightError();
            }
        }
        String enabled = enabledText.length() == 0 ? "无" : enabledText.toString();
        String state = total == GradeBookEditorModel.TOTAL_WEIGHT_BASIS_POINTS
                && enabledText.length() > 0 ? "已配齐" : "未配齐";
        String text = "启用组成：" + enabled + "　权重合计：" + total + "/"
                + GradeBookEditorModel.TOTAL_WEIGHT_BASIS_POINTS + "（万分比，" + state + "）";
        return weightError == null ? text : text + "　权重输入有误：" + weightError;
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
        // 编辑器自己管生命周期（单击即编辑、输入即写入、方向键导航），不再走 startEdit/commitEdit：
        // 那套“回车提交”的默认行为正是用户抱怨的“还要二次确认”。
        column.setCellFactory(ignored -> new ScoreEditCell(this, column, code));
    }

    /**
     * 总评/绩点两列的取值来自每行一份的可观察对象（而不是每次新造一个只读包装）：单元格订阅它，
     * 所以逐格录入时这两列能自己更新，不需要重建表格，也就不用打断正在输入的编辑器。
     */
    private void bindDerivedColumn(TableColumn<Row, String> column, boolean totalColumn) {
        if (column == null) return;
        column.setCellValueFactory(cell -> {
            Row row = cell.getValue();
            if (row == null) return new ReadOnlyStringWrapper("");
            RowDisplay display = displayOf(row);
            return totalColumn ? display.total : display.point;
        });
    }

    private RowDisplay displayOf(Row row) {
        return displays.computeIfAbsent(row, ignored -> new RowDisplay());
    }

    /** 重算某一行的总评/绩点并写入可观察值；界面上的那两格会立刻跟着变。 */
    private void refreshRowDisplay(Row row) {
        RowDisplay display = displayOf(row);
        display.total.set(totalText(row));
        display.point.set(pointText(row));
    }

    private void refreshAllDisplays() {
        if (model == null) return;
        for (Row row : model.rows()) {
            refreshRowDisplay(row);
        }
    }

    private String totalText(Row row) {
        BigDecimal total = model == null ? null : model.rowTotal(row);
        return total == null ? PLACEHOLDER : total.toPlainString();
    }

    private String pointText(Row row) {
        BigDecimal point = model == null ? null : model.rowGradePoint(row);
        return point == null ? PLACEHOLDER : point.toPlainString();
    }

    /**
     * 权重输入框要不要标红：启用且文本非法才标。禁用列的残留文本不会进入请求（服务端保留草稿
     * 旧值），因此不标红，也不需要用户去修它。渲染与测试共用这一个判断。
     */
    static boolean weightFieldHasError(Column column) {
        return column != null && column.enabled() && column.weightError() != null;
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
