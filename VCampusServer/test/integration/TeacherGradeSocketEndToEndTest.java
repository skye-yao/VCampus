package integration;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dao.TeacherGradeBookDAO;
import dto.course.CourseActions;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
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
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Real TCP/MySQL proof of the whole teacher-grade chain in <em>one</em> fixture: the teacher saves a
 * partial draft over the wire, completes and submits it, the administrator approves that same batch,
 * and the student reads the resulting grade through {@code course}/{@code loadGrades}.
 *
 * <p>Why one fixture: the unit and service suites pin each half against snapshots the other half
 * never wrote — the teacher test asserts the JSON {@code submitGradeBook} stores and the approval
 * test parses a hand-written fixture of the same shape. Both would keep passing if the two sides
 * drifted apart. Here the scheme the administrator reads back is the scheme the teacher sent in this
 * very run, and the totals the student sees are recomputed from the item snapshot this run captured.
 *
 * <p>What the three offerings prove:
 * <ul>
 *   <li>A (13 students, one per grade-point band, EXPERIMENT disabled) — the draft/partial state, the
 *       PENDING batch being invisible to the student, the 13 captured totals and their grade points,
 *       {@code NULL} for a disabled component (never 0), a student enrolled after the submission
 *       neither joining the batch nor invalidating it, and the teacher's roster-change notice.</li>
 *   <li>B (1 student, all four components captured) — every component non-NULL, and the credit-weighted
 *       term GPA the student reads once both courses are published.</li>
 *   <li>C (2 students) — a rejection that leaves no projection behind, the teacher reading the
 *       administrator's instruction back over TCP, a RESUBMISSION batch carrying its base batch, and a
 *       refused approval of the superseded version that leaves the published projection byte-identical.</li>
 * </ul>
 *
 * <p>Fixtures live in the 983xxx id range with the {@code tge983-} UID prefix — none of
 * {@code tgb947-} (947000-947999), {@code tgs948-} (948000-948999) or {@code gra974-}
 * (974100-974799) — and only {@code virtual_campus_course_test} is allowed. Every fixture row is
 * deleted again even when an assertion fails.
 */
public final class TeacherGradeSocketEndToEndTest {
    private static final String TEST_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tge983-";
    private static final String ADMIN = PREFIX + "admin";
    private static final String TEACHER = PREFIX + "teacher";
    private static final String TEACHER_NAME = "Tge983 Teacher";
    private static final String ADMIN_NAME = "Tge983 Admin";
    private static final String PASSWORD = "course-test-only";
    private static final Gson GSON = new Gson();
    private static final Type OFFERING_ITEMS = new TypeToken<List<TeacherGradeOfferingDTO>>() { }.getType();

    private static final int YEAR = 2027;
    private static final int SEMESTER = 1;

    private static final long COURSE_A = 983101L;
    private static final long COURSE_B = 983102L;
    private static final long COURSE_C = 983103L;
    private static final long OFFERING_A = 983201L;
    private static final long OFFERING_B = 983202L;
    private static final long OFFERING_C = 983203L;
    /** A's 13 band enrollments are 983301..983313; the post-submission newcomer is 983314. */
    private static final long ENROLLMENT_A_FIRST = 983301L;
    private static final long ENROLLMENT_NEWCOMER = 983314L;
    private static final long ENROLLMENT_B = 983321L;
    private static final long ENROLLMENT_C1 = 983331L;
    private static final long ENROLLMENT_C2 = 983332L;

    /**
     * 13 students, one per grade-point band. The scheme weights DAILY 4000 / MIDTERM 2000 /
     * FINALTERM 4000, so three equal components average back to exactly that value and the stored
     * total is the band's own score — the 13 bands are therefore exercised by real submissions
     * rather than by a table lookup. The last band (59.99 → 0.0) also pins the failed-count edge.
     */
    private static final String[] BAND_SCORES = {"96.00", "93.00", "90.00", "86.00", "83.00", "80.00",
            "76.00", "73.00", "70.00", "66.00", "63.00", "60.00", "59.99"};
    private static final String[] BAND_POINTS = {"4.8", "4.5", "4.0", "3.8", "3.5", "3.0", "2.8",
            "2.5", "2.0", "1.8", "1.5", "1.0", "0.0"};

    /** sha256 of the canonical roster text "983301\n...\n983313\n" (sorted ids, one per line). */
    private static final String DIGEST_A =
            "93ada010813eab72cf5714e03689bd827026eb0f9b6c1e30cdac8ac5479ff5dd";

    private TeacherGradeSocketEndToEndTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        LoginSchemaTestBridge.ensureBankAccountTable();
        cleanup();
        try {
            insertFixtures();
            Server server = new Server(0, new OnlineConnectionRegistry(),
                    new MessageDispatcher(), null, null);
            Thread serverThread = new Thread(server::start, "teacher-grade-e2e-server");
            serverThread.setDaemon(true);
            try {
                serverThread.start();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!server.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
                require(server.isRunning(), "real server must start");
                try (JsonLineClient teacher = new JsonLineClient(server.getPort());
                     JsonLineClient admin = new JsonLineClient(server.getPort());
                     JsonLineClient student = new JsonLineClient(server.getPort());
                     JsonLineClient other = new JsonLineClient(server.getPort())) {
                    String teacherToken = teacher.login(TEACHER, "教师");
                    String adminToken = admin.login(ADMIN, "管理员");
                    String studentToken = student.login(studentUid(1), "学生");
                    // Offering C enrolls students 31/32, so its reads come from one of them.
                    String otherToken = other.login(studentUid(31), "学生");
                    long batchA = offerA(teacher, teacherToken, admin, adminToken, student,
                            studentToken);
                    offerB(teacher, teacherToken, admin, adminToken, student, studentToken);
                    offerC(teacher, teacherToken, admin, adminToken, other, otherToken);
                    verifyTeacherStillSeesTheDecidedBatches(teacher, teacherToken, batchA);
                }
            } finally {
                server.stop();
                serverThread.join(5_000);
                require(!serverThread.isAlive(), "server must stop after the test");
            }
        } finally {
            cleanup();
        }
        require(number("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'") == 0
                        && number("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 983200 AND 983299") == 0
                        && number("SELECT COUNT(*) FROM grade_submission WHERE offering_id BETWEEN"
                        + " 983200 AND 983299") == 0,
                "cleanup must leave no fixture row behind");
        System.out.println("Teacher grade socket end-to-end test passed.");
    }

    // ------------------------------------------------------------- offering A

    /**
     * The partial-draft → submit → approve → student-read chain plus the post-submission newcomer.
     *
     * @return the id of the batch A submission so later steps can inspect it
     */
    private static long offerA(JsonLineClient teacher, String teacherToken, JsonLineClient admin,
                               String adminToken, JsonLineClient student, String studentToken)
            throws Exception {
        TeacherGradeBookDTO virtual = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", Long.toString(OFFERING_A))));
        require(virtual.getRevision() == 0 && "DRAFT".equals(virtual.getState()) && virtual.isCanEdit()
                        && virtual.getLastSubmissionId() == null && virtual.getRows().size() == 13,
                "an untouched offering reads as the revision-0 virtual draft (observed "
                        + virtual.getRevision() + "/" + virtual.getState() + "/"
                        + virtual.getRows().size() + ")");
        require(virtual.getScheme() != null && virtual.getScheme().getComponents().size() == 4
                        && virtual.getScheme().getComponents().get(0).getWeightBasisPoints() == 0,
                "the virtual draft carries the default four-component, zero-weight scheme");
        require(virtual.getRows().stream().allMatch(row -> row.getTotalScore() == null),
                "a zero-weight draft must not invent a total");
        require(countOfferings(teacher, teacherToken) == 3, "the term lists the teacher's own batches");
        TeacherGradeOfferingDTO listed = offeringIn(listOfferings(teacher, teacherToken), OFFERING_A);
        require("DRAFT".equals(listed.getState()) && listed.getEnteredCount() == 0
                        && listed.getMissingCount() == 13 && listed.getLastSubmissionId() == null,
                "the grade list reports an empty draft as 0 entered / 13 missing (observed "
                        + listed.getEnteredCount() + "/" + listed.getMissingCount() + ")");
        requireNoRecord(grades(student, studentToken), "TGE983A", "before the draft is complete");

        // 1. A partial draft: every student scored except the last one's final-term component.
        String draftOperation = op(1);
        WriteGradeBookRequestDTO partial = write(draftOperation, OFFERING_A, 0, DIGEST_A,
                schemeA(), rowsA(false));
        TeacherGradeBookDTO draft = book(save(teacher, teacherToken,
                TeacherCourseActions.SAVE_GRADE_DRAFT, Map.of("request", partial)));
        require(draft.getRevision() == 1 && "DRAFT".equals(draft.getState()) && draft.isCanEdit()
                        && draft.getLastSubmissionId() == null,
                "the partial draft is saved as revision 1 and stays editable (observed "
                        + draft.getRevision() + "/" + draft.getState() + ")");
        require(number("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_A
                        + " AND revision=1 AND draft_open=1 AND draft_kind='INITIAL'"
                        + " AND last_submission_id IS NULL") == 1
                        && number("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_A) == 13
                        && number("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_A + " AND enrollment_id=" + (ENROLLMENT_A_FIRST + 12) + " AND"
                        + " finalterm_score IS NULL AND daily_score=59.99 AND experiment_score"
                        + " IS NULL") == 1,
                "the saved draft stores 13 rows and keeps the incomplete component NULL");
        require(number("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFFERING_A) == 0
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN "
                        + ENROLLMENT_A_FIRST + " AND " + (ENROLLMENT_A_FIRST + 12)) == 0,
                "a draft writes no batch and no grade projection");
        listed = offeringIn(listOfferings(teacher, teacherToken), OFFERING_A);
        require(listed.getEnteredCount() == 13 && listed.getMissingCount() == 1,
                "the grade list counts 13 entered and 1 missing after the partial draft (observed "
                        + listed.getEnteredCount() + "/" + listed.getMissingCount() + ")");
        requireNoRecord(grades(student, studentToken), "TGE983A", "while the batch is still a draft");

        // 2. Completing the draft and submitting the very same content as one batch.
        String submitOperation = op(2);
        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(submitOperation, OFFERING_A, 1, DIGEST_A, schemeA(),
                        rowsA(true))));
        requireCode(submitted, MessageCode.SUCCESS, "submit offering A over TCP");
        TeacherGradeBookDTO pending = book(submitted);
        long batchA = Long.parseLong(pending.getLastSubmissionId());
        require("PENDING".equals(pending.getState()) && !pending.isCanEdit()
                        && pending.getRevision() == 2 && "成绩批次已提交".equals(message(submitted)),
                "the submitted book is a read-only PENDING batch at revision 2 (observed "
                        + pending.getState() + "/" + pending.isCanEdit() + "/"
                        + pending.getRevision() + ")");
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + batchA
                        + " AND offering_id=" + OFFERING_A + " AND version=1 AND status='PENDING'"
                        + " AND submitted_by='" + TEACHER + "' AND submission_kind='INITIAL'"
                        + " AND base_submission_id IS NULL AND total_count=13 AND failed_count=1"
                        + " AND average_score=76.61 AND max_score=96.00 AND min_score=59.99"
                        + " AND roster_digest='" + DIGEST_A + "'"
                        + " AND JSON_EXTRACT(scheme_snapshot_json,'$.components[*].code')="
                        + "JSON_ARRAY('DAILY','MIDTERM','EXPERIMENT','FINALTERM')"
                        + " AND JSON_EXTRACT(scheme_snapshot_json,'$.components[*].weightBasisPoints')"
                        + "=JSON_ARRAY(4000,2000,0,4000)"
                        + " AND JSON_EXTRACT(scheme_snapshot_json,'$.components[*].enabled')="
                        + "JSON_ARRAY(true,true,false,true)") == 1,
                "the batch captures the scheme, the roster digest and the recomputed statistics ("
                        + text("SELECT CONCAT(average_score,'/',max_score,'/',min_score,'/',"
                        + "failed_count,'/',total_count) FROM grade_submission WHERE submission_id="
                        + batchA) + ")");
        require(number("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id=" + batchA)
                        == 13,
                "the batch captures the whole roster");
        for (int index = 0; index < BAND_SCORES.length; index++) {
            String score = BAND_SCORES[index];
            require(number("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id=" + batchA
                            + " AND enrollment_id=" + (ENROLLMENT_A_FIRST + index)
                            + " AND daily_score=" + score + " AND midterm_score=" + score
                            + " AND finalterm_score=" + score + " AND experiment_score IS NULL"
                            + " AND score=" + score + " AND grade_point=" + BAND_POINTS[index]
                            + " AND student_uid_snapshot='" + studentUid(index + 1) + "'") == 1,
                    "item " + (index + 1) + " captures its band value, total and grade point");
        }
        require(number("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_A + " AND action='submitGradeBook' AND book_revision=2"
                        + " AND enrollment_id=" + (ENROLLMENT_A_FIRST + 12)
                        + " AND before_json IS NOT NULL AND after_json IS NOT NULL") == 1
                        && number("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_A + " AND action='submitGradeBook'") == 1
                        && number("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER + "' AND operation_id='" + submitOperation
                        + "' AND action='submitGradeBook' AND target_id='" + OFFERING_A
                        + "' AND result_code='OK'") == 1,
                "the submission logs its audit trail and its operation id");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN "
                        + ENROLLMENT_A_FIRST + " AND " + (ENROLLMENT_A_FIRST + 12)) == 0,
                "a pending batch publishes nothing");
        requireNoRecord(grades(student, studentToken), "TGE983A",
                "while the batch is pending approval");

        // 3. The administrator reads the frozen batch the teacher actually wrote.
        GradeSubmissionDetailDTO detail = submissionDetail(admin, adminToken, batchA);
        require(detail.getSummary().getStatus() == ApprovalStatusDTO.PENDING
                        && detail.getSummary().getVersion() == 1
                        && detail.getSummary().getStudentCount() == 13
                        && detail.getSummary().getAverage() == 76.61
                        && detail.getSummary().getHighest() == 96.0
                        && detail.getSummary().getLowest() == 59.99
                        && detail.getSummary().getFailCount() == 1
                        && TEACHER.equals(detail.getSummary().getTeacherUid())
                        && TEACHER_NAME.equals(detail.getSummary().getTeacherName())
                        && "TGE983-A".equals(detail.getSummary().getOfferingCode()),
                "the administrator's detail joins the teacher, the course and the captured statistics");
        require(detail.getBaseSubmissionId() == null && detail.getUncoveredCount() == 0,
                "a first submission carries no base batch and, for now, no uncovered newcomer");
        requireSnapshotMatchesA(detail);
        requireBandsMatchA(detail);
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + batchA
                        + " AND roster_digest='" + DIGEST_A + "'") == 1,
                "the digest the teacher sent is the one independently computed for this roster");

        // 4. A student enrolled after the submission: reported, not absorbed, not invalidating.
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + ENROLLMENT_NEWCOMER + "," + OFFERING_A + ","
                + COURSE_A + "," + YEAR + "," + SEMESTER + ",'" + studentUid(14)
                + "',2,'2027-02-01 00:00:00')");
        GradeSubmissionDetailDTO withNewcomer = submissionDetail(admin, adminToken, batchA);
        require(withNewcomer.getUncoveredCount() == 1 && withNewcomer.getItems().size() == 13,
                "the detail reports the newcomer without adding them to the frozen batch (observed "
                        + withNewcomer.getUncoveredCount() + "/" + withNewcomer.getItems().size() + ")");

        // 5. Approval publishes exactly the captured students.
        Message approved = review(admin, adminToken, op(3), batchA, 1, true, null);
        requireCode(approved, MessageCode.SUCCESS, "approve batch A over TCP");
        GradeSubmissionDetailDTO approvedEntity = entity(approved);
        require(approvedEntity.getSummary().getStatus() == ApprovalStatusDTO.APPROVED
                        && ADMIN.equals(approvedEntity.getReviewedBy())
                        && approvedEntity.getReviewedAt() != null,
                "the approval records its reviewer and instant");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN "
                        + ENROLLMENT_A_FIRST + " AND " + (ENROLLMENT_A_FIRST + 12)
                        + " AND is_published=1 AND publish_time IS NOT NULL") == 13
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id="
                        + ENROLLMENT_NEWCOMER) == 0,
                "the approval publishes the 13 captured students and never the newcomer");
        for (int index = 0; index < BAND_SCORES.length; index++) {
            String score = BAND_SCORES[index];
            require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id="
                            + (ENROLLMENT_A_FIRST + index) + " AND score=" + score
                            + " AND daily_score=" + score + " AND midterm_score=" + score
                            + " AND finalterm_score=" + score + " AND experiment_score IS NULL"
                            + " AND grade_point=" + BAND_POINTS[index] + " AND is_published=1") == 1,
                    "the published projection keeps band " + (index + 1) + "'s values and point");
        }
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN
                        + "' AND operation_id='" + op(3) + "' AND action='reviewGradeSubmission'"
                        + " AND result_code='OK'") == 1,
                "the approval is audited exactly once");

        // 6. The student reads the published grade immediately.
        GradeSummaryDTO summary = grades(student, studentToken);
        GradeRecordDTO recordA = recordFor(summary, "TGE983A");
        require(recordA != null, "the student must see the approved course");
        require(close(recordA.getScore(), 96.0) && close(recordA.getGradePoint(), 4.8)
                        && close(recordA.getDailyScore(), 96.0)
                        && close(recordA.getMidtermScore(), 96.0)
                        && close(recordA.getFinalScore(), 96.0)
                        && recordA.getExperimentScore() == null
                        && close(recordA.getCredit(), 3.0),
                "the student reads the four components (with the disabled one NULL), the total and"
                        + " the grade point (observed " + recordA.getScore() + "/"
                        + recordA.getGradePoint() + "/" + recordA.getExperimentScore() + ")");
        require(close(summary.getTermGpa(), 4.8) && close(summary.getTermAverage(), 96.0),
                "one published course makes the term GPA the course's own point (observed "
                        + summary.getTermGpa() + "/" + summary.getTermAverage() + ")");

        // 7. The teacher's read of the decided batch sees the roster change.
        TeacherGradeBookDTO decided = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", Long.toString(OFFERING_A))));
        require("APPROVED".equals(decided.getState()) && !decided.isCanEdit()
                        && Long.toString(batchA).equals(decided.getLastSubmissionId())
                        && decided.isRosterChangedSinceSubmission() && decided.getRows().size() == 14,
                "the teacher sees an APPROVED read-only batch plus the roster-change notice"
                        + " (observed " + decided.getState() + "/"
                        + decided.isRosterChangedSinceSubmission() + "/" + decided.getRows().size()
                        + ")");
        return batchA;
    }

    // ------------------------------------------------------------- offering B

    /** The second published course: all four components non-NULL and the credit-weighted term GPA. */
    private static void offerB(JsonLineClient teacher, String teacherToken, JsonLineClient admin,
                               String adminToken, JsonLineClient student, String studentToken)
            throws Exception {
        String digest = TeacherGradeBookDAO.rosterDigest(List.of(ENROLLMENT_B));
        WriteGradeBookRequestDTO write = write(op(4), OFFERING_B, 0, digest, schemeB(),
                List.of(row(ENROLLMENT_B, "60.00", "60.00", "60.00", "60.00")));
        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write));
        requireCode(submitted, MessageCode.SUCCESS, "submit offering B over TCP");
        long batchB = Long.parseLong(book(submitted).getLastSubmissionId());
        GradeSubmissionDetailDTO detail = submissionDetail(admin, adminToken, batchB);
        require(detail.getItems().size() == 1 && detail.getItems().get(0).getExperimentScore() != null
                        && close(detail.getItems().get(0).getDailyScore(), 60.0)
                        && close(detail.getItems().get(0).getMidtermScore(), 60.0)
                        && close(detail.getItems().get(0).getFinaltermScore(), 60.0)
                        && close(detail.getItems().get(0).getScore(), 60.0)
                        && close(detail.getItems().get(0).getGradePoint(), 1.0),
                "an enabled component is captured with its value, not as NULL");
        require(detail.getSchemeSnapshot() != null
                        && detail.getSchemeSnapshot().getComponents().size() == 4,
                "the administrator reads the captured scheme of the second batch");
        Map<GradeComponentCodeDTO, GradeComponentDTO> captured = new LinkedHashMap<>();
        for (GradeComponentDTO component : detail.getSchemeSnapshot().getComponents()) {
            captured.put(component.getCode(), component);
        }
        require(component(captured, GradeComponentCodeDTO.DAILY, true, 3000)
                        && component(captured, GradeComponentCodeDTO.MIDTERM, true, 2000)
                        && component(captured, GradeComponentCodeDTO.EXPERIMENT, true, 2000)
                        && component(captured, GradeComponentCodeDTO.FINALTERM, true, 3000),
                "the administrator reads back the 30/20/20/30 scheme this run submitted");
        requireCode(review(admin, adminToken, op(5), batchB, 1, true, null), MessageCode.SUCCESS,
                "approve batch B over TCP");
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT_B
                        + " AND score=60.00 AND daily_score=60.00 AND midterm_score=60.00"
                        + " AND experiment_score=60.00 AND finalterm_score=60.00"
                        + " AND grade_point=1.0 AND is_published=1") == 1,
                "the second approval publishes its own projection");

        GradeSummaryDTO summary = grades(student, studentToken);
        require(summary.getRecords().size() == 2, "the student now sees both published courses ("
                + summary.getRecords().size() + ")");
        // (4.8*3 + 1.0*1.5) / 4.5 = 3.5333... and (96*3 + 60*1.5) / 4.5 = 84.0 exactly.
        require(close(summary.getTermGpa(), 15.9 / 4.5) && close(summary.getTermAverage(), 84.0),
                "the term GPA is weighted by credit (observed " + summary.getTermGpa() + "/"
                        + summary.getTermAverage() + ")");
        require(close(summary.getCumulativeGpa(), 15.9 / 4.5)
                        && close(summary.getCumulativeAverage(), 84.0),
                "the cumulative figures match the only two published courses this student has");
        GradeRecordDTO recordB = recordFor(summary, "TGE983B");
        require(recordB != null && close(recordB.getScore(), 60.0)
                        && close(recordB.getGradePoint(), 1.0)
                        && close(recordB.getExperimentScore(), 60.0)
                        && close(recordB.getCredit(), 1.5),
                "the second record carries its own credit, total and non-NULL experiment score");
    }

    // ------------------------------------------------------------- offering C

    /**
     * Rejection → teacher re-read → resubmission → approval → a refused replay of the old version.
     *
     * <p>The offering starts with an already <em>published</em> grade, so "a rejection never clears a
     * published grade" is shown on a real published row rather than inferred from an empty table.
     */
    private static void offerC(JsonLineClient teacher, String teacherToken, JsonLineClient admin,
                               String adminToken, JsonLineClient student, String studentToken)
            throws Exception {
        String digest = TeacherGradeBookDAO.rosterDigest(List.of(ENROLLMENT_C1, ENROLLMENT_C2));
        assertPublishedPremise(student, studentToken, "before the first submission");
        Message submitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(op(6), OFFERING_C, 0, digest, schemeC(),
                        List.of(row(ENROLLMENT_C1, "70.00", "70.00", "70.00", "70.00"),
                                row(ENROLLMENT_C2, "50.00", "50.00", "50.00", "50.00")))));
        requireCode(submitted, MessageCode.SUCCESS, "submit offering C over TCP");
        long batchC1 = Long.parseLong(book(submitted).getLastSubmissionId());

        String publishedBeforeOffer = publishedSnapshot();
        Message rejected = review(admin, adminToken, op(7), batchC1, 1, false, "  平时分与总评不一致，请核对  ");
        requireCode(rejected, MessageCode.SUCCESS, "reject batch C v1 over TCP");
        require(entity(rejected).getSummary().getStatus() == ApprovalStatusDTO.REJECTED
                        && "平时分与总评不一致，请核对".equals(entity(rejected).getReviewComment()),
                "the rejection records the trimmed instruction");
        // 这个教学班在本次提交之前就有一条已发布成绩：驳回必须原样留着它（不发布、不清空、不改值）。
        require(publishedBeforeOffer.equals(publishedSnapshot()),
                "a rejection must leave every already published projection byte-identical");
        assertPublishedPremise(student, studentToken, "after a rejection");

        TeacherGradeBookDTO afterRejection = book(save(teacher, teacherToken,
                TeacherCourseActions.GET_GRADE_BOOK, Map.of("offeringId", Long.toString(OFFERING_C))));
        require("REJECTED".equals(afterRejection.getState()) && afterRejection.isCanEdit()
                        && "平时分与总评不一致，请核对".equals(afterRejection.getReviewComment())
                        && Long.toString(batchC1).equals(afterRejection.getLastSubmissionId())
                        && afterRejection.getRevision() == 1,
                "the teacher reads the rejected batch and the administrator's instruction over TCP"
                        + " (observed " + afterRejection.getState() + "/"
                        + afterRejection.getReviewComment() + ")");

        Message resubmitted = save(teacher, teacherToken, TeacherCourseActions.SUBMIT_GRADE_BOOK,
                Map.of("request", write(op(8), OFFERING_C, 1, digest, schemeC(),
                        List.of(row(ENROLLMENT_C1, "90.00", "90.00", "90.00", "90.00"),
                                row(ENROLLMENT_C2, "60.00", "60.00", "60.00", "60.00")))));
        requireCode(resubmitted, MessageCode.SUCCESS, "resubmit offering C over TCP");
        TeacherGradeBookDTO pending = book(resubmitted);
        long batchC2 = Long.parseLong(pending.getLastSubmissionId());
        require(batchC2 != batchC1 && "PENDING".equals(pending.getState()) && pending.getRevision() == 2,
                "the re-submission is a new batch and the book is PENDING at revision 2 (observed "
                        + batchC1 + "->" + batchC2 + "/" + pending.getState() + ")");
        require(number("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + batchC2
                        + " AND version=2 AND submission_kind='RESUBMISSION' AND base_submission_id="
                        + batchC1 + " AND total_count=2 AND failed_count=0") == 1
                        && number("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + batchC1 + " AND enrollment_id=" + ENROLLMENT_C1 + " AND score=70.00") == 1
                        && number("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + batchC1 + " AND status='REJECTED'") == 1,
                "the resubmission carries its base batch and the rejected snapshot stays intact");

        requireCode(review(admin, adminToken, op(9), batchC2, 2, true, null), MessageCode.SUCCESS,
                "approve the resubmission over TCP");
        // 这两名学生本来就有已发布成绩：审批必须把它**更新**掉（每个选课记录仍然只有一行），
        // 而不是再插一行，也不是留着旧值。
        require(number("SELECT COUNT(*) FROM grade WHERE enrollment_id IN (" + ENROLLMENT_C1 + ","
                        + ENROLLMENT_C2 + ")") == 2
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT_C1
                        + " AND score=90.00 AND grade_point=4.0 AND is_published=1"
                        + " AND daily_score=90.00 AND experiment_score=90.00") == 1
                        && number("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + ENROLLMENT_C2
                        + " AND score=60.00 AND grade_point=1.0 AND is_published=1") == 1,
                "the approved resubmission must upsert the published projection, not add a row");
        GradeRecordDTO recordC = recordFor(grades(student, studentToken), "TGE983C");
        require(recordC != null && close(recordC.getScore(), 90.0) && close(recordC.getGradePoint(), 4.0),
                "the student sees the corrected version only after it is approved");

        // A fresh decision on the superseded (rejected) version must be refused and change nothing.
        String publishedBefore = publishedSnapshot();
        Message refused = review(admin, adminToken, op(10), batchC1, 1, true, null);
        requireCode(refused, MessageCode.CONFLICT,
                "an approval of the superseded rejected version is refused");
        require(refused.getMessage() != null && refused.getMessage().contains("已被处理")
                        && refused.getData("latest") != null,
                "the refusal names the settled state and carries the latest detail (observed "
                        + refused.getMessage() + ")");
        require(publishedBefore.equals(publishedSnapshot()),
                "a refused approval leaves every published projection byte-identical");
        require(number("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(10) + "'") == 0,
                "a refused approval writes no audit row");
    }

    // ------------------------------------------------------------ final reads

    private static void verifyTeacherStillSeesTheDecidedBatches(JsonLineClient teacher, String token,
                                                                long batchA) throws IOException {
        TeacherGradeBookDTO bookA = book(save(teacher, token, TeacherCourseActions.GET_GRADE_BOOK,
                Map.of("offeringId", Long.toString(OFFERING_A))));
        require("APPROVED".equals(bookA.getState()) && !bookA.isCanEdit()
                        && Long.toString(batchA).equals(bookA.getLastSubmissionId()),
                "the approved batch stays decided after the other chains ran");
        require(bookA.getReviewComment() == null
                        && bookA.getRows().stream().filter(row -> row.getTotalScore() != null)
                        .count() == 13,
                "the thirteen decided rows still preview their total (the newcomer has none)");
    }

    // -------------------------------------------------------------- assertions

    /** The snapshot the administrator reads back must be the scheme this run submitted. */
    private static void requireSnapshotMatchesA(GradeSubmissionDetailDTO detail) {
        require(detail.getSchemeSnapshot() != null
                        && detail.getSchemeSnapshot().getComponents().size() == 4,
                "the detail exposes the captured scheme");
        Map<GradeComponentCodeDTO, GradeComponentDTO> captured = new LinkedHashMap<>();
        for (GradeComponentDTO component : detail.getSchemeSnapshot().getComponents()) {
            captured.put(component.getCode(), component);
        }
        require(component(captured, GradeComponentCodeDTO.DAILY, true, 4000)
                        && component(captured, GradeComponentCodeDTO.MIDTERM, true, 2000)
                        && component(captured, GradeComponentCodeDTO.EXPERIMENT, false, 0)
                        && component(captured, GradeComponentCodeDTO.FINALTERM, true, 4000),
                "the captured scheme is exactly the 4000/2000/0(disabled)/4000 the teacher sent");
    }

    private static boolean component(Map<GradeComponentCodeDTO, GradeComponentDTO> components,
                                     GradeComponentCodeDTO code, boolean enabled, int weight) {
        GradeComponentDTO component = components.get(code);
        return component != null && component.isEnabled() == enabled
                && component.getWeightBasisPoints() == weight;
    }

    /** Every captured item must recompute from the captured scheme to its stored total and point. */
    private static void requireBandsMatchA(GradeSubmissionDetailDTO detail) {
        List<String> uids = new ArrayList<>();
        for (int index = 0; index < detail.getItems().size(); index++) {
            GradeSubmissionItemDTO item = detail.getItems().get(index);
            uids.add(item.getStudentUid());
            require(item.getDailyScore() != null && item.getMidtermScore() != null
                            && item.getFinaltermScore() != null
                            && item.getExperimentScore() == null
                            && close(item.getScore(), Double.parseDouble(BAND_SCORES[index]))
                            && close(item.getGradePoint(), Double.parseDouble(BAND_POINTS[index]))
                            && item.getGradeLevel() == null,
                    "item " + item.getStudentUid() + " carries band " + (index + 1)
                            + " (observed " + item.getScore() + "/" + item.getGradePoint() + "/"
                            + item.getExperimentScore() + ")");
        }
        require(uids.equals(List.of(studentUid(1), studentUid(2), studentUid(3), studentUid(4),
                        studentUid(5), studentUid(6), studentUid(7), studentUid(8), studentUid(9),
                        studentUid(10), studentUid(11), studentUid(12), studentUid(13))),
                "items are ordered by student UID (observed " + uids + ")");
    }

    // --------------------------------------------------------------- fixtures

    private static void insertFixtures() throws Exception {
        StringBuilder users = new StringBuilder("INSERT INTO tbl_user(UID,name,password,salt,role,"
                + "college,major) VALUES('" + ADMIN + "','" + ADMIN_NAME + "',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',0,'Administration','Registrar'),('"
                + TEACHER + "','" + TEACHER_NAME + "',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',1,'Engineering','Professor')");
        for (int index = 1; index <= 14; index++) {
            users.append(",('").append(studentUid(index)).append("','Tge983 Student ")
                    .append(String.format("%02d", index)).append("',"
                            + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                            + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        }
        users.append(",('").append(studentUid(31)).append("','Tge983 Student 31',"
                + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS'),('")
                .append(studentUid(32)).append("','Tge983 Student 32',"
                        + "'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=',"
                        + "'Y291cnNlLXRlc3Qtc2FsdC12MQ==',2,'Engineering','CS')");
        execute(users.toString());
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_A + ",'TGE983A','Teacher Grade E2E A',"
                + "3.00,48,1,'ACTIVE'),(" + COURSE_B + ",'TGE983B','Teacher Grade E2E B',"
                + "1.50,24,1,'ACTIVE'),(" + COURSE_C + ",'TGE983C','Teacher Grade E2E C',"
                + "2.00,32,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_A + ",'TGE983-A'," + COURSE_A + ","
                + YEAR + "," + SEMESTER + ",40,2),(" + OFFERING_B + ",'TGE983-B'," + COURSE_B + ","
                + YEAR + "," + SEMESTER + ",40,2),(" + OFFERING_C + ",'TGE983-C'," + COURSE_C + ","
                + YEAR + "," + SEMESTER + ",40,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_A
                + ",'" + TEACHER + "',0),(" + OFFERING_B + ",'" + TEACHER + "',0),(" + OFFERING_C
                + ",'" + TEACHER + "',0)");
        StringBuilder enrollments = new StringBuilder("INSERT INTO enrollment(enrollment_id,"
                + "offering_id,course_id,academic_year,semester,uid,status,select_time) VALUES");
        for (int index = 1; index <= 13; index++) {
            if (index > 1) enrollments.append(',');
            enrollments.append('(').append(ENROLLMENT_A_FIRST + index - 1).append(',')
                    .append(OFFERING_A).append(',').append(COURSE_A).append(',').append(YEAR)
                    .append(',').append(SEMESTER).append(",'").append(studentUid(index))
                    .append("',2,'2027-02-01 00:00:00')");
        }
        enrollments.append(",(").append(ENROLLMENT_B).append(',').append(OFFERING_B).append(',')
                .append(COURSE_B).append(',').append(YEAR).append(',').append(SEMESTER).append(",'")
                .append(studentUid(1)).append("',2,'2027-02-01 00:00:00'),(")
                .append(ENROLLMENT_C1).append(',').append(OFFERING_C).append(',').append(COURSE_C)
                .append(',').append(YEAR).append(',').append(SEMESTER).append(",'")
                .append(studentUid(31)).append("',2,'2027-02-01 00:00:00'),(")
                .append(ENROLLMENT_C2).append(',').append(OFFERING_C).append(',').append(COURSE_C)
                .append(',').append(YEAR).append(',').append(SEMESTER).append(",'")
                .append(studentUid(32)).append("',2,'2027-02-01 00:00:00')");
        execute(enrollments.toString());
        // Offering C starts with an already published grade (an earlier version of this course), so
        // "a rejection never clears a published grade" has something real to leave intact.
        execute("INSERT INTO grade(enrollment_id,daily_score,midterm_score,experiment_score,"
                + "finalterm_score,score,grade_level,grade_point,is_published,publish_time) VALUES("
                + ENROLLMENT_C1 + ",80.00,75.00,NULL,70.00,75.00,2,2.5,1,'2027-02-02 08:00:00'),("
                + ENROLLMENT_C2 + ",40.00,45.00,NULL,38.00,40.00,NULL,0.0,1,"
                + "'2027-02-02 08:00:00')");
    }

    /** Deletion is scoped to this test's own ranges and UID prefix, never to a shared id space. */
    private static void cleanup() throws Exception {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid='" + ADMIN + "'");
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid='" + TEACHER + "'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid='" + TEACHER + "'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM grade WHERE enrollment_id IN (SELECT enrollment_id FROM enrollment"
                + " WHERE offering_id BETWEEN 983200 AND 983299)");
        execute("DELETE FROM grade_submission_item WHERE submission_id IN (SELECT submission_id"
                + " FROM grade_submission WHERE offering_id BETWEEN 983200 AND 983299)");
        execute("DELETE FROM grade_submission WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 983200 AND 983299");
        execute("DELETE FROM course WHERE course_id BETWEEN 983100 AND 983199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    // ---------------------------------------------------------------- helpers

    private static String studentUid(int index) {
        return PREFIX + "s" + String.format("%02d", index);
    }

    private static String op(int value) {
        return String.format("98300000-0000-0000-0000-%012d", value);
    }

    /** A's scheme: three enabled components summing to 10000 and a disabled EXPERIMENT. */
    private static GradeSchemeDTO schemeA() {
        return new GradeSchemeDTO(List.of(
                new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 4000),
                new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 4000)));
    }

    private static GradeSchemeDTO schemeB() {
        return new GradeSchemeDTO(List.of(
                new GradeComponentDTO(GradeComponentCodeDTO.DAILY, true, 3000),
                new GradeComponentDTO(GradeComponentCodeDTO.MIDTERM, true, 2000),
                new GradeComponentDTO(GradeComponentCodeDTO.EXPERIMENT, true, 2000),
                new GradeComponentDTO(GradeComponentCodeDTO.FINALTERM, true, 3000)));
    }

    private static GradeSchemeDTO schemeC() {
        return schemeB();
    }

    /** One row per band; {@code complete} adds the last student's final-term component. */
    private static List<GradeRowInputDTO> rowsA(boolean complete) {
        List<GradeRowInputDTO> rows = new ArrayList<>();
        for (int index = 0; index < BAND_SCORES.length; index++) {
            String score = BAND_SCORES[index];
            String finalterm = !complete && index == BAND_SCORES.length - 1 ? null : score;
            rows.add(row(ENROLLMENT_A_FIRST + index, score, score, null, finalterm));
        }
        return rows;
    }

    private static GradeRowInputDTO row(long enrollmentId, String daily, String midterm,
                                        String experiment, String finalterm) {
        return new GradeRowInputDTO(Long.toString(enrollmentId), new GradeScoresDTO(
                decimal(daily), decimal(midterm), decimal(experiment), decimal(finalterm)));
    }

    private static WriteGradeBookRequestDTO write(String operationId, long offeringId,
                                                  int expectedRevision, String digest,
                                                  GradeSchemeDTO scheme,
                                                  List<GradeRowInputDTO> rows) {
        return new WriteGradeBookRequestDTO(operationId, new GradeBookContentDTO(
                Long.toString(offeringId), expectedRevision, digest, scheme, rows));
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static Message save(JsonLineClient teacher, String token, String action,
                                Map<String, Object> data) throws IOException {
        return teacher.request("courseTeacher", action, token, data);
    }

    private static Message review(JsonLineClient admin, String token, String operationId,
                                  long submissionId, int expectedVersion, boolean approved,
                                  String comment) throws IOException {
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("operationId", operationId);
        decision.put("requestId", Long.toString(submissionId));
        decision.put("expectedVersion", expectedVersion);
        decision.put("approved", approved);
        if (comment != null) decision.put("reviewComment", comment);
        return admin.admin(AdminCourseActions.REVIEW_GRADE_SUBMISSION, token,
                Map.of("request", decision));
    }

    private static GradeSubmissionDetailDTO submissionDetail(JsonLineClient admin, String token,
                                                             long submissionId) throws IOException {
        Message response = admin.admin(AdminCourseActions.GET_GRADE_SUBMISSION, token,
                Map.of("submissionId", Long.toString(submissionId)));
        requireCode(response, MessageCode.SUCCESS, "read submission " + submissionId);
        return GSON.fromJson(GSON.toJsonTree(response.getData("gradeSubmission")),
                GradeSubmissionDetailDTO.class);
    }

    private static GradeSubmissionDetailDTO entity(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return GSON.fromJson(result.get("entity"), GradeSubmissionDetailDTO.class);
    }

    private static String message(Message response) {
        JsonObject result = GSON.toJsonTree(response.getData("result")).getAsJsonObject();
        return result.has("message") && !result.get("message").isJsonNull()
                ? result.get("message").getAsString() : response.getMessage();
    }

    /** The teacher's write envelope carries the book in {@code result.value}; a read returns it directly. */
    private static TeacherGradeBookDTO book(Message response) {
        requireCode(response, MessageCode.SUCCESS, "teacher grade request");
        Object source = response.getData("gradeBook");
        if (source == null) {
            source = GSON.toJsonTree(response.getData("result")).getAsJsonObject().get("value");
        }
        require(source != null, "the response must carry a grade book");
        return GSON.fromJson(GSON.toJsonTree(source), TeacherGradeBookDTO.class);
    }

    private static List<TeacherGradeOfferingDTO> listOfferings(JsonLineClient teacher, String token)
            throws IOException {
        Message response = save(teacher, token, TeacherCourseActions.LIST_GRADE_OFFERINGS,
                Map.of("academicYear", YEAR, "semester", SEMESTER, "page", 1, "size", 20));
        requireCode(response, MessageCode.SUCCESS, "list the teacher's grade offerings");
        JsonObject page = GSON.toJsonTree(response.getData("offerings")).getAsJsonObject();
        require(page.get("totalCount").getAsInt() == 3,
                "the page must report all three fixtures as its total");
        return GSON.fromJson(page.get("items"), OFFERING_ITEMS);
    }

    private static int countOfferings(JsonLineClient teacher, String token) throws IOException {
        return listOfferings(teacher, token).size();
    }

    private static TeacherGradeOfferingDTO offeringIn(List<TeacherGradeOfferingDTO> offerings,
                                                      long offeringId) {
        for (TeacherGradeOfferingDTO offering : offerings) {
            if (Long.toString(offeringId).equals(offering.getOffering().getOfferingId())) {
                return offering;
            }
        }
        throw new AssertionError("offering " + offeringId + " is missing from the grade list");
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

    private static void requireNoRecord(GradeSummaryDTO summary, String courseCode, String when) {
        require(recordFor(summary, courseCode) == null,
                courseCode + " must stay invisible to the student " + when + " (observed "
                        + summary.getRecords().size() + " records)");
    }

    /**
     * Offering C's published precondition: the student reads the already published row (75.00 / 2.5)
     * both before the new submission and after the rejection — the fixture seeds it, the chain must
     * leave it alone.
     */
    private static void assertPublishedPremise(JsonLineClient student, String token, String when)
            throws IOException {
        GradeRecordDTO published = recordFor(grades(student, token), "TGE983C");
        require(published != null && close(published.getScore(), 75.0)
                        && close(published.getGradePoint(), 2.5)
                        && close(published.getDailyScore(), 80.0)
                        && close(published.getFinalScore(), 70.0)
                        && published.getExperimentScore() == null,
                "the offering must already have a published grade " + when + " (observed "
                        + (published == null ? "none" : published.getScore() + "/"
                        + published.getGradePoint()) + ")");
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 0.005;
    }

    private static boolean close(Double actual, double expected) {
        return actual != null && Math.abs(actual - expected) < 0.005;
    }

    /** Every published projection row of this test's offerings, in a canonical text form. */
    private static String publishedSnapshot() throws Exception {
        return text("SELECT GROUP_CONCAT(CONCAT(g.enrollment_id,'|',g.daily_score,'|',"
                + "g.midterm_score,'|',g.experiment_score,'|',g.finalterm_score,'|',g.score,'|',"
                + "g.grade_level,'|',g.grade_point,'|',g.is_published) ORDER BY g.enrollment_id"
                + " SEPARATOR ',') FROM grade g JOIN enrollment e ON e.enrollment_id=g.enrollment_id"
                + " WHERE e.offering_id BETWEEN 983200 AND 983299");
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
                "Refusing teacher grade e2e: the JDBC URL must target the guarded schema");
        require(TEST_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade e2e outside the guarded schema");
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
            Message captcha = request("user", "get_captcha", null, Map.of());
            requireCode(captcha, MessageCode.SUCCESS, "get captcha for " + uid);
            Object captchaIdValue = captcha.getData("captchaId");
            String captchaId = String.valueOf(captchaIdValue);
            Message response = request("user", "login", null,
                    Map.of("cardNo", uid, "password", PASSWORD, "role", role,
                            "captchaId", captchaId,
                            "captchaCode", CaptchaTestBridge.codeFor(captchaId)));
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
