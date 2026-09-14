package service;

import dao.AdminScheduleConflictDAO;
import dao.AdminScheduleDAO;
import dao.TeacherAdjustmentConflictDAO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * 教师调课提交与管理员审批共用的有效课表冲突检查。
 *
 * <p>每次调用在一个调用者提供的事务连接上检查一批候选（一个申请的全部目标），并返回
 * {@link ScheduleConflictDTO}：教学班、教师/助教（同一人员资源，跨角色也算，教师UID是唯一标识）、
 * 教室都是资源冲突；本教学班其他课次、目标日期越界、申请内部自身重叠/重复目标是 BLOCKING；
 * 学生风险只报告本班同时正常选课的学生。
 *
 * <p>有效课表来自本教学班所在学期当前 PUBLISHED 方案的 {@code course_schedule_arrangement}：
 * 被 ACTIVE 调课替换的原 occurrence 不再占用资源，ACTIVE 调课本身在其目标 UTC 窗口占用资源；
 * 只有 {@code originalIds} 里点名的原 occurrence 被排除，绝不排除整个 arrangement。跨周调课按
 * 目标教学日在教学日历里解析出的 UTC 窗口查询，日期越界一律 BLOCKING。
 */
public class ScheduleAdjustmentConflictService {
    /** 本班学生的其他有效课程；对教师是提示，对管理员是需确认的 OVERRIDABLE 风险。 */
    public static final String STUDENT_SCHEDULE = "STUDENT_SCHEDULE";
    /** 目标日期/节次不在教学日历范围内，与管理员既有 {@code SLOT_INVALID} 同值。 */
    public static final String SLOT_INVALID = "ADJUSTMENT_SLOT_INVALID";

    private final AdminScheduleDAO scheduleDAO;
    private final AdminScheduleConflictDAO conflictDAO;
    private final TeacherAdjustmentConflictDAO teacherConflictDAO;

    public ScheduleAdjustmentConflictService() {
        this(new AdminScheduleDAO(), new AdminScheduleConflictDAO(),
                new TeacherAdjustmentConflictDAO());
    }

    public ScheduleAdjustmentConflictService(AdminScheduleDAO scheduleDAO,
            AdminScheduleConflictDAO conflictDAO, TeacherAdjustmentConflictDAO teacherConflictDAO) {
        this.scheduleDAO = scheduleDAO;
        this.conflictDAO = conflictDAO;
        this.teacherConflictDAO = teacherConflictDAO;
    }

    /**
     * 一个目标落点：目标教学日、教学周与节次、解析出的 UTC 窗口，以及这套新安排需要的人员与教室。
     * 一次申请的所有目标共用同一个新节次与资源，但可以落在不同周的不同教学日。
     */
    public record Candidate(long calendarDateId, int week, int teachingWeekday,
            int startPeriod, int endPeriod, Instant startAt, Instant endAt,
            String teacherUid, String assistantUid, Long classroomId) {
    }

    /**
     * 返回候选集合的全部冲突，按发现顺序：先是申请内部冲突，再按候选顺序检查每个候选。冲突的
     * week/dayOfWeek/startPeriod/endPeriod 恒为候选自己的落点，便于前端直接标注到目标周。空候选
     * 返回空列表；未知教学班、没有已发布方案或教学日历都视为调用方状态错误，抛
     * {@link IllegalArgumentException}。
     */
    public List<ScheduleConflictDTO> check(Connection connection, long offeringId,
            List<Candidate> candidates, Set<Long> originalIds) throws SQLException {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Set<Long> excluded = originalIds == null ? Set.of() : originalIds;

        TeacherAdjustmentConflictDAO.OfferingTerm term =
                teacherConflictDAO.offeringTerm(connection, offeringId);
        if (term == null) throw new IllegalArgumentException("教学班不存在");
        Long planId = conflictDAO.publishedPlanId(connection, term.academicYear(), term.semester());
        if (planId == null) throw new IllegalArgumentException("该学期暂无已发布的排课方案");
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, planId);
        if (plan == null) throw new IllegalArgumentException("排课方案不存在");
        AdminScheduleDAO.CalendarContext calendar =
                scheduleDAO.loadCalendar(connection, plan.calendarId());
        if (calendar == null) throw new IllegalArgumentException("教学日历不存在");
        AdminScheduleDAO.OfferingState offering = scheduleDAO.offeringState(connection, offeringId);
        if (offering == null) throw new IllegalArgumentException("教学班不存在");

        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        selfConflicts(candidates, offeringId, conflicts, seen);

