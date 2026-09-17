package service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dao.AdminCourseOperationDAO;
import dao.AdminScheduleConflictDAO;
import dao.AdminScheduleDAO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.SchedulePlanDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;
import dto.course.admin.schedule.SaveArrangementRequestDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;
import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.OVERRIDABLE;

/**
 * Draft scheduling mutations. Each write runs in one READ_COMMITTED transaction that replays the
 * stored result for a repeated operation identity, locks the aggregate, validates structure and
 * referenced objects, rebuilds the arrangement's normalized children, recalculates conflicts from
 * the authoritative transactional state, and records the audit row.
 */
public class ScheduleManagementService {
    private static final Gson GSON = new Gson();
    private static final Type ARRANGEMENT_RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<ScheduleArrangementDTO>>() { }.getType();
    private static final Type VOID_RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<Void>>() { }.getType();
    private static final Type PLAN_RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<SchedulePlanDTO>>() { }.getType();

    private static final String ARRANGEMENT_TARGET = "ARRANGEMENT";
    private static final String PLAN_TARGET = "SCHEDULE_PLAN";
    private static final String OK = "OK";
    private static final String DRAFT = "DRAFT";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String TEACHER_RESOURCE = "teacher";
    private static final String CLASSROOM_RESOURCE = "classroom";
    private static final String TEACHER_ROLE = "TEACHER";
    private static final String ASSISTANT_ROLE = "ASSISTANT";
    private static final String CLASSROOM_ROLE = "CLASSROOM";
    private static final int CANCELLED_OFFERING = 4;
    private static final String ACTIVE_COURSE = "ACTIVE";

    private final AdminScheduleDAO scheduleDAO;
    private final AdminScheduleConflictDAO conflictDAO;
    private final AdminCourseOperationDAO operationDAO;
    private final CourseConflictService conflicts;
    private final java.time.Clock clock;

    public ScheduleManagementService() {
        this(new AdminScheduleDAO(), new AdminScheduleConflictDAO(), java.time.Clock.systemUTC());
    }

