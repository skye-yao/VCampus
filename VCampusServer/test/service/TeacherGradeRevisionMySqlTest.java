package service;

import dao.AdminCourseOperationDAO;
import dao.GradeApprovalDAO;
import dao.TeacherCourseOperationDAO;
import dao.TeacherGradeAuditDAO;
import dao.TeacherGradeBookDAO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.teacher.GradeBookContentDTO;
import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeRowInputDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import dto.course.teacher.StartGradeRevisionRequestDTO;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Guarded MySQL coverage for the two explicit version-chain entry points —
 * {@code reopenRejectedGradeBook} (a rejected batch becomes an editable RESUBMISSION draft) and
 * {@code beginGradeCorrection} (an approved batch becomes an editable CORRECTION draft with a
 * mandatory reason).
 *
 * <p>What the suite pins: a source that is not the offering's latest batch, a source in the wrong
 * state, an expected revision that has moved, a PENDING batch, an already-open draft, a teacher who
 * is not the offering's {@code role=0} teacher and a blank correction reason are all refusals that
 * write nothing; a replayed start returns the stored result without resetting the draft the teacher
 * has already edited; two teachers racing for one offering leave exactly one start; an injected
 * failure inside the transaction rolls the copied draft, the working copy, the audit and the
 * operation log back together; the editable copy comes from the <em>frozen batch</em>, so a student
 * enrolled after submission has four NULL scores (never 0) and a student who dropped has no row at
 * all; a scheme-only change logs every student whose recomputed total or grade point actually
 * moved; the lazy reopen (a plain save against a closed REJECTED draft) and the explicit button
 * leave the same draft kind, the same base batch and the same student-level audit row, and the whole
 * version chain is walked end to end with the student-visible value read from the published
 * {@code grade} projection: v1 APPROVED → students read v1 → correction draft → still v1 → v2
 * PENDING → still v1 → v2 REJECTED → still v1 → resubmission → v3 APPROVED → students read v3 while
 * the v1/v2 snapshots stay queryable.
 *
 * <p>Fixtures live in the 949000-949999 band with the {@code tgr949-} prefix (947000-947999 is
 * {@code TeacherGradeDraftMySqlTest}, 948000-948999 is {@code TeacherGradeSubmissionMySqlTest}), and
 * every row is deleted again by {@link #cleanup()}: deletion is scoped by this test's own offerings
 * and UID prefix, never by an id range another test's auto-increment could fall into.
 *
 * <p>Roster digests are built with {@link TeacherGradeBookDAO#rosterDigest} and pinned once against
 * {@link #DIGEST_CHAIN}, a constant computed outside this code base
 * ({@code printf '949401\n949402\n' | sha256sum}), so the canonical form cannot drift silently while
 * the roster-changing fixtures stay readable.
 */
public final class TeacherGradeRevisionMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tgr949-";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T03:00:00Z"),
            ZoneOffset.UTC);
    /** The injected clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-16 03:00:00";

    private static final String TEACHER = PREFIX + "teacher";
    /** A teacher role user, but not a {@code role=0} teacher of any offering in this fixture. */
    private static final String OUTSIDER = PREFIX + "outsider";
    private static final String ADMIN = PREFIX + "admin";

    private static final String CORRECTION_REASON = "期末成绩登分错误，需要更正";
    private static final String WEIGHT_REASON = "调整权重";

    /** sha256 of "949401\n949402\n". */
    private static final String DIGEST_CHAIN =
            "493d1f45ffdf362dcd528a765447fce5c4912080222cdfc81db411c4cf8226b0";

    private static final int STUDENTS = 29;

    private static final long COURSE_INDEX = 949101L;
    private static final long OFF_CHAIN = 949201L;
    private static final long OFF_WRONG = 949202L;
    private static final long OFF_PENDING = 949203L;
    private static final long OFF_DENIED = 949204L;
    private static final long OFF_REASON = 949205L;
    private static final long OFF_REJECT = 949206L;
    private static final long OFF_REPLAY = 949207L;
    private static final long OFF_ROSTER = 949208L;
    private static final long OFF_RACE = 949209L;
    private static final long OFF_FAIL = 949210L;
    private static final long OFF_LAZY = 949211L;
    private static final long OFF_EXPLICIT = 949212L;
    private static final long OFF_WEIGHT = 949213L;

    /** Student {@code i} owns enrollment {@code 949400+i} and is enrolled in exactly one offering. */
    private static final long A1 = 949401L;
    private static final long A2 = 949402L;
    private static final long B1 = 949403L;
    private static final long B2 = 949404L;
    private static final long C1 = 949405L;
    private static final long C2 = 949406L;
    private static final long D1 = 949407L;
    private static final long D2 = 949408L;
    private static final long E1 = 949409L;
    private static final long E2 = 949410L;
    private static final long F1 = 949411L;
    private static final long F2 = 949412L;
    private static final long G1 = 949413L;
    private static final long G2 = 949414L;
    private static final long H1 = 949415L;
    /** Enrolled before v1, dropped after it: the batch keeps the row, the draft must not. */
    private static final long H_DROPPED = 949416L;
    /** Enrolled after v1: the reopened book must show four NULL scores, never 0. */
    private static final long H_ADDED = 949417L;
    private static final long I1 = 949418L;
    private static final long I2 = 949419L;
    private static final long J1 = 949420L;
    private static final long J2 = 949421L;
    /** Dropped before v1: holds an old draft row that belongs to no batch. */
    private static final long J_STRAY = 949422L;
    private static final long K1 = 949423L;
    private static final long K2 = 949424L;
    private static final long L1 = 949425L;
    private static final long L2 = 949426L;
    private static final long M1 = 949427L;
    private static final long M2 = 949428L;
    /** All four components equal 80: no reweighting of a complete scheme can move this total. */
    private static final long M_FLAT = 949429L;

    private static final int YEAR = 2026;
    private static final int SEMESTER = 3;
    /** Approvals are driven with their own operation ids, away from the fixture's op(1..99). */
    private static final AtomicInteger REVIEWS = new AtomicInteger(900);

    private TeacherGradeRevisionMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher grade revision test was "
                    + "not run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        requireDigestConvention();
        cleanup();
        insertFixtures();
        try {
            TeacherGradeBookService service = service(new TeacherGradeBookDAO());
            verifyVersionChainEndToEnd(service);
            verifyWrongSourceAndStateAreRefused(service);
            verifyPendingBatchBlocksBothEntryPoints(service);
            verifyNonOwnerTeacherIsRefused(service);
            verifyCorrectionReasonIsRequired(service);
            verifyReopenNeedsNoReason(service);
            verifyReplayedStartKeepsTheDraft(service);
            verifySnapshotCopyMergesTheCurrentRoster(service);
            verifyTwoTeachersRaceForOneStart(service);
            verifyInjectedFailureRollsTheStartBack();
            verifyLazyAndExplicitReopenAgree(service);
            verifySchemeOnlyChangeLogsEveryMovedStudent(service);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher grade revision MySQL test passed.");
    }

    // ---------------------------------------------------- the whole chain, v1 to v3

    /**
     * The brief's version chain, with the student-visible value read from the published projection
     * instead of the submission tables: a draft, a pending batch and a rejected batch never move it,
     * and only an approved successor does. The v1/v2 snapshots stay queryable throughout.
     */
    private static void verifyVersionChainEndToEnd(TeacherGradeBookService service) throws Exception {
        TeacherGradeBookDTO first = submit(service, op(1), OFF_CHAIN, 0, completeScheme(),
                List.of(row(A1, "90.00", "80.00", "70.00", "60.00"),
                        row(A2, "70.00", "60.00", "50.00", "40.00")));
        long v1 = Long.parseLong(first.getLastSubmissionId());
        require(first.getRevision() == 1 && "PENDING".equals(first.getState()),
                "the chain starts from a single-call submission at revision 1");
        require(published(A1) == null && published(A2) == null,
                "a batch that is not approved yet publishes no student-visible grade");
        approve(v1, 1, null);
        require("75.00".equals(published(A1)) && "55.00".equals(published(A2)),
                "the student reads v1 once it is approved (observed " + published(A1) + "/"
                        + published(A2) + ")");

        TeacherGradeBookDTO corrected = service.beginGradeCorrection(TEACHER,
                revision(op(2), OFF_CHAIN, v1, 1, CORRECTION_REASON)).getValue();
        require("DRAFT".equals(corrected.getState()) && corrected.isCanEdit()
                        && corrected.getRevision() == 1
                        && Long.toString(v1).equals(corrected.getBaseSubmissionId())
                        && CORRECTION_REASON.equals(corrected.getCorrectionReason()),
                "starting a correction opens the draft, keeps the revision and records base plus"
                        + " reason (observed " + corrected.getState() + "/" + corrected.getRevision()
                        + "/" + corrected.getBaseSubmissionId() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_CHAIN
                        + " AND draft_open=1 AND draft_kind='CORRECTION' AND base_submission_id=" + v1
                        + " AND revision=1 AND correction_reason='" + CORRECTION_REASON
                        + "' AND updated_by='" + TEACHER + "' AND updated_at='" + CLOCK_TEXT + "'")
                        == 1,
                "the correction draft records its kind, base, reason and teacher in one write");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_CHAIN
                        + " AND enrollment_id=" + A1 + " AND daily_score=90.00 AND midterm_score=80.00"
                        + " AND experiment_score=70.00 AND finalterm_score=60.00") == 1,
                "the editable copy carries the frozen v1 scores");
        require("75.00".equals(published(A1)),
                "a correction draft alone never touches the student's current grade");

        TeacherGradeBookDTO second = submit(service, op(3), OFF_CHAIN, 1, completeScheme(),
                List.of(row(A1, "90.00", "80.00", "70.00", "90.00"),
                        row(A2, "70.00", "60.00", "50.00", "40.00")));
        long v2 = Long.parseLong(second.getLastSubmissionId());
        require(second.getRevision() == 2 && "PENDING".equals(second.getState()),
                "the correction batch becomes the offering's second version");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + v2
                        + " AND version=2 AND status='PENDING' AND submission_kind='CORRECTION'"
                        + " AND base_submission_id=" + v1 + " AND correction_reason='"
                        + CORRECTION_REASON + "'") == 1,
                "the correction batch carries its kind, its base and its reason");
        require("75.00".equals(published(A1)),
                "a PENDING correction still leaves the student on v1");

        reject(v2, 2, "期末成绩需复核");
        require("75.00".equals(published(A1)) && "55.00".equals(published(A2)),
                "a REJECTED correction still leaves the student on v1");

        // The latest submission is now v2, so v1 is a stale source — the one place a real batch id
        // (not a fabricated one) is no longer the book's last submission.
        TeacherGradeBookService.ConflictException stale = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.reopenRejectedGradeBook(TEACHER, revision(op(4), OFF_CHAIN, v1, 2, null)),
                "a source that is no longer the last submission conflicts");
        require(stale.getMessage() != null && stale.getMessage().contains("最后一次提交")
                        && stale.getEntity() != null
                        && "REJECTED".equals(stale.getEntity().getState())
                        && "期末成绩需复核".equals(stale.getEntity().getReviewComment()),
                "the stale-source refusal carries the current book and its review comment (observed "
                        + stale.getMessage() + ")");

        TeacherGradeBookDTO reopened = service.reopenRejectedGradeBook(TEACHER,
                revision(op(5), OFF_CHAIN, v2, 2, null)).getValue();
        require("DRAFT".equals(reopened.getState()) && reopened.isCanEdit()
                        && reopened.getRevision() == 2
                        && Long.toString(v2).equals(reopened.getBaseSubmissionId())
                        && reopened.getCorrectionReason() == null,
                "the resubmission draft keeps the revision, bases itself on the rejected batch and"
                        + " carries no reason (observed " + reopened.getRevision() + "/"
                        + reopened.getBaseSubmissionId() + "/" + reopened.getCorrectionReason() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_CHAIN
                        + " AND draft_open=1 AND draft_kind='RESUBMISSION' AND base_submission_id=" + v2
                        + " AND revision=2 AND correction_reason IS NULL") == 1,
                "the resubmission clears the previous correction reason instead of inheriting it");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_CHAIN
                        + " AND enrollment_id=" + A1 + " AND finalterm_score=90.00") == 1,
                "the resubmission copy comes from the rejected batch, not from v1");
        require("75.00".equals(published(A1)), "reopening a rejected batch never moves the grade");

        TeacherGradeBookDTO third = submit(service, op(6), OFF_CHAIN, 2, completeScheme(),
                List.of(row(A1, "90.00", "80.00", "70.00", "90.00"),
                        row(A2, "70.00", "60.00", "50.00", "80.00")));
        long v3 = Long.parseLong(third.getLastSubmissionId());
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + v3
                        + " AND version=3 AND submission_kind='RESUBMISSION' AND base_submission_id="
                        + v2 + " AND correction_reason IS NULL") == 1,
                "the resubmitted batch is a RESUBMISSION of the rejected v2 without a reason");
        require("75.00".equals(published(A1)) && "55.00".equals(published(A2)),
                "a PENDING resubmission still leaves the student on v1");

        approve(v3, 3, null);
        require("84.00".equals(published(A1)) && "67.00".equals(published(A2)),
                "the student switches to v3 only once v3 is approved (observed " + published(A1)
                        + "/" + published(A2) + ")");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE offering_id=" + OFF_CHAIN) == 3
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + v1
                        + " AND status='APPROVED'") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id=" + v1)
                        == 2
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + v2
                        + " AND status='REJECTED'") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id=" + v2)
                        == 2,
                "the v1 and v2 snapshots stay queryable after v3 is published");
    }

    // ------------------------------------------------------- wrong source and state

    private static void verifyWrongSourceAndStateAreRefused(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(7), OFF_WRONG, 0, completeScheme(),
                List.of(row(B1, "80.00", "70.00", "60.00", "50.00"),
                        row(B2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFF_WRONG);

        TeacherGradeBookService.ConflictException state = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.reopenRejectedGradeBook(TEACHER,
                        revision(op(8), OFF_WRONG, v1, 1, null)),
                "only a rejected batch can be reopened");
        require(state.getMessage() != null && state.getMessage().contains("被驳回")
                        && state.getEntity() != null
                        && "APPROVED".equals(state.getEntity().getState())
                        && state.getEntity().isCanEdit() == false,
                "the wrong-state refusal names the state and carries the current book (observed "
                        + state.getMessage() + ")");

        TeacherGradeBookService.ConflictException unknown = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.beginGradeCorrection(TEACHER,
                        revision(op(9), OFF_WRONG, 999999L, 1, "改动")),
                "a source batch that is not this offering's last submission conflicts");
        require(unknown.getMessage() != null && unknown.getMessage().contains("最后一次提交")
                        && unknown.getEntity() != null,
                "the unknown-source refusal carries the current book (observed "
                        + unknown.getMessage() + ")");

        TeacherGradeBookService.ConflictException revision = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.beginGradeCorrection(TEACHER,
                        revision(op(10), OFF_WRONG, v1, 99, "改动")),
                "an expectedRevision that has moved conflicts");
        require(revision.getMessage() != null && revision.getMessage().contains("版本已变化")
                        && revision.getEntity() != null && revision.getEntity().getRevision() == 1,
                "the revision refusal carries the current book (observed "
                        + revision.getMessage() + ")");

        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_WRONG
                        + " AND draft_open=0 AND draft_kind='INITIAL' AND revision=1"
                        + " AND base_submission_id IS NULL AND correction_reason IS NULL") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_WRONG) == auditBefore
                        && count(operationLog(op(8), OFF_WRONG, "reopenRejectedGradeBook")) == 0
                        && count(operationLog(op(9), OFF_WRONG, "beginGradeCorrection")) == 0
                        && count(operationLog(op(10), OFF_WRONG, "beginGradeCorrection")) == 0,
                "every wrong-source refusal leaves the working copy, the audit and the log untouched");
    }

    // -------------------------------------------------------- a pending batch wins

    private static void verifyPendingBatchBlocksBothEntryPoints(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO pending = submit(service, op(11), OFF_PENDING, 0, completeScheme(),
                List.of(row(C1, "80.00", "70.00", "60.00", "50.00"),
                        row(C2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(pending.getLastSubmissionId());
        require("PENDING".equals(pending.getState()), "the fixture batch is PENDING");
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFF_PENDING);

        TeacherGradeBookService.ConflictException correction = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.beginGradeCorrection(TEACHER,
                        revision(op(12), OFF_PENDING, v1, 1, "改动")),
                "a PENDING batch refuses a correction");
        require(correction.getMessage() != null && correction.getMessage().contains("待审批")
                        && correction.getEntity() != null
                        && "PENDING".equals(correction.getEntity().getState()),
                "the correction refusal names the pending batch (observed "
                        + correction.getMessage() + ")");

        TeacherGradeBookService.ConflictException reopen = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.reopenRejectedGradeBook(TEACHER,
                        revision(op(13), OFF_PENDING, v1, 1, null)),
                "a PENDING batch refuses a reopen too");
        require(reopen.getMessage() != null && reopen.getMessage().contains("待审批"),
                "the reopen refusal names the pending batch (observed "
                        + reopen.getMessage() + ")");

        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_PENDING
                        + " AND draft_open=0 AND draft_kind='INITIAL' AND revision=1") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_PENDING) == auditBefore
                        && count(operationLog(op(12), OFF_PENDING, "beginGradeCorrection")) == 0
                        && count(operationLog(op(13), OFF_PENDING, "reopenRejectedGradeBook")) == 0,
                "both refusals leave the working copy, the audit and the log untouched");
    }

    // ------------------------------------------------------------------- no rights

    private static void verifyNonOwnerTeacherIsRefused(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(14), OFF_DENIED, 0, completeScheme(),
                List.of(row(D1, "80.00", "70.00", "60.00", "50.00"),
                        row(D2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);

        TeacherAccessPolicy.AccessDeniedException denied = expect(
                TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.beginGradeCorrection(OUTSIDER,
                        revision(op(15), OFF_DENIED, v1, 1, "改动")),
                "a teacher who is not this offering's role=0 teacher cannot start a correction");
        require(denied.getMessage() != null && denied.getMessage().contains("任课教师"),
                "the refusal explains the missing teaching relation (observed "
                        + denied.getMessage() + ")");

        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='"
                        + OUTSIDER + "'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_DENIED + " AND action='beginGradeCorrection'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_DENIED + " AND draft_open=0 AND draft_kind='INITIAL' AND revision=1")
                        == 1,
                "a refused non-owner writes nothing at all");
    }

    // ------------------------------------- an empty correction reason, and a second press

    private static void verifyCorrectionReasonIsRequired(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(16), OFF_REASON, 0, completeScheme(),
                List.of(row(E1, "80.00", "70.00", "60.00", "50.00"),
                        row(E2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFF_REASON);

        String[] blanks = {null, "", "   "};
        String[] operations = {op(17), op(18), op(19)};
        for (int position = 0; position < blanks.length; position++) {
            String blank = blanks[position];
            String operation = operations[position];
            IllegalArgumentException refused = expect(IllegalArgumentException.class,
                    () -> service.beginGradeCorrection(TEACHER,
                            revision(operation, OFF_REASON, v1, 1, blank)),
                    "a blank correction reason is refused");
            require(refused.getMessage() != null && refused.getMessage().contains("更正原因"),
                    "the refusal names the missing reason (observed " + refused.getMessage() + ")");
        }
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_REASON)
                        == auditBefore
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_REASON + " AND draft_open=0 AND revision=1"
                        + " AND correction_reason IS NULL") == 1
                        && count(operationLog(operations[0], OFF_REASON, "beginGradeCorrection")) == 0
                        && count(operationLog(operations[1], OFF_REASON, "beginGradeCorrection")) == 0
                        && count(operationLog(operations[2], OFF_REASON, "beginGradeCorrection")) == 0,
                "a refused reason writes neither a draft nor an audit nor a log row");

        TeacherGradeBookDTO opened = service.beginGradeCorrection(TEACHER,
                revision(op(20), OFF_REASON, v1, 1, "更正期中成绩")).getValue();
        require("DRAFT".equals(opened.getState()) && opened.isCanEdit(),
                "a correction with a reason opens the draft (observed " + opened.getState() + ")");

        // The half-finished draft is the "已打开未处理草稿" guard: a second press is a conflict,
        // not a silent reset back to the frozen batch.
        TeacherGradeBookService.ConflictException second = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.beginGradeCorrection(TEACHER,
                        revision(op(21), OFF_REASON, v1, 1, "再改一次")),
                "an already-open draft refuses a second start");
        require(second.getMessage() != null && second.getMessage().contains("已经打开")
                        && second.getEntity() != null
                        && "DRAFT".equals(second.getEntity().getState()),
                "the open-draft refusal carries the draft it refused to reset (observed "
                        + second.getMessage() + ")");
        require(count(operationLog(op(21), OFF_REASON, "beginGradeCorrection")) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_REASON + " AND action='beginGradeCorrection'") == 1,
                "the refused second press writes no log and leaves the one start behind");
    }

    // ------------------------------------------------------------- reason not needed

    private static void verifyReopenNeedsNoReason(TeacherGradeBookService service) throws Exception {
        TeacherGradeBookDTO book = submit(service, op(22), OFF_REJECT, 0, completeScheme(),
                List.of(row(F1, "80.00", "70.00", "60.00", "50.00"),
                        row(F2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        reject(v1, 1, "成绩与名单不符");

        // A blank reason is normalised to "no reason at all" and is all a resubmission needs.
        TeacherGradeBookDTO reopened = service.reopenRejectedGradeBook(TEACHER,
                revision(op(23), OFF_REJECT, v1, 1, "   ")).getValue();
        require("DRAFT".equals(reopened.getState()) && reopened.isCanEdit()
                        && reopened.getRevision() == 1
                        && Long.toString(v1).equals(reopened.getBaseSubmissionId())
                        && reopened.getCorrectionReason() == null,
                "a resubmission needs no reason and never invents one (observed "
                        + reopened.getCorrectionReason() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_REJECT
                        + " AND draft_open=1 AND draft_kind='RESUBMISSION' AND base_submission_id="
                        + v1 + " AND revision=1 AND correction_reason IS NULL") == 1,
                "the resubmission draft records its kind and base without a reason");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_REJECT
                        + " AND action='reopenRejectedGradeBook' AND enrollment_id IS NULL"
                        + " AND reason IS NULL AND book_revision=1 AND before_json IS NOT NULL"
                        + " AND after_json IS NOT NULL") == 1,
                "the start leaves a class-level trace even though no score has changed yet");
    }

    // ------------------------------------------------------------ a replayed start

    private static void verifyReplayedStartKeepsTheDraft(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(24), OFF_REPLAY, 0, completeScheme(),
                List.of(row(G1, "90.00", "80.00", "70.00", "60.00"),
                        row(G2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);
        String start = op(25);

        TeacherOperationResultDTO<TeacherGradeBookDTO> first = service.beginGradeCorrection(TEACHER,
                revision(start, OFF_REPLAY, v1, 1, "更正期末"));
        require(!first.isReplayed() && "已开始更正草稿".equals(first.getMessage()),
                "the first start is not a replay (observed " + first.getMessage() + ")");

        TeacherGradeBookDTO saved = service.saveDraft(TEACHER,
                request(op(26), OFF_REPLAY, 1, currentDigest(OFF_REPLAY), completeScheme(),
                        List.of(row(G1, "11.00", "22.00", "33.00", "44.00"),
                                row(G2, "60.00", "50.00", "40.00", "30.00")))).getValue();
        require(saved.getRevision() == 2, "the teacher's edit moves the draft to revision 2");

        TeacherOperationResultDTO<TeacherGradeBookDTO> replay = service.beginGradeCorrection(TEACHER,
                revision(start, OFF_REPLAY, v1, 1, "更正期末"));
        require(replay.isReplayed() && "已开始更正草稿".equals(replay.getMessage())
                        && replay.getValue() != null,
                "the same operationId with the same body replays the stored result (observed "
                        + replay.isReplayed() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_REPLAY
                        + " AND enrollment_id=" + G1 + " AND daily_score=11.00"
                        + " AND finalterm_score=44.00") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_REPLAY + " AND revision=2 AND draft_kind='CORRECTION'"
                        + " AND draft_open=1") == 1,
                "a replayed start neither resets the teacher's edit nor the revision");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_REPLAY
                        + " AND action='beginGradeCorrection'") == 1
                        && count(operationLog(start, OFF_REPLAY, "beginGradeCorrection")) == 1,
                "a replay writes no second audit or log row");

        TeacherGradeBookService.ConflictException digest = expect(
                TeacherGradeBookService.ConflictException.class,
                () -> service.beginGradeCorrection(TEACHER,
                        revision(start, OFF_REPLAY, v1, 1, "另一个原因")),
                "the same operationId with a different body conflicts");
        require(digest.getMessage() != null && digest.getMessage().contains("operationId"),
                "the digest conflict names the reused operationId (observed "
                        + digest.getMessage() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_REPLAY
                        + " AND action='beginGradeCorrection'") == 1
                        && count(operationLog(start, OFF_REPLAY, "beginGradeCorrection")) == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_REPLAY + " AND revision=2") == 1,
                "the digest conflict writes nothing");
    }

    // ------------------------------------------------- snapshot copy merges the roster

    private static void verifySnapshotCopyMergesTheCurrentRoster(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(27), OFF_ROSTER, 0, completeScheme(),
                List.of(row(H1, "90.00", "80.00", "70.00", "60.00"),
                        row(H_DROPPED, "50.00", "40.00", "30.00", "20.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);

        // After the batch was frozen: a new student enrols, an old one drops out.
        enroll(H_ADDED, student(17), OFF_ROSTER, COURSE_INDEX + 7, "2026-09-16 01:00:00", false);
        execute("UPDATE enrollment SET status=3, drop_time='2026-09-16 02:00:00' WHERE enrollment_id="
                + H_DROPPED);

        TeacherGradeBookDTO corrected = service.beginGradeCorrection(TEACHER,
                revision(op(28), OFF_ROSTER, v1, 1, "补录新生成绩")).getValue();
        Map<Long, TeacherGradeRowDTO> rows = byEnrollment(corrected);
        require(rows.size() == 2 && rows.containsKey(H1) && !rows.containsKey(H_DROPPED)
                        && rows.containsKey(H_ADDED),
                "the reopened book shows exactly the current normal roster (observed "
                        + rows.keySet() + ")");
        TeacherGradeRowDTO newcomer = rows.get(H_ADDED);
        require(newcomer.getScores().getDailyScore() == null
                        && newcomer.getScores().getMidtermScore() == null
                        && newcomer.getScores().getExperimentScore() == null
                        && newcomer.getScores().getFinaltermScore() == null
                        && newcomer.getTotalScore() == null,
                "a student enrolled after the submission has four NULL scores, never 0 (observed "
                        + newcomer.getScores().getDailyScore() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_ROSTER
                        + " AND enrollment_id=" + H_ADDED) == 0,
                "the newcomer gets no draft row at all, so nothing can become a silent 0");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_ROSTER
                        + " AND enrollment_id=" + H_DROPPED) == 0,
                "the student who dropped has no draft row in the reopened copy");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_ROSTER
                        + " AND enrollment_id=" + H1 + " AND daily_score=90.00"
                        + " AND finalterm_score=60.00") == 1,
                "the students still enrolled keep the frozen v1 scores");
        require("75.00".equals(published(H1)),
                "the roster churn alone never touches the published grade");
    }

    // ------------------------------------------------------------ two teachers race

    private static void verifyTwoTeachersRaceForOneStart(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(29), OFF_RACE, 0, completeScheme(),
                List.of(row(I1, "90.00", "80.00", "70.00", "60.00"),
                        row(I2, "60.00", "50.00", "40.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<TeacherOperationResultDTO<TeacherGradeBookDTO>> winner =
                new AtomicReference<>();
        AtomicReference<TeacherGradeBookService.ConflictException> loser = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Thread first = new Thread(attempt(service, op(30), OFF_RACE, v1, 1, barrier, winner, loser,
                unexpected), "revision-starter-a");
        Thread second = new Thread(attempt(service, op(31), OFF_RACE, v1, 1, barrier, winner, loser,
                unexpected), "revision-starter-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(),
                "both racing starts only ever saw a success or a conflict (" + unexpected + ")");
        require(winner.get() != null && !winner.get().isReplayed()
                        && "已开始更正草稿".equals(winner.get().getMessage()),
                "exactly one of the two racing starts commits");
        require(loser.get() != null && loser.get().getEntity() != null
                        && "DRAFT".equals(loser.get().getEntity().getState()),
                "the loser gets a conflict carrying the draft the winner opened");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_RACE
                        + " AND action='beginGradeCorrection'") == 1
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid='" + TEACHER + "' AND action='beginGradeCorrection'"
                        + " AND target_id='" + OFF_RACE + "'") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id="
                        + OFF_RACE + " AND draft_open=1 AND draft_kind='CORRECTION'"
                        + " AND base_submission_id=" + v1 + " AND revision=1") == 1,
                "the race leaves exactly one start, one audit row and one open correction draft");
    }

    private static Runnable attempt(TeacherGradeBookService service, String operationId,
                                    long offeringId, long sourceSubmissionId, long expectedRevision,
                                    CyclicBarrier barrier,
                                    AtomicReference<TeacherOperationResultDTO<TeacherGradeBookDTO>> winner,
                                    AtomicReference<TeacherGradeBookService.ConflictException> loser,
                                    List<Throwable> unexpected) {
        return () -> {
            try {
                barrier.await();
                winner.set(service.beginGradeCorrection(TEACHER, revision(operationId, offeringId,
                        sourceSubmissionId, expectedRevision, "并发开始更正")));
            } catch (TeacherGradeBookService.ConflictException conflict) {
                loser.set(conflict);
            } catch (Throwable failure) {
                synchronized (unexpected) {
                    unexpected.add(failure);
                }
            }
        };
    }

    // ------------------------------------------------------- injected rollback seam

    private static void verifyInjectedFailureRollsTheStartBack() throws Exception {
        TeacherGradeBookService service = service(new TeacherGradeBookDAO());
        // The experiment component is disabled: the batch snapshot stores NULL for it while the
        // draft keeps the teacher's value — which is exactly what "copy the snapshot" must overwrite.
        GradeSchemeDTO disabled = scheme(3000, 3000, null, 4000);
        TeacherGradeBookDTO book = submit(service, op(32), OFF_FAIL, 0, disabled,
                List.of(row(J1, "80.00", "70.00", "77.00", "60.00"),
                        row(J2, "50.00", "40.00", "66.00", "30.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id=" + v1
                        + " AND experiment_score IS NULL") == 2,
                "the frozen batch stores NULL for the disabled component");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_FAIL
                        + " AND enrollment_id IN (" + J1 + "," + J2 + ")"
                        + " AND experiment_score IS NOT NULL") == 2,
                "the draft still holds the disabled component's values");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_FAIL
                        + " AND enrollment_id=" + J_STRAY) == 1,
                "the dropped student still owns a stray draft row from before the submission");

        TeacherGradeBookService failing = service(new FailingRevisionDao());
        DatabaseException failure = expect(DatabaseException.class,
                () -> failing.beginGradeCorrection(TEACHER,
                        revision(op(33), OFF_FAIL, v1, 1, "更正实验成绩")),
                "an injected failure inside the version-change transaction is a database failure");
        require(failure.getMessage() != null && failure.getMessage().contains("版本变更"),
                "the failure names the version-change transaction (observed "
                        + failure.getMessage() + ")");

        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_FAIL
                        + " AND enrollment_id=" + J_STRAY + " AND daily_score=88.00") == 1,
                "the failed start restores the draft row it had deleted");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id=" + OFF_FAIL
                        + " AND enrollment_id=" + J1 + " AND experiment_score=77.00") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFF_FAIL + " AND enrollment_id=" + J2 + " AND experiment_score=66.00") == 1,
                "the failed start restores the scores the snapshot copy had overwritten");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_FAIL
                        + " AND draft_open=0 AND draft_kind='INITIAL' AND revision=1"
                        + " AND base_submission_id IS NULL AND correction_reason IS NULL") == 1,
                "the failed start leaves the working copy closed and unchanged");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_FAIL
                        + " AND action='beginGradeCorrection'") == 0
                        && count(operationLog(op(33), OFF_FAIL, "beginGradeCorrection")) == 0,
                "the failed start writes neither an audit nor a log row");
    }

    /** Overridable write seam: failing the book update proves the draft copy rolls back with it. */
    private static final class FailingRevisionDao extends TeacherGradeBookDAO {
        @Override
        public int reopenFromSubmission(Connection connection, long offeringId, int revision,
                                        String draftKind, long sourceSubmissionId,
                                        String correctionReason, String updatedBy,
                                        Instant updatedAt) throws SQLException {
            throw new SQLException("injected version-change failure");
        }
    }

    // ------------------------------------------- lazy reopen versus explicit reopen

    /**
     * Ruling 1: the explicit button and the lazy next-save are two doors into the same room. Both
     * leave the same draft kind, the same base batch and the same student-level audit row at the
     * same revision; the only difference is that the explicit door records the act of starting,
     * while the lazy door's trace is the save that triggered it.
     */
    private static void verifyLazyAndExplicitReopenAgree(TeacherGradeBookService service)
            throws Exception {
        long explicitSource = rejectedBatch(service, op(34), OFF_EXPLICIT, L1, L2);
        long lazySource = rejectedBatch(service, op(35), OFF_LAZY, K1, K2);

        service.reopenRejectedGradeBook(TEACHER,
                revision(op(36), OFF_EXPLICIT, explicitSource, 1, null));
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFF_EXPLICIT
                        + " AND draft_open=1 AND revision=1") == 1,
                "the explicit reopen leaves the revision for the save that follows");

        TeacherGradeBookDTO explicitSaved = service.saveDraft(TEACHER,
                request(op(37), OFF_EXPLICIT, 1, currentDigest(OFF_EXPLICIT), completeScheme(),
                        List.of(row(L1, "30.00", "80.00", "70.00", "60.00"),
                                row(L2, "50.00", "40.00", "30.00", "20.00")))).getValue();
        TeacherGradeBookDTO lazySaved = service.saveDraft(TEACHER,
                request(op(38), OFF_LAZY, 1, currentDigest(OFF_LAZY), completeScheme(),
                        List.of(row(K1, "30.00", "80.00", "70.00", "60.00"),
                                row(K2, "50.00", "40.00", "30.00", "20.00")))).getValue();
        require(explicitSaved.getRevision() == 2 && lazySaved.getRevision() == 2,
                "the save after an explicit reopen and the save that performs the lazy reopen reach"
                        + " the same revision (observed " + explicitSaved.getRevision() + "/"
                        + lazySaved.getRevision() + ")");

        String explicitShape = bookShape(OFF_EXPLICIT);
        String lazyShape = bookShape(OFF_LAZY);
        require(("2/RESUBMISSION/" + explicitSource).equals(explicitShape)
                        && ("2/RESUBMISSION/" + lazySource).equals(lazyShape),
                "both doors leave a RESUBMISSION draft based on the batch that was rejected"
                        + " (observed " + explicitShape + " / " + lazyShape + ")");
        require(bookKind(OFF_EXPLICIT).equals(bookKind(OFF_LAZY))
                        && "RESUBMISSION".equals(bookKind(OFF_LAZY)),
                "the two paths cannot diverge in the draft kind they leave behind");

        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_EXPLICIT + " AND action='saveGradeDraft' AND enrollment_id=" + L1
                        + " AND book_revision=2") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_LAZY + " AND action='saveGradeDraft' AND enrollment_id=" + K1
                        + " AND book_revision=2") == 1,
                "both paths log the same student-level change at the same revision");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_EXPLICIT + " AND action='reopenRejectedGradeBook'"
                        + " AND enrollment_id IS NULL AND reason IS NULL AND book_revision=1"
                        + " AND before_json IS NOT NULL AND after_json IS NOT NULL") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFF_LAZY + " AND action='reopenRejectedGradeBook'") == 0,
                "the explicit start is its own auditable act while the lazy reopen is traced by the"
                        + " save that triggered it");

        TeacherGradeBookDTO explicitBatch = submit(service, op(39), OFF_EXPLICIT, 2, completeScheme(),
                List.of(row(L1, "30.00", "80.00", "70.00", "60.00"),
                        row(L2, "50.00", "40.00", "30.00", "20.00")));
        TeacherGradeBookDTO lazyBatch = submit(service, op(40), OFF_LAZY, 2, completeScheme(),
                List.of(row(K1, "30.00", "80.00", "70.00", "60.00"),
                        row(K2, "50.00", "40.00", "30.00", "20.00")));
        require(("2/RESUBMISSION/" + explicitSource).equals(
                        batchShape(explicitBatch.getLastSubmissionId()))
                        && ("2/RESUBMISSION/" + lazySource).equals(
                        batchShape(lazyBatch.getLastSubmissionId())),
                "the batch that follows each path is the same RESUBMISSION of the rejected batch"
                        + " (observed " + batchShape(explicitBatch.getLastSubmissionId()) + " / "
                        + batchShape(lazyBatch.getLastSubmissionId()) + ")");
    }

    /** A batch that is submitted and then rejected, so the entry points have a legal source. */
    private static long rejectedBatch(TeacherGradeBookService service, String operationId,
                                      long offeringId, long first, long second) throws Exception {
        TeacherGradeBookDTO book = submit(service, operationId, offeringId, 0, completeScheme(),
                List.of(row(first, "90.00", "80.00", "70.00", "60.00"),
                        row(second, "50.00", "40.00", "30.00", "20.00")));
        long submission = Long.parseLong(book.getLastSubmissionId());
        reject(submission, 1, "请复核");
        return submission;
    }

    // ------------------------------------------------------ a scheme-only correction

    /**
     * A correction that changes nothing but the weights: not one component score moves, yet the
     * recomputed total (and grade point) of two students does. Those two students must be logged with
     * their before/after snapshots; the third — whose four equal components cannot move under any
     * complete scheme — must not be logged at all.
     */
    private static void verifySchemeOnlyChangeLogsEveryMovedStudent(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = submit(service, op(41), OFF_WEIGHT, 0, scheme(4000, 3000, 1000, 2000),
                List.of(row(M1, "90.00", "70.00", "60.00", "50.00"),
                        row(M2, "60.00", "80.00", "70.00", "90.00"),
                        row(M_FLAT, "80.00", "80.00", "80.00", "80.00")));
        long v1 = Long.parseLong(book.getLastSubmissionId());
        approve(v1, 1, null);

        service.beginGradeCorrection(TEACHER,
                revision(op(42), OFF_WEIGHT, v1, 1, WEIGHT_REASON));
        TeacherGradeBookDTO saved = service.saveDraft(TEACHER,
                request(op(43), OFF_WEIGHT, 1, currentDigest(OFF_WEIGHT), completeScheme(),
                        List.of(row(M1, "90.00", "70.00", "60.00", "50.00"),
                                row(M2, "60.00", "80.00", "70.00", "90.00"),
                                row(M_FLAT, "80.00", "80.00", "80.00", "80.00")))).getValue();
        require(saved.getRevision() == 2,
                "the weight-only correction reaches revision 2 without touching a score");

        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_WEIGHT
                        + " AND action='saveGradeDraft' AND enrollment_id=" + M1
                        + " AND reason='" + WEIGHT_REASON + "'"
                        + " AND JSON_EXTRACT(before_json,'$.totalScore')=73.00"
                        + " AND JSON_EXTRACT(after_json,'$.totalScore')=68.00"
                        + " AND JSON_EXTRACT(before_json,'$.gradePoint')=2.5"
                        + " AND JSON_EXTRACT(after_json,'$.gradePoint')=1.8") == 1,
                "the moved student is logged with the totals and points it actually moved between");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_WEIGHT
                        + " AND action='saveGradeDraft' AND enrollment_id=" + M2
                        + " AND JSON_EXTRACT(before_json,'$.totalScore')=73.00"
                        + " AND JSON_EXTRACT(after_json,'$.totalScore')=75.00") == 1,
                "the second moved student is logged too, even though no component score changed");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_WEIGHT
                        + " AND action='saveGradeDraft' AND enrollment_id=" + M_FLAT) == 0,
                "a student whose recomputed total cannot move gets no row");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_WEIGHT
                        + " AND action='saveGradeDraft' AND enrollment_id IS NULL AND reason='"
                        + WEIGHT_REASON + "' AND book_revision=2") == 1,
                "the class-level scheme change carries the correction reason");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id=" + OFF_WEIGHT
                        + " AND action='beginGradeCorrection' AND enrollment_id IS NULL"
                        + " AND reason='" + WEIGHT_REASON + "' AND book_revision=1") == 1,
                "starting the correction is audited with its reason before any student row exists");
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertFixtures() throws SQLException {
        StringBuilder users = new StringBuilder(
                "INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES");
        users.append("('").append(TEACHER).append("','Tgr949 Teacher','x','x',1,'Engineering','Professor'),")
                .append("('").append(OUTSIDER).append("','Tgr949 Outsider','x','x',1,'Engineering','Professor'),")
                .append("('").append(ADMIN).append("','Tgr949 Admin','x','x',0,'Administration','Registrar')");
        for (int index = 1; index <= STUDENTS; index++) {
            users.append(",('").append(student(index)).append("','Tgr949 Student ").append(index)
                    .append("','x','x',2,'Engineering','Student')");
        }
        execute(users.toString());

        StringBuilder courses = new StringBuilder("INSERT INTO course(course_id,course_code,"
                + "course_name,credit,credit_hours,course_type,status) VALUES");
        StringBuilder offerings = new StringBuilder("INSERT INTO course_offering(offering_id,"
                + "offering_code,course_id,academic_year,semester,capacity,status) VALUES");
        StringBuilder assignments = new StringBuilder(
                "INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES");
        for (int index = 0; index < 13; index++) {
            long courseId = COURSE_INDEX + index;
            long offeringId = OFF_CHAIN + index;
            courses.append(index == 0 ? "" : ",").append("(").append(courseId).append(",'TGR949")
                    .append((char) ('A' + index)).append("','Grade Revision Course ")
                    .append((char) ('A' + index)).append("',3.00,48,1,'ACTIVE')");
            offerings.append(index == 0 ? "" : ",").append("(").append(offeringId).append(",'TGR949-")
                    .append(offeringId).append("',").append(courseId).append(",").append(YEAR)
                    .append(",").append(SEMESTER).append(",30,2)");
            assignments.append(index == 0 ? "" : ",").append("(").append(offeringId).append(",'")
                    .append(TEACHER).append("',0)");
        }
        execute(courses.toString());
        execute(offerings.toString());
        execute(assignments.toString());

        // Explicit enrollment ids pin the roster digests; each offering owns its own students.
        for (int index = 0; index < 13; index++) {
            long courseId = COURSE_INDEX + index;
            long offeringId = OFF_CHAIN + index;
            int[] students = STUDENTS_OF_OFFERING[index];
            for (int position = 0; position < students.length; position++) {
                int studentIndex = students[position];
                boolean dropped = studentIndex == 22;
                enroll(A1 + studentIndex - 1, student(studentIndex), offeringId, courseId,
                        dropped ? "2026-09-15 01:00:00" : "2026-09-14 01:00:00", dropped);
            }
        }
        execute("INSERT INTO teacher_grade_draft_item(offering_id,enrollment_id,daily_score,"
                + "midterm_score,experiment_score,finalterm_score) VALUES(" + OFF_FAIL + ","
                + J_STRAY + ",88.00,88.00,88.00,88.00)");
    }

    /** Student indexes per offering, in the offering order OFF_CHAIN..OFF_WEIGHT. */
    private static final int[][] STUDENTS_OF_OFFERING = {
        {1, 2}, {3, 4}, {5, 6}, {7, 8}, {9, 10}, {11, 12}, {13, 14}, {15, 16}, {18, 19},
        {20, 21, 22}, {23, 24}, {25, 26}, {27, 28, 29},
    };

    /**
     * 清场按“自己的教学班范围 + 自己的 UID 前缀”删除，绝不按 submission_id / enrollment_id 区间：
     * 这个受保护测试库被同一台机器上的多个串行测试共用，别的测试的自增 ID 会落进同一段数字。
     */
    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM grade WHERE enrollment_id IN (SELECT enrollment_id FROM enrollment"
                + " WHERE offering_id BETWEEN 949200 AND 949299)");
        execute("DELETE FROM grade_submission_item WHERE submission_id IN (SELECT submission_id"
                + " FROM grade_submission WHERE offering_id BETWEEN 949200 AND 949299)");
        execute("DELETE FROM grade_submission WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 949200 AND 949299");
        execute("DELETE FROM course WHERE course_id BETWEEN 949100 AND 949199");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid"
                        + " LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid"
                        + " LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id"
                        + " BETWEEN 949200 AND 949299") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id BETWEEN"
                        + " 949200 AND 949299") == 0
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN (SELECT"
                        + " enrollment_id FROM enrollment WHERE offering_id BETWEEN 949200 AND"
                        + " 949299)") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE offering_id BETWEEN"
                        + " 949200 AND 949299") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE offering_id BETWEEN 949200"
                        + " AND 949299") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 949200 AND 949299") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 949100 AND"
                        + " 949199") == 0
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

    private static StartGradeRevisionRequestDTO revision(String operationId, long offeringId,
                                                        long sourceSubmissionId,
                                                        long expectedRevision, String reason) {
        return new StartGradeRevisionRequestDTO(operationId, Long.toString(offeringId),
                Long.toString(sourceSubmissionId), expectedRevision, reason);
    }

    /** One-call submission through the real service: the draft save and the batch are one write. */
    private static TeacherGradeBookDTO submit(TeacherGradeBookService service, String operationId,
                                              long offeringId, int expectedRevision,
                                              GradeSchemeDTO scheme, List<GradeRowInputDTO> rows)
            throws Exception {
        return service.submitGradeBook(TEACHER, request(operationId, offeringId, expectedRevision,
                currentDigest(offeringId), scheme, rows)).getValue();
    }

    private static AdminOperationResultDTO<GradeSubmissionDetailDTO> review(boolean approved,
                                                                            long submissionId,
                                                                            int version, String comment)
            throws Exception {
        GradeApprovalService approvals = new GradeApprovalService(new GradeApprovalDAO(),
                new AdminCourseOperationDAO(), CLOCK);
        return approvals.review(ADMIN, new ApprovalDecisionRequestDTO(
                op(REVIEWS.incrementAndGet()), Long.toString(submissionId), version, approved, false,
                null, comment));
    }

    private static void approve(long submissionId, int version, String comment) throws Exception {
        AdminOperationResultDTO<GradeSubmissionDetailDTO> result =
                review(true, submissionId, version, comment);
        require(result.getEntity() != null
                        && ApprovalStatusDTO.APPROVED == result.getEntity().getSummary().getStatus(),
                "the fixture batch must be approvable");
    }

    private static void reject(long submissionId, int version, String comment) throws Exception {
        AdminOperationResultDTO<GradeSubmissionDetailDTO> result =
                review(false, submissionId, version, comment);
        require(result.getEntity() != null
                        && ApprovalStatusDTO.REJECTED == result.getEntity().getSummary().getStatus(),
                "the fixture batch must be rejectable");
    }

    /**
     * 当前正常名单摘要，用服务端同一份定义（规范形式本身由 {@link #requireDigestConvention()}
     * 与外部工具算出的常量钉住）。
     */
    private static String currentDigest(long offeringId) throws SQLException {
        try (Connection connection = DBUtil.getConnection()) {
            return TeacherGradeBookDAO.rosterDigest(
                    new TeacherGradeBookDAO().normalEnrollmentIds(connection, offeringId));
        }
    }

    private static void requireDigestConvention() {
        require(DIGEST_CHAIN.equals(TeacherGradeBookDAO.rosterDigest(List.of(A1, A2))),
                "the roster digest canonical form must match the externally computed SHA-256");
    }

    /** 工作副本留下的形状：{@code revision/draft_kind/base_submission_id}。 */
    private static String bookShape(long offeringId) throws SQLException {
        return nullableText("SELECT CONCAT(revision,'/',draft_kind,'/',COALESCE(base_submission_id,0))"
                + " FROM teacher_grade_book WHERE offering_id=" + offeringId);
    }

    private static String bookKind(long offeringId) throws SQLException {
        return nullableText("SELECT draft_kind FROM teacher_grade_book WHERE offering_id="
                + offeringId);
    }

    /** 批次头的形状（不含批次 ID 本身）：{@code version/submission_kind/base_submission_id}。 */
    private static String batchShape(String submissionId) throws SQLException {
        return nullableText("SELECT CONCAT(version,'/',submission_kind,'/',"
                + "COALESCE(base_submission_id,0)) FROM grade_submission WHERE submission_id="
                + submissionId);
    }

    /** 学生可见的成绩：已发布投影里的总评；没有投影行说明学生还没有读到任何成绩。 */
    private static String published(long enrollmentId) throws SQLException {
        return nullableText("SELECT CAST(score AS CHAR) FROM grade WHERE enrollment_id="
                + enrollmentId + " AND is_published=1");
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

    private static String student(int index) {
        return PREFIX + "s" + index;
    }

    private static String op(int value) {
        return UUID.fromString(String.format("94900000-0000-0000-0000-%012d", value)).toString();
    }

    private static String operationLog(String operationId, long offeringId, String action) {
        return "SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='" + TEACHER
                + "' AND operation_id='" + operationId + "' AND action='" + action
                + "' AND target_type='GRADE_BOOK' AND target_id='" + offeringId
                + "' AND result_code='OK' AND CHAR_LENGTH(request_digest)=64"
                + " AND response_json IS NOT NULL";
    }

    /** 已退课（status=3）的行必须同时给出 drop_time，否则 chk_enrollment_drop_time 会拒绝。 */
    private static void enroll(long enrollmentId, String uid, long offeringId, long courseId,
                               String selectTime, boolean dropped) throws SQLException {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time,drop_time) VALUES(" + enrollmentId + "," + offeringId
                + "," + courseId + "," + YEAR + "," + SEMESTER + ",'" + uid + "',"
                + (dropped ? 3 : 2) + ",'" + selectTime + "',"
                + (dropped ? "'2026-09-15 02:00:00'" : "NULL") + ")");
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
                "Refusing teacher grade revision test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade revision test outside the guarded schema");
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
        String value = nullableText(sql);
        require(value != null, "query returned no row");
        return value;
    }

    private static String nullableText(String sql) throws SQLException {
        try (Connection connection = DBUtil.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getString(1) : null;
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
