package controller;

import java.io.IOException;
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
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import model.course.admin.AdminCourseView;
import model.course.admin.AdminOfferingView;
import protocol.MessageCode;
import service.AdminCourseService;
import service.AdminCourseServices;
import service.SocketAdminCourseService.AdminCourseServiceException;
import util.AlertUtil;

/**
 * 管理员课程目录页面：FXML 负责稳定结构，控制器只生成数据相关的课程/教学班行与操作控件。
 */
public final class AdminCourseCatalogController {
    static final String CONFLICT_MESSAGE = "数据已被其他管理员修改";
    static final String STATUS_ALL = "全部";
    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_ARCHIVED = "ARCHIVED";

    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";
    private static final String CANCELLED = "CANCELLED";
    private static final String NOT_OPEN = "NOT_OPEN";
    private static final String OPEN = "OPEN";
    private static final String STOPPED = "STOPPED";
    private static final String SCHEDULED = "SCHEDULED";
    private static final String UNSCHEDULED = "UNSCHEDULED";

    private final AdminCourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private final Set<String> pendingWrites = new HashSet<>();
    private final Map<String, List<Consumer<Boolean>>> writeControls = new HashMap<>();
    private final Set<String> failedOfferingCourses = new HashSet<>();

    private List<AdminCourseView> courses = List.of();
    private String query = "";
    private String status = STATUS_ALL;
    private boolean loading;
    private String errorText;
    private boolean syncingFilters;
    private long listGeneration;

    @FXML private TextField searchField;
    @FXML private ComboBox<String> statusFilter;
    @FXML private Button refreshButton;
    @FXML private Button createCourseButton;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;
    @FXML private VBox courseList;

    public AdminCourseCatalogController() {
        this(AdminCourseServices.current(), AlertUtil::showConfirm,
                AlertUtil::showError, Platform::runLater);
    }

