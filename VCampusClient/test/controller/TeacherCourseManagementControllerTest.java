package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import dto.course.CourseTermDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.event.Event;
import javafx.scene.control.Button;
import service.MockTeacherCourseService;
import service.TeacherCourseService;
import service.TeacherCourseServices;
import util.PageLeaveGuard;

/**
 * 无 JavaFX 工具包依赖的教师工作台外壳测试。
 *
 * <p>不启动 toolkit：外壳契约靠注入的服务、子页控制器、回退动作与 {@code null} 节点断言；视图契约
 * 改为用 {@link DocumentBuilderFactory} 结构化解析 FXML，而不是匹配原始文本——原始文本断言会被注释
 * 或被注释掉的元素块骗过（既能造成假红，也能造成假绿），且只有结构化检查才能在真实元素上确认
 * {@code disable} 这类属性存在。静态检查仍无法证明任意属性名是可写属性，那只有真正的
 * {@code FXMLLoader} 加载能证明，属于 T6 的 {@code ui.*} 冒烟范围。
 *
 * <p>导航部分用真实的子页控制器装配到外壳：工作台必须把列表页保存的学期/搜索/页码在往返详情后
 * 保持不变，在离开子页时释放它，并在四个入口上分别激活对应子页。成绩编辑表自 T5 起是真实子页，
 * 因此“有未保存内容时导航要被离开守卫拦下”也在这里钉住。
 */
