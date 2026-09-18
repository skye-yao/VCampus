package controller;

import dto.course.CourseCalendarDateDTO;
import dto.course.CoursePeriodDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.Node;
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
import model.course.CourseTermView;
import model.course.ScheduleEntryView;
import model.course.ScheduleWeekView;
import service.CourseService;
import service.CourseServices;
import util.AlertUtil;

public final class ScheduleController {
    /**
     * 节次列的固定宽度：行头是 {@code 第 13 节 18:00:00-18:45:00}（11px 字号约 120px 字形），窄了
     * 会被 {@code Label} 默认的 {@code TextOverrun.ELLIPSIS} 裁掉时刻。与教师端同值——两边的行头
     * 文案与字号完全一样（原来 50px 只够放 {@code 第 N 节}，放不下本 Task 新增的时间区间）。教师端的
     * 行头另有 {@code 4px 6px} 内边距，学生端的 {@code .course-period-label} 没有，150 只用字形宽度
     * 解释。
     */
    private static final double PERIOD_COLUMN_WIDTH = 150.0;
    /** 日期列的最小宽度（同时是"铺满"的下限）与首选宽度，沿用学生端原有的观感。 */
    private static final double DAY_COLUMN_MIN_WIDTH = 72.0;
    private static final double DAY_COLUMN_PREF_WIDTH = 96.0;

    private final CourseService service;
    private final BiConsumer<String, String> infoReporter;
    private final BiConsumer<String, String> errorReporter;
    private final Consumer<Runnable> fxExecutor;
    private CourseTermView selectedTerm;
    /** 正在显示的那一周（未加载、加载中为 null）；周次控件与“回到本周”的可用性都由它决定。 */
    private ScheduleWeekView week;
    /** 最近一次请求的周次；null = 跟随服务端当前周（“回到本周”与刷新都用它）。 */
    private Integer requestedWeek;
    /** 同步周次控件（范围与值）时置位：那次值变化不是用户的选择，不得再发起一次加载。 */
    private boolean syncingWeekSpinner;
    /** 当前这张表画了哪些节次行（不含表头行），由本教学周的节次字典决定。 */
    private List<Integer> periodRows = List.of();
    private long loadGeneration; // 请求的版本管理，用来解决用户短时间多次点击，确认最终的返回结果
    private long termLoadGeneration; // 学期加载的版本管理，避免陈旧学期覆盖最新服务端学期

