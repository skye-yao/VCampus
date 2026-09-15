package service;

import com.google.gson.reflect.TypeToken;
import dao.AdminCourseOperationDAO;
import dao.AdminScheduleDAO;
import dao.ScheduleAdjustmentDAO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.AdjustmentRequestPageDTO;
import dto.course.admin.approval.AdjustmentRequestSummaryDTO;
import dto.course.admin.approval.AdjustmentTargetDTO;
import dto.course.admin.approval.ApprovalDecisionRequestDTO;
import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dto.course.admin.schedule.ScheduleConflictSeverityDTO.BLOCKING;

/**
 * Administrator review of temporary schedule adjustment requests.
 *
 * <p>A request carries one proposed slot and one or more immutable original-occurrence targets.
 * Approval is all-or-nothing: it expands every target into one ACTIVE adjustment inside a single
 * READ_COMMITTED transaction, publishes exactly one linked notice and records one audit row, while
 * the published base plan, its rules, its weeks, its occurrences and its bookings stay untouched.
 * Conflicts are always recalculated against the effective schedule — occurrences already replaced
 * by an ACTIVE adjustment — after excluding the request's own originals, which are still in place
 * until this very approval commits.
 */
public class ScheduleAdjustmentApprovalService {
    /** A target whose snapshot no longer matches the effective published plan. */
    public static final String TARGET_INVALID = "ADJUSTMENT_TARGET_INVALID";
    /** A target whose occurrence already carries an ACTIVE temporary adjustment. */
    public static final String TARGET_ADJUSTED = "ADJUSTMENT_TARGET_ADJUSTED";
    /** A proposed weekday/period the teaching calendar cannot place in the target week. */
    public static final String SLOT_INVALID = "ADJUSTMENT_SLOT_INVALID";

    private static final String TARGET_TYPE = "ADJUSTMENT_REQUEST";
    private static final String OK = "OK";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String ACTIVE = "ACTIVE";
    private static final String NOTICE_TITLE = "调课安排已生效";
    private static final int MAX_TEXT = 500;
    private static final int DUPLICATE_KEY = 1062;
    private static final Type RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<AdjustmentRequestDetailDTO>>() { }.getType();
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

    private final ScheduleAdjustmentDAO dao;
    private final AdminCourseOperationDAO operations;
    private final ScheduleAdjustmentConflictService conflicts;
    private final Clock clock;

    public ScheduleAdjustmentApprovalService() {
        this(new ScheduleAdjustmentDAO(), new AdminCourseOperationDAO(),
                new ScheduleAdjustmentConflictService(), Clock.systemUTC());
    }

    public ScheduleAdjustmentApprovalService(ScheduleAdjustmentDAO dao,
                                             AdminCourseOperationDAO operations,
                                             ScheduleAdjustmentConflictService conflicts,
                                             Clock clock) {
        this.dao = dao;
        this.operations = operations;
        this.conflicts = conflicts;
        this.clock = clock;
    }

    // ------------------------------------------------------------------- reads

    public AdjustmentRequestPageDTO listRequests(AdjustmentRequestStatusDTO status, int page, int size) {
        if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("每页条数必须为 1 至 100");
        AdjustmentRequestStatusDTO filter = status == null ? AdjustmentRequestStatusDTO.PENDING : status;
        try (Connection connection = DBUtil.getConnection()) {
            long total = dao.countRequests(connection, filter);
            List<AdjustmentRequestSummaryDTO> items =
                    dao.listRequests(connection, filter, (page - 1) * size, size);
            return new AdjustmentRequestPageDTO(items, total, page, size);
        } catch (SQLException failure) {
            throw new DatabaseException("查询调课申请失败", failure);
        }
    }

    public AdjustmentRequestDetailDTO getRequest(String requestId) {
        long id = AdminOperationTransaction.parseId(requestId, "requestId");
        try (Connection connection = DBUtil.getConnection()) {
            ScheduleAdjustmentDAO.RequestRow row = dao.findRequest(connection, id);
            if (row == null) throw new NotFoundException("调课申请不存在");
            List<ScheduleAdjustmentDAO.TargetRow> targets = dao.listTargets(connection, id);
            Assessment assessment = inspect(connection, row, targets, new Calendars(dao));
            List<ScheduleConflictDTO> found = row.status() == AdjustmentRequestStatusDTO.PENDING
                    ? assessment.conflicts() : List.of();
            return detail(connection, row, targets, found);
        } catch (SQLException failure) {
            throw new DatabaseException("查询调课申请详情失败", failure);
        }
    }

