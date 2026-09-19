package dao;

import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Queries and row transitions used by administrator enrollment transactions. */
public class AdminEnrollmentDAO {
    private static final String STUDENT_FROM = " FROM tbl_user u"
            + " LEFT JOIN student_academic_profile sap ON sap.uid=u.UID"
            + " LEFT JOIN major m ON m.major_id=sap.major_id";
    private static final String STUDENT_COLUMNS = "u.UID,u.name,u.role,sap.status AS academic_status,"
            + "COALESCE(m.major_name,u.major) AS major_name,sap.cohort_year";
    private static final String SEARCH = " AND (?='' OR u.UID LIKE ? ESCAPE '=' OR u.name LIKE ? ESCAPE '=')";
    private static final String GRADE_LOCKED = "(EXISTS (SELECT 1 FROM grade g"
            + " WHERE g.enrollment_id=e.enrollment_id AND g.is_published=1)"
            + " OR EXISTS (SELECT 1 FROM grade_submission_item gi"
            + " JOIN grade_submission gs ON gs.submission_id=gi.submission_id"
            + " WHERE gi.enrollment_id=e.enrollment_id AND gs.status IN ('PENDING','APPROVED')))";
    private static final String ENROLLMENT_FROM = " FROM enrollment e"
            + " JOIN tbl_user u ON u.UID=e.uid"
            + " LEFT JOIN student_academic_profile sap ON sap.uid=u.UID"
            + " LEFT JOIN major m ON m.major_id=sap.major_id"
            + " JOIN course_offering o ON o.offering_id=e.offering_id"
            + " JOIN course c ON c.course_id=o.course_id";
    private static final String ENROLLMENT_COLUMNS = STUDENT_COLUMNS
            + ",e.enrollment_id,e.status AS enrollment_status,o.status AS offering_status,"
            + "c.status AS course_status," + GRADE_LOCKED + " AS grade_locked";

    private final CourseSelectionDAO selectionDAO = new CourseSelectionDAO();
    private final CourseWaitlistDAO waitlistDAO = new CourseWaitlistDAO();

