package service;

import com.google.gson.reflect.TypeToken;
import dao.AdminCourseOperationDAO;
import dao.AdminOfferingDAO;
import dto.course.CourseTermDTO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminOfferingDTO;
import dto.course.admin.catalog.OfferingEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import exception.DatabaseException;
import service.AdminOperationTransaction.Execution;
import util.DBUtil;

import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Set;

public class AdminOfferingService {
    private static final Type OFFERING_RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<AdminOfferingDTO>>() { }.getType();
    private static final Type VOID_RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<Void>>() { }.getType();
    private static final String TARGET_TYPE = "OFFERING";
    private static final String DUPLICATE_KEY_STATE = "23000";
    private static final Set<Integer> CREATE_STATUSES = Set.of(1, 2);
    private static final Set<Integer> UPDATE_STATUSES = Set.of(1, 2, 3);
    private static final int CANCELLED = 4;
    private static final int DRAFT = 1;

    private final AdminOfferingDAO offeringDAO;
    private final AdminOperationTransaction transaction;
    private final Clock clock;

    public AdminOfferingService() {
        this(Clock.systemUTC());
    }

    AdminOfferingService(Clock clock) {
        this(new AdminOfferingDAO(), new AdminCourseOperationDAO(), clock);
    }

    AdminOfferingService(AdminOfferingDAO offeringDAO, AdminCourseOperationDAO operationDAO,
                         Clock clock) {
        this.offeringDAO = offeringDAO;
        this.transaction = new AdminOperationTransaction(operationDAO, clock, TARGET_TYPE,
                "教学班管理事务执行失败");
        this.clock = clock;
    }

    public List<AdminOfferingDTO> list(String courseId) {
        long id = AdminOperationTransaction.parseId(courseId, "courseId");
        try (Connection connection = DBUtil.getConnection()) {
            return offeringDAO.list(connection, id);
        } catch (SQLException failure) {
            throw new DatabaseException("教学班列表查询失败", failure);
        }
    }

    public List<CourseTermDTO> listTerms() {
        try (Connection connection = DBUtil.getConnection()) {
            return offeringDAO.listTerms(connection);
        } catch (SQLException failure) {
            throw new DatabaseException("学期列表查询失败", failure);
        }
    }

    public AdminOperationResultDTO<AdminOfferingDTO> create(String adminUid,
                                                            OfferingEditorRequestDTO request) {
        AdminOfferingDAO.OfferingFields fields = fields(request, CREATE_STATUSES);
        Staff staff = staff(request);
        String operationId = request.getOperationId();
        return transaction.execute(adminUid, operationId, AdminCourseActions.CREATE_OFFERING,
                request, OFFERING_RESULT_TYPE, connection -> { }, connection -> {
                    requireActiveCourse(connection, fields.courseId());
                    requireStaff(connection, staff);
                    long offeringId = uniqueCode(() -> offeringDAO.insert(connection, fields, adminUid));
                    offeringDAO.replaceStaff(connection, offeringId, staff.teacherUid(),
                            staff.assistantUid());
                    return new Execution<>(ok(operationId, "教学班已创建",
                            offeringDAO.find(connection, offeringId)), offeringId);
                });
    }

    public AdminOperationResultDTO<AdminOfferingDTO> update(String adminUid,
                                                            OfferingEditorRequestDTO request) {
        AdminOfferingDAO.OfferingFields fields = fields(request, UPDATE_STATUSES);
        Staff staff = staff(request);
        long offeringId = AdminOperationTransaction.parseId(request.getOfferingId(), "offeringId");
        int expectedVersion = AdminOperationTransaction.version(request.getExpectedVersion());
        String operationId = request.getOperationId();
        return transaction.execute(adminUid, operationId, AdminCourseActions.UPDATE_OFFERING,
                request, OFFERING_RESULT_TYPE,
                connection -> offeringDAO.lock(connection, offeringId), connection -> {
                    AdminOfferingDAO.OfferingRow current = requireOpen(connection, offeringId);
                    boolean termChanged = current.courseId() != fields.courseId()
                            || current.academicYear() != fields.academicYear()
                            || current.semester() != fields.semester();
                    if (termChanged && offeringDAO.hasEnrollment(connection, offeringId)) {
                        throw new IllegalArgumentException("已存在选课记录，课程与学期不可修改");
                    }
                    if (fields.capacity() < current.enrolledCount()) {
                        throw new IllegalArgumentException("容量不能低于已选人数");
                    }
                    if (current.courseId() != fields.courseId()) {
                        requireActiveCourse(connection, fields.courseId());
                    }
                    requireStaff(connection, staff);
                    int affected = uniqueCode(() -> offeringDAO.update(
                            connection, offeringId, expectedVersion, fields));
                    requireAffected(connection, offeringId, affected);
                    offeringDAO.replaceStaff(connection, offeringId, staff.teacherUid(),
                            staff.assistantUid());
                    return new Execution<>(ok(operationId, "教学班已更新",
                            offeringDAO.find(connection, offeringId)), offeringId);
                });
    }

    public AdminOperationResultDTO<AdminOfferingDTO> cancel(String adminUid, String offeringId,
                                                            int expectedVersion,
                                                            String operationId) {
        long id = AdminOperationTransaction.parseId(offeringId, "offeringId");
        int version = AdminOperationTransaction.version(expectedVersion);
        return transaction.execute(adminUid, operationId, AdminCourseActions.CANCEL_OFFERING,
                AdminOperationTransaction.targetRequest(id, version), OFFERING_RESULT_TYPE,
                connection -> offeringDAO.lock(connection, id), connection -> {
                    requireOpen(connection, id);
                    requireAffected(connection, id, offeringDAO.cancel(
                            connection, id, version, adminUid, clock.instant()));
                    return new Execution<>(ok(operationId, "教学班已取消",
                            offeringDAO.find(connection, id)), id);
                });
    }