    // ------------------------------------------------------------------ review

    public AdminOperationResultDTO<AdjustmentRequestDetailDTO> review(String adminUid,
            ApprovalDecisionRequestDTO raw) {
        String admin = adminUid == null ? null : adminUid.trim();
        AdminOperationTransaction.validate(admin, raw == null ? null : raw.getOperationId());
        ApprovalDecisionRequestDTO request = validate(raw);
        String action = AdminCourseActions.REVIEW_ADJUSTMENT_REQUEST;
        String digest = operations.digest(action, request);
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                AdminCourseOperationDAO.StoredOperation stored =
                        operations.find(connection, admin, request.getOperationId());
                AdminOperationResultDTO<AdjustmentRequestDetailDTO> result;
                if (stored != null) {
                    result = replay(stored, digest);
                } else {
                    // The request lock serializes same-request decisions, but a lost racing
                    // operation may already have committed, so the log is re-read under it.
                    dao.lockRequest(connection,
                            AdminOperationTransaction.parseId(request.getRequestId(), "requestId"));
                    stored = operations.find(connection, admin, request.getOperationId());
                    result = stored != null ? replay(stored, digest)
                            : decide(connection, admin, request, action, digest);
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
            throw new DatabaseException("调课审批事务执行失败", failure);
        }
    }

    private AdminOperationResultDTO<AdjustmentRequestDetailDTO> decide(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest)
            throws SQLException {
        long requestId = AdminOperationTransaction.parseId(request.getRequestId(), "requestId");
        Calendars calendars = new Calendars(dao);
        ScheduleAdjustmentDAO.RequestRow row = dao.findRequest(connection, requestId);
        if (row == null) throw new NotFoundException("调课申请不存在");
        List<ScheduleAdjustmentDAO.TargetRow> targets = dao.listTargets(connection, requestId);
        if (row.status() != AdjustmentRequestStatusDTO.PENDING) {
            Assessment current = inspect(connection, row, targets, calendars);
            throw conflict("调课申请已被处理，请刷新后重试", connection, row, targets, current.conflicts());
        }
        if (row.version() != request.getExpectedVersion()) {
            Assessment current = inspect(connection, row, targets, calendars);
            throw conflict("调课申请版本已变化，请刷新后重试", connection, row, targets,
                    current.conflicts());
        }
        if (targets.isEmpty()) {
            Assessment current = inspect(connection, row, targets, calendars);
            throw conflict("调课申请没有目标周次，无法审批", connection, row, targets,
                    current.conflicts());
        }

        Set<Long> originals = new LinkedHashSet<>();
        for (ScheduleAdjustmentDAO.TargetRow target : targets) {
            originals.add(target.originalOccurrenceId());
        }
        // Design section 7 fixes the order request -> offering -> occurrences for both the teacher
        // submit and this approval, so the two can never form a lock cycle.
        dao.lockOffering(connection, row.offeringId());
        dao.lockOccurrencesAscending(connection, originals);
        Assessment assessment = inspect(connection, row, targets, calendars);

        // Conflicts constrain approval only. Declining a pending request must stay possible even
        // when its proposal could never be approved.
        if (!request.isApproved()) {
            return persistRejection(connection, admin, request, action, digest, row, targets);
        }
        if (assessment.conflicts().stream().anyMatch(c -> BLOCKING == c.getSeverity())) {
            throw conflict("存在阻断性冲突，无法通过调课申请", connection, row, targets,
                    assessment.conflicts());
        }
        if (!assessment.conflicts().isEmpty() && !request.isForce()) {
            throw conflict("存在可绕过冲突，请确认后强制通过", connection, row, targets,
                    assessment.conflicts());
        }
        return persistApproval(connection, admin, request, action, digest, row, targets, assessment,
                calendars);
    }

    private AdminOperationResultDTO<AdjustmentRequestDetailDTO> persistRejection(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            ScheduleAdjustmentDAO.RequestRow row, List<ScheduleAdjustmentDAO.TargetRow> targets)
            throws SQLException {
        int affected = dao.updateDecision(connection, row.requestId(), request.getExpectedVersion(),
                AdjustmentRequestStatusDTO.REJECTED, admin, clock.instant(), request.getReviewComment());
        if (affected == 0) throw new ConflictException("调课申请状态已变化，请刷新后重试");
        ScheduleAdjustmentDAO.RequestRow decided = dao.findRequest(connection, row.requestId());
        AdjustmentRequestDetailDTO entity = detail(connection, decided, targets, List.of());
        AdminOperationResultDTO<AdjustmentRequestDetailDTO> result = new AdminOperationResultDTO<>(
                request.getOperationId(), OK, "调课申请已驳回", entity, List.of());
        return auditOrRecover(connection, admin, request, action, digest, result, List.of());
    }

