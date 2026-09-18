package dao;

import dto.course.admin.approval.ApprovalStatusDTO;
import dto.course.admin.approval.GradeSubmissionSummaryDTO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads, locks and writes of the grade-submission aggregate. Every mutation belongs to a
 * caller-owned transaction; {@link #upsertGrade} is the write seam a test overrides to prove that a
 * failure anywhere in the decision rolls the whole transaction back.
 *
 * <p>A submission and its items are frozen history, so this DAO never rewrites them. Approval only
 * upserts the current {@code grade} projection by {@code enrollment_id} and stamps the submission
 * decision; the database's {@code UNIQUE KEY uk_grade_enrollment(enrollment_id)} is what makes that
 * upsert well-defined.
 */
public class GradeApprovalDAO {
    private static final String SUBMISSION_COLUMNS =
            "s.submission_id,s.offering_id,c.course_name,o.offering_code,s.version,s.submitted_by,"
            + "u.name AS teacher_name,s.submitted_at,s.status,s.reviewed_by,s.reviewed_at,"
            + "s.review_comment,s.average_score,s.max_score,s.min_score,s.failed_count,s.total_count,"
            + "s.scheme_snapshot_json,s.roster_digest,s.base_submission_id,s.submission_kind,"
            + "s.correction_reason";

    private static final String SUBMISSION_JOINS =
            " FROM grade_submission s"
            + " JOIN course_offering o ON o.offering_id=s.offering_id"
            + " JOIN course c ON c.course_id=o.course_id"
            + " JOIN tbl_user u ON u.UID=s.submitted_by";

    // ------------------------------------------------------------------- reads

    public SubmissionRow findSubmission(Connection connection, long submissionId) throws SQLException {
        String sql = "SELECT " + SUBMISSION_COLUMNS + SUBMISSION_JOINS + " WHERE s.submission_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? submissionRow(rows) : null;
            }
        }
    }

    public List<GradeSubmissionSummaryDTO> listSubmissions(Connection connection,
                                                           ApprovalStatusDTO status, int offset,
                                                           int limit) throws SQLException {
        String sql = "SELECT " + SUBMISSION_COLUMNS + SUBMISSION_JOINS + " WHERE s.status=?"
                + " ORDER BY s.submitted_at DESC,s.submission_id DESC LIMIT ? OFFSET ?";
        List<GradeSubmissionSummaryDTO> summaries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) summaries.add(summary(submissionRow(rows)));
            }
        }
        return List.copyOf(summaries);
    }

    public long countSubmissions(Connection connection, ApprovalStatusDTO status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM grade_submission WHERE status=?")) {
            statement.setString(1, status.name());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    /**
     * Items joined with their student identity and ordered by student UID, as the detail requires.
     * A new batch shows the identity captured at submission; a legacy item with no snapshot keeps
     * the historical join, so no identity is invented for batches written before V007.
     */
    public List<ItemRow> findItems(Connection connection, long submissionId) throws SQLException {
        String sql = "SELECT i.item_id,i.submission_id,i.enrollment_id,"
                + "COALESCE(i.student_uid_snapshot,e.uid) AS student_uid,"
                + "COALESCE(i.student_name_snapshot,u.name) AS student_name,"
                + "i.student_uid_snapshot,i.daily_score,"
                + "i.midterm_score,i.experiment_score,i.finalterm_score,i.score,i.grade_level,"
                + "i.grade_point FROM grade_submission_item i"
                + " JOIN enrollment e ON e.enrollment_id=i.enrollment_id"
                + " JOIN tbl_user u ON u.UID=e.uid"
                + " WHERE i.submission_id=? ORDER BY e.uid";
        List<ItemRow> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(itemRow(rows));
            }
        }
        return List.copyOf(items);
    }

    /** The offering's currently eligible active enrollments, i.e. {@code enrollment.status = 2}. */
    public Set<Long> findEligibleEnrollmentIds(Connection connection, long offeringId)
            throws SQLException {
        Set<Long> enrollments = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enrollment_id FROM enrollment WHERE offering_id=? AND status=2")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) enrollments.add(rows.getLong("enrollment_id"));
            }
        }
        return enrollments;
    }

    /**
     * One enrollment row regardless of its status: a batch keeps the students who dropped after
     * submission, so membership is judged by the offering, not by {@code status = 2}. Missing
     * rows return {@code null}; foreign keys make a deleted enrollment unreachable in practice.
     */
    public EnrollmentRow findEnrollment(Connection connection, long enrollmentId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT offering_id,uid FROM enrollment WHERE enrollment_id=?")) {
            statement.setLong(1, enrollmentId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next()
                        ? new EnrollmentRow(enrollmentId, rows.getLong("offering_id"),
                                rows.getString("uid"))
                        : null;
            }
        }
    }

    // ------------------------------------------------------------------- locks

    public void lockSubmission(Connection connection, long submissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT submission_id FROM grade_submission WHERE submission_id=? FOR UPDATE")) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    /** Ascending primary-key order, so two concurrent decisions can never form a lock cycle. */
    public void lockItems(Connection connection, long submissionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT item_id FROM grade_submission_item WHERE submission_id=?"
                        + " ORDER BY item_id FOR UPDATE")) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // Locking requires walking the result set.
                }
            }
        }
    }

    // ------------------------------------------------------------------ writes

    /**
     * Pins the correction version being decided. Unlike the schedule-adjustment request, the version
     * column is the batch's correction version fixed by {@code uk_grade_submission_offering_version},
     * so it is never bumped here.
     */
    public int updateDecision(Connection connection, long submissionId, int expectedVersion,
                              ApprovalStatusDTO status, String reviewerUid, Instant reviewedAt,
                              String reviewComment) throws SQLException {
        String sql = "UPDATE grade_submission SET status=?,reviewed_by=?,reviewed_at=?,"
                + "review_comment=? WHERE submission_id=? AND version=? AND status='PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, reviewerUid);
            statement.setTimestamp(3, timestamp(reviewedAt));
            if (reviewComment == null) statement.setNull(4, Types.VARCHAR);
            else statement.setString(4, reviewComment);
            statement.setLong(5, submissionId);
            statement.setInt(6, expectedVersion);
            return statement.executeUpdate();
        }
    }

    /**
     * Upserts one student's current projection by {@code enrollment_id}. Every nullable component is
     * assigned exactly, so a missing component stays {@code NULL} rather than becoming zero, and the
     * row is published with the decision's single transaction timestamp.
     *
     * <p>Overridable seam: a failure here must roll back the projection, the submission decision and
     * the audit row together.
     */
    public void upsertGrade(Connection connection, ItemRow item, Timestamp publishTime)
            throws SQLException {
        String sql = "INSERT INTO grade (enrollment_id, daily_score, midterm_score,"
                + " finalterm_score, experiment_score, score, grade_level,"
                + " grade_point, is_published, publish_time)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?)"
                + " ON DUPLICATE KEY UPDATE"
                + " daily_score=VALUES(daily_score), midterm_score=VALUES(midterm_score),"
                + " finalterm_score=VALUES(finalterm_score),"
                + " experiment_score=VALUES(experiment_score), score=VALUES(score),"
                + " grade_level=VALUES(grade_level), grade_point=VALUES(grade_point),"
                + " is_published=1, publish_time=VALUES(publish_time)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, item.enrollmentId());
            decimal(statement, 2, item.dailyScore());
            decimal(statement, 3, item.midtermScore());
            decimal(statement, 4, item.finaltermScore());
            decimal(statement, 5, item.experimentScore());
            decimal(statement, 6, item.score());
            if (item.gradeLevel() == null) statement.setNull(7, Types.TINYINT);
            else statement.setInt(7, item.gradeLevel());
            decimal(statement, 8, item.gradePoint());
            statement.setTimestamp(9, publishTime);
            statement.executeUpdate();
        }
    }

    private static void decimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.DECIMAL);
        else statement.setBigDecimal(index, value);
    }

    // ----------------------------------------------------------------- helpers

    private static SubmissionRow submissionRow(ResultSet rows) throws SQLException {
        long baseSubmissionId = rows.getLong("base_submission_id");
        Long base = rows.wasNull() ? null : baseSubmissionId;
        return new SubmissionRow(rows.getLong("submission_id"), rows.getLong("offering_id"),
                rows.getString("course_name"), rows.getString("offering_code"),
                rows.getInt("version"), rows.getString("submitted_by"),
                rows.getString("teacher_name"), rows.getTimestamp("submitted_at"),
                ApprovalStatusDTO.valueOf(rows.getString("status")), rows.getString("reviewed_by"),
                rows.getTimestamp("reviewed_at"), rows.getString("review_comment"),
                rows.getBigDecimal("average_score"), rows.getBigDecimal("max_score"),
                rows.getBigDecimal("min_score"), rows.getInt("failed_count"),
                rows.getInt("total_count"), rows.getString("scheme_snapshot_json"),
                rows.getString("roster_digest"), base, rows.getString("submission_kind"),
                rows.getString("correction_reason"));
    }

    private static ItemRow itemRow(ResultSet rows) throws SQLException {
        int level = rows.getInt("grade_level");
        Integer gradeLevel = rows.wasNull() ? null : level;
        return new ItemRow(rows.getLong("item_id"), rows.getLong("submission_id"),
                rows.getLong("enrollment_id"), rows.getString("student_uid"),
                rows.getString("student_name"), rows.getString("student_uid_snapshot"),
                rows.getBigDecimal("daily_score"), rows.getBigDecimal("midterm_score"),
                rows.getBigDecimal("experiment_score"), rows.getBigDecimal("finalterm_score"),
                rows.getBigDecimal("score"), gradeLevel, rows.getBigDecimal("grade_point"));
    }

    /** A NULL primitive statistic is reported as {@code 0.0}, the summary DTO being primitive. */
    public static GradeSubmissionSummaryDTO summary(SubmissionRow row) {
        return new GradeSubmissionSummaryDTO(Long.toString(row.submissionId()),
                Long.toString(row.offeringId()), row.courseName(), row.offeringCode(),
                row.version(), row.submittedBy(), row.teacherName(), row.totalCount(),
                stored(row.averageScore()), stored(row.maxScore()), stored(row.minScore()),
                row.failedCount(), row.status(), instantText(row.submittedAt()));
    }

    private static double stored(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    /** The column stores a UTC wall clock, so the instant must be re-anchored at UTC. */
    public static Instant instant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    public static String instantText(Timestamp value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant(value));
    }

    public static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /**
     * {@code correctionReason} is the reason the teacher wrote when this batch was started as a
     * correction; it is {@code NULL} for ordinary submissions and for resubmissions. The approval
     * detail shows it next to the comparison, and it is read from the batch itself — never from the
     * mutable working copy, which the teacher may already have moved on from.
     */
    public record SubmissionRow(long submissionId, long offeringId, String courseName,
                                String offeringCode, int version, String submittedBy,
                                String teacherName, Timestamp submittedAt, ApprovalStatusDTO status,
                                String reviewedBy, Timestamp reviewedAt, String reviewComment,
                                BigDecimal averageScore, BigDecimal maxScore, BigDecimal minScore,
                                int failedCount, int totalCount, String schemeSnapshotJson,
                                String rosterDigest, Long baseSubmissionId, String submissionKind,
                                String correctionReason) {
    }

    /**
     * One submitted item. {@code studentUid}/{@code studentName} are the display identity
     * (captured snapshot when the batch has one, otherwise the historical enrollment join);
     * {@code snapshotUid} is the raw captured value, {@code null} for pre-V007 batches, and is
     * what approval checks against the enrollment.
     */
    public record ItemRow(long itemId, long submissionId, long enrollmentId, String studentUid,
                          String studentName, String snapshotUid, BigDecimal dailyScore,
                          BigDecimal midtermScore, BigDecimal experimentScore,
                          BigDecimal finaltermScore, BigDecimal score, Integer gradeLevel,
                          BigDecimal gradePoint) {
    }

    /** One enrollment row regardless of status; approval needs membership and current identity. */
    public record EnrollmentRow(long enrollmentId, long offeringId, String uid) {
    }
}
