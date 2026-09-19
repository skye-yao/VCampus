package service;

import com.google.gson.reflect.TypeToken;
import course.grade.GradeCalculator;
import course.grade.GradePointScale;
import dao.AdminCourseOperationDAO;
import dao.GradeApprovalDAO;
import dao.TeacherGradeBookDAO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeCorrectionChangeDTO;
import dto.course.admin.approval.GradeCorrectionComparisonDTO;
import dto.course.admin.approval.GradeDistributionBucketDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;
import dto.course.admin.approval.GradeSubmissionItemDTO;
import dto.course.admin.approval.GradeSubmissionPageDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
* Administrator review of teacher grade submissions.
*
* <p>A submission is a frozen batch: its header and items are never rewritten. Approval is
* all-or-nothing — it validates the batch, then upserts one current {@code grade} row per item in
* a single READ_COMMITTED transaction, publishing every row with one transaction timestamp before
* marking the submission APPROVED and committing. Rejection only stamps the submission's review
* fields, so the current projection is never touched and a corrected batch can follow.
*
* <p>The validation depends on the batch. A batch captured with its scheme (V007) is judged by what
* it captured: it must cover exactly its own students, each recomputed against that captured scheme,
* and enrollments added after submission neither join the batch nor invalidate it. A legacy batch
* without a scheme snapshot keeps the original rule — its items must equal the offering's eligible
* active enrollments — and no recomputation is invented for it.
*
* <p>Whole-batch decisions only: a single item can neither be approved nor rejected on its own.
*/
public class GradeApprovalService {
    private static final String TARGET_TYPE = "GRADE_SUBMISSION";
    private static final String OK = "OK";
    private static final int MAX_TEXT = 500;
    private static final int DUPLICATE_KEY = 1062;
    private static final double TOLERANCE = 0.005;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);
    private static final BigDecimal SIXTY = BigDecimal.valueOf(60);
    /** The five score bands, always in this order and always emitted even at count zero. */
    private static final List<String> BAND_LABELS =
            List.of("90-100", "80-89", "70-79", "60-69", "0-59");
    private static final Type RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<GradeSubmissionDetailDTO>>() { }.getType();

    private final GradeApprovalDAO dao;
    private final AdminCourseOperationDAO operations;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of GradeApprovalService.
    */
    public GradeApprovalService() {
        this(new GradeApprovalDAO(), new AdminCourseOperationDAO(), Clock.systemUTC());
    }

    /**
    * Handles the course-management responsibility of GradeApprovalService.
    */
    public GradeApprovalService(GradeApprovalDAO dao, AdminCourseOperationDAO operations,
                                Clock clock) {
        this.dao = dao;
        this.operations = operations;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- reads

    /**
    * Lists GradeSubmissionsPage data.
    */
    public GradeSubmissionPageDTO listGradeSubmissionsPage(ApprovalStatusDTO status, int page,
                                                           int size) {
        if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("每页条数必须为 1 至 100");
        ApprovalStatusDTO filter = status == null ? ApprovalStatusDTO.PENDING : status;
        try (Connection connection = DBUtil.getConnection()) {
            long total = dao.countSubmissions(connection, filter);
            List<GradeSubmissionSummaryDTO> items =
                    dao.listSubmissions(connection, filter, (page - 1) * size, size);
            return new GradeSubmissionPageDTO(items, total, page, size);
        } catch (SQLException failure) {
            throw new DatabaseException("查询成绩提交失败", failure);
        }
    }

    /** Thin adapter kept for the bare-list contract; the handler consumes the paged form. */
    public List<GradeSubmissionSummaryDTO> listGradeSubmissions(ApprovalStatusDTO status, int page,
                                                                int size) {
        return listGradeSubmissionsPage(status, page, size).getItems();
    }

    /**
    * Obtains GradeSubmission data.
    */
    public GradeSubmissionDetailDTO getGradeSubmission(String submissionId) {
        long id = AdminOperationTransaction.parseId(submissionId, "submissionId");
        try (Connection connection = DBUtil.getConnection()) {
            GradeApprovalDAO.SubmissionRow row = dao.findSubmission(connection, id);
            if (row == null) throw new NotFoundException("成绩提交不存在");
            return detail(connection, row, dao.findItems(connection, id));
        } catch (SQLException failure) {
            throw new DatabaseException("查询成绩提交详情失败", failure);
        }
    }

    // ------------------------------------------------------------------ review

    /**
    * Handles the course-management responsibility of review.
    */
    public AdminOperationResultDTO<GradeSubmissionDetailDTO> review(String adminUid,
            ApprovalDecisionRequestDTO raw) {
        String admin = adminUid == null ? null : adminUid.trim();
        AdminOperationTransaction.validate(admin, raw == null ? null : raw.getOperationId());
        ApprovalDecisionRequestDTO request = validate(raw);
        String action = AdminCourseActions.REVIEW_GRADE_SUBMISSION;
        String digest = operations.digest(action, request);
        long submissionId = AdminOperationTransaction.parseId(request.getRequestId(), "submissionId");
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                AdminCourseOperationDAO.StoredOperation stored =
                        operations.find(connection, admin, request.getOperationId());
                AdminOperationResultDTO<GradeSubmissionDetailDTO> result;
                if (stored != null) {
                    result = replay(stored, digest);
                } else {
                    // The submission lock serializes same-batch decisions, but a lost racing
                    // operation may already have committed, so the log is re-read under it.
                    dao.lockSubmission(connection, submissionId);
                    stored = operations.find(connection, admin, request.getOperationId());
                    result = stored != null ? replay(stored, digest)
                            : decide(connection, admin, request, action, digest, submissionId);
                }
                connection.commit();
                committed = true;
                return result;
            } catch (RuntimeException | SQLException failure) {
                inFlight = failure;
                rollback(connection, failure);
                throw failure;
            } finally {
                if (!committed) {
                    rollback(connection, inFlight);
                }
                restoreAutoCommit(connection, originalAutoCommit, inFlight);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("成绩审批事务执行失败", failure);
        }
    }

    private AdminOperationResultDTO<GradeSubmissionDetailDTO> decide(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            long submissionId) throws SQLException {
        GradeApprovalDAO.SubmissionRow row = dao.findSubmission(connection, submissionId);
        if (row == null) throw new NotFoundException("成绩提交不存在");
        dao.lockItems(connection, submissionId);
        List<GradeApprovalDAO.ItemRow> items = dao.findItems(connection, submissionId);
        if (row.status() != ApprovalStatusDTO.PENDING) {
            throw conflict(connection, "成绩提交已被处理，请刷新后重试", row, items);
        }
        if (row.version() != request.getExpectedVersion()) {
            throw conflict(connection, "成绩提交版本已变化，请刷新后重试", row, items);
        }
        // Validation constrains approval only. Declining a frozen batch must stay possible even
        // when that batch could never be approved.
        if (!request.isApproved()) {
            return persistRejection(connection, admin, request, action, digest, row, items);
        }
        String problem = validationProblem(connection, row, items);
        if (problem != null) throw conflict(connection, problem, row, items);
        return persistApproval(connection, admin, request, action, digest, row, items);
    }

    private AdminOperationResultDTO<GradeSubmissionDetailDTO> persistRejection(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            GradeApprovalDAO.SubmissionRow row, List<GradeApprovalDAO.ItemRow> items)
            throws SQLException {
        Instant now = clock.instant();
        int affected = dao.updateDecision(connection, row.submissionId(), request.getExpectedVersion(),
                ApprovalStatusDTO.REJECTED, admin, now, request.getReviewComment());
        if (affected == 0) throw new ConflictException("成绩提交状态已变化，请刷新后重试");
        GradeApprovalDAO.SubmissionRow decided = dao.findSubmission(connection, row.submissionId());
        GradeSubmissionDetailDTO entity = detail(connection, decided, items);
        AdminOperationResultDTO<GradeSubmissionDetailDTO> result = new AdminOperationResultDTO<>(
                request.getOperationId(), OK, "成绩提交已驳回", entity, List.of());
        return auditOrRecover(connection, admin, request, action, digest, result);
    }

    private AdminOperationResultDTO<GradeSubmissionDetailDTO> persistApproval(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            GradeApprovalDAO.SubmissionRow row, List<GradeApprovalDAO.ItemRow> items)
            throws SQLException {
        Instant now = clock.instant();
        // One timestamp shared by every row: the whole batch becomes visible atomically. Older
        // projections are never unpublished, so a partial failure leaves the previous state intact.
        Timestamp publishTime = GradeApprovalDAO.timestamp(now);
        for (GradeApprovalDAO.ItemRow item : items) {
            dao.upsertGrade(connection, item, publishTime);
        }
        int affected = dao.updateDecision(connection, row.submissionId(), request.getExpectedVersion(),
                ApprovalStatusDTO.APPROVED, admin, now, request.getReviewComment());
        if (affected == 0) throw new ConflictException("成绩提交状态已变化，请刷新后重试");
        GradeApprovalDAO.SubmissionRow decided = dao.findSubmission(connection, row.submissionId());
        GradeSubmissionDetailDTO entity = detail(connection, decided, items);
        AdminOperationResultDTO<GradeSubmissionDetailDTO> result = new AdminOperationResultDTO<>(
                request.getOperationId(), OK, "成绩提交已通过", entity, List.of());
        return auditOrRecover(connection, admin, request, action, digest, result);
    }

    /**
    * The operation log is the only thing two decisions that hold <em>different</em> submission locks
    * still share, so it is where a reused operation id is detected. A losing insert waits for the
    * winner to commit; every local write is then discarded and the committed operation is read back
    * as a replay or a digest conflict instead of surfacing as a driver error.
    */
    private AdminOperationResultDTO<GradeSubmissionDetailDTO> auditOrRecover(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            AdminOperationResultDTO<GradeSubmissionDetailDTO> result) throws SQLException {
        try {
            operations.insert(connection, admin, request.getOperationId(), action, TARGET_TYPE,
                    request.getRequestId(), digest, request, result.getOutcomeCode(), result,
                    clock.instant(), null, false, null);
            return result;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != DUPLICATE_KEY) throw failure;
            rollback(connection, failure);
            AdminCourseOperationDAO.StoredOperation winner =
                    operations.find(connection, admin, request.getOperationId());
            if (winner == null) throw failure;
            return replay(winner, digest);
        }
    }

    private AdminOperationResultDTO<GradeSubmissionDetailDTO> replay(
            AdminCourseOperationDAO.StoredOperation stored, String digest) {
        if (!digest.equals(stored.requestDigest())) {
            throw new ConflictException("operationId 已用于不同的业务请求");
        }
        return operations.decode(stored.responseJson(), RESULT_TYPE);
    }

    // -------------------------------------------------------------- validation

    /**
    * Null-safe and rounded whole-batch validation: every component and the grade point must be in
    * range, the covered set must be approvable and the header snapshot must agree with what the
    * items recompute. A {@code NULL} stored statistic matches only when no item carries a non-null
    * score.
    *
    * <p>Which covered set is approvable depends on the batch: a batch that captured its scheme
    * (V007) must publish exactly the students it captured, while a legacy batch with a NULL
    * snapshot keeps the original "must cover every eligible enrollment" rule verbatim.
    *
    * @return the reason the batch cannot be approved, or {@code null} when it is approvable
    */
    private String validationProblem(Connection connection, GradeApprovalDAO.SubmissionRow row,
                                     List<GradeApprovalDAO.ItemRow> items) throws SQLException {
        Set<Long> covered = new LinkedHashSet<>();
        for (GradeApprovalDAO.ItemRow item : items) {
            covered.add(item.enrollmentId());
            String problem = range(item);
            if (problem != null) return problem;
        }
        if (row.schemeSnapshotJson() == null) {
            // 旧批次没有方案快照：保留原有验证与原有失败信息，不伪造可重算性。
            if (!covered.equals(dao.findEligibleEnrollmentIds(connection, row.offeringId()))) {
                return "成绩明细未覆盖教学班全部有效选课学生";
            }
        } else {
            String problem = capturedProblem(connection, row, items);
            if (problem != null) return problem;
        }
        if (row.totalCount() != items.size()) {
            return "成绩提交人数与成绩明细数量不一致";
        }
        List<BigDecimal> scores = new ArrayList<>();
        int failed = 0;
        for (GradeApprovalDAO.ItemRow item : items) {
            if (item.score() == null) continue;
            scores.add(item.score());
            if (item.score().compareTo(SIXTY) < 0) failed++;
        }
        if (scores.isEmpty()) {
            if (row.averageScore() != null || row.maxScore() != null || row.minScore() != null) {
                return "没有总评成绩时平均分、最高分和最低分必须为空";
            }
        } else {
            if (!matches(row.averageScore(), mean(scores))) return "平均分与成绩明细不一致";
            if (!matches(row.maxScore(), scores.stream().max(BigDecimal::compareTo).orElseThrow())) {
                return "最高分与成绩明细不一致";
            }
            if (!matches(row.minScore(), scores.stream().min(BigDecimal::compareTo).orElseThrow())) {
                return "最低分与成绩明细不一致";
            }
        }
        if (row.failedCount() != failed) return "不及格人数与成绩明细不一致";
        return null;
    }

    /**
    * Validation of a batch that captured its scheme. Approval publishes <em>exactly</em> the
    * captured student set: a student who dropped after submission stays in the batch, and a
    * student who enrolled afterwards neither gets stuffed into the batch nor invalidates it (the
    * detail carries the count of such students, and the follow-up version picks them up).
    *
    * <p>What is still verified: every item's enrollment must belong to the offering whatever its
    * status, the captured identity must match the enrollment, and every item's stored component
    * scores, total and grade point must recompute from the captured scheme with the same pure
    * calculator the teacher's submission used.
    *
    * @return the reason the batch cannot be approved, or {@code null} when it is approvable
    */
    private String capturedProblem(Connection connection, GradeApprovalDAO.SubmissionRow row,
                                   List<GradeApprovalDAO.ItemRow> items) throws SQLException {
        GradeSchemeDTO scheme;
        try {
            scheme = TeacherGradeBookDAO.scheme(row.schemeSnapshotJson());
            GradeCalculator.validateScheme(scheme, true);
        } catch (IllegalArgumentException broken) {
            return "成绩方案快照非法: " + broken.getMessage();
        }
        for (GradeApprovalDAO.ItemRow item : items) {
            GradeApprovalDAO.EnrollmentRow enrollment =
                    dao.findEnrollment(connection, item.enrollmentId());
            if (enrollment == null || enrollment.offeringId() != row.offeringId()) {
                return "成绩明细包含不属于该教学班的选课记录";
            }
            if (item.snapshotUid() != null && !item.snapshotUid().equals(enrollment.uid())) {
                return "成绩明细的学生身份与选课记录不一致";
            }
            BigDecimal total;
            try {
                total = GradeCalculator.total(scheme, new GradeScoresDTO(item.dailyScore(),
                        item.midtermScore(), item.experimentScore(), item.finaltermScore()));
            } catch (IllegalArgumentException broken) {
                return "成绩明细与方案快照不一致: " + broken.getMessage();
            }
            if (total == null) {
                return "成绩明细缺少启用组成分数，无法按方案快照重算总评";
            }
            if (item.score() == null || item.score().compareTo(total) != 0) {
                return "总评与方案快照重算结果不一致";
            }
            BigDecimal point = GradePointScale.gradePointFor(total);
            if (item.gradePoint() == null || item.gradePoint().compareTo(point) != 0) {
                return "绩点与方案快照重算结果不一致";
            }
        }
        return null;
    }

    private static String range(GradeApprovalDAO.ItemRow item) {
        if (outOfRange(item.dailyScore(), HUNDRED)) return "平时成绩必须在 0 到 100 之间";
        if (outOfRange(item.midtermScore(), HUNDRED)) return "期中成绩必须在 0 到 100 之间";
        if (outOfRange(item.experimentScore(), HUNDRED)) return "实验成绩必须在 0 到 100 之间";
        if (outOfRange(item.finaltermScore(), HUNDRED)) return "期末成绩必须在 0 到 100 之间";
        if (outOfRange(item.score(), HUNDRED)) return "总评成绩必须在 0 到 100 之间";
        if (outOfRange(item.gradePoint(), FIVE)) return "绩点必须在 0 到 5 之间";
        return null;
    }

    private static boolean outOfRange(BigDecimal value, BigDecimal max) {
        return value != null && (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(max) > 0);
    }

    /**
    * The exact mean, carried to a scale far finer than the 0.005 tolerance, so the tolerance itself
    * is what absorbs the header's {@code DECIMAL(5,2)} rounding rather than a pre-rounded mean.
    */
    private static BigDecimal mean(List<BigDecimal> scores) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal score : scores) total = total.add(score);
        return total.divide(BigDecimal.valueOf(scores.size()), 10, RoundingMode.HALF_UP);
    }

    /** The stored snapshot absorbs {@code DECIMAL(5,2)} rounding through a 0.005 tolerance. */
    private static boolean matches(BigDecimal stored, BigDecimal recomputed) {
        if (stored == null) return false;
        return stored.subtract(recomputed).abs().compareTo(BigDecimal.valueOf(TOLERANCE)) <= 0;
    }

    // ---------------------------------------------------------------- mapping

    private GradeSubmissionDetailDTO detail(Connection connection, GradeApprovalDAO.SubmissionRow row,
                                            List<GradeApprovalDAO.ItemRow> items) throws SQLException {
        return new GradeSubmissionDetailDTO(GradeApprovalDAO.summary(row), distribution(items),
                mapItems(items), row.reviewedBy(), GradeApprovalDAO.instantText(row.reviewedAt()),
                row.reviewComment(), TeacherGradeBookDAO.scheme(row.schemeSnapshotJson()),
                row.baseSubmissionId() == null ? null : Long.toString(row.baseSubmissionId()),
                uncoveredCount(connection, row, items),
                comparison(connection, row, items));
    }

    /**
    * What this batch actually changed against the batch it was based on.
    *
    * <p>Both sides are read from their own {@code grade_submission_item} rows. That is the whole
    * point: the teacher's working copy has moved on since submission (or is about to), so a
    * "previous value" taken from it would be a fabricated history. A batch without a base, or whose
    * base can no longer be read, yields {@code null} rather than an invented comparison.
    *
    * <p>Only genuinely moved students are listed — including one who was added by this correction
    * (no previous item) and one who dropped between the two submissions (no current item).
    */
    private GradeCorrectionComparisonDTO comparison(Connection connection,
            GradeApprovalDAO.SubmissionRow row, List<GradeApprovalDAO.ItemRow> items)
            throws SQLException {
        if (row.baseSubmissionId() == null) return null;
        GradeApprovalDAO.SubmissionRow base = dao.findSubmission(connection, row.baseSubmissionId());
        if (base == null) return null;
        Map<Long, GradeApprovalDAO.ItemRow> previous = new LinkedHashMap<>();
        for (GradeApprovalDAO.ItemRow item : dao.findItems(connection, row.baseSubmissionId())) {
            previous.put(item.enrollmentId(), item);
        }
        Set<Long> current = new LinkedHashSet<>();
        List<GradeCorrectionChangeDTO> changes = new ArrayList<>();
        for (GradeApprovalDAO.ItemRow item : items) {
            current.add(item.enrollmentId());
            GradeApprovalDAO.ItemRow before = previous.get(item.enrollmentId());
            if (before == null || !sameItem(before, item)) {
                changes.add(new GradeCorrectionChangeDTO(
                        before == null ? null : mapItem(before), mapItem(item)));
            }
        }
        for (GradeApprovalDAO.ItemRow before : previous.values()) {
            if (!current.contains(before.enrollmentId())) {
                changes.add(new GradeCorrectionChangeDTO(mapItem(before), null));
            }
        }
        // The base's own status travels with the comparison: a resubmission after a rejection is
        // based on a batch that was never approved, and the admin surface must be able to say so.
        return new GradeCorrectionComparisonDTO(base.version(), base.status(),
                row.correctionReason(), changes);
    }

    /**
    * Every published value of one item: a student whose four component scores are identical but
    * whose total or grade point moved is still a change worth showing.
    */
    private static boolean sameItem(GradeApprovalDAO.ItemRow left, GradeApprovalDAO.ItemRow right) {
        return same(left.dailyScore(), right.dailyScore())
                && same(left.midtermScore(), right.midtermScore())
                && same(left.experimentScore(), right.experimentScore())
                && same(left.finaltermScore(), right.finaltermScore())
                && same(left.score(), right.score())
                && Objects.equals(left.gradeLevel(), right.gradeLevel())
                && same(left.gradePoint(), right.gradePoint());
    }

    /** {@code BigDecimal} equality by value, so {@code 88.50} and {@code 88.5} are not a change. */
    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    /**
    * The students the offering now has as normal enrollments but the batch never captured. It is
    * what the administrator's "尚未纳入已提交批次" hint counts; approving the batch neither adds
    * them nor refuses it, so the display is the only place the gap becomes visible.
    */
    private int uncoveredCount(Connection connection, GradeApprovalDAO.SubmissionRow row,
                               List<GradeApprovalDAO.ItemRow> items) throws SQLException {
        Set<Long> covered = new LinkedHashSet<>();
        for (GradeApprovalDAO.ItemRow item : items) {
            covered.add(item.enrollmentId());
        }
        int uncovered = 0;
        for (Long enrollmentId : dao.findEligibleEnrollmentIds(connection, row.offeringId())) {
            if (!covered.contains(enrollmentId)) uncovered++;
        }
        return uncovered;
    }

    private static List<GradeSubmissionItemDTO> mapItems(List<GradeApprovalDAO.ItemRow> items) {
        List<GradeSubmissionItemDTO> mapped = new ArrayList<>();
        for (GradeApprovalDAO.ItemRow item : items) {
            mapped.add(mapItem(item));
        }
        return mapped;
    }

    private static GradeSubmissionItemDTO mapItem(GradeApprovalDAO.ItemRow item) {
        return new GradeSubmissionItemDTO(Long.toString(item.enrollmentId()),
                item.studentUid(), item.studentName(), number(item.dailyScore()),
                number(item.midtermScore()), number(item.experimentScore()),
                number(item.finaltermScore()), number(item.score()), item.gradeLevel(),
                number(item.gradePoint()));
    }

    /**
    * The five bands always present in order, counting the items whose rounded-to-2dp score falls in
    * each. An item without a score belongs to no band, so the bucket total may trail the item count.
    */
    private static List<GradeDistributionBucketDTO> distribution(
            List<GradeApprovalDAO.ItemRow> items) {
        int[] counts = new int[BAND_LABELS.size()];
        for (GradeApprovalDAO.ItemRow item : items) {
            if (item.score() == null) continue;
            double score = item.score().setScale(2, RoundingMode.HALF_UP).doubleValue();
            if (score >= 90) counts[0]++;
            else if (score >= 80) counts[1]++;
            else if (score >= 70) counts[2]++;
            else if (score >= 60) counts[3]++;
            else counts[4]++;
        }
        List<GradeDistributionBucketDTO> buckets = new ArrayList<>();
        for (int index = 0; index < BAND_LABELS.size(); index++) {
            buckets.add(new GradeDistributionBucketDTO(BAND_LABELS.get(index), counts[index]));
        }
        return buckets;
    }

    private static Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private ConflictException conflict(Connection connection, String message,
                                       GradeApprovalDAO.SubmissionRow row,
                                       List<GradeApprovalDAO.ItemRow> items) throws SQLException {
        return new ConflictException(message, detail(connection, row, items));
    }

    // ------------------------------------------------------------- validation

    private static ApprovalDecisionRequestDTO validate(ApprovalDecisionRequestDTO request) {
        long submissionId = AdminOperationTransaction.parseId(request.getRequestId(), "submissionId");
        int version = AdminOperationTransaction.version(request.getExpectedVersion());
        // Grade approval judges a frozen batch whole, so there is no conflict to override.
        if (request.isForce()) throw new IllegalArgumentException("成绩审批不支持强制覆盖");
        String reviewComment = bounded(request.getReviewComment(), "审批意见");
        if (!request.isApproved() && reviewComment == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        return new ApprovalDecisionRequestDTO(request.getOperationId(), Long.toString(submissionId),
                version, request.isApproved(), false, null, reviewComment);
    }

    private static String bounded(String value, String label) {
        String text = AdminOperationTransaction.blankToNull(value);
        if (text != null && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException(label + "不能超过 " + MAX_TEXT + " 字符");
        }
        return text;
    }

    // ------------------------------------------------------------ transaction

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight,
    *  and a failed rollback is swallowed rather than replacing an escaping {@link Error}. */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
        }
    }

    /** Never replaces an in-flight failure with a connection-cleanup failure. */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit,
                                          Throwable inFlight) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoration) {
            if (inFlight != null) {
                inFlight.addSuppressed(restoration);
            } else {
                throw new DatabaseException("成绩审批事务执行失败", restoration);
            }
        }
    }

    /**
    * Internal course-management type NotFoundException.
    */
    public static class NotFoundException extends RuntimeException {
        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) { super(message); }
    }

    /**
    * Internal course-management type ConflictException.
    */
    public static class ConflictException extends RuntimeException {
        private final GradeSubmissionDetailDTO entity;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message) { this(message, null); }

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, GradeSubmissionDetailDTO entity) {
            super(message);
            this.entity = entity;
        }

        /**
        * Obtains Entity data.
        */
        public GradeSubmissionDetailDTO getEntity() { return entity; }
    }
}
