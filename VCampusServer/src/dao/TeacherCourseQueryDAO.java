package dao;

import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherRosterRowDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
* 教师端只读查询：本人教学班、教学班详情与教师名单、学生名单、学期，以及某学期当前正式排课
* 方案。所有可见性都按“当前”关系判定，绝不把历史任课关系算成当前权限。
*
* <p>教学班列表与计数共用 {@link #OFFERING_FROM} 与 {@link #bindOfferingFilter}，学生名单的
* 列表与计数共用 {@link #ROSTER_FROM} 与 {@link #rosterFilter}，避免过滤条件漂移。
* 姓名和课程检索一律使用绑定参数并转义 LIKE 通配符。
*/
public class TeacherCourseQueryDAO {
    private static final String LIKE_ESCAPE = "!";
    /** 系统角色先要是“教师”（设计第 4 节），再谈教学班关系。 */
    private static final String TEACHER_ROLE_GATE =
            "EXISTS (SELECT 1 FROM tbl_user tu WHERE tu.UID=? AND tu.role=1)";
    /** 教师可见某教学班的三条关系，参数顺序与 {@link #bindOfferingFilter} 一致。 */
    private static final String VIEW_RELATION = "("
            + "t.role IN (0,1)"
            + " OR EXISTS (SELECT 1 FROM course_schedule_arrangement a"
            + "     JOIN schedule_plan p ON p.id=a.plan_id AND p.status='PUBLISHED'"
            + "     WHERE a.offering_id=o.offering_id AND a.status='ACTIVE' AND a.teacher_uid=?)"
            + " OR EXISTS (SELECT 1 FROM course_schedule_adjustment j"
            + "     JOIN course_occurrence oc ON oc.id=j.original_occurrence_id"
            + "     JOIN course_schedule_rule r ON r.id=oc.rule_id"
            + "     JOIN course_schedule_arrangement a2 ON a2.arrangement_id=r.arrangement_id"
            + "     WHERE a2.offering_id=o.offering_id AND j.status='ACTIVE'"
            + "       AND j.end_at_utc > UTC_TIMESTAMP(6) AND j.teacher_uid=?)"
            + ")";
    private static final String OFFERING_FROM = " FROM course_offering o"
            + " JOIN course c ON c.course_id=o.course_id"
            + " LEFT JOIN course_offering_teacher t"
            + "     ON t.offering_id=o.offering_id AND t.uid=?"
            + " WHERE " + TEACHER_ROLE_GATE + " AND " + VIEW_RELATION
            + " AND o.academic_year=? AND o.semester=?";
    private static final String ROSTER_FROM = " FROM enrollment e"
            + " JOIN tbl_user u ON u.UID=e.uid"
            + " LEFT JOIN student_academic_profile p ON p.uid=e.uid"
            + " LEFT JOIN major m ON m.major_id=p.major_id";
    private static final String ROSTER_SELECT = "SELECT e.enrollment_id,e.uid,e.status,"
            + "e.select_time,e.drop_time,u.name,COALESCE(m.major_name,u.major) AS major"
            + ROSTER_FROM;

    /**
    * Lists Terms data.
    */
    public List<CourseTermDTO> listTerms(Connection connection, String uid) throws SQLException {
        // 教师学期来自本人实际关联的教学班，不依赖 course_selection_window：
        // 没有选课窗口的历史学期教师仍然要能看到自己的教学班。
        String sql = "SELECT DISTINCT o.academic_year,o.semester FROM course_offering o"
                + " LEFT JOIN course_offering_teacher t"
                + "     ON t.offering_id=o.offering_id AND t.uid=?"
                + " WHERE " + TEACHER_ROLE_GATE + " AND " + VIEW_RELATION
                + " ORDER BY o.academic_year DESC,o.semester DESC";
        List<CourseTermDTO> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setString(2, uid);
            statement.setString(3, uid);
            statement.setString(4, uid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    terms.add(CourseQueryDAO.term(rows.getInt("academic_year"),
                            rows.getInt("semester")));
                }
            }
        }
        return List.copyOf(terms);
    }

    /**
    * Lists Offerings data.
    */
    public List<TeacherOfferingDTO> listOfferings(Connection connection, String uid,
                                                  int academicYear, int semester, String query,
                                                  int limit, int offset) throws SQLException {
        String pattern = like(query);
        String sql = "SELECT o.offering_id,o.offering_code,o.academic_year,o.semester,"
                + "o.enrolled_count,o.capacity,o.status,"
                + "c.course_id,c.course_code,c.course_name,c.credit,t.role AS member_role"
                + OFFERING_FROM
                + (pattern == null ? "" : " AND (c.course_name LIKE ? ESCAPE '!'"
                        + " OR c.course_code LIKE ? ESCAPE '!'"
                        + " OR o.offering_code LIKE ? ESCAPE '!')")
                + " ORDER BY o.offering_code,o.offering_id LIMIT ? OFFSET ?";
        List<TeacherOfferingDTO> offerings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindOfferingFilter(statement, 1, uid, academicYear, semester, pattern);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) offerings.add(mapOffering(rows));
            }
        }
        return List.copyOf(offerings);
    }

    /**
    * Handles the course-management responsibility of countOfferings.
    */
    public long countOfferings(Connection connection, String uid, int academicYear, int semester,
                               String query) throws SQLException {
        String pattern = like(query);
        String sql = "SELECT COUNT(*)" + OFFERING_FROM
                + (pattern == null ? "" : " AND (c.course_name LIKE ? ESCAPE '!'"
                        + " OR c.course_code LIKE ? ESCAPE '!'"
                        + " OR o.offering_code LIKE ? ESCAPE '!')");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindOfferingFilter(statement, 1, uid, academicYear, semester, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                requireRow(rows);
                return rows.getLong(1);
            }
        }
    }

    /**
    * Finds Offering data.
    */
    public OfferingDetail findOffering(Connection connection, String uid, long offeringId)
            throws SQLException {
        String sql = "SELECT o.offering_id,o.offering_code,o.academic_year,o.semester,"
                + "o.enrolled_count,o.capacity,o.status,"
                + "c.course_id,c.course_code,c.course_name,c.credit,t.role AS member_role,"
                + "c.offering_college,c.description"
                + " FROM course_offering o JOIN course c ON c.course_id=o.course_id"
                + " LEFT JOIN course_offering_teacher t"
                + "     ON t.offering_id=o.offering_id AND t.uid=?"
                + " WHERE o.offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                // 学院与简介保持原样：offering_college 为 NULL 时就是 NULL，界面显示“未维护”，
                // 不能用教师自己的学院冒充。
                return new OfferingDetail(mapOffering(rows), rows.getString("offering_college"),
                        rows.getString("description"));
            }
        }
    }

    /**
    * Lists OfferingTeachers data.
    */
    public List<ScheduleResourceDTO> listOfferingTeachers(Connection connection, long offeringId)
            throws SQLException {
        String sql = "SELECT t.uid,tu.name FROM course_offering_teacher t"
                + " JOIN tbl_user tu ON tu.UID=t.uid"
                + " WHERE t.offering_id=? ORDER BY t.role,t.uid";
        List<ScheduleResourceDTO> teachers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String uid = rows.getString("uid");
                    teachers.add(new ScheduleResourceDTO(uid, uid, rows.getString("name"),
                            "teacher", 0));
                }
            }
        }
        return List.copyOf(teachers);
    }

    /**
    * Lists Students data.
    */
    public List<TeacherRosterRowDTO> listStudents(Connection connection, long offeringId,
                                                  String query, Integer enrollmentStatus,
                                                  int limit, int offset) throws SQLException {
        String pattern = like(query);
        String sql = ROSTER_SELECT + rosterFilter(enrollmentStatus, pattern)
                + " ORDER BY e.uid,e.enrollment_id LIMIT ? OFFSET ?";
        List<TeacherRosterRowDTO> roster = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindRosterFilter(statement, 1, offeringId, enrollmentStatus, pattern);
            statement.setInt(index++, limit);
            statement.setInt(index, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) roster.add(mapRoster(rows));
            }
        }
        return List.copyOf(roster);
    }

    /**
    * Handles the course-management responsibility of countStudents.
    */
    public long countStudents(Connection connection, long offeringId, String query,
                              Integer enrollmentStatus) throws SQLException {
        String pattern = like(query);
        String sql = "SELECT COUNT(*)" + ROSTER_FROM + rosterFilter(enrollmentStatus, pattern);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRosterFilter(statement, 1, offeringId, enrollmentStatus, pattern);
            try (ResultSet rows = statement.executeQuery()) {
                requireRow(rows);
                return rows.getLong(1);
            }
        }
    }

    /**
    * 导出用：按与列表**完全相同**的过滤条件一次取回名单，最多 {@code limit} 行、不带 OFFSET。
    *
    * <p>导出不是「当前页」：调用方传入「上限 + 1」行，一次查询就能判断是否超限并明确报错，既不用
    * 发第二次 COUNT，也不存在「计数与取数之间名单变化」导致的静默截断。过滤条件与排序列与
    * {@link #listStudents} 共用 {@link #ROSTER_SELECT}、{@link #rosterFilter}，导出与列表的口径
    * 不会各自漂移。
    */
    public List<TeacherRosterRowDTO> listStudentsForExport(Connection connection, long offeringId,
            String query, Integer enrollmentStatus, int limit) throws SQLException {
        String pattern = like(query);
        String sql = ROSTER_SELECT + rosterFilter(enrollmentStatus, pattern)
                + " ORDER BY e.uid,e.enrollment_id LIMIT ?";
        List<TeacherRosterRowDTO> roster = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindRosterFilter(statement, 1, offeringId, enrollmentStatus, pattern);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) roster.add(mapRoster(rows));
            }
        }
        return List.copyOf(roster);
    }

    /** 教学班所属学期，用于把教师查询锁定到他自己的那个学期。 */
    public Term findOfferingTerm(Connection connection, long offeringId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT academic_year,semester FROM course_offering WHERE offering_id=?")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new Term(rows.getInt("academic_year"),
                        rows.getInt("semester")) : null;
            }
        }
    }

    /**
    * 该学期当前正式排课方案：只考虑 {@code status='PUBLISHED'}，并在多个候选里优先选择
    * {@code teaching_calendar.current_schedule_plan_id} 指向的那个。没有正式方案时返回
    * {@code null}，调用方据此返回空列表，绝不回退到管理员的工作 DRAFT。
    */
    public Long findPublishedPlanId(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT p.id FROM schedule_plan p"
                + " JOIN teaching_calendar c ON c.id=p.calendar_id"
                + " WHERE c.academic_year=? AND c.semester=? AND p.status='PUBLISHED'"
                + " ORDER BY (p.id=c.current_schedule_plan_id) DESC,p.revision DESC,p.id DESC"
                + " LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String rosterFilter(Integer enrollmentStatus, String pattern) {
        StringBuilder filter = new StringBuilder(" WHERE e.offering_id=?");
        if (enrollmentStatus != null) filter.append(" AND e.status=?");
        if (pattern != null) {
            filter.append(" AND (u.name LIKE ? ESCAPE '!' OR e.uid LIKE ? ESCAPE '!')");
        }
        return filter.toString();
    }

    private static int bindRosterFilter(PreparedStatement statement, int index, long offeringId,
                                        Integer enrollmentStatus, String pattern)
            throws SQLException {
        statement.setLong(index++, offeringId);
        if (enrollmentStatus != null) statement.setInt(index++, enrollmentStatus);
        if (pattern != null) {
            statement.setString(index++, pattern);
            statement.setString(index++, pattern);
        }
        return index;
    }

    private static int bindOfferingFilter(PreparedStatement statement, int index, String uid,
                                          int academicYear, int semester, String pattern)
            throws SQLException {
        statement.setString(index++, uid);
        statement.setString(index++, uid);
        statement.setString(index++, uid);
        statement.setString(index++, uid);
        statement.setInt(index++, academicYear);
        statement.setInt(index++, semester);
        if (pattern != null) {
            statement.setString(index++, pattern);
            statement.setString(index++, pattern);
            statement.setString(index++, pattern);
        }
        return index;
    }

    private static TeacherOfferingDTO mapOffering(ResultSet rows) throws SQLException {
        int memberRole = rows.getInt("member_role");
        boolean member = !rows.wasNull();
        String courseName = rows.getString("course_name");
        String offeringCode = rows.getString("offering_code");
        return new TeacherOfferingDTO(Long.toString(rows.getLong("offering_id")), offeringCode,
                courseName + " " + offeringCode, Long.toString(rows.getLong("course_id")),
                rows.getString("course_code"), courseName, rows.getDouble("credit"),
                rows.getInt("academic_year"), rows.getInt("semester"),
                rows.getInt("enrolled_count"), rows.getInt("capacity"),
                AdminOfferingDAO.statusLabel(rows.getInt("status")),
                member && memberRole == 0,
                // 设计第 4 节：任课教师可为本人课次申请调课；助教只读；安排级教师可为其实际
                // 授课的课次申请，所以能力位为“非助教”。
                !(member && memberRole == 1));
    }

    private static TeacherRosterRowDTO mapRoster(ResultSet rows) throws SQLException {
        int status = rows.getInt("status");
        return new TeacherRosterRowDTO(Long.toString(rows.getLong("enrollment_id")),
                rows.getString("uid"), rows.getString("name"), rows.getString("major"),
                status == 2 ? "ENROLLED" : "DROPPED",
                utc(rows, "select_time"), utc(rows, "drop_time"));
    }

    private static String utc(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    }

    private static String like(String query) {
        if (query == null || query.isBlank()) return null;
        return "%" + query.trim()
                .replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_") + "%";
    }

    private static void requireRow(ResultSet rows) throws SQLException {
        if (!rows.next()) throw new SQLException("Count query returned no row");
    }

    /** 教学班所属学期。 */
    public record Term(int academicYear, int semester) {
    }

    /** 教学班详情所需的附加列，学院未维护时为 null。 */
    public record OfferingDetail(TeacherOfferingDTO offering, String offeringCollege,
                                 String description) {
    }
}
