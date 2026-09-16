package controller;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.function.Function;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
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
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import model.course.teacher.GradeBookEditorModel;
import model.course.teacher.GradeBookEditorModel.Column;
import model.course.teacher.GradeBookEditorModel.Row;
import model.course.teacher.GradeClipboardParser;
import model.course.teacher.GradeBookNavigator;
import model.course.teacher.GradeBookNavigator.Move;
import model.course.teacher.GradeBookNavigator.Position;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.SocketTeacherFileTransport;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import service.TeacherFileTransport;
import util.AlertUtil;
import util.FXMLUtil;
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
 * <p>状态提示（{@code gradeBookFeedbackLabel}）与三个导入入口同排：新文本一到达就显示，留
 * {@link #FEEDBACK_VISIBLE_DURATION} 之后渐变淡出并隐藏，隐藏只是界面状态——
 * {@link #feedbackText} 里那句话不会被清掉（换班与页面重新进入才清）。每次到达都会取消上一次
 * 计时并重新计时，因此同一个字符串再次到达（连续两次「成绩草稿已保存」）同样会重新显示。
 *
 * <p>录入交互是“Excel 式”的（见 {@link ScoreEditCell}）：单击选中即编辑、输入即写入模型、
 * 方向键即导航、离开单元格即完成、只有非法输入才在本格标红打断。所有导航与剪贴板的判定都放在
 * 无工具包的 {@link GradeBookNavigator}/{@link GradeClipboardParser} 里，本类只负责接线与渲染。
 *
 * <p>写库仍然是批量的（保存草稿 / 提交成绩两个按钮）：实时写入只落在内存模型上，
 * 因此既没有“按回车才算数”的二次确认，也不会为每敲一个键发一次网络请求。以后要加防抖自动保存，
 * 只需在 {@link #liveScoreEdit} 之后挂一个定时器即可，模型与幂等 ID 的设计都不用动。
 *
 * <p>批次另有 <b>两个新的版本入口</b>（设计 §8），各自只在一种状态下出现：被驳回显示
 * 「重新编辑」（{@link #reopenRejected()}，按那一批的冻结快照重开，取消不建草稿），已通过显示
 * 「申请修改」（{@link #beginCorrection()}，从表格里选中的那一位学生打开更正表单）。更正始终以整个
 * 教学班为单位提交：表单只是把拟修改分数写回这张表，随后仍走同一套保存/提交，本页不新增第二条写库
 * 通路，因而也不会把新增补录写成一行独立的已发布成绩。待审核时两个入口都不出现。
 *
 * <p>所有节点都可能为 {@code null}，控制器测试因此无需 JavaFX 工具包；离开确认函数可注入，
 * 测试不会真的弹对话框。
 */
public final class TeacherGradeBookController implements PageLeaveGuard,
        TeacherGradeImportController.Host {
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
    static final String FROZEN_SCHEME_TEXT = "导入预览期间方案已冻结，取消导入后才能调整权重";
    static final String PLACEHOLDER = "—";

    // ---- 两个版本入口（设计 §8：驳回重提与发起更正）。入口文案同时是结果提示的来源，
    //      避免同一件事出现两套说法。
    static final String REOPEN_PROMPT_TEXT = "重新编辑会按最后一次被驳回的批次重建草稿："
            + "提交该批次时未启用的组成没有进过批次，你为它们输入的值不会回来。确定重新编辑吗？";
    static final String REOPENING_TEXT = "正在按被驳回的批次重建草稿...";
    static final String REOPEN_SUCCESS_TEXT = "已回到编辑状态，修改后重新提交";
    static final String REOPEN_FAILURE_TEXT = "重新编辑失败，请重试";
    static final String REOPEN_CONFLICT_PREFIX = "成绩表已被其他操作更新，请重新加载后再重新编辑：";
    static final String CORRECTION_OPEN_FAILURE_TEXT = "无法打开更正表单，请重试";
    static final String CORRECTION_STARTED_TEXT = "更正草稿已建立，拟修改的分数已写入成绩表；保存或提交后等待管理员审核";
    /** 更正表单的资源路径；标题由 {@link TeacherGradeCorrectionDialogController#TITLE} 固定。 */
    static final String CORRECTION_VIEW = "/resources/fxml/TeacherGradeCorrectionDialog.fxml";
    /** 只有这两种批次状态各自有一个新的版本入口；其它状态下两个按钮都不出现。 */
    private static final String STATE_APPROVED = "APPROVED";
    private static final String STATE_REJECTED = "REJECTED";

    /** 导航到一个还没渲染出来的行时，等布局把它带进视口的重试次数上限。 */
    private static final int PENDING_EDIT_RETRIES = 5;
    /** 权重输入框的非法样式：与单元格标红同一套视觉，用户一眼能找到是哪一列。 */
    private static final String ERROR_FIELD_CLASS = "teacher-course-weight-field-error";
    /** 状态提示留在按钮行上的时长，以及随后渐变淡出的时长（3 秒后消退，不是瞬间隐藏）。 */
    private static final Duration FEEDBACK_VISIBLE_DURATION = Duration.seconds(3);
    private static final Duration FEEDBACK_FADE_DURATION = Duration.millis(400);

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;
    /** 离开/重新加载确认：消息 → 是否同意。默认弹对话框，测试注入固定回答。 */
    private final Function<String, Boolean> confirmation;
    /**
     * Excel 导入的编排：票据、短连接传输、服务端预览与修订都归它管。本类只把它接到同一张成绩表
     * （{@link TeacherGradeImportController.Host}），绝不复制第二张表。
     */
    private final TeacherGradeImportController importController;

    private Runnable onBack = () -> { };
    private GradeBookEditorModel model;
    private String offeringId;
    private boolean active;
    private boolean loading;
    private boolean saving;
    private boolean submitting;
    private boolean confirmingSubmit;
    private boolean reopening;
    private boolean syncingScheme;
    /**
     * 更正入口选中的那一行：更正从某一位学生打开，但提交的仍是整个教学班的新版本。
     *
     * <p>它只在表格存在时由选中监听器推进，也由 {@link #selectRow(Row)} 直接注入——无工具包的
     * 控制器测试因此不需要真的构造一棵表格树。
     */
    private Row selectedRow;
    /** 打开更正表单的一方；默认弹窗口，测试注入替身（与课表页的弹窗打开方式一致）。 */
    private Consumer<Row> correctionOpener = this::openCorrectionDialog;
    /**
     * 重新编辑的确认：<b>不与</b>{@link #confirmation}共用。那一个的标题是「未保存的成绩」，而重开
     * 只出现在只读的被驳回批次上——那一刻证明得了「没有任何未保存的成绩」，用一个说自己有未保存内容的
     * 标题去问要不要重建草稿，是标题在撒谎。这里给它自己的标题。
     */
    private Function<String, Boolean> reopenConfirmation = TeacherGradeBookController::confirmReopen;
    /**
     * 状态提示的文本：本类的唯一事实来源（测试经 {@link #feedbackText()} 读取）。
     *
     * <p>只经 {@link #setFeedback} 写入——它同时推进 {@link #feedbackRevision}，渲染因此能区分
     * “这一次渲染之前有新提示到达”与“这只是一次重画”。
     */
    private String feedbackText;
    /**
     * 提示到达计数：每次 {@link #setFeedback} 前进一格。
     *
     * <p>触发条件是“到达”而不是“文本变化”：连续两次「成绩草稿已保存」是同一个字符串，按文本比较
     * 会漏掉第二次到达，上一次消退的收尾就会把这条新提示连着一起清掉，用户从此看不到任何提示。
     */
    private long feedbackRevision;
    /** 已经渲染过的到达计数：两者不等就是有新提示要显示并重新计时。 */
    private long renderedFeedbackRevision;
    /** 提示是否已经随渐变消退并隐藏；文本没再到达时 {@link #render} 不把它显示回来。 */
    private boolean feedbackFaded;
    /** 正在跑的那次消退（{@code null} 表示没有在跑的计时）；换新提示时取消旧的。 */
    private Timeline feedbackFade;
    private String errorText;
    private String pendingSaveOperationId;
    private String pendingSubmitOperationId;
    /**
     * 重新编辑的幂等 ID：与保存/提交同一套语义——只在第一次真正发请求时生成，失败后重试复用同一个。
     *
     * <p>复用是必要的：服务端已经打开了草稿但响应在网络上丢了时，换一个新 ID 再按一次只会拿到
     * 「成绩草稿已经打开」的冲突，而这个操作其实早就成功了；同一个 ID 换回来的是那次成功的重放。
     * 重新加载、切换教学班或离开页面都会作废它（页面状态变了，这一次意图不再成立）。
     */
    private String pendingReopenOperationId;
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
    @FXML private Button gradeBookReopenButton;
    @FXML private Button gradeBookCorrectionButton;
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
    @FXML private Label gradeBookImportSummaryLabel;
    @FXML private Button gradeBookDownloadTemplateButton;
    @FXML private Button gradeBookExportGradesButton;
    @FXML private Button gradeBookImportButton;
    @FXML private Button gradeBookImportIssuesButton;
    @FXML private Button gradeBookCancelImportButton;
    @FXML private Button gradeBookConfirmImportButton;

    public TeacherGradeBookController() {
        this(TeacherCourseServices.current(), new SocketTeacherFileTransport(), Platform::runLater,
                TeacherGradeBookController::confirm, null);
    }

    TeacherGradeBookController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this(service, new SocketTeacherFileTransport(), fxExecutor, TeacherGradeBookController::confirm,
                null);
    }

    TeacherGradeBookController(TeacherCourseService service, Consumer<Runnable> fxExecutor,
            Function<String, Boolean> confirmation) {
        this(service, new SocketTeacherFileTransport(), fxExecutor, confirmation, null);
    }

    TeacherGradeBookController(TeacherCourseService service, TeacherFileTransport transport,
            Consumer<Runnable> fxExecutor) {
        this(service, transport, fxExecutor, TeacherGradeBookController::confirm, null);
    }

    /**
     * @param dialogs 文件选择端口；null 表示用真实的 JavaFX 选择器（测试注入替身，不需要工具包）。
     */
    TeacherGradeBookController(TeacherCourseService service, TeacherFileTransport transport,
            Consumer<Runnable> fxExecutor, Function<String, Boolean> confirmation,
            TeacherGradeImportController.FileDialogs dialogs) {
        this(service, transport, fxExecutor, confirmation, dialogs,
                TeacherGradeImportController::confirmOverwrite);
    }

    /**
     * @param overwriteConfirmation 下载目标已存在时的覆盖确认；与 {@code confirmation}（离开/重新加载
     *                              未保存修改）刻意分开：同一个函数会让教师把覆盖问题当成丢修改的警告。
     */
    TeacherGradeBookController(TeacherCourseService service, TeacherFileTransport transport,
            Consumer<Runnable> fxExecutor, Function<String, Boolean> confirmation,
            TeacherGradeImportController.FileDialogs dialogs,
            Function<String, Boolean> overwriteConfirmation) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.confirmation = Objects.requireNonNull(confirmation, "Confirmation is required");
        this.importController = new TeacherGradeImportController(service,
                Objects.requireNonNull(transport, "File transport is required"), fxExecutor,
                dialogs == null ? TeacherGradeImportController.fxDialogs(this::ownerWindow) : dialogs,
                Objects.requireNonNull(overwriteConfirmation, "Overwrite confirmation is required"),
                this::ownerWindow);
        this.importController.attach(this);
    }

    /** 生产路径的确认对话框：在 FX 线程弹模态框，等待用户选择。 */
    private static boolean confirm(String message) {
        return AlertUtil.showConfirm("未保存的成绩", message) == ButtonType.OK;
    }

    /** 重新编辑的确认框：标题就是它正在问的那件事。 */
    private static boolean confirmReopen(String message) {
        return AlertUtil.showConfirm("重新编辑成绩表", message) == ButtonType.OK;
    }

    @FXML
    public void initialize() {
        if (gradeBookTable != null) {
            gradeBookTable.setEditable(true);
            // 九列在 860 宽的窗口里放不下：保留列宽并横向滚动，而不是把文字压成省略号。
            gradeBookTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            bindTableKeyboard();
            gradeBookTable.getSelectionModel().selectedItemProperty().addListener(
                    (observable, previous, next) -> selectRow(next));
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
        // 换班前先了结上一班的导入：取消在途短连接并丢弃预览。新班绝不能继承旧班的教学班、
        // 版本或名单摘要。
        importController.cancelOnLeave();
        this.offeringId = offeringId;
        // 换班即丢弃上一个班的编辑内容与幂等 ID：新班必须拿到自己的 revision 与名单摘要。
        closeActiveCell();
        clearPendingEdit();
        this.model = null;
        this.pendingSaveOperationId = null;
        this.pendingSubmitOperationId = null;
        this.pendingReopenOperationId = null;
        this.confirmingSubmit = false;
        this.reopening = false;
        // 选中行属于上一个班：新班的更正入口必须等到它自己的表格选中了人才可用。
        this.selectedRow = null;
        setFeedback(null);
        this.errorText = null;
        this.active = true;
        // 同一个页面实例会被反复进出（打开教学班 → 返回列表 → 再打开），导入编排跟着重新激活。
        importController.attach(this);
        PageLeaveGuard.install(this);
        render();
        loadBook();
    }

    /**
     * 工作台离开本页：取消在途请求（含导入的短连接）、注销离开守卫。页面被卸下之后响应不再写界面，
     * 因此不需要（也不允许）在 {@code release()} 之后继续更新控件。
     */
    void release() {
        active = false;
        loading = false;
        saving = false;
        submitting = false;
        confirmingSubmit = false;
        reopening = false;
        selectedRow = null;
        generation++;
        // 提示的消退计时也一起停掉：页面已卸下，不能留下一个还在跑的动画（同一个控制器实例
        // 会被反复进出，计时器更不能跨页泄漏）。
        stopFeedbackFade();
        // 页面被卸下：把打开的编辑器收起来，也别让排队的“落到某一格”在页面之外生效。
        closeActiveCell();
        clearPendingEdit();
        importController.release();
        PageLeaveGuard.clear(this);
    }

    private void clearPendingEdit() {
        pendingEditRow = -1;
        pendingEditColumn = null;
        pendingEditSeed = null;
        pendingEditRetries = 0;
    }

    /**
     * 离开保护：导入预览是临时的，离开本页要取消在途传输（关闭短连接）并恢复导入前的编辑副本，
     * 然后按恢复出来的 {@code dirty} 决定要不要提示——教师原本的未保存修改如实被问一次。
     *
     * <p><b>顺序按「有没有教师的临时成果会丢」分开：</b>还没有预览时，取消只是关掉在途短连接
     * （上传链的每一段都会先 {@code requireStillImporting} 再派发，因此票据不会被白花、也不会留下
     * 孤儿文件），照旧在提问之前就取消；已经有预览时则先问、得到「确定离开」才取消——教师点返回
     * 又回答「取消，不离开」的话，预览、修正与那份服务端候选必须原封不动地留在原地。
     *
     * <p><b>确认在途时直接拒绝离开：</b>{@link TeacherGradeImportController#confirming()} 期间那条
     * 写请求可能已经在服务端写成草稿，此刻取消会给出「已恢复导入前的编辑内容」这句与数据库矛盾的
     * 话。留在页面上等它落地是唯一不撒谎的选择（见 importController 里 {@code confirming} 的不变式）。
     */
    @Override
    public boolean requestLeave() {
        if (importController.confirming()) {
            setFeedback(TeacherGradeImportController.CONFIRMING_TEXT);
            render();
            return false;
        }
        if (!importController.importing()) {
            // 没有预览：先取消在途传输（关闭短连接），这里没有教师的临时成果会丢。
            importController.cancelOnLeave();
        }
        if (!dirty()) return true;
        if (!confirmation.apply(LEAVE_PROMPT_TEXT)) return false;
        // 明确要离开才丢预览与修正：响应里的 dirty 随之回到导入前的真实状态（本方法已经问过，
        // 因此这次恢复不再触发第二次提问）。
        importController.cancelOnLeave();
        return true;
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
        if (importController.confirming()) {
            // 确认在途时重新加载会丢掉那条写请求的结果（按钮此时本来也是禁用的，这里是第二道门）。
            setFeedback(TeacherGradeImportController.CONFIRMING_TEXT);
            render();
            return;
        }
        if (model != null && dirty() && !confirmation.apply(RELOAD_PROMPT_TEXT)) return;
        pendingSaveOperationId = null;
        pendingSubmitOperationId = null;
        // 重新加载会拿到新的草稿版本，旧预览与旧候选一并作废。
        importController.cancelOnLeave();
        loadBook();
    }

    // ------------------------------------------------------------------ Excel 模板、导入与名单导出

    /** 下载成绩模板：导入控制器负责票据、覆盖确认与短连接传输，本类只提供教学班与窗口。 */
    @FXML
    void handleDownloadTemplate(Event event) {
        importController.downloadTemplate(offeringId);
    }

    /**
     * 导出成绩：本页导出的是**这张表当前的草稿**——名单三列加四项成绩、总评与绩点，未填写的成绩在
     * 文件里写 0。要导出学生名单本身（含退课行、状态与选课/退课时间，并可按当前筛选）走教学班详情页
     * 学生名单 Tab 的导出按钮，那是另一条路径、另一份文件。
     */
    @FXML
    void handleExportGrades(Event event) {
        importController.exportGrades(offeringId);
    }

    /** 导入入口：FileChooser 在 FX 线程，上传与解析在后台，服务端预览回来后合并进这张表。 */
    @FXML
    void handleStartImport(Event event) {
        importController.startImport();
    }

    /** 重新打开异常明细：关闭弹窗不影响同表预览，这里把它再叫回来。 */
    @FXML
    void handleShowImportIssues(Event event) {
        importController.showIssues();
    }

    @FXML
    void handleCancelImport(Event event) {
        importController.cancelImport();
    }

    @FXML
    void handleConfirmImport(Event event) {
        importController.confirmImport();
    }

    // ------------------------------------------------------------------ 导入控制器对宿主的要求

    @Override
    public void feedback(String text) {
        setFeedback(text);
        render();
    }

    @Override
    public void gradeBookChanged() {
        render();
    }

    @Override
    public void importStateChanged() {
        render();
    }

    /**
     * 确认成功的落点：用服务端返回的草稿整体替换编辑内容（dirty 随之清零），并作废旧幂等 ID——
     * 导入之后的下一次写操作一定是新的 UUID，绝不复用导入时那个。
     */
    @Override
    public void replaceWithServerDraft(TeacherGradeBookDTO book) {
        if (model != null && book != null) {
            model.applyServerSnapshot(book);
        }
        pendingSaveOperationId = null;
        pendingSubmitOperationId = null;
        render();
    }

    /** 弹窗的 owner 窗口：表格还没进场景时返回 null，弹窗仍然打开，只是不锁定父窗口。 */
    private Window ownerWindow() {
        if (gradeBookTable == null || gradeBookTable.getScene() == null) return null;
        return gradeBookTable.getScene().getWindow();
    }

    private void loadBook() {
        if (offeringId == null) return;
        long current = ++generation;
        loading = true;
        errorText = null;
        // 重新加载作废在途写请求的响应（generation 已经变了）；写入标志复位，按钮不会永久禁用。
        saving = false;
        submitting = false;
        reopening = false;
        // 行对象会被整份换掉，旧选中行不再属于这张表。
        selectedRow = null;
        // 重新加载拿到的是新快照：这条路走完，之前那次重开的意图与它的幂等 ID 都不再成立。
        pendingReopenOperationId = null;
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
        // 导入预览期间只能取消或确认导入：草稿写入必须来自其中一条明确的路径。
        if (importController.importing()) {
            setFeedback(FROZEN_SCHEME_TEXT);
            render();
            return;
        }
        String blocked = model.saveBlockReason();
        if (blocked != null) {
            setFeedback(blocked);
            render();
            return;
        }
        if (pendingSaveOperationId == null) pendingSaveOperationId = UUID.randomUUID().toString();
        WriteGradeBookRequestDTO request;
        try {
            request = model.writeRequest(pendingSaveOperationId);
        } catch (IllegalStateException refused) {
            setFeedback(refused.getMessage());
            render();
            return;
        }
        saving = true;
        // 下一行的 generation 会作废在途加载的响应（它再也走不到自己的复位分支）：加载标志在这里
        // 一并复位，否则“正在加载成绩表...”会一直挂着、重新加载按钮永久禁用——与 loadBook 在开头
        // 复位写入标志是对称的同一件事。
        loading = false;
        setFeedback(SAVING_TEXT);
        errorText = null;
        render();
        long current = ++generation;
        service.saveGradeDraft(request).whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            saving = false;
            if (failure != null) {
                // 失败保留编辑内容：模型一个字段都不动，只把原因显示出来。
                setFeedback(conflictPrefix(failure, SAVE_CONFLICT_PREFIX)
                        + failureText(failure, SAVE_FAILURE_TEXT));
                render();
                return;
            }
            applySnapshot(result);
            pendingSaveOperationId = null;
            setFeedback(SAVE_SUCCESS_TEXT);
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
        // 导入预览期间不进入提交确认：确认导入不是提交审批，两条流程不能混在一起。
        if (importController.importing()) {
            setFeedback(FROZEN_SCHEME_TEXT);
            render();
            return;
        }
        confirmingSubmit = true;
        setFeedback(null);
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
        if (importController.importing()) {
            setFeedback(FROZEN_SCHEME_TEXT);
            render();
            return;
        }
        String blocked = model.submitBlockReason();
        if (blocked != null) {
            confirmingSubmit = false;
            setFeedback(blocked);
            render();
            return;
        }
        confirmingSubmit = false;
        if (pendingSubmitOperationId == null) pendingSubmitOperationId = UUID.randomUUID().toString();
        WriteGradeBookRequestDTO request;
        try {
            request = model.writeRequest(pendingSubmitOperationId);
        } catch (IllegalStateException refused) {
            setFeedback(refused.getMessage());
            render();
            return;
        }
        submitting = true;
        // 同 save()：提交同样作废在途加载的响应，加载标志必须一起复位，不能让它悬空。
        loading = false;
        setFeedback(SUBMITTING_TEXT);
        errorText = null;
        render();
        long current = ++generation;
        service.submitGradeBook(request).whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            submitting = false;
            if (failure != null) {
                setFeedback(conflictPrefix(failure, SUBMIT_CONFLICT_PREFIX)
                        + failureText(failure, SUBMIT_FAILURE_TEXT));
                render();
                return;
            }
            applySnapshot(result);
            pendingSubmitOperationId = null;
            setFeedback(SUBMIT_SUCCESS_TEXT);
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

    // ------------------------------------------------------------------ 新的版本入口

    /**
     * 被驳回的批次：显式「重新编辑」。在此之前教师只能靠“再存一次”让草稿悄悄重开，现在有一个
     * 说清楚发生了什么的入口。
     *
     * <p>它<b>不是</b>一次普通保存：草稿按那一批的冻结快照重建，因此提交时被禁用的组成如果当时
     * 没进批次，教师为它输入的值不会回来——提示语必须如实这么说，不能承诺未提交的修改会幸存。
     * 用户在这里点「取消」什么都不发生：不建草稿、不发请求、也不消耗 operationId。
     */
    @FXML
    void handleReopenRejected(Event event) {
        reopenRejected();
    }

    void reopenRejected() {
        if (!canReopenRejected()) return;
        if (!reopenConfirmation.apply(REOPEN_PROMPT_TEXT)) return;
        if (pendingReopenOperationId == null) {
            pendingReopenOperationId = UUID.randomUUID().toString();
        }
        StartGradeRevisionRequestDTO request = new StartGradeRevisionRequestDTO(
                pendingReopenOperationId, offeringId, model.lastSubmissionId(), model.revision(),
                null);
        reopening = true;
        loading = false;
        setFeedback(REOPENING_TEXT);
        errorText = null;
        render();
        long current = ++generation;
        service.reopenRejectedGradeBook(request).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrent(current)) return;
                    reopening = false;
                    if (failure != null) {
                        // 与保存/提交同一套失败语义：保留编辑内容，只把原因和当前版本说清楚。
                        setFeedback(conflictPrefix(failure, REOPEN_CONFLICT_PREFIX)
                                + failureText(failure, REOPEN_FAILURE_TEXT));
                        render();
                        return;
                    }
                    applySnapshot(result);
                    // 重开之后草稿是新的一份：上一次流程的幂等 ID 一律作废。
                    pendingSaveOperationId = null;
                    pendingSubmitOperationId = null;
                    pendingReopenOperationId = null;
                    setFeedback(REOPEN_SUCCESS_TEXT);
                    errorText = null;
                    render();
                }));
    }

    /**
     * 已通过的批次：唯一可编辑入口是「申请修改」——它打开更正表单，由表单建立更正草稿。更正从
     * 表格里选中的那一位学生打开（表单要显示他的姓名、学号与原始分数），但提交的仍是整个教学班的
     * 新版本，这一页不会因为“只改一个人”而多出一条写单行成绩的通路。
     */
    @FXML
    void handleBeginCorrection(Event event) {
        beginCorrection();
    }

    void beginCorrection() {
        if (!canRequestCorrection()) return;
        correctionOpener.accept(selectedRow);
    }

    /**
     * 更正表单确认后的落点：先把服务端建立的更正草稿换成当前编辑内容（页面因此变成可编辑的
     * DRAFT），再把教师在表单里填写的拟修改原文写进编辑模型。
     *
     * <p>只是写进模型：脏标记随之立起，随后照常走「保存草稿 / 提交成绩」，本页不新增第二条写入
     * 通路，也绝不单独写一行已发布的成绩。
     */
    void applyCorrection(TeacherGradeCorrectionDialogController.CorrectionOutcome outcome) {
        if (model == null || outcome == null || outcome.book() == null) return;
        model.applyServerSnapshot(outcome.book());
        pendingSaveOperationId = null;
        pendingSubmitOperationId = null;
        if (!hasRow(outcome.enrollmentId())) {
            // 更正草稿建立之后名单里已经没有这名学生（例如他退课了）：如实说明，不把分数写到别处。
            setFeedback(CORRECTION_STARTED_TEXT);
            render();
            return;
        }
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            model.setScore(outcome.enrollmentId(), code, outcome.proposed().get(code));
        }
        setFeedback(CORRECTION_STARTED_TEXT);
        errorText = null;
        render();
    }

    /**
     * 用独立 {@code WINDOW_MODAL} Stage 打开更正表单。表单持有同一个教师课程服务实例
     * （生产路径上是共享单例），确认成功后把新草稿与拟修改值交回本页。
     */
    private void openCorrectionDialog(Row row) {
        if (row == null || model == null) return;
        FXMLLoader loader = FXMLUtil.getLoader(CORRECTION_VIEW);
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            setFeedback(CORRECTION_OPEN_FAILURE_TEXT);
            render();
            return;
        }
        TeacherGradeCorrectionDialogController dialog = loader.getController();
        dialog.setOnConfirmed(this::applyCorrection);
        dialog.prepare(new TeacherGradeCorrectionDialogController.CorrectionTarget(offeringId,
                model.lastSubmissionId(), model.revision(), row.enrollmentId(), row.studentUid(),
                row.studentName(), originalsOf(row)));
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        Window owner = ownerWindow();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(TeacherGradeCorrectionDialogController.TITLE);
        stage.setScene(new Scene(root));
        stage.setOnHidden(event -> dialog.dispose());
        stage.show();
    }

    /**
     * 被驳回且本班确实有那一批才可重开：没有批次就没有可回到的基线。
     * 导入过程中的短连接在途时同样不给入口——同一个页面上两条写流程不能同时开跑。
     */
    boolean canReopenRejected() {
        if (model == null || importBusy() || saving || submitting || reopening) return false;
        return STATE_REJECTED.equals(model.state()) && model.lastSubmissionId() != null;
    }

    /** 更正要有个对象：已通过 + 表格里选中了一位学生，两个条件缺一不可。 */
    boolean canRequestCorrection() {
        if (model == null || importBusy() || saving || submitting || reopening) return false;
        return STATE_APPROVED.equals(model.state()) && selectedRow != null
                && model.lastSubmissionId() != null;
    }

    private boolean importBusy() {
        return importController.importing() || importController.busy();
    }

    private boolean hasRow(String enrollmentId) {
        if (enrollmentId == null) return false;
        for (Row row : model.rows()) {
            if (enrollmentId.equals(row.enrollmentId())) return true;
        }
        return false;
    }

    private static Map<GradeComponentCodeDTO, String> originalsOf(Row row) {
        Map<GradeComponentCodeDTO, String> values = new LinkedHashMap<>();
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            values.put(code, row.cell(code).text());
        }
        return values;
    }

    /** 由表格的选中监听器与测试共用：选中一位学生，更正入口据此可用。 */
    void selectRow(Row row) {
        if (selectedRow == row) return;
        selectedRow = row;
        render();
    }

    Row selectedRow() {
        return selectedRow;
    }

    /** 打开更正表单的一方；null 表示回到真实的弹窗口（见 {@link #openCorrectionDialog(Row)}）。 */
    void setCorrectionOpener(Consumer<Row> opener) {
        this.correctionOpener = opener == null ? this::openCorrectionDialog : opener;
    }

    /**
     * 重新编辑的确认：消息 → 是否同意；null 表示回到真实的「重新编辑成绩表」对话框。
     * 它与离开/重新加载那个确认刻意分开，理由见 {@link #reopenConfirmation}。
     */
    void setReopenConfirmation(Function<String, Boolean> confirmation) {
        this.reopenConfirmation = confirmation == null
                ? TeacherGradeBookController::confirmReopen : confirmation;
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
        if (importController.importing()
                && !importController.isCorrectableCell(model.rows().get(rowIndex), code)) {
            // 预览期间只有服务端报过问题的格子可以改：其它格子改了要么是本地假象，要么会被
            // 服务端在确认时忽略，宁可不打开编辑器也不让教师白改。
            setFeedback(TeacherGradeImportController.NO_ISSUE_CELL_TEXT);
            render();
            return;
        }
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
        if (model == null || row == null) return;
        if (importController.importing()) {
            // 预览期间的修改不是本地编辑：原文本进模型，修正立刻发给服务端重新校验（revise），
            // 红框只由服务端的下一次预览消除——绝不在本地把红框敲掉就算完事。
            importController.correctionTyped(row, code, text);
            return;
        }
        if (!model.canEdit()) return;
        model.setScore(row.enrollmentId(), code, text);
        refreshRowDisplay(row);
        render(false);
    }

    /**
     * {@code Esc}：把这一格恢复成编辑开始时的原文。
     *
     * <p>导入预览期间「恢复」同样是一次修正（原文重新成为这次修订里该格的文本），因此走的是同一条
     * revise 路径：撤销修正也要让服务端重新校验，不能只在本地改回去。
     */
    void revertScore(Row row, GradeComponentCodeDTO code, String text) {
        if (model == null || row == null) return;
        if (importController.importing()) {
            importController.correctionTyped(row, code, text);
            return;
        }
        if (!model.canEdit()) return;
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
        // 导入预览期间不接收批量粘贴：一次铺开的格子大多不是异常格，改完也不会进候选。
        if (importController.importing()) {
            setFeedback(TeacherGradeImportController.NO_ISSUE_CELL_TEXT);
            render();
            return;
        }
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
    void applyEnabled(GradeComponentCodeDTO code, boolean enabled) {
        if (syncingScheme) return;
        if (importController.importing()) {
            // 冻结方案切换：控件会被 render 拨回模型状态，同时把原因说清楚。
            setFeedback(FROZEN_SCHEME_TEXT);
            render();
            return;
        }
        if (model == null || !model.canEdit()) {
            render();
            return;
        }
        model.setEnabled(code, enabled);
        render();
    }

    void applyWeight(GradeComponentCodeDTO code, String text) {
        if (syncingScheme) return;
        if (importController.importing()) {
            setFeedback(FROZEN_SCHEME_TEXT);
            render();
            return;
        }
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

    /**
     * 状态提示的唯一写入口：置文本并推进到达计数（画由调用方紧随其后的 {@link #render} 完成，
     * 渲染出口保持只有 {@link #render} 一个）。
     */
    private void setFeedback(String text) {
        feedbackText = text;
        feedbackRevision++;
    }

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
        renderFeedback();

        // 导入预览期间方案与保存/提交都冻结：候选是按当前权重算出来的，改权重会让它作废；
        // 而确认导入只写草稿，提交审批只能走原来的「提交成绩」流程。
        boolean importing = importController.importing();
        boolean editable = hasModel && model.canEdit() && !importing;
        String state = hasModel ? model.state() : null;
        if (gradeBookReloadButton != null) {
            // 写请求在途时不许重新加载：否则会用旧快照覆盖刚提交的结果，写入标志也会被复位。
            gradeBookReloadButton.setDisable(!hasModel || loading || saving || submitting
                    || reopening || importController.busy());
        }
        if (gradeBookSaveButton != null) {
            gradeBookSaveButton.setDisable(!editable || saving || submitting || reopening);
        }
        if (gradeBookSubmitButton != null) {
            gradeBookSubmitButton.setDisable(
                    !editable || saving || submitting || reopening || confirmingSubmit);
        }
        // 两个版本入口各自只在一种批次状态下出现：PENDING 一个可编辑按钮都不给。
        setActive(gradeBookReopenButton, STATE_REJECTED.equals(state));
        setActive(gradeBookCorrectionButton, STATE_APPROVED.equals(state));
        if (gradeBookReopenButton != null) {
            gradeBookReopenButton.setDisable(!canReopenRejected());
        }
        if (gradeBookCorrectionButton != null) {
            gradeBookCorrectionButton.setDisable(!canRequestCorrection());
        }
        setActive(gradeBookConfirmSubmitButton, confirmingSubmit);
        setActive(gradeBookCancelSubmitButton, confirmingSubmit);
        if (gradeBookConfirmSubmitButton != null) {
            gradeBookConfirmSubmitButton.setDisable(submitting);
        }
        renderImportBar(hasModel, importing);
        syncSchemeControls();
        if (refreshTable) renderTable();
    }

    /**
     * 成绩表底部的导入区：模板下载、导出成绩、导入入口，进入预览后换成取消/确认与异常明细。
     * 确认按钮的可用性直接取最新服务端预览的有效性（没有未解决异常行），不看本地红框。
     */
    private void renderImportBar(boolean hasModel, boolean importing) {
        if (gradeBookImportSummaryLabel != null) {
            String summary = importing ? importController.summaryText() : null;
            gradeBookImportSummaryLabel.setText(summary == null ? "" : summary);
        }
        setActive(gradeBookImportSummaryLabel, importing);
        boolean busy = importController.busy();
        if (gradeBookDownloadTemplateButton != null) {
            gradeBookDownloadTemplateButton.setDisable(!hasModel || importing || busy);
        }
        if (gradeBookExportGradesButton != null) {
            gradeBookExportGradesButton.setDisable(!hasModel || importing || busy);
        }
        if (gradeBookImportButton != null) {
            gradeBookImportButton.setDisable(!hasModel || !model.canEdit() || importing || busy);
        }
        setActive(gradeBookImportIssuesButton, importing);
        setActive(gradeBookCancelImportButton, importing);
        setActive(gradeBookConfirmImportButton, importing);
        if (gradeBookImportIssuesButton != null) {
            gradeBookImportIssuesButton.setDisable(!importing);
        }
        if (gradeBookCancelImportButton != null) {
            gradeBookCancelImportButton.setDisable(!importing);
        }
        if (gradeBookConfirmImportButton != null) {
            gradeBookConfirmImportButton.setDisable(!importController.confirmEnabled());
        }
    }

    /**
     * 状态提示：新文本一到达就显示，并在 {@link #FEEDBACK_VISIBLE_DURATION} 之后渐变淡出。
     *
     * <p>只认“有新提示到达”（{@link #feedbackRevision} 前进），不认“这次渲染和上次不一样”：页面上的
     * 任何一次重画（逐格录入、按钮可用性、导入态）都会走到 {@link #render} 里来，按渲染次数重新计时
     * 会让提示永远等不到消退。
     *
     * <p>标签缺失（无工具包的控制器测试）时只保留 {@code feedbackText} 字段本身：它仍然是这条提示的
     * 唯一事实来源，测试照旧读得到。
     */
    private void renderFeedback() {
        if (gradeBookFeedbackLabel == null) return;
        gradeBookFeedbackLabel.setText(feedbackText == null ? "" : feedbackText);
        if (feedbackRevision != renderedFeedbackRevision) {
            renderedFeedbackRevision = feedbackRevision;
            feedbackFaded = false;
            startFeedbackFade();
        }
        setActive(gradeBookFeedbackLabel, feedbackText != null && !feedbackFaded);
    }

    /**
     * 按当前提示重新计时：非空则先原样留 {@link #FEEDBACK_VISIBLE_DURATION}，再
     * {@link #FEEDBACK_FADE_DURATION} 之内淡出；提示被置空则只是取消在跑的那次消退。
     *
     * <p>旧计时一律先取消：新提示到家时，上一次的收尾既不能把它隐藏，也不能把不透明度留成 0。
     * 收尾自身还有一道“我还是当前这次消退吗”的检查，因此即使某个实现会在 {@code stop()} 里同步
     * 触发 {@code onFinished}，也清不掉刚落地的提示。
     */
    private void startFeedbackFade() {
        stopFeedbackFade();
        if (gradeBookFeedbackLabel == null || feedbackText == null) return;
        gradeBookFeedbackLabel.setOpacity(1);
        Timeline fade = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(gradeBookFeedbackLabel.opacityProperty(), 1)),
                new KeyFrame(FEEDBACK_VISIBLE_DURATION,
                        new KeyValue(gradeBookFeedbackLabel.opacityProperty(), 1)),
                new KeyFrame(FEEDBACK_VISIBLE_DURATION.add(FEEDBACK_FADE_DURATION),
                        new KeyValue(gradeBookFeedbackLabel.opacityProperty(), 0)));
        fade.setOnFinished(event -> {
            if (feedbackFade != fade) return;
            feedbackFade = null;
            feedbackFaded = true;
            // 复位不透明度：下一次提示从全不透明开始，不能继承上一轮淡出到 0 的那一帧。
            gradeBookFeedbackLabel.setOpacity(1);
            render(false);
        });
        feedbackFade = fade;
        fade.play();
    }

    /**
     * 取消在跑的消退并解绑。先解绑再停：解绑之后这次消退的收尾不再作数，与
     * {@code stop()} 是否回调 {@code onFinished} 无关。
     */
    private void stopFeedbackFade() {
        Timeline running = feedbackFade;
        feedbackFade = null;
        if (running != null) {
            running.stop();
        }
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
                    // 导入预览期间方案冻结：候选是按当时的启用项与权重算出来的。
                    boolean frozen = importController.importing();
                    if (toggle != null) {
                        toggle.setSelected(enabled);
                        // 只读状态（已提交/已通过）下开关不能再动；禁用项本身仍然可以重新启用。
                        toggle.setDisable(!model.canEdit() || frozen);
                    }
                    if (weight != null) {
                        if (!column.weightText().equals(weight.getText())) {
                            weight.setText(column.weightText());
                        }
                        weight.setDisable(!enabled || !model.canEdit() || frozen);
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

    /** 当前编辑模型（同时是 {@link TeacherGradeImportController.Host} 的实现）：同一张表、同一个模型。 */
    @Override
    public GradeBookEditorModel model() {
        return model;
    }

    /** 导入编排器：测试直接驱动它与真实模型/假传输，验证「同一张表」的预览与取消。 */
    TeacherGradeImportController importController() {
        return importController;
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

    boolean reopening() {
        return reopening;
    }

    boolean dirty() {
        return model != null && model.dirty();
    }

    /** 最近一条状态提示：消退只隐藏标签，不改这个字段（换班/重新进入才清）。 */
    String feedbackText() {
        return feedbackText;
    }

    String errorText() {
        return errorText;
    }

    String pendingSubmitOperationId() {
        return pendingSubmitOperationId;
    }

    String pendingReopenOperationId() {
        return pendingReopenOperationId;
    }

    List<Row> rows() {
        return model == null ? List.of() : model.rows();
    }

    /** 表格当前绑定的行数；节点缺失时与模型行数一致，便于断言渲染结果。 */
    int tableRowCount() {
        return gradeBookTable == null ? rows().size() : gradeBookTable.getItems().size();
    }
}
