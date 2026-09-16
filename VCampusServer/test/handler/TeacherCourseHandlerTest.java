package handler;

import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.MarkTeacherApplicationReadDTO;
import dto.course.teacher.TeacherApplicationDTO;
import dto.course.teacher.TeacherApplicationDetailDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;
import exception.DatabaseException;
import network.MessageDispatcher;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.TeacherAccessPolicy;
import service.TeacherApplicationService;
import service.TeacherCourseQueryService;
import session.SessionManager;
import session.UserSession;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师课程 Handler 的鉴权、身份来源、参数校验与错误映射矩阵。
 *
 * <p>关键边界：教师 UID 只能来自 Session，请求体里的 uid/teacherId/sender 一律不参与身份判定；
 * 数据库异常只进服务端日志，绝不把 SQL 或堆栈写进响应。
 */
public final class TeacherCourseHandlerTest {
    private static final String TEACHER_A = "teacher-a";
    private static final String TEACHER_B = "teacher-b";
    private static final String OFFERING_A = "9007199254740993";
    private static final String OFFERING_B = "9007199254740995";
    private static final String PLANNED_OFFERING = "9007199254740999";
    private static final String PLAN_ID = "7001";
    /** 「我的申请」的 BIGINT 申请 ID（同样超出 JavaScript 安全整数）与它当前的状态键。 */
    private static final String APPLICATION_ID = "9007199254740994";
    private static final String STATE_KEY = "PENDING:2026-09-14T08:00:00Z";

    private TeacherCourseHandlerTest() {
    }

    public static void main(String[] args) {
        SessionManager sessions = SessionManager.getInstance();
        UserSession teacherA = sessions.createSession(TEACHER_A, "教师");
        UserSession teacherB = sessions.createSession(TEACHER_B, "教师");
        UserSession student = sessions.createSession("student-a", "学生");
        UserSession administrator = sessions.createSession("admin-a", "管理员");
        try {
            FakeQueryService queries = new FakeQueryService();
            queries.owner.put(OFFERING_A, TEACHER_A);
            queries.owner.put(OFFERING_B, TEACHER_B);
            queries.owner.put(PLANNED_OFFERING, TEACHER_A);
            TeacherCourseHandler handler = new TeacherCourseHandler(queries);

            authenticationMatrix(handler, teacherA, student, administrator);
            identityComesOnlyFromTheSession(handler, queries, teacherA);
            responseKeysAndQueryArguments(handler, queries, teacherA);
            bigIntegerIdsStayExact(handler, queries, teacherA);
            malformedInputIsBadRequest(handler, teacherA);
            teachingScheduleAccessAndParameters(handler, queries, teacherA, student, administrator);
            databaseFailureStaysInTheServerLog(handler, queries, teacherA);
            runtimeFailureStaysInTheServerLog(handler, queries, teacherA);
            dispatcherRoutesTheTeacherModule(handler, teacherA);

            // 任务六 T3 的三个「我的申请」动作走各自注入的服务替身：它们要断言的是
            // 响应键、写请求体的位置与两条新 catch 分支的 MessageCode，不是查询服务。
            FakeApplicationService applications = new FakeApplicationService();
            TeacherCourseHandler applicationHandler =
                    new TeacherCourseHandler(queries, null, null, null, null, applications);
            applicationActionsExposeTheirKeysAndArguments(applicationHandler, applications, teacherA);
            applicationActionsRejectIllegalFiltersAndBodies(applicationHandler, applications, teacherA);
            applicationActionsMapTheirExceptionTypesToTheirOwnCodes(applicationHandler, applications,
                    teacherA);
            applicationActionsReportNotWiredWhenTheServiceIsMissing(queries, teacherA);
        } finally {
            sessions.removeSession(teacherA.getToken());
            sessions.removeSession(teacherB.getToken());
            sessions.removeSession(student.getToken());
            sessions.removeSession(administrator.getToken());
        }
        System.out.println("Teacher course handler test passed.");
    }

    private static void authenticationMatrix(TeacherCourseHandler handler, UserSession teacher,
            UserSession student, UserSession administrator) {
        require(handler.handle(courseTeacher("listTerms", null)).getCode() == MessageCode.UNAUTHORIZED,
                "a missing token must be unauthorized");
        require(handler.handle(courseTeacher("listTerms", "")).getCode() == MessageCode.UNAUTHORIZED,
                "an empty token must be unauthorized");
        require(handler.handle(courseTeacher("listTerms", "expired-token")).getCode()
                        == MessageCode.UNAUTHORIZED,
                "an expired token must be unauthorized");
        require(handler.handle(courseTeacher("listTerms", student.getToken())).getCode()
                        == MessageCode.FORBIDDEN,
                "a student token must be forbidden");
        require(handler.handle(courseTeacher("listTerms", administrator.getToken())).getCode()
                        == MessageCode.FORBIDDEN,
                "an administrator token must be forbidden");
        require(handler.handle(courseTeacher("listTerms", teacher.getToken())).getCode()
                        == MessageCode.SUCCESS,
                "a teacher token must be accepted");
    }

    /**
     * The request body carries another teacher's uid, teacherId and sender. Identity must still be
     * teacher A, so A's own offering stays reachable and B's offering stays forbidden.
     */
    private static void identityComesOnlyFromTheSession(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher) {
        Message forgedOwner = courseTeacher("getOffering", teacher.getToken());
        forgedOwner.putData("uid", TEACHER_B);
        forgedOwner.putData("teacherId", TEACHER_B);
        forgedOwner.setSender(TEACHER_B);
        forgedOwner.putData("offeringId", OFFERING_B);

        Message denied = handler.handle(forgedOwner);
        require(denied.getCode() == MessageCode.FORBIDDEN,
                "a forged uid must not hand over another teacher's offering");
        require(denied.getMessage() != null && !denied.getMessage().contains("SELECT")
                        && !denied.getMessage().contains("course_offering"),
                "a denial must not leak internal relations or SQL");
        require(TEACHER_A.equals(queries.lastUid),
                "the query must run as the session teacher, never as the forged uid");

        Message forgedOnOwnOffering = courseTeacher("getOffering", teacher.getToken());
        forgedOnOwnOffering.putData("uid", TEACHER_B);
        forgedOnOwnOffering.putData("teacherId", TEACHER_B);
        forgedOnOwnOffering.setSender(TEACHER_B);
        forgedOnOwnOffering.putData("offeringId", OFFERING_A);
        require(handler.handle(forgedOnOwnOffering).getCode() == MessageCode.SUCCESS,
                "the session teacher must keep access to its own offering");
        require(TEACHER_A.equals(queries.lastUid),
                "the forged uid must not replace the authenticated identity");

        Message forgedTerms = courseTeacher("listTerms", teacher.getToken());
        forgedTerms.putData("uid", TEACHER_B);
        forgedTerms.putData("teacherId", TEACHER_B);
        require(handler.handle(forgedTerms).getCode() == MessageCode.SUCCESS,
                "listTerms must be served for the session teacher");
        require(TEACHER_A.equals(queries.lastUid),
                "listTerms must run as the session teacher");
    }

