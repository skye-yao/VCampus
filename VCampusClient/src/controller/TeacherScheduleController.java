package controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import dto.course.CourseTermDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import util.AlertUtil;
import util.FXMLUtil;

/**
 * 教师教学课程表页（设计 §3）：按教学日历的一周展示本人课次，点击卡片打开课次详情。
 *
 * <p>页面只消费服务端返回的不可变业务 DTO：日期列来自 {@code dates}（含非教学日，仍占一列），
 * 节次行是本周 {@code periods} 里出现过的节次编号的升序并集（不同日期可以用不同的节次模板，因此
 * 既不是固定的 1..10，也不是固定的 5 天），卡片文案直接取用 DTO 里的课程名与地点，不重新推导。
 * 网格整体放进 {@code ScrollPane}，页面自身不声明比 860×580 外壳更大的 pref 尺寸。
 *
 * <p>周导航只保留一个可变状态 {@link #requestedWeek}（null 表示“由服务端决定本周”）：上一周/下一周
 * 以已加载周为基准加减 1，边界来自响应里的 {@code minWeek}/{@code maxWeek}；“回到本周”只在响应
 * 给出 {@code currentWeek} 时可用。切换学期会把 {@link #requestedWeek} 重置为 null。
 *
 * <p>冲突布局交给教师端的 {@link TeacherScheduleLayout}：{@code ADJUSTED_ORIGINAL} 只是画在旧位置
 * 上的提示层，不占额外列，因此成对的“原安排/调课后”不会被拆成并排的两列。
 *
 * <p>所有节点都可能为 {@code null}：控制器测试按仓库约定在无工具包、无 FXML 节点的环境下运行，
 * 卡片的收集与网格的几何计算因此与节点绘制分开——节点缺失时卡片集合照常算出，测试才能验证
 * “点的是哪一张卡片”。页面被 {@link #unload()} 卸下后，在途响应一律丢弃（active + generation
 * 双重判定），不会再写任何控件。
 */
public final class TeacherScheduleController {
    static final String LOAD_FAILURE_TEXT = "教学课表加载失败，请重试";
    /** 课次详情弹窗的资源路径；标题由 {@link TeacherCourseDetailDialogController#TITLE} 固定。 */
    static final String DIALOG_VIEW = "/resources/fxml/TeacherCourseDetailDialog.fxml";

    private static final double PERIOD_COLUMN_WIDTH = 96.0;
    private static final double DAY_COLUMN_MIN_WIDTH = 96.0;
    private static final double DAY_COLUMN_PREF_WIDTH = 112.0;
    private static final double HEADER_ROW_HEIGHT = 34.0;
    private static final double PERIOD_ROW_HEIGHT = 44.0;

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private Consumer<String> openOffering = offeringId -> { };
    private Consumer<TeacherScheduleEntryDTO> detailOpener = entry -> openDetailDialog(entry);

    private List<CourseTermDTO> terms = List.of();
    private CourseTermDTO term;
    private TeacherScheduleWeekDTO week;
    /** null 表示“由服务端决定本周”；其余情况是最后一次请求的周号。 */
    private Integer requestedWeek;
    private Integer loadedWeek;
    private List<Integer> periodRows = List.of();
    private List<TeacherScheduleEntryDTO> cardEntries = List.of();
    private boolean termsLoaded;
    private boolean loading;
    private boolean active;
    private boolean syncingFilters;
    private String errorText;
    private long generation;

    @FXML private ComboBox<String> termFilter;
    @FXML private Button currentWeekButton;
    @FXML private Button previousWeekButton;
    @FXML private Label weekLabel;
    @FXML private Button nextWeekButton;
    @FXML private Button refreshButton;
    @FXML private ScrollPane scheduleScroll;
    @FXML private GridPane scheduleGrid;
    @FXML private Label loadingLabel;
    @FXML private Label emptyLabel;
    @FXML private Label errorLabel;
    @FXML private Button errorRetryButton;

