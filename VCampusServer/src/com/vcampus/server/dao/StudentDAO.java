package com.vcampus.server.dao;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.vcampus.common.entity.Student;
import com.vcampus.server.util.DBUtil;

/** 正式学籍数据访问。 */
public class StudentDAO {
    private static final List<String> COLUMNS = Arrays.asList(
        "studentId", "userId", "name", "gender", "politicalStatus", "nationality", "idType", "idNumber",
        "idIssueDate", "birthDate", "nativePlace", "householdType", "birthPlace", "sourcePlace",
        "registeredResidence", "leagueMember", "leagueJoinDate", "partyMember", "partyJoinDate", "healthStatus",
        "studentCategory", "registered", "inSchool", "studentStatus", "campus", "grade", "college", "major",
        "className", "educationLevel", "trainingMode", "schoolingLength", "counselorName", "counselorPhone",
        "candidateCategory", "admissionDate", "admissionMethod", "graduationSchool", "middleSchoolClass",
        "middleSchoolTeacher", "telephone", "mobile", "email", "qq", "wechat", "campusAddress",
        "emergencyContact", "emergencyPhone");

    public Student findByUserId(String userId) throws SQLException {
        return findOne("userId", userId);
    }

    public Student findByStudentId(String studentId) throws SQLException {
        return findOne("studentId", studentId);
    }

    private Student findOne(String column, String value) throws SQLException {
        String sql = "SELECT * FROM tblStudent WHERE " + column + " = ?";
        try (Connection c = DBUtil.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public List<Student> findAll() throws SQLException {
        List<Student> result = new ArrayList<>();
        try (Connection c = DBUtil.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM tblStudent ORDER BY studentId"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) result.add(map(rs));
        }
        return result;
    }

    public boolean insert(Student student) throws SQLException {
        String marks = String.join(",", java.util.Collections.nCopies(COLUMNS.size(), "?"));
        String sql = "INSERT INTO tblStudent (" + String.join(",", COLUMNS) + ") VALUES (" + marks + ")";
        try (Connection c = DBUtil.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, student, COLUMNS); return ps.executeUpdate() == 1;
        }
    }

    public boolean update(Student student) throws SQLException {
        try (Connection c = DBUtil.getConnection()) { return update(c, student); }
    }

    public boolean update(Connection c, Student student) throws SQLException {
        List<String> fields = COLUMNS.subList(1, COLUMNS.size());
        String assignments = String.join(",", fields.stream().map(v -> v + "=?").toArray(String[]::new));
        try (PreparedStatement ps = c.prepareStatement("UPDATE tblStudent SET " + assignments + " WHERE studentId=?")) {
            bind(ps, student, fields); ps.setString(fields.size() + 1, student.getStudentId());
            return ps.executeUpdate() == 1;
        }
    }

    /** 审核通过时只允许更新文档声明可由学生申请修改的联系字段。 */
    public boolean updateApprovedFields(Connection c, String studentId,
            List<com.vcampus.common.entity.StudentChangeItem> items) throws SQLException {
        List<String> allowed = Arrays.asList("telephone", "mobile", "email", "qq", "wechat", "campusAddress",
                "emergencyContact", "emergencyPhone");
        if (items == null || items.isEmpty()) return false;
        for (com.vcampus.common.entity.StudentChangeItem item : items) {
            if (!allowed.contains(item.getFieldName())) throw new SQLException("不允许学生修改字段: " + item.getFieldName());
        }
        String set = String.join(",", items.stream().map(i -> i.getFieldName() + "=?").toArray(String[]::new));
        try (PreparedStatement ps = c.prepareStatement("UPDATE tblStudent SET " + set + " WHERE studentId=?")) {
            int index = 1;
            for (com.vcampus.common.entity.StudentChangeItem item : items) ps.setString(index++, item.getNewValue());
            ps.setString(index, studentId); return ps.executeUpdate() == 1;
        }
    }

    private static void bind(PreparedStatement ps, Student student, List<String> fields) throws SQLException {
        try {
            int i = 1;
            for (String name : fields) {
                Field f = Student.class.getDeclaredField(name); f.setAccessible(true); ps.setObject(i++, f.get(student));
            }
        } catch (ReflectiveOperationException e) { throw new SQLException("学籍字段映射失败", e); }
    }

    private static Student map(ResultSet rs) throws SQLException {
        Student student = new Student();
        try {
            for (String name : COLUMNS) {
                Field f = Student.class.getDeclaredField(name); f.setAccessible(true);
                if (f.getType() == boolean.class) f.setBoolean(student, rs.getBoolean(name));
                else if (f.getType() == int.class) f.setInt(student, rs.getInt(name));
                else if (f.getType() == java.sql.Date.class) f.set(student, rs.getDate(name));
                else f.set(student, rs.getString(name));
            }
            return student;
        } catch (ReflectiveOperationException e) { throw new SQLException("学籍字段映射失败", e); }
    }
}
