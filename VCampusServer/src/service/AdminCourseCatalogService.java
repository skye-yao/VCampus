package service;

import com.google.gson.reflect.TypeToken;
import dao.AdminCourseCatalogDAO;
import dao.AdminCourseOperationDAO;
import dto.course.admin.AdminCourseActions;
import dto.course.admin.catalog.AdminCourseDTO;
import dto.course.admin.catalog.CourseEditorRequestDTO;
import dto.course.admin.result.AdminOperationResultDTO;
import exception.DatabaseException;
import service.AdminOperationTransaction.Execution;
import util.DBUtil;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;

/**
* Internal course-management type AdminCourseCatalogService.
*/
public class AdminCourseCatalogService {
    private static final Type RESULT_TYPE =
            new TypeToken<AdminOperationResultDTO<AdminCourseDTO>>() { }.getType();
    private static final String TARGET_TYPE = "COURSE";
    private static final String DUPLICATE_KEY_STATE = "23000";
    private static final BigDecimal MAX_CREDIT = BigDecimal.TEN;

    private final AdminCourseCatalogDAO catalogDAO;
    private final AdminOperationTransaction transaction;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of AdminCourseCatalogService.
    */
    public AdminCourseCatalogService() {
        this(Clock.systemUTC());
    }

    AdminCourseCatalogService(Clock clock) {
        this(new AdminCourseCatalogDAO(), new AdminCourseOperationDAO(), clock);
    }

    AdminCourseCatalogService(AdminCourseCatalogDAO catalogDAO,
                              AdminCourseOperationDAO operationDAO, Clock clock) {
        this.catalogDAO = catalogDAO;
        this.transaction = new AdminOperationTransaction(operationDAO, clock, TARGET_TYPE,
                "课程管理事务执行失败");
        this.clock = clock;
    }

    /**
    * Lists  data.
    */
    public List<AdminCourseDTO> list(String query, String status, Integer academicYear,
                                     Integer semester) {
        String statusFilter = AdminOperationTransaction.blankToNull(status);
        if (statusFilter != null && !"ACTIVE".equals(statusFilter)
                && !"ARCHIVED".equals(statusFilter)) {
            throw new IllegalArgumentException("课程状态无效");
        }
        if (academicYear != null && semester == null
                || academicYear == null && semester != null) {
            throw new IllegalArgumentException("学期无效");
        }
        if (academicYear != null && (academicYear <= 0 || semester < 1 || semester > 3)) {
            throw new IllegalArgumentException("学期无效");
        }
        try (Connection connection = DBUtil.getConnection()) {
            return catalogDAO.list(connection, AdminOperationTransaction.blankToNull(query),
                    statusFilter, academicYear, semester);
        } catch (SQLException failure) {
            throw new DatabaseException("课程列表查询失败", failure);
        }
    }

    /**
    * Creates create data.
    */
    public AdminOperationResultDTO<AdminCourseDTO> create(String adminUid,
                                                          CourseEditorRequestDTO request) {
        AdminCourseCatalogDAO.CourseFields fields = fields(request);
        String operationId = request.getOperationId();
        return transaction.execute(adminUid, operationId, AdminCourseActions.CREATE_COURSE,
                request, RESULT_TYPE, connection -> { }, connection -> {
                    long courseId;
                    try {
                        courseId = catalogDAO.insert(connection, fields);
                    } catch (SQLException failure) {
                        if (DUPLICATE_KEY_STATE.equals(failure.getSQLState())) {
                            throw new ConflictException("课程代码已存在");
                        }
                        throw failure;
                    }
                    return new Execution<>(ok(operationId, "课程已创建",
                            catalogDAO.find(connection, courseId)), courseId);
                });
    }

    /**
    * Persists update data.
    */
    public AdminOperationResultDTO<AdminCourseDTO> update(String adminUid,
                                                          CourseEditorRequestDTO request) {
        AdminCourseCatalogDAO.CourseFields fields = fields(request);
        long courseId = AdminOperationTransaction.parseId(request.getCourseId(), "courseId");
        int expectedVersion = AdminOperationTransaction.version(request.getExpectedVersion());
        String operationId = request.getOperationId();
        return transaction.execute(adminUid, operationId, AdminCourseActions.UPDATE_COURSE,
                request, RESULT_TYPE, connection -> catalogDAO.lock(connection, courseId),
                connection -> {
                    AdminCourseDTO current = required(connection, courseId);
                    if (!current.getCourseCode().equals(fields.courseCode())) {
                        throw new IllegalArgumentException("课程代码创建后不可修改");
                    }
                    int affected = catalogDAO.update(connection, courseId, expectedVersion, fields);
                    return new Execution<>(committed(connection, courseId, affected,
                            operationId, "课程已更新"), courseId);
                });
    }

