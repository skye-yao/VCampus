package controller;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import javafx.event.Event;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的调课表单控制器测试：注入假服务与 {@code Runnable::run} 的 FX 执行器，
 * 覆盖“完整表单才预检查、字段变化使旧预览失效、提交期间禁用、双击只提交一次、取消不发送”。
 *
 * <p>另含视图契约：结构化解析 {@link #VIEW}，确认每个 {@code fx:id}/{@code onAction} 都能在控制器上
 * 找到对应字段/方法，且用到的样式类都在 {@code teacher-course.css} 里——原始文本匹配会被注释骗过。
 */
public final class TeacherAdjustmentDialogControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherAdjustmentDialog.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    /** 固定的“今天”：选项里的 2026-09-14 已过去，2026-10-26 与 2026-11-02 还没有。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 15);
    private static final String OFFERING = "9007199254740995";
    private static final String OCCURRENCE = "9203";
    private static final String SAME_WEEK_DATE = "2026-10-26";
    private static final String CROSS_WEEK_DATE = "2026-11-02";

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        loadsOptionsAndMarksPastDatesForGraying();
        targetWeekTextNamesTheOriginalAndTargetWeek();
        previewsOnlyCompleteTargets();
        changingTheTargetInvalidatesTheOldPreview();
        editingTheReasonOnlyRegatesSubmit();
        submitsOnceWhileInFlightAndReusesTheOperationIdAfterAFailure();
        cancelSendsNothing();
        conflictsAreLabelledByTheirServerMessage();
        optionsFailureIsReportedAndRetryable();

        viewWiresEveryIdAndAction(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        System.out.println("TeacherAdjustmentDialogControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 行为

    private static void loadsOptionsAndMarksPastDatesForGraying() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);

        controller.prepare(entry());

        require(service.optionCalls.equals(List.of(OFFERING + "|" + OCCURRENCE)),
                "opening the form must load the options for that occurrence, saw "
                        + service.optionCalls);
        require(controller.options() != null, "a loaded option domain must be retained");
        require(controller.originalText().contains("第 8 周")
                        && controller.originalText().contains("周六")
                        && controller.originalText().contains("2026-10-31")
                        && controller.originalText().contains("第 12-13 节")
                        && controller.originalText().contains("C-301"),
                "the form must spell out the original occurrence, saw "
                        + controller.originalText());
        require(controller.targetWeekText().contains("原周：第 8 周"),
                "the target line must name the original week, saw "
                        + controller.targetWeekText());

        TeacherAdjustmentDialogController.DateChoice past = choice(controller, "2026-09-14");
        TeacherAdjustmentDialogController.DateChoice sameWeek = choice(controller, SAME_WEEK_DATE);
        TeacherAdjustmentDialogController.DateChoice crossWeek = choice(controller, CROSS_WEEK_DATE);
        require(past.past(), "a date before today must be marked for graying, saw " + past);
        require(!sameWeek.past() && !crossWeek.past(),
                "future dates must stay selectable, saw " + sameWeek + " / " + crossWeek);
        require(sameWeek.label().equals("第 8 周 周一 2026-10-26"),
                "a date option must name its teaching week and weekday, saw " + sameWeek.label());
        require(crossWeek.week() == 9, "a date choice must keep its teaching week");
        require(!controller.canSubmit(), "an empty form must not be submittable");
    }

    private static void targetWeekTextNamesTheOriginalAndTargetWeek() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());

        require(controller.targetWeekText().contains("未选择"),
                "before choosing a date the form must say so, saw "
                        + controller.targetWeekText());

        controller.selectDate(CROSS_WEEK_DATE);
        require(controller.targetWeekText().contains("第 9 周")
                        && controller.targetWeekText().contains(CROSS_WEEK_DATE)
                        && controller.targetWeekText().contains("跨周"),
                "a cross-week target must name its week, date and relation, saw "
                        + controller.targetWeekText());

        controller.selectDate(SAME_WEEK_DATE);
        require(controller.targetWeekText().contains("第 8 周")
                        && controller.targetWeekText().contains("同周"),
                "a same-week target must say so, saw " + controller.targetWeekText());
    }

    private static void previewsOnlyCompleteTargets() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());

        controller.selectDate(SAME_WEEK_DATE);
        require(service.previews.isEmpty(),
                "a partial form (no periods) must not trigger a preview, saw " + service.previews);

        controller.selectPeriods(3, 2);
        require(service.previews.isEmpty(),
                "an inverted period range must stay incomplete, saw " + service.previews);

        controller.selectPeriods(1, 2);
        require(service.previews.size() == 1, "a complete target must trigger a preview, saw "
                + service.previews.size());
        TeacherAdjustmentWriteDTO first = service.previews.get(0);
        require(first.getOperationId() == null,
                "the preview must not mint an operation id, saw " + first.getOperationId());
        require(OFFERING.equals(first.getOfferingId())
                        && first.getTargets().size() == 1
                        && OCCURRENCE.equals(first.getTargets().get(0).getOriginalOccurrenceId())
                        && SAME_WEEK_DATE.equals(first.getTargets().get(0).getTargetDate()),
                "the preview must carry the occurrence and the ISO target date");
        require(first.getNewStartPeriod() == 1 && first.getNewEndPeriod() == 2,
                "the preview must carry the selected periods");
        require(first.getNewClassroomId() == null,
                "keeping the original classroom must be sent as null, saw "
                        + first.getNewClassroomId());

        require(controller.availablePeriods().contains(13),
                "the period template must cover the whole day, saw "
                        + controller.availablePeriods());
        require(controller.classroomChoices().size() == 3
                        && controller.classroomChoices().get(0).classroomId() == null,
                "the classroom list must start with keeping the original and then the rooms");
        controller.selectClassroom("8103");
        require(service.previews.size() == 2
                        && "8103".equals(service.previews.get(1).getNewClassroomId()),
                "changing the classroom must re-run the preview with the new classroom");
    }

    private static void changingTheTargetInvalidatesTheOldPreview() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());

        controller.selectDate(CROSS_WEEK_DATE);
        controller.selectPeriods(1, 2);
        service.previewFutures.get(0).complete(new TeacherAdjustmentPreviewDTO(List.of(), true));
        controller.setReason("教师出差");
        require(controller.preview() != null && controller.canSubmit(),
                "a clean preview plus a reason must enable submitting");

        // 改日期：旧“无冲突”必须立刻失效，不能继续启用提交。
        controller.selectDate(SAME_WEEK_DATE);
        require(service.previews.size() == 2,
                "a complete form must re-preview after the date change, saw "
                        + service.previews.size());
        require(controller.preview() == null && !controller.canSubmit(),
                "changing the date must invalidate the previous preview immediately");

        // 再改回跨周：同一日期上的旧响应（对已经改过的表单）绝不能把旧结果塞回来。
        controller.selectDate(CROSS_WEEK_DATE);
        service.previewFutures.get(1).complete(new TeacherAdjustmentPreviewDTO(List.of(), true));
        require(controller.preview() == null && !controller.canSubmit(),
                "a stale preview response must be ignored, saw " + controller.preview());

        // 新预览到达后才重新判定；冲突结果仍然禁止提交。
        service.previewFutures.get(2).complete(new TeacherAdjustmentPreviewDTO(List.of(
                conflict("TEACHER_OVERLAP", "任课教师在该时间已有其他课程",
                        ScheduleConflictSeverityDTO.BLOCKING)), false));
        require(controller.preview() != null && !controller.canSubmit(),
                "a conflicting preview must never enable submitting");
        require(controller.previewLines().size() == 1
                        && controller.previewLines().get(0).contains("任课教师在该时间已有其他课程"),
                "the conflict row must render the server message, saw "
                        + controller.previewLines());
    }

    private static void editingTheReasonOnlyRegatesSubmit() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());
        controller.selectDate(CROSS_WEEK_DATE);
        controller.selectPeriods(1, 2);
        service.previewFutures.get(0).complete(new TeacherAdjustmentPreviewDTO(List.of(), true));

        require(!controller.canSubmit(), "a blank reason must keep submitting disabled");
        controller.setReason("  教师出差  ");
        require(controller.preview() != null,
                "editing the reason must not invalidate the schedule preview");
        require(service.previews.size() == 1,
                "editing the reason must not send another preview request");
        require(controller.canSubmit(),
                "a non-blank reason plus a clean preview must be submittable");

        controller.setReason("   ");
        require(!controller.canSubmit(), "clearing the reason must disable submitting again");
    }

    private static void submitsOnceWhileInFlightAndReusesTheOperationIdAfterAFailure() {
        ControlledService service = new ControlledService();
        List<AdjustmentRequestDetailDTO> submitted = new ArrayList<>();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.setOnSubmitted(submitted::add);
        controller.prepare(entry());
        controller.selectDate(CROSS_WEEK_DATE);
        controller.selectPeriods(1, 2);
        service.previewFutures.get(0).complete(new TeacherAdjustmentPreviewDTO(List.of(), true));
        controller.setReason("教师出差");

        controller.submit();
        require(service.submits.size() == 1 && controller.submitting(),
                "confirming a form must send exactly one submit");
        require(!controller.canSubmit(),
                "the submit button must stay disabled while the request is in flight");
        controller.submit();
        require(service.submits.size() == 1,
                "a second trigger while in flight must not send another submit, saw "
                        + service.submits.size());
        String operationId = service.submits.get(0).getOperationId();
        require(operationId != null && operationId.length() == 36,
                "submitting must mint a canonical operation id, saw " + operationId);

        service.submitFutures.get(0).completeExceptionally(new TeacherCourseServiceException(
                MessageCode.CONFLICT, "存在冲突，无法提交调课申请"));
        require(!controller.submitting() && !controller.closed(),
                "a failed submit must leave the form open for a retry");
        require(controller.errorText() != null && controller.errorText().contains("存在冲突"),
                "a failed submit must surface the server message, saw " + controller.errorText());

        controller.submit();
        require(service.submits.size() == 2
                        && operationId.equals(service.submits.get(1).getOperationId()),
                "retrying the same confirmed form must reuse the operation id, saw "
                        + service.submits.get(1).getOperationId());

        service.submitFutures.get(1).complete(new TeacherOperationResultDTO<>(operationId,
                "调课申请已提交", detail(AdjustmentRequestStatusDTO.PENDING), false));
        require(submitted.size() == 1 && controller.closed(),
                "a successful submit must close the form and report the new application");
    }

    private static void cancelSendsNothing() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());
        controller.selectDate(CROSS_WEEK_DATE);
        controller.selectPeriods(1, 2);
        service.previewFutures.get(0).complete(new TeacherAdjustmentPreviewDTO(List.of(), true));
        controller.setReason("教师出差");

        controller.dispose();

        require(controller.closed(), "closing the form must mark it closed");
        require(service.submits.isEmpty(), "cancelling must not send any submit");
        require(!controller.canSubmit(), "a closed form must never be submittable");
    }

    private static void conflictsAreLabelledByTheirServerMessage() {
        ControlledService service = new ControlledService();
        TeacherAdjustmentDialogController controller = controller(service);
        controller.prepare(entry());
        controller.selectDate(SAME_WEEK_DATE);
        controller.selectPeriods(1, 2);
        service.previewFutures.get(0).complete(new TeacherAdjustmentPreviewDTO(List.of(
                conflict("OFFERING_OVERLAP", "新时间段与同一申请中的其他目标重复",
                        ScheduleConflictSeverityDTO.BLOCKING),
                conflict("OFFERING_OVERLAP", "该课程实例已有待审批的调课申请",
                        ScheduleConflictSeverityDTO.BLOCKING)), false));

        require(controller.previewLines().size() == 2,
                "every conflict must get its own row, saw " + controller.previewLines());
        require(controller.previewLines().get(0).contains("新时间段与同一申请中的其他目标重复")
                        && controller.previewLines().get(1).contains("该课程实例已有待审批的调课申请"),
                "conflicts of the same type must be distinguished by their message, saw "
                        + controller.previewLines());
        require(controller.previewText().contains("2"),
                "the preview header must count the conflicts, saw " + controller.previewText());
    }

    private static void optionsFailureIsReportedAndRetryable() {
        ControlledService service = new ControlledService();
        service.failNextOptions(new TeacherCourseServiceException(
                MessageCode.NOT_FOUND, "该学期暂无已发布的教学日历"));
        TeacherAdjustmentDialogController controller = controller(service);

        controller.prepare(entry());
        require(controller.optionsErrorText() != null
                        && controller.optionsErrorText().contains("该学期暂无已发布的教学日历"),
                "a failed options load must surface the server message, saw "
                        + controller.optionsErrorText());
        require(controller.options() == null, "no options may be retained after a failure");

        controller.retryOptions();
        require(service.optionCalls.size() == 2 && controller.options() != null,
                "retrying must reload the options, saw " + service.optionCalls.size() + " calls");
    }

    // ------------------------------------------------------------------ 夹具

    private static TeacherAdjustmentDialogController controller(ControlledService service) {
        return new TeacherAdjustmentDialogController(service, Runnable::run, () -> TODAY);
    }

    private static TeacherAdjustmentDialogController.DateChoice choice(
            TeacherAdjustmentDialogController controller, String date) {
        for (TeacherAdjustmentDialogController.DateChoice choice : controller.dateChoices()) {
            if (date.equals(choice.date())) return choice;
        }
        throw new AssertionError("missing date choice for " + date);
    }

    private static TeacherScheduleEntryDTO entry() {
        return new TeacherScheduleEntryDTO(OCCURRENCE, OFFERING, "CS301", "操作系统原理", "陈老师",
                "C-301", "2026-10-31", 8, 6, 12, 13, ScheduleDisplayKindDTO.NORMAL, null, null,
                null, null, true);
    }

    private static TeacherAdjustmentOptionsDTO options() {
        List<TeacherCalendarDateDTO> dates = List.of(
                new TeacherCalendarDateDTO("2026-09-14", 2, 1, true),
                new TeacherCalendarDateDTO(SAME_WEEK_DATE, 8, 1, true),
                new TeacherCalendarDateDTO(CROSS_WEEK_DATE, 9, 1, true));
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        for (TeacherCalendarDateDTO date : dates) {
            for (int period = 1; period <= 13; period++) {
                periods.add(new TeacherPeriodDTO(date.getDate(), period, "08:00:00", "08:45:00"));
            }
        }
        return new TeacherAdjustmentOptionsDTO("C9000001", "Asia/Shanghai", dates, periods,
                List.of(new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                        new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60)));
    }

    private static ScheduleConflictDTO conflict(String type, String message,
            ScheduleConflictSeverityDTO severity) {
        return new ScheduleConflictDTO(type, severity, OCCURRENCE, OFFERING, 8, 1, 1, 2, message);
    }

    private static AdjustmentRequestDetailDTO detail(AdjustmentRequestStatusDTO status) {
        return new AdjustmentRequestDetailDTO("9406", OFFERING, "00001234", "教师出差", status, 1, 1,
                1, 2, null, null, null,
                List.of(new AdjustmentTargetDTO(OCCURRENCE, 8, "2026-10-31T04:00:00Z",
                        "2026-10-31T05:35:00Z", "陈老师", null, "C-301", CROSS_WEEK_DATE)),
                List.of(), "2026-09-14T08:00:00Z", null, null, null);
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewWiresEveryIdAndAction(Document view) {
        Class<?> controller = TeacherAdjustmentDialogController.class;
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
                "the form must actually declare fx:id and onAction bindings, saw "
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
        require(!styleClasses.isEmpty(), "the form must use style classes from teacher-course.css");
        for (String name : styleClasses) {
            require(css.contains("." + name),
                    "styleClass " + name + " has no ." + name
                            + " selector in teacher-course.css");
        }
    }

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherAdjustmentDialogControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherAdjustmentDialogControllerTest.class.getResourceAsStream(path)) {
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
     * 可控的教师课程服务替身：只实现调课表单用到的三个方法，其余方法属于其它页面。
     * 每个请求都返回一个新的未完成 Future 并登记，测试据此制造“在途”与“迟到的旧响应”。
     */
    private static final class ControlledService implements TeacherCourseService {
        private final List<CompletableFuture<TeacherAdjustmentOptionsDTO>> optionPages =
                new ArrayList<>();
        private final List<String> optionCalls = new ArrayList<>();
        private final List<TeacherAdjustmentWriteDTO> previews = new ArrayList<>();
        private final List<CompletableFuture<TeacherAdjustmentPreviewDTO>> previewFutures =
                new ArrayList<>();
        private final List<TeacherAdjustmentWriteDTO> submits = new ArrayList<>();
        private final List<CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>>
                submitFutures = new ArrayList<>();
        private int nextOption = 0;

        private void failNextOptions(Throwable failure) {
            optionPages.add(CompletableFuture.failedFuture(failure));
        }

        @Override
        public CompletableFuture<TeacherAdjustmentOptionsDTO> getAdjustmentOptions(
                String offeringId, String originalOccurrenceId) {
            optionCalls.add(offeringId + "|" + originalOccurrenceId);
            if (nextOption < optionPages.size()) return optionPages.get(nextOption++);
            return CompletableFuture.completedFuture(options());
        }

        @Override
        public CompletableFuture<TeacherAdjustmentPreviewDTO> previewAdjustment(
                TeacherAdjustmentWriteDTO request) {
            previews.add(request);
            CompletableFuture<TeacherAdjustmentPreviewDTO> future = new CompletableFuture<>();
            previewFutures.add(future);
            return future;
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
                submitAdjustment(TeacherAdjustmentWriteDTO request) {
            submits.add(request);
            CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>> future =
                    new CompletableFuture<>();
            submitFutures.add(future);
            return future;
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
}
