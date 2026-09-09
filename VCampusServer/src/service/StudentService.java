package service;
import com.google.gson.Gson;
import dao.*;
import entity.*;
import enums.StudentChangeStatus;
import util.DBUtil;
import vo.StudentOverviewVO;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import protocol.LockRequest;
import session.UserSession;
public class StudentService implements IStudentService {
    @Override
    public void authorizeLock(UserSession user, LockRequest proof) throws SQLException {
        boolean admin = "ADMIN".equalsIgnoreCase(user.getRole()) || "管理员".equals(user.getRole());
        if ("STUDENT_CHANGE_REQUEST".equals(proof.resourceType())) {
            if (!admin) throw new SecurityException("仅管理员可审核");
            StudentChangeRequest request = queryRequest(Long.parseLong(proof.resourceId()));
            if (request.getStatus() != StudentChangeStatus.PENDING) throw new IllegalStateException("申请已处理，请刷新");
            return;
        }
        Student target = admin ? students.findByStudentId(proof.resourceId()) : students.findByUID(user.getUsername());
        if (target == null) throw new IllegalArgumentException("学生档案不存在");
        if (!target.getStudentId().equals(proof.resourceId())) throw new SecurityException("无权编辑其他人的档案");
        if (requests.findPendingByStudentId(target.getStudentId()) != null) throw new IllegalStateException("存在待审核申请，请先审核");
    }
    private static final String EXPERIENCE_ADD="experience.add", EXPERIENCE_UPDATE="experience.update", EXPERIENCE_DELETE="experience.delete";
    private static final String FAMILY_ADD="family.add", FAMILY_UPDATE="family.update", FAMILY_DELETE="family.delete";
    private static final Set<String> STUDENT_EDITABLE_FIELDS = Set.of(
            "politicalStatus", "nationality", "gender", "idType", "idNumber", "idIssueDate",
            "birthDate", "nativePlace", "householdType", "birthPlace", "sourcePlace",
            "registeredResidence", "leagueMember", "leagueJoinDate", "partyMember", "partyJoinDate",
            "healthStatus", "campus", "trainingMode", "schoolingLength", "counselorName",
            "counselorPhone", "candidateCategory", "admissionDate", "admissionMethod",
            "graduationSchool", "middleSchoolClass", "middleSchoolTeacher", "telephone", "mobile",
            "email", "qq", "wechat", "campusAddress", "emergencyContact", "emergencyPhone"
    );
    private static final Set<String> REQUIRED_FIELDS = Set.of(
            "politicalStatus", "nationality", "gender", "idType", "idNumber", "idIssueDate",
            "birthDate", "nativePlace", "householdType", "birthPlace", "sourcePlace",
            "registeredResidence", "leagueMember", "healthStatus", "graduationSchool", "telephone",
            "mobile", "emergencyContact", "emergencyPhone"
    );
    private static final Set<String> ID_TYPES = Set.of("居民身份证", "港澳台居民居住证", "护照", "其他");
    private static final Set<String> HOUSEHOLD_TYPES = Set.of("城镇户口", "农村居民户口", "集体户口");
    private final StudentDAO students = new StudentDAO();
    private final StudentChangeRequestDAO requests = new StudentChangeRequestDAO();
    private final StudentAwardDAO awards = new StudentAwardDAO();
    private final StudentAidDAO aids = new StudentAidDAO();
    private final StudentExperienceDAO experiences = new StudentExperienceDAO();
    private final StudentFamilyMemberDAO familyMembers = new StudentFamilyMemberDAO();
    private final Gson gson = new Gson();
    @Override
    public StudentOverviewVO queryByUID(String UID) throws SQLException {
        Student student = students.findByUID(UID);
        return student == null ? null : overview(student);
    }
    @Override
    public StudentOverviewVO queryByStudentId(String studentId) throws SQLException {
        Student student = students.findByStudentId(studentId);
        return student == null ? null : overview(student);
    }
    private StudentOverviewVO overview(Student student) throws SQLException {
        StudentOverviewVO result = new StudentOverviewVO();
        result.setStudent(student);
        result.setAwards(awards.findByStudentId(student.getStudentId()));
        result.setAids(aids.findByStudentId(student.getStudentId()));
        result.setExperiences(experiences.findByStudentId(student.getStudentId()));
        result.setFamilyMembers(familyMembers.findByStudentId(student.getStudentId()));
        result.setPendingRequest(requests.findPendingByStudentId(student.getStudentId()));
        result.setLatestRequest(requests.findLatestByStudentId(student.getStudentId()));
        return result;
    }
    @Override
    public List<Student> listStudents() throws SQLException {
        return students.findAll();
    }
    @Override
    public List<StudentChangeRequest> listMyRequests(String UID) throws SQLException {
        return requests.findByStudentId(requireStudent(UID).getStudentId());
    }
    @Override
    public List<StudentChangeRequest> listPending() throws SQLException {
        return requests.findAll();
    }
    @Override
    public StudentChangeRequest queryRequest(long requestId) throws SQLException {
        StudentChangeRequest request = requests.findById(requestId);
        if (request == null) throw new IllegalArgumentException("修改申请不存在");
        return request;
    }
    @Override
    public void cancel(String UID, long requestId) throws SQLException {
        if (!requests.cancel(requestId, requireStudent(UID).getStudentId())) {
            throw new IllegalStateException("申请不存在或已处理");
        }
    }
    @Override
    public long submit(String UID, StudentChangeRequest request) throws SQLException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("修改项不能为空");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Student student = students.lockByUID(connection, UID);
                if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
                validateStudentRequest(student, request);
                if (requests.findPendingByStudentId(connection, student.getStudentId()) != null) {
                    throw new IllegalStateException("已有待审核申请");
                }
                request.setStudentId(student.getStudentId());
                long requestId = requests.insert(connection, request);
                connection.commit();
                return requestId;
            }
            catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
    @Override
    public void review(long requestId, StudentChangeStatus result, String reviewer, String remark) throws SQLException {
        if (result != StudentChangeStatus.APPROVED && result != StudentChangeStatus.REJECTED) {
            throw new IllegalArgumentException("审核结果无效");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            try {
                StudentChangeRequest request = requests.findByIdForUpdate(connection, requestId);
                if (request == null || request.getStatus() != StudentChangeStatus.PENDING) {
                    throw new IllegalStateException("申请不存在或已处理");
                }
                if (result == StudentChangeStatus.APPROVED) {
                    List<StudentChangeItem> studentFields=new ArrayList<>();
                    for(StudentChangeItem item:request.getItems()) {
                        if(isRelatedRecordOperation(item.getFieldName()))applyRelatedRecordOperation(connection,request.getStudentId(),item);
                        else studentFields.add(item);
                    }
                    if(!studentFields.isEmpty()&&!students.updateApprovedFields(connection, request.getStudentId(), studentFields)) {
                        throw new SQLException("正式学籍更新失败");
                    }
                }
                if (!requests.review(connection, requestId, result, reviewer, remark)) {
                    throw new IllegalStateException("申请状态已变化");
                }
                connection.commit();
            }
            catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
    @Override
    public boolean updateByAdmin(String adminId,Student student,Student original) throws SQLException {
        if(student==null || original==null || student.getStudentId()==null
                || !student.getStudentId().equals(original.getStudentId()))
            throw new IllegalArgumentException("缺少原始学籍快照或学生编号已变化，请刷新");
        return students.updateIfUnchanged(student,original);
    }
    @Override
    public String studentIdForUser(String uid)throws SQLException{return requireStudent(uid).getStudentId();}
    @Override
    public String recordStudentId(String table,String column,long id)throws SQLException {
        if(!Set.of("tblStudentAward:awardId","tblStudentAid:aidId").contains(table+":"+column))
            throw new IllegalArgumentException("记录类型无效");
        try(Connection c=DBUtil.getConnection();java.sql.PreparedStatement p=c.prepareStatement(
                "SELECT studentId FROM "+table+" WHERE "+column+"=?")) {
            p.setLong(1,id);
            try(java.sql.ResultSet r=p.executeQuery()) {
                if(!r.next())throw new IllegalArgumentException("记录不存在，请刷新");
                return r.getString(1);
            }
        }
    }
    @Override
    public boolean addAward(StudentAward award) throws SQLException {
        validateAward(award);
        return awards.insert(award);
    }
    @Override
    public boolean updateAward(StudentAward award) throws SQLException {
        validateAward(award);
        return awards.update(award);
    }
    @Override
    public boolean deleteAward(long awardId) throws SQLException {
        return awards.delete(awardId);
    }
    @Override
    public boolean addAid(StudentAid aid) throws SQLException {
        validateAid(aid);
        return aids.insert(aid);
    }
    @Override
    public boolean updateAid(StudentAid aid) throws SQLException {
        validateAid(aid);
        return aids.update(aid);
    }
    @Override
    public boolean deleteAid(long aidId) throws SQLException {
        return aids.delete(aidId);
    }
    private void validateAward(StudentAward award){
        if(award==null)throw new IllegalArgumentException("奖励信息不能为空");
        if(award.getAwardName()==null||award.getAwardName().isBlank())throw new IllegalArgumentException("奖励名称不能为空");
        if(award.getAwardType()==null)throw new IllegalArgumentException("奖励类型不能为空");
        if(award.getAwardDate()==null)throw new IllegalArgumentException("奖励日期不能为空");
    }
    private void validateAid(StudentAid aid){
        if(aid==null)throw new IllegalArgumentException("资助信息不能为空");
        if(aid.getAidName()==null||aid.getAidName().isBlank())throw new IllegalArgumentException("资助名称不能为空");
        if(aid.getAidType()==null||aid.getAidType().isBlank())throw new IllegalArgumentException("资助类型不能为空");
        if(aid.getAidDate()==null)throw new IllegalArgumentException("资助日期不能为空");
    }
    @Override
    public boolean addExperience(String UID,StudentExperience value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("学习经历不能为空");validateRelatedRecord(value);
        value.setExperienceId(null);value.setStudentId(requireStudent(UID).getStudentId());return requireChanged(experiences.insert(value),"学习经历添加失败");
    }
    @Override
    public boolean addFamilyMember(String UID,StudentFamilyMember value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("家庭成员不能为空");validateRelatedRecord(value);
        value.setMemberId(null);value.setStudentId(requireStudent(UID).getStudentId());return requireChanged(familyMembers.insert(value),"家庭成员添加失败");
    }
    @Override
    public boolean updateExperience(String UID,StudentExperience value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("学习经历不能为空");validateRelatedRecord(value);requiredId(value.getExperienceId(),"学习经历");
        return requireChanged(experiences.update(requireStudent(UID).getStudentId(),value),"学习经历不存在或更新失败");
    }
    @Override
    public boolean deleteExperience(String UID,long id)throws SQLException{return requireChanged(experiences.delete(requireStudent(UID).getStudentId(),id),"学习经历不存在或删除失败");}
    @Override
    public boolean updateFamilyMember(String UID,StudentFamilyMember value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("家庭成员不能为空");validateRelatedRecord(value);requiredId(value.getMemberId(),"家庭成员");
        return requireChanged(familyMembers.update(requireStudent(UID).getStudentId(),value),"家庭成员不存在或更新失败");
    }
    @Override
    public boolean deleteFamilyMember(String UID,long id)throws SQLException{return requireChanged(familyMembers.delete(requireStudent(UID).getStudentId(),id),"家庭成员不存在或删除失败");}
    private long submitRelatedRecord(String UID,String operation,Object oldValue,Object newValue)throws SQLException {
        if(newValue==null)throw new IllegalArgumentException("提交内容不能为空");
        if(!operation.endsWith(".delete"))validateRelatedRecord(newValue);
        StudentChangeItem item=new StudentChangeItem();item.setFieldName(operation);
        item.setOldValue(oldValue==null?"":gson.toJson(oldValue));item.setNewValue(gson.toJson(newValue));
        StudentChangeRequest request=new StudentChangeRequest();request.setItems(List.of(item));
        return submit(UID,request);
    }
    private void applyRelatedRecordOperation(Connection c,String studentId,StudentChangeItem item)throws SQLException {
        boolean changed=switch(item.getFieldName()) {
            case EXPERIENCE_ADD -> {StudentExperience x=gson.fromJson(item.getNewValue(),StudentExperience.class);x.setExperienceId(null);x.setStudentId(studentId);yield experiences.insert(c,x);}
            case EXPERIENCE_UPDATE -> {StudentExperience x=gson.fromJson(item.getNewValue(),StudentExperience.class);yield experiences.update(c,studentId,x);}
            case EXPERIENCE_DELETE -> {StudentExperience x=gson.fromJson(item.getNewValue(),StudentExperience.class);yield experiences.delete(c,studentId,requiredId(x.getExperienceId(),"学习经历"));}
            case FAMILY_ADD -> {StudentFamilyMember x=gson.fromJson(item.getNewValue(),StudentFamilyMember.class);x.setMemberId(null);x.setStudentId(studentId);yield familyMembers.insert(c,x);}
            case FAMILY_UPDATE -> {StudentFamilyMember x=gson.fromJson(item.getNewValue(),StudentFamilyMember.class);yield familyMembers.update(c,studentId,x);}
            case FAMILY_DELETE -> {StudentFamilyMember x=gson.fromJson(item.getNewValue(),StudentFamilyMember.class);yield familyMembers.delete(c,studentId,requiredId(x.getMemberId(),"家庭成员"));}
            default -> throw new IllegalArgumentException("未知关联信息变更类型");
        };
        if(!changed)throw new IllegalStateException("待审核的数据已发生变化，请驳回后让学生重新提交");
    }
    private Student requireStudent(String UID) throws SQLException {
        Student student = students.findByUID(UID);
        if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
        return student;
    }
    private void validateStudentRequest(Student current,StudentChangeRequest request)throws SQLException {
        Set<String> seen=ConcurrentHashMap.newKeySet();
        for(StudentChangeItem item:request.getItems()) {
            String field=item.getFieldName();
            if(isRelatedRecordOperation(field)) {
                if(!seen.add(field))throw new IllegalArgumentException("一次申请不能包含重复的关联信息操作");
                continue;
            }
            if(field==null||!STUDENT_EDITABLE_FIELDS.contains(field)||!seen.add(field))
                throw new SecurityException("学生无权修改字段: "+field);
            String actual=students.fieldValueAsString(current,field);
            if(!actual.equals(normalize(item.getOldValue())))throw new IllegalStateException("学籍信息已变化，请刷新后重新编辑");
            item.setOldValue(actual);
        }
        Map<String,String> finalValues=new java.util.HashMap<>();
        for(String field:STUDENT_EDITABLE_FIELDS)finalValues.put(field,students.fieldValueAsString(current,field));
        for(StudentChangeItem item:request.getItems())finalValues.put(item.getFieldName(),normalize(item.getNewValue()));
        for(String field:REQUIRED_FIELDS)if(finalValues.getOrDefault(field,"").isBlank())
            throw new IllegalArgumentException("必填字段不能为空: "+field);
        if(truthy(finalValues.get("leagueMember"))&&finalValues.getOrDefault("leagueJoinDate","").isBlank())
            throw new IllegalArgumentException("团员必须填写入团时间");
        if(truthy(finalValues.get("partyMember"))&&finalValues.getOrDefault("partyJoinDate","").isBlank())
            throw new IllegalArgumentException("党员必须填写入党时间");
        if(!ID_TYPES.contains(finalValues.get("idType")))throw new IllegalArgumentException("身份证件类型无效");
        if(!HOUSEHOLD_TYPES.contains(finalValues.get("householdType")))throw new IllegalArgumentException("户口性质无效");
    }
    private static boolean isRelatedRecordOperation(String field){return Set.of(EXPERIENCE_ADD,EXPERIENCE_UPDATE,EXPERIENCE_DELETE,FAMILY_ADD,FAMILY_UPDATE,FAMILY_DELETE).contains(field);}
    private static long requiredId(Long id,String name){if(id==null||id<=0)throw new IllegalArgumentException(name+"编号无效");return id;}
    private static boolean requireChanged(boolean changed,String message){if(!changed)throw new IllegalStateException(message);return true;}
    private static void validateRelatedRecord(Object value){
        if(value instanceof StudentExperience x) {
            if(x.getStartDate()==null||x.getEndDate()==null||normalize(x.getSchoolName()).isBlank()||normalize(x.getEducationLevel()).isBlank())throw new IllegalArgumentException("开始日期、结束日期、学校名称和学习阶段不能为空");
            if(x.getEndDate().before(x.getStartDate()))throw new IllegalArgumentException("结束日期不能早于开始日期");
        } else if(value instanceof StudentFamilyMember x) {
            if(normalize(x.getName()).isBlank()||normalize(x.getRelationship()).isBlank()||x.getBirthDate()==null||normalize(x.getRegisteredResidence()).isBlank()||normalize(x.getWorkplace()).isBlank()||normalize(x.getPhone()).isBlank())throw new IllegalArgumentException("家庭成员姓名、关系、出生年月、户口所在地、工作单位和联系电话不能为空");
        }
    }
    private static String normalize(String value){return value==null?"":value.trim();}
    private static boolean truthy(String value){return Set.of("true","1","是","在籍","在校").contains(normalize(value));}
    private static String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value;}
}
