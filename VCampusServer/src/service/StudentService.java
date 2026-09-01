package service;

import dao.*;
import entity.*;
import enums.StudentChangeStatus;
import util.DBUtil;
import vo.StudentOverviewVO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class StudentService {
    private final StudentDAO students = new StudentDAO();
    private final StudentChangeRequestDAO requests = new StudentChangeRequestDAO();
    private final StudentAwardDAO awards = new StudentAwardDAO();
    private final StudentAidDAO aids = new StudentAidDAO();

    public StudentOverviewVO queryByUserId(String userId) throws SQLException {
        Student student = students.findByUserId(userId);
        return student == null ? null : overview(student);
    }

    public StudentOverviewVO queryByStudentId(String studentId) throws SQLException {
        Student student = students.findByStudentId(studentId);
        return student == null ? null : overview(student);
    }

    private StudentOverviewVO overview(Student student) throws SQLException {
        StudentOverviewVO result = new StudentOverviewVO();
        result.setStudent(student);
        result.setAwards(awards.findByStudentId(student.getStudentId()));
        result.setAids(aids.findByStudentId(student.getStudentId()));
        result.setPendingRequest(requests.findPendingByStudentId(student.getStudentId()));
        return result;
    }

    public List<Student> listStudents() throws SQLException {
        return students.findAll();
    }

    public List<StudentChangeRequest> listMyRequests(String userId) throws SQLException {
        return requests.findByStudentId(requireStudent(userId).getStudentId());
    }

    public List<StudentChangeRequest> listPending() throws SQLException {
        return requests.findPending();
    }

    public StudentChangeRequest queryRequest(long requestId) throws SQLException {
        StudentChangeRequest request = requests.findById(requestId);
        if (request == null) throw new IllegalArgumentException("修改申请不存在");
        return request;
    }

    public void cancel(String userId, long requestId) throws SQLException {
        if (!requests.cancel(requestId, requireStudent(userId).getStudentId())) {
            throw new IllegalStateException("申请不存在或已处理");
        }
    }

    public long submit(String userId, StudentChangeRequest request) throws SQLException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("修改项不能为空");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Student student = students.lockByUserId(connection, userId);
                if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
                if (requests.findPendingByStudentId(connection, student.getStudentId()) != null) {
                    throw new IllegalStateException("已有待审核申请");
                }
                request.setStudentId(student.getStudentId());
                long requestId = requests.insert(connection, request);
                connection.commit();
                return requestId;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public void review(long requestId, StudentChangeStatus result, String reviewer, String remark)
            throws SQLException {
        if (result != StudentChangeStatus.APPROVED && result != StudentChangeStatus.REJECTED) {
            throw new IllegalArgumentException("审核结果无效");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StudentChangeRequest request = requests.findById(connection, requestId);
                if (request == null || request.getStatus() != StudentChangeStatus.PENDING) {
                    throw new IllegalStateException("申请不存在或已处理");
                }
                if (result == StudentChangeStatus.APPROVED
                        && !students.updateApprovedFields(connection, request.getStudentId(), request.getItems())) {
                    throw new SQLException("正式学籍更新失败");
                }
                if (!requests.review(connection, requestId, result, reviewer, remark)) {
                    throw new IllegalStateException("申请状态已变化");
                }
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    public boolean updateByAdmin(Student student) throws SQLException {
        if (student == null || student.getStudentId() == null || student.getStudentId().isBlank()) {
            throw new IllegalArgumentException("学生信息不能为空");
        }
        if (!students.update(student)) throw new IllegalStateException("学生不存在或更新失败");
        return true;
    }

    public boolean addAward(StudentAward award) throws SQLException { return awards.insert(award); }
    public boolean updateAward(StudentAward award) throws SQLException { return awards.update(award); }
    public boolean deleteAward(long awardId) throws SQLException { return awards.delete(awardId); }
    public boolean addAid(StudentAid aid) throws SQLException { return aids.insert(aid); }
    public boolean updateAid(StudentAid aid) throws SQLException { return aids.update(aid); }
    public boolean deleteAid(long aidId) throws SQLException { return aids.delete(aidId); }

    private Student requireStudent(String userId) throws SQLException {
        Student student = students.findByUserId(userId);
        if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
        return student;
    }
}
