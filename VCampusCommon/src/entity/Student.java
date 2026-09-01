package entity;
import java.io.Serializable;
import java.sql.Date;
public class Student implements Serializable {
    private static final long serialVersionUID = 1L;

    private String studentId;
    private String userId;
    private String name;
    private String gender;
    private String politicalStatus;
    private String nationality;
    private String idType;
    private String idNumber;
    private String nativePlace;
    private String householdType;
    private String birthPlace;
    private String sourcePlace;
    private String registeredResidence;
    private String healthStatus;
    private String studentCategory;
    private String studentStatus;
    private String campus;
    private String grade;
    private String college;
    private String major;
    private String className;
    private String educationLevel;
    private String trainingMode;
    private String counselorName;
    private String counselorPhone;
    private String candidateCategory;
    private String admissionMethod;
    private String graduationSchool;
    private String middleSchoolClass;
    private String middleSchoolTeacher;
    private String telephone;
    private String mobile;
    private String email;
    private String qq;
    private String wechat;
    private String campusAddress;
    private String emergencyContact;
    private String emergencyPhone;

    private Date idIssueDate;
    private Date birthDate;
    private Date leagueJoinDate;
    private Date partyJoinDate;
    private Date admissionDate;

    private boolean leagueMember;
    private boolean partyMember;
    private boolean registered;
    private boolean inSchool;
    private int schoolingLength;
    public String getStudentId() {
        return studentId;
    }
    public void setStudentId(String v) {
        studentId=v;
    }
    public String getUserId() {
        return userId;
    }
    public void setUserId(String v) {
        userId=v;
    }
    public String getName() {
        return name;
    }
    public void setName(String v) {
        name=v;
    }
    public String getGender() {
        return gender;
    }
    public void setGender(String v) {
        gender=v;
    }
    public String getPoliticalStatus() {
        return politicalStatus;
    }
    public void setPoliticalStatus(String v) {
        politicalStatus=v;
    }
    public String getNationality() {
        return nationality;
    }
    public void setNationality(String v) {
        nationality=v;
    }
    public String getIdType() {
        return idType;
    }
    public void setIdType(String v) {
        idType=v;
    }
    public String getIdNumber() {
        return idNumber;
    }
    public void setIdNumber(String v) {
        idNumber=v;
    }
    public Date getIdIssueDate() {
        return idIssueDate;
    }
    public void setIdIssueDate(Date v) {
        idIssueDate=v;
    }
    public Date getBirthDate() {
        return birthDate;
    }
    public void setBirthDate(Date v) {
        birthDate=v;
    }
    public String getNativePlace() {
        return nativePlace;
    }
    public void setNativePlace(String v) {
        nativePlace=v;
    }
    public String getHouseholdType() {
        return householdType;
    }
    public void setHouseholdType(String v) {
        householdType=v;
    }
    public String getBirthPlace() {
        return birthPlace;
    }
    public void setBirthPlace(String v) {
        birthPlace=v;
    }
    public String getSourcePlace() {
        return sourcePlace;
    }
    public void setSourcePlace(String v) {
        sourcePlace=v;
    }
    public String getRegisteredResidence() {
        return registeredResidence;
    }
    public void setRegisteredResidence(String v) {
        registeredResidence=v;
    }
    public boolean isLeagueMember() {
        return leagueMember;
    }
    public void setLeagueMember(boolean v) {
        leagueMember=v;
    }
    public Date getLeagueJoinDate() {
        return leagueJoinDate;
    }
    public void setLeagueJoinDate(Date v) {
        leagueJoinDate=v;
    }
    public boolean isPartyMember() {
        return partyMember;
    }
    public void setPartyMember(boolean v) {
        partyMember=v;
    }
    public Date getPartyJoinDate() {
        return partyJoinDate;
    }
    public void setPartyJoinDate(Date v) {
        partyJoinDate=v;
    }
    public String getHealthStatus() {
        return healthStatus;
    }
    public void setHealthStatus(String v) {
        healthStatus=v;
    }
    public String getStudentCategory() {
        return studentCategory;
    }
    public void setStudentCategory(String v) {
        studentCategory=v;
    }
    public boolean isRegistered() {
        return registered;
    }
    public void setRegistered(boolean v) {
        registered=v;
    }
    public boolean isInSchool() {
        return inSchool;
    }
    public void setInSchool(boolean v) {
        inSchool=v;
    }
    public String getStudentStatus() {
        return studentStatus;
    }
    public void setStudentStatus(String v) {
        studentStatus=v;
    }
    public String getCampus() {
        return campus;
    }
    public void setCampus(String v) {
        campus=v;
    }
    public String getGrade() {
        return grade;
    }
    public void setGrade(String v) {
        grade=v;
    }
    public String getCollege() {
        return college;
    }
    public void setCollege(String v) {
        college=v;
    }
    public String getMajor() {
        return major;
    }
    public void setMajor(String v) {
        major=v;
    }
    public String getClassName() {
        return className;
    }
    public void setClassName(String v) {
        className=v;
    }
    public String getEducationLevel() {
        return educationLevel;
    }
    public void setEducationLevel(String v) {
        educationLevel=v;
    }
    public String getTrainingMode() {
        return trainingMode;
    }
    public void setTrainingMode(String v) {
        trainingMode=v;
    }
    public int getSchoolingLength() {
        return schoolingLength;
    }
    public void setSchoolingLength(int v) {
        schoolingLength=v;
    }
    public String getCounselorName() {
        return counselorName;
    }
    public void setCounselorName(String v) {
        counselorName=v;
    }
    public String getCounselorPhone() {
        return counselorPhone;
    }
    public void setCounselorPhone(String v) {
        counselorPhone=v;
    }
    public String getCandidateCategory() {
        return candidateCategory;
    }
    public void setCandidateCategory(String v) {
        candidateCategory=v;
    }
    public Date getAdmissionDate() {
        return admissionDate;
    }
    public void setAdmissionDate(Date v) {
        admissionDate=v;
    }
    public String getAdmissionMethod() {
        return admissionMethod;
    }
    public void setAdmissionMethod(String v) {
        admissionMethod=v;
    }
    public String getGraduationSchool() {
        return graduationSchool;
    }
    public void setGraduationSchool(String v) {
        graduationSchool=v;
    }
    public String getMiddleSchoolClass() {
        return middleSchoolClass;
    }
    public void setMiddleSchoolClass(String v) {
        middleSchoolClass=v;
    }
    public String getMiddleSchoolTeacher() {
        return middleSchoolTeacher;
    }
    public void setMiddleSchoolTeacher(String v) {
        middleSchoolTeacher=v;
    }
    public String getTelephone() {
        return telephone;
    }
    public void setTelephone(String v) {
        telephone=v;
    }
    public String getMobile() {
        return mobile;
    }
    public void setMobile(String v) {
        mobile=v;
    }
    public String getEmail() {
        return email;
    }
    public void setEmail(String v) {
        email=v;
    }
    public String getQq() {
        return qq;
    }
    public void setQq(String v) {
        qq=v;
    }
    public String getWechat() {
        return wechat;
    }
    public void setWechat(String v) {
        wechat=v;
    }
    public String getCampusAddress() {
        return campusAddress;
    }
    public void setCampusAddress(String v) {
        campusAddress=v;
    }
    public String getEmergencyContact() {
        return emergencyContact;
    }
    public void setEmergencyContact(String v) {
        emergencyContact=v;
    }
    public String getEmergencyPhone() {
        return emergencyPhone;
    }
    public void setEmergencyPhone(String v) {
        emergencyPhone=v;
    }
}
