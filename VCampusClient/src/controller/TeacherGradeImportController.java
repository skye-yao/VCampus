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
import java.util.concurrent.CancellationException;
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
 * <p>四条最容易做错的规则在这里落地：
 * <ul>
 *   <li><b>取消恢复导入前的编辑副本</b>：{@link TeacherGradeImportController#cancelImport()} 用
 *       {@link GradeBookEditorModel#restore} 整体恢复，{@code dirty} 标志一起恢复；离开上传页
 *       （{@link #cancelOnLeave()}）先取消在途 Future（传输层据此关闭短连接）再恢复。</li>
 *   <li><b>迟到的预览响应被丢弃</b>：每次预览/修订派发都递增 {@code generation}，只有最新一次
 *       的响应对得上号，教师修正期间返回的旧预览绝不覆盖新状态。</li>
 *   <li><b>确认按钮取最新服务端预览的有效性</b>：{@link #confirmEnabled()} 只看最近一次预览里
 *       还有没有**未被明确排除**的异常行（服务端 {@code blocked()} 的同一份事实），不看本地红框
 *       是否被敲掉，也不看会把已排除行算进去的 {@code errorRows}。</li>
 *   <li><b>在途确认不会被偷走</b>：确认只认自己的代际 {@code confirmGeneration}，且在途期间修正/
 *       排除/取消/离开一律被拒绝——见 {@link #confirming} 上写死的不变式。</li>
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
    /** 异常明细弹窗的提示行：异常行已全部解决 / 还要继续修正或排除（见 {@link #hintText()}）。 */
    static final String HINT_SOLVED_TEXT = "异常行已全部解决，可以回到成绩表确认导入。";
    static final String HINT_PENDING_TEXT = "修正单元格或勾选排除后，服务端会重新校验；关闭本窗口不会取消导入。";
    static final String CANCEL_TEXT = "已取消导入，已恢复导入前的编辑内容";
    static final String NOT_IMPORTING_TEXT = "当前没有导入预览";
    static final String NO_ISSUE_CELL_TEXT = "预览期间只有服务端标记异常的单元格可以修改，其它格子请先取消导入";
    static final String READ_ONLY_TEXT = "当前成绩表为只读状态，不能导入";
    static final String SUMMARY_PREFIX = "导入预览：";
    static final String EXPORT_FILENAME = "学生名单.xlsx";
    static final String TEMPLATE_FILENAME = "成绩模板.xlsx";
    /**
     * 成绩录入页的「导出成绩」自己的一份文件名与成功文案。
     *
     * <p>刻意不复用 {@link #EXPORT_FILENAME}／{@link #ROSTER_SUCCESS_TEXT}：那两个常量是教学班
     * 详情页导出名单的交付物，改一个字都等于改了那个页面（导出的是一个班的学生名单，不是成绩）。
     */
    static final String GRADE_EXPORT_FILENAME = "学生成绩.xlsx";
    static final String GRADE_EXPORT_SUCCESS_TEXT = "学生成绩已保存到：";
    static final String GRADE_EXPORT_FAILURE_TEXT = "成绩导出失败，请重试";

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
    /**
     * 页面是否仍在活动。后台线程上的链要读它（{@link #requireStillImporting}），因此是 volatile：
     * 离开上传页的判断必须对后台线程立即可见，否则「检查再派发」的防线可能读到旧值。
     */
    private volatile boolean active;

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
    /**
     * 确认请求是否在途。
     *
     * <p><b>不变式：{@code confirming} 只在「确认请求在途且这次导入仍然作数」时为 true，它永远不会
     * 被留在 true 上。</b>留在 true 的代价是整个页面永久锁死：{@link #confirmEnabled()} 恒为 false
     * （确认按钮永远不可用），{@link #busy()} 恒为 true（保存草稿/提交成绩/重新加载一起被挡住），
     * 唯一的出口只剩「取消导入」。
     *
     * <p><b>确认有自己的代际 {@link #confirmGeneration}，不与 {@link #generation} 共享。</b>上传与
     * 修订每派发一次都会推进 {@code generation}，而确认的完成回调里带着「我派发时的代际」才能落地。
     * 共用一个计数器时，任何一次修订都能把在途确认的完成回调悄悄丢掉（回调开头的
     * {@code current != generation} 直接 return），{@code confirming} 从此再也没人清。
     *
     * <p>能推进 {@code confirmGeneration} 的只有两处：确认自己派发（{@link #confirmImport()}），以及
     * {@link #abandonImport}（取消/离开/页面卸下）。因此让 {@code confirming} 落回 false 的只有三处：
     * 确认完成回调真正落地的那次（成功或失败）、服务同步抛出的那次（同一个 try 的 catch），与
     * {@link #closeImportState()}（{@code abandonImport} 必经）。其余一切会改动导入状态的路径——
     * 修正、排除、取消导入、返回、重新加载——在 {@code confirming} 期间一律拒绝执行（见
     * {@link #correct}、{@link #setExcluded}、{@link #cancelImport} 与
     * {@code TeacherGradeBookController#requestLeave}）。这样「确认在途」这件事要么完成、要么被显式
     * 放弃，不会被第三只手偷走。
     *
     * <p>残余：页面被直接卸下（{@link #release()}，例如关窗）时确认仍在途，服务端可能已经把草稿写成。
     * 那条路不向教师显示任何「已恢复」的文案（页面已经不在），重新进入时 {@code loadBook} 拿到的
     * 就是服务端真实草稿，因此不会出现界面与数据库互相矛盾的说法。
     */
    private boolean confirming;
    private boolean dialogOpen;
    private String feedbackText;

    /**
     * 每次导入/修订派发递增：只有最新一次的响应可以应用，迟到的旧预览直接丢弃。
     * 后台线程上的链读它（{@link #requireStillImporting}），因此是 volatile。
     */
    private volatile long generation;
    /**
     * 确认自己的代际：只由确认派发与 {@link #abandonImport} 推进，因此不可能被修订/上传的派发
     * 顺手推走（见 {@link #confirming} 的注释）。
     */
    private long confirmGeneration;
    /**
     * 修正/排除的状态版本：每次改动递增。派发修订时记下当时的版本，响应落地后若已经更大，
     * 就用刚拿到的 previewRevision 再冲刷一次——连续打字因此不会各自带着同一个基版本撞服务端。
     */
    private long correctionVersion;
    /**
     * 下载各自一条线：回调只在仍是最新一次下载时写提示。离开页面（{@link #release()}、
     * {@link #cancelOnLeave()}）也要推进它：页面会被复用（离开教学班 A、再打开 B 时
     * {@code active} 与宿主都会重新有值），只判「是不是活动页面」拦不住 A 的迟到下载。
     */
    private long downloadGeneration;
    /**
     * 正在传输的短连接 Future：离开上传页时取消它，传输层据此关闭 Socket。
     * volatile 与 {@link #generation}/{@link #active} 同一条理由——后台线程写它（派发上传），
     * FX 线程读它（{@code cancel(true)}），两者之间没有别的 happens-before 边。
     */
    private volatile CompletableFuture<Void> inFlightTransfer;
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
        // 下载也要跟着作废：本控制器会被同一个工作台反复复用，离开页面再打开时 active 与宿主都会
        // 重新有值，只有向前的代际能拦住「A 的模板/名单已保存到 …」被写到 B 的页面上。
        downloadGeneration++;
        active = false;
        dialogOpen = false;
        dialog = null;
    }

    // ------------------------------------------------------------------ 下载：模板、名单导出与成绩导出

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
            reportDownload(saved, failure, TEMPLATE_SUCCESS_TEXT, DOWNLOAD_FAILURE_TEXT);
        }));
    }

    /**
     * 导出名单：与名单列表相同的过滤条件，但服务端取全部结果而不是当前页。这是**教学班详情页**
     * 学生名单 Tab 的导出通道（成绩录入页导出的是成绩，见 {@link #exportGrades}）。详情页自己有反馈区，
     * 所以只在挂了宿主时提示，返回的 Future 始终带着「保存到哪个文件」（用户取消时为 null）。
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
                    reportDownload(saved, failure, ROSTER_SUCCESS_TEXT, DOWNLOAD_FAILURE_TEXT);
                }));
    }

    /**
     * 导出成绩：成绩录入页的入口，服务端取**已保存的草稿**（名单 + 四项成绩 + 总评 + 绩点），
     * 未填写的成绩在文件里是 0。与 {@link #exportRoster} 各走各的动作与文案（成功与失败两句都是
     * 本页自己的常量），但那一条下载通道（选文件 → 覆盖确认 → 票据 → 后台传输）完全共用，
     * 提示照旧进本页的反馈区。
     */
    CompletableFuture<Path> exportGrades(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        long current = ++downloadGeneration;
        return downloadTicketToFile(dialogs, transport, overwriteConfirmation,
                () -> service.requestGradeExport(offeringId), GRADE_EXPORT_FILENAME)
                .whenComplete((saved, failure) -> fxExecutor.accept(() -> {
                    if (current != downloadGeneration) return;
                    reportDownload(saved, failure, GRADE_EXPORT_SUCCESS_TEXT,
                            GRADE_EXPORT_FAILURE_TEXT);
                }));
    }

    /**
     * 下载后的统一提示：取消（null）不提示，成功给保存路径，失败给可重试的说明。
     *
     * <p>{@code failureFallback} 由入口自己给：每个入口说自己那一件事失败了（模板/名单/成绩各一份），
     * 服务端的业务拒绝文案仍然原样优先显示。
     *
     * <p>页面已经卸下（{@link #release()}）时一个界面字段都不写：宿主是刻意保留的（同一个控制器
     * 反复进出工作台），所以「宿主还在」不等于「页面还在」。代际那半句由调用方把关。
     */
    private void reportDownload(Path saved, Throwable failure, String successPrefix,
            String failureFallback) {
        if (host == null || !active) return;
        if (failure != null) {
            host.feedback(failureText(failure, failureFallback));
            return;
        }
        if (saved == null) return;
        host.feedback(successPrefix + saved);
    }

    /**
     * 下载票据到用户选定的文件：<b>FileChooser 与覆盖确认都在 FX 线程</b>（调用方的处理器里），
     * 之后的票据申请与文件传输都在后台。返回保存到的路径，用户取消时为 null。
     *
     * <p>三个下载入口（成绩模板、名单导出、导出成绩）共用这一条路：覆盖策略只有一处实现，
     * 不会一边问「要不要覆盖」另一边默默替换。
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
                .thenCompose(fingerprint -> {
                    requireStillImporting(current);
                    return service.beginGradeUpload(new TeacherFileUploadRequestDTO(
                            draft.getOfferingId(), draft.getExpectedRevision(),
                            fileNameOf(source), fingerprint.length(), fingerprint.sha256()));
                })
                .thenCompose(ticket -> {
                    requireStillImporting(current);
                    // 票据只在这一段链里存在：兑换一次、预览一次，不落到任何字段或提示文案里。
                    inFlightTransfer = transport.upload(ticket, source);
                    return inFlightTransfer.thenApply(ignored -> ticket);
                })
                .thenCompose(ticket -> {
                    requireStillImporting(current);
                    return service.previewGradeImport(
                            new PreviewGradeImportRequestDTO(ticket.getTicket(), draft));
                });
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

    /**
     * 每个 compose 主体在派发下一步之前先确认这次导入还作数。
     *
     * <p>{@link CompletableFuture#cancel} 只让<b>当前</b>那一段以后不再继续，已经在跑的中间段会照常
     * 执行完——所以在中间段里「先检查再派发」才是唯一真正挡住后续网络动作的地方。不这么做的话，
     * 教师在指纹计算或申请票据的往返途中点「返回」，上传仍会开出一条没人在等的短连接，
     * 白白消费掉一次性票据并留下一个孤儿临时文件。
     */
    private void requireStillImporting(long current) {
        if (current != generation || !active) {
            throw new CancellationException("导入已取消");
        }
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
        if (!importing || confirming || host == null || row == null) return;
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
        if (refuseWhileConfirming()) return;
        corrections.computeIfAbsent(rowNumber, ignored -> new LinkedHashMap<>())
                .put(field, text == null ? "" : text.trim());
        correctionVersion++;
        revise();
    }

    /** 明确排除/取消排除一行；已排除的行不再阻止确认导入。 */
    void setExcluded(int rowNumber, boolean excluded) {
        if (!importing) return;
        if (refuseWhileConfirming()) return;
        if (excluded) {
            excludedRows.add(rowNumber);
        } else {
            excludedRows.remove(rowNumber);
        }
        correctionVersion++;
        revise();
    }

    boolean isExcluded(int rowNumber) {
        return excludedRows.contains(rowNumber);
    }

    /**
     * 确认在途期间拒绝一次会改动预览状态的教师手势（修正、排除）。
     *
     * <p>不是为了省一次往返，而是为了让 {@link #confirming} 的不变式成立：一次修订会推进代际并
     * 应用一份新的预览，而确认正锁着它当时看到的那一版（服务端严格要求 {@code expectedPreviewRevision}
     * 相等）。拒绝的理由说给教师听——这句话会显示在成绩表页的反馈区，弹窗里则由
     * {@link Feedback#render()} 的提示行说明。
     *
     * @return true 表示这次手势已经被拒绝，调用方必须原样返回
     */
    private boolean refuseWhileConfirming() {
        if (!confirming) return false;
        if (host != null) host.feedback(CONFIRMING_TEXT);
        return true;
    }

    /**
     * 修订预览：请求里带的是「完整修正状态」（修正集合 + 排除集合），因此取消一处修正或取消排除
     * 同样能如实表达。
     *
     * <p><b>一次只派发一个修订。</b>服务端严格要求 {@code expectedPreviewRevision} 与它当前的预览版本
     * 相等，而版本只有在响应回来时才前进；连续打字如果每次都立刻发一个请求，第二个请求带的就是同一个
     * 基版本（要么被拒、要么把第一次的成功结果挤掉），教师的每一次击键都会因此永久卡死在这条
     * 「预览已更新」上。所以修订在途时只累积修正，等响应落地拿到新的 previewRevision 之后再一次性
     * 冲刷出去——请求数从「每个击键一次」降到「每个在途窗口一次」，而内容始终是那一刻的完整状态。
     */
    private void revise() {
        if (!importing || importToken == null || preview == null) return;
        if (confirming) return;       // 确认在途：一个修订都不派发（调用方已被拒绝，这里是兜底）
        if (revising) return;         // 在途：只累积，响应落地后统一冲刷
        dispatchRevise();
    }

    private void dispatchRevise() {
        long current = ++generation;
        revising = true;
        long dispatchedAt = correctionVersion;
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
                    // 在途期间教师又改了：现在才拿到新的 previewRevision，用这一刻的完整状态冲刷。
                    if (correctionVersion != dispatchedAt) dispatchRevise();
                }));
    }

    // ------------------------------------------------------------------ 确认与取消

    /**
     * 确认按钮是否可用：只看最近一次服务端预览的**有效性**——还有没有未被明确排除的异常行。
     *
     * <p>刻意不用 {@code errorRows}：那是文件里的异常行数，**把教师已经排除的行也算在内**
     * （服务端的口径是「有效 + 异常 = 文件数据行数」，见 {@code TeacherGradeImportService#merge}），
     * 所以它不等于「还没解决的行数」。按它判断按钮的话，教师排除掉未知学生行之后
     * {@code errorRows} 仍然是 1，确认按钮会永远不可用；而服务端的 {@code blocked()} 恰恰允许确认
     * （「请修正或明确排除后再确认导入」）。这里因此复用服务端同一份事实：逐条问题看
     * {@code isExcluded}。
     *
     * <p>响应里根本没有问题列表时**关掉**确认（fail-closed）：那种响应既没告诉我们有多少未解决的
     * 问题，服务端也多半会拒绝，把按钮点亮只会让教师白点一次、拿到一句服务端拒绝文案。
     * （当前服务端一定会带上这个数组，所以这是对畸形响应的兜底，方向选安全的那一边。）
     */
    boolean confirmEnabled() {
        if (!importing || confirming || revising || uploading || preview == null) return false;
        List<GradeImportRowIssueDTO> issues = preview.getIssues();
        return issues != null && issues.stream().noneMatch(issue -> !issue.isExcluded());
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
        // 确认认自己的代际：修订/上传的派发推进的是 generation，与这里无关（见 confirming 的注释）。
        long current = ++confirmGeneration;
        confirming = true;
        feedbackText = CONFIRMING_TEXT;
        host.feedback(feedbackText);
        host.importStateChanged();
        // 弹窗还开着的话立刻重画一次：把「修正/排除」冻上并换成「正在保存」那句提示——
        // 模态窗口挡住成绩表页的反馈区，那半句解释只有在弹窗里才看得见。
        if (dialogOpen && dialog != null) dialog.render();
        CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> pending;
        try {
            pending = service.confirmGradeImport(new ConfirmGradeImportRequestDTO(
                    pendingConfirmOperationId, token, previewRevision, expectedRevision));
        } catch (RuntimeException refused) {
            // 服务同步抛出（而不是返回失败的 Future）也是「确认已经结束」的一种：不变式没有第三个
            // 出口，这里把 confirming 收回来，其余按与异步失败完全相同的口径处理。真实实现在生产里
            // 永远返回 Future（SocketClient.sendAsync 把一切异常都装进 Future），所以这条分支是对
            // 不变式的结构性兜底，由 aSynchronouslyFailingConfirmDoesNotWedgeThePage 钉住。
            confirming = false;
            reportConfirmFailure(refused);
            return;
        }
        pending.whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (current != confirmGeneration) return;
            // 不变式优先于界面写入：只要这次确认仍然作数，confirming 无条件落下；真的被界面之外的
            // 原因卸下了页面（active 为 false），也只是少写一次界面而已。
            confirming = false;
            if (!active) return;
            if (failure != null) {
                // 冲突（版本过期/名单变化）与其它失败一样保留预览与导入前的副本，等教师决定。
                reportConfirmFailure(failure);
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
     * 确认失败的统一收尾：预览与导入前的副本都保留（教师可以重新加载或取消），提示带上冲突前缀与
     * 服务端原因。异步失败与同步抛出走同一条路，两种失败对教师是同一件事。
     */
    private void reportConfirmFailure(Throwable failure) {
        if (host == null) return;
        feedbackText = conflictPrefix(failure) + failureText(failure, CONFIRM_FAILURE_TEXT);
        host.feedback(feedbackText);
        host.importStateChanged();
    }

    /**
     * 取消导入：丢弃服务端预览令牌（尽力而为），恢复导入前的编辑副本（含 dirty 标志），
     * 关闭在途短连接并回到普通编辑。
     *
     * <p><b>确认在途时拒绝取消。</b>确认这条写请求一旦发出就没法收回，而这里能做的只有把本地状态
     * 收回去；真那样做，教师看到的「已取消导入，已恢复导入前的编辑内容」就会说在一份可能已经写入
     * 的草稿上（界面与数据库互相矛盾，只有下一次保存的版本冲突才暴露）。等一个不能取消的写请求
     * 回来更诚实，所以这里什么都不动，只说明原因。
     */
    void cancelImport() {
        if (!importing) {
            if (host != null) host.feedback(NOT_IMPORTING_TEXT);
            return;
        }
        if (confirming) {
            if (host != null) host.feedback(CONFIRMING_TEXT);
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
     *
     * <p>下载代际无条件前进：在途的可能只有一次模板/名单下载（它不记在
     * {@code inFlightTransfer}/{@code inFlightChain} 里），下面那个提前返回正好会漏掉它。
     */
    void cancelOnLeave() {
        downloadGeneration++;
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
        // 在途确认就此被**显式放弃**：代际前进让它的完成回调不再落地（哪怕服务端随后真的写了草稿，
        // 也不再往已经收回去的界面上写），而 closeImportState 保证 confirming 一定落回 false。
        confirmGeneration++;
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

    /**
     * 异常学生姓名（姓名优先，缺姓名回退学号，再回退行号）；已排除的行不再算「待处理」但仍列出。
     *
     * <p>响应里没有带问题列表时不谎称「没有异常行」——总记录那半句里的异常条数可能仍然大于 0，
     * 那会自相矛盾；这时只说明明细不可用（{@link #confirmEnabled()} 也已经关掉确认）。
     */
    static String abnormalNames(GradeImportPreviewDTO preview) {
        if (preview == null || preview.getIssues() == null) return "异常明细不可用";
        if (preview.getIssues().isEmpty()) return "没有异常行";
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

    /**
     * 服务端问题落到表格格子上的映射：学号 → 这一行，字段 → 哪一个成绩格。
     *
     * <p>响应没带问题列表时没有任何红框可画：返回空映射，而不是在这里抛 NPE 把整条预览链截断
     * （同形响应的展示口径见 {@link #abnormalNames(GradeImportPreviewDTO)}）。
     */
    private Map<String, Map<GradeComponentCodeDTO, String>> issueCells(GradeImportPreviewDTO next) {
        Map<String, Map<GradeComponentCodeDTO, String>> byEnrollment = new LinkedHashMap<>();
        if (host == null || host.model() == null || next == null || next.getIssues() == null) {
            return byEnrollment;
        }
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
                // 还没绑定宿主时（FXML 的 initialize() 会先 render 一次）沿用原来那句：标签非空
                // 本身就是「render 真的跑过」的证据，GUI 冒烟用例据此断言。
                feedbackHintLabel.setText(owner == null
                        ? TeacherGradeImportController.HINT_SOLVED_TEXT : owner.hintText());
            }
            if (feedbackIssueList == null) return;
            feedbackIssueList.getChildren().clear();
            // 响应没带问题列表（畸形/老响应）时只渲染空列表：这里抛异常会被 showFeedbackDialog
            // 的兜底吞掉，教师看到的是「弹窗没打开」而不是「弹出去了但内容不对」。
            if (preview == null || preview.getIssues() == null) return;
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

            // 确认在途时这些控件冻结：控制器那边本来就会拒绝，这里只是别让教师白点一次。
            boolean frozen = owner != null && owner.confirming();

            CheckBox exclude = new CheckBox("排除该行");
            exclude.setSelected(issue.isExcluded());
            exclude.setDisable(frozen);
            exclude.selectedProperty().addListener((observable, previous, next) -> {
                if (owner != null) owner.setExcluded(issue.getRowNumber(), Boolean.TRUE.equals(next));
            });
            row.getChildren().add(exclude);

            TextField correction = new TextField(issue.getRawValue());
            correction.setPrefWidth(90.0);
            correction.setPromptText("修正为");
            correction.setDisable(frozen);
            Button apply = new Button("修正");
            apply.setDisable(frozen);
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

    /** 确认是否在途：离开保护据此拒绝离开（见 {@link #confirming} 的不变式）。 */
    boolean confirming() {
        return confirming;
    }

    /**
     * 异常明细弹窗的提示行：确认在途时明说「正在保存」，与
     * {@link #refuseWhileConfirming()} 的拒绝保持同一句话（弹窗是模态的，成绩表页的反馈区被它挡住）。
     */
    String hintText() {
        if (confirming) return CONFIRMING_TEXT;
        return confirmEnabled() ? HINT_SOLVED_TEXT : HINT_PENDING_TEXT;
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
