package entity;
import java.io.Serializable;
import java.sql.Date;
public class Teacher implements Serializable {
    private static final long serialVersionUID=1L;
    private String UID,teacherId,name,politicalStatus,nationality,gender,idType,idNumber,nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,healthStatus,employmentStatus,campus,college,department,title,position,telephone,mobile,email,qq,wechat,officeAddress,emergencyContact,emergencyPhone;
    private String education;
    private Date employmentStartDate;
    public String getEducation(){return education;} public void setEducation(String v){education=v;}
    public Date getEmploymentStartDate(){return employmentStartDate;} public void setEmploymentStartDate(Date v){employmentStartDate=v;}
    private Date idIssueDate,birthDate,partyJoinDate; private boolean partyMember,employed;
    public String getUID(){return UID;} public void setUID(String v){UID=v;}
    public String getTeacherId(){return teacherId;} public void setTeacherId(String v){teacherId=v;}
    public String getName(){return name;} public void setName(String v){name=v;}
    public String getPoliticalStatus(){return politicalStatus;} public void setPoliticalStatus(String v){politicalStatus=v;}
    public String getNationality(){return nationality;} public void setNationality(String v){nationality=v;}
    public String getGender(){return gender;} public void setGender(String v){gender=v;}
    public String getIdType(){return idType;} public void setIdType(String v){idType=v;}
    public String getIdNumber(){return idNumber;} public void setIdNumber(String v){idNumber=v;}
    public Date getIdIssueDate(){return idIssueDate;} public void setIdIssueDate(Date v){idIssueDate=v;}
    public Date getBirthDate(){return birthDate;} public void setBirthDate(Date v){birthDate=v;}
    public String getNativePlace(){return nativePlace;} public void setNativePlace(String v){nativePlace=v;}
    public String getHouseholdType(){return householdType;} public void setHouseholdType(String v){householdType=v;}
    public String getBirthPlace(){return birthPlace;} public void setBirthPlace(String v){birthPlace=v;}
    public String getSourcePlace(){return sourcePlace;} public void setSourcePlace(String v){sourcePlace=v;}
    public String getRegisteredResidence(){return registeredResidence;} public void setRegisteredResidence(String v){registeredResidence=v;}
    public boolean isPartyMember(){return partyMember;} public void setPartyMember(boolean v){partyMember=v;}
    public Date getPartyJoinDate(){return partyJoinDate;} public void setPartyJoinDate(Date v){partyJoinDate=v;}
    public String getHealthStatus(){return healthStatus;} public void setHealthStatus(String v){healthStatus=v;}
    public boolean isEmployed(){return employed;} public void setEmployed(boolean v){employed=v;}
    public String getEmploymentStatus(){return employmentStatus;} public void setEmploymentStatus(String v){employmentStatus=v;}
    public String getCampus(){return campus;} public void setCampus(String v){campus=v;}
    public String getCollege(){return college;} public void setCollege(String v){college=v;}
    public String getDepartment(){return department;} public void setDepartment(String v){department=v;}
    public String getTitle(){return title;} public void setTitle(String v){title=v;}
    public String getPosition(){return position;} public void setPosition(String v){position=v;}
    public String getTelephone(){return telephone;} public void setTelephone(String v){telephone=v;}
    public String getMobile(){return mobile;} public void setMobile(String v){mobile=v;}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getQq(){return qq;} public void setQq(String v){qq=v;}
    public String getWechat(){return wechat;} public void setWechat(String v){wechat=v;}
    public String getOfficeAddress(){return officeAddress;} public void setOfficeAddress(String v){officeAddress=v;}
    public String getEmergencyContact(){return emergencyContact;} public void setEmergencyContact(String v){emergencyContact=v;}
    public String getEmergencyPhone(){return emergencyPhone;} public void setEmergencyPhone(String v){emergencyPhone=v;}
}
