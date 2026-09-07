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
import lock.ResourceLockManager;
import protocol.LockRequest;
import session.UserSession;
public class StudentService {
    private final ResourceLockManager locks=ResourceLockManager.getInstance();
    private boolean admin(UserSession s) {return "ADMIN".equalsIgnoreCase(s.getRole()) || "管理员".equals(s.getRole());}

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
    public StudentOverviewVO queryByUID(String UID) throws SQLException {
        Student student = students.findByUID(UID);
        return student == null ? null : overview(student);
    }
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
    public List<Student> listStudents() throws SQLException {
        return students.findAll();
    }
    public List<StudentChangeRequest> listMyRequests(String UID) throws SQLException {
        return requests.findByStudentId(requireStudent(UID).getStudentId());
    }
    public List<StudentChangeRequest> listPending() throws SQLException {
        return requests.findAll();
    }
    public StudentChangeRequest queryRequest(long requestId) throws SQLException {
        StudentChangeRequest request = requests.findById(requestId);
        if (request == null) throw new IllegalArgumentException("修改申请不存在");
        return request;
    }
    public void cancel(String UID,long id)throws SQLException {
        String owner=requireStudent(UID).getStudentId();
        try(Connection c=DBUtil.getConnection()){c.setAutoCommit(false);try{
            StudentChangeRequest request=requests.findByIdForUpdate(c,id);
            if(request==null||!owner.equals(request.getStudentId()))throw new SecurityException("无权撤回该申请");
            if(request.getStatus()!=StudentChangeStatus.PENDING||!requests.cancel(c,id,owner))throw new IllegalStateException("申请已处理，请刷新");
            c.commit();
        }catch(Exception e){c.rollback();throw e;}}
    }
    private long submit(String UID, StudentChangeRequest request) throws SQLException {
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
    private void review(long requestId, StudentChangeStatus result, String reviewer, String remark) throws SQLException {
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
    private boolean updateByAdmin(String adminId,Student student) throws SQLException {
        if (student == null || student.getStudentId() == null || student.getStudentId().isBlank()) {
            throw new IllegalArgumentException("学生信息不能为空");
        }
        if(requests.findPendingByStudentId(student.getStudentId())!=null)throw new IllegalStateException("存在待审核申请，请先审核");
        if (!students.update(student)) throw new IllegalStateException("学生不存在或更新失败");

        return true;
    }
    private boolean addAward(StudentAward award) throws SQLException {
        if(award==null||award.getAwardName()==null||award.getAwardType()==null||award.getAwardDate()==null||award.getAwardName().isBlank())throw new IllegalArgumentException("名称、类型和日期不能为空");
        return awards.insert(award);
    }
    private boolean updateAward(StudentAward award) throws SQLException {
        if(award==null||award.getAwardName()==null||award.getAwardType()==null||award.getAwardDate()==null||award.getAwardName().isBlank())throw new IllegalArgumentException("名称、类型和日期不能为空");
        return awards.update(award);
    }
    private boolean deleteAward(long awardId) throws SQLException {
        return awards.delete(awardId);
    }
    private boolean addAid(StudentAid aid) throws SQLException {
        if(aid==null||aid.getAidName()==null||aid.getAidType()==null||aid.getAidDate()==null||aid.getAidName().isBlank())throw new IllegalArgumentException("名称、类型和日期不能为空");
        return aids.insert(aid);
    }
    private boolean updateAid(StudentAid aid) throws SQLException {
        if(aid==null||aid.getAidName()==null||aid.getAidType()==null||aid.getAidDate()==null||aid.getAidName().isBlank())throw new IllegalArgumentException("名称、类型和日期不能为空");
        return aids.update(aid);
    }
    private boolean deleteAid(long aidId) throws SQLException {
        return aids.delete(aidId);
    }
    private boolean addExperience(String UID,StudentExperience value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("学习经历不能为空");validateRelatedRecord(value);
        value.setExperienceId(null);value.setStudentId(requireStudent(UID).getStudentId());return requireChanged(experiences.insert(value),"学习经历添加失败");
    }
    private boolean addFamilyMember(String UID,StudentFamilyMember value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("家庭成员不能为空");validateRelatedRecord(value);
        value.setMemberId(null);value.setStudentId(requireStudent(UID).getStudentId());return requireChanged(familyMembers.insert(value),"家庭成员添加失败");
    }
    private boolean updateExperience(String UID,StudentExperience value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("学习经历不能为空");validateRelatedRecord(value);requiredId(value.getExperienceId(),"学习经历");
        return requireChanged(experiences.update(requireStudent(UID).getStudentId(),value),"学习经历不存在或更新失败");
    }
    private boolean deleteExperience(String UID,long id)throws SQLException{return requireChanged(experiences.delete(requireStudent(UID).getStudentId(),id),"学习经历不存在或删除失败");}
    private boolean updateFamilyMember(String UID,StudentFamilyMember value)throws SQLException{
        if(value==null)throw new IllegalArgumentException("家庭成员不能为空");validateRelatedRecord(value);requiredId(value.getMemberId(),"家庭成员");
        return requireChanged(familyMembers.update(requireStudent(UID).getStudentId(),value),"家庭成员不存在或更新失败");
    }
    private boolean deleteFamilyMember(String UID,long id)throws SQLException{return requireChanged(familyMembers.delete(requireStudent(UID).getStudentId(),id),"家庭成员不存在或删除失败");}
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

    public void authorizeLock(UserSession user,LockRequest proof)throws SQLException {
        if("STUDENT_CHANGE_REQUEST".equals(proof.resourceType())) {
            if(!admin(user))throw new SecurityException("仅管理员可审核");
            StudentChangeRequest request=queryRequest(Long.parseLong(proof.resourceId()));
            if(request.getStatus()!=StudentChangeStatus.PENDING)throw new IllegalStateException("申请已处理，请刷新");
        } else {
            Student value=studentForLock(user,proof.resourceId());
            if("STUDENT".equals(proof.resourceType())&&requests.findPendingByStudentId(value.getStudentId())!=null) {
                throw new IllegalStateException(admin(user)
                        ?"该学生档案存在待审核申请，请先完成审核后再编辑"
                        :"当前已有修改申请正在等待审核，审核完成后才能再次编辑");
            }
        }
    }
    private Student studentForLock(UserSession user,String id)throws SQLException {
        Student value=admin(user)?students.findByStudentId(id):requireStudent(user.getUsername());
        if(value==null)throw new IllegalArgumentException("档案不存在");
        if(!value.getStudentId().equals(id))throw new SecurityException("无权编辑其他人的档案");
        return value;
    }
    public long submit(UserSession user,StudentChangeRequest request,LockRequest proof)throws SQLException {
        Student value=requireStudent(user.getUsername());String key="STUDENT:"+value.getStudentId();
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            long id=submit(user.getUsername(),request);
            locks.release(user,proof);return id;
        }
    }
    public boolean updateByAdmin(UserSession user,Student value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(value==null || value.getStudentId()==null)throw new IllegalArgumentException("缺少档案编号");
        String key="STUDENT:"+value.getStudentId();
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            boolean updated=updateByAdmin(user.getUsername(),value);
            locks.release(user,proof);return updated;
        }
    }
    public void review(UserSession user,long id,StudentChangeStatus result,String note,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        String key="STUDENT_CHANGE_REQUEST:"+id;
        try(var guard=locks.guard(key)) {
            locks.validate(user,proof,key);
            review(id,result,user.getUsername(),note);
            locks.release(user,proof);
        }
    }
    public StudentChangeRequest queryRequest(UserSession user,long id,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可审核");
        StudentChangeRequest result=queryRequest(id);
        if(result.getStatus()==StudentChangeStatus.PENDING)locks.validate(user,proof,"STUDENT_CHANGE_REQUEST:"+id);
        return result;
    }
    public boolean addExperience(UserSession user,StudentExperience value,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=addExperience(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean updateExperience(UserSession user,StudentExperience value,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=updateExperience(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean deleteExperience(UserSession user,long id,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=deleteExperience(user.getUsername(),id);locks.release(user,proof);return changed;}
    }
    public boolean addFamilyMember(UserSession user,StudentFamilyMember value,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=addFamilyMember(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean updateFamilyMember(UserSession user,StudentFamilyMember value,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=updateFamilyMember(user.getUsername(),value);locks.release(user,proof);return changed;}
    }
    public boolean deleteFamilyMember(UserSession user,long id,LockRequest proof)throws SQLException {
        String key="STUDENT_RECORDS:"+requireStudent(user.getUsername()).getStudentId();
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);boolean changed=deleteFamilyMember(user.getUsername(),id);locks.release(user,proof);return changed;}
    }
    public boolean addAward(UserSession user,StudentAward value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(value==null||!id.equals(value.getStudentId()))throw new SecurityException("档案编号不匹配");boolean changed=addAward(value);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
    public boolean updateAward(UserSession user,StudentAward value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(value==null||!id.equals(value.getStudentId()))throw new SecurityException("档案编号不匹配");if(awards.findByStudentId(id).stream().noneMatch(x->java.util.Objects.equals(x.getAwardId(),value.getAwardId())))throw new SecurityException("记录不属于当前档案");boolean changed=updateAward(value);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
    public boolean deleteAward(UserSession user,long recordId,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(awards.findByStudentId(id).stream().noneMatch(x->java.util.Objects.equals(x.getAwardId(),recordId)))throw new SecurityException("记录不属于当前档案");boolean changed=deleteAward(recordId);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
    public boolean addAid(UserSession user,StudentAid value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(value==null||!id.equals(value.getStudentId()))throw new SecurityException("档案编号不匹配");boolean changed=addAid(value);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
    public boolean updateAid(UserSession user,StudentAid value,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(value==null||!id.equals(value.getStudentId()))throw new SecurityException("档案编号不匹配");if(aids.findByStudentId(id).stream().noneMatch(x->java.util.Objects.equals(x.getAidId(),value.getAidId())))throw new SecurityException("记录不属于当前档案");boolean changed=updateAid(value);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
    public boolean deleteAid(UserSession user,long recordId,LockRequest proof)throws SQLException {
        if(!admin(user))throw new SecurityException("仅管理员可操作");
        if(proof==null)throw new IllegalStateException(ResourceLockManager.LOST);
        String id=proof.resourceId(),key="STUDENT_RECORDS:"+id;
        try(var guard=locks.guard(key)){locks.validate(user,proof,key);authorizeLock(user,proof);if(aids.findByStudentId(id).stream().noneMatch(x->java.util.Objects.equals(x.getAidId(),recordId)))throw new SecurityException("记录不属于当前档案");boolean changed=deleteAid(recordId);if(!changed)throw new IllegalStateException("记录已变化，请刷新");locks.release(user,proof);return true;}
    }
}
