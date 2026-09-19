package controller;

import java.util.ArrayList;
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

import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import model.course.admin.AdminEnrollmentPageView;
import model.course.admin.AdminOfferingView;
import model.course.admin.StudentSearchResultView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 添加学生对话框：先搜索候选学生，选中后加载容量/时间/先修风险预览，再按风险等级决定加入路径。
 *
 * <p>服务端权威状态（搜索结果、风险、版本）只保存在控制器字段中。所有异步结果都带 generation
 * 与目标学生校验，过期或已卸载的响应不得覆盖更新的状态，也不得授权写入。</p>
 */
/** 管理员为教学班添加学生的 JavaFX 对话框控制器。 */
public final class AddOfferingStudentDialogController {
    static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";

    private static final String ADD_KEY = "enrollment:add";
    private static final int PAGE_SIZE = 10;

    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;

    private final Set<String> pendingWrites = new HashSet<>();
    private final Map<String, List<Consumer<Boolean>>> writeControls = new HashMap<>();
    private WriteAttempt retryWrite;

    private AdminOfferingView offering;
    private String query = "";
    private List<StudentSearchResultView> results = List.of();
    private long totalCount;
    private int pageNumber = 1;
    private boolean searching;
    private String searchError;

    private String selectedUid;
    private StudentSearchResultView selectedStudent;
    private List<ScheduleConflictDTO> risks = List.of();
    private boolean previewCurrent;
    private boolean previewPending;

    private String message;
    private long searchGeneration;
    private long previewGeneration;
    private boolean closed;
    private boolean mutated;
    private Runnable onChanged = () -> { };

    @FXML private Node dialogRoot;
    @FXML private Label offeringContextLabel;
    @FXML private TextField queryField;
    @FXML private Button searchButton;
    @FXML private VBox studentResultList;
    @FXML private Label resultLoadingLabel;
    @FXML private Label resultEmptyLabel;
    @FXML private Label resultErrorLabel;
    @FXML private Button resultRetryButton;
    @FXML private Label paginationLabel;
    @FXML private Button prevPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label selectedStudentLabel;
    @FXML private Label riskSummaryLabel;
    @FXML private VBox riskArea;
    @FXML private TextField overrideReasonField;
    @FXML private Label validationLabel;
    @FXML private Button cancelButton;
    @FXML private Button previewButton;
    @FXML private Button forceAddButton;
    @FXML private Button addButton;

