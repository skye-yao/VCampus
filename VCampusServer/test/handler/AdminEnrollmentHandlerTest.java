package handler;

import dto.course.admin.AdminCourseActions;
import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminEnrollmentService;
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exercises the real protocol boundary; only the database service is replaced. */
public final class AdminEnrollmentHandlerTest {
    private static final String OFFERING = "9007199254740995";
    private static final String OPERATION = "30000000-0000-0000-0000-000000000001";
    private static final List<String> ACTIONS = List.of(AdminCourseActions.SEARCH_STUDENTS,
            AdminCourseActions.LIST_OFFERING_STUDENTS, AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT,
            AdminCourseActions.ADD_STUDENT_TO_OFFERING, AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING);

    public static void main(String[] args) {
        SessionManager sessions = SessionManager.getInstance();
        UserSession admin = sessions.createSession("admin-enrollment", "管理员");
        UserSession student = sessions.createSession("student-enrollment", "学生");
        UserSession teacher = sessions.createSession("teacher-enrollment", "教师");
        try {
            permissionsAndCompatibleConstructors(admin.getToken(), student.getToken(), teacher.getToken());
            queryPagesAndPreviewUseTypedWireKeys(admin.getToken());
            mutationsUseOnlyTokenIdentity(admin.getToken());
            malformedQueriesNeverReachService(admin.getToken());
            malformedMutationScalarsNeverReachService(admin.getToken());
            domainErrorsStaySafe(admin.getToken());
        } finally {
            sessions.removeSession(admin.getToken());
            sessions.removeSession(student.getToken());
            sessions.removeSession(teacher.getToken());
        }
        System.out.println("AdminEnrollmentHandlerTest: PASS");
    }

