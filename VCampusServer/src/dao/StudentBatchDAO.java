package dao;

import entity.*;
import java.sql.*;
import java.util.List;

/** All methods use the caller's transaction; no connection or DDL is created here. */
public class StudentBatchDAO {
    public record Receipt(String adminId, String kind, String digest, String resultJson) {}
    public record Target(String studentId, String name) {}
    public Receipt claim(Connection c, String id, String admin, String kind, String digest) throws SQLException {
        // The unique key serializes same-operation retries, including an in-flight first attempt.
        try (PreparedStatement p = c.prepareStatement("INSERT INTO tblStudentInformationBatch(operationId,adminId,operationType,payloadDigest) VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE operationId=operationId")) {
            p.setString(1,id);p.setString(2,admin);p.setString(3,kind);p.setString(4,digest);p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("SELECT adminId,operationType,payloadDigest,resultJson FROM tblStudentInformationBatch WHERE operationId=? FOR UPDATE")) {
            p.setString(1,id);
            try (ResultSet r=p.executeQuery()) {
                if(!r.next())throw new SQLException("批次记录不存在");
                return new Receipt(r.getString(1),r.getString(2),r.getString(3),r.getString(4));
            }
        }
    }
    public void complete(Connection c,String id,String result,int count)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("UPDATE tblStudentInformationBatch SET resultJson=?,successCount=?,completedAt=CURRENT_TIMESTAMP WHERE operationId=?")) {
            p.setString(1,result);p.setInt(2,count);p.setString(3,id);
            if(p.executeUpdate()!=1)throw new SQLException("批次结果保存失败");
        }
    }
    public Target lockStudent(Connection c,String id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT studentId,name FROM tblStudent WHERE studentId=? FOR UPDATE")) {
            p.setString(1,id);
            try(ResultSet r=p.executeQuery()){return r.next()?new Target(r.getString(1),r.getString(2)):null;}
        }
    }
    public boolean pending(Connection c,String id)throws SQLException {
        try(PreparedStatement p=c.prepareStatement("SELECT requestId FROM tblStudentChangeRequest WHERE studentId=? AND status='PENDING' LIMIT 1")) {
            p.setString(1,id);try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    public boolean duplicate(Connection c,String id,StudentAward award,StudentAid aid)throws SQLException {
        boolean a=award!=null;
        String sql=a?"SELECT awardId FROM tblStudentAward WHERE studentId=? AND TRIM(awardName)=? AND awardType=? AND awardDate=? LIMIT 1"
                :"SELECT aidId FROM tblStudentAid WHERE studentId=? AND TRIM(aidName)=? AND TRIM(aidType)=? AND aidDate=? LIMIT 1";
        try(PreparedStatement p=c.prepareStatement(sql)) {
            p.setString(1,id);p.setString(2,a?award.getAwardName():aid.getAidName());
            p.setString(3,a?award.getAwardType().name():aid.getAidType());p.setDate(4,a?award.getAwardDate():aid.getAidDate());
            try(ResultSet r=p.executeQuery()){return r.next();}
        }
    }
    public void insert(Connection c,List<String> ids,StudentAward award,StudentAid aid)throws SQLException {
        boolean a=award!=null;
        String sql=a?"INSERT INTO tblStudentAward(studentId,awardName,awardType,awardLevel,awardDate,organization,description) VALUES(?,?,?,?,?,?,?)"
                :"INSERT INTO tblStudentAid(studentId,aidName,aidType,amount,aidDate,provider,status,description) VALUES(?,?,?,?,?,?,?,?)";
        try(PreparedStatement p=c.prepareStatement(sql)) {
            for(String id:ids) {
                p.setString(1,id);
                if(a){p.setString(2,award.getAwardName());p.setString(3,award.getAwardType().name());p.setString(4,award.getAwardLevel());p.setDate(5,award.getAwardDate());p.setString(6,award.getOrganization());p.setString(7,award.getDescription());}
                else{p.setString(2,aid.getAidName());p.setString(3,aid.getAidType());p.setBigDecimal(4,aid.getAmount());p.setDate(5,aid.getAidDate());p.setString(6,aid.getProvider());p.setString(7,aid.getStatus().name());p.setString(8,aid.getDescription());}
                p.addBatch();
            }
            int[] counts=p.executeBatch();
            if(counts.length!=ids.size())throw new SQLException("批量写入数量不一致");
            for(int count:counts)if(count!=1&&count!=Statement.SUCCESS_NO_INFO)throw new SQLException("批量写入失败");
        }
    }
}