    public TeacherScheduleController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherScheduleController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        if (termFilter != null) {
            termFilter.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, previous, next) ->
                            selectTerm(next == null ? -1 : next.intValue()));
        }
        render();
    }

    /**
     * 工作台切换到本页时调用：首次进入先取学期并选中第一个（选中变化后以 {@code week=null} 请求，
     * 由服务端给出本周），再次进入按当前学期与最后一次请求的周重新加载。
     */
    void activate() {
        active = true;
        if (!termsLoaded) {
            loadTerms();
            return;
        }
        loadWeek(requestedWeek);
    }

    /** 工作台离开本页时调用：保留界面状态，但在途响应从此不再写界面。 */
    void unload() {
        active = false;
        generation++;
    }

    /** 把工作台的教学班导航接进来（查看教学班 → {@code showOffering(offeringId)}）。 */
    void setOpenOffering(Consumer<String> openOffering) {
        this.openOffering = openOffering == null ? offeringId -> { } : openOffering;
    }

    /** 测试注入点：默认打开真实的课次详情弹窗。 */
    void setDetailOpener(Consumer<TeacherScheduleEntryDTO> detailOpener) {
        this.detailOpener = detailOpener == null ? entry -> openDetailDialog(entry) : detailOpener;
    }

    // ---------------------------------------------------------------- 周导航

    @FXML
    void handlePreviousWeek(Event event) {
        if (loadedWeek == null) return;
        loadWeek(loadedWeek - 1);
    }

    @FXML
    void handleNextWeek(Event event) {
        if (loadedWeek == null) return;
        loadWeek(loadedWeek + 1);
    }

    /** 回到本周：以 {@code week=null} 请求，让服务端按教学日历与系统时钟决定。 */
    @FXML
    void handleBackToCurrentWeek(Event event) {
        loadWeek(null);
    }

    /** 刷新当前学期与周次；页面未激活（已卸下）时什么都不做，免得在途状态又去写控件。 */
    @FXML
    void refresh() {
        if (!active) return;
        if (!termsLoaded) {
            activate();
            return;
        }
        loadWeek(requestedWeek);
    }

    /** 切换学期：周次回到“由服务端决定”，其余界面状态保留。 */
    void selectTerm(int index) {
        if (syncingFilters || index < 0 || index >= terms.size()) return;
        CourseTermDTO next = terms.get(index);
        if (next == term) return;
        term = next;
        requestedWeek = null;
        loadWeek(null);
    }

    /** 打开某个课次的详情；卡片的 onAction 与测试都走这里，因此“点的是哪一张”是可验证的。 */
    void openDetail(TeacherScheduleEntryDTO entry) {
        if (entry == null) return;
        detailOpener.accept(entry);
    }

    boolean canGoPrevious() {
        return !loading && week != null && loadedWeek != null && loadedWeek > week.getMinWeek();
    }

    boolean canGoNext() {
        return !loading && week != null && loadedWeek != null && loadedWeek < week.getMaxWeek();
    }

    boolean canGoCurrent() {
        return !loading && week != null && week.getCurrentWeek() != null;
    }

    // ---------------------------------------------------------------- 加载

    private void loadTerms() {
        long current = ++generation;
        loading = true;
        errorText = null;
        render();
        service.listTerms().whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(current)) return;
            loading = false;
            if (failure != null) {
                errorText = LOAD_FAILURE_TEXT;
                render();
                return;
            }
            terms = List.copyOf(loaded == null ? List.of() : loaded);
            termsLoaded = true;
            term = terms.isEmpty() ? null : terms.get(0);
            requestedWeek = null;
            renderTerms();
            if (term == null) {
                week = null;
                loadedWeek = null;
                errorText = null;
                render();
                return;
            }
            loadWeek(null);
        }));
    }

    private void loadWeek(Integer targetWeek) {
        if (term == null) {
            render();
            return;
        }
        requestedWeek = targetWeek;
        long current = ++generation;
        loading = true;
        errorText = null;
        render();
        service.loadTeachingSchedule(term.getAcademicYear(), term.getSemester(), targetWeek)
                .whenComplete((value, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrent(current)) return;
                    loading = false;
                    if (failure != null) {
                        // 保留已显示的周与卡片，只提示可以重试。
                        requestedWeek = loadedWeek;
                        errorText = LOAD_FAILURE_TEXT;
                        render();
                        return;
                    }
                    errorText = null;
                    week = value;
                    loadedWeek = value == null ? null : value.getWeek();
                    render();
                }));
    }

    /** 当前 generation 且页面仍处于激活状态时才允许写界面。 */
    private boolean isCurrent(long current) {
        return active && current == generation;
    }

    private void renderTerms() {
        if (termFilter == null) return;
        syncingFilters = true;
        try {
            termFilter.getItems().setAll(
                    terms.stream().map(CourseTermDTO::getDisplayName).toList());
            termFilter.getSelectionModel().select(term == null ? -1 : terms.indexOf(term));
        } finally {
            syncingFilters = false;
        }
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        renderGrid();
        if (previousWeekButton != null) previousWeekButton.setDisable(!canGoPrevious());
        if (nextWeekButton != null) nextWeekButton.setDisable(!canGoNext());
        if (currentWeekButton != null) currentWeekButton.setDisable(!canGoCurrent());
        if (weekLabel != null) weekLabel.setText(weekLabel(week));
        setActive(loadingLabel, loading);
        setActive(emptyLabel, !loading && errorText == null && week != null
                && cardEntries.isEmpty());
        if (errorLabel != null) errorLabel.setText(errorText == null ? "" : errorText);
        setActive(errorLabel, errorText != null);
        setActive(errorRetryButton, errorText != null);
    }

    /**
     * 重建日期列、节次行与冲突组件；卡片集合与节次行在节点缺失时也照常算出，测试据此断言。
     */
    private void renderGrid() {
        periodRows = week == null ? List.of() : periodNumbers(week.getPeriods());
        if (week == null) {
            cardEntries = List.of();
            if (scheduleGrid != null) {
                scheduleGrid.getChildren().clear();
                scheduleGrid.getColumnConstraints().clear();
                scheduleGrid.getRowConstraints().clear();
            }
            return;
        }

        List<TeacherCalendarDateDTO> dates = week.getDates();
        List<TeacherScheduleEntryDTO> cards = new ArrayList<>();
        if (scheduleGrid != null) {
            scheduleGrid.getChildren().clear();
            scheduleGrid.getColumnConstraints().clear();
            scheduleGrid.getRowConstraints().clear();
            scheduleGrid.getColumnConstraints().add(
                    new ColumnConstraints(PERIOD_COLUMN_WIDTH, PERIOD_COLUMN_WIDTH,
                            PERIOD_COLUMN_WIDTH));
            for (int column = 0; column < dates.size(); column++) {
                ColumnConstraints dayColumn = new ColumnConstraints(DAY_COLUMN_MIN_WIDTH,
                        DAY_COLUMN_PREF_WIDTH, Double.MAX_VALUE);
                dayColumn.setHgrow(Priority.ALWAYS);
                scheduleGrid.getColumnConstraints().add(dayColumn);
            }
            scheduleGrid.getRowConstraints().add(new RowConstraints(HEADER_ROW_HEIGHT));
            for (int row = 0; row < periodRows.size(); row++) {
                RowConstraints periodRow = new RowConstraints(PERIOD_ROW_HEIGHT);
                periodRow.setMinHeight(PERIOD_ROW_HEIGHT);
                periodRow.setVgrow(Priority.ALWAYS);
                scheduleGrid.getRowConstraints().add(periodRow);
            }

            addLabel("节次", 0, 0, HPos.CENTER, "teacher-schedule-header");
            for (int column = 0; column < dates.size(); column++) {
                TeacherCalendarDateDTO date = dates.get(column);
                addLabel(dayHeader(date), column + 1, 0, HPos.CENTER,
                        date.isTeachingDay()
                                ? new String[] {"teacher-schedule-header"}
                                : new String[] {"teacher-schedule-header",
                                        "teacher-schedule-non-teaching"});
            }
            for (int row = 0; row < periodRows.size(); row++) {
                int period = periodRows.get(row);
                addLabel(periodHeader(period, firstPeriod(week, period)), 0, row + 1, HPos.CENTER,
                        "teacher-schedule-period-label");
                for (int column = 0; column < dates.size(); column++) {
                    scheduleGrid.add(dayCell(dates.get(column)), column + 1, row + 1);
                }
            }
        }

        if (periodRows.isEmpty()) {
            cardEntries = List.of();
            return;
        }
        int firstPeriod = periodRows.get(0);
        int lastPeriod = periodRows.get(periodRows.size() - 1);
        for (int column = 0; column < dates.size(); column++) {
            TeacherCalendarDateDTO date = dates.get(column);
            for (TeacherScheduleLayout.Component component : TeacherScheduleLayout.layoutDay(
                    week.getEntries(), date.getTeachingWeekday(), firstPeriod, lastPeriod)) {
                int componentRow = rowIndex(component.getStartPeriod());
                GridPane componentGrid = scheduleGrid == null
                        ? null : createComponentGrid(component);
                if (componentGrid != null) {
                    scheduleGrid.add(componentGrid, column + 1, componentRow);
                }
                for (TeacherScheduleLayout.PlacedEntry placed : component.getEntries()) {
                    cards.add(placed.getEntry());
                    if (componentGrid == null) continue;
                    Button card = createCard(placed.getEntry());
                    GridPane.setRowSpan(card, rowSpan(placed.getStartPeriod(),
                            placed.getEndPeriod()));
                    GridPane.setHgrow(card, Priority.ALWAYS);
                    GridPane.setVgrow(card, Priority.ALWAYS);
                    componentGrid.add(card, placed.getLane(),
                            rowIndex(placed.getStartPeriod()) - componentRow);
                }
            }
        }
        cardEntries = List.copyOf(cards);
    }

    private void addLabel(String text, int column, int row, HPos alignment,
            String... styleClasses) {
        if (scheduleGrid == null) return;
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        label.setAlignment(Pos.CENTER);
        label.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        GridPane.setHalignment(label, alignment);
        GridPane.setHgrow(label, Priority.ALWAYS);
        GridPane.setVgrow(label, Priority.ALWAYS);
        scheduleGrid.add(label, column, row);
    }

    private Region dayCell(TeacherCalendarDateDTO date) {
        Region cell = new Region();
        cell.getStyleClass().add("teacher-schedule-cell");
        if (!date.isTeachingDay()) {
            cell.getStyleClass().add("teacher-schedule-non-teaching");
        }
        cell.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        return cell;
    }

    /** 一个冲突组件一个嵌套网格：组件内按 lane 分列，组件整体占住它覆盖的节次行。 */
    private GridPane createComponentGrid(TeacherScheduleLayout.Component component) {
        GridPane componentGrid = new GridPane();
        componentGrid.getStyleClass().add("teacher-schedule-component");
        componentGrid.setHgap(2.0);
        componentGrid.setVgap(1.0);
        componentGrid.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        int componentRowSpan = rowSpan(component.getStartPeriod(), component.getEndPeriod());
        for (int row = 0; row < componentRowSpan; row++) {
            RowConstraints rowConstraints = new RowConstraints();
            rowConstraints.setVgrow(Priority.ALWAYS);
            componentGrid.getRowConstraints().add(rowConstraints);
        }
        for (int lane = 0; lane < component.getLaneCount(); lane++) {
            ColumnConstraints columnConstraints = new ColumnConstraints();
            columnConstraints.setPercentWidth(100.0 / component.getLaneCount());
            columnConstraints.setHgrow(Priority.ALWAYS);
            componentGrid.getColumnConstraints().add(columnConstraints);
        }
        GridPane.setRowSpan(componentGrid, componentRowSpan);
        GridPane.setHgrow(componentGrid, Priority.ALWAYS);
        GridPane.setVgrow(componentGrid, Priority.ALWAYS);
        return componentGrid;
    }

    /**
     * 一个课次的卡片：课程名 + 地点，调课的两个位置各带角标与样式类（普通课次没有角标）。
     * 卡片本身是按钮，点击把这条 DTO 原样交给详情弹窗。
     */
    private Button createCard(TeacherScheduleEntryDTO entry) {
        Label title = new Label(orDash(entry.getCourseName()));
        title.getStyleClass().add("teacher-schedule-card-title");
        title.setWrapText(true);
        Label meta = new Label(orDash(entry.getLocation()));
        meta.getStyleClass().add("teacher-schedule-card-meta");
        meta.setWrapText(true);

        VBox content = new VBox(2.0, title, meta);
        content.setAlignment(Pos.CENTER_LEFT);
        String badge = badgeText(entry);
        if (badge != null) {
            Label badgeLabel = new Label(badge);
            badgeLabel.getStyleClass().add("teacher-schedule-badge");
            content.getChildren().add(0, badgeLabel);
        }

        Button card = new Button();
        card.getStyleClass().add("teacher-schedule-card");
        String adjustmentStyle = cardStyleClass(entry);
        if (adjustmentStyle != null) {
            card.getStyleClass().add(adjustmentStyle);
        }
        card.setGraphic(content);
        card.setMinSize(0.0, 0.0);
        card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        card.setOnAction(event -> openDetail(entry));
        return card;
    }

    /** 节次编号在网格里的行号：第 0 行是表头，其余按升序并集排列。 */
    private int rowIndex(int period) {
        for (int index = 0; index < periodRows.size(); index++) {
            if (periodRows.get(index) >= period) return index + 1;
        }
        return periodRows.size();
    }

    private int rowSpan(int startPeriod, int endPeriod) {
        return Math.max(1, rowIndex(endPeriod) - rowIndex(startPeriod) + 1);
    }

    // ---------------------------------------------------------------- 详情弹窗

    /**
     * 用独立 {@code WINDOW_MODAL} Stage 打开课次详情。标题固定为
     * {@link TeacherCourseDetailDialogController#TITLE}（GUI 冒烟测试靠它在窗口列表里认出弹窗），
     * 弹窗持有不可变的条目与周 DTO 引用，不重新推导展示字段。
     */
    private void openDetailDialog(TeacherScheduleEntryDTO entry) {
        FXMLLoader loader = FXMLUtil.getLoader(DIALOG_VIEW);
        Parent root;
        try {
            root = loader.load();
        } catch (IOException | RuntimeException failure) {
            AlertUtil.showError("打开失败", "无法打开课程详情窗口：" + errorMessage(failure));
            return;
        }
        TeacherCourseDetailDialogController dialog = loader.getController();
        dialog.setOpenOffering(offeringId -> openOffering.accept(offeringId));
        dialog.prepare(entry, week);

        Stage stage = new Stage();
        stage.initModality(Modality.WINDOW_MODAL);
        Window owner = scheduleGrid == null || scheduleGrid.getScene() == null
                ? null : scheduleGrid.getScene().getWindow();
        if (owner != null) stage.initOwner(owner);
        stage.setTitle(TeacherCourseDetailDialogController.TITLE);
        stage.setScene(new Scene(root));
        stage.setOnHidden(event -> dialog.dispose());
        stage.show();
    }

    // ---------------------------------------------------------------- 纯文本

    /** 周标签：数字全部来自响应 DTO，例如 {@code 第 8 周（1-16）}。 */
    static String weekLabel(TeacherScheduleWeekDTO value) {
        if (value == null) return "";
        return "第 " + value.getWeek() + " 周（" + value.getMinWeek() + "-"
                + value.getMaxWeek() + "）";
    }

    /** 日期列头：星期名 + 该日期的 MM-dd；非教学日照样成列。 */
    static String dayHeader(TeacherCalendarDateDTO date) {
        if (date == null) return "";
        String value = date.getDate() == null ? "" : date.getDate();
        return weekdayText(date.getTeachingWeekday())
                + (value.length() > 5 ? " " + value.substring(value.length() - 5) : "");
    }

    /** 节次行头：{@code 第 N 节} 加上该节次的时间区间（原样使用 DTO 的定宽 HH:mm:ss）。 */
    static String periodHeader(int period, TeacherPeriodDTO definition) {
        String header = "第 " + period + " 节";
        if (definition == null || definition.getStartTime() == null
                || definition.getEndTime() == null) {
            return header;
        }
        return header + " " + definition.getStartTime() + "-" + definition.getEndTime();
    }

    /** 本周节次行的编号：响应里出现过的节次（按教学日模板可不同）的升序并集。 */
    static List<Integer> periodNumbers(List<TeacherPeriodDTO> periods) {
        List<Integer> numbers = new ArrayList<>();
        if (periods == null) return List.of();
        for (TeacherPeriodDTO period : periods) {
            if (period == null || numbers.contains(period.getPeriod())) continue;
            numbers.add(period.getPeriod());
        }
        numbers.sort(Comparator.naturalOrder());
        return List.copyOf(numbers);
    }

    /** 卡片正文：课程名与地点，全部来自 DTO。 */
    static List<String> cardLines(TeacherScheduleEntryDTO entry) {
        if (entry == null) return List.of("—", "—");
        return List.of(orDash(entry.getCourseName()), orDash(entry.getLocation()));
    }

    /** 调课角标；普通课次没有角标。 */
    static String badgeText(TeacherScheduleEntryDTO entry) {
        if (entry == null || entry.getDisplayKind() == null) return null;
        return switch (entry.getDisplayKind()) {
            case ADJUSTED_ORIGINAL -> "原安排";
            case ADJUSTED_TARGET -> "调课后";
            case NORMAL -> null;
        };
    }

    /** 调课卡片附加的样式类；普通课次没有任何附加样式。 */
    static String cardStyleClass(TeacherScheduleEntryDTO entry) {
        if (entry == null || entry.getDisplayKind() == null) return null;
        return switch (entry.getDisplayKind()) {
            case ADJUSTED_ORIGINAL -> "teacher-schedule-adjusted-original";
            case ADJUSTED_TARGET -> "teacher-schedule-adjusted-target";
            case NORMAL -> null;
        };
    }

    /** 教学日序号 1..7 的中文星期名；教学日历覆盖周末，不能只认五天。 */
    static String weekdayText(int teachingWeekday) {
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return teachingWeekday >= 1 && teachingWeekday <= 7
                ? weekdays[teachingWeekday - 1] : "周" + teachingWeekday;
    }

    private static TeacherPeriodDTO firstPeriod(TeacherScheduleWeekDTO value, int period) {
        if (value == null) return null;
        for (TeacherPeriodDTO candidate : value.getPeriods()) {
            if (candidate != null && candidate.getPeriod() == period) return candidate;
        }
        return null;
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String errorMessage(Throwable failure) {
        return failure == null || failure.getMessage() == null
                ? "未知错误" : failure.getMessage();
    }

    // -------------------------------------------------------------- 测试访问器

    CourseTermDTO term() {
        return term;
    }

    Integer requestedWeek() {
        return requestedWeek;
    }

    Integer loadedWeek() {
        return loadedWeek;
    }

    TeacherScheduleWeekDTO week() {
        return week;
    }

    List<Integer> periodRows() {
        return periodRows;
    }

    /** 本次渲染出的卡片（按日期列、组件、lane 的顺序），节点缺失时同样可用。 */
    List<TeacherScheduleEntryDTO> cardEntries() {
        return cardEntries;
    }

    boolean loading() {
        return loading;
    }

    boolean active() {
        return active;
    }

    String errorText() {
        return errorText;
    }
}
