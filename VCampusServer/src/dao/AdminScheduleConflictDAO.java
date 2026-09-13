package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the effective schedule: occurrences that still occupy resources, with the ones replaced
 * by an ACTIVE temporary adjustment removed and the ACTIVE adjustments themselves added at their
 * replacement time and resources.
 *
 * <p>The overlap predicate is exactly {@code start_at < candidate_end AND end_at > candidate_start}
 * so that two slots which merely touch at a boundary instant are not reported as overlapping.
 */
public class AdminScheduleConflictDAO {
    /**
     * Effective occurrences overlapping the candidate window. Both branches carry the required
     * half-open predicate; only the second one is expressed against the adjustment's own columns.
     */
    private static final String OVERLAPPING = "SELECT o.id AS occurrence_id,"
            + " a.arrangement_id,a.offering_id,a.teacher_uid,a.assistant_uid,a.classroom_id,"
            + " o.start_at,o.end_at"
            + " FROM course_occurrence o"
            + " JOIN course_schedule_rule r ON r.id=o.rule_id"
            + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
            + " WHERE o.plan_id=? AND start_at < ? AND end_at > ?"
            + " AND r.status='ACTIVE' AND a.status='ACTIVE'"
            + " AND NOT EXISTS (SELECT 1 FROM course_schedule_adjustment j"
            + " WHERE j.original_occurrence_id=o.id AND j.status='ACTIVE')"
            + " AND a.arrangement_id<>?"
            + " UNION ALL"
            + " SELECT o.id AS occurrence_id,"
            + " a.arrangement_id,a.offering_id,j.teacher_uid,j.assistant_uid,j.classroom_id,"
            + " j.start_at_utc AS start_at,j.end_at_utc AS end_at"
            + " FROM course_schedule_adjustment j"
            + " JOIN course_occurrence o ON o.id=j.original_occurrence_id"
            + " JOIN course_schedule_rule r ON r.id=o.rule_id"
            + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
            + " WHERE j.status='ACTIVE' AND o.plan_id=?"
            + " AND r.status='ACTIVE' AND a.status='ACTIVE'"
            + " AND j.start_at_utc < ? AND j.end_at_utc > ?"
            + " AND a.arrangement_id<>?";

    public List<EffectiveOccurrence> overlapping(Connection connection, long planId,
                                                 Timestamp candidateStart, Timestamp candidateEnd,
                                                 Long excludedArrangementId) throws SQLException {
        long excluded = excludedArrangementId == null ? -1L : excludedArrangementId;
        List<EffectiveOccurrence> occurrences = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(OVERLAPPING)) {
            statement.setLong(1, planId);
            statement.setTimestamp(2, candidateEnd);
            statement.setTimestamp(3, candidateStart);
            statement.setLong(4, excluded);
            statement.setLong(5, planId);
            statement.setTimestamp(6, candidateEnd);
            statement.setTimestamp(7, candidateStart);
            statement.setLong(8, excluded);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) occurrences.add(map(rows));
            }
        }
        return occurrences;
    }

    private static EffectiveOccurrence map(ResultSet rows) throws SQLException {
        long classroomId = rows.getLong("classroom_id");
        Long classroom = rows.wasNull() ? null : classroomId;
        return new EffectiveOccurrence(rows.getLong("occurrence_id"),
                rows.getLong("arrangement_id"), rows.getLong("offering_id"),
                rows.getString("teacher_uid"), rows.getString("assistant_uid"), classroom,
                rows.getTimestamp("start_at"), rows.getTimestamp("end_at"));
    }

    /** One occurrence as the conflict engine must see it, regardless of which table produced it. */
    public record EffectiveOccurrence(long occurrenceId, long arrangementId, long offeringId,
                                      String teacherUid, String assistantUid, Long classroomId,
                                      Timestamp startAt, Timestamp endAt) {
    }
}
