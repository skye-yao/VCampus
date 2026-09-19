package service;

import com.google.gson.reflect.TypeToken;
import dao.AdminScheduleDAO;
import dao.ScheduleAdjustmentDAO;
import dao.TeacherAdjustmentDAO;
import dao.TeacherCourseOperationDAO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.teacher.TeacherAdjustmentOptionsDTO;
import dto.course.teacher.TeacherAdjustmentPreviewDTO;
import dto.course.teacher.TeacherAdjustmentTargetInputDTO;
import dto.course.teacher.TeacherAdjustmentWriteDTO;
import dto.course.teacher.TeacherCourseActions;
import dto.course.teacher.TeacherOperationResultDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.WithdrawTeacherAdjustmentRequestDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;

/**
* 教师调课申请的提交、撤销与本人查询。
*
* <p>权限（设计第 4 节与控制器裁定 R11）：教学班 role=0 的任课教师可以对自己教学班的课次申请，
* 或某个课次在有效课表里的实际任课教师（包括只通过当前正式安排出现的教师）只能申请自己实际讲授
* 的课次；助教与无关教师一律 {@link TeacherAccessPolicy.AccessDeniedException}。新安排的教师与
* 助教永远由服务端按原课次快照派生，客户端没有任何字段可以替换。
*
* <p>提交顺序与设计第 7 节一致：规范化/校验 → 查同操作结果 → 锁 offering → 按 ID 升序锁原
* occurrences → 复查归属/正式方案/重复 PENDING 目标 → 共用冲突检查 → 写申请与不可变目标 →
* 写教师操作日志 → commit。教师没有 force：任何冲突都禁止提交，结果 JSON 与摘要一起写入
* {@code teacher_course_operation_log}，同 operationId 同摘要重放，不同摘要返回 CONFLICT。
*/
public class TeacherAdjustmentApplicationService {
    /** 教师操作日志的目标类型；与管理员调课申请同名，便于两侧审计对齐。 */
    public static final String TARGET_TYPE = "ADJUSTMENT_REQUEST";
    private static final String OK = "OK";
    private static final int MAX_TEXT = 500;
    private static final int DUPLICATE_KEY = 1062;
    private static final Type RESULT_TYPE =
            new TypeToken<TeacherOperationResultDTO<AdjustmentRequestDetailDTO>>() { }.getType();
    private static final Comparator<ScheduleConflictDTO> CONFLICT_ORDER =
            Comparator.comparingInt(ScheduleConflictDTO::getWeek)
                    .thenComparing(ScheduleConflictDTO::getType)
                    .thenComparing(ScheduleConflictDTO::getSubjectId,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ScheduleConflictDTO::getRelatedOfferingId,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparingInt(ScheduleConflictDTO::getDayOfWeek)
                    .thenComparingInt(ScheduleConflictDTO::getStartPeriod)
                    .thenComparingInt(ScheduleConflictDTO::getEndPeriod);

    private final TeacherAdjustmentDAO dao;
    private final ScheduleAdjustmentDAO adjustments;
    private final TeacherCourseOperationDAO operations;
    private final ScheduleAdjustmentConflictService conflicts;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of TeacherAdjustmentApplicationService.
    */
    public TeacherAdjustmentApplicationService() {
        this(new TeacherAdjustmentDAO(), new ScheduleAdjustmentDAO(),
                new TeacherCourseOperationDAO(), new ScheduleAdjustmentConflictService(),
                Clock.systemUTC());
    }

    /** 供测试注入固定 {@link Clock} 与可覆写 DAO，从而不依赖运行当天、可验证事务回滚。 */
    public TeacherAdjustmentApplicationService(TeacherAdjustmentDAO dao,
            ScheduleAdjustmentDAO adjustments, TeacherCourseOperationDAO operations,
            ScheduleAdjustmentConflictService conflicts, Clock clock) {
        this.dao = dao;
        this.adjustments = adjustments;
        this.operations = operations;
        this.conflicts = conflicts;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- options

    /**
    * 某个原课次的可选目标域：本人任课教师的原课次所属教学日历的教学日、节次与全部教室资源。
    * 先校验课次归属，再读取同日历的日期域，绝不接受客户端传来的 courseAdmin 资源。
    */
    public TeacherAdjustmentOptionsDTO options(String uid, String offeringId,
                                               String originalOccurrenceId) {
        String teacher = requireUid(uid);
        long offering = AdminOperationTransaction.parseId(offeringId, "offeringId");
        long occurrenceId = AdminOperationTransaction.parseId(originalOccurrenceId,
                "originalOccurrenceId");
        try (Connection connection = DBUtil.getConnection()) {
            if (!dao.isTeacher(connection, teacher)) throw denied("没有该教学班的调课权限");
            TeacherAdjustmentDAO.OfferingCalendar calendar = dao.offeringCalendar(connection, offering);
            if (calendar == null) throw denied("没有该教学班的调课权限");
            requireOccurrenceAccess(connection, teacher, offering, occurrenceId);
            return new TeacherAdjustmentOptionsDTO(Long.toString(calendar.calendarId()),
                    calendar.timezone(), dao.teachingDates(connection, calendar.calendarId()),
                    dao.periods(connection, calendar.calendarId()), dao.classrooms(connection));
        } catch (SQLException failure) {
            throw new DatabaseException("查询调课选项失败", failure);
        }
    }

    // ------------------------------------------------------------------- preview

    /** 纯预检查：不做任何写操作，忽略 operationId，也不要求原因非空（提交才要求）。 */
    public TeacherAdjustmentPreviewDTO preview(String uid, TeacherAdjustmentWriteDTO raw) {
        String teacher = requireUid(uid);
        Normalized request = normalize(teacher, raw, null, false);
        try (Connection connection = DBUtil.getConnection()) {
            List<ScheduleConflictDTO> found = assess(connection, request).conflicts();
            return new TeacherAdjustmentPreviewDTO(found, found.isEmpty());
        } catch (SQLException failure) {
            throw new DatabaseException("调课预检查失败", failure);
        }
    }

    // -------------------------------------------------------------------- submit

    /**
    * Handles the course-management responsibility of submit.
    */
    public TeacherOperationResultDTO<AdjustmentRequestDetailDTO> submit(String uid,
            TeacherAdjustmentWriteDTO raw) {
        String teacher = requireUid(uid);
        AdminOperationTransaction.validate(teacher,
                raw == null ? null : raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        Normalized request = normalize(teacher, raw, operationId, true);
        String action = TeacherCourseActions.SUBMIT_ADJUSTMENT;
        String digest = operations.digest(action, request.write());
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                        submitTransaction(connection, teacher, request, action, digest);
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
            throw new DatabaseException("提交调课申请事务执行失败", failure);
        }
    }

    private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> submitTransaction(
            Connection connection, String teacher, Normalized request, String action, String digest)
            throws SQLException {
        TeacherCourseOperationDAO.StoredOperation stored =
                operations.find(connection, teacher, request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        adjustments.lockOffering(connection, request.offeringId());
        adjustments.lockOccurrencesAscending(connection, request.targetIds());
        // A duplicate request may have committed while this one waited for the locks.
        stored = operations.find(connection, teacher, request.operationId());
        if (stored != null) return replay(stored, digest, request.operationId());

        Assessment assessment = assess(connection, request);
        if (!assessment.conflicts().isEmpty()) {
            throw new ConflictException("存在冲突，无法提交调课申请", null, assessment.conflicts());
        }

        long requestId = dao.insertRequest(connection, request.offeringId(), teacher,
                request.reason(), assessment.newWeekday(), request.newStartPeriod(),
                request.newEndPeriod(), request.newClassroomId(), clock.instant());
        for (ResolvedTarget target : assessment.targets()) {
            TeacherAdjustmentDAO.Occurrence occurrence = target.occurrence();
            dao.insertTarget(connection, requestId, target.calendarDateId(),
                    occurrence.occurrenceId(), occurrence.weekNo(), occurrence.startAt(),
                    occurrence.endAt(), occurrence.teacherUid(), occurrence.assistantUid(),
                    occurrence.classroomId());
        }
        ScheduleAdjustmentDAO.RequestRow row = adjustments.findRequest(connection, requestId);
        List<ScheduleAdjustmentDAO.TargetRow> targets = adjustments.listTargets(connection, requestId);
        AdjustmentRequestDetailDTO entity = detail(connection, row, targets, List.of());
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                new TeacherOperationResultDTO<>(request.operationId(), "调课申请已提交", entity, false);
        return auditOrRecover(connection, teacher, request.operationId(), request.write(), action,
                digest, result, Long.toString(requestId));
    }

    // ------------------------------------------------------------------ withdraw

    /**
    * Handles the course-management responsibility of withdraw.
    */
    public TeacherOperationResultDTO<AdjustmentRequestDetailDTO> withdraw(String uid,
            WithdrawTeacherAdjustmentRequestDTO raw) {
        String teacher = requireUid(uid);
        if (raw == null) throw new IllegalArgumentException("撤销请求不能为空");
        AdminOperationTransaction.validate(teacher, raw.getOperationId());
        String operationId = UUID.fromString(raw.getOperationId().trim()).toString();
        long requestId = AdminOperationTransaction.parseId(raw.getRequestId(), "requestId");
        int expectedVersion = AdminOperationTransaction.version(raw.getExpectedVersion());
        WithdrawTeacherAdjustmentRequestDTO request = new WithdrawTeacherAdjustmentRequestDTO(
                operationId, Long.toString(requestId), expectedVersion);
        String action = TeacherCourseActions.WITHDRAW_ADJUSTMENT;
        String digest = operations.digest(action, request);
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                        withdrawTransaction(connection, teacher, request, action, digest);
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
            throw new DatabaseException("撤销调课申请事务执行失败", failure);
        }
    }

    private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> withdrawTransaction(
            Connection connection, String teacher, WithdrawTeacherAdjustmentRequestDTO request,
            String action, String digest) throws SQLException {
        long requestId = Long.parseLong(request.getRequestId());
        TeacherCourseOperationDAO.StoredOperation stored =
                operations.find(connection, teacher, request.getOperationId());
        if (stored != null) return replay(stored, digest, request.getOperationId());

        int affected = dao.withdraw(connection, requestId, teacher, request.getExpectedVersion(),
                clock.instant());
        if (affected == 1) {
            ScheduleAdjustmentDAO.RequestRow row = adjustments.findRequest(connection, requestId);
            List<ScheduleAdjustmentDAO.TargetRow> targets =
                    adjustments.listTargets(connection, requestId);
            AdjustmentRequestDetailDTO entity = detail(connection, row, targets, List.of());
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result =
                    new TeacherOperationResultDTO<>(request.getOperationId(), "调课申请已撤销",
                            entity, false);
            return auditOrRecover(connection, teacher, request.getOperationId(), request, action,
                    digest, result, Long.toString(requestId));
        }
        // A racing identical operation may have committed while this one waited for the row lock.
        stored = operations.find(connection, teacher, request.getOperationId());
        if (stored != null) return replay(stored, digest, request.getOperationId());
        ScheduleAdjustmentDAO.RequestRow row = adjustments.findRequest(connection, requestId);
        if (row == null || !teacher.equals(row.applicantUid())) {
            throw new NotFoundException("调课申请不存在");
        }
        List<ScheduleAdjustmentDAO.TargetRow> targets = adjustments.listTargets(connection, requestId);
        AdjustmentRequestDetailDTO entity = detail(connection, row, targets, List.of());
        throw new ConflictException(row.status() == AdjustmentRequestStatusDTO.PENDING
                ? "调课申请版本已变化，请刷新后重试" : "调课申请已被处理，请刷新后重试", entity, List.of());
    }

    // ----------------------------------------------------------------- reads

    /** 本人申请详情；别人的申请在这里永远是不可见（NOT_FOUND），不是无权限。 */
    public AdjustmentRequestDetailDTO get(String uid, String requestId) {
        String teacher = requireUid(uid);
        long id = AdminOperationTransaction.parseId(requestId, "requestId");
        try (Connection connection = DBUtil.getConnection()) {
            ScheduleAdjustmentDAO.RequestRow row = adjustments.findRequest(connection, id);
            if (row == null || !teacher.equals(row.applicantUid())) {
                throw new NotFoundException("调课申请不存在");
            }
            List<ScheduleAdjustmentDAO.TargetRow> targets = adjustments.listTargets(connection, id);
            List<ScheduleConflictDTO> found = row.status() == AdjustmentRequestStatusDTO.PENDING
                    ? detailConflicts(connection, row, targets) : List.of();
            return detail(connection, row, targets, found);
        } catch (SQLException failure) {
            throw new DatabaseException("查询调课申请失败", failure);
        }
    }

    /**
    * Lists Mine data.
    */
    public TeacherPageDTO<AdjustmentRequestSummaryDTO> listMine(String uid,
            AdjustmentRequestStatusDTO status, int page, int size) {
        String teacher = requireUid(uid);
        if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("每页条数必须为 1 至 100");
        AdjustmentRequestStatusDTO filter =
                status == null ? AdjustmentRequestStatusDTO.PENDING : status;
        try (Connection connection = DBUtil.getConnection()) {
            long total = dao.countMine(connection, teacher, filter);
            List<AdjustmentRequestSummaryDTO> items =
                    dao.listMine(connection, teacher, filter, (page - 1) * size, size);
            return new TeacherPageDTO<>(items, total, page, size);
        } catch (SQLException failure) {
            throw new DatabaseException("查询我的调课申请失败", failure);
        }
    }

    // ------------------------------------------------------------- assessment

    /**
    * 提交与预检查共用的完整校验：归属、有效课表、时间未过、目标日期与落点、无变化、重复 PENDING
    * 目标，最后用共用冲突检查一次算清资源冲突。可能失败的目标按类型报告而不进入冲突引擎，所以
    * 一条坏目标不会让整次检查抛异常。
    */
    private Assessment assess(Connection connection, Normalized request) throws SQLException {
        if (!dao.isTeacher(connection, request.uid())) throw denied("没有该教学班的调课权限");
        TeacherAdjustmentDAO.OfferingCalendar calendar =
                dao.offeringCalendar(connection, request.offeringId());
        if (calendar == null) throw denied("没有该教学班的调课权限");
        boolean offeringTeacher = dao.isOfferingTeacher(connection, request.offeringId(),
                request.uid());
        var occurrences = dao.findOccurrences(connection, request.targetIds());
        AdminScheduleDAO.CalendarContext context =
                adjustments.loadCalendar(connection, calendar.calendarId());
        if (context == null) throw new IllegalArgumentException("教学日历不存在");

        List<ScheduleConflictDTO> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<ResolvedTarget> resolved = new ArrayList<>();
        List<ScheduleAdjustmentConflictService.Candidate> candidates = new ArrayList<>();
        Instant now = clock.instant();
        int newWeekday = 0;

        for (TargetInput input : request.targets()) {
            TeacherAdjustmentDAO.Occurrence occurrence = occurrences.get(input.occurrenceId());
            if (occurrence == null || occurrence.offeringId() != request.offeringId()) {
                throw denied("没有该课次的调课权限");
            }
            if (!offeringTeacher && !request.uid().equals(occurrence.effectiveTeacherUid())) {
                throw denied("没有该课次的调课权限");
            }
            if (!occurrence.inEffectiveSchedule()) {
                add(found, seen, targetConflict(TeacherAdjustmentApplicationService.TARGET_INVALID,
                        occurrence, request, "调课目标与当前正式课表不一致"));
                continue;
            }
            if (occurrence.adjusted()) {
                add(found, seen, targetConflict(TeacherAdjustmentApplicationService.TARGET_ADJUSTED,
                        occurrence, request, "该课程实例已有生效的调课记录"));
                continue;
            }
            if (!ScheduleAdjustmentDAO.instant(occurrence.startAt()).isAfter(now)) {
                throw new IllegalArgumentException("原课次的开始时间已过去，不能申请调课");
            }
            TeacherAdjustmentDAO.CalendarDateRow date =
                    dao.calendarDate(connection, calendar.calendarId(), input.targetDate());
            if (date == null || !date.teachingDay()) {
                add(found, seen, targetConflict(ScheduleAdjustmentConflictService.SLOT_INVALID,
                        occurrence, request, "目标日期不在教学日历范围内"));
                continue;
            }
            AdminScheduleDAO.Window window = context.window(date.weekNo(), date.teachingWeekday(),
                    request.newStartPeriod(), request.newEndPeriod());
            if (window == null) {
                add(found, seen, targetConflict(ScheduleAdjustmentConflictService.SLOT_INVALID,
                        occurrence, request, "新时间段不在教学日历范围内"));
                continue;
            }
            if (!ScheduleAdjustmentDAO.instant(window.start()).isAfter(now)) {
                throw new IllegalArgumentException("目标开始时间已过去，不能申请调课");
            }
            Long classroom = request.newClassroomId() != null ? request.newClassroomId()
                    : occurrence.classroomId();
            if (date.weekNo() == occurrence.weekNo()
                    && date.teachingWeekday() == occurrence.teachingWeekday()
                    && request.newStartPeriod() == occurrence.startPeriod()
                    && request.newEndPeriod() == occurrence.endPeriod()
                    && Objects.equals(classroom, occurrence.classroomId())) {
                throw new IllegalArgumentException("新日期、节次与教室和原安排相同，无需调课");
            }
            if (newWeekday == 0) {
                newWeekday = date.teachingWeekday();
            } else if (newWeekday != date.teachingWeekday()) {
                throw new IllegalArgumentException("一次申请的目标必须落在同一教学星期");
            }
            resolved.add(new ResolvedTarget(input.occurrenceId(), date.calendarDateId(), occurrence));
            candidates.add(new ScheduleAdjustmentConflictService.Candidate(date.calendarDateId(),
                    date.weekNo(), date.teachingWeekday(), request.newStartPeriod(),
                    request.newEndPeriod(), ScheduleAdjustmentDAO.instant(window.start()),
                    ScheduleAdjustmentDAO.instant(window.end()), occurrence.teacherUid(),
                    occurrence.assistantUid(), classroom));
        }

        for (Long pending : dao.pendingTargets(connection, request.uid(), request.targetIds())) {
            TeacherAdjustmentDAO.Occurrence occurrence = occurrences.get(pending);
            if (occurrence != null) {
                add(found, seen, targetConflict(TeacherAdjustmentApplicationService.TARGET_ADJUSTED,
                        occurrence, request, "该课程实例已有待审批的调课申请"));
            }
        }
        if (!candidates.isEmpty()) {
            for (ScheduleConflictDTO conflict : conflicts.check(connection, request.offeringId(),
                    candidates, new LinkedHashSet<>(request.targetIds()))) {
                add(found, seen, conflict);
            }
        }
        found.sort(CONFLICT_ORDER);
        return new Assessment(List.copyOf(found), List.copyOf(resolved), newWeekday);
    }

    /**
    * 详情页对 PENDING 申请重新计算一次资源冲突；显式目标日期按
    * {@code target_calendar_date_id} 解析（提交时存下的那一天永远优先，方案/日历之后变化也不改写
    * 快照），历史 NULL 行退回 {@code original_week_no + new_weekday}。方案下架、日历缺失等“当前
    * 无法检查”只降级成空快照，绝不让本人详情（含撤销需要的 version）不可读。
    */
    private List<ScheduleConflictDTO> detailConflicts(Connection connection,
            ScheduleAdjustmentDAO.RequestRow row, List<ScheduleAdjustmentDAO.TargetRow> targets)
            throws SQLException {
        if (targets.isEmpty()) return List.of();
        try {
            TeacherAdjustmentDAO.OfferingCalendar calendar =
                    dao.offeringCalendar(connection, row.offeringId());
            if (calendar == null) return List.of();
            Map<Long, AdminScheduleDAO.CalendarContext> contexts = new HashMap<>();
            List<ScheduleAdjustmentConflictService.Candidate> candidates = new ArrayList<>();
            Set<Long> originals = new LinkedHashSet<>();
            for (ScheduleAdjustmentDAO.TargetRow target : targets) {
                originals.add(target.originalOccurrenceId());
                TeacherAdjustmentDAO.CalendarDateRow date = target.targetCalendarDateId() != null
                        ? dao.calendarDateById(connection, target.targetCalendarDateId())
                        : dao.calendarDate(connection, calendar.calendarId(), target.week(),
                                row.newDayOfWeek());
                if (date == null) continue;
                AdminScheduleDAO.CalendarContext context = contexts.get(date.calendarId());
                if (context == null) {
                    context = adjustments.loadCalendar(connection, date.calendarId());
                    if (context == null) continue;
                    contexts.put(date.calendarId(), context);
                }
                AdminScheduleDAO.Window window = context.window(date.weekNo(), date.teachingWeekday(),
                        row.newStartPeriod(), row.newEndPeriod());
                if (window == null) continue;
                String teacher = row.newTeacherUid() != null ? row.newTeacherUid()
                        : target.teacherUid();
                String assistant = row.newAssistantUid() != null ? row.newAssistantUid()
                        : target.assistantUid();
                Long classroom = row.newClassroomId() != null ? row.newClassroomId()
                        : target.classroomId();
                candidates.add(new ScheduleAdjustmentConflictService.Candidate(
                        date.calendarDateId(), date.weekNo(), date.teachingWeekday(),
                        row.newStartPeriod(), row.newEndPeriod(),
                        ScheduleAdjustmentDAO.instant(window.start()),
                        ScheduleAdjustmentDAO.instant(window.end()), teacher, assistant, classroom));
            }
            if (candidates.isEmpty()) return List.of();
            List<ScheduleConflictDTO> sorted = new ArrayList<>(conflicts.check(connection,
                    row.offeringId(), candidates, originals));
            sorted.sort(CONFLICT_ORDER);
            return List.copyOf(sorted);
        } catch (IllegalArgumentException unavailable) {
            // The term currently has no published plan (or its calendar is broken): the request
            // itself is still the teacher's own data, only the conflict snapshot cannot be checked.
            return List.of();
        }
    }

    // ------------------------------------------------------------- validation

    /**
    * Internal course-management type TargetInput.
    */
    private record TargetInput(long occurrenceId, LocalDate targetDate) {
    }

    /**
    * Internal course-management type Normalized.
    */
    private record Normalized(String uid, String operationId, long offeringId,
                              List<TargetInput> targets, int newStartPeriod, int newEndPeriod,
                              Long newClassroomId, String reason, TeacherAdjustmentWriteDTO write) {
        private List<Long> targetIds() {
            List<Long> ids = new ArrayList<>();
            for (TargetInput target : targets) ids.add(target.occurrenceId());
            return ids;
        }
    }

    /**
    * Internal course-management type ResolvedTarget.
    */
    private record ResolvedTarget(long occurrenceId, long calendarDateId,
                                  TeacherAdjustmentDAO.Occurrence occurrence) {
    }

    /**
    * Internal course-management type Assessment.
    */
    private record Assessment(List<ScheduleConflictDTO> conflicts, List<ResolvedTarget> targets,
                              int newWeekday) {
    }

    /**
    * 规范化：ID 转十进制规范形态、日期转 ISO、目标按 occurrenceId 排序、原因去空白，摘要与
    * 幂等键都建立在这份规范化请求上。{@code requireReason} 只有提交为 true。
    */
    private static Normalized normalize(String uid, TeacherAdjustmentWriteDTO raw,
            String operationId, boolean requireReason) {
        if (raw == null) throw new IllegalArgumentException("请求体不能为空");
        long offeringId = AdminOperationTransaction.parseId(raw.getOfferingId(), "offeringId");
        if (raw.getTargets().isEmpty()) throw new IllegalArgumentException("调课目标不能为空");
        int startPeriod = raw.getNewStartPeriod();
        int endPeriod = raw.getNewEndPeriod();
        if (startPeriod <= 0) throw new IllegalArgumentException("newStartPeriod 必须为正整数");
        if (endPeriod < startPeriod) {
            throw new IllegalArgumentException("newEndPeriod 不能小于 newStartPeriod");
        }
        Long classroomId = AdminOperationTransaction.blankToNull(raw.getNewClassroomId()) == null
                ? null : AdminOperationTransaction.parseId(raw.getNewClassroomId(), "newClassroomId");
        String reason = AdminOperationTransaction.blankToNull(raw.getReason());
        if (requireReason && reason == null) throw new IllegalArgumentException("调课原因不能为空");
        if (reason != null && reason.length() > MAX_TEXT) {
            throw new IllegalArgumentException("调课原因不能超过 " + MAX_TEXT + " 字符");
        }

        List<TargetInput> inputs = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (TeacherAdjustmentTargetInputDTO target : raw.getTargets()) {
            long occurrenceId = AdminOperationTransaction.parseId(target.getOriginalOccurrenceId(),
                    "originalOccurrenceId");
            if (!seen.add(occurrenceId)) {
                throw new IllegalArgumentException("同一课次不能在同一申请里重复出现");
            }
            String text = AdminOperationTransaction.blankToNull(target.getTargetDate());
            if (text == null) throw new IllegalArgumentException("targetDate 不能为空");
            LocalDate date;
            try {
                date = LocalDate.parse(text);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("targetDate 必须是 ISO 本地日期");
            }
            inputs.add(new TargetInput(occurrenceId, date));
        }
        inputs.sort(Comparator.comparingLong(TargetInput::occurrenceId));

        List<TeacherAdjustmentTargetInputDTO> canonicalTargets = new ArrayList<>();
        for (TargetInput input : inputs) {
            canonicalTargets.add(new TeacherAdjustmentTargetInputDTO(Long.toString(input.occurrenceId()),
                    input.targetDate().toString()));
        }
        TeacherAdjustmentWriteDTO canonical = new TeacherAdjustmentWriteDTO(operationId,
                Long.toString(offeringId), canonicalTargets, startPeriod, endPeriod,
                classroomId == null ? null : Long.toString(classroomId), reason);
        return new Normalized(uid, operationId, offeringId, List.copyOf(inputs), startPeriod,
                endPeriod, classroomId, reason, canonical);
    }

    // ------------------------------------------------------------- permissions

    private void requireOccurrenceAccess(Connection connection, String teacher, long offeringId,
                                         long occurrenceId) throws SQLException {
        var occurrence = dao.findOccurrences(connection, List.of(occurrenceId))
                .get(occurrenceId);
        if (occurrence == null || occurrence.offeringId() != offeringId) {
            throw denied("没有该课次的调课权限");
        }
        if (dao.isOfferingTeacher(connection, offeringId, teacher)) return;
        if (teacher.equals(occurrence.effectiveTeacherUid())) return;
        throw denied("没有该课次的调课权限");
    }

    private static TeacherAccessPolicy.AccessDeniedException denied(String message) {
        return new TeacherAccessPolicy.AccessDeniedException(message);
    }

    private static String requireUid(String uid) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        return uid.trim();
    }

    // ---------------------------------------------------------------- conflicts

    private static ScheduleConflictDTO targetConflict(String type,
            TeacherAdjustmentDAO.Occurrence occurrence, Normalized request, String message) {
        return new ScheduleConflictDTO(type, BLOCKING, Long.toString(occurrence.occurrenceId()),
                Long.toString(request.offeringId()), occurrence.weekNo(),
                occurrence.teachingWeekday(), occurrence.startPeriod(), occurrence.endPeriod(),
                message);
    }

    private static void add(List<ScheduleConflictDTO> conflicts, Set<String> seen,
                            ScheduleConflictDTO conflict) {
        String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
                + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId();
        if (seen.add(key)) conflicts.add(conflict);
    }

    // ---------------------------------------------------------------- operation log

    /**
    * 操作日志是同一 operationId 的两个并发请求唯一共享的行，也是重复操作被识别的地方：插入撞上
    * 主键时回滚本地写入，读回已提交结果，按摘要重放或返回摘要冲突，而不是把驱动错误抛给客户端。
    */
    private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> auditOrRecover(Connection connection,
            String teacher, String operationId, Object request, String action, String digest,
            TeacherOperationResultDTO<AdjustmentRequestDetailDTO> result, String targetId)
            throws SQLException {
        try {
            operations.insert(connection, teacher, operationId, action, TARGET_TYPE, targetId,
                    digest, operations.json(request), operations.json(result), OK);
            return result;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != DUPLICATE_KEY) throw failure;
            rollback(connection, failure);
            TeacherCourseOperationDAO.StoredOperation winner =
                    operations.find(connection, teacher, operationId);
            if (winner == null) throw failure;
            return replay(winner, digest, operationId);
        }
    }

