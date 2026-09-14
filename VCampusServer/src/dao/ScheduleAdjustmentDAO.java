package dao;

import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads, locks and writes of the temporary-adjustment aggregate. Every mutation belongs to a
 * caller-owned transaction; the write methods are the seam a test overrides to prove that a
 * failure anywhere in the decision rolls the whole transaction back.
 *
 * <p>Temporary adjustments never rewrite the published base plan, so this DAO only touches
 * {@code course_schedule_adjustment_request}, {@code course_schedule_adjustment_target},
 * {@code course_schedule_adjustment} and the linked notice.
 */
public class ScheduleAdjustmentDAO {
    private static final String TEACHER = "teacher";
    private static final String CLASSROOM = "classroom";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String RESCHEDULED = "RESCHEDULED";

    private static final String REQUEST_COLUMNS = "request_id,offering_id,requested_by,reason,version,"
            + "status,new_weekday,new_start_period,new_end_period,new_teacher_uid,new_assistant_uid,"
            + "new_classroom_id,submitted_at,reviewed_by,reviewed_at,review_comment";

    private final AdminScheduleDAO scheduleDAO;

    public ScheduleAdjustmentDAO() {
        this(new AdminScheduleDAO());
    }

    public ScheduleAdjustmentDAO(AdminScheduleDAO scheduleDAO) {
        this.scheduleDAO = scheduleDAO;
    }

    // ------------------------------------------------------------------- reads