        Map<Long, List<String>> studentsByOffering = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (!teacherConflictDAO.isTeachingDate(connection, candidate.calendarDateId(),
                    plan.calendarId())) {
                add(conflicts, seen, slotInvalid(offeringId, candidate, "目标日期不在教学日历范围内"));
                continue;
            }
            AdminScheduleDAO.Window window = calendar.window(candidate.week(),
                    candidate.teachingWeekday(), candidate.startPeriod(), candidate.endPeriod());
            if (window == null) {
                add(conflicts, seen, slotInvalid(offeringId, candidate, "新时间段不在教学日历范围内"));
                continue;
            }
            Integer roomCapacity = candidate.classroomId() == null ? null
                    : scheduleDAO.classroomCapacity(connection, candidate.classroomId());
            if (roomCapacity != null && roomCapacity < offering.capacity()) {
                add(conflicts, seen, new ScheduleConflictDTO(CourseConflictService.CLASSROOM_CAPACITY,
                        OVERRIDABLE, Long.toString(candidate.classroomId()), null, candidate.week(),
                        candidate.teachingWeekday(), candidate.startPeriod(), candidate.endPeriod(),
                        "教室容量 " + roomCapacity + " 小于教学班容量 " + offering.capacity()));
            }
            for (AdminScheduleConflictDAO.EffectiveOccurrence other : conflictDAO
                    .overlappingExcludingOccurrences(connection, planId, window.start(),
                            window.end(), excluded)) {
                classify(offeringId, candidate, other, conflicts, seen);
                if (other.offeringId() == offeringId) continue;
                List<String> students = studentsByOffering.get(other.offeringId());
                if (students == null) {
                    students = teacherConflictDAO.sharedNormalStudents(connection, offeringId,
                            other.offeringId());
                    studentsByOffering.put(other.offeringId(), students);
                }
                for (String uid : students) {
                    add(conflicts, seen, new ScheduleConflictDTO(STUDENT_SCHEDULE, OVERRIDABLE, uid,
                            Long.toString(other.offeringId()), candidate.week(),
                            candidate.teachingWeekday(), candidate.startPeriod(),
                            candidate.endPeriod(), "本班学生在该时间已有其他有效课程"));
                }
            }
        }
        return List.copyOf(conflicts);
    }

    /**
     * 候选之间的相互占用：同一目标日期出现两次，或两个目标的 UTC 窗口实际重叠（[start,end)，
     * 首尾相接不算），都让整张申请 BLOCKING。候选窗口重叠用调用方给出的 instants 判定，与资源
     * 查询用的教学日历窗口无关。
     */
    private static void selfConflicts(List<Candidate> candidates, long offeringId,
            List<ScheduleConflictDTO> conflicts, Set<String> seen) {
        String offering = Long.toString(offeringId);
        for (int first = 0; first < candidates.size(); first++) {
            for (int second = first + 1; second < candidates.size(); second++) {
                Candidate one = candidates.get(first);
                Candidate other = candidates.get(second);
                if (one.calendarDateId() == other.calendarDateId()) {
                    add(conflicts, seen, conflict(CourseConflictService.OFFERING_OVERLAP, BLOCKING,
                            offering, offering, one, "同一申请重复指定相同的目标日期"));
                } else if (overlaps(one, other)) {
                    add(conflicts, seen, conflict(CourseConflictService.OFFERING_OVERLAP, BLOCKING,
                            offering, offering, one, "同一申请内多个目标的新时间段相互重叠"));
                }
            }
        }
    }

    private static boolean overlaps(Candidate one, Candidate other) {
        return one.startAt().isBefore(other.endAt()) && other.startAt().isBefore(one.endAt());
    }

    /**
     * 一个有效课次对一个候选的资源占用。教师/助教是同一人员资源：候选教师撞上对方的助教、候选
     * 助教撞上对方的教师都算冲突，但类型与 subject 始终按候选人在本课程里的角色报告。本教学班
     * 自己的课次是 BLOCKING，其他教学班的教师/助教/教室冲突是管理员既有分类的 OVERRIDABLE。
     */
    private static void classify(long offeringId, Candidate candidate,
            AdminScheduleConflictDAO.EffectiveOccurrence other, List<ScheduleConflictDTO> conflicts,
            Set<String> seen) {
        String related = Long.toString(other.offeringId());
        if (other.offeringId() == offeringId) {
            add(conflicts, seen, conflict(CourseConflictService.OFFERING_OVERLAP, BLOCKING, related,
                    related, candidate, "同一教学班在该时间已有排课"));
        }
        if (candidate.teacherUid() != null
                && (candidate.teacherUid().equals(other.teacherUid())
                    || candidate.teacherUid().equals(other.assistantUid()))) {
            add(conflicts, seen, conflict(CourseConflictService.TEACHER_OVERLAP, OVERRIDABLE,
                    candidate.teacherUid(), related, candidate, "任课教师在该时间已有其他课程"));
        }
        if (candidate.assistantUid() != null
                && (candidate.assistantUid().equals(other.teacherUid())
                    || candidate.assistantUid().equals(other.assistantUid()))) {
            add(conflicts, seen, conflict(CourseConflictService.ASSISTANT_OVERLAP, OVERRIDABLE,
                    candidate.assistantUid(), related, candidate, "助教在该时间已有其他课程"));
        }
        if (candidate.classroomId() != null && candidate.classroomId().equals(other.classroomId())) {
            add(conflicts, seen, conflict(CourseConflictService.CLASSROOM_OVERLAP, OVERRIDABLE,
                    Long.toString(candidate.classroomId()), related, candidate,
                    "教室在该时间已被其他课程占用"));
        }
    }

    private static ScheduleConflictDTO slotInvalid(long offeringId, Candidate candidate,
                                                   String message) {
        String offering = Long.toString(offeringId);
        return conflict(SLOT_INVALID, BLOCKING, offering, null, candidate, message);
    }

    private static ScheduleConflictDTO conflict(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, Candidate candidate, String message) {
        return new ScheduleConflictDTO(type, severity, subjectId, relatedOfferingId,
                candidate.week(), candidate.teachingWeekday(), candidate.startPeriod(),
                candidate.endPeriod(), message);
    }

    /** 与管理员既有检查相同的去重键，保证同一窗口的同一原因只报告一次。 */
    private static void add(List<ScheduleConflictDTO> conflicts, Set<String> seen,
                            ScheduleConflictDTO conflict) {
        String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
                + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId();
        if (seen.add(key)) conflicts.add(conflict);
    }
}