    private static void permissionsAndCompatibleConstructors(String admin, String student, String teacher) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        for (String action : ACTIONS) {
            for (String token : new String[] {null, "invalid-token"}) {
                require(handler.handle(request(action, token)).getCode() == MessageCode.UNAUTHORIZED,
                        action + " must require a live session");
            }
            for (String token : new String[] {student, teacher}) {
                require(handler.handle(request(action, token)).getCode() == MessageCode.FORBIDDEN,
                        action + " must forbid non-administrators");
            }
            for (AdminCourseHandler compatible : List.of(new AdminCourseHandler(null, null),
                    new AdminCourseHandler(null, null, null))) {
                Message unavailable = compatible.handle(request(action, admin));
                require(unavailable.getCode() == MessageCode.BAD_REQUEST
                                && "该管理员操作尚未开放".equals(unavailable.getMessage()),
                        "earlier constructors must retain unavailable enrollment behavior");
            }
        }
        require(service.calls == 0, "authorization and unavailable actions must stop at the handler");
    }

    private static void queryPagesAndPreviewUseTypedWireKeys(String token) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        Message query = request(AdminCourseActions.SEARCH_STUDENTS, token);
        query.putData("query", "  王  ");
        query.putData("pageNumber", 2.0);
        query.putData("pageSize", 10.0);
        Message page = handler.handle(query);
        require(page.getCode() == MessageCode.SUCCESS && query.getUID().equals(page.getUID())
                        && page.getType() == MessageType.RESPONSE && "courseAdmin".equals(page.getModule()),
                "query must preserve the correlated response envelope");
        require("王".equals(service.query) && service.page == 2 && service.size == 10,
                "search must trim query and forward pageNumber/pageSize");
        require(page.getData("students") instanceof List<?> students
                        && students.get(0) instanceof StudentSearchResultDTO,
                "search rows must use the typed students key");
        require(((Number) page.getData("totalCount")).longValue() == 23
                        && ((Number) page.getData("pageNumber")).intValue() == 2
                        && ((Number) page.getData("pageSize")).intValue() == 10,
                "server page totals and page coordinates must survive");

        Message roster = request(AdminCourseActions.LIST_OFFERING_STUDENTS, token);
        roster.putData("query", "   ");
        Message rosterPage = handler.handle(roster);
        require(rosterPage.getCode() == MessageCode.SUCCESS && OFFERING.equals(service.offeringId),
                "roster must accept blank query and preserve exact BIGINT strings");
        require(rosterPage.getData("offeringStudents") instanceof List<?> rows
                        && rows.get(0) instanceof OfferingStudentDTO row
                        && "9007199254740997".equals(row.getEnrollmentId()) && !row.isRemovable(),
                "roster must retain history IDs and grade-lock details");
        require(((Number) rosterPage.getData("totalCount")).longValue() == 31,
                "roster totalCount must come from the service");

        Message preview = handler.handle(request(AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT, token));
        require(preview.getCode() == MessageCode.SUCCESS
                        && preview.getData("preview") instanceof AdminEnrollmentPreviewDTO dto
                        && dto.getRisks().get(0).getSeverity() == ScheduleConflictSeverityDTO.OVERRIDABLE,
                "preview must retain typed risks under preview");
    }

    private static void mutationsUseOnlyTokenIdentity(String token) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        for (String action : ACTIONS.subList(3, 5)) {
            Message request = request(action, token);
            request.setSender("forged-sender");
            request.putData("uid", "forged-admin");
            Message result = handler.handle(request);
            require(result.getCode() == MessageCode.SUCCESS
                            && result.getData("result") instanceof AdminOperationResultDTO<?>,
                    action + " must use typed data.request and result");
            require("admin-enrollment".equals(service.adminUid)
                            && OFFERING.equals(service.payload.getOfferingId())
                            && "student-alpha".equals(service.payload.getStudentUid())
                            && OPERATION.equals(service.payload.getOperationId()),
                    "authenticated admin and exact target identifiers must reach the service");
            require(action.equals(service.action), "add and remove must invoke their own service methods");
        }
    }

    private static void malformedQueriesNeverReachService(String token) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        for (Object query : new Object[] {null, "", "   ", 123, true}) {
            bad(handler, service, altered(AdminCourseActions.SEARCH_STUDENTS, token, "query", query));
        }
        for (String action : ACTIONS.subList(0, 2)) {
            for (Object page : new Object[] {null, 0, -1, 1.5, true, "x", Double.NaN, 2147483648L}) {
                bad(handler, service, altered(action, token, "pageNumber", page));
            }
            for (Object size : new Object[] {null, 0, -1, 101, 2.5, true}) {
                bad(handler, service, altered(action, token, "pageSize", size));
            }
        }
        for (String action : ACTIONS.subList(1, 3)) {
            for (Object id : new Object[] {null, 9007199254740995L, 1.0, true, "0", "-1",
                    "1.0", "1e3", "9223372036854775808"}) {
                bad(handler, service, altered(action, token, "offeringId", id));
            }
        }
        for (Object uid : new Object[] {null, " ", 123, true, "x".repeat(33)}) {
            bad(handler, service, altered(AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT, token,
                    "studentUid", uid));
        }
    }

    private static void malformedMutationScalarsNeverReachService(String token) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        Map<String, Object[]> invalid = new HashMap<>();
        invalid.put("offeringId", new Object[] {null, 9007199254740995L, true, "0", "-1", "1.5",
                "9223372036854775808"});
        invalid.put("studentUid", new Object[] {null, " ", 1001, true, "x".repeat(33)});
        invalid.put("operationId", new Object[] {null, 123, true, "bad-uuid", "1-1-1-1-1"});
        invalid.put("force", new Object[] {"true", "false", 1, Map.of()});
        invalid.put("overrideReason", new Object[] {12, true, "x".repeat(501)});
        for (String action : ACTIONS.subList(3, 5)) {
            for (Map.Entry<String, Object[]> entry : invalid.entrySet()) {
                for (Object value : entry.getValue()) {
                    Map<String, Object> payload = payload();
                    payload.put(entry.getKey(), value);
                    bad(handler, service, altered(action, token, "request", payload));
                }
            }
            Map<String, Object> forced = payload();
            forced.put("force", true);
            forced.put("overrideReason", "   ");
            bad(handler, service, altered(action, token, "request", forced));
            bad(handler, service, altered(action, token, "request", List.of()));
        }
    }

    private static void domainErrorsStaySafe(String token) {
        FakeEnrollment service = new FakeEnrollment();
        AdminCourseHandler handler = handler(service);
        Message request = request(AdminCourseActions.ADD_STUDENT_TO_OFFERING, token);
        service.failure = new AdminEnrollmentService.NotFoundException("学生不存在");
        require(handler.handle(request).getCode() == MessageCode.NOT_FOUND, "unknown students use NOT_FOUND");
        service.failure = new AdminEnrollmentService.ConflictException("需要确认风险", row(), List.of(risk()));
        Message conflict = handler.handle(request);
        require(conflict.getCode() == MessageCode.CONFLICT && conflict.getData("latest") instanceof OfferingStudentDTO
                        && conflict.getData("conflicts") instanceof List<?> risks
                        && risks.get(0) instanceof ScheduleConflictDTO,
                "conflicts must carry typed risks and latest enrollment state");
        service.failure = new AdminEnrollmentService.ConflictException("operationId 已用于其他请求", null, List.of());
        require(handler.handle(request).getCode() == MessageCode.CONFLICT,
                "operation digest conflicts must stay CONFLICT");
        for (RuntimeException failure : List.of(new DatabaseException("private SQL context"),
                new IllegalStateException("private runtime context"))) {
            service.failure = failure;
            ByteArrayOutputStream logged = new ByteArrayOutputStream();
            PrintStream original = System.err;
            Message response;
            try (PrintStream capture = new PrintStream(logged, true, StandardCharsets.UTF_8)) {
                System.setErr(capture);
                response = handler.handle(request);
            } finally {
                System.setErr(original);
            }
            require(response.getCode() == MessageCode.ERROR && !response.getMessage().contains("private"),
                    "database/runtime details must not escape to clients");
        }
    }

    private static void bad(AdminCourseHandler handler, FakeEnrollment service, Message request) {
        int before = service.calls;
        require(handler.handle(request).getCode() == MessageCode.BAD_REQUEST,
                "malformed " + request.getAction() + " must be BAD_REQUEST: " + request.getData());
        require(service.calls == before, "raw malformed data must be rejected before service invocation");
    }

    private static AdminCourseHandler handler(FakeEnrollment service) {
        return new AdminCourseHandler(null, null, null, service);
    }

    private static Message altered(String action, String token, String key, Object value) {
        Message result = request(action, token);
        result.putData(key, value);
        return result;
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseAdmin", action);
        request.setToken(token);
        request.putData("query", "王");
        request.putData("pageNumber", 1);
        request.putData("pageSize", 20);
        request.putData("offeringId", OFFERING);
        request.putData("studentUid", "student-alpha");
        request.putData("request", payload());
        return request;
    }

    private static Map<String, Object> payload() {
        Map<String, Object> result = new HashMap<>();
        result.put("operationId", OPERATION);
        result.put("offeringId", OFFERING);
        result.put("studentUid", "student-alpha");
        result.put("force", false);
        result.put("adminUid", "forged-payload-admin");
        return result;
    }

    private static OfferingStudentDTO row() {
        return new OfferingStudentDTO("9007199254740997", "student-alpha", "王同学", "软件工程",
                2024, "ENROLLED", false, "成绩已发布");
    }

    private static ScheduleConflictDTO risk() {
        return new ScheduleConflictDTO("CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "student-alpha", OFFERING, 0, 0, 0, 0, "教学班容量已满");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeEnrollment extends AdminEnrollmentService {
        private int calls;
        private int page;
        private int size;
        private String query;
        private String offeringId;
        private String adminUid;
        private String action;
        private AdminEnrollmentRequestDTO payload;
        private RuntimeException failure;

        private FakeEnrollment() { super(null, null, null, Clock.systemUTC(), ignored -> { }); }
        private void called() { calls++; if (failure != null) throw failure; }

        @Override
        public AdminEnrollmentPageDTO<StudentSearchResultDTO> searchStudents(String query, int page, int size) {
            called(); this.query = query; this.page = page; this.size = size;
            return new AdminEnrollmentPageDTO<>(List.of(new StudentSearchResultDTO("student-alpha",
                    "王同学", "软件工程", 2024, "ACTIVE")), 23, page, size);
        }

        @Override
        public AdminEnrollmentPageDTO<OfferingStudentDTO> listOfferingStudents(String offeringId,
                String query, int page, int size) {
            called(); this.offeringId = offeringId; this.query = query;
            return new AdminEnrollmentPageDTO<>(List.of(row()), 31, page, size);
        }

        @Override
        public AdminEnrollmentPreviewDTO previewAdminEnrollment(String offeringId, String studentUid) {
            called(); this.offeringId = offeringId;
            return new AdminEnrollmentPreviewDTO(offeringId, studentUid, List.of(risk()));
        }

        @Override
        public AdminOperationResultDTO<OfferingStudentDTO> addStudentToOffering(String adminUid,
                AdminEnrollmentRequestDTO request) {
            called(); this.adminUid = adminUid; this.payload = request;
            action = AdminCourseActions.ADD_STUDENT_TO_OFFERING;
            return new AdminOperationResultDTO<>(request.getOperationId(), "OK", "已添加", row(), List.of());
        }

        @Override
        public AdminOperationResultDTO<OfferingStudentDTO> removeStudentFromOffering(String adminUid,
                AdminEnrollmentRequestDTO request) {
            called(); this.adminUid = adminUid; this.payload = request;
            action = AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING;
            return new AdminOperationResultDTO<>(request.getOperationId(), "OK", "已移除", row(), List.of());
        }
    }
}
