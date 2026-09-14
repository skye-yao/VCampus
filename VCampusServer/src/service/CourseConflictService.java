package service;

import dao.AdminScheduleConflictDAO;
import dao.AdminScheduleDAO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Centralized effective-schedule conflict detection. A conflicting occurrence replaced by an
 * ACTIVE temporary adjustment no longer occupies resources, while the adjustment itself does at
 * its replacement time and resources.
 */
public class CourseConflictService {
    public static final String OFFERING_OVERLAP = "OFFERING_OVERLAP";
    public static final String TEACHER_OVERLAP = "TEACHER_OVERLAP";
    public static final String ASSISTANT_OVERLAP = "ASSISTANT_OVERLAP";
    public static final String CLASSROOM_OVERLAP = "CLASSROOM_OVERLAP";
    public static final String CLASSROOM_CAPACITY = "CLASSROOM_CAPACITY";

    private final AdminScheduleDAO scheduleDAO;
    private final AdminScheduleConflictDAO conflictDAO;

    public CourseConflictService() {
        this(new AdminScheduleDAO(), new AdminScheduleConflictDAO());
    }

    public CourseConflictService(AdminScheduleDAO scheduleDAO,
                                 AdminScheduleConflictDAO conflictDAO) {
        this.scheduleDAO = scheduleDAO;
        this.conflictDAO = conflictDAO;
    }

    /** An arrangement as it is about to be written, or an existing one being re-checked. */
    public record Candidate(long planId, Long arrangementId, long offeringId, String teacherUid,
                            String assistantUid, Long classroomId, List<ScheduleSlotDTO> slots,
                            int startWeek, int endWeek) {
    }

    public List<ScheduleConflictDTO> check(Candidate candidate) {
        try (Connection connection = DBUtil.getConnection()) {
            return check(connection, candidate);
        } catch (SQLException failure) {
            throw new DatabaseException("排课冲突检查失败", failure);
        }
    }