    private AdminOperationResultDTO<AdjustmentRequestDetailDTO> persistApproval(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            ScheduleAdjustmentDAO.RequestRow row, List<ScheduleAdjustmentDAO.TargetRow> targets,
            Assessment assessment, Calendars calendars) throws SQLException {
        for (ScheduleAdjustmentDAO.TargetRow target : targets) {
            ResolvedTarget spot = assessment.targets().get(target.targetId());
            if (spot == null) throw new ConflictException("调课目标无法映射到教学日历");
            // The concrete day comes from the explicit V006 target date; legacy NULL rows keep the
            // original_week_no + request.new_weekday derivation resolved during inspection.
            AdminScheduleDAO.CalendarContext calendar =
                    calendars.get(connection, assessment.occurrences()
                            .get(target.originalOccurrenceId()).calendarId());
            AdminScheduleDAO.Window window = calendar == null ? null : calendar.window(spot.week(),
                    spot.weekday(), row.newStartPeriod(), row.newEndPeriod());
            if (window == null) throw new ConflictException("周次或节次超出教学日历范围");
            // A null proposed resource keeps that target's own snapshot, so a time-only request
            // can still resolve to a concrete teacher the adjustment column requires.
            String teacher = row.newTeacherUid() != null ? row.newTeacherUid()
                    : target.teacherUid();
            if (teacher == null) throw new IllegalArgumentException("调课后的任课教师不能为空");
            String assistant = row.newAssistantUid() != null ? row.newAssistantUid()
                    : target.assistantUid();
            Long classroom = row.newClassroomId() != null ? row.newClassroomId()
                    : target.classroomId();
            dao.insertAdjustment(connection, row.requestId(), target.originalOccurrenceId(),
                    window.start(), window.end(), teacher, assistant, classroom);
        }
        int affected = dao.updateDecision(connection, row.requestId(), request.getExpectedVersion(),
                AdjustmentRequestStatusDTO.APPROVED, admin, clock.instant(), request.getReviewComment());
        if (affected == 0) throw new ConflictException("调课申请状态已变化，请刷新后重试");
        dao.insertNotice(connection, row.requestId(), row.offeringId(), admin, NOTICE_TITLE,
                noticeContent(connection, row, targets, assessment, calendars), clock.instant());
        ScheduleAdjustmentDAO.RequestRow decided = dao.findRequest(connection, row.requestId());
        AdjustmentRequestDetailDTO entity =
                detail(connection, decided, targets, assessment.conflicts());
        AdminOperationResultDTO<AdjustmentRequestDetailDTO> result = new AdminOperationResultDTO<>(
                request.getOperationId(), OK, "调课申请已通过", entity, assessment.conflicts());
        return auditOrRecover(connection, admin, request, action, digest, result,
                assessment.conflicts());
    }

