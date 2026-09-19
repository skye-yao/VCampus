package service;

import dao.CourseEventOutboxDAO;
import dao.CourseOperationDAO;
import dao.CourseQueryDAO;
import dao.CourseSelectionDAO;
import dao.CourseWaitlistDAO;
import dto.course.CourseActions;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CoursePushEventTypeDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
* Internal course-management type CourseWaitlistService.
*/
public class CourseWaitlistService implements WaitlistAdvanceTrigger {
    private static final Duration OFFER_DURATION = Duration.ofMinutes(5);
    private static final int TRIGGER_LIMIT = 100;

    private final CourseSelectionDAO selectionDAO;
    private final CourseWaitlistDAO waitlistDAO;
    private final CourseEventOutboxDAO outboxDAO;
    private final CourseOperationDAO operationDAO;
    private final CourseQueryDAO queryDAO;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of CourseWaitlistService.
    */
    public CourseWaitlistService() {
        this(Clock.systemUTC());
    }

    CourseWaitlistService(Clock clock) {
        this(new CourseSelectionDAO(), new CourseWaitlistDAO(), new CourseEventOutboxDAO(),
                new CourseOperationDAO(), new CourseQueryDAO(), clock);
    }

    CourseWaitlistService(CourseSelectionDAO selectionDAO, CourseWaitlistDAO waitlistDAO,
                          CourseEventOutboxDAO outboxDAO, CourseOperationDAO operationDAO,
                          CourseQueryDAO queryDAO, Clock clock) {
        this.selectionDAO = selectionDAO;
        this.waitlistDAO = waitlistDAO;
        this.outboxDAO = outboxDAO;
        this.operationDAO = operationDAO;
        this.queryDAO = queryDAO;
        this.clock = clock;
    }

    /**
    * Handles the course-management responsibility of newSelectionService.
    */
    public CourseSelectionService newSelectionService() {
        return new CourseSelectionService(clock, this);
    }

