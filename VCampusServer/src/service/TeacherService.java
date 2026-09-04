package service;
import dao.*; import entity.*; import enums.StudentChangeStatus; import util.DBUtil; import vo.TeacherOverviewVO; import java.sql.*; import java.util.*;
public class TeacherService {
 private static void validateJob(String education,java.sql.Date start){if(education==null||education.isBlank())throw new IllegalArgumentException("学历不能为空");if(start==null)throw new IllegalArgumentException("入职日期不能为空");}
 private static void validateJobChanges(Teacher teacher,List<TeacherChangeItem> items){String education=teacher.getEducation();java.sql.Date start=teacher.getEmploymentStartDate();for(TeacherChangeItem item:items){if("education".equals(item.getFieldName()))education=item.getNewValue();if("employmentStartDate".equals(item.getFieldName())){String value=item.getNewValue();try{start=value==null||value.isBlank()?null:java.sql.Date.valueOf(value.trim());}catch(IllegalArgumentException e){throw new IllegalArgumentException("入职日期格式错误");}}}validateJob(education,start);}
 private final TeacherFamilyMemberDAO familyMembers=new TeacherFamilyMemberDAO();
 private static final Set<String> EDITABLE=Set.of("politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate","nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","partyMember","partyJoinDate","healthStatus","education","employmentStartDate","campus","telephone","mobile","email","qq","wechat","officeAddress","emergencyContact","emergencyPhone");
 private final TeacherDAO teachers=new TeacherDAO(); private final TeacherChangeRequestDAO requests=new TeacherChangeRequestDAO();private final TeacherWorkExperienceDAO workExperiences=new TeacherWorkExperienceDAO();
 public TeacherOverviewVO queryByUID(String UID)throws SQLException{return overview(teachers.findByUID(UID));}
 public TeacherOverviewVO queryByTeacherId(String id)throws SQLException{return overview(teachers.findByTeacherId(id));}
 private TeacherOverviewVO overview(Teacher t)throws SQLException{if(t==null)return null;TeacherOverviewVO v=new TeacherOverviewVO();v.setTeacher(t);v.setPendingRequest(requests.findPendingByTeacherId(t.getTeacherId()));v.setLatestRequest(requests.findLatestByTeacherId(t.getTeacherId()));v.setWorkExperiences(workExperiences.findByTeacherId(t.getTeacherId()));v.setFamilyMembers(familyMembers.findByTeacherId(t.getTeacherId()));return v;}
 public List<Teacher> listTeachers()throws SQLException{return teachers.findAll();} public List<TeacherChangeRequest> listAll()throws SQLException{return requests.findAll();}
 public TeacherChangeRequest queryRequest(long id)throws SQLException{TeacherChangeRequest r=requests.findById(id);if(r==null)throw new IllegalArgumentException("申请不存在");return r;}
 public long submit(String UID,TeacherChangeRequest r)throws SQLException{Teacher t=teachers.findByUID(UID);if(t==null)throw new IllegalArgumentException("当前用户没有教师档案");if(r==null||r.getItems()==null||r.getItems().isEmpty())throw new IllegalArgumentException("修改项不能为空");if(requests.findPendingByTeacherId(t.getTeacherId())!=null)throw new IllegalStateException("已有待审核申请");Set<String> seen=new HashSet<>();for(TeacherChangeItem i:r.getItems())if(!EDITABLE.contains(i.getFieldName())||!seen.add(i.getFieldName()))throw new SecurityException("无权修改字段: "+i.getFieldName());validateJobChanges(t,r.getItems());r.setTeacherId(t.getTeacherId());try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{long id=requests.insert(c,r);c.commit();return id;}catch(Exception e){c.rollback();throw e;}}}
 public void review(long id,StudentChangeStatus result,String reviewer,String note)throws SQLException{if(result!=StudentChangeStatus.APPROVED&&result!=StudentChangeStatus.REJECTED)throw new IllegalArgumentException("审核结果无效");try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{TeacherChangeRequest r=requests.findByIdForUpdate(c,id);if(r==null||r.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请不存在或已处理");if(result==StudentChangeStatus.APPROVED&&!teachers.apply(c,r.getTeacherId(),r.getItems()))throw new SQLException("教师信息更新失败");if(!requests.review(c,id,result,reviewer,note))throw new IllegalStateException("申请状态已变化");c.commit();}catch(Exception e){c.rollback();throw e;}}}
 public boolean updateByAdmin(Teacher t)throws SQLException{if(t==null||t.getTeacherId()==null)throw new IllegalArgumentException("教师信息不能为空");validateJob(t.getEducation(),t.getEmploymentStartDate());return teachers.update(t);}
 public boolean addWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);value.setExperienceId(null);value.setTeacherId(teacher.getTeacherId());return requireChanged(workExperiences.insert(value),"工作经历添加失败");}
 public boolean updateWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);if(value.getExperienceId()==null||value.getExperienceId()<=0)throw new IllegalArgumentException("工作经历编号无效");return requireChanged(workExperiences.update(teacher.getTeacherId(),value),"工作经历不存在或更新失败");}
 public boolean deleteWorkExperience(String UID,long id)throws SQLException{return requireChanged(workExperiences.delete(requireTeacher(UID).getTeacherId(),id),"工作经历不存在或删除失败");}
 public boolean addFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{
  Teacher teacher=requireTeacher(UID);validateFamilyMember(value);value.setMemberId(null);value.setTeacherId(teacher.getTeacherId());
  return requireChanged(familyMembers.insert(value),"成员添加失败");
 }
 public boolean updateFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{
  Teacher teacher=requireTeacher(UID);validateFamilyMember(value);
  if(value.getMemberId()==null||value.getMemberId()<=0)throw new IllegalArgumentException("成员编号无效");
  return requireChanged(familyMembers.update(teacher.getTeacherId(),value),"成员不存在或更新失败");
 }
 public boolean deleteFamilyMember(String UID,long id)throws SQLException{
  if(id<=0)throw new IllegalArgumentException("成员编号无效");
  return requireChanged(familyMembers.delete(requireTeacher(UID).getTeacherId(),id),"成员不存在或删除失败");
 }
 private void validateFamilyMember(TeacherFamilyMember value){
  if(value==null)throw new IllegalArgumentException("成员信息不能为空");
  String[] labels={"姓名","与本人关系","户口所在地","工作单位","联系电话"};
  String[] values={value.getName(),value.getRelationship(),value.getRegisteredResidence(),value.getWorkplace(),value.getPhone()};
  for(int i=0;i<labels.length;i++)if(values[i]==null||values[i].isBlank())throw new IllegalArgumentException(labels[i]+"不能为空");
 }
 private Teacher requireTeacher(String UID)throws SQLException{Teacher teacher=teachers.findByUID(UID);if(teacher==null)throw new IllegalArgumentException("当前用户没有教师档案");return teacher;}
 private void validateExperience(TeacherWorkExperience value){if(value==null)throw new IllegalArgumentException("工作经历不能为空");if(value.getStartDate()==null)throw new IllegalArgumentException("开始时间不能为空");if(value.getOrganization()==null||value.getOrganization().isBlank())throw new IllegalArgumentException("工作单位不能为空");if(value.getDepartment()==null||value.getDepartment().isBlank())throw new IllegalArgumentException("所在部门不能为空");if(value.getPosition()==null||value.getPosition().isBlank())throw new IllegalArgumentException("职务不能为空");if(value.getEndDate()!=null&&value.getEndDate().before(value.getStartDate()))throw new IllegalArgumentException("结束时间不能早于开始时间");}
 private boolean requireChanged(boolean changed,String message){if(!changed)throw new IllegalStateException(message);return true;}
}
