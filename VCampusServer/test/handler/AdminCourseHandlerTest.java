package handler;

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
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
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
        public List<AdminCourseDTO> list(String query, String status) {
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

        @Override
        public List<AdminOfferingDTO> list(String courseId) {
            return List.of(offering());
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
}
