package dao;

import dto.course.teacher.TeacherApplicationDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 「我的申请」的统一读与已读回执写。
 *
 * <p>两张事实表 {@code course_schedule_adjustment_request} 与 {@code grade_submission} 的形状不同
 * （状态字母表、被处理时间列、撤销列都不一样），所以分页**必须在 SQL 里合并**，不能各取一页再到
 * Java 里拼：先在 Java 合并就再也说不清 {@code totalCount} 与「第 2 页」覆盖的是哪个集合。这里用
 * {@code UNION ALL} 把两条各自限定本人的分支合起来，排序与 LIMIT/OFFSET 都落在合并后的集合上。
 *
 * <p>类型列是每条分支里的**字符串字面量**，不是拼接出来的表名：分支只有两条，都由调用方从
 * {@link TeacherApplicationDTO} 的类型白名单里选定，任何用户输入都不会进 SQL 文本。
 *
 * <p>已读回执在同一个查询里 LEFT JOIN 出来（主键 {@code (teacher_uid, application_type,
 * application_id)}），因此列表上的 {@code unread} 与详情、标记已读看到的判据是同一个键，不存在
 * 「列表说未读、点进去说已读」的第二种算法。
 */
public class TeacherApplicationDAO {
    private static final String ADJUSTMENT = TeacherApplicationDTO.SCHEDULE_ADJUSTMENT;
    private static final String SUBMISSION = TeacherApplicationDTO.GRADE_SUBMISSION;

    /**
     * 一条合并后的申请行。{@code handledAt} 是**结果产生时间**：调课申请取
     * {@code reviewed_at ?? withdrawn_at}（撤销是本人的终态，它只有 withdrawn_at），成绩提交取
     * {@code reviewed_at}；两者在未处理时都是 null，状态键这时回落到 {@code submittedAt}。
     */
    public record Row(String type, long id, long offeringId, String courseName, String offeringCode,
                      String status, Timestamp submittedAt, Timestamp handledAt,
                      String reviewComment, String seenStateKey) {
    }

    // ------------------------------------------------------------------ 合并分页