    /**
     * The operation log is the only thing two decisions that hold <em>different</em> request locks
     * still share, so it is where a reused operation id is detected. A losing insert waits for the
     * winner to commit; every local write is then discarded and the committed operation is read
     * back as a replay or a digest conflict instead of surfacing as a driver error.
     */
    private AdminOperationResultDTO<AdjustmentRequestDetailDTO> auditOrRecover(Connection connection,
            String admin, ApprovalDecisionRequestDTO request, String action, String digest,
            AdminOperationResultDTO<AdjustmentRequestDetailDTO> result,
            List<ScheduleConflictDTO> conflicts) throws SQLException {
        try {
            operations.insert(connection, admin, request.getOperationId(), action, TARGET_TYPE,
                    request.getRequestId(), digest, request, result.getOutcomeCode(), result,
                    clock.instant(), conflicts, request.isForce(), request.getOverrideReason());
            return result;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != DUPLICATE_KEY) throw failure;
            rollback(connection, failure);
            AdminCourseOperationDAO.StoredOperation winner =
                    operations.find(connection, admin, request.getOperationId());
            if (winner == null) throw failure;
            return replay(winner, digest);
        }
    }

    private AdminOperationResultDTO<AdjustmentRequestDetailDTO> replay(
            AdminCourseOperationDAO.StoredOperation stored, String digest) {
        if (!digest.equals(stored.requestDigest())) {
            throw new ConflictException("operationId 已用于不同的业务请求");
        }
        return operations.decode(stored.responseJson(), RESULT_TYPE);
    }

    // -------------------------------------------------------------- assessment

    /**
     * Every target's blocking reason, its resolved teaching day and the shared conflict engine's
     * results, merged and deduplicated in stable week/type/subject order. Targets that cannot be
     * approved are reported and skipped instead of being handed to the conflict engine, so a
     * malformed request is always typed rather than fatal.
     *
     * <p>The proposed day is resolved per target: an explicit V006 {@code target_calendar_date_id}
     * first, and legacy rows keep the {@code original_week_no + request.new_weekday} derivation.
     */
    private Assessment inspect(Connection connection, ScheduleAdjustmentDAO.RequestRow row,
                               List<ScheduleAdjustmentDAO.TargetRow> targets, Calendars calendars)
            throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (ScheduleAdjustmentDAO.TargetRow target : targets) {
            ids.add(target.originalOccurrenceId());
        }
        Map<Long, ScheduleAdjustmentDAO.OccurrenceRow> occurrences = dao.findOccurrences(connection, ids);
        List<ScheduleConflictDTO> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Map<Long, ResolvedTarget> resolved = new LinkedHashMap<>();
        List<ScheduleAdjustmentConflictService.Candidate> candidates = new ArrayList<>();
        for (ScheduleAdjustmentDAO.TargetRow target : targets) {
            ScheduleAdjustmentDAO.OccurrenceRow occurrence =
                    occurrences.get(target.originalOccurrenceId());
            String subject = Long.toString(target.originalOccurrenceId());
            if (!matches(row, target, occurrence)) {
                add(found, seen, blocking(TARGET_INVALID, subject, row, target.week(),
                        "调课目标与当前正式课表不一致"));
                continue;
            }
            if (occurrence.adjusted()) {
                add(found, seen, blocking(TARGET_ADJUSTED, subject, row, target.week(),
                        "该课程实例已有生效的调课记录"));
                continue;
            }
            ScheduleAdjustmentDAO.CalendarDateRow day = target.targetCalendarDateId() != null
                    ? dao.findCalendarDate(connection, target.targetCalendarDateId())
                    : dao.findCalendarDate(connection, occurrence.calendarId(), target.week(),
                            row.newDayOfWeek());
            if (day == null || !day.teachingDay() || day.calendarId() != occurrence.calendarId()) {
                add(found, seen, blocking(SLOT_INVALID, subject, row, target.week(),
                        "目标日期不在教学日历范围内"));
                continue;
            }
            AdminScheduleDAO.CalendarContext calendar =
                    calendars.get(connection, occurrence.calendarId());
            AdminScheduleDAO.Window window = calendar == null ? null : calendar.window(day.weekNo(),
                    day.teachingWeekday(), row.newStartPeriod(), row.newEndPeriod());
            if (window == null) {
                add(found, seen, blocking(SLOT_INVALID, subject, row, target.week(),
                        "新时间段不在教学日历范围内"));
                continue;
            }
            // A null proposed resource keeps that target's own snapshot, so a time-only request
            // can still resolve to a concrete teacher the adjustment column requires.
            String teacher = row.newTeacherUid() != null ? row.newTeacherUid() : target.teacherUid();
            String assistant = row.newAssistantUid() != null ? row.newAssistantUid()
                    : target.assistantUid();
            Long classroom = row.newClassroomId() != null ? row.newClassroomId()
                    : target.classroomId();
            resolved.put(target.targetId(), new ResolvedTarget(day.weekNo(), day.teachingWeekday()));
            candidates.add(new ScheduleAdjustmentConflictService.Candidate(day.calendarDateId(),
                    day.weekNo(), day.teachingWeekday(), row.newStartPeriod(),
                    row.newEndPeriod(), ScheduleAdjustmentDAO.instant(window.start()),
                    ScheduleAdjustmentDAO.instant(window.end()), teacher, assistant, classroom));
        }
        if (!candidates.isEmpty()) {
            Set<Long> excluded = new LinkedHashSet<>(ids);
            for (ScheduleConflictDTO conflict : conflicts.check(connection, row.offeringId(),
                    candidates, excluded)) {
                add(found, seen, conflict);
            }
        }
        found.sort(CONFLICT_ORDER);
        return new Assessment(occurrences, List.copyOf(found), Map.copyOf(resolved));
    }

    private static ScheduleConflictDTO blocking(String type, String subject,
            ScheduleAdjustmentDAO.RequestRow row, int week, String message) {
        return new ScheduleConflictDTO(type, BLOCKING, subject, Long.toString(row.offeringId()),
                week, row.newDayOfWeek(), row.newStartPeriod(), row.newEndPeriod(), message);
    }

    /**
     * The snapshot must still describe the effective published schedule: same offering, still the
     * calendar's current PUBLISHED plan, and the same week, window and resource arrangement.
     */
    private static boolean matches(ScheduleAdjustmentDAO.RequestRow row,
                                   ScheduleAdjustmentDAO.TargetRow target,
                                   ScheduleAdjustmentDAO.OccurrenceRow occurrence) {
        if (occurrence == null || occurrence.offeringId() != row.offeringId()) return false;
        if (!PUBLISHED.equals(occurrence.planStatus())) return false;
        if (occurrence.currentPlanId() == null || occurrence.currentPlanId() != occurrence.planId()) {
            return false;
        }
        if (!ACTIVE.equals(occurrence.ruleStatus()) || !ACTIVE.equals(occurrence.arrangementStatus())) {
            return false;
        }
        if (occurrence.weekNo() != target.week()) return false;
        if (!occurrence.startAt().equals(target.startAt())) return false;
        if (!occurrence.endAt().equals(target.endAt())) return false;
        if (!Objects.equals(occurrence.teacherUid(), target.teacherUid())) return false;
        if (!Objects.equals(occurrence.assistantUid(), target.assistantUid())) return false;
        return Objects.equals(occurrence.classroomId(), target.classroomId());
    }

    // -------------------------------------------------------------- notice text

    private String noticeContent(Connection connection, ScheduleAdjustmentDAO.RequestRow row,
                                 List<ScheduleAdjustmentDAO.TargetRow> targets,
                                 Assessment assessment, Calendars calendars) throws SQLException {
        ScheduleAdjustmentDAO.OccurrenceRow first =
                assessment.occurrences().get(targets.get(0).originalOccurrenceId());
        AdminScheduleDAO.CalendarContext calendar = calendars.get(connection, first.calendarId());
        ZoneId zone = calendar == null ? ZoneId.of("UTC") : calendar.zone();
        StringBuilder content = new StringBuilder("临时调课已生效。");
        for (int index = 0; index < targets.size(); index++) {
            ScheduleAdjustmentDAO.TargetRow target = targets.get(index);
            if (index > 0) content.append('；');
            content.append("第").append(target.week()).append("周 原安排 ")
                    .append(localText(zone, target.startAt(), "MM-dd HH:mm")).append('-')
                    .append(localText(zone, target.endAt(), "HH:mm")).append(' ')
                    .append(span(connection, target.teacherUid(), target.assistantUid(),
                            target.classroomId()))
                    .append("，新安排 星期").append(row.newDayOfWeek()).append(" 第")
                    .append(row.newStartPeriod()).append('-').append(row.newEndPeriod())
                    .append("节 ").append(span(connection,
                            row.newTeacherUid() != null ? row.newTeacherUid() : target.teacherUid(),
                            row.newAssistantUid() != null ? row.newAssistantUid() : target.assistantUid(),
                            row.newClassroomId() != null ? row.newClassroomId() : target.classroomId()));
        }
        return content.append('。').toString();
    }

    private String span(Connection connection, String teacherUid, String assistantUid,
                        Long classroomId) throws SQLException {
        StringBuilder span = new StringBuilder("教师").append(displayName(connection, teacherUid));
        if (assistantUid != null) {
            span.append(" 助教").append(displayName(connection, assistantUid));
        }
        if (classroomId != null) {
            span.append(" 教室").append(
                    dao.classroomResource(connection, classroomId).getName());
        }
        return span.toString();
    }

    private String displayName(Connection connection, String uid) throws SQLException {
        return uid == null ? "" : dao.teacherResource(connection, uid).getName();
    }

    private static String localText(ZoneId zone, Timestamp value, String pattern) {
        return DateTimeFormatter.ofPattern(pattern)
                .format(ScheduleAdjustmentDAO.instant(value).atZone(zone));
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
                        : dao.teacherResource(connection, row.newTeacherUid()),
                row.newAssistantUid() == null ? null
                        : dao.teacherResource(connection, row.newAssistantUid()),
                row.newClassroomId() == null ? null
                        : dao.classroomResource(connection, row.newClassroomId()),
                mapped, conflicts, ScheduleAdjustmentDAO.instantText(row.submittedAt()),
                row.reviewedBy(), ScheduleAdjustmentDAO.instantText(row.reviewedAt()),
                row.reviewComment());
    }

    private String name(Connection connection, String uid) throws SQLException {
        return uid == null ? null : dao.teacherResource(connection, uid).getName();
    }

    private String classroomName(Connection connection, Long classroomId) throws SQLException {
        return classroomId == null ? null : dao.classroomResource(connection, classroomId).getName();
    }

    private ConflictException conflict(String message, Connection connection,
            ScheduleAdjustmentDAO.RequestRow row, List<ScheduleAdjustmentDAO.TargetRow> targets,
            List<ScheduleConflictDTO> conflicts) throws SQLException {
        return new ConflictException(message, detail(connection, row, targets, conflicts), conflicts);
    }

    // ------------------------------------------------------------- validation

    private static ApprovalDecisionRequestDTO validate(ApprovalDecisionRequestDTO request) {
        long requestId = AdminOperationTransaction.parseId(request.getRequestId(), "requestId");
        int version = request.getExpectedVersion();
        if (version <= 0) throw new IllegalArgumentException("expectedVersion 必须为正整数");
        if (request.isForce() && !request.isApproved()) {
            throw new IllegalArgumentException("只有通过调课申请才支持强制覆盖");
        }
        String overrideReason = bounded(request.getOverrideReason(), "强制原因");
        if (request.isForce() && overrideReason == null) {
            throw new IllegalArgumentException("强制通过必须填写原因");
        }
        String reviewComment = bounded(request.getReviewComment(), "审批意见");
        if (!request.isApproved() && reviewComment == null) {
            throw new IllegalArgumentException("驳回必须填写审批意见");
        }
        return new ApprovalDecisionRequestDTO(request.getOperationId(), Long.toString(requestId),
                version, request.isApproved(), request.isForce(), overrideReason, reviewComment);
    }

    private static String bounded(String value, String label) {
        String text = AdminOperationTransaction.blankToNull(value);
        if (text != null && text.length() > MAX_TEXT) {
            throw new IllegalArgumentException(label + "不能超过 " + MAX_TEXT + " 字符");
        }
        return text;
    }

    private static void add(List<ScheduleConflictDTO> conflicts, Set<String> seen,
                            ScheduleConflictDTO conflict) {
        String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
                + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
                + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId();
        if (seen.add(key)) conflicts.add(conflict);
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
                throw new DatabaseException("调课审批事务执行失败", restoration);
            }
        }
    }

    /** One teaching-calendar read per distinct calendar id within a single decision. */
    private static final class Calendars {
        private final ScheduleAdjustmentDAO dao;
        private final Map<Long, AdminScheduleDAO.CalendarContext> loaded = new HashMap<>();

        private Calendars(ScheduleAdjustmentDAO dao) {
            this.dao = dao;
        }

        private AdminScheduleDAO.CalendarContext get(Connection connection, long calendarId)
                throws SQLException {
            if (!loaded.containsKey(calendarId)) {
                loaded.put(calendarId, dao.loadCalendar(connection, calendarId));
            }
            return loaded.get(calendarId);
        }
    }

    private record Assessment(Map<Long, ScheduleAdjustmentDAO.OccurrenceRow> occurrences,
                             List<ScheduleConflictDTO> conflicts,
                             Map<Long, ResolvedTarget> targets) {
    }

    /** One target's resolved teaching day; the UTC window is derived from it at write time. */
    private record ResolvedTarget(int week, int weekday) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        private final AdjustmentRequestDetailDTO entity;
        private final List<ScheduleConflictDTO> conflicts;

        public ConflictException(String message) { this(message, null, List.of()); }

        public ConflictException(String message, AdjustmentRequestDetailDTO entity,
                                 List<ScheduleConflictDTO> conflicts) {
            super(message);
            this.entity = entity;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        public AdjustmentRequestDetailDTO getEntity() { return entity; }

        public List<ScheduleConflictDTO> getConflicts() { return conflicts; }
    }
}
