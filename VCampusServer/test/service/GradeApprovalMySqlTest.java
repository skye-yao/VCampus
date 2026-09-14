package service;

import dao.AdminCourseOperationDAO;
import dao.GradeApprovalDAO;
import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.result.AdminOperationResultDTO;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static dto.course.admin.approval.ApprovalStatusDTO.APPROVED;
import static dto.course.admin.approval.ApprovalStatusDTO.PENDING;
import static dto.course.admin.approval.ApprovalStatusDTO.REJECTED;

/**
 * Guarded MySQL coverage for administrator grade-submission approval: paged querying with a PENDING
 * default, detail mapping with the five ordered distribution bands, the all-or-nothing approval
 * that projects every item into the current {@code grade} table and publishes it, rejection that
 * leaves the projection untouched, correction batches that supersede the published projection only
 * once approved, typed refusals for unapprovable batches, operation-id replay, stale versions, two
 * administrators racing, two requests sharing one operation id and an injected mid-projection
 * failure that rolls the whole decision back.
 *
 * <p>Fixtures live in the 974xxx id range and are removed by {@link #cleanup()}.
 */
public final class GradeApprovalMySqlTest {
    private static final String GUARDED_DATABASE = "virtual_campus_course_test";
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T06:30:00Z"), ZoneOffset.UTC);
    /** The clock instant as it appears in a UTC DATETIME(6) column. */
    private static final String CLOCK_TEXT = "2026-09-14 06:30:00";

    private static final String ADMIN_A = "gra974-admin-a";
    private static final String ADMIN_B = "gra974-admin-b";
    private static final String TEACHER = "gra974-teacher";
    private static final String TEACHER_B = "gra974-teacher-b";
    private static final String S1 = "gra974-s1";
    private static final String S2 = "gra974-s2";
    private static final String S3 = "gra974-s3";
    private static final String S4 = "gra974-s4";
    private static final String S5 = "gra974-s5";
    private static final String S6 = "gra974-s6";

    private static final int YEAR = 2026;
    private static final int SEMESTER = 3;

    private static final long OFFERING_PLAIN = 974301L;
    private static final long OFFERING_NULL = 974302L;
    private static final long OFFERING_REJECT = 974303L;
    private static final long OFFERING_CORRECTION = 974304L;
    private static final long OFFERING_DUP = 974305L;
    private static final long OFFERING_STALE = 974306L;
    private static final long OFFERING_RACE = 974307L;
    private static final long OFFERING_SHARED_A = 974308L;
    private static final long OFFERING_SHARED_B = 974309L;
    private static final long OFFERING_ROLLBACK = 974310L;
    private static final long OFFERING_INVALID = 974311L;
    private static final long OFFERING_BADSTAT = 974312L;
    private static final long OFFERING_ROUNDED = 974313L;
    private static final long OFFERING_TIGHT = 974314L;

    private static final long SUB_PLAIN = 974701L;
    private static final long SUB_NULL = 974702L;
    private static final long SUB_REJECT = 974703L;
    private static final long SUB_CORR_V1 = 974704L;
    private static final long SUB_CORR_V2 = 974705L;
    private static final long SUB_DUP = 974706L;
    private static final long SUB_STALE = 974707L;
    private static final long SUB_RACE = 974708L;
    private static final long SUB_SHARED_A = 974709L;
    private static final long SUB_SHARED_B = 974710L;
    private static final long SUB_ROLLBACK = 974711L;
    private static final long SUB_INVALID = 974712L;
    private static final long SUB_BADSTAT = 974713L;
    private static final long SUB_ROUNDED = 974714L;
    private static final long SUB_TIGHT = 974715L;

    private static final List<String> BANDS = List.of("90-100", "80-89", "70-79", "60-69", "0-59");

    private GradeApprovalMySqlTest() {
    }

    public static void main(String[] args) throws Exception {
        requireTestDatabase();
        cleanup();
        insertFixtures();
        try {
            verify("list and detail", GradeApprovalMySqlTest::verifyListAndDetail);
            verify("approval projects and publishes immediately",
                    GradeApprovalMySqlTest::verifyApprovalAndStudentVisibility);
            verify("null statistics approve with no scored items",
                    GradeApprovalMySqlTest::verifyNullStatistics);
            verify("rejection leaves the projection unchanged",
                    GradeApprovalMySqlTest::verifyRejection);
            verify("correction supersedes the projection only once approved",
                    GradeApprovalMySqlTest::verifyCorrection);
            verify("out-of-range components are typed refusals",
                    GradeApprovalMySqlTest::verifyOutOfRangeItemsAreTyped);
            verify("unapprovable batches stay typed and can still be rejected",
                    GradeApprovalMySqlTest::verifyTypedRefusals);
            verify("stored-statistic tolerance is rounded, not exact",
                    GradeApprovalMySqlTest::verifyRoundedStatisticTolerance);
            verify("duplicate operation replays", GradeApprovalMySqlTest::verifyDuplicateReplay);
            verify("stale version conflicts", GradeApprovalMySqlTest::verifyStaleVersion);
            verify("concurrent administrators", GradeApprovalMySqlTest::verifyConcurrentAdmins);
            verify("two requests sharing one operation id",
                    GradeApprovalMySqlTest::verifySharedOperationRace);
            verify("injected projection failure rolls back",
                    GradeApprovalMySqlTest::verifyInjectedFailureRollback);
        } finally {
            cleanup();
        }
        verifyNoFixtureRows();
        System.out.println("Grade approval MySQL test passed.");
    }

    // ------------------------------------------------------------------ group 1

