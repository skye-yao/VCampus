package dao;

import dto.course.admin.catalog.AdminCourseDTO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

public class AdminCourseCatalogDAO {
    private static final String SELECT = "SELECT c.course_id,c.course_code,c.course_name,"
            + "c.course_type,c.credit,c.credit_hours,c.description,c.prerequisites,"
            + "c.allow_cross_major,c.final_exam,c.status,c.version,"
            + "(SELECT COUNT(*) FROM course_offering o WHERE o.course_id=c.course_id"
            + " AND o.status<>4) AS offering_count FROM course c";

    public List<AdminCourseDTO> list(Connection connection, String query, String status)
            throws SQLException {
        List<String> clauses = new ArrayList<>();
        List<String> params = new ArrayList<>();
        if (status != null) {
            clauses.add("c.status=?");
            params.add(status);
        }
        if (query != null) {
            clauses.add("(c.course_code LIKE ? ESCAPE '!' OR c.course_name LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(query) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        String sql = SELECT + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + " ORDER BY c.course_code";
        List<AdminCourseDTO> courses = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) statement.setString(i + 1, params.get(i));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) courses.add(map(rows));
            }
        }
        return List.copyOf(courses);
    }

    public void lock(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT course_id FROM course WHERE course_id=? FOR UPDATE")) {
            statement.setLong(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
        }
    }

    public AdminCourseDTO find(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE c.course_id=?")) {
            statement.setLong(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }

    public long insert(Connection connection, CourseFields fields) throws SQLException {
        String sql = "INSERT INTO course(course_code,course_name,credit,credit_hours,course_type,"
                + "allow_cross_major,description,prerequisites,final_exam)"
                + " VALUES(?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement statement = connection.prepareStatement(
                sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, fields.courseCode());
            statement.setString(2, fields.courseName());
            statement.setBigDecimal(3, fields.credit());
            statement.setInt(4, fields.creditHours());
            statement.setInt(5, fields.courseType());
            statement.setInt(6, fields.allowCrossMajor() ? 1 : 0);
            statement.setString(7, fields.description());
            statement.setString(8, fields.prerequisites());
            statement.setInt(9, fields.finalExam() ? 1 : 0);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    public int update(Connection connection, long courseId, int expectedVersion,
                      CourseFields fields) throws SQLException {
        String sql = "UPDATE course SET course_name=?, credit=?, credit_hours=?, course_type=?,"
                + " description=?, prerequisites=?, allow_cross_major=?, final_exam=?,"
                + " version=version+1 WHERE course_id=? AND version=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, fields.courseName());
            statement.setBigDecimal(2, fields.credit());
            statement.setInt(3, fields.creditHours());
            statement.setInt(4, fields.courseType());
            statement.setString(5, fields.description());
            statement.setString(6, fields.prerequisites());
            statement.setInt(7, fields.allowCrossMajor() ? 1 : 0);
            statement.setInt(8, fields.finalExam() ? 1 : 0);
            statement.setLong(9, courseId);
            statement.setInt(10, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public int archive(Connection connection, long courseId, int expectedVersion,
                       String adminUid, Instant archivedAt) throws SQLException {
        String sql = "UPDATE course SET status='ARCHIVED', archived_by=?, archived_at=?,"
                + " version=version+1 WHERE course_id=? AND version=? AND status='ACTIVE'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, adminUid);
            statement.setTimestamp(2, Timestamp.valueOf(
                    archivedAt.atOffset(ZoneOffset.UTC).toLocalDateTime()));
            statement.setLong(3, courseId);
            statement.setInt(4, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public int restore(Connection connection, long courseId, int expectedVersion)
            throws SQLException {
        String sql = "UPDATE course SET status='ACTIVE', archived_by=NULL, archived_at=NULL,"
                + " version=version+1 WHERE course_id=? AND version=? AND status='ARCHIVED'";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, courseId);
            statement.setInt(2, expectedVersion);
            return statement.executeUpdate();
        }
    }

    public boolean hasActiveOfferings(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM course_offering WHERE course_id=? AND status IN (1,2,3) LIMIT 1")) {
            statement.setLong(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /** Makes {@code %}, {@code _} and the escape char literal inside a LIKE pattern. */
    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    public static String courseTypeLabel(int value) {
        return switch (value) {
            case 1 -> "必修";
            case 2 -> "限选";
            case 3 -> "选修";
            case 4 -> "通选";
            default -> throw new IllegalArgumentException("课程类型无效");
        };
    }

    public static int courseTypeCode(String label) {
        if (label == null) throw new IllegalArgumentException("课程类型无效");
        return switch (label.trim()) {
            case "必修" -> 1;
            case "限选" -> 2;
            case "选修" -> 3;
            case "通选" -> 4;
            default -> throw new IllegalArgumentException("课程类型无效");
        };
    }

    private static AdminCourseDTO map(ResultSet rows) throws SQLException {
        return new AdminCourseDTO(Long.toString(rows.getLong("course_id")),
                rows.getString("course_code"), rows.getString("course_name"),
                courseTypeLabel(rows.getInt("course_type")),
                rows.getBigDecimal("credit").doubleValue(), rows.getInt("credit_hours"),
                rows.getString("description"), rows.getString("prerequisites"),
                rows.getInt("allow_cross_major") == 1, rows.getInt("final_exam") == 1,
                rows.getString("status"), rows.getInt("offering_count"), rows.getInt("version"));
    }

    public record CourseFields(String courseCode, String courseName, int courseType,
                               BigDecimal credit, int creditHours, String description,
                               String prerequisites, boolean allowCrossMajor, boolean finalExam) {
    }
}
