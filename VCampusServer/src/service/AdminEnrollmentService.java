package service;

import com.google.gson.reflect.TypeToken;
import dao.AdminCourseOperationDAO;
import dao.AdminEnrollmentDAO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.enrollment.AdminEnrollmentPageDTO;
import dto.course.admin.enrollment.AdminEnrollmentPreviewDTO;
import dto.course.admin.enrollment.AdminEnrollmentRequestDTO;
import dto.course.admin.enrollment.OfferingStudentDTO;
import dto.course.admin.enrollment.StudentSearchResultDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleConflictSeverityDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
* Internal course-management type AdminEnrollmentService.
*/
public class AdminEnrollmentService {
    private static final Type RESULT_TYPE = new TypeToken<AdminOperationResultDTO<OfferingStudentDTO>>() { }.getType();
    private static final Logger LOG = Logger.getLogger(AdminEnrollmentService.class.getName());
    private final AdminEnrollmentDAO dao;
    private final AdminCourseOperationDAO operations;
    private final AdminEnrollmentRiskService riskService;
    private final Clock clock;
    private final WaitlistAdvanceTrigger waitlistTrigger;

    /**
    * Handles the course-management responsibility of AdminEnrollmentService.
    */
    public AdminEnrollmentService() {
        this(new AdminEnrollmentDAO(), new AdminCourseOperationDAO(), new AdminEnrollmentRiskService(),
                Clock.systemUTC(), new CourseWaitlistService());
    }

    /**
    * Handles the course-management responsibility of AdminEnrollmentService.
    */
    public AdminEnrollmentService(AdminEnrollmentDAO dao, AdminCourseOperationDAO operations,
            AdminEnrollmentRiskService riskService, Clock clock, WaitlistAdvanceTrigger waitlistTrigger) {
        this.dao = dao;
        this.operations = operations;
        this.riskService = riskService;
        this.clock = clock;
        this.waitlistTrigger = waitlistTrigger;
    }

    /**
    * Handles the course-management responsibility of searchStudents.
    */
    public AdminEnrollmentPageDTO<StudentSearchResultDTO> searchStudents(String query, int page, int size) {
        validatePage(page, size);
        try (Connection connection = DBUtil.getConnection()) {
            return dao.searchStudents(connection, normalizeQuery(query), page, size);
        } catch (SQLException failure) { throw new DatabaseException("查询学生失败", failure); }
    }

    /**
    * Lists OfferingStudents data.
    */
    public AdminEnrollmentPageDTO<OfferingStudentDTO> listOfferingStudents(String offeringId,
            String query, int page, int size) {
        long id = offeringId(offeringId);
        validatePage(page, size);
        try (Connection connection = DBUtil.getConnection()) {
            requireOffering(connection, id);
            return dao.listOfferingStudents(connection, id, normalizeQuery(query), page, size);
        } catch (SQLException failure) { throw new DatabaseException("查询教学班学生失败", failure); }
    }

    /**
    * Handles the course-management responsibility of previewAdminEnrollment.
    */
    public AdminEnrollmentPreviewDTO previewAdminEnrollment(String offeringId, String studentUid) {
        long id = offeringId(offeringId);
        String uid = uid(studentUid, "studentUid");
        try (Connection connection = DBUtil.getConnection()) {
            AdminEnrollmentDAO.OfferingRow offering = requireOffering(connection, id);
            AdminEnrollmentDAO.StudentRow student = requireStudent(connection, uid);
            List<ScheduleConflictDTO> risks = riskService.calculate(connection, student, offering,
                    dao.findEnrollment(connection, uid, id, false), false, clock.instant());
            return new AdminEnrollmentPreviewDTO(Long.toString(id), uid, risks);
        } catch (SQLException failure) { throw new DatabaseException("检查添加学生风险失败", failure); }
    }

    /**
    * Handles the course-management responsibility of addStudentToOffering.
    */
    public AdminOperationResultDTO<OfferingStudentDTO> addStudentToOffering(String adminUid,
            AdminEnrollmentRequestDTO request) {
        return transact(adminUid, request, false);
    }

    /**
    * Removes or cancels removeStudentFromOffering data.
    */
    public AdminOperationResultDTO<OfferingStudentDTO> removeStudentFromOffering(String adminUid,
            AdminEnrollmentRequestDTO request) {
        return transact(adminUid, request, true);
    }