    public AddOfferingStudentDialogController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm,
                AlertUtil::showError, Platform::runLater);
    }

    AddOfferingStudentDialogController(AdminCourseService service,
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
        if (queryField != null) {
            queryField.textProperty().addListener(
                    (observable, oldValue, newValue) -> onQueryEdited(newValue));
        }
        render();
    }

    public void prepareForOffering(AdminOfferingView offering) {
        this.offering = offering;
        this.query = "";
        this.results = List.of();
        this.totalCount = 0;
        this.pageNumber = 1;
        this.searching = false;
        this.searchError = null;
        this.message = null;
        this.selectedUid = null;
        this.selectedStudent = null;
        this.risks = List.of();
        this.previewCurrent = false;
        this.previewPending = false;
        this.closed = false;
        this.mutated = false;
        this.searchGeneration++;
        this.previewGeneration++;
        this.retryWrite = null;
        render();
    }

    public void setOnChanged(Runnable handler) {
        this.onChanged = handler == null ? () -> { } : handler;
    }

    // ---------------------------------------------------------------- 搜索

    /**
     * 显式搜索：每次查询只采纳最新一次结果；编辑输入会立即作废进行中的搜索与已选学生。
     */
    void search(String nextQuery) {
        if (closed) return;
        this.query = nextQuery == null ? "" : nextQuery.trim();
        this.pageNumber = 1;
        this.message = null;
        clearSelection();
        this.results = List.of();
        this.totalCount = 0;
        if (this.query.isEmpty()) {
            this.searching = false;
            this.searchGeneration++;
            this.message = "请输入学号或姓名";
            render();
            return;
        }
        loadResults();
    }

    void onQueryEdited(String text) {
        if (closed) return;
        this.query = text == null ? "" : text.trim();
        this.searchGeneration++;
        this.searching = false;
        this.searchError = null;
        this.message = null;
        clearSelection();
        render();
    }

    private void loadResults() {
        String sentQuery = query;
        long generation = ++searchGeneration;
        searching = true;
        searchError = null;
        render();
        CompletableFuture<AdminEnrollmentPageView<StudentSearchResultView>> future;
        try {
            future = service.searchStudentsPage(sentQuery, pageNumber, PAGE_SIZE);
        } catch (RuntimeException failure) {
            searching = false;
            searchError = "学生搜索失败，请重试";
            render();
            return;
        }
        if (future == null) {
            searching = false;
            searchError = "学生搜索失败，请重试";
            render();
            return;
        }
        future.whenComplete((page, failure) -> fxExecutor.accept(() -> {
            if (closed || generation != searchGeneration) return;
            searching = false;
            if (failure != null) {
                searchError = "学生搜索失败，请重试";
                render();
                return;
            }
            results = page == null ? List.of() : List.copyOf(page.getItems());
            totalCount = page == null ? 0 : page.getTotalCount();
            pageNumber = page == null ? pageNumber : page.getPageNumber();
            render();
        }));
    }

    private void clearSelection() {
        selectedUid = null;
        selectedStudent = null;
        clearPreview();
    }

    private void clearPreview() {
        risks = List.of();
        previewCurrent = false;
        previewPending = false;
        previewGeneration++;
    }

    // ---------------------------------------------------------------- 预览

    void selectStudent(String uid) {
        if (closed || uid == null) return;
        StudentSearchResultView match = null;
        for (StudentSearchResultView candidate : results) {
            if (uid.equals(candidate.getUid())) {
                match = candidate;
                break;
            }
        }
        if (match == null) return;
        selectedStudent = match;
        selectedUid = match.getUid();
        requestPreview();
    }

    void requestPreview() {
        if (closed || selectedStudent == null || selectedUid == null) return;
        final String requestedUid = selectedUid;
        final String requestedOfferingId = offeringId();
        long generation = ++previewGeneration;
        previewCurrent = false;
        previewPending = true;
        risks = List.of();
        message = null;
        render();
        CompletableFuture<AdminEnrollmentPreviewDTO> future;
        try {
            future = service.previewAdminEnrollment(requestedOfferingId, requestedUid);
        } catch (RuntimeException failure) {
            previewPending = false;
            message = "风险预览失败，请重试";
            render();
            return;
        }
        if (future == null) {
            previewPending = false;
            message = "风险预览失败，请重试";
            render();
            return;
        }
        future.whenComplete((preview, failure) -> fxExecutor.accept(() -> {
            if (closed || generation != previewGeneration || !requestedUid.equals(selectedUid)) return;
            previewPending = false;
            if (failure != null) {
                previewCurrent = false;
                risks = List.of();
                message = "风险预览失败，请重试";
                render();
                return;
            }
            risks = preview == null ? List.of() : preview.getRisks();
            previewCurrent = true;
            render();
        }));
    }

    // ---------------------------------------------------------------- 写入

    void addStudent() {
        if (closed) return;
        message = null;
        if (offering == null) {
            message = "缺少教学班上下文";
            render();
            return;
        }
        if (selectedUid == null) {
            message = "请先选择学生";
            render();
            return;
        }
        if (!previewCurrent) {
            message = "请先查看风险预览";
            render();
            return;
        }
        if (hasBlockingRisks()) {
            message = "存在阻断性风险，无法加入";
            render();
            return;
        }
        if (!overridableRisks().isEmpty()) {
            message = "存在可绕过风险，请填写原因并确认";
            render();
            return;
        }
        executeWrite(false, null);
    }

    void forceAddStudent(String reason) {
        if (closed) return;
        message = null;
        if (offering == null) {
            message = "缺少教学班上下文";
            render();
            return;
        }
        if (selectedUid == null) {
            message = "请先选择学生";
            render();
            return;
        }
        if (hasBlockingRisks()) {
            message = "存在阻断性风险，无法强制加入";
            render();
            return;
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            message = "请填写强制加入原因";
            render();
            return;
        }
        if (!previewCurrent) {
            message = "请先查看风险预览";
            render();
            return;
        }
        String confirmationMessage = "确认强制将学生「" + studentLabel() + "」加入教学班「"
                + offering.getOfferingCode() + "」？原因：" + trimmed;
        if (confirmation.apply("强制加入学生", confirmationMessage) != ButtonType.OK) {
            render();
            return;
        }
        executeWrite(true, trimmed);
    }

    private void executeWrite(boolean force, String reason) {
        List<Object> intent = List.of(offeringId(), selectedUid, force, reason == null ? "" : reason);
        WriteAttempt attempt = retryWrite;
        if (attempt == null || !attempt.intent().equals(intent)) {
            attempt = new WriteAttempt(intent, UUID.randomUUID().toString());
            retryWrite = attempt;
        }
        if (!pendingWrites.add(ADD_KEY)) {
            updateWriteControls(ADD_KEY);
            return;
        }
        updateWriteControls(ADD_KEY);
        render();

        CompletableFuture<model.course.admin.AdminOperationResultView<model.course.admin.OfferingStudentView>>
                future;
        try {
            future = service.addStudentToOffering(new AdminEnrollmentRequestDTO(
                    attempt.operationId(), offeringId(), selectedUid, force, reason));
        } catch (RuntimeException failure) {
            finishWriteFailure(ADD_KEY, failure);
            return;
        }
        if (future == null) {
            finishWriteFailure(ADD_KEY, new IllegalStateException("写操作未启动"));
            return;
        }
        future.whenComplete((result, failure) -> fxExecutor.accept(() -> {
            if (failure != null) {
                finishWriteFailure(ADD_KEY, failure);
                return;
            }
            pendingWrites.remove(ADD_KEY);
            retryWrite = null;
            mutated = true;
            updateWriteControls(ADD_KEY);
            message = result == null || result.getMessage() == null
                    ? "已将学生加入教学班" : result.getMessage();
            closed = true;
            onChanged.run();
            hideWindow();
        }));
    }

    private void finishWriteFailure(String key, Throwable failure) {
        pendingWrites.remove(key);
        Throwable cause = rootCause(failure);
        if (cause instanceof AdminCourseServiceException
                && ((AdminCourseServiceException) cause).getCode() == MessageCode.CONFLICT) {
            retryWrite = null;
            message = CONFLICT_MESSAGE;
            errorReporter.accept("操作冲突", CONFLICT_MESSAGE);
        } else {
            errorReporter.accept("操作失败", errorMessage(cause));
        }
        render();
    }

    private void updateWriteControls(String key) {
        boolean disabled = pendingWrites.contains(key);
        for (Consumer<Boolean> setDisabled : writeControls.getOrDefault(key, List.of())) {
            setDisabled.accept(disabled);
        }
    }

    void dispose() {
        if (closed) return;
        closed = true;
        searchGeneration++;
        previewGeneration++;
        hideWindow();
    }

    // ---------------------------------------------------------------- 状态查询

    String message() {
        return message;
    }

    boolean isSearching() {
        return searching;
    }

    boolean isPreviewPending() {
        return previewPending;
    }

    boolean isPreviewCurrent() {
        return previewCurrent;
    }

    List<StudentSearchResultView> results() {
        return results;
    }

    long totalCount() {
        return totalCount;
    }

    int pageNumber() {
        return pageNumber;
    }

    boolean hasNextPage() {
        return (long) pageNumber * PAGE_SIZE < totalCount;
    }

    boolean hasPreviousPage() {
        return pageNumber > 1;
    }

    StudentSearchResultView selectedStudent() {
        return selectedStudent;
    }

    List<ScheduleConflictDTO> risks() {
        return risks;
    }

    List<ScheduleConflictDTO> blockingRisks() {
        return filterSeverity(risks, ScheduleConflictSeverityDTO.BLOCKING);
    }

    List<ScheduleConflictDTO> overridableRisks() {
        return filterSeverity(risks, ScheduleConflictSeverityDTO.OVERRIDABLE);
    }

    boolean hasBlockingRisks() {
        return !blockingRisks().isEmpty();
    }

    /**
     * 只有当前预览无风险时才允许普通加入；可绕过风险改用强制路径。
     */
    boolean canAdd() {
        return !closed && offering != null && selectedUid != null && previewCurrent
                && !previewPending && !writeBusy()
                && !hasBlockingRisks() && overridableRisks().isEmpty();
    }

    /**
     * 只有存在可绕过风险、没有阻断风险且当前预览有效时才暴露强制加入。
     */
    boolean canForceAdd() {
        return !closed && offering != null && selectedUid != null && previewCurrent
                && !previewPending && !writeBusy() && !hasBlockingRisks()
                && !overridableRisks().isEmpty();
    }

    boolean isClosed() {
        return closed;
    }

    boolean hasMutated() {
        return mutated;
    }

    private boolean writeBusy() {
        return !pendingWrites.isEmpty();
    }

    private String offeringId() {
        return offering == null ? null : offering.getOfferingId();
    }

    private String studentLabel() {
        if (selectedStudent == null) return selectedUid == null ? "" : selectedUid;
        return selectedStudent.getName() + "(" + selectedStudent.getUid() + ")";
    }

    private static List<ScheduleConflictDTO> filterSeverity(
            List<ScheduleConflictDTO> source, ScheduleConflictSeverityDTO severity) {
        List<ScheduleConflictDTO> result = new ArrayList<>();
        for (ScheduleConflictDTO conflict : source) {
            if (conflict != null && conflict.getSeverity() == severity) result.add(conflict);
        }
        return List.copyOf(result);
    }

    // ---------------------------------------------------------------- FXML 动作

    @FXML
    private void handleSearch() {
        search(queryField == null ? null : queryField.getText());
    }

    @FXML
    private void handleRetry() {
        if (searchError != null) {
            loadResults();
        } else if (selectedUid != null && !previewCurrent) {
            requestPreview();
        }
    }

    @FXML
    private void handlePrevPage() {
        if (closed || searching || pageNumber <= 1) return;
        pageNumber--;
        loadResults();
    }

    @FXML
    private void handleNextPage() {
        if (closed || searching || !hasNextPage()) return;
        pageNumber++;
        loadResults();
    }

    @FXML
    private void handlePreview() {
        requestPreview();
    }

    @FXML
    private void handleAdd() {
        addStudent();
    }

    @FXML
    private void handleForceAdd() {
        forceAddStudent(overrideReasonField == null ? null : overrideReasonField.getText());
    }

    @FXML
    private void handleCancel() {
        dispose();
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (closed && dialogRoot == null) return;
        if (offeringContextLabel != null) {
            offeringContextLabel.setText(offering == null ? ""
                    : offering.getOfferingCode() + " · 已选 " + offering.getEnrolledCount()
                            + "/" + offering.getCapacity() + " 人");
        }
        if (selectedStudentLabel != null) {
            selectedStudentLabel.setText(selectedStudent == null
                    ? "未选择学生" : "已选择：" + studentLabel());
        }
        if (riskSummaryLabel != null) {
            String summary = riskSummary();
            boolean any = !risks.isEmpty();
            riskSummaryLabel.setText(summary);
            riskSummaryLabel.setVisible(any);
            riskSummaryLabel.setManaged(any);
        }
        if (riskArea != null) {
            riskArea.getChildren().clear();
            for (ScheduleConflictDTO conflict : blockingRisks()) {
                riskArea.getChildren().add(riskLabel(conflict, "course-admin-conflict-blocking"));
            }
            for (ScheduleConflictDTO conflict : overridableRisks()) {
                riskArea.getChildren().add(riskLabel(conflict, "course-admin-conflict-overridable"));
            }
        }
        if (resultLoadingLabel != null) {
            resultLoadingLabel.setVisible(searching);
            resultLoadingLabel.setManaged(searching);
        }
        if (resultErrorLabel != null) {
            boolean hasError = searchError != null;
            resultErrorLabel.setText(hasError ? searchError : "");
            resultErrorLabel.setVisible(hasError);
            resultErrorLabel.setManaged(hasError);
        }
        if (resultRetryButton != null) {
            boolean hasError = searchError != null;
            resultRetryButton.setVisible(hasError);
            resultRetryButton.setManaged(hasError);
        }
        if (resultEmptyLabel != null) {
            boolean empty = !searching && searchError == null && results.isEmpty()
                    && !query.isEmpty();
            resultEmptyLabel.setVisible(empty);
            resultEmptyLabel.setManaged(empty);
        }
        renderResults();
        renderPagination();
        if (validationLabel != null) {
            boolean show = message != null;
            validationLabel.setText(show ? message : "");
            validationLabel.setVisible(show);
            validationLabel.setManaged(show);
        }
        if (previewButton != null) {
            previewButton.setDisable(writeBusy() || selectedUid == null);
        }
        if (addButton != null) {
            addButton.setDisable(!canAdd());
        }
        if (forceAddButton != null) {
            boolean visible = canForceAdd();
            forceAddButton.setVisible(visible);
            forceAddButton.setManaged(visible);
            forceAddButton.setDisable(writeBusy() || !canForceAdd());
        }
        if (overrideReasonField != null) {
            overrideReasonField.setDisable(!canForceAdd() && !writeBusy());
        }
        if (searchButton != null) searchButton.setDisable(writeBusy());
        if (cancelButton != null) cancelButton.setDisable(false);
    }

    private void renderResults() {
        if (studentResultList == null) return;
        studentResultList.getChildren().clear();
        for (StudentSearchResultView student : results) {
            studentResultList.getChildren().add(createStudentRow(student));
        }
    }

    private Node createStudentRow(StudentSearchResultView student) {
        VBox info = new VBox(1.0,
                styledLabel(student.getName(), "course-admin-student-name"),
                styledLabel(student.getUid() + " · " + student.getMajor()
                        + " · " + student.getCohortYear() + " 级",
                        "course-admin-student-meta"));
        HBox.setHgrow(info, Priority.ALWAYS);

        Label status = styledLabel(student.getAcademicStatus(), "course-admin-row-meta");

        Button select = new Button(selectedUid != null && selectedUid.equals(student.getUid())
                ? "已选择" : "选择");
        select.getStyleClass().addAll("course-admin-action", "course-admin-student-select");
        select.setOnAction(event -> selectStudent(student.getUid()));

        HBox row = new HBox(8.0, info, status, select);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("course-admin-student-row");
        return row;
    }

    private void renderPagination() {
        boolean show = totalCount > PAGE_SIZE;
        if (paginationLabel != null) {
            paginationLabel.setText("第 " + pageNumber + " 页 · 共 " + totalCount + " 人");
            paginationLabel.setVisible(show);
            paginationLabel.setManaged(show);
        }
        if (prevPageButton != null) {
            prevPageButton.setVisible(show);
            prevPageButton.setManaged(show);
            prevPageButton.setDisable(!hasPreviousPage() || searching || writeBusy());
        }
        if (nextPageButton != null) {
            nextPageButton.setVisible(show);
            nextPageButton.setManaged(show);
            nextPageButton.setDisable(!hasNextPage() || searching || writeBusy());
        }
    }

    private String riskSummary() {
        int blocking = blockingRisks().size();
        int overridable = overridableRisks().size();
        if (blocking == 0 && overridable == 0) return "未发现风险";
        StringBuilder text = new StringBuilder();
        if (blocking > 0) text.append("阻断性风险 ").append(blocking).append(" 项");
        if (overridable > 0) {
            if (text.length() > 0) text.append(" · ");
            text.append("可绕过风险 ").append(overridable).append(" 项");
        }
        return text.toString();
    }

    private static Label riskLabel(ScheduleConflictDTO conflict, String styleClass) {
        String text = conflict.getMessage() == null ? conflict.getType() : conflict.getMessage();
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setWrapText(true);
        return label;
    }

    private static Label styledLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    private void hideWindow() {
        if (dialogRoot != null && dialogRoot.getScene() != null
                && dialogRoot.getScene().getWindow() instanceof Stage stage) {
            stage.close();
        }
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

    private record WriteAttempt(List<?> intent, String operationId) { }
}
