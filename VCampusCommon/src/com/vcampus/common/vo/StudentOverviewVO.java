package com.vcampus.common.vo;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import com.vcampus.common.entity.Student;
import com.vcampus.common.entity.StudentAid;
import com.vcampus.common.entity.StudentAward;
import com.vcampus.common.entity.StudentChangeRequest;

public class StudentOverviewVO implements Serializable {
    private static final long serialVersionUID = 1L;
    private Student student;
    /** 成绩模块尚未定型，先保留可序列化扩展点。 */
    private List<Object> grades = new ArrayList<>();
    private List<StudentAward> awards = new ArrayList<>();
    private List<StudentAid> aids = new ArrayList<>();
    private StudentChangeRequest pendingRequest;
    public Student getStudent() { return student; }
    public void setStudent(Student v) { student = v; }
    public List<Object> getGrades() { return grades; }
    public void setGrades(List<Object> v) { grades = v == null ? new ArrayList<>() : v; }
    public List<StudentAward> getAwards() { return awards; }
    public void setAwards(List<StudentAward> v) { awards = v == null ? new ArrayList<>() : v; }
    public List<StudentAid> getAids() { return aids; }
    public void setAids(List<StudentAid> v) { aids = v == null ? new ArrayList<>() : v; }
    public StudentChangeRequest getPendingRequest() { return pendingRequest; }
    public void setPendingRequest(StudentChangeRequest v) { pendingRequest = v; }
}
