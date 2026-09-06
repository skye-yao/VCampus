package dao;
import entity.*;
import enums.StudentChangeStatus;
import util.DBUtil;
import java.sql.*;
import java.util.*;
@SuppressWarnings( {
    "SqlNoDataSourceInspection", "SqlResolve"
}
) public class TeacherChangeRequestDAO {
    private final TeacherChangeItemDAO items=new TeacherChangeItemDAO();
    public long insert(Connection c,TeacherChangeRequest r)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("INSERT INTO tblTeacherChangeRequest(teacherId,status,submitTime)VALUES(?,'PENDING',CURRENT_TIMESTAMP)",Statement.RETURN_GENERATED_KEYS)) {
            p.setString(1,r.getTeacherId());
            p.executeUpdate();
            try(ResultSet k=p.getGeneratedKeys()) {
                if(!k.next())throw new SQLException("未生成申请编号");
                long id=k.getLong(1);
                items.insertAll(c,id,r.getItems());
                return id;
            }
        }
    }
    public TeacherChangeRequest findById(long id)throws SQLException {
        try(Connection c=DBUtil.getConnection()) {
            return findById(c,id);
        }
    }
    public TeacherChangeRequest findById(Connection c,long id)throws SQLException {
        return findById(c,id,false);
    }
    public TeacherChangeRequest findByIdForUpdate(Connection c,long id)throws SQLException {
        return findById(c,id,true);
    }
    private TeacherChangeRequest findById(Connection c,long id,boolean lock)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblTeacherChangeRequest WHERE requestId=?"+(lock?" FOR UPDATE":""))) {
            p.setLong(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next())return null;
                TeacherChangeRequest x=map(r);
                x.setItems(items.findByRequestId(c,id));
                return x;
            }
        }
    }
    /** 详情页审核进度使用的最近一次申请摘要，只读取已有记录。 */
    public TeacherChangeRequest findLatestByTeacherId(String id)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement(
                "SELECT * FROM tblTeacherChangeRequest WHERE teacherId=? ORDER BY submitTime DESC, requestId DESC LIMIT 1")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()) { return r.next()?map(r):null; }
        }
    }
    public List<TeacherChangeRequest> findByTeacherId(String id)throws SQLException {
        return list("SELECT * FROM tblTeacherChangeRequest WHERE teacherId=? ORDER BY submitTime DESC",id);
    }
    public List<TeacherChangeRequest> findPending()throws SQLException {
        return list("SELECT * FROM tblTeacherChangeRequest WHERE status='PENDING' ORDER BY submitTime",null);
    }
    public List<TeacherChangeRequest> findAll()throws SQLException {
        return list("SELECT * FROM tblTeacherChangeRequest ORDER BY submitTime DESC",null);
    }
    public TeacherChangeRequest findPendingByTeacherId(String id)throws SQLException {
        try(Connection c=DBUtil.getConnection()) {
            return findPendingByTeacherId(c,id);
        }
    }
    public TeacherChangeRequest findPendingByTeacherId(Connection c,String id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT * FROM tblTeacherChangeRequest WHERE teacherId=? AND status='PENDING' ORDER BY submitTime DESC LIMIT 1")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next())return null;
                TeacherChangeRequest x=map(r);
                x.setItems(items.findByRequestId(c,x.getRequestId()));
                return x;
            }
        }
    }
    private List<TeacherChangeRequest> list(String q,String a)throws SQLException {
        List<TeacherChangeRequest>o=new ArrayList<>();
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement(q)) {
            if(a!=null)p.setString(1,a);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    TeacherChangeRequest x=map(r);
                    x.setItems(items.findByRequestId(c,x.getRequestId()));
                    o.add(x);
                }
            }
        }
        return o;
    }
    public boolean review(Connection c,long id,StudentChangeStatus s,String who,String note)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("UPDATE tblTeacherChangeRequest SET status=?,reviewerId=?,reviewTime=CURRENT_TIMESTAMP,reviewRemark=? WHERE requestId=? AND status='PENDING'")) {
            p.setString(1,s.name());
            p.setString(2,who);
            p.setString(3,note);
            p.setLong(4,id);
            return p.executeUpdate()==1;
        }
    }
    public boolean cancel(long id,String sid)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("UPDATE tblTeacherChangeRequest SET status='CANCELLED' WHERE requestId=? AND teacherId=? AND status='PENDING'")) {
            p.setLong(1,id);
            p.setString(2,sid);
            return p.executeUpdate()==1;
        }
    }
    private static TeacherChangeRequest map(ResultSet r)throws SQLException {
        TeacherChangeRequest x=new TeacherChangeRequest();
        x.setRequestId(r.getLong("requestId"));
        x.setTeacherId(r.getString("teacherId"));
        x.setStatus(StudentChangeStatus.valueOf(r.getString("status")));
        x.setSubmitTime(r.getTimestamp("submitTime"));
        x.setReviewerId(r.getString("reviewerId"));
        x.setReviewTime(r.getTimestamp("reviewTime"));
        x.setReviewRemark(r.getString("reviewRemark"));
        return x;
    }
}