    private static void responseKeysAndQueryArguments(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher) {
        Message terms = handler.handle(courseTeacher("listTerms", teacher.getToken()));
        require(terms.getCode() == MessageCode.SUCCESS, "listTerms must succeed");
        requireOnlyKey(terms, "terms");
        require(queries.lastUid.equals(TEACHER_A), "listTerms must pass the session uid");

        Message offerings = courseTeacher("listOfferings", teacher.getToken());
        offerings.putData("academicYear", 2025);
        offerings.putData("semester", 3);
        offerings.putData("query", "数据");
        offerings.putData("page", 2);
        offerings.putData("size", 20);
        Message listed = handler.handle(offerings);
        require(listed.getCode() == MessageCode.SUCCESS, "listOfferings must succeed");
        requireOnlyKey(listed, "offerings");
        require(listed.getData("offerings") instanceof TeacherPageDTO,
                "the offerings key must carry the generic page object");
        require(queries.lastYear == 2025 && queries.lastSemester == 3,
                "the term filters must reach the query service");
        require("数据".equals(queries.lastQuery), "the search text must reach the query service");
        require(queries.lastPage == 2 && queries.lastSize == 20,
                "the paging arguments must reach the query service");

        Message detail = courseTeacher("getOffering", teacher.getToken());
        detail.putData("offeringId", OFFERING_A);
        Message loaded = handler.handle(detail);
        require(loaded.getCode() == MessageCode.SUCCESS, "getOffering must succeed");
        requireOnlyKey(loaded, "offering");

        Message roster = courseTeacher("listOfferingStudents", teacher.getToken());
        roster.putData("offeringId", OFFERING_A);
        roster.putData("page", 1);
        roster.putData("size", 20);
        Message students = handler.handle(roster);
        require(students.getCode() == MessageCode.SUCCESS, "listOfferingStudents must succeed");
        requireOnlyKey(students, "students");
        require(students.getData("students") instanceof TeacherPageDTO,
                "the students key must carry the generic page object");
        require(queries.lastEnrollmentStatus == null,
                "an absent enrollmentStatus must stay null, never a default");
        require(queries.lastQuery == null,
                "an absent query must stay null, never a default");

        Message droppedOnly = courseTeacher("listOfferingStudents", teacher.getToken());
        droppedOnly.putData("offeringId", OFFERING_A);
        droppedOnly.putData("query", " 张三 ");
        droppedOnly.putData("enrollmentStatus", 3);
        droppedOnly.putData("page", 1);
        droppedOnly.putData("size", 20);
        require(handler.handle(droppedOnly).getCode() == MessageCode.SUCCESS,
                "a dropped-status filter must be accepted");
        require(queries.lastEnrollmentStatus != null && queries.lastEnrollmentStatus == 3,
                "enrollmentStatus 3 must reach the query service");
        require(" 张三 ".equals(queries.lastQuery), "the search text must travel unchanged");

        Message schedules = courseTeacher("listOfferingSchedules", teacher.getToken());
        schedules.putData("offeringId", OFFERING_A);
        Message arrangements = handler.handle(schedules);
        require(arrangements.getCode() == MessageCode.SUCCESS,
                "listOfferingSchedules must succeed");
        requireOnlyKey(arrangements, "schedules");
    }

    private static void bigIntegerIdsStayExact(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher) {
        Message detail = courseTeacher("getOffering", teacher.getToken());
        detail.putData("offeringId", PLANNED_OFFERING);
        require(handler.handle(detail).getCode() == MessageCode.SUCCESS,
                "an offering ID beyond the JavaScript safe integer must be accepted");
        require(PLANNED_OFFERING.equals(queries.lastOfferingId),
                "the offering ID must reach the query service as the exact decimal string");

        Message roster = courseTeacher("listOfferingStudents", teacher.getToken());
        roster.putData("offeringId", PLANNED_OFFERING);
        roster.putData("page", 1);
        roster.putData("size", 20);
        require(handler.handle(roster).getCode() == MessageCode.SUCCESS,
                "the roster must accept a BIGINT offering ID");
        require(PLANNED_OFFERING.equals(queries.lastOfferingId),
                "the roster must keep the exact decimal offering ID");
    }

