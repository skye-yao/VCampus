package handler;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
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
import java.util.Set;

/**
 * 教师调课接口（courseTeacher 模块的六个调课动作）的鉴权、身份来源、响应键与错误映射矩阵。
 *
 * <p>与 {@link ScheduleAdjustmentApprovalHandlerTest} 同形：映射类断言跑在记录型假服务上；
 * 解析边界跑在真实 {@link TeacherAdjustmentApplicationService} 上——它的校验全部发生在地库
 * 连接之前，所以非法输入在这里必然先以 BAD_REQUEST 结束，本套件保持无数据库。
 *
 * <p>关键边界：教师 UID 只取自 Session；{@code data.request} 里任何 uid/教师助教字段/force 都
 * 是伪造，直接 BAD_REQUEST 且绝不进入服务；预览忽略 operationId，写操作由服务校验 UUID。
 */
public final class TeacherAdjustmentHandlerTest {
    private static final String TEACHER_A = "teacher-a";
    private static final String TEACHER_B = "teacher-b";
    private static final String OFFERING_ID = "9007199254740993";
    private static final String OCCURRENCE_ID = "9201";
    private static final String REQUEST_ID = "9007199254740994";
    private static final String OPERATION_ID = "30000000-0000-0000-0000-000000000001";
    private static final String CALENDAR_ID = "9007199254740001";
    private static final String SUBMITTED_MESSAGE = "调课申请已提交";

    private TeacherAdjustmentHandlerTest() {
    }

    public static void main(String[] args) {
        SessionManager sessions = SessionManager.getInstance();
        UserSession teacherA = sessions.createSession(TEACHER_A, "教师");
        UserSession student = sessions.createSession("student-a", "学生");
        UserSession administrator = sessions.createSession("admin-a", "管理员");
        try {
            verifyAuthorization(teacherA, student, administrator);
            verifySessionIdentityAndForgedFields(teacherA);
            verifyResponseKeys(teacherA);
            verifyArgumentPassing(teacherA);
            verifyPagingAndStatusParsing(teacherA);
            verifyWriteParsingBoundariesWithTheRealService(teacherA);
            verifyExceptionMapping(teacherA);
            verifyThinWritePath(teacherA);
            verifyUnavailableWithoutTheAdjustmentService(teacherA);
        } finally {
            sessions.removeSession(teacherA.getToken());
            sessions.removeSession(student.getToken());
            sessions.removeSession(administrator.getToken());
        }
        System.out.println("Teacher adjustment handler test passed.");
    }

