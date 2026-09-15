package controller;

import java.util.Objects;
import app.ClientMain;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import util.PageLeaveGuard;

/**
 * 教师工作台外壳：左上“返回首页”，右上四入口（教学课程表、教学班、成绩录入、我的申请）。
 *
 * <p>子页托管沿用 {@link AdminCourseManagementController} 的机制：五个页面（工作台首页、教学班列表、
 * 教学班详情、教学课程表、我的申请）都由 FXML 的 {@code fx:include} 一次性装入，外壳只切换它们的
 * {@code visible/managed}，并在页面被激活时调用子页自己的 {@code activate}/{@code showOffering}
 * 加载数据。选它是因为仓库里所有壳页（学生端 {@code CourseManagementView}、管理员后台、审批控制台）
 * 都是同一套机制，子页控制器还能在无工具包的测试里直接装配。
 *
 * <p>导航语义：列表页保存的学期、搜索文本与页码在往返详情后保持不变（只重新加载当前页）；离开一个
 * 子页时它会被 {@code unload}/{@code release}，在途请求的响应随即失效，详情页也不保留上一个教学班的
 * 数据，因此不存在长期驻留的过期子页控制器。
 *
 * <p>成绩录入（设计 §5.4）：右上入口打开成绩教学班列表，列表行或教学班详情的“成绩录入”打开某个
 * 教学班的成绩编辑表。编辑表有未保存内容时，本工作台的每个导航入口（返回首页、切换子页、切换
 * 教学班）都先问一次当前活动的 {@link PageLeaveGuard}，被拒绝就停在原页；离开成功后由子页自己的
 * {@code unload}/{@code release} 取消在途请求，因此不存在还在写界面的过期子页控制器。
 */
public final class TeacherCourseManagementController {
    /** 首页文案：四个入口全部已接入。 */
    static final String HOME_NOTICE = "教学班、教学课程表、成绩录入、我的申请已接入。";
    /** “返回首页”的目标视图。 */
    static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    /** 当前显示的子页。 */
    static final String PAGE_HOME = "home";
    static final String PAGE_OFFERINGS = "offerings";
    static final String PAGE_DETAIL = "detail";
    static final String PAGE_SCHEDULE = "schedule";
    static final String PAGE_APPLICATIONS = "applications";
    static final String PAGE_GRADES = "grades";
    static final String PAGE_GRADE_BOOK = "gradeBook";

    /**
     * 当前页入口的高亮样式类。右上入口沿用 {@code MainController#updateActiveNavButton} 的做法
     * （普通 {@code Button} + 一个激活样式类），而不是学生端的 {@code ToggleButton}:selected——
     * 入口必须继续是普通按钮，冒烟测试按 {@code Button} 类型查找它们。
     */
    static final String ENTRY_ACTIVE_CLASS = "teacher-course-entry-active";
    /** 四个入口的 fx:id：与 FXML 的 fx:id 及控制器字段名逐字一致，{@link #entryForPage} 也返回它们。 */
    static final String ENTRY_TIMETABLE = "timetableEntryButton";
    static final String ENTRY_OFFERINGS = "offeringsEntryButton";
    static final String ENTRY_GRADES = "gradesEntryButton";
    static final String ENTRY_APPLICATIONS = "applicationsEntryButton";

    private final TeacherCourseService service;
    private Runnable backAction = () -> ClientMain.switchScene(HOME_VIEW);
    private String currentPage = PAGE_HOME;
    private String noticeText = HOME_NOTICE;

    @FXML private Node homePanel;
    @FXML private Node offeringsPage;
    @FXML private TeacherOfferingController offeringsPageController;
    @FXML private Node detailPage;
    @FXML private TeacherOfferingDetailController detailPageController;
    @FXML private Node schedulePage;
    @FXML private TeacherScheduleController schedulePageController;
    @FXML private Node applicationsPage;
    @FXML private TeacherApplicationsController applicationsPageController;
    @FXML private Node gradesPage;
    @FXML private TeacherGradeController gradesPageController;
    @FXML private Node gradeBookPage;
    @FXML private TeacherGradeBookController gradeBookPageController;
    @FXML private Button timetableEntryButton;
    @FXML private Button offeringsEntryButton;
    @FXML private Button gradesEntryButton;
    @FXML private Button applicationsEntryButton;
    @FXML private Label statusLabel;

