package dao;

import dto.course.ScheduleDisplayKindDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;
import dto.course.teacher.TeacherScheduleEntryDTO;
import dto.course.teacher.TeacherScheduleWeekDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师某一教学周的课表读取：日历周、当周日期与节次，以及该教师在该周实际生效的课次。
 *
 * <p>课次归属来自当前正式排课方案里的教学安排（{@code course_schedule_arrangement}）：任课
 * 教师或助教是本人即算本人授课，不要求选课关系，也不要求存在选课窗口。生效调课走两条独立查询：
 * 原 occurrence 所在周返回 {@link ScheduleDisplayKindDTO#ADJUSTED_ORIGINAL}，调课目标时刻落在
 * 哪一周就在那一周返回 {@link ScheduleDisplayKindDTO#ADJUSTED_TARGET}；后者必须由
 * {@code start_at_utc} 经教学日历时区反查日期，绝不能用原 occurrence 的周次代替。
 *
 * <p>日期与节次只来自 {@code calendar_date} 与 {@code period_definition}，因此周数、每周天数与
 * 每日节数都不硬编码。DATETIME 列保存 UTC 墙钟，读取用
 * {@code toLocalDateTime().toInstant(ZoneOffset.UTC)}；{@code TIME} 列是本地墙钟，直接
 * {@code toLocalTime()}。
 */
public class TeacherScheduleDAO {

    private static final Comparator<TeacherScheduleEntryDTO> ENTRY_ORDER =
            Comparator.comparingInt(TeacherScheduleEntryDTO::getDayOfWeek)
                    .thenComparingInt(TeacherScheduleEntryDTO::getStartPeriod)
                    .thenComparingInt(TeacherScheduleEntryDTO::getEndPeriod)
                    .thenComparing(TeacherScheduleEntryDTO::getOfferingId,
                            TeacherScheduleDAO::compareIds)
                    .thenComparing(TeacherScheduleEntryDTO::getOccurrenceId,
                            TeacherScheduleDAO::compareIds);

    /**
     * 该学期当前正式方案中、属于 {@code uid} 的第 {@code week} 教学周。
     *
     * @param week 可为 null：取 {@code clock} 所在教学周，今天不在学期内时取最小教学周
     * @throws IllegalArgumentException 该学期没有已发布的教学日历/方案，或 week 越界
     */
    public TeacherScheduleWeekDTO loadTeachingSchedule(Connection connection, String uid,
            int academicYear, int semester, Integer week, Clock clock) throws SQLException {
        TeachingCalendar calendar = currentCalendar(connection, academicYear, semester);
        int[] bounds = weekBounds(connection, calendar.id());
        int minWeek = bounds[0];
        int maxWeek = bounds[1];
        Integer currentWeek = currentWeek(connection, calendar, clock);
        int effectiveWeek = effectiveWeek(week, minWeek, maxWeek, currentWeek);

        List<TeacherCalendarDateDTO> dates = dates(connection, calendar.id(), effectiveWeek);
        List<TeacherPeriodDTO> periods = periods(connection, calendar.id(), effectiveWeek);
        List<TeacherScheduleEntryDTO> entries = new ArrayList<>(
                publishedEntries(connection, uid, calendar, effectiveWeek));
        entries.addAll(adjustedTargets(connection, uid, calendar,
                window(periods, calendar.zone())));
        entries.sort(ENTRY_ORDER);

        return new TeacherScheduleWeekDTO(String.valueOf(calendar.id()), calendar.timezone(),
                effectiveWeek, minWeek, maxWeek, currentWeek, dates, periods, entries);
    }

    // ------------------------------------------------------------ calendar context

    private static TeachingCalendar currentCalendar(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT cal.id, cal.timezone, cal.current_schedule_plan_id"
                + " FROM teaching_calendar cal"
                + " JOIN schedule_plan p ON p.id = cal.current_schedule_plan_id"
                + "     AND p.calendar_id = cal.id AND p.status = 'PUBLISHED'"
                + " WHERE cal.academic_year = ? AND cal.semester = ?"
                + " AND cal.current_schedule_plan_id IS NOT NULL"
                + " ORDER BY cal.version DESC, cal.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new IllegalArgumentException("该学期暂无已发布的教学日历");
                }
                String timezone = rows.getString("timezone");
                return new TeachingCalendar(rows.getLong("id"), timezone, ZoneId.of(timezone),
                        rows.getLong("current_schedule_plan_id"));
            }
        }
    }

    /** min/max teaching week over the whole calendar; non-teaching weeks stay navigable. */
    private static int[] weekBounds(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT MIN(week_no) AS min_week, MAX(week_no) AS max_week"
                + " FROM calendar_date WHERE calendar_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getObject("min_week") == null
                        || rows.getObject("max_week") == null) {
                    throw new IllegalArgumentException("该学期暂无已发布的教学日历");
                }
                return new int[] {rows.getInt("min_week"), rows.getInt("max_week")};
            }
        }
    }

    private static Integer currentWeek(Connection connection, TeachingCalendar calendar, Clock clock)
            throws SQLException {
        LocalDate today = LocalDate.now(clock.withZone(calendar.zone()));
        String sql = "SELECT week_no FROM calendar_date WHERE calendar_id = ? AND local_date = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendar.id());
            statement.setDate(2, java.sql.Date.valueOf(today));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt("week_no") : null;
            }
        }
    }

    private static int effectiveWeek(Integer week, int minWeek, int maxWeek, Integer currentWeek) {
        if (week == null) {
            return currentWeek != null ? currentWeek : minWeek;
        }
        if (week < 1 || week < minWeek || week > maxWeek) {
            throw new IllegalArgumentException(
                    "week 必须在 " + minWeek + ".." + maxWeek + " 之间");
        }
        return week;
    }

    // ------------------------------------------------------------------ 日期与节次

    private static List<TeacherCalendarDateDTO> dates(Connection connection, long calendarId,
            int week) throws SQLException {
        String sql = "SELECT local_date, week_no, teaching_weekday, is_teaching_day"
                + " FROM calendar_date WHERE calendar_id = ? AND week_no = ?"
                + " ORDER BY teaching_weekday";
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    dates.add(new TeacherCalendarDateDTO(
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
    private static List<TeacherPeriodDTO> periods(Connection connection, long calendarId, int week)
            throws SQLException {
        String sql = "SELECT cd.local_date, pd.period_no, pd.start_time, pd.end_time"
                + " FROM calendar_date cd"
                + " JOIN period_definition pd ON pd.day_template_id = cd.day_template_id"
                + " WHERE cd.calendar_id = ? AND cd.week_no = ?"
                + " ORDER BY cd.teaching_weekday, pd.period_no";
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    periods.add(new TeacherPeriodDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("period_no"),
                            rows.getTime("start_time").toLocalTime().toString(),
                            rows.getTime("end_time").toLocalTime().toString()));
                }
            }
        }
        return periods;
    }

    /** The UTC window [start, end) covered by this week's periods, or null when it has none. */
    private static Window window(List<TeacherPeriodDTO> periods, ZoneId zone) {
        ZonedDateTime start = null;
        ZonedDateTime end = null;
        for (TeacherPeriodDTO period : periods) {
            LocalDate date = LocalDate.parse(period.getDate());
            ZonedDateTime periodStart = ZonedDateTime.of(date,
                    LocalTime.parse(period.getStartTime()), zone);
            ZonedDateTime periodEnd = ZonedDateTime.of(date,
                    LocalTime.parse(period.getEndTime()), zone);
            if (start == null || periodStart.isBefore(start)) start = periodStart;
            if (end == null || periodEnd.isAfter(end)) end = periodEnd;
        }
        if (start == null || !start.isBefore(end)) return null;
        return new Window(start.toInstant(), end.toInstant());
    }

    // -------------------------------------------------------------------- 课次

    /**
     * Published occurrences of the week that belong to {@code uid} through the current plan's
     * arrangement, with the ACTIVE adjustment (if any) riding along. An occurrence without one is
     * NORMAL; with one it is ADJUSTED_ORIGINAL and keeps its original position, teacher and room.
     */
    private static List<TeacherScheduleEntryDTO> publishedEntries(Connection connection, String uid,
            TeachingCalendar calendar, int effectiveWeek) throws SQLException {
        String sql = "SELECT o.id AS occurrence_id, o.teaching_weekday,"
                + " r.course_offering_id AS offering_id, r.start_period, r.end_period,"
                + " c.course_code, c.course_name,"
                + " teacher.name AS teacher_name, assistant.name AS assistant_name,"
                + " room.name AS room_name, cd.local_date,"
                + " j.adjustment_id,"
                + " q.new_weekday, q.new_start_period, q.new_end_period, q.reason,"
                + " adj_room.name AS adjusted_room_name"
                + " FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id = o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id = r.arrangement_id"
                + " JOIN course_offering off ON off.offering_id = r.course_offering_id"
                + " JOIN course c ON c.course_id = off.course_id"
                + " JOIN calendar_date cd ON cd.calendar_id = ?"
                + "     AND cd.week_no = o.week_no AND cd.teaching_weekday = o.teaching_weekday"
                + " LEFT JOIN course_schedule_adjustment j ON j.original_occurrence_id = o.id"
                + "     AND j.status = 'ACTIVE'"
                + " LEFT JOIN course_schedule_adjustment_request q ON q.request_id = j.request_id"
                + " LEFT JOIN tbl_user teacher ON teacher.UID = a.teacher_uid"
                + " LEFT JOIN tbl_user assistant ON assistant.UID = a.assistant_uid"
                + " LEFT JOIN classroom room ON room.id = a.classroom_id"
                + " LEFT JOIN classroom adj_room ON adj_room.id = j.classroom_id"
                + " WHERE o.plan_id = ? AND o.week_no = ?"
                + " AND r.status = 'ACTIVE' AND a.status = 'ACTIVE'"
                + " AND (a.teacher_uid = ? OR a.assistant_uid = ?)"
                + " ORDER BY o.teaching_weekday, r.start_period, r.end_period,"
                + " r.course_offering_id, o.id";
        List<TeacherScheduleEntryDTO> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendar.id());
            statement.setLong(2, calendar.planId());
            statement.setInt(3, effectiveWeek);
            statement.setString(4, uid);
            statement.setString(5, uid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    entries.add(publishedEntry(rows, effectiveWeek));
                }
            }
        }
        return entries;
    }

    private static TeacherScheduleEntryDTO publishedEntry(ResultSet rows, int effectiveWeek)
            throws SQLException {
        String occurrenceId = String.valueOf(rows.getLong("occurrence_id"));
        String offeringId = String.valueOf(rows.getLong("offering_id"));
        String courseCode = rows.getString("course_code");
        String courseName = rows.getString("course_name");
        String teacher = names(rows.getString("teacher_name"), rows.getString("assistant_name"));
        String location = rows.getString("room_name");
        String localDate = rows.getDate("local_date").toLocalDate().toString();
        int weekday = rows.getInt("teaching_weekday");
        int startPeriod = rows.getInt("start_period");
        int endPeriod = rows.getInt("end_period");
        String adjustmentId = rows.getString("adjustment_id");
        if (adjustmentId == null) {
            return new TeacherScheduleEntryDTO(occurrenceId, offeringId, courseCode, courseName,
                    teacher, location, localDate, effectiveWeek, weekday, startPeriod, endPeriod,
                    ScheduleDisplayKindDTO.NORMAL, null, null, null, null, true);
        }
        String originalText = scheduleText(weekday, startPeriod, endPeriod, location);
        String adjustedText = scheduleText(rows.getInt("new_weekday"),
                rows.getInt("new_start_period"), rows.getInt("new_end_period"),
                rows.getString("adjusted_room_name"));
        return new TeacherScheduleEntryDTO(occurrenceId, offeringId, courseCode, courseName,
                teacher, location, localDate, effectiveWeek, weekday, startPeriod, endPeriod,
                ScheduleDisplayKindDTO.ADJUSTED_ORIGINAL, adjustmentId, originalText, adjustedText,
                rows.getString("reason"), false);
    }

    /**
     * ACTIVE adjustments whose target instant falls inside this week's UTC window and whose
     * effective teacher or assistant is {@code uid}. The requesting teacher is irrelevant here —
     * that is what makes a substitute see a lesson they do not own. The week and weekday of the
     * target come from the target instant through the calendar, never from the original
     * occurrence, so a cross-week move lands in the right week.
     */
    private static List<TeacherScheduleEntryDTO> adjustedTargets(Connection connection, String uid,
            TeachingCalendar calendar, Window window) throws SQLException {
        if (window == null) return List.of();
        Map<LocalDate, Spot> spots = calendarSpots(connection, calendar.id());
        String sql = "SELECT j.adjustment_id, j.start_at_utc,"
                + " o.id AS occurrence_id, o.teaching_weekday AS original_weekday,"
                + " r.course_offering_id AS offering_id, r.start_period AS original_start_period,"
                + " r.end_period AS original_end_period,"
                + " c.course_code, c.course_name,"
                + " adj_teacher.name AS teacher_name, adj_assistant.name AS assistant_name,"
                + " adj_room.name AS adjusted_room_name, oroom.name AS original_room_name,"
                + " q.new_weekday, q.new_start_period, q.new_end_period, q.reason"
                + " FROM course_schedule_adjustment j"
                + " JOIN course_occurrence o ON o.id = j.original_occurrence_id"
                + " JOIN course_schedule_rule r ON r.id = o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id = r.arrangement_id"
                + " JOIN course_offering off ON off.offering_id = r.course_offering_id"
                + " JOIN course c ON c.course_id = off.course_id"
                + " JOIN course_schedule_adjustment_request q ON q.request_id = j.request_id"
                + " LEFT JOIN tbl_user adj_teacher ON adj_teacher.UID = j.teacher_uid"
                + " LEFT JOIN tbl_user adj_assistant ON adj_assistant.UID = j.assistant_uid"
                + " LEFT JOIN classroom adj_room ON adj_room.id = j.classroom_id"
                + " LEFT JOIN classroom oroom ON oroom.id = a.classroom_id"
                + " WHERE j.status = 'ACTIVE' AND o.plan_id = ?"
                + " AND j.start_at_utc >= ? AND j.start_at_utc < ?"
                + " AND (j.teacher_uid = ? OR j.assistant_uid = ?)"
                + " ORDER BY j.start_at_utc, j.adjustment_id";
        List<TeacherScheduleEntryDTO> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendar.planId());
            statement.setTimestamp(2, timestamp(window.start()));
            statement.setTimestamp(3, timestamp(window.end()));
            statement.setString(4, uid);
            statement.setString(5, uid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    TeacherScheduleEntryDTO entry = adjustedTarget(rows, calendar, spots);
                    if (entry != null) entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static TeacherScheduleEntryDTO adjustedTarget(ResultSet rows, TeachingCalendar calendar,
            Map<LocalDate, Spot> spots) throws SQLException {
        // DATETIME holds a UTC wall clock; the driver does not convert, so read the wall clock and
        // reinterpret it as UTC explicitly. Timestamp.toInstant() would use the JVM zone and shift.
        Instant instant = rows.getTimestamp("start_at_utc").toLocalDateTime()
                .toInstant(ZoneOffset.UTC);
        Spot spot = spots.get(instant.atZone(calendar.zone()).toLocalDate());
        if (spot == null) return null;
        int startPeriod = rows.getInt("new_start_period");
        int endPeriod = rows.getInt("new_end_period");
        String adjustedText = scheduleText(rows.getInt("new_weekday"), startPeriod, endPeriod,
                rows.getString("adjusted_room_name"));
        String originalText = scheduleText(rows.getInt("original_weekday"),
                rows.getInt("original_start_period"), rows.getInt("original_end_period"),
                rows.getString("original_room_name"));
        return new TeacherScheduleEntryDTO(String.valueOf(rows.getLong("occurrence_id")),
                String.valueOf(rows.getLong("offering_id")), rows.getString("course_code"),
                rows.getString("course_name"),
                names(rows.getString("teacher_name"), rows.getString("assistant_name")),
                rows.getString("adjusted_room_name"), spot.date().toString(), spot.weekNo(),
                spot.weekday(), startPeriod, endPeriod, ScheduleDisplayKindDTO.ADJUSTED_TARGET,
                rows.getString("adjustment_id"), originalText, adjustedText,
                rows.getString("reason"), false);
    }

    /** local_date -> (week_no, teaching_weekday) for the whole calendar, used to place targets. */
    private static Map<LocalDate, Spot> calendarSpots(Connection connection, long calendarId)
            throws SQLException {
        String sql = "SELECT local_date, week_no, teaching_weekday FROM calendar_date"
                + " WHERE calendar_id = ?";
        Map<LocalDate, Spot> spots = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    LocalDate date = rows.getDate("local_date").toLocalDate();
                    spots.put(date, new Spot(date, rows.getInt("week_no"),
                            rows.getInt("teaching_weekday")));
                }
            }
        }
        return spots;
    }

    // ------------------------------------------------------------------ helpers

    private static String names(String teacher, String assistant) {
        List<String> parts = new ArrayList<>();
        if (teacher != null && !teacher.isBlank()) parts.add(teacher);
        if (assistant != null && !assistant.isBlank()) parts.add(assistant);
        return parts.isEmpty() ? null : String.join(", ", parts);
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

    private static int compareIds(String left, String right) {
        long leftId;
        long rightId;
        try {
            leftId = Long.parseLong(left);
            rightId = Long.parseLong(right);
        } catch (NumberFormatException notAnId) {
            return left.compareTo(right);
        }
        int compared = Long.compare(leftId, rightId);
        return compared != 0 ? compared : left.compareTo(right);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private record TeachingCalendar(long id, String timezone, ZoneId zone, long planId) {
    }

    private record Spot(LocalDate date, int weekNo, int weekday) {
    }

    private record Window(Instant start, Instant end) {
    }
}
