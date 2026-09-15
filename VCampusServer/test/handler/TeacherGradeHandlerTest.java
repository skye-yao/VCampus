package handler;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import service.TeacherGradeBookService;
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师成绩接口（courseTeacher 的四个成绩动作）的鉴权、身份来源、响应键、参数传递与错误映射矩阵。
 *
 * <p>与 {@link TeacherAdjustmentHandlerTest} 同形：映射类断言跑在记录型假服务上；解析边界跑在真实
 * {@link TeacherGradeBookService} 上——它的校验（operationId、版本、名单摘要、方案/分数格式）全部
 * 发生在地库连接之前，所以非法输入在这里必然先以 BAD_REQUEST 结束，本套件保持无数据库。
 *
 * <p>关键边界：教师 UID 只取自 Session（请求体里的 uid/teacherId/force 是协议外伪造，出现即
 * BAD_REQUEST 且绝不进入服务）；成绩写请求里的 BIGINT 标识只接受十进制字符串，分数与权重由服务端
 * 重新计算，客户端传来的总评/绩点没有字段可传。
 */
public final class TeacherGradeHandlerTest {
    private static final String TEACHER_A = "teacher-a";
    private static final String TEACHER_B = "teacher-b";
    private static final String OFFERING_ID = "9007199254740993";
    private static final String ENROLLMENT_ID = "9007199254740997";
    private static final String OPERATION_ID = "30000000-0000-0000-0000-000000000001";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String SAVED_MESSAGE = "成绩草稿已保存";
    private static final String SUBMITTED_MESSAGE = "成绩批次已提交";

