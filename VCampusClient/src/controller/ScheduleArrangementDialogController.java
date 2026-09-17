package controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.StringConverter;
import model.course.admin.AdminOfferingView;
import model.course.admin.ScheduleArrangementView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 排课对话框：FXML 负责稳定骨架，控制器负责生成数据相关的教学安排卡片与动态时间段行。
 *
 * 服务端权威状态（方案、安排、冲突、版本）只保存在控制器字段中，既不写入 JavaFX Node，
 * 也不写入 {@link ScheduleSlotEditor}。所有异步结果都带 generation 或关闭标记，
 * 过期响应不得覆盖更新的对话框状态。
 */
public final class ScheduleArrangementDialogController {
    static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";

    private static final String DRAFT = "DRAFT";
    private static final String READY = "READY";
    private static final String PUBLISHED = "PUBLISHED";

    private static final String SAVE_KEY = "schedule:save";
    private static final String PUBLISH_KEY = "schedule:publish";

    private static final String TEACHER_RESOURCE = "teacher";
    private static final String CLASSROOM_RESOURCE = "classroom";
    private enum ReadTarget { RESOURCES, PLAN, ARRANGEMENTS, PREVIEW }

    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;

    private final Set<String> pendingWrites = new HashSet<>();
    private record WriteAttempt(List<?> intent, String operationId,
            Function<String, CompletableFuture<?>> action) { }
    private final Map<String, WriteAttempt> retryWrites = new HashMap<>();
    private final Map<String, List<Consumer<Boolean>>> writeControls = new HashMap<>();
    private final Set<String> cardWriteKeys = new HashSet<>();

    private AdminOfferingView offering;
    private SchedulePlanDTO plan;
    private List<ScheduleResourceDTO> teacherResources = List.of();
    private List<ScheduleResourceDTO> classroomResources = List.of();
    private List<ScheduleArrangementView> arrangements = List.of();

    private final List<ScheduleSlotEditor> slotEditors = new ArrayList<>();
    private ScheduleArrangementView editingArrangement;
    private String teacherUid;
    private String assistantUid;
    private String classroomId;
    private int startWeek = 1;
    private int endWeek = 16;

    private List<ScheduleConflictDTO> conflicts = List.of();
    private boolean previewCurrent;
    private boolean previewPending;
    private boolean previewAfterReload;
    private long previewGeneration;
    private boolean forceIntent;
    private String overrideReason;
    private String lastPreviewOperationId;
    private String lastSaveOperationId;
    private String localMessage;
    private boolean validationVisible;

    private long resourceGeneration;
    private long planGeneration;
    private long arrangementGeneration;
    private boolean loadingResources;
    private boolean loadingPlan;
    private boolean loadingArrangements;
    private final Map<ReadTarget, String> readErrors = new EnumMap<>(ReadTarget.class);

    private boolean closed;
    private boolean createDraftInFlight;
    private boolean mutated;
    private boolean uncertainWrite;
    private boolean changeNotified;
    private boolean syncingControls;
    private boolean slotRowsDirty = true;
    private Runnable onChanged = () -> { };

    @FXML private Node dialogRoot;
    @FXML private Label offeringContextLabel;
    @FXML private Label planContextLabel;
    @FXML private Button createDraftButton;
    @FXML private Label planConflictSummaryLabel;
    @FXML private VBox planConflictArea;
    @FXML private ComboBox<ScheduleResourceDTO> teacherField;
    @FXML private ComboBox<ScheduleResourceDTO> assistantField;
    @FXML private Button clearAssistantButton;
    @FXML private ComboBox<ScheduleResourceDTO> classroomField;
    @FXML private TextField startWeekField;
    @FXML private TextField endWeekField;
    @FXML private VBox arrangementList;
    @FXML private Label emptyArrangementLabel;
    @FXML private VBox slotEditorList;
    @FXML private Button addSlotButton;
    @FXML private Button newArrangementButton;
    @FXML private VBox conflictArea;
    @FXML private Label conflictSummaryLabel;
    @FXML private TextField overrideReasonField;
    @FXML private Label validationLabel;
    @FXML private Label loadingLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private Button previewButton;
    @FXML private Button saveButton;
    @FXML private Button forceSaveButton;
    @FXML private Button publishButton;
    @FXML private Button cancelButton;

