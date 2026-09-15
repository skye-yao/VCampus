package service;

import com.google.gson.Gson;
import dao.StudentBatchDAO;
import exception.StudentBatchConflictException;
import lock.ResourceLockManager;
import session.UserSession;
import util.DBUtil;
import util.StudentBatchValidation;
import vo.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;

public final class StudentBatchService {
    @FunctionalInterface public interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    private final StudentBatchDAO dao;
    private final ResourceLockManager locks;
    private final Gson gson=new Gson();
    public StudentBatchService(){this(DBUtil::getConnection,new StudentBatchDAO(),ResourceLockManager.getInstance());}
    public StudentBatchService(Connections connections,StudentBatchDAO dao,ResourceLockManager locks){this.connections=connections;this.dao=dao;this.locks=locks;}
    public StudentBatchResult add(UserSession user,StudentBatchRequest input,boolean award)throws SQLException {
        if(user==null||!("ADMIN".equalsIgnoreCase(user.getRole())||"管理员".equals(user.getRole())))throw new SecurityException("仅管理员可操作");
        StudentBatchRequest request=StudentBatchValidation.normalize(input,award);
        String kind=award?"AWARD":"AID",digest=digest(request,kind);
        try(Connection c=connections.open()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
            ResourceLockManager.MultiGuard guard=null;
            try {
                StudentBatchDAO.Receipt receipt=dao.claim(c,request.operationId(),user.getUsername(),kind,digest);
                if(!user.getUsername().equals(receipt.adminId()))throw new SecurityException("该操作编号不属于当前管理员");
                if(!kind.equals(receipt.kind())||!digest.equals(receipt.digest()))throw new IllegalArgumentException("操作编号已用于其他内容，请保留原批次重试");
                if(receipt.resultJson()!=null){
                    StudentBatchResult saved=gson.fromJson(receipt.resultJson(),StudentBatchResult.class);c.commit();
                    return new StudentBatchResult(saved.operationId(),saved.successCount(),true,saved.conflicts());
                }
                guard=locks.guardAll(request.studentIds().stream().map(id->"STUDENT:"+id).toList());
                List<StudentBatchResult.Conflict> conflicts=new ArrayList<>();
                Map<String,StudentBatchDAO.Target> targets=new TreeMap<>();
                for(String id:request.studentIds()) {
                    StudentBatchDAO.Target target=dao.lockStudent(c,id);
                    if(target==null){conflicts.add(new StudentBatchResult.Conflict(id,"","学生档案不存在"));continue;}
                    // Prevent alternate-case IDs from bypassing the exact resource key used by editors.
                    if(!id.equals(target.studentId())){conflicts.add(new StudentBatchResult.Conflict(id,target.name(),"学号格式与档案不一致，请刷新名单"));continue;}
                    targets.put(target.studentId(),target);
                    List<String> reasons=new ArrayList<>();
                    if(locks.occupied("STUDENT:"+id))reasons.add("档案正在被编辑");
                    if(dao.pending(c,id))reasons.add("存在待审核申请");
                    if(dao.duplicate(c,id,request.award(),request.aid()))reasons.add(award?"已存在相同名称、类型和日期的奖励":"已存在相同名称、类型和日期的资助");
                    if(!reasons.isEmpty())conflicts.add(new StudentBatchResult.Conflict(id,target.name(),String.join("；",reasons)));
                }
                if(!conflicts.isEmpty())throw new StudentBatchConflictException(new StudentBatchResult(request.operationId(),0,false,List.copyOf(conflicts)));
                dao.insert(c,List.copyOf(targets.keySet()),request.award(),request.aid());
                StudentBatchResult result=new StudentBatchResult(request.operationId(),targets.size(),false,List.of());
                dao.complete(c,request.operationId(),gson.toJson(result),targets.size());c.commit();return result;
            }catch(SQLException|RuntimeException e){
                // A rollback failure must remain an uncertain outcome, not a confirmed business failure.
                try{c.rollback();}catch(SQLException rollback){rollback.addSuppressed(e);throw rollback;}
                throw e;
            }finally{if(guard!=null)guard.close();}
        }
    }
    private String digest(StudentBatchRequest r,String kind) {
        List<Object> values=new ArrayList<>();values.add(kind);values.add(r.studentIds());
        if(r.award()!=null){var a=r.award();values.addAll(Arrays.asList(a.getAwardName(),a.getAwardType().name(),a.getAwardLevel(),a.getAwardDate().toString(),a.getOrganization(),a.getDescription()));}
        else{var a=r.aid();values.addAll(Arrays.asList(a.getAidName(),a.getAidType(),a.getAmount().toPlainString(),a.getAidDate().toString(),a.getProvider(),a.getStatus().name(),a.getDescription()));}
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(gson.toJson(values).getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
}
