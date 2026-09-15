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

import app.ClientMain;
import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import javafx.event.ActionEvent;
import model.course.teacher.GradeBookEditorModel.Row;
import protocol.MessageCode;
import service.MockTeacherCourseService;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;
import util.PageLeaveGuard;

/**
 * 成绩编辑表页面（设计 §5.4）的无工具包测试：编辑/保存/提交的状态机、离开保护、窗体关闭时
 * 的取消，以及两个新 FXML 的结构契约。
 *
 * <p>重点覆盖界面最容易悄悄做错的三件事：非法或半成品单元格绝不能作为 0 分或缺分发送；
 * 保存失败必须保留用户已经输入的内容；提交要二次确认且重复点击只发一个请求（同一个 operationId）。
 * 另外断言“取消离开”会消费关闭事件，而不只是弹了一个框。
 */
public final class TeacherGradeBookControllerTest {
    private static final String OFFERING = "9007199254740993";
    private static final String PENDING_OFFERING = "9007199254740997";
    private static final String EMPTY_OFFERING = "9007199254740995";
    private static final String GRADE_VIEW = "/resources/fxml/TeacherGradeView.fxml";
    private static final String GRADE_BOOK_VIEW = "/resources/fxml/TeacherGradeBookView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";

    private TeacherGradeBookControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        loadingShowsServerScoresAndKeepsBlanks();
        invalidCellBlocksTheWriteAndKeepsTheTypedText();
        failedSaveKeepsTheEditsAndTheDirtyState();
        submitNeedsASecondClickAndOnlySendsOneRequest();
        incompleteWeightsBlockSubmitWithTheSharedRule();
        cancelledLeaveKeepsThePageAndRefusedCloseIsConsumed();
        allowedLeaveReleasesThePageAndClosesTheWindow();
        releasedPageIgnoresLateResponses();
        readOnlyBookShowsTheReviewStateAndBlocksWrites();
        gradeViewsDeclareTheirControllerIdsAndHandlers();
        everyStyleClassExistsInTheStylesheet();
        System.out.println("TeacherGradeBookControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 加载与编辑

    /** 载入真实 mock 夹具：分数来自服务端、未录入保持空、缺启用项分数的行没有总评。 */
    private static void loadingShowsServerScoresAndKeepsBlanks() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);

        controller.showOffering(OFFERING);

        require(controller.active(), "打开教学班后页面必须处于激活状态");
        require(controller.rows().size() == 24,
                "只加载正常修读的学生（退课历史不参与成绩编辑），收到 "
                        + controller.rows().size());
        require(controller.model().canEdit(), "草稿夹具必须是可编辑的");
        require(controller.tableRowCount() == controller.rows().size(),
                "渲染后表格行数必须与模型一致");
        Row first = controller.rows().get(0);
        require("70".equals(first.cell(GradeComponentCodeDTO.DAILY).text()),
                "分数原文来自服务端快照，收到 " + first.cell(GradeComponentCodeDTO.DAILY).text());
        Row withMissingExperiment = missingExperimentRow(controller);
        require("".equals(withMissingExperiment.cell(GradeComponentCodeDTO.EXPERIMENT).text()),
                "缺分必须保持空白，不能显示 0");
        require(!withMissingExperiment.cell(GradeComponentCodeDTO.EXPERIMENT).entered(),
                "缺分不是“已录入”");
        require(controller.model().rowTotal(withMissingExperiment) == null,
                "缺启用项分数时该行没有总评");
        require(!controller.dirty(), "刚加载的页面不是 dirty");

