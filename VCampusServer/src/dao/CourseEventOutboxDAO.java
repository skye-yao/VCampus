package dao;

import com.google.gson.Gson;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseTermDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CourseEventOutboxDAO {
    private static final Gson GSON = new Gson();

    public record OutboxEvent(long eventId, String uid, String eventType, int academicYear,
            int semester, Long offeringId, String payload, Instant createdAt) {
    }

    public List<OutboxEvent> pending(Connection connection, int limit) throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String sql = "SELECT event_id, uid, event_type, academic_year, semester, offering_id, "
                + "payload, created_at FROM course_event_outbox WHERE acked_at IS NULL "
                + "ORDER BY event_id ASC LIMIT ?";
        List<OutboxEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    long offeringId = rs.getLong("offering_id");
                    Long offering = rs.wasNull() ? null : offeringId;
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    events.add(new OutboxEvent(
                            rs.getLong("event_id"),
                            rs.getString("uid"),
                            rs.getString("event_type"),
                            rs.getInt("academic_year"),
                            rs.getInt("semester"),
                            offering,
                            rs.getString("payload"),
                            createdAt == null ? null : createdAt.toInstant()));
                }
            }
        }
        return events;
    }

    public void markAttempt(Connection connection, long eventId, Instant now) throws SQLException {
        String sql = "UPDATE course_event_outbox SET attempt_count = attempt_count + 1, "
                + "last_sent_at = ? WHERE event_id = ? AND acked_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    public boolean acknowledge(Connection connection, String uid, long eventId, Instant now)
            throws SQLException {
        String sql = "UPDATE course_event_outbox SET acked_at = ? "
                + "WHERE event_id = ? AND uid = ? AND acked_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setLong(2, eventId);
            statement.setString(3, uid);
            return statement.executeUpdate() > 0;
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    public void insert(Connection connection, String uid, CoursePushEventTypeDTO eventType,
                       CourseTermDTO term, long offeringId, Instant occurredAt,
                       Instant expiresAt, String message) throws SQLException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType.name());
        payload.put("offeringId", Long.toString(offeringId));
        payload.put("occurredAt", occurredAt.toString());
        if (expiresAt != null) payload.put("expiresAt", expiresAt.toString());
        payload.put("message", message);
        String sql = "INSERT INTO course_event_outbox(uid,event_type,academic_year,semester,"
                + "offering_id,payload,created_at) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setString(2, eventType.name());
            statement.setInt(3, term.getAcademicYear());
            statement.setInt(4, term.getSemester());
            statement.setLong(5, offeringId);
            statement.setString(6, GSON.toJson(payload));
            statement.setTimestamp(7, Timestamp.valueOf(
                    occurredAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.executeUpdate();
        }
    }
}
