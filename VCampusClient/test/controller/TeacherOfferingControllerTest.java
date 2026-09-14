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
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import javafx.event.ActionEvent;
import javafx.event.Event;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的教学班列表页测试。
 *
 * <p>注入假服务、{@code Runnable::run} 的 FX 执行器与 {@code null} 的 FXML 节点：控制器必须在
 * 没有真实控件的环境下也能走完“学期 → 列表 → 分页 → 打开详情”的全部逻辑。服务返回的行文本与
 * 列顺序由纯函数给出，因此可以在没有运行时的环境里断言；视图契约用 {@link DocumentBuilderFactory}
 * 结构化解析 FXML（真实元素上的属性与 {@code fx:id}/{@code onAction} 绑定），而不是匹配原始文本。
 */
public final class TeacherOfferingControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherOfferingView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String FULL_ROSTER = "9007199254740993";
    private static final String EMPTY_CLASS = "9007199254740995";

    private TeacherOfferingControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        defaultTermIsTheFirstServerTermAndLoadsPageOne();
        searchResetsToPageOneAndIsSentToTheService();
        switchingTermReloadsPageOneForTheNewTerm();
        staleSearchResponseCannotReplaceTheNewerResult();
        listFailureKeepsTheRowsAlreadyShownAndOffersRetry();
        paginationWalksPastTheFirstPage();
        hiddenPageIgnoresLateResponsesButKeepsTermQueryAndPage();
        detailRowOpensTheOffering();
        cellsAndStatusTextComeFromTheDto();
        viewIsTheOfferingListPage(parseView());
        System.out.println("TeacherOfferingControllerTest: PASS");
    }

    private static void defaultTermIsTheFirstServerTermAndLoadsPageOne() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3), term(2025, 2));
        service.offeringPage = offeringPage(2, 1, 20,
                offering(FULL_ROSTER, "CS203-01"), offering(EMPTY_CLASS, "CS301-01"));
        TeacherOfferingController controller = controller(service);

        controller.activate();

        require(controller.term() != null && controller.term().getAcademicYear() == 2025
                        && controller.term().getSemester() == 3,
                "the first server term must be selected by default, saw " + controller.term());
        require(service.offeringCalls.equals(List.of("2025|3||1|20")),
                "the default term must be queried on page 1 with an empty query, saw "
                        + service.offeringCalls);
        require(controller.offerings().size() == 2 && controller.totalCount() == 2
                        && !controller.loading() && controller.errorText() == null,
                "the first page must be rendered without an error state");
        require(controller.active(), "an activated page must accept its own responses");
    }

    private static void searchResetsToPageOneAndIsSentToTheService() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(40, 1, 20, offering(FULL_ROSTER, "CS203-01"),
                offering(EMPTY_CLASS, "CS301-01"));
        TeacherOfferingController controller = controller(service);
        controller.activate();
        controller.goToPage(2);

        controller.applyFilters("数据结构");

        require(service.offeringCalls.equals(List.of("2025|3||1|20", "2025|3||2|20",
                        "2025|3|数据结构|1|20")),
                "a new search must be re-sent from page 1, saw " + service.offeringCalls);
        require(controller.query().equals("数据结构") && controller.page() == 1,
                "the controller must remember the applied search");
    }

    private static void switchingTermReloadsPageOneForTheNewTerm() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3), term(2025, 2));
        service.offeringPage = offeringPage(1, 1, 20, offering(FULL_ROSTER, "CS203-01"));
        TeacherOfferingController controller = controller(service);
        controller.activate();

        controller.selectTerm(1);

        require(controller.term() != null && controller.term().getSemester() == 2,
                "the selected term must be remembered, saw " + controller.term());
        require(service.offeringCalls.equals(List.of("2025|3||1|20", "2025|2||1|20")),
                "a term switch must reload page 1 of the new term, saw " + service.offeringCalls);
        require(controller.query().isEmpty() && controller.page() == 1,
                "a term switch must keep the search text and start from page 1");
    }

    private static void staleSearchResponseCannotReplaceTheNewerResult() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(0, 1, 20);
        TeacherOfferingController controller = controller(service);
        controller.activate();

        CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> older = new CompletableFuture<>();
        CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> newer = new CompletableFuture<>();
        service.offeringPages.add(older);
        service.offeringPages.add(newer);

        controller.applyFilters("A");
        controller.applyFilters("B");
        newer.complete(offeringPage(1, 1, 20, offering("9007199254740991", "B-01")));
        require(controller.offerings().size() == 1
                        && "9007199254740991".equals(controller.offerings().get(0).getOfferingId()),
                "the newer response must be rendered");
        older.complete(offeringPage(1, 1, 20, offering("9007199254740992", "A-01")));
        require(controller.offerings().size() == 1
                        && "9007199254740991".equals(controller.offerings().get(0).getOfferingId()),
                "a stale response must never replace the newer result, saw "
                        + controller.offerings());
        require(controller.query().equals("B") && controller.errorText() == null,
                "the stale response must not disturb the applied filter or the error state");
    }

    private static void listFailureKeepsTheRowsAlreadyShownAndOffersRetry() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(2, 1, 20, offering(FULL_ROSTER, "CS203-01"),
                offering(EMPTY_CLASS, "CS301-01"));
        TeacherOfferingController controller = controller(service);
        controller.activate();

        service.offeringPages.add(failed(new IllegalStateException("数据库不可用")));
        controller.refresh();

        require(controller.offerings().size() == 2,
                "a failed reload must keep the rows already displayed, saw "
                        + controller.offerings().size());
        require(TeacherOfferingController.LOAD_FAILURE_TEXT.equals(controller.errorText()),
                "a failed reload must surface the retryable error text, saw "
                        + controller.errorText());
        require(!controller.loading(), "a failed reload must leave the loading state");

        controller.refresh();
        require(controller.errorText() == null && controller.offerings().size() == 2,
                "a successful retry must clear the error text");
    }

    private static void paginationWalksPastTheFirstPage() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(27, 1, 20, offering(FULL_ROSTER, "CS203-01"));
        TeacherOfferingController controller = controller(service);
        controller.activate();

        require(controller.totalPages() == 2 && !controller.hasPreviousPage()
                        && controller.hasNextPage(),
                "27 rows at 20 per page must be two pages starting on the first");

        service.offeringPage = offeringPage(27, 2, 20, offering(FULL_ROSTER, "CS203-01"));
        controller.handleNextPage(new ActionEvent());

        require(controller.page() == 2 && controller.hasPreviousPage()
                        && !controller.hasNextPage(),
                "the second page must be the last page");
        require(service.offeringCalls.get(1).equals("2025|3||2|20"),
                "paging must query the requested page at the fixed page size, saw "
                        + service.offeringCalls);

        controller.handlePreviousPage(new ActionEvent());
        require(controller.page() == 1, "previous page must walk back to page 1");
        require(controller.totalCount() == 27,
                "the total row count is the filtered total, not the page length, saw "
                        + controller.totalCount());

        service.offeringPages.add(failed(new IllegalStateException("数据库不可用")));
        controller.goToPage(3);
        require(controller.page() == 1 && controller.offerings().size() == 1
                        && TeacherOfferingController.LOAD_FAILURE_TEXT.equals(controller.errorText()),
                "a failed page load must keep the displayed page and rows together, saw page="
                        + controller.page() + " rows=" + controller.offerings().size());
    }

    private static void hiddenPageIgnoresLateResponsesButKeepsTermQueryAndPage() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(27, 2, 20, offering(FULL_ROSTER, "CS203-01"));
        TeacherOfferingController controller = controller(service);
        controller.activate();
        controller.applyFilters("CS");
        controller.goToPage(2);

        CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> late = new CompletableFuture<>();
        service.offeringPages.add(late);
        controller.refresh();
        controller.unload();

        require(!controller.active(), "an unloaded page must stop accepting responses");
        late.complete(offeringPage(1, 2, 20, offering("9007199254740994", "LATE-01")));
        require(controller.offerings().size() == 1
                        && FULL_ROSTER.equals(controller.offerings().get(0).getOfferingId()),
                "a response arriving after the page was unloaded must be ignored, saw "
                        + controller.offerings());

        controller.activate();

        require(controller.query().equals("CS") && controller.page() == 2
                        && controller.term().getSemester() == 3,
                "re-activating must preserve the selected term, search text and page");
        require(service.offeringCalls.get(service.offeringCalls.size() - 1).equals(
                        "2025|3|CS|2|20"),
                "re-activating must reload the preserved query and page, saw "
                        + service.offeringCalls);
    }

    private static void detailRowOpensTheOffering() {
        ControlledService service = new ControlledService();
        service.terms = List.of(term(2025, 3));
        service.offeringPage = offeringPage(1, 1, 20, offering(FULL_ROSTER, "CS203-01"));
        TeacherOfferingController controller = controller(service);
        List<String> opened = new ArrayList<>();
        controller.setOnShowOffering(opened::add);
        controller.activate();

        controller.showOffering(controller.offerings().get(0));

        require(opened.equals(List.of(FULL_ROSTER)),
                "opening a row must route its offering id, saw " + opened);

        controller.showOffering(null);
        require(opened.size() == 1, "a null row must not route anything");
    }

    private static void cellsAndStatusTextComeFromTheDto() {
        TeacherOfferingDTO offering = offering(FULL_ROSTER, "CS203-01", "数据结构 CS203-01",
                "2001", "CS203", "数据结构与算法基础", 4.0, 2025, 3, 27, 30, "OPEN");
        require(TeacherOfferingController.offeringCells(offering).equals(List.of(
                        "数据结构 CS203-01", "CS203", "4.0", "27/30", "开放中")),
                "the row cells must follow the DTO and the column order, saw "
                        + TeacherOfferingController.offeringCells(offering));
        require(TeacherOfferingController.offeringCells(
                        offering(EMPTY_CLASS, "CS301-01", "操作系统 CS301-01", "2002", "CS301",
                                "操作系统原理", 3.5, 2025, 3, 0, 40, "STOPPED"))
                        .equals(List.of("操作系统 CS301-01", "CS301", "3.5", "0/40", "已停止")),
                "an empty class must show 0/40 and its own status label");
        require(TeacherOfferingController.offeringStatusText("NOT_OPEN").equals("未开放")
                        && TeacherOfferingController.offeringStatusText("OPEN").equals("开放中")
                        && TeacherOfferingController.offeringStatusText("STOPPED").equals("已停止")
                        && TeacherOfferingController.offeringStatusText("CANCELLED").equals("已取消")
                        && TeacherOfferingController.offeringStatusText(null).equals("未知"),
                "every server status value must have a stable Chinese label");
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewIsTheOfferingListPage(Document view) throws Exception {
        Element root = view.getDocumentElement();
        require("controller.TeacherOfferingController".equals(root.getAttribute("fx:controller")),
                "the view must be controlled by TeacherOfferingController, saw "
                        + root.getAttribute("fx:controller"));

        everyFxIdAndOnActionResolvesOnTheController(view);
        noElementUsesTheReadOnlyDisabledAttribute(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        require(tableWithId(view, "offeringTable") != null,
                "the list page must expose the offering table");
        require(elementWithId(view, "termFilter") != null,
                "the list page must expose the term filter required by §5.1");
        require(elementWithId(view, "nextPageButton") != null
                        && elementWithId(view, "previousPageButton") != null,
                "the list page must expose its paging buttons");
    }

    private static void everyFxIdAndOnActionResolvesOnTheController(Document view) {
        Class<?> controller = TeacherOfferingController.class;
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

    private static TeacherOfferingController controller(TeacherCourseService service) {
        return new TeacherOfferingController(service, Runnable::run);
    }

    private static CourseTermDTO term(int academicYear, int semester) {
        return new CourseTermDTO(academicYear, semester, academicYear + " 学年 第 " + semester + " 学期");
    }

    private static TeacherOfferingDTO offering(String offeringId, String offeringCode) {
        return offering(offeringId, offeringCode, "课程 " + offeringCode, "2001", "C001",
                "课程名称", 3.0, 2025, 3, 0, 30, "OPEN");
    }

    private static TeacherOfferingDTO offering(String offeringId, String offeringCode,
            String offeringName, String courseId, String courseCode, String courseName,
            double credit, int academicYear, int semester, int enrolledCount, int capacity,
            String status) {
        return new TeacherOfferingDTO(offeringId, offeringCode, offeringName, courseId, courseCode,
                courseName, credit, academicYear, semester, enrolledCount, capacity, status,
                true, true);
    }

    @SafeVarargs
    private static TeacherPageDTO<TeacherOfferingDTO> offeringPage(long totalCount, int page,
            int size, TeacherOfferingDTO... items) {
        return new TeacherPageDTO<>(List.of(items), totalCount, page, size);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherOfferingControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherOfferingControllerTest.class.getResourceAsStream(path)) {
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

    private static Element tableWithId(Document view, String id) {
        NodeList tables = view.getElementsByTagName("TableView");
        for (int index = 0; index < tables.getLength(); index++) {
            Element table = (Element) tables.item(index);
            if (id.equals(table.getAttribute("fx:id"))) return table;
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
        private List<CourseTermDTO> terms = List.of();
        private TeacherPageDTO<TeacherOfferingDTO> offeringPage;
        private final Deque<CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>>> offeringPages =
                new ArrayDeque<>();
        private final List<String> offeringCalls = new ArrayList<>();

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            return CompletableFuture.completedFuture(terms);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            offeringCalls.add(academicYear + "|" + semester + "|" + (query == null ? "" : query)
                    + "|" + page + "|" + size);
            if (!offeringPages.isEmpty()) return offeringPages.removeFirst();
            return CompletableFuture.completedFuture(offeringPage == null
                    ? new TeacherPageDTO<>(List.of(), 0, page, size) : offeringPage);
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            throw new UnsupportedOperationException("the list page must not load details");
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            throw new UnsupportedOperationException("the list page must not load rosters");
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            throw new UnsupportedOperationException("the list page must not load schedules");
        }
    }
}