    private static void verifyAuthorization(UserSession teacher, UserSession student,
            UserSession administrator) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        require(handler.handle(request(TeacherCourseActions.PREVIEW_ADJUSTMENT, null)).getCode()
                        == MessageCode.UNAUTHORIZED,
                "a missing token must be unauthorized");
        require(handler.handle(request(TeacherCourseActions.PREVIEW_ADJUSTMENT, "expired-token"))
                        .getCode() == MessageCode.UNAUTHORIZED,
                "an expired token must be unauthorized");
        for (String action : ALL_ACTIONS) {
            require(handler.handle(request(action, student.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a student must be forbidden from " + action);
            require(handler.handle(request(action, administrator.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "an administrator must be forbidden from " + action);
        }
        require(recording.calls == 0,
                "a rejected request must not reach the adjustment service");
        require(handler.handle(request("   ", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "a blank action must be a bad request");
        require(handler.handle(request("deleteAdjustment", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown action must be a bad request");

        Message options = handler.handle(
                optionsRequest(teacher.getToken(), OFFERING_ID, OCCURRENCE_ID));
        require(options.getCode() == MessageCode.SUCCESS,
                "a teacher must reach the adjustment surface: " + options.getMessage());
        require(TEACHER_A.equals(recording.lastUid),
                "the adjustment service must run as the session teacher");
    }

    /**
     * 会话身份优先于请求体；{@code data.request} 里出现的身份/人员/强制字段是协议外伪造，
     * 必须直接 BAD_REQUEST，且一次也不能进入服务。
     */
    private static void verifySessionIdentityAndForgedFields(UserSession teacher) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        Message forgedIdentity = writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                teacher.getToken(), writePayload());
        forgedIdentity.putData("uid", TEACHER_B);
        forgedIdentity.putData("teacherId", TEACHER_B);
        forgedIdentity.setSender(TEACHER_B);
        Message submitted = handler.handle(forgedIdentity);
        require(submitted.getCode() == MessageCode.SUCCESS,
                "a top-level forged identity must not break the write: " + submitted.getMessage());
        require(TEACHER_A.equals(recording.lastUid),
                "the write must run as the session teacher, never as the forged uid");
        require(recording.submitCalls == 1, "the write must reach the service exactly once");

        for (String forged : new String[] {"uid", "teacherId", "teacherUid", "newTeacherUid",
                "assistantId", "assistantUid", "newAssistantUid"}) {
            Map<String, Object> payload = writePayload();
            payload.put(forged, TEACHER_B);
            int before = recording.submitCalls;
            Message response = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "a forged " + forged + " in data.request must be a bad request, saw "
                            + response.getCode());
            require(recording.submitCalls == before,
                    "a forged " + forged + " must never reach the service");
        }

        for (Object force : new Object[] {true, false, "yes"}) {
            Map<String, Object> payload = writePayload();
            payload.put("force", force);
            int before = recording.submitCalls;
            Message response = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "a teacher write carrying force=" + force + " must be a bad request");
            require(recording.submitCalls == before,
                    "force must never reach the service, not even as false");
        }

        Message previewForged = handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT,
                teacher.getToken(), payloadWith("force", true)));
        require(previewForged.getCode() == MessageCode.BAD_REQUEST,
                "a preview carrying force must be a bad request");

        Map<String, Object> withdrawal = withdrawPayload();
        withdrawal.put("uid", TEACHER_B);
        Message forgedWithdraw = handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                teacher.getToken(), withdrawal));
        require(forgedWithdraw.getCode() == MessageCode.BAD_REQUEST,
                "a forged uid on withdraw must be a bad request");
        withdrawal = withdrawPayload();
        withdrawal.put("force", true);
        require(handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                        teacher.getToken(), withdrawal)).getCode() == MessageCode.BAD_REQUEST,
                "force on withdraw must be a bad request");
        require(recording.withdrawCalls == 0,
                "forged withdraw bodies must never reach the service");
    }

    private static void verifyResponseKeys(UserSession teacher) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        Message options = handler.handle(
                optionsRequest(teacher.getToken(), OFFERING_ID, OCCURRENCE_ID));
        require(options.getCode() == MessageCode.SUCCESS, "options must succeed");
        requireOnlyKey(options, "options");
        require(options.getData("options") instanceof TeacherAdjustmentOptionsDTO,
                "the options key must carry the options DTO");

        Message preview = handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT,
                teacher.getToken(), writePayload()));
        require(preview.getCode() == MessageCode.SUCCESS, "preview must succeed");
        requireOnlyKey(preview, "conflicts");
        require(preview.getData("conflicts") instanceof TeacherAdjustmentPreviewDTO,
                "the conflicts key must carry the preview DTO with its canSubmit flag");

        Message detail = handler.handle(requestWith(TeacherCourseActions.GET_ADJUSTMENT_REQUEST,
                teacher.getToken(), "requestId", REQUEST_ID));
        require(detail.getCode() == MessageCode.SUCCESS, "a detail read must succeed");
        requireOnlyKey(detail, "adjustmentRequest");
        require(detail.getData("adjustmentRequest") instanceof AdjustmentRequestDetailDTO,
                "the adjustmentRequest key must carry the detail DTO");

        Message applications = handler.handle(listRequest(teacher.getToken(), null, 1, 20));
        require(applications.getCode() == MessageCode.SUCCESS, "the personal list must succeed");
        require(applications.getData().containsKey("applications"),
                "the personal list must use the applications key");
        require(applications.getData("applications") instanceof TeacherPageDTO,
                "the applications key must carry the generic page object");
        require(recording.lastPage == 1 && recording.lastSize == 20,
                "the paging arguments must reach the service");

        Message submitted = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                teacher.getToken(), writePayload()));
        require(submitted.getCode() == MessageCode.SUCCESS, "a submit must succeed");
        requireOnlyKey(submitted, "result");
        require(submitted.getData("result") instanceof TeacherOperationResultDTO,
                "a write must use the result key with the operation result DTO");
        require(SUBMITTED_MESSAGE.equals(submitted.getMessage()),
                "the response message must come from the operation result, saw "
                        + submitted.getMessage());

        Message withdrawn = handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                teacher.getToken(), withdrawPayload()));
        require(withdrawn.getCode() == MessageCode.SUCCESS, "a withdraw must succeed");
        requireOnlyKey(withdrawn, "result");
    }

    private static void verifyArgumentPassing(UserSession teacher) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        handler.handle(optionsRequest(teacher.getToken(), OFFERING_ID, OCCURRENCE_ID));
        require(OFFERING_ID.equals(recording.lastOfferingId),
                "the offeringId must reach the service as the exact decimal string");
        require(OCCURRENCE_ID.equals(recording.lastOccurrenceId),
                "the originalOccurrenceId must reach the service as the exact decimal string");

        handler.handle(listRequest(teacher.getToken(), AdjustmentRequestStatusDTO.WITHDRAWN, 3, 7));
        require(recording.lastStatus == AdjustmentRequestStatusDTO.WITHDRAWN,
                "the four-state status filter must reach the service");
        require(recording.lastPage == 3 && recording.lastSize == 7,
                "the explicit paging must reach the service");

        Map<String, Object> payload = writePayload();
        handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT, teacher.getToken(),
                payload));
        TeacherAdjustmentWriteDTO write = recording.lastWrite;
        require(write != null, "the preview must receive the write DTO");
        require(OCCURRENCE_ID.equals(write.getTargets().get(0).getOriginalOccurrenceId())
                        && "2026-11-04".equals(write.getTargets().get(0).getTargetDate()),
                "the target occurrence and ISO date must round-trip");
        require(write.getNewStartPeriod() == 3 && write.getNewEndPeriod() == 4,
                "the new period span must round-trip");
        require("8103".equals(write.getNewClassroomId()),
                "the proposed classroom must round-trip as the exact decimal string");
        require("教师出差".equals(write.getReason()), "the reason must round-trip");
        require("not-a-uuid".equals(write.getOperationId()),
                "the preview must ignore, not rewrite, the operationId");

        Map<String, Object> operationIdFree = writePayload();
        operationIdFree.remove("operationId");
        handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT, teacher.getToken(),
                operationIdFree));
        require(recording.lastWrite.getOperationId() == null,
                "a preview without an operationId must stay null");

        handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT, teacher.getToken(),
                withdrawPayload()));
        WithdrawTeacherAdjustmentRequestDTO withdrawal = recording.lastWithdrawal;
        require(withdrawal != null && OPERATION_ID.equals(withdrawal.getOperationId()),
                "the withdraw operationId must round-trip");
        require(REQUEST_ID.equals(withdrawal.getRequestId()),
                "the withdraw requestId must round-trip as the exact decimal string");
        require(withdrawal.getExpectedVersion() == 3,
                "the expected version must round-trip");
    }

    private static void verifyPagingAndStatusParsing(UserSession teacher) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        Message missingPaging = handler.handle(
                request(TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS, teacher.getToken()));
        require(missingPaging.getCode() == MessageCode.BAD_REQUEST,
                "a list without paging must be a bad request");

        for (Object[] paging : new Object[][] {{0, 20}, {-1, 20}, {1, 0}, {1, 101}, {1.5, 20},
                {"abc", 20}, {2000000000, 100}}) {
            Message response = handler.handle(
                    listRequestRaw(teacher.getToken(), null, paging[0], paging[1]));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "illegal paging " + paging[0] + "/" + paging[1] + " must be a bad request, saw "
                            + response.getCode() + ": " + response.getMessage());
        }
        require(recording.listCalls == 0,
                "paging must be validated before the service is called");

        Message unknownStatus = listRequest(teacher.getToken(), null, 1, 20);
        unknownStatus.putData("status", "BOGUS");
        require(handler.handle(unknownStatus).getCode() == MessageCode.BAD_REQUEST,
                "an unknown status must be a bad request");
        Message lowercase = listRequest(teacher.getToken(), null, 1, 20);
        lowercase.putData("status", "withdrawn");
        require(handler.handle(lowercase).getCode() == MessageCode.BAD_REQUEST,
                "a lowercase status must be a bad request");
        require(recording.listCalls == 0,
                "an invalid status must never reach the service");

        for (AdjustmentRequestStatusDTO status : AdjustmentRequestStatusDTO.values()) {
            Message response = handler.handle(listRequest(teacher.getToken(), status, 1, 20));
            require(response.getCode() == MessageCode.SUCCESS,
                    "the four-state filter " + status + " must be accepted");
            require(recording.lastStatus == status,
                    "the accepted filter must reach the service: " + status);
        }
        handler.handle(listRequest(teacher.getToken(), null, 1, 20));
        require(recording.lastStatus == null,
                "an absent status must reach the service as null, which keeps its PENDING default");
    }

    private static void verifyWriteParsingBoundariesWithTheRealService(UserSession teacher) {
        TeacherCourseHandler handler = new TeacherCourseHandler(new TeacherCourseQueryService(),
                new TeacherAdjustmentApplicationService());

        List<Map<String, Object>> submissions = new ArrayList<>();
        Map<String, Object> badOperationId = writePayload();
        badOperationId.put("operationId", "not-a-uuid");
        submissions.add(badOperationId);
        Map<String, Object> missingOperationId = writePayload();
        missingOperationId.remove("operationId");
        submissions.add(missingOperationId);
        Map<String, Object> missingReason = writePayload();
        missingReason.remove("reason");
        submissions.add(missingReason);
        Map<String, Object> emptyTargets = writePayload();
        emptyTargets.put("targets", List.of());
        submissions.add(emptyTargets);
        Map<String, Object> nonIsoDate = writePayload();
        nonIsoDate.put("targets", List.of(targetPayload(OCCURRENCE_ID, "2026/11/04")));
        submissions.add(nonIsoDate);
        Map<String, Object> missingDate = writePayload();
        missingDate.put("targets", List.of(targetPayload(OCCURRENCE_ID, null)));
        submissions.add(missingDate);
        Map<String, Object> blankReason = writePayload();
        blankReason.put("reason", "   ");
        submissions.add(blankReason);
        Map<String, Object> invalidPeriod = writePayload();
        invalidPeriod.put("newStartPeriod", 0);
        submissions.add(invalidPeriod);
        Map<String, Object> reversedPeriod = writePayload();
        reversedPeriod.put("newEndPeriod", 1);
        submissions.add(reversedPeriod);

        for (Map<String, Object> payload : submissions) {
            Message response = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an invalid submit must be a bad request, saw " + response.getCode()
                            + ": " + response.getMessage());
        }
        Message nonIso = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                teacher.getToken(), Map.of("operationId", OPERATION_ID, "offeringId", OFFERING_ID,
                        "targets", List.of(targetPayload(OCCURRENCE_ID, "2026/11/04")),
                        "newStartPeriod", 3, "newEndPeriod", 4, "reason", "教师出差")));
        require("targetDate 必须是 ISO 本地日期".equals(nonIso.getMessage()),
                "the service's ISO-date reason must surface verbatim, saw " + nonIso.getMessage());

        for (Object offeringId : new Object[] {null, "abc", "9.5", "0", "-1",
                "99999999999999999999999", 9007199254740993L, List.of(OFFERING_ID)}) {
            Map<String, Object> payload = writePayload();
            payload.put("offeringId", offeringId);
            Message response = handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an illegal offeringId " + offeringId + " must be a bad request");
        }
        for (Object occurrenceId : new Object[] {null, "abc", 9201, List.of(OCCURRENCE_ID)}) {
            Map<String, Object> payload = writePayload();
            payload.put("targets", List.of(targetPayload(occurrenceId, "2026-11-04")));
            Message response = handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an illegal originalOccurrenceId " + occurrenceId + " must be a bad request");
        }
        Message nonStringDate = handler.handle(writeRequest(TeacherCourseActions.PREVIEW_ADJUSTMENT,
                teacher.getToken(), payloadWith("targets", List.of(targetPayload(OCCURRENCE_ID,
                        20261104)))));
        require(nonStringDate.getCode() == MessageCode.BAD_REQUEST,
                "a numeric targetDate must be rejected before Gson can coerce it");

        Message badOptions = handler.handle(optionsRequest(teacher.getToken(), "abc",
                OCCURRENCE_ID));
        require(badOptions.getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal offeringId on options must be a bad request");
        Message badOccurrence = handler.handle(optionsRequest(teacher.getToken(), OFFERING_ID,
                "9.5"));
        require(badOccurrence.getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal originalOccurrenceId on options must be a bad request");
        Message badDetailId = handler.handle(requestWith(TeacherCourseActions.GET_ADJUSTMENT_REQUEST,
                teacher.getToken(), "requestId", "abc"));
        require(badDetailId.getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal requestId must be a bad request");

        for (Object operationId : new Object[] {null, "not-a-uuid", 1234567}) {
            Map<String, Object> payload = withdrawPayload();
            payload.put("operationId", operationId);
            if (operationId == null) payload.remove("operationId");
            Message response = handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an illegal withdraw operationId " + operationId + " must be a bad request");
        }
        for (Object requestId : new Object[] {null, "abc", 9007199254740994L}) {
            Map<String, Object> payload = withdrawPayload();
            payload.put("requestId", requestId);
            Message response = handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an illegal withdraw requestId " + requestId + " must be a bad request");
        }
        for (Object version : new Object[] {0, -1}) {
            Map<String, Object> payload = withdrawPayload();
            payload.put("expectedVersion", version);
            Message response = handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "expectedVersion " + version + " must be a bad request");
        }
        Message noBody = handler.handle(request(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                teacher.getToken()));
        require(noBody.getCode() == MessageCode.BAD_REQUEST,
                "a write without a request body must be a bad request");
        require("request 必须为 JSON 对象".equals(noBody.getMessage()),
                "a missing body must name the request object contract, saw " + noBody.getMessage());
    }

    private static void verifyExceptionMapping(UserSession teacher) {
        require(handlerFailure(teacher, TeacherCourseActions.SUBMIT_ADJUSTMENT, writePayload(),
                        new TeacherAccessPolicy.AccessDeniedException("没有该课次的调课权限"))
                        .getCode() == MessageCode.FORBIDDEN,
                "an access denial must be forbidden");
        Message denied = handlerFailure(teacher, TeacherCourseActions.SUBMIT_ADJUSTMENT,
                writePayload(),
                new TeacherAccessPolicy.AccessDeniedException("没有该课次的调课权限"));
        require(denied.getData().isEmpty(),
                "a denial must not leak conflicts or entities");

        Message invalid = handlerFailure(teacher, TeacherCourseActions.PREVIEW_ADJUSTMENT,
                writePayload(), new IllegalArgumentException("targetDate 必须是 ISO 本地日期"));
        require(invalid.getCode() == MessageCode.BAD_REQUEST
                        && "targetDate 必须是 ISO 本地日期".equals(invalid.getMessage()),
                "an illegal argument must surface verbatim as a bad request");

        Message conflicted = handlerFailure(teacher, TeacherCourseActions.SUBMIT_ADJUSTMENT,
                writePayload(), new TeacherAdjustmentApplicationService.ConflictException(
                        "存在冲突，无法提交调课申请", detail(), List.of(blocking())));
        require(conflicted.getCode() == MessageCode.CONFLICT,
                "a conflict must use the conflict code");
        require(conflicted.getData().get("conflicts") instanceof List<?> conflicts
                        && conflicts.size() == 1,
                "a conflict must carry the typed conflict list");
        require(conflicted.getData().get("latest") instanceof AdjustmentRequestDetailDTO latest
                        && REQUEST_ID.equals(latest.getRequestId()),
                "a conflict must carry the latest visible detail");

        Message bareConflict = handlerFailure(teacher, TeacherCourseActions.SUBMIT_ADJUSTMENT,
                writePayload(), new TeacherAdjustmentApplicationService.ConflictException(
                        "operationId 已用于不同的业务请求", null, List.of()));
        require(bareConflict.getCode() == MessageCode.CONFLICT
                        && !bareConflict.getData().containsKey("latest")
                        && bareConflict.getData().containsKey("conflicts"),
                "a conflict without an entity must still carry the (possibly empty) conflict list");

        Map<String, Object> detailRequest = new HashMap<>();
        detailRequest.put("requestId", REQUEST_ID);
        require(handlerFailure(teacher, TeacherCourseActions.GET_ADJUSTMENT_REQUEST, detailRequest,
                        new TeacherAdjustmentApplicationService.NotFoundException("调课申请不存在"))
                        .getCode() == MessageCode.NOT_FOUND,
                "a missing personal request must be not found");

        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message database;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            database = handlerFailure(teacher, TeacherCourseActions.PREVIEW_ADJUSTMENT,
                    writePayload(), new DatabaseException("调课预检查失败",
                            new SQLException("SELECT secret FROM course_schedule_adjustment_request")));
        } finally {
            System.setErr(previousError);
        }
        require(database.getCode() == MessageCode.ERROR
                        && "教师课程服务暂不可用".equals(database.getMessage()),
                "a database failure must map to the generic server error");
        String logged = log.toString(StandardCharsets.UTF_8);
        require(logged.contains("action=" + TeacherCourseActions.PREVIEW_ADJUSTMENT)
                        && logged.contains("SELECT secret"),
                "the database failure detail must stay in the server log");

        ByteArrayOutputStream runtimeLog = new ByteArrayOutputStream();
        Message runtime;
        try {
            System.setErr(new PrintStream(runtimeLog, true, StandardCharsets.UTF_8));
            runtime = handlerFailure(teacher, TeacherCourseActions.PREVIEW_ADJUSTMENT,
                    writePayload(), new IllegalStateException("runtime probe"));
        } finally {
            System.setErr(previousError);
        }
        require(runtime.getCode() == MessageCode.ERROR
                        && "服务端内部错误".equals(runtime.getMessage()),
                "a runtime failure must map to the generic server error");
        require(runtimeLog.toString(StandardCharsets.UTF_8).contains("runtime probe"),
                "the runtime failure detail must stay in the server log");
        for (Message failure : List.of(database, runtime)) {
            String message = failure.getMessage();
            require(!message.contains("SELECT") && !message.contains("runtime"),
                    "a failure response must not leak internals, saw " + message);
        }
    }

    /**
     * 提交/撤销是写操作：Handler 不做“先查再写”的归属判断，请求必须径直进入服务（权限由服务端
     * 在事务内重算），中间不产生任何多余的读调用。
     */
    private static void verifyThinWritePath(UserSession teacher) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        TeacherCourseHandler handler = handler(recording);

        require(handler.handle(writeRequest(TeacherCourseActions.SUBMIT_ADJUSTMENT,
                        teacher.getToken(), writePayload())).getCode() == MessageCode.SUCCESS,
                "the submit must succeed");
        require(recording.submitCalls == 1 && recording.getCalls == 0 && recording.listCalls == 0
                        && recording.previewCalls == 0 && recording.optionsCalls == 0,
                "a submit must go straight to the service without a pre-read");

        require(handler.handle(writeRequest(TeacherCourseActions.WITHDRAW_ADJUSTMENT,
                        teacher.getToken(), withdrawPayload())).getCode() == MessageCode.SUCCESS,
                "the withdraw must succeed");
        require(recording.withdrawCalls == 1 && recording.getCalls == 0
                        && recording.listCalls == 0,
                "a withdraw must go straight to the service without a pre-read");
    }

    private static void verifyUnavailableWithoutTheAdjustmentService(UserSession teacher) {
        TeacherCourseHandler legacy = new TeacherCourseHandler(new TeacherCourseQueryService());
        Message response = legacy.handle(optionsRequest(teacher.getToken(), OFFERING_ID,
                OCCURRENCE_ID));
        require(response.getCode() == MessageCode.BAD_REQUEST
                        && "该教师操作尚未开放".equals(response.getMessage()),
                "a handler without an adjustment service must report the operation as not open yet");
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> ALL_ACTIONS = List.of(
            TeacherCourseActions.GET_ADJUSTMENT_OPTIONS,
            TeacherCourseActions.PREVIEW_ADJUSTMENT,
            TeacherCourseActions.SUBMIT_ADJUSTMENT,
            TeacherCourseActions.WITHDRAW_ADJUSTMENT,
            TeacherCourseActions.GET_ADJUSTMENT_REQUEST,
            TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS);

    /** 请求体位于 data.request 的动作；其余动作的参数直接放在 data 上。 */
    private static final Set<String> WRITE_ACTIONS = Set.of(
            TeacherCourseActions.PREVIEW_ADJUSTMENT,
            TeacherCourseActions.SUBMIT_ADJUSTMENT,
            TeacherCourseActions.WITHDRAW_ADJUSTMENT);

    private static TeacherCourseHandler handler(RecordingAdjustmentService recording) {
        return new TeacherCourseHandler(new TeacherCourseQueryService(), recording);
    }

    /** 让记录型服务对任意动作抛出给定异常，返回 Handler 的错误响应。 */
    private static Message handlerFailure(UserSession teacher, String action,
            Map<String, Object> payload, RuntimeException failure) {
        RecordingAdjustmentService recording = new RecordingAdjustmentService();
        recording.failure = failure;
        Message request = request(action, teacher.getToken());
        if (payload != null) {
            if (WRITE_ACTIONS.contains(action)) {
                request.putData("request", payload);
            } else {
                payload.forEach(request::putData);
            }
        }
        return handler(recording).handle(request);
    }

    private static Message request(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseTeacher", action);
        request.setToken(token);
        return request;
    }

    private static Message requestWith(String action, String token, String key, Object value) {
        Message request = request(action, token);
        request.putData(key, value);
        return request;
    }

    private static Message optionsRequest(String token, Object offeringId, Object occurrenceId) {
        Message request = request(TeacherCourseActions.GET_ADJUSTMENT_OPTIONS, token);
        if (offeringId != null) request.putData("offeringId", offeringId);
        if (occurrenceId != null) request.putData("originalOccurrenceId", occurrenceId);
        return request;
    }

    private static Message writeRequest(String action, String token, Map<String, Object> payload) {
        Message request = request(action, token);
        request.putData("request", payload);
        return request;
    }

    private static Message listRequest(String token, AdjustmentRequestStatusDTO status, int page,
            int size) {
        Message request = listRequestRaw(token, status, page, size);
        return request;
    }

    private static Message listRequestRaw(String token, Object status, Object page, Object size) {
        Message request = request(TeacherCourseActions.LIST_MY_ADJUSTMENT_REQUESTS, token);
        if (status != null) {
            request.putData("status", status instanceof AdjustmentRequestStatusDTO typed
                    ? typed.name() : status);
        }
        request.putData("page", page);
        request.putData("size", size);
        return request;
    }

    private static Map<String, Object> writePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", "not-a-uuid");
        payload.put("offeringId", OFFERING_ID);
        payload.put("targets", List.of(targetPayload(OCCURRENCE_ID, "2026-11-04")));
        payload.put("newStartPeriod", 3);
        payload.put("newEndPeriod", 4);
        payload.put("newClassroomId", "8103");
        payload.put("reason", "教师出差");
        return payload;
    }

    private static Map<String, Object> payloadWith(String key, Object value) {
        Map<String, Object> payload = writePayload();
        payload.put(key, value);
        return payload;
    }

    private static Map<String, Object> targetPayload(Object occurrenceId, Object targetDate) {
        Map<String, Object> target = new HashMap<>();
        target.put("originalOccurrenceId", occurrenceId);
        target.put("targetDate", targetDate);
        return target;
    }

    private static Map<String, Object> withdrawPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", OPERATION_ID);
        payload.put("requestId", REQUEST_ID);
        payload.put("expectedVersion", 3);
        return payload;
    }

    private static AdjustmentRequestDetailDTO detail() {
        return new AdjustmentRequestDetailDTO(REQUEST_ID, OFFERING_ID, TEACHER_A, "教师出差",
                AdjustmentRequestStatusDTO.PENDING, 1, 3, 3, 4,
                null, null, new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60),
                List.of(new AdjustmentTargetDTO(OCCURRENCE_ID, 8, "2026-10-26T00:00:00Z",
                        "2026-10-26T01:35:00Z", "陈老师", null, "A-101", "2026-11-04")),
                List.of(), "2026-09-14T08:00:00Z", null, null, null);
    }

    private static ScheduleConflictDTO blocking() {
        return new ScheduleConflictDTO("TEACHER_OVERLAP", ScheduleConflictSeverityDTO.BLOCKING,
                "T2001", OFFERING_ID, 8, 3, 3, 4, "任课教师在该时间已有其他课程");
    }

    private static void requireOnlyKey(Message response, String key) {
        Map<String, Object> data = response.getData();
        require(data != null && data.size() == 1 && data.containsKey(key),
                "the response must expose exactly the " + key + " key, saw " + data);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /**
     * 记录型假服务：保存 Handler 传入的每个参数，返回固定的四态页、选项、预览与写结果。
     * {@code failure} 非空时所有方法都抛出它，用来覆盖 R13 的异常映射表。
     */
    private static final class RecordingAdjustmentService
            extends TeacherAdjustmentApplicationService {
        private String lastUid;
        private String lastOfferingId;
        private String lastOccurrenceId;
        private AdjustmentRequestStatusDTO lastStatus;
        private int lastPage;
        private int lastSize;
        private TeacherAdjustmentWriteDTO lastWrite;
        private WithdrawTeacherAdjustmentRequestDTO lastWithdrawal;
        private RuntimeException failure;
        private int calls;
        private int optionsCalls;
        private int previewCalls;
        private int submitCalls;
        private int withdrawCalls;
        private int getCalls;
        private int listCalls;

        @Override
        public TeacherAdjustmentOptionsDTO options(String uid, String offeringId,
                String originalOccurrenceId) {
            calls++;
            optionsCalls++;
            lastUid = uid;
            lastOfferingId = offeringId;
            lastOccurrenceId = originalOccurrenceId;
            failIfRequested();
            return new TeacherAdjustmentOptionsDTO(CALENDAR_ID, "Asia/Shanghai",
                    List.of(new TeacherCalendarDateDTO("2026-11-04", 9, 3, true)),
                    List.of(new TeacherPeriodDTO("2026-11-04", 3, "10:00:00", "10:45:00")),
                    List.of(new ScheduleResourceDTO("8103", "3003", "B-203", "classroom", 60)));
        }

        @Override
        public TeacherAdjustmentPreviewDTO preview(String uid, TeacherAdjustmentWriteDTO write) {
            calls++;
            previewCalls++;
            lastUid = uid;
            lastWrite = write;
            failIfRequested();
            return new TeacherAdjustmentPreviewDTO(List.of(), true);
        }

        @Override
        public TeacherOperationResultDTO<AdjustmentRequestDetailDTO> submit(String uid,
                TeacherAdjustmentWriteDTO write) {
            calls++;
            submitCalls++;
            lastUid = uid;
            lastWrite = write;
            failIfRequested();
            return result(write == null ? "not-a-uuid" : write.getOperationId(),
                    SUBMITTED_MESSAGE);
        }

        @Override
        public TeacherOperationResultDTO<AdjustmentRequestDetailDTO> withdraw(String uid,
                WithdrawTeacherAdjustmentRequestDTO withdrawal) {
            calls++;
            withdrawCalls++;
            lastUid = uid;
            lastWithdrawal = withdrawal;
            failIfRequested();
            return result(withdrawal == null ? "not-a-uuid" : withdrawal.getOperationId(),
                    "调课申请已撤销");
        }

        @Override
        public AdjustmentRequestDetailDTO get(String uid, String requestId) {
            calls++;
            getCalls++;
            lastUid = uid;
            failIfRequested();
            return detail();
        }

        @Override
        public TeacherPageDTO<AdjustmentRequestSummaryDTO> listMine(String uid,
                AdjustmentRequestStatusDTO status, int page, int size) {
            calls++;
            listCalls++;
            lastUid = uid;
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            failIfRequested();
            List<AdjustmentRequestSummaryDTO> items = new ArrayList<>();
            items.add(new AdjustmentRequestSummaryDTO(REQUEST_ID, "数据结构", "CS203-01", TEACHER_A,
                    "陈老师", 1, status == null ? AdjustmentRequestStatusDTO.PENDING : status,
                    "2026-09-14T08:00:00Z"));
            return new TeacherPageDTO<>(items, 1L, page, size);
        }

        private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result(String operationId,
                String message) {
            return new TeacherOperationResultDTO<>(operationId, message, detail(), false);
        }

        private void failIfRequested() {
            if (failure != null) throw failure;
        }
    }
}
