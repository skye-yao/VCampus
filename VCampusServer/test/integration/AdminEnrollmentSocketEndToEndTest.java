package integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dto.course.CourseActions;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.DBUtil;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP/MySQL enrollment coverage. Run after V001-V004 and seed-course-test.sql.
 * Only virtual_campus_course_test is allowed. The 96xxxx fixtures and enr-* accounts are
 * owned by this test and removed even on failure; the shared seed is not modified.
 *
 * The final scenario is the Task 5 regression for CourseSelectionDAO.changeEnrolledCount:
 * an ordinary student drop must still reduce a forced over-capacity count. It is expected to
 * fail (RED) until that predicate stops rejecting negative deltas against capacity.
 */
public final class AdminEnrollmentSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String ADMIN = "enr-admin";
    private static final String TEACHER = "enr-teacher";
    private static final String S1 = "enr-s1";
    private static final String S2 = "enr-s2";
    private static final String S3 = "enr-s3";
    private static final String S4 = "enr-s4";
    private static final String S5 = "enr-s5";
    private static final String S6 = "enr-s6";
    private static final String W1 = "enr-w1";
    private static final String W2 = "enr-w2";

    // Test-only credentials shared with seed-course-test.sql for the dedicated schema.
    private static final String PASSWORD = "course-test-only";
    private static final String SALT = "Y291cnNlLXRlc3Qtc2FsdC12MQ==";
    private static final String HASH = "J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=";

    private static final int YEAR = 2029;
    private static final int SEMESTER = 1;
    private static final long MAJOR = 960001;
    private static final long COURSE_FORCE = 960010;
    private static final long COURSE_REMOVE = 960011;
    private static final long COURSE_GRADE = 960012;
    private static final long OFFER_FORCE = 960101;
    private static final long OFFER_REMOVE = 960102;
    private static final long OFFER_GRADE = 960103;
    private static final int FORCE_CAPACITY = 1;
    private static final long CALENDAR = 960201;
    private static final long PLAN = 960202;
    private static final long SUBMISSION = 960301;
    private static final long ENROLL_S5 = 960401;
    private static final long ENROLL_S6 = 960402;
    private static final long WAITLIST_W1 = 960501;
    private static final long WAITLIST_W2 = 960502;
    private static final String FORCE_REASON = "approved extra seat";
    private static final Gson GSON = new Gson();

    private AdminEnrollmentSocketEndToEndTest() { }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        try {
            insertFixtures();
            Server server = new Server(0, new OnlineConnectionRegistry(),
                    new MessageDispatcher(), null, null);
            Thread serverThread = new Thread(server::start, "enrollment-e2e-server");
            serverThread.setDaemon(true);
            try {
                serverThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                require(server.isRunning(), "real server must start");
                try (JsonLineClient admin = new JsonLineClient(server.getPort());
                     JsonLineClient student = new JsonLineClient(server.getPort())) {
                    String adminToken = admin.login(ADMIN, "管理员");
                    String studentToken = student.login(S1, "学生");
                    verifyNegativeStudentTokenControl(student, studentToken);
                    verifyGenuineStudentSelection(student, studentToken);
                    verifySearchPreviewForceAndAudit(admin, adminToken);
                    verifyGradeWorkflowRemovalBlocked(admin, adminToken);
                    verifyEligibleRemovalAndWaitlistAdvancement(admin, adminToken);
                    verifyOrdinaryDropOfOverCapacityOffering(student, studentToken);
                }
            } finally {
                server.stop();
                serverThread.join(5_000);
                require(!serverThread.isAlive(), "server must stop after the test");
            }
        } finally {
            cleanup();
        }
        require(number("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'enr-%'") == 0,
                "test accounts must be removed");
        System.out.println("AdminEnrollmentSocketEndToEndTest: PASS");
    }

    /** A student token must never be accepted by the administrator enrollment surface. */
    private static void verifyNegativeStudentTokenControl(JsonLineClient student, String studentToken)
            throws Exception {
        requireCode(student.request("courseAdmin", AdminCourseActions.SEARCH_STUDENTS, studentToken,
                        Map.of("query", S1, "pageNumber", 1, "pageSize", 10)),
                MessageCode.FORBIDDEN, "student search under an administrator action");
        requireCode(student.request("courseAdmin", AdminCourseActions.ADD_STUDENT_TO_OFFERING,
                        studentToken, Map.of("request", enrollmentRequest(op(99), OFFER_FORCE, S2,
                                false, null))),
                MessageCode.FORBIDDEN, "student forced add under an administrator action");
        require(number("SELECT COUNT(*) FROM enrollment WHERE offering_id=?", OFFER_FORCE) == 0,
                "a rejected student token must not write enrollment");
        System.out.println("[E2E] negative student-token control: PASS");
    }

    /** Genuinely enroll S1 through the ordinary student selection path. */
    private static void verifyGenuineStudentSelection(JsonLineClient student, String studentToken)
            throws Exception {
        Message response = student.request("course", CourseActions.SELECT_OFFERING, studentToken,
                Map.of("academicYear", YEAR, "semester", SEMESTER,
                        "offeringId", Long.toString(OFFER_FORCE), "operationId", studentOp(1)));
        requireCode(response, MessageCode.SUCCESS, "ordinary student selection");
        require(status(S1, OFFER_FORCE) == 2 && countOf(OFFER_FORCE) == 1,
                "the enrolled student must hold status=2 with an incremented count");
        System.out.println("[E2E] genuine student selection: PASS");
    }

    private static void verifySearchPreviewForceAndAudit(JsonLineClient admin, String adminToken)
            throws Exception {
        Message search = admin.admin(AdminCourseActions.SEARCH_STUDENTS, adminToken,
                Map.of("query", S1, "pageNumber", 1, "pageSize", 10));
        requireCode(search, MessageCode.SUCCESS, "administrator student search");
        List<StudentSearchResultDTO> students = GSON.fromJson(GSON.toJsonTree(search.getData("students")),
                new TypeToken<List<StudentSearchResultDTO>>() { }.getType());
        require(students != null && students.size() == 1
                        && S1.equals(students.get(0).getUid())
                        && "ACTIVE".equals(students.get(0).getAcademicStatus()),
                "search must return the exact active student");

        AdminEnrollmentPreviewDTO preview = GSON.fromJson(
                GSON.toJsonTree(admin.admin(AdminCourseActions.PREVIEW_ADMIN_ENROLLMENT, adminToken,
                        Map.of("offeringId", Long.toString(OFFER_FORCE), "studentUid", S4))
                        .getData("preview")), AdminEnrollmentPreviewDTO.class);
        require(preview != null && Long.toString(OFFER_FORCE).equals(preview.getOfferingId()),
                "preview must identify the requested offering");
        requireRisk(preview.getRisks(), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);

        Message rejected = admin.admin(AdminCourseActions.ADD_STUDENT_TO_OFFERING, adminToken,
                Map.of("request", enrollmentRequest(op(1), OFFER_FORCE, S2, false, null)));
        requireCode(rejected, MessageCode.CONFLICT, "non-force add into a full offering");
        requireRisk(conflicts(rejected), "CAPACITY", ScheduleConflictSeverityDTO.OVERRIDABLE);
        require(enrollmentRows(S2, OFFER_FORCE) == 0 && countOf(OFFER_FORCE) == 1,
                "a rejected non-force add must not enroll or increment");

        String operation = op(2);
        OfferingStudentDTO forced = writeResult(
                admin.admin(AdminCourseActions.ADD_STUDENT_TO_OFFERING, adminToken,
                        Map.of("request", enrollmentRequest(operation, OFFER_FORCE, S2, true,
                                "  " + FORCE_REASON + "  "))),
                operation, OfferingStudentDTO.class);
        require(S2.equals(forced.getUid()) && "ENROLLED".equals(forced.getEnrollmentStatus()),
                "forced add must return the enrolled student entity");
        require(countOf(OFFER_FORCE) == FORCE_CAPACITY + 1,
                "force must allow enrolled_count above capacity");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?"
                        + " AND operation_id=? AND forced=1", ADMIN, operation) == 1
                        && FORCE_REASON.equals(text("SELECT override_reason"
                                + " FROM admin_course_operation_log WHERE admin_uid=? AND operation_id=?",
                                ADMIN, operation))
                        && text("SELECT conflict_snapshot_json FROM admin_course_operation_log"
                                + " WHERE admin_uid=? AND operation_id=?", ADMIN, operation)
                                .contains("CAPACITY"),
                "force audit must retain the trimmed reason and the actual risk snapshot");

        writeResult(admin.admin(AdminCourseActions.ADD_STUDENT_TO_OFFERING, adminToken,
                Map.of("request", enrollmentRequest(op(3), OFFER_FORCE, S3, true, "second forced seat"))),
                op(3), OfferingStudentDTO.class);
        require(countOf(OFFER_FORCE) == FORCE_CAPACITY + 2,
                "the fixture must sit at least two seats above capacity");

        OfferingStudentDTO replayed = writeResult(
                admin.admin(AdminCourseActions.ADD_STUDENT_TO_OFFERING, adminToken,
                        Map.of("request", enrollmentRequest(operation, OFFER_FORCE, S2, true,
                                "  " + FORCE_REASON + "  "))),
                operation, OfferingStudentDTO.class);
        require(GSON.toJson(forced).equals(GSON.toJson(replayed)),
                "an identical operationId and digest must replay the stored result exactly");
        require(countOf(OFFER_FORCE) == FORCE_CAPACITY + 2
                        && number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?"
                                + " AND operation_id=?", ADMIN, operation) == 1,
                "replay must not increment the over-capacity count or duplicate its audit row");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?",
                ADMIN) == 2, "only the two forced adds must create success audit rows");
        System.out.println("[E2E] search, preview, rejection, forced add, replay and audit: PASS");
    }

    private static void verifyGradeWorkflowRemovalBlocked(JsonLineClient admin, String adminToken)
            throws Exception {
        Message blocked = admin.admin(AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING, adminToken,
                Map.of("request", enrollmentRequest(op(10), OFFER_GRADE, S6, true, "requested removal")));
        requireCode(blocked, MessageCode.CONFLICT, "removal blocked by a pending grade submission");
        requireRisk(conflicts(blocked), "GRADE_WORKFLOW_LOCKED", ScheduleConflictSeverityDTO.BLOCKING);
        require(status(S6, OFFER_GRADE) == 2 && countOf(OFFER_GRADE) == 1,
                "a blocking grade workflow must preserve status and count even when forced");
        System.out.println("[E2E] grade workflow blocked removal: PASS");
    }

    private static void verifyEligibleRemovalAndWaitlistAdvancement(JsonLineClient admin,
            String adminToken) throws Exception {
        String operation = op(20);
        OfferingStudentDTO removed = writeResult(
                admin.admin(AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING, adminToken,
                        Map.of("request", enrollmentRequest(operation, OFFER_REMOVE, S5, false, null))),
                operation, OfferingStudentDTO.class);
        require(S5.equals(removed.getUid()) && "DROPPED".equals(removed.getEnrollmentStatus()),
                "an eligible removal must return the dropped student entity");
        require(status(S5, OFFER_REMOVE) == 3 && countOf(OFFER_REMOVE) == 1
                        && enrollmentRows(S5, OFFER_REMOVE) == 1,
                "removal must set status=3, retain history and hand the freed seat to the waitlist");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?"
                + " AND operation_id=?", ADMIN, operation) == 1,
                "a committed removal must record exactly one audit row");
        require("ENROLLED".equals(text("SELECT status FROM course_waitlist WHERE uid=?"
                                + " AND offering_id=?", W1, OFFER_REMOVE))
                        && status(W1, OFFER_REMOVE) == 2,
                "waitlist advancement must run after the removal commit and enroll the FIFO head");
        require("WAITING".equals(text("SELECT status FROM course_waitlist WHERE uid=?"
                        + " AND offering_id=?", W2, OFFER_REMOVE)),
                "the second waiter must stay queued until another seat frees");

        OfferingStudentDTO replayed = writeResult(
                admin.admin(AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING, adminToken,
                        Map.of("request", enrollmentRequest(operation, OFFER_REMOVE, S5, false, null))),
                operation, OfferingStudentDTO.class);
        require(GSON.toJson(removed).equals(GSON.toJson(replayed)),
                "removal replay must return the stored result exactly");
        require(countOf(OFFER_REMOVE) == 1
                        && "WAITING".equals(text("SELECT status FROM course_waitlist WHERE uid=?"
                                + " AND offering_id=?", W2, OFFER_REMOVE))
                        && number("SELECT COUNT(*) FROM enrollment WHERE offering_id=?"
                                + " AND uid=?", OFFER_REMOVE, W1) == 1
                        && number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid=?"
                                + " AND operation_id=?", ADMIN, operation) == 1,
                "replaying a removal must not re-trigger advancement or duplicate audit");
        System.out.println("[E2E] eligible removal, after-commit waitlist and replay: PASS");
    }

    /**
     * Task 5 regression. The administrator forced the class to capacity + 2, so an ordinary
     * student drop leaves the count at capacity + 1, still above capacity. The drop must commit
     * status=3 and decrement exactly once. CourseSelectionDAO.changeEnrolledCount currently
     * applies its capacity check to negative deltas too, so the update matches no row and the
     * whole transaction rolls back. This assertion is the expected RED before the predicate fix.
     */
    private static void verifyOrdinaryDropOfOverCapacityOffering(JsonLineClient student,
            String studentToken) throws Exception {
        require(countOf(OFFER_FORCE) == FORCE_CAPACITY + 2,
                "the regression fixture must still sit two seats above capacity");
        Message dropped = student.request("course", CourseActions.DROP_OFFERING, studentToken,
                Map.of("academicYear", YEAR, "semester", SEMESTER,
                        "offeringId", Long.toString(OFFER_FORCE), "operationId", studentOp(2)));
        require(status(S1, OFFER_FORCE) == 3,
                "an ordinary student drop must commit status=3 while the forced count stays above"
                        + " capacity; server response was " + dropped.getCode() + " ("
                        + dropped.getMessage() + ")");
        require(countOf(OFFER_FORCE) == FORCE_CAPACITY + 1,
                "a drop that remains above capacity must decrement enrolled_count exactly once");
        System.out.println("[E2E] ordinary drop of a forced over-capacity offering: PASS");
    }

    private static Map<String, Object> enrollmentRequest(String operationId, long offeringId,
            String studentUid, boolean force, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("operationId", operationId);
        request.put("offeringId", Long.toString(offeringId));
        request.put("studentUid", studentUid);
        request.put("force", force);
        if (reason != null) request.put("overrideReason", reason);
        return request;
    }

    private static <T> T writeResult(Message response, String operationId, Class<T> entityType) {
        requireCode(response, MessageCode.SUCCESS, "administrator enrollment write");
        AdminOperationResultDTO<T> result = GSON.fromJson(GSON.toJsonTree(response.getData("result")),
                TypeToken.getParameterized(AdminOperationResultDTO.class, entityType).getType());
        require(result != null && operationId.equals(result.getOperationId())
                        && "OK".equals(result.getOutcomeCode()) && result.getEntity() != null,
                "write must return its operation identity and authoritative entity");
        return result.getEntity();
    }

    private static List<ScheduleConflictDTO> conflicts(Message response) {
        Object value = response.getData("conflicts");
        require(value != null, "a rejected enrollment must carry its risks");
        List<ScheduleConflictDTO> parsed = GSON.fromJson(GSON.toJsonTree(value),
                new TypeToken<List<ScheduleConflictDTO>>() { }.getType());
        require(parsed != null, "rejected enrollment risks must parse");
        return parsed;
    }

    private static void requireRisk(List<ScheduleConflictDTO> risks, String type,
            ScheduleConflictSeverityDTO severity) {
        require(risks.stream().anyMatch(r -> type.equals(r.getType()) && r.getSeverity() == severity),
                "expected " + type + " / " + severity + ", got " + GSON.toJson(risks));
    }

    private static long enrollmentRows(String uid, long offering) throws SQLException {
        return number("SELECT COUNT(*) FROM enrollment WHERE uid=? AND offering_id=?", uid, offering);
    }

    private static long countOf(long offering) throws SQLException {
        return number("SELECT enrolled_count FROM course_offering WHERE offering_id=?", offering);
    }

    private static long status(String uid, long offering) throws SQLException {
        return number("SELECT status FROM enrollment WHERE uid=? AND offering_id=?", uid, offering);
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO major(major_id,major_code,major_name,college)"
                + " VALUES(960001,'ENR-E2E','Enrollment E2E Major','Test College')");
        user(ADMIN, "Enrollment E2E Administrator", 0);
        user(TEACHER, "Enrollment E2E Teacher", 1);
        for (String uid : List.of(S1, S2, S3, S4, S5, S6, W1, W2)) {
            user(uid, "Enrollment E2E " + uid, 2);
            execute("INSERT INTO student_academic_profile(uid,major_id,cohort_year,status)"
                    + " VALUES(?,?,2029,'ACTIVE')", uid, MAJOR);
        }
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,allow_cross_major,status) VALUES"
                + "(960010,'ENR-E2E-FORCE','Enrollment E2E Force',2,32,3,1,'ACTIVE'),"
                + "(960011,'ENR-E2E-REMOVE','Enrollment E2E Remove',2,32,3,1,'ACTIVE'),"
                + "(960012,'ENR-E2E-GRADE','Enrollment E2E Grade',2,32,3,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,enrolled_count,status) VALUES"
                + "(960101,'ENR-E2E-FORCE-A',960010,2029,1,1,0,2),"
                + "(960102,'ENR-E2E-REMOVE-A',960011,2029,1,1,1,2),"
                + "(960103,'ENR-E2E-GRADE-A',960012,2029,1,5,1,2)");
        execute("INSERT INTO teaching_calendar(id,name,academic_year,semester,week1_start_date,"
                + "timezone,version,status) VALUES(960201,'Enrollment E2E calendar',2029,1,"
                + "'2029-09-03','Asia/Shanghai',1,'PUBLISHED')");
        execute("INSERT INTO schedule_plan(id,name,calendar_id,revision,status)"
                + " VALUES(960202,'Enrollment E2E plan',960201,1,'PUBLISHED')");
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=960202 WHERE id=960201");
        execute("INSERT INTO course_selection_window(academic_year,semester,schedule_plan_id,"
                + "plan_open_at,plan_close_at,selection_open_at,selection_close_at,drop_deadline)"
                + " VALUES(2029,1,960202,'2020-01-01','2020-01-02','2020-01-03','2045-01-01',"
                + "'2045-02-01')");
        execute("INSERT INTO course_plan_item(uid,offering_id,status) VALUES(?,960101,'PLANNED')", S1);
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(960401,960102,960011,2029,1,?,2,"
                + "'2029-09-01 00:00:00.000001')", S5);
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(960402,960103,960012,2029,1,?,2,"
                + "'2029-09-01 00:00:00.000002')", S6);
        execute("INSERT INTO course_waitlist(waitlist_id,uid,offering_id,status,queue_time) VALUES"
                + "(960501,?,960102,'WAITING','2029-09-01 00:00:01.000001'),"
                + "(960502,?,960102,'WAITING','2029-09-01 00:00:02.000002')", W1, W2);
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,status,"
                + "reviewed_at) VALUES(960301,960103,1,?,'PENDING',NULL)", TEACHER);
        execute("INSERT INTO grade_submission_item(submission_id,enrollment_id,score)"
                + " VALUES(960301,960402,75)");
    }

    private static void user(String uid, String name, int role) throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major)"
                + " VALUES(?,?,?,?,?,'Engineering','Computer Science')",
                uid, name, HASH, SALT, role);
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM course_event_outbox WHERE offering_id BETWEEN 960101 AND 960103");
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid=?", ADMIN);
        execute("DELETE FROM course_operation_log WHERE uid LIKE 'enr-%'");
        execute("DELETE FROM grade_submission_item WHERE submission_id=?", SUBMISSION);
        execute("DELETE FROM grade_submission WHERE submission_id=?", SUBMISSION);
        execute("DELETE FROM course_waitlist WHERE offering_id BETWEEN 960101 AND 960103");
        execute("DELETE FROM course_plan_item WHERE offering_id BETWEEN 960101 AND 960103");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 960101 AND 960103");
        execute("DELETE FROM course_selection_window WHERE academic_year=? AND semester=?", YEAR, SEMESTER);
        execute("UPDATE teaching_calendar SET current_schedule_plan_id=NULL WHERE id=?", CALENDAR);
        execute("DELETE FROM schedule_plan WHERE id=?", PLAN);
        execute("DELETE FROM teaching_calendar WHERE id=?", CALENDAR);
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 960101 AND 960103");
        execute("DELETE FROM course WHERE course_id BETWEEN 960010 AND 960012");
        execute("DELETE FROM student_academic_profile WHERE uid LIKE 'enr-%'");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'enr-%'");
        execute("DELETE FROM major WHERE major_id=?", MAJOR);
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream input = DBUtil.class.getClassLoader().getResourceAsStream("resources/db.properties")) {
            require(input != null, "database configuration is unavailable");
            properties.load(input);
        }
        String url = properties.getProperty("db.url", "");
        require(url.startsWith("jdbc:mysql:"), "a MySQL test URL is required");
        require(("/" + TEST_DATABASE).equals(URI.create(url.substring(5)).getPath()),
                "refusing enrollment E2E outside the dedicated test schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "connected database must be the dedicated test schema");
    }

    private static Object scalar(String sql, Object... parameters) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "database assertion must return a row");
                return rows.getObject(1);
            }
        }
    }

    private static long number(String sql, Object... parameters) throws SQLException {
        return ((Number) scalar(sql, parameters)).longValue();
    }

    private static String text(String sql, Object... parameters) throws SQLException {
        Object value = scalar(sql, parameters);
        return value == null ? null : value.toString();
    }

    private static void execute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            statement.setObject(index + 1, parameters[index]);
        }
    }

    private static String op(int value) {
        return String.format("96000000-0000-0000-0000-%012d", value);
    }

    private static String studentOp(int value) {
        return String.format("96010000-0000-0000-0000-%012d", value);
    }

    private static void requireCode(Message response, MessageCode expected, String action) {
        require(response.getCode() == expected, action + ": expected " + expected + ", got "
                + response.getCode() + " (" + response.getMessage() + ")");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private JsonLineClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String login(String uid, String role) throws IOException {
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", PASSWORD, "role", role));
            requireCode(response, MessageCode.SUCCESS, "test login");
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must supply a token");
            require(role.equals(response.getData("role")), "login must return the requested role");
            return (String) token;
        }

        private Message admin(String action, String token, Map<String, Object> data) throws IOException {
            return request("courseAdmin", action, token, data);
        }

        private Message request(String module, String action, String token, Map<String, Object> data)
                throws IOException {
            Message request = new Message(MessageType.REQUEST, module, action);
            request.setToken(token);
            // Spoofed sender: authorization must still come from the authenticated token.
            request.setSender(ADMIN);
            data.forEach(request::putData);
            writer.write(GSON.toJson(request));
            writer.newLine();
            writer.flush();
            String line;
            while ((line = reader.readLine()) != null) {
                Message response = GSON.fromJson(line, Message.class);
                if (response.getType() == MessageType.PUSH) continue;
                require(response.getType() == MessageType.RESPONSE
                                && request.getUID().equals(response.getUID()),
                        "response must correlate to its request");
                return response;
            }
            throw new IOException("server closed before responding to " + action);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
