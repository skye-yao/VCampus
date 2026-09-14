package handler;

import dto.course.CourseTermDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
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