    /**
    * Handles the course-management responsibility of archive.
    */
    public AdminOperationResultDTO<AdminCourseDTO> archive(String adminUid, String courseId,
                                                           int expectedVersion,
                                                           String operationId) {
        long id = AdminOperationTransaction.parseId(courseId, "courseId");
        int version = AdminOperationTransaction.version(expectedVersion);
        return transaction.execute(adminUid, operationId, AdminCourseActions.ARCHIVE_COURSE,
                AdminOperationTransaction.targetRequest(id, version), RESULT_TYPE,
                connection -> catalogDAO.lock(connection, id), connection -> {
                    AdminCourseDTO current = required(connection, id);
                    if (catalogDAO.hasActiveOfferings(connection, id)) {
                        throw new ConflictException("课程仍有未取消的教学班，无法归档", current);
                    }
                    int affected = catalogDAO.archive(connection, id, version, adminUid,
                            clock.instant());
                    return new Execution<>(committed(connection, id, affected,
                            operationId, "课程已归档"), id);
                });
    }

    /**
    * Handles the course-management responsibility of restore.
    */
    public AdminOperationResultDTO<AdminCourseDTO> restore(String adminUid, String courseId,
                                                           int expectedVersion,
                                                           String operationId) {
        long id = AdminOperationTransaction.parseId(courseId, "courseId");
        int version = AdminOperationTransaction.version(expectedVersion);
        return transaction.execute(adminUid, operationId, AdminCourseActions.RESTORE_COURSE,
                AdminOperationTransaction.targetRequest(id, version), RESULT_TYPE,
                connection -> catalogDAO.lock(connection, id), connection -> {
                    required(connection, id);
                    int affected = catalogDAO.restore(connection, id, version);
                    return new Execution<>(committed(connection, id, affected,
                            operationId, "课程已恢复"), id);
                });
    }

    private AdminCourseDTO required(Connection connection, long courseId) throws SQLException {
        AdminCourseDTO current = catalogDAO.find(connection, courseId);
        if (current == null) throw new NotFoundException("课程不存在");
        return current;
    }

    private AdminOperationResultDTO<AdminCourseDTO> committed(Connection connection, long courseId,
                                                              int affected, String operationId,
                                                              String message) throws SQLException {
        AdminCourseDTO latest = required(connection, courseId);
        if (affected == 0) {
            throw new ConflictException("课程版本或状态已变化，请刷新后重试", latest);
        }
        return ok(operationId, message, latest);
    }

    private static AdminOperationResultDTO<AdminCourseDTO> ok(String operationId, String message,
                                                              AdminCourseDTO entity) {
        return new AdminOperationResultDTO<>(operationId, "OK", message, entity, List.of());
    }

    private static AdminCourseCatalogDAO.CourseFields fields(CourseEditorRequestDTO request) {
        if (request == null) throw new IllegalArgumentException("请求不能为空");
        String code = AdminOperationTransaction.requireText(request.getCourseCode(), "课程代码");
        String name = AdminOperationTransaction.requireText(request.getCourseName(), "课程名称");
        int type = AdminCourseCatalogDAO.courseTypeCode(request.getCourseType());
        if (!(request.getCredit() > 0)) throw new IllegalArgumentException("学分必须大于 0");
        BigDecimal credit = BigDecimal.valueOf(request.getCredit());
        if (credit.compareTo(MAX_CREDIT) >= 0) throw new IllegalArgumentException("学分必须小于 10");
        if (request.getCreditHours() <= 0) throw new IllegalArgumentException("学时必须大于 0");
        return new AdminCourseCatalogDAO.CourseFields(code, name, type, credit,
                request.getCreditHours(),
                AdminOperationTransaction.blankToNull(request.getDescription()),
                AdminOperationTransaction.blankToNull(request.getPrerequisites()),
                request.isAllowCrossMajor(), request.isFinalExam());
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

    /**
    * Internal course-management type ConflictException.
    */
    public static class ConflictException extends RuntimeException {
        private final AdminCourseDTO latest;

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message) { this(message, null); }

        /**
        * Handles the course-management responsibility of ConflictException.
        */
        public ConflictException(String message, AdminCourseDTO latest) {
            super(message);
            this.latest = latest;
        }

        /**
        * Obtains Latest data.
        */
        public AdminCourseDTO getLatest() { return latest; }
    }
}