public final class TeacherCourseManagementControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String HOME_NOTICE = "教学班、教学课程表、成绩录入、我的申请已接入。";
    private static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    /** 顶栏标题：与另外两个教务外壳（学生/管理员）统一。 */
    private static final String TITLE = "教务管理系统";
    private static final String TITLE_CLASS = "teacher-course-title";
    private static final String OFFERING = "9007199254740993";
    private static final String APP_STYLESHEET = "@../css/style.css";
    private static final String VIEW_STYLESHEET = "@../css/teacher-course.css";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private TeacherCourseManagementControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        Document view = parseView();
        String css = readResource(CSS);

        defaultConstructorUsesTheSharedTeacherService();
        injectedServiceIsRetainedForLaterSubpages();
        returningHomeTargetsTheMainView();
        returningHomeRunsTheInjectedActionSafely();
        entriesAreSafeWithoutNodes();

        openingTheOfferingsEntryActivatesTheListPage();
        openingADetailReleasesTheListAndReturnsToIt();
        openingTheTimetableEntryActivatesTheSchedulePage();
        openingTheApplicationsEntryActivatesTheApplicationsPage();
        openingTheGradesEntryActivatesTheGradeList();
        openingAGradeBookActivatesTheEditor();
        refusedLeaveKeepsTheCurrentPage();

        theEntryHighlightFollowsEveryNavigationPath();
        theEntryHighlightMapsDetailsAndTheEditorToTheirList();
        everyEntryKeepsItsFxIdAndStaysAPlainButton(view);
        theActiveEntryHighlightFitsTheDarkTopbar(css);
        theSharedEmptyStateCentresItsText(css);

        viewIsTheTeacherWorkspaceShell(view);
        theShellHasNoBackButtonOfItsOwn(view);
        everyEntryIsWiredAndNoneStaysDisabled(view);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyFxIdAndOnActionResolvesOnTheController(view);
        everyStyleClassExistsInTheStylesheet(view, css);
        System.out.println("TeacherCourseManagementControllerTest: PASS");
    }

    private static void defaultConstructorUsesTheSharedTeacherService() {
        TeacherCourseService previous = TeacherCourseServices.current();
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseServices.install(mock);
        try {
            TeacherCourseManagementController controller = new TeacherCourseManagementController();
            require(controller.service() == mock,
                    "the default constructor must take the shared teacher service");
        } finally {
            TeacherCourseServices.install(previous);
        }
    }

    private static void injectedServiceIsRetainedForLaterSubpages() {
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseManagementController controller = new TeacherCourseManagementController(mock);
        require(controller.service() == mock,
                "the injected teacher service must be retained for later subpages");
    }

    private static void returningHomeTargetsTheMainView() {
        require(HOME_VIEW.equals(TeacherCourseManagementController.HOME_VIEW),
                "the workspace must return to the main view, saw "
                        + TeacherCourseManagementController.HOME_VIEW);
    }

    private static void returningHomeRunsTheInjectedActionSafely() {
        TeacherCourseManagementController controller = controller();
        List<Integer> backs = new ArrayList<>();
        controller.setBackAction(() -> backs.add(backs.size()));
        controller.handleBack();
        controller.handleBack();
        require(backs.size() == 2, "every 返回首页 must run the back action, saw " + backs);

        controller.setBackAction(null);
        controller.handleBack();
    }

    private static void entriesAreSafeWithoutNodes() {
        TeacherCourseManagementController controller = controller();
        controller.initialize();
        controller.openOfferings();
        controller.openTimetable();
        controller.openGrades(OFFERING);
        controller.openGrades(null);
        controller.openApplications();
        controller.backToOfferings();
    }

    private static void openingTheOfferingsEntryActivatesTheListPage() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherCourseManagementController controller = controller(service);
        controller.wire(null, null, offerings, null, detail, null, null, null, null);

        controller.openOfferings();

        require(TeacherCourseManagementController.PAGE_OFFERINGS.equals(controller.currentPage()),
                "the offerings entry must show the list page, saw " + controller.currentPage());
        require(offerings.active() && !detail.active(),
                "only the list page may be active after opening 教学班");
        require(offerings.term() != null && service.offeringCalls.size() == 1,
                "the list page must load its own terms and first page");
        require(controller.noticeText().equals(HOME_NOTICE),
                "opening a real page must not change the home notice");
    }

    private static void openingADetailReleasesTheListAndReturnsToIt() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherCourseManagementController controller = controller(service);
        controller.wire(null, null, offerings, null, detail, null, null, null, null);

        controller.openOfferings();
        offerings.applyFilters("CS");
        offerings.goToPage(2);
        controller.showOffering(OFFERING);

        require(TeacherCourseManagementController.PAGE_DETAIL.equals(controller.currentPage()),
                "opening a row must show the detail page, saw " + controller.currentPage());
        require(detail.active() && !offerings.active(),
                "the list page must be unloaded while the detail page is shown");
        require(service.detailCalls.equals(List.of(OFFERING)),
                "the detail page must load exactly the opened offering, saw "
                        + service.detailCalls);

        controller.backToOfferings();

        require(TeacherCourseManagementController.PAGE_OFFERINGS.equals(controller.currentPage()),
                "返回教学班列表 must show the list page again, saw " + controller.currentPage());
        require(offerings.active() && !detail.active(),
                "returning must unload the detail page");
        require(offerings.query().equals("CS") && offerings.page() == 2,
                "the selected search text and page must survive the round trip, saw query="
                        + offerings.query() + " page=" + offerings.page());
        require(detail.offeringId() == null && detail.detail() == null,
                "the detail page must not retain the released class data");
    }

    /** 教学课程表入口现在真的打开课表子页，并且不改变首页文案（不再显示 staging notice）。 */
    private static void openingTheTimetableEntryActivatesTheSchedulePage() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherScheduleController schedule = schedule(service);
        TeacherCourseManagementController controller = controller(service);
        controller.wire(null, null, offerings, null, detail, null, schedule, null, null);
        controller.openOfferings();

        controller.openTimetable();

        require(TeacherCourseManagementController.PAGE_SCHEDULE.equals(controller.currentPage()),
                "the timetable entry must show the schedule page, saw " + controller.currentPage());
        require(schedule.active() && !offerings.active() && !detail.active(),
                "only the schedule page may be active after opening 教学课程表");
        require(schedule.term() != null && service.scheduleCalls == 1,
                "the schedule page must load its own week, saw " + service.scheduleCalls + " calls");
        require(controller.noticeText().equals(HOME_NOTICE),
                "opening the timetable must not change the home notice, saw "
                        + controller.noticeText());
        require(service.detailCalls.isEmpty(),
                "opening the timetable must not load an offering, saw " + service.detailCalls);
    }

    /** 我的申请入口自 T5 起打开真实子页，并卸下其它子页。 */
    private static void openingTheApplicationsEntryActivatesTheApplicationsPage() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherScheduleController schedule = schedule(service);
        TeacherApplicationsController applications = applications(service);
        TeacherCourseManagementController controller = controller(service);
        controller.wire(null, null, offerings, null, detail, null, schedule, null, applications);
        controller.openOfferings();

        controller.openApplications();

        require(TeacherCourseManagementController.PAGE_APPLICATIONS
                        .equals(controller.currentPage()),
                "the applications entry must show the applications page, saw "
                        + controller.currentPage());
        require(applications.active() && !offerings.active() && !detail.active()
                        && !schedule.active(),
                "only the applications page may be active after opening 我的申请");
        require(service.applicationCalls == 1,
                "the applications page must load its own first page, saw "
                        + service.applicationCalls + " calls");
        require(controller.noticeText().equals(HOME_NOTICE),
                "opening the applications page must not change the home notice, saw "
                        + controller.noticeText());
    }

    /** 成绩录入入口（不指定教学班）打开成绩教学班列表，并卸下其它子页。 */
    private static void openingTheGradesEntryActivatesTheGradeList() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherGradeController grades = grades(service);
        TeacherGradeBookController gradeBook = gradeBook(service);
        TeacherCourseManagementController controller = controller(service);
        wireAll(controller, service, offerings, null, null, null, grades, gradeBook);
        controller.openOfferings();

        controller.openGrades(null);

        require(TeacherCourseManagementController.PAGE_GRADES.equals(controller.currentPage()),
                "the grade entry must show the grade list, saw " + controller.currentPage());
        require(grades.active() && !offerings.active() && !gradeBook.active(),
                "only the grade list may be active after opening 成绩录入");
        require(service.gradeListCalls == 1,
                "the grade list must load its own page, saw " + service.gradeListCalls + " calls");
        require(controller.noticeText().equals(HOME_NOTICE),
                "opening the grade list must not change the home notice, saw "
                        + controller.noticeText());
    }

    /** 教学班详情的“成绩录入”与列表行都直接打开某个班的成绩编辑表。 */
    private static void openingAGradeBookActivatesTheEditor() {
        FakeService service = new FakeService();
        TeacherGradeController grades = grades(service);
        TeacherGradeBookController gradeBook = gradeBook(service);
        TeacherCourseManagementController controller = controller(service);
        wireAll(controller, service, null, null, null, null, grades, gradeBook);

        controller.openGrades(OFFERING);

        require(TeacherCourseManagementController.PAGE_GRADE_BOOK
                        .equals(controller.currentPage()),
                "a row or detail entry must show the grade editor, saw "
                        + controller.currentPage());
        require(gradeBook.active() && !grades.active(),
                "only the grade editor may be active after opening one offering");
        require(service.bookCalls.equals(List.of(OFFERING)),
                "the editor must load exactly the opened offering, saw " + service.bookCalls);
        PageLeaveGuard.clear();
    }

    /** 成绩编辑表有未保存内容且用户取消离开时，工作台的每个导航入口都必须停在原页。 */
    private static void refusedLeaveKeepsTheCurrentPage() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherGradeBookController gradeBook = new TeacherGradeBookController(service,
                Runnable::run, message -> false);
        TeacherCourseManagementController controller = controller(service);
        wireAll(controller, service, offerings, null, null, null, null, gradeBook);
        try {
            controller.openGrades(OFFERING);
            gradeBook.model().setScore(gradeBook.rows().get(0).enrollmentId(),
                    dto.course.teacher.GradeComponentCodeDTO.DAILY, "91");
            require(PageLeaveGuard.active() == gradeBook,
                    "the editor must be the active leave guard while it is on screen");

            controller.openOfferings();
            require(TeacherCourseManagementController.PAGE_GRADE_BOOK
                            .equals(controller.currentPage()),
                    "a refused leave must keep the grade editor on screen, saw "
                            + controller.currentPage());
            require(gradeBook.active() && !offerings.active(),
                    "a refused leave must not activate the list page");

            controller.handleBack();
            require(TeacherCourseManagementController.PAGE_GRADE_BOOK
                            .equals(controller.currentPage()),
                    "返回首页 must be blocked by the same guard, saw "
                            + controller.currentPage());
        } finally {
            PageLeaveGuard.clear();
        }
    }

    // ------------------------------------------------------------------ 视图契约

    /**
     * 右上入口高亮的导航契约：每条真实导航路径走完后，{@code currentPage} 必须落在那个入口自己身上，
     * 首页则一个都不点亮。高亮是 {@code render()} 的产物，所以这里钉住的正是“点下去之后高亮跟得上”。
     */
    private static void theEntryHighlightFollowsEveryNavigationPath() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherScheduleController schedule = schedule(service);
        TeacherApplicationsController applications = applications(service);
        TeacherGradeController grades = grades(service);
        TeacherGradeBookController gradeBook = gradeBook(service);
        TeacherCourseManagementController controller = controller(service);
        wireAll(controller, service, offerings, detail, schedule, applications, grades, gradeBook);
        try {
            controller.openOfferings();
            requireHighlight(controller, TeacherCourseManagementController.ENTRY_OFFERINGS, "教学班");
            controller.openTimetable();
            requireHighlight(controller, TeacherCourseManagementController.ENTRY_TIMETABLE,
                    "教学课程表");
            controller.openApplications();
            requireHighlight(controller, TeacherCourseManagementController.ENTRY_APPLICATIONS,
                    "我的申请");
            controller.openGrades(null);
            requireHighlight(controller, TeacherCourseManagementController.ENTRY_GRADES, "成绩录入");
            controller.showOffering(OFFERING);
            requireHighlight(controller, TeacherCourseManagementController.ENTRY_OFFERINGS,
                    "教学班详情");
            controller.showHome();
            requireHighlight(controller, null, "工作台首页");
        } finally {
            PageLeaveGuard.clear();
        }
    }

    /** 页→入口映射本身：详情页算教学班、成绩编辑表算成绩录入，首页与未知页没有入口。 */
    private static void theEntryHighlightMapsDetailsAndTheEditorToTheirList() {
        requireHighlightPage(TeacherCourseManagementController.PAGE_SCHEDULE,
                TeacherCourseManagementController.ENTRY_TIMETABLE);
        requireHighlightPage(TeacherCourseManagementController.PAGE_OFFERINGS,
                TeacherCourseManagementController.ENTRY_OFFERINGS);
        requireHighlightPage(TeacherCourseManagementController.PAGE_DETAIL,
                TeacherCourseManagementController.ENTRY_OFFERINGS);
        requireHighlightPage(TeacherCourseManagementController.PAGE_GRADES,
                TeacherCourseManagementController.ENTRY_GRADES);
        requireHighlightPage(TeacherCourseManagementController.PAGE_GRADE_BOOK,
                TeacherCourseManagementController.ENTRY_GRADES);
        requireHighlightPage(TeacherCourseManagementController.PAGE_APPLICATIONS,
                TeacherCourseManagementController.ENTRY_APPLICATIONS);
        requireHighlightPage(TeacherCourseManagementController.PAGE_HOME, null);
    }

    private static void requireHighlightPage(String page, String expectedEntry) {
        String active = TeacherCourseManagementController.entryForPage(page);
        require(Objects.equals(expectedEntry, active),
                "page " + page + " must highlight " + expectedEntry + ", saw " + active);
    }

    private static void requireHighlight(TeacherCourseManagementController controller,
            String expectedEntry, String context) {
        String active = TeacherCourseManagementController.entryForPage(controller.currentPage());
        require(Objects.equals(expectedEntry, active),
                context + " must highlight " + expectedEntry + ", saw " + active + " for page "
                        + controller.currentPage());
    }

    /**
     * 四个入口的 fx:id 必须与 FXML、控制器字段名三者一致，条目还得是普通 {@code Button}：
     * 高亮走 {@code MainController} 的“普通按钮 + 激活样式类”约定，换成 {@code ToggleButton}
     * 会让冒烟测试的 {@code node instanceof Button} 查找落空。
     */
    private static void everyEntryKeepsItsFxIdAndStaysAPlainButton(Document view) {
        requireEntryElement(view, TeacherCourseManagementController.ENTRY_TIMETABLE, "教学课程表",
                "#handleOpenTimetable");
        requireEntryElement(view, TeacherCourseManagementController.ENTRY_OFFERINGS, "教学班",
                "#handleOpenOfferings");
        requireEntryElement(view, TeacherCourseManagementController.ENTRY_GRADES, "成绩录入",
                "#handleOpenGrades");
        requireEntryElement(view, TeacherCourseManagementController.ENTRY_APPLICATIONS, "我的申请",
                "#handleOpenApplications");
    }

    private static void requireEntryElement(Document view, String id, String text, String handler) {
        Element button = elementWithId(view, id);
        require(button != null, "the " + text + " entry must carry fx:id=\"" + id + "\"");
        require("Button".equals(button.getTagName()),
                "the " + text + " entry must stay a plain Button element, saw " + button.getTagName());
        require(text.equals(button.getAttribute("text")),
                "fx:id=\"" + id + "\" must be the " + text + " entry, saw " + button.getAttribute("text"));
        require(handler.equals(button.getAttribute("onAction")),
                "the " + text + " entry must stay wired to " + handler);
        require(hasStyleClass(button, "teacher-course-entry"),
                "the " + text + " entry must keep .teacher-course-entry: the smoke test looks it up, saw "
                        + button.getAttribute("styleClass"));

        Field field = findField(TeacherCourseManagementController.class, id);
        require(field != null,
                "the controller must expose a field named " + id + " for the " + text + " entry");
        require(field.getType() == Button.class,
                "the " + text + " entry must stay a plain javafx.scene.control.Button, saw "
                        + field.getType().getName());
    }

    /**
     * 高亮的 CSS 契约：顶栏是 #587558 深绿，激活态必须用管理端的深绿底 + 黄下划线（对比度 ≥ 4.5:1），
     * 并且沿用基类的 3px 下边框宽度，否则高亮会让按钮换行抖动。
     */
    private static void theActiveEntryHighlightFitsTheDarkTopbar(String css) {
        String selector = "." + TeacherCourseManagementController.ENTRY_ACTIVE_CLASS;
        require(css.contains(selector),
                "teacher-course.css must define " + selector + " for the active entry");

        String background = cssDeclaration(css, selector, "-fx-background-color");
        String textFill = cssDeclaration(css, selector, "-fx-text-fill");
        String borderColor = cssDeclaration(css, selector, "-fx-border-color");
        require(background != null && textFill != null && borderColor != null,
                selector + " must set background, text fill and border colour, saw "
                        + background + " / " + textFill + " / " + borderColor);
        require("#3f553f".equals(background),
                selector + " must use the admin dark green #3f553f (the top bar is dark green), saw "
                        + background);
        require(borderColor.contains("#fdd000"),
                selector + " must underline the active entry in yellow like the admin shell, saw "
                        + borderColor);
        double contrast = contrastRatio(textFill, background);
        require(contrast >= 4.5,
                selector + " must keep the entry label at or above WCAG AA 4.5:1, saw "
                        + textFill + " on " + background + " = " + contrast);

        String baseBorderWidth = cssDeclaration(css, ".teacher-course-entry", "-fx-border-width");
        require("0 0 3px 0".equals(baseBorderWidth),
                "the base entry keeps the 3px bottom border so highlighting never shifts layout, saw "
                        + baseBorderWidth);
    }

    /**
     * 空状态文案居中：{@code .teacher-course-empty-state} 的标签常带 maxWidth，没有
     * {@code -fx-alignment: center} 时文字会贴在左边缘（学生端/管理端的同类样式都带这一条）。
     */
    private static void theSharedEmptyStateCentresItsText(String css) {
        String alignment = cssDeclaration(css, ".teacher-course-empty-state", "-fx-alignment");
        require("center".equals(alignment),
                "the shared teacher empty state must centre its text like the student and admin ones, saw "
                        + alignment);
    }

    /**
     * 顶栏不再自带“返回首页”按钮：外壳由主窗口侧边栏的“选课”入口装进 center，侧边栏在
     * {@code MainController#loadCenterView} 之后仍然可见，主页按钮就在那里。因此外壳里不该再有任何
     * 元素把 {@code #handleBack} 接到界面上——控制器方法本身保留（导航与离开守卫仍要用它），
     * 只是没有按钮指向它。按 {@code onAction} 断言，注释或被注释掉的元素块都骗不过。
     */
    private static void theShellHasNoBackButtonOfItsOwn(Document view) {
        for (Element element : elementsWithTag(view, "*")) {
            require(!"#handleBack".equals(element.getAttribute("onAction")),
                    "<" + element.getTagName()
                            + "> must not bind #handleBack: the sidebar is the way home");
        }
        require(buttonWithText(view, "返回首页") == null,
                "the removed 返回首页 button must not come back under another handler");
        require(buttonWithText(view, "返回主菜单") == null,
                "the teacher shell must not adopt the student shell's 返回主菜单 label");
    }

    private static void viewIsTheTeacherWorkspaceShell(Document view) {
        Element root = view.getDocumentElement();
        require("BorderPane".equals(root.getTagName()),
                "the workspace root must be a BorderPane, saw " + root.getTagName());
        require("controller.TeacherCourseManagementController"
                        .equals(root.getAttribute("fx:controller")),
                "the view must be controlled by TeacherCourseManagementController, saw "
                        + root.getAttribute("fx:controller"));
        String stylesheets = root.getAttribute("stylesheets");
        require(stylesheets.contains(APP_STYLESHEET),
                "the view must link the app-wide style.css like every other view, saw "
                        + stylesheets);
        require(stylesheets.contains(VIEW_STYLESHEET),
                "the view must link teacher-course.css, saw " + stylesheets);
        require("860.0".equals(root.getAttribute("prefWidth"))
                        && "580.0".equals(root.getAttribute("prefHeight")),
                "the workspace must reuse the 860x580 main window, saw "
                        + root.getAttribute("prefWidth") + "x" + root.getAttribute("prefHeight"));

        Element title = labelWithStyleClass(view, TITLE_CLASS);
        require(title != null,
                "the top bar must lead with a ." + TITLE_CLASS + " title label");
        require(TITLE.equals(title.getAttribute("text")),
                "the shell title must read " + TITLE + " like the other course shells, saw "
                        + title.getAttribute("text"));

        Element statusLabel = elementWithId(view, "statusLabel");
        require(statusLabel != null,
                "the view must expose the status label the staged entries report into");
        require(HOME_NOTICE.equals(statusLabel.getAttribute("text")),
                "the status label must state what is wired and what is staged, saw "
                        + statusLabel.getAttribute("text"));

        for (Element include : elementsWithTag(view, "fx:include")) {
            require(!include.getAttribute("source").isEmpty(),
                    "every fx:include must name its source view");
        }
        require(!elementsWithTag(view, "fx:include").isEmpty(),
                "the workspace must host its sub-pages with fx:include, like the admin shell");
    }

    /**
     * 四个入口（教学班、教学课程表、成绩录入、我的申请）全部可用并接到各自的处理函数：
     * T5 接通成绩录入之后工作台不再有分阶段占位。按 {@code text} 定位元素，因此注释或被注释掉的
     * 元素块都无法满足断言。
     */
    private static void everyEntryIsWiredAndNoneStaysDisabled(Document view) {
        requireEntry(view, "教学班", "#handleOpenOfferings");
        requireEntry(view, "教学课程表", "#handleOpenTimetable");
        requireEntry(view, "成绩录入", "#handleOpenGrades");
        requireEntry(view, "我的申请", "#handleOpenApplications");
    }

    private static void requireEntry(Document view, String text, String handler) {
        Element button = buttonWithText(view, text);
        require(button != null, "the top-right entries must include " + text);
        require(!button.hasAttribute("disable") || "false".equals(
                        button.getAttribute("disable")),
                text + " must be enabled now that its page exists, saw disable=\""
                        + button.getAttribute("disable") + "\"");
        require(handler.equals(button.getAttribute("onAction")),
                text + " must be wired to " + handler + ", saw " + button.getAttribute("onAction"));
    }

    /**
     * 只读属性陷阱：{@code disabled} 在 {@code Node} 上只读，写进 FXML 要到加载期才抛
     * {@code PropertyNotFoundException}，所以这里逐元素确认它没有出现在文档的任何地方。
     */
    private static void noElementUsesTheReadOnlyDisabledAttribute(Document view) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            require(!element.hasAttribute("disabled"),
                    "<" + element.getTagName()
                            + "> must not use the read-only disabled attribute; use disable");
        }
    }

    private static void everyFxIdAndOnActionResolvesOnTheController(Document view) {
        Class<?> controller = TeacherCourseManagementController.class;
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);

            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(controller, id) != null,
                        "<" + element.getTagName() + "> fx:id=\"" + id
                                + "\" has no field on " + controller.getSimpleName());
            }

            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasActionMethod(controller, action),
                        "<" + element.getTagName() + "> onAction=\"#" + action
                                + "\" has no handler on " + controller.getSimpleName());
            }
        }
        require(ids > 0 && actions > 0,
                "the view must actually declare fx:id and onAction bindings, saw "
                        + ids + " ids and " + actions + " actions");
    }

    private static void everyStyleClassExistsInTheStylesheet(Document view, String css) {
        Set<String> styleClasses = new LinkedHashSet<>();
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            String value = ((Element) elements.item(index)).getAttribute("styleClass");
            if (value.isEmpty()) continue;
            for (String token : value.split(",")) {
                String name = token.trim();
                if (!name.isEmpty()) styleClasses.add(name);
            }
        }
        require(!styleClasses.isEmpty(), "the view must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherCourseManagementController controller() {
        return new TeacherCourseManagementController(new FakeService());
    }

    private static TeacherCourseManagementController controller(TeacherCourseService service) {
        return new TeacherCourseManagementController(service);
    }

    private static TeacherOfferingController offerings(TeacherCourseService service) {
        return new TeacherOfferingController(service, Runnable::run);
    }

    private static TeacherOfferingDetailController detail(TeacherCourseService service) {
        return new TeacherOfferingDetailController(service, Runnable::run);
    }

    private static TeacherScheduleController schedule(TeacherCourseService service) {
        return new TeacherScheduleController(service, Runnable::run);
    }

    private static TeacherApplicationsController applications(TeacherCourseService service) {
        return new TeacherApplicationsController(service, Runnable::run);
    }

    private static TeacherGradeController grades(TeacherCourseService service) {
        return new TeacherGradeController(service, Runnable::run);
    }

    private static TeacherGradeBookController gradeBook(TeacherCourseService service) {
        return new TeacherGradeBookController(service, Runnable::run, message -> true);
    }

    /** 用给定的子页装配外壳；成绩子页与其它子页一样只接线、不加载。 */
    private static void wireAll(TeacherCourseManagementController controller,
            TeacherCourseService service, TeacherOfferingController offerings,
            TeacherOfferingDetailController detail, TeacherScheduleController schedule,
            TeacherApplicationsController applications, TeacherGradeController grades,
            TeacherGradeBookController gradeBook) {
        controller.wire(null, null, offerings, null, detail, null, schedule, null, applications);
        controller.wireGrades(null, grades, null, gradeBook);
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherCourseManagementControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherCourseManagementControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Element> elementsWithTag(Document view, String tag) {
        List<Element> found = new ArrayList<>();
        NodeList elements = view.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            found.add((Element) elements.item(index));
        }
        return List.copyOf(found);
    }

    private static Element buttonWithText(Document view, String text) {
        NodeList buttons = view.getElementsByTagName("Button");
        for (int index = 0; index < buttons.getLength(); index++) {
            Element button = (Element) buttons.item(index);
            if (text.equals(button.getAttribute("text"))) return button;
        }
        return null;
    }

    /** 按 styleClass 定位标签；styleClass 可以是逗号分隔的多值，因此逐 token 比对。 */
    private static Element labelWithStyleClass(Document view, String styleClass) {
        for (Element label : elementsWithTag(view, "Label")) {
            for (String token : label.getAttribute("styleClass").split(",")) {
                if (styleClass.equals(token.trim())) return label;
            }
        }
        return null;
    }

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    /** styleClass 可以是逗号分隔的多值，因此逐 token 比对。 */
    private static boolean hasStyleClass(Element element, String styleClass) {
        for (String token : element.getAttribute("styleClass").split(",")) {
            if (styleClass.equals(token.trim())) return true;
        }
        return false;
    }

    /**
     * 取 {@code selector} 块里的某条声明值；选择器写作 {@code .x {…}}（逐字匹配，因此
     * {@code .teacher-course-entry} 不会命中 {@code .teacher-course-entry-active}）。找不到返回 null。
     */
    private static String cssDeclaration(String css, String selector, String property) {
        int start = css.indexOf(selector + " {");
        if (start < 0) return null;
        int end = css.indexOf('}', start);
        if (end < 0) return null;
        for (String line : css.substring(start, end).split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith(property + ":")) continue;
            return trimmed.substring(property.length() + 1).replace(";", "").trim();
        }
        return null;
    }

    /** WCAG 2.1 对比度：用真正的相对亮度公式算，而不是看一眼颜色“觉得很清楚”。 */
    private static double contrastRatio(String foreground, String background) {
        double first = relativeLuminance(foreground);
        double second = relativeLuminance(background);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    private static double relativeLuminance(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        int red = Integer.parseInt(value.substring(0, 2), 16);
        int green = Integer.parseInt(value.substring(2, 4), 16);
        int blue = Integer.parseInt(value.substring(4, 6), 16);
        return 0.2126 * linearChannel(red) + 0.7152 * linearChannel(green)
                + 0.0722 * linearChannel(blue);
    }

    private static double linearChannel(int value) {
        double normalized = value / 255.0;
        return normalized <= 0.03928 ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
        }
        return null;
    }

    /** {@code FXMLLoader} accepts both a no-arg handler and a single {@link Event} handler. */
    private static boolean hasActionMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return true;
                if (method.getParameterCount() == 1
                        && Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 工作台导航测试需要的最小教师课程服务：记录调用并返回确定性的分页数据。 */
    private static final class FakeService implements TeacherCourseService {
        private final Deque<CompletableFuture<TeacherOfferingDetailDTO>> detailPages =
                new ArrayDeque<>();
        private final List<String> offeringCalls = new ArrayList<>();
        private final List<String> detailCalls = new ArrayList<>();
        private final List<String> rosterCalls = new ArrayList<>();
        private final List<String> bookCalls = new ArrayList<>();
        private int scheduleCalls;
        private int applicationCalls;
        private int gradeListCalls;

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            return CompletableFuture.completedFuture(
                    List.of(new CourseTermDTO(2025, 3, "2025-2026 春学期")));
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            offeringCalls.add(academicYear + "|" + semester + "|" + (query == null ? "" : query)
                    + "|" + page + "|" + size);
            return CompletableFuture.completedFuture(new TeacherPageDTO<>(
                    List.of(new TeacherOfferingDTO(OFFERING, "CS203-01", "数据结构 CS203-01",
                            "2001", "CS203", "数据结构与算法基础", 4.0, academicYear, semester,
                            27, 30, "OPEN", true, true)), 27, page, size));
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            detailCalls.add(offeringId);
            if (!detailPages.isEmpty()) return detailPages.removeFirst();
            TeacherOfferingDTO offering = new TeacherOfferingDTO(offeringId, "CS203-01",
                    "数据结构 CS203-01", "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3,
                    27, 30, "OPEN", true, true);
            return CompletableFuture.completedFuture(new TeacherOfferingDetailDTO(offering,
                    List.of(new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0)),
                    "计算机科学与工程学院", "课程简介"));
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            rosterCalls.add(offeringId + "|" + (query == null ? "" : query) + "|"
                    + (enrollmentStatus == null ? "" : enrollmentStatus) + "|" + page + "|"
                    + size);
            return CompletableFuture.completedFuture(new TeacherPageDTO<>(
                    List.of(), 0, page, size));
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            return CompletableFuture.completedFuture(List.of(new ScheduleArrangementDTO(
                    "9503", "7001", offeringId,
                    new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                    new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                    List.of(new ScheduleSlotDTO(1, 1, 2)), 1, 16, "ACTIVE", 1)));
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            scheduleCalls++;
            return CompletableFuture.completedFuture(new TeacherScheduleWeekDTO(
                    "9007199254740991", "Asia/Shanghai", week == null ? 1 : week, 1, 16, 1,
                    List.of(), List.of(), List.of()));
        }

        @Override
        public CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>>
                listMyAdjustmentRequests(AdjustmentRequestStatusDTO status, int page, int size) {
            applicationCalls++;
            return CompletableFuture.completedFuture(
                    new TeacherPageDTO<>(List.of(), 0, page, size));
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
                int academicYear, int semester, int page, int size) {
            gradeListCalls++;
            return CompletableFuture.completedFuture(new TeacherPageDTO<>(
                    List.of(new TeacherGradeOfferingDTO(new TeacherOfferingDTO(OFFERING, "CS203-01",
                            "数据结构 CS203-01", "2001", "CS203", "数据结构与算法基础", 4.0,
                            academicYear, semester, 24, 30, "OPEN", true, true),
                            "DRAFT", 20, 4, null)), 1L, page, size));
        }

        @Override
        public CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
            bookCalls.add(offeringId);
            return CompletableFuture.completedFuture(new TeacherGradeBookDTO(offeringId, 3, DIGEST,
                    "DRAFT", scheme(), List.of(new TeacherGradeRowDTO("50031", "00005678", "张三",
                            new GradeScoresDTO(null, null, null, null), null, null, false,
                            List.of())),
                    null, null, true, null, false));
        }

        private static GradeSchemeDTO scheme() {
            return new GradeSchemeDTO(List.of(
                    new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                    new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                    new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, 2000),
                    new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 3000)));
        }
    }
}