    @FXML private ComboBox<CourseTermView> termFilter;
    @FXML private Spinner<Integer> weekSpinner;
    @FXML private Button currentWeekButton;
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
        if (weekSpinner != null) {
            configureWeekSpinner();
            weekSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue != null) selectWeek(newValue);
            });
        }
        termFilter.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.equals(selectedTerm)) {
                selectedTerm = newValue;
                // 换学期：周次回到“由服务端决定”，否则会把上一个学期的周号带进新日历。
                loadWeek(null);
            }
        });
        renderSchedule(null);
        renderNotices(Collections.emptyList());
        loadTerms();
    }

    /**
     * 周次输入框：允许点进输入框直接敲周次。只放行数字、提交后夹取并回写文本——这两条规则与教师端的
     * 周次控件共用 {@link WeekSpinner}，两端同构；唯一的非默认之处是上下箭头的方向反过来
     * （向上 = 往前一周），同样由它统一提供。
     *
     * <p>范围与初值不在客户端声明：它们来自服务端教学日历（{@link #renderWeekSpinner()} 在每次渲染后
     * 同步），因此这里只装输入框，值工厂等第一次响应回来再建；此前控件是禁用的（原来的 1..20 是
     * 学生端没有服务端周范围时的硬编码，与教师端不一致）。
     */
    private void configureWeekSpinner() {
        WeekSpinner.installEditor(weekSpinner);
    }

    @FXML
    public void refresh() {
        fxExecutor.accept(() -> loadWeek(requestedWeek));
    }

    /** 回到本周：以 {@code week=null} 请求，让服务端按教学日历与系统时钟决定。 */
    @FXML
    void handleBackToCurrentWeek() {
        loadWeek(null);
    }

    /**
     * 周次控件选定了一周（箭头、键盘或输入框提交）：请求那一周。
     *
     * <p>{@link #syncingWeekSpinner} 为真时直接返回：每次加载后写控件（范围与值）本身会触发值变化
     * 监听，那一次不是用户的选择，不能再发起一次加载。没有范围时控件是禁用的，用户也点不到。
     */
    void selectWeek(int week) {
        if (syncingWeekSpinner) return;
        loadWeek(week);
    }

    /**
     * “回到本周”是否可用：服务端给出了当前教学周才可以点。今天不在学期内时 {@code currentWeek} 为
     * null，此时点了也没有意义；没有响应（未加载、加载中、加载失败）时同样不可用。与教师端
     * {@code TeacherScheduleController.canGoCurrent()} 同义。
     */
    static boolean canGoCurrent(ScheduleWeekView week) {
        return week != null && week.getCurrentWeek() != null;
    }

    private void loadTerms() {
        requestTerms(terms -> {
            termFilter.getItems().setAll(terms);
            if (terms.isEmpty()) {
                selectedTerm = null;
                renderSchedule(null);
                renderNotices(Collections.emptyList(), "暂无学期");
            } else {
                termFilter.setValue(terms.get(0));
            }
        }, error -> {
            selectedTerm = null;
            termFilter.getItems().clear();
            renderSchedule(null);
            renderNotices(Collections.emptyList(), "加载失败，请刷新重试");
            errorReporter.accept("加载失败", errorMessage(error));
        });
    }

    void requestTerms(Consumer<List<CourseTermView>> onLoaded,
            Consumer<Throwable> onError) {
        long generation = ++termLoadGeneration;
        service.loadTerms().whenComplete((terms, error) -> fxExecutor.accept(() -> {
            if (generation != termLoadGeneration) return;
            if (error != null) {
                onError.accept(error);
            } else {
                onLoaded.accept(List.copyOf(terms));
            }
        }));
    }

    /**
     * 课表与调课通知两个请求。
     *
     * <p>明确给了周次时两者**并行**发出（通知查哪一周已经是已知的，没有理由让它跟着课表的往返
     * 串行化）；{@code week} 为 null 时才两段式——服务端解析出的那一周才是通知该查的周次，通知因此
     * 要等课表回来再发，否则只能猜一个周号。两种形态共用同一个 generation 守卫与同一套错误语义。
     */
    void requestScheduleData(CourseTermView term, Integer week,
            Consumer<ScheduleData> onLoaded, Consumer<Throwable> onError) {
        long generation = ++loadGeneration;
        if (week != null) {
            CompletableFuture<ScheduleWeekView> scheduleFuture = service.loadSchedule(term, week);
            CompletableFuture<List<CourseNoticeView>> noticeFuture = service.loadNotices(term, week);
            scheduleFuture.thenCombine(noticeFuture, ScheduleData::new)
                    .whenComplete((data, error) -> fxExecutor.accept(() -> {
                        if (generation != loadGeneration) { // 忽略旧请求（用户多次快速点击refresh）
                            return;
                        }
                        if (error != null) {
                            onError.accept(error);
                        } else {
                            onLoaded.accept(data);
                        }
                    }));
            return;
        }
        service.loadSchedule(term, null).whenComplete((schedule, error) ->
                fxExecutor.accept(() -> {
                    if (generation != loadGeneration) {
                        return;
                    }
                    if (error != null) {
                        onError.accept(error);
                        return;
                    }
                    Integer noticeWeek = schedule == null ? null : schedule.getWeek();
                    if (noticeWeek == null) {
                        onLoaded.accept(new ScheduleData(schedule, Collections.emptyList()));
                        return;
                    }
                    service.loadNotices(term, noticeWeek).whenComplete((notices, noticeError) ->
                            fxExecutor.accept(() -> {
                                if (generation != loadGeneration) return;
                                if (noticeError != null) {
                                    onError.accept(noticeError);
                                } else {
                                    onLoaded.accept(new ScheduleData(schedule, notices));
                                }
                            }));
                }));
    }

    /** 请求某一周（{@code targetWeek} 为 null 表示跟随服务端当前周）并重建整页。 */
    private void loadWeek(Integer targetWeek) {
        CourseTermView term = selectedTerm != null ? selectedTerm : termFilter.getValue();
        if (term == null) {
            return;
        }
        requestedWeek = targetWeek;

        // 清空旧数据并显示加载状态
        renderSchedule(null);
        renderNotices(Collections.emptyList(), "正在加载课表...");
        requestScheduleData(term, targetWeek, data -> {
            renderSchedule(data.schedule);
            renderNotices(data.notices);
        }, error -> {
            renderSchedule(null);
            renderNotices(Collections.emptyList(), "加载失败，请刷新重试");
            errorReporter.accept("加载失败", errorMessage(error));
        });
    }

    /**
     * 按教学日历画出这一周的网格：行 = 该周节次字典里出现过的节次，列 = 该周的每一个日期
     * （含非教学日与周末）。几何规则与教师端 {@code TeacherScheduleController.rebuildGrid()} 同源，
     * 客户端与视图里都不再有节次/星期的常量。{@code week} 为 null 或该周没有节次定义时只清空网格。
     */
    private void renderSchedule(ScheduleWeekView week) {
        this.week = week;
        scheduleGrid.getChildren().clear();
        scheduleGrid.getColumnConstraints().clear();
        scheduleGrid.getRowConstraints().clear();
        periodRows = week == null ? List.of() : periodNumbers(week.getPeriods());
        renderWeekControls();
        if (periodRows.isEmpty()) {
            return;
        }

        List<CourseCalendarDateDTO> dates = week.getDates();
        // 列几何同样由数据决定：1 条定宽节次列 + 每个日期一条可拉伸的日期列。视图不再声明任何列约束
        // （原来写死 1 + 5 条），因此多于 5 个日期的列也有约束与 hgrow，少于 5 个时也不会留下空列。
        scheduleGrid.getColumnConstraints().add(new ColumnConstraints(
                PERIOD_COLUMN_WIDTH, PERIOD_COLUMN_WIDTH, PERIOD_COLUMN_WIDTH));
        for (int column = 0; column < dates.size(); column++) {
            ColumnConstraints dayColumn = new ColumnConstraints(
                    DAY_COLUMN_MIN_WIDTH, DAY_COLUMN_PREF_WIDTH, Double.MAX_VALUE);
            dayColumn.setHgrow(Priority.ALWAYS);
            scheduleGrid.getColumnConstraints().add(dayColumn);
        }

        RowConstraints headerRow = new RowConstraints(30.0);
        scheduleGrid.getRowConstraints().add(headerRow);
        for (int row = 0; row < periodRows.size(); row++) {
            RowConstraints periodRow = new RowConstraints();
            periodRow.setMinHeight(30.0);
            periodRow.setVgrow(Priority.ALWAYS);
            scheduleGrid.getRowConstraints().add(periodRow);
        }

        addGridLabel("节次", 0, 0, "course-schedule-header");
        for (int column = 0; column < dates.size(); column++) {
            addGridLabel(dayHeader(dates.get(column)), column + 1, 0,
                    "course-schedule-header");
        }
        for (int row = 0; row < periodRows.size(); row++) {
            int period = periodRows.get(row);
            addGridLabel(periodHeader(period, firstPeriod(week, period)), 0, row + 1,
                    "course-period-label");
            for (int column = 0; column < dates.size(); column++) {
                addGridCell(column + 1, row + 1);
            }
        }

        int firstPeriod = periodRows.get(0);
        int lastPeriod = periodRows.get(periodRows.size() - 1);
        for (int column = 0; column < dates.size(); column++) {
            CourseCalendarDateDTO date = dates.get(column);
            for (ScheduleLayout.Component component : ScheduleLayout.layoutDay(
                    week.getEntries(), date.getTeachingWeekday(), firstPeriod, lastPeriod)) {
                GridPane componentGrid = createComponentGrid(component);
                scheduleGrid.add(componentGrid, column + 1, rowIndex(component.getStartPeriod()));
                GridPane.setRowSpan(componentGrid,
                        component.getEndPeriod() - component.getStartPeriod() + 1);
                GridPane.setHgrow(componentGrid, Priority.ALWAYS);
                GridPane.setVgrow(componentGrid, Priority.ALWAYS);
            }
        }
    }

    /** 周次控件与「回到本周」的可用性：两者都只由服务端响应决定，见 {@link #renderWeekSpinner()}。 */
    private void renderWeekControls() {
        renderWeekSpinner();
        if (currentWeekButton != null) currentWeekButton.setDisable(!canGoCurrent(week));
    }

    /**
     * 把周次控件同步到当前这一周：范围取响应里的 {@code minWeek}/{@code maxWeek}，值为正在显示的周。
     *
     * <p>写控件本身会触发值变化监听（首次 {@code setValueFactory} 的绑定、以及每一次 {@code setValue}），
     * 因此整段都用 {@link #syncingWeekSpinner} 圈起来，加载不会因为同步控件而再发起一次。还没有范围
     * （未加载、无数据、加载中）时控件禁用，而不是显示一个编造的周号——与教师端
     * {@code TeacherScheduleController.renderWeekSpinner()} 同构。
     */
    private void renderWeekSpinner() {
        if (weekSpinner == null) return;
        Integer value = week == null ? null : week.getWeek();
        weekSpinner.setDisable(value == null);
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

    /** 本周节次行的编号：响应里出现过的节次（按教学日模板可不同）的升序并集。 */
    static List<Integer> periodNumbers(List<CoursePeriodDTO> periods) {
        List<Integer> numbers = new ArrayList<>();
        if (periods == null) return List.of();
        for (CoursePeriodDTO period : periods) {
            if (period == null || numbers.contains(period.getPeriod())) continue;
            numbers.add(period.getPeriod());
        }
        numbers.sort(Comparator.naturalOrder());
        return List.copyOf(numbers);
    }

    /** 节次行头：{@code 第 N 节} 加上该节次的时间区间（原样使用 DTO 的定宽 HH:mm:ss）。 */
    static String periodHeader(int period, CoursePeriodDTO definition) {
        String header = "第 " + period + " 节";
        if (definition == null || definition.getStartTime() == null
                || definition.getEndTime() == null) {
            return header;
        }
        return header + " " + definition.getStartTime() + "-" + definition.getEndTime();
    }

    /** 日期列头：星期名 + 该日期的 MM-dd；非教学日照样成列。 */
    static String dayHeader(CourseCalendarDateDTO date) {
        if (date == null) return "";
        String value = date.getDate() == null ? "" : date.getDate();
        return weekdayName(date.getTeachingWeekday())
                + (value.length() > 5 ? " " + value.substring(value.length() - 5) : "");
    }

    private static String weekdayName(int teachingWeekday) {
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return teachingWeekday >= 1 && teachingWeekday <= 7
                ? weekdays[teachingWeekday - 1] : "周" + teachingWeekday;
    }

    /** 该节次在本周日历里的定义：同一节次可能因教学日模板不同而有多条，取第一条。 */
    private static CoursePeriodDTO firstPeriod(ScheduleWeekView week, int period) {
        if (week == null) return null;
        for (CoursePeriodDTO candidate : week.getPeriods()) {
            if (candidate != null && candidate.getPeriod() == period) return candidate;
        }
        return null;
    }

    /** 节次 → 网格行号（表头占第 0 行）。与教师端 `:538-544` 逐字同构。 */
    private int rowIndex(int period) {
        return rowIndexOf(periodRows, period);
    }

    /**
     * 第一个不小于 {@code period} 的节次行（表头占第 0 行）；没有这样的行时夹到表尾。
     *
     * <p>用 {@code >=} 而不是相等，是为了容忍节次字典的缺口——某天模板只有 1、2、4 节时，落在缺口
     * 里的第 3 节要吸附到第 4 节那一行，而不是找不到行。末尾夹到 {@code periodRows.size()} 则保证
     * 绝不返回 {@code -1}：{@code GridPane.add(node, column, -1)} 会被 JavaFX 当作非法行号抛
     * {@code IllegalArgumentException}，整张表都画不出来。教师端 {@code TeacherScheduleController}
     * `:538-544` 是同一条规则。
     */
    static int rowIndexOf(List<Integer> periodRows, int period) {
        for (int index = 0; index < periodRows.size(); index++) {
            if (periodRows.get(index) >= period) return index + 1;
        }
        return periodRows.size();
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

        // 调和后的两个位置共用同一块视觉语言：旧位置灰显并带“原安排”角标，新位置带“调课后”角标。
        // 角标竖排在课次块右侧（不再是标题上方的一行），因此不会撑高课表行；普通课程拿到的就是
        // 正文本身。竖排与“正文吃满剩余宽度”都由 AdjustmentBadge 统一提供，与教师端同构。
        Node graphic = AdjustmentBadge.badged(content, adjustmentBadge(entry),
                "course-adjustment-badge");

        Button block = new Button();
        block.getStyleClass().add("course-class-block");
        String adjustmentStyle = adjustmentStyleClass(entry);
        if (adjustmentStyle != null) {
            block.getStyleClass().add(adjustmentStyle);
        }
        block.setGraphic(graphic);
        block.setMinSize(0.0, 0.0);
        block.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        block.setOnAction(event -> infoReporter.accept("课程详情", detailText(entry)));
        return block;
    }

    /** 调课块附加的样式类；普通课程没有任何附加样式。 */
    static String adjustmentStyleClass(ScheduleEntryView entry) {
        return switch (entry.getDisplayKind()) {
            case ADJUSTED_ORIGINAL -> "course-adjusted-original";
            case ADJUSTED_TARGET -> "course-adjusted-target";
            case NORMAL -> null;
        };
    }

    /** 调课块头部的角标文案；普通课程没有角标。 */
    static String adjustmentBadge(ScheduleEntryView entry) {
        return switch (entry.getDisplayKind()) {
            case ADJUSTED_ORIGINAL -> "原安排";
            case ADJUSTED_TARGET -> "调课后";
            case NORMAL -> null;
        };
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

    static String detailText(ScheduleEntryView entry) {
        int endPeriod = entry.getStartPeriod() + entry.getPeriodCount() - 1;
        StringBuilder text = new StringBuilder("课程名称：").append(entry.getCourseName())
                .append("\n课程代码：").append(entry.getCourseCode())
                .append("\n上课时间：").append(weekdayName(entry.getDayOfWeek())).append(" 第 ")
                .append(entry.getStartPeriod()).append("-").append(endPeriod).append(" 节")
                .append("\n上课地点：").append(entry.getLocation())
                .append("\n授课教师：").append(entry.getTeacher());
        // 两个位置携带完全相同的调课文案，因此从任意一块打开详情都能读到完整信息。
        if (entry.getOriginalScheduleText() != null || entry.getAdjustedScheduleText() != null) {
            text.append("\n调课状态：").append(adjustmentBadge(entry))
                    .append("\n原安排：").append(entry.getOriginalScheduleText())
                    .append("\n调整后：").append(entry.getAdjustedScheduleText());
            if (entry.getAdjustmentReason() != null) {
                text.append("\n调课原因：").append(entry.getAdjustmentReason());
            }
        }
        return text.append("\n备注：第 ").append(entry.getStartWeek()).append("-")
                .append(entry.getEndWeek()).append(" 周").toString();
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

    static final class ScheduleData {
        private final ScheduleWeekView schedule;
        private final List<CourseNoticeView> notices;

        private ScheduleData(ScheduleWeekView schedule,
                List<CourseNoticeView> notices) {
            this.schedule = schedule;
            this.notices = notices;
        }

        ScheduleWeekView getSchedule() {
            return schedule;
        }

        List<CourseNoticeView> getNotices() {
            return notices;
        }
    }
}
