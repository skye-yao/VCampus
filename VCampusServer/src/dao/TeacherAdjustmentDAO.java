package dao;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherCalendarDateDTO;
import dto.course.teacher.TeacherPeriodDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教师调课申请的读与写。
 *
 * <p>读：选项域（教学日历的教学日、节次与教室资源）、原课次快照（含生效调课后的有效教师）、
 * 本人 PENDING 申请已占用的目标。写：申请头、不可变目标快照、撤销的条件更新。所有写方法都在
 * 调用方事务内执行，{@link #insertTarget} 是本测试用于注入中途失败的可覆写接缝。
 */
public class TeacherAdjustmentDAO {
    private static final String CLASSROOM = "classroom";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AdminScheduleConflictDAO conflictDAO = new AdminScheduleConflictDAO();

    /** 教学班所属学期当前正式方案及其教学日历。 */
    public record OfferingCalendar(long offeringId, long planId, long calendarId, String timezone) {
    }

    /** 教学日历里的一行日期；{@code is_teaching_day} 是目标日期的硬条件。 */
    public record CalendarDateRow(long calendarDateId, long calendarId, LocalDate localDate,
                                  int weekNo, int teachingWeekday, boolean teachingDay,
                                  long templateId) {
    }

    /**
     * 原课次在有效课表里的完整状态：所属教学班、时间、原快照资源，以及生效调课（如果有）。
     * {@code effectiveTeacherUid} 就是“当前实际讲授该课次的人”，权限与冲突检查都以它为准。
     */
    public record Occurrence(long occurrenceId, long ruleId, long planId, long offeringId,
                             long calendarId, int weekNo, int teachingWeekday, int startPeriod,
                             int endPeriod, Timestamp startAt, Timestamp endAt, String teacherUid,
                             String assistantUid, Long classroomId, String arrangementStatus,
                             String ruleStatus, String planStatus, Long currentPlanId,
                             Long adjustmentId, String adjustedTeacherUid,
                             String adjustedAssistantUid, Long adjustedClassroomId) {

        public boolean adjusted() {
            return adjustmentId != null;
        }

        /** 仍属于当前正式方案里启用中的安排，否则原快照已经过期。 */
        public boolean inEffectiveSchedule() {
            return "PUBLISHED".equals(planStatus) && "ACTIVE".equals(ruleStatus)
                    && "ACTIVE".equals(arrangementStatus) && currentPlanId != null
                    && currentPlanId == planId;
        }

        /** 有效课表的任课教师；快照过期且没有被调课时无人可代表该课次。 */
        public String effectiveTeacherUid() {
            if (adjusted()) return adjustedTeacherUid;
            return inEffectiveSchedule() ? teacherUid : null;
        }
    }

    // ------------------------------------------------------------- offering domain

    /**
     * 教学班 → 学期 → 当前 PUBLISHED 方案 → 教学日历。教学班不存在返回 null（调用方按无权限处理），
     * 方案不可用则抛 {@link IllegalArgumentException}（调用方状态错误）。
     */
    public OfferingCalendar offeringCalendar(Connection connection, long offeringId)
            throws SQLException {
        Integer academicYear;
        Integer semester;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT academic_year,semester FROM course_offering WHERE offering_id=?")) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                academicYear = rows.getInt("academic_year");
                semester = rows.getInt("semester");
            }
        }
        Long planId;
        try {
            planId = conflictDAO.publishedPlanId(connection, academicYear, semester);
        } catch (AdminScheduleConflictDAO.PublishedPlanUnavailableException unavailable) {
            throw new IllegalArgumentException("该学期暂无已发布的排课方案");
        }
        if (planId == null) throw new IllegalArgumentException("该学期暂无已发布的排课方案");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.calendar_id,cal.timezone FROM schedule_plan p"
                        + " JOIN teaching_calendar cal ON cal.id=p.calendar_id WHERE p.id=?")) {
            statement.setLong(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("排课方案不存在");
                return new OfferingCalendar(offeringId, planId, rows.getLong("calendar_id"),
                        rows.getString("timezone"));
            }
        }
    }

    public boolean isTeacher(Connection connection, String uid) throws SQLException {
        if (uid == null || uid.isBlank()) return false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM tbl_user WHERE UID=? AND role=1")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** 教学班成员表里的任课教师（role=0）；助教（role=1）不算。 */
    public boolean isOfferingTeacher(Connection connection, long offeringId, String uid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM course_offering_teacher WHERE offering_id=? AND uid=? AND role=0")) {
            statement.setLong(1, offeringId);
            statement.setString(2, uid);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    // ------------------------------------------------------------- calendar domain

    public CalendarDateRow calendarDate(Connection connection, long calendarId, LocalDate date)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,calendar_id,local_date,week_no,teaching_weekday,is_teaching_day,"
                        + "day_template_id FROM calendar_date WHERE calendar_id=? AND local_date=?")) {
            statement.setLong(1, calendarId);
            statement.setDate(2, java.sql.Date.valueOf(date));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? calendarDateRow(rows) : null;
            }
        }
    }

    /** Legacy derivation lookup for targets stored before V006 ({@code original_week_no + weekday}). */
    public CalendarDateRow calendarDate(Connection connection, long calendarId, int weekNo,
                                        int weekday) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,calendar_id,local_date,week_no,teaching_weekday,is_teaching_day,"
                        + "day_template_id FROM calendar_date WHERE calendar_id=? AND week_no=?"
                        + " AND teaching_weekday=?")) {
            statement.setLong(1, calendarId);
            statement.setInt(2, weekNo);
            statement.setInt(3, weekday);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? calendarDateRow(rows) : null;
            }
        }
    }

    /** 只返回真实教学日，顺序按教学周与其 teaching_weekday，与课表页一致。 */
    public List<TeacherCalendarDateDTO> teachingDates(Connection connection, long calendarId)
            throws SQLException {
        String sql = "SELECT local_date,week_no,teaching_weekday,is_teaching_day FROM calendar_date"
                + " WHERE calendar_id=? AND is_teaching_day=1 ORDER BY week_no,teaching_weekday";
        List<TeacherCalendarDateDTO> dates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    dates.add(new TeacherCalendarDateDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("week_no"), rows.getInt("teaching_weekday"),
                            rows.getBoolean("is_teaching_day")));
                }
            }
        }
        return List.copyOf(dates);
    }

    /** 每个教学日的节次定义；TIME 列是本地墙钟，直接按 HH:mm:ss 输出。 */
    public List<TeacherPeriodDTO> periods(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT cd.local_date,pd.period_no,pd.start_time,pd.end_time"
                + " FROM calendar_date cd"
                + " JOIN period_definition pd ON pd.day_template_id=cd.day_template_id"
                + " WHERE cd.calendar_id=? AND cd.is_teaching_day=1"
                + " ORDER BY cd.local_date,pd.period_no";
        List<TeacherPeriodDTO> periods = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    periods.add(new TeacherPeriodDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("period_no"),
                            rows.getTime("start_time").toLocalTime().format(CLOCK),
                            rows.getTime("end_time").toLocalTime().format(CLOCK)));
                }
            }
        }
        return List.copyOf(periods);
    }

    public List<ScheduleResourceDTO> classrooms(Connection connection) throws SQLException {
        List<ScheduleResourceDTO> classrooms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,name,capacity FROM classroom ORDER BY id");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String id = Long.toString(rows.getLong("id"));
                classrooms.add(new ScheduleResourceDTO(id, id, rows.getString("name"), CLASSROOM,
                        rows.getInt("capacity")));
            }
        }
        return List.copyOf(classrooms);
    }

    // ----------------------------------------------------------------- occurrences

    public Map<Long, Occurrence> findOccurrences(Connection connection, Collection<Long> ids)
            throws SQLException {
        Map<Long, Occurrence> occurrences = new LinkedHashMap<>();
        if (ids.isEmpty()) return occurrences;
        StringBuilder sql = new StringBuilder("SELECT o.id,o.rule_id,o.plan_id,o.week_no,"
                + "o.teaching_weekday,o.start_at,o.end_at,r.course_offering_id AS offering_id,"
                + "r.start_period,r.end_period,p.calendar_id,p.status AS plan_status,"
                + "cal.current_schedule_plan_id,a.teacher_uid,a.assistant_uid,a.classroom_id,"
                + "a.status AS arrangement_status,r.status AS rule_status,j.adjustment_id,"
                + "j.teacher_uid AS adjusted_teacher_uid,"
                + "j.assistant_uid AS adjusted_assistant_uid,"
                + "j.classroom_id AS adjusted_classroom_id"
                + " FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + " JOIN schedule_plan p ON p.id=o.plan_id"
                + " JOIN teaching_calendar cal ON cal.id=p.calendar_id"
                + " LEFT JOIN course_schedule_adjustment j ON j.original_occurrence_id=o.id"
                + "     AND j.status='ACTIVE' WHERE o.id IN (");
        for (int index = 0; index < ids.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Long id : ids) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Occurrence occurrence = occurrenceRow(rows);
                    occurrences.putIfAbsent(occurrence.occurrenceId(), occurrence);
                }
            }
        }
        return Map.copyOf(occurrences);
    }

    // -------------------------------------------------------------- pending targets

    /** 本人仍在 PENDING 的申请已经占用的原课次，用来拒绝重复提交。 */
    public List<Long> pendingTargets(Connection connection, String teacherUid,
                                     Collection<Long> occurrenceIds) throws SQLException {
        if (occurrenceIds.isEmpty()) return List.of();
        StringBuilder sql = new StringBuilder("SELECT t.original_occurrence_id"
                + " FROM course_schedule_adjustment_target t"
                + " JOIN course_schedule_adjustment_request r ON r.request_id=t.request_id"
                + " WHERE r.requested_by=? AND r.status='PENDING' AND t.original_occurrence_id IN (");
        for (int index = 0; index < occurrenceIds.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY t.original_occurrence_id");
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, teacherUid);
            int index = 2;
            for (Long id : occurrenceIds) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getLong(1));
            }
        }
        return List.copyOf(ids);
    }

    // ---------------------------------------------------------------------- writes

    public long insertRequest(Connection connection, long offeringId, String requestedBy,
                              String reason, int newWeekday, int startPeriod, int endPeriod,
                              Long classroomId, Instant submittedAt) throws SQLException {
        String sql = "INSERT INTO course_schedule_adjustment_request(offering_id,requested_by,reason,"
                + "version,status,new_weekday,new_start_period,new_end_period,new_teacher_uid,"
                + "new_assistant_uid,new_classroom_id,submitted_at)"
                + " VALUES(?,?,?,1,'PENDING',?,?,?,NULL,NULL,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, offeringId);
            statement.setString(2, requestedBy);
            statement.setString(3, reason);
            statement.setInt(4, newWeekday);
            statement.setInt(5, startPeriod);
            statement.setInt(6, endPeriod);
            if (classroomId == null) statement.setNull(7, Types.BIGINT);
            else statement.setLong(7, classroomId);
            statement.setTimestamp(8, timestamp(submittedAt));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Overridable seam: a failure here must roll back the request, its targets and the log. */
    public long insertTarget(Connection connection, long requestId, long calendarDateId,
                             long occurrenceId, int weekNo, Timestamp startAt, Timestamp endAt,
                             String teacherUid, String assistantUid, Long classroomId)
            throws SQLException {
        String sql = "INSERT INTO course_schedule_adjustment_target(request_id,"
                + "original_occurrence_id,original_week_no,original_start_at,original_end_at,"
                + "original_teacher_uid,original_assistant_uid,original_classroom_id,"
                + "target_calendar_date_id) VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, requestId);
            statement.setLong(2, occurrenceId);
            statement.setInt(3, weekNo);
            statement.setTimestamp(4, startAt);
            statement.setTimestamp(5, endAt);
            if (teacherUid == null) statement.setNull(6, Types.VARCHAR);
            else statement.setString(6, teacherUid);
            if (assistantUid == null) statement.setNull(7, Types.VARCHAR);
            else statement.setString(7, assistantUid);
            if (classroomId == null) statement.setNull(8, Types.BIGINT);
            else statement.setLong(8, classroomId);
            statement.setLong(9, calendarDateId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /**
     * 教师撤销本人的 PENDING 申请。0 行表示不可见、已是终态或版本已变化，由调用方分类为
     * NOT_FOUND 或 CONFLICT；撤销时间与版本在同一条件更新里写入，绝不伪装成管理员驳回。
     */
    public int withdraw(Connection connection, long requestId, String teacherUid, int expectedVersion,
                        Instant withdrawnAt) throws SQLException {
        String sql = "UPDATE course_schedule_adjustment_request"
                + " SET status='WITHDRAWN',withdrawn_at=?,version=version+1"
                + " WHERE request_id=? AND requested_by=? AND status='PENDING' AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, timestamp(withdrawnAt));
            statement.setLong(2, requestId);
            statement.setString(3, teacherUid);
            statement.setInt(4, expectedVersion);
            return statement.executeUpdate();
        }
    }

    // ---------------------------------------------------------------- my requests

    public List<AdjustmentRequestSummaryDTO> listMine(Connection connection, String teacherUid,
            AdjustmentRequestStatusDTO status, int offset, int limit) throws SQLException {
        String sql = "SELECT r.request_id,c.course_name,o.offering_code,r.requested_by,u.name,"
                + "(SELECT COUNT(*) FROM course_schedule_adjustment_target t"
                + " WHERE t.request_id=r.request_id) AS target_week_count,r.status,r.submitted_at"
                + " FROM course_schedule_adjustment_request r"
                + " JOIN course_offering o ON o.offering_id=r.offering_id"
                + " JOIN course c ON c.course_id=o.course_id"
                + " JOIN tbl_user u ON u.UID=r.requested_by"
                + " WHERE r.requested_by=? AND r.status=?"
                + " ORDER BY r.submitted_at DESC,r.request_id DESC LIMIT ? OFFSET ?";
        List<AdjustmentRequestSummaryDTO> summaries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setString(2, status.name());
            statement.setInt(3, limit);
            statement.setInt(4, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    summaries.add(new AdjustmentRequestSummaryDTO(
                            Long.toString(rows.getLong("request_id")), rows.getString("course_name"),
                            rows.getString("offering_code"), rows.getString("requested_by"),
                            rows.getString("name"), rows.getInt("target_week_count"),
                            AdjustmentRequestStatusDTO.valueOf(rows.getString("status")),
                            ScheduleAdjustmentDAO.instantText(rows.getTimestamp("submitted_at"))));
                }
            }
        }
        return List.copyOf(summaries);
    }

    public long countMine(Connection connection, String teacherUid, AdjustmentRequestStatusDTO status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM course_schedule_adjustment_request"
                        + " WHERE requested_by=? AND status=?")) {
            statement.setString(1, teacherUid);
            statement.setString(2, status.name());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    // --------------------------------------------------------------------- helpers

    private static CalendarDateRow calendarDateRow(ResultSet rows) throws SQLException {
        long templateId = rows.getLong("day_template_id");
        boolean templateMissing = rows.wasNull();
        return new CalendarDateRow(rows.getLong("id"), rows.getLong("calendar_id"),
                rows.getDate("local_date").toLocalDate(), rows.getInt("week_no"),
                rows.getInt("teaching_weekday"), rows.getBoolean("is_teaching_day"),
                templateMissing ? 0L : templateId);
    }

    private static Occurrence occurrenceRow(ResultSet rows) throws SQLException {
        Long classroom = nullableLong(rows, "classroom_id");
        Long currentPlan = nullableLong(rows, "current_schedule_plan_id");
        Long adjustment = nullableLong(rows, "adjustment_id");
        return new Occurrence(rows.getLong("id"), rows.getLong("rule_id"), rows.getLong("plan_id"),
                rows.getLong("offering_id"), rows.getLong("calendar_id"), rows.getInt("week_no"),
                rows.getInt("teaching_weekday"), rows.getInt("start_period"),
                rows.getInt("end_period"), rows.getTimestamp("start_at"), rows.getTimestamp("end_at"),
                rows.getString("teacher_uid"), rows.getString("assistant_uid"), classroom,
                rows.getString("arrangement_status"), rows.getString("rule_status"),
                rows.getString("plan_status"), currentPlan, adjustment,
                rows.getString("adjusted_teacher_uid"), rows.getString("adjusted_assistant_uid"),
                nullableLong(rows, "adjusted_classroom_id"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }
}
