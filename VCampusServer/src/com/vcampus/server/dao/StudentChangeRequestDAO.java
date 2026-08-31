package com.vcampus.server.dao;

import java.sql.*;
import java.util.*;
import com.vcampus.common.entity.*;
import com.vcampus.common.enums.StudentChangeStatus;
import com.vcampus.server.util.DBUtil;

public class StudentChangeRequestDAO {
    private final StudentChangeItemDAO itemDAO = new StudentChangeItemDAO();

    public long insert(Connection c, StudentChangeRequest request) throws SQLException {
        String sql = "INSERT INTO tblStudentChangeRequest(studentId,status,submitTime) VALUES (?, 'PENDING', CURRENT_TIMESTAMP)";
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, request.getStudentId()); ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (!rs.next()) throw new SQLException("未生成申请编号");
                long id = rs.getLong(1); request.setRequestId(id); itemDAO.insertAll(c, id, request.getItems()); return id;
            }
        }
    }
    public StudentChangeRequest findById(long id) throws SQLException {
        try (Connection c = DBUtil.getConnection()) { return findById(c, id); }
    }
    public StudentChangeRequest findById(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM tblStudentChangeRequest WHERE requestId=?")) {
            ps.setLong(1,id); try (ResultSet rs=ps.executeQuery()) { if (!rs.next()) return null; StudentChangeRequest r=map(rs); r.setItems(itemDAO.findByRequestId(c,id)); return r; }
        }
    }
    public List<StudentChangeRequest> findByStudentId(String studentId) throws SQLException {
        return list("SELECT * FROM tblStudentChangeRequest WHERE studentId=? ORDER BY submitTime DESC", studentId);
    }
    public List<StudentChangeRequest> findPending() throws SQLException {
        return list("SELECT * FROM tblStudentChangeRequest WHERE status='PENDING' ORDER BY submitTime", null);
    }
    public StudentChangeRequest findPendingByStudentId(String studentId) throws SQLException {
        List<StudentChangeRequest> rows=list("SELECT * FROM tblStudentChangeRequest WHERE studentId=? AND status='PENDING' ORDER BY submitTime DESC",studentId);
        return rows.isEmpty()?null:rows.get(0);
    }
    private List<StudentChangeRequest> list(String sql,String arg) throws SQLException {
        List<StudentChangeRequest> out=new ArrayList<>();
        try(Connection c=DBUtil.getConnection(); PreparedStatement ps=c.prepareStatement(sql)){ if(arg!=null)ps.setString(1,arg); try(ResultSet rs=ps.executeQuery()){while(rs.next()){StudentChangeRequest r=map(rs);r.setItems(itemDAO.findByRequestId(c,r.getRequestId()));out.add(r);}}}
        return out;
    }
    public boolean updateReview(Connection c,long id,StudentChangeStatus status,String reviewer,String remark) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("UPDATE tblStudentChangeRequest SET status=?,reviewerId=?,reviewTime=CURRENT_TIMESTAMP,reviewRemark=? WHERE requestId=? AND status='PENDING'")){
            ps.setString(1,status.name());ps.setString(2,reviewer);ps.setString(3,remark);ps.setLong(4,id);return ps.executeUpdate()==1;
        }
    }
    public boolean cancel(long id,String studentId)throws SQLException{try(Connection c=DBUtil.getConnection();PreparedStatement ps=c.prepareStatement("UPDATE tblStudentChangeRequest SET status='CANCELLED',reviewTime=CURRENT_TIMESTAMP WHERE requestId=? AND studentId=? AND status='PENDING'")){ps.setLong(1,id);ps.setString(2,studentId);return ps.executeUpdate()==1;}}
    private static StudentChangeRequest map(ResultSet rs)throws SQLException{StudentChangeRequest r=new StudentChangeRequest();r.setRequestId(rs.getLong("requestId"));r.setStudentId(rs.getString("studentId"));r.setStatus(StudentChangeStatus.valueOf(rs.getString("status")));r.setSubmitTime(rs.getTimestamp("submitTime"));r.setReviewerId(rs.getString("reviewerId"));r.setReviewTime(rs.getTimestamp("reviewTime"));r.setReviewRemark(rs.getString("reviewRemark"));return r;}
}
