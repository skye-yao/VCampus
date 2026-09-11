package dao;

import com.google.gson.Gson;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseTermDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

public class CourseEventOutboxDAO {
    private static final Gson GSON = new Gson();

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