    private static void malformedInputIsBadRequest(TeacherCourseHandler handler,
            UserSession teacher) {
        require(handler.handle(courseTeacher("   ", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "a blank action must be a bad request");
        require(handler.handle(courseTeacher("deleteOffering", teacher.getToken())).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown action must be a bad request");

        require(offeringRequest(handler, teacher, "getOffering", null).getCode()
                        == MessageCode.BAD_REQUEST,
                "a missing offeringId must be a bad request");
        require(offeringRequest(handler, teacher, "getOffering", "9.5").getCode()
                        == MessageCode.BAD_REQUEST,
                "a fractional offeringId must be a bad request");
        require(offeringRequest(handler, teacher, "getOffering", "abc").getCode()
                        == MessageCode.BAD_REQUEST,
                "a non-numeric offeringId must be a bad request");
        require(offeringRequest(handler, teacher, "getOffering", "0").getCode()
                        == MessageCode.BAD_REQUEST,
                "a zero offeringId must be a bad request");
        require(offeringRequest(handler, teacher, "getOffering", "-1").getCode()
                        == MessageCode.BAD_REQUEST,
                "a negative offeringId must be a bad request");
        require(offeringRequest(handler, teacher, "getOffering",
                "99999999999999999999999").getCode() == MessageCode.BAD_REQUEST,
                "an offeringId beyond BIGINT must be a bad request");

        // BIGINT 标识在网络上是十进制字符串：数字类型必须被拒绝，绝不能经 double 静默改写。
        Message numericId = courseTeacher("getOffering", teacher.getToken());
        numericId.putData("offeringId", 9007199254740993L);
        require(handler.handle(numericId).getCode() == MessageCode.BAD_REQUEST,
                "a numeric offeringId must be rejected, never coerced");
        Message doubleId = courseTeacher("getOffering", teacher.getToken());
        doubleId.putData("offeringId", 9007199254740993.0);
        require(handler.handle(doubleId).getCode() == MessageCode.BAD_REQUEST,
                "a double offeringId must be rejected, never routed through a double");
        Message numericAcademicYear = courseTeacher("listOfferings", teacher.getToken());
        numericAcademicYear.putData("academicYear", "2025");
        numericAcademicYear.putData("semester", 3);
        numericAcademicYear.putData("page", 1);
        numericAcademicYear.putData("size", 20);
        require(handler.handle(numericAcademicYear).getCode() == MessageCode.SUCCESS,
                "a numeric-string academicYear must be accepted as an integer");

        Message missingYear = courseTeacher("listOfferings", teacher.getToken());
        missingYear.putData("semester", 3);
        missingYear.putData("page", 1);
        missingYear.putData("size", 20);
        require(handler.handle(missingYear).getCode() == MessageCode.BAD_REQUEST,
                "a missing academicYear must be a bad request");

        require(yearRequest(handler, teacher, "abc", 3, 1, 20).getCode() == MessageCode.BAD_REQUEST,
                "a non-numeric academicYear must be a bad request");
        require(yearRequest(handler, teacher, 2025, 4, 1, 20).getCode() == MessageCode.BAD_REQUEST,
                "a semester outside 1..3 must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, 1.5, 20).getCode()
                        == MessageCode.BAD_REQUEST,
                "a fractional page must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, 0, 20).getCode()
                        == MessageCode.BAD_REQUEST,
                "page 0 must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, -3, 20).getCode()
                        == MessageCode.BAD_REQUEST,
                "a negative page must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, 1, 0).getCode()
                        == MessageCode.BAD_REQUEST,
                "size 0 must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, 1, 101).getCode()
                        == MessageCode.BAD_REQUEST,
                "size 101 must be a bad request");
        require(yearRequest(handler, teacher, 2025, 3, 2000000000, 100).getCode()
                        == MessageCode.BAD_REQUEST,
                "a page whose offset overflows the DAO contract must be a bad request");

        Message numericQuery = courseTeacher("listOfferings", teacher.getToken());
        numericQuery.putData("academicYear", 2025);
        numericQuery.putData("semester", 3);
        numericQuery.putData("query", 5);
        numericQuery.putData("page", 1);
        numericQuery.putData("size", 20);
        require(handler.handle(numericQuery).getCode() == MessageCode.BAD_REQUEST,
                "a non-string query must be a bad request");

        for (Object illegal : new Object[] {1, 4, "2x", 2.5, true}) {
            Message status = courseTeacher("listOfferingStudents", teacher.getToken());
            status.putData("offeringId", OFFERING_A);
            status.putData("enrollmentStatus", illegal);
            status.putData("page", 1);
            status.putData("size", 20);
            require(handler.handle(status).getCode() == MessageCode.BAD_REQUEST,
                    "an illegal enrollmentStatus must be a bad request: " + illegal);
        }

        Message allowedStatus = courseTeacher("listOfferingStudents", teacher.getToken());
        allowedStatus.putData("offeringId", OFFERING_A);
        allowedStatus.putData("enrollmentStatus", 2);
        allowedStatus.putData("page", 1);
        allowedStatus.putData("size", 20);
        require(handler.handle(allowedStatus).getCode() == MessageCode.SUCCESS,
                "enrollmentStatus 2 must be accepted");
    }

    /**
     * 教师课表的鉴权、参数与身份来源。
     *
     * <p>{@code week} 可缺省（服务端按教学日历决定当前周），但一旦出现就必须是合法整数；越界由服务
     * 层判定并映射为 BAD_REQUEST。教师 UID 依然只来自 Session——伪造的 uid/teacherId/sender 不参与
     * 任何判定。失败响应同样只有 code/message。
     */
    private static void teachingScheduleAccessAndParameters(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher, UserSession student,
            UserSession administrator) {
        int before = queries.scheduleCalls;
        Message missingToken = handler.handle(scheduleRequest(null, 2025, 3, null));
        require(missingToken.getCode() == MessageCode.UNAUTHORIZED,
                "a missing token must not reach the timetable query");
        requireFailureOnlyCodeAndMessage(missingToken);
        Message expiredToken = handler.handle(scheduleRequest("expired-token", 2025, 3, null));
        require(expiredToken.getCode() == MessageCode.UNAUTHORIZED,
                "an expired token must not reach the timetable query");
        requireFailureOnlyCodeAndMessage(expiredToken);
        Message deniedStudent = handler.handle(scheduleRequest(student.getToken(), 2025, 3, null));
        require(deniedStudent.getCode() == MessageCode.FORBIDDEN,
                "a student must not read a teacher timetable");
        requireFailureOnlyCodeAndMessage(deniedStudent);
        Message deniedAdministrator =
                handler.handle(scheduleRequest(administrator.getToken(), 2025, 3, null));
        require(deniedAdministrator.getCode() == MessageCode.FORBIDDEN,
                "an administrator must not read a teacher timetable");
        requireFailureOnlyCodeAndMessage(deniedAdministrator);
        require(queries.scheduleCalls == before,
                "a rejected request must not reach the timetable query");

        Message defaultWeek = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, null));
        require(defaultWeek.getCode() == MessageCode.SUCCESS,
                "a teacher must read its own timetable");
        requireOnlyKey(defaultWeek, "schedule");
        require(defaultWeek.getData("schedule") instanceof TeacherScheduleWeekDTO,
                "the schedule key must carry the week DTO");
        require(TEACHER_A.equals(queries.lastUid),
                "the timetable must run as the session teacher");
        require(queries.lastYear == 2025 && queries.lastSemester == 3,
                "the term must reach the timetable query");
        require(queries.lastWeek == null,
                "an absent week must reach the query as null so the server picks the week");

        Message explicitWeek = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, 9));
        require(explicitWeek.getCode() == MessageCode.SUCCESS, "an explicit week must succeed");
        require(queries.lastWeek != null && queries.lastWeek == 9,
                "an explicit week must reach the query unchanged");

        Message forged = scheduleRequest(teacher.getToken(), 2025, 3, 8);
        forged.putData("uid", TEACHER_B);
        forged.putData("teacherId", TEACHER_B);
        forged.setSender(TEACHER_B);
        require(handler.handle(forged).getCode() == MessageCode.SUCCESS,
                "a forged identity must not break the timetable read");
        require(TEACHER_A.equals(queries.lastUid),
                "the timetable must never run as a forged uid");

