package dao;

import com.google.gson.Gson;
import dto.course.teacher.GradeScoresDTO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教师成绩变更审计（{@code teacher_grade_change_log}）的写入口，独立成文件是因为它与工作副本
 * 的读写职责不同：工作副本只保存“现在是什么样”，这里保存“谁在哪个版本把什么改成了什么”。
 *
 * <p>班级级变更（权重/启用变化）的 {@code enrollment_id} 为 NULL；学生级变更带选课记录 ID。
 * {@code before_json} 在首次创建时为 NULL，绝不伪造一份“改之前”的快照；{@code after_json}
 * 记录服务器按当时方案算出的总评与绩点，便于审批与追责时核对。
 *
 * <p>所有写入都在调用方的事务内执行，与业务变更同生共死；{@code reason} 目前只有更正草稿会有值。
 */
public class TeacherGradeAuditDAO {
    /** 快照里的键名与 {@link dto.course.teacher.GradeScoresDTO} 的字段顺序一致。 */
    private static final String[] SCORE_KEYS = {"daily", "midterm", "experiment", "finalterm"};
    private static final Gson GSON = new Gson();

    /**
     * 写一条变更日志。{@code beforeJson}/{@code afterJson}/ {@code reason} 允许为 null，
     * {@code enrollmentId} 为 null 表示班级级变更。
     */
    public void insert(Connection connection, String teacherUid, long offeringId, Long enrollmentId,
                       String operationId, int bookRevision, String action, String beforeJson,
                       String afterJson, String reason) throws SQLException {
        String sql = "INSERT INTO teacher_grade_change_log(teacher_uid,offering_id,enrollment_id,"
                + "operation_id,book_revision,action,before_json,after_json,reason)"
                + " VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setLong(2, offeringId);
            if (enrollmentId == null) statement.setNull(3, Types.BIGINT);
            else statement.setLong(3, enrollmentId);
            statement.setString(4, operationId);
            statement.setInt(5, bookRevision);
            statement.setString(6, action);
            if (beforeJson == null) statement.setNull(7, Types.VARCHAR);
            else statement.setString(7, beforeJson);
            if (afterJson == null) statement.setNull(8, Types.VARCHAR);
            else statement.setString(8, afterJson);
            if (reason == null) statement.setNull(9, Types.VARCHAR);
            else statement.setString(9, reason);
            statement.executeUpdate();
        }
    }

    /**
     * 学生级快照：学生、四项分数与服务器按当时方案算出的总评/绩点。缺失分数保持 JSON null，
     * 不能写成 0；总评算不出来（权重未配齐或缺启用项）时省略这两个键，不写伪造的数字。
     */
    public static String scoreSnapshot(long enrollmentId, GradeScoresDTO scores,
                                       BigDecimal totalScore, BigDecimal gradePoint) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enrollmentId", Long.toString(enrollmentId));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SCORE_KEYS[0], scores == null ? null : scores.getDailyScore());
        values.put(SCORE_KEYS[1], scores == null ? null : scores.getMidtermScore());
        values.put(SCORE_KEYS[2], scores == null ? null : scores.getExperimentScore());
        values.put(SCORE_KEYS[3], scores == null ? null : scores.getFinaltermScore());
        snapshot.put("scores", values);
        if (totalScore != null) snapshot.put("totalScore", totalScore);
        if (gradePoint != null) snapshot.put("gradePoint", gradePoint);
        return GSON.toJson(snapshot);
    }
}
