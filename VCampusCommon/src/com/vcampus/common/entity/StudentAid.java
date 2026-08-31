package com.vcampus.common.entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Date;
import com.vcampus.common.enums.StudentAidStatus;

public class StudentAid implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long aidId;
    private String studentId;
    private String aidName;
    private String aidType;
    private BigDecimal amount;
    private Date aidDate;
    private String provider;
    private StudentAidStatus status = StudentAidStatus.PENDING;
    private String description;
    public Long getAidId() { return aidId; }
    public void setAidId(Long v) { aidId = v; }
    public String getStudentId() { return studentId; }
    public void setStudentId(String v) { studentId = v; }
    public String getAidName() { return aidName; }
    public void setAidName(String v) { aidName = v; }
    public String getAidType() { return aidType; }
    public void setAidType(String v) { aidType = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public Date getAidDate() { return aidDate; }
    public void setAidDate(Date v) { aidDate = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public StudentAidStatus getStatus() { return status; }
    public void setStatus(StudentAidStatus v) { status = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
}