    AdminCourseCatalogController(AdminCourseService service,
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
        statusFilter.getItems().setAll(STATUS_ALL, STATUS_ACTIVE, STATUS_ARCHIVED);
        statusFilter.setValue(STATUS_ALL);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!syncingFilters) applyFilters(newValue, statusFilter.getValue());
        });
        statusFilter.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!syncingFilters) applyFilters(searchField.getText(), newValue);
        });
        render();
    }

    @FXML
    public void refresh() {
        if (searchField != null) {
            query = searchField.getText() == null ? "" : searchField.getText();
        }
        if (statusFilter != null && statusFilter.getValue() != null) {
            status = statusFilter.getValue();
        }
        loadCourses(query, status);
    }

    /**
     * 应用查询与状态筛选并重新加载；选择项在后续刷新与冲突刷新中保持不变。
     */
    void applyFilters(String nextQuery, String nextStatus) {
        query = nextQuery == null ? "" : nextQuery;
        status = nextStatus == null ? STATUS_ALL : nextStatus;
        syncingFilters = true;
        try {
            if (searchField != null && !query.equals(searchField.getText())) {
                searchField.setText(query);
            }
            if (statusFilter != null && !status.equals(statusFilter.getValue())) {
                statusFilter.setValue(status);
            }
        } finally {
            syncingFilters = false;
        }
        loadCourses(query, status);
    }

    String query() {
        return query;
    }

    String status() {
        return status;
    }

    List<AdminCourseView> courses() {
        return courses;
    }

    boolean isLoading() {
        return loading;
    }

    String errorText() {
        return errorText;
    }

    boolean isWritePending(String key) {
        return pendingWrites.contains(key);
    }

    void registerWriteControl(String key, Consumer<Boolean> setDisabled) {
        writeControls.computeIfAbsent(key, ignored -> new ArrayList<>()).add(setDisabled);
        setDisabled.accept(pendingWrites.contains(key));
    }

    /**
     * 执行一次写操作：同一意图只生成一个 operationId，重入点击被忽略。
     */
    void executeWrite(String key, Function<String, CompletableFuture<?>> action,
            Runnable onSuccess) {
        if (!pendingWrites.add(key)) {
            updateWriteControls(key);
            return;
        }
        updateWriteControls(key);

        CompletableFuture<?> future;
        try {
            future = action.apply(UUID.randomUUID().toString());
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
            updateWriteControls(key);
            if (onSuccess != null) onSuccess.run();
            loadCourses(query, status);
        }));
    }

    /**
     * 需要确认的破坏性写操作；取消或关闭确认框不产生任何写请求。
     */
    void executeDestructiveWrite(String key, String title, String message,
            Function<String, CompletableFuture<?>> action) {
        if (confirmation.apply(title, message) != ButtonType.OK) return;
        executeWrite(key, action, null);
    }

    private void finishWriteFailure(String key, Throwable failure) {
        pendingWrites.remove(key);
        updateWriteControls(key);
        Throwable cause = rootCause(failure);
        if (cause instanceof AdminCourseServiceException
                && ((AdminCourseServiceException) cause).getCode() == MessageCode.CONFLICT) {
            errorReporter.accept("操作冲突", CONFLICT_MESSAGE);
            loadCourses(query, status);
            return;
        }
        errorReporter.accept("操作失败", errorMessage(cause));
        render();
    }

    private void updateWriteControls(String key) {
        boolean disabled = pendingWrites.contains(key);
        for (Consumer<Boolean> setDisabled : writeControls.getOrDefault(key, List.of())) {
            setDisabled.accept(disabled);
        }
    }

    private void loadCourses(String nextQuery, String nextStatus) {
        String sentQuery = blankToNull(nextQuery);
        String sentStatus = STATUS_ALL.equals(nextStatus) ? null : blankToNull(nextStatus);
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listCourses(sentQuery, sentStatus).whenComplete((loaded, failure) ->
                fxExecutor.accept(() -> {
                    if (generation != listGeneration) return;
                    loading = false;
                    if (failure != null) {
                        errorText = "课程加载失败，请重试";
                        render();
                        return;
                    }
                    errorText = null;
                    courses = List.copyOf(loaded == null ? List.of() : loaded);
                    render();
                }));
    }

    private void render() {
        boolean hasError = errorText != null;
        boolean empty = !loading && !hasError && courses.isEmpty();
        if (loadingLabel != null) {
            loadingLabel.setVisible(loading);
            loadingLabel.setManaged(loading);
        }
        if (errorLabel != null) {
            errorLabel.setText(hasError ? errorText : "");
            errorLabel.setVisible(hasError);
            errorLabel.setManaged(hasError);
        }
        if (errorRetryButton != null) {
            errorRetryButton.setVisible(hasError);
            errorRetryButton.setManaged(hasError);
        }
        if (emptyLabel != null) {
            emptyLabel.setVisible(empty);
            emptyLabel.setManaged(empty);
        }
        if (courseList == null) return;
        writeControls.clear();
        courseList.getChildren().clear();
        for (AdminCourseView course : courses) {
            courseList.getChildren().add(createCourseRow(course));
        }
    }

    private VBox createCourseRow(AdminCourseView course) {
        VBox row = new VBox(0.0);
        row.getStyleClass().add("course-admin-row");

        VBox titleBlock = new VBox(1.0,
                styledLabel(course.getCourseName(), "course-admin-row-title"),
                styledLabel(course.getCourseCode(), "course-admin-row-code"));
        titleBlock.getStyleClass().add("course-admin-row-title-block");
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        HBox header = new HBox(8.0,
                titleBlock,
                styledLabel(course.getCourseType(), "course-admin-row-meta"),
                styledLabel(course.getCredit() + " 学分", "course-admin-row-meta"),
                styledLabel(course.getCreditHours() + " 学时", "course-admin-row-meta"),
                styledLabel("教学班 " + course.getOfferingCount() + " 个", "course-admin-row-meta"),
                styledLabel(courseStatusText(course.getStatus()),
                        "course-admin-status-label", courseStatusStyle(course)));
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("course-admin-row-header");

        Button detailsButton = actionButton("详情", "course-admin-details-button");
        VBox offeringList = new VBox(4.0);
        offeringList.getStyleClass().add("course-admin-offering-list");
        offeringList.setVisible(false);
        offeringList.setManaged(false);
        detailsButton.setOnAction(event -> toggleOfferings(course, detailsButton, offeringList));

        Button editButton = actionButton("编辑", "course-admin-edit-button");
        editButton.setOnAction(event -> openCourseEditor(course));

        Button offeringButton = actionButton("开设教学班", "course-admin-add-offering-button");
        offeringButton.setOnAction(event -> openOfferingEditor(course, null));

        HBox actions = new HBox(6.0, spacer(), detailsButton, editButton, offeringButton,
                courseMenu(course));
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("course-admin-row-actions");

        row.getChildren().addAll(header, actions, offeringList);
        return row;
    }

    private MenuButton courseMenu(AdminCourseView course) {
        MenuButton menu = new MenuButton("更多");
        menu.getStyleClass().add("course-admin-row-menu");
        String key = "course:" + course.getCourseId() + ":status";
        registerWriteControl(key, menu::setDisable);

        if (ARCHIVED.equals(course.getStatus())) {
            MenuItem restore = new MenuItem("恢复课程");
            restore.getStyleClass().add("course-admin-restore-item");
            restore.setOnAction(event -> executeWrite(key,
                    operationId -> service.restoreCourse(
                            course.getCourseId(), course.getVersion(), operationId), null));
            menu.getItems().add(restore);
        } else {
            MenuItem archive = new MenuItem("归档课程");
            archive.getStyleClass().add("course-admin-archive-item");
            archive.setOnAction(event -> executeDestructiveWrite(key, "归档课程",
                    "确认归档“" + course.getCourseName() + "”？归档后学生将无法选课。",
                    operationId -> service.archiveCourse(
                            course.getCourseId(), course.getVersion(), operationId)));
            menu.getItems().add(archive);
        }
        return menu;
    }

    private void toggleOfferings(AdminCourseView course, Button toggle, VBox container) {
        boolean expanded = !container.isVisible();
        container.setVisible(expanded);
        container.setManaged(expanded);
        toggle.setText(expanded ? "收起" : "详情");
        if (!expanded) return;

        boolean reload = container.getChildren().isEmpty()
                || failedOfferingCourses.remove(course.getCourseId());
        if (!reload) return;
        container.getChildren().setAll(
                styledLabel("正在加载教学班...", "course-admin-loading-text"));
        service.listOfferings(course.getCourseId()).whenComplete((loaded, failure) ->
                fxExecutor.accept(() -> {
                    if (container.getScene() == null) return;
                    if (failure != null) {
                        failedOfferingCourses.add(course.getCourseId());
                        container.getChildren().setAll(styledLabel(
                                "教学班加载失败，请重试", "course-admin-error-text"));
                        errorReporter.accept("教学班加载失败", errorMessage(failure));
                        return;
                    }
                    container.getChildren().clear();
                    if (loaded == null || loaded.isEmpty()) {
                        container.getChildren().add(
                                styledLabel("暂无教学班", "course-admin-empty-text"));
                        return;
                    }
                    for (AdminOfferingView offering : loaded) {
                        container.getChildren().add(createOfferingRow(offering));
                    }
                }));
    }

    private VBox createOfferingRow(AdminOfferingView offering) {
        VBox row = new VBox(3.0);
        row.getStyleClass().add("course-admin-offering-row");

        HBox header = new HBox(8.0,
                styledLabel(offering.getOfferingCode(), "course-admin-offering-code"),
                styledLabel(termText(offering), "course-admin-row-meta"),
                styledLabel("教师 " + displayName(
                        offering.getTeacherName(), offering.getTeacherUid()),
                        "course-admin-row-meta"),
                styledLabel("助教 " + displayName(
                        offering.getAssistantName(), offering.getAssistantUid()),
                        "course-admin-row-meta"),
                styledLabel(offering.getEnrolledCount() + "/" + offering.getCapacity(),
                        "course-admin-capacity"),
                styledLabel(scheduleStatusText(offering.getScheduleStatus()),
                        "course-admin-row-meta"),
                styledLabel(offeringStatusText(offering.getStatus()),
                        "course-admin-status-label", offeringStatusStyle(offering.getStatus())));
        header.setAlignment(Pos.CENTER_LEFT);

        boolean editable = OfferingEditorDialogController.isEditable(offering);
        Button editButton = actionButton("编辑", "course-admin-offering-edit-button");
        editButton.setDisable(!editable);
        editButton.setOnAction(event -> openOfferingEditor(null, offering));

        Button cancelButton = actionButton("取消教学班", "course-admin-offering-cancel-button");
        String cancelKey = "offering:" + offering.getOfferingId() + ":cancel";
        registerWriteControl(cancelKey, cancelButton::setDisable);
        cancelButton.setDisable(cancelButton.isDisabled() || CANCELLED.equals(offering.getStatus()));
        cancelButton.setOnAction(event -> executeDestructiveWrite(cancelKey, "取消教学班",
                "确认取消教学班“" + offering.getOfferingCode() + "”？",
                operationId -> service.cancelOffering(
                        offering.getOfferingId(), offering.getVersion(), operationId)));

        HBox actions = new HBox(6.0, spacer(), editButton, cancelButton,
                offeringMenu(offering));
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("course-admin-offering-actions");

        row.getChildren().addAll(header, actions);
        return row;
    }

    private MenuButton offeringMenu(AdminOfferingView offering) {
        MenuButton menu = new MenuButton("更多");
        menu.getStyleClass().add("course-admin-offering-menu");
        String key = "offering:" + offering.getOfferingId() + ":delete";
        registerWriteControl(key, menu::setDisable);

        MenuItem delete = new MenuItem("删除教学班");
        delete.getStyleClass().add("course-admin-offering-delete-item");
        delete.setOnAction(event -> executeDestructiveWrite(key, "删除教学班",
                "确认删除教学班“" + offering.getOfferingCode() + "”？该操作不可恢复。",
                operationId -> service.deleteDraftOffering(
                        offering.getOfferingId(), offering.getVersion(), operationId)));
        menu.getItems().add(delete);
        return menu;
    }

    @FXML
    private void openCreateCourse() {
        openCourseEditor(null);
    }

    private void openCourseEditor(AdminCourseView course) {
        FXMLLoader loader = new FXMLLoader(AdminCourseCatalogController.class.getResource(
                "/resources/fxml/CourseEditorDialog.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            errorReporter.accept("打开失败", "无法打开课程编辑窗口：" + errorMessage(failure));
            return;
        }
        CourseEditorDialogController dialogController = loader.getController();
        if (course == null) {
            dialogController.prepareForCreate();
        } else {
            dialogController.prepareForEdit(course);
        }
        String key = course == null
                ? "course:create" : "course:" + course.getCourseId() + ":update";
        dialogController.setOnSubmit(() -> executeWrite(key, operationId -> {
            CourseEditorRequestDTO request = dialogController.collectRequest(operationId);
            return course == null
                    ? service.createCourse(request) : service.updateCourse(request);
        }, null));
        showDialog(root, course == null ? "新增课程" : "编辑课程");
    }

    private void openOfferingEditor(AdminCourseView course, AdminOfferingView offering) {
        FXMLLoader loader = new FXMLLoader(AdminCourseCatalogController.class.getResource(
                "/resources/fxml/OfferingEditorDialog.fxml"));
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            errorReporter.accept("打开失败", "无法打开教学班编辑窗口：" + errorMessage(failure));
            return;
        }
        OfferingEditorDialogController dialogController = loader.getController();
        if (offering == null) {
            dialogController.prepareForCreate(course.getCourseId());
        } else {
            dialogController.prepareForEdit(offering);
        }
        String key = offering == null
                ? "course:" + course.getCourseId() + ":offering:create"
                : "offering:" + offering.getOfferingId() + ":update";
        dialogController.setOnSubmit(() -> executeWrite(key, operationId -> {
            OfferingEditorRequestDTO request = dialogController.collectRequest(operationId);
            return offering == null
                    ? service.createOffering(request) : service.updateOffering(request);
        }, null));
        showDialog(root, offering == null ? "新增教学班" : "编辑教学班");
    }

    private void showDialog(Parent root, String title) {
        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        Window owner = courseList == null || courseList.getScene() == null
                ? null : courseList.getScene().getWindow();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(title);
        stage.setScene(new Scene(root));
        stage.show();
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

    private static Label styledLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    static String courseStatusText(String status) {
        if (ACTIVE.equals(status)) return "启用";
        if (ARCHIVED.equals(status)) return "已归档";
        return status == null ? "未知" : status;
    }

    private static String courseStatusStyle(AdminCourseView course) {
        return ARCHIVED.equals(course.getStatus())
                ? "course-admin-status-archived" : "course-admin-status-active";
    }

    static String offeringStatusText(String status) {
        if (NOT_OPEN.equals(status)) return "未开放";
        if (OPEN.equals(status)) return "开放中";
        if (STOPPED.equals(status)) return "已停止";
        if (CANCELLED.equals(status)) return "已取消";
        return status == null ? "未知" : status;
    }

    private static String offeringStatusStyle(String status) {
        String suffix = status == null ? "unknown" : status.toLowerCase(java.util.Locale.ROOT);
        return "course-admin-offering-status-" + suffix;
    }

    private static String scheduleStatusText(String scheduleStatus) {
        if (SCHEDULED.equals(scheduleStatus)) return "已排课";
        if (UNSCHEDULED.equals(scheduleStatus)) return "未排课";
        return scheduleStatus == null ? "排课未知" : scheduleStatus;
    }

    private static String termText(AdminOfferingView offering) {
        return offering.getAcademicYear() + " 学年 第 " + offering.getSemester() + " 学期";
    }

    private static String displayName(String name, String uid) {
        if (name != null && !name.isBlank()) return name;
        if (uid != null && !uid.isBlank()) return uid;
        return "待定";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
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