    private TeacherGradeHandlerTest() {
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
            verifyParsingBoundariesWithTheRealService(teacherA);
            verifyExceptionMapping(teacherA);
            verifyThinWritePath(teacherA);
            verifyUnavailableWithoutTheGradeService(teacherA);
        } finally {
            sessions.removeSession(teacherA.getToken());
            sessions.removeSession(student.getToken());
            sessions.removeSession(administrator.getToken());
        }
        System.out.println("Teacher grade handler test passed.");
    }

    /** 动作名与 T4 冻结的常量逐字符一致：服务端的幂等摘要与操作日志都建立在这个字符串上。 */
    private static void verifyActionConstants() {
        require("listGradeOfferings".equals(TeacherCourseActions.LIST_GRADE_OFFERINGS),
                "成绩列表动作名必须与 Common 常量一致");
        require("getGradeBook".equals(TeacherCourseActions.GET_GRADE_BOOK),
                "成绩表动作名必须与 Common 常量一致");
        require("saveGradeDraft".equals(TeacherCourseActions.SAVE_GRADE_DRAFT),
                "保存草稿动作名必须与 Common 常量一致");
        require("submitGradeBook".equals(TeacherCourseActions.SUBMIT_GRADE_BOOK),
                "提交动作名必须与 Common 常量一致");
    }

    private static void verifyAuthorization(UserSession teacher, UserSession student,
            UserSession administrator) {
        verifyActionConstants();
        RecordingGradeService recording = new RecordingGradeService();
        TeacherCourseHandler handler = handler(recording);

        require(handler.handle(request(TeacherCourseActions.GET_GRADE_BOOK, null)).getCode()
                        == MessageCode.UNAUTHORIZED,
                "a missing token must be unauthorized");
        for (String action : ALL_ACTIONS) {
            require(handler.handle(request(action, student.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "a student must be forbidden from " + action);
            require(handler.handle(request(action, administrator.getToken())).getCode()
                            == MessageCode.FORBIDDEN,
                    "an administrator must be forbidden from " + action);
        }
        require(recording.calls == 0, "a rejected request must not reach the grade service");
        require(handler.handle(request("   ", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "a blank action must be a bad request");
        require(handler.handle(request("deleteGradeBook", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown action must be a bad request");

        Message book = handler.handle(requestWith(TeacherCourseActions.GET_GRADE_BOOK,
                teacher.getToken(), "offeringId", OFFERING_ID));
        require(book.getCode() == MessageCode.SUCCESS,
                "a teacher must reach the grade surface: " + book.getMessage());
        require(TEACHER_A.equals(recording.lastUid),
                "the grade service must run as the session teacher");
        require(recording.lastOfferingId.equals(OFFERING_ID),
                "the offeringId must reach the service as the exact decimal string");
    }

    /**
     * 会话身份优先于请求体：data.request 里的身份/人员/强制字段是协议外伪造，必须直接 BAD_REQUEST；
     * 顶层 uid/sender 则是被忽略的噪音，不能改变服务实际使用的教师。
     */
    private static void verifySessionIdentityAndForgedFields(UserSession teacher) {
        RecordingGradeService recording = new RecordingGradeService();
        TeacherCourseHandler handler = handler(recording);

        Message forgedTopLevel = writeRequest(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                teacher.getToken(), writePayload());
        forgedTopLevel.putData("uid", TEACHER_B);
        forgedTopLevel.putData("teacherId", TEACHER_B);
        forgedTopLevel.setSender(TEACHER_B);
        Message submitted = handler.handle(forgedTopLevel);
        require(submitted.getCode() == MessageCode.SUCCESS,
                "a top-level forged identity must not break the write: " + submitted.getMessage());
        require(TEACHER_A.equals(recording.lastUid),
                "the write must run as the session teacher, never as the forged uid");
        require(recording.submitCalls == 1, "the write must reach the service exactly once");

        for (String forged : new String[] {"uid", "teacherId", "teacherUid", "force",
                "assistantUid", "newTeacherUid"}) {
            Map<String, Object> payload = writePayload();
            payload.put(forged, TEACHER_B);
            int before = recording.submitCalls;
            Message response = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "a forged " + forged + " in data.request must be a bad request, saw "
                            + response.getCode());
            require(recording.submitCalls == before,
                    "a forged " + forged + " must never reach the service");
        }

        // 归属字段不能出现在内容里：成绩写入只认 offeringId 与会话教师的关系。
        Map<String, Object> payload = writePayload();
        contentOf(payload).put("teacherId", TEACHER_B);
        require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                        teacher.getToken(), payload)).getCode() == MessageCode.BAD_REQUEST,
                "a forged teacherId inside content must be a bad request");
        require(recording.saveCalls == 0,
                "a forged content identity must never reach the service");
    }

    private static void verifyResponseKeys(UserSession teacher) {
        RecordingGradeService recording = new RecordingGradeService();
        TeacherCourseHandler handler = handler(recording);

        Message offerings = handler.handle(listRequest(teacher.getToken(), 2025, 3, 1, 20));
        require(offerings.getCode() == MessageCode.SUCCESS, "the grade list must succeed");
        requireOnlyKey(offerings, "offerings");
        require(offerings.getData("offerings") instanceof TeacherPageDTO,
                "the offerings key must carry the generic page object");

        Message book = handler.handle(requestWith(TeacherCourseActions.GET_GRADE_BOOK,
                teacher.getToken(), "offeringId", OFFERING_ID));
        require(book.getCode() == MessageCode.SUCCESS, "the grade book read must succeed");
        requireOnlyKey(book, "gradeBook");
        require(book.getData("gradeBook") instanceof TeacherGradeBookDTO,
                "the gradeBook key must carry the grade book DTO");

        Message saved = handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                teacher.getToken(), writePayload()));
        require(saved.getCode() == MessageCode.SUCCESS, "a draft save must succeed");
        requireOnlyKey(saved, "result");
        require(saved.getData("result") instanceof TeacherOperationResultDTO,
                "a write must use the result key with the operation result DTO");
        require(SAVED_MESSAGE.equals(saved.getMessage()),
                "the response message must come from the operation result, saw "
                        + saved.getMessage());

        Message submitted = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                teacher.getToken(), writePayload()));
        require(submitted.getCode() == MessageCode.SUCCESS, "a submit must succeed");
        requireOnlyKey(submitted, "result");
        require(SUBMITTED_MESSAGE.equals(submitted.getMessage()),
                "the submit message must come from the operation result, saw "
                        + submitted.getMessage());
    }

    /** 参数与请求体逐字段往返：版本、名单摘要、方案与每行分数都由 Gson 解析后交给服务。 */
    private static void verifyArgumentPassing(UserSession teacher) {
        RecordingGradeService recording = new RecordingGradeService();
        TeacherCourseHandler handler = handler(recording);

        handler.handle(listRequest(teacher.getToken(), 2024, 2, 3, 7));
        require(recording.lastTerm[0] == 2024 && recording.lastTerm[1] == 2,
                "the term must reach the service");
        require(recording.lastPage == 3 && recording.lastSize == 7,
                "the explicit paging must reach the service");

        handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT, teacher.getToken(),
                writePayload()));
        WriteGradeBookRequestDTO write = recording.lastWrite;
        require(write != null && OPERATION_ID.equals(write.getOperationId()),
                "the operationId must round-trip");
        require(OFFERING_ID.equals(write.getContent().getOfferingId()),
                "the content offeringId must round-trip as the exact decimal string");
        require(write.getContent().getExpectedRevision() == 3,
                "the expected revision must round-trip");
        require(DIGEST.equals(write.getContent().getRosterDigest()),
                "the roster digest must round-trip");
        require(write.getContent().getRows().size() == 1
                        && ENROLLMENT_ID.equals(
                                write.getContent().getRows().get(0).getEnrollmentId()),
                "the rows must round-trip with exact enrollment ids");
        GradeScoresDTO scores = write.getContent().getRows().get(0).getScores();
        require(new BigDecimal("88.5").compareTo(scores.getDailyScore()) == 0
                        && scores.getMidtermScore() == null,
                "scores must round-trip with null kept as null, saw " + scores.getDailyScore()
                        + "/" + scores.getMidtermScore());
        GradeSchemeDTO scheme = write.getContent().getScheme();
        require(scheme.getComponents().size() == 4
                        && scheme.getComponents().get(0).getWeightBasisPoints() == 3000,
                "the scheme weights must round-trip as basis points");
    }

    /** 解析边界：真实服务的校验发生在地库连接之前，非法输入在这里必须已经是 BAD_REQUEST。 */
    private static void verifyParsingBoundariesWithTheRealService(UserSession teacher) {
        TeacherCourseHandler handler = new TeacherCourseHandler(new TeacherCourseQueryService(),
                null, new TeacherGradeBookService());

        List<Map<String, Object>> submissions = new ArrayList<>();
        Map<String, Object> badOperationId = writePayload();
        badOperationId.put("operationId", "not-a-uuid");
        submissions.add(badOperationId);
        Map<String, Object> missingOperationId = writePayload();
        missingOperationId.remove("operationId");
        submissions.add(missingOperationId);
        Map<String, Object> missingContent = writePayload();
        missingContent.remove("content");
        submissions.add(missingContent);
        Map<String, Object> negativeRevision = writePayload();
        contentOf(negativeRevision).put("expectedRevision", -1);
        submissions.add(negativeRevision);
        Map<String, Object> badDigest = writePayload();
        contentOf(badDigest).put("rosterDigest", "not-a-digest");
        submissions.add(badDigest);
        Map<String, Object> duplicateRows = writePayload();
        contentOf(duplicateRows).put("rows", List.of(rowPayload(ENROLLMENT_ID),
                rowPayload(ENROLLMENT_ID)));
        submissions.add(duplicateRows);
        Map<String, Object> illegalScore = writePayload();
        contentOf(illegalScore).put("rows", List.of(Map.of("enrollmentId", ENROLLMENT_ID,
                "scores", Map.of("dailyScore", "88.555"))));
        submissions.add(illegalScore);
        Map<String, Object> outOfRangeScore = writePayload();
        contentOf(outOfRangeScore).put("rows", List.of(Map.of("enrollmentId", ENROLLMENT_ID,
                "scores", Map.of("finaltermScore", "101"))));
        submissions.add(outOfRangeScore);
        // 结构性问题（权重为负）连草稿都要拒绝；权重总和不等于 10000 只对提交是问题（见下）。
        Map<String, Object> negativeWeight = writePayload();
        contentOf(negativeWeight).put("scheme", schemePayload(-1, 2000, 2000, 3000));
        submissions.add(negativeWeight);

        for (Map<String, Object> payload : submissions) {
            Message response = handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                    teacher.getToken(), payload));
            require(response.getCode() == MessageCode.BAD_REQUEST,
                    "an invalid draft save must be a bad request, saw " + response.getCode()
                            + ": " + response.getMessage());
        }

        // 提交比草稿严格：权重必须配齐（全 0 的方案草稿合法，提交必须被拒绝）。
        Map<String, Object> submitWithDraftWeights = writePayload();
        contentOf(submitWithDraftWeights).put("scheme", schemePayload(0, 0, 0, 0));
        Message strictSubmit = handler.handle(writeRequest(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                teacher.getToken(), submitWithDraftWeights));
        require(strictSubmit.getCode() == MessageCode.BAD_REQUEST,
                "a submit with an incomplete scheme must be a bad request, saw "
                        + strictSubmit.getMessage());

        // BIGINT 标识只接受十进制字符串：数字、小数、负数、数组都在 Gson 之前被拒绝。
        for (Object offeringId : new Object[] {null, "abc", "9.5", "0", "-1",
                "99999999999999999999999", 9007199254740993L, List.of(OFFERING_ID)}) {
            Map<String, Object> payload = writePayload();
            contentOf(payload).put("offeringId", offeringId);
            require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                            teacher.getToken(), payload)).getCode() == MessageCode.BAD_REQUEST,
                    "an illegal offeringId " + offeringId + " must be a bad request");
        }
        for (Object enrollmentId : new Object[] {null, "abc", 9007199254740997L}) {
            Map<String, Object> payload = writePayload();
            contentOf(payload).put("rows", List.of(rowPayload(enrollmentId)));
            require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                            teacher.getToken(), payload)).getCode() == MessageCode.BAD_REQUEST,
                    "an illegal enrollmentId " + enrollmentId + " must be a bad request");
        }
        for (Object rows : new Object[] {null, "not-an-array", List.of("row")}) {
            Map<String, Object> payload = writePayload();
            contentOf(payload).put("rows", rows);
            require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                            teacher.getToken(), payload)).getCode() == MessageCode.BAD_REQUEST,
                    "illegal rows " + rows + " must be a bad request");
        }
        Map<String, Object> nonObjectContent = writePayload();
        nonObjectContent.put("content", "not-an-object");
        require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                        teacher.getToken(), nonObjectContent)).getCode() == MessageCode.BAD_REQUEST,
                "a non-object content must be a bad request");
        Message noBody = handler.handle(request(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                teacher.getToken()));
        require(noBody.getCode() == MessageCode.BAD_REQUEST
                        && "request 必须为 JSON 对象".equals(noBody.getMessage()),
                "a write without a request body must name the request object contract, saw "
                        + noBody.getMessage());

        // 读路径的非法标识同样先被拒绝。
        require(handler.handle(requestWith(TeacherCourseActions.GET_GRADE_BOOK, teacher.getToken(),
                        "offeringId", "abc")).getCode() == MessageCode.BAD_REQUEST,
                "a non-decimal offeringId on the read must be a bad request");
        require(handler.handle(request(TeacherCourseActions.GET_GRADE_BOOK, teacher.getToken()))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a read without an offeringId must be a bad request");
        require(handler.handle(listRequest(teacher.getToken(), 2025, 3, 0, 20)).getCode()
                        == MessageCode.BAD_REQUEST,
                "illegal paging on the grade list must be a bad request");
    }

    private static void verifyExceptionMapping(UserSession teacher) {
        require(handlerFailure(teacher, TeacherCourseActions.GET_GRADE_BOOK,
                        new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班成绩的权限"))
                        .getCode() == MessageCode.FORBIDDEN,
                "an access denial must be forbidden");
        Message denied = handlerFailure(teacher, TeacherCourseActions.SAVE_GRADE_DRAFT,
                new TeacherAccessPolicy.AccessDeniedException("没有编辑该教学班成绩的权限"));
        require(denied.getCode() == MessageCode.FORBIDDEN && denied.getData().isEmpty(),
                "a denial must not leak entities");

        Message invalid = handlerFailure(teacher, TeacherCourseActions.SAVE_GRADE_DRAFT,
                new IllegalArgumentException("rosterDigest 必须是 64 位小写十六进制 SHA-256"));
        require(invalid.getCode() == MessageCode.BAD_REQUEST
                        && "rosterDigest 必须是 64 位小写十六进制 SHA-256".equals(
                                invalid.getMessage()),
                "an illegal argument must surface verbatim as a bad request");

        Message conflicted = handlerFailure(teacher, TeacherCourseActions.SAVE_GRADE_DRAFT,
                new TeacherGradeBookService.ConflictException("成绩草稿版本已变化，请重新加载后重试",
                        gradeBook()));
        require(conflicted.getCode() == MessageCode.CONFLICT,
                "a stale write must use the conflict code");
        require(conflicted.getData().get("gradeBook") instanceof TeacherGradeBookDTO latest
                        && latest.getRevision() == gradeBook().getRevision(),
                "a grade conflict must carry the latest grade book so the page can refresh");
        require(!conflicted.getData().containsKey("latest"),
                "a grade conflict must not fabricate an adjustment detail under latest");

        Message bareConflict = handlerFailure(teacher, TeacherCourseActions.SAVE_GRADE_DRAFT,
                new TeacherGradeBookService.ConflictException("operationId 已用于不同的成绩写入请求"));
        require(bareConflict.getCode() == MessageCode.CONFLICT
                        && !bareConflict.getData().containsKey("gradeBook"),
                "a conflict without an entity must not invent one");

        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message database;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            database = handlerFailure(teacher, TeacherCourseActions.GET_GRADE_BOOK,
                    new DatabaseException("成绩表查询失败",
                            new SQLException("SELECT * FROM teacher_grade_book")));
        } finally {
            System.setErr(previousError);
        }
        require(database.getCode() == MessageCode.ERROR
                        && "教师课程服务暂不可用".equals(database.getMessage()),
                "a database failure must map to the generic server error");
        String logged = log.toString(StandardCharsets.UTF_8);
        require(logged.contains("action=" + TeacherCourseActions.GET_GRADE_BOOK)
                        && logged.contains("SELECT * FROM teacher_grade_book"),
                "the database failure detail must stay in the server log");
        require(!database.getMessage().contains("SELECT"),
                "a failure response must not leak internals");
    }

    /**
     * 保存与提交都是写操作：Handler 不做“先查后写”，请求必须径直进入服务（版本与归属由服务端在
     * 事务内重新校验），中间不产生任何多余的读调用。
     */
    private static void verifyThinWritePath(UserSession teacher) {
        RecordingGradeService recording = new RecordingGradeService();
        TeacherCourseHandler handler = handler(recording);

        require(handler.handle(writeRequest(TeacherCourseActions.SAVE_GRADE_DRAFT,
                        teacher.getToken(), writePayload())).getCode() == MessageCode.SUCCESS,
                "the draft save must succeed");
        require(recording.saveCalls == 1 && recording.getCalls == 0 && recording.listCalls == 0,
                "a draft save must go straight to the service without a pre-read");
        require(handler.handle(writeRequest(TeacherCourseActions.SUBMIT_GRADE_BOOK,
                        teacher.getToken(), writePayload())).getCode() == MessageCode.SUCCESS,
                "the submit must succeed");
        require(recording.submitCalls == 1 && recording.getCalls == 0
                        && recording.listCalls == 0,
                "a submit must go straight to the service without a pre-read");
    }

    private static void verifyUnavailableWithoutTheGradeService(UserSession teacher) {
        TeacherCourseHandler legacy = new TeacherCourseHandler(new TeacherCourseQueryService(),
                new TeacherAdjustmentApplicationService());
        Message response = legacy.handle(requestWith(TeacherCourseActions.GET_GRADE_BOOK,
                teacher.getToken(), "offeringId", OFFERING_ID));
        require(response.getCode() == MessageCode.BAD_REQUEST
                        && "该教师操作尚未开放".equals(response.getMessage()),
                "a handler without a grade service must report the operation as not open yet");
    }

    // ------------------------------------------------------------------ helpers

    private static final List<String> ALL_ACTIONS = List.of(
            TeacherCourseActions.LIST_GRADE_OFFERINGS,
            TeacherCourseActions.GET_GRADE_BOOK,
            TeacherCourseActions.SAVE_GRADE_DRAFT,
            TeacherCourseActions.SUBMIT_GRADE_BOOK);

    private static TeacherCourseHandler handler(RecordingGradeService recording) {
        return new TeacherCourseHandler(new TeacherCourseQueryService(),
                new TeacherAdjustmentApplicationService(), recording);
    }

    /** 让记录型服务对任意动作抛出给定异常，返回 Handler 的错误响应。 */
    private static Message handlerFailure(UserSession teacher, String action,
            RuntimeException failure) {
        RecordingGradeService recording = new RecordingGradeService();
        recording.failure = failure;
        Message request = request(action, teacher.getToken());
        switch (action) {
            case TeacherCourseActions.SAVE_GRADE_DRAFT, TeacherCourseActions.SUBMIT_GRADE_BOOK ->
                    request.putData("request", writePayload());
            case TeacherCourseActions.GET_GRADE_BOOK ->
                    request.putData("offeringId", OFFERING_ID);
            default -> { }
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

    private static Message writeRequest(String action, String token, Map<String, Object> payload) {
        Message request = request(action, token);
        request.putData("request", payload);
        return request;
    }

    private static Message listRequest(String token, Object academicYear, Object semester,
            Object page, Object size) {
        Message request = request(TeacherCourseActions.LIST_GRADE_OFFERINGS, token);
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        request.putData("page", page);
        request.putData("size", size);
        return request;
    }

    /** 与线上协议同形的写请求体：scheme 是含 components 的对象，分数用 DTO 的字段名。 */
    private static Map<String, Object> writePayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationId", OPERATION_ID);
        Map<String, Object> content = new HashMap<>();
        content.put("offeringId", OFFERING_ID);
        content.put("expectedRevision", 3);
        content.put("rosterDigest", DIGEST);
        content.put("scheme", schemePayload(3000, 2000, 2000, 3000));
        content.put("rows", List.of(rowPayload(ENROLLMENT_ID)));
        payload.put("content", content);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> contentOf(Map<String, Object> payload) {
        return (Map<String, Object>) payload.get("content");
    }

    private static Map<String, Object> schemePayload(int... weights) {
        Map<String, Object> scheme = new HashMap<>();
        scheme.put("components", List.of(component("DAILY", true, weights[0]),
                component("MIDTERM", true, weights[1]), component("EXPERIMENT", true, weights[2]),
                component("FINALTERM", true, weights[3])));
        return scheme;
    }

    private static Map<String, Object> rowPayload(Object enrollmentId) {
        Map<String, Object> scores = new HashMap<>();
        scores.put("dailyScore", "88.5");
        scores.put("midtermScore", null);
        Map<String, Object> row = new HashMap<>();
        row.put("enrollmentId", enrollmentId);
        row.put("scores", scores);
        return row;
    }

    private static Map<String, Object> component(String code, boolean enabled, int weight) {
        Map<String, Object> component = new HashMap<>();
        component.put("code", code);
        component.put("enabled", enabled);
        component.put("weightBasisPoints", weight);
        return component;
    }

    private static TeacherGradeBookDTO gradeBook() {
        List<GradeComponentDTO> components = new ArrayList<>();
        components.add(new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000));
        components.add(new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000));
        components.add(new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, 2000));
        components.add(new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 3000));
        List<TeacherGradeRowDTO> rows = List.of(new TeacherGradeRowDTO(ENROLLMENT_ID, "00005678",
                "张三", new GradeScoresDTO(new BigDecimal("88.5"), null, null, null),
                null, null, false, List.of()));
        return new TeacherGradeBookDTO(OFFERING_ID, 4, DIGEST, "DRAFT",
                new GradeSchemeDTO(components), rows, null, null, true, null, false);
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
     * 记录型假服务：保存 Handler 传入的每个参数，返回固定的成绩页/成绩表/写结果。
     * {@code failure} 非空时所有方法都抛出它，用来覆盖异常映射表。
     */
    private static final class RecordingGradeService extends TeacherGradeBookService {
        private String lastUid;
        private String lastOfferingId;
        private final int[] lastTerm = new int[2];
        private int lastPage;
        private int lastSize;
        private WriteGradeBookRequestDTO lastWrite;
        private RuntimeException failure;
        private int calls;
        private int listCalls;
        private int getCalls;
        private int saveCalls;
        private int submitCalls;

        @Override
        public TeacherPageDTO<TeacherGradeOfferingDTO> listGradeOfferings(String uid,
                int academicYear, int semester, int page, int size) {
            calls++;
            listCalls++;
            lastUid = uid;
            lastTerm[0] = academicYear;
            lastTerm[1] = semester;
            lastPage = page;
            lastSize = size;
            failIfRequested();
            TeacherOfferingDTO offering = new TeacherOfferingDTO(OFFERING_ID, "CS203-01",
                    "数据结构 CS203-01", "2001", "CS203", "数据结构与算法基础", 4.0, academicYear,
                    semester, 24, 30, "OPEN", true, true);
            return new TeacherPageDTO<>(List.of(new TeacherGradeOfferingDTO(offering, "DRAFT", 20,
                    4, null)), 1L, page, size);
        }

        @Override
        public TeacherGradeBookDTO getGradeBook(String uid, String offeringId) {
            calls++;
            getCalls++;
            lastUid = uid;
            lastOfferingId = offeringId;
            failIfRequested();
            return gradeBook();
        }

        @Override
        public TeacherOperationResultDTO<TeacherGradeBookDTO> saveDraft(String uid,
                WriteGradeBookRequestDTO raw) {
            calls++;
            saveCalls++;
            lastUid = uid;
            lastWrite = raw;
            failIfRequested();
            return result(raw, SAVED_MESSAGE);
        }

        @Override
        public TeacherOperationResultDTO<TeacherGradeBookDTO> submitGradeBook(String uid,
                WriteGradeBookRequestDTO raw) {
            calls++;
            submitCalls++;
            lastUid = uid;
            lastWrite = raw;
            failIfRequested();
            return result(raw, SUBMITTED_MESSAGE);
        }

        private TeacherOperationResultDTO<TeacherGradeBookDTO> result(WriteGradeBookRequestDTO raw,
                String message) {
            return new TeacherOperationResultDTO<>(
                    raw == null ? "not-a-uuid" : raw.getOperationId(), message, gradeBook(), false);
        }

        private void failIfRequested() {
            if (failure != null) throw failure;
        }
    }
}
