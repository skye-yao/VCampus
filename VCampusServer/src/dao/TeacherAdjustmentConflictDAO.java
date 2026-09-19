package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
* Teacher-side reads for the shared adjustment conflict check: the offering's term (which resolves
* the published plan and calendar domain), whether a target date is a real teaching day of that
* calendar, and which normally enrolled students of one offering also sit in another.
*
* <p>Student risk is limited to {@code enrollment.status = 2} in <em>both</em> offerings, so a
* dropped enrollment never produces a conflict and students of other classes are never listed.
*/
public class TeacherAdjustmentConflictDAO {

    /** The term an offering belongs to; the same term identifies its published plan. */
    public record OfferingTerm(int academicYear, int semester) {
    }

    /**
    * Handles the course-management responsibility of offeringTerm.
    */
    public OfferingTerm offeringTerm(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT academic_year,semester FROM course_offering WHERE offering_id=?")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new OfferingTerm(rows.getInt("academic_year"), rows.getInt("semester"));
            }
        }
    }

    /**
    * True only when the date belongs to the given calendar and is marked as a teaching day, so a
    * target date from another term's calendar or a non-teaching day is never checked as if it
    * could carry a class.
    */
    public boolean isTeachingDate(Connection connection, long calendarDateId, long calendarId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT is_teaching_day FROM calendar_date WHERE id=? AND calendar_id=?")) {
            statement.setLong(1, calendarDateId);
            statement.setLong(2, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean("is_teaching_day");
            }
        }
    }

    /**
    * UIDs of the students normally enrolled in both offerings, ordered by UID so repeated checks
    * of the same pair report the same sequence. A uid in only one of the two offerings, or with a
    * dropped enrollment on either side, is not returned.
    */
    public List<String> sharedNormalStudents(Connection connection, long offeringId,
            long otherOfferingId) throws SQLException {
        String sql = "SELECT e1.uid FROM enrollment e1"
                + " JOIN enrollment e2 ON e2.uid=e1.uid AND e2.offering_id=?"
                + " WHERE e1.offering_id=? AND e1.status=2 AND e2.status=2"
                + " ORDER BY e1.uid";
        List<String> uids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, otherOfferingId);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) uids.add(rows.getString("uid"));
            }
        }
        return List.copyOf(uids);
    }
}
