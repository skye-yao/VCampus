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
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import javafx.event.Event;
import protocol.MessageCode;
import service.SocketTeacherCourseService.TeacherCourseServiceException;
import service.TeacherCourseService;

/**
 * 无 JavaFX 工具包依赖的“我的调课申请”控制器测试：状态筛选、分页、详情展示（真实目标日期、
 * 教师/助教回落到目标原快照）、两步确认撤销、审批后撤销返回 CONFLICT 并刷新，以及
 * NOT_FOUND 绝不显示成系统错误。
 *
 * <p>另含视图契约：结构化解析 {@link #VIEW}，确认 {@code fx:id}/{@code onAction} 都能在控制器上
 * 解析、样式类都在 {@code teacher-course.css} 里。
 */
public final class TeacherApplicationsControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherApplicationsView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String INTERACTION_OFFERING = "9007199254740997";

    public static void main(String[] args) throws Exception {
        Document view = parseView();

        defaultFilterLoadsPendingApplicationsAndRendersRows();
        statusFilterMapsAllFourStates();
        selectingARowLoadsAndRendersTheDetailWithRealTargetDates();
        legacyTargetsWithoutADateKeepTheOldDisplay();
        targetLineFallsBackPerFieldWhenOnlyOnePersonChanges();
        pendingCanWithdrawButTerminalCannot();
        withdrawSendsTheDetailVersionAndRefreshes();
        withdrawCannotBeSentTwiceWhileInFlightOrAfterCancelling();
        withdrawAfterApprovalConflictsAndRefreshes();
        withdrawNotFoundIsNotRenderedAsASystemError();
        staleListCannotReplaceNewerResult();
        listFailureSurfacesTheError();

        viewWiresEveryIdAndAction(view);
        everyStyleClassExistsInTheStylesheet(view, readResource(CSS));
        System.out.println("TeacherApplicationsControllerTest: PASS");
    }

    // ------------------------------------------------------------------ 列表

    private static void defaultFilterLoadsPendingApplicationsAndRendersRows() {
        ControlledService service = new ControlledService();
        service.page = page(AdjustmentRequestStatusDTO.PENDING, 1, 20, summary("9405"));
        TeacherApplicationsController controller = controller(service);

        controller.activate();

        require(service.listCalls.equals(List.of("PENDING|1|20")),
                "the first page must query the pending applications, saw " + service.listCalls);
        require(controller.applications().size() == 1 && controller.totalCount() == 1
                        && !controller.loading() && controller.errorText() == null,
                "the loaded page must be rendered without an error state");
        require("人机交互导论　CS352-01"
                        .equals(TeacherApplicationsController.summaryTitle(
                                controller.applications().get(0))),
                "a row must name the course and the offering");
        String meta = TeacherApplicationsController.summaryMeta(controller.applications().get(0));
        require(meta.contains("9405") && meta.contains("待审批")
                        && meta.contains("2026-09-14T07:00:00Z"),
                "a row must show the request id, status and submitted time, saw " + meta);
        require(!controller.canWithdraw(),
                "without a selected request no withdrawal may be offered");
    }

    private static void statusFilterMapsAllFourStates() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);

        controller.applyStatus("已撤销");
        require("WITHDRAWN|1|20".equals(service.listCalls.get(0)),
                "choosing 已撤销 must query that status, saw " + service.listCalls);
        controller.applyStatus("已撤销");
        require(service.listCalls.size() == 1,
                "re-choosing the active status must not reload");
        controller.applyStatus("已通过");
        controller.applyStatus("已驳回");
        require("APPROVED|1|20".equals(service.listCalls.get(1))
                        && "REJECTED|1|20".equals(service.listCalls.get(2)),
                "every terminal filter must reach the service, saw " + service.listCalls);
        require(controller.status() == AdjustmentRequestStatusDTO.REJECTED,
                "the controller must remember the active filter");
    }

    // ------------------------------------------------------------------ 详情

    private static void selectingARowLoadsAndRendersTheDetailWithRealTargetDates() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();

        controller.select("9405");

        require(service.detailCalls.equals(List.of("9405")),
                "selecting a row must load exactly that request, saw " + service.detailCalls);
        require(controller.detail() != null
                        && controller.detail().getStatus() == AdjustmentRequestStatusDTO.PENDING,
                "the selected detail must be retained");
        List<String> lines = controller.renderedDetailLines();
        require(lines.stream().anyMatch(line -> line.startsWith(
                        "目标安排：2026-11-06 周五 第 5-6 节")),
                "the target arrangement must show the real ISO date instead of only the weekday, saw "
                        + lines);
        require(lines.stream().anyMatch(line -> line.startsWith(
                        "目标安排：2026-11-06 周五 第 5-6 节")
                        && line.contains("陈老师, 王助教") && line.contains("A-101")),
                "a teacher request has no new teacher/classroom, so the target snapshot must be "
                        + "shown instead, saw " + lines);
        require(lines.stream().anyMatch(line -> line.startsWith("原安排（第 8 周）")
                        && line.contains("2026-10-27T00:00:00Z~2026-10-27T01:35:00Z")),
                "the original arrangement must name its week and snapshot, saw " + lines);
        require(lines.stream().anyMatch(
                        line -> line.contains("该课程实例已有待审批的调课申请")),
                "the live PENDING conflict snapshot must be rendered, saw " + lines);
    }

    private static void legacyTargetsWithoutADateKeepTheOldDisplay() {
        ControlledService service = new ControlledService();
        service.detail = legacyDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9403");

        List<String> lines = controller.renderedDetailLines();
        String target = lines.stream().filter(line -> line.startsWith("目标安排：")).findFirst()
                .orElseThrow(() -> new AssertionError("missing target line, saw " + lines));
        require(target.contains("周五 第 7-8 节") && !target.contains("20"),
                "a legacy target without a date must keep the weekday-only display, saw " + target);
        require(lines.stream().anyMatch(line -> line.contains("材料不全")),
                "the review comment must be rendered for a terminal request, saw " + lines);
    }

    /**
     * 终审修复：服务端按字段写入替换资源，我的申请页也必须逐字段回落——只换教师时保留原助教、
     * 只换助教时保留原教师、两个都换时都显示新资源、都不换时保持原快照。
     */
    private static void targetLineFallsBackPerFieldWhenOnlyOnePersonChanges() {
        AdjustmentTargetDTO target = new AdjustmentTargetDTO("9203", 8, "2026-10-27T00:00:00Z",
                "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06");

        String teacherOnly = TeacherApplicationsController.targetLine(
                mixedDetail(resource("T2001", "李老师", "teacher"), null, null), target);
        require(teacherOnly.contains("李老师, 王助教"),
                "a teacher-only replacement must keep the original assistant, saw " + teacherOnly);

        String assistantOnly = TeacherApplicationsController.targetLine(
                mixedDetail(null, resource("T3001", "赵助教", "teacher"), null), target);
        require(assistantOnly.contains("陈老师, 赵助教"),
                "an assistant-only replacement must keep the original teacher, saw "
                        + assistantOnly);

        String both = TeacherApplicationsController.targetLine(
                mixedDetail(resource("T2001", "李老师", "teacher"),
                        resource("T3001", "赵助教", "teacher"), null), target);
        require(both.contains("李老师, 赵助教"),
                "a full replacement must show both new persons, saw " + both);

        String unchanged = TeacherApplicationsController.targetLine(
                mixedDetail(null, null, null), target);
        require(unchanged.contains("陈老师, 王助教") && unchanged.contains("A-101"),
                "an unreplaced request must keep the target snapshot, saw " + unchanged);
    }

    // ------------------------------------------------------------------ 撤销

    private static void pendingCanWithdrawButTerminalCannot() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9405");
        require(controller.canWithdraw(),
                "a PENDING application must offer the withdrawal");

        service.details.addLast(CompletableFuture.completedFuture(
                approvedDetail(AdjustmentRequestStatusDTO.APPROVED)));
        controller.select("9405");
        require(!controller.canWithdraw(),
                "an approved application is read-only and must not offer the withdrawal");

        service.details.addLast(CompletableFuture.completedFuture(
                approvedDetail(AdjustmentRequestStatusDTO.WITHDRAWN)));
        controller.select("9405");
        require(!controller.canWithdraw(),
                "an already withdrawn application must not offer the withdrawal again");
    }

    private static void withdrawSendsTheDetailVersionAndRefreshes() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9405");

        controller.requestWithdraw();
        require(controller.confirmingWithdraw(),
                "the first click must only arm the confirmation");
        require(service.withdrawals.isEmpty(), "arming must not send anything");
        CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>> inFlight =
                new CompletableFuture<>();
        service.withdrawals.addLast(inFlight);
        controller.confirmWithdraw();

        require(service.withdraws.size() == 1,
                "confirming must send exactly one withdrawal, saw " + service.withdraws.size());
        WithdrawTeacherAdjustmentRequestDTO request = service.withdraws.get(0);
        require("9405".equals(request.getRequestId()) && request.getExpectedVersion() == 1,
                "the withdrawal must carry the detail identity and version, saw "
                        + request.getRequestId() + " v" + request.getExpectedVersion());
        require(request.getOperationId() != null && request.getOperationId().length() == 36,
                "the withdrawal must mint a canonical operation id, saw "
                        + request.getOperationId());
        require(controller.submitting() && !controller.canWithdraw(),
                "the withdrawal must disable itself while in flight");

        inFlight.complete(new TeacherOperationResultDTO<>(
                request.getOperationId(), "调课申请已撤销",
                approvedDetail(AdjustmentRequestStatusDTO.WITHDRAWN), false));

        require(controller.detail() != null
                        && controller.detail().getStatus() == AdjustmentRequestStatusDTO.WITHDRAWN
                        && controller.detail().getVersion() == 2,
                "a successful withdrawal must render the refreshed detail, saw "
                        + controller.detail());
        require(!controller.submitting() && !controller.confirmingWithdraw(),
                "the withdrawal must be finished");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("已撤销"),
                "the page must report the withdrawal, saw " + controller.feedbackText());
        require(service.listCalls.size() == 2,
                "a successful write must re-query the list, saw " + service.listCalls);
    }

    private static void withdrawCannotBeSentTwiceWhileInFlightOrAfterCancelling() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9405");

        controller.requestWithdraw();
        controller.cancelWithdraw();
        require(!controller.confirmingWithdraw() && service.withdrawals.isEmpty(),
                "cancelling the confirmation must not send anything");

        service.withdrawals.addLast(new CompletableFuture<>());
        controller.requestWithdraw();
        controller.confirmWithdraw();
        controller.confirmWithdraw();
        require(service.withdraws.size() == 1,
                "a second confirmation while in flight must not send another withdrawal, saw "
                        + service.withdraws.size());
    }

    private static void withdrawAfterApprovalConflictsAndRefreshes() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9405");

        AdjustmentRequestDetailDTO approved = approvedDetail(AdjustmentRequestStatusDTO.APPROVED);
        service.withdrawals.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.CONFLICT,
                        "调课申请已被处理，请刷新后重试", List.of(), approved)));
        controller.requestWithdraw();
        controller.confirmWithdraw();

        require(controller.detail() != null
                        && controller.detail().getStatus() == AdjustmentRequestStatusDTO.APPROVED,
                "a conflict must render the latest server state, saw " + controller.detail());
        require(!controller.canWithdraw(),
                "the approval is terminal, so the withdrawal must be gone");
        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("调课申请已被处理"),
                "a conflict must report the server message, saw " + controller.feedbackText());
        require(service.listCalls.size() == 2,
                "a conflict must also re-query the list, saw " + service.listCalls);
    }

    private static void withdrawNotFoundIsNotRenderedAsASystemError() {
        ControlledService service = new ControlledService();
        service.detail = pendingDetail();
        TeacherApplicationsController controller = controller(service);
        controller.activate();
        controller.select("9405");

        service.withdrawals.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.NOT_FOUND, "调课申请不存在")));
        controller.requestWithdraw();
        controller.confirmWithdraw();

        require(controller.feedbackText() != null
                        && controller.feedbackText().contains("不存在"),
                "a missing request must read as a friendly state, saw "
                        + controller.feedbackText());
        require(!controller.feedbackText().contains("系统错误")
                        && !controller.feedbackText().contains("ERROR"),
                "NOT_FOUND must not be rendered as a system error, saw "
                        + controller.feedbackText());
        require(controller.detail() == null && controller.errorText() == null,
                "a missing request must clear the selection without an error banner");
        require(service.listCalls.size() == 2,
                "the list must be re-queried after a vanished request, saw " + service.listCalls);
    }

    // ------------------------------------------------------------------ 失败与竞态

    private static void staleListCannotReplaceNewerResult() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);

        CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>> older =
                new CompletableFuture<>();
        CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>> newer =
                new CompletableFuture<>();
        service.pages.addLast(older);
        service.pages.addLast(newer);
        controller.activate();
        controller.applyStatus("已通过");

        older.complete(page(AdjustmentRequestStatusDTO.PENDING, 1, 20, summary("9405")));
        newer.complete(page(AdjustmentRequestStatusDTO.APPROVED, 1, 20, summary("9401")));

        require(controller.applications().size() == 1
                        && "9401".equals(controller.applications().get(0).getRequestId()),
                "a stale page must not replace the newer result, saw " + controller.applications());
    }

    private static void listFailureSurfacesTheError() {
        ControlledService service = new ControlledService();
        TeacherApplicationsController controller = controller(service);
        service.pages.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.BAD_REQUEST, "size 必须为 1 至 100")));

        controller.activate();

        require("size 必须为 1 至 100".equals(controller.errorText())
                        && controller.applications().isEmpty() && !controller.loading(),
                "a business rejection must surface the server message, saw "
                        + controller.errorText());

        service.pages.addLast(CompletableFuture.failedFuture(
                new TeacherCourseServiceException(MessageCode.ERROR, "缺少响应字段: applications")));
        controller.refresh();
        require(TeacherApplicationsController.LOAD_FAILURE_TEXT.equals(controller.errorText()),
                "a technical failure must show the retry text instead of internal details, saw "
                        + controller.errorText());
    }

    // ------------------------------------------------------------------ 夹具

    private static TeacherApplicationsController controller(ControlledService service) {
        return new TeacherApplicationsController(service, Runnable::run);
    }

    private static AdjustmentRequestSummaryDTO summary(String requestId) {
        return new AdjustmentRequestSummaryDTO(requestId, "人机交互导论", "CS352-01", "00001234",
                "陈老师", 1, AdjustmentRequestStatusDTO.PENDING, "2026-09-14T07:00:00Z");
    }

    private static TeacherPageDTO<AdjustmentRequestSummaryDTO> page(
            AdjustmentRequestStatusDTO status, int number, int size,
            AdjustmentRequestSummaryDTO... items) {
        return new TeacherPageDTO<>(List.of(items), items.length, number, size);
    }

    private static AdjustmentRequestDetailDTO pendingDetail() {
        return new AdjustmentRequestDetailDTO("9405", INTERACTION_OFFERING, "00001234", "临时调课",
                AdjustmentRequestStatusDTO.PENDING, 1, 5, 5, 6, null, null, null,
                List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                List.of(new ScheduleConflictDTO("ADJUSTMENT_TARGET_ADJUSTED",
                        ScheduleConflictSeverityDTO.BLOCKING, "9202", INTERACTION_OFFERING, 8, 2, 5,
                        6, "该课程实例已有待审批的调课申请")),
                "2026-09-14T07:00:00Z", null, null, null);
    }

    private static AdjustmentRequestDetailDTO approvedDetail(AdjustmentRequestStatusDTO status) {
        return new AdjustmentRequestDetailDTO("9405", INTERACTION_OFFERING, "00001234", "临时调课",
                status, 2, 5, 5, 6, null, null, null,
                List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                List.of(), "2026-09-14T07:00:00Z", "admin-alpha", "2026-09-14T09:00:00Z", "同意");
    }

    /** 按字段回落用例的详情夹具：目标原快照固定为 陈老师/王助教/A-101。 */
    private static AdjustmentRequestDetailDTO mixedDetail(ScheduleResourceDTO newTeacher,
            ScheduleResourceDTO newAssistant, ScheduleResourceDTO newClassroom) {
        return new AdjustmentRequestDetailDTO("9407", INTERACTION_OFFERING, "00001234", "临时调课",
                AdjustmentRequestStatusDTO.PENDING, 1, 5, 5, 6, newTeacher, newAssistant,
                newClassroom,
                List.of(new AdjustmentTargetDTO("9203", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", "王助教", "A-101", "2026-11-06")),
                List.of(), "2026-09-14T07:00:00Z", null, null, null);
    }

    private static ScheduleResourceDTO resource(String id, String name, String type) {
        return new ScheduleResourceDTO(id, id, name, type, 0);
    }

    private static AdjustmentRequestDetailDTO legacyDetail() {
        return new AdjustmentRequestDetailDTO("9403", INTERACTION_OFFERING, "00001234", "材料不全的申请",
                AdjustmentRequestStatusDTO.REJECTED, 2, 5, 7, 8, null, null, null,
                List.of(new AdjustmentTargetDTO("9202", 8, "2026-10-27T00:00:00Z",
                        "2026-10-27T01:35:00Z", "陈老师", null, "A-101")),
                List.of(), "2026-09-12T06:00:00Z", "admin-alpha", "2026-09-14T09:00:00Z", "材料不足");
    }

    // ------------------------------------------------------------------ 视图契约

    private static void viewWiresEveryIdAndAction(Document view) {
        Class<?> controller = TeacherApplicationsController.class;
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

    private static Document parseView() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        try (InputStream stream =
                TeacherApplicationsControllerTest.class.getResourceAsStream(VIEW)) {
            if (stream == null) throw new IOException("Missing resource: " + VIEW);
            return factory.newDocumentBuilder().parse(stream);
        }
    }

    private static String readResource(String path) throws IOException {
        try (InputStream stream =
                TeacherApplicationsControllerTest.class.getResourceAsStream(path)) {
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

    /** 记录调用并按队列发放响应的教师课程服务替身；队列为空时返回确定性的已完成响应。 */
    private static final class ControlledService implements TeacherCourseService {
        private final Deque<CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>>> pages =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<AdjustmentRequestDetailDTO>> details =
                new ArrayDeque<>();
        private final Deque<CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>>
                withdrawals = new ArrayDeque<>();
        private final List<String> listCalls = new ArrayList<>();
        private final List<String> detailCalls = new ArrayList<>();
        private final List<WithdrawTeacherAdjustmentRequestDTO> withdraws = new ArrayList<>();
        private AdjustmentRequestSummaryDTO summary;
        private TeacherPageDTO<AdjustmentRequestSummaryDTO> page;
        private AdjustmentRequestDetailDTO detail;

        @Override
        public CompletableFuture<TeacherPageDTO<AdjustmentRequestSummaryDTO>>
                listMyAdjustmentRequests(AdjustmentRequestStatusDTO status, int pageNumber,
                        int size) {
            listCalls.add(status + "|" + pageNumber + "|" + size);
            if (!pages.isEmpty()) return pages.removeFirst();
            AdjustmentRequestSummaryDTO[] items = summary == null
                    ? new AdjustmentRequestSummaryDTO[0]
                    : new AdjustmentRequestSummaryDTO[] {summary};
            return CompletableFuture.completedFuture(page == null
                    ? new TeacherPageDTO<>(List.of(items), items.length, pageNumber, size)
                    : page);
        }

        @Override
        public CompletableFuture<AdjustmentRequestDetailDTO> getAdjustmentRequest(String requestId) {
            detailCalls.add(requestId);
            if (!details.isEmpty()) return details.removeFirst();
            return CompletableFuture.completedFuture(detail);
        }

        @Override
        public CompletableFuture<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>
                withdrawAdjustment(WithdrawTeacherAdjustmentRequestDTO request) {
            withdraws.add(request);
            if (!withdrawals.isEmpty()) return withdrawals.removeFirst();
            return CompletableFuture.completedFuture(new TeacherOperationResultDTO<>(
                    request.getOperationId(), "调课申请已撤销", detail, false));
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