    ScheduleManagementService(AdminScheduleDAO scheduleDAO, AdminScheduleConflictDAO conflictDAO,
                              java.time.Clock clock) {
        this.scheduleDAO = scheduleDAO;
        this.conflictDAO = conflictDAO;
        this.operationDAO = new AdminCourseOperationDAO();
        this.conflicts = new CourseConflictService(scheduleDAO, conflictDAO);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ reads

    public List<ScheduleResourceDTO> listResources(String type, String query) {
        if (type != null && !TEACHER_RESOURCE.equals(type) && !CLASSROOM_RESOURCE.equals(type)) {
            throw new IllegalArgumentException("未知的排课资源类型");
        }
        try (Connection connection = DBUtil.getConnection()) {
            return scheduleDAO.listResources(connection, type, query);
        } catch (SQLException failure) {
            throw new DatabaseException("排课资源查询失败", failure);
        }
    }

    public SchedulePlanDTO loadPlan(int academicYear, int semester) {
        if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
        try (Connection connection = DBUtil.getConnection()) {
            AdminScheduleDAO.PlanRow plan =
                    scheduleDAO.findPlanByTerm(connection, academicYear, semester);
            if (plan == null) throw new NotFoundException("该学期尚未创建排课方案");
            return planDTO(connection, plan, conflicts.checkPlan(connection, plan.planId()));
        } catch (SQLException failure) {
            throw new DatabaseException("排课方案加载失败", failure);
        }
    }

    public List<ScheduleArrangementDTO> listArrangements(String planId, String offeringId) {
        long plan = AdminOperationTransaction.parseId(planId, "planId");
        String offering = AdminOperationTransaction.blankToNull(offeringId);
        try (Connection connection = DBUtil.getConnection()) {
            if (scheduleDAO.findPlan(connection, plan) == null) {
                throw new NotFoundException("排课方案不存在");
            }
            return scheduleDAO.listArrangements(connection, plan,
                    offering == null ? null : AdminOperationTransaction.parseId(offering, "offeringId"));
        } catch (SQLException failure) {
            throw new DatabaseException("教学安排查询失败", failure);
        }
    }

    /** 表单级预检查：返回前把跨周的同一冲突合并为区间，界面上不再按周刷屏。 */
    public List<ScheduleConflictDTO> checkArrangement(SaveArrangementRequestDTO request) {
        CourseConflictService.Candidate candidate = candidate(request);
        try (Connection connection = DBUtil.getConnection()) {
            validateReferences(connection, candidate);
            return CourseConflictService.mergeWeekRanges(conflicts.check(connection, candidate));
        } catch (SQLException failure) {
            throw new DatabaseException("排课冲突检查失败", failure);
        }
    }

    // ----------------------------------------------------------------- writes

    public AdminOperationResultDTO<ScheduleArrangementDTO> save(String adminUid,
                                                                SaveArrangementRequestDTO request) {
        CourseConflictService.Candidate candidate = candidate(request);
        String operationId = request.getOperationId();
        String reason = AdminOperationTransaction.blankToNull(request.getOverrideReason());
        if (request.isForce() && reason == null) {
            throw new IllegalArgumentException("强制保存必须填写原因");
        }
        Long arrangementId = candidate.arrangementId();
        int expectedVersion = request.getExpectedVersion();
        if (arrangementId != null) AdminOperationTransaction.version(expectedVersion);
        Lock lock = connection -> {
            scheduleDAO.lockPlan(connection, candidate.planId());
            if (arrangementId != null) scheduleDAO.lockArrangement(connection, arrangementId);
        };
        Mutation<ScheduleArrangementDTO> mutation = connection -> {
            AdminScheduleDAO.PlanRow plan = requireDraftPlan(connection, candidate.planId());
            AdminScheduleDAO.CalendarContext calendar =
                    scheduleDAO.loadCalendar(connection, plan.calendarId());
            if (calendar == null) throw new NotFoundException("教学日历不存在");
            ScheduleArrangementDTO previous = null;
            if (arrangementId != null) {
                previous = requireEditable(connection, arrangementId, plan.planId(), expectedVersion);
            }
            validateReferences(connection, candidate);
            requireSlotWindows(calendar, candidate);

            // course_schedule_rule carries a unique slot identity, so a blocking self-overlap must
            // be rejected with a typed conflict before the row is written rather than surfaced as
            // a raw duplicate-key failure.
            List<ScheduleConflictDTO> preWrite = conflicts.check(connection, candidate);
            for (ScheduleConflictDTO conflict : preWrite) {
                if (BLOCKING == conflict.getSeverity()) {
                    throw new ConflictException("存在阻断性冲突，无法保存", previous, preWrite);
                }
            }
            long id;
            if (arrangementId == null) {
                id = scheduleDAO.insertArrangement(connection, plan.planId(),
                        candidate.offeringId(), candidate.teacherUid(), candidate.assistantUid(),
                        candidate.classroomId(), adminUid);
            } else {
                id = arrangementId;
                if (scheduleDAO.hasAdjustmentHistory(connection, id)) {
                    throw new ConflictException("教学安排存在调课记录，无法改写", previous);
                }
                scheduleDAO.deleteChildren(connection, id);
                int affected = scheduleDAO.updateArrangement(connection, id, expectedVersion,
                        candidate.teacherUid(), candidate.assistantUid(), candidate.classroomId());
                if (affected == 0) {
                    throw new ConflictException("教学安排版本已变化，请刷新后重试",
                            scheduleDAO.findArrangement(connection, id));
                }
            }
            writeChildren(connection, id, plan.planId(), candidate, calendar);
            List<ScheduleConflictDTO> found = conflicts.check(connection,
                    new CourseConflictService.Candidate(candidate.planId(), id,
                            candidate.offeringId(), candidate.teacherUid(),
                            candidate.assistantUid(), candidate.classroomId(), candidate.slots(),
                            candidate.startWeek(), candidate.endWeek()));
            for (ScheduleConflictDTO conflict : found) {
                if (BLOCKING == conflict.getSeverity()) {
                    throw new ConflictException("存在阻断性冲突，无法保存", previous, found);
                }
            }
            if (!request.isForce()) {
                for (ScheduleConflictDTO conflict : found) {
                    if (OVERRIDABLE == conflict.getSeverity()) {
                        throw new ConflictException("存在可绕过冲突，请确认后强制保存", previous, found);
                    }
                }
            }
            ScheduleArrangementDTO entity = scheduleDAO.findArrangement(connection, id);
            return new Outcome<>(new AdminOperationResultDTO<>(operationId, OK, "教学安排已保存",
                    entity, found), ARRANGEMENT_TARGET, Long.toString(id), found, request.isForce(),
                    reason);
        };
        return execute(adminUid, operationId, AdminCourseActions.SAVE_ARRANGEMENT, request,
                ARRANGEMENT_RESULT_TYPE, lock, mutation);
    }

    public AdminOperationResultDTO<Void> delete(String adminUid, String arrangementId,
                                                int expectedVersion, String operationId) {
        long id = AdminOperationTransaction.parseId(arrangementId, "arrangementId");
        int version = AdminOperationTransaction.version(expectedVersion);
        Lock lock = connection -> {
            AdminScheduleDAO.ArrangementRow row = scheduleDAO.arrangementRow(connection, id);
            // Same plan-then-arrangement order as save, so two administrators editing and deleting
            // in one plan can never form a lock cycle. A row already gone is left to the mutation,
            // so a completed delete still resolves as a replay instead of a not-found.
            if (row != null) {
                scheduleDAO.lockPlan(connection, row.planId());
                scheduleDAO.lockArrangement(connection, id);
            }
        };
        Mutation<Void> mutation = connection -> {
            AdminScheduleDAO.ArrangementRow row = scheduleDAO.arrangementRow(connection, id);
            if (row == null) throw new NotFoundException("教学安排不存在");
            AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, row.planId());
            if (plan == null || !DRAFT.equals(plan.status())) {
                throw new ConflictException("只有草稿方案的教学安排可以删除",
                        scheduleDAO.findArrangement(connection, id));
            }
            if (row.version() != version) {
                throw new ConflictException("教学安排版本已变化，请刷新后重试",
                        scheduleDAO.findArrangement(connection, id));
            }
            if (scheduleDAO.hasAdjustmentHistory(connection, id)) {
                throw new ConflictException("教学安排存在调课记录，无法删除",
                        scheduleDAO.findArrangement(connection, id));
            }
            scheduleDAO.deleteArrangement(connection, id);
            return new Outcome<Void>(new AdminOperationResultDTO<>(operationId, OK, "教学安排已删除",
                    null, List.of()), ARRANGEMENT_TARGET, Long.toString(id), List.of(), false, null);
        };
        return execute(adminUid, operationId, AdminCourseActions.DELETE_ARRANGEMENT,
                AdminOperationTransaction.targetRequest(id, version), VOID_RESULT_TYPE, lock,
                mutation);
    }

