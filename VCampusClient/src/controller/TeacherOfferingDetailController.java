package controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教学班详情页（设计 §5.1）：基本信息、学生名单、上课安排、成绩情况四个 Tab。
 *
 * <p>成绩情况 Tab 自 T5 起指向已交付的成绩录入页：本页只做只读摘要与入口，完整编辑在
 * {@link TeacherGradeBookController} 里进行。
 *
 * <p>四个 Tab 的数据都来自 DTO，按需加载：基本信息在打开教学班时加载，名单与安排只在对应 Tab
 * 首次被选中时各请求一次并缓存，隐藏的 Tab 不发起任何请求。名单支持姓名/学号筛选与正常/退课
 * 状态筛选，分页超过一页时可翻页；退课行只读保留为历史。
 *
 * <p>本页刻意保持只读：导出按钮保持禁用（伞形计划的 T5 接通），教师看不到任何添加/删除学生
 * 入口，成绩情况只显示只读摘要，登记成绩的入口只调用工作台的 {@code openGrades}（由工作台打开
 * {@link TeacherGradeBookController}），本页不发起任何写请求。开课学院缺值显示“未维护”，
 * 绝不用教师个人学院冒充。
 *
 * <p>打开另一个教学班或 {@link #release()} 之后，任何在途响应都被丢弃（generation + active 判定），
 * 页面也不会保留上一个教学班的数据。
 */
public final class TeacherOfferingDetailController {
    /** 名单分页大小，与列表页共用固定页长。 */
    static final int ROSTER_PAGE_SIZE = 20;
    static final String ALL_LABEL = "全部";
    static final String ENROLLED_LABEL = "正常";
    static final String DROPPED_LABEL = "退课";
    static final String UNMAINTAINED_COLLEGE = "未维护";
    static final String BASIC_FAILURE_TEXT = "基本信息加载失败，请重试";
    static final String ROSTER_FAILURE_TEXT = "学生名单加载失败，请重试";
    static final String SCHEDULE_FAILURE_TEXT = "上课安排加载失败，请重试";

    private static final int ENROLLED_CODE = 2;
    private static final int DROPPED_CODE = 3;
    private static final int BASIC_TAB = 0;
    private static final int ROSTER_TAB = 1;
    private static final int SCHEDULE_TAB = 2;
    private static final int GRADE_TAB = 3;

    private final TeacherCourseService service;
    private final Consumer<Runnable> fxExecutor;

    private Runnable backAction = () -> { };
    private Consumer<String> openGrades = offeringId -> { };

    private String offeringId;
    private boolean active;
    private int selectedTab = BASIC_TAB;
    private boolean syncingTabs;
    private boolean syncingRosterFilters;

    private TeacherOfferingDetailDTO detail;
    private boolean loadingBasic;
    private String basicErrorText;
    private long detailGeneration;

    private List<TeacherRosterRowDTO> roster = List.of();
    private String rosterQuery = "";
    private String rosterStatusLabel = ALL_LABEL;
    private int rosterPage = 1;
    private int rosterLoadedPage = 1;
    private long rosterTotalCount;
    private boolean rosterLoaded;
    private boolean loadingRoster;
    private String rosterErrorText;
    private long rosterGeneration;

    private List<ScheduleArrangementDTO> schedules = List.of();
    private boolean scheduleLoaded;
    private boolean loadingSchedules;
    private String scheduleErrorText;
    private long scheduleGeneration;

    @FXML private Button backToOfferingsButton;
    @FXML private Label detailTitleLabel;
    @FXML private Button gradeEntryButton;
    @FXML private Button detailRefreshButton;
    @FXML private TabPane detailTabs;
    @FXML private VBox basicBody;
    @FXML private Label basicErrorLabel;
    @FXML private TextField rosterSearchField;
    @FXML private ComboBox<String> rosterStatusFilter;
    @FXML private Button exportButton;
    @FXML private TableView<TeacherRosterRowDTO> rosterTable;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterUidColumn;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterNameColumn;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterMajorColumn;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterStatusColumn;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterSelectedColumn;
    @FXML private TableColumn<TeacherRosterRowDTO, String> rosterDroppedColumn;
    @FXML private Label rosterLoadingLabel;
    @FXML private Label rosterEmptyLabel;
    @FXML private Label rosterErrorLabel;
    @FXML private Button rosterRetryButton;
    @FXML private Label rosterPageLabel;
    @FXML private Button previousRosterPageButton;
    @FXML private Button nextRosterPageButton;
    @FXML private VBox scheduleBody;
    @FXML private Label scheduleErrorLabel;
    @FXML private VBox gradeBody;

    public TeacherOfferingDetailController() {
        this(TeacherCourseServices.current(), Platform::runLater);
    }

    TeacherOfferingDetailController(TeacherCourseService service, Consumer<Runnable> fxExecutor) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
        this.fxExecutor = Objects.requireNonNull(fxExecutor, "FX executor is required");
    }

    @FXML
    public void initialize() {
        bindColumn(rosterUidColumn, 0);
        bindColumn(rosterNameColumn, 1);
        bindColumn(rosterMajorColumn, 2);
        bindColumn(rosterStatusColumn, 3);
        bindColumn(rosterSelectedColumn, 4);
        bindColumn(rosterDroppedColumn, 5);
        if (rosterStatusFilter != null) {
            rosterStatusFilter.getItems().setAll(ALL_LABEL, ENROLLED_LABEL, DROPPED_LABEL);
            rosterStatusFilter.setValue(ALL_LABEL);
            rosterStatusFilter.valueProperty().addListener((observable, previous, next) -> {
                if (!syncingRosterFilters) applyRosterStatus(next);
            });
        }
        if (rosterSearchField != null) {
            rosterSearchField.textProperty().addListener((observable, previous, next) -> {
                if (!syncingRosterFilters) applyRosterFilter(next);
            });
        }
        if (detailTabs != null) {
            detailTabs.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, previous, next) -> {
                        if (syncingTabs) return;
                        selectTab(next == null ? BASIC_TAB : next.intValue());
                    });
        }
        render();
    }

    /** 打开一个教学班：清空上一个教学班的全部状态，回到基本信息并加载。 */
    void showOffering(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        release();
        this.offeringId = offeringId;
        active = true;
        selectedTab = BASIC_TAB;
        render();
        loadBasic();
    }

    /** 工作台离开本页时调用：丢弃在途响应，并且不再保留这个教学班的任何数据。 */
    void release() {
        offeringId = null;
        active = false;
        detail = null;
        loadingBasic = false;
        basicErrorText = null;
        detailGeneration++;
        roster = List.of();
        rosterTotalCount = 0;
        rosterPage = 1;
        rosterLoadedPage = 1;
        rosterLoaded = false;
        loadingRoster = false;
        rosterErrorText = null;
        rosterGeneration++;
        schedules = List.of();
        scheduleLoaded = false;
        loadingSchedules = false;
        scheduleErrorText = null;
        scheduleGeneration++;
        selectedTab = BASIC_TAB;
        render();
    }

    /** 切换 Tab；名单与安排在首次选中时各加载一次，隐藏的 Tab 不发起请求。 */
    void selectTab(int index) {
        if (index < BASIC_TAB || index > GRADE_TAB) return;
        selectedTab = index;
        if (active && offeringId != null) ensureTab(index);
        render();
    }

    @FXML
    void handleBack(Event event) {
        backAction.run();
    }

    @FXML
    void handleRefresh(Event event) {
        if (offeringId == null || !active) return;
        loadBasic();
        if (selectedTab == ROSTER_TAB) {
            loadRoster(rosterPage);
        } else if (selectedTab == SCHEDULE_TAB) {
            loadSchedules();
        }
    }

    /**
     * 登记成绩的入口：只在本人具备成绩编辑能力时把教学班交给工作台；本页不发起任何写请求。
     */
    @FXML
    void handleOpenGrades(Event event) {
        if (offeringId == null || !canEditGrades()) return;
        openGrades.accept(offeringId);
    }

    @FXML
    void handleNextRosterPage(Event event) {
        goToRosterPage(rosterPage + 1);
    }

    @FXML
    void handlePreviousRosterPage(Event event) {
        goToRosterPage(rosterPage - 1);
    }

    void setBackAction(Runnable backAction) {
        this.backAction = backAction == null ? () -> { } : backAction;
    }

    void setOpenGrades(Consumer<String> openGrades) {
        this.openGrades = openGrades == null ? offeringId -> { } : openGrades;
    }

    void applyRosterFilter(String nextQuery) {
        rosterQuery = nextQuery == null ? "" : nextQuery;
        syncingRosterFilters = true;
        try {
            if (rosterSearchField != null && !rosterQuery.equals(rosterSearchField.getText())) {
                rosterSearchField.setText(rosterQuery);
            }
        } finally {
            syncingRosterFilters = false;
        }
        goToRosterPage(1);
    }

    void applyRosterStatus(String label) {
        String next = label == null ? ALL_LABEL : label;
        if (next.equals(rosterStatusLabel)) return;
        rosterStatusLabel = next;
        syncingRosterFilters = true;
        try {
            if (rosterStatusFilter != null && !next.equals(rosterStatusFilter.getValue())) {
                rosterStatusFilter.setValue(next);
            }
        } finally {
            syncingRosterFilters = false;
        }
        goToRosterPage(1);
    }

    void goToRosterPage(int nextPage) {
        if (active && offeringId != null) {
            loadRoster(Math.max(1, nextPage));
            return;
        }
        rosterPage = Math.max(1, nextPage);
        render();
    }

    private void ensureTab(int index) {
        if (index == ROSTER_TAB && !rosterLoaded && !loadingRoster) {
            loadRoster(rosterPage);
        } else if (index == SCHEDULE_TAB && !scheduleLoaded && !loadingSchedules) {
            loadSchedules();
        }
    }

    // ------------------------------------------------------------------ 加载

    private void loadBasic() {
        long generation = ++detailGeneration;
        loadingBasic = true;
        basicErrorText = null;
        render();
        service.getOffering(offeringId).whenComplete((value, failure) -> fxExecutor.accept(() -> {
            if (!isCurrent(generation, detailGeneration)) return;
            loadingBasic = false;
            if (failure != null) {
                basicErrorText = BASIC_FAILURE_TEXT;
                render();
                return;
            }
            detail = value;
            basicErrorText = null;
            renderBasic();
            render();
        }));
    }

    private void loadRoster(int nextPage) {
        rosterPage = Math.max(1, nextPage);
        long generation = ++rosterGeneration;
        loadingRoster = true;
        rosterErrorText = null;
        render();
        service.listOfferingStudents(offeringId, blankToNull(rosterQuery), enrollmentStatusCode(),
                        rosterPage, ROSTER_PAGE_SIZE)
                .whenComplete((result, failure) -> fxExecutor.accept(() -> {
                    if (!isCurrent(generation, rosterGeneration)) return;
                    loadingRoster = false;
                    if (failure != null) {
                        rosterPage = rosterLoadedPage;
                        rosterErrorText = ROSTER_FAILURE_TEXT;
                        render();
                        return;
                    }
                    rosterErrorText = null;
                    roster = List.copyOf(result == null ? List.of() : result.getItems());
                    rosterTotalCount = result == null ? 0 : result.getTotalCount();
                    rosterLoadedPage = rosterPage;
                    rosterLoaded = true;
                    render();
                }));
    }

    private void loadSchedules() {
        long generation = ++scheduleGeneration;
        loadingSchedules = true;
        scheduleErrorText = null;
        render();
        service.listOfferingSchedules(offeringId).whenComplete((value, failure) ->
                fxExecutor.accept(() -> {
                    if (!isCurrent(generation, scheduleGeneration)) return;
                    loadingSchedules = false;
                    if (failure != null) {
                        scheduleErrorText = SCHEDULE_FAILURE_TEXT;
                        render();
                        return;
                    }
                    schedules = List.copyOf(value == null ? List.of() : value);
                    scheduleErrorText = null;
                    scheduleLoaded = true;
                    renderSchedules();
                    render();
                }));
    }

    /** 当前 generation 且页面仍持有同一个教学班时才允许写界面。 */
    private boolean isCurrent(long generation, long counter) {
        return active && offeringId != null && generation == counter;
    }

    private Integer enrollmentStatusCode() {
        if (ENROLLED_LABEL.equals(rosterStatusLabel)) return ENROLLED_CODE;
        if (DROPPED_LABEL.equals(rosterStatusLabel)) return DROPPED_CODE;
        return null;
    }

    // ------------------------------------------------------------------ 渲染

    private void render() {
        if (detailTitleLabel != null) {
            detailTitleLabel.setText(detail == null || detail.getOffering() == null
                    ? "教学班详情" : detail.getOffering().getOfferingName());
        }
        if (gradeEntryButton != null) gradeEntryButton.setDisable(!canEditGrades());
        if (detailTabs != null) {
            syncingTabs = true;
            try {
                detailTabs.getSelectionModel().select(selectedTab);
            } finally {
                syncingTabs = false;
            }
        }
        renderBasic();
        renderRoster();
        renderSchedules();
        renderGrades();
    }

    private void renderBasic() {
        renderLines(basicBody, "teacher-course-detail-line",
                detail == null ? List.of() : basicLines(detail));
        renderError(basicErrorLabel, basicErrorText);
    }

    private void renderRoster() {
        if (rosterTable != null) rosterTable.getItems().setAll(roster);
        setActive(rosterLoadingLabel, loadingRoster);
        setActive(rosterEmptyLabel, !loadingRoster && rosterErrorText == null && roster.isEmpty());
        renderError(rosterErrorLabel, rosterErrorText);
        setActive(rosterRetryButton, rosterErrorText != null);
        if (rosterPageLabel != null) rosterPageLabel.setText(rosterPageText());
        if (previousRosterPageButton != null) {
            previousRosterPageButton.setDisable(!hasPreviousRosterPage());
        }
        if (nextRosterPageButton != null) {
            nextRosterPageButton.setDisable(!hasNextRosterPage());
        }
    }

    private void renderSchedules() {
        renderLines(scheduleBody, "teacher-course-schedule-line", arrangementLines(schedules));
        renderError(scheduleErrorLabel, scheduleErrorText);
    }

    private void renderGrades() {
        renderLines(gradeBody, "teacher-course-detail-line",
                detail == null ? List.of() : gradeLines(detail));
    }

    private void renderError(Label label, String text) {
        if (label != null) label.setText(text == null ? "" : text);
        setActive(label, text != null);
    }

    private void renderLines(VBox container, String styleClass, List<String> lines) {
        if (container == null) return;
        container.getChildren().clear();
        for (String line : lines) {
            Label label = new Label(line);
            label.getStyleClass().add(styleClass);
            label.setWrapText(true);
            container.getChildren().add(label);
        }
    }

    private void bindColumn(TableColumn<TeacherRosterRowDTO, String> column, int index) {
        if (column == null) return;
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(
                cell.getValue() == null ? "" : rosterCells(cell.getValue()).get(index)));
    }

    private static void setActive(Node node, boolean active) {
        if (node == null) return;
        node.setVisible(active);
        node.setManaged(active);
    }

    // ------------------------------------------------------------------ 纯文本

    /** 基本信息 Tab 的固定文本行，顺序稳定以便断言。 */
    static List<String> basicLines(TeacherOfferingDetailDTO detail) {
        TeacherOfferingDTO offering = detail.getOffering();
        List<String> lines = new ArrayList<>();
        lines.add("教学班：" + orDash(offering.getOfferingCode()) + "　"
                + orDash(offering.getCourseName()));
        lines.add("课程代码：" + orDash(offering.getCourseCode()) + "　学分："
                + creditText(offering.getCredit()));
        lines.add("开课学期：" + offering.getAcademicYear() + " 学年 第 "
                + offering.getSemester() + " 学期");
        lines.add("教学班人数：" + offering.getEnrolledCount() + " / " + offering.getCapacity());
        lines.add("教学班状态：" + TeacherOfferingController.offeringStatusText(
                offering.getStatus()));
        lines.add("开课学院：" + collegeText(detail.getOfferingCollege()));
        lines.add("任课教师：" + teacherNames(detail.getTeachers()));
        lines.add("课程简介：" + orDash(detail.getDescription()));
        lines.add("调课申请：" + (offering.isCanRequestAdjustment() ? "可申请" : "不可申请"));
        return List.copyOf(lines);
    }

    /**
     * 开课学院缺值时显示“未维护”。这个函数只拿到详情 DTO，结构上无法取用教师个人学院，
     * 因此不存在“用教师学院冒充开课学院”的路径。
     */
    static String collegeText(String offeringCollege) {
        return offeringCollege == null || offeringCollege.isBlank()
                ? UNMAINTAINED_COLLEGE : offeringCollege.trim();
    }

    /** 上课安排 Tab：一个排课方案一行，包含周次、全部时间段、教师、助教与教室。 */
    static List<String> arrangementLines(List<ScheduleArrangementDTO> arrangements) {
        if (arrangements == null || arrangements.isEmpty()) {
            return List.of("暂无正式排课安排");
        }
        List<String> lines = new ArrayList<>();
        for (ScheduleArrangementDTO arrangement : arrangements) {
            StringBuilder line = new StringBuilder();
            line.append("第 ").append(arrangement.getStartWeek()).append('-')
                    .append(arrangement.getEndWeek()).append(" 周");
            for (ScheduleSlotDTO slot : arrangement.getSlots()) {
                line.append("　").append(slotText(slot));
            }
            line.append("　").append(resourceName(arrangement.getTeacher()));
            if (arrangement.getAssistant() != null) {
                line.append("　助教 ").append(resourceName(arrangement.getAssistant()));
            }
            line.append("　").append(resourceName(arrangement.getClassroom()));
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }

    /**
     * 成绩情况 Tab 只显示只读状态：完整成绩表在成绩录入页打开，这里不编造人数、完整或缺失数量。
     */
    static List<String> gradeLines(TeacherOfferingDetailDTO detail) {
        TeacherOfferingDTO offering = detail.getOffering();
        return List.of(
                "成绩情况（只读）",
                "正常修读人数：" + offering.getEnrolledCount() + " 人",
                "成绩录入权限：" + (offering.isCanEditGrades() ? "可录入" : "只读"),
                "成绩编辑只包含当前正常修读的学生；退课记录只读保留，不参与成绩编辑。",
                "完整成绩表请从“成绩录入”进入（保存到草稿，提交后由管理员审核）。");
    }

    /** 名单一行，顺序与 FXML 的列顺序一一对应；未退课的退课时间显示为占位符。 */
    static List<String> rosterCells(TeacherRosterRowDTO row) {
        return List.of(
                orDash(row.getStudentUid()),
                orDash(row.getStudentName()),
                orDash(row.getMajor()),
                enrollmentStatusText(row.getEnrollmentStatus()),
                orDash(row.getSelectedAt()),
                orDash(row.getDroppedAt()));
    }

    static String enrollmentStatusText(String status) {
        if ("ENROLLED".equals(status)) return ENROLLED_LABEL;
        if ("DROPPED".equals(status)) return DROPPED_LABEL;
        return status == null ? "未知" : status;
    }

    static String weekdayText(int dayOfWeek) {
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? weekdays[dayOfWeek - 1] : "周" + dayOfWeek;
    }

    static String slotText(ScheduleSlotDTO slot) {
        return weekdayText(slot.getDayOfWeek()) + " 第 " + slot.getStartPeriod() + "-"
                + slot.getEndPeriod() + " 节";
    }

    private static String teacherNames(List<ScheduleResourceDTO> teachers) {
        if (teachers == null || teachers.isEmpty()) return "待定";
        List<String> names = new ArrayList<>();
        for (ScheduleResourceDTO teacher : teachers) {
            names.add(resourceName(teacher) + "（" + orDash(teacher.getBusinessId()) + "）");
        }
        return String.join("、", names);
    }

    private static String resourceName(ScheduleResourceDTO resource) {
        return resource == null || resource.getName() == null || resource.getName().isBlank()
                ? "待定" : resource.getName();
    }

    private static String creditText(double credit) {
        return String.format(java.util.Locale.ROOT, "%.1f", credit);
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // -------------------------------------------------------------- 测试访问器

    String offeringId() {
        return offeringId;
    }

    boolean active() {
        return active;
    }

    int selectedTab() {
        return selectedTab;
    }

    TeacherOfferingDetailDTO detail() {
        return detail;
    }

    List<TeacherRosterRowDTO> roster() {
        return roster;
    }

    List<ScheduleArrangementDTO> schedules() {
        return schedules;
    }

    int rosterPage() {
        return rosterPage;
    }

    long rosterTotalCount() {
        return rosterTotalCount;
    }

    String basicErrorText() {
        return basicErrorText;
    }

    String rosterErrorText() {
        return rosterErrorText;
    }

    String scheduleErrorText() {
        return scheduleErrorText;
    }

    boolean loadingRoster() {
        return loadingRoster;
    }

    boolean canEditGrades() {
        return detail != null && detail.getOffering() != null
                && detail.getOffering().isCanEditGrades();
    }

    boolean hasPreviousRosterPage() {
        return !loadingRoster && rosterPage > 1;
    }

    boolean hasNextRosterPage() {
        return !loadingRoster && rosterPage < rosterTotalPages();
    }

    private int rosterTotalPages() {
        return rosterTotalCount <= 0
                ? 1 : (int) ((rosterTotalCount + ROSTER_PAGE_SIZE - 1) / ROSTER_PAGE_SIZE);
    }

    private String rosterPageText() {
        if (rosterTotalCount <= 0) return "共 0 条";
        return "第 " + rosterPage + "/" + rosterTotalPages() + " 页　共 " + rosterTotalCount + " 条";
    }
}
