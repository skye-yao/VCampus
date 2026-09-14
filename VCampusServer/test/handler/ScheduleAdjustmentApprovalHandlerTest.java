package handler;

import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminOfferingService;
import service.ScheduleAdjustmentApprovalService;
import session.SessionManager;
import session.UserSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Handler coverage for the adjustment approval surface: role gating, request parsing, the
 * authenticated administrator identity, response keys and conflict propagation.
 *
 * <p>Mapping runs against a recording stand-in service, and the parsing cases run against the real
 * service whose validation rejects malformed input before it would ever open a connection, so this
 * suite stays free of the database.
 */
public final class ScheduleAdjustmentApprovalHandlerTest {
    private static final String REQUEST_ID = "9007199254740993";
    private static final String OPERATION_ID = "20000000-0000-0000-0000-000000000001";

    private ScheduleAdjustmentApprovalHandlerTest() {
    }

    public static void main(String[] args) {
        SessionManager sessions = SessionManager.getInstance();
        UserSession administrator = sessions.createSession("admin-alpha", "管理员");
        UserSession teacher = sessions.createSession("teacher-alpha", "教师");
        UserSession student = sessions.createSession("student-alpha", "学生");
        try {
            verifyAuthorization(administrator, teacher, student);
            verifyUnavailableWithoutTheService(administrator);
            verifyListMapping(administrator);
            verifyDetailMapping(administrator);
            verifyReviewMapping(administrator);
            verifyRejections(administrator);
            verifyConflictAndNotFound(administrator);
        } finally {
            sessions.removeSession(administrator.getToken());
            sessions.removeSession(teacher.getToken());
            sessions.removeSession(student.getToken());
        }
        System.out.println("Schedule adjustment approval handler test passed.");
    }

