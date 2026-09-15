package handler;

import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.AdminCourseCatalogService;
import service.AdminOfferingService;
import service.GradeApprovalService;
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler coverage for the grade approval surface: role gating, request parsing, the authenticated
 * administrator identity, the {@code gradeSubmissions}/{@code gradeSubmission}/{@code result}
 * response keys, force refusal and conflict propagation.
 *
 * <p>Mapping runs against a recording stand-in service, and the parsing cases run against the real
 * service whose validation rejects malformed input before it would ever open a connection, so this
 * suite stays free of the database.
 */
public final class GradeApprovalHandlerTest {
    private static final String SUBMISSION_ID = "9007199254740993";
    private static final String OPERATION_ID = "20000000-0000-0000-0000-000000000001";

    private GradeApprovalHandlerTest() {
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
            verifyDatabaseErrorIsSanitized(administrator);
        } finally {
            sessions.removeSession(administrator.getToken());
            sessions.removeSession(teacher.getToken());
            sessions.removeSession(student.getToken());
        }
        System.out.println("Grade approval handler test passed.");
    }

    private static void verifyAuthorization(UserSession administrator, UserSession teacher,
                                            UserSession student) {
        AdminCourseHandler handler = handler(new RecordingGradeService());
        require(handler.handle(request(AdminCourseActions.LIST_GRADE_SUBMISSIONS, null)).getCode()
                        == MessageCode.UNAUTHORIZED,
                "a missing token must be unauthorized");
        for (String action : new String[] {AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                AdminCourseActions.GET_GRADE_SUBMISSION,
                AdminCourseActions.REVIEW_GRADE_SUBMISSION}) {
            require(handler.handle(request(action, teacher.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a teacher must be forbidden from " + action);
            require(handler.handle(request(action, student.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a student must be forbidden from " + action);
        }
        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, true, null))
                        .getCode() != MessageCode.FORBIDDEN,
                "an administrator must reach the grade approval surface");
    }

    private static void verifyUnavailableWithoutTheService(UserSession administrator) {
        AdminCourseHandler legacy = new AdminCourseHandler(new AdminCourseCatalogService(),
                new AdminOfferingService());
        Message response = legacy.handle(request(AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                administrator.getToken()));
        require(response.getCode() == MessageCode.BAD_REQUEST
                        && "该管理员操作尚未开放".equals(response.getMessage()),
                "a handler without a grade service must report the operation as not open yet");
    }

    private static void verifyListMapping(UserSession administrator) {
        RecordingGradeService recording = new RecordingGradeService();
        AdminCourseHandler handler = handler(recording);

        Message listed = handler.handle(request(AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                administrator.getToken()));
        require(listed.getCode() == MessageCode.SUCCESS, "an unfiltered list must succeed"
                + (listed.getMessage() == null ? "" : ": " + listed.getMessage()));
        require(recording.lastStatus == null && recording.lastPage == 1
                        && recording.lastSize == 20,
                "an absent status must reach the service as null, which is its PENDING default");
        require(listed.getData().containsKey("gradeSubmissions")
                        && listed.getData().containsKey("totalCount")
                        && listed.getData().containsKey("pageNumber")
                        && listed.getData().containsKey("pageSize"),
                "the list must publish gradeSubmissions with its page metadata");
        require(Long.valueOf(7L).equals(listed.getData().get("totalCount"))
                        && Integer.valueOf(1).equals(listed.getData().get("pageNumber"))
                        && Integer.valueOf(20).equals(listed.getData().get("pageSize")),
                "the page metadata must come from the server page");

        Message filtered = request(AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                administrator.getToken());
        filtered.putData("status", "REJECTED");
        filtered.putData("pageNumber", 2);
        filtered.putData("pageSize", 5);
        require(handler.handle(filtered).getCode() == MessageCode.SUCCESS,
                "an explicit status filter must succeed");
        require(recording.lastStatus == ApprovalStatusDTO.REJECTED && recording.lastPage == 2
                        && recording.lastSize == 5,
                "the explicit status and paging must reach the service");
    }

    private static void verifyDetailMapping(UserSession administrator) {
        RecordingGradeService recording = new RecordingGradeService();
        AdminCourseHandler handler = handler(recording);

        Message detail = request(AdminCourseActions.GET_GRADE_SUBMISSION,
                administrator.getToken());
        detail.putData("submissionId", SUBMISSION_ID);
        Message response = handler.handle(detail);
        require(response.getCode() == MessageCode.SUCCESS, "a detail read must succeed"
                + (response.getMessage() == null ? "" : ": " + response.getMessage()));
        require(SUBMISSION_ID.equals(recording.lastSubmissionId),
                "the submission ID must reach the service as the exact decimal string");
        require(response.getData().containsKey("gradeSubmission"),
                "the detail must use the gradeSubmission key");
    }

    private static void verifyReviewMapping(UserSession administrator) {
        RecordingGradeService recording = new RecordingGradeService();
        AdminCourseHandler handler = handler(recording);

        Message message = decision(administrator.getToken(), OPERATION_ID, true, "  同意  ");
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
                        && SUBMISSION_ID.equals(decision.getRequestId())
                        && decision.getExpectedVersion() == 3
                        && decision.isApproved()
                        && "  同意  ".equals(decision.getReviewComment()),
                "the decision DTO must carry typed values through unchanged; normalizing them is the"
                        + " service's single responsibility");
        require(!decision.isForce() && decision.getOverrideReason() == null,
                "grade approval must never forward force or an override reason");
        require(response.getData().containsKey("result"),
                "a decision must use the result response key");
        require("成绩提交已通过".equals(response.getMessage()),
                "the response message must come from the operation result");
    }

    private static void verifyRejections(UserSession administrator) {
        AdminCourseHandler handler = handler(new GradeApprovalService());

        require(handler.handle(decision(administrator.getToken(), OPERATION_ID, false, "   "))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a rejection without a comment must be a bad request");
        require(handler.handle(forced(administrator.getToken(), true, null)).getCode()
                        == MessageCode.BAD_REQUEST,
                "force = true must be a bad request for grade approval");

        RecordingGradeService recording = new RecordingGradeService();
        require(handler(recording).handle(forced(administrator.getToken(), false, null)).getCode()
                        == MessageCode.SUCCESS && !recording.lastDecision.isForce()
                        && recording.lastDecision.getOverrideReason() == null,
                "force = false must be accepted and never forwarded as a forced decision");
        require(handler.handle(decision(administrator.getToken(), "not-a-uuid", true, null))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a non-UUID operationId must be a bad request");
        Map<String, Object> zeroVersionPayload = decisionPayload(OPERATION_ID, true, null);
        zeroVersionPayload.put("expectedVersion", 0);
        Message zeroVersion = request(AdminCourseActions.REVIEW_GRADE_SUBMISSION,
                administrator.getToken());
        zeroVersion.putData("request", zeroVersionPayload);
        require(handler.handle(zeroVersion).getCode() == MessageCode.BAD_REQUEST,
                "a non-positive expectedVersion must be a bad request");

        Map<String, Object> nonBooleanPayload = decisionPayload(OPERATION_ID, true, null);
        nonBooleanPayload.put("approved", "yes");
        Message nonBoolean = request(AdminCourseActions.REVIEW_GRADE_SUBMISSION,
                administrator.getToken());
        nonBoolean.putData("request", nonBooleanPayload);
        require(handler.handle(nonBoolean).getCode() == MessageCode.BAD_REQUEST,
                "a non-boolean approved must be rejected before Gson can coerce it");

        Message badPage = request(AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                administrator.getToken());
        badPage.putData("pageNumber", 0);
        badPage.putData("pageSize", 20);
        require(handler.handle(badPage).getCode() == MessageCode.BAD_REQUEST,
                "pageNumber 0 must be a bad request");
        badPage.putData("pageNumber", 1);
        badPage.putData("pageSize", 101);
        require(handler.handle(badPage).getCode() == MessageCode.BAD_REQUEST,
                "pageSize above 100 must be a bad request");

        Message unknownStatus = request(AdminCourseActions.LIST_GRADE_SUBMISSIONS,
                administrator.getToken());
        unknownStatus.putData("status", "BOGUS");
        require(handler.handle(unknownStatus).getCode() == MessageCode.BAD_REQUEST,
                "an unknown status must be a bad request");

        Message badDetail = request(AdminCourseActions.GET_GRADE_SUBMISSION,
                administrator.getToken());
        badDetail.putData("submissionId", "abc");
        require(handler.handle(badDetail).getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal submission ID must be a bad request");
    }

    private static void verifyConflictAndNotFound(UserSession administrator) {
        RecordingGradeService recording = new RecordingGradeService();
        recording.detailFailure = new GradeApprovalService.ConflictException(
                "成绩提交已被处理，请刷新后重试", detail());
        Message conflicted = handler(recording).handle(decision(administrator.getToken(),
                OPERATION_ID, true, null));
        require(conflicted.getCode() == MessageCode.CONFLICT,
                "a conflict must use the conflict code");
        require(conflicted.getData().get("latest") != null,
                "a conflict must carry the latest typed detail");

        RecordingGradeService bareConflict = new RecordingGradeService();
        bareConflict.detailFailure = new GradeApprovalService.ConflictException(
                "operationId 已用于不同的业务请求");
        Message nullLatest = handler(bareConflict).handle(decision(administrator.getToken(),
                OPERATION_ID, true, null));
        require(nullLatest.getCode() == MessageCode.CONFLICT
                        && nullLatest.getData().get("latest") == null,
                "a defensive conflict with a null entity must not emit a latest payload");

        RecordingGradeService missing = new RecordingGradeService();
        missing.detailFailure = new GradeApprovalService.NotFoundException("成绩提交不存在");
        Message notFound = handler(missing).handle(decision(administrator.getToken(), OPERATION_ID,
                true, null));
        require(notFound.getCode() == MessageCode.NOT_FOUND
                        && "成绩提交不存在".equals(notFound.getMessage()),
                "a missing submission must be reported as not found");
    }

    private static void verifyDatabaseErrorIsSanitized(UserSession administrator) {
        RecordingGradeService recording = new RecordingGradeService();
        recording.detailFailure = new DatabaseException("查询成绩提交失败",
                new SQLException("SELECT secret FROM grade_submission"));
        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message database;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            database = handler(recording).handle(decision(administrator.getToken(), OPERATION_ID,
                    true, null));
        } finally {
            System.setErr(previousError);
        }
        require(database.getCode() == MessageCode.ERROR,
                "a database failure must use the error code");
        require("课程管理服务暂不可用".equals(database.getMessage())
                        && !database.getMessage().contains("SELECT"),
                "an unsanitized database failure must surface as the sanitized message");
    }

    // ------------------------------------------------------------------ helpers

    private static AdminCourseHandler handler(GradeApprovalService grades) {
        return new AdminCourseHandler(new AdminCourseCatalogService(), new AdminOfferingService(),
                null, null, null, grades);
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseAdmin", action);
        request.setToken(token);
        if (AdminCourseActions.LIST_GRADE_SUBMISSIONS.equals(action)) {
            request.putData("pageNumber", 1);
            request.putData("pageSize", 20);
        }
        return request;
    }

    private static Message decision(String token, String operationId, boolean approved,
                                    String reviewComment) {
        Message message = request(AdminCourseActions.REVIEW_GRADE_SUBMISSION, token);
        message.putData("request", decisionPayload(operationId, approved, reviewComment));
        return message;
    }

    /** A decision carrying an explicit force flag, which grade approval must refuse when true. */
    private static Message forced(String token, boolean force, String reviewComment) {
        Message message = request(AdminCourseActions.REVIEW_GRADE_SUBMISSION, token);
        Map<String, Object> payload = decisionPayload(OPERATION_ID, true, reviewComment);
        payload.put("force", force);
        message.putData("request", payload);
        return message;
    }

    private static Map<String, Object> decisionPayload(String operationId, boolean approved,
                                                       String reviewComment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", operationId);
        payload.put("requestId", SUBMISSION_ID);
        payload.put("expectedVersion", 3);
        payload.put("approved", approved);
        payload.put("reviewComment", reviewComment);
        return payload;
    }

    private static GradeSubmissionDetailDTO detail() {
        GradeSubmissionSummaryDTO summary = new GradeSubmissionSummaryDTO(SUBMISSION_ID, "1001",
                "数据结构", "OFF-1001", 1, "T1001", "张老师", 2, 85.0, 90.0, 80.0, 0,
                ApprovalStatusDTO.PENDING, "2026-09-10T02:00:00Z");
        List<GradeDistributionBucketDTO> distribution = List.of(
                new GradeDistributionBucketDTO("90-100", 1),
                new GradeDistributionBucketDTO("80-89", 1),
                new GradeDistributionBucketDTO("70-79", 0),
                new GradeDistributionBucketDTO("60-69", 0),
                new GradeDistributionBucketDTO("0-59", 0));
        List<GradeSubmissionItemDTO> items = List.of(
                new GradeSubmissionItemDTO("8001", "20240031", "陈晨", 88.0, 86.0, null, 84.0, 85.0,
                        3, 3.5),
                new GradeSubmissionItemDTO("8002", "20240032", "林晓", null, null, null, null, null,
                        null, null));
        return new GradeSubmissionDetailDTO(summary, distribution, items, null, null, null);
    }

    private static GradeSubmissionSummaryDTO summary() {
        return new GradeSubmissionSummaryDTO(SUBMISSION_ID, "1001", "数据结构", "OFF-1001", 1,
                "T1001", "张老师", 2, 85.0, 90.0, 80.0, 0, ApprovalStatusDTO.PENDING,
                "2026-09-10T02:00:00Z");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Records what the handler passed down and serves one canned page, detail and outcome. */
    private static final class RecordingGradeService extends GradeApprovalService {
        private ApprovalStatusDTO lastStatus;
        private int lastPage;
        private int lastSize;
        private String lastSubmissionId;
        private String lastAdminUid;
        private ApprovalDecisionRequestDTO lastDecision;
        private RuntimeException detailFailure;

        @Override
        public GradeSubmissionPageDTO listGradeSubmissionsPage(ApprovalStatusDTO status, int page,
                                                               int size) {
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            List<GradeSubmissionSummaryDTO> items = new ArrayList<>();
            items.add(summary());
            return new GradeSubmissionPageDTO(items, 7L, page, size);
        }

        @Override
        public GradeSubmissionDetailDTO getGradeSubmission(String submissionId) {
            lastSubmissionId = submissionId;
            return detail();
        }

        @Override
        public AdminOperationResultDTO<GradeSubmissionDetailDTO> review(String adminUid,
                ApprovalDecisionRequestDTO request) {
            lastAdminUid = adminUid;
            lastDecision = request;
            if (detailFailure != null) throw detailFailure;
            return new AdminOperationResultDTO<>(request.getOperationId(), "OK", "成绩提交已通过",
                    detail(), List.of());
        }
    }
}
