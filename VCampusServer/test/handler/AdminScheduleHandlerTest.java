package handler;

import com.google.gson.Gson;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.ScheduleManagementService;
import session.SessionManager;
import session.UserSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AdminScheduleHandlerTest {
    private static final String OPERATION_ID = "20000000-0000-0000-0000-000000000002";
    private static final String PLAN_ID = "7001";
    private static final String OFFERING_ID = "1001";
    private static final String ARRANGEMENT_ID = "9001";
    private static final String CLASSROOM_ID = "3001";

    private static String adminToken;

    private AdminScheduleHandlerTest() {
    }

    public static void main(String[] args) {
        FakeScheduleService scheduling = new FakeScheduleService();
        AdminCourseHandler handler = new AdminCourseHandler(null, null, scheduling);
        SessionManager sessions = SessionManager.getInstance();
        UserSession administrator = sessions.createSession("admin-alpha", "管理员");
        adminToken = administrator.getToken();
        UserSession teacher = sessions.createSession("teacher-alpha", "教师");
        UserSession student = sessions.createSession("student-alpha", "学生");
        try {
            authenticationGuards(handler, administrator, teacher, student);
            listResourcesUsesExactKey(handler, scheduling);
            loadPlanUsesExactKey(handler, scheduling);
            loadArrangementsUsesExactKey(handler, scheduling);
            checkArrangementUsesExactKey(handler, scheduling);
            saveArrangementUsesResultKeyAndTypedPayload(handler, scheduling, administrator);
            deleteArrangementUsesResultKey(handler, scheduling, administrator);
            publishPlanUsesResultKey(handler, scheduling, administrator);
            malformedTopLevelIdsAreBadRequest(handler, administrator);
            typedDtoRejectionsAreBadRequest(handler, scheduling, administrator);
            notFoundMapsToNotFound(handler, scheduling, administrator);
            conflictMapsToConflictWithConflicts(handler, scheduling, administrator);
            databaseFailureMapsToErrorWithoutLeak(handler, scheduling, administrator);
            unexpectedFailureMapsToError(handler, scheduling, administrator);
            adminIdentityComesFromSession(handler, scheduling, administrator);
        } finally {
            sessions.removeSession(administrator.getToken());
            sessions.removeSession(teacher.getToken());
            sessions.removeSession(student.getToken());
        }
        System.out.println("AdminScheduleHandlerTest: PASS");
    }

    private static void authenticationGuards(AdminCourseHandler handler, UserSession administrator,
            UserSession teacher, UserSession student) {
        Message missing = request(AdminCourseActions.LIST_SCHEDULE_RESOURCES, null);
        require(handler.handle(missing).getCode() == MessageCode.UNAUTHORIZED,
                "a missing token must map to UNAUTHORIZED for scheduling actions");
        Message invalid = request(AdminCourseActions.LIST_SCHEDULE_RESOURCES, "not-a-token");
        require(handler.handle(invalid).getCode() == MessageCode.UNAUTHORIZED,
                "an unknown token must map to UNAUTHORIZED for scheduling actions");
        Message asTeacher = request(AdminCourseActions.SAVE_ARRANGEMENT, teacher.getToken());
        require(handler.handle(asTeacher).getCode() == MessageCode.FORBIDDEN,
                "a non-admin role must map to FORBIDDEN for scheduling writes");
        Message asStudent = request(AdminCourseActions.PUBLISH_SCHEDULE_PLAN, student.getToken());
        require(handler.handle(asStudent).getCode() == MessageCode.FORBIDDEN,
                "a student role must map to FORBIDDEN for scheduling writes");
    }

    private static void listResourcesUsesExactKey(AdminCourseHandler handler,
            FakeScheduleService scheduling) {
        Message request = request(AdminCourseActions.LIST_SCHEDULE_RESOURCES,
                adminToken());
        request.putData("type", "teacher");
        request.putData("query", "张");
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "listScheduleResources must succeed: " + response.getMessage());
        require(List.of("resources").equals(new ArrayList<>(response.getData().keySet())),
                "listScheduleResources must use exactly the resources key: "
                        + response.getData().keySet());
        List<?> resources = (List<?>) response.getData().get("resources");
        require(resources.size() == 1 && resources.get(0) instanceof ScheduleResourceDTO,
                "resources must carry typed ScheduleResourceDTO entries");
        require("teacher".equals(scheduling.lastResourceType),
                "the resource type filter must reach the service");
        require("张".equals(scheduling.lastResourceQuery),
                "the resource query filter must reach the service");
    }

    private static void loadPlanUsesExactKey(AdminCourseHandler handler,
            FakeScheduleService scheduling) {
        Message request = request(AdminCourseActions.LOAD_SCHEDULE_PLAN, adminToken());
        request.putData("academicYear", 2026);
        request.putData("semester", 1);
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "loadSchedulePlan must succeed: " + response.getMessage());
        require(List.of("plan").equals(new ArrayList<>(response.getData().keySet())),
                "loadSchedulePlan must use exactly the plan key: " + response.getData().keySet());
        require(response.getData().get("plan") instanceof SchedulePlanDTO,
                "plan must carry a typed SchedulePlanDTO");
        require(scheduling.lastAcademicYear == 2026 && scheduling.lastSemester == 1,
                "the term must reach the service");
    }

    private static void loadArrangementsUsesExactKey(AdminCourseHandler handler,
            FakeScheduleService scheduling) {
        Message request = request(AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS, adminToken());
        request.putData("planId", PLAN_ID);
        request.putData("offeringId", OFFERING_ID);
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "loadOfferingArrangements must succeed: " + response.getMessage());
        require(List.of("arrangements").equals(new ArrayList<>(response.getData().keySet())),
                "loadOfferingArrangements must use exactly the arrangements key: "
                        + response.getData().keySet());
        require(response.getData().get("arrangements") instanceof List,
                "arrangements must carry a list");
        require(PLAN_ID.equals(scheduling.lastPlanId)
                        && OFFERING_ID.equals(scheduling.lastOfferingId),
                "plan and offering ids must reach the service");
    }

    private static void checkArrangementUsesExactKey(AdminCourseHandler handler,
            FakeScheduleService scheduling) {
        Message request = request(AdminCourseActions.CHECK_ARRANGEMENT, adminToken());
        request.putData("request", wire(arrangementRequest(OPERATION_ID, false, null)));
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "checkArrangement must succeed: " + response.getMessage());
        require(List.of("conflicts").equals(new ArrayList<>(response.getData().keySet())),
                "checkArrangement must use exactly the conflicts key: "
                        + response.getData().keySet());
        require(response.getData().get("conflicts") instanceof List,
                "conflicts must carry a list");
        require(scheduling.lastChecked != null
                        && PLAN_ID.equals(scheduling.lastChecked.getPlanId()),
                "the typed arrangement request must reach the check");
        require(scheduling.lastChecked.getSlots().size() == 2,
                "two typed slots must survive deserialization, not a raw LinkedTreeMap");
    }

    private static void saveArrangementUsesResultKeyAndTypedPayload(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        Message request = request(AdminCourseActions.SAVE_ARRANGEMENT, administrator.getToken());
        request.putData("request", wire(arrangementRequest(OPERATION_ID, false, null)));
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "saveArrangement must succeed: " + response.getMessage());
        require(List.of("result").equals(new ArrayList<>(response.getData().keySet())),
                "saveArrangement must use exactly the result key: "
                        + response.getData().keySet());
        require(response.getData().get("result") instanceof AdminOperationResultDTO,
                "saveArrangement must carry an operation result");
        require(scheduling.lastSaved instanceof SaveArrangementRequestDTO,
                "the DTO must be deserialized from data.request, never raw-cast");
        require(OFFERING_ID.equals(scheduling.lastSaved.getOfferingId())
                        && CLASSROOM_ID.equals(scheduling.lastSaved.getClassroomId()),
                "the saved arrangement must round-trip its ids");
        require("T1001".equals(scheduling.lastSaved.getTeacherUid())
                        && scheduling.lastSaved.getAssistantUid() == null,
                "the saved arrangement must round-trip its resources");
        require(scheduling.lastSaved.getStartWeek() == 1
                        && scheduling.lastSaved.getEndWeek() == 16,
                "the saved arrangement must round-trip its weeks");
    }

    private static void deleteArrangementUsesResultKey(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        Message request = request(AdminCourseActions.DELETE_ARRANGEMENT, administrator.getToken());
        request.putData("arrangementId", ARRANGEMENT_ID);
        request.putData("expectedVersion", 1);
        request.putData("operationId", OPERATION_ID);
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "deleteArrangement must succeed: " + response.getMessage());
        require(List.of("result").equals(new ArrayList<>(response.getData().keySet())),
                "deleteArrangement must use exactly the result key: "
                        + response.getData().keySet());
        require(ARRANGEMENT_ID.equals(scheduling.lastDeletedId)
                        && scheduling.lastExpectedVersion == 1,
                "the delete target must reach the service");
    }

    private static void publishPlanUsesResultKey(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        Message request = request(AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                administrator.getToken());
        request.putData("planId", PLAN_ID);
        request.putData("expectedRevision", 1);
        request.putData("operationId", OPERATION_ID);
        request.putData("force", true);
        request.putData("overrideReason", "  教室临时调整  ");
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.SUCCESS,
                "publishSchedulePlan must succeed: " + response.getMessage());
        require(List.of("result").equals(new ArrayList<>(response.getData().keySet())),
                "publishSchedulePlan must use exactly the result key: "
                        + response.getData().keySet());
        require(PLAN_ID.equals(scheduling.lastPublishedId)
                        && scheduling.lastExpectedRevision == 1,
                "the publish target must reach the service");
        require(scheduling.lastForce && "  教室临时调整  ".equals(scheduling.lastReason),
                "the force flag and raw reason must reach the service for trimming");
    }

    private static void malformedTopLevelIdsAreBadRequest(AdminCourseHandler handler,
            UserSession administrator) {
        Message delete = request(AdminCourseActions.DELETE_ARRANGEMENT, administrator.getToken());
        delete.putData("arrangementId", "9.5");
        delete.putData("expectedVersion", 1);
        delete.putData("operationId", OPERATION_ID);
        require(handler.handle(delete).getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal arrangement id must be BAD_REQUEST");

        Message publish = request(AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                administrator.getToken());
        publish.putData("planId", "abc");
        publish.putData("expectedRevision", 1);
        publish.putData("operationId", OPERATION_ID);
        require(handler.handle(publish).getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal plan id must be BAD_REQUEST");

        Message arrangements = request(AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS,
                administrator.getToken());
        arrangements.putData("planId", "9.5");
        require(handler.handle(arrangements).getCode() == MessageCode.BAD_REQUEST,
                "a malformed plan id must be BAD_REQUEST");

        Message blankOperation = request(AdminCourseActions.DELETE_ARRANGEMENT,
                administrator.getToken());
        blankOperation.putData("arrangementId", ARRANGEMENT_ID);
        blankOperation.putData("expectedVersion", 1);
        blankOperation.putData("operationId", "   ");
        require(handler.handle(blankOperation).getCode() == MessageCode.BAD_REQUEST,
                "a blank operationId must be BAD_REQUEST");

        Message missingPayload = request(AdminCourseActions.SAVE_ARRANGEMENT,
                administrator.getToken());
        require(handler.handle(missingPayload).getCode() == MessageCode.BAD_REQUEST,
                "a missing data.request must be BAD_REQUEST, not a raw cast");
    }

    private static void typedDtoRejectionsAreBadRequest(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        scheduling.rejectTypedInput = true;
        try {
            Message invalidUuid = request(AdminCourseActions.SAVE_ARRANGEMENT,
                    administrator.getToken());
            invalidUuid.putData("request",
                    wire(arrangementRequest("not-a-uuid", false, null)));
            require(handler.handle(invalidUuid).getCode() == MessageCode.BAD_REQUEST,
                    "an invalid operationId UUID must be BAD_REQUEST");

            Map<String, Object> malformed = wire(arrangementRequest(OPERATION_ID, false, null));
            malformed.put("planId", "9.5");
            Message malformedPlan = request(AdminCourseActions.SAVE_ARRANGEMENT,
                    administrator.getToken());
            malformedPlan.putData("request", malformed);
            require(handler.handle(malformedPlan).getCode() == MessageCode.BAD_REQUEST,
                    "a malformed typed plan id must be BAD_REQUEST");

            Message blankReason = request(AdminCourseActions.SAVE_ARRANGEMENT,
                    administrator.getToken());
            blankReason.putData("request",
                    wire(arrangementRequest(OPERATION_ID, true, "   ")));
            require(handler.handle(blankReason).getCode() == MessageCode.BAD_REQUEST,
                    "a blank force reason must be BAD_REQUEST");
        } finally {
            scheduling.rejectTypedInput = false;
        }
    }

    private static void notFoundMapsToNotFound(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        scheduling.mode = Mode.NOT_FOUND;
        Message request = request(AdminCourseActions.LOAD_OFFERING_ARRANGEMENTS,
                administrator.getToken());
        request.putData("planId", PLAN_ID);
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.NOT_FOUND,
                "a missing schedule resource must map to NOT_FOUND");
        scheduling.mode = Mode.SUCCESS;
    }

    private static void conflictMapsToConflictWithConflicts(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        scheduling.mode = Mode.CONFLICT;
        Message request = request(AdminCourseActions.SAVE_ARRANGEMENT,
                administrator.getToken());
        request.putData("request", wire(arrangementRequest(OPERATION_ID, false, null)));
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.CONFLICT,
                "a scheduling conflict must map to CONFLICT");
        require(response.getData().containsKey("conflicts"),
                "a scheduling conflict must expose the conflicts key");
        require(response.getData().get("conflicts") instanceof List,
                "conflict details must be a typed list");
        require(response.getData().containsKey("latest"),
                "a scheduling conflict must keep the latest entity for refresh");
        scheduling.mode = Mode.SUCCESS;
    }

    private static void databaseFailureMapsToErrorWithoutLeak(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        scheduling.mode = Mode.DATABASE;
        Message request = request(AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                administrator.getToken());
        request.putData("planId", PLAN_ID);
        request.putData("expectedRevision", 1);
        request.putData("operationId", OPERATION_ID);
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.ERROR,
                "a database failure must map to ERROR");
        require(response.getMessage() != null
                        && !response.getMessage().contains("SELECT secret"),
                "database details must not leak to the client");
        scheduling.mode = Mode.SUCCESS;
    }

    private static void unexpectedFailureMapsToError(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        scheduling.mode = Mode.UNEXPECTED;
        Message request = request(AdminCourseActions.LIST_SCHEDULE_RESOURCES,
                administrator.getToken());
        Message response = handler.handle(request);
        require(response.getCode() == MessageCode.ERROR,
                "an unexpected failure must map to ERROR");
        scheduling.mode = Mode.SUCCESS;
    }

    private static void adminIdentityComesFromSession(AdminCourseHandler handler,
            FakeScheduleService scheduling, UserSession administrator) {
        Message request = request(AdminCourseActions.SAVE_ARRANGEMENT, administrator.getToken());
        request.setSender("attacker");
        request.putData("uid", "admin-beta");
        request.putData("request", wire(arrangementRequest(OPERATION_ID, false, null)));
        handler.handle(request);
        require("admin-alpha".equals(scheduling.lastAdminUid),
                "the administrator UID must come from the authenticated session, not the client");
    }

    private static String adminToken() {
        return adminToken;
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseAdmin", action);
        request.setToken(token);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> wire(Object dto) {
        Gson gson = new Gson();
        return gson.fromJson(gson.toJson(dto), Map.class);
    }

    private static SaveArrangementRequestDTO arrangementRequest(String operationId,
            boolean force, String reason) {
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 1, 2));
        slots.add(new ScheduleSlotDTO(3, 3, 4));
        return new SaveArrangementRequestDTO(operationId, null, 0, PLAN_ID, OFFERING_ID,
                "T1001", null, CLASSROOM_ID, slots, 1, 16, force, reason);
    }

    private static ScheduleConflictDTO conflict() {
        return new ScheduleConflictDTO("TEACHER", ScheduleConflictSeverityDTO.OVERRIDABLE,
                "8001", "2001", 1, 1, 1, 2, "教师时间冲突");
    }

    private static ScheduleArrangementDTO arrangement() {
        ScheduleResourceDTO teacher = new ScheduleResourceDTO("8001", "T1001", "张老师",
                "teacher", 0);
        ScheduleResourceDTO classroom = new ScheduleResourceDTO("8101", CLASSROOM_ID, "A-101",
                "classroom", 120);
        List<ScheduleSlotDTO> slots = new ArrayList<>();
        slots.add(new ScheduleSlotDTO(1, 1, 2));
        slots.add(new ScheduleSlotDTO(3, 3, 4));
        return new ScheduleArrangementDTO(ARRANGEMENT_ID, PLAN_ID, OFFERING_ID, teacher, null,
                classroom, slots, 1, 16, "DRAFT", 1);
    }

    private static SchedulePlanDTO plan() {
        return new SchedulePlanDTO(PLAN_ID, "2026 秋排课方案", 1, "DRAFT", false, List.of());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Mode { SUCCESS, NOT_FOUND, CONFLICT, DATABASE, UNEXPECTED }

    /**
     * Stands in for Task 2's {@code ScheduleManagementService}: it mirrors the service's
     * typed rejection contract (malformed decimal ids, non-UUID operation ids, blank force
     * reasons) without touching a database.
     */
    private static final class FakeScheduleService extends ScheduleManagementService {
        private Mode mode = Mode.SUCCESS;
        private boolean rejectTypedInput;

        private String lastResourceType;
        private String lastResourceQuery;
        private int lastAcademicYear;
        private int lastSemester;
        private String lastPlanId;
        private String lastOfferingId;
        private SaveArrangementRequestDTO lastChecked;
        private SaveArrangementRequestDTO lastSaved;
        private String lastAdminUid;
        private String lastDeletedId;
        private int lastExpectedVersion;
        private String lastPublishedId;
        private int lastExpectedRevision;
        private boolean lastForce;
        private String lastReason;

        @Override
        public List<ScheduleResourceDTO> listResources(String type, String query) {
            lastResourceType = type;
            lastResourceQuery = query;
            fail();
            return List.of(new ScheduleResourceDTO("8001", "T1001", "张老师", "teacher", 0));
        }

        @Override
        public SchedulePlanDTO loadPlan(int academicYear, int semester) {
            lastAcademicYear = academicYear;
            lastSemester = semester;
            fail();
            return plan();
        }

        @Override
        public List<ScheduleArrangementDTO> listArrangements(String planId, String offeringId) {
            lastPlanId = planId;
            lastOfferingId = offeringId;
            fail();
            return List.of(arrangement());
        }

        @Override
        public List<ScheduleConflictDTO> checkArrangement(SaveArrangementRequestDTO request) {
            lastChecked = request;
            validateLikeService(request);
            fail();
            return List.of(conflict());
        }

        @Override
        public AdminOperationResultDTO<ScheduleArrangementDTO> save(String adminUid,
                SaveArrangementRequestDTO request) {
            lastAdminUid = adminUid;
            lastSaved = request;
            validateLikeService(request);
            fail();
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "教学安排已保存",
                    arrangement(), List.of(conflict()));
        }

        @Override
        public AdminOperationResultDTO<Void> delete(String adminUid, String arrangementId,
                int expectedVersion, String operationId) {
            lastAdminUid = adminUid;
            lastDeletedId = arrangementId;
            lastExpectedVersion = expectedVersion;
            requireUuid(operationId);
            fail();
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "教学安排已删除", null,
                    List.of());
        }

        @Override
        public AdminOperationResultDTO<SchedulePlanDTO> publish(String adminUid, String planId,
                int expectedRevision, String operationId, boolean force, String overrideReason) {
            lastAdminUid = adminUid;
            lastPublishedId = planId;
            lastExpectedRevision = expectedRevision;
            lastForce = force;
            lastReason = overrideReason;
            requireUuid(operationId);
            if (force && (overrideReason == null || overrideReason.isBlank())) {
                throw new IllegalArgumentException("强制发布必须填写原因");
            }
            fail();
            return new AdminOperationResultDTO<>(OPERATION_ID, "OK", "排课方案已发布", plan(),
                    List.of());
        }

        private void validateLikeService(SaveArrangementRequestDTO request) {
            if (!rejectTypedInput || request == null) return;
            requireUuid(request.getOperationId());
            if (request.getPlanId() == null || !request.getPlanId().matches("[0-9]+")) {
                throw new IllegalArgumentException("planId 必须为正整数");
            }
            if (request.isForce() && (request.getOverrideReason() == null
                    || request.getOverrideReason().isBlank())) {
                throw new IllegalArgumentException("强制保存必须填写原因");
            }
        }

        private static void requireUuid(String operationId) {
            if (rejectUuid(operationId)) {
                throw new IllegalArgumentException("operationId 必须是 UUID");
            }
        }

        private static boolean rejectUuid(String operationId) {
            if (operationId == null || operationId.length() != 36) return true;
            try {
                return !UUID.fromString(operationId).toString().equalsIgnoreCase(operationId);
            } catch (IllegalArgumentException failure) {
                return true;
            }
        }

        private void fail() {
            switch (mode) {
                case NOT_FOUND -> throw new NotFoundException("排课资源不存在");
                case CONFLICT -> throw new ConflictException("存在阻断性冲突，无法保存",
                        arrangement(), List.of(conflict()));
                case DATABASE -> throw new DatabaseException("SELECT secret FROM schedule failed");
                case UNEXPECTED -> throw new IllegalStateException("boom");
                case SUCCESS -> { }
            }
        }
    }
}
