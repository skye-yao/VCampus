package integration;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 7 administrator course-catalog vertical-slice verification.
 *
 * <p>Aborts unless the JDBC database is exactly {@code virtual_campus_course_test}. It resets that
 * schema and applies the authoritative {@code tbl_user} definition, V001, V002, V003, V004 and only
 * then {@code seed-course-test.sql}, proving a fresh post-V004 install order. It then starts the real
 * {@link Server} on an OS-assigned loopback port and drives the real JSON-line protocol through the
 * {@code user} and {@code courseAdmin} modules. Every persisted effect is asserted with independent
 * JDBC connections.
 *
 * <p>Run from the repository root with the server, common and server-test classes and the server
 * libraries on the classpath.
 */
public final class AdminCourseCatalogSocketEndToEndTest {

    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String DRIVER = "com.mysql.cj.jdbc.Driver";
    private static final String LOGIN_PASSWORD = "course-test-only";
    private static final String ADMIN_UID = "admin-alpha";
    private static final String ADMIN_ROLE = "管理员";
    private static final String STUDENT_UID = "student-alpha";
    private static final String STUDENT_ROLE = "学生";
    private static final String TEACHER_UID = "teacher-alpha";
    private static final String ASSISTANT_UID = "teacher-beta";
    private static final String COURSE_CODE = "ADM701";
    private static final String OFFERING_CODE = "ADM701-2026-2-A";
    private static final String FORBIDDEN_CODE = "ADM709";
    private static final int ACADEMIC_YEAR = 2026;
    private static final int SEMESTER = 2;
    private static final Gson GSON = new Gson();
    private static final Type COURSE_RESULT =
            new TypeToken<AdminOperationResultDTO<AdminCourseDTO>>() { }.getType();
    private static final Type OFFERING_RESULT =
            new TypeToken<AdminOperationResultDTO<AdminOfferingDTO>>() { }.getType();
    private static final Pattern TBL_USER = Pattern.compile(
            "(?is)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`tbl_user`.*?ENGINE\\s*=\\s*InnoDB.*?;");

    private AdminCourseCatalogSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Properties properties = loadProperties(
                root.resolve("VCampusServer/src/resources/db.properties"));
        String url = requiredProperty(properties, "db.url");
        requireTestDatabase(url);
        String jdbc = withTestAuthentication(url);
        String username = requiredProperty(properties, "db.username");
        String password = requiredProperty(properties, "db.password");
        Class.forName(DRIVER);

        System.out.println("[E2E] reset + fresh install order"
                + " (tbl_user -> V001 -> V002 -> V003 -> V004 -> seed)");
        resetAndSeed(jdbc, username, password, root);
        System.out.println("[E2E] fresh post-V004 seed applied");

