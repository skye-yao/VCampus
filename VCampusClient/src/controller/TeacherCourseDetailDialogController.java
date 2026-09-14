package controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import util.AlertUtil;
import util.FXMLUtil;

/**
 * 课次详情弹窗（设计 §3）：一个课次的完整信息，加上权威教学班快照里的人数。
 *
 * <p>持有的是调用方交来的不可变 {@link TeacherScheduleEntryDTO} 与 {@link TeacherScheduleWeekDTO}
 * 引用，展示字段一律原样渲染：课程编号、课程名、授课教师、日期/星期/节次、地点、周次，调课时再补上
 * 完整的原安排、调整后安排与原因（这三段文案由服务端生成，界面绝不按 {@code dayOfWeek} 重新拼一套）。
 * 教学班与人数来自 {@code service.getOffering(offeringId)} 的权威快照——人数不从周表数据推算；
 * 加载中显示占位，失败显示重试并保留已经显示出来的条目字段。
 *
 * <p>“申请调课”按课次自己的能力位启用：{@code canRequestAdjustment} 为 false（已有生效调课）时禁用
 * 并说明原因；可用时打开独立的调课表单弹窗，提交成功后在本弹窗里显示内联提示。
 *
 * <p>关闭语义：{@link #dispose()} 置 {@code closed} 并递增 generation，弹窗被关掉后（用户关闭、
 * 点“查看教学班”或 {@code Stage.setOnHidden}）在途的教学班快照回调一律直接返回，绝不写已经关闭的
 * 控件。所有节点都可能为 {@code null}，控制器测试因此在无工具包的环境下也能跑完整流程。
 */
public final class TeacherCourseDetailDialogController {
    /** 弹窗 Stage 的标题；GUI 冒烟测试靠它在窗口列表里认出弹窗，因此不做成动态标题。 */
    static final String TITLE = "课程详情";
    /** 已有生效调课的课次本期不再次申请，入口禁用并说明原因。 */
    static final String ADJUSTMENT_BLOCKED_TEXT = "该课次已有生效调课，本期不再接受新的调课申请";
    /** 提交成功后显示在弹窗里的内联提示（不弹模态框，冒烟测试可无人值守继续）。 */
    static final String ADJUSTMENT_SUBMITTED_TEXT = "调课申请已提交，可在“我的申请”里查看处理进度";
    /** 调课表单的资源路径；标题由 {@link TeacherAdjustmentDialogController#TITLE} 固定。 */
    static final String ADJUSTMENT_VIEW = "/resources/fxml/TeacherAdjustmentDialog.fxml";
    static final String SNAPSHOT_LOADING_TEXT = "教学班：加载中...";
    static final String SNAPSHOT_PLACEHOLDER_TEXT = "教学班：—";
    static final String SNAPSHOT_FAILURE_TEXT = "教学班信息加载失败，请重试";

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private Consumer<String> openOffering = offeringId -> { };
    private Consumer<TeacherScheduleEntryDTO> adjustmentOpener = this::openAdjustmentDialog;
    private boolean adjustmentSubmitted;

    private TeacherScheduleEntryDTO entry;
    private TeacherScheduleWeekDTO week;
    private TeacherOfferingDTO offering;
    private boolean loadingSnapshot;
    private String snapshotErrorText;
    private boolean closed;
    private long generation;

    private List<String> lines = List.of();
    private List<String> adjustmentLines = List.of();
    private List<String> snapshotLines = List.of();

    @FXML private Node dialogRoot;
    @FXML private Label dialogTitleLabel;
    @FXML private Label courseCodeLine;
    @FXML private Label courseNameLine;
    @FXML private Label teacherLine;
    @FXML private Label timeLine;
    @FXML private Label locationLine;
    @FXML private Label weekLine;
    @FXML private VBox adjustmentBox;
    @FXML private Label originalScheduleLine;
    @FXML private Label adjustedScheduleLine;
    @FXML private Label adjustmentReasonLine;
    @FXML private Label offeringLine;
    @FXML private Label rosterLine;
    @FXML private Label rosterErrorLabel;
    @FXML private Button rosterRetryButton;
    @FXML private Label adjustmentHintLabel;
    @FXML private Button requestAdjustmentButton;
    @FXML private Button openOfferingButton;
    @FXML private Button closeButton;

    public TeacherCourseDetailDialogController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherCourseDetailDialogController(TeacherCourseService service,
            Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        render();
    }

    /** 由课表页在打开弹窗后调用：把这一条课次与它所在的周交给弹窗，然后加载权威教学班快照。 */
    public void prepare(TeacherScheduleEntryDTO entry, TeacherScheduleWeekDTO week) {
        this.entry = entry;
        this.week = week;
        this.offering = null;
        this.loadingSnapshot = false;
        this.snapshotErrorText = null;
        this.adjustmentSubmitted = false;
        this.closed = false;
        this.generation++;
        render();
        loadSnapshot();
    }

    /** 接住工作台的教学班导航（查看教学班 → {@code showOffering(offeringId)}）。 */
    void setOpenOffering(Consumer<String> openOffering) {
        this.openOffering = openOffering == null ? offeringId -> { } : openOffering;
    }

