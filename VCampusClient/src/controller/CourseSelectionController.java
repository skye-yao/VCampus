package controller;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.course.CourseOfferingView;
import model.course.SelectionStatus;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class CourseSelectionController {
    private final CourseService service;
    private final BiFunction<String, String, ButtonType> confirmation;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private final Set<Long> pendingOfferingIds = new HashSet<>();
    private List<CourseOfferingView> offerings = Collections.emptyList();
    private SelectionStatus selectedStatus;
    private boolean planConfirmationPending;
    private long loadGeneration;

    @FXML private TextField searchField;
    @FXML private ComboBox<String> typeFilter;
    @FXML private ToggleButton allTabButton;
    @FXML private ToggleButton planTabButton;
    @FXML private ToggleButton waitlistTabButton;
    @FXML private ToggleButton enrolledTabButton;
    @FXML private Button confirmPlanButton;
    @FXML private VBox courseList;
    @FXML private Label requiredCountLabel;
    @FXML private Label electiveCountLabel;
    @FXML private Label generalCountLabel;

    public CourseSelectionController() {
        this(CourseServices.current(), AlertUtil::showConfirm, AlertUtil::showError,
                CourseSelectionController::runOnFxThread);
    }

    CourseSelectionController(CourseService service,
            BiFunction<String, String, ButtonType> confirmation,
            BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor) {
        this.service = service;
        this.confirmation = confirmation;
        this.errorReporter = errorReporter;
        this.fxExecutor = fxExecutor;
    }

    @FXML
    public void initialize() {
        typeFilter.getItems().addAll("全部", "必修", "专业选修", "通识选修");
        typeFilter.setValue("全部");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> renderCourses());
        typeFilter.valueProperty().addListener((observable, oldValue, newValue) -> renderCourses());
        selectTab(null);
    }

    public void refresh() {
        fxExecutor.accept(this::loadOfferings);
    }

    List<CourseOfferingView> filterCourses(List<CourseOfferingView> source,
            SelectionStatus status, String keyword, String courseType) {
        String normalizedKeyword = keyword == null
                ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        boolean allTypes = courseType == null || courseType.isEmpty() || "全部".equals(courseType);
        List<CourseOfferingView> filtered = new ArrayList<>();

        for (CourseOfferingView course : source) {
            boolean statusMatches = status == null || course.getSelectionStatus() == status;
            boolean keywordMatches = normalizedKeyword.isEmpty()
                    || course.getCourseName().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                    || course.getCourseCode().toLowerCase(Locale.ROOT).contains(normalizedKeyword);
            boolean typeMatches = allTypes || courseType.equals(course.getCourseType());
            if (statusMatches && keywordMatches && typeMatches) {
                filtered.add(course);
            }
        }
        return filtered;
    }

    boolean tryBeginOfferingOperation(long offeringId) {
        return pendingOfferingIds.add(offeringId);
    }

    void finishOfferingOperation(long offeringId) {
        pendingOfferingIds.remove(offeringId);
    }

    boolean isOfferingOperationPending(long offeringId) {
        return pendingOfferingIds.contains(offeringId);
    }

    boolean tryBeginPlanConfirmation() {
        if (planConfirmationPending) {
            return false;
        }
        planConfirmationPending = true;
        return true;
    }

    void finishPlanConfirmation() {
        planConfirmationPending = false;
    }

    boolean isPlanConfirmationPending() {
        return planConfirmationPending;
    }

    long nextLoadGeneration() {
        return ++loadGeneration;
    }

    boolean isCurrentLoadGeneration(long generation) {
        return generation == loadGeneration;
    }

    void applyOfferingOperationState(long offeringId, Consumer<Boolean> setDisabled) {
        setDisabled.accept(isOfferingOperationPending(offeringId));
    }

    void applyPlanConfirmationState(boolean hasPlannedCourse,
            Consumer<Boolean> setDisabled) {
        setDisabled.accept(!hasPlannedCourse || isPlanConfirmationPending());
    }

    @FXML
    private void showAllCourses() {
        selectTab(null);
    }

    @FXML
    private void showPlannedCourses() {
        selectTab(SelectionStatus.PLANNED);
    }

    @FXML
    private void showWaitlistedCourses() {
        selectTab(SelectionStatus.WAITLISTED);
    }

    @FXML
    private void showEnrolledCourses() {
        selectTab(SelectionStatus.ENROLLED);
    }

    @FXML
    private void confirmPlan() {
        if (!tryBeginPlanConfirmation()) {
            confirmPlanButton.setDisable(true);
            return;
        }
        confirmPlanButton.setDisable(true);
        if (confirmation.apply("确认选课", "确认提交计划中的全部课程？") != ButtonType.OK) {
            finishPlanConfirmation();
            updateConfirmPlanButton();
            return;
        }
        executePlanConfirmation(service::confirmPlan);
    }

    private void loadOfferings() {
        requestOfferings(loaded -> {
            offerings = new ArrayList<>(loaded);
            updateSummary();
            renderCourses();
        }, error -> errorReporter.accept("加载失败", errorMessage(error)));
    }

    void requestOfferings(Consumer<List<CourseOfferingView>> onLoaded,
            Consumer<Throwable> onError) {
        long generation = nextLoadGeneration();
        service.loadOfferings().whenComplete((loaded, error) -> fxExecutor.accept(() -> {
            if (!isCurrentLoadGeneration(generation)) {
                return;
            }
            if (error != null) {
                onError.accept(error);
                return;
            }
            onLoaded.accept(loaded);
        }));
    }

    private void selectTab(SelectionStatus status) {
        selectedStatus = status;
        allTabButton.setSelected(status == null);
        planTabButton.setSelected(status == SelectionStatus.PLANNED);
        waitlistTabButton.setSelected(status == SelectionStatus.WAITLISTED);
        enrolledTabButton.setSelected(status == SelectionStatus.ENROLLED);
        confirmPlanButton.setVisible(status == SelectionStatus.PLANNED);
        confirmPlanButton.setManaged(status == SelectionStatus.PLANNED);
        renderCourses();
    }

    private void renderCourses() {
        courseList.getChildren().clear();
        List<CourseOfferingView> filtered = filterCourses(
                offerings, selectedStatus, searchField.getText(), typeFilter.getValue());
        updateConfirmPlanButton();

        if (filtered.isEmpty()) {
            Label emptyState = new Label(emptyStateText());
            emptyState.getStyleClass().add("course-empty-state");
            emptyState.setMaxWidth(Double.MAX_VALUE);
            courseList.getChildren().add(emptyState);
            return;
        }

        for (CourseOfferingView course : filtered) {
            courseList.getChildren().add(createCourseRow(course));
        }
    }

    private VBox createCourseRow(CourseOfferingView course) {
        VBox row = new VBox();
        row.getStyleClass().add("course-row");

        VBox details = createCourseDetails(course);
        details.setVisible(false);
        details.setManaged(false);

        Button expandButton = new Button("+");
        expandButton.getStyleClass().add("course-expand-button");
        expandButton.setOnAction(event -> {
            boolean expanded = !details.isVisible();
            details.setVisible(expanded);
            details.setManaged(expanded);
            expandButton.setText(expanded ? "-" : "+");
        });

        VBox titleBlock = new VBox(2.0,
                styledLabel(course.getCourseName(), "course-row-title"),
                styledLabel(course.getCourseCode(), "course-row-code"));
        titleBlock.getStyleClass().add("course-row-title-block");
        HBox.setHgrow(titleBlock, Priority.ALWAYS);

        Label statusLabel = styledLabel(statusText(course.getSelectionStatus()),
                statusStyle(course.getSelectionStatus()));
        HBox header = new HBox(10.0,
                expandButton,
                titleBlock,
                styledLabel(course.getCourseType(), "course-row-meta"),
                styledLabel(course.getCredit() + " 学分", "course-row-meta"),
                styledLabel(course.getCreditHours() + " 学时", "course-row-meta"),
                styledLabel(course.getTeacher(), "course-row-meta"),
                styledLabel(course.getEnrolledCount() + "/" + course.getCapacity(),
                        "course-row-meta"),
                statusLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("course-row-header");

        row.getChildren().addAll(header, details);
        return row;
    }

    private VBox createCourseDetails(CourseOfferingView course) {
        Label schedule = styledLabel(
                "上课时间：" + course.getSchedule() + "    地点：" + course.getLocation(),
                "course-detail-text");
        Label description = styledLabel("课程简介：" + course.getDescription(),
                "course-detail-text");
        Label prerequisites = styledLabel("先修要求：" + course.getPrerequisites(),
                "course-detail-text");
        description.setWrapText(true);
        prerequisites.setWrapText(true);

        Button actionButton = createActionButton(course);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(spacer, actionButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox details = new VBox(7.0, schedule, description, prerequisites, actions);
        details.getStyleClass().add("course-row-details");
        return details;
    }

    private Button createActionButton(CourseOfferingView course) {
        Button actionButton = new Button();
        actionButton.getStyleClass().add("course-row-action");
        SelectionStatus status = course.getSelectionStatus();

        if (status == SelectionStatus.AVAILABLE) {
            if (course.getEnrolledCount() >= course.getCapacity()) {
                actionButton.setText("加入候补");
                actionButton.setOnAction(event -> executeTransition(course.getOfferingId(),
                        actionButton,
                        () -> service.joinWaitlist(course.getOfferingId())));
            } else {
                actionButton.setText("加入计划");
                actionButton.setOnAction(event -> executeTransition(course.getOfferingId(),
                        actionButton,
                        () -> service.addToPlan(course.getOfferingId())));
            }
        } else if (status == SelectionStatus.PLANNED) {
            actionButton.setText("移出计划");
            actionButton.setOnAction(event -> executeTransition(course.getOfferingId(),
                    actionButton,
                    () -> service.removeFromPlan(course.getOfferingId())));
        } else if (status == SelectionStatus.WAITLISTED) {
            actionButton.setText("退出候补");
            actionButton.setOnAction(event -> confirmAndExecute(
                    course.getOfferingId(), "退出候补",
                    "确认退出“" + course.getCourseName() + "”的候补？", actionButton,
                    () -> service.leaveWaitlist(course.getOfferingId())));
        } else {
            actionButton.setText("退选课程");
            actionButton.setOnAction(event -> confirmAndExecute(
                    course.getOfferingId(), "退选课程",
                    "确认退选“" + course.getCourseName() + "”？", actionButton,
                    () -> service.dropCourse(course.getOfferingId())));
        }
        applyOfferingOperationState(course.getOfferingId(), actionButton::setDisable);
        return actionButton;
    }

    private void confirmAndExecute(long offeringId, String title, String message,
            Button actionButton, Supplier<CompletableFuture<?>> transition) {
        confirmAndExecute(offeringId, title, message, actionButton::setDisable,
                this::renderCourses, transition);
    }

    void confirmAndExecute(long offeringId, String title, String message,
            Consumer<Boolean> setDisabled, Runnable rerender,
            Supplier<CompletableFuture<?>> transition) {
        if (!tryBeginOfferingOperation(offeringId)) {
            setDisabled.accept(true);
            return;
        }
        setDisabled.accept(true);
        if (confirmation.apply(title, message) != ButtonType.OK) {
            finishOfferingOperation(offeringId);
            setDisabled.accept(false);
            rerender.run();
            return;
        }
        executePendingOfferingTransition(offeringId, setDisabled, rerender, transition);
    }

    private void executeTransition(long offeringId, Button actionButton,
            Supplier<CompletableFuture<?>> transition) {
        executeTransition(offeringId, actionButton::setDisable, this::renderCourses, transition);
    }

    void executeTransition(long offeringId, Consumer<Boolean> setDisabled,
            Runnable rerender, Supplier<CompletableFuture<?>> transition) {
        if (!tryBeginOfferingOperation(offeringId)) {
            setDisabled.accept(true);
            return;
        }
        setDisabled.accept(true);
        executePendingOfferingTransition(offeringId, setDisabled, rerender, transition);
    }

    private void executePendingOfferingTransition(long offeringId,
            Consumer<Boolean> setDisabled, Runnable rerender,
            Supplier<CompletableFuture<?>> transition) {
        CompletableFuture<?> future;
        try {
            future = transition.get();
        } catch (RuntimeException error) {
            finishOfferingOperation(offeringId);
            setDisabled.accept(false);
            rerender.run();
            errorReporter.accept("操作失败", errorMessage(error));
            return;
        }
        future.whenComplete((ignored, error) -> fxExecutor.accept(() -> {
            finishOfferingOperation(offeringId);
            setDisabled.accept(false);
            if (error != null) {
                errorReporter.accept("操作失败", errorMessage(error));
            }
            rerender.run();
            refresh();
        }));
    }

    private void executePlanConfirmation(Supplier<CompletableFuture<?>> transition) {
        CompletableFuture<?> future;
        try {
            future = transition.get();
        } catch (RuntimeException error) {
            finishPlanConfirmation();
            updateConfirmPlanButton();
            errorReporter.accept("操作失败", errorMessage(error));
            return;
        }
        future.whenComplete((ignored, error) -> fxExecutor.accept(() -> {
            finishPlanConfirmation();
            if (error != null) {
                errorReporter.accept("操作失败", errorMessage(error));
            }
            renderCourses();
            refresh();
        }));
    }

    private void updateSummary() {
        long required = offerings.stream()
                .filter(course -> "必修".equals(course.getCourseType()))
                .count();
        long general = offerings.stream()
                .filter(course -> isGeneralCourse(course.getCourseType()))
                .count();
        long elective = offerings.size() - required - general;
        requiredCountLabel.setText(required + " 门");
        electiveCountLabel.setText(elective + " 门");
        generalCountLabel.setText(general + " 门");
    }

    private void updateConfirmPlanButton() {
        boolean hasPlannedCourse = offerings.stream()
                .anyMatch(course -> course.getSelectionStatus() == SelectionStatus.PLANNED);
        applyPlanConfirmationState(hasPlannedCourse, confirmPlanButton::setDisable);
    }

    private String emptyStateText() {
        if (selectedStatus == SelectionStatus.PLANNED) {
            return "计划中暂无课程";
        }
        if (selectedStatus == SelectionStatus.WAITLISTED) {
            return "候补中暂无课程";
        }
        if (selectedStatus == SelectionStatus.ENROLLED) {
            return "当前暂无已选课程";
        }
        return "没有符合条件的课程";
    }

    private static boolean isGeneralCourse(String courseType) {
        return courseType.startsWith("通识") || courseType.startsWith("通选");
    }

    private static Label styledLabel(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private static String statusText(SelectionStatus status) {
        switch (status) {
            case AVAILABLE:
                return "可选";
            case PLANNED:
                return "计划中";
            case WAITLISTED:
                return "候补中";
            case ENROLLED:
                return "已选";
            default:
                throw new IllegalArgumentException("Unknown status: " + status);
        }
    }

    private static String statusStyle(SelectionStatus status) {
        return "course-status-" + status.name().toLowerCase(Locale.ROOT);
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }
}
