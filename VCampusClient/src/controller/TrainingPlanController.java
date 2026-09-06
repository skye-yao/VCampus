package controller;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import model.course.TrainingPlanCourseView;
import model.course.TrainingPlanGroupView;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class TrainingPlanController {
    private final CourseService service;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private long loadGeneration;

    @FXML private Label overallEarnedLabel;
    @FXML private Label overallRequiredLabel;
    @FXML private ProgressBar overallProgressBar;
    @FXML private VBox planGroupList;

    public TrainingPlanController() {
        this(CourseServices.current(), AlertUtil::showError,
                TrainingPlanController::runOnFxThread);
    }

    TrainingPlanController(CourseService service,
            BiConsumer<String, String> errorReporter, Consumer<Runnable> fxExecutor) {
        this.service = service;
        this.errorReporter = errorReporter;
        this.fxExecutor = fxExecutor;
    }

    @FXML
    public void initialize() {
        clearPlan();
        showState("暂无培养方案");
    }

    @FXML
    public void refresh() {
        fxExecutor.accept(this::loadTrainingPlan);
    }

    void requestTrainingPlan(Consumer<List<TrainingPlanGroupView>> onLoaded,
            Consumer<Throwable> onError) {
        long generation = ++loadGeneration;
        CompletableFuture<List<TrainingPlanGroupView>> future;
        try {
            future = service.loadTrainingPlan();
        } catch (RuntimeException error) {
            fxExecutor.accept(() -> deliverFailure(generation, error, onError));
            return;
        }

        future.whenComplete((groups, error) -> fxExecutor.accept(() -> {
            if (generation != loadGeneration) {
                return;
            }
            if (error != null) {
                onError.accept(error);
                return;
            }
            if (groups == null) {
                onError.accept(new IllegalStateException("培养方案数据为空"));
                return;
            }
            onLoaded.accept(groups);
        }));
    }

    static PlanTotals aggregate(List<TrainingPlanGroupView> groups) {
        double earnedCredits = 0.0;
        double requiredCredits = 0.0;
        for (TrainingPlanGroupView group : groups) {
            earnedCredits += group.getEarnedCredits();
            requiredCredits += group.getRequiredCredits();
        }
        return new PlanTotals(earnedCredits, requiredCredits);
    }

    private void loadTrainingPlan() {
        clearPlan();
        showState("正在加载培养方案...");
        requestTrainingPlan(this::renderPlan, error -> {
            clearPlan();
            showState("培养方案加载失败，请刷新重试");
            errorReporter.accept("培养方案加载失败", errorMessage(error));
        });
    }

    private void renderPlan(List<TrainingPlanGroupView> groups) {
        clearPlan();
        if (groups.isEmpty()) {
            showState("暂无培养方案");
            return;
        }

        PlanTotals totals = aggregate(groups);
        overallEarnedLabel.setText(formatCredits(totals.getEarnedCredits()));
        overallRequiredLabel.setText(formatCredits(totals.getRequiredCredits()));
        overallProgressBar.setProgress(totals.getProgress());
        for (TrainingPlanGroupView group : groups) {
            planGroupList.getChildren().add(createGroupSection(group));
        }
    }

    private VBox createGroupSection(TrainingPlanGroupView group) {
        Label title = new Label(group.getName());
        title.getStyleClass().add("course-plan-group-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label credits = new Label("已获 " + formatCredits(group.getEarnedCredits())
                + " / 要求 " + formatCredits(group.getRequiredCredits()) + " 学分");
        credits.getStyleClass().add("course-plan-group-credits");

        HBox heading = new HBox(10.0, title, spacer, credits);
        heading.setAlignment(Pos.CENTER_LEFT);

        ProgressBar progressBar = new ProgressBar(
                progress(group.getEarnedCredits(), group.getRequiredCredits()));
        progressBar.getStyleClass().add("course-progress-bar");
        progressBar.setMaxWidth(Double.MAX_VALUE);

        VBox groupSection = new VBox(7.0, heading, progressBar, createCourseHeader());
        groupSection.getStyleClass().add("course-plan-group");
        groupSection.setMaxWidth(Double.MAX_VALUE);
        for (TrainingPlanCourseView course : group.getCourses()) {
            groupSection.getChildren().add(createCourseRow(course));
        }
        return groupSection;
    }

    private HBox createCourseHeader() {
        HBox header = createCourseRow("课程代码", "课程名称", "学分", "状态");
        header.getStyleClass().add("course-plan-row-header");
        return header;
    }

    private HBox createCourseRow(TrainingPlanCourseView course) {
        HBox row = createCourseRow(course.getCourseCode(), course.getCourseName(),
                formatCredits(course.getCredit()), course.getCompletionStatus());
        row.getChildren().get(3).getStyleClass().add(statusStyle(course.getCompletionStatus()));
        return row;
    }

    private HBox createCourseRow(String code, String name, String credit, String status) {
        Label codeLabel = new Label(code);
        codeLabel.setMinWidth(78.0);
        codeLabel.setPrefWidth(78.0);

        Label nameLabel = new Label(name);
        nameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(nameLabel, Priority.ALWAYS);

        Label creditLabel = new Label(credit);
        creditLabel.setMinWidth(52.0);
        creditLabel.setPrefWidth(52.0);
        creditLabel.setAlignment(Pos.CENTER_RIGHT);

        Label statusLabel = new Label(status);
        statusLabel.setMinWidth(52.0);
        statusLabel.setPrefWidth(52.0);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);

        HBox row = new HBox(10.0, codeLabel, nameLabel, creditLabel, statusLabel);
        row.getStyleClass().add("course-plan-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private void clearPlan() {
        overallEarnedLabel.setText("--");
        overallRequiredLabel.setText("--");
        overallProgressBar.setProgress(0.0);
        planGroupList.getChildren().clear();
    }

    private void showState(String message) {
        Label state = new Label(message);
        state.getStyleClass().add("course-empty-state");
        state.setMaxWidth(Double.MAX_VALUE);
        state.setWrapText(true);
        planGroupList.getChildren().setAll(state);
    }

    private void deliverFailure(long generation, Throwable error,
            Consumer<Throwable> onError) {
        if (generation == loadGeneration) {
            onError.accept(error);
        }
    }

    private static String statusStyle(String status) {
        if ("已修".equals(status)) {
            return "course-plan-complete";
        }
        if ("在修".equals(status)) {
            return "course-plan-current";
        }
        return "course-plan-pending";
    }

    private static String formatCredits(double credits) {
        if (credits == Math.rint(credits)) {
            return String.format(Locale.ROOT, "%.0f", credits);
        }
        return String.format(Locale.ROOT, "%.1f", credits);
    }

    private static double progress(double earnedCredits, double requiredCredits) {
        if (requiredCredits <= 0.0) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, earnedCredits / requiredCredits));
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause.getCause() != null)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "未知错误" : cause.getMessage();
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    static final class PlanTotals {
        private final double earnedCredits;
        private final double requiredCredits;

        private PlanTotals(double earnedCredits, double requiredCredits) {
            this.earnedCredits = earnedCredits;
            this.requiredCredits = requiredCredits;
        }

        double getEarnedCredits() {
            return earnedCredits;
        }

        double getRequiredCredits() {
            return requiredCredits;
        }

        double getProgress() {
            return progress(earnedCredits, requiredCredits);
        }
    }
}