    private TeacherOperationResultDTO<AdjustmentRequestDetailDTO> replay(
            TeacherCourseOperationDAO.StoredOperation stored, String digest, String operationId) {
        if (!digest.equals(stored.requestDigest())) {
            throw new ConflictException("operationId 已用于不同的业务请求", null, List.of());
        }
        TeacherOperationResultDTO<AdjustmentRequestDetailDTO> storedResult =
                operations.decode(stored.responseJson(), RESULT_TYPE);
        return new TeacherOperationResultDTO<>(operationId, storedResult.getMessage(),
                storedResult.getValue(), true);
    }

    // ---------------------------------------------------------------- mapping

    private AdjustmentRequestDetailDTO detail(Connection connection,
            ScheduleAdjustmentDAO.RequestRow row, List<ScheduleAdjustmentDAO.TargetRow> targets,
            List<ScheduleConflictDTO> conflicts) throws SQLException {
        List<AdjustmentTargetDTO> mapped = new ArrayList<>();
        for (ScheduleAdjustmentDAO.TargetRow target : targets) {
            mapped.add(new AdjustmentTargetDTO(Long.toString(target.originalOccurrenceId()),
                    target.week(), ScheduleAdjustmentDAO.instantText(target.startAt()),
                    ScheduleAdjustmentDAO.instantText(target.endAt()),
                    name(connection, target.teacherUid()), name(connection, target.assistantUid()),
                    classroomName(connection, target.classroomId()),
                    target.targetDate() == null ? null : target.targetDate().toString()));
        }
        return new AdjustmentRequestDetailDTO(Long.toString(row.requestId()),
                Long.toString(row.offeringId()), row.applicantUid(), row.reason(), row.status(),
                row.version(), row.newDayOfWeek(), row.newStartPeriod(), row.newEndPeriod(),
                row.newTeacherUid() == null ? null
                        : adjustments.teacherResource(connection, row.newTeacherUid()),
                row.newAssistantUid() == null ? null
                        : adjustments.teacherResource(connection, row.newAssistantUid()),
                row.newClassroomId() == null ? null
                        : adjustments.classroomResource(connection, row.newClassroomId()),
                mapped, conflicts, ScheduleAdjustmentDAO.instantText(row.submittedAt()),
                row.reviewedBy(), ScheduleAdjustmentDAO.instantText(row.reviewedAt()),
                row.reviewComment());
    }

