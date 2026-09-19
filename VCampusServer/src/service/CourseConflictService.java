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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

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

    /**
    * Handles the course-management responsibility of CourseConflictService.
    */
    public CourseConflictService() {
        this(new AdminScheduleDAO(), new AdminScheduleConflictDAO());
    }

    /**
    * Handles the course-management responsibility of CourseConflictService.
    */
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

    /**
    * Handles the course-management responsibility of check.
    */
    public List<ScheduleConflictDTO> check(Candidate candidate) {
        try (Connection connection = DBUtil.getConnection()) {
            return check(connection, candidate);
        } catch (SQLException failure) {
            throw new DatabaseException("排课冲突检查失败", failure);
        }
    }

    /**
    * Handles the course-management responsibility of check.
    */
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
        String offeringId = Long.toString(candidate.offeringId());
        String label = offeringLabel(offering, candidate.offeringId());
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
                            Long.toString(candidate.classroomId()), offeringId, offeringId,
                            label, week, slot.getDayOfWeek(), slot.getStartPeriod(),
                            slot.getEndPeriod(),
                            "教室容量 " + roomCapacity + " 小于教学班容量 " + offering.capacity()));
                }
                for (AdminScheduleConflictDAO.EffectiveOccurrence other : conflictDAO.overlapping(
                        connection, candidate.planId(), window.start(), window.end(),
                        candidate.arrangementId())) {
                    if (excludedOccurrenceIds.contains(other.occurrenceId())) continue;
                    classify(candidate, slot, week, other, conflicts, seen, offeringId, label);
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

    /** 读路径用的整方案有效检查；不完整的安排会被跳过。 */
    public List<ScheduleConflictDTO> checkPlan(long planId) {
        try (Connection connection = DBUtil.getConnection()) {
            return checkPlan(connection, planId);
        } catch (SQLException failure) {
            throw new DatabaseException("排课方案冲突检查失败", failure);
        }
    }

    /** Publication gate for callers that are not already inside a transaction. */
    public void requirePublishable(long planId) {
        try (Connection connection = DBUtil.getConnection()) {
            requirePublishable(connection, planId);
        } catch (SQLException failure) {
            throw new DatabaseException("排课方案发布校验失败", failure);
        }
    }

    /**
    * Full-plan effective check used by reads and by publication. An arrangement that cannot form a
    * candidate — no teacher, or no slots yet — is skipped rather than fatal: a plan that is being
    * edited, or merely displayed, may legitimately contain unfinished rows. Publication still
    * rejects them; see {@link #requirePublishable}.
    */
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
                continue;
            }
            for (ScheduleConflictDTO conflict : check(connection, candidate, calendar)) {
                add(conflicts, seen, conflict);
            }
        }
        return mergeWeekRanges(conflicts);
    }

    /**
    * Publication gate: the plan must exist, hold at least one arrangement, and every arrangement
    * must name a teacher and carry at least one slot. This is the only place the incompleteness is
    * fatal — {@link #checkPlan} deliberately tolerates it so that reading a half-finished plan does
    * not fail.
    *
    * <p>An empty plan is refused as well, and with its own message: publishing one advances
    * {@code teaching_calendar.current_schedule_plan_id} onto a plan with no occurrences, and both
    * the student and the teacher timetable read that pointer, so a single publish would blank both
    * at once. The two refusals stay separate on purpose — an administrator told the arrangements
    * are missing a teacher would look for a row that does not exist.
    */
    public void requirePublishable(Connection connection, long planId) throws SQLException {
        if (scheduleDAO.findPlan(connection, planId) == null) {
            throw new IllegalArgumentException("排课方案不存在");
        }
        List<ScheduleArrangementDTO> arrangements = scheduleDAO.listArrangements(connection, planId,
                null);
        if (arrangements.isEmpty()) {
            throw new IllegalArgumentException("该排课方案没有任何教学安排，无法发布");
        }
        for (ScheduleArrangementDTO arrangement : arrangements) {
            if (candidate(planId, arrangement) == null) {
                throw new IllegalArgumentException("教学安排缺少任课教师或时间段，无法发布");
            }
        }
    }

    /**
    * One stored arrangement reshaped as a candidate, or {@code null} when it cannot form one —
    * no teacher, or no slots yet. Package-private so the copy path in
    * {@code ScheduleManagementService} reuses this single rule instead of forking it.
    */
    static Candidate candidate(long planId, ScheduleArrangementDTO arrangement) {
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
                                 List<ScheduleConflictDTO> conflicts, Set<String> seen,
                                 String offeringId, String offeringLabel) {
        String related = Long.toString(other.offeringId());
        if (other.offeringId() == candidate.offeringId()) {
            add(conflicts, seen, conflict(OFFERING_OVERLAP, BLOCKING, related, related, offeringId,
                    offeringLabel, week, slot, "同一教学班在该时间已有排课"));
        }
        if (candidate.teacherUid() != null && candidate.teacherUid().equals(other.teacherUid())) {
            add(conflicts, seen, conflict(TEACHER_OVERLAP, OVERRIDABLE,
                    candidate.teacherUid(), related, offeringId, offeringLabel, week, slot,
                    "任课教师在该时间已有其他课程"));
        }
        if (candidate.assistantUid() != null
                && candidate.assistantUid().equals(other.assistantUid())) {
            add(conflicts, seen, conflict(ASSISTANT_OVERLAP, OVERRIDABLE, candidate.assistantUid(),
                    related, offeringId, offeringLabel, week, slot, "助教在该时间已有其他课程"));
        }
        if (candidate.classroomId() != null && candidate.classroomId().equals(other.classroomId())) {
            add(conflicts, seen, conflict(CLASSROOM_OVERLAP, OVERRIDABLE,
                    Long.toString(candidate.classroomId()), related, offeringId, offeringLabel,
                    week, slot, "教室在该时间已被其他课程占用"));
        }
    }

    /** 冲突归属标签：教学班代码缺失时退回教学班号，绝不产生空标签。 */
    private static String offeringLabel(AdminScheduleDAO.OfferingState offering, long offeringId) {
        String code = offering.offeringCode();
        return code == null || code.isBlank() ? "教学班 " + offeringId : code;
    }

    private static ScheduleConflictDTO conflict(String type,
                                                dto.course.admin.schedule.ScheduleConflictSeverityDTO severity,
                                                String subjectId, String relatedOfferingId,
                                                String offeringId, String offeringLabel, int week,
                                                ScheduleSlotDTO slot, String message) {
        return new ScheduleConflictDTO(type, severity, subjectId, relatedOfferingId, offeringId,
                offeringLabel, week, slot.getDayOfWeek(), slot.getStartPeriod(),
                slot.getEndPeriod(), message);
    }

    /**
    * 去重键里带上产生该冲突的教学班：{@link #checkPlan} 的 {@code seen} 跨 candidate 共享，若只按
    * 「类型 + 时间 + 对象 + 对方教学班」去重，三个教学班共用同一位教师（或同一教室）时，B 报出的
    * 「与 C 冲突」会被 A 报出的同键条目顶掉，某个教学班在方案级列表里整个消失。
    */
    private static void add(List<ScheduleConflictDTO> conflicts, Set<String> seen,
                            ScheduleConflictDTO conflict) {
        String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
                + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId() + "|"
                + conflict.getOfferingId();
        if (seen.add(key)) conflicts.add(conflict);
    }

    /**
    * 一条跨 N 周的安排会对每周各报一次同样的冲突（用户案例：9 条「教室容量 40 小于教学班容量 45」），
    * 这里把「其余字段完全相同、周次连续」的冲突合并成区间：{@code week}=段起，{@code endWeek}=段止。
    * 只在两个消费点调用——{@link #checkPlan} 与 {@code ScheduleManagementService.checkArrangement}；
    * {@link #check} 引擎本身保持按周列表，调课模块复用 check 并依赖那个形状。
    *
    * <p>每条输入先按其覆盖的周集合展开（旧 journal JSON 缺失 endWeek 时为 0，等价于单周），所以
    * 对已合并的结果再跑一次不变形；组内周次排序后切连续段，「第 3 周和第 9 周」这种不相邻的周次
    * 保持两条。分组键含 message 与归属字段，形状相同但文案或归属不同的冲突不会被并到一起。
    */
    static List<ScheduleConflictDTO> mergeWeekRanges(List<ScheduleConflictDTO> conflicts) {
        Map<String, SortedSet<Integer>> weeksByGroup = new LinkedHashMap<>();
        Map<String, ScheduleConflictDTO> templateByGroup = new LinkedHashMap<>();
        for (ScheduleConflictDTO conflict : conflicts) {
            String key = conflict.getType() + "|" + conflict.getSeverity() + "|"
                    + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId() + "|"
                    + conflict.getOfferingId() + "|" + conflict.getDayOfWeek() + "|"
                    + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                    + conflict.getMessage();
            templateByGroup.putIfAbsent(key, conflict);
            SortedSet<Integer> weeks =
                    weeksByGroup.computeIfAbsent(key, ignored -> new TreeSet<>());
            for (int week = conflict.getWeek();
                    week <= Math.max(conflict.getWeek(), conflict.getEndWeek()); week++) {
                weeks.add(week);
            }
        }
        List<ScheduleConflictDTO> merged = new ArrayList<>();
        for (Map.Entry<String, SortedSet<Integer>> group : weeksByGroup.entrySet()) {
            ScheduleConflictDTO template = templateByGroup.get(group.getKey());
            List<Integer> weeks = List.copyOf(group.getValue());
            int start = weeks.get(0);
            for (int index = 1; index < weeks.size(); index++) {
                if (weeks.get(index) != weeks.get(index - 1) + 1) {
                    merged.add(weekRange(template, start, weeks.get(index - 1)));
                    start = weeks.get(index);
                }
            }
            merged.add(weekRange(template, start, weeks.get(weeks.size() - 1)));
        }
        return List.copyOf(merged);
    }

    private static ScheduleConflictDTO weekRange(ScheduleConflictDTO template, int week,
                                                 int endWeek) {
        return new ScheduleConflictDTO(template.getType(), template.getSeverity(),
                template.getSubjectId(), template.getRelatedOfferingId(), template.getOfferingId(),
                template.getOfferingLabel(), week, endWeek, template.getDayOfWeek(),
                template.getStartPeriod(), template.getEndPeriod(), template.getMessage());
    }
}