    /**
    * Handles the course-management responsibility of searchStudents.
    */
    public AdminEnrollmentPageDTO<StudentSearchResultDTO> searchStudents(Connection connection,
            String query, int page, int size) throws SQLException {
        String where = STUDENT_FROM + " WHERE u.role=2 AND sap.status='ACTIVE'" + SEARCH;
        long total;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + where)) {
            bindSearch(statement, 1, query);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); total = rows.getLong(1); }
        }
        List<StudentSearchResultDTO> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + STUDENT_COLUMNS
                + where + " ORDER BY u.UID LIMIT ? OFFSET ?")) {
            bindSearch(statement, 1, query);
            statement.setInt(4, size);
            statement.setLong(5, (long) (page - 1) * size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(student(rows).dto());
            }
        }
        return new AdminEnrollmentPageDTO<>(items, total, page, size);
    }

    /**
    * Lists OfferingStudents data.
    */
    public AdminEnrollmentPageDTO<OfferingStudentDTO> listOfferingStudents(Connection connection,
            long offeringId, String query, int page, int size) throws SQLException {
        String where = ENROLLMENT_FROM + " WHERE e.offering_id=? AND e.status=2" + SEARCH;
        long total;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*)" + where)) {
            statement.setLong(1, offeringId);
            bindSearch(statement, 2, query);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); total = rows.getLong(1); }
        }
        List<OfferingStudentDTO> items = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + ENROLLMENT_COLUMNS
                + where + " ORDER BY u.UID,e.enrollment_id LIMIT ? OFFSET ?")) {
            statement.setLong(1, offeringId);
            bindSearch(statement, 2, query);
            statement.setInt(5, size);
            statement.setLong(6, (long) (page - 1) * size);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) items.add(offeringStudent(rows));
            }
        }
        return new AdminEnrollmentPageDTO<>(items, total, page, size);
    }

    /**
    * Finds Student data.
    */
    public StudentRow findStudent(Connection connection, String uid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + STUDENT_COLUMNS + STUDENT_FROM + " WHERE u.UID=?")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? student(rows) : null; }
        }
    }

    /** The same profile-first lock used by student selection and waitlist mutations. */
    public void lockStudentProfile(Connection connection, String uid) throws SQLException {
        waitlistDAO.lockStudentProfile(connection, uid);
    }

    /**
    * Finds Offering data.
    */
    public OfferingRow findOffering(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT o.offering_id,o.course_id,o.academic_year,o.semester,o.status,"
                + "o.capacity,o.enrolled_count,c.status AS course_status,c.prerequisites"
                + " FROM course_offering o JOIN course c ON c.course_id=o.course_id"
                + " WHERE o.offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new OfferingRow(rows.getLong("offering_id"), rows.getLong("course_id"),
                        rows.getInt("academic_year"), rows.getInt("semester"), rows.getInt("status"),
                        rows.getInt("capacity"), rows.getInt("enrolled_count"),
                        rows.getString("course_status"), rows.getString("prerequisites"));
            }
        }
    }

    /**
    * Handles the course-management responsibility of activeOfferingIds.
    */
    public List<Long> activeOfferingIds(Connection connection, String uid) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT offering_id FROM enrollment WHERE uid=? AND status=2 ORDER BY offering_id")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getLong(1)); }
        }
        return List.copyOf(ids);
    }

    /**
    * Locks the database rows for OfferingsAscending.
    */
    public List<Long> lockOfferingsAscending(Connection connection, Collection<Long> ids) throws SQLException {
        return selectionDAO.lockOfferingsAscending(connection, ids);
    }

    /**
    * Finds Enrollment data.
    */
    public EnrollmentRow findEnrollment(Connection connection, String uid, long offeringId, boolean lock)
            throws SQLException {
        String sql = "SELECT enrollment_id,status FROM enrollment WHERE uid=? AND offering_id=?"
                + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? new EnrollmentRow(rows.getLong("enrollment_id"), rows.getInt("status")) : null;
            }
        }
    }

    /**
    * Finds OfferingStudent data.
    */
    public OfferingStudentDTO findOfferingStudent(Connection connection, String uid, long offeringId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + ENROLLMENT_COLUMNS
                + ENROLLMENT_FROM + " WHERE e.uid=? AND e.offering_id=?")) {
            statement.setString(1, uid);
            statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? offeringStudent(rows) : null; }
        }
    }

    /**
    * Handles the course-management responsibility of sameCourseActiveOfferingId.
    */
    public Long sameCourseActiveOfferingId(Connection connection, String uid, OfferingRow offering) throws SQLException {
        String sql = "SELECT offering_id FROM enrollment WHERE uid=? AND academic_year=? AND semester=?"
                + " AND course_id=? AND offering_id<>? AND status=2 LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            statement.setInt(2, offering.academicYear());
            statement.setInt(3, offering.semester());
            statement.setLong(4, offering.courseId());
            statement.setLong(5, offering.offeringId());
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getLong(1) : null; }
        }
    }

    /**
    * Handles the course-management responsibility of otherOfferReservations.
    */
    public int otherOfferReservations(Connection connection, long offeringId, String uid, Instant now)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM course_waitlist"
                + " WHERE offering_id=? AND uid<>? AND status='OFFERED' AND expires_at>?")) {
            statement.setLong(1, offeringId);
            statement.setString(2, uid);
            statement.setTimestamp(3, timestamp(now));
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    /** An exact course code wins; an ambiguous course name requires manual confirmation. */
    public Long prerequisiteCourseId(Connection connection, String token) throws SQLException {
        String sql = "SELECT course_id,course_code FROM course WHERE course_code=? OR course_name=?"
                + " ORDER BY (course_code=?) DESC,course_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, token);
            statement.setString(2, token);
            statement.setString(3, token);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long id = rows.getLong("course_id");
                if (token.equalsIgnoreCase(rows.getString("course_code"))) return id;
                return rows.next() ? null : id;
            }
        }
    }

    /**
    * Determines whether hasPassingGrade holds.
    */
    public boolean hasPassingGrade(Connection connection, String uid, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM enrollment e"
                + " JOIN grade g ON g.enrollment_id=e.enrollment_id"
                + " WHERE e.uid=? AND e.course_id=? AND g.is_published=1 AND g.score>=60 LIMIT 1")) {
            statement.setString(1, uid);
            statement.setLong(2, courseId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next(); }
        }
    }

    /**
    * Handles the course-management responsibility of gradeWorkflowLocked.
    */
    public boolean gradeWorkflowLocked(Connection connection, long enrollmentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT " + GRADE_LOCKED
                + " FROM enrollment e WHERE e.enrollment_id=?")) {
            statement.setLong(1, enrollmentId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() && rows.getBoolean(1); }
        }
    }

    /** Existing grade projections and submitted snapshots cannot change while removal is checked. */
    public void lockGradeWorkflow(Connection connection, long enrollmentId) throws SQLException {
        lockRows(connection, "SELECT grade_id FROM grade WHERE enrollment_id=? FOR UPDATE", enrollmentId);
        lockRows(connection, "SELECT gs.submission_id,gi.item_id FROM grade_submission_item gi"
                + " JOIN grade_submission gs ON gs.submission_id=gi.submission_id"
                + " WHERE gi.enrollment_id=? ORDER BY gs.submission_id,gi.item_id FOR UPDATE", enrollmentId);
    }

    /**
    * Handles the course-management responsibility of enroll.
    */
    public void enroll(Connection connection, String uid, OfferingRow offering, EnrollmentRow existing, Instant now)
            throws SQLException {
        if (existing == null) {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO enrollment"
                    + "(offering_id,course_id,academic_year,semester,uid,status,select_time,drop_time)"
                    + " VALUES(?,?,?,?,?,2,?,NULL)")) {
                statement.setLong(1, offering.offeringId());
                statement.setLong(2, offering.courseId());
                statement.setInt(3, offering.academicYear());
                statement.setInt(4, offering.semester());
                statement.setString(5, uid);
                statement.setTimestamp(6, timestamp(now));
                requireChanged(statement);
            }
        } else {
            try (PreparedStatement statement = connection.prepareStatement("UPDATE enrollment"
                    + " SET status=2,select_time=?,drop_time=NULL WHERE enrollment_id=? AND status=3")) {
                statement.setTimestamp(1, timestamp(now));
                statement.setLong(2, existing.enrollmentId());
                requireChanged(statement);
            }
        }
    }

    /**
    * Removes or cancels drop data.
    */
    public void drop(Connection connection, long enrollmentId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE enrollment"
                + " SET status=3,drop_time=? WHERE enrollment_id=? AND status=2")) {
            statement.setTimestamp(1, timestamp(now));
            statement.setLong(2, enrollmentId);
            requireChanged(statement);
        }
    }

    /** Forced administration may exceed capacity, but never make the count negative. */
    public void changeEnrolledCount(Connection connection, long offeringId, int delta) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE course_offering"
                + " SET enrolled_count=enrolled_count+? WHERE offering_id=? AND enrolled_count+?>=0")) {
            statement.setInt(1, delta);
            statement.setLong(2, offeringId);
            statement.setInt(3, delta);
            requireChanged(statement);
        }
    }

    /**
    * Handles the course-management responsibility of settleWaitlistAndPlan.
    */
    public void settleWaitlistAndPlan(Connection connection, String uid, long offeringId) throws SQLException {
        CourseWaitlistDAO.WaitlistRow row = waitlistDAO.findForUpdate(connection, uid, offeringId);
        if (row != null && ("WAITING".equals(row.status()) || "OFFERED".equals(row.status()))) {
            waitlistDAO.markStatus(connection, row.waitlistId(), row.status(), "ENROLLED");
        }
        selectionDAO.deletePlan(connection, uid, offeringId);
    }

    /**
    * Removes or cancels deletePlan data.
    */
    public void deletePlan(Connection connection, String uid, long offeringId) throws SQLException {
        selectionDAO.deletePlan(connection, uid, offeringId);
    }

    private static void bindSearch(PreparedStatement statement, int first, String query) throws SQLException {
        String pattern = "%" + query.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
        statement.setString(first, query);
        statement.setString(first + 1, pattern);
        statement.setString(first + 2, pattern);
    }

    private static StudentRow student(ResultSet rows) throws SQLException {
        return new StudentRow(rows.getString("UID"), rows.getString("name"), rows.getString("major_name"),
                rows.getInt("cohort_year"), rows.getInt("role"), rows.getString("academic_status"));
    }

    private static OfferingStudentDTO offeringStudent(ResultSet rows) throws SQLException {
        StudentRow student = student(rows);
        int status = rows.getInt("enrollment_status");
        String blocked = status != 2 ? "该学生当前已退课"
                : !student.active() ? "学生学籍状态不可用"
                : rows.getInt("offering_status") == 4 ? "教学班已取消"
                : !"ACTIVE".equals(rows.getString("course_status")) ? "课程已归档"
                : rows.getBoolean("grade_locked") ? "该学生已进入成绩审批或已有发布成绩，不能移除" : null;
        return new OfferingStudentDTO(rows.getString("enrollment_id"), student.uid(), student.name(),
                student.major(), student.cohortYear(), status == 2 ? "ENROLLED" : "DROPPED",
                blocked == null, blocked);
    }

    private static void lockRows(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) { while (rows.next()) { /* acquire all locks */ } }
        }
    }

    private static void requireChanged(PreparedStatement statement) throws SQLException {
        if (statement.executeUpdate() != 1) throw new SQLException("Enrollment state or count changed concurrently");
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(instant.atOffset(ZoneOffset.UTC).toLocalDateTime());
    }

    /**
    * Internal course-management type StudentRow.
    */
    public record StudentRow(String uid, String name, String major, int cohortYear, int role, String status) {
        /**
        * Handles the course-management responsibility of active.
        */
        public boolean active() { return role == 2 && "ACTIVE".equals(status); }
        /**
        * Handles the course-management responsibility of dto.
        */
        public StudentSearchResultDTO dto() { return new StudentSearchResultDTO(uid, name, major, cohortYear, status); }
    }

    /**
    * Internal course-management type OfferingRow.
    */
    public record OfferingRow(long offeringId, long courseId, int academicYear, int semester, int status,
                              int capacity, int enrolledCount, String courseStatus, String prerequisites) { }
    /**
    * Internal course-management type EnrollmentRow.
    */
    public record EnrollmentRow(long enrollmentId, int status) { }
}
