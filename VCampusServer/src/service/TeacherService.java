package service;
import dao.*; import entity.*; import enums.StudentChangeStatus; import util.DBUtil; import vo.TeacherOverviewVO; import java.sql.*; import java.util.*;
import lock.ResourceLockManager;
import protocol.LockRequest;
import session.UserSession;
public class TeacherService {
    private final ResourceLockManager locks=ResourceLockManager.getInstance();
    private boolean admin(UserSession s) {return "ADMIN".equalsIgnoreCase(s.getRole()) || "管理员".equals(s.getRole());}

 private static final Set<String> EDITABLE=Set.of("name","politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate","nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","partyMember","partyJoinDate","healthStatus","employed","employmentStatus","campus","college","department","title","position","education","employmentStartDate","telephone","mobile","email","qq","wechat","officeAddress","emergencyContact","emergencyPhone");
 private final TeacherDAO teachers=new TeacherDAO(); private final TeacherChangeRequestDAO requests=new TeacherChangeRequestDAO();private final TeacherWorkExperienceDAO workExperiences=new TeacherWorkExperienceDAO();private final TeacherFamilyMemberDAO familyMembers=new TeacherFamilyMemberDAO();
 public TeacherOverviewVO queryByUID(String UID)throws SQLException{return overview(teachers.findByUID(UID));}
 public TeacherOverviewVO queryByTeacherId(String id)throws SQLException{return overview(teachers.findByTeacherId(id));}
 private TeacherOverviewVO overview(Teacher t)throws SQLException{if(t==null)return null;TeacherOverviewVO v=new TeacherOverviewVO();v.setTeacher(t);v.setPendingRequest(requests.findPendingByTeacherId(t.getTeacherId()));v.setLatestRequest(requests.findLatestByTeacherId(t.getTeacherId()));v.setWorkExperiences(workExperiences.findByTeacherId(t.getTeacherId()));v.setFamilyMembers(familyMembers.findByTeacherId(t.getTeacherId()));return v;}
    public void cancel(String UID,long id)throws SQLException {
        String owner=requireTeacher(UID).getTeacherId();
        try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{
            TeacherChangeRequest request=requests.findByIdForUpdate(c,id);
            if(request==null||!owner.equals(request.getTeacherId()))throw new SecurityException("无权撤回该申请");
            if(request.getStatus()!=StudentChangeStatus.PENDING||!requests.cancel(c,id,owner))throw new IllegalStateException("申请已处理，请刷新");
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }
 public List<Teacher> listTeachers()throws SQLException{return teachers.findAll();} public List<TeacherChangeRequest> listAll()throws SQLException{return requests.findAll();}
 public TeacherChangeRequest queryRequest(long id)throws SQLException{TeacherChangeRequest r=requests.findById(id);if(r==null)throw new IllegalArgumentException("申请不存在");return r;}
 private long submit(String UID,TeacherChangeRequest r)throws SQLException{Teacher t=teachers.findByUID(UID);if(t==null)throw new IllegalArgumentException("当前用户没有教师档案");if(r==null||r.getItems()==null||r.getItems().isEmpty())throw new IllegalArgumentException("修改项不能为空");if(requests.findPendingByTeacherId(t.getTeacherId())!=null)throw new IllegalStateException("已有待审核申请");Set<String> seen=new HashSet<>();for(TeacherChangeItem i:r.getItems())if(!EDITABLE.contains(i.getFieldName())||!seen.add(i.getFieldName()))throw new SecurityException("无权修改字段: "+i.getFieldName());Teacher proposed=new Teacher();proposed.setDepartment(t.getDepartment());proposed.setEducation(t.getEducation());proposed.setEmploymentStartDate(t.getEmploymentStartDate());proposed.setTelephone(t.getTelephone());proposed.setEmergencyContact(t.getEmergencyContact());proposed.setEmergencyPhone(t.getEmergencyPhone());for(TeacherChangeItem i:r.getItems()){if("department".equals(i.getFieldName()))proposed.setDepartment(i.getNewValue());else if("education".equals(i.getFieldName()))proposed.setEducation(i.getNewValue());else if("employmentStartDate".equals(i.getFieldName()))proposed.setEmploymentStartDate(i.getNewValue()==null||i.getNewValue().isBlank()?null:java.sql.Date.valueOf(i.getNewValue()));else if("telephone".equals(i.getFieldName()))proposed.setTelephone(i.getNewValue());else if("emergencyContact".equals(i.getFieldName()))proposed.setEmergencyContact(i.getNewValue());else if("emergencyPhone".equals(i.getFieldName()))proposed.setEmergencyPhone(i.getNewValue());}validateRequiredTeacher(proposed);r.setTeacherId(t.getTeacherId());try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{Teacher locked=teachers.lockByTeacherId(c,t.getTeacherId());if(locked==null)throw new IllegalStateException("教师不存在");if(requests.findPendingByTeacherId(c,t.getTeacherId())!=null)throw new IllegalStateException("已有待审核申请");long id=requests.insert(c,r);c.commit();return id;}catch(Exception e){c.rollback();throw e;}}}
 private void review(long id,StudentChangeStatus result,String reviewer,String note)throws SQLException{if(result!=StudentChangeStatus.APPROVED&&result!=StudentChangeStatus.REJECTED)throw new IllegalArgumentException("审核结果无效");try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{TeacherChangeRequest r=requests.findByIdForUpdate(c,id);if(r==null||r.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请不存在或已处理");if(result==StudentChangeStatus.APPROVED&&!teachers.apply(c,r.getTeacherId(),r.getItems()))throw new SQLException("教师信息更新失败");if(!requests.review(c,id,result,reviewer,note))throw new IllegalStateException("申请状态已变化");c.commit();}catch(Exception e){c.rollback();throw e;}}}
 private boolean updateByAdmin(Teacher t)throws SQLException{if(t==null||t.getTeacherId()==null)throw new IllegalArgumentException("教师信息不能为空");validateRequiredTeacher(t);if(requests.findPendingByTeacherId(t.getTeacherId())!=null)throw new IllegalStateException("存在待审核申请，请先审核");if(!teachers.update(t))throw new IllegalStateException("教师不存在或更新失败");return true;}
 private boolean addWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);value.setExperienceId(null);value.setTeacherId(teacher.getTeacherId());return requireChanged(workExperiences.insert(value),"工作经历添加失败");}
 private boolean updateWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);if(value.getExperienceId()==null||value.getExperienceId()<=0)throw new IllegalArgumentException("工作经历编号无效");return requireChanged(workExperiences.update(teacher.getTeacherId(),value),"工作经历不存在或更新失败");}
 private boolean deleteWorkExperience(String UID,long id)throws SQLException{return requireChanged(workExperiences.delete(requireTeacher(UID).getTeacherId(),id),"工作经历不存在或删除失败");}
 private boolean addFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{Teacher teacher=requireTeacher(UID);validateFamily(value);value.setMemberId(null);value.setTeacherId(teacher.getTeacherId());return requireChanged(familyMembers.insert(value),"主要社会关系添加失败");}
 private boolean updateFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{Teacher teacher=requireTeacher(UID);validateFamily(value);if(value.getMemberId()==null||value.getMemberId()<=0)throw new IllegalArgumentException("成员编号无效");return requireChanged(familyMembers.update(teacher.getTeacherId(),value),"主要社会关系不存在或更新失败");}
 private boolean deleteFamilyMember(String UID,long id)throws SQLException{return requireChanged(familyMembers.delete(requireTeacher(UID).getTeacherId(),id),"主要社会关系不存在或删除失败");}
 private Teacher requireTeacher(String UID)throws SQLException{Teacher teacher=teachers.findByUID(UID);if(teacher==null)throw new IllegalArgumentException("当前用户没有教师档案");return teacher;}
 private void validateRequiredTeacher(Teacher value){if(value.getDepartment()==null||value.getDepartment().isBlank())throw new IllegalArgumentException("部门不能为空");if(value.getEducation()==null||value.getEducation().isBlank())throw new IllegalArgumentException("学历不能为空");if(value.getEmploymentStartDate()==null)throw new IllegalArgumentException("入职日期不能为空");if(value.getTelephone()==null||value.getTelephone().isBlank())throw new IllegalArgumentException("联系电话不能为空");if(value.getEmergencyContact()==null||value.getEmergencyContact().isBlank())throw new IllegalArgumentException("紧急联系人不能为空");if(value.getEmergencyPhone()==null||value.getEmergencyPhone().isBlank())throw new IllegalArgumentException("紧急联系人联系方式不能为空");}
 private void validateExperience(TeacherWorkExperience value){if(value==null)throw new IllegalArgumentException("工作经历不能为空");if(value.getStartDate()==null)throw new IllegalArgumentException("开始时间不能为空");if(value.getOrganization()==null||value.getOrganization().isBlank())throw new IllegalArgumentException("工作单位不能为空");if(value.getDepartment()==null||value.getDepartment().isBlank())throw new IllegalArgumentException("所在部门不能为空");if(value.getPosition()==null||value.getPosition().isBlank())throw new IllegalArgumentException("职务不能为空");if(value.getEndDate()!=null&&value.getEndDate().before(value.getStartDate()))throw new IllegalArgumentException("结束时间不能早于开始时间");}
 private void validateFamily(TeacherFamilyMember value){if(value==null)throw new IllegalArgumentException("主要社会关系不能为空");if(value.getName()==null||value.getName().isBlank())throw new IllegalArgumentException("姓名不能为空");if(value.getRelationship()==null||value.getRelationship().isBlank())throw new IllegalArgumentException("与本人关系不能为空");if(value.getBirthDate()==null)throw new IllegalArgumentException("出生年月不能为空");if(value.getWorkplace()==null||value.getWorkplace().isBlank())throw new IllegalArgumentException("工作单位不能为空");if(value.getPhone()==null||value.getPhone().isBlank())throw new IllegalArgumentException("联系电话不能为空");}
 private boolean requireChanged(boolean changed,String message){if(!changed)throw new IllegalStateException(message);return true;}

    public void authorizeLock(UserSession user,LockRequest proof)throws SQLException {
        if("TEACHER_CHANGE_REQUEST".equals(proof.resourceType())) {
            if(!admin(user))throw new SecurityException("仅管理员可审核");
            TeacherChangeRequest request=queryRequest(Long.parseLong(proof.resourceId()));
            if(request.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请已处理，请刷新");
        } else {
            Teacher value=teacherForLock(user,proof.resourceId());
            if("TEACHER".equals(proof.resourceType())&&requests.findPendingByTeacherId(value.getTeacherId())!=null)throw new IllegalStateException(admin(user)?"该教师档案存在待审核申请，请先完成审核后再编辑":"当前已有修改申请正在等待审核，审核完成后才能再次编辑");
        }
    }
    private Teacher teacherForLock(UserSession user,String id)throws SQLException {
        Teacher value=admin(user)?teachers.findByTeacherId(id):requireTeacher(user.getUsername());
        if(value==null)throw new IllegalArgumentException("档案不存在");
        if(!value.getTeacherId().equals(id))throw new SecurityException("无权编辑其他人的档案");
        return value;
    }
    public long submit(UserSession user,TeacherChangeRequest request,LockRequest proof)throws SQLException {
        Teacher value=requireTeacher(user.getUsername());String key="TEACHER:"+value.getTeacherId();
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            long id=submit(user.getUsername(),request);
            locks.release(user,proof);return id;
        }
    }
    public boolean updateByAdmin(UserSession user,Teacher value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(value==null || value.getTeacherId()==null)throw new IllegalArgumentException("缺少档案编号");
        String key="TEACHER:"+value.getTeacherId();
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            boolean updated=updateByAdmin(value);
            locks.release(user,proof);return updated;
        }
    }
    public void review(UserSession user,long id,StudentChangeStatus result,String note,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        String key="TEACHER_CHANGE_REQUEST:"+id;
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            review(id,result,user.getUsername(),note);
            locks.release(user,proof);
        }
    }
    public TeacherChangeRequest queryRequest(UserSession user,long id,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可审核");
        TeacherChangeRequest result=queryRequest(id);
        if(result.getStatus()==StudentChangeStatus.PENDING)locks.validate(user,proof,"TEACHER_CHANGE_REQUEST:"+id);
        return result;
    }
    public boolean addWorkExperience(UserSession user,TeacherWorkExperience value,LockRequest proof)throws SQLException {
        String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=addWorkExperience(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean updateWorkExperience(UserSession user,TeacherWorkExperience value,LockRequest proof)throws SQLException {
        String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=updateWorkExperience(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean deleteWorkExperience(UserSession user,long id,LockRequest proof)throws SQLException {
        String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=deleteWorkExperience(user.getUsername(),id);locks.release(user,proof);return changed;}
    }
    public boolean addFamilyMember(UserSession user,TeacherFamilyMember value,LockRequest proof)throws SQLException {String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=addFamilyMember(user.getUsername(),value);locks.release(user,proof);return changed;}}
    public boolean updateFamilyMember(UserSession user,TeacherFamilyMember value,LockRequest proof)throws SQLException {String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=updateFamilyMember(user.getUsername(),value);locks.release(user,proof);return changed;}}
    public boolean deleteFamilyMember(UserSession user,long id,LockRequest proof)throws SQLException {String key="TEACHER_RECORDS:"+requireTeacher(user.getUsername()).getTeacherId();try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=deleteFamilyMember(user.getUsername(),id);locks.release(user,proof);return changed;}}
}
