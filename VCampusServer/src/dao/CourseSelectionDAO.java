package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
* Data-access type for CourseSelectionDAO; caller-owned connections are never committed or rolled back here.
*/
public class CourseSelectionDAO {
    /**
    * Locks the database rows for StudentProfile.
    */
    public void lockStudentProfile(Connection connection, String uid) throws SQLException {
        String sql = "SELECT sap.profile_id FROM student_academic_profile sap"
                + " JOIN tbl_user u ON u.UID = sap.uid AND u.role = 2"
                + " WHERE sap.uid = ? AND sap.status = 'ACTIVE' FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MissingRowException("student profile");
            }
        }
    }

    /**
    * Locks the database rows for OfferingsAscending.
    */
    public List<Long> lockOfferingsAscending(Connection connection,
                                             Collection<Long> offeringIds) throws SQLException {
        List<Long> sorted = offeringIds.stream().distinct().sorted().toList();
        List<Long> locked = new ArrayList<>();
        String sql = "SELECT offering_id FROM course_offering WHERE offering_id = ? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Long id : sorted) {
                statement.setLong(1, id);
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next()) locked.add(rows.getLong(1));
                }
            }
        }
        return List.copyOf(locked);
    }

    /**
    * Determines whether hasActiveWaiters holds.
    */
    public boolean hasActiveWaiters(Connection connection, long offeringId) throws SQLException {
        return exists(connection, "SELECT 1 FROM course_waitlist"
                + " WHERE offering_id = ? AND status = 'WAITING' LIMIT 1", offeringId);
    }

    /**
    * Determines whether hasActiveWaitlist holds.
    */
    public boolean hasActiveWaitlist(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT 1 FROM course_waitlist WHERE uid = ? AND offering_id = ?"
                + " AND status IN ('WAITING','OFFERED') LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
    * Finds EnrolledCourseAndTimeConflicts data.
    */
    public List<Long> findEnrolledCourseAndTimeConflicts(Connection connection, String uid,
                                                          int academicYear, int semester,
                                                          long offeringId, long schedulePlanId)
            throws SQLException {
        String sql = "SELECT DISTINCT e.offering_id FROM enrollment e"
                + " JOIN course_offering target ON target.offering_id = ?"
                + " LEFT JOIN course_offering_conflict conflict ON conflict.plan_id = ?"
                + "   AND ((conflict.course_offering_a_id = e.offering_id"
                + "         AND conflict.course_offering_b_id = target.offering_id)"
                + "     OR (conflict.course_offering_b_id = e.offering_id"
                + "         AND conflict.course_offering_a_id = target.offering_id))"
                + " WHERE e.uid = ? AND e.academic_year = ? AND e.semester = ?"
                + " AND e.status = 2"
                + " AND (e.course_id = target.course_id OR conflict.plan_id IS NOT NULL)"
                + " ORDER BY e.offering_id";
        List<Long> conflicts = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setLong(2, schedulePlanId);
            statement.setString(3, uid);
            statement.setInt(4, academicYear);
            statement.setInt(5, semester);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) conflicts.add(rows.getLong(1));
            }
        }
        return List.copyOf(conflicts);
    }

    /**
    * Persists upsertPlan data.
    */
    public void upsertPlan(Connection connection, String uid, long offeringId,
                           String state, String reason) throws SQLException {
        String sql = "INSERT INTO course_plan_item(uid,offering_id,status,last_failure_reason)"
                + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),"
                + " last_failure_reason=VALUES(last_failure_reason),updated_at=CURRENT_TIMESTAMP(6)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            statement.setString(3, state);
            statement.setString(4, reason);
            statement.executeUpdate();
        }
    }

    /**
    * Removes or cancels deletePlan data.
    */
    public void deletePlan(Connection connection, String uid, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM course_plan_item WHERE uid=? AND offering_id=?")) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            statement.executeUpdate();
        }
    }

    /**
    * Handles the course-management responsibility of enroll.
    */
    public void enroll(Connection connection, String uid, long offeringId) throws SQLException {
        String sql = "INSERT INTO enrollment"
                + "(offering_id,course_id,academic_year,semester,uid,status,select_time,drop_time)"
                + " SELECT offering_id,course_id,academic_year,semester,?,2,CURRENT_TIMESTAMP(6),NULL"
                + " FROM course_offering WHERE offering_id=?"
                + " ON DUPLICATE KEY UPDATE status=2,select_time=CURRENT_TIMESTAMP(6),drop_time=NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            if (statement.executeUpdate() == 0) throw new MissingRowException("offering");
        }
    }

    /**
    * Removes or cancels drop data.
    */
    public void drop(Connection connection, String uid, long offeringId, Instant now)
            throws SQLException {
        String sql = "UPDATE enrollment SET status=3,drop_time=?"
                + " WHERE uid=? AND offering_id=? AND status=2";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(now.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.setString(2, uid);
            statement.setLong(3, offeringId);
            if (statement.executeUpdate() != 1) throw new MissingRowException("active enrollment");
        }
    }

    /**
    * Handles the course-management responsibility of changeEnrolledCount.
    */
    public void changeEnrolledCount(Connection connection, long offeringId, int delta)
            throws SQLException {
        String sql = "UPDATE course_offering o SET o.enrolled_count=o.enrolled_count+?"
                + " WHERE o.offering_id=? AND o.enrolled_count+? >= 0"
                + " AND (? <= 0 OR o.enrolled_count+? + (SELECT COUNT(*) FROM course_waitlist w"
                + "   WHERE w.offering_id=o.offering_id AND w.status='OFFERED'"
                + "     AND w.expires_at>UTC_TIMESTAMP(6)) <= o.capacity)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, delta);
            statement.setLong(2, offeringId);
            statement.setInt(3, delta);
            statement.setInt(4, delta);
            statement.setInt(5, delta);
            if (statement.executeUpdate() != 1) {
                throw new CapacityInvariantException("capacity invariant rejected count change");
            }
        }
    }

    /**
    * Handles the course-management responsibility of offering.
    */
    public OfferingRow offering(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT offering_id,course_id,academic_year,semester,status,capacity,enrolled_count"
                + " FROM course_offering WHERE offering_id=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MissingRowException("offering");
                return new OfferingRow(rows.getLong("offering_id"), rows.getLong("course_id"),
                        rows.getInt("academic_year"), rows.getInt("semester"),
                        rows.getInt("status"), rows.getInt("capacity"),
                        rows.getInt("enrolled_count"));
            }
        }
    }

    /**
    * Handles the course-management responsibility of window.
    */
    public WindowRow window(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT win.schedule_plan_id,win.plan_open_at,win.selection_close_at,"
                + " win.selection_open_at,win.drop_deadline FROM course_selection_window win"
                + " JOIN schedule_plan sp ON sp.id=win.schedule_plan_id AND sp.status='PUBLISHED'"
                + " WHERE win.academic_year=? AND win.semester=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new MissingRowException("selection window");
                return new WindowRow(rows.getLong("schedule_plan_id"),
                        instant(rows, "plan_open_at"), instant(rows, "selection_open_at"),
                        instant(rows, "selection_close_at"), instant(rows, "drop_deadline"));
            }
        }
    }

    /**
    * Handles the course-management responsibility of eligible.
    */
    public boolean eligible(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT 1 FROM course_offering o JOIN course c ON c.course_id=o.course_id"
                + " JOIN student_academic_profile sap ON sap.uid=? AND sap.status='ACTIVE'"
                + " WHERE o.offering_id=?"
                + " AND (c.allow_cross_major=1 OR EXISTS (SELECT 1 FROM course_major cm"
                + "   WHERE cm.course_id=c.course_id AND cm.major_id=sap.major_id))"
                + " AND (NOT EXISTS (SELECT 1 FROM course_year cy0 WHERE cy0.course_id=c.course_id)"
                + "   OR EXISTS (SELECT 1 FROM course_year cy WHERE cy.course_id=c.course_id"
                + "     AND cy.year=(o.academic_year-sap.cohort_year+1)))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
    * Handles the course-management responsibility of planState.
    */
    public String planState(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT status FROM course_plan_item WHERE uid=? AND offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
    * Handles the course-management responsibility of enrolled.
    */
    public boolean enrolled(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT 1 FROM enrollment WHERE uid=? AND offering_id=? AND status=2 LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
    * Handles the course-management responsibility of activeOfferReservations.
    */
    public int activeOfferReservations(Connection connection, long offeringId)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM course_waitlist WHERE offering_id=?"
                + " AND status='OFFERED' AND expires_at>UTC_TIMESTAMP(6)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static boolean exists(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    /**
    * Internal course-management type OfferingRow.
    */
    public record OfferingRow(long offeringId, long courseId, int academicYear, int semester,
                              int status, int capacity, int enrolledCount) {
    }

    /**
    * Internal course-management type WindowRow.
    */
    public record WindowRow(long schedulePlanId, Instant planOpenAt, Instant selectionOpenAt,
                            Instant selectionCloseAt, Instant dropDeadline) {
    }

    /**
    * Internal course-management type MissingRowException.
    */
    public static class MissingRowException extends SQLException {
        /**
        * Handles the course-management responsibility of MissingRowException.
        */
        public MissingRowException(String object) {
            super("Missing " + object);
        }
    }

    /**
    * Internal course-management type CapacityInvariantException.
    */
    public static class CapacityInvariantException extends SQLException {
        /**
        * Handles the course-management responsibility of CapacityInvariantException.
        */
        public CapacityInvariantException(String message) {
            super(message);
        }
    }
}