    private static void verifyListAndDetail() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());

        // The guarded schema is shared, so page assertions are anchored on what the database
        // actually holds instead of assuming this fixture is the only PENDING batch in it.
        long strays = count("SELECT COUNT(*) FROM grade_submission"
                + " WHERE status='PENDING' AND submission_id NOT BETWEEN 974700 AND 974799");
        GradeSubmissionPageDTO all = service.listGradeSubmissionsPage(null, 1, 100);
        require(all.getTotalCount() == 15 + strays && all.getItems().size() == 15 + strays
                        && all.getPageNumber() == 1 && all.getPageSize() == 100,
                "the default page filters PENDING and reports the server total (observed total="
                        + all.getTotalCount() + " items=" + all.getItems().size() + " expected="
                        + (15 + strays) + ")");
        List<String> ordered = ids(all);
        require(fixtures(ordered).equals(List.of("974715", "974714", "974713", "974712", "974711",
                        "974710", "974709", "974708", "974707", "974706", "974705", "974704",
                        "974703", "974702", "974701")),
                "the list orders by submitted_at then submission_id descending (observed "
                        + fixtures(ordered) + ")");

        List<String> paged = new ArrayList<>();
        for (int pageNumber = 1; pageNumber <= (all.getTotalCount() + 2) / 3; pageNumber++) {
            GradeSubmissionPageDTO slice = service.listGradeSubmissionsPage(
                    ApprovalStatusDTO.PENDING, pageNumber, 3);
            require(slice.getPageNumber() == pageNumber && slice.getPageSize() == 3
                            && slice.getTotalCount() == all.getTotalCount(),
                    "every page reports its own metadata");
            paged.addAll(ids(slice));
        }
        require(paged.equals(ordered),
                "paging at size 3 reproduces the whole ordered set (observed " + paged + ")");

        GradeSubmissionSummaryDTO summary = summaryOf(all, SUB_PLAIN);
        require("Gra Approval Course 1".equals(summary.getCourseName())
                        && "GRA974301".equals(summary.getOfferingCode())
                        && Long.toString(OFFERING_PLAIN).equals(summary.getOfferingId())
                        && summary.getVersion() == 1
                        && TEACHER.equals(summary.getTeacherUid())
                        && "Gra Teacher A".equals(summary.getTeacherName())
                        && summary.getStudentCount() == 4 && summary.getAverage() == 77.5
                        && summary.getHighest() == 95.0 && summary.getLowest() == 55.0
                        && summary.getFailCount() == 1 && summary.getStatus() == PENDING
                        && instantText("2026-09-10 01:00:00").equals(summary.getSubmittedAt()),
                "summaries join the course, the offering and the submitting teacher (observed "
                        + summary.getTeacherUid() + "/" + summary.getTeacherName() + "/"
                        + summary.getAverage() + ")");

        GradeSubmissionSummaryDTO empty = summaryOf(all, SUB_NULL);
        require(empty.getStudentCount() == 2 && empty.getAverage() == 0.0
                        && empty.getHighest() == 0.0 && empty.getLowest() == 0.0
                        && empty.getFailCount() == 0,
                "a batch with no scored item reports 0.0 for a NULL stored statistic");

        require(service.listGradeSubmissionsPage(APPROVED, 1, 100).getItems().stream()
                        .allMatch(item -> item.getStatus() == APPROVED)
                        && fixtures(ids(service.listGradeSubmissionsPage(APPROVED, 1, 100))).isEmpty(),
                "an explicit status filter returns only that status");
        require(fixtures(ids(service.listGradeSubmissionsPage(REJECTED, 1, 100))).isEmpty(),
                "nothing under test is rejected before the decision scenarios run");

        expect(IllegalArgumentException.class, () -> service.listGradeSubmissionsPage(null, 0, 10),
                "page 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listGradeSubmissionsPage(null, 1, 0),
                "size 0 is rejected");
        expect(IllegalArgumentException.class, () -> service.listGradeSubmissionsPage(null, 1, 101),
                "size above 100 is rejected");
        expect(IllegalArgumentException.class, () -> service.getGradeSubmission("abc"),
                "a non-decimal submission id is rejected");
        expect(GradeApprovalService.NotFoundException.class,
                () -> service.getGradeSubmission("974799"), "a missing submission is not found");

        GradeSubmissionDetailDTO detail = service.getGradeSubmission(Long.toString(SUB_PLAIN));
        require(detail.getSummary() != null && detail.getSummary().getStatus() == PENDING
                        && detail.getSummary().getVersion() == 1,
                "the detail maps the submission head");
        require(detail.getReviewedBy() == null && detail.getReviewedAt() == null
                        && detail.getReviewComment() == null,
                "an undecided batch carries no reviewer");
        require(detail.getItems().size() == 4
                        && S1.equals(detail.getItems().get(0).getStudentUid())
                        && "Gra Student 1".equals(detail.getItems().get(0).getStudentName())
                        && S2.equals(detail.getItems().get(1).getStudentUid())
                        && S3.equals(detail.getItems().get(2).getStudentUid())
                        && S4.equals(detail.getItems().get(3).getStudentUid()),
                "items are ordered by student UID (observed " + studentIds(detail.getItems()) + ")");
        GradeSubmissionItemDTO first = detail.getItems().get(0);
        require(first.getDailyScore() == 88.5 && first.getMidtermScore() == null
                        && first.getExperimentScore() == null && first.getFinaltermScore() == 92.0
                        && first.getScore() == 95.0 && first.getGradeLevel() == 1
                        && first.getGradePoint() == 4.5,
                "nullable components stay null instead of collapsing to zero");
        GradeSubmissionItemDTO third = detail.getItems().get(2);
        require(third.getDailyScore() == null && third.getMidtermScore() == null
                        && third.getExperimentScore() == null && third.getFinaltermScore() == null
                        && third.getScore() == 75.0 && third.getGradeLevel() == 3
                        && third.getGradePoint() == 2.5,
                "a wholly unscored component set keeps every component null");
        require(bands(detail.getDistribution()).equals(List.of("90-100=1", "80-89=1", "70-79=1",
                        "60-69=0", "0-59=1")),
                "the five bands are always present in order (observed "
                        + bands(detail.getDistribution()) + ")");

        GradeSubmissionDetailDTO unscored =
                service.getGradeSubmission(Long.toString(SUB_NULL));
        require(unscored.getItems().size() == 2
                        && unscored.getItems().stream().allMatch(item -> item.getScore() == null)
                        && bands(unscored.getDistribution()).equals(List.of("90-100=0", "80-89=0",
                        "70-79=0", "60-69=0", "0-59=0")),
                "an item without a score falls in no band, so the bucket total may trail the count");
    }

    // ------------------------------------------------------------------ group 2

    private static void verifyApprovalAndStudentVisibility() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        long enrollment = enrollmentId(S1, OFFERING_PLAIN);
        require(gradeCount(enrollment) == 0, "the offering starts with no projection row");

        AdminOperationResultDTO<GradeSubmissionDetailDTO> result = service.review(ADMIN_A,
                decision(op(1), SUB_PLAIN, 1, true, null, null, false));
        require("OK".equals(result.getOutcomeCode()) && result.getConflicts().isEmpty(),
                "a valid approval succeeds");
        GradeSubmissionDetailDTO entity = result.getEntity();
        require(entity.getSummary().getStatus() == APPROVED
                        && entity.getSummary().getVersion() == 1
                        && ADMIN_A.equals(entity.getReviewedBy())
                        && instantText("2026-09-14 06:30:00").equals(entity.getReviewedAt()),
                "the approved entity records the reviewer and the transaction instant");

        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_PLAIN + ")") == 4,
                "every eligible enrollment gains exactly one projection row");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment
                        + " AND daily_score=88.50 AND midterm_score IS NULL"
                        + " AND experiment_score IS NULL AND finalterm_score=92.00"
                        + " AND score=95.00 AND grade_level=1 AND grade_point=4.5"
                        + " AND is_published=1 AND publish_time='" + CLOCK_TEXT + "'") == 1,
                "the projection assigns every component exactly and publishes it");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_PLAIN + ") AND is_published=1 AND publish_time='"
                        + CLOCK_TEXT + "'") == 4,
                "one shared transaction timestamp publishes every row");

        GradeSummaryDTO visible = new CourseQueryService().loadGrades(S1, YEAR, SEMESTER);
        require(visible.getRecords().size() == 1
                        && "GRA974101".equals(visible.getRecords().get(0).getCourseCode())
                        && visible.getRecords().get(0).getScore() == 95.0
                        && visible.getRecords().get(0).getGradePoint() == 4.5
                        && visible.getRecords().get(0).getMidtermScore() == null,
                "the student read path sees the published projection immediately");

        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(1) + "' AND action='reviewGradeSubmission'"
                        + " AND target_type='GRADE_SUBMISSION' AND target_id='" + SUB_PLAIN
                        + "' AND forced=0 AND result_code='OK' AND completed_at='" + CLOCK_TEXT
                        + "'") == 1,
                "the approval writes exactly one audit row");
        require(fixtures(ids(service.listGradeSubmissionsPage(APPROVED, 1, 100)))
                        .contains(Long.toString(SUB_PLAIN))
                        && !fixtures(ids(service.listGradeSubmissionsPage(PENDING, 1, 100)))
                        .contains(Long.toString(SUB_PLAIN)),
                "the decided batch leaves the PENDING filter for the APPROVED one");
    }

    // ------------------------------------------------------------------ group 3

    private static void verifyNullStatistics() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        AdminOperationResultDTO<GradeSubmissionDetailDTO> result = service.review(ADMIN_A,
                decision(op(2), SUB_NULL, 1, true, null, null, false));
        require(result.getEntity().getSummary().getStatus() == APPROVED,
                "a batch without any scored item still approves");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_NULL + ") AND score IS NULL AND is_published=1") == 2,
                "an unscored item projects a NULL total that stays NULL and is still published");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_NULL + ")") == 2,
                "the null-score projection writes exactly one row per enrollment");
    }

    // ------------------------------------------------------------------ group 4

    private static void verifyRejection() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        long enrollment = enrollmentId(S1, OFFERING_REJECT);
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment
                        + " AND score=50.00 AND is_published=0 AND publish_time IS NULL") == 1,
                "the rejection fixture starts with an unpublished projection row");

        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(3), SUB_REJECT, 1, false, null, null, false)),
                "rejection without a comment is rejected");
        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(4), SUB_REJECT, 1, false, null, "   ", false)),
                "a blank rejection comment is rejected");
        expect(IllegalArgumentException.class,
                () -> service.review(ADMIN_A, decision(op(5), SUB_REJECT, 1, true, "强迫", null, true)),
                "grade approval does not support a forced override");

        AdminOperationResultDTO<GradeSubmissionDetailDTO> rejected = service.review(ADMIN_A,
                decision(op(6), SUB_REJECT, 1, false, null, "  材料不足  ", false));
        require("OK".equals(rejected.getOutcomeCode())
                        && REJECTED == rejected.getEntity().getSummary().getStatus()
                        && rejected.getEntity().getSummary().getVersion() == 1
                        && "材料不足".equals(rejected.getEntity().getReviewComment())
                        && ADMIN_A.equals(rejected.getEntity().getReviewedBy()),
                "a rejection records the trimmed comment, reviewer and the unchanged version");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment
                        + " AND score=50.00 AND is_published=0 AND publish_time IS NULL") == 1,
                "a rejection leaves every projection row untouched");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(6) + "' AND action='reviewGradeSubmission'"
                        + " AND target_id='" + SUB_REJECT + "' AND forced=0 AND result_code='OK'") == 1,
                "a rejection writes its own audit row");
        require(fixtures(ids(service.listGradeSubmissionsPage(REJECTED, 1, 100)))
                        .contains(Long.toString(SUB_REJECT)),
                "the rejected batch appears under the REJECTED filter");
    }

    // ------------------------------------------------------------------ group 5

    private static void verifyCorrection() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        long enrollment = enrollmentId(S2, OFFERING_CORRECTION);
        require(gradeCount(enrollment) == 0, "a correction offering starts unpublished");

        service.review(ADMIN_A, decision(op(7), SUB_CORR_V1, 1, true, null, null, false));
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment
                        + " AND score=60.00 AND is_published=1 AND publish_time='" + CLOCK_TEXT
                        + "'") == 1,
                "the first version publishes its projection");
        require(studentScore(S2, "GRA974104") == 60.0,
                "the student sees the first version once it is approved");

        AdminOperationResultDTO<GradeSubmissionDetailDTO> correction = service.review(ADMIN_A,
                decision(op(8), SUB_CORR_V2, 2, true, null, null, false));
        require(APPROVED == correction.getEntity().getSummary().getStatus()
                        && correction.getEntity().getSummary().getVersion() == 2,
                "the correction batch approves against its own version");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment) == 1
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollment
                        + " AND score=90.00 AND is_published=1 AND publish_time='" + CLOCK_TEXT
                        + "'") == 1,
                "the correction upserts the current projection instead of adding a row");
        require(studentScore(S2, "GRA974104") == 90.0,
                "the student sees the correction only after it is approved");
        require(count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + SUB_CORR_V1 + " AND score=60.00") == 1
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id="
                        + SUB_CORR_V2 + " AND score=90.00") == 1
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + SUB_CORR_V1 + " AND status='APPROVED'") == 1,
                "the superseded snapshot stays unchanged and queryable");
    }

    // ------------------------------------------------------------------ group 6

    /**
     * The schema's CHECK constraints make an out-of-range component unrepresentable, so R2's typed
     * refusal is proved by handing the service a crafted item through the DAO read seam.
     */
    private static void verifyOutOfRangeItemsAreTyped() throws Exception {
        GradeApprovalService outOfRange = service(new CraftedItemDao(SUB_INVALID, new GradeApprovalDAO.ItemRow(
                0L, SUB_INVALID, enrollmentId(S2, OFFERING_INVALID), S2, "Gra Student 2",
                null, null, null, null, new BigDecimal("150.00"), null, null)));
        GradeApprovalService.ConflictException score = expect(
                GradeApprovalService.ConflictException.class,
                () -> outOfRange.review(ADMIN_A, decision(op(21), SUB_INVALID, 1, true, null, null)),
                "a total score above 100 is refused as a typed conflict");
        require(score.getMessage() != null && score.getMessage().contains("总评成绩")
                        && score.getEntity() != null
                        && PENDING == score.getEntity().getSummary().getStatus(),
                "the range refusal names the component and carries the current detail");

        GradeApprovalService gradePoint = service(new CraftedItemDao(SUB_TIGHT, new GradeApprovalDAO.ItemRow(
                0L, SUB_TIGHT, enrollmentId(S1, OFFERING_TIGHT), S1, "Gra Student 1",
                null, null, null, null, new BigDecimal("70.00"), null, new BigDecimal("6.0"))));
        GradeApprovalService.ConflictException point = expect(
                GradeApprovalService.ConflictException.class,
                () -> gradePoint.review(ADMIN_A, decision(op(22), SUB_TIGHT, 1, true, null, null)),
                "a grade point above 5 is refused as a typed conflict");
        require(point.getMessage() != null && point.getMessage().contains("绩点"),
                "the grade-point refusal names the component");

        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id IN ("
                        + OFFERING_INVALID + "," + OFFERING_TIGHT + "))") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id IN ("
                        + SUB_INVALID + "," + SUB_TIGHT + ") AND status='PENDING'") == 2,
                "an out-of-range refusal writes no projection and leaves both batches PENDING");
    }

    // ------------------------------------------------------------------ group 7

    private static void verifyTypedRefusals() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());

        GradeApprovalService.ConflictException coverage = expect(
                GradeApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(9), SUB_INVALID, 1, true, null, null, false)),
                "an item set that misses an eligible enrollment is refused");
        require(coverage.getEntity() != null
                        && PENDING == coverage.getEntity().getSummary().getStatus()
                        && coverage.getEntity().getSummary().getVersion() == 1
                        && coverage.getEntity().getItems().size() == 1,
                "the coverage refusal carries the current detail");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_INVALID + ")") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(9) + "'") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + SUB_INVALID + " AND status='PENDING'") == 1,
                "an unapprovable batch makes no partial write");

        GradeApprovalService.ConflictException statistics = expect(
                GradeApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(10), SUB_BADSTAT, 1, true, null, null, false)),
                "a stored statistic that disagrees with the items is refused");
        require(statistics.getEntity() != null
                        && PENDING == statistics.getEntity().getSummary().getStatus(),
                "the statistic refusal carries the current detail");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_BADSTAT + ")") == 0,
                "the statistic refusal writes no projection");

        // A batch that can never be approved must still be rejectable, like the sibling service.
        AdminOperationResultDTO<GradeSubmissionDetailDTO> rejected = service.review(ADMIN_B,
                decision(op(11), SUB_BADSTAT, 1, false, null, "统计与明细不符", false));
        require(REJECTED == rejected.getEntity().getSummary().getStatus()
                        && "统计与明细不符".equals(rejected.getEntity().getReviewComment()),
                "an unapprovable batch can still be rejected");
        AdminOperationResultDTO<GradeSubmissionDetailDTO> coverageRejected = service.review(ADMIN_B,
                decision(op(12), SUB_INVALID, 1, false, null, "学生名单不完整", false));
        require(REJECTED == coverageRejected.getEntity().getSummary().getStatus(),
                "the incomplete-coverage batch can still be rejected");
    }

    // ------------------------------------------------------------------ group 8

    private static void verifyRoundedStatisticTolerance() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());

        // 212/3 = 70.666..., so a stored 70.67 differs from the exact recomputed mean by 0.0033.
        AdminOperationResultDTO<GradeSubmissionDetailDTO> rounded = service.review(ADMIN_A,
                decision(op(19), SUB_ROUNDED, 1, true, null, null));
        require(APPROVED == rounded.getEntity().getSummary().getStatus(),
                "a stored statistic that only differs by DECIMAL(5,2) rounding is accepted");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_ROUNDED + ") AND is_published=1") == 3,
                "the rounding-tolerant batch still projects every enrollment");

        // 70.66 is more than the 0.005 tolerance away from the same exact mean.
        GradeApprovalService.ConflictException tight = expect(
                GradeApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(20), SUB_TIGHT, 1, true, null, null)),
                "a stored statistic outside the tolerance is refused");
        require(tight.getEntity() != null
                        && PENDING == tight.getEntity().getSummary().getStatus(),
                "the tolerance refusal carries the current detail");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_TIGHT + ")") == 0,
                "the tolerance refusal writes no projection");
    }

    // ------------------------------------------------------------------ group 9

    private static void verifyDuplicateReplay() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        AdminOperationResultDTO<GradeSubmissionDetailDTO> first = service.review(ADMIN_A,
                decision(op(13), SUB_DUP, 1, true, null, null, false));
        require(APPROVED == first.getEntity().getSummary().getStatus(),
                "the replay fixture approves once");
        long projections = count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                + " (SELECT enrollment_id FROM enrollment WHERE offering_id=" + OFFERING_DUP + ")");

        AdminOperationResultDTO<GradeSubmissionDetailDTO> replay = service.review(ADMIN_A,
                decision(op(13), SUB_DUP, 1, true, null, null, false));
        require(APPROVED == replay.getEntity().getSummary().getStatus()
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_DUP + ")") == projections
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='"
                        + ADMIN_A + "' AND operation_id='" + op(13) + "'") == 1,
                "the same operation identity replays the stored decision without a second write");

        expect(GradeApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(13), SUB_STALE, 1, true, null, null, false)),
                "the same operationId with a different submission digest conflicts");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(13) + "'") == 1
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + SUB_STALE + " AND status='PENDING'") == 1,
                "a digest conflict writes nothing");
    }

    // ------------------------------------------------------------------ group 10

    private static void verifyStaleVersion() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        GradeApprovalService.ConflictException stale = expect(
                GradeApprovalService.ConflictException.class,
                () -> service.review(ADMIN_A, decision(op(14), SUB_STALE, 9, true, null, null, false)),
                "a stale expectedVersion conflicts");
        require(stale.getEntity() != null
                        && PENDING == stale.getEntity().getSummary().getStatus()
                        && stale.getEntity().getSummary().getVersion() == 1,
                "the stale conflict carries the latest detail");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_STALE + ")") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(14) + "'") == 0
                        && count("SELECT COUNT(*) FROM grade_submission WHERE submission_id="
                        + SUB_STALE + " AND status='PENDING' AND version=1") == 1,
                "a stale decision makes no partial write");
    }

    // ----------------------------------------------------------------- group 11

    private static void verifyConcurrentAdmins() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<AdminOperationResultDTO<GradeSubmissionDetailDTO>> success =
                new AtomicReference<>();
        AtomicReference<GradeApprovalService.ConflictException> refusal = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Thread first = new Thread(attempt(service, ADMIN_A, op(15), SUB_RACE, 1, barrier, success,
                refusal, unexpected), "grade-approver-a");
        Thread second = new Thread(attempt(service, ADMIN_B, op(16), SUB_RACE, 1, barrier, success,
                refusal, unexpected), "grade-approver-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(),
                "both racing administrations only saw a success or a conflict (" + unexpected + ")");
        require(success.get() != null && APPROVED == success.get().getEntity().getSummary().getStatus(),
                "exactly one racing administration commits the decision");
        require(refusal.get() != null && refusal.get().getEntity() != null
                        && APPROVED == refusal.get().getEntity().getSummary().getStatus(),
                "the loser receives the committed latest state");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_RACE + ")") == 1
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE target_id='"
                        + SUB_RACE + "'") == 1,
                "the race leaves exactly one projection row and one audit row");
    }

    // ----------------------------------------------------------------- group 12

    private static void verifySharedOperationRace() throws Exception {
        GradeApprovalService service = service(new GradeApprovalDAO());
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<AdminOperationResultDTO<GradeSubmissionDetailDTO>> committed =
                new AtomicReference<>();
        AtomicReference<GradeApprovalService.ConflictException> refusal = new AtomicReference<>();
        List<Throwable> unexpected = new ArrayList<>();

        Thread first = new Thread(attempt(service, ADMIN_A, op(17), SUB_SHARED_A, 1, barrier,
                committed, refusal, unexpected), "shared-operation-id-a");
        Thread second = new Thread(attempt(service, ADMIN_A, op(17), SUB_SHARED_B, 1, barrier,
                committed, refusal, unexpected), "shared-operation-id-b");
        first.start();
        second.start();
        first.join();
        second.join();

        require(unexpected.isEmpty(),
                "reusing one operationId for two submissions must not surface a database failure ("
                        + unexpected + ")");
        require(committed.get() != null && APPROVED == committed.get().getEntity().getSummary().getStatus()
                        && refusal.get() != null,
                "one of the two submissions claims the operation identity");
        require(count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid='" + ADMIN_A
                        + "' AND operation_id='" + op(17) + "'") == 1,
                "one operationId keeps exactly one audit row");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id IN ("
                        + SUB_SHARED_A + "," + SUB_SHARED_B + ") AND status='PENDING'") == 1,
                "the losing submission is fully rolled back to PENDING");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id IN ("
                        + OFFERING_SHARED_A + "," + OFFERING_SHARED_B + "))") == 1,
                "the losing submission writes no projection row");
    }

    // ----------------------------------------------------------------- group 13

    private static void verifyInjectedFailureRollback() throws Exception {
        GradeApprovalService failing = service(new FailingProjectionDao());

        expect(DatabaseException.class,
                () -> failing.review(ADMIN_A, decision(op(18), SUB_ROLLBACK, 1, true, null, null, false)),
                "an injected projection failure surfaces as a database failure");
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id=" + SUB_ROLLBACK
                        + " AND status='PENDING' AND version=1 AND reviewed_at IS NULL") == 1,
                "the failed transaction rolls the submission decision back");
        require(count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN"
                        + " (SELECT enrollment_id FROM enrollment WHERE offering_id="
                        + OFFERING_ROLLBACK + ")") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE operation_id='"
                        + op(18) + "'") == 0,
                "the failed transaction rolls the projection and the audit row back");
    }

    // ------------------------------------------------------------------ fixtures

    private static GradeApprovalService service(GradeApprovalDAO dao) {
        return new GradeApprovalService(dao, new AdminCourseOperationDAO(), CLOCK);
    }

    private static ApprovalDecisionRequestDTO decision(String operationId, long submissionId,
                                                       int expectedVersion, boolean approved,
                                                       String overrideReason, String reviewComment) {
        return decision(operationId, submissionId, expectedVersion, approved, overrideReason,
                reviewComment, false);
    }

    private static ApprovalDecisionRequestDTO decision(String operationId, long submissionId,
                                                       int expectedVersion, boolean approved,
                                                       String overrideReason, String reviewComment,
                                                       boolean force) {
        return new ApprovalDecisionRequestDTO(operationId, Long.toString(submissionId),
                expectedVersion, approved, force, overrideReason, reviewComment);
    }

    private static Runnable attempt(GradeApprovalService service, String admin, String operationId,
                                    long submissionId, int expectedVersion, CyclicBarrier barrier,
                                    AtomicReference<AdminOperationResultDTO<GradeSubmissionDetailDTO>> success,
                                    AtomicReference<GradeApprovalService.ConflictException> refusal,
                                    List<Throwable> unexpected) {
        return () -> {
            try {
                barrier.await();
                success.set(service.review(admin,
                        decision(operationId, submissionId, expectedVersion, true, null, null)));
            } catch (GradeApprovalService.ConflictException expected) {
                refusal.set(expected);
            } catch (Throwable other) {
                synchronized (unexpected) {
                    unexpected.add(other);
                }
            }
        };
    }

    /** Overridable write seam: a failure here must roll back the whole decision. */
    private static final class FailingProjectionDao extends GradeApprovalDAO {
        @Override
        public void upsertGrade(Connection connection, ItemRow item, Timestamp publishTime)
                throws SQLException {
            throw new SQLException("injected projection failure");
        }
    }

    /**
     * Read seam that hands the service one crafted item for a single submission. The schema's CHECK
     * constraints forbid an out-of-range component from ever being stored, so this is the only way
     * to prove the service still reports it as a typed refusal rather than trusting the database.
     */
    private static final class CraftedItemDao extends GradeApprovalDAO {
        private final long target;
        private final ItemRow crafted;

        private CraftedItemDao(long target, ItemRow crafted) {
            this.target = target;
            this.crafted = crafted;
        }

        @Override
        public List<ItemRow> findItems(Connection connection, long submissionId) throws SQLException {
            if (target == submissionId) return List.of(crafted);
            return super.findItems(connection, submissionId);
        }
    }

    private static void insertFixtures() throws SQLException {
        execute("INSERT INTO tbl_user(UID,name,password,salt,role,college,major) VALUES"
                + "('" + ADMIN_A + "','Gra Admin A','x','x',0,'Administration','Registrar'),"
                + "('" + ADMIN_B + "','Gra Admin B','x','x',0,'Administration','Registrar'),"
                + "('" + TEACHER + "','Gra Teacher A','x','x',1,'Engineering','Professor'),"
                + "('" + TEACHER_B + "','Gra Teacher B','x','x',1,'Engineering','Professor'),"
                + "('" + S1 + "','Gra Student 1','x','x',2,'Engineering','Student'),"
                + "('" + S2 + "','Gra Student 2','x','x',2,'Engineering','Student'),"
                + "('" + S3 + "','Gra Student 3','x','x',2,'Engineering','Student'),"
                + "('" + S4 + "','Gra Student 4','x','x',2,'Engineering','Student'),"
                + "('" + S5 + "','Gra Student 5','x','x',2,'Engineering','Student'),"
                + "('" + S6 + "','Gra Student 6','x','x',2,'Engineering','Student')");

        StringBuilder courses = new StringBuilder("INSERT INTO course(course_id,course_code,"
                + "course_name,credit,credit_hours,course_type,status) VALUES");
        StringBuilder offerings = new StringBuilder("INSERT INTO course_offering(offering_id,"
                + "offering_code,course_id,academic_year,semester,capacity,status) VALUES");
        for (int index = 0; index < 14; index++) {
            if (index > 0) {
                courses.append(',');
                offerings.append(',');
            }
            long courseId = 974101L + index;
            long offeringId = 974301L + index;
            courses.append('(').append(courseId).append(",'GRA").append(courseId).append("','Gra"
                    + " Approval Course ").append(index + 1).append("',3.00,48,1,'ACTIVE')");
            offerings.append('(').append(offeringId).append(",'GRA").append(offeringId).append("',")
                    .append(courseId).append(',').append(YEAR).append(',').append(SEMESTER)
                    .append(",40,2)");
        }
        execute(courses.toString());
        execute(offerings.toString());
        // The offering's default teacher differs from the submitter, so R9 is observable.
        execute("INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES("
                + OFFERING_PLAIN + ",'" + TEACHER_B + "',0)");

        long e1 = enroll(S1, OFFERING_PLAIN);
        long e2 = enroll(S2, OFFERING_PLAIN);
        long e3 = enroll(S3, OFFERING_PLAIN);
        long e4 = enroll(S4, OFFERING_PLAIN);
        long e5 = enroll(S5, OFFERING_NULL);
        long e6 = enroll(S6, OFFERING_NULL);
        long eReject = enroll(S1, OFFERING_REJECT);
        long eCorrect = enroll(S2, OFFERING_CORRECTION);
        long eDup = enroll(S3, OFFERING_DUP);
        long eStale = enroll(S4, OFFERING_STALE);
        long eRace = enroll(S5, OFFERING_RACE);
        long eSharedA = enroll(S6, OFFERING_SHARED_A);
        long eSharedB = enroll(S1, OFFERING_SHARED_B);
        long eRollback = enroll(S3, OFFERING_ROLLBACK);
        long eInvalid = enroll(S2, OFFERING_INVALID);
        enroll(S3, OFFERING_INVALID);
        long eBadA = enroll(S4, OFFERING_BADSTAT);
        long eBadB = enroll(S5, OFFERING_BADSTAT);
        long eRoundA = enroll(S4, OFFERING_ROUNDED);
        long eRoundB = enroll(S5, OFFERING_ROUNDED);
        long eRoundC = enroll(S6, OFFERING_ROUNDED);
        long eTightA = enroll(S1, OFFERING_TIGHT);
        long eTightB = enroll(S2, OFFERING_TIGHT);
        long eTightC = enroll(S3, OFFERING_TIGHT);

        // A: summary statistics, nullable components, distribution bands and the approval projection.
        submission(SUB_PLAIN, OFFERING_PLAIN, 1, "2026-09-10 01:00:00", "77.50", "95.00", "55.00", 1, 4);
        item(SUB_PLAIN, e1, "88.50", null, null, "92.00", "95.00", 1, "4.5");
        item(SUB_PLAIN, e2, "80.00", "85.00", "90.00", "85.00", "85.00", 2, "3.5");
        item(SUB_PLAIN, e3, null, null, null, null, "75.00", 3, "2.5");
        item(SUB_PLAIN, e4, "60.00", "50.00", "55.00", "55.00", "55.00", 4, "1.0");
        // B: no scored item at all, so the header statistic snapshot is NULL.
        submission(SUB_NULL, OFFERING_NULL, 1, "2026-09-10 02:00:00", null, null, null, 0, 2);
        item(SUB_NULL, e5, null, null, null, null, null, null, null);
        item(SUB_NULL, e6, null, null, null, null, null, null, null);
        // C: rejectable, with an unpublished projection row that must survive the rejection.
        submission(SUB_REJECT, OFFERING_REJECT, 1, "2026-09-10 03:00:00", "88.00", "88.00", "88.00", 0, 1);
        item(SUB_REJECT, eReject, "70.00", "70.00", "70.00", "70.00", "88.00", 2, "3.0");
        execute("INSERT INTO grade(enrollment_id,score,is_published) VALUES(" + eReject + ",50.00,0)");
        // D: a correction pair sharing one offering.
        submission(SUB_CORR_V1, OFFERING_CORRECTION, 1, "2026-09-10 04:00:00", "60.00", "60.00", "60.00", 0, 1);
        item(SUB_CORR_V1, eCorrect, "60.00", "60.00", "60.00", "60.00", "60.00", 2, "2.0");
        submission(SUB_CORR_V2, OFFERING_CORRECTION, 2, "2026-09-10 05:00:00", "90.00", "90.00", "90.00", 0, 1);
        item(SUB_CORR_V2, eCorrect, "90.00", "90.00", "90.00", "90.00", "90.00", 1, "4.5");
        // E: replay and stale-version fixtures.
        submission(SUB_DUP, OFFERING_DUP, 1, "2026-09-10 06:00:00", "80.00", "80.00", "80.00", 0, 1);
        item(SUB_DUP, eDup, "80.00", "80.00", "80.00", "80.00", "80.00", 2, "3.0");
        submission(SUB_STALE, OFFERING_STALE, 1, "2026-09-10 07:00:00", "70.00", "70.00", "70.00", 0, 1);
        item(SUB_STALE, eStale, "70.00", "70.00", "70.00", "70.00", "70.00", 2, "2.5");
        // F: race fixtures.
        submission(SUB_RACE, OFFERING_RACE, 1, "2026-09-10 08:00:00", "65.00", "65.00", "65.00", 0, 1);
        item(SUB_RACE, eRace, "65.00", "65.00", "65.00", "65.00", "65.00", 2, "2.0");
        submission(SUB_SHARED_A, OFFERING_SHARED_A, 1, "2026-09-10 09:00:00", "60.00", "60.00", "60.00", 0, 1);
        item(SUB_SHARED_A, eSharedA, "60.00", "60.00", "60.00", "60.00", "60.00", 2, "1.5");
        submission(SUB_SHARED_B, OFFERING_SHARED_B, 1, "2026-09-10 09:00:00", "61.00", "61.00", "61.00", 0, 1);
        item(SUB_SHARED_B, eSharedB, "61.00", "61.00", "61.00", "61.00", "61.00", 2, "1.5");
        // G: the injected projection failure.
        submission(SUB_ROLLBACK, OFFERING_ROLLBACK, 1, "2026-09-10 10:00:00", "62.00", "62.00", "62.00", 0, 1);
        item(SUB_ROLLBACK, eRollback, "62.00", "62.00", "62.00", "62.00", "62.00", 2, "1.5");
        // H: an item set that misses one eligible enrollment.
        submission(SUB_INVALID, OFFERING_INVALID, 1, "2026-09-10 11:00:00", "50.00", "50.00", "50.00", 1, 1);
        item(SUB_INVALID, eInvalid, "50.00", "50.00", "50.00", "50.00", "50.00", 3, "1.0");
        // I: a stored average that disagrees with the two items.
        submission(SUB_BADSTAT, OFFERING_BADSTAT, 1, "2026-09-10 12:00:00", "70.00", "90.00", "80.00", 0, 2);
        item(SUB_BADSTAT, eBadA, "80.00", "80.00", "80.00", "80.00", "80.00", 2, "3.0");
        item(SUB_BADSTAT, eBadB, "90.00", "90.00", "90.00", "90.00", "90.00", 1, "4.0");
        // J: the exact mean 212/3 rounds to 70.67, so the stored statistic passes within tolerance.
        submission(SUB_ROUNDED, OFFERING_ROUNDED, 1, "2026-09-10 13:00:00", "70.67", "71.00", "70.00", 0, 3);
        item(SUB_ROUNDED, eRoundA, "70.00", "70.00", "70.00", "70.00", "70.00", 2, "2.0");
        item(SUB_ROUNDED, eRoundB, "71.00", "71.00", "71.00", "71.00", "71.00", 2, "2.0");
        item(SUB_ROUNDED, eRoundC, "71.00", "71.00", "71.00", "71.00", "71.00", 2, "2.0");
        // K: the same items one cent below the correctly rounded mean, so the tolerance must refuse.
        submission(SUB_TIGHT, OFFERING_TIGHT, 1, "2026-09-10 14:00:00", "70.66", "71.00", "70.00", 0, 3);
        item(SUB_TIGHT, eTightA, "70.00", "70.00", "70.00", "70.00", "70.00", 2, "2.0");
        item(SUB_TIGHT, eTightB, "71.00", "71.00", "71.00", "71.00", "71.00", 2, "2.0");
        item(SUB_TIGHT, eTightC, "71.00", "71.00", "71.00", "71.00", "71.00", 2, "2.0");
    }

    private static long enroll(String uid, long offeringId) throws SQLException {
        execute("INSERT INTO enrollment(offering_id,course_id,academic_year,semester,uid,status,"
                + "select_time) SELECT offering_id,course_id,academic_year,semester,'" + uid
                + "',2,'2026-09-01 00:00:00' FROM course_offering WHERE offering_id=" + offeringId);
        return count("SELECT enrollment_id FROM enrollment WHERE uid='" + uid + "' AND offering_id="
                + offeringId);
    }

    private static void submission(long submissionId, long offeringId, int version,
                                   String submittedAt, String average, String max, String min,
                                   int failedCount, int totalCount) throws SQLException {
        execute("INSERT INTO grade_submission(submission_id,offering_id,version,submitted_by,"
                + "submitted_at,status,average_score,max_score,min_score,failed_count,total_count)"
                + " VALUES(" + submissionId + "," + offeringId + "," + version + ",'" + TEACHER
                + "','" + submittedAt + "','PENDING'," + number(average) + "," + number(max) + ","
                + number(min) + "," + failedCount + "," + totalCount + ")");
    }

    private static void item(long submissionId, long enrollmentId, String daily, String midterm,
                             String experiment, String finalterm, String score, Integer level,
                             String point) throws SQLException {
        execute("INSERT INTO grade_submission_item(submission_id,enrollment_id,daily_score,"
                + "midterm_score,experiment_score,finalterm_score,score,grade_level,grade_point)"
                + " VALUES(" + submissionId + "," + enrollmentId + "," + number(daily) + ","
                + number(midterm) + "," + number(experiment) + "," + number(finalterm) + ","
                + number(score) + "," + (level == null ? "NULL" : level.toString()) + ","
                + number(point) + ")");
    }

    private static void cleanup() throws SQLException {
        execute("DELETE FROM admin_course_operation_log WHERE admin_uid IN ('" + ADMIN_A + "','"
                + ADMIN_B + "')");
        execute("DELETE FROM grade WHERE enrollment_id IN (SELECT enrollment_id FROM enrollment"
                + " WHERE offering_id BETWEEN 974300 AND 974399)");
        execute("DELETE FROM grade_submission_item WHERE submission_id BETWEEN 974700 AND 974799");
        execute("DELETE FROM grade_submission WHERE submission_id BETWEEN 974700 AND 974799");
        execute("DELETE FROM enrollment WHERE offering_id BETWEEN 974300 AND 974399");
        execute("DELETE FROM course_offering_teacher WHERE offering_id BETWEEN 974300 AND 974399");
        execute("DELETE FROM course_offering WHERE offering_id BETWEEN 974300 AND 974399");
        execute("DELETE FROM course WHERE course_id BETWEEN 974100 AND 974199");
        execute("DELETE FROM tbl_user WHERE UID LIKE 'gra974-%'");
    }

    private static void verifyNoFixtureRows() throws SQLException {
        require(count("SELECT COUNT(*) FROM grade_submission WHERE submission_id BETWEEN 974700"
                        + " AND 974799") == 0
                        && count("SELECT COUNT(*) FROM grade_submission_item WHERE submission_id"
                        + " BETWEEN 974700 AND 974799") == 0
                        && count("SELECT COUNT(*) FROM enrollment WHERE offering_id BETWEEN 974300"
                        + " AND 974399") == 0
                        && count("SELECT COUNT(*) FROM grade WHERE enrollment_id IN (SELECT"
                        + " enrollment_id FROM enrollment WHERE offering_id BETWEEN 974300"
                        + " AND 974399)") == 0
                        && count("SELECT COUNT(*) FROM course_offering WHERE offering_id BETWEEN"
                        + " 974300 AND 974399") == 0
                        && count("SELECT COUNT(*) FROM course WHERE course_id BETWEEN 974100"
                        + " AND 974199") == 0
                        && count("SELECT COUNT(*) FROM admin_course_operation_log WHERE admin_uid IN ('"
                        + ADMIN_A + "','" + ADMIN_B + "')") == 0
                        && count("SELECT COUNT(*) FROM tbl_user WHERE UID LIKE 'gra974-%'") == 0,
                "cleanup must leave no fixture row behind");
    }

    // ------------------------------------------------------------------ helpers

    private static long enrollmentId(String uid, long offeringId) throws SQLException {
        return count("SELECT enrollment_id FROM enrollment WHERE uid='" + uid + "' AND offering_id="
                + offeringId);
    }

    private static int gradeCount(long enrollmentId) throws SQLException {
        return count("SELECT COUNT(*) FROM grade WHERE enrollment_id=" + enrollmentId);
    }

    private static double studentScore(String uid, String courseCode) throws SQLException {
        GradeSummaryDTO summary = new CourseQueryService().loadGrades(uid, YEAR, SEMESTER);
        for (GradeRecordDTO record : summary.getRecords()) {
            if (courseCode.equals(record.getCourseCode())) return record.getScore();
        }
        throw new AssertionError("course " + courseCode + " is not visible to " + uid);
    }

    /** A nullable numeric literal, so a NULL statistic stays NULL. */
    private static String number(String value) {
        return value == null ? "NULL" : value;
    }

    private static String op(int value) {
        return String.format("97400000-0000-0000-0000-%012d", value);
    }

    private static String instantText(String utcLiteral) {
        return DateTimeFormatter.ISO_INSTANT.format(
                Timestamp.valueOf(utcLiteral).toLocalDateTime().toInstant(ZoneOffset.UTC));
    }

    private static void verify(String name, CheckedRun action) throws Exception {
        action.run();
        System.out.println("PASS " + name);
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
                "Refusing grade approval test: the JDBC URL must target the guarded schema");
        require(GUARDED_DATABASE.equals(text("SELECT DATABASE()")),
                "Refusing grade approval test outside the guarded schema");
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

    private static <X extends Throwable> X expect(Class<X> type, ThrowingRun action, String message) {
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

    @FunctionalInterface
    private interface CheckedRun {
        void run() throws Exception;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static List<String> ids(GradeSubmissionPageDTO page) {
        List<String> values = new ArrayList<>();
        for (GradeSubmissionSummaryDTO summary : page.getItems()) {
            values.add(summary.getSubmissionId());
        }
        return values;
    }

    /** The 9747xx fixture identities of a result set, keeping the order it was returned in. */
    private static List<String> fixtures(List<String> ids) {
        List<String> mine = new ArrayList<>();
        for (String id : ids) {
            if (id.startsWith("9747")) mine.add(id);
        }
        return mine;
    }

    private static GradeSubmissionSummaryDTO summaryOf(GradeSubmissionPageDTO page, long submissionId) {
        for (GradeSubmissionSummaryDTO summary : page.getItems()) {
            if (Long.toString(submissionId).equals(summary.getSubmissionId())) return summary;
        }
        throw new AssertionError("submission " + submissionId + " is absent from " + ids(page));
    }

    private static List<String> studentIds(List<GradeSubmissionItemDTO> items) {
        List<String> values = new ArrayList<>();
        for (GradeSubmissionItemDTO item : items) {
            values.add(item.getStudentUid());
        }
        return values;
    }

    private static List<String> bands(List<GradeDistributionBucketDTO> distribution) {
        List<String> values = new ArrayList<>();
        for (GradeDistributionBucketDTO bucket : distribution) {
            values.add(bucket.getLabel() + "=" + bucket.getCount());
        }
        return values;
    }
}
