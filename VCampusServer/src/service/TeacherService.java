package service;
import dao.*; import entity.*; import enums.StudentChangeStatus; import util.DBUtil; import util.InformationDateRules; import vo.TeacherOverviewVO; import java.sql.*; import java.util.*; import protocol.LockRequest; import session.UserSession;
import util.LocalTimeConnection;
public class TeacherService implements ITeacherService {
 @Override
    public void cancel(String UID,long requestId)throws SQLException{
  if(!requests.cancel(requestId,requireTeacher(UID).getTeacherId()))throw new IllegalStateException("申请不存在、已处理或不属于当前用户，请刷新后重试");
 }
 @Override
    public void authorizeLock(UserSession user,LockRequest proof)throws SQLException{boolean admin="ADMIN".equalsIgnoreCase(user.getRole())||"管理员".equals(user.getRole());if("TEACHER_CHANGE_REQUEST".equals(proof.resourceType())){if(!admin)throw new SecurityException("仅管理员可审核");TeacherChangeRequest request=queryRequest(Long.parseLong(proof.resourceId()));if(request.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请已处理，请刷新");return;}Teacher target=admin?teachers.findByTeacherId(proof.resourceId()):teachers.findByUID(user.getUsername());if(target==null)throw new IllegalArgumentException("教师档案不存在");if(!target.getTeacherId().equals(proof.resourceId()))throw new SecurityException("无权编辑其他人的档案");if(requests.findPendingByTeacherId(target.getTeacherId())!=null)throw new IllegalStateException("存在待审核申请，请先审核");}
 private static final Set<String> EDITABLE=Set.of("politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate","nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","partyMember","partyJoinDate","healthStatus","campus","department","education","employmentStartDate","telephone","mobile","email","qq","wechat","officeAddress","emergencyContact","emergencyPhone");
 private static final Set<String> REQUIRED=Set.of("politicalStatus","nationality","gender","idType","idNumber","idIssueDate","birthDate","nativePlace","householdType","birthPlace","sourcePlace","registeredResidence","partyMember","department","education","employmentStartDate","telephone","mobile","emergencyContact","emergencyPhone");
 private final TeacherDAO teachers=new TeacherDAO(); private final TeacherChangeRequestDAO requests=new TeacherChangeRequestDAO();private final TeacherWorkExperienceDAO workExperiences=new TeacherWorkExperienceDAO();private final TeacherFamilyMemberDAO familyMembers=new TeacherFamilyMemberDAO();
 @Override
    public TeacherOverviewVO queryByUID(String UID)throws SQLException{return overview(teachers.findByUID(UID));}
 @Override
    public TeacherOverviewVO queryByTeacherId(String id)throws SQLException{return overview(teachers.findByTeacherId(id));}
 private TeacherOverviewVO overview(Teacher t)throws SQLException{if(t==null)return null;TeacherOverviewVO v=new TeacherOverviewVO();v.setTeacher(t);v.setPendingRequest(requests.findPendingByTeacherId(t.getTeacherId()));v.setLatestRequest(requests.findLatestByTeacherId(t.getTeacherId()));v.setWorkExperiences(workExperiences.findByTeacherId(t.getTeacherId()));v.setFamilyMembers(familyMembers.findByTeacherId(t.getTeacherId()));return v;}
 @Override
    public List<Teacher> listTeachers()throws SQLException{return teachers.findAll();} @Override
    public List<TeacherChangeRequest> listAll()throws SQLException{return requests.findAll();}
 @Override
    public TeacherChangeRequest queryRequest(long id)throws SQLException{TeacherChangeRequest r=requests.findById(id);if(r==null)throw new IllegalArgumentException("申请不存在");return r;}
 @Override
    public long submit(String UID,TeacherChangeRequest r)throws SQLException{Teacher t=teachers.findByUID(UID);if(t==null)throw new IllegalArgumentException("当前用户没有教师档案");if(r==null||r.getItems()==null||r.getItems().isEmpty())throw new IllegalArgumentException("修改项不能为空");if(requests.findPendingByTeacherId(t.getTeacherId())!=null)throw new IllegalStateException("已有待审核申请");Set<String> seen=new HashSet<>();for(TeacherChangeItem i:r.getItems()){if(!EDITABLE.contains(i.getFieldName())||!seen.add(i.getFieldName()))throw new SecurityException("无权修改字段: "+i.getFieldName());}Map<String,String> finalValues=new HashMap<>();for(String field:EDITABLE)finalValues.put(field,teachers.fieldValueAsString(t,field));for(TeacherChangeItem item:r.getItems())finalValues.put(item.getFieldName(),teachers.canonicalValue(item.getFieldName(),item.getNewValue()));validateRequiredValues(finalValues);for(TeacherChangeItem item:r.getItems()) {
 String actual=teachers.fieldValueAsString(t,item.getFieldName());
 if(!actual.equals(teachers.canonicalValue(item.getFieldName(),item.getOldValue())))throw new IllegalStateException("教师信息已变化，请刷新后重新编辑");
 item.setOldValue(actual);String canonical=teachers.canonicalValue(item.getFieldName(),item.getNewValue());validateDateField(item.getFieldName(),canonical);
 }r.setTeacherId(t.getTeacherId());try(Connection c=LocalTimeConnection.getConnection()){c.setAutoCommit(false);try{long id=requests.insert(c,r);c.commit();return id;}catch(Exception e){c.rollback();throw e;}}}
 @Override
    public void review(long id, StudentChangeStatus result, String reviewer, String note) throws SQLException {
        note = util.InformationRules.reviewNote(note);
        if (result != StudentChangeStatus.APPROVED && result != StudentChangeStatus.REJECTED)
            throw new IllegalArgumentException("审核结果无效");
        try (Connection c = LocalTimeConnection.getConnection()) {
            c.setAutoCommit(false);
            try {
                TeacherChangeRequest r = requests.findByIdForUpdate(c, id);
                if (r == null || r.getStatus() != StudentChangeStatus.PENDING)
                    throw new IllegalStateException("申请不存在或已处理");
                if (result == StudentChangeStatus.APPROVED) {
                    for (TeacherChangeItem item : r.getItems())
                        validateDateField(item.getFieldName(), teachers.canonicalValue(item.getFieldName(), item.getNewValue()));
                    if (!teachers.apply(c, r.getTeacherId(), r.getItems()))
                        throw new SQLException("教师信息更新失败");
                    new UserDAO().syncUserInfo(c, r.getTeacherId());
                }
                if (!requests.review(c, id, result, reviewer, note))
                    throw new IllegalStateException("申请状态已变化");
                String teacherUid = r.getTeacherId();
                String reviewTitle = "教职审核结果";
                String reviewContent = result == StudentChangeStatus.APPROVED
                        ? "您的教职信息修改申请已审核通过"
                        : "您的教职信息修改申请已被驳回" + (note != null && !note.isBlank() ? "（原因：" + note + "）" : "");
                NotificationService.notify(c, teacherUid, "REVIEW", reviewTitle, reviewContent, "TEACHER_STATUS");
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }
 @Override
    public boolean updateByAdmin(Teacher t,Teacher original)throws SQLException{
		if(t==null||original==null||t.getTeacherId()==null||!t.getTeacherId().equals(original.getTeacherId()))throw new IllegalArgumentException("缺少原始教师快照或教师编号已变化，请刷新");
		validateRequiredJob(t);validateTeacherDates(t);boolean ok=teachers.updateIfUnchanged(t,original);if(ok){new UserDAO().syncUserInfo(t.getUID()!=null?t.getUID():t.getTeacherId());}return ok;
	}
 @Override public String teacherIdForUser(String uid)throws SQLException{return requireTeacher(uid).getTeacherId();}
 private void validateRequiredJob(Teacher t)throws SQLException{Map<String,String> values=new HashMap<>();for(String field:EDITABLE)values.put(field,teachers.fieldValueAsString(t,field));validateRequiredValues(values);}
 private static void validateRequiredValues(Map<String,String> values){Set<String> required=new HashSet<>(REQUIRED);if(Set.of("true","1","是").contains(values.getOrDefault("partyMember","").trim()))required.add("partyJoinDate");for(String field:required)if(values.getOrDefault(field,"").isBlank())throw new IllegalArgumentException(requiredLabel(field)+"不能为空");}
 private static String requiredLabel(String field){return switch(field){case "politicalStatus"->"政治面貌";case "nationality"->"民族";case "gender"->"性别";case "idType"->"身份证件类型";case "idNumber"->"身份证号";case "idIssueDate"->"身份证签发日期";case "birthDate"->"出生日期";case "nativePlace"->"籍贯";case "householdType"->"户口性质";case "birthPlace"->"出生地";case "sourcePlace"->"生源地";case "registeredResidence"->"户口所在地";case "partyMember"->"是否党员";case "partyJoinDate"->"入党时间";case "department"->"部门";case "education"->"学历";case "employmentStartDate"->"入职日期";case "telephone"->"联系电话";case "mobile"->"手机号";case "emergencyContact"->"紧急联系人";case "emergencyPhone"->"紧急联系人联系方式";default->field;};}
 @Override
    public boolean addWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);value.setExperienceId(null);value.setTeacherId(teacher.getTeacherId());return requireChanged(InformationRecordLimit.insert("tblTeacherWorkExperience",value.getTeacherId(),c->workExperiences.insert(c,value)),"工作经历添加失败");}
 @Override
    public boolean updateWorkExperience(String UID,TeacherWorkExperience value)throws SQLException{Teacher teacher=requireTeacher(UID);validateExperience(value);if(value.getExperienceId()==null||value.getExperienceId()<=0)throw new IllegalArgumentException("工作经历编号无效");return requireChanged(workExperiences.update(teacher.getTeacherId(),value),"工作经历不存在或更新失败");}
 @Override
    public boolean deleteWorkExperience(String UID,long id)throws SQLException{return requireChanged(workExperiences.delete(requireTeacher(UID).getTeacherId(),id),"工作经历不存在或删除失败");}
 @Override
    public boolean addFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{Teacher teacher=requireTeacher(UID);validateFamilyMember(value);value.setMemberId(null);value.setTeacherId(teacher.getTeacherId());return requireChanged(InformationRecordLimit.insert("tblTeacherFamilyMember",value.getTeacherId(),c->familyMembers.insert(c,value)),"社会关系成员添加失败");}
 @Override
    public boolean updateFamilyMember(String UID,TeacherFamilyMember value)throws SQLException{Teacher teacher=requireTeacher(UID);validateFamilyMember(value);if(value.getMemberId()==null||value.getMemberId()<=0)throw new IllegalArgumentException("社会关系成员编号无效");return requireChanged(familyMembers.update(teacher.getTeacherId(),value),"社会关系成员不存在或更新失败");}
 @Override
    public boolean deleteFamilyMember(String UID,long id)throws SQLException{return requireChanged(familyMembers.delete(requireTeacher(UID).getTeacherId(),id),"社会关系成员不存在或删除失败");}
 private Teacher requireTeacher(String UID)throws SQLException{Teacher teacher=teachers.findByUID(UID);if(teacher==null)throw new IllegalArgumentException("当前用户没有教师档案");return teacher;}
 private void validateExperience(TeacherWorkExperience value){if(value==null)throw new IllegalArgumentException("工作经历不能为空");if(value.getStartDate()==null)throw new IllegalArgumentException("开始时间不能为空");if(value.getOrganization()==null||value.getOrganization().isBlank())throw new IllegalArgumentException("工作单位不能为空");if(value.getDepartment()==null||value.getDepartment().isBlank())throw new IllegalArgumentException("所在部门不能为空");if(value.getPosition()==null||value.getPosition().isBlank())throw new IllegalArgumentException("职务不能为空");InformationDateRules.requireNotFuture(value.getStartDate(),"工作经历开始日期");InformationDateRules.requireNotFuture(value.getEndDate(),"工作经历结束日期");if(value.getEndDate()!=null&&value.getEndDate().before(value.getStartDate()))throw new IllegalArgumentException("结束时间不能早于开始时间");}
 private void validateFamilyMember(TeacherFamilyMember value){if(value==null)throw new IllegalArgumentException("社会关系成员不能为空");if(value.getName()==null||value.getName().isBlank())throw new IllegalArgumentException("姓名不能为空");if(value.getRelationship()==null||value.getRelationship().isBlank())throw new IllegalArgumentException("与本人关系不能为空");if(value.getBirthDate()==null)throw new IllegalArgumentException("出生年月不能为空");InformationDateRules.requireNotFuture(value.getBirthDate(),"家庭成员出生日期");if(value.getRegisteredResidence()==null||value.getRegisteredResidence().isBlank())throw new IllegalArgumentException("户口所在地不能为空");if(value.getWorkplace()==null||value.getWorkplace().isBlank())throw new IllegalArgumentException("工作单位不能为空");if(value.getPhone()==null||value.getPhone().isBlank())throw new IllegalArgumentException("联系电话不能为空");}
 private static void validateTeacherDates(Teacher t){InformationDateRules.requireNotFuture(t.getBirthDate(),"出生日期");InformationDateRules.requireNotFuture(t.getIdIssueDate(),"身份证签发日期");InformationDateRules.requireNotFuture(t.getPartyJoinDate(),"入党日期");InformationDateRules.requireNotFuture(t.getEmploymentStartDate(),"入职日期");}
 private static void validateDateField(String field,String value){String label=switch(field){case "birthDate"->"出生日期";case "idIssueDate"->"身份证签发日期";case "partyJoinDate"->"入党日期";case "employmentStartDate"->"入职日期";default->null;};if(label!=null)InformationDateRules.requireNotFuture(value,label);}
 private boolean requireChanged(boolean changed,String message){if(!changed)throw new IllegalStateException(message);return true;}
}
