package dao;

import java.sql.*;
import java.util.Map;
import util.DBUtil;
import util.InformationRules;
import util.LocalTimeConnection;

/** Serialize capacity checks with insertion on the owner's database row. */
public final class InformationRecordLimit {
    private record Spec(String parent, String owner, int limit, String label) {}
    private static final Map<String, Spec> TABLES = Map.of(
        "tblStudentExperience", new Spec("tblStudent", "studentId", InformationRules.STUDENT_EXPERIENCES, "学习经历"),
        "tblStudentFamilyMember", new Spec("tblStudent", "studentId", InformationRules.STUDENT_FAMILY, "家庭成员"),
        "tblTeacherWorkExperience", new Spec("tblTeacher", "teacherId", InformationRules.TEACHER_EXPERIENCES, "工作经历"),
        "tblTeacherFamilyMember", new Spec("tblTeacher", "teacherId", InformationRules.TEACHER_FAMILY, "社会关系"));
    @FunctionalInterface public interface Insert { boolean run(Connection c) throws SQLException; }
    public static boolean insert(String table, String id, Insert insert) throws SQLException {
        try (Connection c = LocalTimeConnection.getConnection()) {
            c.setAutoCommit(false);
            try { check(c, table, id); boolean result = insert.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
        }
    }
    public static void check(Connection c, String table, String id) throws SQLException {
        Spec spec = TABLES.get(table);
        if (spec == null) throw new IllegalArgumentException("记录类型无效");
        try (PreparedStatement p = c.prepareStatement("SELECT " + spec.owner + " FROM " + spec.parent + " WHERE " + spec.owner + "=? FOR UPDATE")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) { if (!r.next()) throw new IllegalArgumentException("档案不存在"); }
        }
        // A locking read sees the latest rows even when the review transaction
        // already read its change items under MySQL's REPEATABLE READ isolation.
        try (PreparedStatement p = c.prepareStatement("SELECT " + spec.owner + " FROM " + table + " WHERE " + spec.owner + "=? FOR UPDATE")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                int count = 0;
                while (r.next()) count++;
                InformationRules.requireRoom(count, spec.limit, spec.label);
            }
        }
    }
}
