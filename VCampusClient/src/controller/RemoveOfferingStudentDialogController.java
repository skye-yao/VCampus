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

import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
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
import model.course.admin.AdminOperationResultView;
import model.course.admin.OfferingStudentView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 删除学生对话框：展示教学班当前学生、支持学号/姓名过滤，并在二次确认后移除。
 *
 * <p>不可移除的学生展示明确原因并禁用删除按钮。列表响应带 generation 校验，
 * 写入完成后重载权威花名册并通知目录刷新展开的教学班行。</p>
 */
/** 管理员从教学班移除学生的 JavaFX 对话框控制器。 */
public final class RemoveOfferingStudentDialogController {
    static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";

    private static final int PAGE_SIZE = 10;

    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;

    private final Set<String> pendingWrites = new HashSet<>();
    private final Map<String, List<Consumer<Boolean>>> writeControls = new HashMap<>();
    private final Map<String, WriteAttempt> retryWrites = new HashMap<>();

    private AdminOfferingView offering;
    private String query = "";
    private List<OfferingStudentView> students = List.of();
    private long totalCount;
    private int pageNumber = 1;
    private boolean loading;
    private String errorText;
    private String message;
    private long listGeneration;
    private boolean closed;
    private boolean mutated;
    private Runnable onChanged = () -> { };

    @FXML private Node dialogRoot;
    @FXML private Label offeringContextLabel;
    @FXML private TextField queryField;
    @FXML private Button filterButton;
    @FXML private VBox studentList;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private Label paginationLabel;
    @FXML private Button prevPageButton;
    @FXML private Button nextPageButton;
    @FXML private Label validationLabel;
    @FXML private Button cancelButton;

