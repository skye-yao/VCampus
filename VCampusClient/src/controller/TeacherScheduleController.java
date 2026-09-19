package controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
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
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
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
 * <p>周导航只保留一个可变状态 {@link #requestedWeek}（null 表示“由服务端决定本周”）：周次控件是
 * 学生端同款的 {@link Spinner}（上下箭头方向同样反过来，见 {@link WeekSpinner}），范围与值在每次
 * 加载后由响应里的 {@code minWeek}/{@code maxWeek} 同步，没有范围时禁用；“回到本周”只在响应给出
 * {@code currentWeek} 时可用。切换学期会把 {@link #requestedWeek} 重置为 null。
 *
 * <p>冲突布局交给教师端的 {@link TeacherScheduleLayout}：{@code ADJUSTED_ORIGINAL} 只是画在旧位置
 * 上的提示层，不占额外列，因此成对的“原安排/调课后”不会被拆成并排的两列。
 *
 * <p>所有节点都可能为 {@code null}：控制器测试按仓库约定在无工具包、无 FXML 节点的环境下运行，
 * 卡片的收集与网格的几何计算因此与节点绘制分开——节点缺失时卡片集合照常算出，测试才能验证
 * “点的是哪一张卡片”。页面被 {@link #unload()} 卸下后，在途响应一律丢弃（active + generation
 * 双重判定），不会再写任何控件。
 */
/** 教师按教学周查看本人课程表的 JavaFX 页面控制器。 */
public final class TeacherScheduleController {
    static final String LOAD_FAILURE_TEXT = "教学课表加载失败，请重试";
    /** 课次详情弹窗的资源路径；标题由 {@link TeacherCourseDetailDialogController#TITLE} 固定。 */
    static final String DIALOG_VIEW = "/resources/fxml/TeacherCourseDetailDialog.fxml";

    /**
     * 节次列要放下三行里最宽的一行 {@code 第 13 节}（13px 字号约 60px 字形 + 12px 内边距）；
     * 时间各占一行（{@code HH:mm}）之后不再有原来的 120px 单行宽度，但这个固定宽度维持 150：
     * 整表最小宽度因此仍是 {@code 150 + 7 × 84 + 7 × 2 = 752}，小于 860 窗口里可用的约 810px，
     * {@code fitToWidth} 才能把 7 个日期列拉伸到视口宽度（表格铺满）。
     */
    private static final double PERIOD_COLUMN_WIDTH = 150.0;
    /**
     * 日期列的最小宽度：它同时是「铺满」的下限。整表的最小宽度是
     * {@code 150 + 7 × 84 + 7 × 2 = 752}（节次列 + 7 天 + {@code hgap}），比 860 窗口里可用的
     * 约 810px 窄，因此 {@code fitToWidth} 能把 7 个日期列拉伸到视口宽度（表格铺满）；
     * 窗口再窄就轮到 ScrollPane 横向滚动，而不是把列压到读不出来。
     */
    private static final double DAY_COLUMN_MIN_WIDTH = 84.0;
    private static final double DAY_COLUMN_PREF_WIDTH = 112.0;
    private static final double HEADER_ROW_HEIGHT = 34.0;
    /**
     * 节次行的最小高度：行头是三行（{@code 第 N 节} / 开始 / 结束），13px 字号的三行文本加 8px
     * 上下内边距约 57px；课次卡片的两行正文（13px 标题 + 12px 地点）也与行头同处一行，取 60 留余量。
     * 这只是下限：{@code GridPane} 取行约束与子节点最小高度的较大者，窗口变高时再按 {@code vgrow}
     * 把多出来的高度分摊给各行。
     */
    private static final double PERIOD_ROW_HEIGHT = 60.0;

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
    /** 程序化同步周次控件（写范围与值）期间为真：那段时间里的值变化不是用户的选择。 */
    private boolean syncingWeekSpinner;

    @FXML private ComboBox<String> termFilter;
    @FXML private Spinner<Integer> weekSpinner;
    @FXML private Button currentWeekButton;
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
        if (weekSpinner != null) {
            WeekSpinner.installEditor(weekSpinner);
            weekSpinner.valueProperty().addListener((observable, previous, next) -> {
                if (next != null) selectWeek(next);
            });
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

    /** 回到本周：以 {@code week=null} 请求，让服务端按教学日历与系统时钟决定。 */
    @FXML
    void handleBackToCurrentWeek(Event event) {
        loadWeek(null);
    }

    /**
     * 周次控件选定了一周（箭头、键盘或输入框提交）：请求那一周。
     *
     * <p>{@link #syncingWeekSpinner} 为真时直接返回：每次加载后写控件（范围与值）本身会触发值变化
     * 监听，那一次不是用户的选择，不能再发起一次加载。范围不存在时控件是禁用的，用户也点不到。
     */
    void selectWeek(int week) {
        if (syncingWeekSpinner) return;
        loadWeek(week);
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
                errorText = loadFailureText(failure);
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
                        // 保留已显示的周与卡片，只提示失败原因（业务拒绝显示服务端的原话）。
                        requestedWeek = loadedWeek;
                        errorText = loadFailureText(failure);
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
        renderWeekSpinner();
        if (currentWeekButton != null) currentWeekButton.setDisable(!canGoCurrent());
        setActive(loadingLabel, loading);
        setActive(emptyLabel, !loading && errorText == null && week != null
                && cardEntries.isEmpty());
        if (errorLabel != null) errorLabel.setText(errorText == null ? "" : errorText);
        setActive(errorLabel, errorText != null);
        setActive(errorRetryButton, errorText != null);
    }

    /**
     * 重建日期列、节次行与冲突组件；卡片集合与节次行在节点缺失时也照常算出，测试据此断言。
     *
     * <p>重建后视口必须回到左上角：新渲染的一周要从列头与第 1 节开始显示，而不是继承用户上一次
     * 滚动到的位置——否则滚到底看过第 13 节再切周，下一周会停在底部、连日期列头都看不见。
     */
    private void renderGrid() {
        rebuildGrid();
        resetViewport();
    }

    /**
     * 把周次控件同步到当前这一周：范围取响应里的 {@code minWeek}/{@code maxWeek}，值为正在显示的周。
     *
     * <p>写控件本身会触发值变化监听（首次 {@code setValueFactory} 的绑定、以及每一次 {@code setValue}），
     * 因此整段都用 {@link #syncingWeekSpinner} 圈起来，加载不会因为同步控件而再发起一次。
     * 还没有范围（未加载、无数据、加载中）时控件禁用，而不是显示一个错误的周号。
     */
    private void renderWeekSpinner() {
        if (weekSpinner == null) return;
        Integer value = weekSpinnerValue(week, loadedWeek);
        weekSpinner.setDisable(value == null || loading);
        if (value == null) return;
        SpinnerValueFactory<Integer> factory = weekSpinner.getValueFactory();
        syncingWeekSpinner = true;
        try {
            if (factory instanceof SpinnerValueFactory.IntegerSpinnerValueFactory range) {
                range.setMin(week.getMinWeek());
                range.setMax(week.getMaxWeek());
                range.setValue(value);
            } else {
                weekSpinner.setValueFactory(WeekSpinner.valueFactory(
                        week.getMinWeek(), week.getMaxWeek(), value));
            }
        } finally {
            syncingWeekSpinner = false;
        }
    }

    private void rebuildGrid() {
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
            // 表头行固定 34px；节次行 60px 起、不设上限，窗口高过整表时由 vgrow 分摊多出来的高度
            // （max 仍停在 60 的话 vgrow 是无效的，表格纵向永远铺不满）。
            scheduleGrid.getRowConstraints().add(new RowConstraints(HEADER_ROW_HEIGHT));
            for (int row = 0; row < periodRows.size(); row++) {
                RowConstraints periodRow = new RowConstraints(PERIOD_ROW_HEIGHT);
                periodRow.setMinHeight(PERIOD_ROW_HEIGHT);
                periodRow.setMaxHeight(Double.MAX_VALUE);
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

    /**
     * 把滚动视口带回左上角。滚动位置是 {@code ScrollPane} 自己的状态，重建网格不会自动归零，
     * 因此每次渲染后都显式复位；节点缺失（无工具包的控制器测试）时什么都不做。
     */
    private void resetViewport() {
        if (scheduleScroll == null) return;
        scheduleScroll.setVvalue(0.0);
        scheduleScroll.setHvalue(0.0);
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
     * 角标竖排在课次块右侧，不再占标题上方的一行，因此不会撑高课表行；卡片本身是按钮，
     * 点击把这条 DTO 原样交给详情弹窗。
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
        // 竖排角标与“正文吃满剩余宽度”由 AdjustmentBadge 统一提供，与学生端同构；
        // 普通课次拿到的就是上面这个正文节点本身。
        Node graphic = AdjustmentBadge.badged(content, badgeText(entry),
                "teacher-schedule-badge");

        Button card = new Button();
        card.getStyleClass().add("teacher-schedule-card");
        String adjustmentStyle = cardStyleClass(entry);
        if (adjustmentStyle != null) {
            card.getStyleClass().add(adjustmentStyle);
        }
        card.setGraphic(graphic);
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
     *
     * <p>窗口打开时就贴合内容：{@code sizeToScene()} 在窗口还没有 peer 时会被记下来，显示时再按
     * 应用了样式之后的偏好尺寸执行一次（全新 Stage 的首次显示本来也会走这一步，这里显式写出来是
     * 为了不依赖那一步的细节）。真正会把底部按钮裁掉的是打开之后才到达的异步内容——教学班快照、
     * 提交提示与失败重试都会把内容顶出窗口，因此由 {@link TeacherCourseDetailDialogController}
     * 在每次渲染后重新贴合（只增不减）。
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
        // 窗口还没显示（没有 peer）时记下这次请求，show() 时按样式应用之后的偏好尺寸执行。
        stage.sizeToScene();
        stage.show();
    }

    // ---------------------------------------------------------------- 纯文本

    /**
     * 周次控件该显示的周：数字全部来自响应 DTO，客户端不自己推周号。
     *
     * <p>范围是响应里的 {@code minWeek}/{@code maxWeek}；值优先取已加载（正在显示）的周——用户翻到
     * 别的周之后控件必须继续显示那一周，而不是弹回本周；没有已加载的周时退回 {@code currentWeek}，
     * 再退回 {@code minWeek}。范围不存在（未加载、无数据）时返回 {@code null}，控件随之禁用，
     * 而不是显示一个错误的周号。
     */
    static Integer weekSpinnerValue(TeacherScheduleWeekDTO week, Integer loadedWeek) {
        if (week == null) return null;
        int min = week.getMinWeek();
        int max = week.getMaxWeek();
        if (min > max) return null;
        Integer value = loadedWeek != null ? loadedWeek : week.getCurrentWeek();
        if (value == null) value = min;
        return Math.max(min, Math.min(max, value));
    }

    /** 日期列头：星期名 + 该日期的 MM-dd；非教学日照样成列。 */
    static String dayHeader(TeacherCalendarDateDTO date) {
        if (date == null) return "";
        String value = date.getDate() == null ? "" : date.getDate();
        return weekdayText(date.getTeachingWeekday())
                + (value.length() > 5 ? " " + value.substring(value.length() - 5) : "");
    }

    /**
     * 节次行头三行：{@code 第 N 节} 一行，开始与结束时间各占一行（只到分钟，不显示秒、也不加横杠）。
     * 时间是展示用的，兜底照旧：节次模板缺失或任一时间缺失、空白时只画 {@code 第 N 节} 一行，
     * 而不是画一行半截的时间。
     */
    static String periodHeader(int period, TeacherPeriodDTO definition) {
        String header = "第 " + period + " 节";
        if (definition == null) return header;
        String start = clockTime(definition.getStartTime());
        String end = clockTime(definition.getEndTime());
        if (start == null || end == null) return header;
        return header + "\n" + start + "\n" + end;
    }

    /**
     * 行头时间只取到分钟：定宽的 {@code HH:mm:ss} 截成 {@code HH:mm}；已经是 {@code HH:mm} 的短串
     * 与占位符（如 {@code —}）原样保留；null 与空白返回 {@code null}，由调用方退回单行行头。
     */
    private static String clockTime(String value) {
        if (value == null) return null;
        String text = value.trim();
        if (text.isEmpty()) return null;
        if (text.length() >= 8 && text.charAt(2) == ':' && text.charAt(5) == ':'
                && Character.isDigit(text.charAt(0)) && Character.isDigit(text.charAt(1))
                && Character.isDigit(text.charAt(3)) && Character.isDigit(text.charAt(4))
                && Character.isDigit(text.charAt(6)) && Character.isDigit(text.charAt(7))) {
            return text.substring(0, 5);
        }
        return text;
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

    /**
     * 加载失败的提示文案。
     *
     * <p>只有当失败是服务端给出的业务拒绝（{@code BAD_REQUEST} / {@code NOT_FOUND}）时，才把服务端
     * 自己的话原样显示——那是给用户看的，例如「该学期暂无已发布的教学日历」。判定用的是
     * {@link TeacherCourseServiceException#getCode()} 而不是“消息非空”，因此客户端技术串
     * （如 {@code 缺少响应字段: schedule}，它带的是 {@code ERROR}）与传输/连接失败一样只显示可重试的
     * 通用文案，绝不把内部细节当成用户可见的提示。
     */
    static String loadFailureText(Throwable failure) {
        Throwable cause = unwrap(failure);
        if (cause instanceof TeacherCourseServiceException serviceFailure) {
            MessageCode code = serviceFailure.getCode();
            String message = serviceFailure.getMessage();
            if ((code == MessageCode.BAD_REQUEST || code == MessageCode.NOT_FOUND)
                    && message != null && !message.isBlank()) {
                return message;
            }
        }
        return LOAD_FAILURE_TEXT;
    }

    /** {@code CompletableFuture.whenComplete} 可能把真实异常包在 CompletionException 里。 */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
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
