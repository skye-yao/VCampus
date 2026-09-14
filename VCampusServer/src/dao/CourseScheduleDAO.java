package dao;

import dto.course.CourseNoticeDTO;
import dto.course.ScheduleDisplayKindDTO;
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
        // The ACTIVE adjustment of the week's occurrence, if any, rides along so the mapping can
        // emit the published arrangement and the temporary replacement as one pair. The proposal
        // lives on the request: the adjustment row itself only stores the resolved UTC window.
        String sql = "SELECT o.offering_id, c.course_code, c.course_name,"
                + " GROUP_CONCAT(DISTINCT teacher.name ORDER BY teacher.name SEPARATOR ', ') AS teacher,"
                + " GROUP_CONCAT(DISTINCT room.name ORDER BY room.name SEPARATOR ', ') AS location,"
                + " r.weekday AS day_of_week, r.start_period, r.end_period,"
                + " MIN(rw.week_no) AS start_week, MAX(rw.week_no) AS end_week,"
                + " j.adjustment_id AS adjustment_id, q.reason AS adjustment_reason,"
                + " q.new_weekday AS new_weekday, q.new_start_period AS new_start_period,"
                + " q.new_end_period AS new_end_period,"
                + " CONCAT_WS(', ', adj_teacher.name, adj_assistant.name) AS adjusted_teacher,"
                + " adj_room.name AS adjusted_location"
                + " FROM course_schedule_rule r"
                + " JOIN course_schedule_rule_week rw ON rw.rule_id = r.id AND rw.week_no = ?"
                + " JOIN course_offering o ON o.offering_id = r.course_offering_id"
                + " JOIN course c ON c.course_id = o.course_id"
                + " JOIN enrollment e ON e.offering_id = o.offering_id"
                + "     AND e.uid = ? AND e.status = 2"
                + " LEFT JOIN course_offering_teacher cot ON cot.offering_id = o.offering_id"
                + " LEFT JOIN tbl_user teacher ON teacher.UID = cot.uid"
                + " LEFT JOIN course_occurrence occ ON occ.rule_id = r.id AND occ.week_no = ?"
                + " LEFT JOIN course_schedule_adjustment j ON j.original_occurrence_id = occ.id"
                + "     AND j.status = 'ACTIVE'"
                + " LEFT JOIN course_schedule_adjustment_request q ON q.request_id = j.request_id"
                + " LEFT JOIN tbl_user adj_teacher ON adj_teacher.UID = j.teacher_uid"
                + " LEFT JOIN tbl_user adj_assistant ON adj_assistant.UID = j.assistant_uid"
                + " LEFT JOIN classroom adj_room ON adj_room.id = j.classroom_id"
                + " LEFT JOIN resource_booking rb ON rb.occurrence_id = occ.id"
                + "     AND rb.resource_role = 'CLASSROOM'"
                + " LEFT JOIN schedule_resource sr ON sr.id = rb.resource_id"
                + "     AND sr.resource_type = 'classroom'"
                + " LEFT JOIN classroom room ON CAST(room.id AS CHAR) = sr.business_id"
                + " WHERE r.plan_id = ? AND r.status = 'ACTIVE'"
                + " AND o.academic_year = ? AND o.semester = ?"
                + " GROUP BY r.id, o.offering_id, c.course_code, c.course_name,"
                + " r.weekday, r.start_period, r.end_period, j.adjustment_id, q.reason,"
                + " q.new_weekday, q.new_start_period, q.new_end_period, adj_teacher.name,"
                + " adj_assistant.name, adj_room.name"
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

    /**
     * One row per published weekly meeting. A meeting whose occurrence carries an ACTIVE adjustment
     * becomes a pair: the original is display-only and no longer occupies its slot, while the target
     * states where the lesson actually happens. Either half carries both texts, so a detail view can
     * render from whichever block the student picked.
     */
    static List<ScheduleEntryDTO> mapScheduleRows(ResultSet rows, String term)
            throws SQLException {
        List<ScheduleEntryDTO> entries = new ArrayList<>();
        while (rows.next()) {
            int start = rows.getInt("start_period");
            int end = rows.getInt("end_period");
            String adjustmentId = rows.getString("adjustment_id");
            if (adjustmentId == null) {
                entries.add(new ScheduleEntryDTO(rows.getString("offering_id"), term,
                        rows.getString("course_code"), rows.getString("course_name"),
                        rows.getString("teacher"), rows.getString("location"),
                        rows.getInt("day_of_week"), start, end - start + 1,
                        rows.getInt("start_week"), rows.getInt("end_week")));
                continue;
            }
            int newStart = rows.getInt("new_start_period");
            int newEnd = rows.getInt("new_end_period");
            String originalText = scheduleText(rows.getInt("day_of_week"), start, end,
                    rows.getString("location"));
            String adjustedText = scheduleText(rows.getInt("new_weekday"), newStart, newEnd,
                    rows.getString("adjusted_location"));
            String reason = rows.getString("adjustment_reason");
            entries.add(new ScheduleEntryDTO(rows.getString("offering_id"), term,
                    rows.getString("course_code"), rows.getString("course_name"),
                    rows.getString("teacher"), rows.getString("location"),
                    rows.getInt("day_of_week"), start, end - start + 1,
                    rows.getInt("start_week"), rows.getInt("end_week"),
                    ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, adjustmentId, originalText,
                    adjustedText, reason));
            entries.add(new ScheduleEntryDTO(rows.getString("offering_id"), term,
                    rows.getString("course_code"), rows.getString("course_name"),
                    rows.getString("adjusted_teacher"), rows.getString("adjusted_location"),
                    rows.getInt("new_weekday"), newStart, newEnd - newStart + 1,
                    rows.getInt("start_week"), rows.getInt("end_week"),
                    ScheduleDisplayKindDTO.ADJUSTED_TARGET, adjustmentId, originalText,
                    adjustedText, reason));
        }
        return List.copyOf(entries);
    }

    private static String scheduleText(int dayOfWeek, int startPeriod, int endPeriod,
                                       String location) {
        String day = switch (dayOfWeek) {
            case 1 -> "周一";
            case 2 -> "周二";
            case 3 -> "周三";
            case 4 -> "周四";
            case 5 -> "周五";
            case 6 -> "周六";
            case 7 -> "周日";
            default -> "周" + dayOfWeek;
        };
        return day + " 第" + startPeriod + "-" + endPeriod + "节"
                + (location == null || location.isBlank() ? "" : " " + location);
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
        String current = "SELECT sp.id FROM teaching_calendar cal"
                + " LEFT JOIN schedule_plan sp ON sp.id = cal.current_schedule_plan_id"
                + " AND sp.calendar_id = cal.id AND sp.status = 'PUBLISHED'"
                + " WHERE cal.academic_year = ? AND cal.semester = ?"
                + " AND cal.current_schedule_plan_id IS NOT NULL"
                + " ORDER BY cal.version DESC, cal.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(current)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    long planId = rows.getLong(1);
                    if (rows.wasNull()) throw new SQLException("Current schedule plan is unavailable");
                    return planId;
                }
            }
        }
        // Pre-V004 data has no current pointer and still uses the selection-window binding.
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
