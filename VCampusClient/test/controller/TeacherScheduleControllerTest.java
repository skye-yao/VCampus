package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
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
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import javafx.event.ActionEvent;
import javafx.event.Event;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的教师周课表页与课次详情弹窗测试。
 *
 * <p>注入假服务、{@code Runnable::run} 的 FX 执行器与 {@code null} 的 FXML 节点：周导航的边界、
 * “回到本周”的可空判定、快速切周只接收最后结果、卡片到详情的传递，以及 {@code unload()} 之后在途
 * 响应被丢弃，全部在没有真实控件的环境下断言。卡片集合由控制器在渲染时收集（节点为 null 时也照常
 * 计算），因此“点的是哪一张”可以被真的验证，而不是只验证一个静态文案函数。
 *
 * <p>两个新视图用 {@link DocumentBuilderFactory} 结构化校验（fx:id ↔ 控制器字段、onAction ↔ 真实
 * 方法、styleClass ↔ teacher-course.css 里的 `.选择器`、以及绝不出现只读的 {@code disabled}），
 * 与 T1 的外壳测试同一手法。
 */
public final class TeacherScheduleControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherScheduleView.fxml";
    private static final String DIALOG_VIEW = "/resources/fxml/TeacherCourseDetailDialog.fxml";
    private static final String SHELL_VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";

    private static final int ACADEMIC_YEAR = 2025;
    private static final int SPRING = 3;
    private static final int MIN_WEEK = 1;
    private static final int MAX_WEEK = 16;
    private static final int CURRENT_WEEK = 8;
    private static final int TEACHING_DAYS = 6;
    private static final int PERIODS_PER_TEACHING_DAY = 13;
    private static final LocalDate WEEK_ONE_START = LocalDate.of(2026, 9, 7);
    private static final LocalTime FIRST_PERIOD_START = LocalTime.of(8, 0);
    private static final DateTimeFormatter PERIOD_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final String FULL_ROSTER_OFFERING = "9007199254740993";
    private static final String INTERACTION_OFFERING = "9007199254740997";
    private static final String PLAIN_OFFERING = "9007199254740995";
    private static final String CROSS_WEEK_OCCURRENCE = "9201";
    private static final String CROSS_WEEK_ORIGINAL_TEXT = "周一 第1-2节 A-101";
    private static final String CROSS_WEEK_ADJUSTED_TEXT = "周三 第3-4节 B-203";
    private static final String CROSS_WEEK_REASON = "教师出差";

    private TeacherScheduleControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        controllerIsSafeWithoutNodes();
        weekNavigationFollowsTheLoadedBoundaries();
        backToCurrentWeekFollowsTheNullableCurrentWeek();
        loadFailuresRenderTheServerMessageOnlyWhenItIsBusinessFacing();
        onlyTheNewestWeekResponseIsRendered();
        detailOpensTheClickedCardAndUnloadDropsLateResponses();
        weekLabelAndGridTextComeFromTheDto();
        cardBadgesAndStyleClassesFollowTheDisplayKind();
        dialogShowsTheEntrySnapshotAndDropsResponsesAfterClosing();
        dialogTextsAreTheTaskFiveContract();

        viewIsTheTeacherTimetablePage(parseView(VIEW));
        dialogViewIsTheCardDetailDialog(parseView(DIALOG_VIEW));
        shellKeepsTheMainWindowSize(parseView(SHELL_VIEW));
        System.out.println("TeacherScheduleControllerTest: PASS");
    }

    private static void controllerIsSafeWithoutNodes() {
        TeacherScheduleController controller = controller(new ControlledService());
        controller.initialize();
        controller.activate();
        controller.handleNextWeek(new ActionEvent());
        controller.handlePreviousWeek(new ActionEvent());
        controller.handleBackToCurrentWeek(new ActionEvent());
        controller.refresh();
        controller.openDetail(null);
        controller.setOpenOffering(null);
        controller.setDetailOpener(null);
        controller.unload();
        require(!controller.active(), "unloading must stop the page from accepting responses");
    }

    private static void weekNavigationFollowsTheLoadedBoundaries() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(ACADEMIC_YEAR, SPRING));
        TeacherScheduleController controller = controller(service);
        service.week = week(MIN_WEEK, MIN_WEEK, MAX_WEEK, MIN_WEEK, List.of());

        controller.activate();

        require(controller.loadedWeek() != null && controller.loadedWeek() == MIN_WEEK
                        && controller.requestedWeek() == null,
                "the first load must let the server pick the week, saw "
                        + controller.requestedWeek());
        require(!controller.canGoPrevious() && controller.canGoNext(),
                "the first week must not walk before minWeek but must walk forward");

        service.week = week(MIN_WEEK + 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of());
        controller.handleNextWeek(new ActionEvent());
        require(controller.requestedWeek() != null && controller.requestedWeek() == MIN_WEEK + 1,
                "下一周 must request loadedWeek + 1, saw " + controller.requestedWeek());
        require(controller.canGoPrevious() && controller.canGoNext(),
                "a week inside the bounds must allow both directions");

        service.week = week(MAX_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of());
        controller.handleNextWeek(new ActionEvent());
        require(controller.loadedWeek() == MAX_WEEK && !controller.canGoNext()
                        && controller.canGoPrevious(),
                "the last week must not walk past maxWeek, saw " + controller.loadedWeek());

        require(service.scheduleCalls.equals(List.of("2025|3|null", "2025|3|2", "2025|3|3")),
                "every navigation must request exactly one week, saw " + service.scheduleCalls);
    }

    private static void backToCurrentWeekFollowsTheNullableCurrentWeek() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(ACADEMIC_YEAR, SPRING));
        TeacherScheduleController controller = controller(service);
        service.week = week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, null, List.of());

        controller.activate();

        require(!controller.canGoCurrent(),
                "回到本周 must be disabled while the server reports no current week");

        service.week = week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of());
        controller.refresh();
        require(controller.canGoCurrent(),
                "回到本周 must be enabled once the server reports a current week");

        service.week = week(CURRENT_WEEK + 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of());
        controller.handleNextWeek(new ActionEvent());
        require(controller.loadedWeek() == CURRENT_WEEK + 1,
                "the page must follow the loaded week, saw " + controller.loadedWeek());

        controller.handleBackToCurrentWeek(new ActionEvent());

        require(controller.requestedWeek() == null,
                "回到本周 must ask the server for the current week, saw "
                        + controller.requestedWeek());
        require(service.scheduleCalls.get(service.scheduleCalls.size() - 1).equals("2025|3|null"),
                "回到本周 must send a null week, saw " + service.scheduleCalls);
    }

    private static void onlyTheNewestWeekResponseIsRendered() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(ACADEMIC_YEAR, SPRING));
        TeacherScheduleController controller = controller(service);
        service.week = week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, weekEightEntries());
        controller.activate();

        CompletableFuture<TeacherScheduleWeekDTO> older = new CompletableFuture<>();
        CompletableFuture<TeacherScheduleWeekDTO> newer = new CompletableFuture<>();
        service.weekFutures.add(older);
        service.weekFutures.add(newer);

        controller.handleNextWeek(new ActionEvent());
        controller.handlePreviousWeek(new ActionEvent());

        newer.complete(week(CURRENT_WEEK - 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK,
                List.of(normalEntry("9301", PLAIN_OFFERING, CURRENT_WEEK - 1, 3, 1, 2))));
        require(controller.week() != null && controller.week().getWeek() == CURRENT_WEEK - 1,
                "the newer response must be rendered, saw " + controller.loadedWeek());
        require(controller.loadedWeek() == CURRENT_WEEK - 1,
                "the loaded week must follow the rendered response");

        older.complete(week(CURRENT_WEEK + 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of()));
        require(controller.week().getWeek() == CURRENT_WEEK - 1
                        && controller.week().getEntries().size() == 1
                        && "9301".equals(controller.week().getEntries().get(0).getOccurrenceId()),
                "a stale response must never replace the newer result, saw week "
                        + controller.week().getWeek() + " with "
                        + controller.week().getEntries().size() + " entries");
        require(controller.requestedWeek() == CURRENT_WEEK - 1 && controller.errorText() == null,
                "the stale response must not disturb the requested week or the error state, saw "
                        + controller.requestedWeek() + " / " + controller.errorText());
    }

    private static void detailOpensTheClickedCardAndUnloadDropsLateResponses() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(ACADEMIC_YEAR, SPRING));
        TeacherScheduleController controller = controller(service);
        service.week = week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, weekEightEntries());
        controller.activate();

        List<TeacherScheduleEntryDTO> cards = controller.cardEntries();
        require(cards.size() == 4, "week 8 must render one card per entry, saw " + cards.size());
        TeacherScheduleEntryDTO card = cards.get(0);
        require(CROSS_WEEK_OCCURRENCE.equals(card.getOccurrenceId())
                        && FULL_ROSTER_OFFERING.equals(card.getOfferingId()),
                "the first card must be the Monday original marker, saw "
                        + card.getOccurrenceId());

        List<TeacherScheduleEntryDTO> opened = new ArrayList<>();
        controller.setDetailOpener(opened::add);
        controller.openDetail(card);

        require(opened.equals(List.of(card)),
                "the clicked card must be handed to the detail opener untouched");
        require(CROSS_WEEK_OCCURRENCE.equals(opened.get(0).getOccurrenceId())
                        && FULL_ROSTER_OFFERING.equals(opened.get(0).getOfferingId()),
                "the detail must receive the clicked card's offering and occurrence, saw "
                        + opened.get(0).getOfferingId() + "/" + opened.get(0).getOccurrenceId());

        CompletableFuture<TeacherScheduleWeekDTO> late = new CompletableFuture<>();
        service.weekFutures.add(late);
        controller.handleNextWeek(new ActionEvent());
        controller.unload();
        require(!controller.active(), "an unloaded page must stop accepting responses");

        late.complete(week(CURRENT_WEEK + 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of()));
        require(controller.week().getWeek() == CURRENT_WEEK && controller.cardEntries().size() == 4,
                "a response arriving after unload must be ignored, saw week "
                        + controller.week().getWeek());

        service.week = week(CURRENT_WEEK + 1, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of());
        controller.activate();
        require(service.scheduleCalls.get(service.scheduleCalls.size() - 1).equals("2025|3|9"),
                "re-activating must reload the week the page was left on, saw "
                        + service.scheduleCalls);
    }

    private static void weekLabelAndGridTextComeFromTheDto() {
        require(TeacherScheduleController.weekLabel(
                        week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of()))
                        .equals("第 8 周（1-16）"),
                "the week label must read 第 N 周（min-max） for the loaded week, saw "
                        + TeacherScheduleController.weekLabel(
                                week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, List.of())));
        require(TeacherScheduleController.weekLabel(null).isEmpty(),
                "no loaded week must render an empty week label");

        require(TeacherScheduleController.weekdayText(1).equals("周一")
                        && TeacherScheduleController.weekdayText(6).equals("周六")
                        && TeacherScheduleController.weekdayText(7).equals("周日"),
                "the teacher calendar covers the whole week, weekend included");

        TeacherCalendarDateDTO monday = new TeacherCalendarDateDTO("2026-09-07", CURRENT_WEEK, 1, true);
        require(TeacherScheduleController.dayHeader(monday).equals("周一 09-07"),
                "a date column must show the teaching weekday and the month-day, saw "
                        + TeacherScheduleController.dayHeader(monday));
        TeacherCalendarDateDTO sunday = new TeacherCalendarDateDTO("2026-09-13", CURRENT_WEEK, 7, false);
        require(TeacherScheduleController.dayHeader(sunday).equals("周日 09-13"),
                "a non-teaching date still gets its own column header");

        TeacherPeriodDTO thirteenth = period(dateOf(CURRENT_WEEK, 1), PERIODS_PER_TEACHING_DAY);
        require(TeacherScheduleController.periodHeader(PERIODS_PER_TEACHING_DAY, thirteenth)
                        .equals("第 13 节 18:00:00-18:45:00"),
                "a period row must carry the DTO's fixed-width HH:mm:ss range, saw "
                        + TeacherScheduleController.periodHeader(PERIODS_PER_TEACHING_DAY,
                                thirteenth));
        require(TeacherScheduleController.periodHeader(1, null).equals("第 1 节"),
                "a period without a definition must still render its own row header");

        require(TeacherScheduleController.periodNumbers(List.of(
                        new TeacherPeriodDTO("2026-09-07", 5, "12:20:00", "13:05:00"),
                        new TeacherPeriodDTO("2026-09-07", 1, "08:00:00", "08:45:00"),
                        new TeacherPeriodDTO("2026-09-07", 5, "12:20:00", "13:05:00")))
                        .equals(List.of(1, 5)),
                "the period rows must be the ascending union of the response's periods, never a"
                        + " hardcoded range");

        require(TeacherScheduleController.cardLines(weekEightEntries().get(0))
                        .equals(List.of("数据结构与算法基础", "A-101")),
                "a card must show the course name and the location from the DTO, saw "
                        + TeacherScheduleController.cardLines(weekEightEntries().get(0)));
    }

    private static void cardBadgesAndStyleClassesFollowTheDisplayKind() {
        TeacherScheduleEntryDTO normal =
                normalEntry("9301", PLAIN_OFFERING, CURRENT_WEEK, 3, 1, 2);
        TeacherScheduleEntryDTO original =
                adjustedEntry(CROSS_WEEK_OCCURRENCE, ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL);
        TeacherScheduleEntryDTO target =
                adjustedEntry(CROSS_WEEK_OCCURRENCE, ScheduleDisplayKindDTO.ADJUSTED_TARGET);

        require(TeacherScheduleController.badgeText(normal) == null,
                "a normal lesson carries no adjustment badge");
        require("原安排".equals(TeacherScheduleController.badgeText(original)),
                "an original marker must be badged 原安排, saw "
                        + TeacherScheduleController.badgeText(original));
        require("调课后".equals(TeacherScheduleController.badgeText(target)),
                "an adjusted target must be badged 调课后, saw "
                        + TeacherScheduleController.badgeText(target));

        require(TeacherScheduleController.cardStyleClass(normal) == null,
                "a normal lesson adds no adjustment style class");
        require("teacher-schedule-adjusted-original"
                        .equals(TeacherScheduleController.cardStyleClass(original)),
                "an original marker must carry its own style class, saw "
                        + TeacherScheduleController.cardStyleClass(original));
        require("teacher-schedule-adjusted-target"
                        .equals(TeacherScheduleController.cardStyleClass(target)),
                "an adjusted target must carry its own style class, saw "
                        + TeacherScheduleController.cardStyleClass(target));
    }

    private static void dialogShowsTheEntrySnapshotAndDropsResponsesAfterClosing() {
        TeacherScheduleEntryDTO entry = weekEightEntries().get(0);
        TeacherScheduleWeekDTO spring =
                week(CURRENT_WEEK, MIN_WEEK, MAX_WEEK, CURRENT_WEEK, weekEightEntries());

        DialogService service = new DialogService();
        service.detail = offeringDetail(27, 30);
        TeacherCourseDetailDialogController dialog =
                new TeacherCourseDetailDialogController(service, Runnable::run);

        dialog.prepare(entry, spring);

        require(service.offeringCalls.equals(List.of(FULL_ROSTER_OFFERING)),
                "the dialog must load the authoritative offering snapshot, saw "
                        + service.offeringCalls);
        require(dialog.lines().equals(List.of(
                        "课程编号：CS203",
                        "课程名称：数据结构与算法基础",
                        "授课教师：陈老师, 王助教",
                        "上课时间：2026-10-26 周一 第 1-2 节",
                        "上课地点：A-101",
                        "周次：第 8 周（1-16）")),
                "the dialog must show every business field of the entry, saw " + dialog.lines());
        require(dialog.adjustmentLines().equals(List.of(
                        "原安排：" + CROSS_WEEK_ORIGINAL_TEXT,
                        "调整后：" + CROSS_WEEK_ADJUSTED_TEXT,
                        "调课原因：" + CROSS_WEEK_REASON)),
                "the dialog must show the server's adjustment texts verbatim, saw "
                        + dialog.adjustmentLines());
        require(dialog.snapshotLines().equals(List.of(
                        "教学班：CS203-01　数据结构 CS203-01",
                        "教学班人数：27 / 30")),
                "the headcount must come from the offering snapshot, saw "
                        + dialog.snapshotLines());
        require(!dialog.closed() && dialog.snapshotErrorText() == null && !dialog.loadingSnapshot(),
                "a loaded snapshot must leave no error state");

        List<String> routed = new ArrayList<>();
        dialog.setOpenOffering(routed::add);
        dialog.handleRequestAdjustment(new ActionEvent());
        require(routed.isEmpty() && !dialog.closed(),
                "申请调课 must not start any request in this phase");
        dialog.handleOpenOffering(new ActionEvent());
        require(routed.equals(List.of(FULL_ROSTER_OFFERING)) && dialog.closed(),
                "查看教学班 must route the offering and close the dialog, saw " + routed);

        // 关闭后取消：关闭时递增的 generation 让在途响应不再写任何控件。
        DialogService closing = new DialogService();
        closing.detail = offeringDetail(27, 30);
        CompletableFuture<TeacherOfferingDetailDTO> late = new CompletableFuture<>();
        closing.detailFutures.add(late);
        TeacherCourseDetailDialogController closed =
                new TeacherCourseDetailDialogController(closing, Runnable::run);
        closed.prepare(entry, spring);
        require(closed.loadingSnapshot(), "the dialog must report its pending snapshot request");
        closed.dispose();
        require(closed.closed(), "closing the dialog must mark it closed");
        late.complete(offeringDetail(1, 2));
        require(closed.snapshotLines().equals(
                        List.of(TeacherCourseDetailDialogController.SNAPSHOT_LOADING_TEXT)),
                "a snapshot arriving after closing must be dropped, saw " + closed.snapshotLines());

        DialogService failing = new DialogService();
        failing.detail = offeringDetail(27, 30);
        failing.detailFutures.add(failed(new IllegalStateException("数据库不可用")));
        TeacherCourseDetailDialogController retrying =
                new TeacherCourseDetailDialogController(failing, Runnable::run);
        retrying.prepare(entry, spring);
        require(TeacherCourseDetailDialogController.SNAPSHOT_FAILURE_TEXT
                        .equals(retrying.snapshotErrorText()),
                "a failed snapshot must offer a retry, saw " + retrying.snapshotErrorText());
        require(retrying.lines().equals(List.of(
                        "课程编号：CS203",
                        "课程名称：数据结构与算法基础",
                        "授课教师：陈老师, 王助教",
                        "上课时间：2026-10-26 周一 第 1-2 节",
                        "上课地点：A-101",
                        "周次：第 8 周（1-16）")),
                "a failed snapshot must keep the entry fields on screen, saw " + retrying.lines());
        retrying.retry(new ActionEvent());
        require(retrying.snapshotErrorText() == null && retrying.snapshotLines().size() == 2
                        && !retrying.loadingSnapshot(),
                "a successful retry must fill the snapshot, saw " + retrying.snapshotLines());
    }

    private static void dialogTextsAreTheTaskFiveContract() {
        require("课程详情".equals(TeacherCourseDetailDialogController.TITLE),
                "the dialog stage title is the anchor the GUI smoke test finds the window by, saw "
                        + TeacherCourseDetailDialogController.TITLE);
        require("申请调课将在后续阶段接入".equals(
                        TeacherCourseDetailDialogController.ADJUSTMENT_STAGING_TEXT),
                "the disabled 申请调课 entry must state when it arrives, saw "
                        + TeacherCourseDetailDialogController.ADJUSTMENT_STAGING_TEXT);
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewIsTheTeacherTimetablePage(Document view) throws Exception {
        Element root = view.getDocumentElement();
        require("controller.TeacherScheduleController".equals(root.getAttribute("fx:controller")),
                "the timetable must be controlled by TeacherScheduleController, saw "
                        + root.getAttribute("fx:controller"));

        everyFxIdAndOnActionResolvesOnTheController(view, TeacherScheduleController.class);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));

        require(elementWithId(view, "scheduleGrid") != null,
                "the page must expose the timetable grid");
        require(elementWithId(view, "weekLabel") != null,
                "the page must expose the week label");
        require(elementWithId(view, "previousWeekButton") != null
                        && elementWithId(view, "nextWeekButton") != null
                        && elementWithId(view, "currentWeekButton") != null,
                "the page must expose all three week navigation buttons");
        require(elementWithId(view, "termFilter") != null,
                "the page must expose the term filter shared with the offering list");

        Element scroll = elementWithId(view, "scheduleScroll");
        require(scroll != null && "ScrollPane".equals(scroll.getTagName()),
                "the grid must live in a ScrollPane so 860x580 can scroll in both directions");
        require(containsId(scroll, "scheduleGrid"),
                "the timetable grid must be the scroll pane's content");
    }

    private static void dialogViewIsTheCardDetailDialog(Document view) throws Exception {
        Element root = view.getDocumentElement();
        require("controller.TeacherCourseDetailDialogController"
                        .equals(root.getAttribute("fx:controller")),
                "the dialog must be controlled by TeacherCourseDetailDialogController, saw "
                        + root.getAttribute("fx:controller"));
        require(root.getAttribute("stylesheets").contains("@../css/teacher-course.css"),
                "the dialog must link teacher-course.css, saw "
                        + root.getAttribute("stylesheets"));

        everyFxIdAndOnActionResolvesOnTheController(
                view, TeacherCourseDetailDialogController.class);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));

        Element requestAdjustment = elementWithId(view, "requestAdjustmentButton");
        require(requestAdjustment != null && "true".equals(
                        requestAdjustment.getAttribute("disable")),
                "申请调课 must stay disabled until the adjustment phase lands");
        require(elementWithId(view, "openOfferingButton") != null,
                "the dialog must expose the 查看教学班 entry");
        require(elementWithId(view, "closeButton") != null,
                "the dialog must expose a close button the GUI smoke test can use");
    }

    private static void shellKeepsTheMainWindowSize(Document view) {
        Element root = view.getDocumentElement();
        require("860.0".equals(root.getAttribute("prefWidth"))
                        && "580.0".equals(root.getAttribute("prefHeight")),
                "the workspace must keep the 860x580 main window, saw "
                        + root.getAttribute("prefWidth") + "x" + root.getAttribute("prefHeight"));
    }

    private static void everyFxIdAndOnActionResolvesOnTheController(Document view,
            Class<?> controller) {
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
        require(!styleClasses.isEmpty(),
                "the view must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherScheduleController controller(TeacherCourseService service) {
        return new TeacherScheduleController(service, Runnable::run);
    }

    private static CourseTermDTO term(int academicYear, int semester) {
        return new CourseTermDTO(academicYear, semester,
                academicYear + " 学年 第 " + semester + " 学期");
    }

    /** Mock 第 8 周的四个课次：跨周原位置、同周调整的一对、以及一节未调整的周末第 12-13 节。 */
    private static List<TeacherScheduleEntryDTO> weekEightEntries() {
        return List.of(
                new TeacherScheduleEntryDTO(CROSS_WEEK_OCCURRENCE, FULL_ROSTER_OFFERING, "CS203",
                        "数据结构与算法基础", "陈老师, 王助教", "A-101", dateOf(CURRENT_WEEK, 1),
                        CURRENT_WEEK, 1, 1, 2, ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, "9301",
                        CROSS_WEEK_ORIGINAL_TEXT, CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON,
                        false),
                new TeacherScheduleEntryDTO("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                        "陈老师", "A-101", dateOf(CURRENT_WEEK, 2), CURRENT_WEEK, 2, 1, 2,
                        ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, "9302", "周二 第1-2节 A-101",
                        "周五 第5-6节 B-203", "临时调课", false),
                new TeacherScheduleEntryDTO("9202", INTERACTION_OFFERING, "CS352", "人机交互导论",
                        "陈老师", "B-203", dateOf(CURRENT_WEEK, 5), CURRENT_WEEK, 5, 5, 6,
                        ScheduleDisplayKindDTO.ADJUSTED_TARGET, "9302", "周二 第1-2节 A-101",
                        "周五 第5-6节 B-203", "临时调课", false),
                normalEntry("9203", PLAIN_OFFERING, CURRENT_WEEK, 6, 12, 13));
    }

    private static TeacherScheduleEntryDTO adjustedEntry(String occurrenceId,
            ScheduleDisplayKindDTO kind) {
        return new TeacherScheduleEntryDTO(occurrenceId, FULL_ROSTER_OFFERING, "CS203",
                "数据结构与算法基础", "陈老师, 王助教", "A-101", dateOf(CURRENT_WEEK, 1),
                CURRENT_WEEK, 1, 1, 2, kind, "9301", CROSS_WEEK_ORIGINAL_TEXT,
                CROSS_WEEK_ADJUSTED_TEXT, CROSS_WEEK_REASON, false);
    }

    private static TeacherScheduleEntryDTO normalEntry(String occurrenceId, String offeringId,
            int week, int weekday, int startPeriod, int endPeriod) {
        return new TeacherScheduleEntryDTO(occurrenceId, offeringId, "CS301", "操作系统原理",
                "陈老师", "C-301", dateOf(week, weekday), week, weekday, startPeriod, endPeriod,
                ScheduleDisplayKindDTO.NORMAL, null, null, null, null, true);
    }

    private static String dateOf(int week, int teachingWeekday) {
        return WEEK_ONE_START.plusDays((week - 1) * 7L + teachingWeekday - 1L).toString();
    }

    private static TeacherPeriodDTO period(String date, int period) {
        LocalTime start = FIRST_PERIOD_START.plusMinutes(50L * (period - 1));
        return new TeacherPeriodDTO(date, period, start.format(PERIOD_TIME),
                start.plusMinutes(45).format(PERIOD_TIME));
    }

    /** 与 {@code MockTeacherCourseService} 同形的整周：7 行日期、教学日 13 节、第 7 天非教学日。 */
    private static TeacherScheduleWeekDTO week(int week, int minWeek, int maxWeek,
            Integer currentWeek, List<TeacherScheduleEntryDTO> entries) {
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        for (int weekday = 1; weekday <= 7; weekday++) {
            String date = dateOf(week, weekday);
            boolean teachingDay = weekday <= TEACHING_DAYS;
            dates.add(new TeacherCalendarDateDTO(date, week, weekday, teachingDay));
            if (!teachingDay) continue;
            for (int period = 1; period <= PERIODS_PER_TEACHING_DAY; period++) {
                periods.add(period(date, period));
            }
        }
        return new TeacherScheduleWeekDTO("9007199254740001", "Asia/Shanghai", week, minWeek,
                maxWeek, currentWeek, dates, periods, entries);
    }

    private static TeacherOfferingDetailDTO offeringDetail(int enrolledCount, int capacity) {
        return new TeacherOfferingDetailDTO(new TeacherOfferingDTO(FULL_ROSTER_OFFERING,
                "CS203-01", "数据结构 CS203-01", "2001", "CS203", "数据结构与算法基础", 4.0,
                ACADEMIC_YEAR, SPRING, enrolledCount, capacity, "OPEN", true, true),
                List.of(), "计算机科学与工程学院", "课程简介");
    }

    /**
     * 加载失败时提示区写什么：服务端给出的业务拒绝（BAD_REQUEST / NOT_FOUND）必须原样显示，
     * 例如 Task 2 裁定里的「该学期暂无已发布的教学日历」；传输失败与客户端技术串都只显示可重试的
     * 通用文案，绝不把 {@code 缺少响应字段: schedule} 这种内部细节当成用户可见的提示。
     */
    private static void loadFailuresRenderTheServerMessageOnlyWhenItIsBusinessFacing() {
        String serverMessage = "该学期暂无已发布的教学日历";

        ControlledService badRequest = termService();
        badRequest.weekFutures.add(failed(new TeacherCourseServiceException(
                MessageCode.BAD_REQUEST, serverMessage)));
        TeacherScheduleController badRequestPage = controller(badRequest);
        badRequestPage.activate();
        require(serverMessage.equals(badRequestPage.errorText()),
                "a BAD_REQUEST must show the server's own message, saw "
                        + badRequestPage.errorText());

        ControlledService notFound = termService();
        notFound.weekFutures.add(failed(new TeacherCourseServiceException(
                MessageCode.NOT_FOUND, serverMessage)));
        TeacherScheduleController notFoundPage = controller(notFound);
        notFoundPage.activate();
        require(serverMessage.equals(notFoundPage.errorText()),
                "a NOT_FOUND must show the server's own message, saw "
                        + notFoundPage.errorText());

        ControlledService transport = termService();
        transport.weekFutures.add(failed(new IOException("Connection refused")));
        TeacherScheduleController transportPage = controller(transport);
        transportPage.activate();
        require(TeacherScheduleController.LOAD_FAILURE_TEXT.equals(transportPage.errorText()),
                "a transport failure must stay a retryable generic message, saw "
                        + transportPage.errorText());

        ControlledService technical = termService();
        technical.weekFutures.add(failed(new TeacherCourseServiceException(
                MessageCode.ERROR, "缺少响应字段: schedule")));
        TeacherScheduleController technicalPage = controller(technical);
        technicalPage.activate();
        require(TeacherScheduleController.LOAD_FAILURE_TEXT.equals(technicalPage.errorText()),
                "a client-side technical string must never become user-facing copy, saw "
                        + technicalPage.errorText());
    }

    /** 已经选好唯一学期的服务，方便只关心 loadTeachingSchedule 失败形态的用例。 */
    private static ControlledService termService() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(ACADEMIC_YEAR, SPRING));
        return service;
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static Document parseView(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream = TeacherScheduleControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherScheduleControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static boolean containsId(Element parent, String id) {
        NodeList elements = parent.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return true;
        }
        return false;
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

    /** 课表页测试需要的最小教师课程服务：记录请求并返回确定性的整周。 */
    private static final class ControlledService implements TeacherCourseService {
        private List<CourseTermDTO> terms = List.of();
        private TeacherScheduleWeekDTO week;
        private final Deque<CompletableFuture<TeacherScheduleWeekDTO>> weekFutures =
                new ArrayDeque<>();
        private final List<String> scheduleCalls = new ArrayList<>();

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            return CompletableFuture.completedFuture(terms);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            throw new UnsupportedOperationException("the timetable must not list offerings");
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            throw new UnsupportedOperationException("the timetable must not load details");
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            throw new UnsupportedOperationException("the timetable must not load rosters");
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            throw new UnsupportedOperationException("the timetable must not load arrangements");
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            scheduleCalls.add(academicYear + "|" + semester + "|"
                    + (week == null ? "null" : week));
            if (!weekFutures.isEmpty()) return weekFutures.removeFirst();
            return CompletableFuture.completedFuture(this.week);
        }
    }

    /** 详情弹窗测试需要的最小教师课程服务：只回答权威教学班快照。 */
    private static final class DialogService implements TeacherCourseService {
        private TeacherOfferingDetailDTO detail;
        private final Deque<CompletableFuture<TeacherOfferingDetailDTO>> detailFutures =
                new ArrayDeque<>();
        private final List<String> offeringCalls = new ArrayList<>();

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            throw new UnsupportedOperationException("the dialog must not list terms");
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            throw new UnsupportedOperationException("the dialog must not list offerings");
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            offeringCalls.add(offeringId);
            if (!detailFutures.isEmpty()) return detailFutures.removeFirst();
            return CompletableFuture.completedFuture(detail);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            throw new UnsupportedOperationException("the dialog must not load rosters");
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            throw new UnsupportedOperationException("the dialog must not load arrangements");
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            throw new UnsupportedOperationException("the dialog must not load the timetable");
        }
    }
}
