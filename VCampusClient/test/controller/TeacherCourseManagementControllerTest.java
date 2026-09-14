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
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.event.Event;
import service.MockTeacherCourseService;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 无 JavaFX 工具包依赖的教师工作台外壳测试。
 *
 * <p>不启动 toolkit：外壳契约靠注入的服务、子页控制器、回退动作与 {@code null} 节点断言；视图契约
 * 改为用 {@link DocumentBuilderFactory} 结构化解析 FXML，而不是匹配原始文本——原始文本断言会被注释
 * 或被注释掉的元素块骗过（既能造成假红，也能造成假绿），且只有结构化检查才能在真实元素上确认
 * {@code disable} 这类属性存在。静态检查仍无法证明任意属性名是可写属性，那只有真正的
 * {@code FXMLLoader} 加载能证明，属于 T6 的 {@code ui.*} 冒烟范围。
 *
 * <p>导航部分用真实的 {@link TeacherOfferingController}、{@link TeacherOfferingDetailController} 与
 * {@link TeacherScheduleController} 装配到外壳：工作台必须把列表页保存的学期/搜索/页码在往返详情后
 * 保持不变，在离开子页时释放它，并在“教学课程表”入口上激活课表子页而不改写首页文案。
 */
public final class TeacherCourseManagementControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String STAGING_NOTICE = "该功能将在后续阶段接入";
    private static final String HOME_NOTICE = "教学班、教学课程表、我的申请已接入；成绩录入将在后续阶段接入。";
    private static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    private static final String OFFERING = "9007199254740993";
    private static final String APP_STYLESHEET = "@../css/style.css";
    private static final String VIEW_STYLESHEET = "@../css/teacher-course.css";
    /** 仅剩成绩录入仍是分阶段占位；我的申请自 T5 起真正打开子页。 */
    private static final List<String> STAGED_ENTRIES = List.of("成绩录入");

    private TeacherCourseManagementControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        defaultConstructorUsesTheSharedTeacherService();
        injectedServiceIsRetainedForLaterSubpages();
        returningHomeTargetsTheMainView();
        returningHomeRunsTheInjectedActionSafely();
        entriesAreSafeWithoutNodes();

        openingTheOfferingsEntryActivatesTheListPage();
        openingADetailReleasesTheListAndReturnsToIt();
        openingTheTimetableEntryActivatesTheSchedulePage();
        openingTheApplicationsEntryActivatesTheApplicationsPage();
        stagedEntriesOnlyShowTheStagingNotice();

        viewIsTheTeacherWorkspaceShell(view);
        entriesAreWiredAndOnlyTheGradeEntryStaysStaged(view);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyFxIdAndOnActionResolvesOnTheController(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
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

    private static void stagedEntriesOnlyShowTheStagingNotice() {
        FakeService service = new FakeService();
        TeacherOfferingController offerings = offerings(service);
        TeacherOfferingDetailController detail = detail(service);
        TeacherScheduleController schedule = schedule(service);
        TeacherApplicationsController applications = applications(service);
        TeacherCourseManagementController controller = controller(service);
        controller.wire(null, null, offerings, null, detail, null, schedule, null, applications);
        controller.openOfferings();

        controller.openGrades(OFFERING);
        requireStaging(controller, "成绩录入");
        require(!offerings.active() && !detail.active() && !schedule.active()
                        && !applications.active(),
                "a staged entry must leave no hidden page active");
        require(service.detailCalls.isEmpty() && service.rosterCalls.isEmpty()
                        && service.scheduleCalls == 0 && service.applicationCalls == 0,
                "the staged grade route must not start any read or write request, saw details "
                        + service.detailCalls + " rosters " + service.rosterCalls + " schedules "
                        + service.scheduleCalls + " applications " + service.applicationCalls);
    }

    private static void requireStaging(TeacherCourseManagementController controller,
            String entry) {
        require(TeacherCourseManagementController.PAGE_HOME.equals(controller.currentPage()),
                entry + " must fall back to the workspace home, saw " + controller.currentPage());
        require(STAGING_NOTICE.equals(controller.noticeText()),
                entry + " must report the staging notice, saw " + controller.noticeText());
    }

    // ------------------------------------------------------------------ 视图契约

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

        Element back = buttonWithText(view, "返回首页");
        require(back != null, "the top-left entry must be a 返回首页 button");
        require("#handleBack".equals(back.getAttribute("onAction")),
                "返回首页 must be bound to #handleBack, saw " + back.getAttribute("onAction"));

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
     * 教学班、教学课程表与我的申请入口必须可用且接到各自的处理函数；只剩成绩录入仍是带
     * {@code disable="true"} 的占位。按 {@code text} 定位元素，因此注释或被注释掉的元素块都无法
     * 满足断言。
     */
    private static void entriesAreWiredAndOnlyTheGradeEntryStaysStaged(Document view) {
        Element offerings = buttonWithText(view, "教学班");
        require(offerings != null, "the top-right entries must include 教学班");
        require(!offerings.hasAttribute("disable") || "false".equals(
                        offerings.getAttribute("disable")),
                "教学班 must be enabled now that the page exists, saw disable=\""
                        + offerings.getAttribute("disable") + "\"");
        require("#handleOpenOfferings".equals(offerings.getAttribute("onAction")),
                "教学班 must be wired to #handleOpenOfferings, saw "
                        + offerings.getAttribute("onAction"));

        Element timetable = buttonWithText(view, "教学课程表");
        require(timetable != null, "the top-right entries must include 教学课程表");
        require(!timetable.hasAttribute("disable") || "false".equals(
                        timetable.getAttribute("disable")),
                "教学课程表 must be enabled now that the page exists, saw disable=\""
                        + timetable.getAttribute("disable") + "\"");
        require("#handleOpenTimetable".equals(timetable.getAttribute("onAction")),
                "教学课程表 must be wired to #handleOpenTimetable, saw "
                        + timetable.getAttribute("onAction"));

        Element applications = buttonWithText(view, "我的申请");
        require(applications != null, "the top-right entries must include 我的申请");
        require(!applications.hasAttribute("disable") || "false".equals(
                        applications.getAttribute("disable")),
                "我的申请 must be enabled now that the page exists, saw disable=\""
                        + applications.getAttribute("disable") + "\"");
        require("#handleOpenApplications".equals(applications.getAttribute("onAction")),
                "我的申请 must be wired to #handleOpenApplications, saw "
                        + applications.getAttribute("onAction"));

        for (String entry : STAGED_ENTRIES) {
            Element button = buttonWithText(view, entry);
            require(button != null, "the top-right entries must include " + entry);
            require("true".equals(button.getAttribute("disable")),
                    "entry " + entry + " must stay staged with disable=\"true\", saw \""
                            + button.getAttribute("disable") + "\"");
            require(!button.getAttribute("onAction").isEmpty(),
                    "entry " + entry + " must still be wired to its handler");
        }
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

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
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
        private int scheduleCalls;
        private int applicationCalls;

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
    }
}
