package integration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dto.course.CourseActions;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP/MySQL proof that an administrator's grade-approval decision publishes to the enrolled
 * student and that every superseded correction version stays frozen.
 *
 * <p>Everything lives in an isolated 2028/1 term with 98xxxx fixtures owned by this test: a
 * pre-existing <em>unpublished</em> {@code grade} row proves the student read path hides it until
 * approval, and v1-approved / v2-rejected / v3-approved batches prove the projection advances while
 * all three item snapshots survive. A replayed operationId and a two-connection administrator race
 * close the loop. Nothing outside the 981xxx range is touched, so the shared seed and any stray dev
 * row in the guarded schema cannot influence the result.
 *
 * <p>Only {@code virtual_campus_course_test} is allowed, and every fixture is removed even on failure.
 */
public final class GradeApprovalSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String ADMIN_A = "grade-e2e-admin-a";
    private static final String ADMIN_B = "grade-e2e-admin-b";
    private static final String TEACHER = "grade-e2e-teacher";
    private static final String STUDENT = "grade-e2e-student";
    private static final String STUDENT_RACE = "grade-e2e-student-race";
    private static final String PASSWORD = "course-test-only";
    private static final Gson GSON = new Gson();

    private static final int YEAR = 2028;
    private static final int SEMESTER = 1;

    private static final long COURSE = 981101;
    private static final long OFFERING = 981201;
    private static final long OFFERING_RACE = 981202;
    private static final long ENROLLMENT = 981301;
    private static final long ENROLLMENT_RACE = 981302;
    private static final long SUBMISSION_V1 = 981401;
    private static final long SUBMISSION_V2 = 981402;
    private static final long SUBMISSION_V3 = 981403;
    private static final long SUBMISSION_RACE = 981404;
    private static final long ITEM_V1 = 981501;
    private static final long ITEM_V2 = 981502;
    private static final long ITEM_V3 = 981503;
    private static final long ITEM_RACE = 981504;

    private static final String OP_V1 = "98110000-0000-0000-0000-000000000001";
    private static final String OP_V2 = "98110000-0000-0000-0000-000000000002";
    private static final String OP_V3 = "98110000-0000-0000-0000-000000000003";
    private static final String OP_SECOND = "98110000-0000-0000-0000-000000000004";
    private static final String OP_RACE_A = "98110000-0000-0000-0000-000000000005";
    private static final String OP_RACE_B = "98110000-0000-0000-0000-000000000006";

    private GradeApprovalSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        try {
            insertFixtures();
            Server server = new Server(0, new OnlineConnectionRegistry(),
                    new MessageDispatcher(), null, null);
            Thread serverThread = new Thread(server::start, "grade-e2e-server");
            serverThread.setDaemon(true);
            try {
                serverThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                require(server.isRunning(), "real server must start");
                try (JsonLineClient admin = new JsonLineClient(server.getPort());
                     JsonLineClient student = new JsonLineClient(server.getPort())) {
                    String adminToken = admin.login(ADMIN_A, "管理员");
                    String studentToken = student.login(STUDENT, "学生");
                    verifyUnpublishedBatchHidden(admin, adminToken, student, studentToken);
                    verifyApprovalV1(admin, adminToken);
                    verifyStoredV1();
                    verifyStudentSees(student, studentToken, "GRD-E2E", 88.0, 90.0, 92.0, 3.7);
                    verifyRejectedV2(admin, adminToken);
                    verifyStudentStillSeesV1(student, studentToken);
                    String frozenV1 = frozenSnapshot(SUBMISSION_V1);
                    String frozenV2 = frozenSnapshot(SUBMISSION_V2);
                    verifyApprovalV3(admin, adminToken);
                    verifyStudentSees(student, studentToken, "GRD-E2E", 91.0, 92.0, 90.0, 4.1);
                    verifyVersionsPreserved(frozenV1, frozenV2);
                    verifyReplayV1(admin, adminToken);
                    verifySecondAdministratorLoses(server.getPort(), adminToken);
                    verifyConcurrentRace(server.getPort());
                }
            } finally {
                server.stop();
                serverThread.join(5_000);
                require(!serverThread.isAlive(), "server must stop after the test");
            }
        } finally {
            cleanup();
        }
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN 981300 AND 981399") == 0
                        && number("SELECT COUNT(*) FROM grade_submission WHERE submission_id"
                        + " BETWEEN 981400 AND 981499") == 0
                        && number("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id"
                        + " BETWEEN 981400 AND 981499") == 0
                        && number("SELECT COUNT(*) FROM enrollment WHERE enrollment_id"
                        + " BETWEEN 981300 AND 981399") == 0
                        && number("SELECT COUNT(*) FROM course_offering WHERE offering_id"
                        + " BETWEEN 981200 AND 981299") == 0
                        && number("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'grade-e2e-%'") == 0,
                "cleanup must leave no fixture row behind");
        System.out.println("Grade approval socket end-to-end test passed.");
    }

    // ------------------------------------------------------------------- steps

    /**
     * The batch is PENDING and its {@code grade} row exists but is unpublished: the administrator
     * reads the frozen batch over TCP while the student's read path must still hide it.
     */
    private static void verifyUnpublishedBatchHidden(JsonLineClient admin, String adminToken,
                                                     JsonLineClient student, String studentToken)
            throws Exception {
        Message detail = admin.admin(AdminCourseActions.GET_GRADE_SUBMISSION, adminToken,
                Map.of("submissionId", Long.toString(SUBMISSION_V1)));
        requireCode(detail, MessageCode.SUCCESS, "read the pending submission");
        GradeSubmissionDetailDTO submission = GSON.fromJson(
                GSON.toJsonTree(detail.getData("gradeSubmission")), GradeSubmissionDetailDTO.class);
        require(submission.getSummary().getStatus() == ApprovalStatusDTO.PENDING
                        && submission.getSummary().getVersion() == 1
                        && submission.getItems().size() == 1
                        && close(submission.getItems().get(0).getScore(), 88.0),
                "the administrator must read the frozen pending batch over TCP");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT
                        + " AND is_published=0") == 1,
                "the projection must exist unpublished before approval");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid LIKE"
                        + " 'grade-e2e-%'") == 0,
                "no review must be recorded before the first decision");
        require(recordFor(grades(student, studentToken), "GRD-E2E") == null,
                "an unpublished projection must never reach the student");
    }

    private static void verifyApprovalV1(JsonLineClient admin, String token) throws IOException {
        Message approved = review(admin, token, OP_V1, SUBMISSION_V1, 1, true, null);
        requireCode(approved, MessageCode.SUCCESS, "approve v1 over TCP");
        GradeSubmissionDetailDTO entity = entity(approved);
        require(entity.getSummary().getStatus() == ApprovalStatusDTO.APPROVED
                        && entity.getSummary().getVersion() == 1
                        && ADMIN_A.equals(entity.getReviewedBy())
                        && entity.getReviewedAt() != null
                        && entity.getItems().size() == 1,
                "the approved v1 entity must expose APPROVED, the reviewer and the review time");
    }

    private static void verifyStoredV1() throws Exception {
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + SUBMISSION_V1
                        + " AND status='APPROVED' AND version=1 AND reviewed_by='" + ADMIN_A
                        + "' AND reviewed_at IS NOT NULL") == 1,
                "v1 must be stored APPROVED with its reviewer and review time");
        Instant reviewed = instant("SELECT reviewed_at FROM grade_submission WHERE submission_id="
                + SUBMISSION_V1);
        Instant submitted = instant("SELECT submitted_at FROM grade_submission WHERE submission_id="
                + SUBMISSION_V1);
        require(reviewed.isAfter(submitted), "the review must follow the submission");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT
                        + " AND is_published=1 AND score=88.00 AND daily_score=90.00"
                        + " AND midterm_score=85.00 AND experiment_score=95.00"
                        + " AND finalterm_score=92.00 AND grade_level=3 AND grade_point=3.7"
                        + " AND publish_time IS NOT NULL") == 1,
                "approval must upsert every projection column of the existing unpublished row");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + OP_V1 + "' AND result_code='OK'") == 1,
                "the approval must be audited exactly once");
    }

    private static void verifyRejectedV2(JsonLineClient admin, String token) throws Exception {
        Message rejected = review(admin, token, OP_V2, SUBMISSION_V2, 2, false, "成绩有误，请复核");
        requireCode(rejected, MessageCode.SUCCESS, "reject v2 over TCP");
        GradeSubmissionDetailDTO entity = entity(rejected);
        require(entity.getSummary().getStatus() == ApprovalStatusDTO.REJECTED
                        && entity.getSummary().getVersion() == 2
                        && ADMIN_A.equals(entity.getReviewedBy())
                        && "成绩有误，请复核".equals(entity.getReviewComment()),
                "the rejected v2 entity must expose the decision and its comment");
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + SUBMISSION_V2
                        + " AND status='REJECTED' AND reviewed_at IS NOT NULL") == 1,
                "v2 must be stored REJECTED with a review time");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT
                        + " AND score=88.00") == 1,
                "a rejection must never rewrite the current projection");
    }

    private static void verifyStudentStillSeesV1(JsonLineClient student, String token)
            throws IOException {
        GradeRecordDTO record = recordFor(grades(student, token), "GRD-E2E");
        require(record != null && close(record.getScore(), 88.0)
                        && close(record.getGradePoint(), 3.7),
                "a rejected correction must leave the student on the v1 values");
    }

    private static void verifyApprovalV3(JsonLineClient admin, String token) throws IOException {
        Message approved = review(admin, token, OP_V3, SUBMISSION_V3, 3, true, null);
        requireCode(approved, MessageCode.SUCCESS, "approve v3 over TCP");
        require(entity(approved).getSummary().getStatus() == ApprovalStatusDTO.APPROVED
                        && entity(approved).getSummary().getVersion() == 3,
                "the v3 correction must itself be approved");
    }

    private static void verifyStudentSees(JsonLineClient student, String token, String courseCode,
                                          double score, double daily, double finalScore,
                                          double gradePoint) throws IOException {
        GradeRecordDTO record = recordFor(grades(student, token), courseCode);
        require(record != null, "the student must see " + courseCode + " once it is published");
        require(close(record.getScore(), score) && close(record.getDailyScore(), daily)
                        && close(record.getFinalScore(), finalScore)
                        && close(record.getGradePoint(), gradePoint),
                "the published record must carry the approved values, saw " + record.getScore()
                        + "/" + record.getDailyScore() + "/" + record.getFinalScore() + "/"
                        + record.getGradePoint());
    }

    /** All three frozen batch versions must survive the v3 decision byte-for-byte. */
    private static void verifyVersionsPreserved(String frozenV1, String frozenV2) throws Exception {
        require(number("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING) == 3,
                "every correction version must remain stored");
        require(number("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id IN ("
                        + SUBMISSION_V1 + "," + SUBMISSION_V2 + "," + SUBMISSION_V3 + ")") == 3,
                "every item snapshot must remain stored");
        require(frozenV1.equals(frozenSnapshot(SUBMISSION_V1))
                        && frozenV2.equals(frozenSnapshot(SUBMISSION_V2)),
                "an approved correction must never rewrite the frozen v1/v2 item snapshots");
        require("88.00".equals(text("SELECT score FROM grade_submission_item WHERE submission_id="
                        + SUBMISSION_V1)) && "55.00".equals(text("SELECT score FROM"
                        + " grade_submission_item WHERE submission_id=" + SUBMISSION_V2))
                        && "91.00".equals(text("SELECT score FROM grade_submission_item WHERE"
                        + " submission_id=" + SUBMISSION_V3)),
                "each version must keep its own distinct snapshot");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT
                        + " AND is_published=1 AND score=91.00") == 1
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT) == 1,
                "the current projection must be the single v3 value");
    }

    private static void verifyReplayV1(JsonLineClient admin, String token) throws Exception {
        Instant before = instant("SELECT reviewed_at FROM grade_submission WHERE submission_id="
                + SUBMISSION_V1);
        Message replayed = review(admin, token, OP_V1, SUBMISSION_V1, 1, true, null);
        requireCode(replayed, MessageCode.SUCCESS, "replay the v1 approval");
        require(entity(replayed).getSummary().getStatus() == ApprovalStatusDTO.APPROVED,
                "a replay must return the committed decision");
        require(before.equals(instant("SELECT reviewed_at FROM grade_submission WHERE submission_id="
                        + SUBMISSION_V1))
                        && number("SELECT COUNT(*) FROM admin_course_operation_log WHERE"
                        + " operation_id='" + OP_V1 + "'") == 1
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT) == 1,
                "a replay must neither re-decide nor duplicate the projection or the audit row");
    }

    private static void verifySecondAdministratorLoses(int port, String firstToken) throws Exception {
        try (JsonLineClient second = new JsonLineClient(port)) {
            String secondToken = second.login(ADMIN_B, "管理员");
            Message refused = review(second, secondToken, OP_SECOND, SUBMISSION_V1, 1, true, null);
            requireCode(refused, MessageCode.CONFLICT,
                    "a second administrator must not re-decide a settled submission");
            require(refused.getData("latest") != null,
                    "the refusal must carry the settled state for the dialog");
        }
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT) == 1,
                "the losing decision must not touch the projection");
    }

    /** Two administrators race one pending submission from two connections; exactly one commits. */
    private static void verifyConcurrentRace(int port) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> observed = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();

        Thread threadA = new Thread(racer(port, ADMIN_A, OP_RACE_A, barrier, observed, failures),
                "grade-racer-a");
        Thread threadB = new Thread(racer(port, ADMIN_B, OP_RACE_B, barrier, observed, failures),
                "grade-racer-b");
        threadA.start();
        threadB.start();
        threadA.join();
        threadB.join();

        require(failures.isEmpty(), "the race must not raise " + failures);
        require(observed.size() == 2
                        && observed.stream().filter(entry -> entry.startsWith("200")).count() == 1
                        && observed.stream().filter(entry -> entry.startsWith("409")).count() == 1,
                "exactly one racing administrator must commit and one must conflict, saw " + observed);
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + SUBMISSION_RACE
                        + " AND status='APPROVED' AND reviewed_at IS NOT NULL") == 1,
                "the race must settle the submission exactly once");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id IN ('"
                        + OP_RACE_A + "','" + OP_RACE_B + "')") == 1,
                "the race must be audited exactly once");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT_RACE
                        + " AND is_published=1 AND score=77.00") == 1,
                "the race must publish exactly one projection");
    }

    private static Runnable racer(int port, String admin, String operationId, CyclicBarrier barrier,
                                  List<String> observed, List<Throwable> failures) {
        return () -> {
            try (JsonLineClient client = new JsonLineClient(port)) {
                String token = client.login(admin, "管理员");
                barrier.await(10, TimeUnit.SECONDS);
                Message response = review(client, token, operationId, SUBMISSION_RACE, 1, true, null);
                synchronized (observed) {
                    observed.add(response.getCode().getCode() + "/" + response.getMessage() + "/"
                            + GSON.toJson(response.getData()));
                }
            } catch (Throwable failure) {
                synchronized (failures) {
                    failures.add(failure);
                }
            }
        };
    }

    // ------------------------------------------------------------------- fixtures

    private static void insertFixtures() throws Exception {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + ADMIN_A + "','Grade E2E Admin A',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),"
                + "('" + ADMIN_B + "','Grade E2E Admin B',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),"
                + "('" + TEACHER + "','Grade E2E Teacher',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',1,'Engineering','Lecturer'),"
                + "('" + STUDENT + "','Grade E2E Student',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS'),"
                + "('" + STUDENT_RACE + "','Grade E2E Race Student',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE + ",'GRD-E2E','Grade Approval E2E Course',"
                + "3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING + ",'GRD-E2E-A'," + COURSE + ","
                + YEAR + "," + SEMESTER + ",30,2),(" + OFFERING_RACE + ",'GRD-E2E-B'," + COURSE + ","
                + YEAR + "," + SEMESTER + ",30,2)");
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + ENROLLMENT + "," + OFFERING + "," + COURSE + ","
                + YEAR + "," + SEMESTER + ",'" + STUDENT + "',2,'2028-02-01 00:00:00'),("
                + ENROLLMENT_RACE + "," + OFFERING_RACE + "," + COURSE + "," + YEAR + ","
                + SEMESTER + ",'" + STUDENT_RACE + "',2,'2028-02-01 00:00:00')");
        // An unpublished projection already exists, so approval must exercise the upsert branch.
        execute("INSERT INTO grade(enrollment_id,daily_score,midterm_score,experiment_score,"
                + "finalterm_score,score,grade_level,grade_point,is_published,publish_time) VALUES("
                + ENROLLMENT + ",99.00,99.00,99.00,99.00,99.00,5,4.0,0,NULL)");
        submission(SUBMISSION_V1, OFFERING, 1, ITEM_V1, ENROLLMENT, "90.00", "85.00", "95.00",
                "92.00", "88.00", 3, "3.7", "88.00", "88.00", "88.00", 0);
        submission(SUBMISSION_V2, OFFERING, 2, ITEM_V2, ENROLLMENT, "60.00", "50.00", "55.00",
                "55.00", "55.00", 4, "1.0", "55.00", "55.00", "55.00", 1);
        submission(SUBMISSION_V3, OFFERING, 3, ITEM_V3, ENROLLMENT, "92.00", "90.00", "93.00",
                "90.00", "91.00", 2, "4.1", "91.00", "91.00", "91.00", 0);
        submission(SUBMISSION_RACE, OFFERING_RACE, 1, ITEM_RACE, ENROLLMENT_RACE, "78.00", "76.00",
                "77.00", "77.00", "77.00", 2, "2.7", "77.00", "77.00", "77.00", 0);
    }

    private static void submission(long submissionId, long offeringId, int version, long itemId,
                                   long enrollmentId, String daily, String midterm,
                                   String experiment, String finalterm, String score, int level,
                                   String point, String average, String max, String min, int failed)
            throws Exception {
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,"
                + "submitted_at,status,reviewed_by,reviewed_at,review_comment,average_score,"
                + "max_score,min_score,failed_count,total_count) VALUES(" + submissionId + ","
                + offeringId + "," + version + ",'" + TEACHER + "','2026-01-01 00:00:00',"
                + "'PENDING',NULL,NULL,NULL," + average + "," + max + "," + min + "," + failed
                + ",1)");
        execute("INSERT INTO grade_submission_item(item_id,submission_id,enrollment_id,daily_score,"
                + "midterm_score,experiment_score,finalterm_score,score,grade_level,grade_point)"
                + " VALUES(" + itemId + "," + submissionId + "," + enrollmentId + "," + daily + ","
                + midterm + "," + experiment + "," + finalterm + "," + score + "," + level + ","
                + point + ")");
    }

    private static void cleanup() throws Exception {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid LIKE 'grade-e2e-%'");
        execute("DELETE FROM grade WHERE enrollment_id BETWEEN 981300 AND 981399");
        execute("DELETE FROM grade_submission_item WHERE submission_id BETWEEN 981400 AND 981499");
        execute("DELETE FROM grade_submission WHERE submission_id BETWEEN 981400 AND 981499");
        execute("DELETE FROM enrollment WHERE enrollment_id BETWEEN 981300 AND 981399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 981200 AND 981299");
        execute("DELETE FROM course WHERE course_id BETWEEN 981100 AND 981199");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'grade-e2e-%'");
    }

    /** Canonical text of one submission's frozen item snapshot, to prove a later decision left it. */
    private static String frozenSnapshot(long submissionId) throws Exception {
        return text("SELECT GROUP_CONCAT(CONCAT(item_id,'|',enrollment_id,'|',daily_score,'|',"
                + "midterm_score,'|',experiment_score,'|',finalterm_score,'|',score,'|',"
                + "grade_level,'|',grade_point) ORDER BY item_id SEPARATOR ',')"
                + " FROM grade_submission_item WHERE submission_id=" + submissionId);
    }

    // -------------------------------------------------------------------- helpers

    private static Message review(JsonLineClient admin, String token, String operationId,
                                  long submissionId, int expectedVersion, boolean approved,
                                  String comment) throws IOException {
        Map<String, Object> decision = new java.util.LinkedHashMap<>();
        decision.put("operationId", operationId);
        decision.put("requestId", Long.toString(submissionId));
        decision.put("expectedVersion", expectedVersion);
        decision.put("approved", approved);
        if (comment != null) decision.put("reviewComment", comment);
        return admin.admin(AdminCourseActions.REVIEW_GRADE_SUBMISSION, token,
                Map.of("request", decision));
    }

    private static GradeSubmissionDetailDTO entity(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("entity"), GradeSubmissionDetailDTO.class);
    }

    private static GradeSummaryDTO grades(JsonLineClient student, String token) throws IOException {
        Message response = student.request("course", CourseActions.LOAD_GRADES, token,
                Map.of("academicYear", YEAR, "semester", SEMESTER));
        requireCode(response, MessageCode.SUCCESS, "read the student grades");
        return GSON.fromJson(GSON.toJsonTree(response.getData("grades")), GradeSummaryDTO.class);
    }

    private static GradeRecordDTO recordFor(GradeSummaryDTO summary, String courseCode) {
        for (GradeRecordDTO record : summary.getRecords()) {
            if (courseCode.equals(record.getCourseCode())) return record;
        }
        return null;
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 0.005;
    }

    private static void requireTestDatabase() throws Exception {
        Properties properties = new Properties();
        try (InputStream stream = DBUtil.class.getClassLoader()
                .getResourceAsStream("resources/db.properties")) {
            require(stream != null, "db.properties is unavailable on the runtime classpath");
            properties.load(stream);
        }
        String url = properties.getProperty("db.url");
        require(url != null && !url.isBlank(), "db.url is not configured");
        String raw = url.startsWith("jdbc:") ? url.substring(5) : url;
        String path = URI.create(raw).getPath();
        String database = path == null ? "" : path.replaceFirst("^/", "");
        require(TEST_DATABASE.equals(database),
                "Refusing grade e2e: the JDBC URL must target the guarded schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing grade e2e outside the guarded schema");
    }

    private static void requireCode(Message response, MessageCode expected, String what) {
        require(response.getCode() == expected,
                what + " must be " + expected + ", saw " + response.getCode() + " ("
                        + response.getMessage() + ")");
    }

    private static int number(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            String value = rows.getString(1);
            return value == null ? "" : value;
        }
    }

    /** DATETIME columns hold a UTC wall clock, so the instant must be re-anchored at UTC. */
    private static Instant instant(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            java.sql.Timestamp value = rows.getTimestamp(1);
            require(value != null, "expected a non-null timestamp from " + sql);
            return value.toLocalDateTime().toInstant(ZoneOffset.UTC);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Minimal real-socket client: one JSON line per message, logged in with a real token. */
    private static final class JsonLineClient implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader reader;
        private final BufferedWriter writer;

        private JsonLineClient(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(15_000);
            reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private String login(String uid, String role) throws IOException {
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", PASSWORD, "role", role));
            requireCode(response, MessageCode.SUCCESS, "login for " + uid);
            Object token = response.getData("token");
            require(token instanceof String value && !value.isBlank(), "login must supply a token");
            return (String) token;
        }

        private Message admin(String action, String token, Map<String, Object> data)
                throws IOException {
            return request("courseAdmin", action, token, data);
        }

        private Message request(String module, String action, String token,
                                Map<String, Object> data) throws IOException {
            Message request = new Message(MessageType.REQUEST, module, action);
            request.setToken(token);
            if (data != null) {
                for (Map.Entry<String, Object> entry : data.entrySet()) {
                    request.putData(entry.getKey(), entry.getValue());
                }
            }
            writer.write(GSON.toJson(request));
            writer.newLine();
            writer.flush();
            String line = reader.readLine();
            require(line != null, "server must answer " + module + "." + action);
            return GSON.fromJson(line, Message.class);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
