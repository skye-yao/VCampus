package controller;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import dto.course.teacher.ConfirmGradeImportRequestDTO;
import dto.course.teacher.GradeImportCorrectionDTO;
import dto.course.teacher.GradeImportPreviewDTO;
import dto.course.teacher.GradeImportRowIssueDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.PreviewGradeImportRequestDTO;
import dto.course.teacher.ReviseGradeImportRequestDTO;
import dto.course.teacher.TeacherFileTicketDTO;
import dto.course.teacher.TeacherFileUploadRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import model.course.teacher.GradeBookEditorModel;
import model.course.teacher.GradeBookEditorModel.Row;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import service.TeacherFileTransport;
import util.AlertUtil;

/**
 * Excel 导入的编排（设计 §9）：票据 → 短连接传输 → 服务端解析预览 → 同表预览 → 修正/排除 → 确认。
 *
 * <p><b>不复制第二张成绩表。</b>预览就是把服务端返回的候选合并进原 {@link GradeBookEditorModel}：
 * 行对象不换、表格不重建，教师看到的是自己那张表被导入内容刷新后的样子；红框同样长在这张表上
 * （服务端报在哪一格，就在哪一格显示那句话），异常行则通过错误列表修正或明确排除。
 *
 * <p>线程约定：{@link FileDialogs} 全部在 FX 线程调用（文件选择、覆盖确认），文件指纹、上传、
 * 下载、预览与修订的等待都在后台线程，回到 FX 线程才触碰控件——与仓库里其它控制器一致，由调用方
 * 注入的 {@code fxExecutor} 决定“回到 FX 线程”的方式。
 *
 * <p>三条最容易做错的规则在这里落地：
 * <ul>
 *   <li><b>取消恢复导入前的编辑副本</b>：{@link TeacherGradeImportController#cancelImport()} 用
 *       {@link GradeBookEditorModel#restore} 整体恢复，{@code dirty} 标志一起恢复；离开上传页
 *       （{@link #cancelOnLeave()}）先取消在途 Future（传输层据此关闭短连接）再恢复。</li>
 *   <li><b>迟到的预览响应被丢弃</b>：每次预览/修订派发都递增 {@code generation}，只有最新一次
 *       的响应对得上号，教师修正期间返回的旧预览绝不覆盖新状态。</li>
 *   <li><b>确认按钮取最新服务端预览的有效性</b>：{@link #confirmEnabled()} 只看最近一次预览的
 *       {@code errorRows}，不看本地红框是否被敲掉。</li>
 * </ul>
 *
 * <p>确认导入只调用 {@code confirmGradeImport}：它是保存草稿，不是提交审批，本类里没有
 * {@code submitGradeBook} 这条路径。{@code importToken} 是内部句柄，任何提示文案里都不出现它。
 *
 * <p>所有节点都可能为 {@code null}，弹窗与文件选择都可注入，因此控制器测试不需要 JavaFX 工具包。
 */
public final class TeacherGradeImportController {

    /** 下载目标已存在时的覆盖确认（在 FileChooser 返回之后、传输开始之前在 FX 线程提问）。 */
    static final String OVERWRITE_TEXT = "目标文件已存在，确定覆盖吗？";
    static final String TEMPLATE_SUCCESS_TEXT = "成绩模板已保存到：";
    static final String ROSTER_SUCCESS_TEXT = "学生名单已保存到：";
    static final String DOWNLOAD_FAILURE_TEXT = "文件下载失败，请重试";
    static final String UPLOADING_TEXT = "正在上传并解析文件...";
    static final String UPLOAD_FAILURE_TEXT = "文件上传或解析失败，请重试";
    static final String REVISING_TEXT = "正在按修正重新校验...";
    static final String REVISE_FAILURE_TEXT = "修正未生效，请检查数字格式后重试";
    static final String CONFIRMING_TEXT = "正在保存导入结果...";
    static final String CONFIRM_SUCCESS_TEXT = "导入已保存到成绩草稿，可以继续手工修改";
    static final String CONFIRM_FAILURE_TEXT = "导入保存失败，已保留预览，请重试";
    static final String CONFIRM_CONFLICT_PREFIX = "成绩表已被其他操作更新，导入预览已保留；请重新加载后再导入：";
    static final String CONFIRM_BLOCKED_TEXT = "还有未解决的异常行，请先修正或明确排除它们";
    static final String CANCEL_TEXT = "已取消导入，已恢复导入前的编辑内容";
    static final String NOT_IMPORTING_TEXT = "当前没有导入预览";
    static final String NO_ISSUE_CELL_TEXT = "预览期间只有服务端标记异常的单元格可以修改，其它格子请先取消导入";
    static final String READ_ONLY_TEXT = "当前成绩表为只读状态，不能导入";
    static final String SUMMARY_PREFIX = "导入预览：";
    static final String EXPORT_FILENAME = "学生名单.xlsx";
    static final String TEMPLATE_FILENAME = "成绩模板.xlsx";