    public TeacherCourseManagementController() {
        this(TeacherCourseServices.current());
    }

    TeacherCourseManagementController(TeacherCourseService service) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
    }

    @FXML
    public void initialize() {
        wire(homePanel, offeringsPage, offeringsPageController, detailPage, detailPageController,
                schedulePage, schedulePageController, applicationsPage, applicationsPageController);
        wireGrades(gradesPage, gradesPageController, gradeBookPage, gradeBookPageController);
    }

    /**
     * 装配已加载的子页：把打开详情、返回列表、成绩入口与课表的“查看教学班”导航接上，然后回到首页。
     *
     * <p>节点可以为 {@code null}（控制器测试不加载 FXML）；子页控制器为 {@code null} 时导航只切换
     * 当前页，不做任何加载。子页控制器由 {@code FXMLLoader} 用无参构造创建，与外壳一样取用
     * {@link TeacherCourseServices#current()} 这个共享实例，因此生产路径上只有一份教师课程服务。
     */
    void wire(Node homePanel, Node offeringsPage, TeacherOfferingController offeringsPageController,
            Node detailPage, TeacherOfferingDetailController detailPageController,
            Node schedulePage, TeacherScheduleController schedulePageController,
            Node applicationsPage, TeacherApplicationsController applicationsPageController) {
        this.homePanel = homePanel;
        this.offeringsPage = offeringsPage;
        this.offeringsPageController = offeringsPageController;
        this.detailPage = detailPage;
        this.detailPageController = detailPageController;
        this.schedulePage = schedulePage;
        this.schedulePageController = schedulePageController;
        this.applicationsPage = applicationsPage;
        this.applicationsPageController = applicationsPageController;
        if (offeringsPageController != null) {
            offeringsPageController.setOnShowOffering(this::showOffering);
        }
        if (detailPageController != null) {
            detailPageController.setBackAction(this::backToOfferings);
            detailPageController.setOpenGrades(this::openGrades);
        }
        if (schedulePageController != null) {
            schedulePageController.setOpenOffering(this::showOffering);
        }
        showHome();
    }

    /**
     * 装配两个成绩子页：成绩列表把“录入成绩”交给工作台，成绩编辑表的“返回成绩列表”也回到列表页。
     * 与其它子页一样只接线、不加载；成绩页的守卫由页面自己在成为当前页时注册。
     */
    void wireGrades(Node gradesPage, TeacherGradeController gradesPageController,
            Node gradeBookPage, TeacherGradeBookController gradeBookPageController) {
        this.gradesPage = gradesPage;
        this.gradesPageController = gradesPageController;
        this.gradeBookPage = gradeBookPage;
        this.gradeBookPageController = gradeBookPageController;
        if (gradesPageController != null) {
            gradesPageController.setOnOpenGradeBook(this::openGradeBook);
        }
        if (gradeBookPageController != null) {
            gradeBookPageController.setOnBack(this::openGradeList);
        }
    }

    @FXML
    void handleBack() {
        if (!leaveCurrentPage()) return;
        releaseCurrentPage();
        backAction.run();
    }

    void setBackAction(Runnable action) {
        this.backAction = action == null ? () -> { } : action;
    }

    /** 后续阶段装入的子页从这里取用共享的教师课程服务。 */
    TeacherCourseService service() {
        return service;
    }

    // 四个入口自 T5 起全部接入：教学班、教学课程表、成绩录入（列表 + 编辑表）、我的申请。

    @FXML
    void handleOpenOfferings(Event event) {
        openOfferings();
    }

    @FXML
    void handleOpenTimetable(Event event) {
        openTimetable();
    }

    @FXML
    void handleOpenGrades(Event event) {
        openGrades(null);
    }

    @FXML
    void handleOpenApplications(Event event) {
        openApplications();
    }

    /** 打开教学班列表；离开详情页与课表页时卸下它们，列表页保留自己的学期、搜索与页码。 */
    void openOfferings() {
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        if (offeringsPageController != null) {
            offeringsPageController.activate();
        }
        currentPage = PAGE_OFFERINGS;
        render();
    }

    /**
     * 打开某个教学班的详情；列表页与课表页都被卸下，因此在途响应不会再写界面。
     * 课次详情弹窗的“查看教学班”也走这里。
     */
    void showOffering(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        if (detailPageController != null) {
            detailPageController.showOffering(offeringId);
        }
        currentPage = PAGE_DETAIL;
        render();
    }

    /** 从详情页返回列表页。 */
    void backToOfferings() {
        openOfferings();
    }

    /** 打开教学课程表：卸下其它子页并激活课表页，首页文案保持不变。 */
    void openTimetable() {
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        if (schedulePageController != null) {
            schedulePageController.activate();
        }
        currentPage = PAGE_SCHEDULE;
        render();
    }

    /**
     * 成绩入口：{@code offeringId} 为空时打开成绩教学班列表（右上入口），否则直接打开该班的成绩
     * 编辑表（教学班详情的“成绩录入”、成绩列表的行内按钮）。离开当前页前先问过离开守卫，
     * 有未保存成绩时保持原页不动。
     */
    void openGrades(String offeringId) {
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        if (offeringId == null || offeringId.isBlank()) {
            if (gradeBookPageController != null) gradeBookPageController.release();
            if (gradesPageController != null) gradesPageController.activate();
            currentPage = PAGE_GRADES;
        } else {
            if (gradesPageController != null) gradesPageController.unload();
            if (gradeBookPageController != null) gradeBookPageController.showOffering(offeringId);
            currentPage = PAGE_GRADE_BOOK;
        }
        render();
    }

    /** 成绩列表行打开一个教学班的成绩表；离开检查与 {@link #openGrades(String)} 同一条路径。 */
    void openGradeBook(String offeringId) {
        openGrades(offeringId);
    }

    /** 成绩编辑表的“返回成绩列表”。 */
    void openGradeList() {
        openGrades(null);
    }

    /** 打开我的申请：卸下其它子页并激活申请页，进入即重新查询（写操作后的状态才最新）。 */
    void openApplications() {
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        if (applicationsPageController != null) {
            applicationsPageController.activate();
        }
        currentPage = PAGE_APPLICATIONS;
        render();
    }

    /** 回到工作台首页，同时卸下全部子页。 */
    void showHome() {
        if (!leaveCurrentPage()) return;
        unloadAllSubPages();
        currentPage = PAGE_HOME;
        render();
    }

    /**
     * 离开当前页面前的统一检查：有页面注册了离开守卫且它拒绝时就停在这里。
     * 没有守卫（首页、只读页）时返回 true，与引入守卫之前的行为一致。
     */
    private static boolean leaveCurrentPage() {
        PageLeaveGuard guard = PageLeaveGuard.active();
        return guard == null || guard.requestLeave();
    }

    /** 卸下全部子页：在途响应随即失效，成绩编辑表的离开守卫也在 release 里注销。 */
    private void unloadAllSubPages() {
        if (offeringsPageController != null) offeringsPageController.unload();
        if (detailPageController != null) detailPageController.release();
        if (schedulePageController != null) schedulePageController.unload();
        if (applicationsPageController != null) applicationsPageController.unload();
        if (gradesPageController != null) gradesPageController.unload();
        if (gradeBookPageController != null) gradeBookPageController.release();
    }

    /** “返回首页”会整体替换场景，因此先让当前子页自己收尾（取消在途请求、注销守卫）。 */
    private void releaseCurrentPage() {
        if (PAGE_OFFERINGS.equals(currentPage)) {
            if (offeringsPageController != null) offeringsPageController.unload();
        } else if (PAGE_DETAIL.equals(currentPage)) {
            if (detailPageController != null) detailPageController.release();
        } else if (PAGE_SCHEDULE.equals(currentPage)) {
            if (schedulePageController != null) schedulePageController.unload();
        } else if (PAGE_APPLICATIONS.equals(currentPage)) {
            if (applicationsPageController != null) applicationsPageController.unload();
        } else if (PAGE_GRADES.equals(currentPage)) {
            if (gradesPageController != null) gradesPageController.unload();
        } else if (PAGE_GRADE_BOOK.equals(currentPage)) {
            if (gradeBookPageController != null) gradeBookPageController.release();
        }
    }

    private void render() {
        setPageState(homePanel, PAGE_HOME.equals(currentPage));
        setPageState(offeringsPage, PAGE_OFFERINGS.equals(currentPage));
        setPageState(detailPage, PAGE_DETAIL.equals(currentPage));
        setPageState(schedulePage, PAGE_SCHEDULE.equals(currentPage));
        setPageState(applicationsPage, PAGE_APPLICATIONS.equals(currentPage));
        setPageState(gradesPage, PAGE_GRADES.equals(currentPage));
        setPageState(gradeBookPage, PAGE_GRADE_BOOK.equals(currentPage));
        highlightCurrentEntry();
        if (statusLabel != null) {
            statusLabel.setText(noticeText);
        }
    }

    /**
     * 右上入口的高亮跟随当前页：只有 {@link #entryForPage} 指到的那个入口带
     * {@link #ENTRY_ACTIVE_CLASS}，其余三个一律去掉。
     *
     * <p>挂在 {@code render()} 里而不是各个点击处理里，因此程序化导航（详情页返回列表、成绩编辑表
     * 返回列表、课次详情的“查看教学班”）和点击一样会让高亮保持真实；首页没有对应入口，四个入口
     * 都不高亮。
     */
    private void highlightCurrentEntry() {
        String activeEntry = entryForPage(currentPage);
        setEntryActive(timetableEntryButton, ENTRY_TIMETABLE.equals(activeEntry));
        setEntryActive(offeringsEntryButton, ENTRY_OFFERINGS.equals(activeEntry));
        setEntryActive(gradesEntryButton, ENTRY_GRADES.equals(activeEntry));
        setEntryActive(applicationsEntryButton, ENTRY_APPLICATIONS.equals(activeEntry));
    }

    /**
     * 当前页该点亮哪个右上入口，返回它的 fx:id；首页（以及任何未列出的页）返回 {@code null}，
     * 表示没有入口处于激活态。教学班详情算“教学班”，成绩编辑表算“成绩录入”，这样从列表钻进
     * 详情/编辑表时入口不会突然熄灭。
     */
    static String entryForPage(String page) {
        if (PAGE_SCHEDULE.equals(page)) return ENTRY_TIMETABLE;
        if (PAGE_OFFERINGS.equals(page) || PAGE_DETAIL.equals(page)) return ENTRY_OFFERINGS;
        if (PAGE_GRADES.equals(page) || PAGE_GRADE_BOOK.equals(page)) return ENTRY_GRADES;
        if (PAGE_APPLICATIONS.equals(page)) return ENTRY_APPLICATIONS;
        return null;
    }

    /** 节点可以为 {@code null}（控制器测试不加载 FXML），加/去样式类都可以重复调用。 */
    private static void setEntryActive(Button entry, boolean active) {
        if (entry == null) return;
        entry.getStyleClass().remove(ENTRY_ACTIVE_CLASS);
        if (active) {
            entry.getStyleClass().add(ENTRY_ACTIVE_CLASS);
        }
    }

    private static void setPageState(Node page, boolean active) {
        if (page == null) return;
        page.setVisible(active);
        page.setManaged(active);
    }

    // -------------------------------------------------------------- 测试访问器

    String currentPage() {
        return currentPage;
    }

    String noticeText() {
        return noticeText;
    }
}