    private String name(Connection connection, String uid) throws SQLException {
        return uid == null ? null : adjustments.teacherResource(connection, uid).getName();
    }

    private String classroomName(Connection connection, Long classroomId) throws SQLException {
        return classroomId == null ? null
                : adjustments.classroomResource(connection, classroomId).getName();
    }

    // ------------------------------------------------------------ transaction

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
                throw new DatabaseException("教师调课事务执行失败", restoration);
            }
        }
    }

    /** 调课目标已与当前正式课表不一致（被管理员改过或已不在当前方案）。 */
    public static final String TARGET_INVALID = ScheduleAdjustmentApprovalService.TARGET_INVALID;
    /** 原课次已有生效调课或已有待审批的申请，本期不再重复申请。 */
    public static final String TARGET_ADJUSTED = ScheduleAdjustmentApprovalService.TARGET_ADJUSTED;

    /** 教师写操作的冲突：携带最新可见实体（可能为 null）与冲突列表，上层映射为 CONFLICT。 */
    public static class ConflictException extends RuntimeException {
        private final AdjustmentRequestDetailDTO entity;
        private final List<ScheduleConflictDTO> conflicts;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message) {
            this(message, null, List.of());
        }

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, AdjustmentRequestDetailDTO entity,
                                 List<ScheduleConflictDTO> conflicts) {
            super(message);
            this.entity = entity;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        /**
        * Obtains Entity data.
        */
        public AdjustmentRequestDetailDTO getEntity() {
            return entity;
        }

        /**
        * Obtains Conflicts data.
        */
        public List<ScheduleConflictDTO> getConflicts() {
            return conflicts;
        }
    }

    /** 不属于本人（或不存在）的申请；对外与“不存在”不可区分，绝不返回 FORBIDDEN。 */
    public static class NotFoundException extends RuntimeException {
        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) {
            super(message);
        }
    }
}
