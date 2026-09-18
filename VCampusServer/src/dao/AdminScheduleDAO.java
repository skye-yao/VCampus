package dao;

import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;

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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rows of the scheduling aggregate: plans, resources, arrangements, rules, shared weeks,
 * generated occurrences, resource bookings, publication pointers, and the scheduling-specific
 * administrator operation log. Every write belongs to a caller-owned transaction.
 */
public class AdminScheduleDAO {
    private static final String TEACHER = "teacher";
    private static final String CLASSROOM = "classroom";
    private static final String DUPLICATE_KEY_STATE = "23000";
    private static final String EXCLUSIVE = "EXCLUSIVE";

    public List<ScheduleResourceDTO> listResources(Connection connection, String type, String query)
            throws SQLException {
        String pattern = like(query);
        List<ScheduleResourceDTO> resources = new ArrayList<>();
        if (type == null || TEACHER.equals(type)) {
            String sql = "SELECT UID,name FROM tbl_user WHERE role=1"
                    + (pattern == null ? "" : " AND (UID LIKE ? ESCAPE '!' OR name LIKE ? ESCAPE '!')")
                    + " ORDER BY UID";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (pattern != null) {
                    statement.setString(1, pattern);
                    statement.setString(2, pattern);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String uid = rows.getString("UID");
                        resources.add(new ScheduleResourceDTO(uid, uid, rows.getString("name"),
                                TEACHER, 0));
                    }
                }
            }
        }
        if (type == null || CLASSROOM.equals(type)) {
            String sql = "SELECT id,name,capacity FROM classroom"
                    + (pattern == null ? "" : " WHERE (CAST(id AS CHAR) LIKE ? ESCAPE '!'"
                            + " OR name LIKE ? ESCAPE '!')")
                    + " ORDER BY id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                if (pattern != null) {
                    statement.setString(1, pattern);
                    statement.setString(2, pattern);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String id = rows.getString("id");
                        resources.add(new ScheduleResourceDTO(id, id, rows.getString("name"),
                                CLASSROOM, rows.getInt("capacity")));
                    }
                }
            }
        }
        return List.copyOf(resources);
    }

    // ---------------------------------------------------------------- calendar

    public CalendarContext loadCalendar(Connection connection, long calendarId) throws SQLException {
        String timezone;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT timezone FROM teaching_calendar WHERE id=?")) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                timezone = rows.getString("timezone");
            }
        }
        Map<Integer, CalendarDay> byWeekDay = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT local_date,week_no,teaching_weekday,day_template_id FROM calendar_date"
                        + " WHERE calendar_id=?")) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    CalendarDay day = new CalendarDay(rows.getDate("local_date").toLocalDate(),
                            rows.getInt("week_no"), rows.getInt("teaching_weekday"),
                            rows.getLong("day_template_id"));
                    byWeekDay.put(key(day.weekNo(), day.weekday()), day);
                }
            }
        }
        Map<Long, Map<Integer, Period>> templates = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.day_template_id,p.period_no,p.start_time,p.end_time FROM period_definition p"
                        + " WHERE p.day_template_id IN (SELECT DISTINCT day_template_id"
                        + " FROM calendar_date WHERE calendar_id=?)")) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    templates.computeIfAbsent(rows.getLong("day_template_id"),
                                    ignored -> new LinkedHashMap<>())
                            .put(rows.getInt("period_no"), new Period(
                                    rows.getTime("start_time").toLocalTime(),
                                    rows.getTime("end_time").toLocalTime()));
                }
            }
        }
        return new CalendarContext(calendarId, ZoneId.of(timezone), byWeekDay, templates);
    }

    public boolean hasOpenSelectionWindowForOtherPlan(Connection connection, long calendarId,
                                                      long planId) throws SQLException {
        String sql = "SELECT 1 FROM course_selection_window w"
                + " JOIN teaching_calendar c ON c.id=?"
                + " WHERE w.academic_year=c.academic_year AND w.semester=c.semester"
                + " AND w.schedule_plan_id<>? AND UTC_TIMESTAMP(6)>=w.plan_open_at"
                + " AND UTC_TIMESTAMP(6)<=w.drop_deadline";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setLong(2, planId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * True when an occurrence of this arrangement is referenced by a temporary adjustment. Both
     * {@code course_schedule_adjustment.original_occurrence_id} and
     * {@code course_schedule_adjustment_target.original_occurrence_id} are RESTRICT, so rewriting or
     * deleting the occurrence rows would surface as a raw driver error rather than a typed refusal.
     */
    public boolean hasAdjustmentHistory(Connection connection, long arrangementId)
            throws SQLException {
        String sql = "SELECT 1 FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " WHERE r.arrangement_id=?"
                + " AND (EXISTS (SELECT 1 FROM course_schedule_adjustment j"
                + " WHERE j.original_occurrence_id=o.id)"
                + " OR EXISTS (SELECT 1 FROM course_schedule_adjustment_target t"
                + " WHERE t.original_occurrence_id=o.id)) LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, arrangementId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Recomputes the student-facing precomputed conflict table for one plan, replacing whatever it
     * held. Pairs are stored in normalized order, only positive counts are written, and the
     * occurrences compared are the <em>effective</em> ones: originals replaced by an ACTIVE
     * adjustment are dropped and the adjustment itself counts at its replacement instant, matching
     * {@link AdminScheduleConflictDAO}.
     */
    public void rebuildOfferingConflicts(Connection connection, long planId) throws SQLException {
        String effective = "SELECT a.offering_id AS offering_id,o.start_at AS start_at,"
                + "o.end_at AS end_at FROM course_occurrence o"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + " WHERE o.plan_id=? AND r.status='ACTIVE' AND a.status='ACTIVE'"
                + " AND NOT EXISTS (SELECT 1 FROM course_schedule_adjustment j"
                + " WHERE j.original_occurrence_id=o.id AND j.status='ACTIVE')"
                + " UNION ALL"
                + " SELECT a.offering_id,j.start_at_utc,j.end_at_utc"
                + " FROM course_schedule_adjustment j"
                + " JOIN course_occurrence o ON o.id=j.original_occurrence_id"
                + " JOIN course_schedule_rule r ON r.id=o.rule_id"
                + " JOIN course_schedule_arrangement a ON a.arrangement_id=r.arrangement_id"
                + " WHERE j.status='ACTIVE' AND o.plan_id=?"
                + " AND r.status='ACTIVE' AND a.status='ACTIVE'";
        execute(connection, "DELETE FROM course_offering_conflict WHERE plan_id=?", planId);
        String sql = "INSERT INTO course_offering_conflict(plan_id,course_offering_a_id,"
                + "course_offering_b_id,conflict_count,first_conflict_at,last_conflict_at)"
                + " SELECT ?,ea.offering_id,eb.offering_id,COUNT(*),"
                + "MIN(LEAST(ea.start_at,eb.start_at)),MAX(GREATEST(ea.start_at,eb.start_at))"
                + " FROM (" + effective + ") ea JOIN (" + effective + ") eb"
                + " ON ea.offering_id<eb.offering_id"
                + " AND ea.start_at<eb.end_at AND ea.end_at>eb.start_at"
                + " GROUP BY ea.offering_id,eb.offering_id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // One for the inserted plan id, then two inside each copy of the effective-occurrence
            // derived table.
            for (int index = 1; index <= 5; index++) statement.setLong(index, planId);
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------- plans

    public PlanRow findPlan(Connection connection, long planId) throws SQLException {
        String sql = "SELECT p.id,p.name,p.calendar_id,p.revision,p.status,"
                + "c.current_schedule_plan_id FROM schedule_plan p"
                + " JOIN teaching_calendar c ON c.id=p.calendar_id WHERE p.id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                long current = rows.getLong("current_schedule_plan_id");
                return new PlanRow(rows.getLong("id"), rows.getString("name"),
                        rows.getLong("calendar_id"), rows.getInt("revision"),
                        rows.getString("status"), rows.wasNull() ? null : current);
            }
        }
    }

    public PlanRow findPlanByTerm(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT p.id FROM schedule_plan p JOIN teaching_calendar c ON c.id=p.calendar_id"
                + " WHERE c.academic_year=? AND c.semester=?"
                + " ORDER BY (p.status='DRAFT') DESC,p.revision DESC,p.id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? findPlan(connection, rows.getLong(1)) : null;
            }
        }
    }

    /** 该学期最新的教学日历 id；null 表示该学期尚未创建教学日历，排课无处容身。 */
    public Long findCalendarIdByTerm(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT id FROM teaching_calendar WHERE academic_year=? AND semester=?"
                + " ORDER BY version DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 该日历下已发布的方案 id；null 表示还没有可复制的来源。 */
    public Long findPublishedPlanId(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT id FROM schedule_plan WHERE calendar_id=? AND status='PUBLISHED'"
                + " ORDER BY revision DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 同一日历下已有的草稿方案 id；用于"已有草稿则拒绝重复创建"。 */
    public Long findDraftPlanId(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT id FROM schedule_plan WHERE calendar_id=? AND status='DRAFT'"
                + " ORDER BY revision DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 同名方案的下一个可用修订号；唯一键是 (calendar_id, name, revision)。 */
    public int nextRevision(Connection connection, long calendarId, String name)
            throws SQLException {
        String sql = "SELECT COALESCE(MAX(revision),0)+1 FROM schedule_plan"
                + " WHERE calendar_id=? AND name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public long insertPlan(Connection connection, String name, long calendarId, int revision,
                           String createdBy) throws SQLException {
        String sql = "INSERT INTO schedule_plan(name,calendar_id,revision,status,created_by)"
                + " VALUES(?,?,?,'DRAFT',?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setLong(2, calendarId);
            statement.setInt(3, revision);
            statement.setString(4, createdBy);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void lockPlan(Connection connection, long planId) throws SQLException {
        lock(connection, "SELECT id FROM schedule_plan WHERE id=? FOR UPDATE", planId);
    }

    public void lockCalendar(Connection connection, long calendarId) throws SQLException {
        lock(connection, "SELECT id FROM teaching_calendar WHERE id=? FOR UPDATE", calendarId);
    }

    public void lockArrangement(Connection connection, long arrangementId) throws SQLException {
        lock(connection, "SELECT arrangement_id FROM course_schedule_arrangement"
                + " WHERE arrangement_id=? FOR UPDATE", arrangementId);
    }

    public void markReady(Connection connection, long planId) throws SQLException {
        execute(connection, "UPDATE schedule_plan SET status='READY' WHERE id=? AND status='DRAFT'",
                planId);
    }

    public void markPublished(Connection connection, long planId, String adminUid,
                              Instant publishedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE schedule_plan SET status='PUBLISHED',published_by=?,published_at=?"
                        + " WHERE id=?")) {
            statement.setString(1, adminUid);
            statement.setTimestamp(2, timestamp(publishedAt));
            statement.setLong(3, planId);
            statement.executeUpdate();
        }
    }

    public void updateCalendarPointer(Connection connection, long calendarId, long planId)
            throws SQLException {
        execute(connection, "UPDATE teaching_calendar SET current_schedule_plan_id=? WHERE id=?",
                planId, calendarId);
    }

    // ------------------------------------------------------------ arrangements

    public List<ScheduleArrangementDTO> listArrangements(Connection connection, long planId,
                                                         Long offeringId) throws SQLException {
        String sql = "SELECT a.arrangement_id,a.plan_id,a.offering_id,a.teacher_uid,"
                + "a.assistant_uid,a.classroom_id,a.status,a.version,"
                + "r.weekday,r.start_period,r.end_period,w.week_no"
                + " FROM course_schedule_arrangement a"
                + " LEFT JOIN course_schedule_rule r ON r.arrangement_id=a.arrangement_id"
                + "     AND r.status='ACTIVE'"
                + " LEFT JOIN course_schedule_rule_week w ON w.rule_id=r.id"
                + " WHERE a.plan_id=? AND a.status='ACTIVE'"
                + (offeringId == null ? "" : " AND a.offering_id=?")
                + " ORDER BY a.arrangement_id,r.weekday,r.start_period,w.week_no";
        Map<Long, Aggregate> aggregates = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, planId);
            if (offeringId != null) statement.setLong(2, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    long arrangementId = rows.getLong("arrangement_id");
                    Aggregate aggregate = aggregates.get(arrangementId);
                    if (aggregate == null) {
                        aggregate = new Aggregate(arrangementRow(rows));
                        aggregates.put(arrangementId, aggregate);
                    }
                    int weekday = rows.getInt("weekday");
                    int startPeriod = rows.getInt("start_period");
                    boolean hasSlot = !rows.wasNull();
                    int endPeriod = rows.getInt("end_period");
                    if (hasSlot) {
                        aggregate.slots.put(weekday + ":" + startPeriod + ":" + endPeriod,
                                new ScheduleSlotDTO(weekday, startPeriod, endPeriod));
                    }
                    int week = rows.getInt("week_no");
                    if (!rows.wasNull()) {
                        aggregate.startWeek = Math.min(aggregate.startWeek, week);
                        aggregate.endWeek = Math.max(aggregate.endWeek, week);
                    }
                }
            }
        }
        List<ScheduleArrangementDTO> arrangements = new ArrayList<>();
        for (Aggregate aggregate : aggregates.values()) {
            aggregate.teacher = resource(connection, TEACHER, aggregate.row.teacherUid());
            aggregate.assistant = resource(connection, TEACHER, aggregate.row.assistantUid());
            aggregate.classroom = aggregate.row.classroomId() == null ? null
                    : resource(connection, CLASSROOM, Long.toString(aggregate.row.classroomId()));
            arrangements.add(aggregate.toDTO());
        }
        return List.copyOf(arrangements);
    }

    public ScheduleArrangementDTO findArrangement(Connection connection, long arrangementId)
            throws SQLException {
        long planId;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT plan_id FROM course_schedule_arrangement WHERE arrangement_id=?")) {
            statement.setLong(1, arrangementId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                planId = rows.getLong(1);
            }
        }
        for (ScheduleArrangementDTO arrangement : listArrangements(connection, planId, null)) {
            if (arrangementId == Long.parseLong(arrangement.getArrangementId())) return arrangement;
        }
        return null;
    }

    public ArrangementRow arrangementRow(Connection connection, long arrangementId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT arrangement_id,plan_id,offering_id,teacher_uid,assistant_uid,classroom_id,"
                        + "status,version FROM course_schedule_arrangement WHERE arrangement_id=?")) {
            statement.setLong(1, arrangementId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? arrangementRow(rows) : null;
            }
        }
    }

    public long insertArrangement(Connection connection, long planId, long offeringId,
                                  String teacherUid, String assistantUid, Long classroomId,
                                  String adminUid) throws SQLException {
        String sql = "INSERT INTO course_schedule_arrangement"
                + "(plan_id,offering_id,teacher_uid,assistant_uid,classroom_id,status,version,"
                + "created_by,updated_at) VALUES(?,?,?,?,?,'ACTIVE',1,?,UTC_TIMESTAMP(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, planId);
            statement.setLong(2, offeringId);
            statement.setString(3, teacherUid);
            statement.setString(4, assistantUid);
            if (classroomId == null) statement.setNull(5, Types.BIGINT);
            else statement.setLong(5, classroomId);
            statement.setString(6, adminUid);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public int updateArrangement(Connection connection, long arrangementId, int expectedVersion,
                                 String teacherUid, String assistantUid, Long classroomId)
            throws SQLException {
        String sql = "UPDATE course_schedule_arrangement SET teacher_uid=?,assistant_uid=?,"
                + "classroom_id=?,version=version+1,updated_at=UTC_TIMESTAMP(6)"
                + " WHERE arrangement_id=? AND version=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, teacherUid);
            statement.setString(2, assistantUid);
            if (classroomId == null) statement.setNull(3, Types.BIGINT);
            else statement.setLong(3, classroomId);
            statement.setLong(4, arrangementId);
            statement.setInt(5, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public void deleteArrangement(Connection connection, long arrangementId) throws SQLException {
        execute(connection, "DELETE FROM course_schedule_arrangement WHERE arrangement_id=?",
                arrangementId);
    }

    /** Removes this aggregate's normalized children so they can be rebuilt from the request. */
    public void deleteChildren(Connection connection, long arrangementId) throws SQLException {
        execute(connection, "DELETE FROM resource_booking WHERE occurrence_id IN"
                + " (SELECT o.id FROM course_occurrence o JOIN course_schedule_rule r"
                + " ON r.id=o.rule_id WHERE r.arrangement_id=?)", arrangementId);
        execute(connection, "DELETE FROM course_occurrence WHERE rule_id IN"
                + " (SELECT id FROM course_schedule_rule WHERE arrangement_id=?)", arrangementId);
        execute(connection, "DELETE FROM course_schedule_rule WHERE arrangement_id=?",
                arrangementId);
    }

    public long insertRule(Connection connection, long planId, long offeringId, long arrangementId,
                           ScheduleSlotDTO slot) throws SQLException {
        String sql = "INSERT INTO course_schedule_rule"
                + "(plan_id,course_offering_id,arrangement_id,weekday,start_period,end_period,status)"
                + " VALUES(?,?,?,?,?,?,'ACTIVE')";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, planId);
            statement.setLong(2, offeringId);
            statement.setLong(3, arrangementId);
            statement.setInt(4, slot.getDayOfWeek());
            statement.setInt(5, slot.getStartPeriod());
            statement.setInt(6, slot.getEndPeriod());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public void insertRuleWeek(Connection connection, long ruleId, int weekNo) throws SQLException {
        execute(connection, "INSERT INTO course_schedule_rule_week(rule_id,week_no) VALUES(?,?)",
                ruleId, weekNo);
    }

    public long insertOccurrence(Connection connection, long ruleId, long planId, Timestamp startAt,
                                 Timestamp endAt, int weekNo, int weekday) throws SQLException {
        String sql = "INSERT INTO course_occurrence(rule_id,plan_id,start_at,end_at,week_no,"
                + "teaching_weekday) VALUES(?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, ruleId);
            statement.setLong(2, planId);
            statement.setTimestamp(3, startAt);
            statement.setTimestamp(4, endAt);
            statement.setInt(5, weekNo);
            statement.setInt(6, weekday);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public long ensureResource(Connection connection, String type, String businessId)
            throws SQLException {
        Long existing = resourceId(connection, type, businessId);
        if (existing != null) return existing;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO schedule_resource(resource_type,business_id,conflict_mode)"
                        + " VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, type);
            statement.setString(2, businessId);
            statement.setString(3, EXCLUSIVE);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException failure) {
            if (!DUPLICATE_KEY_STATE.equals(failure.getSQLState())) throw failure;
            Long raced = resourceId(connection, type, businessId);
            if (raced == null) throw failure;
            return raced;
        }
    }

    public void insertBooking(Connection connection, long planId, long occurrenceId, long resourceId,
                              String role) throws SQLException {
        execute(connection, "INSERT INTO resource_booking(plan_id,occurrence_id,resource_id,"
                + "resource_role) VALUES(?,?,?,?)", planId, occurrenceId, resourceId, role);
    }

    // ------------------------------------------------------- referenced objects

    public OfferingState offeringState(Connection connection, long offeringId) throws SQLException {
        String sql = "SELECT o.capacity,o.status,o.offering_code,c.status AS course_status"
                + " FROM course_offering o JOIN course c ON c.course_id=o.course_id"
                + " WHERE o.offering_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, offeringId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new OfferingState(rows.getInt("capacity"), rows.getInt("status"),
                        rows.getString("course_status"), rows.getString("offering_code"));
            }
        }
    }

    public Integer classroomCapacity(Connection connection, long classroomId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT capacity FROM classroom WHERE id=?")) {
            statement.setLong(1, classroomId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : null;
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

    // ------------------------------------------------------------- operation log

    public void insertOperation(Connection connection, String adminUid, String operationId,
                                String action, String targetType, String targetId, String digest,
                                String requestJson, String conflictSnapshotJson, boolean forced,
                                String overrideReason, String resultCode, String responseJson,
                                Instant completedAt) throws SQLException {
        String sql = "INSERT INTO admin_course_operation_log(admin_uid,operation_id,action,"
                + "target_type,target_id,request_digest,request_json,conflict_snapshot_json,forced,"
                + "override_reason,result_code,response_json,completed_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adminUid);
            statement.setString(2, operationId);
            statement.setString(3, action);
            statement.setString(4, targetType);
            statement.setString(5, targetId);
            statement.setString(6, digest);
            statement.setString(7, requestJson);
            statement.setString(8, conflictSnapshotJson);
            statement.setInt(9, forced ? 1 : 0);
            statement.setString(10, overrideReason);
            statement.setString(11, resultCode);
            statement.setString(12, responseJson);
            statement.setTimestamp(13, timestamp(completedAt));
            statement.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ helpers

    private Long resourceId(Connection connection, String type, String businessId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM schedule_resource WHERE resource_type=? AND business_id=?")) {
            statement.setString(1, type);
            statement.setString(2, businessId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private ScheduleResourceDTO resource(Connection connection, String type, String businessId)
            throws SQLException {
        if (businessId == null) return null;
        if (TEACHER.equals(type)) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM tbl_user WHERE UID=?")) {
                statement.setString(1, businessId);
                try (ResultSet rows = statement.executeQuery()) {
                    String name = rows.next() ? rows.getString(1) : businessId;
                    return new ScheduleResourceDTO(businessId, businessId, name, TEACHER, 0);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,capacity FROM classroom WHERE id=?")) {
            statement.setLong(1, Long.parseLong(businessId));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return new ScheduleResourceDTO(businessId, businessId, businessId, CLASSROOM, 0);
                }
                return new ScheduleResourceDTO(businessId, businessId, rows.getString(1), CLASSROOM,
                        rows.getInt(2));
            }
        }
    }

    private static ArrangementRow arrangementRow(ResultSet rows) throws SQLException {
        long classroomId = rows.getLong("classroom_id");
        Long classroom = rows.wasNull() ? null : classroomId;
        return new ArrangementRow(rows.getLong("arrangement_id"), rows.getLong("plan_id"),
                rows.getLong("offering_id"), rows.getString("teacher_uid"),
                rows.getString("assistant_uid"), classroom, rows.getString("status"),
                rows.getInt("version"));
    }

    private static void lock(Connection connection, String sql, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (argument instanceof Integer value) statement.setInt(index + 1, value);
                else if (argument instanceof Long value) statement.setLong(index + 1, value);
                else statement.setString(index + 1, (String) argument);
            }
            statement.executeUpdate();
        }
    }

    private static String like(String query) {
        if (query == null || query.isBlank()) return null;
        return "%" + query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static int key(int weekNo, int weekday) {
        return weekNo * 10 + weekday;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.valueOf(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
    }

    private static Timestamp timestamp(ZonedDateTime local) {
        return timestamp(local.toInstant());
    }

    public record PlanRow(long planId, String name, long calendarId, int revision, String status,
                          Long currentPlanId) {
    }

    public record ArrangementRow(long arrangementId, long planId, long offeringId, String teacherUid,
                                 String assistantUid, Long classroomId, String status, int version) {
    }

    public record OfferingState(int capacity, int status, String courseStatus, String offeringCode) {
    }

    public record CalendarDay(LocalDate date, int weekNo, int weekday, long templateId) {
    }

    public record Period(LocalTime start, LocalTime end) {
    }

    public record Window(Timestamp start, Timestamp end) {
    }

    /** Teaching calendar flipped into teaching weeks and the concrete period clock. */
    public static final class CalendarContext {
        private final long calendarId;
        private final ZoneId zone;
        private final Map<Integer, CalendarDay> byWeekDay;
        private final Map<Long, Map<Integer, Period>> templates;

        CalendarContext(long calendarId, ZoneId zone, Map<Integer, CalendarDay> byWeekDay,
                        Map<Long, Map<Integer, Period>> templates) {
            this.calendarId = calendarId;
            this.zone = zone;
            this.byWeekDay = byWeekDay;
            this.templates = templates;
        }

        public long calendarId() {
            return calendarId;
        }

        public ZoneId zone() {
            return zone;
        }

        /**
         * The UTC window for one slot in one teaching week, or {@code null} when the week has no
         * such teaching day or the period numbers are undefined for that day's template.
         */
        public Window window(int weekNo, int weekday, int startPeriod, int endPeriod) {
            CalendarDay day = byWeekDay.get(key(weekNo, weekday));
            if (day == null) return null;
            Map<Integer, Period> periods = templates.get(day.templateId());
            if (periods == null) return null;
            Period first = periods.get(startPeriod);
            Period last = periods.get(endPeriod);
            if (first == null || last == null) return null;
            return new Window(timestamp(ZonedDateTime.of(day.date(), first.start(), zone)),
                    timestamp(ZonedDateTime.of(day.date(), last.end(), zone)));
        }
    }

    private static final class Aggregate {
        private final ArrangementRow row;
        private final Map<String, ScheduleSlotDTO> slots = new LinkedHashMap<>();
        private int startWeek = Integer.MAX_VALUE;
        private int endWeek = 0;
        private ScheduleResourceDTO teacher;
        private ScheduleResourceDTO assistant;
        private ScheduleResourceDTO classroom;

        private Aggregate(ArrangementRow row) {
            this.row = row;
        }

        private ScheduleArrangementDTO toDTO() {
            return new ScheduleArrangementDTO(Long.toString(row.arrangementId()),
                    Long.toString(row.planId()), Long.toString(row.offeringId()), teacher, assistant,
                    classroom, List.copyOf(slots.values()),
                    startWeek == Integer.MAX_VALUE ? 0 : startWeek, endWeek, row.status(),
                    row.version());
        }
    }
}
