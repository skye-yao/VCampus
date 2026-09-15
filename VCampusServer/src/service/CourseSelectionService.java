package service;

import dao.CourseOperationDAO;
import dao.CourseQueryDAO;
import dao.CourseSelectionDAO;
import dto.course.CourseActions;
import dto.course.CourseMutationResultDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseSelectionItemDTO;
import dto.course.CourseTermDTO;
import dto.course.SelectionStateDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CourseSelectionService {
    private final CourseSelectionDAO selectionDAO;
    private final CourseOperationDAO operationDAO;
    private final CourseQueryDAO queryDAO;
    private final Clock clock;
    private final WaitlistAdvanceTrigger waitlistTrigger;

    public CourseSelectionService() {
        this(Clock.systemUTC(), WaitlistAdvanceTrigger.NO_OP);
    }

    CourseSelectionService(Clock clock, WaitlistAdvanceTrigger waitlistTrigger) {
        this(new CourseSelectionDAO(), new CourseOperationDAO(), new CourseQueryDAO(),
                clock, waitlistTrigger);
    }

    CourseSelectionService(CourseSelectionDAO selectionDAO, CourseOperationDAO operationDAO,
                           CourseQueryDAO queryDAO, Clock clock,
                           WaitlistAdvanceTrigger waitlistTrigger) {
        this.selectionDAO = selectionDAO;
        this.operationDAO = operationDAO;
        this.queryDAO = queryDAO;
        this.clock = clock;
        this.waitlistTrigger = waitlistTrigger;
    }

    public CourseMutationResultDTO addToPlan(String uid, CourseTermDTO term,
                                             long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.ADD_TO_PLAN,
                connection -> addToPlan(connection, uid, term, offeringId, operationId));
    }

    public CourseMutationResultDTO removeFromPlan(String uid, CourseTermDTO term,
                                                  long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.REMOVE_FROM_PLAN,
                connection -> removeFromPlan(connection, uid, term, offeringId, operationId));
    }

    public CourseMutationResultDTO selectOffering(String uid, CourseTermDTO term,
                                                  long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.SELECT_OFFERING,
                connection -> selectOffering(connection, uid, term, offeringId, operationId));
    }

    public CourseMutationResultDTO dropOffering(String uid, CourseTermDTO term,
                                                long offeringId, String operationId) {
        return transact(uid, term, offeringId, operationId, CourseActions.DROP_OFFERING,
                connection -> dropOffering(connection, uid, term, offeringId, operationId));
    }

    private Execution addToPlan(Connection connection, String uid, CourseTermDTO term,
                                long offeringId, String operationId) throws SQLException {
        lockTarget(connection, offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        CourseSelectionDAO.WindowRow window = selectionDAO.window(
                connection, term.getAcademicYear(), term.getSemester());
        requireEligible(connection, uid, offeringId);
        Instant now = clock.instant();
        if (now.isBefore(window.planOpenAt()) || !now.isBefore(window.selectionCloseAt())) {
            return execution(result(connection, uid, term, offering, operationId,
                    currentState(connection, uid, offeringId), "WINDOW_CLOSED",
                    "当前不在课程计划开放时段"));
        }
        if (selectionDAO.enrolled(connection, uid, offeringId)) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.ENROLLED, "ALREADY_ENROLLED", "该教学班已经选中"));
        }
        if (selectionDAO.hasActiveWaitlist(connection, uid, offeringId)) {
            return execution(result(connection, uid, term, offering, operationId,
                    currentState(connection, uid, offeringId), "WAITLIST_ACTIVE",
                    "请先处理当前候补状态"));
        }
        selectionDAO.upsertPlan(connection, uid, offeringId, "PLANNED", null);
        return execution(result(connection, uid, term, offering, operationId,
                SelectionStateDTO.PLANNED, "PLANNED", "已加入选课计划"));
    }

    private Execution removeFromPlan(Connection connection, String uid, CourseTermDTO term,
                                     long offeringId, String operationId) throws SQLException {
        lockTarget(connection, offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        if (selectionDAO.enrolled(connection, uid, offeringId)) {
            throw new ConflictException("已选课程必须通过退选操作处理");
        }
        if (selectionDAO.hasActiveWaitlist(connection, uid, offeringId)) {
            throw new ConflictException("请先取消或处理候补状态");
        }
        selectionDAO.deletePlan(connection, uid, offeringId);
        return execution(result(connection, uid, term, offering, operationId,
                SelectionStateDTO.AVAILABLE, "AVAILABLE", "已移出选课计划"));
    }

    private Execution selectOffering(Connection connection, String uid, CourseTermDTO term,
                                     long offeringId, String operationId) throws SQLException {
        CourseSelectionDAO.WindowRow window = selectionDAO.window(
                connection, term.getAcademicYear(), term.getSemester());
        List<Long> predictedConflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        Set<Long> affected = new LinkedHashSet<>(predictedConflicts);
        affected.add(offeringId);
        List<Long> locked = selectionDAO.lockOfferingsAscending(connection, affected);
        if (!locked.contains(offeringId)) throw new CourseSelectionDAO.MissingRowException("offering");

        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        requireEligible(connection, uid, offeringId);
        Instant now = clock.instant();
        if (now.isBefore(window.selectionOpenAt()) || !now.isBefore(window.selectionCloseAt())) {
            return execution(result(connection, uid, term, offering, operationId,
                    currentState(connection, uid, offeringId), "SELECTION_CLOSED",
                    "当前不在正式选课时段"));
        }
        if (selectionDAO.enrolled(connection, uid, offeringId)) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.ENROLLED, "ALREADY_ENROLLED", "该教学班已经选中"));
        }
        if (!"PLANNED".equals(selectionDAO.planState(connection, uid, offeringId))) {
            throw new ConflictException("只有计划中的教学班可以直接选择");
        }

        List<Long> conflicts = selectionDAO.findEnrolledCourseAndTimeConflicts(
                connection, uid, term.getAcademicYear(), term.getSemester(), offeringId,
                window.schedulePlanId());
        if (!conflicts.isEmpty()) {
            boolean sameCourse = false;
            for (Long conflictId : conflicts) {
                if (selectionDAO.offering(connection, conflictId).courseId() == offering.courseId()) {
                    sameCourse = true;
                    break;
                }
            }
            String code = sameCourse ? "SAME_COURSE_CONFLICT" : "SCHEDULE_CONFLICT";
            String message = sameCourse ? "同一学期已选该课程的其他教学班" : "与已选课程时间冲突";
            selectionDAO.upsertPlan(connection, uid, offeringId, "PLANNED", message);
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.PLANNED, code, message));
        }

        if (selectionDAO.hasActiveWaiters(connection, offeringId)) {
            return full(connection, uid, term, offering, operationId, "候补队列优先");
        }
        int reservations = selectionDAO.activeOfferReservations(connection, offeringId);
        if (offering.enrolledCount() + reservations >= offering.capacity()) {
            return full(connection, uid, term, offering, operationId, "教学班容量已满");
        }

        selectionDAO.enroll(connection, uid, offeringId);
        selectionDAO.changeEnrolledCount(connection, offeringId, 1);
        selectionDAO.deletePlan(connection, uid, offeringId);
        CourseSelectionDAO.OfferingRow updated = selectionDAO.offering(connection, offeringId);
        return execution(result(connection, uid, term, updated, operationId,
                SelectionStateDTO.ENROLLED, "ENROLLED", "选课成功"));
    }

    private Execution dropOffering(Connection connection, String uid, CourseTermDTO term,
                                   long offeringId, String operationId) throws SQLException {
        lockTarget(connection, offeringId);
        CourseSelectionDAO.OfferingRow offering = checkedOffering(connection, term, offeringId);
        CourseSelectionDAO.WindowRow window = selectionDAO.window(
                connection, term.getAcademicYear(), term.getSemester());
        if (!selectionDAO.enrolled(connection, uid, offeringId)) {
            throw new ConflictException("该教学班当前未选中");
        }
        Instant now = clock.instant();
        if (!now.isBefore(window.dropDeadline())) {
            return execution(result(connection, uid, term, offering, operationId,
                    SelectionStateDTO.ENROLLED, "DROP_CLOSED", "已超过退选截止时间"));
        }
        selectionDAO.drop(connection, uid, offeringId, now);
        selectionDAO.changeEnrolledCount(connection, offeringId, -1);
        selectionDAO.deletePlan(connection, uid, offeringId);
        CourseSelectionDAO.OfferingRow updated = selectionDAO.offering(connection, offeringId);
        return new Execution(result(connection, uid, term, updated, operationId,
                SelectionStateDTO.AVAILABLE, "DROPPED", "退选成功"), offeringId);
    }

    private Execution full(Connection connection, String uid, CourseTermDTO term,
                           CourseSelectionDAO.OfferingRow offering, String operationId,
                           String reason) throws SQLException {
        selectionDAO.upsertPlan(connection, uid, offering.offeringId(), "FULL", reason);
        return execution(result(connection, uid, term, offering, operationId,
                SelectionStateDTO.FULL, "FULL", reason));
    }

    private CourseMutationResultDTO transact(String uid, CourseTermDTO term, long offeringId,
                                             String operationId, String action,
                                             Mutation mutation) {
        validate(uid, term, offeringId, operationId);
        String digest = operationDAO.digest(action, term, offeringId);
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
                        throw new OperationConflictException(
                                "operationId 已用于不同的业务请求");
                    }
                    connection.commit();
                    execution = execution(stored.result());
                } else {
                    execution = mutation.execute(connection);
                    operationDAO.insert(connection, uid, operationId, action, offeringId,
                            digest, execution.result());
                    connection.commit();
                }
            } catch (RuntimeException | SQLException failure) {
                rollback(connection, failure);
                throw failure;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (CourseSelectionDAO.MissingRowException failure) {
            throw new NotFoundException("课程资源不存在", failure);
        } catch (OperationConflictException | ConflictException | NotFoundException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new DatabaseException("选课事务执行失败", failure);
        }

        if (execution.freedOfferingId() != null) {
            try {
                waitlistTrigger.offeringFreed(execution.freedOfferingId());
            } catch (RuntimeException triggerFailure) {
                System.err.println("候补推进触发失败，offeringId=" + execution.freedOfferingId());
            }
        }
        return execution.result();
    }

    private void lockTarget(Connection connection, long offeringId) throws SQLException {
        if (!selectionDAO.lockOfferingsAscending(connection, List.of(offeringId))
                .contains(offeringId)) {
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
        if (offering.status() != 2) throw new ConflictException("教学班当前未开放");
        return offering;
    }

    private void requireEligible(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (!selectionDAO.eligible(connection, uid, offeringId)) {
            throw new ConflictException("当前学生不具备该课程资格");
        }
    }

    private SelectionStateDTO currentState(Connection connection, String uid, long offeringId)
            throws SQLException {
        if (selectionDAO.enrolled(connection, uid, offeringId)) return SelectionStateDTO.ENROLLED;
        if (selectionDAO.hasActiveWaitlist(connection, uid, offeringId)) {
            return item(connection, uid, offeringId).getOffering().getSelectionState();
        }
        String plan = selectionDAO.planState(connection, uid, offeringId);
        if ("FULL".equals(plan)) return SelectionStateDTO.FULL;
        if ("PLANNED".equals(plan)) return SelectionStateDTO.PLANNED;
        return SelectionStateDTO.AVAILABLE;
    }

    private CourseMutationResultDTO result(Connection connection, String uid, CourseTermDTO term,
                                           CourseSelectionDAO.OfferingRow offering,
                                           String operationId, SelectionStateDTO finalState,
                                           String outcomeCode, String message) throws SQLException {
        CourseSelectionItemDTO item = item(connection, uid, offering.offeringId());
        CoursePlanSnapshotDTO snapshot = queryDAO.loadSelectionSnapshot(connection, uid,
                term.getAcademicYear(), term.getSemester());
        return new CourseMutationResultDTO(operationId, item, finalState,
                outcomeCode, message, snapshot);
    }

    private CourseSelectionItemDTO item(Connection connection, String uid, long offeringId)
            throws SQLException {
        CourseSelectionDAO.OfferingRow offering = selectionDAO.offering(connection, offeringId);
        List<CourseOfferingDTO> offerings = queryDAO.listCourseOfferings(connection, uid,
                offering.academicYear(), offering.semester(), offering.courseId());
        CourseOfferingDTO match = offerings.stream()
                .filter(candidate -> Long.toString(offeringId).equals(candidate.getOfferingId()))
                .findFirst().orElseThrow(() -> new ConflictException("课程当前不可见"));
        dto.course.CourseDTO course = queryDAO.listCourses(connection, uid,
                        offering.academicYear(), offering.semester()).stream()
                .filter(candidate -> match.getCourseId().equals(candidate.getCourseId()))
                .findFirst().orElseThrow(() -> new ConflictException("课程当前不可见"));
        return new CourseSelectionItemDTO(course, match);
    }

    private static void validate(String uid, CourseTermDTO term, long offeringId,
                                 String operationId) {
        if (uid == null || uid.isBlank()) throw new IllegalArgumentException("UID 不能为空");
        if (term == null || term.getAcademicYear() <= 0
                || term.getSemester() < 1 || term.getSemester() > 3) {
            throw new IllegalArgumentException("学期参数无效");
        }
        if (offeringId <= 0) throw new IllegalArgumentException("offeringId 必须为正整数");
        if (operationId == null || operationId.length() != 36) {
            throw new IllegalArgumentException("operationId 必须是 UUID");
        }
        try {
            if (!UUID.fromString(operationId).toString().equalsIgnoreCase(operationId)) {
                throw new IllegalArgumentException("operationId 必须是 UUID");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("operationId 必须是 UUID");
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Execution execution(CourseMutationResultDTO result) {
        return new Execution(result, null);
    }

    @FunctionalInterface
    private interface Mutation {
        Execution execute(Connection connection) throws SQLException;
    }

    private record Execution(CourseMutationResultDTO result, Long freedOfferingId) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
        public NotFoundException(String message, Throwable cause) { super(message, cause); }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) { super(message); }
    }

    public static class OperationConflictException extends IllegalArgumentException {
        public OperationConflictException(String message) { super(message); }
    }
}