    public ScheduleArrangementDialogController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm,
                AlertUtil::showError, Platform::runLater);
    }

    ScheduleArrangementDialogController(AdminCourseService service,
            BiFunction<String, String, ButtonType> confirmation,
            BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Admin course service is required");
        this.confirmation = Objects.requireNonNull(confirmation, "Confirmation is required");
        this.errorReporter = Objects.requireNonNull(errorReporter, "Error reporter is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        if (teacherField != null) {
            teacherField.setConverter(resourceConverter());
            teacherField.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (syncingControls) return;
                setTeacherUid(newValue == null ? null : newValue.getBusinessId());
            });
        }
        if (assistantField != null) {
            assistantField.setConverter(resourceConverter());
            assistantField.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (syncingControls) return;
                setAssistantUid(newValue == null ? null : newValue.getBusinessId());
            });
        }
        if (classroomField != null) {
            classroomField.setConverter(resourceConverter());
            classroomField.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (syncingControls) return;
                setClassroomId(newValue == null ? null : newValue.getBusinessId());
            });
        }
        if (startWeekField != null) {
            startWeekField.textProperty().addListener(
                    (observable, oldValue, newValue) -> onWeekFieldsChanged());
        }
        if (endWeekField != null) {
            endWeekField.textProperty().addListener(
                    (observable, oldValue, newValue) -> onWeekFieldsChanged());
        }
        if (overrideReasonField != null) {
            overrideReasonField.textProperty().addListener(
                    (observable, oldValue, newValue) -> setOverrideReason(newValue));
        }
        render();
    }

    /**
     * 打开指定教学班的排课对话框：读取身份与学期，然后异步加载资源、方案与权威安排。
     */
    public void prepareForOffering(AdminOfferingView offering) {
        this.offering = offering;
        this.closed = false;
        this.mutated = false;
        this.uncertainWrite = false;
        this.changeNotified = false;
        this.retryWrites.clear();
        this.previewAfterReload = false;
        this.plan = null;
        this.arrangements = List.of();
        this.editingArrangement = null;
        this.teacherUid = offering == null ? null : blankToNull(offering.getTeacherUid());
        this.assistantUid = offering == null ? null : blankToNull(offering.getAssistantUid());
        this.classroomId = null;
        this.startWeek = 1;
        this.endWeek = 16;
        this.overrideReason = null;
        this.forceIntent = false;
        this.localMessage = null;
        this.validationVisible = false;
        this.readErrors.clear();
        resetSlotEditors(new ScheduleSlotDTO(1, 1, 2));
        clearPreview();
        syncWeekFields();
        render();
        loadResources();
        loadPlan();
    }

    public void setOnChanged(Runnable handler) {
        this.onChanged = handler == null ? () -> { } : handler;
    }

    // ---------------------------------------------------------------- 加载

    private void loadResources() {
        if (offering == null || closed) return;
        long generation = ++resourceGeneration;
        loadingResources = true;
        readErrors.remove(ReadTarget.RESOURCES);
        render();
        service.listScheduleResources(null, null)
                .whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
                    if (isStale(resourceGeneration, generation)) return;
                    loadingResources = false;
                    if (failure == null) {
                        List<ScheduleResourceDTO> items =
                                loaded == null ? List.of() : List.copyOf(loaded);
                        teacherResources = filterByType(items, TEACHER_RESOURCE);
                        classroomResources = filterByType(items, CLASSROOM_RESOURCE);
                    } else {
                        readErrors.put(ReadTarget.RESOURCES, "教师和教室加载失败，请重试");
                    }
                    render();
                }));
    }

    private void loadPlan() {
        if (offering == null || closed) return;
        long generation = ++planGeneration;
        arrangementGeneration++;
        loadingArrangements = false;
        clearPreview();
        loadingPlan = true;
        readErrors.remove(ReadTarget.PLAN);
        render();
        service.loadSchedulePlan(offering.getAcademicYear(), offering.getSemester())
                .whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
                    if (isStale(planGeneration, generation)) return;
                    loadingPlan = false;
                    if (failure != null) {
                        plan = null;
                        // 服务端消息必须留下：丢掉它，「缺少任课教师或时间段」这类真实原因就永远看不见。
                        readErrors.put(ReadTarget.PLAN, "排课方案加载失败：" + errorMessage(failure));
                        render();
                        return;
                    }
                    plan = loaded;
                    render();
                    loadArrangements();
                }));
    }

    void reloadArrangements() {
        loadArrangements();
    }

    private void loadArrangements() {
        if (closed || plan == null || plan.getPlanId() == null) return;
        long generation = ++arrangementGeneration;
        loadingArrangements = true;
        readErrors.remove(ReadTarget.ARRANGEMENTS);
        render();
        service.loadOfferingArrangements(plan.getPlanId(),
                        offering == null ? null : offering.getOfferingId())
                .whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
                    if (isStale(arrangementGeneration, generation)) return;
                    loadingArrangements = false;
                    if (failure != null) {
                        readErrors.put(ReadTarget.ARRANGEMENTS, "教学安排加载失败，请重试");
                        render();
                        return;
                    }
                    arrangements = loaded == null ? List.of() : List.copyOf(loaded);
                    if (previewAfterReload) {
                        previewAfterReload = false;
                        if (editingArrangement != null) {
                            String id = editingArrangement.getArrangementId();
                            editingArrangement = arrangements.stream()
                                    .filter(item -> id.equals(item.getArrangementId()))
                                    .findFirst().orElse(null);
                            if (editingArrangement == null) {
                                localMessage = "原教学安排已删除，请选择其他安排或点击新增安排";
                                clearPreview();
                                render();
                                return;
                            }
                        }
                        if (editablePlan()) previewArrangement();
                    }
                    render();
                }));
    }

    private boolean isStale(long current, long issued) {
        return closed || current != issued;
    }

    // ---------------------------------------------------------------- 时间段行

    void addSlotRow() {
        slotEditors.add(newSlotEditor(null));
        slotRowsDirty = true;
        onFormEdited();
    }

    void removeSlotRow(int index) {
        if (index < 0 || index >= slotEditors.size()) return;
        if (slotEditors.size() <= 1) return;
        slotEditors.remove(index);
        slotRowsDirty = true;
        onFormEdited();
    }

    private ScheduleSlotEditor newSlotEditor(ScheduleSlotDTO slot) {
        ScheduleSlotEditor editor = slot == null
                ? new ScheduleSlotEditor() : new ScheduleSlotEditor(slot);
        editor.setOnChange(this::onFormEdited);
        return editor;
    }

    private void resetSlotEditors(ScheduleSlotDTO initial) {
        slotEditors.clear();
        slotEditors.add(newSlotEditor(initial));
        slotRowsDirty = true;
    }

    // ---------------------------------------------------------------- 表单状态

    void setTeacherUid(String teacherUid) {
        this.teacherUid = blankToNull(teacherUid);
        syncCombo(teacherField, this.teacherUid);
        onFormEdited();
    }

    void setAssistantUid(String assistantUid) {
        this.assistantUid = blankToNull(assistantUid);
        syncCombo(assistantField, this.assistantUid);
        onFormEdited();
    }

    void setClassroomId(String classroomId) {
        this.classroomId = blankToNull(classroomId);
        syncCombo(classroomField, this.classroomId);
        onFormEdited();
    }

    void setWeekRange(int startWeek, int endWeek) {
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        syncWeekFields();
        onFormEdited();
    }

    private void onWeekFieldsChanged() {
        if (syncingControls) return;
        Integer start = parseIntOrNull(startWeekField);
        Integer end = parseIntOrNull(endWeekField);
        this.startWeek = start == null ? 0 : start;
        this.endWeek = end == null ? 0 : end;
        onFormEdited();
    }

    void setOverrideReason(String reason) {
        this.overrideReason = reason;
        localMessage = null;
        if (overrideReasonField != null && !syncingControls
                && !Objects.equals(reason, overrideReasonField.getText())) {
            syncingControls = true;
            try {
                overrideReasonField.setText(reason == null ? "" : reason);
            } finally {
                syncingControls = false;
            }
        }
        render();
    }

    void setForceIntent(boolean force) {
        this.forceIntent = force;
        localMessage = null;
        render();
    }

    private void syncWeekFields() {
        if (startWeekField == null && endWeekField == null) return;
        syncingControls = true;
        try {
            if (startWeekField != null) startWeekField.setText(String.valueOf(startWeek));
            if (endWeekField != null) endWeekField.setText(String.valueOf(endWeek));
        } finally {
            syncingControls = false;
        }
    }

    private void syncCombo(ComboBox<ScheduleResourceDTO> combo, String businessId) {
        if (combo == null) return;
        ScheduleResourceDTO match = findResource(combo.getItems(), businessId);
        if (match == combo.getValue()) return;
        syncingControls = true;
        try {
            combo.setValue(match);
        } finally {
            syncingControls = false;
        }
    }

    /**
     * 表单一旦变化，已完成的预检查不再授权保存，进行中的预检查也会被作废。
     */
    private void onFormEdited() {
        localMessage = null;
        previewAfterReload = false;
        clearPreview();
        render();
    }

    private void clearPreview() {
        conflicts = List.of();
        previewCurrent = false;
        previewPending = false;
        previewGeneration++;
        readErrors.remove(ReadTarget.PREVIEW);
    }

    // ---------------------------------------------------------------- 校验

    String validationMessage() {
        if (localMessage != null) return localMessage;
        return validateForm();
    }

    private String validateForm() {
        if (offering == null) return "缺少教学班上下文";
        if (plan == null || plan.getPlanId() == null) return "排课方案尚未加载";
        if (!editablePlan()) return "仅可编辑已加载的草稿方案";
        if (loadingResources || readErrors.containsKey(ReadTarget.RESOURCES)) {
            return "请等待教师和教室加载完成，失败时请重试";
        }
        if (startWeek < 1 || endWeek < startWeek) return "周次范围无效";
        if (teacherUid == null) return "请选择任课教师";
        if (teacherUid.equals(assistantUid)) return "任课教师与助教不能是同一人";
        if (classroomId == null) return "请选择教室";
        if (slotEditors.isEmpty()) return "至少需要一个时间段";
        for (int index = 0; index < slotEditors.size(); index++) {
            String message = slotEditors.get(index).validationMessage();
            if (message != null) return "第 " + (index + 1) + " 个时间段：" + message;
        }
        for (int left = 0; left < slotEditors.size(); left++) {
            for (int right = left + 1; right < slotEditors.size(); right++) {
                if (slotEditors.get(left).overlaps(slotEditors.get(right))) {
                    return "时间段存在重叠，请合并或修改";
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 预检查与冲突

    void previewArrangement() {
        localMessage = null;
        validationVisible = true;
        String invalid = validateForm();
        if (invalid != null) {
            localMessage = invalid;
            render();
            return;
        }
        String operationId = UUID.randomUUID().toString();
        lastPreviewOperationId = operationId;
        SaveArrangementRequestDTO request = buildRequest(operationId, false, null);
        long generation = ++previewGeneration;
        previewCurrent = false;
        conflicts = List.of();
        previewPending = true;
        readErrors.remove(ReadTarget.PREVIEW);
        render();
        service.checkArrangement(request).whenComplete((found, failure) -> fxExecutor.accept(() -> {
            if (isStale(previewGeneration, generation)) return;
            previewPending = false;
            if (failure != null) {
                previewCurrent = false;
                conflicts = List.of();
                readErrors.put(ReadTarget.PREVIEW, "冲突预检查失败，请重试");
                render();
                return;
            }
            conflicts = found == null ? List.of() : List.copyOf(found);
            previewCurrent = true;
            render();
        }));
    }

    boolean isPreviewPending() {
        return previewPending;
    }

    boolean isPreviewCurrent() {
        return previewCurrent;
    }

    List<ScheduleConflictDTO> conflicts() {
        return conflicts;
    }

    List<ScheduleConflictDTO> blockingConflicts() {
        return filterSeverity(conflicts, ScheduleConflictSeverityDTO.BLOCKING);
    }

    List<ScheduleConflictDTO> overridableConflicts() {
        return filterSeverity(conflicts, ScheduleConflictSeverityDTO.OVERRIDABLE);
    }

    boolean hasBlockingConflicts() {
        return !blockingConflicts().isEmpty();
    }

    /**
     * 阻断冲突必须移除强制路径；只有存在可绕过冲突时才允许强制。
     */
    boolean canForce() {
        return !closed && previewCurrent && !hasBlockingConflicts()
                && !overridableConflicts().isEmpty();
    }

    /**
     * 只有本地校验通过、预检查结果对应当前表单，并且强制意图与原因齐备时才允许保存。
     */
    boolean canSave() {
        if (writeBusy() || !editablePlan() || previewPending) return false;
        if (validationMessage() != null || !previewCurrent) return false;
        if (hasBlockingConflicts()) return false;
        if (overridableConflicts().isEmpty()) return true;
        return forceIntent && !trimmedReason().isEmpty();
    }

    private String trimmedReason() {
        return overrideReason == null ? "" : overrideReason.trim();
    }

    private static List<ScheduleConflictDTO> filterSeverity(
            List<ScheduleConflictDTO> source, ScheduleConflictSeverityDTO severity) {
        List<ScheduleConflictDTO> result = new ArrayList<>();
        for (ScheduleConflictDTO conflict : source) {
            if (conflict != null && conflict.getSeverity() == severity) result.add(conflict);
        }
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------- 写入

    void saveArrangement() {
        localMessage = null;
        validationVisible = true;
        String invalid = validateForm();
        if (invalid != null) {
            localMessage = invalid;
            render();
            return;
        }
        if (!previewCurrent) {
            localMessage = "请先预检查排课冲突";
            render();
            return;
        }
        if (hasBlockingConflicts()) {
            localMessage = "存在阻断性冲突，请返回修改";
            render();
            return;
        }
        if (!overridableConflicts().isEmpty()) {
            localMessage = "存在可绕过冲突，请填写原因后强制保存";
            render();
            return;
        }
        SaveArrangementRequestDTO candidate = buildRequest(null, false, null);
        executeWrite(SAVE_KEY, arrangementIntent(candidate), operationId -> {
            lastSaveOperationId = operationId;
            return service.saveArrangement(withOperationId(candidate, operationId));
        });
    }

    void saveArrangementWithForce(String reason) {
        localMessage = null;
        validationVisible = true;
        String invalid = validateForm();
        if (invalid != null) {
            localMessage = invalid;
            render();
            return;
        }
        if (hasBlockingConflicts()) {
            localMessage = "存在阻断性冲突，无法强制保存";
            render();
            return;
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            localMessage = "请填写强制保存原因";
            render();
            return;
        }
        if (!previewCurrent) {
            localMessage = "请先预检查排课冲突";
            render();
            return;
        }
        this.overrideReason = trimmed;
        this.forceIntent = true;
        SaveArrangementRequestDTO candidate = buildRequest(null, true, trimmed);
        executeWrite(SAVE_KEY, arrangementIntent(candidate), operationId -> {
            lastSaveOperationId = operationId;
            return service.saveArrangement(withOperationId(candidate, operationId));
        });
    }

    /**
     * 删除需要二次确认，并携带当前权威版本；取消不产生任何写入。
     */
    void requestDeleteArrangement(ScheduleArrangementView arrangement) {
        if (arrangement == null || !editablePlan() || writeBusy()) return;
        localMessage = null;
        if (confirmation.apply("删除教学安排",
                "确认删除该教学安排？删除后需要重新排课。") != ButtonType.OK) {
            return;
        }
        String key = "schedule:delete:" + arrangement.getArrangementId();
        executeWrite(key, List.of(arrangement.getArrangementId(), arrangement.getVersion()),
                operationId -> service.deleteArrangement(
                arrangement.getArrangementId(), arrangement.getVersion(), operationId));
    }

    void requestPublishPlan() {
        localMessage = null;
        validationVisible = true;
        if (!editablePlan() || writeBusy()) {
            localMessage = "仅可发布已加载的草稿方案";
            render();
            return;
        }
        if (plan == null || plan.getPlanId() == null) {
            localMessage = "排课方案尚未加载";
            render();
            return;
        }
        if (!planConflicts(ScheduleConflictSeverityDTO.BLOCKING).isEmpty()) {
            localMessage = "存在阻断性冲突，无法发布";
            render();
            return;
        }
        if (!planConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE).isEmpty()) {
            publishPlanWithForce(overrideReason);
            return;
        }
        if (confirmation.apply("发布排课方案",
                "确认发布排课方案？发布后学生课表将按该方案生效。") != ButtonType.OK) {
            return;
        }
        publishPlan(false, null);
    }

    void publishPlanWithForce(String reason) {
        localMessage = null;
        validationVisible = true;
        if (!editablePlan() || writeBusy()) {
            localMessage = "仅可发布已加载的草稿方案";
            render();
            return;
        }
        if (plan == null || plan.getPlanId() == null) {
            localMessage = "排课方案尚未加载";
            render();
            return;
        }
        if (!planConflicts(ScheduleConflictSeverityDTO.BLOCKING).isEmpty()) {
            localMessage = "存在阻断性冲突，无法强制发布";
            render();
            return;
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            localMessage = "请填写强制发布原因";
            render();
            return;
        }
        if (confirmation.apply("发布排课方案",
                "确认强制发布排课方案？") != ButtonType.OK) {
            return;
        }
        publishPlan(true, trimmed);
    }

    private void publishPlan(boolean force, String reason) {
        String planId = plan.getPlanId();
        int revision = plan.getRevision();
        executeWrite(PUBLISH_KEY, Arrays.asList(planId, revision, force, reason),
                operationId -> service.publishSchedulePlan(planId, revision, operationId, force, reason));
    }

    private List<ScheduleConflictDTO> planConflicts(ScheduleConflictSeverityDTO severity) {
        return plan == null ? List.of() : filterSeverity(plan.getConflicts(), severity);
    }

    /**
     * 执行一次写操作：同一意图只生成一个 operationId，重入点击被忽略，
     * 成功或失败都必须恢复控件。
     */
    private void executeWrite(String key, List<?> intent,
            Function<String, CompletableFuture<?>> action) {
        if (closed || writeBusy()) return;
        WriteAttempt attempt = retryWrites.get(key);
        if (attempt == null || !attempt.intent().equals(intent)) {
            attempt = new WriteAttempt(intent, UUID.randomUUID().toString(), action);
            retryWrites.put(key, attempt);
        }
        if (!pendingWrites.add(key)) {
            updateWriteControls(key);
            return;
        }
        updateWriteControls(key);
        render();

        CompletableFuture<?> future;
        try {
            future = attempt.action().apply(attempt.operationId());
        } catch (RuntimeException failure) {
            finishWriteFailure(key, failure);
            return;
        }
        if (future == null) {
            finishWriteFailure(key, new IllegalStateException("写操作未启动"));
            return;
        }
        future.whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (failure != null) {
                finishWriteFailure(key, failure);
                return;
            }
            pendingWrites.remove(key);
            retryWrites.clear();
            mutated = true;
            if (closed) {
                notifyCatalogIfChanged();
                return;
            }
            updateWriteControls(key);
            editingArrangement = null;
            forceIntent = false;
            setOverrideReason(null);
            clearPreview();
            loadPlan();
        }));
    }

    private void finishWriteFailure(String key, Throwable failure) {
        pendingWrites.remove(key);
        Throwable cause = rootCause(failure);
        uncertainWrite |= !(cause instanceof AdminCourseServiceException);
        if (closed) {
            notifyCatalogIfChanged();
            return;
        }
        updateWriteControls(key);
        if (cause instanceof AdminCourseServiceException
                && ((AdminCourseServiceException) cause).getCode() == MessageCode.CONFLICT) {
            retryWrites.remove(key);
            errorReporter.accept("操作冲突", CONFLICT_MESSAGE);
            previewAfterReload = SAVE_KEY.equals(key);
            loadPlan();
            return;
        }
        errorReporter.accept("操作失败", errorMessage(cause));
        render();
    }

    boolean isWritePending(String key) {
        return pendingWrites.contains(key);
    }

    void registerWriteControl(String key, Consumer<Boolean> setDisabled) {
        writeControls.computeIfAbsent(key, ignored -> new ArrayList<>()).add(setDisabled);
        setDisabled.accept(pendingWrites.contains(key));
    }

    private void updateWriteControls(String key) {
        boolean disabled = pendingWrites.contains(key);
        for (Consumer<Boolean> setDisabled : writeControls.getOrDefault(key, List.of())) {
            setDisabled.accept(disabled);
        }
    }

    private boolean writeBusy() {
        return !pendingWrites.isEmpty();
    }

    private boolean editablePlan() {
        return !closed && plan != null && DRAFT.equals(plan.getStatus())
                && !loadingPlan && !loadingArrangements
                && !readErrors.containsKey(ReadTarget.PLAN)
                && !readErrors.containsKey(ReadTarget.ARRANGEMENTS);
    }

    /**
     * 「创建草稿方案」只在学期确实没有可编辑草稿时提供：方案加载中、已是草稿、写入或创建进行中
     * 都不提供，避免给出一个必然被服务端以「该学期已有草稿方案」拒绝的按钮。
     */
    boolean isCreateDraftOffered() {
        return offering != null && !closed && !loadingPlan && !writeBusy() && !createDraftInFlight
                && (plan == null || !DRAFT.equals(plan.getStatus()));
    }

    // ---------------------------------------------------------------- 编辑既有安排

    @FXML
    private void handleNewArrangement() {
        if (writeBusy() || !editablePlan()) return;
        retryWrites.remove(SAVE_KEY);
        editingArrangement = null;
        teacherUid = offering == null ? null : blankToNull(offering.getTeacherUid());
        assistantUid = offering == null ? null : blankToNull(offering.getAssistantUid());
        classroomId = null;
        startWeek = 1;
        endWeek = 16;
        forceIntent = false;
        setOverrideReason(null);
        validationVisible = false;
        resetSlotEditors(new ScheduleSlotDTO(1, 1, 2));
        syncWeekFields();
        onFormEdited();
    }

    void editArrangement(ScheduleArrangementView arrangement) {
        if (arrangement == null || writeBusy() || !editablePlan()) return;
        retryWrites.remove(SAVE_KEY);
        editingArrangement = arrangement;
        teacherUid = arrangement.getTeacher() == null
                ? null : blankToNull(arrangement.getTeacher().getBusinessId());
        assistantUid = arrangement.getAssistant() == null
                ? null : blankToNull(arrangement.getAssistant().getBusinessId());
        classroomId = arrangement.getClassroom() == null
                ? null : blankToNull(arrangement.getClassroom().getBusinessId());
        startWeek = arrangement.getStartWeek();
        endWeek = arrangement.getEndWeek();
        slotEditors.clear();
        for (ScheduleSlotDTO slot : arrangement.getSlots()) {
            slotEditors.add(newSlotEditor(slot));
        }
        if (slotEditors.isEmpty()) slotEditors.add(newSlotEditor(null));
        slotRowsDirty = true;
        syncWeekFields();
        onFormEdited();
    }

    // ---------------------------------------------------------------- 取消/关闭

    @FXML
    public void handleCancel() {
        dispose();
        hideWindow();
    }

    public void dispose() {
        if (closed) return;
        closed = true;
        resourceGeneration++;
        planGeneration++;
        arrangementGeneration++;
        clearPreview();
        notifyCatalogIfChanged();
    }

    private void notifyCatalogIfChanged() {
        if (closed && (mutated || uncertainWrite) && !changeNotified && pendingWrites.isEmpty()) {
            changeNotified = true;
            onChanged.run();
        }
    }

    boolean isClosed() {
        return closed;
    }

    boolean hasMutated() {
        return mutated;
    }

    // ---------------------------------------------------------------- 访问器

    SchedulePlanDTO plan() {
        return plan;
    }

    List<ScheduleArrangementView> arrangements() {
        return arrangements;
    }

    List<ScheduleSlotEditor> slotEditors() {
        return slotEditors;
    }

    boolean isLoadingArrangements() {
        return loadingArrangements;
    }

    String errorText() {
        return readErrors.isEmpty() ? null : String.join("\n", readErrors.values());
    }

    String lastPreviewOperationId() {
        return lastPreviewOperationId;
    }

    String lastSaveOperationId() {
        return lastSaveOperationId;
    }

    String arrangementSummary(ScheduleArrangementView arrangement) {
        if (arrangement == null) return "";
        StringBuilder text = new StringBuilder();
        text.append("教师 ").append(resourceName(arrangement.getTeacher()));
        text.append(" · 助教 ").append(resourceName(arrangement.getAssistant()));
        text.append(" · 教室 ").append(resourceName(arrangement.getClassroom()));
        text.append(" · ").append(arrangement.getStartWeek()).append('-')
                .append(arrangement.getEndWeek()).append(" 周");
        for (ScheduleSlotDTO slot : arrangement.getSlots()) {
            text.append(" · ").append(slotSummary(slot));
        }
        return text.toString();
    }

    String slotSummary(ScheduleSlotDTO slot) {
        if (slot == null) return "";
        return ScheduleSlotEditor.weekdayLabel(slot.getDayOfWeek())
                + " 第" + ScheduleSlotEditor.periodLabel(
                        slot.getStartPeriod(), slot.getEndPeriod()) + "节";
    }

    // ---------------------------------------------------------------- FXML 动作

    @FXML
    private void handlePreview() {
        previewArrangement();
    }

    @FXML
    private void handleSave() {
        saveArrangement();
    }

    @FXML
    private void handleForceSave() {
        saveArrangementWithForce(overrideReasonField == null ? null : overrideReasonField.getText());
    }

    @FXML
    private void handleAddSlot() {
        addSlotRow();
    }

    @FXML
    private void handleClearAssistant() {
        if (!writeBusy() && editablePlan()) setAssistantUid(null);
    }

    @FXML
    private void handlePublish() {
        requestPublishPlan();
    }

    /**
     * 学期还没有方案（或只有已发布的方案）时，从服务端开一份草稿：这是本对话框里唯一能让整屏
     * 写控件重新可用的入口。创建结果本身不参与任何判断——它的 conflicts 在服务端固定为空，
     * 真正的权威冲突随 {@link #loadPlan()} 一起回来，所以成功一律以重新加载的方案为准。
     * 服务端的结果文案（含已复制/跳过条数）则原样留在校验行上：看不到「复制了 0 条」正是空草稿
     * 一直被静默的原因。
     */
    @FXML
    public void handleCreateDraft() {
        if (!isCreateDraftOffered()) return;
        createDraftInFlight = true;
        localMessage = null;
        render();
        String operationId = UUID.randomUUID().toString();
        // 复制意图的真值来源：只有确知该学期没有方案（加载成功且返回空）时才传 false。加载失败
        // 时信息不足，必须按服务端的「有则复制、无则照样建空」传 true——false 是在信息不足时
        // 主动放弃一次可能存在的复制。也不要靠隐藏按钮回避：加载失败后仍给出出路是刻意做的。
        boolean copyPublished = plan != null || readErrors.containsKey(ReadTarget.PLAN);
        service.createSchedulePlan(offering.getAcademicYear(), offering.getSemester(),
                        copyPublished, operationId)
                .whenComplete((created, failure) -> fxExecutor.accept(() -> {
                    createDraftInFlight = false;
                    if (failure != null) {
                        validationVisible = true;
                        localMessage = "创建草稿方案失败：" + errorMessage(failure);
                        render();
                        return;
                    }
                    String message = created == null ? null : created.getMessage();
                    if (message != null && !message.isBlank()) {
                        localMessage = message;
                        validationVisible = true;
                    }
                    loadPlan();
                }));
    }

    @FXML
    private void handleRetry() {
        Set<ReadTarget> failed = Set.copyOf(readErrors.keySet());
        if (failed.contains(ReadTarget.RESOURCES)) loadResources();
        if (failed.contains(ReadTarget.PLAN)) loadPlan();
        else if (failed.contains(ReadTarget.ARRANGEMENTS)) loadArrangements();
        if (failed.contains(ReadTarget.PREVIEW)) previewArrangement();
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (closed) return;
        if (dialogRoot == null && arrangementList == null && slotEditorList == null) return;

        if (offeringContextLabel != null) {
            offeringContextLabel.setText(offering == null ? "" : offering.getOfferingCode()
                    + " · " + offering.getAcademicYear() + " 学年 第 "
                    + offering.getSemester() + " 学期");
        }
        if (planContextLabel != null) {
            planContextLabel.setText(plan == null
                    ? "排课方案：加载中"
                    : "排课方案：" + plan.getName() + " · 修订 " + plan.getRevision()
                            + " · " + planStatusText(plan.getStatus()));
        }
        if (validationLabel != null) {
            String message = validationMessage();
            boolean show = validationVisible && message != null;
            validationLabel.setText(show ? message : "");
            validationLabel.setVisible(show);
            validationLabel.setManaged(show);
        }
        if (loadingLabel != null) {
            boolean loading = loadingPlan || loadingResources || loadingArrangements;
            loadingLabel.setVisible(loading);
            loadingLabel.setManaged(loading);
        }
        if (emptyArrangementLabel != null) {
            boolean empty = !loadingArrangements && errorText() == null && arrangements.isEmpty();
            emptyArrangementLabel.setVisible(empty);
            emptyArrangementLabel.setManaged(empty);
        }
        if (errorLabel != null) {
            boolean hasError = errorText() != null;
            errorLabel.setText(hasError ? errorText() : "");
            errorLabel.setVisible(hasError);
            errorLabel.setManaged(hasError);
        }
        if (errorRetryButton != null) {
            boolean hasError = errorText() != null;
            errorRetryButton.setVisible(hasError);
            errorRetryButton.setManaged(hasError);
        }
        renderResourceChoices();
        renderSlotRows();
        renderArrangementCards();
        renderConflictArea();
        renderPlanConflicts();
        renderWriteControls();
    }

    private void renderResourceChoices() {
        if (teacherField != null && !teacherField.getItems().equals(teacherResources)) {
            syncingControls = true;
            try {
                teacherField.getItems().setAll(teacherResources);
            } finally {
                syncingControls = false;
            }
        }
        if (assistantField != null && !assistantField.getItems().equals(teacherResources)) {
            syncingControls = true;
            try {
                assistantField.getItems().setAll(teacherResources);
            } finally {
                syncingControls = false;
            }
        }
        if (classroomField != null && !classroomField.getItems().equals(classroomResources)) {
            syncingControls = true;
            try {
                classroomField.getItems().setAll(classroomResources);
            } finally {
                syncingControls = false;
            }
        }
        syncCombo(teacherField, teacherUid);
        syncCombo(assistantField, assistantUid);
        syncCombo(classroomField, classroomId);
    }

    private void renderSlotRows() {
        if (slotEditorList == null) return;
        if (!slotRowsDirty && slotEditorList.getChildren().size() == slotEditors.size()) return;
        slotRowsDirty = false;
        slotEditorList.getChildren().clear();
        for (ScheduleSlotEditor editor : slotEditors) {
            slotEditorList.getChildren().add(createSlotRow(editor));
        }
    }

    private Node createSlotRow(ScheduleSlotEditor editor) {
        ComboBox<Integer> weekday = new ComboBox<>();
        weekday.getStyleClass().add("course-admin-slot-weekday");
        weekday.getItems().setAll(1, 2, 3, 4, 5, 6, 7);
        weekday.setConverter(weekdayConverter());
        weekday.setValue(editor.dayOfWeek() == 0 ? null : editor.dayOfWeek());
        weekday.valueProperty().addListener((observable, oldValue, newValue) ->
                editor.setDayOfWeek(newValue == null ? 0 : newValue));

        ComboBox<Integer> start = periodCombo(editor.startPeriod());
        start.valueProperty().addListener((observable, oldValue, newValue) ->
                editor.setStartPeriod(newValue == null ? 0 : newValue));
        ComboBox<Integer> end = periodCombo(editor.endPeriod());
        end.valueProperty().addListener((observable, oldValue, newValue) ->
                editor.setEndPeriod(newValue == null ? 0 : newValue));

        Button remove = new Button("删除");
        remove.getStyleClass().add("course-admin-slot-remove");
        remove.setOnAction(event -> removeSlotRow(indexOfEditor(editor)));

        HBox row = new HBox(8.0, weekday, start, end, remove);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("course-admin-slot-row");
        return row;
    }

    private ComboBox<Integer> periodCombo(int value) {
        ComboBox<Integer> combo = new ComboBox<>();
        combo.getStyleClass().add("course-admin-slot-period");
        for (int period = ScheduleSlotEditor.MIN_PERIOD;
                period <= ScheduleSlotEditor.MAX_PERIOD; period++) {
            combo.getItems().add(period);
        }
        combo.setValue(value <= 0 ? null : value);
        return combo;
    }

    private int indexOfEditor(ScheduleSlotEditor editor) {
        for (int index = 0; index < slotEditors.size(); index++) {
            if (slotEditors.get(index) == editor) return index;
        }
        return -1;
    }

    private void renderArrangementCards() {
        if (arrangementList == null) return;
        for (String key : cardWriteKeys) {
            writeControls.remove(key);
        }
        cardWriteKeys.clear();
        arrangementList.getChildren().clear();
        for (ScheduleArrangementView arrangement : arrangements) {
            arrangementList.getChildren().add(createArrangementCard(arrangement));
        }
    }

    private Node createArrangementCard(ScheduleArrangementView arrangement) {
        VBox card = new VBox(3.0);
        card.getStyleClass().add("course-admin-arrangement-card");

        Label title = new Label(arrangementSummary(arrangement));
        title.getStyleClass().add("course-admin-arrangement-title");
        title.setWrapText(true);

        Label slots = new Label("状态 " + arrangement.getStatus()
                + " · 版本 " + arrangement.getVersion());
        slots.getStyleClass().add("course-admin-arrangement-meta");

        Button edit = actionButton("修改", "course-admin-arrangement-edit");
        edit.setOnAction(event -> editArrangement(arrangement));

        Button delete = actionButton("删除", "course-admin-arrangement-delete");
        String key = "schedule:delete:" + arrangement.getArrangementId();
        registerWriteControl(key, delete::setDisable);
        cardWriteKeys.add(key);
        delete.setOnAction(event -> requestDeleteArrangement(arrangement));

        HBox actions = new HBox(6.0, spacer(), edit, delete);
        actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        actions.getStyleClass().add("course-admin-arrangement-actions");

        card.getChildren().addAll(title, slots, actions);
        return card;
    }

    private void renderConflictArea() {
        if (conflictSummaryLabel != null) {
            String summary = conflictSummary();
            boolean any = !conflicts.isEmpty();
            conflictSummaryLabel.setText(summary);
            conflictSummaryLabel.setVisible(any);
            conflictSummaryLabel.setManaged(any);
        }
        if (conflictArea == null) return;
        conflictArea.getChildren().clear();
        for (ScheduleConflictDTO conflict : blockingConflicts()) {
            conflictArea.getChildren().add(conflictLabel(conflict,
                    "course-admin-conflict-blocking"));
        }
        for (ScheduleConflictDTO conflict : overridableConflicts()) {
            conflictArea.getChildren().add(conflictLabel(conflict,
                    "course-admin-conflict-overridable"));
        }
    }

    private String conflictSummary() {
        int blocking = blockingConflicts().size();
        int overridable = overridableConflicts().size();
        if (blocking == 0 && overridable == 0) return "未发现冲突";
        StringBuilder text = new StringBuilder();
        if (blocking > 0) text.append("阻断性冲突 ").append(blocking).append(" 项");
        if (overridable > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append("可绕过冲突 ").append(overridable).append(" 项");
        }
        return text.toString();
    }

    private Label conflictLabel(ScheduleConflictDTO conflict, String styleClass) {
        String message = conflict.getMessage() == null ? conflict.getType() : conflict.getMessage();
        String position = conflict.getWeek() > 0
                ? "（第 " + conflict.getWeek() + " 周）" : "";
        Label label = new Label(message + position);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private void renderWriteControls() {
        boolean busy = writeBusy();
        boolean editingDisabled = busy || !editablePlan();
        if (previewButton != null) {
            previewButton.setDisable(editingDisabled || previewPending || loadingResources);
        }
        if (saveButton != null) {
            saveButton.setDisable(busy || !canSave() || !overridableConflicts().isEmpty());
        }
        if (forceSaveButton != null) {
            boolean visible = canForce();
            forceSaveButton.setVisible(visible);
            forceSaveButton.setManaged(visible);
            forceSaveButton.setDisable(editingDisabled || !canForce());
        }
        if (publishButton != null) {
            boolean hasWarnings = !planConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE).isEmpty();
            publishButton.setText(hasWarnings ? "填写原因并发布" : "发布方案");
            publishButton.setDisable(editingDisabled
                    || !planConflicts(ScheduleConflictSeverityDTO.BLOCKING).isEmpty());
        }
        if (createDraftButton != null) {
            boolean offered = isCreateDraftOffered();
            createDraftButton.setVisible(offered);
            createDraftButton.setManaged(offered);
            createDraftButton.setDisable(!offered);
        }
        if (addSlotButton != null) addSlotButton.setDisable(editingDisabled);
        if (newArrangementButton != null) newArrangementButton.setDisable(editingDisabled);
        if (teacherField != null) teacherField.setDisable(editingDisabled);
        if (assistantField != null) assistantField.setDisable(editingDisabled);
        if (clearAssistantButton != null) {
            clearAssistantButton.setDisable(editingDisabled || assistantUid == null);
        }
        if (classroomField != null) classroomField.setDisable(editingDisabled);
        if (startWeekField != null) startWeekField.setDisable(editingDisabled);
        if (endWeekField != null) endWeekField.setDisable(editingDisabled);
        if (slotEditorList != null) slotEditorList.setDisable(editingDisabled);
        if (arrangementList != null) arrangementList.setDisable(editingDisabled);
        if (overrideReasonField != null) overrideReasonField.setDisable(editingDisabled);
        if (cancelButton != null) cancelButton.setDisable(false);
    }

    private void renderPlanConflicts() {
        List<ScheduleConflictDTO> blocking = planConflicts(ScheduleConflictSeverityDTO.BLOCKING);
        List<ScheduleConflictDTO> overridable = planConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE);
        boolean any = !blocking.isEmpty() || !overridable.isEmpty();
        if (planConflictSummaryLabel != null) {
            planConflictSummaryLabel.setText(!blocking.isEmpty()
                    ? "方案存在阻断性冲突，修改后才能发布"
                    : "方案存在可绕过冲突，填写原因后可发布");
            planConflictSummaryLabel.setVisible(any);
            planConflictSummaryLabel.setManaged(any);
        }
        if (planConflictArea == null) return;
        planConflictArea.getChildren().clear();
        for (ScheduleConflictDTO conflict : blocking) {
            planConflictArea.getChildren().add(conflictLabel(conflict, "course-admin-conflict-blocking"));
        }
        for (ScheduleConflictDTO conflict : overridable) {
            planConflictArea.getChildren().add(conflictLabel(conflict, "course-admin-conflict-overridable"));
        }
        planConflictArea.setVisible(any);
        planConflictArea.setManaged(any);
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
    }

    // ---------------------------------------------------------------- 构造

    private static List<?> arrangementIntent(SaveArrangementRequestDTO request) {
        List<List<Integer>> slots = request.getSlots().stream()
                .map(slot -> List.of(slot.getDayOfWeek(), slot.getStartPeriod(), slot.getEndPeriod()))
                .toList();
        return Arrays.asList(request.getArrangementId(), request.getExpectedVersion(),
                request.getPlanId(), request.getOfferingId(), request.getTeacherUid(),
                request.getAssistantUid(), request.getClassroomId(), slots,
                request.getStartWeek(), request.getEndWeek(), request.isForce(), request.getOverrideReason());
    }

    private static SaveArrangementRequestDTO withOperationId(
            SaveArrangementRequestDTO request, String operationId) {
        return new SaveArrangementRequestDTO(operationId, request.getArrangementId(),
                request.getExpectedVersion(), request.getPlanId(), request.getOfferingId(),
                request.getTeacherUid(), request.getAssistantUid(), request.getClassroomId(),
                request.getSlots(), request.getStartWeek(), request.getEndWeek(),
                request.isForce(), request.getOverrideReason());
    }

    private SaveArrangementRequestDTO buildRequest(String operationId, boolean force,
            String reason) {
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        for (ScheduleSlotEditor editor : slotEditors) {
            slots.add(editor.value());
        }
        return new SaveArrangementRequestDTO(operationId,
                editingArrangement == null ? null : editingArrangement.getArrangementId(),
                editingArrangement == null ? 0 : editingArrangement.getVersion(),
                plan == null ? null : plan.getPlanId(),
                offering == null ? null : offering.getOfferingId(),
                teacherUid, assistantUid, classroomId, slots, startWeek, endWeek,
                force, reason);
    }

    private static StringConverter<ScheduleResourceDTO> resourceConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(ScheduleResourceDTO resource) {
                return resource == null ? "" : resourceName(resource);
            }

            @Override
            public ScheduleResourceDTO fromString(String text) {
                return null;
            }
        };
    }

    private static StringConverter<Integer> weekdayConverter() {
        return new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : ScheduleSlotEditor.weekdayLabel(value);
            }

            @Override
            public Integer fromString(String text) {
                return null;
            }
        };
    }

    private static Button actionButton(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll("course-admin-action", styleClass);
        return button;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private static List<ScheduleResourceDTO> filterByType(
            List<ScheduleResourceDTO> source, String resourceType) {
        List<ScheduleResourceDTO> result = new ArrayList<>();
        for (ScheduleResourceDTO resource : source) {
            if (resource != null && resourceType.equals(resource.getResourceType())) {
                result.add(resource);
            }
        }
        return List.copyOf(result);
    }

    private static ScheduleResourceDTO findResource(
            List<ScheduleResourceDTO> source, String businessId) {
        if (businessId == null || source == null) return null;
        for (ScheduleResourceDTO resource : source) {
            if (businessId.equals(resource.getBusinessId())) return resource;
        }
        return null;
    }

    private static String resourceName(ScheduleResourceDTO resource) {
        if (resource == null) return "待定";
        if (resource.getName() != null && !resource.getName().isBlank()) return resource.getName();
        if (resource.getBusinessId() != null && !resource.getBusinessId().isBlank()) {
            return resource.getBusinessId();
        }
        return "待定";
    }

    private static String planStatusText(String status) {
        if (DRAFT.equals(status)) return "草稿";
        if (READY.equals(status)) return "待发布";
        if (PUBLISHED.equals(status)) return "已发布";
        return status == null ? "未知" : status;
    }

    private static Integer parseIntOrNull(TextField field) {
        if (field == null) return null;
        String text = field.getText();
        if (text == null || text.isBlank()) return null;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String errorMessage(Throwable failure) {
        Throwable cause = rootCause(failure);
        return cause.getMessage() == null
                ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
