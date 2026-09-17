package service;

import dao.CourseAcademicDAO;
import dao.CourseQueryDAO;
import dao.CourseScheduleDAO;
import dto.course.CourseDTO;
import dto.course.CourseNoticeDTO;
import dto.course.CourseOfferingDTO;
import dto.course.CoursePlanSnapshotDTO;
import dto.course.CourseScheduleWeekDTO;
import dto.course.CourseTermDTO;
import dto.course.GradeSummaryDTO;
import dto.course.TrainingPlanGroupDTO;
import exception.DatabaseException;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class CourseQueryService {
    private final CourseQueryDAO queryDAO;
    private final CourseScheduleDAO scheduleDAO;
    private final CourseAcademicDAO academicDAO;

    public CourseQueryService() {
        this(new CourseQueryDAO(), new CourseScheduleDAO(), new CourseAcademicDAO());
    }

    CourseQueryService(CourseQueryDAO queryDAO, CourseScheduleDAO scheduleDAO,
                       CourseAcademicDAO academicDAO) {
        this.queryDAO = queryDAO;
        this.scheduleDAO = scheduleDAO;
        this.academicDAO = academicDAO;
    }

    public List<CourseTermDTO> listTerms(String uid) {
        return read(connection -> queryDAO.listTerms(connection, uid));
    }

    public List<CourseDTO> listCourses(String uid, int academicYear, int semester) {
        return read(connection -> queryDAO.listCourses(connection, uid, academicYear, semester));
    }

    public List<CourseOfferingDTO> listCourseOfferings(String uid, int academicYear,
                                                       int semester, long courseId) {
        return read(connection -> {
            boolean visible = queryDAO.listCourses(connection, uid, academicYear, semester)
                    .stream().anyMatch(course -> Long.toString(courseId).equals(course.getCourseId()));
            if (!visible) throw new NotFoundException("课程不存在或不可见");
            return queryDAO.listCourseOfferings(connection, uid, academicYear, semester, courseId);
        });
    }

    public CoursePlanSnapshotDTO loadSelectionSnapshot(String uid, int academicYear,
                                                       int semester) {
        return read(connection -> queryDAO.loadSelectionSnapshot(
                connection, uid, academicYear, semester));
    }

    public CourseScheduleWeekDTO loadSchedule(String uid, int academicYear,
                                              int semester, int week) {
        return read(connection -> scheduleDAO.loadSchedule(
                connection, uid, academicYear, semester, week));
    }

    public List<CourseNoticeDTO> loadNotices(String uid, int academicYear,
                                             int semester, int week) {
        return read(connection -> scheduleDAO.loadNotices(
                connection, uid, academicYear, semester, week));
    }

    public GradeSummaryDTO loadGrades(String uid, int academicYear, int semester) {
        return read(connection -> academicDAO.loadGrades(connection, uid, academicYear, semester));
    }

    public List<TrainingPlanGroupDTO> loadTrainingPlan(String uid) {
        return read(connection -> academicDAO.loadTrainingPlan(connection, uid));
    }

    private static <T> T read(SqlRead<T> operation) {
        try (Connection connection = DBUtil.getConnection()) {
            return operation.execute(connection);
        } catch (NotFoundException failure) {
            throw failure;
        } catch (SQLException failure) {
            throw new DatabaseException("课程查询失败", failure);
        }
    }

    @FunctionalInterface
    private interface SqlRead<T> {
        T execute(Connection connection) throws SQLException;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
