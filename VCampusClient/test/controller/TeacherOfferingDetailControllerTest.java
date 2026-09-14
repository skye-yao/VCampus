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
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.event.ActionEvent;
import javafx.event.Event;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的教学班详情页测试（基本信息 / 学生名单 / 上课安排 / 成绩情况 四个 Tab）。
 *
 * <p>注入假服务、{@code Runnable::run} 的 FX 执行器与 {@code null} 的 FXML 节点。所有用户可见的
 * 文本都由纯函数给出，因此可以在没有运行时的环境里断言：开课学院缺值必须是“未维护”，退课行必须
 * 带退课时间，多个时间段必须全部渲染，登记成绩的入口必须由能力字段控制且不写库。
 */
public final class TeacherOfferingDetailControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherOfferingDetailView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String OFFERING = "9007199254740993";
    private static final String OTHER_OFFERING = "9007199254740999";

    private TeacherOfferingDetailControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        basicInfoShowsTheOfferingAndTheUnmaintainedCollege();
        hiddenTabsDoNotRequestAndLoadedTabsAreCached();
        rosterFilterByNameAndUidReachesTheService();
        droppedRowsRenderTheDropStateAndTime();
        rosterPageWalksPastTheFirstPage();
        anEmptyClassHasAnEmptyRoster();
        everyScheduleSlotIsRendered();
        theGradeEntryIsCapabilityGatedAndReadOnly();
        staleDetailResponseCannotReplaceTheNewerOffering();
        aFailedSectionKeepsItsDataAndOffersARetry();
        releasedPageDropsItsDataAndIgnoresLateResponses();
        theSharedServiceContractExposesNoWritePath();
        viewIsTheOfferingDetailPage(parseView());
        System.out.println("TeacherOfferingDetailControllerTest: PASS");
    }

    private static void basicInfoShowsTheOfferingAndTheUnmaintainedCollege() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        service.detailPages.add(completed(detailWithoutCollege(OFFERING)));
        controller.showOffering(OFFERING);

        require(service.detailCalls.equals(List.of(OFFERING)),
                "showing an offering must load its detail, saw " + service.detailCalls);
        require(controller.offeringId().equals(OFFERING) && controller.detail() != null,
                "the loaded detail must be retained for the active offering");

        List<String> lines = TeacherOfferingDetailController.basicLines(controller.detail());
        require(lines.contains("开课学院：未维护"),
                "a missing offering college must read 未维护, saw " + lines);
        require(lines.contains("教学班状态：开放中"),
                "the basic tab must show the class status, saw " + lines);
        require(lines.contains("调课申请：可申请"),
                "the basic tab must expose the adjustment capability, saw " + lines);
        require(lines.stream().noneMatch(line -> line.contains("计算机科学与工程学院")),
                "the teacher's own college must never stand in for a missing offering college, saw "
                        + lines);

        require(TeacherOfferingDetailController.collegeText(null).equals("未维护")
                        && TeacherOfferingDetailController.collegeText("  ").equals("未维护")
                        && TeacherOfferingDetailController.collegeText("外国语学院")
                                .equals("外国语学院"),
                "collegeText must render a blank college as 未维护 and keep a real one");
    }

    private static void hiddenTabsDoNotRequestAndLoadedTabsAreCached() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        controller.showOffering(OFFERING);

        require(controller.selectedTab() == 0, "the basic tab must be the entry tab");
        require(service.rosterCalls.isEmpty() && service.scheduleCalls.isEmpty(),
                "hidden tabs must not issue requests, saw rosters " + service.rosterCalls
                        + " schedules " + service.scheduleCalls);

        controller.selectTab(1);
        require(service.rosterCalls.equals(List.of(OFFERING + "|||1|20")),
                "selecting the roster tab must load page 1 once, saw " + service.rosterCalls);

        controller.selectTab(2);
        require(service.scheduleCalls.equals(List.of(OFFERING)),
                "selecting the schedule tab must load the arrangements once, saw "
                        + service.scheduleCalls);

        controller.selectTab(1);
        controller.selectTab(3);
        require(service.rosterCalls.size() == 1 && service.scheduleCalls.size() == 1,
                "a tab already loaded must not request again");
        require(controller.selectedTab() == 3,
                "the grade tab must be selectable as read-only status");
    }

    private static void rosterFilterByNameAndUidReachesTheService() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        controller.showOffering(OFFERING);
        controller.selectTab(1);
        controller.goToRosterPage(2);

        controller.applyRosterFilter("5603");
        require(last(service.rosterCalls).equals(OFFERING + "|5603||1|20"),
                "a name/uid filter must reset to page 1, saw " + service.rosterCalls);

        controller.applyRosterStatus(TeacherOfferingDetailController.DROPPED_LABEL);
        require(last(service.rosterCalls).equals(OFFERING + "|5603|3|1|20"),
                "the 退课 filter must be sent as enrollmentStatus 3, saw " + service.rosterCalls);

        controller.applyRosterStatus(TeacherOfferingDetailController.ENROLLED_LABEL);
        require(last(service.rosterCalls).equals(OFFERING + "|5603|2|1|20"),
                "the 正常 filter must be sent as enrollmentStatus 2, saw " + service.rosterCalls);

        controller.applyRosterStatus(TeacherOfferingDetailController.ALL_LABEL);
        require(last(service.rosterCalls).equals(OFFERING + "|5603||1|20"),
                "全部 must send no enrollment status filter, saw " + service.rosterCalls);
    }

    private static void droppedRowsRenderTheDropStateAndTime() {
        TeacherRosterRowDTO dropped = new TeacherRosterRowDTO("50034", "00005603",
                "欧阳阿依古丽·买买提江·吐尔逊超长姓名测试", "计算机科学与技术（人工智能方向）实验班",
                "DROPPED", "2026-09-01T01:00:00Z", "2026-09-10T02:30:00Z");
        require(TeacherOfferingDetailController.rosterCells(dropped).equals(List.of(
                        "00005603", "欧阳阿依古丽·买买提江·吐尔逊超长姓名测试",
                        "计算机科学与技术（人工智能方向）实验班", "退课", "2026-09-01T01:00:00Z",
                        "2026-09-10T02:30:00Z")),
                "a dropped row must keep its own status text and drop time, saw "
                        + TeacherOfferingDetailController.rosterCells(dropped));

        TeacherRosterRowDTO enrolled = new TeacherRosterRowDTO("50035", "00005604", "学生01",
                null, "ENROLLED", "2026-09-01T01:00:00Z", null);
        require(TeacherOfferingDetailController.rosterCells(enrolled).equals(List.of(
                        "00005604", "学生01", "—", "正常", "2026-09-01T01:00:00Z", "—")),
                "an enrolled row must never fabricate a drop time, saw "
                        + TeacherOfferingDetailController.rosterCells(enrolled));
        require(TeacherOfferingDetailController.enrollmentStatusText("ENROLLED").equals("正常")
                        && TeacherOfferingDetailController.enrollmentStatusText("DROPPED")
                                .equals("退课")
                        && TeacherOfferingDetailController.enrollmentStatusText(null)
                                .equals("未知"),
                "both enrollment states must have stable labels");
    }

    private static void rosterPageWalksPastTheFirstPage() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        service.rosterPage = rosterPage(27, 1, 20, rosterRow("5600", "ENROLLED"),
                rosterRow("5601", "ENROLLED"));
        controller.showOffering(OFFERING);
        controller.selectTab(1);

        require(controller.rosterTotalCount() == 27 && !controller.hasPreviousRosterPage()
                        && controller.hasNextRosterPage(),
                "27 roster rows at 20 per page must be two pages starting on the first");

        service.rosterPage = rosterPage(27, 2, 20, rosterRow("5620", "ENROLLED"));
        controller.handleNextRosterPage(new ActionEvent());

        require(controller.rosterPage() == 2 && controller.hasPreviousRosterPage()
                        && !controller.hasNextRosterPage(),
                "the second roster page must be the last page");
        require(service.rosterCalls.get(1).equals(OFFERING + "|||2|20"),
                "paging must query the requested roster page, saw " + service.rosterCalls);

        controller.handlePreviousRosterPage(new ActionEvent());
        require(controller.rosterPage() == 1, "previous page must walk back to page 1");
    }

    private static void anEmptyClassHasAnEmptyRoster() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        controller.showOffering("9007199254740995");
        controller.selectTab(1);

        require(controller.roster().isEmpty() && controller.rosterTotalCount() == 0
                        && controller.rosterErrorText() == null,
                "an empty class must render an empty roster without an error");
    }

    private static void everyScheduleSlotIsRendered() {
        List<ScheduleArrangementDTO> arrangements = List.of(new ScheduleArrangementDTO(
                "9503", "7001", OFFERING,
                new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0),
                new ScheduleResourceDTO("8002", "00009012", "王助教", "teacher", 0),
                new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                1, 16, "ACTIVE", 1));

        List<String> lines = TeacherOfferingDetailController.arrangementLines(arrangements);
        require(lines.size() == 1, "one arrangement is one line, saw " + lines);
        require(lines.get(0).contains("周一 第 1-2 节") && lines.get(0).contains("周三 第 3-4 节"),
                "every time slot must be rendered, saw " + lines);
        require(lines.get(0).contains("第 1-16 周") && lines.get(0).contains("陈老师")
                        && lines.get(0).contains("A-101") && lines.get(0).contains("王助教"),
                "the line must carry the week range, teacher, assistant and classroom, saw " + lines);
        require(TeacherOfferingDetailController.arrangementLines(List.of())
                        .equals(List.of("暂无正式排课安排")),
                "a class without a published plan must say so instead of showing nothing");
        require(TeacherOfferingDetailController.weekdayText(7).equals("周日")
                        && TeacherOfferingDetailController.weekdayText(1).equals("周一")
                        && TeacherOfferingDetailController.weekdayText(0).equals("周0"),
                "weekday text must follow the calendar day numbering");
    }

    private static void theGradeEntryIsCapabilityGatedAndReadOnly() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        List<String> opened = new ArrayList<>();
        controller.setOpenGrades(opened::add);

        controller.showOffering(OFFERING);
        require(controller.canEditGrades(),
                "a class the teacher owns must report the grade capability");
        List<String> gradeLines = TeacherOfferingDetailController.gradeLines(controller.detail());
        require(gradeLines.contains("成绩录入权限：可录入"),
                "the grade tab must report the capability read-only, saw " + gradeLines);
        require(gradeLines.stream().noneMatch(line -> line.contains("缺失"))
                        && gradeLines.stream().noneMatch(line -> line.contains("总评")),
                "this phase must not fabricate grade statistics, saw " + gradeLines);

        controller.handleOpenGrades(new ActionEvent());
        require(opened.equals(List.of(OFFERING)),
                "the grade entry must route the workspace openGrades, saw " + opened);

        service.detailPages.add(completed(detailOf(OTHER_OFFERING, false)));
        controller.showOffering(OTHER_OFFERING);
        require(!controller.canEditGrades(),
                "an assistant-only class must not offer grade editing");
        controller.handleOpenGrades(new ActionEvent());
        require(opened.equals(List.of(OFFERING)),
                "a class without the capability must not route the grade entry, saw " + opened);
        require(TeacherOfferingDetailController.gradeLines(controller.detail())
                        .contains("成绩录入权限：只读"),
                "the read-only class must say so");
    }

    private static void staleDetailResponseCannotReplaceTheNewerOffering() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        CompletableFuture<TeacherOfferingDetailDTO> older = new CompletableFuture<>();
        CompletableFuture<TeacherOfferingDetailDTO> newer = new CompletableFuture<>();
        service.detailPages.add(older);
        service.detailPages.add(newer);

        controller.showOffering(OFFERING);
        controller.showOffering(OTHER_OFFERING);
        newer.complete(detailOf(OTHER_OFFERING, true));
        require(OTHER_OFFERING.equals(controller.detail().getOffering().getOfferingId()),
                "the newer offering must be rendered");

        older.complete(detailOf(OFFERING, true));
        require(OTHER_OFFERING.equals(controller.detail().getOffering().getOfferingId()),
                "an older offering's response must never replace the current one, saw "
                        + controller.detail().getOffering().getOfferingId());
        require(controller.offeringId().equals(OTHER_OFFERING),
                "the active offering id must stay the newer one");
    }

    /**
     * 一段加载失败只影响这一段：界面保留已经显示的数据并给出可重试的错误文案，其他 Tab 不受影响。
     */
    private static void aFailedSectionKeepsItsDataAndOffersARetry() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);

        service.detailPages.add(failed(new IllegalStateException("数据库不可用")));
        controller.showOffering(OFFERING);
        require(TeacherOfferingDetailController.BASIC_FAILURE_TEXT.equals(
                        controller.basicErrorText()) && controller.detail() == null,
                "a failed basic load must be reported without inventing a detail, saw "
                        + controller.basicErrorText());

        controller.showOffering(OFFERING);
        require(controller.detail() != null && controller.basicErrorText() == null,
                "a retried basic load must clear the error text");

        CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> pendingRoster =
                new CompletableFuture<>();
        service.rosterPages.add(pendingRoster);
        controller.selectTab(1);
        require(controller.loadingRoster(),
                "the roster section must report that it is loading while the request is in flight");
        pendingRoster.completeExceptionally(new IllegalStateException("数据库不可用"));
        require(!controller.loadingRoster()
                        && TeacherOfferingDetailController.ROSTER_FAILURE_TEXT.equals(
                                controller.rosterErrorText()),
                "a failed roster load must surface the retryable error, saw "
                        + controller.rosterErrorText());

        service.schedulePages.add(failed(new IllegalStateException("数据库不可用")));
        controller.selectTab(2);
        require(TeacherOfferingDetailController.SCHEDULE_FAILURE_TEXT.equals(
                        controller.scheduleErrorText()),
                "a failed schedule load must surface the retryable error, saw "
                        + controller.scheduleErrorText());
        require(service.scheduleCalls.size() == 1,
                "a failed load must not be issued twice for the same tab selection");

        // 失败不会被缓存：再次进入该 Tab 会再试一次，成功后错误文案消失。
        controller.selectTab(2);
        require(service.scheduleCalls.size() == 2 && controller.scheduleErrorText() == null
                        && controller.schedules().size() == 1,
                "re-entering a failed tab must retry once and render on success, saw calls "
                        + service.scheduleCalls.size());

        controller.handleRefresh(new ActionEvent());
        require(service.scheduleCalls.size() == 3 && service.detailCalls.size() == 3,
                "刷新 must reload the basic info and the active tab, saw schedules "
                        + service.scheduleCalls.size() + " details " + service.detailCalls.size());
    }

    private static void releasedPageDropsItsDataAndIgnoresLateResponses() {
        ControlledService service = new ControlledService();
        TeacherOfferingDetailController controller = controller(service);
        CompletableFuture<TeacherOfferingDetailDTO> pending = new CompletableFuture<>();
        service.detailPages.add(pending);
        controller.showOffering(OFFERING);
        controller.selectTab(1);

        controller.release();

        require(!controller.active() && controller.offeringId() == null
                        && controller.detail() == null && controller.roster().isEmpty()
                        && controller.schedules().isEmpty(),
                "a released page must not retain the previous class data");
        pending.complete(detailOf(OFFERING, true));
        require(controller.detail() == null,
                "a response arriving after release must be ignored");
    }

    private static void theSharedServiceContractExposesNoWritePath() {
        Set<String> allowed = Set.of("listTerms", "listOfferings", "getOffering",
                "listOfferingStudents", "listOfferingSchedules", "loadTeachingSchedule");
        for (Method method : TeacherCourseService.class.getDeclaredMethods()) {
            require(allowed.contains(method.getName()),
                    "the teacher course service must expose only read paths, found "
                            + method.getName());
        }
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewIsTheOfferingDetailPage(Document view) throws Exception {
        Element root = view.getDocumentElement();
        require("controller.TeacherOfferingDetailController"
                        .equals(root.getAttribute("fx:controller")),
                "the view must be controlled by TeacherOfferingDetailController, saw "
                        + root.getAttribute("fx:controller"));

        everyFxIdAndOnActionResolvesOnTheController(view);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));

        Element tabs = elementWithTagAndId(view, "TabPane", "detailTabs");
        require(tabs != null, "the detail page must expose its tab pane");
        require(tabTexts(view).equals(List.of("基本信息", "学生名单", "上课安排", "成绩情况")),
                "the detail page must have the four §5.1 tabs, saw " + tabTexts(view));

        Element exportButton = buttonWithText(view, "导出");
        require(exportButton != null, "the roster tab must expose the export entry");
        require("true".equals(exportButton.getAttribute("disable")),
                "the export button stays disabled until the umbrella plan's T5, saw \""
                        + exportButton.getAttribute("disable") + "\"");
        require(exportButton.getAttribute("onAction").isEmpty(),
                "the staged export button must not pretend to do anything");

        for (String forbidden : List.of("添加学生", "删除学生", "移除学生", "退课")) {
            require(buttonWithText(view, forbidden) == null,
                    "teachers must not see a " + forbidden + " entry");
        }
    }

    private static List<String> tabTexts(Document view) {
        List<String> texts = new ArrayList<>();
        NodeList tabs = view.getElementsByTagName("Tab");
        for (int index = 0; index < tabs.getLength(); index++) {
            texts.add(((Element) tabs.item(index)).getAttribute("text"));
        }
        return List.copyOf(texts);
    }

    private static void everyFxIdAndOnActionResolvesOnTheController(Document view) {
        Class<?> controller = TeacherOfferingDetailController.class;
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

    private static void noElementUsesTheReadOnlyDisabledAttribute(Document view) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            require(!element.hasAttribute("disabled"),
                    "<" + element.getTagName()
                            + "> must not use the read-only disabled attribute; use disable");
        }
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
                    "styleClass " + name + " has no ." + name + " selector in teacher-course.css");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherOfferingDetailController controller(TeacherCourseService service) {
        return new TeacherOfferingDetailController(service, Runnable::run);
    }

    private static TeacherOfferingDetailDTO detailOf(String offeringId, boolean canEditGrades) {
        return detail(offeringId, canEditGrades, "计算机科学与工程学院");
    }

    private static TeacherOfferingDetailDTO detailWithoutCollege(String offeringId) {
        return detail(offeringId, true, null);
    }

    private static TeacherOfferingDetailDTO detail(String offeringId, boolean canEditGrades,
            String offeringCollege) {
        TeacherOfferingDTO offering = new TeacherOfferingDTO(offeringId,
                "CS" + offeringId.substring(offeringId.length() - 4), "课程 " + offeringId,
                "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3, 27, 30, "OPEN",
                canEditGrades, true);
        return new TeacherOfferingDetailDTO(offering,
                List.of(new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0)),
                offeringCollege, "课程简介");
    }

    private static TeacherRosterRowDTO rosterRow(String uid, String status) {
        return new TeacherRosterRowDTO("5" + uid, uid, "学生" + uid, "计算机科学与技术", status,
                "2026-09-01T01:00:00Z",
                "DROPPED".equals(status) ? "2026-09-10T02:30:00Z" : null);
    }

    @SafeVarargs
    private static TeacherPageDTO<TeacherRosterRowDTO> rosterPage(long totalCount, int page,
            int size, TeacherRosterRowDTO... items) {
        return new TeacherPageDTO<>(List.of(items), totalCount, page, size);
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static String last(List<String> calls) {
        return calls.get(calls.size() - 1);
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherOfferingDetailControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherOfferingDetailControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Element elementWithTagAndId(Document view, String tag, String id) {
        NodeList elements = view.getElementsByTagName(tag);
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static Element buttonWithText(Document view, String text) {
        NodeList buttons = view.getElementsByTagName("Button");
        for (int index = 0; index < buttons.getLength(); index++) {
            Element button = (Element) buttons.item(index);
            if (text.equals(button.getAttribute("text"))) return button;
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

    private static final class ControlledService implements TeacherCourseService {
        private TeacherPageDTO<TeacherRosterRowDTO> rosterPage;
        private final Deque<CompletableFuture<TeacherOfferingDetailDTO>> detailPages =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>>> rosterPages =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<List<ScheduleArrangementDTO>>> schedulePages =
                new ArrayDeque<>();
        private final List<String> detailCalls = new ArrayList<>();
        private final List<String> rosterCalls = new ArrayList<>();
        private final List<String> scheduleCalls = new ArrayList<>();

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            throw new UnsupportedOperationException("the detail page must not load terms");
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            throw new UnsupportedOperationException("the detail page must not load the list");
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            detailCalls.add(offeringId);
            if (!detailPages.isEmpty()) return detailPages.removeFirst();
            return CompletableFuture.completedFuture(detailOf(offeringId, true));
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            rosterCalls.add(offeringId + "|" + (query == null ? "" : query) + "|"
                    + (enrollmentStatus == null ? "" : enrollmentStatus) + "|" + page + "|"
                    + size);
            if (!rosterPages.isEmpty()) return rosterPages.removeFirst();
            return CompletableFuture.completedFuture(rosterPage == null
                    ? new TeacherPageDTO<>(List.of(), 0, page, size) : rosterPage);
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            scheduleCalls.add(offeringId);
            if (!schedulePages.isEmpty()) return schedulePages.removeFirst();
            return CompletableFuture.completedFuture(List.of(new ScheduleArrangementDTO(
                    "9503", "7001", offeringId,
                    new ScheduleResourceDTO("8001", "00001234", "陈老师", "teacher", 0), null,
                    new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                    List.of(new ScheduleSlotDTO(1, 1, 2), new ScheduleSlotDTO(3, 3, 4)),
                    1, 16, "ACTIVE", 1)));
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            return CompletableFuture.completedFuture(new TeacherScheduleWeekDTO(
                    "9007199254740991", "Asia/Shanghai", week == null ? 1 : week, 1, 16, 1,
                    List.of(), List.of(), List.of()));
        }
    }
}