        TeacherGradeBookController empty = controller(new RecordingService(), message -> true);
        empty.showOffering(EMPTY_OFFERING);
        require(empty.rows().isEmpty(), "空班必须渲染成 0 行");
    }

    /** 非法文本：原文保留、本地挡住写请求、一个字节都不发给服务端。 */
    private static void invalidCellBlocksTheWriteAndKeepsTheTypedText() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);

        controller.model().setScore(controller.rows().get(0).enrollmentId(),
                GradeComponentCodeDTO.DAILY, "88.");
        controller.save();

        require(service.saves.isEmpty(),
                "非法单元格必须让保存一个请求都不发出，收到 " + service.saves.size());
        require("88.".equals(controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "非法文本必须原样留在输入框里");
        require(controller.feedbackText() != null && controller.feedbackText().contains("非法"),
                "非法输入必须给出可读的提示，收到 " + controller.feedbackText());
        require(controller.dirty(), "非法输入仍然是未保存的修改");

        controller.confirmSubmit();
        require(service.submits.isEmpty(), "非法单元格同样挡住提交");
    }

    /** 保存失败保留编辑：内容与 dirty 都不变，只显示失败原因。 */
    private static void failedSaveKeepsTheEditsAndTheDirtyState() {
        RecordingService service = new RecordingService();
        service.saveFailure = new TeacherCourseServiceException(MessageCode.ERROR, "服务端内部错误");
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);
        String enrollmentId = controller.rows().get(0).enrollmentId();
        int revisionBefore = controller.model().revision();

        controller.model().setScore(enrollmentId, GradeComponentCodeDTO.DAILY, "95.5");
        controller.save();

        require(service.saves.size() == 1, "失败的保存确实发出过一次请求");
        require("95.5".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "保存失败后用户输入必须原样保留");
        require(controller.dirty(), "保存失败后仍然是未保存状态");
        require(controller.model().revision() == revisionBefore,
                "保存失败不能推进本地版本");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已保留"),
                "失败提示必须说明修改被保留，收到 " + controller.feedbackText());

        // 重试复用同一个 operationId：响应丢失时服务端会重放，而不是产生第二份写入。
        controller.save();
        require(service.saves.size() == 2 && service.saves.get(0).getOperationId()
                        .equals(service.saves.get(1).getOperationId()),
                "同一次保存流程重试必须复用 operationId");

        // 成功之后编辑内容被服务端快照取代，dirty 清零，且下一次保存用新的 operationId。
        service.saveFailure = null;
        controller.save();
        require(!controller.dirty(), "保存成功后必须回到干净状态");
        require("95.5".equals(
                        controller.rows().get(0).cell(GradeComponentCodeDTO.DAILY).text()),
                "保存成功后显示服务端返回的值");
        controller.model().setScore(enrollmentId, GradeComponentCodeDTO.DAILY, "96");
        controller.save();
        require(!service.saves.get(2).getOperationId()
                        .equals(service.saves.get(3).getOperationId()),
                "保存成功后必须换一个新的 operationId");
    }

    /** 提交：第一次点击只是确认，第二次才发请求；在途期间重复点击只发一个。 */
    private static void submitNeedsASecondClickAndOnlySendsOneRequest() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(OFFERING);
        completeScheme(controller);
        fillEveryScore(controller);

        controller.requestSubmit();
        require(controller.confirmingSubmit(), "第一次点击必须进入确认态");
        require(service.submits.isEmpty(), "确认态不能发出任何请求");

        service.holdSubmit();
        controller.confirmSubmit();
        require(controller.submitting(), "确认后必须处于提交中");
        require(service.submits.size() == 1,
                "确认一次只能发一个提交请求，收到 " + service.submits.size());

        controller.confirmSubmit();
        controller.requestSubmit();
        require(service.submits.size() == 1,
                "提交在途时重复点击不能再发请求，收到 " + service.submits.size());
        require(service.submits.get(0).getOperationId()
                        .equals(controller.pendingSubmitOperationId()),
                "在途提交的 operationId 必须是这一次确认里生成的那一个");

        service.finishSubmit();
        require(!controller.submitting(), "服务端返回后必须结束提交中状态");
        require("PENDING".equals(controller.model().state()),
                "提交成功后状态必须变成待审核，收到 " + controller.model().state());
        require(!controller.model().canEdit(), "提交成功后成绩表必须变成只读");
        require(!controller.dirty(), "提交成功后没有未保存的修改");
        require(controller.pendingSubmitOperationId() == null,
                "提交成功后必须作废这次流程的 operationId");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已提交"),
                "提交成功必须给出反馈，收到 " + controller.feedbackText());

        // 取消确认不发送请求，也不改变编辑内容。
        service.submits.clear();
        TeacherGradeBookController second = controller(new RecordingService(), message -> true);
        second.showOffering(OFFERING);
        completeScheme(second);
        fillEveryScore(second);
        second.requestSubmit();
        second.cancelSubmit();
        require(!second.confirmingSubmit(), "取消后必须退出确认态");
        require(second.pendingSubmitOperationId() == null, "取消必须作废这次确认的幂等 ID");
    }

    /** 权重未配齐：提交被共享计算规则挡住并说明原因，草稿保存不受影响。 */
    private static void incompleteWeightsBlockSubmitWithTheSharedRule() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(EMPTY_OFFERING);

        controller.model().setWeightText(GradeComponentCodeDTO.DAILY, "60");
        controller.requestSubmit();
        controller.confirmSubmit();

        require(service.submits.isEmpty(), "权重未配齐不能发出提交请求");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("权重"),
                "必须说明是权重问题，收到 " + controller.feedbackText());
        require(!controller.confirmingSubmit(), "被本地校验拒绝后必须退出确认态");

        controller.save();
        require(service.saves.size() == 1, "草稿仍然允许保存未配齐的权重");
    }

    // ------------------------------------------------------------------ 离开保护

    /** 有未保存修改时取消离开：守卫返回 false，页面留在原地，关闭事件被消费。 */
    private static void cancelledLeaveKeepsThePageAndRefusedCloseIsConsumed() {
        List<String> asked = new ArrayList<>();
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> {
            asked.add(message);
            return false;
        });
        try {
            controller.showOffering(OFFERING);
            controller.model().setScore(controller.rows().get(0).enrollmentId(),
                    GradeComponentCodeDTO.DAILY, "91");
            require(PageLeaveGuard.active() == controller,
                    "页面成为当前页后必须注册为唯一的活动守卫");

            ActionEvent closeEvent = new ActionEvent();
            ClientMain.requestWindowClose(closeEvent);

            require(closeEvent.isConsumed(),
                    "页面拒绝离开时关闭事件必须被消费掉");
            require(controller.active(), "被拒绝的关闭不能让页面进入关闭状态");
            require(PageLeaveGuard.active() == controller, "被拒绝后守卫仍然有效");
            require(asked.size() == 1 && asked.get(0).contains("未保存"),
                    "必须问过一次并说明未保存的修改，收到 " + asked);

            // 没有修改时不再询问，直接放行。
            controller.model().applyServerSnapshot(service.bookSnapshot(OFFERING));
            require(!controller.dirty(), "快照后应回到干净状态");
            require(controller.requestLeave(), "没有未保存修改时必须直接允许离开");
            require(asked.size() == 1, "干净状态下不应该再弹确认，收到 " + asked);
        } finally {
            PageLeaveGuard.clear();
        }
    }

    /** 同意离开：守卫放行，页面取消在途请求并注销，关闭事件不被消费。 */
    private static void allowedLeaveReleasesThePageAndClosesTheWindow() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        try {
            controller.showOffering(OFFERING);
            controller.model().setScore(controller.rows().get(0).enrollmentId(),
                    GradeComponentCodeDTO.DAILY, "91");

            ActionEvent closeEvent = new ActionEvent();
            ClientMain.requestWindowClose(closeEvent);

            require(!closeEvent.isConsumed(), "允许离开时关闭事件不能被消费");
            require(!controller.active(), "允许离开后页面必须被释放");
            require(PageLeaveGuard.active() == null, "释放后不能留下全局守卫");
        } finally {
            PageLeaveGuard.clear();
        }
    }

    /** 页面被释放后，迟到在途响应不能写界面，也不能把编辑内容换成服务端的旧快照。 */
    private static void releasedPageIgnoresLateResponses() {
        RecordingService service = new RecordingService();
        CompletableFuture<TeacherGradeBookDTO> pending = new CompletableFuture<>();
        service.pendingBook = pending;
        TeacherGradeBookController controller = controller(service, message -> true);

        controller.showOffering(OFFERING);
        controller.release();
        pending.complete(service.bookSnapshot(OFFERING));

        require(controller.model() == null,
                "释放后的迟到响应不能构建编辑模型（页面已不显示）");
        require(!controller.active(), "释放之后页面保持非激活");
    }

    /** 只读状态（待审核）：显示审核状态、禁用两个写入入口，也不注册成可写的编辑页。 */
    private static void readOnlyBookShowsTheReviewStateAndBlocksWrites() {
        RecordingService service = new RecordingService();
        TeacherGradeBookController controller = controller(service, message -> true);
        controller.showOffering(PENDING_OFFERING);

        require(!controller.model().canEdit(), "待审核的教学班必须只读");
        require(controller.model().readOnlyNotice() != null
                        && controller.model().readOnlyNotice().contains("待审核"),
                "只读状态必须显示审核状态，收到 " + controller.model().readOnlyNotice());
        controller.save();
        controller.requestSubmit();
        controller.confirmSubmit();
        require(service.saves.isEmpty() && service.submits.isEmpty(),
                "只读状态下两个写入口都不能发请求");
        require(!controller.dirty(), "只读状态下没有可保存的修改");
    }

    // ------------------------------------------------------------------ 视图契约

    /** 两个新视图：fx:controller、fx:id 与 onAction 全部能在对应控制器上解析。 */
    private static void gradeViewsDeclareTheirControllerIdsAndHandlers() throws Exception {
        Document listView = parseView(GRADE_VIEW);
        require("controller.TeacherGradeController"
                        .equals(listView.getDocumentElement().getAttribute("fx:controller")),
                "成绩列表视图必须由 TeacherGradeController 控制");
        verifyBindings(listView, TeacherGradeController.class, "TeacherGradeController");
        require(elementsWithTag(listView, "TableColumn").size() == 7,
                "成绩列表必须给出 7 列（教学班/课程代码/人数/状态/已录入/缺失/操作）");

        Document bookView = parseView(GRADE_BOOK_VIEW);
        require("controller.TeacherGradeBookController"
                        .equals(bookView.getDocumentElement().getAttribute("fx:controller")),
                "成绩编辑表必须由 TeacherGradeBookController 控制");
        verifyBindings(bookView, TeacherGradeBookController.class, "TeacherGradeBookController");
        require(elementsWithTag(bookView, "TableColumn").size() == 9,
                "成绩编辑表必须包含学号/姓名/四个成绩列/总评/绩点/错误共 9 列");
        for (String text : List.of("平时", "期中", "实验", "期末")) {
            require(columnWithText(bookView, text) != null,
                    "四个成绩列必须始终存在: " + text);
        }
        require(elementsWithTag(bookView, "CheckBox").size() == 4,
                "每一列都要有启用开关");
        require(elementsWithTag(bookView, "TextField").size() == 4,
                "每一列都要有百分比权重输入");
        require(elementWithId(bookView, "gradeBookNoticeLabel") != null,
                "只读状态必须有显示审核意见的位置");
        require(elementWithId(bookView, "gradeBookConfirmSubmitButton") != null
                        && elementWithId(bookView, "gradeBookCancelSubmitButton") != null,
                "提交必须有二次确认与取消入口");
    }

    /** 所有 styleClass 都能在 teacher-course.css 里找到选择器。 */
    private static void everyStyleClassExistsInTheStylesheet() throws Exception {
        String css = readResource(CSS);
        for (String view : List.of(GRADE_VIEW, GRADE_BOOK_VIEW)) {
            Document document = parseView(view);
            NodeList elements = document.getElementsByTagName("*");
            int classes = 0;
            for (int index = 0; index < elements.getLength(); index++) {
                String value = ((Element) elements.item(index)).getAttribute("styleClass");
                if (value.isEmpty()) continue;
                for (String token : value.split(",")) {
                    String name = token.trim();
                    if (name.isEmpty()) continue;
                    classes++;
                    require(css.contains("." + name),
                            view + " 的 styleClass " + name + " 在 teacher-course.css 里没有选择器");
                }
            }
            require(classes > 0, view + " 必须使用 teacher-course.css 的样式类");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherGradeBookController controller(TeacherCourseService service,
            java.util.function.Function<String, Boolean> confirmation) {
        return new TeacherGradeBookController(service, Runnable::run, confirmation);
    }

    /** 把这个夹具的权重配齐成 30/20/20/30（等价于界面上依次输入百分比）。 */
    private static void completeScheme(TeacherGradeBookController controller) {
        controller.model().setWeightText(GradeComponentCodeDTO.DAILY, "30");
        controller.model().setWeightText(GradeComponentCodeDTO.MIDTERM, "20");
        controller.model().setWeightText(GradeComponentCodeDTO.EXPERIMENT, "20");
        controller.model().setWeightText(GradeComponentCodeDTO.FINALTERM, "30");
    }

    private static void fillEveryScore(TeacherGradeBookController controller) {
        for (Row row : controller.rows()) {
            for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
                controller.model().setScore(row.enrollmentId(), code, "85");
            }
        }
    }

    private static Row missingExperimentRow(TeacherGradeBookController controller) {
        for (Row row : controller.rows()) {
            if (!row.cell(GradeComponentCodeDTO.EXPERIMENT).entered()) return row;
        }
        throw new AssertionError("夹具里必须有一行缺实验分");
    }

    private static void verifyBindings(Document view, Class<?> controller, String label) {
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(controller, id) != null,
                        label + " 的 fx:id=\"" + id + "\" 没有对应字段");
            }
            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasActionMethod(controller, action),
                        label + " 的 onAction=\"#" + action + "\" 没有对应处理函数");
            }
            require(!element.hasAttribute("disabled"),
                    label + " 不能使用只读的 disabled 属性，要用 disable");
        }
        require(ids > 0 && actions > 0,
                label + " 必须声明 fx:id 与 onAction，收到 " + ids + " ids / " + actions
                        + " actions");
    }

    private static Document parseView(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream = TeacherGradeBookControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream = TeacherGradeBookControllerTest.class.getResourceAsStream(path)) {
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

    private static Element elementWithId(Document view, String id) {
        NodeList elements = view.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            if (id.equals(element.getAttribute("fx:id"))) return element;
        }
        return null;
    }

    private static Element columnWithText(Document view, String text) {
        for (Element column : elementsWithTag(view, "TableColumn")) {
            if (text.equals(column.getAttribute("text"))) return column;
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

    private static boolean hasActionMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name)) continue;
                if (method.getParameterCount() == 0) return true;
                if (method.getParameterCount() == 1
                        && javafx.event.Event.class.isAssignableFrom(
                                method.getParameterTypes()[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * 记录型成绩服务：读路径全部委托给确定性 mock，写路径可以只记录不落地（默认）或注入失败，
     * 也可以自行控制“何时返回”，用来验证重复点击不会发第二个请求。
     */
    private static final class RecordingService implements TeacherCourseService {
        private final MockTeacherCourseService delegate = new MockTeacherCourseService();
        private final List<WriteGradeBookRequestDTO> saves = new ArrayList<>();
        private final List<WriteGradeBookRequestDTO> submits = new ArrayList<>();
        private final Deque<CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>>
                submitResponses = new ArrayDeque<>();
        private CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> heldSubmit;
        private RuntimeException saveFailure;
        private CompletableFuture<TeacherGradeBookDTO> pendingBook;

        @Override
        public CompletableFuture<List<CourseTermDTO>> listTerms() {
            return delegate.listTerms();
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            return delegate.listOfferings(academicYear, semester, query, page, size);
        }

        @Override
        public CompletableFuture<TeacherOfferingDetailDTO> getOffering(String offeringId) {
            return delegate.getOffering(offeringId);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            return delegate.listOfferingStudents(offeringId, query, enrollmentStatus, page, size);
        }

        @Override
        public CompletableFuture<List<ScheduleArrangementDTO>> listOfferingSchedules(
                String offeringId) {
            return delegate.listOfferingSchedules(offeringId);
        }

        @Override
        public CompletableFuture<TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            return delegate.loadTeachingSchedule(academicYear, semester, week);
        }

        @Override
        public CompletableFuture<TeacherPageDTO<TeacherGradeOfferingDTO>> listGradeOfferings(
                int academicYear, int semester, int page, int size) {
            return delegate.listGradeOfferings(academicYear, semester, page, size);
        }

        @Override
        public CompletableFuture<TeacherGradeBookDTO> getGradeBook(String offeringId) {
            if (pendingBook != null) return pendingBook;
            return delegate.getGradeBook(offeringId);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> saveGradeDraft(
                WriteGradeBookRequestDTO write) {
            saves.add(write);
            if (saveFailure != null) return failed(saveFailure);
            return delegate.saveGradeDraft(write);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> submitGradeBook(
                WriteGradeBookRequestDTO write) {
            submits.add(write);
            if (!submitResponses.isEmpty()) {
                heldSubmit = submitResponses.removeFirst();
                return heldSubmit;
            }
            return delegate.submitGradeBook(write);
        }

        /** 只读快照：用来把页面恢复成“没有未保存修改”的状态。 */
        private TeacherGradeBookDTO bookSnapshot(String offeringId) {
            return delegate.getGradeBook(offeringId).join();
        }

        /**
         * 让下一次提交悬在半空，从而能观察“在途期间的重复点击”。
         *
         * <p>完成时把记录下来的那次请求交给 mock 真正执行：它和服务端一样重新校验权重与缺分，
         * 因此挂起的响应不是伪造的成功结果。
         */
        private void holdSubmit() {
            submitResponses.addLast(new CompletableFuture<>());
        }

        private void finishSubmit() {
            if (heldSubmit == null) throw new AssertionError("没有待完成的提交");
            heldSubmit.complete(delegate.submitGradeBook(submits.get(0)).join());
            heldSubmit = null;
        }

        private static <T> CompletableFuture<T> failed(Throwable failure) {
            CompletableFuture<T> future = new CompletableFuture<>();
            future.completeExceptionally(failure);
            return future;
        }
    }
}