    public RemoveOfferingStudentDialogController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm,
                AlertUtil::showError, Platform::runLater);
    }

    RemoveOfferingStudentDialogController(AdminCourseService service,
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
                    (observable, oldValue, newValue) -> applyFilter(newValue));
        }
        render();
    }

    public void prepareForOffering(AdminOfferingView offering) {
        this.offering = offering;
        this.query = "";
        this.students = List.of();
        this.totalCount = 0;
        this.pageNumber = 1;
        this.loading = false;
        this.errorText = null;
        this.message = null;
        this.closed = false;
        this.mutated = false;
        this.listGeneration++;
        this.retryWrites.clear();
        render();
    }

    public void setOnChanged(Runnable handler) {
        this.onChanged = handler == null ? () -> { } : handler;
    }

    // ---------------------------------------------------------------- 花名册

    void loadStudents() {
        if (closed) return;
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        CompletableFuture<AdminEnrollmentPageView<OfferingStudentView>> future;
        try {
            future = service.listOfferingStudentsPage(offeringId(), query, pageNumber, PAGE_SIZE);
        } catch (RuntimeException failure) {
            loading = false;
            errorText = "学生名单加载失败，请重试";
            render();
            return;
        }
        if (future == null) {
            loading = false;
            errorText = "学生名单加载失败，请重试";
            render();
            return;
        }
        future.whenComplete((page, failure) -> fxExecutor.accept(() -> {
            if (closed || generation != listGeneration) return;
            loading = false;
            if (failure != null) {
                errorText = "学生名单加载失败，请重试";
                render();
                return;
            }
            students = page == null ? List.of() : List.copyOf(page.getItems());
            totalCount = page == null ? 0 : page.getTotalCount();
            pageNumber = page == null ? pageNumber : page.getPageNumber();
            render();
        }));
    }

    void applyFilter(String nextQuery) {
        if (closed) return;
        this.query = nextQuery == null ? "" : nextQuery.trim();
        this.pageNumber = 1;
        this.message = null;
        loadStudents();
    }

    // ---------------------------------------------------------------- 移除

    /**
     * 二次确认后移除；不可移除的行只展示原因，不确认也不写入。
     */
    void requestRemove(OfferingStudentView row) {
        if (closed || row == null) return;
        message = null;
        if (writeBusy()) return;
        if (!row.isRemovable()) {
            message = row.getBlockedReason() == null ? "该学生当前不可移除" : row.getBlockedReason();
            render();
            return;
        }
        if (confirmation.apply("移除学生", "确认将学生「" + studentLabel(row)
                + "」从教学班「" + offeringCode() + "」移除？") != ButtonType.OK) {
            render();
            return;
        }
        executeRemove(row);
    }

    private void executeRemove(OfferingStudentView row) {
        String key = "enrollment:remove:" + row.getEnrollmentId();
        List<Object> intent = List.of(row.getEnrollmentId(), offeringId(), row.getUid());
        WriteAttempt attempt = retryWrites.get(key);
        if (attempt == null || !attempt.intent().equals(intent)) {
            attempt = new WriteAttempt(intent, UUID.randomUUID().toString());
            retryWrites.put(key, attempt);
        }
        if (!pendingWrites.add(key)) {
            updateWriteControls(key);
            return;
        }
        updateWriteControls(key);
        render();

        CompletableFuture<AdminOperationResultView<OfferingStudentView>> future;
        try {
            future = service.removeStudentFromOffering(new AdminEnrollmentRequestDTO(
                    attempt.operationId(), offeringId(), row.getUid(), false, null));
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
            retryWrites.remove(key);
            mutated = true;
            updateWriteControls(key);
            message = result == null || result.getMessage() == null
                    ? "已从教学班移除学生" : result.getMessage();
            onChanged.run();
            if (closed) return;
            loadStudents();
        }));
    }

    private void finishWriteFailure(String key, Throwable failure) {
        pendingWrites.remove(key);
        Throwable cause = rootCause(failure);
        if (cause instanceof AdminCourseServiceException
                && ((AdminCourseServiceException) cause).getCode() == MessageCode.CONFLICT) {
            retryWrites.remove(key);
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
        listGeneration++;
        hideWindow();
    }

    // ---------------------------------------------------------------- 状态查询

    List<OfferingStudentView> students() {
        return students;
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

    boolean isLoading() {
        return loading;
    }

    String errorText() {
        return errorText;
    }

    String message() {
        return message;
    }

    /**
     * 只有可移除且没有进行中的写入时才允许移除。
     */
    boolean canRemove(OfferingStudentView row) {
        return !closed && row != null && row.isRemovable() && !writeBusy();
    }

    String blockedReason(String uid) {
        if (uid == null) return null;
        for (OfferingStudentView row : students) {
            if (uid.equals(row.getUid())) return row.getBlockedReason();
        }
        return null;
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

    private String offeringCode() {
        return offering == null ? "" : offering.getOfferingCode();
    }

    private static String studentLabel(OfferingStudentView row) {
        return row.getName() + "(" + row.getUid() + ")";
    }

    // ---------------------------------------------------------------- FXML 动作

    @FXML
    private void handleFilter() {
        applyFilter(queryField == null ? null : queryField.getText());
    }

    @FXML
    private void handleRetry() {
        loadStudents();
    }

    @FXML
    private void handlePrevPage() {
        if (closed || loading || pageNumber <= 1) return;
        pageNumber--;
        loadStudents();
    }

    @FXML
    private void handleNextPage() {
        if (closed || loading || !hasNextPage()) return;
        pageNumber++;
        loadStudents();
    }

    @FXML
    private void handleCancel() {
        dispose();
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        if (offeringContextLabel != null) {
            offeringContextLabel.setText(offering == null ? ""
                    : offering.getOfferingCode() + " · 已选 " + offering.getEnrolledCount()
                            + "/" + offering.getCapacity() + " 人");
        }
        if (loadingLabel != null) {
            loadingLabel.setVisible(loading);
            loadingLabel.setManaged(loading);
        }
        if (errorLabel != null) {
            boolean hasError = errorText != null;
            errorLabel.setText(hasError ? errorText : "");
            errorLabel.setVisible(hasError);
            errorLabel.setManaged(hasError);
        }
        if (errorRetryButton != null) {
            boolean hasError = errorText != null;
            errorRetryButton.setVisible(hasError);
            errorRetryButton.setManaged(hasError);
        }
        if (emptyLabel != null) {
            boolean empty = !loading && errorText == null && students.isEmpty();
            emptyLabel.setVisible(empty);
            emptyLabel.setManaged(empty);
        }
        renderStudents();
        renderPagination();
        if (validationLabel != null) {
            boolean show = message != null;
            validationLabel.setText(show ? message : "");
            validationLabel.setVisible(show);
            validationLabel.setManaged(show);
        }
        if (filterButton != null) filterButton.setDisable(writeBusy());
        if (cancelButton != null) cancelButton.setDisable(false);
    }

    private void renderStudents() {
        if (studentList == null) return;
        studentList.getChildren().clear();
        for (OfferingStudentView row : students) {
            studentList.getChildren().add(createStudentRow(row));
        }
    }

    private Node createStudentRow(OfferingStudentView row) {
        VBox info = new VBox(1.0,
                styledLabel(row.getName(), "course-admin-student-name"),
                styledLabel(row.getUid() + " · " + row.getMajor()
                        + " · " + row.getCohortYear() + " 级",
                        "course-admin-student-meta"));
        HBox.setHgrow(info, Priority.ALWAYS);

        Button remove = new Button("移除");
        remove.getStyleClass().addAll("course-admin-action", "course-admin-student-remove");
        String key = "enrollment:remove:" + row.getEnrollmentId();
        registerWriteControl(key, remove::setDisable);
        remove.setDisable(remove.isDisabled() || !row.isRemovable());
        remove.setOnAction(event -> requestRemove(row));

        HBox content = new HBox(8.0, info, remove);
        content.setAlignment(Pos.CENTER_LEFT);

        VBox container = new VBox(2.0, content);
        container.getStyleClass().add("course-admin-student-row");
        if (!row.isRemovable() && row.getBlockedReason() != null) {
            Label blocked = new Label(row.getBlockedReason());
            blocked.getStyleClass().add("course-admin-student-blocked");
            blocked.setWrapText(true);
            container.getChildren().add(blocked);
        }
        return container;
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
            prevPageButton.setDisable(!hasPreviousPage() || loading || writeBusy());
        }
        if (nextPageButton != null) {
            nextPageButton.setVisible(show);
            nextPageButton.setManaged(show);
            nextPageButton.setDisable(!hasNextPage() || loading || writeBusy());
        }
    }

    void registerWriteControl(String key, Consumer<Boolean> setDisabled) {
        writeControls.computeIfAbsent(key, ignored -> new ArrayList<>()).add(setDisabled);
        setDisabled.accept(pendingWrites.contains(key));
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