    private AdminOperationResultDTO<OfferingStudentDTO> transact(String adminUid,
            AdminEnrollmentRequestDTO raw, boolean removal) {
        String admin = uid(adminUid, "adminUid");
        AdminEnrollmentRequestDTO request = validate(raw);
        String action = removal ? AdminCourseActions.REMOVE_STUDENT_FROM_OFFERING
                : AdminCourseActions.ADD_STUDENT_TO_OFFERING;
        String digest = operations.digest(action, request);
        Execution execution;
        try (Connection connection = DBUtil.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            Throwable inFlight = null;
            boolean committed = false;
            try {
                AdminCourseOperationDAO.StoredOperation stored = operations.find(connection, admin, request.getOperationId());
                if (stored == null) {
                    dao.lockStudentProfile(connection, request.getStudentUid());
                    stored = operations.find(connection, admin, request.getOperationId());
                }
                if (stored != null) {
                    execution = new Execution(replay(stored, digest), null);
                } else {
                    execution = mutate(connection, request, removal);
                    execution = persist(connection, admin, action, request, digest, execution);
                }
                connection.commit();
                committed = true;
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
        } catch (SQLException failure) { throw new DatabaseException("管理教学班学生事务执行失败", failure); }

        if (execution.freedOfferingId() != null) {
            try { waitlistTrigger.offeringFreed(execution.freedOfferingId()); }
            catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "候补推进触发失败，offeringId=" + execution.freedOfferingId(), failure);
            }
        }
        return execution.result();
    }

    private Execution mutate(Connection connection, AdminEnrollmentRequestDTO request, boolean removal)
            throws SQLException {
        String uid = request.getStudentUid();
        long offeringId = Long.parseLong(request.getOfferingId());
        AdminEnrollmentDAO.StudentRow student = requireStudent(connection, uid);
        // The profile lock stabilizes this set. Lock all active offerings, so even a simultaneous
        // edit of the target's term cannot make us acquire an additional lower ID out of order.
        Set<Long> affected = new LinkedHashSet<>(dao.activeOfferingIds(connection, uid));
        affected.add(offeringId);
        if (!dao.lockOfferingsAscending(connection, affected).contains(offeringId)) {
            throw new NotFoundException("教学班不存在");
        }
        AdminEnrollmentDAO.OfferingRow offering = requireOffering(connection, offeringId);
        AdminEnrollmentDAO.EnrollmentRow enrollment = dao.findEnrollment(connection, uid, offeringId, true);
        if (removal && enrollment != null) dao.lockGradeWorkflow(connection, enrollment.enrollmentId());
        Instant now = clock.instant();
        List<ScheduleConflictDTO> risks = riskService.calculate(connection, student, offering, enrollment, removal, now);
        OfferingStudentDTO current = dao.findOfferingStudent(connection, uid, offeringId);
        if (risks.stream().anyMatch(r -> r.getSeverity() == ScheduleConflictSeverityDTO.BLOCKING)
                || (!request.isForce() && !risks.isEmpty())) {
            throw new ConflictException("当前条件不允许直接执行，请查看风险信息", current, risks);
        }
        String message;
        Long freed = null;
        if (removal) {
            if (enrollment == null || enrollment.status() != 2) {
                throw new ConflictException("该学生当前未选中此教学班", current, risks);
            }
            dao.drop(connection, enrollment.enrollmentId(), now);
            dao.changeEnrolledCount(connection, offeringId, -1);
            dao.deletePlan(connection, uid, offeringId);
            freed = offeringId;
            message = "已从教学班移除学生";
        } else {
            boolean alreadyEnrolled = enrollment != null && enrollment.status() == 2;
            if (!alreadyEnrolled) {
                dao.enroll(connection, uid, offering, enrollment, now);
                dao.changeEnrolledCount(connection, offeringId, 1);
            }
            dao.settleWaitlistAndPlan(connection, uid, offeringId);
            message = alreadyEnrolled ? "该学生已在教学班中" : "已将学生加入教学班";
        }
        OfferingStudentDTO updated = dao.findOfferingStudent(connection, uid, offeringId);
        return new Execution(new AdminOperationResultDTO<>(request.getOperationId(), "OK", message, updated, risks), freed);
    }

    private Execution persist(Connection connection, String adminUid, String action,
            AdminEnrollmentRequestDTO request, String digest, Execution execution) throws SQLException {
        try {
            operations.insert(connection, adminUid, request.getOperationId(), action, "OFFERING",
                    request.getOfferingId(), digest, request, execution.result().getOutcomeCode(),
                    execution.result(), clock.instant(), execution.result().getConflicts(),
                    request.isForce(), request.getOverrideReason());
            return execution;
        } catch (SQLException failure) {
            if (failure.getErrorCode() != 1062) throw failure;
            // Different student/target locks do not serialize the operation-log primary key.
            // A losing INSERT waits for the winner to commit; discard every local state change
            // before interpreting the now-committed operation as a replay or a digest conflict.
            try { connection.rollback(); }
            catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); throw failure; }
            AdminCourseOperationDAO.StoredOperation winner = operations.find(connection, adminUid, request.getOperationId());
            if (winner == null) throw failure;
            return new Execution(replay(winner, digest), null);
        }
    }

    private AdminOperationResultDTO<OfferingStudentDTO> replay(AdminCourseOperationDAO.StoredOperation stored,
                                                               String digest) {
        if (!digest.equals(stored.requestDigest())) {
            throw new ConflictException("operationId 已用于不同的业务请求", null, List.of());
        }
        return operations.decode(stored.responseJson(), RESULT_TYPE);
    }

    private AdminEnrollmentDAO.OfferingRow requireOffering(Connection connection, long offeringId) throws SQLException {
        AdminEnrollmentDAO.OfferingRow row = dao.findOffering(connection, offeringId);
        if (row == null) throw new NotFoundException("教学班不存在");
        return row;
    }

    private AdminEnrollmentDAO.StudentRow requireStudent(Connection connection, String uid) throws SQLException {
        AdminEnrollmentDAO.StudentRow row = dao.findStudent(connection, uid);
        if (row == null) throw new NotFoundException("学生不存在");
        return row;
    }

    private static AdminEnrollmentRequestDTO validate(AdminEnrollmentRequestDTO request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String operation = request.getOperationId();
        try {
            if (operation == null || !UUID.fromString(operation).toString().equalsIgnoreCase(operation)) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException failure) { throw new IllegalArgumentException("operationId 必须是 UUID"); }
        long offeringId = offeringId(request.getOfferingId());
        String studentUid = uid(request.getStudentUid(), "studentUid");
        String reason = request.getOverrideReason() == null ? null : request.getOverrideReason().trim();
        if (request.isForce() && (reason == null || reason.isEmpty())) {
            throw new IllegalArgumentException("强制操作必须填写原因");
        }
        if (reason != null && reason.length() > 500) throw new IllegalArgumentException("强制原因不能超过 500 字符");
        return new AdminEnrollmentRequestDTO(UUID.fromString(operation).toString(), Long.toString(offeringId),
                studentUid, request.isForce(), reason);
    }

    private static long offeringId(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) throw new IllegalArgumentException("offeringId 必须为正整数");
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("offeringId 超出有效范围"); }
    }

    private static String uid(String value, String field) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 32) {
            throw new IllegalArgumentException(field + " 必须为 1 至 32 个字符");
        }
        return value.trim();
    }

    private static String normalizeQuery(String query) { return query == null ? "" : query.trim(); }

    private static void validatePage(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("页码必须大于 0，每页条数必须为 1 至 100");
    }

    /** Null-safe: an unfinished transaction is rolled back even when no failure is in flight,
    *  and a failed rollback is swallowed rather than replacing an escaping {@link Error}. */
    private static void rollback(Connection connection, Throwable failure) {
        try { connection.rollback(); }
        catch (SQLException rollbackFailure) { if (failure != null) failure.addSuppressed(rollbackFailure); }
    }

    private static void restoreAutoCommit(Connection connection, boolean autoCommit, Throwable inFlight) {
        try { connection.setAutoCommit(autoCommit); }
        catch (SQLException failure) {
            if (inFlight != null) inFlight.addSuppressed(failure);
            else throw new DatabaseException("管理教学班学生事务执行失败", failure);
        }
    }

    /**
    * Internal course-management type Execution.
    */
    private record Execution(AdminOperationResultDTO<OfferingStudentDTO> result, Long freedOfferingId) { }

    /**
    * Internal course-management type ConflictException.
    */
    public static class ConflictException extends RuntimeException {
        private final OfferingStudentDTO entity;
        private final List<ScheduleConflictDTO> conflicts;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, OfferingStudentDTO entity, List<ScheduleConflictDTO> conflicts) {
            super(message);
            this.entity = entity;
            this.conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        }

        /**
        * Obtains Entity data.
        */
        public OfferingStudentDTO getEntity() { return entity; }
        /**
        * Obtains Conflicts data.
        */
        public List<ScheduleConflictDTO> getConflicts() { return conflicts; }
    }

    /**
    * Internal course-management type NotFoundException.
    */
    public static class NotFoundException extends RuntimeException {
        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) { super(message); }
    }
}
