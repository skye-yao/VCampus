package dao;

import com.google.gson.Gson;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 教师成绩工作副本（{@code teacher_grade_book}）与草稿明细（{@code teacher_grade_draft_item}）
 * 的读与写，外加名单摘要与成绩录入列表所需的只读查询。
 *
 * <p>所有写方法都在调用方事务内执行，调用方负责锁顺序：先锁 offering，再锁工作副本行，最后按
 * {@code enrollment_id} 升序写明细。{@link #upsertItem} 是可覆写的接缝，用来证明保存事务的
 * 回滚是整笔的。
 *
 * <p>{@link #rosterDigest} 是名单摘要的唯一定义：<b>正常（status=2）选课记录 ID 升序、每个 ID
 * 后跟随一个换行符、UTF-8 字节的 SHA-256 小写十六进制</b>。计算与复核都调用这一个方法，
 * 任何一处都不再自行拼装字符串。
 *
 * <p>{@code scheme_json} 是 NOT NULL：工作副本永远带着一份完整方案，读不到工作副本时由服务层
 * 提供默认方案，而不是让这里写一份空方案。
 */
public class TeacherGradeBookDAO {
    private static final Gson GSON = new Gson();
    private static final char SEPARATOR = '\n';
    /** 非锁定读的工作副本列；{@code b.} 前缀与 {@link #LIST_FROM} 的别名一致，批次状态来自 join。 */
    private static final String BOOK_COLUMNS = "b.revision,b.draft_open,b.draft_kind,"
            + "b.base_submission_id,b.last_submission_id,b.scheme_json,b.correction_reason,"
            + "s.status AS submission_status";
    /** 锁定读的工作副本列：不带 grade_submission（见 {@link #findBookForUpdate}）。 */
    private static final String BOOK_COLUMNS_LOCKED = "revision,draft_open,draft_kind,"
            + "base_submission_id,last_submission_id,scheme_json,correction_reason";
    private static final String BOOK_JOINS = " FROM teacher_grade_book b"
            + " LEFT JOIN grade_submission s ON s.submission_id=b.last_submission_id";
    private static final String LIST_SELECT = "SELECT o.offering_id,o.offering_code,o.academic_year,"
            + "o.semester,o.capacity,o.status,c.course_id,c.course_code,c.course_name,c.credit,"
            + BOOK_COLUMNS;
    private static final String LIST_FROM = " FROM course_offering o"
            + " JOIN course c ON c.course_id=o.course_id"
            + " JOIN course_offering_teacher t ON t.offering_id=o.offering_id AND t.uid=? AND t.role=0"
            + " LEFT JOIN teacher_grade_book b ON b.offering_id=o.offering_id"
            + " LEFT JOIN grade_submission s ON s.submission_id=b.last_submission_id"
            + " WHERE EXISTS (SELECT 1 FROM tbl_user tu WHERE tu.UID=? AND tu.role=1)"
            + " AND o.academic_year=? AND o.semester=?";
    private static final String ROSTER_SELECT = "SELECT e.offering_id,e.enrollment_id,e.uid,u.name,"
            + "i.daily_score,i.midterm_score,i.experiment_score,i.finalterm_score"
            + " FROM enrollment e"
            + " JOIN tbl_user u ON u.UID=e.uid"
            + " LEFT JOIN teacher_grade_draft_item i"
            + "     ON i.offering_id=e.offering_id AND i.enrollment_id=e.enrollment_id";

    /**
     * 名单摘要的规范形式（见类注释）。{@code enrollmentIds} 允许任意顺序，这里负责排序；
     * 空名单的摘要是空字符串的 SHA-256。
     */
    public static String rosterDigest(List<Long> enrollmentIds) {
        List<Long> sorted = new ArrayList<>(enrollmentIds);
        sorted.sort(null);
        StringBuilder canonical = new StringBuilder();
        for (Long enrollmentId : sorted) {
            canonical.append(enrollmentId).append(SEPARATOR);
        }
        try {
            byte[] value = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static String schemeJson(GradeSchemeDTO scheme) {
        return GSON.toJson(scheme);
    }

    /** 反序列化不经过构造器，所以调用方仍要用 GradeCalculator.validateScheme 复核结构。 */
    public static GradeSchemeDTO scheme(String json) {
        return json == null ? null : GSON.fromJson(json, GradeSchemeDTO.class);
    }

    // -------------------------------------------------------------------- 读

    /** 正常（status=2）选课记录 ID，升序；退课历史不进入名单与摘要。 */
    public List<Long> normalEnrollmentIds(Connection connection, long offeringId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enrollment_id FROM enrollment WHERE offering_id=? AND status=2"
                        + " ORDER BY enrollment_id")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rows.next()) ids.add(rows.getLong(1));
                return List.copyOf(ids);
            }
        }
    }

    /** 非锁定读：工作副本 + 最后一次批次的状态。 */
    public GradeBookRow findBook(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT " + BOOK_COLUMNS + BOOK_JOINS + " WHERE b.offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? bookRow(rows, rows.getString("submission_status")) : null;
            }
        }
    }

    /**
     * 写事务里的工作副本行锁；与 offering 锁一起构成“offering → book → 明细”的顺序。
     *
     * <p>这个 SELECT 刻意不 join {@code grade_submission}：MySQL 的锁定读会把 join 到的批次行也锁住，
     * 等于在 plan 规定的 offering → grade_book 之间多插一个锁，而审批先改批次状态、再动工作副本，
     * 两边顺序互为倒序，驳回后的重提保存会死锁（1213）。批次状态因此单独做一次非锁定读：
     * 驳回是终态，非锁定读最多“还没有看到 REJECTED”，只会让这次保存拿到一个可重试的冲突，
     * 不会放行不该放行的写入。
     */
    public GradeBookRow findBookForUpdate(Connection connection, long offeringId)
            throws SQLException {
        String sql = "SELECT " + BOOK_COLUMNS_LOCKED + " FROM teacher_grade_book"
                + " WHERE offering_id=? FOR UPDATE";
        GradeBookRow row;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                row = bookRow(rows, null);
            }
        }
        if (row.lastSubmissionId() == null) return row;
        return new GradeBookRow(row.revision(), row.draftOpen(), row.draftKind(),
                row.baseSubmissionId(), row.lastSubmissionId(), row.scheme(),
                row.correctionReason(),
                findSubmissionStatus(connection, row.lastSubmissionId()));
    }

    /** 批次状态；批次不存在返回 null。锁定读路径用它单独取状态，避免锁住批次行。 */
    public String findSubmissionStatus(Connection connection, long submissionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM grade_submission WHERE submission_id=?")) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    /**
     * 提交之后名单是否变化：当前正常名单的 <b>UID 集合</b>与批次捕获的身份快照集合是否不同。
     * 新批次写入 student_uid_snapshot；V007 之前的批次没有快照，退回按 enrollment 的 uid 计算，
     * 不猜测身份。已退课学生的 UID 仍在批次集合里，所以退课同样算名单变化。
     */
    public boolean rosterChangedSinceSubmission(Connection connection, long offeringId,
                                                long submissionId) throws SQLException {
        Set<String> current = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uid FROM enrollment WHERE offering_id=? AND status=2")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) current.add(rows.getString(1));
            }
        }
        Set<String> captured = new HashSet<>();
        String sql = "SELECT COALESCE(i.student_uid_snapshot,e.uid) FROM grade_submission_item i"
                + " JOIN enrollment e ON e.enrollment_id=i.enrollment_id"
                + " WHERE i.submission_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, submissionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String uid = rows.getString(1);
                    if (uid != null) captured.add(uid);
                }
            }
        }
        return !current.equals(captured);
    }

    /** 正常名单（按学号排序）连同草稿分数；草稿行不存在时四项都为 null。 */
    public List<RosterScoreRow> listRosterScores(Connection connection,
                                                 Collection<Long> offeringIds) throws SQLException {
        if (offeringIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder(ROSTER_SELECT + " WHERE e.status=2 AND e.offering_id IN (");
        for (int index = 0; index < offeringIds.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY e.uid,e.enrollment_id");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Long offeringId : offeringIds) statement.setLong(index++, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                List<RosterScoreRow> roster = new ArrayList<>();
                while (rows.next()) {
                    roster.add(new RosterScoreRow(rows.getLong("offering_id"),
                            rows.getLong("enrollment_id"), rows.getString("uid"),
                            rows.getString("name"), scores(rows)));
                }
                return List.copyOf(roster);
            }
        }
    }

    /** 现有草稿明细，按 enrollment_id 升序（与写入锁顺序一致）。 */
    public List<ItemRow> listItems(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT enrollment_id,daily_score,midterm_score,experiment_score,finalterm_score"
                        + " FROM teacher_grade_draft_item WHERE offering_id=?"
                        + " ORDER BY enrollment_id")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                List<ItemRow> items = new ArrayList<>();
                while (rows.next()) {
                    items.add(new ItemRow(rows.getLong("enrollment_id"), scores(rows)));
                }
                return List.copyOf(items);
            }
        }
    }

    /**
     * 成绩录入列表：本人担任 {@code role=0} 任课教师的教学班，连同工作副本状态。
     * 嵌入的教学班摘要只带原始字段，名单人数与可编辑位由服务层按当前名单补齐。
     */
    public List<ListedOffering> listOfferings(Connection connection, String uid, int academicYear,
                                              int semester, int limit, int offset)
            throws SQLException {
        String sql = LIST_SELECT + LIST_FROM + " ORDER BY o.offering_code,o.offering_id"
                + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindListFilter(statement, 1, uid, academicYear, semester);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet rows = statement.executeQuery()) {
                List<ListedOffering> offerings = new ArrayList<>();
                while (rows.next()) {
                    offerings.add(new ListedOffering(rows.getLong("offering_id"),
                            rows.getString("offering_code"), rows.getLong("course_id"),
                            rows.getString("course_code"), rows.getString("course_name"),
                            rows.getDouble("credit"), rows.getInt("academic_year"),
                            rows.getInt("semester"), rows.getInt("capacity"),
                            rows.getInt("status"),
                            bookRow(rows, rows.getString("submission_status"))));
                }
                return List.copyOf(offerings);
            }
        }
    }

    public long countOfferings(Connection connection, String uid, int academicYear, int semester)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*)" + LIST_FROM)) {
            bindListFilter(statement, 1, uid, academicYear, semester);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    // -------------------------------------------------------------------- 写

    /** 锁教学班行（“offering → book → 明细”顺序的第一步）；教学班不存在返回 false。 */
    public boolean lockOffering(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT offering_id FROM course_offering WHERE offering_id=? FOR UPDATE")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 首次保存：每班一份工作副本，revision 从 1 起，草稿立即开放。 */
    public void insertBook(Connection connection, long offeringId, String schemeJson,
                           String updatedBy, Instant updatedAt) throws SQLException {
        String sql = "INSERT INTO teacher_grade_book(offering_id,revision,draft_open,draft_kind,"
                + "base_submission_id,last_submission_id,scheme_json,correction_reason,updated_by,"
                + "updated_at) VALUES(?,1,1,'INITIAL',NULL,NULL,?,NULL,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setString(2, schemeJson);
            statement.setString(3, updatedBy);
            statement.setTimestamp(4, timestamp(updatedAt));
            statement.executeUpdate();
        }
    }

    /**
     * 驳回后的惰性重开：只翻转草稿状态，不动 revision（随后那次方案更新才递增版本）。
     * {@code revision} 与 {@code last_submission_id} 都参与条件，过期请求不会重开草稿。
     *
     * @return 受影响行数，调用方必须要求恰好 1
     */
    public int reopenForResubmission(Connection connection, long offeringId, int revision,
                                     long rejectedSubmissionId) throws SQLException {
        String sql = "UPDATE teacher_grade_book SET draft_open=1,draft_kind='RESUBMISSION',"
                + "base_submission_id=? WHERE offering_id=? AND revision=? AND draft_open=0"
                + " AND last_submission_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rejectedSubmissionId);
            statement.setLong(2, offeringId);
            statement.setInt(3, revision);
            statement.setLong(4, rejectedSubmissionId);
            return statement.executeUpdate();
        }
    }

    /**
     * 保存草稿唯一允许的工作副本更新：方案、版本与最后修改人一起原子更新，并只认还开着的、
     * 版本匹配的行。调用方必须在事务内检查返回值恰好为 1，否则不得继续写明细。
     *
     * @return 受影响行数，0 表示版本过期或草稿已关闭
     */
    public int updateScheme(Connection connection, long offeringId, int expectedRevision,
                            String schemeJson, String updatedBy, Instant updatedAt)
            throws SQLException {
        String sql = "UPDATE teacher_grade_book"
                + " SET scheme_json=?,revision=revision+1,updated_by=?,updated_at=?"
                + " WHERE offering_id=? AND revision=? AND draft_open=1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemeJson);
            statement.setString(2, updatedBy);
            statement.setTimestamp(3, timestamp(updatedAt));
            statement.setLong(4, offeringId);
            statement.setInt(5, expectedRevision);
            return statement.executeUpdate();
        }
    }

    /** 写一行草稿明细；null 是“尚未录入”，绝不写 0。可覆写接缝，用于证明保存整笔回滚。 */
    public void upsertItem(Connection connection, long offeringId, long enrollmentId,
                           GradeScoresDTO scores) throws SQLException {
        String sql = "INSERT INTO teacher_grade_draft_item(offering_id,enrollment_id,daily_score,"
                + "midterm_score,experiment_score,finalterm_score) VALUES(?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE daily_score=VALUES(daily_score),"
                + "midterm_score=VALUES(midterm_score),"
                + "experiment_score=VALUES(experiment_score),"
                + "finalterm_score=VALUES(finalterm_score)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            statement.setLong(2, enrollmentId);
            statement.setBigDecimal(3, scores == null ? null : scores.getDailyScore());
            statement.setBigDecimal(4, scores == null ? null : scores.getMidtermScore());
            statement.setBigDecimal(5, scores == null ? null : scores.getExperimentScore());
            statement.setBigDecimal(6, scores == null ? null : scores.getFinaltermScore());
            statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int bindListFilter(PreparedStatement statement, int index, String uid,
                                      int academicYear, int semester) throws SQLException {
        statement.setString(index++, uid);
        statement.setString(index++, uid);
        statement.setInt(index++, academicYear);
        statement.setInt(index, semester);
        return index + 1;
    }

    /** {@code revision} 为 NULL 表示这一行没有工作副本，其余列都是 NULL。 */
    private static GradeBookRow bookRow(ResultSet rows, String submissionStatus)
            throws SQLException {
        int revision = rows.getInt("revision");
        if (rows.wasNull()) return null;
        long baseSubmissionId = rows.getLong("base_submission_id");
        Long base = rows.wasNull() ? null : baseSubmissionId;
        long lastSubmissionId = rows.getLong("last_submission_id");
        Long last = rows.wasNull() ? null : lastSubmissionId;
        return new GradeBookRow(revision, rows.getBoolean("draft_open"),
                rows.getString("draft_kind"), base, last,
                scheme(rows.getString("scheme_json")), rows.getString("correction_reason"),
                submissionStatus);
    }

    private static GradeScoresDTO scores(ResultSet rows) throws SQLException {
        return new GradeScoresDTO(rows.getBigDecimal("daily_score"),
                rows.getBigDecimal("midterm_score"), rows.getBigDecimal("experiment_score"),
                rows.getBigDecimal("finalterm_score"));
    }

    /** DATETIME(6) 存的是 UTC 墙上时钟，与 DBUtil 的 {@code time_zone='+00:00'} 一致。 */
    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    /** 工作副本的一行；service 用它判断草稿状态，余额列按需读取。 */
    public record GradeBookRow(int revision, boolean draftOpen, String draftKind,
                               Long baseSubmissionId, Long lastSubmissionId,
                               GradeSchemeDTO scheme, String correctionReason,
                               String submissionStatus) {
    }

    /** 名单里的一名学生连着他的草稿分数；没有草稿行时四项为 null。 */
    public record RosterScoreRow(long offeringId, long enrollmentId, String studentUid,
                                 String studentName, GradeScoresDTO scores) {
    }

    /** 一条草稿明细；四项分数为 null 表示尚未录入。 */
    public record ItemRow(long enrollmentId, GradeScoresDTO scores) {
    }

    /** 成绩录入列表的一行原始数据；名单人数与可编辑位由服务层补齐。 */
    public record ListedOffering(long offeringId, String offeringCode, long courseId,
                                 String courseCode, String courseName, double credit,
                                 int academicYear, int semester, int capacity, int status,
                                 GradeBookRow book) {
    }
}
