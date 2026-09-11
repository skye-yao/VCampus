package controller;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import model.course.CourseTermView;
import model.course.GradeRecordView;
import model.course.GradeSummaryView;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class GradeController {
    private final CourseService service;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private CourseTermView selectedTerm;
    private long loadGeneration;
    private long termLoadGeneration;

    @FXML private ComboBox<CourseTermView> termFilter;
    @FXML private Label termGpaLabel;
    @FXML private Label termAverageLabel;
    @FXML private Label cumulativeAverageLabel;
    @FXML private Label cumulativeGpaLabel;
    @FXML private TableView<GradeRecordView> gradeTable;
    @FXML private TableColumn<GradeRecordView, String> courseNameColumn;
    @FXML private TableColumn<GradeRecordView, String> courseCodeColumn;
    @FXML private TableColumn<GradeRecordView, String> creditColumn;
    @FXML private TableColumn<GradeRecordView, String> scoreColumn;
    @FXML private TableColumn<GradeRecordView, String> gpaColumn;
    @FXML private Label emptyStateLabel;
    @FXML private Label dailyScoreLabel;
    @FXML private Label midtermScoreLabel;
    @FXML private Label experimentScoreLabel;
    @FXML private Label finalScoreLabel;
    @FXML private Label totalScoreLabel;
    @FXML private Label detailGpaLabel;

    public GradeController() {
        this(CourseServices.current(), AlertUtil::showError, GradeController::runOnFxThread);
    }

    GradeController(CourseService service, BiConsumer<String, String> errorReporter,
            Consumer<Runnable> fxExecutor) {
        this.service = service;
        this.errorReporter = errorReporter;
        this.fxExecutor = fxExecutor;
    }

    @FXML
    public void initialize() {
        configureColumns();
        termFilter.valueProperty().addListener(
                (observable, oldValue, newValue) -> {
                    if (newValue != null && !newValue.equals(selectedTerm)) {
                        selectedTerm = newValue;
                        refresh();
                    }
                });
        gradeTable.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> renderDetails(newValue));
        clearGradeData();
        showEmptyState("请选择学期");
        loadTerms();
    }

    @FXML
    public void refresh() {
        fxExecutor.accept(this::loadGrades);
    }

    private void loadTerms() {
        long generation = ++termLoadGeneration;
        service.loadTerms().whenComplete((terms, error) -> fxExecutor.accept(() -> {
            if (generation != termLoadGeneration) return;
            if (error != null) {
                errorReporter.accept("加载失败", errorMessage(error));
                return;
            }
            termFilter.getItems().setAll(terms);
            if (terms.isEmpty()) {
                selectedTerm = null;
                clearGradeData();
                showEmptyState("暂无学期");
            } else {
                termFilter.setValue(terms.get(0));
            }
        }));
    }

    void requestGrades(CourseTermView term, Consumer<GradeSummaryView> onLoaded,
            Consumer<Throwable> onError) {
        long generation = ++loadGeneration;
        CompletableFuture<GradeSummaryView> future;
        try {
            future = service.loadGrades(term);
        } catch (RuntimeException error) {
            fxExecutor.accept(() -> deliverFailure(generation, error, onError));
            return;
        }

        future.whenComplete((summary, error) -> fxExecutor.accept(() -> {
            if (generation != loadGeneration) {
                return;
            }
            if (error != null) {
                onError.accept(error);
                return;
            }
            if (summary == null) {
                onError.accept(new IllegalStateException("成绩数据为空"));
                return;
            }
            onLoaded.accept(summary);
        }));
    }

    static String formatScore(Double score) {
        if (score == null) {
            return "--";
        }
        if (score == Math.rint(score)) {
            return String.format(Locale.ROOT, "%.0f", score);
        }
        return String.format(Locale.ROOT, "%.1f", score);
    }

    private void configureColumns() {
        courseNameColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().getCourseName()));
        courseCodeColumn.setCellValueFactory(
                cell -> new ReadOnlyStringWrapper(cell.getValue().getCourseCode()));
        creditColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                String.format(Locale.ROOT, "%.1f", cell.getValue().getCredit())));
        scoreColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                formatScore(cell.getValue().getScore())));
        gpaColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                String.format(Locale.ROOT, "%.2f", cell.getValue().getGradePoint())));
        gradeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
    }

    private void loadGrades() {
        CourseTermView term = selectedTerm != null ? selectedTerm : termFilter.getValue();
        clearGradeData();
        if (term == null) {
            showEmptyState("请选择学期");
            return;
        }

        showEmptyState("正在加载成绩...");
        requestGrades(term, this::renderSummary, error -> {
            clearGradeData();
            showEmptyState("成绩加载失败，请刷新重试");
            errorReporter.accept("成绩加载失败", errorMessage(error));
        });
    }

    private void renderSummary(GradeSummaryView summary) {
        termGpaLabel.setText(formatMetric(summary.getTermGpa()));
        termAverageLabel.setText(formatMetric(summary.getTermAverage()));
        cumulativeAverageLabel.setText(formatMetric(summary.getCumulativeAverage()));
        cumulativeGpaLabel.setText(formatMetric(summary.getCumulativeGpa()));
        gradeTable.getItems().setAll(summary.getRecords());

        if (summary.getRecords().isEmpty()) {
            renderDetails(null);
            showEmptyState("该学期暂无成绩");
            return;
        }

        emptyStateLabel.setManaged(false);
        emptyStateLabel.setVisible(false);
        gradeTable.setManaged(true);
        gradeTable.setVisible(true);
        gradeTable.getSelectionModel().selectFirst();
    }

    private void clearGradeData() {
        termGpaLabel.setText("--");
        termAverageLabel.setText("--");
        cumulativeAverageLabel.setText("--");
        cumulativeGpaLabel.setText("--");
        gradeTable.getItems().clear();
        gradeTable.getSelectionModel().clearSelection();
        renderDetails(null);
    }

    private void renderDetails(GradeRecordView record) {
        dailyScoreLabel.setText(record == null ? "--" : formatScore(record.getDailyScore()));
        midtermScoreLabel.setText(
                record == null ? "--" : formatScore(record.getMidtermScore()));
        experimentScoreLabel.setText(
                record == null ? "--" : formatScore(record.getExperimentScore()));
        finalScoreLabel.setText(record == null ? "--" : formatScore(record.getFinalScore()));
        totalScoreLabel.setText(record == null ? "--" : formatScore(record.getScore()));
        detailGpaLabel.setText(record == null ? "--"
                : String.format(Locale.ROOT, "%.2f", record.getGradePoint()));
    }

    private void showEmptyState(String message) {
        gradeTable.setManaged(false);
        gradeTable.setVisible(false);
        emptyStateLabel.setText(message);
        emptyStateLabel.setManaged(true);
        emptyStateLabel.setVisible(true);
    }

    private void deliverFailure(long generation, Throwable error,
            Consumer<Throwable> onError) {
        if (generation == loadGeneration) {
            onError.accept(error);
        }
    }

    private static String formatMetric(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
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
}
