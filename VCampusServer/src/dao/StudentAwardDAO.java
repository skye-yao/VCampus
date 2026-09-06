package dao;
import entity.StudentAward;
import enums.StudentAwardType;
import util.DBUtil;
import java.sql.*;
import java.util.*;
@SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
public class StudentAwardDAO {
    public List<StudentAward> findByStudentId(String id)throws SQLException {
        List<StudentAward>o=new ArrayList<>();
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("SELECT * FROM tblStudentAward WHERE studentId=? ORDER BY awardDate DESC")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()) {
                while(r.next()) {
                    StudentAward a=new StudentAward();
                    a.setAwardId(r.getLong("awardId"));
                    a.setStudentId(id);
                    a.setAwardName(r.getString("awardName"));
                    a.setAwardType(StudentAwardType.valueOf(r.getString("awardType")));
                    a.setAwardLevel(r.getString("awardLevel"));
                    a.setAwardDate(r.getDate("awardDate"));
                    a.setOrganization(r.getString("organization"));
                    a.setDescription(r.getString("description"));
                    o.add(a);
                }
            }
        }
        return o;
    }
    public boolean insert(StudentAward a)throws SQLException {
        return change("INSERT INTO tblStudentAward(studentId,awardName,awardType,awardLevel,awardDate,organization,description)VALUES(?,?,?,?,?,?,?)",a,false);
    }
    public boolean update(StudentAward a)throws SQLException {
        return change("UPDATE tblStudentAward SET studentId=?,awardName=?,awardType=?,awardLevel=?,awardDate=?,organization=?,description=? WHERE awardId=?",a,true);
    }
    private boolean change(String q,StudentAward a,boolean id)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement(q)) {
            p.setString(1,a.getStudentId());
            p.setString(2,a.getAwardName());
            p.setString(3,a.getAwardType().name());
            p.setString(4,a.getAwardLevel());
            p.setDate(5,a.getAwardDate());
            p.setString(6,a.getOrganization());
            p.setString(7,a.getDescription());
            if(id)p.setLong(8,a.getAwardId());
            return p.executeUpdate()==1;
        }
    }
    public boolean delete(long id)throws SQLException {
        try(Connection c=DBUtil.getConnection();PreparedStatement p=c.prepareStatement("DELETE FROM tblStudentAward WHERE awardId=?")) {
            p.setLong(1,id);
            return p.executeUpdate()==1;
        }
    }
}