    public RequestRow findRequest(Connection connection, long requestId) throws SQLException {
        String sql = "SELECT " + REQUEST_COLUMNS + " FROM course_schedule_adjustment_request"
                + " WHERE request_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? requestRow(rows) : null;
            }
        }
    }

    public List<AdjustmentRequestSummaryDTO> listRequests(Connection connection,
                                                          AdjustmentRequestStatusDTO status, int offset,
                                                          int limit) throws SQLException {
        String sql = "SELECT r.request_id,c.course_name,o.offering_code,r.requested_by,u.name,"
                + "(SELECT COUNT(*) FROM course_schedule_adjustment_target t"
                + " WHERE t.request_id=r.request_id) AS target_week_count,r.status,r.submitted_at"
                + " FROM course_schedule_adjustment_request r"
                + " JOIN course_offering o ON o.offering_id=r.offering_id"
                + " JOIN course c ON c.course_id=o.course_id"
                + " JOIN tbl_user u ON u.UID=r.requested_by"
                + " WHERE r.status=?"
                + " ORDER BY r.submitted_at DESC,r.request_id DESC LIMIT ? OFFSET ?";
        List<AdjustmentRequestSummaryDTO> summaries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    summaries.add(new AdjustmentRequestSummaryDTO(
                            Long.toString(rows.getLong("request_id")), rows.getString("course_name"),
                            rows.getString("offering_code"), rows.getString("requested_by"),
                            rows.getString("name"), rows.getInt("target_week_count"),
                            AdjustmentRequestStatusDTO.valueOf(rows.getString("status")),
                            instantText(rows.getTimestamp("submitted_at"))));
                }
            }
        }
        return List.copyOf(summaries);
    }

    public long countRequests(Connection connection, AdjustmentRequestStatusDTO status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM course_schedule_adjustment_request WHERE status=?")) {
            statement.setString(1, status.name());
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    public List<TargetRow> listTargets(Connection connection, long requestId) throws SQLException {
        String sql = "SELECT target_id,original_occurrence_id,original_week_no,original_start_at,"
                + "original_end_at,original_teacher_uid,original_assistant_uid,original_classroom_id"
                + " FROM course_schedule_adjustment_target WHERE request_id=?"
                + " ORDER BY original_week_no,original_occurrence_id";
        List<TargetRow> targets = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) targets.add(targetRow(rows));
            }
        }
        return List.copyOf(targets);
    }

    /** Effective occurrence rows joined with the arrangement and published-plan context. */
    public Map<Long, OccurrenceRow> findOccurrences(Connection connection, Collection<Long> ids)
            throws SQLException {
        Map<Long, OccurrenceRow> occurrences = new LinkedHashMap<>();
        if (ids.isEmpty()) return occurrences;
        StringBuilder sql = new StringBuilder("SELECT o.id,o.rule_id,o.plan_id,o.week_no,"
                + "o.start_at,o.end_at,a.arrangement_id,a.offering_id,a.teacher_uid,a.assistant_uid,"
                + "a.classroom_id,a.status AS arrangement_status,r.status AS rule_status,"
                + "p.status AS plan_status,p.calendar_id,cal.current_schedule_plan_id,"
                + "EXISTS (SELECT 1 FROM course_schedule_adjustment j"
                + " WHERE j.original_occurrence_id=o.id AND j.status='ACTIVE') AS adjusted"
                + " FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + " JOIN schedule_plan p ON p.id=o.plan_id"
                + " JOIN teaching_calendar cal ON cal.id=p.calendar_id WHERE o.id IN (");
        for (int index = 0; index < ids.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(')');
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Long id : ids) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    OccurrenceRow occurrence = occurrenceRow(rows);
                    occurrences.put(occurrence.occurrenceId(), occurrence);
                }
            }
        }
        return Map.copyOf(occurrences);
    }

    public ScheduleResourceDTO teacherResource(Connection connection, String uid) throws SQLException {
        String name = uid;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name FROM tbl_user WHERE UID=?")) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) name = rows.getString(1);
            }
        }
        return new ScheduleResourceDTO(uid, uid, name, TEACHER, 0);
    }

    public ScheduleResourceDTO classroomResource(Connection connection, long classroomId)
            throws SQLException {
        String id = Long.toString(classroomId);
        String name = id;
        int capacity = 0;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,capacity FROM classroom WHERE id=?")) {
            statement.setLong(1, classroomId);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    name = rows.getString(1);
                    capacity = rows.getInt(2);
                }
            }
        }
        return new ScheduleResourceDTO(id, id, name, CLASSROOM, capacity);
    }

    /** Calendar context reuse so the service never duplicates teaching-calendar SQL. */
    public AdminScheduleDAO.CalendarContext loadCalendar(Connection connection, long calendarId)
            throws SQLException {
        return scheduleDAO.loadCalendar(connection, calendarId);
    }

    // ------------------------------------------------------------------- locks

    public void lockRequest(Connection connection, long requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT request_id FROM course_schedule_adjustment_request WHERE request_id=?"
                        + " FOR UPDATE")) {
            statement.setLong(1, requestId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    /** Ascending primary-key order, so two concurrent decisions can never form a lock cycle. */
    public void lockOccurrencesAscending(Connection connection, Collection<Long> ids)
            throws SQLException {
        if (ids.isEmpty()) return;
        List<Long> sorted = new ArrayList<>(ids);
        sorted.sort(null);
        StringBuilder sql = new StringBuilder(
                "SELECT id FROM course_occurrence WHERE id IN (");
        for (int index = 0; index < sorted.size(); index++) {
            sql.append(index == 0 ? "?" : ",?");
        }
        sql.append(") ORDER BY id FOR UPDATE");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            for (Long id : sorted) statement.setLong(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    // Locking requires walking the result set.
                }
            }
        }
    }

    // ------------------------------------------------------------------ writes

    public long insertAdjustment(Connection connection, long requestId, long originalOccurrenceId,
                                 Timestamp startAt, Timestamp endAt, String teacherUid,
                                 String assistantUid, Long classroomId) throws SQLException {
        String sql = "INSERT INTO course_schedule_adjustment(request_id,original_occurrence_id,"
                + "start_at_utc,end_at_utc,teacher_uid,assistant_uid,classroom_id,status)"
                + " VALUES(?,?,?,?,?,?,?,'ACTIVE')";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, requestId);
            statement.setLong(2, originalOccurrenceId);
            statement.setTimestamp(3, startAt);
            statement.setTimestamp(4, endAt);
            statement.setString(5, teacherUid);
            if (assistantUid == null) statement.setNull(6, Types.VARCHAR);
            else statement.setString(6, assistantUid);
            if (classroomId == null) statement.setNull(7, Types.BIGINT);
            else statement.setLong(7, classroomId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public int updateDecision(Connection connection, long requestId, int expectedVersion,
                              AdjustmentRequestStatusDTO status, String reviewerUid, Instant reviewedAt,
                              String reviewComment) throws SQLException {
        String sql = "UPDATE course_schedule_adjustment_request SET status=?,reviewed_by=?,"
                + "reviewed_at=?,review_comment=?,version=version+1"
                + " WHERE request_id=? AND version=? AND status='PENDING'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, reviewerUid);
            statement.setTimestamp(3, timestamp(reviewedAt));
            if (reviewComment == null) statement.setNull(4, Types.VARCHAR);
            else statement.setString(4, reviewComment);
            statement.setLong(5, requestId);
            statement.setInt(6, expectedVersion);
            return statement.executeUpdate();
        }
    }

    /** Overridable seam: a failure here must roll back the adjustments and the request update. */
    public long insertNotice(Connection connection, long requestId, long offeringId, String adminUid,
                             String title, String content, Instant publishedAt) throws SQLException {
        String sql = "INSERT INTO course_notice(offering_id,title,content,notice_type,week_no,status,"
                + "created_by,published_at,adjustment_request_id)"
                + " VALUES(?,?,?,?,NULL,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, offeringId);
            statement.setString(2, title);
            statement.setString(3, content);
            statement.setString(4, RESCHEDULED);
            statement.setString(5, PUBLISHED);
            statement.setString(6, adminUid);
            statement.setTimestamp(7, timestamp(publishedAt));
            statement.setLong(8, requestId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ----------------------------------------------------------------- helpers

    private static RequestRow requestRow(ResultSet rows) throws SQLException {
        long classroomId = rows.getLong("new_classroom_id");
        Long classroom = rows.wasNull() ? null : classroomId;
        return new RequestRow(rows.getLong("request_id"), rows.getLong("offering_id"),
                rows.getString("requested_by"), rows.getString("reason"), rows.getInt("version"),
                AdjustmentRequestStatusDTO.valueOf(rows.getString("status")), rows.getInt("new_weekday"),
                rows.getInt("new_start_period"), rows.getInt("new_end_period"),
                rows.getString("new_teacher_uid"), rows.getString("new_assistant_uid"), classroom,
                rows.getTimestamp("submitted_at"), rows.getString("reviewed_by"),
                rows.getTimestamp("reviewed_at"), rows.getString("review_comment"));
    }

    private static TargetRow targetRow(ResultSet rows) throws SQLException {
        long classroomId = rows.getLong("original_classroom_id");
        Long classroom = rows.wasNull() ? null : classroomId;
        return new TargetRow(rows.getLong("target_id"), rows.getLong("original_occurrence_id"),
                rows.getInt("original_week_no"), rows.getTimestamp("original_start_at"),
                rows.getTimestamp("original_end_at"), rows.getString("original_teacher_uid"),
                rows.getString("original_assistant_uid"), classroom);
    }

    private static OccurrenceRow occurrenceRow(ResultSet rows) throws SQLException {
        long classroomId = rows.getLong("classroom_id");
        Long classroom = rows.wasNull() ? null : classroomId;
        long currentPlanId = rows.getLong("current_schedule_plan_id");
        Long current = rows.wasNull() ? null : currentPlanId;
        return new OccurrenceRow(rows.getLong("id"), rows.getLong("rule_id"),
                rows.getLong("plan_id"), rows.getInt("week_no"), rows.getTimestamp("start_at"),
                rows.getTimestamp("end_at"), rows.getLong("arrangement_id"),
                rows.getLong("offering_id"), rows.getString("teacher_uid"),
                rows.getString("assistant_uid"), classroom, rows.getString("arrangement_status"),
                rows.getString("rule_status"), rows.getString("plan_status"),
                rows.getLong("calendar_id"), current, rows.getBoolean("adjusted"));
    }

    /** The column stores a UTC wall clock, so the instant must be re-anchored at UTC. */
    public static Instant instant(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toInstant(ZoneOffset.UTC);
    }

    public static String instantText(Timestamp value) {
        return value == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant(value));
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    public record RequestRow(long requestId, long offeringId, String applicantUid, String reason,
                             int version, AdjustmentRequestStatusDTO status, int newDayOfWeek,
                             int newStartPeriod, int newEndPeriod, String newTeacherUid,
                             String newAssistantUid, Long newClassroomId, Timestamp submittedAt,
                             String reviewedBy, Timestamp reviewedAt, String reviewComment) {
    }

    public record TargetRow(long targetId, long originalOccurrenceId, int week, Timestamp startAt,
                            Timestamp endAt, String teacherUid, String assistantUid,
                            Long classroomId) {
    }

    public record OccurrenceRow(long occurrenceId, long ruleId, long planId, int weekNo,
                                Timestamp startAt, Timestamp endAt, long arrangementId,
                                long offeringId, String teacherUid, String assistantUid,
                                Long classroomId, String arrangementStatus, String ruleStatus,
                                String planStatus, long calendarId, Long currentPlanId,
                                boolean adjusted) {
    }
}