    /**
    * Handles the course-management responsibility of joinWaitlist.
    */
    public CourseMutationResultDTO joinWaitlist(String uid, CourseTermDTO term,
                                                long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.JOIN_WAITLIST,
                connection -> join(connection, uid, term, offeringId, operationId));
    }

    /**
    * Removes or cancels cancelWaitlist data.
    */
    public CourseMutationResultDTO cancelWaitlist(String uid, CourseTermDTO term,
                                                  long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.CANCEL_WAITLIST,
                connection -> cancel(connection, uid, term, offeringId, operationId));
    }

    /**
    * Handles the course-management responsibility of resolveWaitlistOffer.
    */
    public CourseMutationResultDTO resolveWaitlistOffer(String uid, CourseTermDTO term,
                                                        long offeringId, String operationId,
                                                        String decision) {
        String normalized = decision == null ? "" : decision.trim().toUpperCase();
        if (!"ACCEPT".equals(normalized) && !"ABANDON".equals(normalized)) {
            throw new IllegalArgumentException("decision 必须是 ACCEPT 或 ABANDON");
        }
        String digestAction = CourseActions.RESOLVE_WAITLIST_OFFER + ":" + normalized;
        return transact(uid, term, offeringId, operationId, digestAction,
                connection -> resolve(connection, uid, term, offeringId, operationId, normalized));
    }

    @Override
    /**
    * Handles the course-management responsibility of offeringFreed.
    */
    public void offeringFreed(long offeringId) {
        if (offeringId <= 0) throw new IllegalArgumentException("offeringId 必须为正整数");
        advanceOffering(offeringId, clock.instant(), TRIGGER_LIMIT);
    }

    int expireOverdueOffers(Instant now, int limit) {
        requireBatch(now, limit);
        List<CourseWaitlistDAO.WaitlistRow> candidates;
        try (Connection connection = DBUtil.getConnection()) {
            candidates = waitlistDAO.overdueOffers(connection, now, limit);
        } catch (SQLException failure) {
            throw new DatabaseException("扫描过期候补席位失败", failure);
        }
        int changed = 0;
        for (CourseWaitlistDAO.WaitlistRow candidate : candidates) {
            if (expireOffer(candidate, now)) changed++;
        }
        return changed;
    }

    int advanceOpenVacancies(Instant now, int limit) {
        requireBatch(now, limit);
        List<CourseWaitlistDAO.WaitlistRow> closed;
        List<Long> offerings;
        try (Connection connection = DBUtil.getConnection()) {
            closed = waitlistDAO.waitingAfterWindowClose(connection, now, limit);
            offerings = waitlistDAO.vacantOfferingIds(connection, now, limit);
        } catch (SQLException failure) {
            throw new DatabaseException("扫描候补队列失败", failure);
        }
        int changed = 0;
        for (CourseWaitlistDAO.WaitlistRow candidate : closed) {
            if (changed >= limit) break;
            if (expireClosedWaiter(candidate, now)) changed++;
        }
        for (Long offeringId : offerings) {
            if (changed >= limit) break;
            changed += advanceOffering(offeringId, now, limit - changed);
        }
        return changed;
    }

    private Execution join(Connection connection, String uid, CourseTermDTO term,
                           long offeringId, String operationId) throws SQLException {
        CourseSelectionDAO.WindowRow window = selectionDAO.window(
                connection, term.getAcademicYear(), term.getSemester());
        List<Long> predictedConflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        Set<Long> affected = new LinkedHashSet<>(predictedConflicts);
        affected.add(offeringId);
        lockOfferings(connection, affected, offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        Instant now = clock.instant();
        if (now.isBefore(window.selectionOpenAt()) || !now.isBefore(window.selectionCloseAt())) {
            throw new CourseSelectionService.ConflictException("当前不在正式选课时段");
        }
        requireEligible(connection, uid, offeringId);
        if (selectionDAO.enrolled(connection, uid, offeringId)) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.ENROLLED, "ALREADY_ENROLLED", "该教学班已经选中"));
        }
        CourseWaitlistDAO.WaitlistRow existing = waitlistDAO.findForUpdate(
                connection, uid, offeringId);
        if (existing != null && "WAITING".equals(existing.status())) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.WAITLISTED, "WAITLIST_ACTIVE", "已经在候补队列中"));
        }
        if (existing != null && "OFFERED".equals(existing.status())
                && existing.expiresAt().isAfter(now)) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.WAITLIST_OFFERED, "WAITLIST_ACTIVE", "已有待处理候补席位"));
        }
        if (!"FULL".equals(selectionDAO.planState(connection, uid, offeringId))) {
            throw new CourseSelectionService.ConflictException("只有满员计划项可以加入候补");
        }

        List<Long> conflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        boolean queueExists = selectionDAO.hasActiveWaiters(connection, offeringId);
        int used = offering.enrolledCount()
                + waitlistDAO.activeReservations(connection, offeringId, now);
        waitlistDAO.saveWaiting(connection, uid, offeringId,
                waitlistDAO.nextQueueTime(connection, offeringId, now));
        CourseWaitlistDAO.WaitlistRow created = waitlistDAO.findForUpdate(connection, uid, offeringId);
        if (!queueExists && used < offering.capacity()) {
            if (conflicts.isEmpty()) {
                waitlistDAO.markStatus(connection, created.waitlistId(), "WAITING", "ENROLLED");
                selectionDAO.enroll(connection, uid, offeringId);
                selectionDAO.changeEnrolledCount(connection, offeringId, 1);
                selectionDAO.deletePlan(connection, uid, offeringId);
                event(connection, uid, term, offeringId, CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED,
                        now, null, "候补加入时已有空位，已自动选中");
                return execution(result(connection, uid, term,
                        selectionDAO.offering(connection, offeringId), operationId,
                        SelectionStateDTO.ENROLLED, "ENROLLED", "选课成功"));
            }
            Instant expiresAt = now.plus(OFFER_DURATION);
            waitlistDAO.markOffered(connection, created.waitlistId(), now, expiresAt);
            event(connection, uid, term, offeringId, CoursePushEventTypeDTO.WAITLIST_OFFERED,
                    now, expiresAt, "候补席位已到位，请在五分钟内处理冲突");
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.WAITLIST_OFFERED, "WAITLIST_OFFERED", "候补席位已到位"));
        }
        selectionDAO.upsertPlan(connection, uid, offeringId, "FULL", "正在候补队列中");
        return execution(result(connection, uid, term, offering, operationId,
                SelectionStateDTO.WAITLISTED, "WAITLISTED", "已加入候补队列"));
    }

    private Execution cancel(Connection connection, String uid, CourseTermDTO term,
                             long offeringId, String operationId) throws SQLException {
        lockOfferings(connection, List.of(offeringId), offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        CourseWaitlistDAO.WaitlistRow row = waitlistDAO.findForUpdate(connection, uid, offeringId);
        if (row == null || !"WAITING".equals(row.status())) {
            throw new CourseSelectionService.ConflictException("当前没有可取消的候补排队");
        }
        waitlistDAO.markStatus(connection, row.waitlistId(), "WAITING", "CANCELLED");
        selectionDAO.upsertPlan(connection, uid, offeringId, "FULL", "已取消候补");
        return execution(result(connection, uid, term, offering, operationId,
                SelectionStateDTO.FULL, "WAITLIST_CANCELLED", "已取消候补"));
    }

    private Execution resolve(Connection connection, String uid, CourseTermDTO term,
                              long offeringId, String operationId, String decision)
            throws SQLException {
        CourseWaitlistDAO.WaitlistRow observed = waitlistDAO.find(connection, uid, offeringId);
        if (observed == null || !"OFFERED".equals(observed.status())) {
            throw new CourseSelectionService.ConflictException("当前没有待处理的候补席位");
        }
        CourseSelectionDAO.WindowRow window = selectionDAO.window(
                connection, term.getAcademicYear(), term.getSemester());
        List<Long> predictedConflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        Set<Long> affected = new LinkedHashSet<>(predictedConflicts);
        affected.add(offeringId);
        lockOfferings(connection, affected, offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        CourseWaitlistDAO.WaitlistRow row = waitlistDAO.findForUpdate(connection, uid, offeringId);
        if (row == null || !"OFFERED".equals(row.status())) {
            throw new CourseSelectionService.ConflictException("候补席位状态已经变化");
        }
        Instant now = clock.instant();
        if (!row.expiresAt().isAfter(now)) {
            expire(connection, row, term, now, "候补席位已超时");
            return new Execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.FULL, "WAITLIST_OFFER_EXPIRED", "候补席位已超时"),
                    Set.of(offeringId));
        }
        if ("ABANDON".equals(decision)) {
            waitlistDAO.markStatus(connection, row.waitlistId(), "OFFERED", "CANCELLED");
            selectionDAO.upsertPlan(connection, uid, offeringId, "FULL", "已放弃候补席位");
            event(connection, uid, term, offeringId,
                    CoursePushEventTypeDTO.WAITLIST_OFFER_ABANDONED,
                    now, null, "已放弃候补席位");
            return new Execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.FULL, "WAITLIST_OFFER_ABANDONED", "已放弃候补席位"),
                    Set.of(offeringId));
        }

        List<Long> conflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        for (Long conflictId : conflicts) {
            selectionDAO.drop(connection, uid, conflictId, now);
            selectionDAO.changeEnrolledCount(connection, conflictId, -1);
        }
        waitlistDAO.markStatus(connection, row.waitlistId(), "OFFERED", "ENROLLED");
        selectionDAO.enroll(connection, uid, offeringId);
        selectionDAO.changeEnrolledCount(connection, offeringId, 1);
        selectionDAO.deletePlan(connection, uid, offeringId);
        event(connection, uid, term, offeringId,
                CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED,
                now, null, "候补席位已接受并选中");
        Set<Long> freed = new LinkedHashSet<>(conflicts);
        return new Execution(result(connection, uid, term,
                selectionDAO.offering(connection, offeringId), operationId,
                SelectionStateDTO.ENROLLED, "ENROLLED", "候补席位接受成功"), Set.copyOf(freed));
    }

    private int advanceOffering(long offeringId, Instant now, int limit) {
        int changed = 0;
        while (changed < limit) {
            CourseWaitlistDAO.WaitlistRow candidate = head(offeringId);
            if (candidate == null) break;
            AdvanceResult result = advanceCandidate(candidate, now);
            if (result == AdvanceResult.STOP) break;
            if (result == AdvanceResult.CHANGED) changed++;
        }
        return changed;
    }

    private CourseWaitlistDAO.WaitlistRow head(long offeringId) {
        try (Connection connection = DBUtil.getConnection()) {
            return waitlistDAO.head(connection, offeringId);
        } catch (SQLException failure) {
            throw new DatabaseException("读取候补队首失败", failure);
        }
    }

    private AdvanceResult advanceCandidate(CourseWaitlistDAO.WaitlistRow candidate, Instant now) {
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                CourseWaitlistDAO.StudentRow student = waitlistDAO.lockStudentProfile(
                        connection, candidate.uid());
                CourseWaitlistDAO.OfferingInfo info = waitlistDAO.offeringInfo(
                        connection, candidate.offeringId());
                if (info == null) {
                    connection.rollback();
                    return AdvanceResult.STOP;
                }
                CourseSelectionDAO.WindowRow window = null;
                List<Long> predictedConflicts = List.of();
                try {
                    window = selectionDAO.window(connection, info.academicYear(), info.semester());
                    if (student != null && student.role() == 2 && "ACTIVE".equals(student.status())) {
                        predictedConflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                                connection, candidate.uid(), info.academicYear(), info.semester(),
                                info.offeringId(), window.schedulePlanId());
                    }
                } catch (CourseSelectionDAO.MissingRowException ignored) {
                    // The locked candidate is cancelled below after the target offering is locked.
                }
                Set<Long> affected = new LinkedHashSet<>(predictedConflicts);
                affected.add(candidate.offeringId());
                lockOfferings(connection, affected, candidate.offeringId());
                CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(
                        connection, candidate.offeringId());
                CourseWaitlistDAO.WaitlistRow head = waitlistDAO.headForUpdate(
                        connection, candidate.offeringId());
                if (head == null || head.waitlistId() != candidate.waitlistId()
                        || !head.uid().equals(candidate.uid())) {
                    connection.commit();
                    return AdvanceResult.RETRY;
                }
                CourseTermDTO term = CourseQueryDAO.term(
                        offering.academicYear(), offering.semester());
                String invalidReason = invalidReason(connection, student, offering, window,
                        candidate.uid(), now);
                if ("WAIT".equals(invalidReason)) {
                    connection.commit();
                    return AdvanceResult.STOP;
                }
                if (invalidReason != null) {
                    cancelInvalid(connection, head, term, now, invalidReason);
                    connection.commit();
                    return AdvanceResult.CHANGED;
                }
                int used = offering.enrolledCount()
                        + waitlistDAO.activeReservations(connection, offering.offeringId(), now);
                if (used >= offering.capacity()) {
                    connection.commit();
                    return AdvanceResult.STOP;
                }
                List<Long> conflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                        connection, candidate.uid(), offering.academicYear(), offering.semester(),
                        offering.offeringId(), window.schedulePlanId());
                if (conflicts.isEmpty()) {
                    waitlistDAO.markStatus(connection, head.waitlistId(), "WAITING", "ENROLLED");
                    selectionDAO.enroll(connection, candidate.uid(), offering.offeringId());
                    selectionDAO.changeEnrolledCount(connection, offering.offeringId(), 1);
                    selectionDAO.deletePlan(connection, candidate.uid(), offering.offeringId());
                    event(connection, candidate.uid(), term, offering.offeringId(),
                            CoursePushEventTypeDTO.WAITLIST_AUTO_ENROLLED,
                            now, null, "候补已按顺序自动选中");
                } else {
                    Instant expiresAt = now.plus(OFFER_DURATION);
                    waitlistDAO.markOffered(connection, head.waitlistId(), now, expiresAt);
                    event(connection, candidate.uid(), term, offering.offeringId(),
                            CoursePushEventTypeDTO.WAITLIST_OFFERED,
                            now, expiresAt, "候补席位已到位，请在五分钟内处理冲突");
                }
                connection.commit();
                return AdvanceResult.CHANGED;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("候补推进失败", failure);
        }
    }

    private String invalidReason(Connection connection, CourseWaitlistDAO.StudentRow student,
                                 CourseSelectionDAO.OfferingRow offering,
                                 CourseSelectionDAO.WindowRow window, String uid, Instant now)
            throws SQLException {
        if (student == null || student.role() != 2 || !"ACTIVE".equals(student.status())) {
            return "学生资格已失效";
        }
        if (offering.status() != 2) return "教学班已关闭";
        if (window == null) return "学期排课方案不可用";
        if (!now.isBefore(window.selectionCloseAt())) return "正式选课窗口已关闭";
        if (now.isBefore(window.selectionOpenAt())) return "WAIT";
        if (!selectionDAO.eligible(connection, uid, offering.offeringId())) {
            return "不再符合专业或年级资格";
        }
        if (selectionDAO.enrolled(connection, uid, offering.offeringId())) {
            return "目标教学班已经选中";
        }
        return null;
    }

    private void cancelInvalid(Connection connection, CourseWaitlistDAO.WaitlistRow row,
                               CourseTermDTO term, Instant now, String reason) throws SQLException {
        String state = "正式选课窗口已关闭".equals(reason) ? "EXPIRED" : "CANCELLED";
        waitlistDAO.markStatus(connection, row.waitlistId(), "WAITING", state);
        selectionDAO.upsertPlan(connection, row.uid(), row.offeringId(), "FULL", reason);
        event(connection, row.uid(), term, row.offeringId(),
                CoursePushEventTypeDTO.WAITLIST_OFFER_EXPIRED, now, null, reason);
    }

    private boolean expireOffer(CourseWaitlistDAO.WaitlistRow candidate, Instant now) {
        boolean changed = false;
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                waitlistDAO.lockStudentProfile(connection, candidate.uid());
                lockOfferings(connection, List.of(candidate.offeringId()), candidate.offeringId());
                CourseWaitlistDAO.WaitlistRow row = waitlistDAO.findForUpdate(
                        connection, candidate.uid(), candidate.offeringId());
                if (row != null && "OFFERED".equals(row.status())
                        && !row.expiresAt().isAfter(now)) {
                    CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(
                            connection, candidate.offeringId());
                    expire(connection, row, CourseQueryDAO.term(
                            offering.academicYear(), offering.semester()), now, "候补席位已超时");
                    changed = true;
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("候补席位超时处理失败", failure);
        }
        if (changed) advanceOffering(candidate.offeringId(), now, TRIGGER_LIMIT);
        return changed;
    }

    private boolean expireClosedWaiter(CourseWaitlistDAO.WaitlistRow candidate, Instant now) {
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                waitlistDAO.lockStudentProfile(connection, candidate.uid());
                lockOfferings(connection, List.of(candidate.offeringId()), candidate.offeringId());
                CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(
                        connection, candidate.offeringId());
                CourseSelectionDAO.WindowRow window = selectionDAO.window(
                        connection, offering.academicYear(), offering.semester());
                CourseWaitlistDAO.WaitlistRow row = waitlistDAO.findForUpdate(
                        connection, candidate.uid(), candidate.offeringId());
                if (row == null || !"WAITING".equals(row.status())
                        || now.isBefore(window.selectionCloseAt())) {
                    connection.commit();
                    return false;
                }
                CourseTermDTO term = CourseQueryDAO.term(offering.academicYear(), offering.semester());
                waitlistDAO.markStatus(connection, row.waitlistId(), "WAITING", "EXPIRED");
                selectionDAO.upsertPlan(connection, row.uid(), row.offeringId(),
                        "FULL", "正式选课窗口已关闭");
                event(connection, row.uid(), term, row.offeringId(),
                        CoursePushEventTypeDTO.WAITLIST_OFFER_EXPIRED,
                        now, null, "正式选课窗口已关闭");
                connection.commit();
                return true;
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException failure) {
            throw new DatabaseException("关闭候补队列失败", failure);
        }
    }

    private void expire(Connection connection, CourseWaitlistDAO.WaitlistRow row,
                        CourseTermDTO term, Instant now, String reason) throws SQLException {
        waitlistDAO.markStatus(connection, row.waitlistId(), "OFFERED", "EXPIRED");
        selectionDAO.upsertPlan(connection, row.uid(), row.offeringId(), "FULL", reason);
        event(connection, row.uid(), term, row.offeringId(),
                CoursePushEventTypeDTO.WAITLIST_OFFER_EXPIRED, now, null, reason);
    }

    private CourseMutationResultDTO transact(String uid, CourseTermDTO term, long offeringId,
                                              String operationId, String digestAction,
                                              Mutation mutation) {
        validate(uid, term, offeringId, operationId);
        String digest = operationDAO.digest(digestAction, term, offeringId);
        Execution execution;
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                selectionDAO.lockStudentProfile(connection, uid);
                CourseOperationDAO.StoredOperation stored = operationDAO.find(
                        connection, uid, operationId);
                if (stored != null) {
                    if (!digest.equals(stored.requestDigest())) {
                        throw new CourseSelectionService.OperationConflictException(
                                "operationId 已用于不同的业务请求");
                    }
                    execution = execution(stored.result());
                } else {
                    execution = mutation.execute(connection);
                    operationDAO.insert(connection, uid, operationId, digestAction,
                            offeringId, digest, execution.result());
                }
                connection.commit();
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (CourseSelectionDAO.MissingRowException failure) {
            throw new CourseSelectionService.NotFoundException("课程资源不存在", failure);
        } catch (CourseSelectionService.OperationConflictException
                 | CourseSelectionService.ConflictException
                 | CourseSelectionService.NotFoundException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new DatabaseException("候补事务执行失败", failure);
        }
        for (Long freedOfferingId : execution.freedOfferingIds()) {
            try {
                advanceOffering(freedOfferingId, clock.instant(), TRIGGER_LIMIT);
            } catch (RuntimeException triggerFailure) {
                System.err.println("候补推进触发失败，offeringId=" + freedOfferingId);
            }
        }
        return execution.result();
    }

    private CourseMutationResultDTO result(Connection connection, String uid, CourseTermDTO term,
                                           CourseSelectionDAO.OfferingRow offering,
                                           String operationId, SelectionStateDTO state,
                                           String code, String message) throws SQLException {
        CourseSelectionItemDTO item = item(connection, uid, offering.offeringId());
        CoursePlanSnapshotDTO snapshot = queryDAO.loadSelectionSnapshot(connection, uid,
                term.getAcademicYear(), term.getSemester());
        return new CourseMutationResultDTO(operationId, item, state, code, message, snapshot);
    }

    private CourseSelectionItemDTO item(Connection connection, String uid, long offeringId)
            throws SQLException {
        CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(connection, offeringId);
        List<CourseOfferingDTO> offerings = queryDAO.listCourseOfferings(connection, uid,
                offering.academicYear(), offering.semester(), offering.courseId());
        CourseOfferingDTO match = offerings.stream()
                .filter(candidate -> Long.toString(offeringId).equals(candidate.getOfferingId()))
                .findFirst().orElseThrow(() -> new CourseSelectionService.ConflictException(
                        "课程当前不可见"));
        dto.course.CourseDTO course = queryDAO.listCourses(connection, uid,
                        offering.academicYear(), offering.semester()).stream()
                .filter(candidate -> match.getCourseId().equals(candidate.getCourseId()))
                .findFirst().orElseThrow(() -> new CourseSelectionService.ConflictException(
                        "课程当前不可见"));
        return new CourseSelectionItemDTO(course, match);
    }

    private void event(Connection connection, String uid, CourseTermDTO term, long offeringId,
                       CoursePushEventTypeDTO type, Instant now, Instant expiresAt, String message)
            throws SQLException {
        outboxDAO.insert(connection, uid, type, term, offeringId, now, expiresAt, message);
    }

    private void lockOfferings(Connection connection, Iterable<Long> ids, long required)
            throws SQLException {
        Set<Long> values = new LinkedHashSet<>();
        ids.forEach(values::add);
        if (!selectionDAO.lockOfferingsAscending(connection, values).contains(required)) {
            throw new CourseSelectionDAO.MissingRowException("offering");
        }
    }

    private CourseSelectionDAO.OfferingRow checkedOffering(Connection connection,
                                                            CourseTermDTO term,
                                                            long offeringId) throws SQLException {
        CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(connection, offeringId);
        if (offering.academicYear() != term.getAcademicYear()
                || offering.semester() != term.getSemester()) {
            throw new CourseSelectionDAO.MissingRowException("offering in requested term");
        }
        if (offering.status() != 2) {
            throw new CourseSelectionService.ConflictException("教学班当前未开放");
        }
        return offering;
    }

    private void requireEligible(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (!selectionDAO.eligible(connection, uid, offeringId)) {
            throw new CourseSelectionService.ConflictException("当前学生不具备该课程资格");
        }
    }

    private static void validate(String uid, CourseTermDTO term, long offeringId,
                                 String operationId) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        if (term == null || term.getAcademicYear() <= 0
                || term.getSemester() < 1 || term.getSemester() > 3) {
            throw new IllegalArgumentException("学期参数无效");
        }
        if (offeringId <= 0) throw new IllegalArgumentException("offeringId 必须为正整数");
        try {
            if (operationId == null || operationId.length() != 36
                    || !UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException("operationId 必须是 UUID");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("operationId 必须是 UUID");
        }
    }

    private static void requireBatch(Instant now, int limit) {
        if (now == null) throw new IllegalArgumentException("now 不能为空");
        if (limit <= 0) throw new IllegalArgumentException("limit 必须为正整数");
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Execution execution(CourseMutationResultDTO result) {
        return new Execution(result, Set.of());
    }

    /**
    * Internal course-management type AdvanceResult.
    */
    private enum AdvanceResult { CHANGED, RETRY, STOP }

    @FunctionalInterface
    /**
    * Internal course-management type Mutation.
    */
    private interface Mutation {
        Execution execute(Connection connection) throws SQLException;
    }

    /**
    * Internal course-management type Execution.
    */
    private record Execution(CourseMutationResultDTO result, Set<Long> freedOfferingIds) {
    }
}
