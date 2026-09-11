package dao;

import com.google.gson.Gson;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseTermDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

public class CourseOperationDAO {
    private static final Gson GSON = new Gson();

    public StoredOperation find(Connection connection, String uid, String operationId)
            throws SQLException {
        String sql = "SELECT request_digest,response_json FROM course_operation_log"
                + " WHERE uid=? AND operation_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setString(2, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new StoredOperation(rows.getString("request_digest"),
                        GSON.fromJson(rows.getString("response_json"),
                                CourseMutationResultDTO.class));
            }
        }
    }

    public void insert(Connection connection, String uid, String operationId, String action,
                       long offeringId, String digest, CourseMutationResultDTO result)
            throws SQLException {
        String sql = "INSERT INTO course_operation_log"
                + "(uid,operation_id,action,offering_id,request_digest,result_code,response_json)"
                + " VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setString(2, operationId);
            statement.setString(3, action);
            statement.setLong(4, offeringId);
            statement.setString(5, digest);
            statement.setString(6, result.getOutcomeCode());
            statement.setString(7, GSON.toJson(result));
            statement.executeUpdate();
        }
    }

    public String digest(String action, CourseTermDTO term, long offeringId) {
        String canonical = action + "\n" + term.getAcademicYear() + "\n"
                + term.getSemester() + "\n" + offeringId;
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public record StoredOperation(String requestDigest, CourseMutationResultDTO result) {
    }
}
