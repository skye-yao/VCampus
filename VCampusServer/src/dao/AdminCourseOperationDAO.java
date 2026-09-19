package dao;

import com.google.gson.Gson;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

/**
* Data-access type for AdminCourseOperationDAO; caller-owned connections are never committed or rolled back here.
*/
public class AdminCourseOperationDAO {
    private static final Gson GSON = new Gson();

    /**
    * Finds  data.
    */
    public StoredOperation find(Connection connection, String adminUid, String operationId)
            throws SQLException {
        String sql = "SELECT request_digest,response_json FROM admin_course_operation_log"
                + " WHERE admin_uid=? AND operation_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adminUid);
            statement.setString(2, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new StoredOperation(rows.getString("request_digest"),
                        rows.getString("response_json"));
            }
        }
    }

    /**
    * Creates insert data.
    */
    public void insert(Connection connection, String adminUid, String operationId, String action,
                       String targetType, String targetId, String digest, Object request,
                       String resultCode, Object response, Instant completedAt)
            throws SQLException {
        insert(connection, adminUid, operationId, action, targetType, targetId, digest, request,
                resultCode, response, completedAt, null, false, null);
    }

    /** Compatible audit overload for mutations that permit explicit conflict overrides. */
    public void insert(Connection connection, String adminUid, String operationId, String action,
                       String targetType, String targetId, String digest, Object request,
                       String resultCode, Object response, Instant completedAt,
                       Object conflicts, boolean forced, String overrideReason) throws SQLException {
        String sql = "INSERT INTO admin_course_operation_log"
                + "(admin_uid,operation_id,action,target_type,target_id,request_digest,"
                + "request_json,conflict_snapshot_json,forced,override_reason,result_code,"
                + "response_json,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adminUid);
            statement.setString(2, operationId);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, digest);
            statement.setString(7, GSON.toJson(request));
            statement.setString(8, conflicts == null ? null : GSON.toJson(conflicts));
            statement.setBoolean(9, forced);
            statement.setString(10, overrideReason);
            statement.setString(11, resultCode);
            statement.setString(12, GSON.toJson(response));
            statement.setTimestamp(13, Timestamp.valueOf(
                    completedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.executeUpdate();
        }
    }

    /**
    * Handles the course-management responsibility of digest.
    */
    public String digest(String action, Object request) {
        String canonical = action + "\n" + GSON.toJson(request);
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
    * Handles the course-management responsibility of decode.
    */
    public <T> T decode(String responseJson, Type type) {
        return GSON.fromJson(responseJson, type);
    }

    /**
    * Internal course-management type StoredOperation.
    */
    public record StoredOperation(String requestDigest, String responseJson) {
    }
}
