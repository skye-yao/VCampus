package service;

import dao.AdminScheduleDAO;
import dao.TeacherCourseQueryDAO;
import dto.course.CourseTermDTO;
import dto.course.admin.schedule.ScheduleArrangementDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.teacher.TeacherOfferingDTO;
import dto.course.teacher.TeacherOfferingDetailDTO;
import dto.course.teacher.TeacherPageDTO;
import dto.course.teacher.TeacherRosterRowDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * 教师端只读课程查询。
 *
 * <p>{@code uid} 必须是服务端校验过的 Session 身份，本类不信任调用方传入的其它归属信息；
 * 所有涉及具体教学班的方法都先经 {@link TeacherAccessPolicy} 重新校验关系，再读取数据。
 * 页大小按设计第 3 节限制为 1..100，页码从 1 开始。
 */
public class TeacherCourseQueryService {
    private static final int MAX_PAGE_SIZE = 100;

    private final TeacherCourseQueryDAO queryDAO;
    private final AdminScheduleDAO scheduleDAO;
    private final TeacherAccessPolicy accessPolicy;

    public TeacherCourseQueryService() {
        this.queryDAO = new TeacherCourseQueryDAO();
        this.scheduleDAO = new AdminScheduleDAO();
        this.accessPolicy = new TeacherAccessPolicy();
    }

    public List<CourseTermDTO> listTerms(String uid) {
        return read(connection -> queryDAO.listTerms(connection, uid));
    }

    public TeacherPageDTO<TeacherOfferingDTO> listOfferings(String uid, int academicYear,
                                                            int semester, String query,
                                                            int page, int size) {
        requireTerm(academicYear, semester);
        int offset = offset(page, size);
        return read(connection -> new TeacherPageDTO<>(
                queryDAO.listOfferings(connection, uid, academicYear, semester, query, size, offset),
                queryDAO.countOfferings(connection, uid, academicYear, semester, query), page, size));
    }

    public TeacherOfferingDetailDTO getOffering(String uid, String offeringId) {
        long id = parseOfferingId(offeringId);
        return read(connection -> {
            accessPolicy.requireViewOffering(connection, uid, id);
            TeacherCourseQueryDAO.OfferingDetail detail = queryDAO.findOffering(connection, uid, id);
            if (detail == null) {
                throw new TeacherAccessPolicy.AccessDeniedException("没有查看该教学班的权限");
            }
            List<ScheduleResourceDTO> teachers = queryDAO.listOfferingTeachers(connection, id);
            return new TeacherOfferingDetailDTO(detail.offering(), teachers,
                    detail.offeringCollege(), detail.description());
        });
    }

    public TeacherPageDTO<TeacherRosterRowDTO> listOfferingStudents(String uid, String offeringId,
                                                                    String query,
                                                                    Integer enrollmentStatus,
                                                                    int page, int size) {
        long id = parseOfferingId(offeringId);
        requireEnrollmentStatus(enrollmentStatus);
        int offset = offset(page, size);
        return read(connection -> {
            accessPolicy.requireViewOffering(connection, uid, id);
            return new TeacherPageDTO<>(
                    queryDAO.listStudents(connection, id, query, enrollmentStatus, size, offset),
                    queryDAO.countStudents(connection, id, query, enrollmentStatus), page, size);
        });
    }

    /**
     * 该教学班当前正式方案中的全部安排。没有 PUBLISHED 方案时返回空列表，绝不回退到管理员的
     * DRAFT 工作方案。
     */
    public List<ScheduleArrangementDTO> listOfferingSchedules(String uid, String offeringId) {
        long id = parseOfferingId(offeringId);
        return read(connection -> {
            accessPolicy.requireViewOffering(connection, uid, id);
            TeacherCourseQueryDAO.Term term = queryDAO.findOfferingTerm(connection, id);
            if (term == null) {
                return List.of();
            }
            Long planId = queryDAO.findPublishedPlanId(connection,
                    term.academicYear(), term.semester());
            if (planId == null) {
                return List.of();
            }
            return scheduleDAO.listArrangements(connection, planId, id);
        });
    }

    // ------------------------------------------------------------------ 校验

    private static long parseOfferingId(String offeringId) {
        if (offeringId == null || !offeringId.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("offeringId 必须为正整数");
        }
        try {
            return Long.parseLong(offeringId);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("offeringId 超出有效范围");
        }
    }

    private static void requireEnrollmentStatus(Integer enrollmentStatus) {
        if (enrollmentStatus != null && enrollmentStatus != 2 && enrollmentStatus != 3) {
            throw new IllegalArgumentException("enrollmentStatus 只接受 2（正常）或 3（退课）");
        }
    }

    private static void requireTerm(int academicYear, int semester) {
        if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
    }

    private static int offset(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("页码必须大于 0，每页条数必须为 1 至 100");
        }
        return (page - 1) * size;
    }

    private static <T> T read(SqlRead<T> operation) {
        try (Connection connection = DBUtil.getConnection()) {
            return operation.execute(connection);
        } catch (SQLException failure) {
            throw new DatabaseException("教师课程查询失败", failure);
        }
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T execute(Connection connection) throws SQLException;
    }
}