    /** 测试注入点：默认打开真实的调课表单弹窗。 */
    void setAdjustmentOpener(Consumer<TeacherScheduleEntryDTO> adjustmentOpener) {
        this.adjustmentOpener = adjustmentOpener == null ? this::openAdjustmentDialog
                : adjustmentOpener;
    }

    void loadSnapshot() {
        if (closed || entry == null || entry.getOfferingId() == null) return;
        long current = ++generation;
        loadingSnapshot = true;
        snapshotErrorText = null;
        render();
        service.getOffering(entry.getOfferingId()).whenComplete((value, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrent(current)) return;
                    loadingSnapshot = false;
                    if (failure != null) {
                        // 保留已经显示出来的条目字段与上一次的快照，只提示可以重试。
                        snapshotErrorText = SNAPSHOT_FAILURE_TEXT;
                        render();
                        return;
                    }
                    offering = value == null ? null : value.getOffering();
                    snapshotErrorText = null;
                    render();
                }));
    }

    @FXML
    void retry(Event event) {
        loadSnapshot();
    }

    /** 查看教学班：先关掉弹窗，再把 offeringId 交给工作台的导航。 */
    @FXML
    void handleOpenOffering(Event event) {
        if (closed || entry == null || entry.getOfferingId() == null) return;
        String offeringId = entry.getOfferingId();
        dispose();
        openOffering.accept(offeringId);
    }

    /**
     * 申请调课入口：只有当前课次可以申请时可用（已有生效调课的课次禁用并说明原因）；
     * 打开独立的调课表单弹窗，提交成功后由表单回调把内联提示写回本弹窗。
     */
    @FXML
    void handleRequestAdjustment(Event event) {
        if (closed || entry == null || !entry.isCanRequestAdjustment()) return;
        adjustmentOpener.accept(entry);
    }

    /**
     * 用独立 {@code WINDOW_MODAL} Stage 打开调课表单。表单持有同一个教师课程服务实例
     * （生产路径上是共享单例，冒烟测试里是 Mock），提交成功后只更新本弹窗的内联提示。
     */
    private void openAdjustmentDialog(TeacherScheduleEntryDTO value) {
        FXMLLoader loader = FXMLUtil.getLoader(ADJUSTMENT_VIEW);
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            AlertUtil.showError("打开失败", "无法打开调课表单窗口：" + errorMessage(failure));
            return;
        }
        TeacherAdjustmentDialogController dialog = loader.getController();
        dialog.setOnSubmitted(application -> {
            adjustmentSubmitted = true;
            render();
        });
        dialog.prepare(value);

        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        Window owner = dialogRoot == null || dialogRoot.getScene() == null
                ? null : dialogRoot.getScene().getWindow();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(TeacherAdjustmentDialogController.TITLE);
        stage.setScene(new Scene(root));
        stage.setOnHidden(event -> dialog.dispose());
        stage.show();
    }

    @FXML
    void handleClose(Event event) {
        dispose();
    }

    /** 关闭弹窗：置 closed 并递增 generation，此后任何在途响应都不再写控件。 */
    void dispose() {
        if (closed) return;
        closed = true;
        generation++;
        hideWindow();
    }

    private boolean isCurrent(long current) {
        return !closed && current == generation;
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (dialogTitleLabel != null) dialogTitleLabel.setText(TITLE);

        List<String> entryLines = new ArrayList<>();
        entryLines.add(setText(courseCodeLine, courseCodeText(entry)));
        entryLines.add(setText(courseNameLine, courseNameText(entry)));
        entryLines.add(setText(teacherLine, teacherText(entry)));
        entryLines.add(setText(timeLine, timeText(entry)));
        entryLines.add(setText(locationLine, locationText(entry)));
        entryLines.add(setText(weekLine, weekText(entry, week)));
        lines = List.copyOf(entryLines);

        List<String> adjustment = new ArrayList<>();
        boolean adjusted = entry != null && entry.getDisplayKind() != null
                && ScheduleDisplayKindDTO.NORMAL != entry.getDisplayKind();
        if (adjusted) {
            adjustment.add(setText(originalScheduleLine, originalText(entry)));
            adjustment.add(setText(adjustedScheduleLine, adjustedText(entry)));
            adjustment.add(setText(adjustmentReasonLine, reasonText(entry)));
        }
        adjustmentLines = List.copyOf(adjustment);
        setActive(adjustmentBox, adjusted);

        renderSnapshot();

        String hint = adjustmentHintText();
        if (adjustmentHintLabel != null) {
            adjustmentHintLabel.setText(hint == null ? "" : hint);
        }
        setActive(adjustmentHintLabel, hint != null);
        if (requestAdjustmentButton != null) {
            requestAdjustmentButton.setDisable(entry == null || !entry.isCanRequestAdjustment()
                    || adjustmentSubmitted);
        }
        if (openOfferingButton != null) {
            openOfferingButton.setDisable(entry == null || entry.getOfferingId() == null);
        }
    }

    /**
     * 调课入口的说明文案：提交过后显示成功提示（入口同时禁用，同一课次不再重复申请）；
     * 已有生效调课的课次显示不能申请的原因；其余情况没有提示。
     */
    private String adjustmentHintText() {
        if (adjustmentSubmitted) return ADJUSTMENT_SUBMITTED_TEXT;
        if (entry != null && !entry.isCanRequestAdjustment()) return ADJUSTMENT_BLOCKED_TEXT;
        return null;
    }

    private void renderSnapshot() {
        List<String> snapshot = new ArrayList<>();
        if (loadingSnapshot) {
            snapshot.add(setText(offeringLine, SNAPSHOT_LOADING_TEXT));
            if (rosterLine != null) rosterLine.setText("");
        } else if (offering != null) {
            snapshot.add(setText(offeringLine, offeringText(offering)));
            snapshot.add(setText(rosterLine, countText(offering)));
        } else {
            snapshot.add(setText(offeringLine, SNAPSHOT_PLACEHOLDER_TEXT));
            if (rosterLine != null) rosterLine.setText("");
        }
        snapshotLines = List.copyOf(snapshot);

        setActive(rosterLine, !loadingSnapshot && offering != null);
        if (rosterErrorLabel != null) {
            rosterErrorLabel.setText(snapshotErrorText == null ? "" : snapshotErrorText);
        }
        setActive(rosterErrorLabel, snapshotErrorText != null);
        setActive(rosterRetryButton, snapshotErrorText != null);
    }

    /** 写标签并把写进去的文本回传，便于同时断言“界面写了什么”与“写了哪些行”。 */
    private static String setText(Label label, String text) {
        if (label != null) label.setText(text);
        return text;
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ---------------------------------------------------------------- 纯文本

    static String courseCodeText(TeacherScheduleEntryDTO entry) {
        return "课程编号：" + orDash(entry == null ? null : entry.getCourseCode());
    }

    static String courseNameText(TeacherScheduleEntryDTO entry) {
        return "课程名称：" + orDash(entry == null ? null : entry.getCourseName());
    }

    /** 授课教师原样使用 DTO 的字符串（如 {@code 主教师, 助教}）。 */
    static String teacherText(TeacherScheduleEntryDTO entry) {
        return "授课教师：" + orDash(entry == null ? null : entry.getTeacher());
    }

    static String locationText(TeacherScheduleEntryDTO entry) {
        return "上课地点：" + orDash(entry == null ? null : entry.getLocation());
    }

    /** 日期 + 星期 + 节次取自本课次自己的字段，与调课文案无关。 */
    static String timeText(TeacherScheduleEntryDTO entry) {
        if (entry == null) return "上课时间：—";
        return "上课时间：" + orDash(entry.getLocalDate()) + " "
                + TeacherScheduleController.weekdayText(entry.getDayOfWeek())
                + " 第 " + entry.getStartPeriod() + "-" + entry.getEndPeriod() + " 节";
    }

    static String weekText(TeacherScheduleEntryDTO entry, TeacherScheduleWeekDTO week) {
        if (entry == null) return "周次：—";
        String text = "周次：第 " + entry.getWeek() + " 周";
        return week == null ? text
                : text + "（" + week.getMinWeek() + "-" + week.getMaxWeek() + "）";
    }

    /** 原安排、调整后安排与原因都由服务端生成，界面原样显示。 */
    static String originalText(TeacherScheduleEntryDTO entry) {
        return "原安排：" + orDash(entry == null ? null : entry.getOriginalScheduleText());
    }

    static String adjustedText(TeacherScheduleEntryDTO entry) {
        return "调整后：" + orDash(entry == null ? null : entry.getAdjustedScheduleText());
    }

    static String reasonText(TeacherScheduleEntryDTO entry) {
        return "调课原因：" + orDash(entry == null ? null : entry.getAdjustmentReason());
    }

    static String offeringText(TeacherOfferingDTO offering) {
        return "教学班：" + orDash(offering.getOfferingCode()) + "　"
                + orDash(offering.getOfferingName());
    }

    /** 人数只来自教学班详情快照，绝不用周表数据推算。 */
    static String countText(TeacherOfferingDTO offering) {
        return "教学班人数：" + offering.getEnrolledCount() + " / " + offering.getCapacity();
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String errorMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "未知错误" : failure.getMessage();
    }

    // -------------------------------------------------------------- 测试访问器

    boolean closed() {
        return closed;
    }

    boolean loadingSnapshot() {
        return loadingSnapshot;
    }

    String snapshotErrorText() {
        return snapshotErrorText;
    }

    /** 已经渲染到条目字段上的六行文本，顺序与 FXML 里的标签一致。 */
    List<String> lines() {
        return lines;
    }

    /** 调课时的三行原文；普通课次为空列表，整块也被隐藏。 */
    List<String> adjustmentLines() {
        return adjustmentLines;
    }

    /** 已经渲染到教学班快照上的行（教学班与人数）。 */
    List<String> snapshotLines() {
        return snapshotLines;
    }
}
