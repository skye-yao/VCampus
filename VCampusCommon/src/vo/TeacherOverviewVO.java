package vo;
import entity.*; import java.io.Serializable;
public class TeacherOverviewVO implements Serializable {
    private TeacherChangeRequest latestRequest;
    public TeacherChangeRequest getLatestRequest(){return latestRequest;}
    public void setLatestRequest(TeacherChangeRequest value){latestRequest=value;}

 private static final long serialVersionUID=1L; private Teacher teacher; private TeacherChangeRequest pendingRequest;private java.util.List<TeacherWorkExperience> workExperiences=new java.util.ArrayList<>();
 public Teacher getTeacher(){return teacher;} public void setTeacher(Teacher v){teacher=v;} public TeacherChangeRequest getPendingRequest(){return pendingRequest;} public void setPendingRequest(TeacherChangeRequest v){pendingRequest=v;}
 public String getTeacherId(){return teacher==null?null:teacher.getTeacherId();} public String getName(){return teacher==null?null:teacher.getName();}
 public String getCampus(){return teacher==null?null:teacher.getCampus();} public String getCollege(){return teacher==null?null:teacher.getCollege();} public String getDepartment(){return teacher==null?null:teacher.getDepartment();}
 public String getTitle(){return teacher==null?null:teacher.getTitle();} public String getPosition(){return teacher==null?null:teacher.getPosition();} public String getEmploymentStatus(){return teacher==null?null:teacher.getEmploymentStatus();}
 public java.util.List<TeacherWorkExperience> getWorkExperiences(){return workExperiences;}public void setWorkExperiences(java.util.List<TeacherWorkExperience> v){workExperiences=v;}
}
