package controller;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 成绩更正表单（设计 §8）：从成绩表里某一位学生打开，确认他的四项原始分数、填写拟修改后的分数与
 * 更正原因，确认后由服务端以本班最后一次<b>已通过</b>的批次为来源建立一份更正草稿。
 *
 * <p><b>更正永远以整班为单位提交。</b>表单只是把教师想改的那名学生带到眼前，它建立的草稿仍然是
 * 整个教学班的新版本；拟修改的分数由打开方写回成绩编辑表，随后照常走「保存草稿 / 提交成绩」，本题
 * 不新增第二条写入通路，也绝不单独写一行已发布的成绩。新增学生的补录走同一份版本，没有例外。
 *
 * <p>拟修改分数是<b>原文</b>，与成绩表里的格子同一口径：这里不做分数合法性判断，非法值原样进入编辑
 * 模型并在那里标红、挡住保存——表单只负责把原文交给模型，绝不把它折成 0 或悄悄丢掉。
 *
 * <p>原因必填且不超过 {@link #MAX_REASON_LENGTH} 字符（服务端同样拒绝空白与超长）：空原因在本地就
 * 被挡下，一个请求都不发。超长输入按上界截断，与服务端 500 字符的列宽一致。
 *
 * <p>取消不创建任何草稿：确认之前不发请求、不生成 operationId（它只在真正提交的那一刻生成），
 * 关闭之后的任何在途响应都不再写控件。失败保留填写内容并复用同一个 operationId（响应丢失时的
 * 幂等重放）；任何一处填写变化都换新 id，避免用旧 id 提交新内容被服务端按摘要冲突永久拒绝。
 *
 * <p>所有节点都可能为 {@code null}：控制器测试按仓库约定在无工具包、无 FXML 节点的环境下运行，
 * 状态机与纯文本函数因此与控件渲染分开。
 */
/** 教师为已通过成绩发起更正的 JavaFX 对话框控制器。 */
public final class TeacherGradeCorrectionDialogController {
    /** 弹窗 Stage 的标题；GUI 冒烟测试靠它在窗口列表里认出弹窗，因此不做成动态标题。 */
    static final String TITLE = "申请更正成绩";

    static final String REASON_REQUIRED_TEXT = "请填写更正原因";
    static final String SUBMITTING_TEXT = "正在建立更正草稿...";
    static final String SUBMIT_FAILURE_TEXT = "更正草稿建立失败，请重试";
    /** 与服务端一致的原因上界：超出部分在界面层截断，不靠服务端 400 来发现。 */
    static final int MAX_REASON_LENGTH = 500;

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;
    private Consumer<CorrectionOutcome> onConfirmed = outcome -> { };

    private CorrectionTarget target;
    private final Map<GradeComponentCodeDTO, String> proposed =
            new LinkedHashMap<>();
    private String reason = "";
    private boolean submitting;
    private boolean closed;
    private String errorText;
    private String operationId;
    /** 渲染时同步控件值会触发文本监听器；同步期间跳过它们，避免把程序化回填当成用户输入。 */
    private boolean syncing;

    @FXML private Node dialogRoot;
    @FXML private Label dialogTitleLabel;
    @FXML private Label studentLine;
    @FXML private Label correctionHintLabel;
    @FXML private Label dailyOriginalLabel;
    @FXML private TextField dailyField;
    @FXML private Label midtermOriginalLabel;
    @FXML private TextField midtermField;
    @FXML private Label experimentOriginalLabel;
    @FXML private TextField experimentField;
    @FXML private Label finaltermOriginalLabel;
    @FXML private TextField finaltermField;
    @FXML private TextArea reasonArea;
    @FXML private Label errorLabel;
    @FXML private Button submitButton;
    @FXML private Button cancelButton;

    public TeacherGradeCorrectionDialogController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherGradeCorrectionDialogController(TeacherCourseService service,
            Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        wireProposed(dailyField, GradeComponentCodeDTO.DAILY);
        wireProposed(midtermField, GradeComponentCodeDTO.MIDTERM);
        wireProposed(experimentField, GradeComponentCodeDTO.EXPERIMENT);
        wireProposed(finaltermField, GradeComponentCodeDTO.FINALTERM);
        if (reasonArea != null) {
            reasonArea.textProperty().addListener((observable, previous, next) -> {
                if (syncing) return;
                setReason(next);
            });
        }
        render();
    }

    private void wireProposed(TextField field, GradeComponentCodeDTO code) {
        if (field == null) return;
        field.textProperty().addListener((observable, previous, next) -> {
            if (syncing) return;
            setProposed(code, next);
        });
    }

    // ------------------------------------------------------------------ 打开

    /** 由成绩表注入：要更正的学生、他在当前批次里的四项原始分数，以及这次版本变更的来源。 */
    void prepare(CorrectionTarget value) {
        this.target = value;
        proposed.clear();
        if (value != null) {
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                proposed.put(code, value.originals().getOrDefault(code, ""));
            }
        }
        this.reason = "";
        this.submitting = false;
        this.closed = false;
        this.errorText = null;
        this.operationId = null;
        render();
    }

    /** 确认成功的一方：收到新的更正草稿与教师想写进成绩编辑表的拟修改原文。 */
    void setOnConfirmed(Consumer<CorrectionOutcome> onConfirmed) {
        this.onConfirmed = onConfirmed == null ? outcome -> { } : onConfirmed;
    }

    // ------------------------------------------------------------------ 填写

    /**
     * 修改一个组成的拟修改分数。空串是合法填写（等于“这一格没有分”），因此这里既不做本地校验也不换
     * 措辞——合法性由成绩编辑表在写请求前判定，非法值在那里标红。
     */
    void setProposed(GradeComponentCodeDTO code, String text) {
        if (code == null) return;
        String next = text == null ? "" : text.trim();
        if (next.equals(proposed.getOrDefault(code, ""))) return;
        proposed.put(code, next);
        // 拟修改分数是请求摘要的一部分：变了就必须换新的 operationId（见类注释）。
        operationId = null;
        render();
    }

    /** 更正原因：超长按上界截断；变化同样作废上一次的 operationId。 */
    void setReason(String value) {
        String next = value == null ? "" : value;
        if (next.length() > MAX_REASON_LENGTH) next = next.substring(0, MAX_REASON_LENGTH);
        if (next.equals(reason)) return;
        reason = next;
        operationId = null;
        render();
    }

    String proposedText(GradeComponentCodeDTO code) {
        return proposed.getOrDefault(code, "");
    }

    String reason() {
        return reason;
    }

    // ------------------------------------------------------------------ 确认

    /**
     * 只有拿到了目标学生、填了原因、且不在提交中才允许确认；没有目标就没有可提交的内容，
     * 空原因在本地就被挡住，不会有任何服务端往返。
     */
    boolean canSubmit() {
        return !closed && !submitting && target != null && !reason.isBlank();
    }

    void submit() {
        if (!canSubmit()) {
            if (!closed && !submitting) {
                errorText = REASON_REQUIRED_TEXT;
                render();
            }
            return;
        }
        if (operationId == null) operationId = UUID.randomUUID().toString();
        StartGradeRevisionRequestDTO request = new StartGradeRevisionRequestDTO(operationId,
                target.offeringId(), target.sourceSubmissionId(), target.expectedRevision(),
                reason.trim());
        submitting = true;
        errorText = null;
        render();
        service.beginGradeCorrection(request).whenComplete((result, failure) ->
                fxExecutor.accept(() -> {
                    if (closed) return;
                    submitting = false;
                    if (failure != null) {
                        errorText = failureText(failure, SUBMIT_FAILURE_TEXT);
                        render();
                        return;
                    }
                    onConfirmed.accept(outcomeOf(result));
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
        hideWindow();
    }

    private CorrectionOutcome outcomeOf(TeacherOperationResultDTO<TeacherGradeBookDTO> result) {
        return new CorrectionOutcome(result == null ? null : result.getValue(),
                target == null ? null : target.enrollmentId(), Map.copyOf(proposed));
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        if (dialogTitleLabel != null) dialogTitleLabel.setText(TITLE);
        if (studentLine != null) studentLine.setText(studentLineText());
        if (correctionHintLabel != null) correctionHintLabel.setText(hintText());
        syncing = true;
        try {
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                TextField field = fieldOf(code);
                String text = proposedText(code);
                if (field != null && !text.equals(field.getText())) field.setText(text);
            }
            if (reasonArea != null && !reason.equals(reasonArea.getText())) {
                reasonArea.setText(reason);
            }
        } finally {
            syncing = false;
        }
        if (dailyOriginalLabel != null) dailyOriginalLabel.setText(originalLine(
                GradeComponentCodeDTO.DAILY));
        if (midtermOriginalLabel != null) midtermOriginalLabel.setText(originalLine(
                GradeComponentCodeDTO.MIDTERM));
        if (experimentOriginalLabel != null) experimentOriginalLabel.setText(originalLine(
                GradeComponentCodeDTO.EXPERIMENT));
        if (finaltermOriginalLabel != null) finaltermOriginalLabel.setText(originalLine(
                GradeComponentCodeDTO.FINALTERM));
        if (errorLabel != null) errorLabel.setText(errorText == null ? "" : errorText);
        setActive(errorLabel, errorText != null);
        if (submitButton != null) submitButton.setDisable(!canSubmit());
        if (cancelButton != null) cancelButton.setDisable(false);
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------------ 纯文本

    /** 姓名与学号：教师据此确认自己改的是哪一名学生。 */
    String studentLineText() {
        if (target == null) return "";
        return "姓名：" + orDash(target.studentName()) + "　学号：" + orDash(target.studentUid());
    }

    /**
     * 一句说明：这次更正建立的是<b>整个教学班</b>的新版本，拟修改的分数仍需保存/提交才生效。
     * 不承诺任何超出这套机制的话。
     */
    static String hintText() {
        return "确认后为整个教学班建立一个新的更正版本；你在这里填写的拟修改分数会写回成绩表，"
                + "仍需保存草稿或提交成绩才生效。";
    }

    /** 某个组成的原分数行：未录入显示占位符，绝不显示 0 或 null。 */
    String originalLine(GradeComponentCodeDTO code) {
        if (target == null || code == null) return "";
        return componentLabel(code) + "　原分数 "
                + orDash(target.originals().getOrDefault(code, ""));
    }

    static String componentLabel(GradeComponentCodeDTO code) {
        if (code == null) return "未知组成";
        return switch (code) {
            case DAILY -> "平时";
            case MIDTERM -> "期中";
            case EXPERIMENT -> "实验";
            case FINALTERM -> "期末";
        };
    }

    private static String orDash(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    private TextField fieldOf(GradeComponentCodeDTO code) {
        return switch (code) {
            case DAILY -> dailyField;
            case MIDTERM -> midtermField;
            case EXPERIMENT -> experimentField;
            case FINALTERM -> finaltermField;
        };
    }

    /** 业务拒绝原样显示服务端的话；其余只给可重试的通用文案。 */
    static String failureText(Throwable failure, String fallback) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TeacherCourseServiceException failureInfo) {
            MessageCode code = failureInfo.getCode();
            String message = failureInfo.getMessage();
            if (code != MessageCode.ERROR && message != null && !message.isBlank()) {
                return message;
            }
        }
        return fallback;
    }

    // -------------------------------------------------------------- 测试访问器

    boolean closed() {
        return closed;
    }

    boolean submitting() {
        return submitting;
    }

    String errorText() {
        return errorText;
    }

    String operationId() {
        return operationId;
    }

    // -------------------------------------------------------------------- 值对象

    /**
     * 更正目标：一名学生、他在当前批次里的四项原始分数，以及这次版本变更的来源。
     *
     * @param offeringId 教学班
     * @param sourceSubmissionId 来源（最后一次已通过）批次；服务端在同一把锁内核对它是最新批次
     * @param expectedRevision 客户端读到的草稿版本；并发下的第二次点击只会拿到冲突
     * @param originals 四项分数的原文，缺项为空串
     */
    record CorrectionTarget(String offeringId, String sourceSubmissionId, long expectedRevision,
            String enrollmentId, String studentUid, String studentName,
            Map<GradeComponentCodeDTO, String> originals) {
    }

    /**
     * 确认结果：服务端建立的更正草稿，以及要写回成绩编辑表的拟修改原文。
     * 草稿为 {@code null} 表示响应没有携带成绩表，调用方保持原编辑内容不变。
     */
    record CorrectionOutcome(TeacherGradeBookDTO book, String enrollmentId,
            Map<GradeComponentCodeDTO, String> proposed) {
    }
}
