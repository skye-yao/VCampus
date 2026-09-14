package controller;

import java.util.Objects;
import app.ClientMain;
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Label;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 教师工作台外壳：左上“返回首页”，右上四入口（教学课程表、教学班、成绩录入、我的申请）。
 *
 * <p>子页托管沿用 {@link AdminCourseManagementController} 的机制：三个页面（工作台首页、教学班列表、
 * 教学班详情）都由 FXML 的 {@code fx:include} 一次性装入，外壳只切换它们的 {@code visible/managed}，
 * 并在页面被激活时调用子页自己的 {@code activate}/{@code showOffering} 加载数据。选它是因为仓库里
 * 所有壳页（学生端 {@code CourseManagementView}、管理员后台、审批控制台）都是同一套机制，子页控制器
 * 还能在无工具包的测试里直接装配。
 *
 * <p>导航语义：列表页保存的学期、搜索文本与页码在往返详情后保持不变（只重新加载当前页）；离开一个
 * 子页时它会被 {@code unload}/{@code release}，在途请求的响应随即失效，详情页也不保留上一个教学班的
 * 数据，因此不存在长期驻留的过期子页控制器。
 *
 * <p>分阶段边界：教学班已接入；教学课程表、成绩录入、我的申请仍属于后续阶段，入口保持禁用并把
 * {@link #STAGING_NOTICE} 显示在首页。详情页的“成绩录入”按钮只把教学班交回工作台的
 * {@link #openGrades(String)}，本阶段不打开任何成绩页、也不发起任何写请求。
 */
public final class TeacherCourseManagementController {
    /** 供未交付入口使用的阶段性提示文案。 */
    static final String STAGING_NOTICE = "该功能将在后续阶段接入";
    /** 首页默认文案：说明已接入与待接入的功能。 */
    static final String HOME_NOTICE = "教学班已接入；教学课程表、成绩录入、我的申请将在后续阶段接入。";
    /** “返回首页”的目标视图。 */
    static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    /** 当前显示的子页。 */
    static final String PAGE_HOME = "home";
    static final String PAGE_OFFERINGS = "offerings";
    static final String PAGE_DETAIL = "detail";

    private final TeacherCourseService service;
    private Runnable backAction = () -> ClientMain.switchScene(HOME_VIEW);
    private String currentPage = PAGE_HOME;
    private String noticeText = HOME_NOTICE;

    @FXML private Node homePanel;
    @FXML private Node offeringsPage;
    @FXML private TeacherOfferingController offeringsPageController;
    @FXML private Node detailPage;
    @FXML private TeacherOfferingDetailController detailPageController;
    @FXML private Label statusLabel;

    public TeacherCourseManagementController() {
        this(TeacherCourseServices.current());
    }

    TeacherCourseManagementController(TeacherCourseService service) {
        this.service = Objects.requireNonNull(service, "Teacher course service is required");
    }

    @FXML
    public void initialize() {
        wire(homePanel, offeringsPage, offeringsPageController, detailPage, detailPageController);
    }

    /**
     * 装配已加载的子页：把打开详情、返回列表与成绩入口接上，然后回到首页。
     *
     * <p>节点可以为 {@code null}（控制器测试不加载 FXML）；子页控制器为 {@code null} 时导航只切换
     * 当前页，不做任何加载。子页控制器由 {@code FXMLLoader} 用无参构造创建，与外壳一样取用
     * {@link TeacherCourseServices#current()} 这个共享实例，因此生产路径上只有一份教师课程服务。
     */
    void wire(Node homePanel, Node offeringsPage, TeacherOfferingController offeringsPageController,
            Node detailPage, TeacherOfferingDetailController detailPageController) {
        this.homePanel = homePanel;
        this.offeringsPage = offeringsPage;
        this.offeringsPageController = offeringsPageController;
        this.detailPage = detailPage;
        this.detailPageController = detailPageController;
        if (offeringsPageController != null) {
            offeringsPageController.setOnShowOffering(this::showOffering);
        }
        if (detailPageController != null) {
            detailPageController.setBackAction(this::backToOfferings);
            detailPageController.setOpenGrades(this::openGrades);
        }
        showHome();
    }

    @FXML
    void handleBack() {
        backAction.run();
    }

    void setBackAction(Runnable action) {
        this.backAction = action == null ? () -> { } : action;
    }

    /** 后续阶段装入的子页从这里取用共享的教师课程服务。 */
    TeacherCourseService service() {
        return service;
    }

    // 四个入口：教学班已接入，其余三个按分阶段计划仍是占位。

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

    /** 打开教学班列表；离开详情页时释放它，列表页保留自己的学期、搜索与页码。 */
    void openOfferings() {
        if (detailPageController != null) {
            detailPageController.release();
        }
        if (offeringsPageController != null) {
            offeringsPageController.activate();
        }
        currentPage = PAGE_OFFERINGS;
        render();
    }

    /** 打开某个教学班的详情；列表页被卸下，因此在途响应不会再写界面。 */
    void showOffering(String offeringId) {
        if (offeringId == null || offeringId.isBlank()) return;
        if (offeringsPageController != null) {
            offeringsPageController.unload();
        }
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

    void openTimetable() {
        showHome();
        showStagingNotice();
    }

    /**
     * 成绩入口：完整成绩表属于后续阶段，本阶段只回到首页并提示，不打开页面、不写库。
     */
    void openGrades(String offeringId) {
        showHome();
        showStagingNotice();
    }

    void openApplications() {
        showHome();
        showStagingNotice();
    }

    /** 回到工作台首页，同时卸下两个子页。 */
    void showHome() {
        if (offeringsPageController != null) {
            offeringsPageController.unload();
        }
        if (detailPageController != null) {
            detailPageController.release();
        }
        currentPage = PAGE_HOME;
        render();
    }

    private void showStagingNotice() {
        noticeText = STAGING_NOTICE;
        render();
    }

    private void render() {
        setPageState(homePanel, PAGE_HOME.equals(currentPage));
        setPageState(offeringsPage, PAGE_OFFERINGS.equals(currentPage));
        setPageState(detailPage, PAGE_DETAIL.equals(currentPage));
        if (statusLabel != null) {
            statusLabel.setText(noticeText);
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
