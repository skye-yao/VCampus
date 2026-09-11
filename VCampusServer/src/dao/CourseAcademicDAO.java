package dao;

import dto.course.GradeRecordDTO;
import dto.course.GradeSummaryDTO;
import dto.course.TrainingPlanCourseDTO;
import dto.course.TrainingPlanGroupDTO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class CourseAcademicDAO {
    public GradeSummaryDTO loadGrades(Connection connection, String studentUid,
                                      int academicYear, int semester) throws SQLException {
        String sql = "SELECT c.course_code, c.course_name, c.credit, g.score, g.grade_point,"
                + " g.daily_score, g.midterm_score, g.experiment_score, g.finalterm_score"
                + " FROM grade g JOIN enrollment e ON e.enrollment_id = g.enrollment_id"
                + " JOIN course c ON c.course_id = e.course_id"
                + " WHERE e.uid = ? AND e.academic_year = ? AND e.semester = ?"
                + " AND g.is_published = 1 ORDER BY c.course_code";
        List<GradeRecordDTO> records;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            statement.setInt(2, academicYear);
            statement.setInt(3, semester);
            try (ResultSet rows = statement.executeQuery()) {
                records = mapGradeRows(rows,
                        CourseQueryDAO.term(academicYear, semester).getDisplayName());
            }
        }
        double termCredits = 0;
        double termScore = 0;
        double termPoints = 0;
        for (GradeRecordDTO record : records) {
            termCredits += record.getCredit();
            termScore += record.getScore() * record.getCredit();
            termPoints += record.getGradePoint() * record.getCredit();
        }
        double[] cumulative = cumulative(connection, studentUid);
        return new GradeSummaryDTO(CourseQueryDAO.term(academicYear, semester).getDisplayName(),
                divide(termPoints, termCredits), divide(termScore, termCredits),
                cumulative[0], cumulative[1], records);
    }

    public List<TrainingPlanGroupDTO> loadTrainingPlan(Connection connection, String studentUid)
            throws SQLException {
        String sql = "SELECT g.group_id, g.group_name, g.required_credits, g.sort_order,"
                + " c.course_code, c.course_name, c.credit,"
                + " CASE WHEN EXISTS (SELECT 1 FROM enrollment pe"
                + "     JOIN grade pg ON pg.enrollment_id = pe.enrollment_id"
                + "     WHERE pe.uid = sap.uid AND pe.course_id = c.course_id"
                + "       AND pg.is_published = 1 AND pg.score >= 60) THEN '已修'"
                + "   WHEN EXISTS (SELECT 1 FROM enrollment ce"
                + "     WHERE ce.uid = sap.uid AND ce.course_id = c.course_id"
                + "       AND ce.status = 2) THEN '在修' ELSE '未修' END AS completion_status"
                + " FROM student_academic_profile sap"
                + " JOIN training_plan tp ON tp.major_id = sap.major_id"
                + "   AND tp.cohort_year = sap.cohort_year AND tp.status = 'PUBLISHED'"
                + "   AND tp.version = (SELECT MAX(tp2.version) FROM training_plan tp2"
                + "       WHERE tp2.major_id = sap.major_id"
                + "       AND tp2.cohort_year = sap.cohort_year AND tp2.status = 'PUBLISHED')"
                + " JOIN training_plan_group g ON g.plan_id = tp.plan_id"
                + " JOIN training_plan_course pc ON pc.group_id = g.group_id"
                + " JOIN course c ON c.course_id = pc.course_id"
                + " WHERE sap.uid = ? AND sap.status = 'ACTIVE'"
                + " ORDER BY g.sort_order, g.group_id, pc.recommended_semester, c.course_code";
        Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, studentUid);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String groupId = rows.getString("group_id");
                    GroupAccumulator group = groups.computeIfAbsent(groupId,
                            ignored -> new GroupAccumulator(text(rows, "group_name"),
                                    number(rows, "required_credits")));
                    double credit = rows.getDouble("credit");
                    String status = rows.getString("completion_status");
                    group.courses.add(new TrainingPlanCourseDTO(rows.getString("course_code"),
                            rows.getString("course_name"), credit, status));
                    if ("已修".equals(status)) group.earnedCredits += credit;
                }
            }
        }
        return groups.values().stream().map(GroupAccumulator::dto).toList();
    }

    static List<GradeRecordDTO> mapGradeRows(ResultSet rows, String term) throws SQLException {
        List<GradeRecordDTO> records = new ArrayList<>();
        while (rows.next()) {
            records.add(new GradeRecordDTO(term, rows.getString("course_code"),
                    rows.getString("course_name"), rows.getDouble("credit"),
                    rows.getDouble("score"), rows.getDouble("grade_point"),
                    nullableDouble(rows, "daily_score"),
                    nullableDouble(rows, "midterm_score"),
                    nullableDouble(rows, "experiment_score"),
                    nullableDouble(rows, "finalterm_score")));
        }
        return List.copyOf(records);
    }

    private static double[] cumulative(Connection connection, String uid) throws SQLException {
        String sql = "SELECT"
                + " COALESCE(SUM(g.score * c.credit) / NULLIF(SUM(c.credit), 0), 0),"
                + " COALESCE(SUM(g.grade_point * c.credit) / NULLIF(SUM(c.credit), 0), 0)"
                + " FROM grade g JOIN enrollment e ON e.enrollment_id = g.enrollment_id"
                + " JOIN course c ON c.course_id = e.course_id"
                + " WHERE e.uid = ? AND g.is_published = 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uid);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return new double[]{0, 0};
                return new double[]{rows.getDouble(1), rows.getDouble(2)};
            }
        }
    }

    private static Double nullableDouble(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        return value == null ? null : ((Number) value).doubleValue();
    }

    private static double divide(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static String text(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    private static double number(ResultSet rows, String column) {
        try {
            return rows.getDouble(column);
        } catch (SQLException failure) {
            throw new RowMappingException(failure);
        }
    }

    private static final class GroupAccumulator {
        private final String name;
        private final double requiredCredits;
        private final List<TrainingPlanCourseDTO> courses = new ArrayList<>();
        private double earnedCredits;

        private GroupAccumulator(String name, double requiredCredits) {
            this.name = name;
            this.requiredCredits = requiredCredits;
        }

        private TrainingPlanGroupDTO dto() {
            return new TrainingPlanGroupDTO(name, requiredCredits, earnedCredits, courses);
        }
    }

    private static final class RowMappingException extends RuntimeException {
        private RowMappingException(SQLException cause) {
            super(cause);
        }
    }
}