    public List<ScheduleConflictDTO> check(Connection connection, Candidate candidate)
            throws SQLException {
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, candidate.planId());
        if (plan == null) throw new IllegalArgumentException("排课方案不存在");
        AdminScheduleDAO.CalendarContext calendar =
                scheduleDAO.loadCalendar(connection, plan.calendarId());
        if (calendar == null) throw new IllegalArgumentException("教学日历不存在");
        return check(connection, candidate, calendar);
    }

    /**
     * Same classification against the effective schedule, but occurrences the caller names are
     * ignored. A temporary adjustment request in flight replaces originals that are still present,
     * so the approval of that very request has to exclude them explicitly; the arrangement-level
     * exclusion below cannot express a non-contiguous set of temporary targets.
     */
    public List<ScheduleConflictDTO> check(Connection connection, Candidate candidate,
                                           Set<Long> excludedOccurrenceIds)
            throws SQLException {
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, candidate.planId());
        if (plan == null) throw new IllegalArgumentException("排课方案不存在");
        AdminScheduleDAO.CalendarContext calendar =
                scheduleDAO.loadCalendar(connection, plan.calendarId());
        if (calendar == null) throw new IllegalArgumentException("教学日历不存在");
        return check(connection, candidate, calendar, excludedOccurrenceIds);
    }

    private List<ScheduleConflictDTO> check(Connection connection, Candidate candidate,
                                           AdminScheduleDAO.CalendarContext calendar)
            throws SQLException {
        return check(connection, candidate, calendar, Set.of());
    }

    private List<ScheduleConflictDTO> check(Connection connection, Candidate candidate,
                                           AdminScheduleDAO.CalendarContext calendar,
                                           Set<Long> excludedOccurrenceIds)
            throws SQLException {
        AdminScheduleDAO.OfferingState offering =
                scheduleDAO.offeringState(connection, candidate.offeringId());
        if (offering == null) throw new IllegalArgumentException("教学班不存在");
        Integer roomCapacity = candidate.classroomId() == null ? null
                : scheduleDAO.classroomCapacity(connection, candidate.classroomId());

        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ScheduleSlotDTO slot : candidate.slots()) {
            for (int week = candidate.startWeek(); week <= candidate.endWeek(); week++) {
                AdminScheduleDAO.Window window = calendar.window(week, slot.getDayOfWeek(),
                        slot.getStartPeriod(), slot.getEndPeriod());
                if (window == null) {
                    throw new IllegalArgumentException("周次或节次超出教学日历范围");
                }
                if (roomCapacity != null && roomCapacity < offering.capacity()) {
                    add(conflicts, seen, new ScheduleConflictDTO(CLASSROOM_CAPACITY, OVERRIDABLE,
                            Long.toString(candidate.classroomId()), null, week, slot.getDayOfWeek(),
                            slot.getStartPeriod(), slot.getEndPeriod(),
                            "教室容量 " + roomCapacity + " 小于教学班容量 " + offering.capacity()));
                }
                for (AdminScheduleConflictDAO.EffectiveOccurrence other : conflictDAO.overlapping(
                        connection, candidate.planId(), window.start(), window.end(),
                        candidate.arrangementId())) {
                    if (excludedOccurrenceIds.contains(other.occurrenceId())) continue;
                    classify(candidate, slot, week, other, conflicts, seen);
                }
            }
        }
        return List.copyOf(conflicts);
    }


    /** Student enrollment uses effective timestamps, including adjustments on either offering. */
    public List<ScheduleConflictDTO> checkStudent(Connection connection, String studentUid,
            long offeringId, int academicYear, int semester, List<Long> enrolledOfferingIds)
            throws SQLException {
        Long planId = conflictDAO.publishedPlanId(connection, academicYear, semester);
        if (planId == null) return List.of();
        Set<Long> enrolled = new LinkedHashSet<>(enrolledOfferingIds);
        enrolled.remove(offeringId);
        if (enrolled.isEmpty()) return List.of();
        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AdminScheduleConflictDAO.StudentWindow window
                : conflictDAO.offeringWindows(connection, planId, offeringId)) {
            for (AdminScheduleConflictDAO.EffectiveOccurrence other : conflictDAO.overlapping(
                    connection, planId, window.startAt(), window.endAt(), null)) {
                if (enrolled.contains(other.offeringId())) {
                    add(conflicts, seen, new ScheduleConflictDTO("STUDENT_SCHEDULE", OVERRIDABLE,
                            studentUid, Long.toString(other.offeringId()), window.week(), window.dayOfWeek(),
                            window.startPeriod(), window.endPeriod(), "与该学生已选教学班的有效课表时间冲突"));
                }
            }
        }
        return List.copyOf(conflicts);
    }

    /** Full-plan effective check used before publication. */
    public List<ScheduleConflictDTO> checkPlan(long planId) {
        try (Connection connection = DBUtil.getConnection()) {
            return checkPlan(connection, planId);
        } catch (SQLException failure) {
            throw new DatabaseException("排课方案冲突检查失败", failure);
        }
    }

    public List<ScheduleConflictDTO> checkPlan(Connection connection, long planId)
            throws SQLException {
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, planId);
        if (plan == null) throw new IllegalArgumentException("排课方案不存在");
        AdminScheduleDAO.CalendarContext calendar =
                scheduleDAO.loadCalendar(connection, plan.calendarId());
        if (calendar == null) throw new IllegalArgumentException("教学日历不存在");
        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ScheduleArrangementDTO arrangement : scheduleDAO.listArrangements(connection, planId,
                null)) {
            Candidate candidate = candidate(planId, arrangement);
            if (candidate == null) {
                throw new IllegalArgumentException("教学安排缺少任课教师或时间段，无法发布");
            }
            for (ScheduleConflictDTO conflict : check(connection, candidate, calendar)) {
                add(conflicts, seen, conflict);
            }
        }
        return List.copyOf(conflicts);
    }

    private static Candidate candidate(long planId, ScheduleArrangementDTO arrangement) {
        ScheduleResourceDTO teacher = arrangement.getTeacher();
        ScheduleResourceDTO classroom = arrangement.getClassroom();
        if (teacher == null || arrangement.getSlots().isEmpty()) return null;
        return new Candidate(planId, Long.parseLong(arrangement.getArrangementId()),
                Long.parseLong(arrangement.getOfferingId()), teacher.getBusinessId(),
                arrangement.getAssistant() == null ? null : arrangement.getAssistant().getBusinessId(),
                classroom == null ? null : Long.valueOf(classroom.getBusinessId()),
                arrangement.getSlots(), arrangement.getStartWeek(), arrangement.getEndWeek());
    }

    private static void classify(Candidate candidate, ScheduleSlotDTO slot, int week,
                                 AdminScheduleConflictDAO.EffectiveOccurrence other,
                                 List<ScheduleConflictDTO> conflicts, Set<String> seen) {
        String related = Long.toString(other.offeringId());
        if (other.offeringId() == candidate.offeringId()) {
            add(conflicts, seen, conflict(OFFERING_OVERLAP, BLOCKING, related, related, week, slot,
                    "同一教学班在该时间已有排课"));
        }
        if (candidate.teacherUid() != null && candidate.teacherUid().equals(other.teacherUid())) {
            add(conflicts, seen, conflict(TEACHER_OVERLAP, OVERRIDABLE,
                    candidate.teacherUid(), related, week, slot, "任课教师在该时间已有其他课程"));
        }
        if (candidate.assistantUid() != null
                && candidate.assistantUid().equals(other.assistantUid())) {
            add(conflicts, seen, conflict(ASSISTANT_OVERLAP, OVERRIDABLE, candidate.assistantUid(),
                    related, week, slot, "助教在该时间已有其他课程"));
        }
        if (candidate.classroomId() != null && candidate.classroomId().equals(other.classroomId())) {
            add(conflicts, seen, conflict(CLASSROOM_OVERLAP, OVERRIDABLE,
                    Long.toString(candidate.classroomId()), related, week, slot,
                    "教室在该时间已被其他课程占用"));
        }
    }

    private static ScheduleConflictDTO conflict(String type,
                                                dto.course.admin.schedule.ScheduleConflictSeverityDTO severity,
                                                String subjectId, String relatedOfferingId,
                                                int week, ScheduleSlotDTO slot, String message) {
        return new ScheduleConflictDTO(type, severity, subjectId, relatedOfferingId, week,
                slot.getDayOfWeek(), slot.getStartPeriod(), slot.getEndPeriod(), message);
    }

    private static void add(List<ScheduleConflictDTO> conflicts, Set<String> seen,
                            ScheduleConflictDTO conflict) {
        String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
                + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId();
        if (seen.add(key)) conflicts.add(conflict);
    }
}