        Message missingYear = courseTeacher("loadTeachingSchedule", teacher.getToken());
        missingYear.putData("semester", 3);
        require(handler.handle(missingYear).getCode() == MessageCode.BAD_REQUEST,
                "a missing academicYear must be a bad request");

        Message nonNumericWeek = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, "abc"));
        require(nonNumericWeek.getCode() == MessageCode.BAD_REQUEST,
                "a non-integer week must be a bad request");
        Message outOfRangeWeek = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, 17));
        require(outOfRangeWeek.getCode() == MessageCode.BAD_REQUEST,
                "a week beyond maxWeek must be a bad request");
        Message zeroWeek = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, 0));
        require(zeroWeek.getCode() == MessageCode.BAD_REQUEST,
                "week 0 must be a bad request");
        for (Message rejected : new Message[] {nonNumericWeek, outOfRangeWeek, zeroWeek}) {
            requireFailureOnlyCodeAndMessage(rejected);
            String message = rejected.getMessage();
            require(message != null && !message.contains(TEACHER_A) && !message.contains(TEACHER_B),
                    "a rejection must not depend on the session identity, saw " + message);
        }

        queries.emptyTerm = true;
        try {
            Message emptyTerm = handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, 8));
            require(emptyTerm.getCode() == MessageCode.BAD_REQUEST,
                    "a term without a published calendar must be a bad request");
            require("该学期暂无已发布的教学日历".equals(emptyTerm.getMessage()),
                    "the empty-term reason must be returned verbatim, saw " + emptyTerm.getMessage());
        } finally {
            queries.emptyTerm = false;
        }

        queries.mode = Mode.DATABASE;
        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message scheduleDatabaseFailure;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            scheduleDatabaseFailure =
                    handler.handle(scheduleRequest(teacher.getToken(), 2025, 3, 8));
        } finally {
            System.setErr(previousError);
            queries.mode = Mode.SUCCESS;
        }
        require(scheduleDatabaseFailure.getCode() == MessageCode.ERROR,
                "a database failure on the timetable must be a server error");
        require("教师课程服务暂不可用".equals(scheduleDatabaseFailure.getMessage()),
                "a database failure must return the generic message");
        String scheduleFailureMessage = scheduleDatabaseFailure.getMessage();
        require(!scheduleFailureMessage.contains("SELECT")
                        && !scheduleFailureMessage.contains("course_schedule")
                        && !scheduleFailureMessage.contains("SQLException")
                        && !scheduleFailureMessage.contains("at handler"),
                "the timetable failure must not leak SQL, table names or a stack trace");
    }

    private static Message scheduleRequest(String token, Object academicYear, Object semester,
            Object week) {
        Message request = courseTeacher("loadTeachingSchedule", token);
        if (academicYear != null) request.putData("academicYear", academicYear);
        if (semester != null) request.putData("semester", semester);
        if (week != null) request.putData("week", week);
        return request;
    }

    private static void requireFailureOnlyCodeAndMessage(Message response) {
        Map<String, Object> data = response.getData();
        require(data == null || data.isEmpty(),
                "a failure response must carry only code/message, saw " + data);
    }

    private static void databaseFailureStaysInTheServerLog(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher) {
        queries.mode = Mode.DATABASE;
        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message response;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            response = handler.handle(courseTeacher("listTerms", teacher.getToken()));
        } finally {
            System.setErr(previousError);
            queries.mode = Mode.SUCCESS;
        }
        require(response.getCode() == MessageCode.ERROR,
                "a database failure must map to the server error code");
        require("教师课程服务暂不可用".equals(response.getMessage()),
                "a database failure must return a generic message");
        require(!response.getMessage().contains("SELECT")
                        && !response.getMessage().contains("course_offering"),
                "a database failure must not leak SQL or table names");
        String logged = log.toString(StandardCharsets.UTF_8);
        require(logged.contains("action=listTerms") && logged.contains("DatabaseException")
                        && logged.contains("SELECT secret"),
                "the database failure detail must remain in the server log");
    }

    private static void runtimeFailureStaysInTheServerLog(TeacherCourseHandler handler,
            FakeQueryService queries, UserSession teacher) {
        queries.mode = Mode.RUNTIME;
        PrintStream previousError = System.err;
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Message response;
        try {
            System.setErr(new PrintStream(log, true, StandardCharsets.UTF_8));
            response = handler.handle(courseTeacher("listTerms", teacher.getToken()));
        } finally {
            System.setErr(previousError);
            queries.mode = Mode.SUCCESS;
        }
        require(response.getCode() == MessageCode.ERROR,
                "a runtime failure must map to the server error code");
        require("服务端内部错误".equals(response.getMessage()),
                "a runtime failure must not leak exception text");
        String logged = log.toString(StandardCharsets.UTF_8);
        require(logged.contains("action=listTerms") && logged.contains("IllegalStateException")
                        && logged.contains("runtime probe"),
                "the runtime failure detail must remain in the server log");
    }

    private static void dispatcherRoutesTheTeacherModule(TeacherCourseHandler handler,
            UserSession teacher) {
        Message unrouted = courseTeacher("listTerms", "expired-token");
        require(new MessageDispatcher().dispatch(unrouted).getCode() == MessageCode.UNAUTHORIZED,
                "the default dispatcher must route courseTeacher");

        Message routed = courseTeacher("listTerms", teacher.getToken());
        Message routedResponse = new MessageDispatcher(handler).dispatch(routed);
        require(routedResponse.getCode() == MessageCode.SUCCESS,
                "an injected teacher handler must serve the courseTeacher module");
        require(routed.getUID().equals(routedResponse.getUID()),
                "the dispatcher must preserve the transport request UID");
        require("courseTeacher".equals(routedResponse.getModule()),
                "the teacher response must keep the courseTeacher module");

        Message unknown = new Message(MessageType.REQUEST, "courseStudent", "listOfferings");
        unknown.setToken(teacher.getToken());
        require(new MessageDispatcher(handler).dispatch(unknown).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown module must stay a bad request");

        Message legacyCourse = new Message(MessageType.REQUEST, "course", "listTerms");
        legacyCourse.setToken("expired-token");
        require(new MessageDispatcher().dispatch(legacyCourse).getCode()
                        == MessageCode.UNAUTHORIZED,
                "the default dispatcher must keep routing the course module");
        require(new MessageDispatcher(new CourseHandler()).dispatch(legacyCourse).getCode()
                        == MessageCode.UNAUTHORIZED,
                "the single-argument dispatcher must keep routing the course module");

        Message legacyAdmin = new Message(MessageType.REQUEST, "courseAdmin", "listCourses");
        legacyAdmin.setToken("expired-token");
        require(new MessageDispatcher(new CourseHandler(), new AdminCourseHandler())
                        .dispatch(legacyAdmin).getCode() == MessageCode.UNAUTHORIZED,
                "the two-argument dispatcher must keep routing the courseAdmin module");
    }

    // ------------------------------------------------ 我的申请（任务六 T3）

    /**
     * 三个新动作的成功路径：各自写出自己的响应键，参数原样到达服务，身份只来自会话。
     *
     * <p>{@code applications} 是合并分页、{@code application} 是单数详情、{@code result} 是写操作的
     * 信封——键错了客户端会以 {@code 缺少响应字段: <key>} 失败，所以这里逐个钉住。
     */
    private static void applicationActionsExposeTheirKeysAndArguments(TeacherCourseHandler handler,
            FakeApplicationService applications, UserSession teacher) {
        Message list = courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        list.putData("type", TeacherApplicationDTO.GRADE_SUBMISSION);
        list.putData("status", "APPROVED");
        list.putData("page", 2);
        list.putData("size", 20);
        Message listed = handler.handle(list);
        require(listed.getCode() == MessageCode.SUCCESS, "listMyApplications must succeed");
        requireOnlyKey(listed, "applications");
        require(listed.getData("applications") instanceof TeacherPageDTO,
                "the applications key must carry the generic page object");
        require(TEACHER_A.equals(applications.lastUid),
                "the list must run as the session teacher");
        require(TeacherApplicationDTO.GRADE_SUBMISSION.equals(applications.lastType)
                        && "APPROVED".equals(applications.lastStatus)
                        && applications.lastPage == 2 && applications.lastSize == 20,
                "the type/status filters and the paging must reach the service unchanged, saw "
                        + applications.lastType + "|" + applications.lastStatus + "|"
                        + applications.lastPage + "|" + applications.lastSize);

        // 不限类型/状态：两个筛选都不出现，服务端按「全部」处理，而不是客户端编一个默认值。
        Message unfiltered =
                courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        unfiltered.putData("page", 1);
        unfiltered.putData("size", 20);
        require(handler.handle(unfiltered).getCode() == MessageCode.SUCCESS,
                "an unfiltered list must succeed");
        require(applications.lastType == null && applications.lastStatus == null,
                "absent filters must stay null, never a fabricated default, saw "
                        + applications.lastType + "|" + applications.lastStatus);

        Message detail = courseTeacher(TeacherCourseActions.GET_MY_APPLICATION, teacher.getToken());
        detail.putData("type", TeacherApplicationDTO.SCHEDULE_ADJUSTMENT);
        detail.putData("id", APPLICATION_ID);
        Message loaded = handler.handle(detail);
        require(loaded.getCode() == MessageCode.SUCCESS, "getMyApplication must succeed");
        requireOnlyKey(loaded, "application");
        require(loaded.getData("application") instanceof TeacherApplicationDetailDTO,
                "the application key must carry the typed detail object");
        require(TEACHER_A.equals(applications.lastUid),
                "the detail must run as the session teacher");
        require(APPLICATION_ID.equals(applications.lastId),
                "an id beyond the JavaScript safe integer must reach the service as the exact"
                        + " decimal string, saw " + applications.lastId);

        Message read = readRequest(teacher,
                readBody(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID, STATE_KEY));
        Message marked = handler.handle(read);
        require(marked.getCode() == MessageCode.SUCCESS, "markApplicationRead must succeed");
        requireOnlyKey(marked, "result");
        Object envelope = marked.getData("result");
        require(envelope instanceof TeacherOperationResultDTO<?> result
                        && result.getValue() == applications.readRow,
                "the result key must carry the operation envelope whose value is the refreshed"
                        + " row, saw " + envelope);
        require("申请结果已标记为已读".equals(marked.getMessage()),
                "the write response must carry the operation message, saw " + marked.getMessage());
        require(TEACHER_A.equals(applications.lastUid),
                "the write must run as the session teacher");
        require(APPLICATION_ID.equals(applications.lastId) && STATE_KEY.equals(applications.lastStateKey),
                "the id and the seen state key must reach the service unchanged, saw "
                        + applications.lastId + "|" + applications.lastStateKey);
    }

    /**
     * 形状与白名单的拒绝：类型/状态先过白名单，写请求体的 BIGINT 只接受十进制字符串，
     * 伪造的身份字段出现即拒。每一条都必须在本动作内变成 BAD_REQUEST，而不是走到服务里。
     */
    private static void applicationActionsRejectIllegalFiltersAndBodies(TeacherCourseHandler handler,
            FakeApplicationService applications, UserSession teacher) {
        require(applicationList(handler, teacher, "SOMETHING_ELSE", null).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown application type must be a bad request");
        require(applicationList(handler, teacher, "grade_submission", null).getCode()
                        == MessageCode.BAD_REQUEST,
                "the type must match the whitelist exactly, never case-insensitively");
        require(applicationList(handler, teacher, TeacherApplicationDTO.GRADE_SUBMISSION,
                "WITHDRAWN").getCode() == MessageCode.BAD_REQUEST,
                "a status outside that type's alphabet must be a bad request, not ignored");
        require(applicationList(handler, teacher, null, "CANCELLED").getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown status must be a bad request even with an open type");
        require(applicationList(handler, teacher, 7, null).getCode() == MessageCode.BAD_REQUEST,
                "a non-string type must be a bad request");
        require(applicationList(handler, teacher, null, 5).getCode() == MessageCode.BAD_REQUEST,
                "a non-string status must be a bad request");
        Message missingPaging =
                courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        missingPaging.putData("type", TeacherApplicationDTO.SCHEDULE_ADJUSTMENT);
        require(handler.handle(missingPaging).getCode() == MessageCode.BAD_REQUEST,
                "a missing page/size must be a bad request");
        require(applicationListOverflow(handler, teacher).getCode() == MessageCode.BAD_REQUEST,
                "a page whose offset overflows the DAO contract must be a bad request");

        int detailCalls = applications.detailCalls;
        require(detailRequest(handler, teacher, null, APPLICATION_ID).getCode()
                        == MessageCode.BAD_REQUEST,
                "a missing type must be a bad request");
        require(detailRequest(handler, teacher, "  ", APPLICATION_ID).getCode()
                        == MessageCode.BAD_REQUEST,
                "a blank type must be a bad request");
        require(detailRequest(handler, teacher, "SOMETHING_ELSE", APPLICATION_ID).getCode()
                        == MessageCode.BAD_REQUEST,
                "an unknown type must be a bad request");
        require(detailRequest(handler, teacher, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, null)
                        .getCode() == MessageCode.BAD_REQUEST,
                "a missing id must be a bad request");
        require(detailRequest(handler, teacher, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "abc")
                        .getCode() == MessageCode.BAD_REQUEST,
                "a non-numeric id must be a bad request");
        require(detailRequest(handler, teacher, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "0")
                        .getCode() == MessageCode.BAD_REQUEST,
                "a zero id must be a bad request");
        require(detailRequest(handler, teacher, TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "9.5")
                        .getCode() == MessageCode.BAD_REQUEST,
                "a fractional id must be a bad request");
        Message numericId = courseTeacher(TeacherCourseActions.GET_MY_APPLICATION,
                teacher.getToken());
        numericId.putData("type", TeacherApplicationDTO.SCHEDULE_ADJUSTMENT);
        numericId.putData("id", 9007199254740994L);
        require(handler.handle(numericId).getCode() == MessageCode.BAD_REQUEST,
                "a numeric id must be rejected, never coerced through a double");
        require(applications.detailCalls == detailCalls,
                "no rejected detail request may reach the service, saw "
                        + (applications.detailCalls - detailCalls) + " calls");

        int readCalls = applications.readCalls;
        Message notAnObject =
                courseTeacher(TeacherCourseActions.MARK_APPLICATION_READ, teacher.getToken());
        notAnObject.putData("request", "not-an-object");
        require(handler.handle(notAnObject).getCode() == MessageCode.BAD_REQUEST,
                "a write body that is not a JSON object must be a bad request");
        Message missingBody =
                courseTeacher(TeacherCourseActions.MARK_APPLICATION_READ, teacher.getToken());
        require(handler.handle(missingBody).getCode() == MessageCode.BAD_REQUEST,
                "a missing write body must be a bad request");
        Map<String, Object> forgedBody = readBody(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT,
                APPLICATION_ID, STATE_KEY);
        forgedBody.put("uid", TEACHER_B);
        require(handler.handle(readRequest(teacher, forgedBody)).getCode()
                        == MessageCode.BAD_REQUEST,
                "a forged uid inside the write body must be rejected before the service");
        require(handler.handle(readRequest(teacher, readBody(
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, 9007199254740994L, STATE_KEY)))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a numeric id in the write body must be a bad request");
        require(handler.handle(readRequest(teacher, readBody(
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, "0", STATE_KEY))).getCode()
                        == MessageCode.BAD_REQUEST,
                "a zero id in the write body must be a bad request");
        require(handler.handle(readRequest(teacher, readBody(
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID, 42))).getCode()
                        == MessageCode.BAD_REQUEST,
                "a non-string expectedStateKey must be a bad request");
        require(applications.readCalls == readCalls,
                "no malformed confirmation may reach the service, saw "
                        + (applications.readCalls - readCalls) + " calls");

        // 形状合法、语义为空的两种请求由服务层判定（Handler 只保证形状，类型白名单与状态键非空
        // 是服务层的规则），所以它们**必须**到达服务，并且仍然以 BAD_REQUEST 结束——服务抛的是
        // IllegalArgumentException，不允许变成 500 式的 ERROR。
        require(handler.handle(readRequest(teacher, readBody(null, APPLICATION_ID, STATE_KEY)))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a missing type in the write body must be a bad request");
        require(handler.handle(readRequest(teacher, readBody(
                        TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID, null)))
                        .getCode() == MessageCode.BAD_REQUEST,
                "a missing expectedStateKey must be a bad request");
        require(applications.readCalls == readCalls + 2,
                "exactly the two semantic rejections are judged by the service, saw "
                        + (applications.readCalls - readCalls) + " calls");
    }

    /**
     * Ruling G 的实测：这两条 catch 分支各自映射成自己的 {@link MessageCode}。
     *
     * <p>没有这两条分支时，两个异常都会掉进最后的 {@code RuntimeException} 分支，变成
     * {@code ERROR / 服务端内部错误}——所以下面每一条断言的都是 code 本身，而冲突那条还要并接着
     * 断言「当前申请行放在 {@code application} 键上，且没有伪造调课/成绩的冲突载体」。
     */
    private static void applicationActionsMapTheirExceptionTypesToTheirOwnCodes(
            TeacherCourseHandler handler, FakeApplicationService applications, UserSession teacher) {
        applications.mode = ApplicationMode.NOT_FOUND;
        Message missingDetail = detailRequest(handler, teacher,
                TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID);
        require(missingDetail.getCode() == MessageCode.NOT_FOUND,
                "a missing or foreign application must map to NOT_FOUND, saw "
                        + missingDetail.getCode());
        requireFailureOnlyCodeAndMessage(missingDetail);
        Message missingRead = handler.handle(readRequest(teacher,
                readBody(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID, STATE_KEY)));
        require(missingRead.getCode() == MessageCode.NOT_FOUND,
                "marking an invisible application must map to NOT_FOUND, saw "
                        + missingRead.getCode());

        applications.mode = ApplicationMode.CONFLICT;
        TeacherApplicationDTO current = applications.conflictRow;
        Message stale = handler.handle(readRequest(teacher,
                readBody(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID, STATE_KEY)));
        require(stale.getCode() == MessageCode.CONFLICT,
                "a stale confirmation must map to CONFLICT, saw " + stale.getCode());
        require("申请结果已更新，请刷新后重试".equals(stale.getMessage()),
                "the conflict must keep the server message, saw " + stale.getMessage());
        Map<String, Object> data = stale.getData();
        require(data != null && data.size() == 1 && data.get("application") == current,
                "the conflict must carry the current row under the application key and nothing"
                        + " else, saw " + data);
        require(!List.of("conflicts", "latest", "gradeBook").stream()
                        .anyMatch(key -> data.containsKey(key)),
                "the conflict must not fabricate the adjustment or grade conflict carriers, saw "
                        + data);
        applications.mode = ApplicationMode.SUCCESS;
    }

    /** 未接线时明确报「尚未开放」，与其它可选服务的处理一致，而不是掉进 NPE。 */
    private static void applicationActionsReportNotWiredWhenTheServiceIsMissing(
            TeacherCourseQueryService queries, UserSession teacher) {
        TeacherCourseHandler bare = new TeacherCourseHandler(queries, null, null, null, null, null);
        Message list = courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        list.putData("page", 1);
        list.putData("size", 20);
        Message response = bare.handle(list);
        require(response.getCode() == MessageCode.BAD_REQUEST,
                "an unwired applications service must be a bad request, saw " + response.getCode());
        require("该教师操作尚未开放".equals(response.getMessage()),
                "an unwired applications service must say so, saw " + response.getMessage());
    }

    private static Message applicationList(TeacherCourseHandler handler, UserSession teacher,
            Object type, Object status) {
        Message request = courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        if (type != null) request.putData("type", type);
        if (status != null) request.putData("status", status);
        request.putData("page", 1);
        request.putData("size", 20);
        return handler.handle(request);
    }

    private static Message applicationListOverflow(TeacherCourseHandler handler,
            UserSession teacher) {
        Message request = courseTeacher(TeacherCourseActions.LIST_MY_APPLICATIONS, teacher.getToken());
        request.putData("page", 2000000000);
        request.putData("size", 100);
        return handler.handle(request);
    }

    private static Message detailRequest(TeacherCourseHandler handler, UserSession teacher,
            Object type, Object id) {
        Message request = courseTeacher(TeacherCourseActions.GET_MY_APPLICATION, teacher.getToken());
        if (type != null) request.putData("type", type);
        if (id != null) request.putData("id", id);
        return handler.handle(request);
    }

    private static Message readRequest(UserSession teacher, Map<String, Object> body) {
        Message request =
                courseTeacher(TeacherCourseActions.MARK_APPLICATION_READ, teacher.getToken());
        request.putData("request", body);
        return request;
    }

    private static Map<String, Object> readBody(Object type, Object id, Object stateKey) {
        Map<String, Object> body = new HashMap<>();
        if (type != null) body.put("type", type);
        if (id != null) body.put("id", id);
        if (stateKey != null) body.put("expectedStateKey", stateKey);
        return body;
    }

    /** 一行真实形状的申请：服务端算出的 stateKey 与 unread 由这个夹具带出来。 */
    private static TeacherApplicationDTO applicationRow(String status, boolean unread) {
        String handledAt = "PENDING".equals(status) ? null : "2026-09-14T09:00:00Z";
        String stateKey = handledAt == null ? "PENDING:2026-09-14T08:00:00Z"
                : status + ":" + handledAt;
        return new TeacherApplicationDTO(TeacherApplicationDTO.SCHEDULE_ADJUSTMENT, APPLICATION_ID,
                OFFERING_A, "人机交互导论　CS352-01", status, "2026-09-14T08:00:00Z", handledAt,
                "PENDING".equals(status) ? null : "同意", "PENDING".equals(status), stateKey, unread);
    }

    private enum ApplicationMode { SUCCESS, NOT_FOUND, CONFLICT }

    /**
     * 只覆写三个新方法的服务替身：本测试要的是 Handler 的键、参数与异常映射，不是服务行为
     * （服务行为由 {@code TeacherApplicationsMySqlTest} 对着真实库验证）。方法体镜像真实服务的
     * 前置校验，因此「Handler 必须在服务之前拒绝」这一类断言才有意义。
     */
    private static final class FakeApplicationService extends TeacherApplicationService {
        /** 列表与详情看到的那一行；已读确认成功之后返回的是它的「已读」版本。 */
        private final TeacherApplicationDTO row = applicationRow("PENDING", true);
        private final TeacherApplicationDTO readRow = applicationRow("PENDING", false);
        /** 过期确认时服务端带回的**当前**行：与请求方看到的那一行不是同一个对象。 */
        private final TeacherApplicationDTO conflictRow = applicationRow("APPROVED", true);
        private ApplicationMode mode = ApplicationMode.SUCCESS;
        private String lastUid;
        private String lastType;
        private String lastStatus;
        private String lastId;
        private String lastStateKey;
        private int lastPage;
        private int lastSize;
        private int detailCalls;
        private int readCalls;

        @Override
        public TeacherPageDTO<TeacherApplicationDTO> listMyApplications(String uid, String type,
                String status, int page, int size) {
            lastUid = uid;
            lastType = type;
            lastStatus = status;
            lastPage = page;
            lastSize = size;
            if (mode == ApplicationMode.NOT_FOUND) {
                throw new NotFoundException("申请不存在或不属于本人");
            }
            return new TeacherPageDTO<>(List.of(row), 1L, page, size);
        }

        @Override
        public TeacherApplicationDetailDTO getMyApplication(String uid, String type, String id) {
            detailCalls++;
            lastUid = uid;
            lastType = type;
            lastId = id;
            if (mode == ApplicationMode.NOT_FOUND) {
                throw new NotFoundException("申请不存在或不属于本人");
            }
            return new TeacherApplicationDetailDTO(row, null, null);
        }

        @Override
        public TeacherApplicationDTO markApplicationRead(String uid,
                MarkTeacherApplicationReadDTO raw) {
            readCalls++;
            lastUid = uid;
            if (raw == null) throw new IllegalArgumentException("请求体不能为空");
            lastType = raw.getType();
            lastId = raw.getId();
            lastStateKey = raw.getExpectedStateKey();
            if (raw.getType() == null || raw.getType().isBlank()) {
                throw new IllegalArgumentException("type 不能为空");
            }
            if (raw.getExpectedStateKey() == null || raw.getExpectedStateKey().isBlank()) {
                throw new IllegalArgumentException("expectedStateKey 不能为空");
            }
            if (mode == ApplicationMode.NOT_FOUND) {
                throw new NotFoundException("申请不存在或不属于本人");
            }
            if (mode == ApplicationMode.CONFLICT) {
                throw new ConflictException("申请结果已更新，请刷新后重试", conflictRow);
            }
            return readRow;
        }
    }

    private static Message courseTeacher(String action, String token) {
        Message request = new Message(MessageType.REQUEST, "courseTeacher", action);
        request.setToken(token);
        return request;
    }

    private static Message offeringRequest(TeacherCourseHandler handler, UserSession teacher,
            String action, String offeringId) {
        Message request = courseTeacher(action, teacher.getToken());
        if (offeringId != null) request.putData("offeringId", offeringId);
        return handler.handle(request);
    }

    private static Message yearRequest(TeacherCourseHandler handler, UserSession teacher,
            Object academicYear, Object semester, Object page, Object size) {
        Message request = courseTeacher("listOfferings", teacher.getToken());
        request.putData("academicYear", academicYear);
        request.putData("semester", semester);
        request.putData("page", page);
        request.putData("size", size);
        return handler.handle(request);
    }

    private static void requireOnlyKey(Message response, String key) {
        Map<String, Object> data = response.getData();
        require(data != null && data.size() == 1 && data.containsKey(key),
                "the response must expose exactly the " + key + " key, saw " + data);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Mode { SUCCESS, DATABASE, RUNTIME }

    /**
     * 以真实查询服务的签名与校验为模板的替换实现：先记录身份，再按关系判定归属。任何越界参数
     * 在到达这里之前就必须被 Handler 拒绝，否则抛出 {@link AssertionError} 让测试立刻失败。
     */
    private static final class FakeQueryService extends TeacherCourseQueryService {
        private final Map<String, String> owner = new HashMap<>();
        private Mode mode = Mode.SUCCESS;
        private String lastUid;
        private String lastOfferingId;
        private String lastQuery;
        private int lastYear;
        private int lastSemester;
        private int lastPage;
        private int lastSize;
        private Integer lastEnrollmentStatus;
        private Integer lastWeek;
        private int scheduleCalls;
        private boolean emptyTerm;

        @Override
        public List<CourseTermDTO> listTerms(String uid) {
            lastUid = uid;
            failIfRequested();
            return List.of(new CourseTermDTO(2025, 3, "2025-2026 春学期"),
                    new CourseTermDTO(2025, 2, "2025-2026 秋学期"));
        }

        @Override
        public TeacherPageDTO<TeacherOfferingDTO> listOfferings(String uid, int academicYear,
                int semester, String query, int page, int size) {
            lastUid = uid;
            lastYear = academicYear;
            lastSemester = semester;
            lastQuery = query;
            lastPage = page;
            lastSize = size;
            failIfRequested();
            requireTerm(academicYear, semester);
            requirePage(page, size);
            return new TeacherPageDTO<>(List.of(offering(OFFERING_A)), 1L, page, size);
        }

        @Override
        public TeacherOfferingDetailDTO getOffering(String uid, String offeringId) {
            lastUid = uid;
            lastOfferingId = offeringId;
            failIfRequested();
            requireOwner(uid, offeringId);
            return new TeacherOfferingDetailDTO(offering(offeringId),
                    List.of(new ScheduleResourceDTO("8001", uid, "陈老师", "teacher", 0)),
                    "计算机科学与工程学院", "专业核心课");
        }

        @Override
        public TeacherPageDTO<TeacherRosterRowDTO> listOfferingStudents(String uid, String offeringId,
                String query, Integer enrollmentStatus, int page, int size) {
            lastUid = uid;
            lastOfferingId = offeringId;
            lastQuery = query;
            lastEnrollmentStatus = enrollmentStatus;
            lastPage = page;
            lastSize = size;
            failIfRequested();
            requireOwner(uid, offeringId);
            requirePage(page, size);
            return new TeacherPageDTO<>(List.of(new TeacherRosterRowDTO("50031", "00005678", "张三",
                    "计算机科学与技术", "ENROLLED", "2026-09-01T01:00:00Z", null)), 1L, page, size);
        }

        @Override
        public List<ScheduleArrangementDTO> listOfferingSchedules(String uid, String offeringId) {
            lastUid = uid;
            lastOfferingId = offeringId;
            failIfRequested();
            requireOwner(uid, offeringId);
            return List.of(new ScheduleArrangementDTO("9503", PLAN_ID, offeringId,
                    new ScheduleResourceDTO("8001", uid, "陈老师", "teacher", 0), null,
                    new ScheduleResourceDTO("8101", "3001", "A-101", "classroom", 120),
                    List.of(new ScheduleSlotDTO(1, 1, 2)), 1, 16, "ACTIVE", 1));
        }

        /**
         * 课表查询：先记录身份与参数，再按越界/空学期/数据库故障的顺序模拟真实服务的失败。
         * Handler 未接线时这里永远不会被调用，测试会停在 BAD_REQUEST「不支持的教师课程操作」。
         */
        @Override
        public TeacherScheduleWeekDTO loadTeachingSchedule(String uid, int academicYear,
                int semester, Integer week) {
            lastUid = uid;
            lastYear = academicYear;
            lastSemester = semester;
            lastWeek = week;
            scheduleCalls++;
            failIfRequested();
            requireTerm(academicYear, semester);
            if (emptyTerm) {
                throw new IllegalArgumentException("该学期暂无已发布的教学日历");
            }
            if (week != null && (week < 1 || week > 16)) {
                throw new IllegalArgumentException("week 必须在 1..16 之间");
            }
            int selectedWeek = week == null ? 8 : week;
            return new TeacherScheduleWeekDTO(PLAN_ID, "Asia/Shanghai", selectedWeek, 1, 16, 8,
                    List.of(new TeacherCalendarDateDTO("2026-10-26", selectedWeek, 1, true)),
                    List.of(new TeacherPeriodDTO("2026-10-26", 1, "08:00:00", "08:45:00")),
                    List.of(new TeacherScheduleEntryDTO("9201", OFFERING_A, "CS203", "数据结构",
                            "陈老师", "A-101", "2026-10-26", selectedWeek, 1, 1, 2,
                            ScheduleDisplayKindDTO.NORMAL, null, null, null, null, true)));
        }

        private void requireOwner(String uid, String offeringId) {
            if (!uid.equals(owner.get(offeringId))) {
                throw new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班的权限");
            }
        }

        private void failIfRequested() {
            if (mode == Mode.DATABASE) {
                throw new DatabaseException("教师课程查询失败",
                        new SQLException("SELECT secret FROM course_offering"));
            }
            if (mode == Mode.RUNTIME) {
                throw new IllegalStateException("runtime probe");
            }
        }

        private static void requireTerm(int academicYear, int semester) {
            if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
            if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
        }

        private static void requirePage(int page, int size) {
            if (page < 1 || size < 1 || size > 100) {
                throw new AssertionError(
                        "the handler must reject out-of-range paging before the query runs");
            }
        }

        private static TeacherOfferingDTO offering(String offeringId) {
            return new TeacherOfferingDTO(offeringId, "CS203-01", "数据结构 CS203-01", "2001",
                    "CS203", "数据结构", 4.0, 2025, 3, 58, 60, "ACTIVE", true, true);
        }
    }
}