    public AdminOperationResultDTO<SchedulePlanDTO> publish(String adminUid, String planId,
                                                            int expectedRevision, String operationId,
                                                            boolean force, String overrideReason) {
        long id = AdminOperationTransaction.parseId(planId, "planId");
        int revision = AdminOperationTransaction.version(expectedRevision);
        String reason = AdminOperationTransaction.blankToNull(overrideReason);
        if (force && reason == null) throw new IllegalArgumentException("强制发布必须填写原因");
        Lock lock = connection -> {
            scheduleDAO.lockPlan(connection, id);
            AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, id);
            if (plan != null) scheduleDAO.lockCalendar(connection, plan.calendarId());
        };
        Mutation<SchedulePlanDTO> mutation = connection -> {
            AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, id);
            if (plan == null) throw new NotFoundException("排课方案不存在");
            if (plan.revision() != revision) {
                throw new ConflictException("方案修订号已变化，请刷新后重试",
                        planDTO(connection, plan, List.of()));
            }
            if (!DRAFT.equals(plan.status())) {
                throw new ConflictException("只有草稿方案可以发布",
                        planDTO(connection, plan, List.of()));
            }
            if (scheduleDAO.hasOpenSelectionWindowForOtherPlan(connection, plan.calendarId(), id)) {
                throw new ConflictException("选课窗口开放期间不能切换排课方案",
                        planDTO(connection, plan, List.of()));
            }
            conflicts.requirePublishable(connection, id);
            List<ScheduleConflictDTO> found = conflicts.checkPlan(connection, id);
            for (ScheduleConflictDTO conflict : found) {
                if (BLOCKING == conflict.getSeverity()) {
                    throw new ConflictException("存在阻断性冲突，无法发布",
                            planDTO(connection, plan, found), found);
                }
            }
            if (!force && !found.isEmpty()) {
                throw new ConflictException("存在可绕过冲突，请确认后强制发布",
                        planDTO(connection, plan, found), found);
            }
            scheduleDAO.markReady(connection, id);
            scheduleDAO.markPublished(connection, id, adminUid, clock.instant());
            scheduleDAO.rebuildOfferingConflicts(connection, id);
            scheduleDAO.updateCalendarPointer(connection, plan.calendarId(), id);
            AdminScheduleDAO.PlanRow published = scheduleDAO.findPlan(connection, id);
            return new Outcome<>(new AdminOperationResultDTO<>(operationId, OK, "排课方案已发布",
                    planDTO(connection, published, found), found), PLAN_TARGET, Long.toString(id),
                    found, force, reason);
        };
        return execute(adminUid, operationId, AdminCourseActions.PUBLISH_SCHEDULE_PLAN,
                AdminOperationTransaction.targetRequest(id, revision), PLAN_RESULT_TYPE, lock,
                mutation);
    }

    /**
     * Opens the editable draft of a term, optionally seeding it from the term's current published
     * plan. This is the only way a DRAFT plan can come into existence — every other scheduling
     * write requires one to exist already.
     */
    public AdminOperationResultDTO<SchedulePlanDTO> createDraftPlan(String adminUid, int academicYear,
                                                                    int semester, boolean copyPublished,
                                                                    String operationId) {
        if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
        Lock lock = connection -> {
            Long calendarId = scheduleDAO.findCalendarIdByTerm(connection, academicYear, semester);
            if (calendarId != null) scheduleDAO.lockCalendar(connection, calendarId);
        };
        Mutation<SchedulePlanDTO> mutation = connection -> {
            Long calendarId = scheduleDAO.findCalendarIdByTerm(connection, academicYear, semester);
            if (calendarId == null) throw new NotFoundException("该学期尚未创建教学日历");
            if (scheduleDAO.findDraftPlanId(connection, calendarId) != null) {
                throw new ConflictException("该学期已有草稿方案");
            }
            AdminScheduleDAO.CalendarContext calendar = scheduleDAO.loadCalendar(connection, calendarId);
            if (calendar == null) throw new NotFoundException("教学日历不存在");
            // Name is the term itself, so nextRevision lands on 1 the first time and the
            // (calendar_id,name,revision) unique key can never collide.
            String name = academicYear + "-" + (academicYear + 1) + " 学年"
                    + switch (semester) {
                        case 1 -> "第一学期";
                        case 2 -> "第二学期";
                        default -> "第三学期";
                    } + "排课方案";
            int revision = scheduleDAO.nextRevision(connection, calendarId, name);
            long planId = scheduleDAO.insertPlan(connection, name, calendarId, revision, adminUid);
            int copied = 0;
            int skipped = 0;
            if (copyPublished) {
                Long sourcePlanId = scheduleDAO.findPublishedPlanId(connection, calendarId);
                if (sourcePlanId != null) {
                    List<ScheduleArrangementDTO> source =
                            scheduleDAO.listArrangements(connection, sourcePlanId, null);
                    copied = copyArrangements(connection, sourcePlanId, source, planId, calendar,
                            adminUid);
                    // The copy loop has exactly two outcomes per source row — copied or skipped —
                    // so the remainder is the skip count the result message reports.
                    skipped = source.size() - copied;
                }
            }
            AdminScheduleDAO.PlanRow created = scheduleDAO.findPlan(connection, planId);
            // 复制/跳过条数随结果消息回到界面：管理员看不到「复制了 0 条」正是空草稿被静默的原因。
            return new Outcome<>(new AdminOperationResultDTO<>(operationId, OK,
                    "草稿方案已创建：已复制 " + copied + " 条 / 跳过 " + skipped + " 条",
                    planDTO(connection, created, List.of()), List.of()), PLAN_TARGET,
                    Long.toString(planId), List.of(), false, null);
        };
        return execute(adminUid, operationId, AdminCourseActions.CREATE_SCHEDULE_PLAN,
                AdminOperationTransaction.termRequest(academicYear, semester, copyPublished),
                PLAN_RESULT_TYPE, lock, mutation);
    }

    /**
     * Copies every arrangement of {@code source} that can be carried over into {@code targetPlanId}
     * by re-running the same child writer {@code save} uses, so the copied {@code course_occurrence}
     * UTC windows are derived from the calendar exactly as a hand-edited arrangement would be.
     * Returns the number of rows copied; the caller reports the remainder of {@code source} as
     * skipped.
     *
     * <p>Rows that cannot form a candidate — no teacher, or no slots — are skipped rather than
     * fatal. The demo seed's arrangement 4104 is deliberately teacher-less, and
     * {@code writeChildren} would hand a null business id to {@code ensureResource}. The skip also
     * keeps this consistent with the read path, which now tolerates exactly the same rows.
     *
     * <p>Two more shapes are skipped instead of failing the whole copy. A null {@code classroom_id}
     * is a legal historical row ({@code V004} declares the column nullable) that
     * {@code writeChildren} would unbox while naming the classroom resource. And a row whose weeks
     * or periods have no window in this term's teaching calendar would be dereferenced as null
     * there; {@code save} refuses such rows up front through {@link #requireSlotWindows}, and this
     * is the only other writer of child rows, so it vets them the same way and skips what fails.
     * Skipping happens before the parent row is inserted, so a skipped row leaves nothing behind.
     *
     * <p>{@code writeChildren} reads only offeringId/teacherUid/assistantUid/classroomId/slots/
     * startWeek/endWeek off the candidate — never {@code arrangementId()}, which it takes as its own
     * parameter. Reusing the source row's candidate is therefore safe; do not "fix" it by passing the
     * source arrangement id into {@code writeChildren}.
     */
    private int copyArrangements(Connection connection, long sourcePlanId,
                                 List<ScheduleArrangementDTO> source, long targetPlanId,
                                 AdminScheduleDAO.CalendarContext calendar, String adminUid)
            throws SQLException {
        int copied = 0;
        for (ScheduleArrangementDTO arrangement : source) {
            CourseConflictService.Candidate candidate =
                    CourseConflictService.candidate(sourcePlanId, arrangement);
            if (candidate == null || candidate.classroomId() == null) continue;
            if (!hasSlotWindows(calendar, candidate)) continue;
            long id = scheduleDAO.insertArrangement(connection, targetPlanId, candidate.offeringId(),
                    candidate.teacherUid(), candidate.assistantUid(), candidate.classroomId(),
                    adminUid);
            writeChildren(connection, id, targetPlanId, candidate, calendar);
            copied++;
        }
        return copied;
    }

    // -------------------------------------------------------------- validation

    private CourseConflictService.Candidate candidate(SaveArrangementRequestDTO request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        long planId = AdminOperationTransaction.parseId(request.getPlanId(), "planId");
        long offeringId = AdminOperationTransaction.parseId(request.getOfferingId(), "offeringId");
        long classroomId = AdminOperationTransaction.parseId(request.getClassroomId(), "classroomId");
        String teacherUid = AdminOperationTransaction.requireText(request.getTeacherUid(), "任课教师");
        String assistantUid = AdminOperationTransaction.blankToNull(request.getAssistantUid());
        if (teacherUid.equals(assistantUid)) {
            throw new IllegalArgumentException("助教不能与任课教师相同");
        }
        String arrangement = AdminOperationTransaction.blankToNull(request.getArrangementId());
        Long arrangementId = arrangement == null ? null
                : AdminOperationTransaction.parseId(arrangement, "arrangementId");
        List<ScheduleSlotDTO> slots = slots(request.getSlots());
        requireWeeks(request.getStartWeek(), request.getEndWeek());
        return new CourseConflictService.Candidate(planId, arrangementId, offeringId, teacherUid,
                assistantUid, classroomId, slots, request.getStartWeek(), request.getEndWeek());
    }

    private static List<ScheduleSlotDTO> slots(List<ScheduleSlotDTO> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new IllegalArgumentException("时间段不能为空");
        }
        List<ScheduleSlotDTO> slots = new ArrayList<>(requested);
        for (ScheduleSlotDTO slot : slots) {
            if (slot == null) throw new IllegalArgumentException("时间段不能为空");
            if (slot.getDayOfWeek() < 1 || slot.getDayOfWeek() > 7) {
                throw new IllegalArgumentException("上课星期必须在 1-7 之间");
            }
            if (slot.getStartPeriod() < 1) throw new IllegalArgumentException("起始节次必须为正");
            if (slot.getEndPeriod() < slot.getStartPeriod()) {
                throw new IllegalArgumentException("结束节次不能早于起始节次");
            }
        }
        slots.sort(Comparator.comparingInt(ScheduleSlotDTO::getDayOfWeek)
                .thenComparingInt(ScheduleSlotDTO::getStartPeriod)
                .thenComparingInt(ScheduleSlotDTO::getEndPeriod));
        for (int index = 1; index < slots.size(); index++) {
            ScheduleSlotDTO previous = slots.get(index - 1);
            ScheduleSlotDTO current = slots.get(index);
            if (previous.getDayOfWeek() == current.getDayOfWeek()) {
                if (previous.getStartPeriod() == current.getStartPeriod()
                        && previous.getEndPeriod() == current.getEndPeriod()) {
                    throw new IllegalArgumentException("时间段不能重复");
                }
                if (current.getStartPeriod() <= previous.getEndPeriod()) {
                    throw new IllegalArgumentException("同一教学安排的时间段不能重叠");
                }
            }
        }
        return List.copyOf(slots);
    }

    private static void requireWeeks(int startWeek, int endWeek) {
        if (startWeek < 1) throw new IllegalArgumentException("起始周次必须为正");
        if (endWeek < startWeek) throw new IllegalArgumentException("结束周次不能早于起始周次");
    }

    private static void requireSlotWindows(AdminScheduleDAO.CalendarContext calendar,
                                           CourseConflictService.Candidate candidate) {
        if (!hasSlotWindows(calendar, candidate)) {
            throw new IllegalArgumentException("周次或节次超出教学日历范围");
        }
    }

    /**
     * The single predicate behind {@link #requireSlotWindows}: true when every week and slot of the
     * candidate resolves to a window in the calendar. {@code save} refuses the candidate when it is
     * false, the copy path skips the row instead, and only a candidate that passes reaches the
     * windows {@link #writeChildren} dereferences.
     */
    private static boolean hasSlotWindows(AdminScheduleDAO.CalendarContext calendar,
                                          CourseConflictService.Candidate candidate) {
        for (ScheduleSlotDTO slot : candidate.slots()) {
            for (int week = candidate.startWeek(); week <= candidate.endWeek(); week++) {
                if (calendar.window(week, slot.getDayOfWeek(), slot.getStartPeriod(),
                        slot.getEndPeriod()) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    private AdminScheduleDAO.PlanRow requireDraftPlan(Connection connection, long planId)
            throws SQLException {
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, planId);
        if (plan == null) throw new NotFoundException("排课方案不存在");
        if (!DRAFT.equals(plan.status())) throw new ConflictException("只有草稿方案可以编辑");
        return plan;
    }

    private ScheduleArrangementDTO requireEditable(Connection connection, long arrangementId,
                                                   long planId, int expectedVersion)
            throws SQLException {
        AdminScheduleDAO.ArrangementRow row = scheduleDAO.arrangementRow(connection, arrangementId);
        if (row == null) throw new NotFoundException("教学安排不存在");
        if (row.planId() != planId) throw new ConflictException("教学安排不属于该排课方案");
        if (row.version() != expectedVersion) {
            throw new ConflictException("教学安排版本已变化，请刷新后重试",
                    scheduleDAO.findArrangement(connection, arrangementId));
        }
        return scheduleDAO.findArrangement(connection, arrangementId);
    }

    private void requireOffering(Connection connection, long offeringId) throws SQLException {
        AdminScheduleDAO.OfferingState offering = scheduleDAO.offeringState(connection, offeringId);
        if (offering == null) throw new NotFoundException("教学班不存在");
        if (!ACTIVE_COURSE.equals(offering.courseStatus())) {
            throw new ConflictException("课程已归档，无法排课");
        }
        if (offering.status() == CANCELLED_OFFERING) {
            throw new ConflictException("教学班已取消，无法排课");
        }
    }

    private void validateReferences(Connection connection,
                                    CourseConflictService.Candidate candidate) throws SQLException {
        requireOffering(connection, candidate.offeringId());
        if (scheduleDAO.classroomCapacity(connection, candidate.classroomId()) == null) {
            throw new NotFoundException("教室不存在");
        }
        requireTeacher(connection, candidate.teacherUid(), "任课教师");
        requireTeacher(connection, candidate.assistantUid(), "助教");
    }

    private void requireTeacher(Connection connection, String uid, String label)
            throws SQLException {
        if (uid == null) return;
        if (!scheduleDAO.isTeacher(connection, uid)) {
            throw new IllegalArgumentException(label + "不存在或不是教师");
        }
    }

    /**
     * Rebuilds the arrangement's normalized children. Callers must have vetted the candidate's slot
     * windows first — {@code save} through {@link #requireSlotWindows}, the copy path through
     * {@link #hasSlotWindows} — so every window dereferenced below is known non-null.
     */
    private void writeChildren(Connection connection, long arrangementId, long planId,
                               CourseConflictService.Candidate candidate,
                               AdminScheduleDAO.CalendarContext calendar) throws SQLException {
        long teacherResource = scheduleDAO.ensureResource(connection, TEACHER_RESOURCE,
                candidate.teacherUid());
        Long assistantResource = candidate.assistantUid() == null ? null
                : scheduleDAO.ensureResource(connection, TEACHER_RESOURCE,
                        candidate.assistantUid());
        long classroomResource = scheduleDAO.ensureResource(connection, CLASSROOM_RESOURCE,
                Long.toString(candidate.classroomId()));
        for (ScheduleSlotDTO slot : candidate.slots()) {
            long ruleId = scheduleDAO.insertRule(connection, planId, candidate.offeringId(),
                    arrangementId, slot);
            for (int week = candidate.startWeek(); week <= candidate.endWeek(); week++) {
                scheduleDAO.insertRuleWeek(connection, ruleId, week);
                AdminScheduleDAO.Window window = calendar.window(week, slot.getDayOfWeek(),
                        slot.getStartPeriod(), slot.getEndPeriod());
                long occurrenceId = scheduleDAO.insertOccurrence(connection, ruleId, planId,
                        window.start(), window.end(), week, slot.getDayOfWeek());
                scheduleDAO.insertBooking(connection, planId, occurrenceId, teacherResource,
                        TEACHER_ROLE);
                if (assistantResource != null) {
                    scheduleDAO.insertBooking(connection, planId, occurrenceId, assistantResource,
                            ASSISTANT_ROLE);
                }
                scheduleDAO.insertBooking(connection, planId, occurrenceId, classroomResource,
                        CLASSROOM_ROLE);
            }
        }
    }

    private static SchedulePlanDTO planDTO(Connection connection, AdminScheduleDAO.PlanRow plan,
                                           List<ScheduleConflictDTO> found) {
        return new SchedulePlanDTO(Long.toString(plan.planId()), plan.name(), plan.revision(),
                plan.status(), plan.currentPlanId() != null && plan.currentPlanId() == plan.planId(),
                found);
    }

    // --------------------------------------------------------------- transaction

    private <T> AdminOperationResultDTO<T> execute(String adminUid, String operationId, String action,
                                                   Object request, Type resultType, Lock lock,
                                                   Mutation<T> mutation) {
        AdminOperationTransaction.validate(adminUid, operationId);
        String digest = operationDAO.digest(action, request);
        String requestJson = GSON.toJson(request);
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                lock.apply(connection);
                AdminCourseOperationDAO.StoredOperation stored =
                        operationDAO.find(connection, adminUid, operationId);
                AdminOperationResultDTO<T> result;
                if (stored != null) {
                    if (!digest.equals(stored.requestDigest())) {
                        throw new ConflictException("operationId 已用于不同的业务请求");
                    }
                    result = operationDAO.decode(stored.responseJson(), resultType);
                } else {
                    Outcome<T> outcome = mutation.apply(connection);
                    result = outcome.result();
                    scheduleDAO.insertOperation(connection, adminUid, operationId, action,
                            outcome.targetType(), outcome.targetId(), digest, requestJson,
                            GSON.toJson(outcome.conflicts()), outcome.forced(),
                            outcome.overrideReason(), result.getOutcomeCode(), GSON.toJson(result),
                            clock.instant());
                }
                connection.commit();
                committed = true;
                return result;
            } catch (RuntimeException | SQLException failure) {
                inFlight = failure;
                rollback(connection, failure);
                throw failure;
            } finally {
                if (!committed) {
                    rollback(connection, inFlight);
                }
                restoreAutoCommit(connection, originalAutoCommit, inFlight);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("排课事务执行失败", failure);
        }
    }

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight,
     *  and a failed rollback is swallowed rather than replacing an escaping {@link Error}. */
    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            if (failure != null) failure.addSuppressed(rollbackFailure);
        }
    }

    /** Never replaces an in-flight failure with a connection-cleanup failure. */
    private static void restoreAutoCommit(Connection connection, boolean autoCommit,
                                          Throwable inFlight) {
        try {
            connection.setAutoCommit(autoCommit);
        } catch (SQLException restoration) {
            if (inFlight != null) {
                inFlight.addSuppressed(restoration);
            } else {
                throw new DatabaseException("排课事务执行失败", restoration);
            }
        }
    }

    @FunctionalInterface
    private interface Lock {
        void apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface Mutation<T> {
        Outcome<T> apply(Connection connection) throws SQLException;
    }

    private record Outcome<T>(AdminOperationResultDTO<T> result, String targetType, String targetId,
                              List<ScheduleConflictDTO> conflicts, boolean forced,
                              String overrideReason) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        private final Object entity;
        private final List<ScheduleConflictDTO> conflicts;

        public ConflictException(String message) { this(message, null, List.of()); }

        public ConflictException(String message, Object entity) {
            this(message, entity, List.of());
        }

        public ConflictException(String message, Object entity,
                                 List<ScheduleConflictDTO> conflicts) {
            super(message);
            this.entity = entity;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public Object getEntity() { return entity; }

        public List<ScheduleConflictDTO> getConflicts() { return conflicts; }
    }
}