    public AdminOperationResultDTO<Void> deleteDraft(String adminUid, String offeringId,
                                                     int expectedVersion, String operationId) {
        long id = AdminOperationTransaction.parseId(offeringId, "offeringId");
        int version = AdminOperationTransaction.version(expectedVersion);
        return transaction.execute(adminUid, operationId, AdminCourseActions.DELETE_DRAFT_OFFERING,
                AdminOperationTransaction.targetRequest(id, version), VOID_RESULT_TYPE,
                connection -> offeringDAO.lock(connection, id), connection -> {
                    AdminOfferingDAO.OfferingRow current = requireRow(connection, id);
                    if (current.status() != DRAFT) {
                        throw new ConflictException("只有未开放的教学班可以删除",
                                offeringDAO.find(connection, id));
                    }
                    if (current.version() != version) {
                        throw new ConflictException("教学班版本或状态已变化，请刷新后重试",
                                offeringDAO.find(connection, id));
                    }
                    if (offeringDAO.hasDependencies(connection, id)) {
                        throw new ConflictException("教学班已被引用，无法删除",
                                offeringDAO.find(connection, id));
                    }
                    offeringDAO.deleteStaff(connection, id);
                    requireAffected(connection, id, offeringDAO.deleteDraft(connection, id, version));
                    return new Execution<Void>(new AdminOperationResultDTO<>(
                            operationId, "OK", "教学班已删除", null, List.of()), id);
                });
    }

    private AdminOfferingDAO.OfferingRow requireRow(Connection connection, long offeringId)
            throws SQLException {
        AdminOfferingDAO.OfferingRow current = offeringDAO.row(connection, offeringId);
        if (current == null) throw new NotFoundException("教学班不存在");
        return current;
    }

    private AdminOfferingDAO.OfferingRow requireOpen(Connection connection, long offeringId)
            throws SQLException {
        AdminOfferingDAO.OfferingRow current = requireRow(connection, offeringId);
        if (current.status() == CANCELLED) {
            throw new ConflictException("教学班已取消", offeringDAO.find(connection, offeringId));
        }
        return current;
    }

    private void requireAffected(Connection connection, long offeringId, int affected)
            throws SQLException {
        if (affected > 0) return;
        AdminOfferingDTO latest = offeringDAO.find(connection, offeringId);
        if (latest == null) throw new NotFoundException("教学班不存在");
        throw new ConflictException("教学班版本或状态已变化，请刷新后重试", latest);
    }

    private void requireActiveCourse(Connection connection, long courseId) throws SQLException {
        String status = offeringDAO.courseStatus(connection, courseId);
        if (status == null) throw new NotFoundException("课程不存在");
        if (!"ACTIVE".equals(status)) throw new ConflictException("课程已归档，无法开设教学班");
    }

    private void requireStaff(Connection connection, Staff staff) throws SQLException {
        if (!offeringDAO.isTeacher(connection, staff.teacherUid())) {
            throw new IllegalArgumentException("任课教师不存在或不是教师");
        }
        if (staff.assistantUid() != null && !offeringDAO.isTeacher(connection, staff.assistantUid())) {
            throw new IllegalArgumentException("助教不存在或不是教师");
        }
    }

    private static <T> T uniqueCode(SqlCall<T> call) throws SQLException {
        try {
            return call.call();
        } catch (SQLException failure) {
            if (DUPLICATE_KEY_STATE.equals(failure.getSQLState())) {
                throw new ConflictException("教学班代码已存在");
            }
            throw failure;
        }
    }

    private static AdminOperationResultDTO<AdminOfferingDTO> ok(String operationId,
                                                                String message,
                                                                AdminOfferingDTO entity) {
        return new AdminOperationResultDTO<>(operationId, "OK", message, entity, List.of());
    }

    private static AdminOfferingDAO.OfferingFields fields(OfferingEditorRequestDTO request,
                                                          Set<Integer> allowedStatuses) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String code = AdminOperationTransaction.requireText(request.getOfferingCode(), "教学班代码");
        long courseId = AdminOperationTransaction.parseId(request.getCourseId(), "courseId");
        if (request.getAcademicYear() <= 0) throw new IllegalArgumentException("学年无效");
        if (request.getSemester() < 1 || request.getSemester() > 3) {
            throw new IllegalArgumentException("学期无效");
        }
        if (request.getCapacity() < 0) throw new IllegalArgumentException("容量不能为负数");
        if (!allowedStatuses.contains(request.getStatus())) {
            throw new IllegalArgumentException("教学班状态无效");
        }
        return new AdminOfferingDAO.OfferingFields(code, courseId, request.getAcademicYear(),
                request.getSemester(), request.getCapacity(), request.getStatus());
    }

    private static Staff staff(OfferingEditorRequestDTO request) {
        String teacherUid = AdminOperationTransaction.requireText(request.getTeacherUid(), "任课教师");
        String assistantUid = AdminOperationTransaction.blankToNull(request.getAssistantUid());
        if (teacherUid.equals(assistantUid)) {
            throw new IllegalArgumentException("助教不能与任课教师相同");
        }
        return new Staff(teacherUid, assistantUid);
    }

    @FunctionalInterface
    private interface SqlCall<T> {
        T call() throws SQLException;
    }

    private record Staff(String teacherUid, String assistantUid) {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) { super(message); }
    }

    public static class ConflictException extends RuntimeException {
        private final AdminOfferingDTO latest;

        public ConflictException(String message) { this(message, null); }

        public ConflictException(String message, AdminOfferingDTO latest) {
            super(message);
            this.latest = latest;
        }

        public AdminOfferingDTO getLatest() { return latest; }
    }
}
