package dao;

import dto.course.CourseCalendarDateDTO;
import dto.course.CourseNoticeDTO;
import dto.course.CoursePeriodDTO;
import dto.course.CourseScheduleWeekDTO;
import dto.course.ScheduleDisplayKindDTO;
import dto.course.ScheduleEntryDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CourseScheduleDAO {
    /** Period clock strings are a fixed wire shape; {@code LocalTime.toString()} drops zero seconds. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Stable display order: teaching day, first period, exact offering id, then the half. */
    private static final Comparator<ScheduleEntryDTO> ENTRY_ORDER =
            Comparator.comparingInt(ScheduleEntryDTO::getDayOfWeek)
                    .thenComparingInt(ScheduleEntryDTO::getStartPeriod)
                    .thenComparing(entry -> parseOfferingId(entry.getOfferingId()))
                    .thenComparingInt(entry -> displayKindRank(entry.getDisplayKind()));

    /**
     * The student week view covers both date ranges the design requires: the published meetings of
     * the week (an ACTIVE adjustment keeps its original as a display-only marker) and the ACTIVE
     * adjustments whose effective target instant falls inside this week, which is what makes a
     * cross-week move appear in its target week. The published query alone can never describe the
     * new position, so it only ever contributes NORMAL or ADJUSTED_ORIGINAL entries.
     *
     * <p>The grid geometry travels with the entries: {@code dates} and {@code periods} come from
     * {@code calendar_date ⋈ period_definition}, so the row and column counts are never hardcoded
     * on either side and the student grid matches the teacher timetable. The same response carries
     * the calendar's teaching-week bounds and today's teaching week (both from the teaching
     * calendar's own time zone), so the client can offer “back to the current week” instead of
     * hardcoding a range.
     *
     * @param week 可为 null（或非正）：取 {@code clock} 所在教学周，今天不在学期内时取最小教学周；
     *             明确给定时照旧查看那一周（见 {@link #effectiveWeek}）
     */
    public CourseScheduleWeekDTO loadSchedule(Connection connection, String studentUid,
                                              int academicYear, int semester, Integer week,
                                              Clock clock)
            throws SQLException {
        long planId = publishedPlanId(connection, academicYear, semester);
        long calendar = calendarId(connection, planId);
        int[] bounds = weekBounds(connection, calendar);
        Integer currentWeek = currentWeek(connection, calendar, clock);
        int effectiveWeek = effectiveWeek(week, bounds, currentWeek);
        // 教学日历一个日期都没有时无从谈"范围"，退回"只有正在查看的这一周"（与旧构造同一语义）。
        int minWeek = bounds == null ? effectiveWeek : bounds[0];
        int maxWeek = bounds == null ? effectiveWeek : bounds[1];
        String term = CourseQueryDAO.term(academicYear, semester).getDisplayName();
        List<ScheduleEntryDTO> entries = new ArrayList<>(publishedEntries(connection, studentUid,
                academicYear, semester, effectiveWeek, planId, term));
        // The paired target half of a published week is rebuilt from the adjustment instant below;
        // dropping it here is exactly what keeps a cross-week target out of its origin week.
        entries.removeIf(entry -> ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind());
        entries.addAll(adjustedTargets(connection, studentUid, academicYear, semester, effectiveWeek,
                planId, term));
        entries.sort(ENTRY_ORDER);
        return new CourseScheduleWeekDTO(effectiveWeek, minWeek, maxWeek, currentWeek,
                dates(connection, calendar, effectiveWeek),
                periods(connection, calendar, effectiveWeek), entries);
    }

    // ------------------------------------------------------------------ 周范围与当前周

    /**
     * min/max teaching week over the whole calendar; non-teaching weeks stay navigable. {@code null}
     * when the calendar carries no date at all——那时既没有范围也没有当前周，界面只显示正在查看的
     * 那一周（旧行为：没有日期的教学日历返回一周空课表，不能被这里改成报错）。
     */
    private static int[] weekBounds(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT MIN(week_no) AS min_week, MAX(week_no) AS max_week"
                + " FROM calendar_date WHERE calendar_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject("min_week") == null
                        || rows.getObject("max_week") == null) {
                    return null;
                }
                return new int[] {rows.getInt("min_week"), rows.getInt("max_week")};
            }
        }
    }

    /**
     * 今天落在哪一个教学周，判定用教学日历自己的时区而不是 JVM 时区；今天不在学期内时为 null
     * （响应里照实为空，GUI 据此禁用“回到本周”）。
     */
    private static Integer currentWeek(Connection connection, long calendarId, Clock clock)
            throws SQLException {
        ZoneId zone = ZoneId.of(calendarZone(connection, calendarId));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        String sql = "SELECT week_no FROM calendar_date WHERE calendar_id = ? AND local_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setDate(2, java.sql.Date.valueOf(today));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt("week_no") : null;
            }
        }
    }

    private static String calendarZone(Connection connection, long calendarId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT timezone FROM teaching_calendar WHERE id = ?")) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Teaching calendar is unavailable");
                return rows.getString("timezone");
            }
        }
    }

    /**
     * 实际查看哪一周：缺省（或非正）时取服务端当前周，当前周为空再退回最小教学周（两者都没有时退回
     * 第 1 周）；明确给定时**原样使用**。
     *
     * <p>越界的一周不报错而是一周空课表：这是学生端既有的语义（跨周调课的目标周判定、以及
     * {@code loadSchedule} 的既有调用点都按"没有课"处理），范围只用来告诉界面可选哪些周——周次
     * 控件据此夹取，用户根本走不到越界值。教师端的 {@code TeacherScheduleDAO} 选择报错，是因为那条
     * 链路只服务教师自己的课表，两边在这一点的差别是有意的。
     */
    private static int effectiveWeek(Integer week, int[] bounds, Integer currentWeek) {
        if (week != null && week > 0) return week;
        if (currentWeek != null) return currentWeek;
        return bounds == null ? 1 : bounds[0];
    }

    /**
     * Published meetings of the week; the ACTIVE adjustment of the week's occurrence, if any, rides
     * along so the mapping can emit the published arrangement and the temporary replacement as one
     * pair. The proposal lives on the request: the adjustment row itself only stores the resolved
     * UTC window. The pair's target half is discarded by the caller.
     */
    private static List<ScheduleEntryDTO> publishedEntries(Connection connection, String studentUid,
            int academicYear, int semester, int week, long planId, String term) throws SQLException {
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
                return mapScheduleRows(rows, term);
            }
        }
    }

    /**
     * ACTIVE adjustments whose target instant falls inside this week's local date range and whose
     * offering the student is still enrolled in ({@code status=2}, checked on this side exactly as
     * the published side checks it). The week of the target comes from the adjustment's resolved
     * UTC window mapped through the teaching calendar, never from the original occurrence, so a
     * cross-week move appears only in its real target week even when no rule publishes that week.
     */
    private static List<ScheduleEntryDTO> adjustedTargets(Connection connection, String studentUid,
            int academicYear, int semester, int week, long planId, String term) throws SQLException {
        Window window = weekWindow(connection, planId, week);
        if (window == null) return List.of();
        String sql = "SELECT o.offering_id, c.course_code, c.course_name,"
                + " CONCAT_WS(', ', adj_teacher.name, adj_assistant.name) AS adjusted_teacher,"
                + " adj_room.name AS adjusted_location,"
                + " q.new_weekday, q.new_start_period, q.new_end_period, q.reason,"
                + " r.weekday AS day_of_week, r.start_period, r.end_period,"
                + " GROUP_CONCAT(DISTINCT room.name ORDER BY room.name SEPARATOR ', ') AS location,"
                + " j.adjustment_id"
                + " FROM course_schedule_adjustment j"
                + " JOIN course_schedule_adjustment_request q ON q.request_id = j.request_id"
                + " JOIN course_occurrence occ ON occ.id = j.original_occurrence_id"
                + " JOIN course_schedule_rule r ON r.id = occ.rule_id"
                + " JOIN course_offering o ON o.offering_id = r.course_offering_id"
                + " JOIN course c ON c.course_id = o.course_id"
                + " JOIN enrollment e ON e.offering_id = o.offering_id AND e.uid = ? AND e.status = 2"
                + " LEFT JOIN tbl_user adj_teacher ON adj_teacher.UID = j.teacher_uid"
                + " LEFT JOIN tbl_user adj_assistant ON adj_assistant.UID = j.assistant_uid"
                + " LEFT JOIN classroom adj_room ON adj_room.id = j.classroom_id"
                + " LEFT JOIN resource_booking rb ON rb.occurrence_id = occ.id"
                + "     AND rb.resource_role = 'CLASSROOM'"
                + " LEFT JOIN schedule_resource sr ON sr.id = rb.resource_id"
                + "     AND sr.resource_type = 'classroom'"
                + " LEFT JOIN classroom room ON CAST(room.id AS CHAR) = sr.business_id"
                + " WHERE j.status = 'ACTIVE' AND r.plan_id = ? AND r.status = 'ACTIVE'"
                + " AND o.academic_year = ? AND o.semester = ?"
                + " AND j.start_at_utc >= ? AND j.start_at_utc < ?"
                + " GROUP BY j.adjustment_id, o.offering_id, c.course_code, c.course_name,"
                + " adj_teacher.name, adj_assistant.name, adj_room.name, q.new_weekday,"
                + " q.new_start_period, q.new_end_period, q.reason, r.weekday, r.start_period,"
                + " r.end_period"
                + " ORDER BY q.new_weekday, q.new_start_period, o.offering_id";
        List<ScheduleEntryDTO> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            statement.setLong(2, planId);
            statement.setInt(3, academicYear);
            statement.setInt(4, semester);
            statement.setTimestamp(5, utcTimestamp(window.start()));
            statement.setTimestamp(6, utcTimestamp(window.end()));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int newStart = rows.getInt("new_start_period");
                    int newEnd = rows.getInt("new_end_period");
                    String adjustedLocation = rows.getString("adjusted_location");
                    entries.add(new ScheduleEntryDTO(rows.getString("offering_id"), term,
                            rows.getString("course_code"), rows.getString("course_name"),
                            rows.getString("adjusted_teacher"), adjustedLocation,
                            rows.getInt("new_weekday"), newStart, newEnd - newStart + 1, week, week,
                            ScheduleDisplayKindDTO.ADJUSTED_TARGET, rows.getString("adjustment_id"),
                            scheduleText(rows.getInt("day_of_week"), rows.getInt("start_period"),
                                    rows.getInt("end_period"), rows.getString("location")),
                            scheduleText(rows.getInt("new_weekday"), newStart, newEnd,
                                    adjustedLocation),
                            rows.getString("reason")));
                }
            }
        }
        return entries;
    }

    /**
     * The queried week's whole local date range as a UTC instant window, or {@code null} when the
     * published plan's calendar has no date for that week. The stored UTC wall clock is compared
     * through the teaching calendar's time zone, so the target instant is judged on the local
     * teaching day, not on the JVM zone.
     */
    private static Window weekWindow(Connection connection, long planId, int week)
            throws SQLException {
        String sql = "SELECT cal.timezone, MIN(cd.local_date) AS first_date,"
                + " MAX(cd.local_date) AS last_date"
                + " FROM schedule_plan sp"
                + " JOIN teaching_calendar cal ON cal.id = sp.calendar_id"
                + " JOIN calendar_date cd ON cd.calendar_id = cal.id AND cd.week_no = ?"
                + " WHERE sp.id = ? GROUP BY cal.timezone";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, week);
            statement.setLong(2, planId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                ZoneId zone = ZoneId.of(rows.getString("timezone"));
                LocalDate first = rows.getDate("first_date").toLocalDate();
                LocalDate last = rows.getDate("last_date").toLocalDate();
                return new Window(first.atStartOfDay(zone).toInstant(),
                        last.plusDays(1).atStartOfDay(zone).toInstant());
            }
        }
    }

    /**
     * The published notice surface keeps the plain week filter, but a notice linked to an
     * adjustment request is scoped to that request's original and target weeks and returned once
     * (the EXISTS de-duplicates multiple targets): the summary must not sit in every week of the
     * term, and a same-week move must not produce two notices.
     */
    public List<CourseNoticeDTO> loadNotices(Connection connection, String studentUid,
                                             int academicYear, int semester, int week)
            throws SQLException {
        String sql = "SELECT n.notice_id, n.offering_id, n.week_no, n.notice_type,"
                + " n.title, n.content FROM course_notice n"
                + " JOIN course_offering o ON o.offering_id = n.offering_id"
                + " JOIN enrollment e ON e.offering_id = o.offering_id"
                + "     AND e.uid = ? AND e.status = 2"
                + " WHERE o.academic_year = ? AND o.semester = ?"
                + " AND n.status = 'PUBLISHED'"
                + " AND ((n.adjustment_request_id IS NULL AND (n.week_no IS NULL OR n.week_no = ?))"
                + " OR EXISTS (SELECT 1 FROM course_schedule_adjustment_target t"
                + "     LEFT JOIN calendar_date cd ON cd.id = t.target_calendar_date_id"
                + "     WHERE t.request_id = n.adjustment_request_id"
                + "     AND (t.original_week_no = ? OR cd.week_no = ?)))"
                + " ORDER BY n.published_at DESC, n.notice_id DESC";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            statement.setInt(2, academicYear);
            statement.setInt(3, semester);
            statement.setInt(4, week);
            statement.setInt(5, week);
            statement.setInt(6, week);
            try (ResultSet rows = statement.executeQuery()) {
                return mapNoticeRows(rows, CourseQueryDAO.term(academicYear, semester)
                        .getDisplayName());
            }
        }
    }

    /**
     * One row per published weekly meeting. A meeting whose occurrence carries an ACTIVE adjustment
     * becomes a pair: the original is display-only and no longer occupies its slot, while the target
     * states where the lesson actually happens. Either half carries both texts, so a detail view can
     * render from whichever block the student picked.
     *
     * <p>The student week assembly keeps only the original half of this pair and rebuilds the target
     * from the adjustment instant ({@link #adjustedTargets}), because the published week can never
     * describe a position outside it; the teacher timetable reads the original half directly.
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

    /** Shared by the student and teacher timetable queries; the wording is the rendered contract. */
    static String scheduleText(int dayOfWeek, int startPeriod, int endPeriod, String location) {
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

    private static long parseOfferingId(String offeringId) {
        try {
            return Long.parseLong(offeringId);
        } catch (NumberFormatException notAnId) {
            return Long.MAX_VALUE;
        }
    }

    /** NORMAL first, then the display-only original, then the effective target. */
    private static int displayKindRank(ScheduleDisplayKindDTO kind) {
        return switch (kind) {
            case NORMAL -> 0;
            case ADJUSTED_ORIGINAL -> 1;
            case ADJUSTED_TARGET -> 2;
        };
    }

    /** The DATETIME columns store a UTC wall clock, so the instant is re-anchored at UTC. */
    private static Timestamp utcTimestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private record Window(Instant start, Instant end) {
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

    // ------------------------------------------------------------------ 日期与节次

    private static long calendarId(Connection connection, long planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT calendar_id FROM schedule_plan WHERE id=?")) {
            statement.setLong(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Schedule plan is unavailable");
                return rows.getLong(1);
            }
        }
    }

    private static List<CourseCalendarDateDTO> dates(Connection connection, long calendarId, int week)
            throws SQLException {
        String sql = "SELECT local_date, week_no, teaching_weekday, is_teaching_day"
                + " FROM calendar_date WHERE calendar_id = ? AND week_no = ?"
                + " ORDER BY teaching_weekday";
        List<CourseCalendarDateDTO> dates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    dates.add(new CourseCalendarDateDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("week_no"), rows.getInt("teaching_weekday"),
                            rows.getBoolean("is_teaching_day")));
                }
            }
        }
        return dates;
    }

    /**
     * Periods are per date, because two dates of the same week may use different day templates.
     * {@code start_time}/{@code end_time} are local wall clocks and must never be read as UTC.
     */
    private static List<CoursePeriodDTO> periods(Connection connection, long calendarId, int week)
            throws SQLException {
        String sql = "SELECT cd.local_date, pd.period_no, pd.start_time, pd.end_time"
                + " FROM calendar_date cd"
                + " JOIN period_definition pd ON pd.day_template_id = cd.day_template_id"
                + " WHERE cd.calendar_id = ? AND cd.week_no = ?"
                + " ORDER BY cd.teaching_weekday, pd.period_no";
        List<CoursePeriodDTO> periods = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    periods.add(new CoursePeriodDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("period_no"),
                            rows.getTime("start_time").toLocalTime().format(CLOCK),
                            rows.getTime("end_time").toLocalTime().format(CLOCK)));
                }
            }
        }
        return periods;
    }
}
