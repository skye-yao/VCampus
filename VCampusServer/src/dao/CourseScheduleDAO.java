package dao;

import dto.course.CourseNoticeDTO;
import dto.course.ScheduleEntryDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class CourseScheduleDAO {
    public List<ScheduleEntryDTO> loadSchedule(Connection connection, String studentUid,
                                               int academicYear, int semester, int week)
            throws SQLException {
        long planId = publishedPlanId(connection, academicYear, semester);
        String sql = "SELECT o.offering_id, c.course_code, c.course_name,"
                + " GROUP_CONCAT(DISTINCT teacher.name ORDER BY teacher.name SEPARATOR ', ') AS teacher,"
                + " GROUP_CONCAT(DISTINCT room.name ORDER BY room.name SEPARATOR ', ') AS location,"
                + " r.weekday AS day_of_week, r.start_period, r.end_period,"
                + " MIN(rw.week_no) AS start_week, MAX(rw.week_no) AS end_week"
                + " FROM course_schedule_rule r"
                + " JOIN course_schedule_rule_week rw ON rw.rule_id = r.id AND rw.week_no = ?"
                + " JOIN course_offering o ON o.offering_id = r.course_offering_id"
                + " JOIN course c ON c.course_id = o.course_id"
                + " JOIN enrollment e ON e.offering_id = o.offering_id"
                + "     AND e.uid = ? AND e.status = 2"
                + " LEFT JOIN course_offering_teacher cot ON cot.offering_id = o.offering_id"
                + " LEFT JOIN tbl_user teacher ON teacher.UID = cot.uid"
                + " LEFT JOIN course_occurrence occ ON occ.rule_id = r.id AND occ.week_no = ?"
                + " LEFT JOIN resource_booking rb ON rb.occurrence_id = occ.id"
                + "     AND rb.resource_role = 'CLASSROOM'"
                + " LEFT JOIN schedule_resource sr ON sr.id = rb.resource_id"
                + "     AND sr.resource_type = 'classroom'"
                + " LEFT JOIN classroom room ON CAST(room.id AS CHAR) = sr.business_id"
                + " WHERE r.plan_id = ? AND r.status = 'ACTIVE'"
                + " AND o.academic_year = ? AND o.semester = ?"
                + " GROUP BY r.id, o.offering_id, c.course_code, c.course_name,"
                + " r.weekday, r.start_period, r.end_period"
                + " ORDER BY r.weekday, r.start_period, o.offering_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, week);
            statement.setString(2, studentUid);
            statement.setInt(3, week);
            statement.setLong(4, planId);
            statement.setInt(5, academicYear);
            statement.setInt(6, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return mapScheduleRows(rows,
                        CourseQueryDAO.term(academicYear, semester).getDisplayName());
            }
        }
    }

    public List<CourseNoticeDTO> loadNotices(Connection connection, String studentUid,
                                             int academicYear, int semester, int week)
            throws SQLException {
        String sql = "SELECT n.notice_id, n.offering_id, n.week_no, n.notice_type,"
                + " n.title, n.content FROM course_notice n"
                + " JOIN course_offering o ON o.offering_id = n.offering_id"
                + " JOIN enrollment e ON e.offering_id = o.offering_id"
                + "     AND e.uid = ? AND e.status = 2"
                + " WHERE o.academic_year = ? AND o.semester = ?"
                + " AND n.status = 'PUBLISHED' AND (n.week_no IS NULL OR n.week_no = ?)"
                + " ORDER BY n.published_at DESC, n.notice_id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            statement.setInt(2, academicYear);
            statement.setInt(3, semester);
            statement.setInt(4, week);
            try (ResultSet rows = statement.executeQuery()) {
                return mapNoticeRows(rows,
                        CourseQueryDAO.term(academicYear, semester).getDisplayName());
            }
        }
    }

    static List<ScheduleEntryDTO> mapScheduleRows(ResultSet rows, String term)
            throws SQLException {
        List<ScheduleEntryDTO> entries = new ArrayList<>();
        while (rows.next()) {
            int start = rows.getInt("start_period");
            int end = rows.getInt("end_period");
            entries.add(new ScheduleEntryDTO(rows.getString("offering_id"), term,
                    rows.getString("course_code"), rows.getString("course_name"),
                    rows.getString("teacher"), rows.getString("location"),
                    rows.getInt("day_of_week"), start, end - start + 1,
                    rows.getInt("start_week"), rows.getInt("end_week")));
        }
        return List.copyOf(entries);
    }

    static List<CourseNoticeDTO> mapNoticeRows(ResultSet rows, String term)
            throws SQLException {
        List<CourseNoticeDTO> notices = new ArrayList<>();
        while (rows.next()) {
            Object week = rows.getObject("week_no");
            notices.add(new CourseNoticeDTO(rows.getString("notice_id"),
                    rows.getString("offering_id"), term,
                    week == null ? 0 : rows.getInt("week_no"),
                    rows.getString("notice_type"), rows.getString("title"),
                    rows.getString("content")));
        }
        return List.copyOf(notices);
    }

    private static long publishedPlanId(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT win.schedule_plan_id FROM course_selection_window win"
                + " JOIN schedule_plan sp ON sp.id = win.schedule_plan_id"
                + " WHERE win.academic_year = ? AND win.semester = ?"
                + " AND sp.status = 'PUBLISHED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Published schedule plan is unavailable");
                return rows.getLong(1);
            }
        }
    }
}
