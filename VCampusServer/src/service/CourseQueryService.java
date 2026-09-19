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
import java.time.Clock;
import java.util.List;

/**
* Internal course-management type CourseQueryService.
*/
public class CourseQueryService {
    private final CourseQueryDAO queryDAO;
    private final CourseScheduleDAO scheduleDAO;
    private final CourseAcademicDAO academicDAO;
    private final Clock clock;

    /**
    * Handles the course-management responsibility of CourseQueryService.
    */
    public CourseQueryService() {
        this(new CourseQueryDAO(), new CourseScheduleDAO(), new CourseAcademicDAO(),
                Clock.systemUTC());
    }

    /** 供测试注入固定 {@link Clock}，从而不依赖运行当天的真实日期（与教师端同一注入方式）。 */
    public CourseQueryService(CourseQueryDAO queryDAO, CourseScheduleDAO scheduleDAO,
                              CourseAcademicDAO academicDAO, Clock clock) {
        this.queryDAO = queryDAO;
        this.scheduleDAO = scheduleDAO;
        this.academicDAO = academicDAO;
        this.clock = clock;
    }

    /**
    * Lists Terms data.
    */
    public List<CourseTermDTO> listTerms(String uid) {
        return read(connection -> queryDAO.listTerms(connection, uid));
    }

    /**
    * Lists Courses data.
    */
    public List<CourseDTO> listCourses(String uid, int academicYear, int semester) {
        return read(connection -> queryDAO.listCourses(connection, uid, academicYear, semester));
    }

    /**
    * Lists CourseOfferings data.
    */
    public List<CourseOfferingDTO> listCourseOfferings(String uid, int academicYear,
                                                       int semester, long courseId) {
        return read(connection -> {
            boolean visible = queryDAO.listCourses(connection, uid, academicYear, semester)
                    .stream().anyMatch(course -> Long.toString(courseId).equals(course.getCourseId()));
            if (!visible) throw new NotFoundException("课程不存在或不可见");
            return queryDAO.listCourseOfferings(connection, uid, academicYear, semester, courseId);
        });
    }

    /**
    * Obtains dSelectionSnapshot data.
    */
    public CoursePlanSnapshotDTO loadSelectionSnapshot(String uid, int academicYear,
                                                       int semester) {
        return read(connection -> queryDAO.loadSelectionSnapshot(
                connection, uid, academicYear, semester));
    }

    /**
    * 学生某个教学日历周的课表。
    *
    * <p>{@code week} 为 null（或非正）时取今天所在教学周，今天不在学期内时取最小教学周；给出明确
    * 周次时行为与旧实现完全一致（教学周范围之外就是一周空课表）。响应带上教学日历的
    * {@code minWeek}/{@code maxWeek}/{@code currentWeek}，周次控件与「回到本周」据此取值。学年/学期
    * 无效或该学期没有已发布的教学日历/方案时抛 {@link IllegalArgumentException} / {@link DatabaseException}，
    * 由上层映射为错误响应。
    */
    public CourseScheduleWeekDTO loadSchedule(String uid, int academicYear,
                                              int semester, Integer week) {
        return read(connection -> scheduleDAO.loadSchedule(
                connection, uid, academicYear, semester, week, clock));
    }

    /**
    * Obtains dNotices data.
    */
    public List<CourseNoticeDTO> loadNotices(String uid, int academicYear,
                                             int semester, int week) {
        return read(connection -> scheduleDAO.loadNotices(
                connection, uid, academicYear, semester, week));
    }

    /**
    * Obtains dGrades data.
    */
    public GradeSummaryDTO loadGrades(String uid, int academicYear, int semester) {
        return read(connection -> academicDAO.loadGrades(connection, uid, academicYear, semester));
    }

    /**
    * Obtains dTrainingPlan data.
    */
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
    /**
    * Internal course-management type SqlRead.
    */
    private interface SqlRead<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
    * Internal course-management type NotFoundException.
    */
    public static class NotFoundException extends RuntimeException {
        /**
        * Handles the course-management responsibility of NotFoundException.
        */
        public NotFoundException(String message) {
            super(message);
        }
    }
}
