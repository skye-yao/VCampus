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
import dto.course.teacher.TeacherGradeOfferingDTO;
import dto.course.teacher.TeacherGradeRowDTO;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guarded MySQL coverage for the teacher grade working copy: the read-only virtual draft, first
 * creation, empty draft, partial scores, disabled components keeping their draft values, the stale
 * revision and forged student rejections, the roster-digest CONFLICT, idempotent replay and digest
 * conflicts, two teachers racing to save, injected-failure rollback and the lazy reopen after a
 * rejection.
 *
 * <p>Fixtures live in the 947000-947999 band (CourseConflictMySqlTest uses 9000xx-9002xx,
 * TeacherAdjustmentConflictMySqlTest 9003xx-9008xx, ScheduleManagementMySqlTest 930xxx/940001,
 * TeacherAdjustmentApplicationMySqlTest 945000-945899, ScheduleAdjustmentApprovalMySqlTest 970xxx
 * and GradeApprovalMySqlTest 974xxx) with the {@code tgb947-} requester prefix, and every fixture
 * row is deleted again by {@link #cleanup()}. Without a {@code mysql} argument the test prints SKIP
 * and is never reported as passing.
 *
 * <p>The three roster digests are hard-coded constants computed outside this code base
 * ({@code printf '947401\n947402\n947403\n947404\n' | sha256sum} and friends), so the canonical form —
 * ascending enrollment ids, newline separated, UTF-8, lowercase hex — is pinned by an independent
 * tool and cannot drift with the implementation.
 */
public final class TeacherGradeDraftMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final String PREFIX = "tgb947-";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"),
            ZoneOffset.UTC);
    /** The injected clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-15 08:00:00";

    private static final String TEACHER_A = PREFIX + "teacher-a";
    private static final String TEACHER_B = PREFIX + "teacher-b";
    private static final String TEACHER_C = PREFIX + "teacher-c";
    private static final String ASSISTANT = PREFIX + "assistant";
    private static final String ADMIN = PREFIX + "admin";

    private static final long COURSE_MAIN = 947201L;
    private static final long COURSE_OTHER = 947202L;
    private static final long OFFERING_MAIN = 947301L;
    private static final long OFFERING_OTHER = 947302L;

    private static final long S1 = 947401L;
    private static final long S2 = 947402L;
    private static final long S3 = 947403L;
    private static final long S4 = 947404L;
    /** 退课（status=3）的选课记录：不属于正常名单，也不在名单摘要里。 */
    private static final long DROPPED = 947405L;
    /** 管理端随后新增的正常选课记录。 */
    private static final long ADDED = 947407L;
    /** 属于另一个教学班的选课记录，用来伪造越权学生。 */
    private static final long FOREIGN = 947411L;
    private static final long MISSING_ENROLLMENT = 947499L;

    private static final long SUBMISSION_REJECTED = 947501L;
    private static final long SUBMISSION_APPROVED = 947502L;

    /** sha256 of "947401\n947402\n947403\n947404\n". */
    private static final String ROSTER_DIGEST =
            "2158a84fc3431f6af20b133891f1ec17ebad7a9841c0ecca79cee2e18854c7ea";
    /** sha256 of "947401\n947402\n947403\n947404\n947407\n". */
    private static final String ROSTER_DIGEST_AFTER_ADD =
            "ac24c555c02b519325bc38f87b5df461cea4f1c70df65a9f66aa3af81b5642fe";
    /** sha256 of "947402\n947403\n947404\n947407\n". */
    private static final String ROSTER_DIGEST_AFTER_DROP =
            "7a771c05fe534dfddf2bc24d1b73c33cfecc4f9e0c6abe5725151e736c95063d";

    private TeacherGradeDraftMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean withMySql = false;
        for (String argument : args) {
            if ("mysql".equals(argument) || "--mysql".equals(argument)) withMySql = true;
        }
        if (!withMySql) {
            System.out.println("SKIP: no `mysql` argument, so the teacher grade draft test was not "
                    + "run and is NOT reported as passing. Pass -WithMySql to run it.");
            return;
        }
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            TeacherGradeBookService service = service(new TeacherGradeBookDAO());
            verifyVirtualDraftReadsOnly(service);
            verifyFirstSaveEmptyDraftAndReplay(service);
            verifyPartialScoresCountersAndList(service);
            verifyDisabledComponentKeepsDraftValues(service);
            verifyStaleRevisionConflict(service);
            verifyForgedStudentAndForeignTeacher(service);
            verifyRosterChangeConflict(service);
            verifyReplayAndDigestConflict(service);
            verifyConcurrentTeacherSaves(service);
            verifyInjectedFailureRollback();
            verifyReopenAfterRejectionAndApprovedReadOnly(service);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Teacher grade draft MySQL test passed.");
    }

    // ------------------------------------------------------- virtual draft reads

    private static void verifyVirtualDraftReadsOnly(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO book = service.getGradeBook(TEACHER_A, Long.toString(OFFERING_MAIN));
        require(book.getRevision() == 0, "an offering without a working copy reads as revision 0");
        require("DRAFT".equals(book.getState()) && book.isCanEdit(),
                "a virtual draft is editable and shows DRAFT");
        require(ROSTER_DIGEST.equals(book.getRosterDigest()),
                "the digest is the sorted normal-enrollment-id SHA-256 (observed "
                        + book.getRosterDigest() + ")");
        require(book.getLastSubmissionId() == null && book.getBaseSubmissionId() == null
                        && book.getCorrectionReason() == null,
                "a virtual draft carries no submission, base or correction reason");
        require(schemeText(defaultScheme()).equals(schemeText(book.getScheme())),
                "the server supplies the four-component default scheme (observed "
                        + schemeText(book.getScheme()) + ")");
        require(book.getRows().size() == 4, "the virtual draft lists the normal roster (observed "
                + book.getRows().size() + ")");
        for (TeacherGradeRowDTO row : book.getRows()) {
            require(blank(row.getScores()) && row.getTotalScore() == null
                            && row.getGradePoint() == null && !row.isComplete()
                            && row.getErrors().isEmpty(),
                    "a virtual draft invents no score for " + row.getStudentUid());
        }
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN)
                        == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == 0,
                "reading a missing working copy writes nothing");

        TeacherGradeBookDTO assistant = service.getGradeBook(ASSISTANT,
                Long.toString(OFFERING_MAIN));
        require(!assistant.isCanEdit() && "DRAFT".equals(assistant.getState()),
                "an assistant may read the grade book but cannot edit it");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.getGradeBook(TEACHER_C, Long.toString(OFFERING_MAIN)),
                "an unrelated teacher cannot read another offering's grade book");
        expect(IllegalArgumentException.class, () -> service.getGradeBook(TEACHER_A, "abc"),
                "a non-decimal offeringId is rejected");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.getGradeBook(TEACHER_A, "947399"), "a missing offering is denied");
    }

    // ------------------------------------------- first creation, empty draft, replay

    private static void verifyFirstSaveEmptyDraftAndReplay(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO virtual = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        String operationId = op(1);
        TeacherOperationResultDTO<TeacherGradeBookDTO> result = service.saveDraft(TEACHER_A,
                request(operationId, 0, ROSTER_DIGEST, virtual.getScheme(), List.of()));
        require(!result.isReplayed() && "成绩草稿已保存".equals(result.getMessage()),
                "a first save is not a replay (observed " + result.getMessage() + ")");
        TeacherGradeBookDTO saved = result.getValue();
        require(saved.getRevision() == 1 && "DRAFT".equals(saved.getState()) && saved.isCanEdit(),
                "the first save creates revision 1 and stays editable");
        require(saved.getRows().size() == 4
                        && saved.getRows().stream().allMatch(row -> blank(row.getScores())
                        && row.getTotalScore() == null),
                "an empty draft keeps the roster rows unscored");
        require(saved.getRosterDigest().equals(ROSTER_DIGEST), "the empty draft keeps the digest");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=1 AND draft_open=1 AND draft_kind='INITIAL'"
                        + " AND base_submission_id IS NULL AND last_submission_id IS NULL"
                        + " AND correction_reason IS NULL AND updated_by='" + TEACHER_A
                        + "' AND updated_at='" + CLOCK_TEXT + "'"
                        + " AND JSON_EXTRACT(scheme_json,'$.components[*].code')="
                        + "JSON_ARRAY('DAILY','MIDTERM','EXPERIMENT','FINALTERM')"
                        + " AND JSON_EXTRACT(scheme_json,'$.components[*].enabled')="
                        + "JSON_ARRAY(true,true,true,true)"
                        + " AND JSON_EXTRACT(scheme_json,'$.components[*].weightBasisPoints')="
                        + "JSON_ARRAY(0,0,0,0)") == 1,
                "the first save stores the canonical default scheme with the injected clock");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == 0, "an empty draft writes no item rows");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id IS NULL AND before_json IS NULL"
                        + " AND after_json IS NOT NULL AND book_revision=1 AND operation_id='"
                        + operationId + "' AND action='saveGradeDraft' AND reason IS NULL"
                        + " AND teacher_uid='" + TEACHER_A + "'") == 1,
                "the first creation logs one class-level audit row without before_json");
        require(count(operationLog(operationId)) == 1,
                "the first save logs one teacher operation row");

        TeacherOperationResultDTO<TeacherGradeBookDTO> replay = service.saveDraft(TEACHER_A,
                request(operationId, 0, ROSTER_DIGEST, virtual.getScheme(), List.of()));
        require(replay.isReplayed() && replay.getValue().getRevision() == 1,
                "the same operationId and content replays the stored response");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN)
                        == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == 1
                        && count(operationLog(operationId)) == 1,
                "a replay duplicates no working copy, audit row or operation row");
    }

    // ------------------------------------------ partial scores, counters and list

    private static void verifyPartialScoresCountersAndList(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        TeacherGradeBookDTO book = service.saveDraft(TEACHER_A,
                request(op(2), current.getRevision(), current.getRosterDigest(),
                        scheme(3000, 2000, 2000, 3000),
                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90"),
                                row(S2, "60.00", "50.00", null, "100.00"),
                                row(S3, null, null, null, null)))).getValue();
        require(book.getRevision() == 2, "a save bumps the revision to 2");
        require(book.getRosterDigest().equals(ROSTER_DIGEST),
                "saving the same roster keeps the digest");

        Map<Long, TeacherGradeRowDTO> byEnrollment = byEnrollment(book);
        TeacherGradeRowDTO first = byEnrollment.get(S1);
        require(decimal("84.77").compareTo(first.getTotalScore()) == 0
                        && decimal("3.5").compareTo(first.getGradePoint()) == 0 && first.isComplete(),
                "the server recomputes the total and grade point (observed "
                        + first.getTotalScore() + "/" + first.getGradePoint() + ")");
        require(byEnrollment.get(S2).getTotalScore() == null
                        && byEnrollment.get(S2).getGradePoint() == null
                        && !byEnrollment.get(S2).isComplete(),
                "a missing enabled component keeps the total NULl instead of counting zero");
        require(byEnrollment.get(S2).getScores().getExperimentScore() == null,
                "a missing component stays null in the response");
        require(blank(byEnrollment.get(S3).getScores())
                        && blank(byEnrollment.get(S4).getScores()),
                "students without a request row stay unscored");
        require(byEnrollment.get(S1).getErrors().isEmpty(), "an accepted row carries no row error");

        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1 + " AND daily_score=80.00"
                        + " AND midterm_score=90.00 AND experiment_score=70.00"
                        + " AND finalterm_score=95.90") == 1,
                "the entered scores are stored exactly");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == 3
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S4) == 0,
                "only the requested rows are written and the missing value stays NULL");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + S1
                        + " AND daily_score=88.50 AND score=93.00 AND grade_point=4.5"
                        + " AND grade_level=1 AND is_published=1"
                        + " AND publish_time='2026-09-01 00:00:00'") == 1,
                "a draft save never disturbs the published grade projection");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND operation_id='" + op(2) + "'"
                        + " AND enrollment_id IS NULL AND before_json IS NOT NULL"
                        + " AND after_json IS NOT NULL AND book_revision=2") == 1,
                "a weight change logs a class-level row with before and after");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND operation_id='" + op(2) + "'"
                        + " AND enrollment_id=" + S1 + " AND before_json IS NULL"
                        + " AND JSON_EXTRACT(after_json,'$.totalScore')=84.77"
                        + " AND JSON_EXTRACT(after_json,'$.gradePoint')=3.5") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND operation_id='" + op(2)
                        + "' AND enrollment_id IS NOT NULL") == 2,
                "score changes log one student-level row per changed student");

        TeacherPageDTO<TeacherGradeOfferingDTO> page =
                service.listGradeOfferings(TEACHER_A, 2026, 3, 1, 10);
        require(page.getTotalCount() == 1 && page.getItems().size() == 1
                        && page.getPage() == 1 && page.getSize() == 10,
                "the grade list shows exactly the teacher's own offering of the term");
        TeacherGradeOfferingDTO listed = page.getItems().get(0);
        require(Long.toString(OFFERING_MAIN).equals(listed.getOffering().getOfferingId())
                        && "DRAFT".equals(listed.getState()) && listed.getLastSubmissionId() == null,
                "the listed offering carries its working-copy state");
        require(listed.getEnteredCount() == 2 && listed.getMissingCount() == 3,
                "enteredCount counts any non-null score and missingCount counts incomplete rows"
                        + " (observed " + listed.getEnteredCount() + "/" + listed.getMissingCount()
                        + ")");
        require(listed.getOffering().getEnrolledCount() == 4
                        && listed.getOffering().isCanEditGrades(),
                "the embedded offering carries the roster size, not the stored counter (observed "
                        + listed.getOffering().getEnrolledCount() + ")");
        expect(IllegalArgumentException.class,
                () -> service.listGradeOfferings(TEACHER_A, 2026, 3, 0, 10),
                "page 0 is rejected");
        expect(IllegalArgumentException.class,
                () -> service.listGradeOfferings(TEACHER_A, 2026, 3, 1, 101),
                "size above 100 is rejected");
        require(service.listGradeOfferings(TEACHER_A, 2027, 3, 1, 10).getItems().isEmpty(),
                "another term lists nothing");
        require(service.listGradeOfferings(ASSISTANT, 2026, 3, 1, 10).getItems().isEmpty(),
                "an assistant owns no grade-editable offering");
    }

    // ---------------------------------- disabled components keep their draft values

    private static void verifyDisabledComponentKeepsDraftValues(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        TeacherGradeBookDTO disabled = service.saveDraft(TEACHER_A,
                request(op(3), current.getRevision(), current.getRosterDigest(),
                        scheme(3000, 3000, null, 4000),
                        List.of(row(S1, "80.00", "90.00", null, "95.90")))).getValue();
        Map<Long, TeacherGradeRowDTO> rows = byEnrollment(disabled);
        require(decimal("70.00").compareTo(rows.get(S1).getScores().getExperimentScore()) == 0,
                "disabling a component must not clear its already-entered draft value (observed "
                        + rows.get(S1).getScores().getExperimentScore() + ")");
        require(decimal("89.36").compareTo(rows.get(S1).getTotalScore()) == 0
                        && decimal("3.8").compareTo(rows.get(S1).getGradePoint()) == 0,
                "a disabled component does not participate in the total (observed "
                        + rows.get(S1).getTotalScore() + ")");
        require(decimal("60.00").compareTo(rows.get(S2).getScores().getDailyScore()) == 0
                        && rows.get(S2).getScores().getFinaltermScore() != null,
                "a student omitted from the request keeps the stored draft values");

        current = service.getGradeBook(TEACHER_A, Long.toString(OFFERING_MAIN));
        TeacherGradeBookDTO reenabled = service.saveDraft(TEACHER_A,
                request(op(4), current.getRevision(), current.getRosterDigest(),
                        scheme(3000, 2000, 2000, 3000),
                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90")))).getValue();
        TeacherGradeRowDTO first = byEnrollment(reenabled).get(S1);
        require(decimal("70.00").compareTo(first.getScores().getExperimentScore()) == 0
                        && decimal("84.77").compareTo(first.getTotalScore()) == 0,
                "re-enabling a component restores the preserved value and the original total"
                        + " (observed " + first.getTotalScore() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id IS NULL"
                        + " AND operation_id IN ('" + op(3) + "','" + op(4) + "')") == 2,
                "each weight change logs its own class-level audit row");
    }

    // ------------------------------------------------------- stale revision

    private static void verifyStaleRevisionConflict(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        int revision = current.getRevision();
        String schemeBefore = text("SELECT scheme_json FROM teacher_grade_book WHERE offering_id="
                + OFFERING_MAIN);
        int itemsBefore = count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                + OFFERING_MAIN);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFFERING_MAIN);

        TeacherGradeBookService.ConflictException conflict =
                expect(TeacherGradeBookService.ConflictException.class,
                        () -> service.saveDraft(TEACHER_A,
                                request(op(5), revision - 1, current.getRosterDigest(),
                                        scheme(3000, 2000, 2000, 3000),
                                        List.of(row(S1, "51.00", "52.00", "53.00", "54.00")))),
                        "a stale expectedRevision is refused with a conflict");
        require(conflict.getEntity() != null && conflict.getEntity().getRevision() == revision,
                "the conflict carries the reloadable current revision");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + revision) == 1,
                "a stale save does not bump the revision");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND scheme_json=CAST('" + schemeBefore + "' AS JSON)") == 1,
                "a stale save does not rewrite the scheme (observed '"
                        + text("SELECT scheme_json FROM teacher_grade_book WHERE offering_id="
                        + OFFERING_MAIN) + "' vs '" + schemeBefore + "')");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == itemsBefore
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1 + " AND daily_score=80.00") == 1,
                "a stale save writes no item rows");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == auditBefore,
                "a stale save writes no audit rows");
        require(count(operationLog(op(5))) == 0, "a stale save writes no operation row");
    }

    // -------------------------------------------- forged student and permissions

    private static void verifyForgedStudentAndForeignTeacher(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        int revision = current.getRevision();
        GradeSchemeDTO scheme = current.getScheme();
        String digest = current.getRosterDigest();
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFFERING_MAIN);
        int itemsBefore = count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                + OFFERING_MAIN);
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(6), revision, digest, scheme,
                                List.of(row(FOREIGN, "10.00", "10.00", "10.00", "10.00")))),
                "a student enrolled in another offering is rejected");
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(7), revision, digest, scheme,
                                List.of(row(DROPPED, "10.00", "10.00", "10.00", "10.00")))),
                "a dropped enrollment is not part of the normal roster");
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(8), revision, digest, scheme,
                                List.of(row(MISSING_ENROLLMENT, "10.00", "10.00", "10.00",
                                        "10.00")))),
                "a nonexistent enrollment is rejected");
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(9), revision, digest, scheme,
                                List.of(row(S1, "101.00", "0.00", "0.00", "0.00")))),
                "a score outside 0..100 is rejected");
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(10), revision, digest, scheme,
                                List.of(row(S1, "88.555", "0.00", "0.00", "0.00")))),
                "a score with more than two decimals is rejected instead of being rounded");
        expect(IllegalArgumentException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(11), revision, digest, scheme,
                                List.of(row(S1, "1.00", "1.00", "1.00", "1.00"),
                                        row(S1, "2.00", "2.00", "2.00", "2.00")))),
                "the same student twice in one save is rejected");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.saveDraft(TEACHER_C,
                        request(op(12), revision, digest, scheme,
                                List.of(row(S1, "10.00", "10.00", "10.00", "10.00")))),
                "a teacher of another offering cannot save");
        expect(TeacherAccessPolicy.AccessDeniedException.class,
                () -> service.saveDraft(ASSISTANT,
                        request(op(13), revision, digest, scheme,
                                List.of(row(S1, "10.00", "10.00", "10.00", "10.00")))),
                "an assistant cannot save");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + revision) == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == itemsBefore
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id IN (" + FOREIGN + "," + DROPPED + ","
                        + MISSING_ENROLLMENT + ")") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1 + " AND daily_score=80.00") == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == auditBefore
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid LIKE '" + PREFIX + "%' AND operation_id IN ('" + op(6)
                        + "','" + op(7) + "','" + op(8) + "','" + op(9) + "','" + op(10) + "','"
                        + op(11) + "','" + op(12) + "','" + op(13) + "')") == 0,
                "every rejected save leaves the working copy, items, audit and log untouched");
    }

    // ---------------------------------------------------- roster digest CONFLICT

    private static void verifyRosterChangeConflict(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        int revision = current.getRevision();

        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,"
                + "semester,uid,status,select_time) SELECT " + ADDED
                + ",offering_id,course_id,academic_year,semester,'" + PREFIX
                + "s7',2,'2026-09-15 07:00:00' FROM course_offering WHERE offering_id="
                + OFFERING_MAIN);
        TeacherGradeBookService.ConflictException added =
                expect(TeacherGradeBookService.ConflictException.class,
                        () -> service.saveDraft(TEACHER_A,
                                request(op(14), revision, ROSTER_DIGEST, current.getScheme(),
                                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90")))),
                        "a roster that gained a student is a conflict");
        require(added.getEntity() != null
                        && ROSTER_DIGEST_AFTER_ADD.equals(added.getEntity().getRosterDigest())
                        && added.getEntity().getRows().size() == 5
                        && added.getEntity().getRevision() == revision
                        && added.getEntity().isCanEdit(),
                "the conflict hands back the reloadable roster, its digest and the unchanged"
                        + " revision (observed " + added.getEntity().getRosterDigest() + "/"
                        + added.getEntity().getRows().size() + " rows)");
        require(byEnrollment(added.getEntity()).containsKey(ADDED),
                "the reloaded roster already contains the new student");

        TeacherOperationResultDTO<TeacherGradeBookDTO> merged = service.saveDraft(TEACHER_A,
                request(op(15), revision, ROSTER_DIGEST_AFTER_ADD, current.getScheme(),
                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90"),
                                row(ADDED, "70.00", "70.00", "70.00", "70.00"))));
        require(merged.getValue().getRevision() == revision + 1
                        && merged.getValue().getRows().size() == 5,
                "the merged save commits against the reloaded digest");

        execute("UPDATE enrollment SET status=3,drop_time='2026-09-15 07:30:00' WHERE"
                + " enrollment_id=" + S1);
        TeacherGradeBookDTO beforeDrop = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        require(beforeDrop.getRows().size() == 4, "the dropped student leaves the roster view");
        TeacherGradeBookService.ConflictException removed =
                expect(TeacherGradeBookService.ConflictException.class,
                        () -> service.saveDraft(TEACHER_A,
                                request(op(16), beforeDrop.getRevision(), ROSTER_DIGEST_AFTER_ADD,
                                        beforeDrop.getScheme(),
                                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90")))),
                        "a roster that lost a student is a conflict");
        require(ROSTER_DIGEST_AFTER_DROP.equals(removed.getEntity().getRosterDigest())
                        && removed.getEntity().getRows().size() == 4
                        && !byEnrollment(removed.getEntity()).containsKey(S1),
                "the conflict reports the new digest and the shrunken roster (observed "
                        + removed.getEntity().getRosterDigest() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1) == 1,
                "the dropped student's draft row is preserved, not deleted");
        execute("UPDATE enrollment SET status=2,drop_time=NULL WHERE enrollment_id=" + S1);
    }

    // -------------------------------------------------- replay and digest conflict

    private static void verifyReplayAndDigestConflict(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO current = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        String operationId = op(17);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFFERING_MAIN);
        TeacherOperationResultDTO<TeacherGradeBookDTO> first = service.saveDraft(TEACHER_A,
                request(operationId, current.getRevision(), current.getRosterDigest(),
                        current.getScheme(),
                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90"))));
        require(!first.isReplayed() && first.getValue().getRevision() == current.getRevision() + 1,
                "a fresh operationId commits and bumps the revision");

        TeacherOperationResultDTO<TeacherGradeBookDTO> replay = service.saveDraft(TEACHER_A,
                request(operationId, current.getRevision(), current.getRosterDigest(),
                        current.getScheme(),
                        List.of(row(S1, "80.00", "90.00", "70.00", "95.90"))));
        require(replay.isReplayed()
                        && replay.getValue().getRevision() == first.getValue().getRevision(),
                "the same operationId replays the stored response even though its revision is now"
                        + " stale");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + first.getValue().getRevision()) == 1
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN + " AND operation_id='" + operationId + "'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == auditBefore
                        && count(operationLog(operationId)) == 1,
                "saving scores that did not change logs no audit row, and a replay duplicates"
                        + " neither the revision nor the operation row");

        expect(TeacherGradeBookService.ConflictException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(operationId, current.getRevision(), current.getRosterDigest(),
                                current.getScheme(),
                                List.of(row(S1, "80.00", "90.00", "70.00", "95.91")))),
                "the same operationId with different content is a digest conflict");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1 + " AND finalterm_score=95.90")
                        == 1
                        && count(operationLog(operationId)) == 1,
                "a digest conflict writes nothing");
    }

    // ------------------------------------------------- two teachers saving at once

    private static void verifyConcurrentTeacherSaves(TeacherGradeBookService service)
            throws Exception {
        TeacherGradeBookDTO forA = service.getGradeBook(TEACHER_A, Long.toString(OFFERING_MAIN));
        TeacherGradeBookDTO forB = service.getGradeBook(TEACHER_B, Long.toString(OFFERING_MAIN));
        require(forA.getRevision() == forB.getRevision()
                        && forA.getRosterDigest().equals(forB.getRosterDigest()),
                "both role=0 teachers see the same working copy");
        int revision = forA.getRevision();
        List<WriteGradeBookRequestDTO> writes = List.of(
                request(op(18), revision, forA.getRosterDigest(), forA.getScheme(),
                        List.of(row(S1, "90.00", "90.00", "90.00", "90.00"))),
                request(op(19), revision, forB.getRosterDigest(), forB.getScheme(),
                        List.of(row(S1, "10.00", "10.00", "10.00", "10.00"))));
        String[] teachers = {TEACHER_A, TEACHER_B};

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<TeacherOperationResultDTO<TeacherGradeBookDTO>> committed =
                Collections.synchronizedList(new ArrayList<>());
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            int slot = index;
            threads.add(new Thread(() -> {
                try {
                    barrier.await();
                    committed.add(service.saveDraft(teachers[slot], writes.get(slot)));
                } catch (TeacherGradeBookService.ConflictException expected) {
                    conflicts.incrementAndGet();
                } catch (Throwable other) {
                    unexpected.add(other);
                }
            }, "grade-save-" + slot));
        }
        for (Thread thread : threads) thread.start();
        for (Thread thread : threads) thread.join();

        require(unexpected.isEmpty(),
                "a concurrent save must not surface a driver error (" + unexpected + ")");
        require(committed.size() == 1 && conflicts.get() == 1,
                "one concurrent save wins and the other is refused with a conflict (observed "
                        + committed.size() + " commits / " + conflicts.get() + " conflicts)");
        require(committed.get(0).getValue().getRevision() == revision + 1,
                "the winner bumps the revision exactly once");
        TeacherGradeRowDTO stored = byEnrollment(service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN))).get(S1);
        boolean allNinety = decimal("90.00").compareTo(stored.getScores().getDailyScore()) == 0
                && decimal("90.00").compareTo(stored.getScores().getFinaltermScore()) == 0;
        boolean allTen = decimal("10.00").compareTo(stored.getScores().getDailyScore()) == 0
                && decimal("10.00").compareTo(stored.getScores().getFinaltermScore()) == 0;
        require(allNinety || allTen,
                "the stored scores come from exactly one of the two requests, never a mixture"
                        + " (observed " + stored.getScores().getDailyScore() + "/"
                        + stored.getScores().getFinaltermScore() + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN)
                        == 1
                        && count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE"
                        + " teacher_uid LIKE '" + PREFIX + "%' AND operation_id IN ('" + op(18)
                        + "','" + op(19) + "')") == 1,
                "the race leaves one working copy and one committed operation row");
    }

    // ------------------------------------------------- injected failure rollback

    private static void verifyInjectedFailureRollback() throws Exception {
        TeacherGradeBookService failing = service(new FailingItemDao());
        TeacherGradeBookDTO current = failing.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        int revision = current.getRevision();
        String schemeBefore = text("SELECT scheme_json FROM teacher_grade_book WHERE offering_id="
                + OFFERING_MAIN);
        String updatedByBefore = text("SELECT updated_by FROM teacher_grade_book WHERE offering_id="
                + OFFERING_MAIN);
        int itemsBefore = count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                + OFFERING_MAIN);
        int auditBefore = count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                + OFFERING_MAIN);
        String firstRowBefore = text("SELECT CONCAT(daily_score,'/',midterm_score,'/',"
                + "experiment_score,'/',finalterm_score) FROM teacher_grade_draft_item WHERE"
                + " offering_id=" + OFFERING_MAIN + " AND enrollment_id=" + S1);

        expect(DatabaseException.class,
                () -> failing.saveDraft(TEACHER_A,
                        request(op(20), revision, current.getRosterDigest(),
                                scheme(2500, 2500, 2500, 2500),
                                List.of(row(S1, "11.00", "11.00", "11.00", "11.00")))),
                "an injected item failure surfaces as a database failure");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + revision + " AND scheme_json=CAST('" + schemeBefore
                        + "' AS JSON) AND updated_by='" + updatedByBefore + "'") == 1,
                "the failed save rolls the book revision and scheme back");
        require(count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN) == itemsBefore
                        && firstRowBefore.equals(text("SELECT CONCAT(daily_score,'/',midterm_score,"
                        + "'/',experiment_score,'/',finalterm_score) FROM"
                        + " teacher_grade_draft_item WHERE offering_id=" + OFFERING_MAIN
                        + " AND enrollment_id=" + S1))
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND daily_score=11.00") == 0,
                "the failed save rolls the item writes back (observed "
                        + text("SELECT CONCAT(daily_score,'/',midterm_score,'/',experiment_score,"
                        + "'/',finalterm_score) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND enrollment_id=" + S1) + " vs " + firstRowBefore
                        + ")");
        require(count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE offering_id="
                        + OFFERING_MAIN) == auditBefore
                        && count(operationLog(op(20))) == 0,
                "the failed save rolls the audit rows and the operation log back");
    }

    /** Overridable write hook proves the whole save rolls back, not only the book update. */
    private static final class FailingItemDao extends TeacherGradeBookDAO {
        @Override
        public void upsertItem(Connection connection, long offeringId, long enrollmentId,
                               GradeScoresDTO scores) throws SQLException {
            throw new SQLException("injected draft item failure");
        }
    }

    // ---------------------------------- reopen after rejection, read-only approved

    private static void verifyReopenAfterRejectionAndApprovedReadOnly(
            TeacherGradeBookService service) throws Exception {
        execute("UPDATE teacher_grade_book SET draft_open=0,draft_kind='INITIAL',"
                + "base_submission_id=NULL,last_submission_id=" + SUBMISSION_REJECTED
                + ",correction_reason=NULL WHERE offering_id=" + OFFERING_MAIN);
        TeacherGradeBookDTO rejected = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        require("REJECTED".equals(rejected.getState()) && rejected.isCanEdit(),
                "a rejected batch shows REJECTED and stays editable");
        require(rejected.isRosterChangedSinceSubmission(),
                "the submitted roster differs from the current one");
        require(Long.toString(SUBMISSION_REJECTED).equals(rejected.getLastSubmissionId()),
                "the rejected submission is reported as the last one");
        int revision = rejected.getRevision();

        TeacherOperationResultDTO<TeacherGradeBookDTO> reopened = service.saveDraft(TEACHER_A,
                request(op(21), revision, rejected.getRosterDigest(), rejected.getScheme(),
                        List.of()));
        require(reopened.getValue().getRevision() == revision + 1
                        && "DRAFT".equals(reopened.getValue().getState())
                        && reopened.getValue().isCanEdit(),
                "saving a rejected book reopens the draft in the same transaction");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + (revision + 1) + " AND draft_open=1"
                        + " AND draft_kind='RESUBMISSION' AND base_submission_id="
                        + SUBMISSION_REJECTED + " AND last_submission_id=" + SUBMISSION_REJECTED
                        + " AND updated_by='" + TEACHER_A + "'") == 1,
                "the reopen flips draft_open, marks RESUBMISSION and keeps the rejected batch as"
                        + " the base");

        execute("INSERT INTO grade_submission_item(submission_id,enrollment_id,"
                + "student_uid_snapshot,student_name_snapshot) VALUES"
                + "(" + SUBMISSION_APPROVED + "," + S1 + ",'" + PREFIX + "s1','Tgb947 Student 1'),"
                + "(" + SUBMISSION_APPROVED + "," + S2 + ",'" + PREFIX + "s2','Tgb947 Student 2'),"
                + "(" + SUBMISSION_APPROVED + "," + S3 + ",'" + PREFIX + "s3','Tgb947 Student 3'),"
                + "(" + SUBMISSION_APPROVED + "," + S4 + ",'" + PREFIX + "s4','Tgb947 Student 4'),"
                + "(" + SUBMISSION_APPROVED + "," + ADDED + ",'" + PREFIX
                + "s7','Tgb947 Student 7')");
        execute("UPDATE teacher_grade_book SET draft_open=0,draft_kind='INITIAL',"
                + "base_submission_id=NULL,last_submission_id=" + SUBMISSION_APPROVED
                + " WHERE offering_id=" + OFFERING_MAIN);
        TeacherGradeBookDTO approved = service.getGradeBook(TEACHER_A,
                Long.toString(OFFERING_MAIN));
        require("APPROVED".equals(approved.getState()) && !approved.isCanEdit(),
                "an approved batch is read-only");
        require(!approved.isRosterChangedSinceSubmission(),
                "an approved batch whose roster still matches reports no change");
        int approvedRevision = approved.getRevision();
        String firstRowBefore = text("SELECT CONCAT(daily_score,'/',midterm_score,'/',"
                + "experiment_score,'/',finalterm_score) FROM teacher_grade_draft_item WHERE"
                + " offering_id=" + OFFERING_MAIN + " AND enrollment_id=" + S1);
        expect(TeacherGradeBookService.ConflictException.class,
                () -> service.saveDraft(TEACHER_A,
                        request(op(22), approvedRevision, approved.getRosterDigest(),
                                approved.getScheme(),
                                List.of(row(S1, "99.00", "99.00", "99.00", "99.00")))),
                "saving an approved book is refused");
        require(count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id=" + OFFERING_MAIN
                        + " AND revision=" + approvedRevision + " AND draft_open=0") == 1
                        && firstRowBefore.equals(text("SELECT CONCAT(daily_score,'/',midterm_score,"
                        + "'/',experiment_score,'/',finalterm_score) FROM"
                        + " teacher_grade_draft_item WHERE offering_id=" + OFFERING_MAIN
                        + " AND enrollment_id=" + S1))
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id="
                        + OFFERING_MAIN + " AND daily_score=99.00") == 0
                        && count(operationLog(op(22))) == 0,
                "the refused save never touches the read-only working copy");
    }

    // ---------------------------------------------------------------- fixtures

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + TEACHER_A + "','Tgb947 Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Tgb947 Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_C + "','Tgb947 Teacher C','x','x',1,'Engineering','Professor'),"
                + "('" + ASSISTANT + "','Tgb947 Assistant','x','x',1,'Engineering','Assistant'),"
                + "('" + ADMIN + "','Tgb947 Admin','x','x',0,'Administration','Registrar'),"
                + "('" + PREFIX + "s1','Tgb947 Student 1','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s2','Tgb947 Student 2','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s3','Tgb947 Student 3','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s4','Tgb947 Student 4','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s5','Tgb947 Student 5','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s6','Tgb947 Student 6','x','x',2,'Engineering','Student'),"
                + "('" + PREFIX + "s7','Tgb947 Student 7','x','x',2,'Engineering','Student')");

        execute("INSERT INTO course(course_id,course_code,course_name,credit,credit_hours,"
                + "course_type,status) VALUES(" + COURSE_MAIN
                + ",'TGB947A','Grade Draft Course A',3.00,48,1,'ACTIVE'),(" + COURSE_OTHER
                + ",'TGB947B','Grade Draft Course B',3.00,48,1,'ACTIVE')");
        execute("INSERT INTO course_offering(offering_id,offering_code,course_id,academic_year,"
                + "semester,capacity,status) VALUES(" + OFFERING_MAIN + ",'TGB947-A',"
                + COURSE_MAIN + ",2026,3,30,2),(" + OFFERING_OTHER + ",'TGB947-B'," + COURSE_OTHER
                + ",2026,3,30,2)");
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(" + OFFERING_MAIN
                + ",'" + TEACHER_A + "',0),(" + OFFERING_MAIN + ",'" + TEACHER_B + "',0),("
                + OFFERING_MAIN + ",'" + ASSISTANT + "',1),(" + OFFERING_OTHER + ",'" + TEACHER_C
                + "',0)");
        // The stored counter is deliberately wrong: the grade list must report the roster size.
        execute("UPDATE course_offering SET enrolled_count=99 WHERE offering_id=" + OFFERING_MAIN);

        // Explicit enrollment ids pin the roster digest; the insert order is shuffled on purpose
        // to prove the digest is computed over the sorted id set.
        enroll(S3, PREFIX + "s3", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S1, PREFIX + "s1", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S4, PREFIX + "s4", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        enroll(S2, PREFIX + "s2", OFFERING_MAIN, COURSE_MAIN, 2, "2026-09-14 01:00:00");
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time,drop_time) VALUES(" + DROPPED + "," + OFFERING_MAIN + ","
                + COURSE_MAIN + ",2026,3,'" + PREFIX + "s5',3,'2026-09-10 01:00:00',"
                + "'2026-09-12 01:00:00')");
        enroll(FOREIGN, PREFIX + "s6", OFFERING_OTHER, COURSE_OTHER, 2, "2026-09-14 01:00:00");

        // A published projection that drafts must never disturb.
        execute("INSERT INTO grade(enrollment_id,daily_score,midterm_score,finalterm_score,"
                + "experiment_score,score,grade_level,grade_point,is_published,publish_time)"
                + " VALUES(" + S1 + ",88.50,90.00,95.90,88.00,93.00,1,4.5,1,"
                + "'2026-09-01 00:00:00')");

        // Historical batches drive the REJECTED/APPROVED working-copy states.
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,"
                + "submitted_at,status,reviewed_by,reviewed_at,review_comment,total_count,"
                + "failed_count) VALUES(" + SUBMISSION_REJECTED + "," + OFFERING_MAIN
                + ",1,'" + TEACHER_A + "','2026-09-10 02:00:00','REJECTED','" + ADMIN
                + "','2026-09-11 02:00:00','权重不对',2,0),(" + SUBMISSION_APPROVED + ","
                + OFFERING_MAIN + ",2,'" + TEACHER_A + "','2026-09-12 02:00:00','APPROVED','"
                + ADMIN + "','2026-09-13 02:00:00','同意',5,0)");
        execute("INSERT INTO grade_submission_item(submission_id,enrollment_id,"
                + "student_uid_snapshot,student_name_snapshot) VALUES(" + SUBMISSION_REJECTED + ","
                + S1 + ",'" + PREFIX + "s1','Tgb947 Student 1'),(" + SUBMISSION_REJECTED + "," + S2
                + ",'" + PREFIX + "s2','Tgb947 Student 2')");
    }

    private static void enroll(long enrollmentId, String uid, long offeringId, long courseId,
                               int status, String selectTime) throws SQLException {
        execute("INSERT INTO enrollment(enrollment_id,offering_id,course_id,academic_year,semester,"
                + "uid,status,select_time) VALUES(" + enrollmentId + "," + offeringId + ","
                + courseId + ",2026,3,'" + uid + "'," + status + ",'" + selectTime + "')");
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM teacher_course_operation_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_change_log WHERE teacher_uid LIKE '" + PREFIX + "%'");
        execute("DELETE FROM teacher_grade_draft_item WHERE offering_id BETWEEN 947300 AND 947399");
        execute("DELETE FROM teacher_grade_book WHERE offering_id BETWEEN 947300 AND 947399");
        execute("DELETE FROM grade WHERE enrollment_id BETWEEN 947400 AND 947499");
        execute("DELETE FROM grade_submission_item WHERE submission_id BETWEEN 947500 AND 947599");
        execute("DELETE FROM grade_submission WHERE submission_id BETWEEN 947500 AND 947599");
        execute("DELETE FROM enrollment WHERE enrollment_id BETWEEN 947400 AND 947499");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 947300 AND 947399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 947300 AND 947399");
        execute("DELETE FROM course WHERE course_id BETWEEN 947200 AND 947299");
        execute("DELETE FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid LIKE '"
                        + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_change_log WHERE teacher_uid"
                        + " LIKE '" + PREFIX + "%'") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_draft_item WHERE offering_id"
                        + " BETWEEN 947300 AND 947399") == 0
                        && count("SELECT COUNT(*) FROM teacher_grade_book WHERE offering_id BETWEEN"
                        + " 947300 AND 947399") == 0
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id BETWEEN 947400"
                        + " AND 947499") == 0
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id"
                        + " BETWEEN 947500 AND 947599") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id BETWEEN"
                        + " 947500 AND 947599") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE enrollment_id BETWEEN 947400"
                        + " AND 947499") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 947300 AND 947399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 947200 AND"
                        + " 947299") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE '" + PREFIX + "%'")
                        == 0,
                "cleanup must leave no fixture row behind");
    }

    // ------------------------------------------------------------- helpers

    private static TeacherGradeBookService service(TeacherGradeBookDAO dao) {
        return new TeacherGradeBookService(dao, new TeacherGradeAuditDAO(),
                new TeacherCourseOperationDAO(), new TeacherAccessPolicy(), CLOCK);
    }

    private static GradeSchemeDTO defaultScheme() {
        return scheme(0, 0, 0, 0);
    }

    /** A scheme built from enabled weights; {@code null} disables the component with weight 0. */
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

    /** "DAILY:on:0|..." so the assertion message shows the whole scheme at once. */
    private static String schemeText(GradeSchemeDTO scheme) {
        StringBuilder text = new StringBuilder();
        for (GradeComponentDTO component : scheme.getComponents()) {
            if (text.length() > 0) text.append('|');
            text.append(component.getCode()).append(':').append(component.isEnabled()).append(':')
                    .append(component.getWeightBasisPoints());
        }
        return text.toString();
    }

    private static WriteGradeBookRequestDTO request(String operationId, int expectedRevision,
                                                    String rosterDigest, GradeSchemeDTO scheme,
                                                    List<GradeRowInputDTO> rows) {
        return new WriteGradeBookRequestDTO(operationId, new GradeBookContentDTO(
                Long.toString(OFFERING_MAIN), expectedRevision, rosterDigest, scheme, rows));
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

    private static boolean blank(GradeScoresDTO scores) {
        return scores == null || (scores.getDailyScore() == null && scores.getMidtermScore() == null
                && scores.getExperimentScore() == null && scores.getFinaltermScore() == null);
    }

    private static BigDecimal decimal(String text) {
        return text == null ? null : new BigDecimal(text);
    }

    private static String op(int value) {
        return UUID.fromString(String.format("94700000-0000-0000-0000-%012d", value)).toString();
    }

    private static String operationLog(String operationId) {
        return "SELECT COUNT(*) FROM teacher_course_operation_log WHERE teacher_uid='" + TEACHER_A
                + "' AND operation_id='" + operationId + "' AND action='saveGradeDraft'"
                + " AND target_type='GRADE_BOOK' AND target_id='" + OFFERING_MAIN
                + "' AND result_code='OK' AND CHAR_LENGTH(request_digest)=64"
                + " AND response_json IS NOT NULL";
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
                "Refusing teacher grade draft test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing teacher grade draft test outside the guarded schema");
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
