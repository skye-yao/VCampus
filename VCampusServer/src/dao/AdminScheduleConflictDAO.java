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


    /** Uses the same current-plan priority as CourseScheduleDAO, including its legacy fallback. */
    public Long publishedPlanId(Connection connection, int academicYear, int semester) throws SQLException {
        String current = "SELECT sp.id FROM teaching_calendar cal"
                + " LEFT JOIN schedule_plan sp ON sp.id=cal.current_schedule_plan_id"
                + " AND sp.calendar_id=cal.id AND sp.status='PUBLISHED'"
                + " WHERE cal.academic_year=? AND cal.semester=?"
                + " AND cal.current_schedule_plan_id IS NOT NULL"
                + " ORDER BY cal.version DESC,cal.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(current)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    long id = rows.getLong(1);
                    if (rows.wasNull()) throw new PublishedPlanUnavailableException();
                    return id;
                }
            }
        }
        String legacy = "SELECT sp.id FROM course_selection_window win"
                + " LEFT JOIN schedule_plan sp ON sp.id=win.schedule_plan_id AND sp.status='PUBLISHED'"
                + " WHERE win.academic_year=? AND win.semester=?";
        try (PreparedStatement statement = connection.prepareStatement(legacy)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long id = rows.getLong(1);
                if (rows.wasNull()) throw new PublishedPlanUnavailableException();
                return id;
            }
        }
    }

    /** Effective target windows feed the existing overlapping() engine for student checks. */
    public List<StudentWindow> offeringWindows(Connection connection, long planId, long offeringId)
            throws SQLException {
        String sql = "SELECT o.week_no,COALESCE(q.new_weekday,r.weekday) AS weekday,"
                + "COALESCE(q.new_start_period,r.start_period) AS start_period,"
                + "COALESCE(q.new_end_period,r.end_period) AS end_period,"
                + "COALESCE(j.start_at_utc,o.start_at) AS start_at,"
                + "COALESCE(j.end_at_utc,o.end_at) AS end_at"
                + " FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + " LEFT JOIN course_schedule_adjustment j"
                + " ON j.original_occurrence_id=o.id AND j.status='ACTIVE'"
                + " LEFT JOIN course_schedule_adjustment_request q ON q.request_id=j.request_id"
                + " WHERE o.plan_id=? AND a.offering_id=? AND r.status='ACTIVE' AND a.status='ACTIVE'"
                + " ORDER BY start_at,o.id";
        List<StudentWindow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, planId);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new StudentWindow(rows.getInt("week_no"),
                        rows.getInt("weekday"), rows.getInt("start_period"), rows.getInt("end_period"),
                        rows.getTimestamp("start_at"), rows.getTimestamp("end_at")));
            }
        }
        return List.copyOf(result);
    }

    public record StudentWindow(int week, int dayOfWeek, int startPeriod, int endPeriod,
                                Timestamp startAt, Timestamp endAt) { }

    public static class PublishedPlanUnavailableException extends SQLException {
        public PublishedPlanUnavailableException() { super("Current published schedule plan is unavailable"); }
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