    /**
     * 合并后的稳定分页：{@code (submittedAt DESC, type, id DESC)}。{@code type} 作为第二排序键让
     * 「同一时刻提交的两条不同表记录」也有确定顺序，{@code id DESC} 让同一张表里的并列同样是确定的。
     *
     * @param type   {@link TeacherApplicationDTO#SCHEDULE_ADJUSTMENT}、
     *               {@link TeacherApplicationDTO#GRADE_SUBMISSION} 或 null（不限类型）
     * @param status 该类型白名单内的状态，或 null（不限状态）
     */
    public List<Row> list(Connection connection, String teacherUid, String type, String status,
            int offset, int limit) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT u.application_type,u.application_id,u.offering_id,u.course_name,"
                + "u.offering_code,u.status,u.submitted_at,u.handled_at,u.review_comment,"
                + "u.seen_state_key FROM (");
        List<String> parameters = new ArrayList<>();
        boolean adjustment = type == null || ADJUSTMENT.equals(type);
        boolean submission = type == null || SUBMISSION.equals(type);
        boolean first = true;
        // 状态过滤对两条分支各写一次、原样带同一个值：成绩提交那一支由自己的 CHECK 约束保证
        // 不会出现 WITHDRAWN，所以它自然匹配 0 行，不需要在 Java 里为“哪种状态属于哪张表”分支。
        if (adjustment) {
            sql.append(ADJUSTMENT_BRANCH);
            if (status != null) sql.append(" AND r.status=?");
            parameters.add(teacherUid);
            if (status != null) parameters.add(status);
            first = false;
        }
        if (submission) {
            if (!first) sql.append(" UNION ALL ");
            sql.append(SUBMISSION_BRANCH);
            if (status != null) sql.append(" AND s.status=?");
            parameters.add(teacherUid);
            if (status != null) parameters.add(status);
        }
        sql.append(") u ORDER BY u.submitted_at DESC,u.application_type,u.application_id DESC"
                + " LIMIT ? OFFSET ?");

        List<Row> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String parameter : parameters) statement.setString(index++, parameter);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(row(result));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * 合并后的总行数。两条分支各自按主键计数、再相加，就是合并集合的元素个数——两条分支之间
     * 不可能出现同一行（一张申请只属于一张事实表），所以不需要为了计数再跑一次 UNION。
     */
    public long count(Connection connection, String teacherUid, String type, String status)
            throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> parameters = new ArrayList<>();
        boolean adjustment = type == null || ADJUSTMENT.equals(type);
        boolean submission = type == null || SUBMISSION.equals(type);
        if (adjustment) {
            sql.append("(SELECT COUNT(*) FROM course_schedule_adjustment_request r"
                    + " WHERE r.requested_by=?");
            parameters.add(teacherUid);
            if (status != null) {
                sql.append(" AND r.status=?");
                parameters.add(status);
            }
            sql.append(')');
        } else {
            sql.append("0");
        }
        sql.append(" + ");
        if (submission) {
            sql.append("(SELECT COUNT(*) FROM grade_submission s WHERE s.submitted_by=?");
            parameters.add(teacherUid);
            if (status != null) {
                sql.append(" AND s.status=?");
                parameters.add(status);
            }
            sql.append(')');
        } else {
            sql.append("0");
        }
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (String parameter : parameters) statement.setString(index++, parameter);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    /** {@code UNION ALL} 的调课申请分支：状态、被处理时间与已读回执都按这张表的列取。 */
    private static final String ADJUSTMENT_BRANCH = "SELECT '" + ADJUSTMENT
            + "' AS application_type,r.request_id AS application_id,r.offering_id,"
            + "c.course_name,o.offering_code,r.status,r.submitted_at,"
            + "COALESCE(r.reviewed_at,r.withdrawn_at) AS handled_at,r.review_comment,"
            + "k.seen_state_key AS seen_state_key"
            + " FROM course_schedule_adjustment_request r"
            + " JOIN course_offering o ON o.offering_id=r.offering_id"
            + " JOIN course c ON c.course_id=o.course_id"
            + " LEFT JOIN teacher_application_read k ON k.teacher_uid=r.requested_by"
            + "     AND k.application_type='" + ADJUSTMENT + "'"
            + "     AND k.application_id=r.request_id"
            + " WHERE r.requested_by=?";

    /** {@code UNION ALL} 的成绩提交分支：没有 withdrawn_at，被处理时间只能是 reviewed_at。 */
    private static final String SUBMISSION_BRANCH = "SELECT '" + SUBMISSION
            + "' AS application_type,s.submission_id AS application_id,s.offering_id,"
            + "c.course_name,o.offering_code,s.status,s.submitted_at,s.reviewed_at AS handled_at,"
            + "s.review_comment,k.seen_state_key AS seen_state_key"
            + " FROM grade_submission s"
            + " JOIN course_offering o ON o.offering_id=s.offering_id"
            + " JOIN course c ON c.course_id=o.course_id"
            + " LEFT JOIN teacher_application_read k ON k.teacher_uid=s.submitted_by"
            + "     AND k.application_type='" + SUBMISSION + "'"
            + "     AND k.application_id=s.submission_id"
            + " WHERE s.submitted_by=?";

    // ---------------------------------------------------------------------- 单条

    /**
     * 一条属于本人的申请（别人的与不存在的都是 null，对外不可区分）。
     *
     * <p>类型必须**明确**落在白名单里：这里不像 {@link #list} 那样把「空类型」当成「不限类型」，
     * 因为单条查询最终要落到一张具体的事实表上——一个未知或 null 的类型如果默认走成绩分支，
     * 就会用一个没人问过的分支回答（今天是不可达的，只因为调用方先过了白名单）。所以未知类型
     * 直接拒绝，和 {@code TeacherApplicationService.requireType} 一样是 IllegalArgumentException。
     *
     * <p>故意不加 {@code FOR UPDATE}：标记已读是 compare-and-set，而两张事实表的状态只会
     * PENDING → 终态单向迁移一次、时间戳由数据库固定，所以「读到旧键、随后管理员提交」的错序只会
     * 让回执存下一个**已经过时**的键，下一次查询照样显示未读——正是设计 §10 要的结果。加行锁只会
     * 让一次读确认去等管理员的下一次审批，换不到任何正确性。
     */
    public Row find(Connection connection, String teacherUid, String type, long id)
            throws SQLException {
        if (ADJUSTMENT.equals(type)) {
            return findOwned(connection, ADJUSTMENT_BRANCH, teacherUid, id);
        }
        if (SUBMISSION.equals(type)) {
            return findOwned(connection, SUBMISSION_BRANCH, teacherUid, id);
        }
        throw new IllegalArgumentException("未知的申请类型: " + type);
    }

    private static Row findOwned(Connection connection, String branch, String teacherUid, long id)
            throws SQLException {
        String sql = "SELECT u.application_type,u.application_id,u.offering_id,u.course_name,"
                + "u.offering_code,u.status,u.submitted_at,u.handled_at,u.review_comment,"
                + "u.seen_state_key FROM (" + branch + ") u WHERE u.application_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setLong(2, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? row(result) : null;
            }
        }
    }

    // ---------------------------------------------------------------- 已读回执

    /**
     * 写入一条已读回执（主键是「教师 + 类型 + 申请」，所以两张表里数字相同的 ID 互不覆盖）。
     * {@code read_at} 用调用方注入的时钟，测试才能钉住时间；重复标记同一状态键只是把行原样收敛。
     */
    public int upsertSeen(Connection connection, String teacherUid, String type, long id,
            String seenStateKey, Instant readAt) throws SQLException {
        String sql = "INSERT INTO teacher_application_read(teacher_uid,application_type,"
                + "application_id,seen_state_key,read_at) VALUES(?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE seen_state_key=VALUES(seen_state_key),"
                + " read_at=VALUES(read_at)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setString(2, type);
            statement.setLong(3, id);
            statement.setString(4, seenStateKey);
            statement.setTimestamp(5, Timestamp.valueOf(
                    LocalDateTime.ofInstant(readAt, ZoneOffset.UTC)));
            return statement.executeUpdate();
        }
    }

    // --------------------------------------------------------------------- 映射

    private static Row row(ResultSet result) throws SQLException {
        return new Row(result.getString("application_type"), result.getLong("application_id"),
                result.getLong("offering_id"), result.getString("course_name"),
                result.getString("offering_code"), result.getString("status"),
                result.getTimestamp("submitted_at"), result.getTimestamp("handled_at"),
                result.getString("review_comment"), result.getString("seen_state_key"));
    }
}
