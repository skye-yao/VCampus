package com.vcampus.server.service;

import java.sql.Connection;import java.sql.SQLException;import java.util.List;
import com.vcampus.common.entity.*;import com.vcampus.common.enums.StudentChangeStatus;import com.vcampus.common.vo.StudentOverviewVO;
import com.vcampus.server.dao.*;import com.vcampus.server.util.DBUtil;

/** 学籍业务层：集中权限之外的校验与审核事务。 */
public class StudentService {
 private final StudentDAO students=new StudentDAO(); private final StudentChangeRequestDAO requests=new StudentChangeRequestDAO(); private final StudentAwardDAO awards=new StudentAwardDAO(); private final StudentAidDAO aids=new StudentAidDAO();
 public StudentOverviewVO queryByUserId(String userId)throws SQLException{Student s=students.findByUserId(userId);if(s==null)return null;return overview(s);}
 public StudentOverviewVO queryByStudentId(String id)throws SQLException{Student s=students.findByStudentId(id);if(s==null)return null;return overview(s);}
 private StudentOverviewVO overview(Student s)throws SQLException{StudentOverviewVO v=new StudentOverviewVO();v.setStudent(s);v.setAwards(awards.findByStudentId(s.getStudentId()));v.setAids(aids.findByStudentId(s.getStudentId()));v.setPendingRequest(requests.findPendingByStudentId(s.getStudentId()));return v;}
 public List<Student> listStudents()throws SQLException{return students.findAll();}
 public List<StudentChangeRequest> listMyRequests(String userId)throws SQLException{Student s=requireStudent(userId);return requests.findByStudentId(s.getStudentId());}
 public List<StudentChangeRequest> listPending()throws SQLException{return requests.findPending();}
 public void cancel(String userId,long requestId)throws SQLException{Student s=requireStudent(userId);if(!requests.cancel(requestId,s.getStudentId()))throw new IllegalStateException("申请不存在或已处理");}
 public StudentChangeRequest queryRequest(long id)throws SQLException{return requests.findById(id);}
 public long submit(String userId,StudentChangeRequest request)throws SQLException{Student s=requireStudent(userId);if(requests.findPendingByStudentId(s.getStudentId())!=null)throw new IllegalStateException("已有待审核申请");if(request==null||request.getItems()==null||request.getItems().isEmpty())throw new IllegalArgumentException("修改项不能为空");request.setStudentId(s.getStudentId());try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{long id=requests.insert(c,request);c.commit();return id;}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}}
 public void review(long id,StudentChangeStatus result,String reviewer,String remark)throws SQLException{if(result!=StudentChangeStatus.APPROVED&&result!=StudentChangeStatus.REJECTED)throw new IllegalArgumentException("审核结果只能是 APPROVED 或 REJECTED");try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{StudentChangeRequest r=requests.findById(c,id);if(r==null)throw new IllegalArgumentException("申请不存在");if(r.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请已处理");if(result==StudentChangeStatus.APPROVED&&!students.updateApprovedFields(c,r.getStudentId(),r.getItems()))throw new SQLException("正式学籍更新失败");if(!requests.updateReview(c,id,result,reviewer,remark))throw new IllegalStateException("申请状态已变化");c.commit();}catch(Exception e){c.rollback();throw e;}finally{c.setAutoCommit(true);}}}
 public boolean updateByAdmin(Student s)throws SQLException{return students.update(s);} public boolean addAward(StudentAward a)throws SQLException{return awards.insert(a);} public boolean updateAward(StudentAward a)throws SQLException{return awards.update(a);} public boolean deleteAward(long id)throws SQLException{return awards.delete(id);} public boolean addAid(StudentAid a)throws SQLException{return aids.insert(a);} public boolean updateAid(StudentAid a)throws SQLException{return aids.update(a);} public boolean deleteAid(long id)throws SQLException{return aids.delete(id);}
 private Student requireStudent(String userId)throws SQLException{Student s=students.findByUserId(userId);if(s==null)throw new IllegalArgumentException("当前用户没有学籍");return s;}
}
