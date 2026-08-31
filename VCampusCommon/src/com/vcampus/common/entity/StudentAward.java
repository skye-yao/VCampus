package com.vcampus.common.entity;

import java.io.Serializable;
import java.sql.Date;
import com.vcampus.common.enums.StudentAwardType;

public class StudentAward implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long awardId;
    private String studentId;
    private String awardName;
    private StudentAwardType awardType;
    private String awardLevel;
    private Date awardDate;
    private String organization;
    private String description;
    public Long getAwardId() { return awardId; }
    public void setAwardId(Long v) { awardId = v; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String v) { studentId = v; }
    public String getAwardName() { return awardName; }
    public void setAwardName(String v) { awardName = v; }
    public StudentAwardType getAwardType() { return awardType; }
    public void setAwardType(StudentAwardType v) { awardType = v; }
    public String getAwardLevel() { return awardLevel; }
    public void setAwardLevel(String v) { awardLevel = v; }
    public Date getAwardDate() { return awardDate; }
    public void setAwardDate(Date v) { awardDate = v; }
    public String getOrganization() { return organization; }
    public void setOrganization(String v) { organization = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
}