    /** 文件指纹与解析等待的后台线程池；FX 线程只负责选文件与触碰控件。 */
    private static final ExecutorService BACKGROUND = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "teacher-grade-import");
        thread.setDaemon(true);
        return thread;
    });

    /** 文件选择端口：上传选源文件，下载选目标文件。返回 null 表示用户取消；两个方法都在 FX 线程。 */
    interface FileDialogs {
        Path chooseUploadSource();

        Path chooseSaveTarget(String suggestedFileName);
    }

    /** 导入控制器对成绩编辑页的最小依赖：同一张表、同一个模型、同一套按钮状态。 */
    interface Host {
        GradeBookEditorModel model();

        /** 导入流程要说给教师听的那句话；null 表示清空。 */
        void feedback(String text);

        /** 模型内容变了（预览合并或恢复）：重画同一张表，不新建表格。 */
        void gradeBookChanged();

        /** 进入/退出导入态：冻结方案切换与保存/提交，切换底部按钮。 */
        void importStateChanged();

        /** 确认成功：用服务端返回的草稿整体替换编辑内容，回到普通编辑。 */
        void replaceWithServerDraft(TeacherGradeBookDTO book);
    }

    private final TeacherCourseService service;
    private final TeacherFileTransport transport;
    private final Consumer<Runnable> fxExecutor;
    private final FileDialogs dialogs;
    /** 下载目标已存在时的覆盖确认（消息 → 是否覆盖）。它与「离开未保存修改」是两回事，各有各的措辞。 */
    private final Function<String, Boolean> overwriteConfirmation;
    /** 反馈弹窗的 owner 窗口（取不到时为 null，弹窗仍可打开，只是不锁定父窗口）。 */
    private final Supplier<Window> ownerWindow;

    private Host host;
    private boolean active;

    private GradeImportPreviewDTO preview;
    /** 导入前的编辑副本；取消/离开时用它整体恢复（含 dirty）。 */
    private GradeBookEditorModel.Snapshot beforeImportSnapshot;
    private GradeBookContentDTO baseDraft;
    /** 内部句柄：只用于后续请求，绝不进任何提示文案。 */
    private String importToken;
    private final Map<Integer, Map<String, String>> corrections = new LinkedHashMap<>();
    private final Set<Integer> excludedRows = new LinkedHashSet<>();
    private String pendingConfirmOperationId;

    private boolean importing;
    private boolean uploading;
    private boolean revising;
    private boolean confirming;
    private boolean dialogOpen;
    private String feedbackText;

    /** 每次导入/修订派发递增：只有最新一次的响应可以应用，迟到的旧预览直接丢弃。 */
    private long generation;
    /** 下载各自一条线：回调只在仍是最新一次下载时写提示。 */
    private long downloadGeneration;
    /** 正在传输的短连接 Future：离开上传页时取消它，传输层据此关闭 Socket。 */
    private CompletableFuture<Void> inFlightTransfer;
    /** 正在等待的整条链（上传 → 预览）：取消它可以让后续步骤不再派发。 */
    private CompletableFuture<?> inFlightChain;

    private Consumer<GradeImportPreviewDTO> feedbackPresenter = this::showFeedbackDialog;
    private Feedback dialog;

    TeacherGradeImportController(TeacherCourseService service, TeacherFileTransport transport,
            Consumer<Runnable> fxExecutor, FileDialogs dialogs,
            Function<String, Boolean> overwriteConfirmation) {
        this(service, transport, fxExecutor, dialogs, overwriteConfirmation, () -> null);
    }

    TeacherGradeImportController(TeacherCourseService service, TeacherFileTransport transport,
            Consumer<Runnable> fxExecutor, FileDialogs dialogs,
            Function<String, Boolean> overwriteConfirmation, Supplier<Window> ownerWindow) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.transport = Objects.requireNonNull(transport, "File transport is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
        this.dialogs = Objects.requireNonNull(dialogs, "File dialogs are required");
        this.overwriteConfirmation = Objects.requireNonNull(overwriteConfirmation,
                "Overwrite confirmation is required");
        this.ownerWindow = ownerWindow == null ? () -> null : ownerWindow;
    }

    /** 生产路径的覆盖确认：标题与「未保存的成绩」分开，教师不会把覆盖问题当成丢修改的警告。 */
    static boolean confirmOverwrite(String message) {
        return AlertUtil.showConfirm("覆盖文件", message) == ButtonType.OK;
    }

    /** 生产实现：真实的 JavaFX 文件选择器（选择器必须由 FX 线程调用，见 {@link FileDialogs}）。 */
    static FileDialogs fxDialogs(Supplier<Window> owner) {
        return new FxFileDialogs(owner);
    }

    void attach(Host host) {
        this.host = Objects.requireNonNull(host, "Host is required");
        this.active = true;
    }

    /**
     * 页面被卸下：取消在途传输（关闭短连接）并丢弃预览状态，此后不再触碰控件。
     *
     * <p>宿主保留不置空：工作台里的成绩页是同一个控制器实例反复进出（打开教学班 → 返回列表 →
     * 再打开），下一次 {@link #attach} 只是把页面重新标成活动，不需要重建整套导入编排。
     */
    void release() {
        abandonImport(false);
        active = false;
        dialogOpen = false;
        dialog = null;
    }

    // ------------------------------------------------------------------ 下载：模板与名单导出

    /**
     * 下载成绩模板：选目标文件 → 覆盖确认 → 申请票据 → 后台传输。
     *
     * <p>覆盖确认刻意放在 FileChooser 之后、传输之前：传输层只负责原子替换，是否覆盖由这里决定，
     * 用户可以看到自己选的到底是哪个文件之后再回答。
     */
    void downloadTemplate(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        long current = ++downloadGeneration;
        CompletableFuture<Path> download = downloadTicketToFile(dialogs, transport,
                overwriteConfirmation,
                () -> service.requestGradeTemplate(offeringId), TEMPLATE_FILENAME);
        download.whenComplete((saved, failure) -> fxExecutor.accept(() -> {
            if (current != downloadGeneration) return;
            reportDownload(saved, failure, TEMPLATE_SUCCESS_TEXT);
        }));
    }

    /**
     * 导出名单：与名单列表相同的过滤条件，但服务端取全部结果而不是当前页。成绩表页挂在本控制器
     * 上，提示直接进反馈区；详情页自己有反馈区，所以只在挂了宿主时提示，返回的 Future 始终带着
     * 「保存到哪个文件」（用户取消时为 null）。
     */
    CompletableFuture<Path> exportRoster(String offeringId, String query, Integer enrollmentStatus) {
        if (offeringId == null || offeringId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        long current = ++downloadGeneration;
        return downloadTicketToFile(dialogs, transport, overwriteConfirmation,
                () -> service.requestRosterExport(offeringId, query, enrollmentStatus),
                EXPORT_FILENAME).whenComplete((saved, failure) -> fxExecutor.accept(() -> {
                    if (current != downloadGeneration) return;
                    reportDownload(saved, failure, ROSTER_SUCCESS_TEXT);
                }));
    }

    /** 下载后的统一提示：取消（null）不提示，成功给保存路径，失败给可重试的说明。 */
    private void reportDownload(Path saved, Throwable failure, String successPrefix) {
        if (host == null) return;
        if (failure != null) {
            host.feedback(failureText(failure, DOWNLOAD_FAILURE_TEXT));
            return;
        }
        if (saved == null) return;
        host.feedback(successPrefix + saved);
    }

    /**
     * 下载票据到用户选定的文件：<b>FileChooser 与覆盖确认都在 FX 线程</b>（调用方的处理器里），
     * 之后的票据申请与文件传输都在后台。返回保存到的路径，用户取消时为 null。
     *
     * <p>两个下载入口（成绩模板、名单导出）共用这一条路：覆盖策略只有一处实现，不会一边问
     * 「要不要覆盖」另一边默默替换。
     */
    static CompletableFuture<Path> downloadTicketToFile(FileDialogs dialogs,
            TeacherFileTransport transport, Function<String, Boolean> confirmation,
            Supplier<CompletableFuture<TeacherFileTicketDTO>> ticket, String suggestedFileName) {
        Path target = dialogs.chooseSaveTarget(suggestedFileName);
        if (target == null) return CompletableFuture.completedFuture(null);
        if (Files.exists(target) && !confirmation.apply(OVERWRITE_TEXT)) {
            return CompletableFuture.completedFuture(null);
        }
        // 传输显式切到后台线程：票据即使已经就绪（业务响应早于点击到达），文件字节也绝不在
        // FX 线程上读写。
        return ticket.get()
                .thenComposeAsync(value -> transport.download(value, target), BACKGROUND)
                .thenApply(ignored -> target);
    }

    // ------------------------------------------------------------------ 上传 → 预览

    /** 导入入口：选文件 → 后台算指纹并上传 → 服务端解析 → 同表预览。 */
    void startImport() {
        if (!active || importing || uploading || host == null) return;
        GradeBookEditorModel model = host.model();
        if (model == null || !model.canEdit()) {
            host.feedback(READ_ONLY_TEXT);
            return;
        }
        // 上传的是「当前编辑副本」：本地非法输入先修好，绝不把非法文本当成导入基线发出去。
        String blocked = model.saveBlockReason();
        if (blocked != null) {
            host.feedback(blocked);
            return;
        }
        Path source = dialogs.chooseUploadSource();
        if (source == null) return;
        GradeBookContentDTO draft;
        try {
            draft = model.content();
        } catch (IllegalStateException refused) {
            host.feedback(refused.getMessage());
            return;
        }
        this.baseDraft = draft;
        // 导入前的编辑副本在这里留底：取消或离开时整体恢复（值 + dirty）。
        this.beforeImportSnapshot = model.snapshot();

        long current = ++generation;
        uploading = true;
        feedbackText = UPLOADING_TEXT;
        host.feedback(feedbackText);
        host.importStateChanged();
        CompletableFuture<GradeImportPreviewDTO> chain = CompletableFuture
                .supplyAsync(() -> fingerprintOf(source), BACKGROUND)
                .thenCompose(fingerprint -> service.beginGradeUpload(new TeacherFileUploadRequestDTO(
                        draft.getOfferingId(), draft.getExpectedRevision(),
                        fileNameOf(source), fingerprint.length(), fingerprint.sha256())))
                .thenCompose(ticket -> {
                    // 票据只在这一段链里存在：兑换一次、预览一次，不落到任何字段或提示文案里。
                    inFlightTransfer = transport.upload(ticket, source);
                    return inFlightTransfer.thenApply(ignored -> ticket);
                })
                .thenCompose(ticket -> service.previewGradeImport(
                        new PreviewGradeImportRequestDTO(ticket.getTicket(), draft)));
        inFlightChain = chain;
        chain.whenComplete((next, failure) -> fxExecutor.accept(() -> {
            if (current != generation || !active) return;
            uploading = false;
            inFlightTransfer = null;
            inFlightChain = null;
            if (failure != null) {
                // 上传失败时模型一个字段都没改过，因此没有需要恢复的副本，也没有预览要取消。
                beforeImportSnapshot = null;
                baseDraft = null;
                feedbackText = failureText(failure, UPLOAD_FAILURE_TEXT);
                host.feedback(feedbackText);
                host.importStateChanged();
                return;
            }
            clearCorrections();
            applyPreview(next, true);
        }));
    }

    private void applyPreview(GradeImportPreviewDTO next, boolean firstPreview) {
        if (host == null) return;
        GradeBookEditorModel model = host.model();
        if (model == null) return;
        preview = next;
        importToken = next.getImportToken();
        importing = true;
        model.clearImportIssues();
        model.applyCandidate(next.getCandidate());
        model.markImportIssues(issueCells(next));
        feedbackText = previewFeedback(next);
        host.gradeBookChanged();
        host.feedback(feedbackText);
        host.importStateChanged();
        if (firstPreview) feedbackPresenter.accept(next);
    }

    /** 预览期间这张表的提示：总记录/有效/异常。服务端令牌不在任何文案里。 */
    static String previewFeedback(GradeImportPreviewDTO preview) {
        return SUMMARY_PREFIX + countsText(preview) + "　" + abnormalNames(preview);
    }

    // ------------------------------------------------------------------ 修正与排除

    /** 这一格是不是服务端在这份预览里报了问题的格子：预览期间只有它们可以修改。 */
    boolean isCorrectableCell(Row row, GradeComponentCodeDTO code) {
        return issueRowNumber(row, code) != null;
    }

    /** 表格里键入的修正：原文先进模型（教师看得见自己敲的字），红框仍由服务端问题维持。 */
    void correctionTyped(Row row, GradeComponentCodeDTO code, String text) {
        if (!importing || host == null || row == null) return;
        Integer rowNumber = issueRowNumber(row, code);
        if (rowNumber == null) {
            host.feedback(NO_ISSUE_CELL_TEXT);
            return;
        }
        host.model().setScore(row.enrollmentId(), code, text);
        host.gradeBookChanged();
        correct(rowNumber, fieldOf(code), text);
    }

    /**
     * 一处修正（表格与错误列表共用）：记录到「本次修订后的完整修正状态」并立即调用 revise。
     * 空文本是一次有意义的修正——它表示「这一格没有值」，与文件里的空白同义（保留原草稿值），
     * 绝不是 0 分；撤销修正的做法是把原文再写回去，而不是从这里抹掉一条。本地不做任何合法性
     * 判断，校验规则只有服务端一份。
     */
    void correct(int rowNumber, String field, String text) {
        if (!importing || field == null) return;
        corrections.computeIfAbsent(rowNumber, ignored -> new LinkedHashMap<>())
                .put(field, text == null ? "" : text.trim());
        revise();
    }

    /** 明确排除/取消排除一行；已排除的行不再阻止确认导入。 */
    void setExcluded(int rowNumber, boolean excluded) {
        if (!importing) return;
        if (excluded) {
            excludedRows.add(rowNumber);
        } else {
            excludedRows.remove(rowNumber);
        }
        revise();
    }

    boolean isExcluded(int rowNumber) {
        return excludedRows.contains(rowNumber);
    }

    /**
     * 修订预览：请求里带的是「完整修正状态」（修正集合 + 排除集合），因此取消一处修正或取消排除
     * 同样能如实表达。发出请求前的旧预览从此作废——响应回来时若已经不是最新一次派发，直接丢弃。
     */
    private void revise() {
        if (!importing || importToken == null || preview == null) return;
        long current = ++generation;
        revising = true;
        feedbackText = REVISING_TEXT;
        host.feedback(feedbackText);
        host.importStateChanged();
        service.reviseGradeImport(new ReviseGradeImportRequestDTO(importToken,
                preview.getPreviewRevision(), correctionList(), List.copyOf(excludedRows)))
                .whenComplete((next, failure) -> fxExecutor.accept(() -> {
                    if (current != generation || !active) return;
                    revising = false;
                    if (failure != null) {
                        feedbackText = failureText(failure, REVISE_FAILURE_TEXT);
                        host.feedback(feedbackText);
                        host.importStateChanged();
                        return;
                    }
                    // 后面的预览不是「首次」：不重开反馈弹窗，同表预览原地更新。
                    applyPreview(next, false);
                    if (dialogOpen && dialog != null) dialog.render();
                }));
    }

    // ------------------------------------------------------------------ 确认与取消

    /** 确认按钮是否可用：只看最近一次服务端预览的有效性（没有未解决异常行），不看本地状态。 */
    boolean confirmEnabled() {
        return importing && !confirming && !revising && !uploading && preview != null
                && preview.getErrorRows() == 0;
    }

    /**
     * 确认导入：只调用 {@code confirmGradeImport}（把候选写成草稿），<b>绝不触发提交审批</b>。
     * 幂等 ID 在一次导入流程里保持不变，失败重试仍复用同一个，响应丢失时服务端重放已保存的结果。
     */
    void confirmImport() {
        if (!active || host == null || !importing || confirming || preview == null) return;
        if (!confirmEnabled()) {
            host.feedback(CONFIRM_BLOCKED_TEXT);
            return;
        }
        if (pendingConfirmOperationId == null) {
            pendingConfirmOperationId = UUID.randomUUID().toString();
        }
        String token = importToken;
        int previewRevision = preview.getPreviewRevision();
        long expectedRevision = baseDraft == null ? 0 : baseDraft.getExpectedRevision();
        long current = ++generation;
        confirming = true;
        feedbackText = CONFIRMING_TEXT;
        host.feedback(feedbackText);
        host.importStateChanged();
        service.confirmGradeImport(new ConfirmGradeImportRequestDTO(pendingConfirmOperationId, token,
                previewRevision, expectedRevision))
                .whenComplete((result, failure) -> fxExecutor.accept(() -> {
                    if (current != generation || !active) return;
                    confirming = false;
                    if (failure != null) {
                        // 冲突（版本过期/名单变化）与其它失败一样保留预览与导入前的副本，等教师决定。
                        feedbackText = conflictPrefix(failure)
                                + failureText(failure, CONFIRM_FAILURE_TEXT);
                        host.feedback(feedbackText);
                        host.importStateChanged();
                        return;
                    }
                    TeacherGradeBookDTO book = result == null ? null : result.getValue();
                    closeImportState();
                    if (book != null) {
                        host.replaceWithServerDraft(book);
                    } else {
                        host.gradeBookChanged();
                    }
                    feedbackText = CONFIRM_SUCCESS_TEXT;
                    host.feedback(feedbackText);
                    host.importStateChanged();
                }));
    }

    /**
     * 取消导入：丢弃服务端预览令牌（尽力而为），恢复导入前的编辑副本（含 dirty 标志），
     * 关闭在途短连接并回到普通编辑。
     */
    void cancelImport() {
        if (!importing) {
            if (host != null) host.feedback(NOT_IMPORTING_TEXT);
            return;
        }
        abandonImport(true);
        if (host != null) {
            feedbackText = CANCEL_TEXT;
            host.feedback(feedbackText);
            host.importStateChanged();
        }
    }

    /**
     * 离开上传页：取消在途 Future（传输层据此关闭短连接）并恢复导入前的编辑副本。
     * 恢复之后 {@code dirty} 是导入前的真实状态，离开保护因此按教师原本的内容提问；
     * 提示也换掉，页面不会留在「导入预览：…」上而实际已经退出导入态。
     */
    void cancelOnLeave() {
        if (!importing && inFlightTransfer == null && inFlightChain == null) return;
        boolean abandonedPreview = importing;
        abandonImport(true);
        if (host != null) {
            if (abandonedPreview) feedbackText = CANCEL_TEXT;
            host.feedback(feedbackText);
            host.importStateChanged();
        }
    }

    /**
     * 丢弃导入状态：取消在途传输、清空预览与修正，并按需把模型整体恢复成导入前的副本。
     * 服务端令牌的丢弃是尽力而为的——本地状态无论如何都要回到干净状态。
     */
    private void abandonImport(boolean restoreModel) {
        generation++;
        CompletableFuture<Void> transfer = inFlightTransfer;
        inFlightTransfer = null;
        if (transfer != null) transfer.cancel(true);
        CompletableFuture<?> chain = inFlightChain;
        inFlightChain = null;
        if (chain != null) chain.cancel(true);
        String token = importToken;
        boolean wasImporting = importing;
        GradeBookEditorModel.Snapshot snapshot = beforeImportSnapshot;
        closeImportState();
        if (restoreModel && wasImporting && snapshot != null && host != null
                && host.model() != null) {
            host.model().restore(snapshot);
            host.gradeBookChanged();
        }
        if (token != null) {
            try {
                service.cancelGradeImport(token);
            } catch (RuntimeException ignored) {
                // 取消服务端令牌失败不影响本地恢复：预览本来就没有写过任何正式成绩。
            }
        }
    }

    /** 清空导入状态（不动模型）：预览、令牌、修正与排除、在途标志与幂等 ID。 */
    private void closeImportState() {
        preview = null;
        importToken = null;
        baseDraft = null;
        beforeImportSnapshot = null;
        clearCorrections();
        importing = false;
        uploading = false;
        revising = false;
        confirming = false;
        pendingConfirmOperationId = null;
        inFlightTransfer = null;
        inFlightChain = null;
    }

    private void clearCorrections() {
        corrections.clear();
        excludedRows.clear();
    }

    private List<GradeImportCorrectionDTO> correctionList() {
        List<GradeImportCorrectionDTO> values = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> entry : corrections.entrySet()) {
            values.add(new GradeImportCorrectionDTO(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(values);
    }

    // ------------------------------------------------------------------ 错误列表

    /** 反馈弹窗的摘要：总记录/有效/异常 + 异常学生姓名。 */
    static String summaryText(GradeImportPreviewDTO preview) {
        if (preview == null) return "";
        return countsText(preview) + "　" + abnormalNames(preview);
    }

    static String countsText(GradeImportPreviewDTO preview) {
        if (preview == null) return "";
        return "总记录 " + preview.getTotalRows() + " 条　有效 " + preview.getValidRows()
                + " 条　异常 " + preview.getErrorRows() + " 条";
    }

    /** 异常学生姓名（姓名优先，缺姓名回退学号，再回退行号）；已排除的行不再算「待处理」但仍列出。 */
    static String abnormalNames(GradeImportPreviewDTO preview) {
        if (preview == null || preview.getIssues().isEmpty()) return "没有异常行";
        List<String> names = new ArrayList<>();
        for (GradeImportRowIssueDTO issue : preview.getIssues()) {
            String name = issueLabel(issue);
            if (!names.contains(name)) names.add(name);
        }
        return "异常学生：" + String.join("、", names);
    }

    static String issueLabel(GradeImportRowIssueDTO issue) {
        if (issue.getStudentName() != null && !issue.getStudentName().isBlank()) {
            return issue.getStudentName();
        }
        if (issue.getStudentUid() != null && !issue.getStudentUid().isBlank()) {
            return issue.getStudentUid();
        }
        return "第 " + issue.getRowNumber() + " 行";
    }

    /** 一行异常的可读描述：第几行、谁、哪一格、原文、说明（原文与说明都来自服务端）。 */
    static String issueLine(GradeImportRowIssueDTO issue) {
        StringBuilder line = new StringBuilder("第 ").append(issue.getRowNumber()).append(" 行　")
                .append(issueLabel(issue)).append("　").append(fieldLabel(issue.getField()));
        if (!issue.getRawValue().isEmpty()) {
            line.append("＝").append(issue.getRawValue());
        }
        if (issue.getMessage() != null && !issue.getMessage().isBlank()) {
            line.append("　").append(issue.getMessage());
        }
        if (issue.isExcluded()) line.append("（已排除）");
        return line.toString();
    }

    static String fieldLabel(String field) {
        if (field == null) return "未知字段";
        return switch (field) {
            case GradeImportRowIssueDTO.FIELD_STUDENT_UID -> "学号";
            case GradeImportRowIssueDTO.FIELD_STUDENT_NAME -> "姓名";
            case GradeImportRowIssueDTO.FIELD_DAILY_SCORE -> "平时成绩";
            case GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE -> "期中成绩";
            case GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE -> "实验成绩";
            case GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE -> "期末成绩";
            default -> field;
        };
    }

    static String fieldOf(GradeComponentCodeDTO code) {
        if (code == null) return null;
        return switch (code) {
            case DAILY -> GradeImportRowIssueDTO.FIELD_DAILY_SCORE;
            case MIDTERM -> GradeImportRowIssueDTO.FIELD_MIDTERM_SCORE;
            case EXPERIMENT -> GradeImportRowIssueDTO.FIELD_EXPERIMENT_SCORE;
            case FINALTERM -> GradeImportRowIssueDTO.FIELD_FINALTERM_SCORE;
        };
    }

    private static GradeComponentCodeDTO componentOf(String field) {
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            if (fieldOf(code).equals(field)) return code;
        }
        return null;
    }

    /** 这一行这一格对应的 Excel 数据行号；不是异常格时返回 null。 */
    private Integer issueRowNumber(Row row, GradeComponentCodeDTO code) {
        if (preview == null || row == null || code == null) return null;
        String field = fieldOf(code);
        for (GradeImportRowIssueDTO issue : preview.getIssues()) {
            if (!issue.isExcluded() && field.equals(issue.getField())
                    && row.studentUid().equals(issue.getStudentUid())) {
                return issue.getRowNumber();
            }
        }
        return null;
    }

    /** 服务端问题落到表格格子上的映射：学号 → 这一行，字段 → 哪一个成绩格。 */
    private Map<String, Map<GradeComponentCodeDTO, String>> issueCells(GradeImportPreviewDTO next) {
        Map<String, Map<GradeComponentCodeDTO, String>> byEnrollment = new LinkedHashMap<>();
        if (host == null || host.model() == null || next == null) return byEnrollment;
        for (GradeImportRowIssueDTO issue : next.getIssues()) {
            if (issue.isExcluded()) continue;
            GradeComponentCodeDTO code = componentOf(issue.getField());
            if (code == null) continue;
            for (Row row : host.model().rows()) {
                if (row.studentUid().equals(issue.getStudentUid())) {
                    byEnrollment.computeIfAbsent(row.enrollmentId(),
                            ignored -> new LinkedHashMap<>()).putIfAbsent(code, issue.getMessage());
                    break;
                }
            }
        }
        return byEnrollment;
    }

    // ------------------------------------------------------------------ 反馈弹窗

    /**
     * 反馈弹窗：显示总记录/有效/异常与异常姓名，并允许逐行修正或明确排除。关闭弹窗只收起它，
     * 同表预览与预览状态都保留（不是取消导入）。
     */
    private void showFeedbackDialog(GradeImportPreviewDTO next) {
        if (dialogOpen) {
            if (dialog != null) dialog.render();
            return;
        }
        Window owner = ownerWindow.get();
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/resources/fxml/TeacherGradeImportFeedback.fxml"));
            Parent root = loader.load();
            Feedback controller = loader.getController();
            controller.bind(this);
            Stage stage = new Stage();
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) stage.initOwner(owner);
            stage.setTitle(Feedback.TITLE);
            stage.setScene(new Scene(root));
            stage.setOnHidden(event -> {
                dialogOpen = false;
                dialog = null;
            });
            dialog = controller;
            dialogOpen = true;
            controller.render();
            stage.show();
        } catch (IOException | RuntimeException | LinkageError failure) {
            // 弹窗打不开不能吞掉预览：同表预览与底部摘要照常工作——异常明细是补充信息，
            // 不是导入流程的前置条件（无工具包的环境里 JavaFX 控件连类都初始化不了）。
            dialogOpen = false;
            dialog = null;
        }
    }

    void setFeedbackPresenter(Consumer<GradeImportPreviewDTO> presenter) {
        this.feedbackPresenter = presenter == null ? value -> { } : presenter;
    }

    /** 重新打开异常明细：关闭弹窗只是收起它，同表预览与导入状态都还在。 */
    void showIssues() {
        if (!importing || preview == null) {
            if (host != null) host.feedback(NOT_IMPORTING_TEXT);
            return;
        }
        feedbackPresenter.accept(preview);
    }

    /** 弹窗需要的能力：读取最新预览并回写修正/排除。 */
    GradeImportPreviewDTO preview() {
        return preview;
    }

    /**
     * 反馈弹窗的控制器：只做渲染与转发，状态机全部在 {@link TeacherGradeImportController} 里，
     * 因此表格与弹窗两条纠错路径收敛到同一套 revise 逻辑。
     */
    public static final class Feedback {
        /** 弹窗 Stage 标题。 */
        static final String TITLE = "导入异常明细";

        @FXML private VBox feedbackDialogRoot;
        @FXML private Label feedbackSummaryLabel;
        @FXML private Label feedbackHintLabel;
        @FXML private VBox feedbackIssueList;
        @FXML private Button feedbackCloseButton;

        private TeacherGradeImportController owner;

        void bind(TeacherGradeImportController owner) {
            this.owner = owner;
        }

        @FXML
        public void initialize() {
            render();
        }

        @FXML
        void handleClose(Event event) {
            if (feedbackDialogRoot != null && feedbackDialogRoot.getScene() != null
                    && feedbackDialogRoot.getScene().getWindow() instanceof Stage stage) {
                stage.close();
            }
        }

        /** 重画摘要与异常行：每次都从最新预览生成，绝不保留上一份的内容。 */
        void render() {
            GradeImportPreviewDTO preview = owner == null ? null : owner.preview();
            if (feedbackSummaryLabel != null) {
                feedbackSummaryLabel.setText(
                        preview == null ? "" : TeacherGradeImportController.summaryText(preview));
            }
            if (feedbackHintLabel != null) {
                feedbackHintLabel.setText(owner == null || owner.confirmEnabled()
                        ? "异常行已全部解决，可以回到成绩表确认导入。"
                        : "修正单元格或勾选排除后，服务端会重新校验；关闭本窗口不会取消导入。");
            }
            if (feedbackIssueList == null) return;
            feedbackIssueList.getChildren().clear();
            if (preview == null) return;
            for (GradeImportRowIssueDTO issue : preview.getIssues()) {
                feedbackIssueList.getChildren().add(issueRow(issue));
            }
        }

        /** 一条异常行：说明 + 排除开关 + 「修正为」输入与按钮。 */
        private Node issueRow(GradeImportRowIssueDTO issue) {
            HBox row = new HBox(8.0);
            row.setAlignment(Pos.CENTER_LEFT);
            Label line = new Label(TeacherGradeImportController.issueLine(issue));
            line.setWrapText(true);
            line.setMaxWidth(400.0);
            row.getChildren().add(line);

            CheckBox exclude = new CheckBox("排除该行");
            exclude.setSelected(issue.isExcluded());
            exclude.selectedProperty().addListener((observable, previous, next) -> {
                if (owner != null) owner.setExcluded(issue.getRowNumber(), Boolean.TRUE.equals(next));
            });
            row.getChildren().add(exclude);

            TextField correction = new TextField(issue.getRawValue());
            correction.setPrefWidth(90.0);
            correction.setPromptText("修正为");
            Button apply = new Button("修正");
            apply.setOnAction(event -> {
                if (owner != null) {
                    owner.correct(issue.getRowNumber(), issue.getField(), correction.getText());
                }
            });
            row.getChildren().addAll(correction, apply);
            return row;
        }
    }

    /** 生产环境的文件选择器：全部在 FX 线程调用。 */
    private static final class FxFileDialogs implements FileDialogs {
        private final Supplier<Window> owner;

        private FxFileDialogs(Supplier<Window> owner) {
            this.owner = owner == null ? () -> null : owner;
        }

        @Override
        public Path chooseUploadSource() {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("选择要导入的成绩文件");
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Excel 工作簿 (*.xlsx)", "*.xlsx"));
            java.io.File file = chooser.showOpenDialog(owner.get());
            return file == null ? null : file.toPath();
        }

        @Override
        public Path chooseSaveTarget(String suggestedFileName) {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("保存到文件");
            chooser.setInitialFileName(suggestedFileName);
            chooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Excel 工作簿 (*.xlsx)", "*.xlsx"));
            java.io.File file = chooser.showSaveDialog(owner.get());
            return file == null ? null : file.toPath();
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 上传前的本地指纹：长度 + SHA-256，在后台算完才申请票据。 */
    record FileFingerprint(long length, String sha256) {
    }

    static FileFingerprint fingerprintOf(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long length = 0;
            try (InputStream source = Files.newInputStream(file)) {
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                        length += read;
                    }
                }
            }
            return new FileFingerprint(length, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException failure) {
            throw new CompletionException(failure);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("运行环境缺少 SHA-256", impossible);
        }
    }

    static String fileNameOf(Path file) {
        Path name = file == null ? null : file.getFileName();
        return name == null ? "" : name.toString();
    }

    /** 业务拒绝原样显示服务端的话；其余给可重试的通用文案，内部细节不当成用户提示。 */
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

    /** 冲突说明：服务端带回最新成绩表版本，让教师知道重新加载后会拿到哪一版。 */
    private static String conflictPrefix(Throwable failure) {
        Throwable cause = rootCause(failure);
        if (cause instanceof TeacherCourseServiceException failureInfo
                && failureInfo.getCode() == MessageCode.CONFLICT) {
            TeacherGradeBookDTO latest = failureInfo.getLatestGradeBook();
            if (latest != null) {
                return CONFIRM_CONFLICT_PREFIX + "（服务端版本 v" + latest.getRevision() + "）";
            }
            return CONFIRM_CONFLICT_PREFIX;
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

    boolean importing() {
        return importing;
    }

    boolean busy() {
        return uploading || revising || confirming;
    }

    boolean active() {
        return active;
    }

    String feedbackText() {
        return feedbackText;
    }

    String summaryText() {
        return preview == null ? null : SUMMARY_PREFIX + countsText(preview);
    }

    int correctionCount() {
        return corrections.size();
    }

    Set<Integer> excludedRows() {
        return Set.copyOf(excludedRows);
    }

    String importToken() {
        return importToken;
    }

    CompletableFuture<?> inFlightFuture() {
        return inFlightChain;
    }
}
