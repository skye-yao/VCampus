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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* Data-access type for CourseEventOutboxDAO; caller-owned connections are never committed or rolled back here.
*/
public class CourseEventOutboxDAO {
    private static final Gson GSON = new Gson();

    /**
    * Internal course-management type OutboxEvent.
    */
    public record OutboxEvent(long eventId, String uid, String eventType, int academicYear,
            int semester, Long offeringId, String payload, Instant createdAt) {
    }

    /**
    * 仅返回 {@code onlineUids} 中账号的未 ACK 事件，按 event_id 升序有界返回。
    *
    * <p>空集合直接返回空列表，避免生成 {@code IN ()} 这类非法 SQL；离线账号的事件不会占用
    * 批次名额，因此离线积压不会饿死后续在线账号的事件。
    */
    public List<OutboxEvent> pending(Connection connection, Collection<String> onlineUids, int limit)
            throws SQLException {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (onlineUids == null || onlineUids.isEmpty()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT event_id, uid, event_type, academic_year, semester, offering_id, "
                + "payload, created_at FROM course_event_outbox WHERE acked_at IS NULL "
                + "AND uid IN (");
        for (int index = 0; index < onlineUids.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY event_id ASC LIMIT ?");
        List<OutboxEvent> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String uid : onlineUids) {
                statement.setString(index++, uid);
            }
            statement.setInt(index, limit);
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

    /**
    * Persists markAttempt data.
    */
    public void markAttempt(Connection connection, long eventId, Instant now) throws SQLException {
        String sql = "UPDATE course_event_outbox SET attempt_count = attempt_count + 1, "
                + "last_sent_at = ? WHERE event_id = ? AND acked_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setLong(2, eventId);
            statement.executeUpdate();
        }
    }

    /**
    * 事件所有者的 ACK 幂等：首次与重复 ACK 均返回 true；非所有者或不存在的行返回 false。
    *
    * <p>先按 (event_id, uid) 判定归属，再仅在 {@code acked_at IS NULL} 时写入时间戳；重复
    * ACK 保留首次确认时间且仍视为成功，避免同账号多连接竞争时第二个客户端收到失败响应。
    */
    public boolean acknowledge(Connection connection, String uid, long eventId, Instant now)
            throws SQLException {
        if (uid == null) {
            return false;
        }
        String ownerSql = "SELECT acked_at FROM course_event_outbox "
                + "WHERE event_id = ? AND uid = ?";
        try (PreparedStatement statement = connection.prepareStatement(ownerSql)) {
            statement.setLong(1, eventId);
            statement.setString(2, uid);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                if (rs.getTimestamp("acked_at") != null) {
                    return true;
                }
            }
        }
        String updateSql = "UPDATE course_event_outbox SET acked_at = ? "
                + "WHERE event_id = ? AND uid = ? AND acked_at IS NULL";
        try (PreparedStatement statement = connection.prepareStatement(updateSql)) {
            statement.setTimestamp(1, timestamp(now));
            statement.setLong(2, eventId);
            statement.setString(3, uid);
            statement.executeUpdate();
            return true;
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    /**
    * Creates insert data.
    */
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
