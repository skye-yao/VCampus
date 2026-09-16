package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import javafx.event.Event;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的成绩更正表单测试：注入假服务与 {@code Runnable::run} 的 FX 执行器，覆盖
 * “显示姓名/学号/原分数、空原因在本地就被拒绝、取消不建草稿、确认建立更正草稿并把拟修改值交回成绩表、
 * 在途只发一次、失败保留填写并复用幂等 ID”。
 *
 * <p>另含视图契约：结构化解析 {@link #VIEW}，确认每个 {@code fx:id}/{@code onAction} 都能在控制器上
 * 找到对应字段/方法，且用到的样式类都在 {@code teacher-course.css} 里——原始文本匹配会被注释骗过。
 */
public final class TeacherGradeCorrectionDialogControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherGradeCorrectionDialog.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String OFFERING = "9007199254740993";
    private static final String SOURCE = "9601";
    private static final String ENROLLMENT = "50031";
    private static final String UID = "00005600";
    private static final String NAME = "学生00";
    private static final String OPERATION_ID = "30000000-0000-0000-0000-000000000001";

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        showsTheStudentAndEveryOriginalScore();
        emptyReasonIsRefusedWithoutAnyServerCall();
        longReasonsAreCappedAtTheServerLimit();
        confirmingBuildsTheRevisionRequestAndHandsBackTheProposedValues();
        cancellingCreatesNoDraftAndBurnsNoOperationId();
        oneConfirmPerClickWhileInFlight();
        aFailedConfirmKeepsTheFormAndReusesTheOperationId();

        viewWiresEveryIdAndAction(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        System.out.println("TeacherGradeCorrectionDialogControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 行为

    /** 姓名、学号与四项原分数都来自打开方注入的当前批次快照；缺项显示占位符，绝不显示 0。 */
    private static void showsTheStudentAndEveryOriginalScore() {
        TeacherGradeCorrectionDialogController controller = controller(new ControlledService());
        Map<GradeComponentCodeDTO, String> values = originals("70", "", "91", "77.5");
        controller.prepare(target(values));

        require(controller.studentLineText().contains(NAME)
                        && controller.studentLineText().contains(UID),
                "the form must name the student it corrects, saw " + controller.studentLineText());
        require(controller.originalLine(GradeComponentCodeDTO.DAILY)
                        .equals("平时　原分数 70"),
                "the original score must be spelled out per component, saw "
                        + controller.originalLine(GradeComponentCodeDTO.DAILY));
        require(controller.originalLine(GradeComponentCodeDTO.MIDTERM)
                        .equals("期中　原分数 —"),
                "a missing score must render as a placeholder, never 0, saw "
                        + controller.originalLine(GradeComponentCodeDTO.MIDTERM));
        require(controller.originalLine(GradeComponentCodeDTO.EXPERIMENT)
                        .equals("实验　原分数 91"),
                "an entered score must render verbatim, saw "
                        + controller.originalLine(GradeComponentCodeDTO.EXPERIMENT));
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            require(controller.proposedText(code).equals(values.get(code)),
                    "the proposed value must start as the current one, saw "
                            + controller.proposedText(code));
        }
        require(!controller.canSubmit(), "an empty reason must not be submittable");
        require(controller.operationId() == null,
                "opening the form must not burn an operation id");
        require(controller.errorText() == null, "opening the form must not show an error");
        require(TeacherGradeCorrectionDialogController.hintText().contains("整个教学班")
                        && TeacherGradeCorrectionDialogController.hintText().contains("保存")
                        && TeacherGradeCorrectionDialogController.hintText().contains("提交"),
                "the hint must say the correction becomes a whole-class version carried by save/submit, saw "
                        + TeacherGradeCorrectionDialogController.hintText());
    }

    /** 空原因与纯空白原因都在本地被挡下：一个请求都不发，并指出缺的是什么。 */
    private static void emptyReasonIsRefusedWithoutAnyServerCall() {
        ControlledService service = new ControlledService();
        TeacherGradeCorrectionDialogController controller = controller(service);
        controller.prepare(target(originals("70", "65", "75", "80")));
        controller.setProposed(GradeComponentCodeDTO.EXPERIMENT, "88");

        controller.submit();

        require(service.corrections.isEmpty(),
                "an empty reason must not reach the server, saw " + service.corrections.size());
        require(TeacherGradeCorrectionDialogController.REASON_REQUIRED_TEXT
                        .equals(controller.errorText()),
                "the form must say what is missing, saw " + controller.errorText());
        require(!controller.canSubmit(), "a blank reason must keep the confirm disabled");

        controller.setReason("   ");
        require(!controller.canSubmit(), "a whitespace-only reason must not count as a reason");
        controller.submit();
        require(service.corrections.isEmpty(),
                "a whitespace-only reason must not reach the server either");
    }

    /** 超长原因按服务端 500 字符的列宽截断，而不是让服务端用一个 400 来发现它。 */
    private static void longReasonsAreCappedAtTheServerLimit() {
        TeacherGradeCorrectionDialogController controller = controller(new ControlledService());
        controller.prepare(target(originals("70", "65", "75", "80")));

        controller.setReason("正".repeat(501));
        require(controller.reason().length() == TeacherGradeCorrectionDialogController
                        .MAX_REASON_LENGTH,
                "an over-long reason must be capped, saw " + controller.reason().length());
        require(controller.canSubmit(), "a capped reason is still a reason");

        controller.setReason("正".repeat(600));
        require(controller.reason().length() == 500,
                "the cap must hold for any longer input, saw " + controller.reason().length());
    }

    /**
     * 确认：请求体是「教学班 + 来源批次 + 期望版本 + 原因」，拟修改的原文原样交回打开方
     * （由它写进成绩编辑表），成功之后表单关闭。
     */
    private static void confirmingBuildsTheRevisionRequestAndHandsBackTheProposedValues() {
        ControlledService service = new ControlledService();
        List<TeacherGradeCorrectionDialogController.CorrectionOutcome> outcomes = new ArrayList<>();
        TeacherGradeCorrectionDialogController controller = controller(service);
        controller.setOnConfirmed(outcomes::add);
        controller.prepare(target(originals("70", "65", "75", "80")));
        controller.setProposed(GradeComponentCodeDTO.EXPERIMENT, "88");
        controller.setProposed(GradeComponentCodeDTO.FINALTERM, "90");
        controller.setReason("  实验分录入有误  ");

        controller.submit();

        require(service.corrections.size() == 1,
                "confirming must send exactly one request, saw " + service.corrections.size());
        StartGradeRevisionRequestDTO request = service.corrections.get(0);
        require(OFFERING.equals(request.getOfferingId())
                        && SOURCE.equals(request.getSourceSubmissionId())
                        && request.getExpectedRevision() == 7,
                "the request must name the offering, the approved source batch and the revision, saw "
                        + request.getOfferingId() + "/" + request.getSourceSubmissionId() + "/"
                        + request.getExpectedRevision());
        require("实验分录入有误".equals(request.getReason()),
                "the reason must travel trimmed, saw " + request.getReason());
        require(request.getOperationId() != null
                        && !request.getOperationId().isBlank(),
                "a confirmed correction must carry an operation id");
        require(controller.submitting(), "the in-flight state must be visible");
        require(outcomes.isEmpty(), "nothing may be handed back before the server answers");

        controller.submit();
        require(service.corrections.size() == 1,
                "a second click while in flight must not send another request, saw "
                        + service.corrections.size());

        service.succeed();
        require(controller.closed(), "a confirmed correction must close the form");
        require(outcomes.size() == 1, "the outcome must be handed to the opener exactly once");
        TeacherGradeCorrectionDialogController.CorrectionOutcome outcome = outcomes.get(0);
        require(outcome.book() != null && ENROLLMENT.equals(outcome.enrollmentId()),
                "the outcome must carry the new draft and the corrected student");
        require("88".equals(outcome.proposed().get(GradeComponentCodeDTO.EXPERIMENT))
                        && "90".equals(outcome.proposed().get(GradeComponentCodeDTO.FINALTERM))
                        && "70".equals(outcome.proposed().get(GradeComponentCodeDTO.DAILY)),
                "every proposed value must be handed back verbatim, saw " + outcome.proposed());
    }

    /** 取消：不建草稿、不发请求、也不消耗 operationId——关闭后的确认同样什么都发不出去。 */
    private static void cancellingCreatesNoDraftAndBurnsNoOperationId() {
        ControlledService service = new ControlledService();
        TeacherGradeCorrectionDialogController controller = controller(service);
        controller.prepare(target(originals("70", "65", "75", "80")));
        controller.setReason("实验分录入有误");

        controller.dispose();

        require(controller.closed(), "cancelling must close the form");
        require(controller.operationId() == null,
                "cancelling must not consume an operation id");
        require(service.corrections.isEmpty(), "cancelling must not create a draft");
        require(!controller.canSubmit(), "a closed form is not submittable");
        controller.submit();
        require(service.corrections.isEmpty(),
                "confirming a closed form must send nothing, saw " + service.corrections.size());
    }

    /** 双击确认只发一次：在途期间按钮不可用，重复触发直接返回。 */
    private static void oneConfirmPerClickWhileInFlight() {
        ControlledService service = new ControlledService();
        TeacherGradeCorrectionDialogController controller = controller(service);
        controller.prepare(target(originals("70", "65", "75", "80")));
        controller.setReason("实验分录入有误");

        controller.submit();
        String first = service.corrections.get(0).getOperationId();
        controller.submit();
        controller.submit();

        require(service.corrections.size() == 1,
                "one confirm must send one request, saw " + service.corrections.size());
        require(first.equals(service.corrections.get(0).getOperationId()),
                "the in-flight request must keep its own operation id");
    }

    /**
     * 失败保留填写内容并复用同一个 operationId（响应丢失时服务端按同 ID 重放）；任何一处填写变化
     * 都必须换新 ID，否则会拿旧 ID 提交新内容，被服务端按摘要冲突永久拒绝。
     */
    private static void aFailedConfirmKeepsTheFormAndReusesTheOperationId() {
        ControlledService service = new ControlledService();
        TeacherGradeCorrectionDialogController controller = controller(service);
        controller.prepare(target(originals("70", "65", "75", "80")));
        controller.setReason("实验分录入有误");

        service.fail(new TeacherCourseServiceException(MessageCode.ERROR, "连接中断"));
        controller.submit();

        require(!controller.closed(), "a failed confirm must keep the form open");
        require(TeacherGradeCorrectionDialogController.SUBMIT_FAILURE_TEXT
                        .equals(controller.errorText()),
                "a transport failure must fall back to the retryable wording, saw "
                        + controller.errorText());
        require("实验分录入有误".equals(controller.reason()),
                "a failed confirm must keep what the teacher typed");
        String first = service.corrections.get(0).getOperationId();

        service.fail(new TeacherCourseServiceException(MessageCode.ERROR, "连接中断"));
        controller.submit();
        require(first.equals(service.corrections.get(1).getOperationId()),
                "retrying the same content must reuse the operation id");

        // 业务拒绝（非 ERROR）原样显示服务端那句话，不换成自己的措辞。
        controller.setReason("实验分录入有误，需更正");
        service.fail(new TeacherCourseServiceException(MessageCode.CONFLICT,
                "成绩草稿已经打开，请先提交或丢弃当前草稿"));
        controller.submit();
        require(!first.equals(service.corrections.get(2).getOperationId()),
                "changing the content must use a new operation id");
        require("成绩草稿已经打开，请先提交或丢弃当前草稿".equals(controller.errorText()),
                "a business refusal must keep the server wording, saw " + controller.errorText());
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewWiresEveryIdAndAction(Document view) {
        require("controller.TeacherGradeCorrectionDialogController"
                        .equals(view.getDocumentElement().getAttribute("fx:controller")),
                "the correction form must be controlled by TeacherGradeCorrectionDialogController");
        NodeList elements = view.getElementsByTagName("*");
        int ids = 0;
        int actions = 0;
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            String id = element.getAttribute("fx:id");
            if (!id.isEmpty()) {
                ids++;
                require(findField(TeacherGradeCorrectionDialogController.class, id) != null,
                        "the correction form's fx:id=\"" + id + "\" has no matching field");
            }
            String action = element.getAttribute("onAction");
            if (action.startsWith("#")) action = action.substring(1);
            if (!action.isEmpty()) {
                actions++;
                require(hasActionMethod(TeacherGradeCorrectionDialogController.class, action),
                        "the correction form's onAction=\"#" + action
                                + "\" has no matching handler");
            }
            require(!element.hasAttribute("disabled"),
                    "the correction form must not use the read-only disabled attribute");
        }
        require(ids > 0 && actions > 0,
                "the correction form must declare fx:id and onAction, saw " + ids + "/" + actions);
        require(view.getElementsByTagName("TextField").getLength() == 4,
                "every component needs exactly one proposed-score input");
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
        require(!styleClasses.isEmpty(), "the form must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static TeacherGradeCorrectionDialogController controller(
            TeacherCourseService service) {
        return new TeacherGradeCorrectionDialogController(service, Runnable::run);
    }

    /** 一个学生的四项原文（按组成顺序），缺项传空串。 */
    private static Map<GradeComponentCodeDTO, String> originals(String daily, String midterm,
            String experiment, String finalterm) {
        Map<GradeComponentCodeDTO, String> values = new LinkedHashMap<>();
        values.put(GradeComponentCodeDTO.DAILY, daily);
        values.put(GradeComponentCodeDTO.MIDTERM, midterm);
        values.put(GradeComponentCodeDTO.EXPERIMENT, experiment);
        values.put(GradeComponentCodeDTO.FINALTERM, finalterm);
        return values;
    }

    /** 更正目标；期望版本固定为 7，便于断言它原样出行。 */
    private static TeacherGradeCorrectionDialogController.CorrectionTarget target(
            Map<GradeComponentCodeDTO, String> originals) {
        return new TeacherGradeCorrectionDialogController.CorrectionTarget(OFFERING, SOURCE, 7,
                ENROLLMENT, UID, NAME, originals);
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherGradeCorrectionDialogControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherGradeCorrectionDialogControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
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

    /**
     * 只实现更正表单用到的那个方法的替身：每个请求返回一个新的未完成 Future 并登记，测试据此制造
     * “在途”“丢失的响应”与“业务拒绝”。其余方法属于别的页面，按仓库约定直接抛
     * {@link UnsupportedOperationException}（接口的 default 实现就是这么写的）。
     */
    private static final class ControlledService implements TeacherCourseService {
        private final List<StartGradeRevisionRequestDTO> corrections = new ArrayList<>();
        private final Deque<CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>>
                queued = new ArrayDeque<>();
        /** 留在半空的那次确认：{@link #succeed()} 完成的就是它。 */
        private CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>> pending;

        private void fail(Throwable failure) {
            queued.addLast(CompletableFuture.failedFuture(failure));
        }

        /** 让下一次确认悬在半空（观察“在途期间的重复点击”）。 */
        private void hold() {
            queued.addLast(new CompletableFuture<>());
        }

        /** 完成悬在半空的那次确认：与服务端一样返回建立好的更正草稿。 */
        private void succeed() {
            if (pending == null) throw new AssertionError("没有待完成的确认");
            pending.complete(new TeacherOperationResultDTO<>(OPERATION_ID, "更正草稿已建立",
                    correctedBook(), false));
            pending = null;
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<TeacherGradeBookDTO>>
                beginGradeCorrection(StartGradeRevisionRequestDTO request) {
            corrections.add(request);
            if (!queued.isEmpty()) {
                pending = queued.removeFirst();
                return pending;
            }
            pending = new CompletableFuture<>();
            return pending;
        }

        @Override
        public CompletableFuture<List<dto.course.CourseTermDTO>> listTerms() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherPageDTO<
                dto.course.teacher.TeacherOfferingDTO>> listOfferings(
                int academicYear, int semester, String query, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherOfferingDetailDTO> getOffering(
                String offeringId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherPageDTO<
                dto.course.teacher.TeacherRosterRowDTO>> listOfferingStudents(
                String offeringId, String query, Integer enrollmentStatus, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<List<dto.course.admin.schedule.ScheduleArrangementDTO>>
                listOfferingSchedules(String offeringId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<dto.course.teacher.TeacherScheduleWeekDTO> loadTeachingSchedule(
                int academicYear, int semester, Integer week) {
            throw new UnsupportedOperationException();
        }
    }

    /** 更正草稿建立后的成绩表：状态回到可编辑的草稿，并带上基础批次与更正原因。 */
    static TeacherGradeBookDTO correctedBook() {
        return new TeacherGradeBookDTO(OFFERING, 8, "digest", "DRAFT", null, List.of(),
                SOURCE, SOURCE, true, "实验分录入有误", false, null);
    }
}
