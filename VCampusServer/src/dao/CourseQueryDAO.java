package dao;

import dto.course.CourseDTO;
import dto.course.CourseMeetingDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.CourseTeacherDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CourseQueryDAO {
    private static final String VISIBLE_COURSE = ""
            + " EXISTS (SELECT 1 FROM student_academic_profile sap"
            + " WHERE sap.uid = ? AND sap.status = 'ACTIVE'"
            + " AND (c.allow_cross_major = 1 OR EXISTS (SELECT 1 FROM course_major cm"
            + "     WHERE cm.course_id = c.course_id AND cm.major_id = sap.major_id))"
            + " AND (NOT EXISTS (SELECT 1 FROM course_year cy0 WHERE cy0.course_id = c.course_id)"
            + "     OR EXISTS (SELECT 1 FROM course_year cy WHERE cy.course_id = c.course_id"
            + "         AND cy.year = (o.academic_year - sap.cohort_year + 1))))";

    private static final String OFFERING_SELECT = ""
            + "SELECT c.course_id, c.course_code, c.course_name, c.course_type, c.credit,"
            + " c.credit_hours, c.description, c.prerequisites, o.offering_id,"
            + " o.offering_code, o.enrolled_count, o.capacity, pi.status AS plan_status,"
            + " pi.last_failure_reason AS failure_reason, cw.status AS waitlist_status,"
            + " cw.offered_at, cw.expires_at, e.enrollment_id,"
            + " cot.uid AS teacher_uid, tu.name AS teacher_name,"
            + " meeting.meeting_id, meeting.day_of_week, meeting.start_period,"
            + " meeting.end_period, meeting.start_week, meeting.end_week,"
            + " meeting.week_pattern, meeting.location, meeting.starts_at_utc,"
            + " meeting.ends_at_utc"
            + " FROM course_offering o"
            + " JOIN course c ON c.course_id = o.course_id"
            + " JOIN course_selection_window win ON win.academic_year = o.academic_year"
            + "     AND win.semester = o.semester"
            + " JOIN schedule_plan sp ON sp.id = win.schedule_plan_id AND sp.status = 'PUBLISHED'"
            + " LEFT JOIN course_plan_item pi ON pi.offering_id = o.offering_id AND pi.uid = ?"
            + " LEFT JOIN course_waitlist cw ON cw.offering_id = o.offering_id AND cw.uid = ?"
            + " LEFT JOIN enrollment e ON e.offering_id = o.offering_id AND e.uid = ?"
            + "     AND e.status = 2"
            + " LEFT JOIN course_offering_teacher cot ON cot.offering_id = o.offering_id"
            + " LEFT JOIN tbl_user tu ON tu.UID = cot.uid"
            // 周次与教室分别按规则预聚合后再拼到规则上；两者若同时 JOIN 到规则会形成
            // 周次 x 实例的笛卡尔积（每条规则 13 x 13 行），再乘教室预订后需对上万行做
            // GROUP_CONCAT，实测单次查询 400-900 ms。拆开后每个子查询都是线性扫描。
            + " LEFT JOIN ("
            + "   SELECT r.id AS meeting_id, r.plan_id, r.course_offering_id,"
            + "     r.weekday AS day_of_week, r.start_period, r.end_period,"
            + "     wk.start_week, wk.end_week, wk.week_pattern,"
            + "     rm.location, wk.starts_at_utc, wk.ends_at_utc"
            + "   FROM course_schedule_rule r"
            + "   LEFT JOIN ("
            + "     SELECT rw.rule_id, MIN(rw.week_no) AS start_week, MAX(rw.week_no) AS end_week,"
            + "       GROUP_CONCAT(DISTINCT rw.week_no ORDER BY rw.week_no SEPARATOR ',') AS week_pattern,"
            + "       MIN(occ.start_at) AS starts_at_utc, MAX(occ.end_at) AS ends_at_utc"
            + "     FROM course_schedule_rule_week rw"
            + "     LEFT JOIN course_occurrence occ ON occ.rule_id = rw.rule_id"
            + "         AND occ.week_no = rw.week_no"
            + "     GROUP BY rw.rule_id"
            + "   ) wk ON wk.rule_id = r.id"
            + "   LEFT JOIN ("
            + "     SELECT occ.rule_id,"
            + "       GROUP_CONCAT(DISTINCT room.name ORDER BY room.name SEPARATOR ', ') AS location"
            + "     FROM course_occurrence occ"
            + "     JOIN resource_booking rb ON rb.occurrence_id = occ.id"
            + "         AND rb.resource_role = 'CLASSROOM'"
            + "     JOIN schedule_resource sr ON sr.id = rb.resource_id"
            + "         AND sr.resource_type = 'classroom'"
            + "     JOIN classroom room ON CAST(room.id AS CHAR) = sr.business_id"
            + "     GROUP BY occ.rule_id"
            + "   ) rm ON rm.rule_id = r.id"
            + "   WHERE r.status = 'ACTIVE'"
            + " ) meeting ON meeting.course_offering_id = o.offering_id"
            + "     AND meeting.plan_id = win.schedule_plan_id ";

    public List<CourseTermDTO> listTerms(Connection connection, String studentUid)
            throws SQLException {
        String sql = "SELECT DISTINCT win.academic_year, win.semester "
                + "FROM course_selection_window win "
                + "JOIN schedule_plan sp ON sp.id = win.schedule_plan_id AND sp.status = 'PUBLISHED' "
                + "JOIN course_offering o ON o.academic_year = win.academic_year "
                + " AND o.semester = win.semester AND o.status = 2 "
                + "JOIN course c ON c.course_id = o.course_id WHERE " + VISIBLE_COURSE
                + " ORDER BY win.academic_year DESC, win.semester DESC";
        List<CourseTermDTO> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int year = rows.getInt("academic_year");
                    int semester = rows.getInt("semester");
                    terms.add(term(year, semester));
                }
            }
        }
        return List.copyOf(terms);
    }

    public List<CourseDTO> listCourses(Connection connection, String studentUid,
                                       int academicYear, int semester) throws SQLException {
        String sql = "SELECT DISTINCT c.course_id, c.course_code, c.course_name, c.course_type,"
                + " c.credit, c.credit_hours, c.description, c.prerequisites"
                + " FROM course c JOIN course_offering o ON o.course_id = c.course_id"
                + " JOIN course_selection_window win ON win.academic_year = o.academic_year"
                + " AND win.semester = o.semester"
                + " JOIN schedule_plan sp ON sp.id = win.schedule_plan_id AND sp.status = 'PUBLISHED'"
                + " WHERE o.academic_year = ? AND o.semester = ? AND o.status = 2 AND "
                + VISIBLE_COURSE + " ORDER BY c.course_code";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            statement.setString(3, studentUid);
            try (ResultSet rows = statement.executeQuery()) {
                return mapCourseRows(rows);
            }
        }
    }

    public List<CourseOfferingDTO> listCourseOfferings(Connection connection, String studentUid,
                                                       int academicYear, int semester,
                                                       long courseId) throws SQLException {
        String sql = OFFERING_SELECT
                + " WHERE o.academic_year = ? AND o.semester = ? AND o.course_id = ?"
                + " AND o.status = 2 AND " + VISIBLE_COURSE
                + " ORDER BY o.offering_id, cot.uid, meeting.meeting_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setIdentity(statement, studentUid);
            statement.setInt(4, academicYear);
            statement.setInt(5, semester);
            statement.setLong(6, courseId);
            statement.setString(7, studentUid);
            try (ResultSet rows = statement.executeQuery()) {
                return mapOfferingRows(rows);
            }
        }
    }

    public CoursePlanSnapshotDTO loadSelectionSnapshot(Connection connection, String studentUid,
                                                       int academicYear, int semester)
            throws SQLException {
        String sql = OFFERING_SELECT
                + " WHERE o.academic_year = ? AND o.semester = ?"
                + " AND (pi.plan_item_id IS NOT NULL"
                + " OR cw.status IN ('WAITING', 'OFFERED') OR e.enrollment_id IS NOT NULL)"
                + " ORDER BY o.offering_id, cot.uid, meeting.meeting_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            setIdentity(statement, studentUid);
            statement.setInt(4, academicYear);
            statement.setInt(5, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return mapSnapshotRows(rows, term(academicYear, semester));
            }
        }
    }

    private static void setIdentity(PreparedStatement statement, String uid) throws SQLException {
        statement.setString(1, uid);
        statement.setString(2, uid);
        statement.setString(3, uid);
    }

    static List<CourseDTO> mapCourseRows(ResultSet rows) throws SQLException {
        List<CourseDTO> courses = new ArrayList<>();
        while (rows.next()) courses.add(mapCourse(rows));
        return List.copyOf(courses);
    }

    static List<CourseOfferingDTO> mapOfferingRows(ResultSet rows) throws SQLException {
        return readOfferingRows(rows).values().stream()
                .map(OfferingAccumulator::offering).toList();
    }

    static CoursePlanSnapshotDTO mapSnapshotRows(ResultSet rows, CourseTermDTO term)
            throws SQLException {
        List<CourseSelectionItemDTO> plan = new ArrayList<>();
        List<CourseSelectionItemDTO> waitlist = new ArrayList<>();
        List<CourseSelectionItemDTO> enrolled = new ArrayList<>();
        for (OfferingAccumulator value : readOfferingRows(rows).values()) {
            CourseOfferingDTO offering = value.offering();
            CourseSelectionItemDTO item = new CourseSelectionItemDTO(value.course, offering);
            switch (offering.getSelectionState()) {
                case PLANNED, FULL -> plan.add(item);
                case WAITLISTED, WAITLIST_OFFERED -> waitlist.add(item);
                case ENROLLED -> enrolled.add(item);
                case AVAILABLE -> { }
            }
        }
        return new CoursePlanSnapshotDTO(term, plan, waitlist, enrolled);
    }

    private static Map<String, OfferingAccumulator> readOfferingRows(ResultSet rows)
            throws SQLException {
        Map<String, OfferingAccumulator> offerings = new LinkedHashMap<>();
        while (rows.next()) {
            String id = rows.getString("offering_id");
            OfferingAccumulator value = offerings.computeIfAbsent(id,
                    ignored -> new OfferingAccumulator(uncheckedCourse(rows), id,
                            stateText(rows, "offering_code"),
                            rowsInt(rows, "enrolled_count"), rowsInt(rows, "capacity"),
                            state(rows), stateText(rows, "failure_reason"),
                            utcUnchecked(rows, "offered_at"), utcUnchecked(rows, "expires_at")));
            String teacherUid = rows.getString("teacher_uid");
            if (teacherUid != null) {
                value.teachers.putIfAbsent(teacherUid,
                        new CourseTeacherDTO(teacherUid, rows.getString("teacher_name")));
            }
            Object meetingId = rows.getObject("meeting_id");
            if (meetingId != null) {
                String key = rows.getString("meeting_id");
                value.meetings.putIfAbsent(key, new CourseMeetingDTO(
                        rows.getInt("day_of_week"), rows.getInt("start_period"),
                        rows.getInt("end_period"), rows.getInt("start_week"),
                        rows.getInt("end_week"), rows.getString("week_pattern"),
                        rows.getString("location"), utc(rows, "starts_at_utc"),
                        utc(rows, "ends_at_utc")));
            }
        }
        return offerings;
    }

    private static CourseDTO uncheckedCourse(ResultSet rows) {
        try {
            return mapCourse(rows);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    private static CourseDTO mapCourse(ResultSet rows) throws SQLException {
        return new CourseDTO(rows.getString("course_id"), rows.getString("course_code"),
                rows.getString("course_name"), courseType(rows.getInt("course_type")),
                rows.getDouble("credit"), rows.getInt("credit_hours"),
                rows.getString("description"), rows.getString("prerequisites"));
    }

    private static SelectionStateDTO state(ResultSet rows) {
        try {
            if (rows.getObject("enrollment_id") != null) return SelectionStateDTO.ENROLLED;
            String waitlist = rows.getString("waitlist_status");
            if ("OFFERED".equals(waitlist)) return SelectionStateDTO.WAITLIST_OFFERED;
            if ("WAITING".equals(waitlist)) return SelectionStateDTO.WAITLISTED;
            String plan = rows.getString("plan_status");
            if ("FULL".equals(plan)) return SelectionStateDTO.FULL;
            if ("PLANNED".equals(plan)) return SelectionStateDTO.PLANNED;
            return SelectionStateDTO.AVAILABLE;
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    private static String stateText(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    private static int rowsInt(ResultSet rows, String column) {
        try {
            return rows.getInt(column);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    static String utc(ResultSet rows, String column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC).toString();
    }

    private static String utcUnchecked(ResultSet rows, String column) {
        try {
            return utc(rows, column);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    public static CourseTermDTO term(int academicYear, int semester) {
        String label = switch (semester) {
            case 1 -> "暑期学校";
            case 2 -> "秋学期";
            case 3 -> "春学期";
            default -> "第" + semester + "学期";
        };
        return new CourseTermDTO(academicYear, semester,
                academicYear + "-" + (academicYear + 1) + " " + label);
    }

    private static String courseType(int value) {
        return switch (value) {
            case 1 -> "必修";
            case 2 -> "限选";
            case 3 -> "选修";
            case 4 -> "通选";
            default -> throw new IllegalArgumentException("Unknown course type: " + value);
        };
    }

    private static final class OfferingAccumulator {
        private final CourseDTO course;
        private final String offeringId;
        private final String offeringCode;
        private final int enrolledCount;
        private final int capacity;
        private final SelectionStateDTO state;
        private final String failureReason;
        private final String offeredAt;
        private final String expiresAt;
        private final Map<String, CourseTeacherDTO> teachers = new LinkedHashMap<>();
        private final Map<String, CourseMeetingDTO> meetings = new LinkedHashMap<>();

        private OfferingAccumulator(CourseDTO course, String offeringId, String offeringCode,
                                    int enrolledCount,
                                    int capacity, SelectionStateDTO state, String failureReason,
                                    String offeredAt, String expiresAt) {
            this.course = course;
            this.offeringId = offeringId;
            this.offeringCode = offeringCode;
            this.enrolledCount = enrolledCount;
            this.capacity = capacity;
            this.state = state;
            this.failureReason = state == SelectionStateDTO.PLANNED
                    || state == SelectionStateDTO.FULL ? failureReason : null;
            this.offeredAt = state == SelectionStateDTO.WAITLIST_OFFERED ? offeredAt : null;
            this.expiresAt = state == SelectionStateDTO.WAITLIST_OFFERED ? expiresAt : null;
        }

        private CourseOfferingDTO offering() {
            return new CourseOfferingDTO(offeringId, offeringCode, course.getCourseId(),
                    new ArrayList<>(teachers.values()), new ArrayList<>(meetings.values()),
                    enrolledCount, capacity, state, failureReason, offeredAt, expiresAt);
        }
    }

    private static final class RowMappingException extends RuntimeException {
        private RowMappingException(SQLException cause) {
            super(cause);
        }
    }
}
