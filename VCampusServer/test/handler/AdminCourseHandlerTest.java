package handler;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.CourseTermDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import network.MessageDispatcher;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminOfferingService;
import service.CourseQueryService;
import service.GradeApprovalService;
import service.ScheduleAdjustmentApprovalService;
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AdminCourseHandlerTest {
    private static final String OPERATION_ID = "10000000-0000-0000-0000-000000000001";

    private AdminCourseHandlerTest() {
    }

    public static void main(String[] args) {
        FakeCatalogService catalog = new FakeCatalogService();
        FakeOfferingService offerings = new FakeOfferingService();
        AdminCourseHandler handler = new AdminCourseHandler(catalog, offerings);
        SessionManager sessions = SessionManager.getInstance();
        UserSession administrator = sessions.createSession("admin-alpha", "管理员");
        UserSession teacher = sessions.createSession("teacher-alpha", "教师");
        UserSession student = sessions.createSession("student-alpha", "学生");
        try {
            require(handler.handle(request("listCourses", null)).getCode()
                            == MessageCode.UNAUTHORIZED,
                    "missing token must be unauthorized");
            require(handler.handle(request("listCourses", "invalid-token")).getCode()
                            == MessageCode.UNAUTHORIZED,
                    "invalid token must be unauthorized");
            require(handler.handle(request("listCourses", teacher.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "teacher role must be forbidden");
            require(handler.handle(request("listCourses", student.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "student role must be forbidden");

            require(handler.handle(request("   ", administrator.getToken())).getCode()
                            == MessageCode.BAD_REQUEST,
                    "blank action must be bad request");
            require(handler.handle(request("unknownAdminAction", administrator.getToken()))
                            .getCode() == MessageCode.BAD_REQUEST,
                    "unknown action must be bad request");
            Message unavailable = handler.handle(request("saveArrangement", administrator.getToken()));
            require(unavailable.getCode() == MessageCode.BAD_REQUEST,
                    "scheduling action must be bad request until its plan lands");
            require("该管理员操作尚未开放".equals(unavailable.getMessage()),
                    "scheduling action must report that it is not open yet");

            Message listed = handler.handle(request("listCourses", administrator.getToken()));
            require(listed.getCode() == MessageCode.SUCCESS, "listCourses must succeed");
            require(listed.getData().size() == 1 && listed.getData().containsKey("courses"),
                    "listCourses must use response key courses");

            Message create = request("createCourse", administrator.getToken());
            create.setSender("attacker");
            create.putData("uid", "admin-beta");
            create.putData("request", coursePayload());
            Message created = handler.handle(create);
            require(created.getCode() == MessageCode.SUCCESS,
                    "createCourse must succeed: " + created.getMessage());
            require("admin-alpha".equals(catalog.lastAdminUid),
                    "administrator UID must come from the authenticated session");
            require(catalog.lastRequest != null
                            && "CS900".equals(catalog.lastRequest.getCourseCode()),
                    "typed course payload must round-trip the course code");
            require(catalog.lastRequest.getCredit() == 3.5,
                    "typed course payload must round-trip the credit");
            require(created.getData().containsKey("result"),
                    "createCourse must use the result response key");
            require("课程已创建".equals(created.getMessage()),
                    "mutation message must come from the operation result");

            Message fractionalVersion = request("createCourse", administrator.getToken());
            Map<String, Object> fractionalVersionPayload = coursePayload();
            fractionalVersionPayload.put("expectedVersion", 1.5);
            fractionalVersion.putData("request", fractionalVersionPayload);
            Message fractionalVersionResponse = handler.handle(fractionalVersion);
            require(fractionalVersionResponse.getCode() == MessageCode.BAD_REQUEST,
                    "a fractional typed-payload integer must be bad request");
            require("request 字段格式无效".equals(fractionalVersionResponse.getMessage()),
                    "typed-payload conversion details must not leak to the client");

            Message malformedCredit = request("createCourse", administrator.getToken());
            Map<String, Object> malformedCreditPayload = coursePayload();
            malformedCreditPayload.put("credit", "abc");
            malformedCredit.putData("request", malformedCreditPayload);
            Message malformedCreditResponse = handler.handle(malformedCredit);
            require(malformedCreditResponse.getCode() == MessageCode.BAD_REQUEST,
                    "a non-numeric typed-payload credit must be bad request");
            require("request 字段格式无效".equals(malformedCreditResponse.getMessage()),
                    "number parsing details must not leak to the client");

            Message offeringList = request("listOfferings", administrator.getToken());
            offeringList.putData("courseId", "1001");
            Message offeringsListed = handler.handle(offeringList);
            require(offeringsListed.getCode() == MessageCode.SUCCESS,
                    "listOfferings must succeed");
            require(offeringsListed.getData().size() == 1
                            && offeringsListed.getData().containsKey("offerings"),
                    "listOfferings must use response key offerings");

            Message createOffering = request("createOffering", administrator.getToken());
            createOffering.putData("request", offeringPayload());
            Message offeringCreated = handler.handle(createOffering);
            require(offeringCreated.getCode() == MessageCode.SUCCESS,
                    "createOffering must succeed: " + offeringCreated.getMessage());
            require("admin-alpha".equals(offerings.lastAdminUid),
                    "administrator UID must reach the offering service");
            require(offerings.lastRequest != null
                            && "CS900-01".equals(offerings.lastRequest.getOfferingCode()),
                    "typed offering payload must round-trip the offering code");

            testListOfferingsPassesTheTermThrough(handler, offerings, administrator);
            testListOfferingTermsReturnsTheTermKey(handler, administrator);

            Message malformedId = archiveRequest(administrator.getToken());
            malformedId.putData("courseId", "9.5");
            require(handler.handle(malformedId).getCode() == MessageCode.BAD_REQUEST,
                    "non-decimal course ID must be bad request");

            catalog.mode = Mode.NOT_FOUND;
            require(handler.handle(archiveRequest(administrator.getToken())).getCode()
                            == MessageCode.NOT_FOUND,
                    "missing course must be not found");

            catalog.mode = Mode.CONFLICT;
            Message conflicted = handler.handle(archiveRequest(administrator.getToken()));
            require(conflicted.getCode() == MessageCode.CONFLICT,
                    "version conflict must use the conflict code");
            require(conflicted.getData() != null && conflicted.getData().get("latest") != null,
                    "conflict must carry the latest entity");

            PrintStream previousError = System.err;
            ByteArrayOutputStream databaseLog = new ByteArrayOutputStream();
            catalog.mode = Mode.DATABASE;
            Message database;
            try {
                System.setErr(new PrintStream(databaseLog, true, StandardCharsets.UTF_8));
                database = handler.handle(archiveRequest(administrator.getToken()));
            } finally {
                System.setErr(previousError);
            }
            require(database.getCode() == MessageCode.ERROR,
                    "database failure must use the error code");
            require(database.getMessage() != null
                            && !database.getMessage().contains("SELECT secret"),
                    "database details must not leak");
            String databaseLogText = databaseLog.toString(StandardCharsets.UTF_8);
            require(databaseLogText.contains("action=archiveCourse")
                            && databaseLogText.contains("DatabaseException")
                            && databaseLogText.contains("SELECT secret"),
                    "database failure details must remain in the server log");

            ByteArrayOutputStream runtimeLog = new ByteArrayOutputStream();
            catalog.mode = Mode.RUNTIME;
            Message runtime;
            try {
                System.setErr(new PrintStream(runtimeLog, true, StandardCharsets.UTF_8));
                runtime = handler.handle(archiveRequest(administrator.getToken()));
            } finally {
                System.setErr(previousError);
            }
            require(runtime.getCode() == MessageCode.ERROR,
                    "runtime failure must use the error code");
            require("服务端内部错误".equals(runtime.getMessage()),
                    "runtime failure details must not leak");
            String runtimeLogText = runtimeLog.toString(StandardCharsets.UTF_8);
            require(runtimeLogText.contains("action=archiveCourse")
                            && runtimeLogText.contains("IllegalStateException")
                            && runtimeLogText.contains("runtime probe"),
                    "runtime failure details must remain in the server log");
            catalog.mode = Mode.SUCCESS;

            Message routed = new Message(MessageType.REQUEST, "courseAdmin", "listCourses");
            routed.setToken("invalid-token");
            Message routedResponse = new MessageDispatcher().dispatch(routed);
            require(routedResponse.getCode() == MessageCode.UNAUTHORIZED,
                    "server dispatcher must route the courseAdmin module");
            require(routed.getUID().equals(routedResponse.getUID()),
                    "dispatcher must preserve the transport request UID");

            verifyAdjustmentListAcceptsTheFourStates(administrator);
            verifyGradeFilterStaysThreeState(administrator);

            Message legacy = new Message(MessageType.REQUEST, "course", "listTerms");
            legacy.setToken("invalid-token");
            require(new MessageDispatcher(new CourseHandler(new FakeCourseQueryService()))
                            .dispatch(legacy).getCode() == MessageCode.UNAUTHORIZED,
                    "existing CourseHandler constructor must still route the course module");
        } finally {
            sessions.removeSession(administrator.getToken());
            sessions.removeSession(teacher.getToken());
            sessions.removeSession(student.getToken());
        }
        System.out.println("Admin course handler test passed.");
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseAdmin", action);
        request.setToken(token);
        return request;
    }

    private static void testListOfferingsPassesTheTermThrough(AdminCourseHandler handler,
                                                              FakeOfferingService offerings,
                                                              UserSession administrator) {
        // 请求不带学期时，服务端必须收到两个 null，而不是 0——0 会被当成"学年无效"拒掉。
        Message unscoped = request("listOfferings", administrator.getToken());
        unscoped.putData("courseId", "1001");
        require(handler.handle(unscoped).getCode() == MessageCode.SUCCESS,
                "listOfferings without a term must succeed");
        require(offerings.listCalls.contains("1001|null|null"),
                "an absent term must reach the service as null, saw " + offerings.listCalls);

        Message scoped = request("listOfferings", administrator.getToken());
        scoped.putData("courseId", "1001");
        scoped.putData("academicYear", 2027);
        scoped.putData("semester", 3);
        require(handler.handle(scoped).getCode() == MessageCode.SUCCESS,
                "listOfferings with a term must succeed");
        require(offerings.listCalls.contains("1001|2027|3"),
                "the term must reach the service unchanged, saw " + offerings.listCalls);
    }

    private static void testListOfferingTermsReturnsTheTermKey(AdminCourseHandler handler,
                                                               UserSession administrator) {
        Message terms = request("listOfferingTerms", administrator.getToken());
        Message response = handler.handle(terms);
        require(response.getCode() == MessageCode.SUCCESS, "listOfferingTerms must succeed");
        require(response.getData().size() == 1 && response.getData().containsKey("terms"),
                "listOfferingTerms must use response key terms");
    }

    /**
     * 调课筛选是四态：WITHDRAWN 必须能被筛出来（教师撤销的申请要在审批列表可见），
     * 未登记的状态一律 400。旧的“三态调课查询”在 T1 已直接迁到四态，这里钉住解析行为。
     */
    private static void verifyAdjustmentListAcceptsTheFourStates(UserSession administrator) {
        RecordingAdjustmentService adjustments = new RecordingAdjustmentService();
        AdminCourseHandler handler = new AdminCourseHandler(new AdminCourseCatalogService(),
                new AdminOfferingService(), null, null, adjustments, null);

        require(AdjustmentRequestStatusDTO.values().length == 4,
                "the adjustment status domain must stay four-state");
        for (AdjustmentRequestStatusDTO status : AdjustmentRequestStatusDTO.values()) {
            Message request = request("listAdjustmentRequests", administrator.getToken());
            request.putData("status", status.name());
            request.putData("pageNumber", 1);
            request.putData("pageSize", 20);
            Message response = handler.handle(request);
            require(response.getCode() == MessageCode.SUCCESS,
                    "the adjustment list must accept " + status + ": " + response.getMessage());
            require(adjustments.lastStatus == status,
                    "the four-state filter must reach the approval service: " + status);
            require(response.getData().get("adjustmentRequests") instanceof List<?> items
                            && items.size() == 1
                            && ((AdjustmentRequestSummaryDTO) items.get(0)).getStatus() == status,
                    "the list must return rows in the requested " + status + " state");
        }

        Message unfiltered = request("listAdjustmentRequests", administrator.getToken());
        unfiltered.putData("pageNumber", 1);
        unfiltered.putData("pageSize", 20);
        require(handler.handle(unfiltered).getCode() == MessageCode.SUCCESS,
                "an unfiltered adjustment list must succeed");
        require(adjustments.lastStatus == null,
                "an absent status must reach the service as null, keeping its PENDING default");
    }

    /**
     * 成绩审批仍是三态：{@link ApprovalStatusDTO} 没有 WITHDRAWN，筛选也不得承认它，
     * 否则成绩列表会出现业务上不存在的“已撤销”。
     */
    private static void verifyGradeFilterStaysThreeState(UserSession administrator) {
        RecordingGradeService grades = new RecordingGradeService();
        AdminCourseHandler handler = new AdminCourseHandler(new AdminCourseCatalogService(),
                new AdminOfferingService(), null, null, null, grades);

        require(ApprovalStatusDTO.values().length == 3,
                "the grade approval enum must stay three-state");
        for (ApprovalStatusDTO status : ApprovalStatusDTO.values()) {
            Message request = request("listGradeSubmissions", administrator.getToken());
            request.putData("status", status.name());
            request.putData("pageNumber", 1);
            request.putData("pageSize", 20);
            require(handler.handle(request).getCode() == MessageCode.SUCCESS,
                    "the grade list must accept " + status);
            require(grades.lastStatus == status,
                    "the grade filter must reach the grade service: " + status);
        }

        Message withdrawn = request("listGradeSubmissions", administrator.getToken());
        withdrawn.putData("status", "WITHDRAWN");
        withdrawn.putData("pageNumber", 1);
        withdrawn.putData("pageSize", 20);
        Message rejected = handler.handle(withdrawn);
        require(rejected.getCode() == MessageCode.BAD_REQUEST,
                "the grade filter must reject WITHDRAWN, saw " + rejected.getCode());
        require(rejected.getMessage() != null && rejected.getMessage().contains("REJECTED"),
                "the grade rejection must name the three-state domain, saw "
                        + rejected.getMessage());
    }

    private static Message archiveRequest(String token) {
        Message request = request("archiveCourse", token);
        request.putData("courseId", "1001");
        request.putData("expectedVersion", 1);
        request.putData("operationId", OPERATION_ID);
        return request;
    }

    private static Map<String, Object> coursePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", OPERATION_ID);
        payload.put("courseCode", "CS900");
        payload.put("courseName", "管理课程");
        payload.put("courseType", "必修");
        payload.put("credit", 3.5);
        payload.put("creditHours", 64);
        payload.put("description", "管理员课程");
        payload.put("prerequisites", "");
        payload.put("allowCrossMajor", true);
        payload.put("finalExam", false);
        return payload;
    }

    private static Map<String, Object> offeringPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", OPERATION_ID);
        payload.put("courseId", "1001");
        payload.put("offeringCode", "CS900-01");
        payload.put("academicYear", 2026);
        payload.put("semester", 1);
        payload.put("capacity", 40);
        payload.put("teacherUid", "teacher-alpha");
        payload.put("assistantUid", "");
        payload.put("status", 1);
        return payload;
    }

    private static AdminCourseDTO course() {
        return new AdminCourseDTO("1001", "CS900", "管理课程", "必修", 3.5, 64,
                "管理员课程", null, true, false, "ACTIVE", 0, 1);
    }

    private static AdminOfferingDTO offering() {
        return new AdminOfferingDTO("2001", "CS900-01", "1001", 2026, 1, 40, 0,
                "NOT_OPEN", "teacher-alpha", "教师甲", null, null, "UNSCHEDULED", 1);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Mode { SUCCESS, NOT_FOUND, CONFLICT, DATABASE, RUNTIME }

    private static final class FakeCatalogService extends AdminCourseCatalogService {
        private String lastAdminUid;
        private CourseEditorRequestDTO lastRequest;
        private Mode mode = Mode.SUCCESS;

        @Override
        public List<AdminCourseDTO> list(String query, String status, Integer academicYear,
                                         Integer semester) {
            return List.of(course());
        }

        @Override
        public AdminOperationResultDTO<AdminCourseDTO> create(String adminUid,
                                                              CourseEditorRequestDTO request) {
            lastAdminUid = adminUid;
            lastRequest = request;
            fail();
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "课程已创建", course(),
                    List.of());
        }

        @Override
        public AdminOperationResultDTO<AdminCourseDTO> archive(String adminUid, String courseId,
                                                               int expectedVersion,
                                                               String operationId) {
            lastAdminUid = adminUid;
            fail();
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "课程已归档", course(),
                    List.of());
        }

        private void fail() {
            switch (mode) {
                case NOT_FOUND -> throw new NotFoundException("课程不存在");
                case CONFLICT -> throw new ConflictException("课程版本或状态已变化，请刷新后重试", course());
                case DATABASE -> throw new exception.DatabaseException(
                        "SELECT secret FROM tbl_course failed");
                case RUNTIME -> throw new IllegalStateException("runtime probe");
                case SUCCESS -> { }
            }
        }
    }

    private static final class FakeOfferingService extends AdminOfferingService {
        private String lastAdminUid;
        private OfferingEditorRequestDTO lastRequest;
        final List<String> listCalls = new ArrayList<>();

        @Override
        public List<AdminOfferingDTO> list(String courseId, Integer academicYear, Integer semester) {
            listCalls.add(courseId + "|" + academicYear + "|" + semester);
            return List.of(offering());
        }

        @Override
        public List<CourseTermDTO> listTerms() {
            return List.of(new CourseTermDTO(2027, 3, "2027-2028 春学期"));
        }

        @Override
        public AdminOperationResultDTO<AdminOfferingDTO> create(String adminUid,
                                                                OfferingEditorRequestDTO request) {
            lastAdminUid = adminUid;
            lastRequest = request;
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "教学班已创建", offering(),
                    List.of());
        }
    }

    private static final class FakeCourseQueryService extends CourseQueryService {
    }

    /** 记录调课状态筛选，并按请求的四态回显一行，供“返回所有四种状态”断言。 */
    private static final class RecordingAdjustmentService extends ScheduleAdjustmentApprovalService {
        private AdjustmentRequestStatusDTO lastStatus;

        @Override
        public AdjustmentRequestPageDTO listRequests(AdjustmentRequestStatusDTO status, int page,
                int size) {
            lastStatus = status;
            AdjustmentRequestStatusDTO effective = status == null
                    ? AdjustmentRequestStatusDTO.PENDING : status;
            return new AdjustmentRequestPageDTO(List.of(new AdjustmentRequestSummaryDTO("9001",
                    "数据结构", "CS203-01", "T1001", "张老师", 1, effective,
                    "2026-09-10T02:00:00Z")), 1L, page, size);
        }
    }

    /** 记录成绩状态筛选；只有在解析通过之后才会被调用。 */
    private static final class RecordingGradeService extends GradeApprovalService {
        private ApprovalStatusDTO lastStatus;

        @Override
        public GradeSubmissionPageDTO listGradeSubmissionsPage(ApprovalStatusDTO status, int page,
                int size) {
            lastStatus = status;
            return new GradeSubmissionPageDTO(List.of(), 0L, page, size);
        }
    }
}
