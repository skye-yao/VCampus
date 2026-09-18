package dao;

import dto.course.CourseTermDTO;
import dto.course.TermLabels;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminOfferingDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class AdminOfferingDAO {
    private static final String SELECT = "SELECT o.offering_id,o.offering_code,o.course_id,"
            + "o.academic_year,o.semester,o.capacity,o.enrolled_count,o.status,o.version,"
            + "t.uid AS teacher_uid,tu.name AS teacher_name,"
            + "a.uid AS assistant_uid,au.name AS assistant_name,"
            + "EXISTS(SELECT 1 FROM course_schedule_rule r"
            + " WHERE r.course_offering_id=o.offering_id) AS scheduled"
            + " FROM course_offering o"
            + " LEFT JOIN (SELECT offering_id,MIN(uid) AS uid FROM course_offering_teacher"
            + " WHERE role=0 GROUP BY offering_id) t ON t.offering_id=o.offering_id"
            + " LEFT JOIN tbl_user tu ON tu.UID=t.uid"
            + " LEFT JOIN (SELECT offering_id,MIN(uid) AS uid FROM course_offering_teacher"
            + " WHERE role=1 GROUP BY offering_id) a ON a.offering_id=o.offering_id"
            + " LEFT JOIN tbl_user au ON au.UID=a.uid";
    /** Log rows an offering writes about itself; they never block {@code deleteDraft}. */
    private static final String LIFECYCLE_ACTIONS = "'" + AdminCourseActions.CREATE_OFFERING
            + "','" + AdminCourseActions.UPDATE_OFFERING + "'";

    /**
     * 教学班列表。学期参数同时为 {@code null} 时不按学期限定（旧行为）；
     * 已取消（status=4）的行一律不返回——课程行的"教学班 N 个"也按 status<>4 计数，
     * 两处口径必须一致，否则同一屏上会出现两个打架的数字。
     */
    public List<AdminOfferingDTO> list(Connection connection, long courseId, Integer academicYear,
                                       Integer semester) throws SQLException {
        boolean scoped = academicYear != null && semester != null;
        String sql = SELECT + " WHERE o.course_id=? AND o.status<>4"
                + (scoped ? " AND o.academic_year=? AND o.semester=?" : "")
                + " ORDER BY o.academic_year DESC, o.semester DESC, o.offering_code";
        List<AdminOfferingDTO> offerings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, courseId);
            if (scoped) {
                statement.setInt(index++, academicYear);
                statement.setInt(index, semester);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) offerings.add(map(rows));
            }
        }
        return List.copyOf(offerings);
    }

    /**
     * 学期下拉的取值来源：全局所有教学班出现过的 (academic_year, semester)，最近优先。
     * 不过滤 status——取消掉最后一个教学班的学期也要留在下拉里，否则下拉项会随时间消失，
     * 管理员再也回不到那个学期。
     */
    public List<CourseTermDTO> listTerms(Connection connection) throws SQLException {
        String sql = "SELECT DISTINCT academic_year, semester FROM course_offering"
                + " ORDER BY academic_year DESC, semester DESC";
        List<CourseTermDTO> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int academicYear = rows.getInt("academic_year");
                int semester = rows.getInt("semester");
                terms.add(new CourseTermDTO(academicYear, semester,
                        TermLabels.displayName(academicYear, semester)));
            }
        }
        return List.copyOf(terms);
    }

    public void lock(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT offering_id FROM course_offering WHERE offering_id=? FOR UPDATE")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    /** Reads the mutable columns; callers lock the row via {@link #lock} first. */
    public OfferingRow row(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT offering_id,course_id,academic_year,semester,capacity,"
                + "enrolled_count,status,version FROM course_offering WHERE offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new OfferingRow(rows.getLong("offering_id"), rows.getLong("course_id"),
                        rows.getInt("academic_year"), rows.getInt("semester"),
                        rows.getInt("capacity"), rows.getInt("enrolled_count"),
                        rows.getInt("status"), rows.getInt("version"));
            }
        }
    }

    public AdminOfferingDTO find(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE o.offering_id=?")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public String courseStatus(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM course WHERE course_id=? FOR UPDATE")) {
            statement.setLong(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    public boolean isTeacher(Connection connection, String uid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM tbl_user WHERE UID=? AND role=1")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public long insert(Connection connection, OfferingFields fields, String createdBy)
            throws SQLException {
        String sql = "INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,wanted_count,enrolled_count,status,created_by)"
                + " VALUES(?,?,?,?,?,0,0,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, fields.offeringCode());
            statement.setLong(2, fields.courseId());
            statement.setInt(3, fields.academicYear());
            statement.setInt(4, fields.semester());
            statement.setInt(5, fields.capacity());
            statement.setInt(6, fields.status());
            statement.setString(7, createdBy);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public int update(Connection connection, long offeringId, int expectedVersion,
                      OfferingFields fields) throws SQLException {
        String sql = "UPDATE course_offering SET offering_code=?, course_id=?, academic_year=?,"
                + " semester=?, capacity=?, status=?, version=version+1"
                + " WHERE offering_id=? AND version=? AND status<>4";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fields.offeringCode());
            statement.setLong(2, fields.courseId());
            statement.setInt(3, fields.academicYear());
            statement.setInt(4, fields.semester());
            statement.setInt(5, fields.capacity());
            statement.setInt(6, fields.status());
            statement.setLong(7, offeringId);
            statement.setInt(8, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public int cancel(Connection connection, long offeringId, int expectedVersion,
                      String adminUid, Instant cancelledAt) throws SQLException {
        String sql = "UPDATE course_offering SET status=4, cancelled_by=?, cancelled_at=?,"
                + " version=version+1 WHERE offering_id=? AND version=? AND status<>4";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adminUid);
            statement.setTimestamp(2, Timestamp.valueOf(
                    cancelledAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.setLong(3, offeringId);
            statement.setInt(4, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public int deleteDraft(Connection connection, long offeringId, int expectedVersion)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM course_offering WHERE offering_id=? AND version=? AND status=1")) {
            statement.setLong(1, offeringId);
            statement.setInt(2, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public void deleteStaff(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM course_offering_teacher WHERE offering_id=?")) {
            statement.setLong(1, offeringId);
            statement.executeUpdate();
        }
    }

    public void replaceStaff(Connection connection, long offeringId, String teacherUid,
                             String assistantUid) throws SQLException {
        deleteStaff(connection, offeringId);
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO course_offering_teacher(offering_id,uid,role) VALUES(?,?,?)")) {
            statement.setLong(1, offeringId);
            statement.setString(2, teacherUid);
            statement.setInt(3, 0);
            statement.executeUpdate();
            if (assistantUid != null) {
                statement.setString(2, assistantUid);
                statement.setInt(3, 1);
                statement.executeUpdate();
            }
        }
    }

    public boolean hasEnrollment(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM enrollment WHERE offering_id=? LIMIT 1")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    public boolean hasDependencies(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM course_schedule_rule WHERE course_offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_schedule_arrangement WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM enrollment WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_plan_item WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_waitlist WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_notice WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_schedule_adjustment_request WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM grade_submission WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM course_operation_log WHERE offering_id=?)"
                + " OR EXISTS(SELECT 1 FROM admin_course_operation_log"
                + " WHERE target_type='OFFERING' AND target_id=?"
                + " AND action NOT IN (" + LIFECYCLE_ACTIONS + "))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 9; i++) statement.setLong(i, offeringId);
            statement.setString(10, Long.toString(offeringId));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }

    public static String statusLabel(int value) {
        return switch (value) {
            case 1 -> "NOT_OPEN";
            case 2 -> "OPEN";
            case 3 -> "STOPPED";
            case 4 -> "CANCELLED";
            default -> throw new IllegalArgumentException("教学班状态无效");
        };
    }

    private static AdminOfferingDTO map(ResultSet rows) throws SQLException {
        return new AdminOfferingDTO(Long.toString(rows.getLong("offering_id")),
                rows.getString("offering_code"), Long.toString(rows.getLong("course_id")),
                rows.getInt("academic_year"), rows.getInt("semester"), rows.getInt("capacity"),
                rows.getInt("enrolled_count"), statusLabel(rows.getInt("status")),
                rows.getString("teacher_uid"), rows.getString("teacher_name"),
                rows.getString("assistant_uid"), rows.getString("assistant_name"),
                rows.getInt("scheduled") == 1 ? "SCHEDULED" : "UNSCHEDULED",
                rows.getInt("version"));
    }

    public record OfferingRow(long offeringId, long courseId, int academicYear, int semester,
                              int capacity, int enrolledCount, int status, int version) {
    }

    public record OfferingFields(String offeringCode, long courseId, int academicYear,
                                 int semester, int capacity, int status) {
    }
}