        Harness harness = new Harness();
        try {
            harness.start();
            System.out.println("[E2E] server listening on 127.0.0.1:" + harness.port());
            runScenarios(harness, jdbc, username, password);
        } finally {
            harness.stop();
        }
        System.out.println("Admin course catalog socket end-to-end test passed.");
    }

    private static void runScenarios(Harness harness, String jdbc, String user, String pass)
            throws Exception {
        try (JsonLineClient admin = harness.client();
             JsonLineClient student = harness.client()) {
            String adminToken = admin.login(ADMIN_UID, ADMIN_ROLE);
            listSeededCatalog(admin, adminToken);
            String courseId = createCourseAndReplay(admin, adminToken, jdbc, user, pass);
            updateCourse(admin, adminToken, jdbc, user, pass, courseId);
            archiveAndRestore(admin, adminToken, jdbc, user, pass, courseId);
            String offeringId = createOffering(admin, adminToken, jdbc, user, pass, courseId);
            updateOffering(admin, adminToken, jdbc, user, pass, courseId, offeringId);
            cancelOffering(admin, adminToken, jdbc, user, pass, offeringId);
            listReflectsCancelledOffering(admin, adminToken, courseId);
            studentIsForbidden(student, jdbc, user, pass);
        }
    }

    // ------------------------------------------------------------------
    // Scenario steps
    // ------------------------------------------------------------------

    private static void listSeededCatalog(JsonLineClient admin, String token) {
        Message listed = admin.admin(AdminCourseActions.LIST_COURSES, token, Map.of());
        requireSuccess(listed, "listCourses");
        List<Map<String, Object>> courses = asList(listed.getData("courses"));
        require(courses.stream().anyMatch(course -> "1001".equals(course.get("courseId"))),
                "listCourses must expose the seeded course 1001");
        require(courses.stream().noneMatch(course -> COURSE_CODE.equals(course.get("courseCode"))),
                "the uniquely coded course must not exist before create");
        System.out.println("[E2E] admin listCourses -> " + courses.size() + " courses");
    }

    private static String createCourseAndReplay(JsonLineClient admin, String token, String jdbc,
                                                String user, String pass) throws SQLException {
        String operationId = UUID.randomUUID().toString();
        Map<String, Object> request = createCourseRequest(operationId);

        Message created = admin.admin(AdminCourseActions.CREATE_COURSE, token,
                Map.of("request", request));
        AdminOperationResultDTO<AdminCourseDTO> createdResult = courseResult(created, "createCourse");
        require("OK".equals(createdResult.getOutcomeCode()),
                "create outcome must be OK but was " + createdResult.getOutcomeCode());
        require(operationId.equals(createdResult.getOperationId()),
                "create must echo the operationId");
        AdminCourseDTO course = createdResult.getEntity();
        require(course != null, "create must return the created course");
        String courseId = course.getCourseId();
        require(courseId != null && courseId.matches("[0-9]+"),
                "create must return a decimal-string course id but was " + courseId);
        require(COURSE_CODE.equals(course.getCourseCode()) && "端到端管理员课程".equals(course.getCourseName()),
                "create must return the persisted course fields");
        require("ACTIVE".equals(course.getStatus()) && course.getVersion() == 1,
                "new course must be ACTIVE version 1");
        require("选修".equals(course.getCourseType()) && course.getCredit() == 2.5
                        && course.getCreditHours() == 40 && course.isAllowCrossMajor()
                        && !course.isFinalExam(),
                "create must round-trip every course attribute");
        System.out.println("[E2E] admin createCourse -> " + COURSE_CODE + " id=" + courseId);

        Message replayed = admin.admin(AdminCourseActions.CREATE_COURSE, token,
                Map.of("request", createCourseRequest(operationId)));
        AdminOperationResultDTO<AdminCourseDTO> replayedResult =
                courseResult(replayed, "replayed createCourse");
        require("OK".equals(replayedResult.getOutcomeCode()),
                "replay must succeed with the same outcome");
        require(operationId.equals(replayedResult.getOperationId()),
                "replay must echo the same operationId");
        require(courseId.equals(replayedResult.getEntity().getCourseId())
                        && replayedResult.getEntity().getVersion() == 1,
                "replay must return the identical course without a second version");
        System.out.println("[E2E] admin replayed createCourse with the same operationId");

        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT COUNT(*) FROM course WHERE course_code = ?",
                    COURSE_CODE) == 1, "replay must not insert a second course");
            require(queryInt(connection, "SELECT version FROM course WHERE course_code = ?",
                    COURSE_CODE) == 1, "replay must not apply a second version");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id = ? AND action = 'createCourse'"
                            + " AND target_type = 'COURSE' AND target_id = ?"
                            + " AND result_code = 'OK' AND completed_at IS NOT NULL",
                    ADMIN_UID, operationId, courseId) == 1,
                    "the create identity must have exactly one completed operation-log record");
        });
        return courseId;
    }

    private static void updateCourse(JsonLineClient admin, String token, String jdbc, String user,
                                     String pass, String courseId) throws SQLException {
        String operationId = UUID.randomUUID().toString();
        Map<String, Object> request = courseRequest(operationId, courseId, 1, COURSE_CODE,
                "端到端管理员课程二", "限选", 3.0, 48, "更新后的说明", null, false, true);
        Message updated = admin.admin(AdminCourseActions.UPDATE_COURSE, token,
                Map.of("request", request));
        AdminOperationResultDTO<AdminCourseDTO> result = courseResult(updated, "updateCourse");
        require("OK".equals(result.getOutcomeCode()), "update outcome must be OK");
        AdminCourseDTO course = result.getEntity();
        require(courseId.equals(course.getCourseId()),
                "update must return the same decimal-string course id");
        require(course.getVersion() == 2, "update must increment the version to 2");
        require("端到端管理员课程二".equals(course.getCourseName())
                        && "限选".equals(course.getCourseType()) && course.getCredit() == 3.0
                        && course.getCreditHours() == 48 && !course.isAllowCrossMajor()
                        && course.isFinalExam(),
                "update must return every changed field");
        System.out.println("[E2E] admin updateCourse -> version " + course.getVersion());

        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT version FROM course WHERE course_id = ?",
                    Long.parseLong(courseId)) == 2, "database version must be 2");
            require("端到端管理员课程二".equals(queryString(connection,
                            "SELECT course_name FROM course WHERE course_id = ?",
                            Long.parseLong(courseId))),
                    "database must persist the updated name");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id = ? AND target_id = ?",
                    ADMIN_UID, operationId, courseId) == 1,
                    "update must write one operation-log record");
        });
    }

    private static void archiveAndRestore(JsonLineClient admin, String token, String jdbc,
                                          String user, String pass, String courseId)
            throws SQLException {
        String archiveId = UUID.randomUUID().toString();
        Message archived = admin.admin(AdminCourseActions.ARCHIVE_COURSE, token,
                Map.of("courseId", courseId, "expectedVersion", 2, "operationId", archiveId));
        AdminOperationResultDTO<AdminCourseDTO> archivedResult =
                courseResult(archived, "archiveCourse");
        require("OK".equals(archivedResult.getOutcomeCode()), "archive outcome must be OK");
        require("ARCHIVED".equals(archivedResult.getEntity().getStatus())
                        && archivedResult.getEntity().getVersion() == 3,
                "archive must return ARCHIVED version 3");
        System.out.println("[E2E] admin archiveCourse -> ARCHIVED v"
                + archivedResult.getEntity().getVersion());

        String restoreId = UUID.randomUUID().toString();
        Message restored = admin.admin(AdminCourseActions.RESTORE_COURSE, token,
                Map.of("courseId", courseId, "expectedVersion", 3, "operationId", restoreId));
        AdminOperationResultDTO<AdminCourseDTO> restoredResult =
                courseResult(restored, "restoreCourse");
        require("OK".equals(restoredResult.getOutcomeCode()), "restore outcome must be OK");
        require("ACTIVE".equals(restoredResult.getEntity().getStatus())
                        && restoredResult.getEntity().getVersion() == 4,
                "restore must return ACTIVE version 4");
        System.out.println("[E2E] admin restoreCourse -> ACTIVE v"
                + restoredResult.getEntity().getVersion());

        assertDatabase(jdbc, user, pass, connection -> {
            long id = Long.parseLong(courseId);
            require("ACTIVE".equals(queryString(connection,
                            "SELECT status FROM course WHERE course_id = ?", id)),
                    "course must be ACTIVE after restore");
            require(queryInt(connection, "SELECT version FROM course WHERE course_id = ?",
                    id) == 4, "database version must be 4 after restore");
            require(queryString(connection, "SELECT archived_by FROM course WHERE course_id = ?",
                    id) == null, "restore must clear archived_by");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id IN (?, ?) AND target_id = ?",
                    ADMIN_UID, archiveId, restoreId, courseId) == 2,
                    "archive and restore must each write one operation-log record");
        });
    }

    private static String createOffering(JsonLineClient admin, String token, String jdbc,
                                         String user, String pass, String courseId)
            throws SQLException {
        String operationId = UUID.randomUUID().toString();
        Map<String, Object> request = offeringRequest(operationId, null, 0, courseId, OFFERING_CODE,
                ACADEMIC_YEAR, SEMESTER, 60, TEACHER_UID, "", 1);
        Message created = admin.admin(AdminCourseActions.CREATE_OFFERING, token,
                Map.of("request", request));
        AdminOperationResultDTO<AdminOfferingDTO> result =
                offeringResult(created, "createOffering");
        require("OK".equals(result.getOutcomeCode()), "createOffering outcome must be OK");
        AdminOfferingDTO offering = result.getEntity();
        require(offering != null, "createOffering must return the created offering");
        String offeringId = offering.getOfferingId();
        require(offeringId != null && offeringId.matches("[0-9]+"),
                "createOffering must return a decimal-string id but was " + offeringId);
        require(OFFERING_CODE.equals(offering.getOfferingCode())
                        && courseId.equals(offering.getCourseId())
                        && offering.getAcademicYear() == ACADEMIC_YEAR
                        && offering.getSemester() == SEMESTER && offering.getCapacity() == 60
                        && offering.getEnrolledCount() == 0,
                "createOffering must return the persisted offering fields");
        require("NOT_OPEN".equals(offering.getStatus()) && offering.getVersion() == 1,
                "new offering must be NOT_OPEN version 1");
        require(TEACHER_UID.equals(offering.getTeacherUid())
                        && "Course Test Teacher A".equals(offering.getTeacherName()),
                "createOffering must resolve the seeded teacher fixture");
        System.out.println("[E2E] admin createOffering -> " + OFFERING_CODE + " id=" + offeringId);

        assertDatabase(jdbc, user, pass, connection -> {
            long id = Long.parseLong(offeringId);
            require(queryInt(connection, "SELECT status FROM course_offering"
                    + " WHERE offering_id = ?", id) == 1, "new offering must persist status 1");
            require(queryInt(connection, "SELECT version FROM course_offering"
                    + " WHERE offering_id = ?", id) == 1, "new offering version must be 1");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering_teacher"
                            + " WHERE offering_id = ? AND uid = ? AND role = 0",
                    id, TEACHER_UID) == 1, "the teacher linkage must persist");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id = ? AND target_type = 'OFFERING'"
                            + " AND target_id = ?", ADMIN_UID, operationId, offeringId) == 1,
                    "createOffering must write one operation-log record");
        });
        return offeringId;
    }

    private static void updateOffering(JsonLineClient admin, String token, String jdbc, String user,
                                       String pass, String courseId, String offeringId)
            throws SQLException {
        String operationId = UUID.randomUUID().toString();
        Map<String, Object> request = offeringRequest(operationId, offeringId, 1, courseId,
                OFFERING_CODE, ACADEMIC_YEAR, SEMESTER, 80, TEACHER_UID, ASSISTANT_UID, 2);
        Message updated = admin.admin(AdminCourseActions.UPDATE_OFFERING, token,
                Map.of("request", request));
        AdminOperationResultDTO<AdminOfferingDTO> result =
                offeringResult(updated, "updateOffering");
        require("OK".equals(result.getOutcomeCode()), "updateOffering outcome must be OK");
        AdminOfferingDTO offering = result.getEntity();
        require(offeringId.equals(offering.getOfferingId()),
                "updateOffering must return the same id");
        require(offering.getVersion() == 2, "updateOffering must increment the version to 2");
        require(offering.getCapacity() == 80 && "OPEN".equals(offering.getStatus()),
                "updateOffering must return the changed capacity and status");
        require(ASSISTANT_UID.equals(offering.getAssistantUid())
                        && "Course Test Teacher B".equals(offering.getAssistantName()),
                "updateOffering must link the assistant fixture");
        System.out.println("[E2E] admin updateOffering -> " + offering.getStatus()
                + " v" + offering.getVersion());

        assertDatabase(jdbc, user, pass, connection -> {
            long id = Long.parseLong(offeringId);
            require(queryInt(connection, "SELECT version FROM course_offering"
                    + " WHERE offering_id = ?", id) == 2, "offering database version must be 2");
            require(queryInt(connection, "SELECT capacity FROM course_offering"
                    + " WHERE offering_id = ?", id) == 80, "offering capacity must persist");
            require(queryInt(connection, "SELECT status FROM course_offering"
                    + " WHERE offering_id = ?", id) == 2, "offering status must persist");
            require(queryInt(connection, "SELECT COUNT(*) FROM course_offering_teacher"
                            + " WHERE offering_id = ? AND uid = ? AND role = 1",
                    id, ASSISTANT_UID) == 1, "the assistant linkage must persist");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id = ? AND target_id = ?",
                    ADMIN_UID, operationId, offeringId) == 1,
                    "updateOffering must write one operation-log record");
        });
    }

    private static void cancelOffering(JsonLineClient admin, String token, String jdbc, String user,
                                       String pass, String offeringId) throws SQLException {
        String operationId = UUID.randomUUID().toString();
        Message cancelled = admin.admin(AdminCourseActions.CANCEL_OFFERING, token,
                Map.of("offeringId", offeringId, "expectedVersion", 2, "operationId", operationId));
        AdminOperationResultDTO<AdminOfferingDTO> result =
                offeringResult(cancelled, "cancelOffering");
        require("OK".equals(result.getOutcomeCode()), "cancelOffering outcome must be OK");
        require("CANCELLED".equals(result.getEntity().getStatus())
                        && result.getEntity().getVersion() == 3,
                "cancelOffering must return CANCELLED version 3");
        System.out.println("[E2E] admin cancelOffering -> CANCELLED v"
                + result.getEntity().getVersion());

        assertDatabase(jdbc, user, pass, connection -> {
            long id = Long.parseLong(offeringId);
            require(queryInt(connection, "SELECT status FROM course_offering"
                    + " WHERE offering_id = ?", id) == 4, "cancelled status must be 4");
            require(queryInt(connection, "SELECT version FROM course_offering"
                    + " WHERE offering_id = ?", id) == 3, "cancelled version must be 3");
            require(ADMIN_UID.equals(queryString(connection, "SELECT cancelled_by"
                            + " FROM course_offering WHERE offering_id = ?", id))
                            && queryScalar(connection, "SELECT cancelled_at FROM course_offering"
                            + " WHERE offering_id = ?", id) != null,
                    "cancel must record the audit columns");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log"
                            + " WHERE admin_uid = ? AND operation_id = ? AND target_id = ?",
                    ADMIN_UID, operationId, offeringId) == 1,
                    "cancelOffering must write one operation-log record");
        });
    }

    private static void listReflectsCancelledOffering(JsonLineClient admin, String token,
                                                      String courseId) {
        Message listed = admin.admin(AdminCourseActions.LIST_COURSES, token, Map.of());
        requireSuccess(listed, "final listCourses");
        Map<String, Object> course = asList(listed.getData("courses")).stream()
                .filter(item -> courseId.equals(item.get("courseId")))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the created course must appear in the administrator catalog"));
        require("ACTIVE".equals(course.get("status")),
                "the created course must stay ACTIVE after offering cancel");
        require(number(course.get("offeringCount")) == 0,
                "the cancelled offering must not count as an active offering");
        System.out.println("[E2E] admin listCourses -> created course ACTIVE, 0 active offerings");
    }

    private static void studentIsForbidden(JsonLineClient student, String jdbc, String user,
                                           String pass) throws SQLException {
        String token = student.login(STUDENT_UID, STUDENT_ROLE);
        int coursesBefore;
        int logsBefore;
        try (Connection connection = connect(jdbc, user, pass)) {
            coursesBefore = queryInt(connection, "SELECT COUNT(*) FROM course");
            logsBefore = queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log");
        }

        Message listed = student.admin(AdminCourseActions.LIST_COURSES, token, Map.of());
        require(listed.getCode() == MessageCode.FORBIDDEN,
                "a student token must be FORBIDDEN on courseAdmin but was " + listed.getCode());

        Message write = student.admin(AdminCourseActions.CREATE_COURSE, token,
                Map.of("request", courseRequest(UUID.randomUUID().toString(), null, 0, FORBIDDEN_CODE,
                        "越权课程", "选修", 1.0, 16, null, null, false, false)));
        require(write.getCode() == MessageCode.FORBIDDEN,
                "a student createCourse must be FORBIDDEN but was " + write.getCode());

        assertDatabase(jdbc, user, pass, connection -> {
            require(queryInt(connection, "SELECT COUNT(*) FROM course") == coursesBefore,
                    "a forbidden request must not change the course count");
            require(queryInt(connection, "SELECT COUNT(*) FROM admin_course_operation_log")
                            == logsBefore,
                    "a forbidden request must not write an operation-log record");
            require(queryInt(connection, "SELECT COUNT(*) FROM course WHERE course_code = ?",
                    FORBIDDEN_CODE) == 0, "a forbidden request must not create a course");
        });
        System.out.println("[E2E] student token -> FORBIDDEN with no mutation");
    }

    // ------------------------------------------------------------------
    // Server harness
    // ------------------------------------------------------------------

    private static final class Harness {
        private final OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        private final Server server;
        private final Thread thread;

        Harness() {
            this.server = new Server(0, registry, new MessageDispatcher(), null, null);
            this.thread = new Thread(server::start, "admin-e2e-server");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (!server.isRunning() && System.currentTimeMillis() < deadline) {
                sleep(20);
            }
            require(server.isRunning(), "server must start listening");
        }

        void stop() {
            server.stop();
        }

        int port() {
            return server.getPort();
        }

        JsonLineClient client() throws IOException {
            return new JsonLineClient("127.0.0.1", port());
        }
    }

    // ------------------------------------------------------------------
    // Real JSON-line client
    // ------------------------------------------------------------------

    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedWriter writer;
        private final BufferedReader reader;
        private final Map<Long, CompletableFuture<Message>> pending = new ConcurrentHashMap<>();

        JsonLineClient(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            this.reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Thread readerThread = new Thread(this::readLoop, "admin-e2e-client-reader");
            readerThread.setDaemon(true);
            readerThread.start();
        }

        private void readLoop() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message message = GSON.fromJson(line, Message.class);
                    if (message == null || message.getType() == MessageType.PUSH) {
                        continue;
                    }
                    CompletableFuture<Message> future = pending.remove(message.getUID());
                    if (future != null) {
                        future.complete(message);
                    }
                }
            } catch (IOException closed) {
                // socket closed by the test
            }
        }

        String login(String uid, String role) {
            Message response = request("user", "login", null, Map.of(
                    "cardNo", uid, "password", LOGIN_PASSWORD, "role", role));
            requireSuccess(response, "login " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must return a token");
            require(role.equals(response.getData("role")),
                    "login must report the requested role " + role);
            return (String) token;
        }

        Message admin(String action, String token, Map<String, Object> data) {
            return request("courseAdmin", action, token, data);
        }

        Message request(String module, String action, String token, Map<String, Object> data) {
            Message message = new Message(MessageType.REQUEST, module, action);
            if (token != null) {
                message.setToken(token);
            }
            if (data != null) {
                data.forEach(message::putData);
            }
            CompletableFuture<Message> future = new CompletableFuture<>();
            pending.put(message.getUID(), future);
            try {
                synchronized (writer) {
                    writer.write(GSON.toJson(message));
                    writer.write("\n");
                    writer.flush();
                }
            } catch (IOException failure) {
                pending.remove(message.getUID());
                throw new IllegalStateException("send failed", failure);
            }
            try {
                return future.get(20, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("no response for " + module + "/" + action, failure);
            }
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }

    // ------------------------------------------------------------------
    // Wire payloads and response helpers
    // ------------------------------------------------------------------

    private static Map<String, Object> createCourseRequest(String operationId) {
        return courseRequest(operationId, null, 0, COURSE_CODE, "端到端管理员课程", "选修", 2.5, 40,
                "端到端创建", "无", true, false);
    }

    private static Map<String, Object> courseRequest(String operationId, String courseId,
                                                     int expectedVersion, String courseCode,
                                                     String courseName, String courseType,
                                                     double credit, int creditHours,
                                                     String description, String prerequisites,
                                                     boolean allowCrossMajor, boolean finalExam) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operationId", operationId);
        if (courseId != null) {
            request.put("courseId", courseId);
        }
        request.put("expectedVersion", expectedVersion);
        request.put("courseCode", courseCode);
        request.put("courseName", courseName);
        request.put("courseType", courseType);
        request.put("credit", credit);
        request.put("creditHours", creditHours);
        request.put("description", description);
        request.put("prerequisites", prerequisites);
        request.put("allowCrossMajor", allowCrossMajor);
        request.put("finalExam", finalExam);
        return request;
    }

    private static Map<String, Object> offeringRequest(String operationId, String offeringId,
                                                       int expectedVersion, String courseId,
                                                       String offeringCode, int academicYear,
                                                       int semester, int capacity, String teacherUid,
                                                       String assistantUid, int status) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("operationId", operationId);
        if (offeringId != null) {
            request.put("offeringId", offeringId);
        }
        request.put("expectedVersion", expectedVersion);
        request.put("courseId", courseId);
        request.put("offeringCode", offeringCode);
        request.put("academicYear", academicYear);
        request.put("semester", semester);
        request.put("capacity", capacity);
        request.put("teacherUid", teacherUid);
        request.put("assistantUid", assistantUid);
        request.put("status", status);
        return request;
    }

    private static AdminOperationResultDTO<AdminCourseDTO> courseResult(Message response,
                                                                        String label) {
        return result(response, COURSE_RESULT, label);
    }

    private static AdminOperationResultDTO<AdminOfferingDTO> offeringResult(Message response,
                                                                            String label) {
        return result(response, OFFERING_RESULT, label);
    }

    private static <T> AdminOperationResultDTO<T> result(Message response, Type type, String label) {
        requireSuccess(response, label);
        Object raw = response.getData("result");
        require(raw != null, label + " must carry a result");
        AdminOperationResultDTO<T> parsed = GSON.fromJson(GSON.toJson(raw), type);
        require(parsed != null, label + " result must deserialize");
        return parsed;
    }

    private static void requireSuccess(Message response, String label) {
        require(response != null, label + " must return a response");
        require(response.getCode() == MessageCode.SUCCESS,
                label + " must succeed but was " + response.getCode() + ": "
                        + response.getMessage());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        require(value instanceof List<?>, "expected a list but was " + value);
        return (List<Map<String, Object>>) value;
    }

    private static int number(Object value) {
        require(value instanceof Number, "expected a number but was " + value);
        return ((Number) value).intValue();
    }

    // ------------------------------------------------------------------
    // Database helpers
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface SqlAssertion {
        void run(Connection connection) throws SQLException;
    }

    private static void assertDatabase(String jdbc, String user, String pass, SqlAssertion assertion)
            throws SQLException {
        try (Connection connection = connect(jdbc, user, pass)) {
            assertion.run(connection);
        }
    }

    private static Connection connect(String jdbc, String user, String pass) throws SQLException {
        Connection connection = DriverManager.getConnection(jdbc, user, pass);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+00:00'");
        }
        return connection;
    }

    private static int queryInt(Connection connection, String sql, Object... params)
            throws SQLException {
        return ((Number) queryScalar(connection, sql, params)).intValue();
    }

    private static String queryString(Connection connection, String sql, Object... params)
            throws SQLException {
        Object value = queryScalar(connection, sql, params);
        return value == null ? null : String.valueOf(value);
    }

    private static Object queryScalar(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                require(rows.next(), "query returned no row: " + sql);
                return rows.getObject(1);
            }
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    // ------------------------------------------------------------------
    // Schema reset and fresh-order seed
    // ------------------------------------------------------------------

    private static void resetAndSeed(String jdbc, String user, String pass, Path root)
            throws Exception {
        try (Connection connection = connect(jdbc, user, pass)) {
            Object liveDatabase = queryScalar(connection, "SELECT DATABASE()");
            require(TEST_DATABASE.equals(liveDatabase),
                    "refusing live end-to-end test: connected database must be exactly "
                            + TEST_DATABASE + " but was " + liveDatabase);
            requireTestDatabase(jdbc);
            resetTestSchema(connection);
            applyTblUser(connection, root.resolve("VCampusServer/src/resources/init.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V001_create_course_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V002_create_schedule_tables.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V003_extend_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/migrations/V004_admin_course_management.sql"));
            applyScript(connection, root.resolve(
                    "VCampusServer/src/resources/seed-course-test.sql"));
        }
    }

    private static void resetTestSchema(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'")) {
            while (result.next()) {
                tables.add(result.getString(1));
            }
        }
        execute(connection, "SET FOREIGN_KEY_CHECKS = 0");
        try {
            for (String table : tables) {
                execute(connection, "DROP TABLE `" + table.replace("`", "``") + "`");
            }
        } finally {
            execute(connection, "SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static void applyTblUser(Connection connection, Path initSql) throws Exception {
        Matcher matcher = TBL_USER.matcher(Files.readString(initSql, StandardCharsets.UTF_8));
        require(matcher.find(), "authoritative tbl_user definition not found");
        execute(connection, matcher.group());
    }

    private static void applyScript(Connection connection, Path path) throws Exception {
        require(Files.isRegularFile(path), "missing SQL file: " + path.getFileName());
        List<String> statements = splitStatements(Files.readString(path, StandardCharsets.UTF_8));
        for (int i = 0; i < statements.size(); i++) {
            try {
                execute(connection, statements.get(i));
            } catch (SQLException failure) {
                throw new SQLException("failed applying " + path.getFileName()
                        + " statement " + (i + 1), failure);
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean quotedIdentifier = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            char next = i + 1 < script.length() ? script.charAt(i + 1) : '\0';
            if (lineComment) {
                if (c == '\n') {
                    lineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (blockComment) {
                if (c == '*' && next == '/') {
                    blockComment = false;
                    i++;
                    current.append(' ');
                }
                continue;
            }
            if (!single && !quotedIdentifier && c == '-' && next == '-') {
                lineComment = true;
                i++;
                continue;
            }
            if (!single && !quotedIdentifier && c == '#') {
                lineComment = true;
                continue;
            }
            if (!single && !quotedIdentifier && c == '/' && next == '*') {
                blockComment = true;
                i++;
                continue;
            }
            if (!quotedIdentifier && c == '\'') {
                current.append(c);
                if (single && next == '\'') {
                    current.append(next);
                    i++;
                } else {
                    single = !single;
                }
                continue;
            }
            if (!single && c == '`') {
                quotedIdentifier = !quotedIdentifier;
                current.append(c);
                continue;
            }
            if (!single && !quotedIdentifier && c == ';') {
                String statement = current.toString().trim();
                if (!statement.isEmpty()) {
                    statements.add(statement);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            statements.add(trailing);
        }
        return statements;
    }

    // ------------------------------------------------------------------
    // Guard, config and misc helpers
    // ------------------------------------------------------------------

    private static void requireTestDatabase(String jdbcUrl) {
        String raw = jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring(5) : jdbcUrl;
        URI uri = URI.create(raw);
        String path = uri.getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database), "refusing live end-to-end test: JDBC database must be"
                + " exactly " + TEST_DATABASE + " but was " + database);
    }

    private static String withTestAuthentication(String jdbcUrl) {
        if (jdbcUrl.matches("(?i).*([?&])allowPublicKeyRetrieval=true(?:&.*)?$")) {
            return jdbcUrl;
        }
        return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "allowPublicKeyRetrieval=true";
    }

    private static Properties loadProperties(Path path) throws IOException {
        require(Files.isRegularFile(path), "missing ignored local db.properties");
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        }
        return properties;
    }

    private static String requiredProperty(Properties properties, String key) {
        String value = properties.getProperty(key);
        require(value != null && !value.isBlank(), "missing database property: " + key);
        return value;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isRegularFile(candidate.resolve("VCampusServer/src/resources/init.sql"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new AssertionError("repository root was not found");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", interrupted);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
