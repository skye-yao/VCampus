package service;

import dao.TeacherCourseOperationDAO;
import dao.TeacherGradeAuditDAO;
import dao.TeacherGradeBookDAO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.TeacherGradeBookDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.WriteGradeBookRequestDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Guarded MySQL coverage for the atomic teacher submission: the whole batch in one transaction —
 * a missing enabled component is refused (and rolls the draft write back), all-zero scores submit,
 * the statistics and snapshots are recomputed and captured, the draft closes read-only behind at
 * most one PENDING batch, a student enrolled after the request was built is a conflict instead of
 * being silently absorbed, a lost response replays the committed batch without a second one, and an
 * injected failure mid-batch rolls the header, the items, the draft, the audit and the operation
 * log back together.
 *
 * <p>Fixtures live in the 948000-948999 band with the {@code tgs948-} prefix
 * ({@code TeacherGradeDraftMySqlTest} owns 947000-947999 with {@code tgb947-}), and every fixture
 * row is deleted again by {@link #cleanup()}: deletion is scoped by this test's own offerings and
 * UID prefix, never by an id range that another test's auto-increment could fall into.
 *
 * <p>The three roster digests are hard-coded constants computed outside this code base
 * ({@code printf '948401\n948402\n948403\n' | sha256sum} and friends), so the canonical form —
 * ascending normal enrollment ids, newline separated, UTF-8, lowercase hex — is pinned by an
 * independent tool and cannot drift with the implementation.
 */
public final class TeacherGradeSubmissionMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tgs948-";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T09:00:00Z"),
            ZoneOffset.UTC);
    /** The injected clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-15 09:00:00";

    private static final String TEACHER = PREFIX + "teacher";

    /** One course per offering: a student may hold only one active enrollment per course and term. */
    private static final long COURSE_A = 948101L;
    private static final long COURSE_B = 948102L;
    private static final long COURSE_C = 948103L;
    private static final long COURSE_D = 948104L;
    private static final long COURSE_E = 948105L;
    private static final long COURSE_F = 948106L;

    private static final long OFF_STATS = 948201L;
    private static final long OFF_ZERO = 948202L;
    private static final long OFF_MISS = 948203L;
    private static final long OFF_DIGEST = 948204L;
    private static final long OFF_REPLAY = 948205L;
    private static final long OFF_FAIL = 948206L;

    private static final long A1 = 948401L;
    private static final long A2 = 948402L;
    private static final long A3 = 948403L;
    /** 退课（status=3）的选课记录：提交发生之后才退课，且草稿里留着它的旧分数。 */
    private static final long A_DROPPED = 948404L;
    private static final long Z1 = 948421L;
    private static final long Z2 = 948422L;
    private static final long M1 = 948441L;
    private static final long M2 = 948442L;
    private static final long D1 = 948461L;
    private static final long D2 = 948462L;
    /** 提交请求生成之后才新增的正常选课记录。 */
    private static final long D_ADDED = 948463L;
    private static final long R1 = 948481L;
    private static final long R2 = 948482L;
    private static final long F1 = 948491L;
    private static final long F2 = 948492L;

    /** sha256 of "948401\n948402\n948403\n". */
    private static final String DIGEST_STATS =
            "69d244e39190e3c6b3b1bb8c0e9e21716693e403019b6f214e852474e7d23850";
    /** sha256 of "948421\n948422\n". */
    private static final String DIGEST_ZERO =
            "6d1b47fbfc600d21e693a207631cce23cc118f49d8bc869f51456df42a4bc85d";
    /** sha256 of "948441\n948442\n". */
    private static final String DIGEST_MISS =
            "a3bb4d4282bf2ac89fffbc1cd3118ac73ab1268537dd6fb21044332626f60a42";
    /** sha256 of "948461\n948462\n". */
    private static final String DIGEST_DIGEST_BEFORE =
            "d3bf6785af7d0c284deec066e2748d8cab509ff80554a24b2335b16202edf504";
    /** sha256 of "948461\n948462\n948463\n". */
    private static final String DIGEST_DIGEST_AFTER =
            "f0b1498d5beede059f9e03a5574ef89cbeea18c87cc4e3df62a133c870ed2524";
    /** sha256 of "948481\n948482\n". */
    private static final String DIGEST_REPLAY =
            "782ad2cf20be1a471c5f5c0106f24770c81bae819f352b9166d4a37941efc5e9";
    /** sha256 of "948491\n948492\n". */
    private static final String DIGEST_FAIL =
            "c62da3731c037b3eeea4f7a992c6e71a55fcf4877f48ffa7af1499ddb61f4752";

    private TeacherGradeSubmissionMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher grade submission test was "
                    + "not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            TeacherGradeBookService service = service(new TeacherGradeBookDAO());
            verifyMissingScoreRejectedAtomically(service);
            verifyAllZeroSubmitAndDisabledSnapshot(service);
            verifyStatisticsAndReadOnlyAfterSubmit(service);
            verifyNewStudentRaceIsNotSilentlyAbsorbed(service);
            verifyLostResponseReplay(service);
            verifyInjectedFailureRollsTheBatchBack();
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher grade submission MySQL test passed.");
    }

    // ----------------------------------------------------------- missing score

    private static void verifyMissingScoreRejectedAtomically(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO draft = service.saveDraft(TEACHER,
                request(op(1), OFF_MISS, 0, DIGEST_MISS, completeScheme(),
                        List.of(row(M1, "61.00", "71.00", "81.00", "91.00"),
                                row(M2, "62.00", "72.00", "82.00", null)))).getValue();
        require(draft.getRevision() == 1, "the draft is saved before the submit attempt");
        String operationId = op(2);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFF_MISS);

        IllegalArgumentException missing = expect(IllegalArgumentException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(operationId, OFF_MISS, 1, DIGEST_MISS, completeScheme(),
                                List.of(row(M1, "99.00", "71.00", "81.00", "91.00"),
                                        row(M2, "62.00", "72.00", "82.00", null)))),
                "a student missing an enabled component refuses the whole submission");
        require(missing.getMessage() != null && missing.getMessage().contains("缺少学生")
                        && missing.getMessage().contains(PREFIX + "s2"),
                "the refusal names the student whose score is missing (observed "
                        + missing.getMessage() + ")");

        expect(IllegalArgumentException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(op(3), OFF_MISS, 1, DIGEST_MISS, completeScheme(),
                                List.of(row(M1, "99.00", "71.00", "81.00", "91.00")))),
                "a request that omits a roster student refuses the whole submission");

        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_MISS) == 0
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE enrollment_id IN"
                        + " (" + M1 + "," + M2 + ")") == 0
                        && count(operationLog(operationId, OFF_MISS)) == 0
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER + "' AND operation_id IN ('" + operationId
                        + "','" + op(3) + "')") == 0,
                "a refused submission writes no batch, no item and no operation row");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_MISS
                        + " AND revision=1 AND draft_open=1 AND last_submission_id IS NULL") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFF_MISS + " AND enrollment_id=" + M1 + " AND daily_score=61.00") == 1,
                "the refused submission rolls the draft write back to the saved revision");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_MISS)
                        == auditBefore,
                "the refused submission writes no audit row");
    }

    // --------------------------------------------------------------- all zeros

    private static void verifyAllZeroSubmitAndDisabledSnapshot(TeacherGradeBookService service)
            throws Exception {
        GradeSchemeDTO zeroScheme = scheme(4000, 3000, null, 3000);
        TeacherGradeBookDTO draft = service.saveDraft(TEACHER,
                request(op(4), OFF_ZERO, 0, DIGEST_ZERO, completeScheme(),
                        List.of(row(Z1, "88.00", "88.00", "88.00", "88.00"),
                                row(Z2, "88.00", "88.00", "88.00", "88.00")))).getValue();
        require(draft.getRevision() == 1, "the all-zero fixture starts from a saved draft");

        TeacherOperationResultDTO<TeacherGradeBookDTO> submitted = service.submitGradeBook(TEACHER,
                request(op(5), OFF_ZERO, 1, DIGEST_ZERO, zeroScheme,
                        List.of(row(Z1, "0.00", "0.00", null, "0.00"),
                                row(Z2, "0.00", "0.00", null, "0.00"))));
        require(!submitted.isReplayed() && "成绩批次已提交".equals(submitted.getMessage()),
                "an all-zero submission is committed (observed " + submitted.getMessage() + ")");
        TeacherGradeBookDTO book = submitted.getValue();
        require("PENDING".equals(book.getState()) && !book.isCanEdit()
                        && book.getLastSubmissionId() != null && book.getRevision() == 2,
                "the submitted book reports PENDING, read-only, the batch and revision 2 (observed "
                        + book.getState() + "/" + book.isCanEdit() + "/" + book.getRevision() + ")");

        long submissionId = Long.parseLong(book.getLastSubmissionId());
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + submissionId
                        + " AND offering_id=" + OFF_ZERO + " AND version=1 AND status='PENDING'"
                        + " AND submitted_by='" + TEACHER + "' AND submitted_at='" + CLOCK_TEXT
                        + "' AND submission_kind='INITIAL' AND base_submission_id IS NULL"
                        + " AND correction_reason IS NULL AND total_count=2 AND failed_count=2"
                        + " AND average_score=0.00 AND max_score=0.00 AND min_score=0.00"
                        + " AND roster_digest='" + DIGEST_ZERO + "'"
                        + " AND JSON_EXTRACT(scheme_snapshot_json,'$.components[*].code')="
                        + "JSON_ARRAY('DAILY','MIDTERM','EXPERIMENT','FINALTERM')"
                        + " AND JSON_EXTRACT(scheme_snapshot_json,"
                        + "'$.components[*].weightBasisPoints')=JSON_ARRAY(4000,3000,0,3000)") == 1,
                "the batch header captures the scheme, the digest, the statistics and the kind");
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId + " AND daily_score=0.00 AND midterm_score=0.00"
                        + " AND finalterm_score=0.00 AND experiment_score IS NULL"
                        + " AND score=0.00 AND grade_point=0.0 AND grade_level IS NULL"
                        + " AND student_uid_snapshot='" + PREFIX + "s1'"
                        + " AND student_name_snapshot='Tgs948 Student 1'") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId) == 2,
                "zero is stored as a score exactly where the disabled component stays NULL");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_ZERO
                        + " AND revision=2 AND draft_open=0 AND draft_kind='INITIAL'"
                        + " AND last_submission_id=" + submissionId + " AND updated_by='" + TEACHER
                        + "' AND updated_at='" + CLOCK_TEXT + "'") == 1,
                "the batch closes the draft and records itself as the last submission");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_ZERO
                        + " AND experiment_score=88.00") == 2,
                "the draft keeps the disabled component's stored value");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN (" + Z1 + "," + Z2 + ")") == 0,
                "a submission never publishes a grade projection by itself");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_ZERO
                        + " AND action='submitGradeBook' AND book_revision=2"
                        + " AND enrollment_id IS NULL AND before_json IS NOT NULL"
                        + " AND after_json IS NOT NULL") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_ZERO + " AND action='submitGradeBook' AND enrollment_id IS NOT NULL")
                        == 2
                        && count(operationLog(op(5), OFF_ZERO)) == 1,
                "the submission logs its scheme change, its score changes and its operation row");
    }

    // ---------------------------------------- statistics, read-only and PENDING

    private static void verifyStatisticsAndReadOnlyAfterSubmit(TeacherGradeBookService service)
            throws Exception {
        // A draft row for an enrollment that is already dropped: it belongs to no batch.
        execute("INSERT INTO teacher_grade_draft_item(offering_id,enrollment_id,daily_score,"
                + "midterm_score,experiment_score,finalterm_score) VALUES(" + OFF_STATS + ","
                + A_DROPPED + ",88.00,88.00,88.00,88.00)");

        TeacherOperationResultDTO<TeacherGradeBookDTO> submitted = service.submitGradeBook(TEACHER,
                request(op(6), OFF_STATS, 0, DIGEST_STATS, completeScheme(),
                        List.of(row(A1, "91.00", "91.00", "91.00", "91.00"),
                                row(A2, "82.00", "82.00", "82.00", "82.00"),
                                row(A3, "55.50", "55.50", "55.50", "55.50"))));
        long submissionId = Long.parseLong(submitted.getValue().getLastSubmissionId());

        // 91.00 + 82.00 + 55.50 = 228.50; 228.50 / 3 = 76.1666... and HALF_UP to 2dp is 76.17.
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + submissionId
                        + " AND version=1 AND total_count=3 AND failed_count=1"
                        + " AND average_score=76.17 AND max_score=91.00 AND min_score=55.50") == 1,
                "the header statistics are recomputed from the captured totals (observed "
                        + text("SELECT CONCAT(average_score,'/',max_score,'/',min_score,'/',"
                        + "failed_count,'/',total_count) FROM grade_submission WHERE submission_id="
                        + submissionId) + ")");
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId + " AND enrollment_id=" + A1 + " AND score=91.00"
                        + " AND grade_point=4.0") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId + " AND enrollment_id=" + A2 + " AND score=82.00"
                        + " AND grade_point=3.0") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId + " AND enrollment_id=" + A3 + " AND score=55.50"
                        + " AND grade_point=0.0") == 1,
                "every captured item carries the total and the grade point the server recomputed");
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId) == 3
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId + " AND enrollment_id=" + A_DROPPED) == 0,
                "the batch captures the current normal roster and no dropped draft row");

        TeacherGradeBookDTO book = service.getGradeBook(TEACHER, Long.toString(OFF_STATS));
        require("PENDING".equals(book.getState()) && !book.isCanEdit()
                        && Long.toString(submissionId).equals(book.getLastSubmissionId())
                        && !book.isRosterChangedSinceSubmission(),
                "the teacher sees the batch as read-only PENDING with an unchanged roster");
        Map<Long, TeacherGradeRowDTO> rows = byEnrollment(book);
        require(decimal("91.00").compareTo(rows.get(A1).getTotalScore()) == 0
                        && decimal("4.0").compareTo(rows.get(A1).getGradePoint()) == 0,
                "the read model recomputes the same total and grade point as the batch");

        long submissions = count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_STATS);
        expect(TeacherGradeBookService.ConflictException.class,
                () -> service.saveDraft(TEACHER,
                        request(op(7), OFF_STATS, 1, DIGEST_STATS, completeScheme(),
                                List.of(row(A1, "99.00", "91.00", "91.00", "91.00")))),
                "a submitted book cannot be edited");
        TeacherGradeBookService.ConflictException pending = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(op(8), OFF_STATS, 1, DIGEST_STATS, completeScheme(),
                                List.of(row(A1, "91.00", "91.00", "91.00", "91.00"),
                                        row(A2, "82.00", "82.00", "82.00", "82.00"),
                                        row(A3, "55.50", "55.50", "55.50", "55.50")))),
                "an offering with a PENDING batch refuses a second submission");
        require(pending.getMessage() != null && pending.getMessage().contains("待审批")
                        && pending.getEntity() != null
                        && "PENDING".equals(pending.getEntity().getState()),
                "the PENDING refusal explains itself and carries the current book (observed "
                        + pending.getMessage() + ")");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_STATS)
                        == submissions
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFF_STATS + " AND enrollment_id=" + A1 + " AND daily_score=91.00") == 1
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER + "' AND operation_id IN ('" + op(7) + "','"
                        + op(8) + "')") == 0,
                "both refusals leave the batch, the frozen draft and the log untouched");
    }

    // ------------------------------------------------- new student not absorbed

    private static void verifyNewStudentRaceIsNotSilentlyAbsorbed(TeacherGradeBookService service)
            throws Exception {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + D_ADDED + "," + OFF_DIGEST + "," + COURSE_D
                + ",2026,3,'" + PREFIX + "s5',2,'2026-09-15 08:30:00')");

        TeacherGradeBookService.ConflictException raced = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(op(9), OFF_DIGEST, 0, DIGEST_DIGEST_BEFORE, completeScheme(),
                                List.of(row(D1, "70.00", "70.00", "70.00", "70.00"),
                                        row(D2, "70.00", "70.00", "70.00", "70.00")))),
                "a roster that gained a student after the request was built is a conflict");
        TeacherGradeBookDTO reloaded = raced.getEntity();
        require(reloaded != null && DIGEST_DIGEST_AFTER.equals(reloaded.getRosterDigest())
                        && reloaded.getRows().size() == 3 && reloaded.isCanEdit(),
                "the conflict hands back the reloaded roster with the new student (observed "
                        + (reloaded == null ? "null" : reloaded.getRosterDigest() + "/"
                        + reloaded.getRows().size()) + ")");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_DIGEST) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_DIGEST) == 0
                        && count(operationLog(op(9), OFF_DIGEST)) == 0,
                "the raced submission leaves no batch and no working copy behind");

        expect(IllegalArgumentException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(op(10), OFF_DIGEST, 0, DIGEST_DIGEST_AFTER, completeScheme(),
                                List.of(row(D1, "70.00", "70.00", "70.00", "70.00"),
                                        row(D2, "70.00", "70.00", "70.00", "70.00")))),
                "the new student is not silently absorbed into a batch without a score");

        TeacherOperationResultDTO<TeacherGradeBookDTO> submitted = service.submitGradeBook(TEACHER,
                request(op(11), OFF_DIGEST, 0, DIGEST_DIGEST_AFTER, completeScheme(),
                        List.of(row(D1, "70.00", "70.00", "70.00", "70.00"),
                                row(D2, "70.00", "70.00", "70.00", "70.00"),
                                row(D_ADDED, "60.00", "60.00", "60.00", "60.00"))));
        require(submitted.getValue().getRevision() == 1,
                "the reloaded submission creates the working copy at revision 1");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_DIGEST
                        + " AND version=1 AND total_count=3 AND roster_digest='"
                        + DIGEST_DIGEST_AFTER + "'") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE enrollment_id="
                        + D_ADDED) == 1,
                "the captured batch contains the new student once the teacher scored them");
    }

    // ------------------------------------------------------- lost-response replay

    private static void verifyLostResponseReplay(TeacherGradeBookService service) throws Exception {
        String operationId = op(12);
        TeacherOperationResultDTO<TeacherGradeBookDTO> first = service.submitGradeBook(TEACHER,
                request(operationId, OFF_REPLAY, 0, DIGEST_REPLAY, completeScheme(),
                        List.of(row(R1, "80.00", "80.00", "80.00", "80.00"),
                                row(R2, "60.00", "60.00", "60.00", "60.00"))));
        require(!first.isReplayed(), "the first submission is not a replay");
        String submissionId = first.getValue().getLastSubmissionId();

        TeacherOperationResultDTO<TeacherGradeBookDTO> replay = service.submitGradeBook(TEACHER,
                request(operationId, OFF_REPLAY, 0, DIGEST_REPLAY, completeScheme(),
                        List.of(row(R2, "60.0", "60", "60.00", "60.0"),
                                row(R1, "80.0", "80", "80.00", "80.0"))));
        require(replay.isReplayed() && "成绩批次已提交".equals(replay.getMessage())
                        && submissionId.equals(replay.getValue().getLastSubmissionId()),
                "the same operationId replays the committed batch (observed "
                        + replay.getMessage() + "/" + replay.getValue().getLastSubmissionId() + ")");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_REPLAY) == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + submissionId) == 2
                        && count(operationLog(operationId, OFF_REPLAY)) == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_REPLAY + " AND revision=1 AND draft_open=0") == 1,
                "a replayed submit duplicates neither the batch, the items, the revision nor the log");

        expect(TeacherGradeBookService.ConflictException.class,
                () -> service.submitGradeBook(TEACHER,
                        request(operationId, OFF_REPLAY, 0, DIGEST_REPLAY, completeScheme(),
                                List.of(row(R1, "81.00", "80.00", "80.00", "80.00"),
                                        row(R2, "60.00", "60.00", "60.00", "60.00")))),
                "the same operationId with different content is a digest conflict");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_REPLAY) == 1
                        && count(operationLog(operationId, OFF_REPLAY)) == 1,
                "the digest conflict writes no second batch");
    }

    // ------------------------------------------------------ injected rollback

    private static void verifyInjectedFailureRollsTheBatchBack() throws Exception {
        TeacherGradeBookService failing = service(new FailingSubmissionDao());
        DatabaseException failure = expect(DatabaseException.class,
                () -> failing.submitGradeBook(TEACHER,
                        request(op(13), OFF_FAIL, 0, DIGEST_FAIL, completeScheme(),
                                List.of(row(F1, "70.00", "70.00", "70.00", "70.00"),
                                        row(F2, "70.00", "70.00", "70.00", "70.00")))),
                "an injected item failure surfaces as a database failure");
        require(failure.getMessage() != null && failure.getMessage().contains("提交成绩批次"),
                "the failure names the submission transaction (observed " + failure.getMessage() + ")");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_FAIL) == 0
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE enrollment_id IN"
                        + " (" + F1 + "," + F2 + ")") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_FAIL) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFF_FAIL) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_FAIL) == 0
                        && count(operationLog(op(13), OFF_FAIL)) == 0,
                "the failed submission rolls the header, the items, the working copy, the audit and"
                        + " the operation log back together");
        TeacherGradeBookDTO virtual = failing.getGradeBook(TEACHER, Long.toString(OFF_FAIL));
        require(virtual.getRevision() == 0 && virtual.isCanEdit()
                        && virtual.getLastSubmissionId() == null,
                "the offering is back to a virtual draft after the rolled-back submission");
    }

    /** Overridable write seam: failing on the second item proves the whole batch rolls back. */
    private static final class FailingSubmissionDao extends TeacherGradeBookDAO {
        private int items;

        @Override
        public void insertSubmissionItem(Connection connection, long submissionId,
                                         SubmissionItemRow item) throws SQLException {
            if (++items == 2) throw new SQLException("injected submission item failure");
            super.insertSubmissionItem(connection, submissionId, item);
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER + "','Tgs948 Teacher','x','x',1,'Engineering','Professor'),"
                + "('" + PREFIX + "s1','Tgs948 Student 1','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s2','Tgs948 Student 2','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s3','Tgs948 Student 3','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s4','Tgs948 Student 4','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s5','Tgs948 Student 5','x','x',2,'Engineering','Student')");
        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_A
                + ",'TGS948A','Grade Submission Course A',3.00,48,1,'ACTIVE'),(" + COURSE_B
                + ",'TGS948B','Grade Submission Course B',3.00,48,1,'ACTIVE'),(" + COURSE_C
                + ",'TGS948C','Grade Submission Course C',3.00,48,1,'ACTIVE'),(" + COURSE_D
                + ",'TGS948D','Grade Submission Course D',3.00,48,1,'ACTIVE'),(" + COURSE_E
                + ",'TGS948E','Grade Submission Course E',3.00,48,1,'ACTIVE'),(" + COURSE_F
                + ",'TGS948F','Grade Submission Course F',3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFF_STATS + ",'TGS948-A'," + COURSE_A
                + ",2026,3,30,2),(" + OFF_ZERO + ",'TGS948-B'," + COURSE_B + ",2026,3,30,2),("
                + OFF_MISS + ",'TGS948-C'," + COURSE_C + ",2026,3,30,2),(" + OFF_DIGEST
                + ",'TGS948-D'," + COURSE_D + ",2026,3,30,2),(" + OFF_REPLAY + ",'TGS948-E',"
                + COURSE_E + ",2026,3,30,2),(" + OFF_FAIL + ",'TGS948-F'," + COURSE_F
                + ",2026,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFF_STATS
                + ",'" + TEACHER + "',0),(" + OFF_ZERO + ",'" + TEACHER + "',0),(" + OFF_MISS + ",'"
                + TEACHER + "',0),(" + OFF_DIGEST + ",'" + TEACHER + "',0),(" + OFF_REPLAY + ",'"
                + TEACHER + "',0),(" + OFF_FAIL + ",'" + TEACHER + "',0)");

        // Explicit enrollment ids pin the roster digests; each scenario owns its own offering.
        enroll(A1, PREFIX + "s1", OFF_STATS, COURSE_A, 2, "2026-09-14 01:00:00");
        enroll(A2, PREFIX + "s2", OFF_STATS, COURSE_A, 2, "2026-09-14 01:00:00");
        enroll(A3, PREFIX + "s3", OFF_STATS, COURSE_A, 2, "2026-09-14 01:00:00");
        enroll(A_DROPPED, PREFIX + "s4", OFF_STATS, COURSE_A, 3, "2026-09-10 01:00:00");
        enroll(Z1, PREFIX + "s1", OFF_ZERO, COURSE_B, 2, "2026-09-14 01:00:00");
        enroll(Z2, PREFIX + "s2", OFF_ZERO, COURSE_B, 2, "2026-09-14 01:00:00");
        enroll(M1, PREFIX + "s1", OFF_MISS, COURSE_C, 2, "2026-09-14 01:00:00");
        enroll(M2, PREFIX + "s2", OFF_MISS, COURSE_C, 2, "2026-09-14 01:00:00");
        enroll(D1, PREFIX + "s1", OFF_DIGEST, COURSE_D, 2, "2026-09-14 01:00:00");
        enroll(D2, PREFIX + "s2", OFF_DIGEST, COURSE_D, 2, "2026-09-14 01:00:00");
        enroll(R1, PREFIX + "s1", OFF_REPLAY, COURSE_E, 2, "2026-09-14 01:00:00");
        enroll(R2, PREFIX + "s2", OFF_REPLAY, COURSE_E, 2, "2026-09-14 01:00:00");
        enroll(F1, PREFIX + "s1", OFF_FAIL, COURSE_F, 2, "2026-09-14 01:00:00");
        enroll(F2, PREFIX + "s2", OFF_FAIL, COURSE_F, 2, "2026-09-14 01:00:00");
    }

    /**
     * 清场按“自己的教学班范围 + 自己的 UID 前缀”删除，绝不按 submission_id / enrollment_id 区间：
     * 这个受保护测试库被同一台机器上的多个串行测试共用，别的测试的自增 ID 会落进同一段数字。
     */
    private static void cleanup() throws SQLException {
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM grade WHERE enrollment_id IN (SELECT enrollment_id FROM enrollment"
                + " WHERE offering_id BETWEEN 948200 AND 948299)");
        execute("DELETE FROM grade_submission_item WHERE submission_id IN (SELECT submission_id"
                + " FROM grade_submission WHERE offering_id BETWEEN 948200 AND 948299)");
        execute("DELETE FROM grade_submission WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 948200 AND 948299");
        execute("DELETE FROM course WHERE course_id BETWEEN 948100 AND 948199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid"
                        + " LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id"
                        + " BETWEEN 948200 AND 948299") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id BETWEEN"
                        + " 948200 AND 948299") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE offering_id BETWEEN"
                        + " 948200 AND 948299") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE offering_id BETWEEN 948200"
                        + " AND 948299") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 948200 AND 948299") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 948100 AND"
                        + " 948199") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'")
                        == 0,
                "cleanup must leave no fixture row behind");
    }

    // ------------------------------------------------------------- helpers

    private static TeacherGradeBookService service(TeacherGradeBookDAO dao) {
        return new TeacherGradeBookService(dao, new TeacherGradeAuditDAO(),
                new TeacherCourseOperationDAO(), new TeacherAccessPolicy(), CLOCK);
    }

    /** 正式提交的完整方案：3000/2000/2000/3000。 */
    private static GradeSchemeDTO completeScheme() {
        return scheme(3000, 2000, 2000, 3000);
    }

    /** A scheme built from weights; {@code null} disables the component with weight 0. */
    private static GradeSchemeDTO scheme(Integer daily, Integer midterm, Integer experiment,
                                         Integer finalterm) {
        return new GradeSchemeDTO(List.of(
                component(GradeComponentCodeDTO.DAILY, daily),
                component(GradeComponentCodeDTO.MIDTERM, midterm),
                component(GradeComponentCodeDTO.EXPERIMENT, experiment),
                component(GradeComponentCodeDTO.FINALTERM, finalterm)));
    }

    private static GradeComponentDTO component(GradeComponentCodeDTO code, Integer weight) {
        return new GradeComponentDTO(code, weight != null, weight == null ? 0 : weight);
    }

    private static WriteGradeBookRequestDTO request(String operationId, long offeringId,
                                                    int expectedRevision, String rosterDigest,
                                                    GradeSchemeDTO scheme,
                                                    List<GradeRowInputDTO> rows) {
        return new WriteGradeBookRequestDTO(operationId, new GradeBookContentDTO(
                Long.toString(offeringId), expectedRevision, rosterDigest, scheme, rows));
    }

    private static GradeRowInputDTO row(long enrollmentId, String daily, String midterm,
                                        String experiment, String finalterm) {
        return new GradeRowInputDTO(Long.toString(enrollmentId), new GradeScoresDTO(
                decimal(daily), decimal(midterm), decimal(experiment), decimal(finalterm)));
    }

    private static Map<Long, TeacherGradeRowDTO> byEnrollment(TeacherGradeBookDTO book) {
        Map<Long, TeacherGradeRowDTO> rows = new HashMap<>();
        for (TeacherGradeRowDTO row : book.getRows()) {
            rows.put(Long.parseLong(row.getEnrollmentId()), row);
        }
        return rows;
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static String op(int value) {
        return UUID.fromString(String.format("94800000-0000-0000-0000-%012d", value)).toString();
    }

    private static String operationLog(String operationId, long offeringId) {
        return "SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='" + TEACHER
                + "' AND operation_id='" + operationId + "' AND action='submitGradeBook'"
                + " AND target_type='GRADE_BOOK' AND target_id='" + offeringId
                + "' AND result_code='OK' AND CHAR_LENGTH(request_digest)=64"
                + " AND response_json IS NOT NULL";
    }

    /** 已退课（status=3）的行必须同时给出 drop_time，否则 chk_enrollment_drop_time 会拒绝。 */
    private static void enroll(long enrollmentId, String uid, long offeringId, long courseId,
                               int status, String selectTime) throws SQLException {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time,drop_time) VALUES(" + enrollmentId + "," + offeringId
                + "," + courseId + ",2026,3,'" + uid + "'," + status + ",'" + selectTime + "',"
                + (status == 3 ? "'2026-09-14 02:00:00'" : "NULL") + ")");
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
        require(GUARDED_DATABASE.equals(database),
                "Refusing teacher grade submission test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade submission test outside the guarded schema");
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), "query returned no row");
            return rows.getInt(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            require(rows.next(), "query returned no row");
            return rows.getString(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static <X extends Throwable> X expect(Class<X> type, ThrowingRun action,
                                                  String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return type.cast(failure);
            throw new AssertionError(message + " (unexpected " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface ThrowingRun {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
