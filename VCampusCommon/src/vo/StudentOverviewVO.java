package vo;
import java.io.Serializable;
import java.util.*;
import entity.*;
public class StudentOverviewVO implements Serializable {
    private StudentChangeRequest latestRequest;
    public StudentChangeRequest getLatestRequest(){return latestRequest;}
    public void setLatestRequest(StudentChangeRequest value){latestRequest=value;}

    private static final long serialVersionUID=1L;
    private Student student;
    private List<Object> grades=new ArrayList<>();
    private List<StudentAward> awards=new ArrayList<>();
    private List<StudentAid> aids=new ArrayList<>();
    private List<StudentExperience> experiences=new ArrayList<>();
    private List<StudentFamilyMember> familyMembers=new ArrayList<>();
    private StudentChangeRequest pendingRequest;
    public Student getStudent() {
        return student;
    }
    public void setStudent(Student v) {
        student=v;
    }
    public List<Object> getGrades() {
        return grades;
    }
    public void setGrades(List<Object> v) {
        grades=v==null?new ArrayList<>():v;
    }
    public List<StudentAward> getAwards() {
        return awards;
    }
    public void setAwards(List<StudentAward> v) {
        awards=v==null?new ArrayList<>():v;
    }
    public List<StudentAid> getAids() {
        return aids;
    }
    public void setAids(List<StudentAid> v) {
        aids=v==null?new ArrayList<>():v;
    }
    public List<StudentExperience> getExperiences(){return experiences;}
    public void setExperiences(List<StudentExperience> v){experiences=v==null?new ArrayList<>():v;}
    public List<StudentFamilyMember> getFamilyMembers(){return familyMembers;}
    public void setFamilyMembers(List<StudentFamilyMember> v){familyMembers=v==null?new ArrayList<>():v;}
    public StudentChangeRequest getPendingRequest() {
        return pendingRequest;
    }
    public void setPendingRequest(StudentChangeRequest v) {
        pendingRequest=v;
    }
}
