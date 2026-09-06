package controller;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import model.course.CourseNoticeView;
import model.course.ScheduleEntryView;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class ScheduleController {
    private static final String DEFAULT_TERM = "2026-2027 秋学期";
    private static final int FIRST_PERIOD = 1;
    private static final int LAST_PERIOD = 10;

    private final CourseService service;
    private final BiConsumer<String, String> infoReporter;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private long loadGeneration;

    @FXML private ComboBox<String> termFilter;
    @FXML private Spinner<Integer> weekSpinner;
    @FXML private GridPane scheduleGrid;
    @FXML private VBox noticeList;

    public ScheduleController() {
        this(CourseServices.current(), AlertUtil::showInfo, AlertUtil::showError,
                ScheduleController::runOnFxThread);
    }

    ScheduleController(CourseService service, BiConsumer<String, String> infoReporter,
            BiConsumer<String, String> errorReporter, Consumer<Runnable> fxExecutor) {
        this.service = service;
        this.infoReporter = infoReporter;
        this.errorReporter = errorReporter;
        this.fxExecutor = fxExecutor;
    }

    @FXML
    public void initialize() {
        termFilter.getItems().add(DEFAULT_TERM);
        termFilter.setValue(DEFAULT_TERM);
        weekSpinner.setValueFactory(
                new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 20, 3));
        termFilter.valueProperty().addListener(
                (observable, oldValue, newValue) -> refresh());
        weekSpinner.valueProperty().addListener(
                (observable, oldValue, newValue) -> refresh());
        renderSchedule(Collections.emptyList());
        renderNotices(Collections.emptyList());
    }

    @FXML
    public void refresh() {
        fxExecutor.accept(this::loadSchedule);
    }

    private void loadSchedule() {
        String term = termFilter.getValue();
        Integer selectedWeek = weekSpinner.getValue();
        if (term == null || selectedWeek == null) {
            return;
        }

        long generation = ++loadGeneration;
        renderSchedule(Collections.emptyList());
        renderNotices(Collections.emptyList(), "正在加载课表...");
        CompletableFuture<List<ScheduleEntryView>> scheduleFuture =
                service.loadSchedule(term, selectedWeek);
        CompletableFuture<List<CourseNoticeView>> noticeFuture =
                service.loadNotices(term, selectedWeek);

        scheduleFuture.thenCombine(noticeFuture, ScheduleData::new)
                .whenComplete((data, error) -> fxExecutor.accept(() -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    if (error != null) {
                        renderSchedule(Collections.emptyList());
                        renderNotices(Collections.emptyList(), "加载失败，请刷新重试");
                        errorReporter.accept("加载失败", errorMessage(error));
                        return;
                    }
                    renderSchedule(data.entries);
                    renderNotices(data.notices);
                }));
    }

    private void renderSchedule(List<ScheduleEntryView> entries) {
        scheduleGrid.getChildren().clear();
        scheduleGrid.getRowConstraints().clear();

        RowConstraints headerRow = new RowConstraints(30.0);
        scheduleGrid.getRowConstraints().add(headerRow);
        for (int period = FIRST_PERIOD; period <= LAST_PERIOD; period++) {
            RowConstraints periodRow = new RowConstraints();
            periodRow.setMinHeight(30.0);
            periodRow.setVgrow(Priority.ALWAYS);
            scheduleGrid.getRowConstraints().add(periodRow);
        }

        addGridLabel("节次", 0, 0, "course-schedule-header");
        for (int day = 1; day <= 5; day++) {
            addGridLabel(weekdayName(day), day, 0, "course-schedule-header");
        }
        for (int period = FIRST_PERIOD; period <= LAST_PERIOD; period++) {
            addGridLabel("第 " + period + " 节", 0, period, "course-period-label");
            for (int day = 1; day <= 5; day++) {
                addGridCell(day, period);
            }
        }

        for (int day = 1; day <= 5; day++) {
            for (ScheduleLayout.Component component : ScheduleLayout.layoutDay(
                    entries, day, FIRST_PERIOD, LAST_PERIOD)) {
                GridPane componentGrid = createComponentGrid(component);
                scheduleGrid.add(componentGrid, day, component.getStartPeriod());
                GridPane.setRowSpan(componentGrid,
                        component.getEndPeriod() - component.getStartPeriod() + 1);
                GridPane.setHgrow(componentGrid, Priority.ALWAYS);
                GridPane.setVgrow(componentGrid, Priority.ALWAYS);
            }
        }
    }

    private void addGridCell(int column, int row) {
        Region cell = new Region();
        cell.getStyleClass().add("course-schedule-cell");
        cell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setHgrow(cell, Priority.ALWAYS);
        GridPane.setVgrow(cell, Priority.ALWAYS);
        scheduleGrid.add(cell, column, row);
    }

    private GridPane createComponentGrid(ScheduleLayout.Component component) {
        GridPane componentGrid = new GridPane();
        componentGrid.getStyleClass().add("course-schedule-overlap-grid");
        componentGrid.setHgap(2.0);
        componentGrid.setVgap(1.0);
        componentGrid.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);

        int periodCount = component.getEndPeriod() - component.getStartPeriod() + 1;
        for (int row = 0; row < periodCount; row++) {
            RowConstraints rowConstraints = new RowConstraints();
            rowConstraints.setPercentHeight(100.0 / periodCount);
            rowConstraints.setVgrow(Priority.ALWAYS);
            componentGrid.getRowConstraints().add(rowConstraints);
        }
        for (int lane = 0; lane < component.getLaneCount(); lane++) {
            ColumnConstraints columnConstraints = new ColumnConstraints();
            columnConstraints.setPercentWidth(100.0 / component.getLaneCount());
            columnConstraints.setHgrow(Priority.ALWAYS);
            componentGrid.getColumnConstraints().add(columnConstraints);
        }

        for (ScheduleLayout.PlacedEntry placed : component.getEntries()) {
            Button courseBlock = createCourseBlock(placed.getEntry());
            int row = placed.getStartPeriod() - component.getStartPeriod();
            componentGrid.add(courseBlock, placed.getLane(), row);
            GridPane.setRowSpan(courseBlock,
                    placed.getEndPeriod() - placed.getStartPeriod() + 1);
            GridPane.setHgrow(courseBlock, Priority.ALWAYS);
            GridPane.setVgrow(courseBlock, Priority.ALWAYS);
        }
        return componentGrid;
    }

    private void addGridLabel(String text, int column, int row, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        label.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER);
        GridPane.setHalignment(label, HPos.CENTER);
        GridPane.setHgrow(label, Priority.ALWAYS);
        GridPane.setVgrow(label, Priority.ALWAYS);
        scheduleGrid.add(label, column, row);
    }

    private Button createCourseBlock(ScheduleEntryView entry) {
        Label title = new Label(entry.getCourseName());
        title.getStyleClass().add("course-class-title");
        title.setWrapText(true);
        title.setMinWidth(0.0);

        Label meta = new Label(entry.getLocation() + "\n" + entry.getTeacher());
        meta.getStyleClass().add("course-class-meta");
        meta.setWrapText(true);
        meta.setMinWidth(0.0);

        VBox content = new VBox(2.0, title, meta);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMinWidth(0.0);

        Button block = new Button();
        block.getStyleClass().add("course-class-block");
        block.setGraphic(content);
        block.setMinSize(0.0, 0.0);
        block.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        block.setOnAction(event -> infoReporter.accept("课程详情", detailText(entry)));
        return block;
    }

    private void renderNotices(List<CourseNoticeView> notices) {
        renderNotices(notices, "本周暂无调课通知");
    }

    private void renderNotices(List<CourseNoticeView> notices, String emptyMessage) {
        noticeList.getChildren().clear();
        Label heading = new Label("调课通知");
        heading.getStyleClass().add("course-schedule-header");
        heading.setMaxWidth(Double.MAX_VALUE);
        noticeList.getChildren().add(heading);

        if (notices.isEmpty()) {
            Label empty = new Label(emptyMessage);
            empty.getStyleClass().add("course-class-meta");
            empty.setWrapText(true);
            noticeList.getChildren().add(empty);
            return;
        }

        for (CourseNoticeView notice : notices) {
            Label title = new Label(notice.getTitle());
            title.getStyleClass().add("course-class-title");
            title.setWrapText(true);
            Label content = new Label(notice.getContent());
            content.getStyleClass().add("course-class-meta");
            content.setWrapText(true);
            VBox item = new VBox(5.0, title, content);
            item.getStyleClass().add("course-notice-item");
            noticeList.getChildren().add(item);
        }
    }

    private static String detailText(ScheduleEntryView entry) {
        int endPeriod = entry.getStartPeriod() + entry.getPeriodCount() - 1;
        return "课程名称：" + entry.getCourseName()
                + "\n课程代码：" + entry.getCourseCode()
                + "\n上课时间：" + weekdayName(entry.getDayOfWeek()) + " 第 "
                + entry.getStartPeriod() + "-" + endPeriod + " 节"
                + "\n上课地点：" + entry.getLocation()
                + "\n授课教师：" + entry.getTeacher()
                + "\n备注：第 " + entry.getStartWeek() + "-" + entry.getEndWeek() + " 周";
    }

    private static String weekdayName(int dayOfWeek) {
        String[] weekdays = {"", "周一", "周二", "周三", "周四", "周五"};
        return dayOfWeek >= 1 && dayOfWeek <= 5 ? weekdays[dayOfWeek] : "未知";
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

    private static final class ScheduleData {
        private final List<ScheduleEntryView> entries;
        private final List<CourseNoticeView> notices;

        private ScheduleData(List<ScheduleEntryView> entries,
                List<CourseNoticeView> notices) {
            this.entries = entries;
            this.notices = notices;
        }
    }
}
