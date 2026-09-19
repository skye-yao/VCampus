package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
* Data-access type for CourseWaitlistDAO; caller-owned connections are never committed or rolled back here.
*/
public class CourseWaitlistDAO {
    /**
    * Locks the database rows for StudentProfile.
    */
    public StudentRow lockStudentProfile(Connection connection, String uid) throws SQLException {
        String sql = "SELECT u.role,sap.status FROM student_academic_profile sap"
                + " JOIN tbl_user u ON u.UID=sap.uid WHERE sap.uid=? FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new StudentRow(rows.getInt("role"), rows.getString("status"));
            }
        }
    }

    /**
    * Finds  data.
    */
    public WaitlistRow find(Connection connection, String uid, long offeringId)
            throws SQLException {
        return one(connection, "SELECT waitlist_id,uid,offering_id,status,queue_time,"
                + "offered_at,expires_at FROM course_waitlist"
                + " WHERE uid=? AND offering_id=?", statement -> {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
        });
    }

    /**
    * Finds ForUpdate data.
    */
    public WaitlistRow findForUpdate(Connection connection, String uid, long offeringId)
            throws SQLException {
        return one(connection, "SELECT waitlist_id,uid,offering_id,status,queue_time,"
                + "offered_at,expires_at FROM course_waitlist"
                + " WHERE uid=? AND offering_id=? FOR UPDATE", statement -> {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
        });
    }

    /**
    * Handles the course-management responsibility of head.
    */
    public WaitlistRow head(Connection connection, long offeringId) throws SQLException {
        return one(connection, "SELECT waitlist_id,uid,offering_id,status,queue_time,"
                + "offered_at,expires_at FROM course_waitlist"
                + " WHERE offering_id=? AND status='WAITING'"
                + " ORDER BY queue_time,waitlist_id LIMIT 1",
                statement -> statement.setLong(1, offeringId));
    }

    /**
    * Handles the course-management responsibility of headForUpdate.
    */
    public WaitlistRow headForUpdate(Connection connection, long offeringId) throws SQLException {
        return one(connection, "SELECT waitlist_id,uid,offering_id,status,queue_time,"
                + "offered_at,expires_at FROM course_waitlist"
                + " WHERE offering_id=? AND status='WAITING'"
                + " ORDER BY queue_time,waitlist_id LIMIT 1 FOR UPDATE",
                statement -> statement.setLong(1, offeringId));
    }

    /**
    * Persists saveWaiting data.
    */
    public void saveWaiting(Connection connection, String uid, long offeringId, Instant queuedAt)
            throws SQLException {
        String sql = "INSERT INTO course_waitlist(uid,offering_id,status,queue_time,offered_at,expires_at)"
                + " VALUES(?,?,'WAITING',?,NULL,NULL) ON DUPLICATE KEY UPDATE"
                + " status='WAITING',queue_time=VALUES(queue_time),offered_at=NULL,expires_at=NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            statement.setTimestamp(3, timestamp(queuedAt));
            statement.executeUpdate();
        }
    }

    /**
    * Handles the course-management responsibility of nextQueueTime.
    */
    public Instant nextQueueTime(Connection connection, long offeringId, Instant requested)
            throws SQLException {
        String sql = "SELECT MAX(queue_time) FROM course_waitlist"
                + " WHERE offering_id=? AND status='WAITING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                Timestamp tail = rows.getTimestamp(1);
                if (tail == null) return requested;
                Instant tailTime = tail.toLocalDateTime().toInstant(ZoneOffset.UTC);
                return tailTime.isBefore(requested) ? requested : tailTime.plusNanos(1_000);
            }
        }
    }

    /**
    * Persists markOffered data.
    */
    public void markOffered(Connection connection, long waitlistId, Instant offeredAt,
                            Instant expiresAt) throws SQLException {
        update(connection, "UPDATE course_waitlist SET status='OFFERED',offered_at=?,expires_at=?"
                + " WHERE waitlist_id=? AND status='WAITING'", statement -> {
            statement.setTimestamp(1, timestamp(offeredAt));
            statement.setTimestamp(2, timestamp(expiresAt));
            statement.setLong(3, waitlistId);
        });
    }

    /**
    * Persists markStatus data.
    */
    public void markStatus(Connection connection, long waitlistId, String expected,
                           String next) throws SQLException {
        update(connection, "UPDATE course_waitlist SET status=?,offered_at=NULL,expires_at=NULL"
                + " WHERE waitlist_id=? AND status=?", statement -> {
            statement.setString(1, next);
            statement.setLong(2, waitlistId);
            statement.setString(3, expected);
        });
    }

    /**
    * Handles the course-management responsibility of activeReservations.
    */
    public int activeReservations(Connection connection, long offeringId, Instant now)
            throws SQLException {
        String sql = "SELECT COUNT(*) FROM course_waitlist WHERE offering_id=?"
                + " AND status='OFFERED' AND expires_at>?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setTimestamp(2, timestamp(now));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    /**
    * Handles the course-management responsibility of overdueOffers.
    */
    public List<WaitlistRow> overdueOffers(Connection connection, Instant now, int limit)
            throws SQLException {
        return many(connection, "SELECT waitlist_id,uid,offering_id,status,queue_time,"
                + "offered_at,expires_at FROM course_waitlist"
                + " WHERE status='OFFERED' AND expires_at<=?"
                + " ORDER BY expires_at,waitlist_id LIMIT ?", statement -> {
            statement.setTimestamp(1, timestamp(now));
            statement.setInt(2, limit);
        });
    }

    /**
    * Handles the course-management responsibility of waitingAfterWindowClose.
    */
    public List<WaitlistRow> waitingAfterWindowClose(Connection connection, Instant now, int limit)
            throws SQLException {
        return many(connection, "SELECT w.waitlist_id,w.uid,w.offering_id,w.status,w.queue_time,"
                + "w.offered_at,w.expires_at FROM course_waitlist w"
                + " JOIN course_offering o ON o.offering_id=w.offering_id"
                + " JOIN course_selection_window win ON win.academic_year=o.academic_year"
                + " AND win.semester=o.semester WHERE w.status='WAITING'"
                + " AND win.selection_close_at<=? ORDER BY w.queue_time,w.waitlist_id LIMIT ?",
                statement -> {
                    statement.setTimestamp(1, timestamp(now));
                    statement.setInt(2, limit);
                });
    }

    /**
    * Handles the course-management responsibility of vacantOfferingIds.
    */
    public List<Long> vacantOfferingIds(Connection connection, Instant now, int limit)
            throws SQLException {
        String sql = "SELECT DISTINCT o.offering_id FROM course_offering o"
                + " JOIN course_selection_window win ON win.academic_year=o.academic_year"
                + " AND win.semester=o.semester"
                + " JOIN schedule_plan sp ON sp.id=win.schedule_plan_id AND sp.status='PUBLISHED'"
                + " WHERE o.status=2 AND win.selection_open_at<=? AND win.selection_close_at>?"
                + " AND EXISTS (SELECT 1 FROM course_waitlist w WHERE w.offering_id=o.offering_id"
                + " AND w.status='WAITING')"
                + " AND o.enrolled_count+(SELECT COUNT(*) FROM course_waitlist r"
                + " WHERE r.offering_id=o.offering_id AND r.status='OFFERED' AND r.expires_at>?)"
                + " < o.capacity ORDER BY o.offering_id LIMIT ?";
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setTimestamp(2, timestamp(now));
            statement.setTimestamp(3, timestamp(now));
            statement.setInt(4, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return List.copyOf(ids);
    }

    /**
    * Handles the course-management responsibility of offeringInfo.
    */
    public OfferingInfo offeringInfo(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT offering_id,academic_year,semester,status FROM course_offering"
                + " WHERE offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new OfferingInfo(rows.getLong("offering_id"),
                        rows.getInt("academic_year"), rows.getInt("semester"),
                        rows.getInt("status"));
            }
        }
    }

    private static WaitlistRow one(Connection connection, String sql, Binder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? row(rows) : null;
            }
        }
    }

    private static List<WaitlistRow> many(Connection connection, String sql, Binder binder)
            throws SQLException {
        List<WaitlistRow> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(row(rows));
            }
        }
        return List.copyOf(result);
    }

    private static WaitlistRow row(ResultSet rows) throws SQLException {
        return new WaitlistRow(rows.getLong("waitlist_id"), rows.getString("uid"),
                rows.getLong("offering_id"), rows.getString("status"),
                instant(rows, "queue_time"), instant(rows, "offered_at"),
                instant(rows, "expires_at"));
    }

    private static void update(Connection connection, String sql, Binder binder)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("waitlist state changed concurrently");
            }
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.valueOf(value.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    @FunctionalInterface
    /**
    * Internal course-management type Binder.
    */
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    /**
    * Internal course-management type StudentRow.
    */
    public record StudentRow(int role, String status) {
    }

    /**
    * Internal course-management type OfferingInfo.
    */
    public record OfferingInfo(long offeringId, int academicYear, int semester, int status) {
    }

    /**
    * Internal course-management type WaitlistRow.
    */
    public record WaitlistRow(long waitlistId, String uid, long offeringId, String status,
                              Instant queueTime, Instant offeredAt, Instant expiresAt) {
    }
}