    private static void verifyAuthorization(UserSession administrator, UserSession teacher,
                                            UserSession student) {
        AdminCourseHandler handler = handler(new RecordingAdjustmentService());
        require(handler.handle(request("listAdjustmentRequests", null)).getCode()
                        == MessageCode.UNAUTHORIZED,
                "a missing token must be unauthorized");
        for (String action : new String[] {AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                AdminCourseActions.GET_ADJUSTMENT_REQUEST,
                AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST}) {
            require(handler.handle(request(action, teacher.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a teacher must be forbidden from " + action);
            require(handler.handle(request(action, student.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a student must be forbidden from " + action);
        }
        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, true, false, null,
                        null)).getCode() != MessageCode.FORBIDDEN,
                "an administrator must reach the adjustment surface");
    }

    private static void verifyUnavailableWithoutTheService(UserSession administrator) {
        AdminCourseHandler legacy = new AdminCourseHandler(new AdminCourseCatalogService(),
                new AdminOfferingService());
        Message response = legacy.handle(request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                administrator.getToken()));
        require(response.getCode() == MessageCode.BAD_REQUEST
                        && "该管理员操作尚未开放".equals(response.getMessage()),
                "a handler without an adjustment service must report the operation as not open yet");
    }

    private static void verifyListMapping(UserSession administrator) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        AdminCourseHandler handler = handler(recording);

        Message listed = handler.handle(request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                administrator.getToken()));
        require(listed.getCode() == MessageCode.SUCCESS, "an unfiltered list must succeed"
                + (listed.getMessage() == null ? "" : ": " + listed.getMessage()));
        require(recording.lastStatus == null && recording.lastPage == 1
                        && recording.lastSize == 20,
                "an absent status must reach the service as null, which is its PENDING default");
        require(listed.getData().containsKey("adjustmentRequests")
                        && listed.getData().containsKey("totalCount")
                        && listed.getData().containsKey("pageNumber")
                        && listed.getData().containsKey("pageSize"),
                "the list must publish adjustmentRequests with its page metadata");
        require(Long.valueOf(7L).equals(listed.getData().get("totalCount"))
                        && Integer.valueOf(1).equals(listed.getData().get("pageNumber"))
                        && Integer.valueOf(20).equals(listed.getData().get("pageSize")),
                "the page metadata must come from the server page");

        Message filtered = request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                administrator.getToken());
        filtered.putData("status", "APPROVED");
        filtered.putData("pageNumber", 2);
        filtered.putData("pageSize", 5);
        require(handler.handle(filtered).getCode() == MessageCode.SUCCESS,
                "an explicit status filter must succeed");
        require(recording.lastStatus == AdjustmentRequestStatusDTO.APPROVED && recording.lastPage == 2
                        && recording.lastSize == 5,
                "the explicit status and paging must reach the service");
    }

    private static void verifyDetailMapping(UserSession administrator) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        AdminCourseHandler handler = handler(recording);

        Message detail = request(AdminCourseActions.GET_ADJUSTMENT_REQUEST,
                administrator.getToken());
        detail.putData("requestId", REQUEST_ID);
        Message response = handler.handle(detail);
        require(response.getCode() == MessageCode.SUCCESS, "a detail read must succeed"
                + (response.getMessage() == null ? "" : ": " + response.getMessage()));
        require(REQUEST_ID.equals(recording.lastRequestId),
                "the request ID must reach the service as the exact decimal string");
        require(response.getData().containsKey("adjustmentRequest"),
                "the detail must use the adjustmentRequest key");
    }

    private static void verifyReviewMapping(UserSession administrator) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        AdminCourseHandler handler = handler(recording);

        Message message = decision(administrator.getToken(), OPERATION_ID, true, true,
                "  已协调教师  ", "  同意  ");
        message.setSender("attacker");
        message.putData("uid", "admin-beta");
        message.putData("adminUid", "admin-beta");
        Message response = handler.handle(message);
        require(response.getCode() == MessageCode.SUCCESS, "a decision must succeed"
                + (response.getMessage() == null ? "" : ": " + response.getMessage()));
        require("admin-alpha".equals(recording.lastAdminUid)
                        && !"admin-beta".equals(recording.lastAdminUid),
                "the reviewer identity must come from the authenticated session only");
        ApprovalDecisionRequestDTO decision = recording.lastDecision;
        require(decision != null && OPERATION_ID.equals(decision.getOperationId())
                        && REQUEST_ID.equals(decision.getRequestId())
                        && decision.getExpectedVersion() == 3
                        && decision.isApproved() && decision.isForce()
                        && "  已协调教师  ".equals(decision.getOverrideReason())
                        && "  同意  ".equals(decision.getReviewComment()),
                "the decision DTO must carry typed values through unchanged; normalizing them is the"
                        + " service's single responsibility");
        require(response.getData().containsKey("result"),
                "a decision must use the result response key");
        require("调课申请已通过".equals(response.getMessage()),
                "the response message must come from the operation result");
    }

    private static void verifyRejections(UserSession administrator) {
        AdminCourseHandler handler = handler(new ScheduleAdjustmentApprovalService());

        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, false, false, null,
                        "   ")).getCode() == MessageCode.BAD_REQUEST,
                "a rejection without a comment must be a bad request");
        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, false, true,
                        "   ", null)).getCode() == MessageCode.BAD_REQUEST,
                "a forced rejection must be a bad request");
        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, true, true, "   ",
                        null)).getCode() == MessageCode.BAD_REQUEST,
                "force without a reason must be a bad request");
        require(handler.handle(decision(administrator.getToken(), "not-a-uuid", true, false, null,
                        null)).getCode() == MessageCode.BAD_REQUEST,
                "a non-UUID operationId must be a bad request");
        Map<String, Object> zeroVersionPayload =
                decisionPayload(OPERATION_ID, true, false, null, null);
        zeroVersionPayload.put("expectedVersion", 0);
        Message zeroVersion = request(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST,
                administrator.getToken());
        zeroVersion.putData("request", zeroVersionPayload);
        require(handler.handle(zeroVersion).getCode() == MessageCode.BAD_REQUEST,
                "a non-positive expectedVersion must be a bad request");

        Map<String, Object> nonBooleanPayload =
                decisionPayload(OPERATION_ID, true, false, null, null);
        nonBooleanPayload.put("force", "yes");
        Message nonBoolean = request(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST,
                administrator.getToken());
        nonBoolean.putData("request", nonBooleanPayload);
        require(handler.handle(nonBoolean).getCode() == MessageCode.BAD_REQUEST,
                "a non-boolean force must be rejected before Gson can coerce it");

        Message badPage = request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                administrator.getToken());
        badPage.putData("pageNumber", 0);
        badPage.putData("pageSize", 20);
        require(handler.handle(badPage).getCode() == MessageCode.BAD_REQUEST,
                "pageNumber 0 must be a bad request");
        badPage.putData("pageNumber", 1);
        badPage.putData("pageSize", 101);
        require(handler.handle(badPage).getCode() == MessageCode.BAD_REQUEST,
                "pageSize above 100 must be a bad request");

        Message unknownStatus = request(AdminCourseActions.LIST_ADJUSTMENT_REQUESTS,
                administrator.getToken());
        unknownStatus.putData("status", "BOGUS");
        require(handler.handle(unknownStatus).getCode() == MessageCode.BAD_REQUEST,
                "an unknown status must be a bad request");

        Message badDetail = request(AdminCourseActions.GET_ADJUSTMENT_REQUEST,
                administrator.getToken());
        badDetail.putData("requestId", "abc");
        require(handler.handle(badDetail).getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal request ID must be a bad request");
    }

    private static void verifyConflictAndNotFound(UserSession administrator) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        recording.detailFailure = new ScheduleAdjustmentApprovalService.ConflictException(
                "存在阻断性冲突，无法通过调课申请", detail(), List.of(blocking(), overridable()));
        Message conflicted = handler(recording).handle(decision(administrator.getToken(),
                OPERATION_ID, true, false, null, null));
        require(conflicted.getCode() == MessageCode.CONFLICT,
                "a conflict must use the conflict code");
        require(conflicted.getData().get("conflicts") != null
                        && conflicted.getData().get("latest") != null,
                "a conflict must carry both the typed conflicts and the latest detail");

        RecordingAdjustmentService missing = new RecordingAdjustmentService();
        missing.detailFailure = new ScheduleAdjustmentApprovalService.NotFoundException("调课申请不存在");
        Message notFound = handler(missing).handle(decision(administrator.getToken(), OPERATION_ID,
                true, false, null, null));
        require(notFound.getCode() == MessageCode.NOT_FOUND
                        && "调课申请不存在".equals(notFound.getMessage()),
                "a missing request must be reported as not found");
    }

    // ------------------------------------------------------------------ helpers

    private static AdminCourseHandler handler(ScheduleAdjustmentApprovalService adjustments) {
        return new AdminCourseHandler(new AdminCourseCatalogService(), new AdminOfferingService(),
                null, null, adjustments);
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseAdmin", action);
        request.setToken(token);
        if (AdminCourseActions.LIST_ADJUSTMENT_REQUESTS.equals(action)) {
            request.putData("pageNumber", 1);
            request.putData("pageSize", 20);
        }
        return request;
    }

    private static Message decision(String token, String operationId, boolean approved,
                                    boolean force, String overrideReason, String reviewComment) {
        Message message = request(AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST, token);
        message.putData("request",
                decisionPayload(operationId, approved, force, overrideReason, reviewComment));
        return message;
    }

    private static Map<String, Object> decisionPayload(String operationId, boolean approved,
                                                       boolean force, String overrideReason,
                                                       String reviewComment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", operationId);
        payload.put("requestId", REQUEST_ID);
        payload.put("expectedVersion", 3);
        payload.put("approved", approved);
        payload.put("force", force);
        payload.put("overrideReason", overrideReason);
        payload.put("reviewComment", reviewComment);
        return payload;
    }

    private static AdjustmentRequestDetailDTO detail() {
        return new AdjustmentRequestDetailDTO(REQUEST_ID, "2001", "T1001", "临时调课",
                AdjustmentRequestStatusDTO.PENDING, 3, 5, 1, 2,
                new ScheduleResourceDTO("T2001", "T2001", "李老师", "teacher", 0), null,
                new ScheduleResourceDTO("3001", "3001", "A-101", "classroom", 120),
                List.of(new AdjustmentTargetDTO("8001", 1, "2026-09-08T00:00:00Z",
                        "2026-09-08T01:35:00Z", "张老师", null, "A-101")),
                List.of(overridable()), "2026-09-10T02:00:00Z", null, null, null);
    }

    private static ScheduleConflictDTO overridable() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP", OVERRIDABLE, "T2001", "2001", 1, 5, 1, 2,
                "任课教师在该时间已有其他课程");
    }

    private static ScheduleConflictDTO blocking() {
        return new ScheduleConflictDTO("OFFERING_OVERLAP", BLOCKING, "2001", "2001", 1, 5, 1, 2,
                "同一教学班在该时间已有排课");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Records what the handler passed down and serves one canned page, detail and outcome. */
    private static final class RecordingAdjustmentService
            extends ScheduleAdjustmentApprovalService {
        private AdjustmentRequestStatusDTO lastStatus;
        private int lastPage;
        private int lastSize;
        private String lastRequestId;
        private String lastAdminUid;
        private ApprovalDecisionRequestDTO lastDecision;
        private RuntimeException detailFailure;

        @Override
        public AdjustmentRequestPageDTO listRequests(AdjustmentRequestStatusDTO status, int page, int size) {
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            List<AdjustmentRequestSummaryDTO> items = new ArrayList<>();
            items.add(new AdjustmentRequestSummaryDTO(REQUEST_ID, "数据结构", "OFF-1", "T1001",
                    "张老师", 2, AdjustmentRequestStatusDTO.PENDING, "2026-09-10T02:00:00Z"));
            return new AdjustmentRequestPageDTO(items, 7L, page, size);
        }

        @Override
        public AdjustmentRequestDetailDTO getRequest(String requestId) {
            lastRequestId = requestId;
            return detail();
        }

        @Override
        public AdminOperationResultDTO<AdjustmentRequestDetailDTO> review(String adminUid,
                ApprovalDecisionRequestDTO request) {
            lastAdminUid = adminUid;
            lastDecision = request;
            if (detailFailure != null) throw detailFailure;
            return new AdminOperationResultDTO<>(request.getOperationId(), "OK", "调课申请已通过",
                    detail(), List.of(overridable()));
        }
    }
}
