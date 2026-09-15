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
import java.util.HexFormat;

/**
 * Idempotent teacher-write audit log ({@code teacher_course_operation_log}, V005).
 *
 * <p>成功写入与业务变更在同一事务：{@code insert} 由调用方在当前事务里执行，主键
 * {@code (teacher_uid, operation_id)} 的重复键说明同一操作被并发重复提交，调用方据此回滚并重读
 * 已提交结果（重放或摘要冲突），而不是把它当成驱动错误。摘要与管理员侧完全一致：动作名加换行加
 * 规范化请求 JSON 的 SHA-256 十六进制小写。
 */
public class TeacherCourseOperationDAO {
    private static final Gson GSON = new Gson();

    public StoredOperation find(Connection connection, String teacherUid, String operationId)
            throws SQLException {
        String sql = "SELECT request_digest,response_json,result_code FROM teacher_course_operation_log"
                + " WHERE teacher_uid=? AND operation_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setString(2, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new StoredOperation(rows.getString("request_digest"),
                        rows.getString("response_json"), rows.getString("result_code"));
            }
        }
    }

    public void insert(Connection connection, String teacherUid, String operationId, String action,
                       String targetType, String targetId, String requestDigest, String requestJson,
                       String responseJson, String resultCode) throws SQLException {
        String sql = "INSERT INTO teacher_course_operation_log(teacher_uid,operation_id,action,"
                + "target_type,target_id,request_digest,request_json,response_json,result_code)"
                + " VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setString(2, operationId);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, requestDigest);
            statement.setString(7, requestJson);
            statement.setString(8, responseJson);
            statement.setString(9, resultCode);
            statement.executeUpdate();
        }
    }

    /** 与管理员侧相同的规范化摘要：{@code action + "\n" + GSON.toJson(request)} 的 SHA-256。 */
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

    public String json(Object value) {
        return GSON.toJson(value);
    }

    public <T> T decode(String responseJson, Type type) {
        return GSON.fromJson(responseJson, type);
    }

    /** 请求摘要、响应快照与结果码；{@code response_json} 只在成功写入时存在。 */
    public record StoredOperation(String requestDigest, String responseJson, String resultCode) {
    }
}
