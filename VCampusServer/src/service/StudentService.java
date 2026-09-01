package service;
import dao.*;
import entity.*;
import enums.StudentChangeStatus;
import util.DBUtil;
import vo.StudentOverviewVO;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
public class StudentService {
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
    private static final long EDIT_LEASE_MILLIS = 15 * 60 * 1000L;
    private static final Map<String, EditLease> EDIT_LEASES = new ConcurrentHashMap<>();
    private record EditLease(String owner,long expiresAt) {}
    private final StudentDAO students = new StudentDAO();
    private final StudentChangeRequestDAO requests = new StudentChangeRequestDAO();
    private final StudentAwardDAO awards = new StudentAwardDAO();
    private final StudentAidDAO aids = new StudentAidDAO();
    public StudentOverviewVO queryByUserId(String userId) throws SQLException {
        Student student = students.findByUserId(userId);
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
        result.setPendingRequest(requests.findPendingByStudentId(student.getStudentId()));
        return result;
    }
    public List<Student> listStudents() throws SQLException {
        return students.findAll();
    }
    public List<StudentChangeRequest> listMyRequests(String userId) throws SQLException {
        return requests.findByStudentId(requireStudent(userId).getStudentId());
    }
    public List<StudentChangeRequest> listPending() throws SQLException {
        return requests.findAll();
    }
    public StudentChangeRequest queryRequest(long requestId) throws SQLException {
        StudentChangeRequest request = requests.findById(requestId);
        if (request == null) throw new IllegalArgumentException("修改申请不存在");
        return request;
    }
    public void cancel(String userId, long requestId) throws SQLException {
        if (!requests.cancel(requestId, requireStudent(userId).getStudentId())) {
            throw new IllegalStateException("申请不存在或已处理");
        }
    }
    public long submit(String userId, StudentChangeRequest request) throws SQLException {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("修改项不能为空");
        }
        try (Connection connection = DBUtil.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Student student = students.lockByUserId(connection, userId);
                if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
                assertMayMutate(student.getStudentId(),"STUDENT:"+userId);
                validateStudentRequest(student, request);
                if (requests.findPendingByStudentId(connection, student.getStudentId()) != null) {
                    throw new IllegalStateException("已有待审核申请");
                }
                request.setStudentId(student.getStudentId());
                long requestId = requests.insert(connection, request);
                connection.commit();
                releaseLease(student.getStudentId(),"STUDENT:"+userId);
                return requestId;
            }
            catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }
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
                if (result == StudentChangeStatus.APPROVED && !students.updateApprovedFields(connection, request.getStudentId(), request.getItems())) {
                    throw new SQLException("正式学籍更新失败");
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
    public boolean updateByAdmin(String adminId,Student student) throws SQLException {
        if (student == null || student.getStudentId() == null || student.getStudentId().isBlank()) {
            throw new IllegalArgumentException("学生信息不能为空");
        }
        assertMayMutate(student.getStudentId(),"ADMIN:"+adminId);
        if (!students.update(student)) throw new IllegalStateException("学生不存在或更新失败");
        releaseLease(student.getStudentId(),"ADMIN:"+adminId);
        return true;
    }
    public String beginEdit(String userId, boolean admin, String requestedStudentId) throws SQLException {
        Student student = admin ? students.findByStudentId(required(requestedStudentId,"缺少学生学号")) : requireStudent(userId);
        if (student == null) throw new IllegalArgumentException("学生不存在");
        String studentId=student.getStudentId(), owner=(admin?"ADMIN:":"STUDENT:")+userId;
        long now=System.currentTimeMillis();
        EDIT_LEASES.compute(studentId,(id,current)-> {
            if(current==null||current.expiresAt()<now||current.owner().equals(owner))return new EditLease(owner,now+EDIT_LEASE_MILLIS);
            throw new IllegalStateException("该学生信息正在被另一端编辑，请稍后再试");
        });
        return studentId;
    }
    public void endEdit(String userId, boolean admin, String requestedStudentId) throws SQLException {
        Student student=admin?null:requireStudent(userId);
        String studentId=admin?required(requestedStudentId,"缺少学生学号"):student.getStudentId();
        String owner=(admin?"ADMIN:":"STUDENT:")+userId;
        EDIT_LEASES.computeIfPresent(studentId,(id,current)->current.owner().equals(owner)?null:current);
    }
    public boolean addAward(StudentAward award) throws SQLException {
        return awards.insert(award);
    }
    public boolean updateAward(StudentAward award) throws SQLException {
        return awards.update(award);
    }
    public boolean deleteAward(long awardId) throws SQLException {
        return awards.delete(awardId);
    }
    public boolean addAid(StudentAid aid) throws SQLException {
        return aids.insert(aid);
    }
    public boolean updateAid(StudentAid aid) throws SQLException {
        return aids.update(aid);
    }
    public boolean deleteAid(long aidId) throws SQLException {
        return aids.delete(aidId);
    }
    private Student requireStudent(String userId) throws SQLException {
        Student student = students.findByUserId(userId);
        if (student == null) throw new IllegalArgumentException("当前用户没有学籍");
        return student;
    }
    private void validateStudentRequest(Student current,StudentChangeRequest request)throws SQLException {
        Set<String> seen=ConcurrentHashMap.newKeySet();
        for(StudentChangeItem item:request.getItems()) {
            String field=item.getFieldName();
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
    private static String normalize(String value){return value==null?"":value.trim();}
    private static boolean truthy(String value){return Set.of("true","1","是","在籍","在校").contains(normalize(value));}
    private static void assertMayMutate(String studentId,String owner) {
        EditLease lease=EDIT_LEASES.get(studentId);long now=System.currentTimeMillis();
        if(lease!=null&&lease.expiresAt()>=now&&!lease.owner().equals(owner))throw new IllegalStateException("该学生信息正在被另一端编辑，请稍后再试");
    }
    private static void releaseLease(String studentId,String owner) {
        EDIT_LEASES.computeIfPresent(studentId,(id,current)->current.owner().equals(owner)?null:current);
    }
    private static String required(String value,String message){if(value==null||value.isBlank())throw new IllegalArgumentException(message);return value;}
}
