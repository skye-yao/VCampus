package entity;
import java.io.Serializable;
import java.sql.Date;
public class TeacherWorkExperience implements Serializable {
 private static final long serialVersionUID=1L;private Long experienceId;private String teacherId,organization,department,position,description;private Date startDate,endDate;
 public Long getExperienceId(){return experienceId;}public void setExperienceId(Long v){experienceId=v;}public String getTeacherId(){return teacherId;}public void setTeacherId(String v){teacherId=v;}
 public String getOrganization(){return organization;}public void setOrganization(String v){organization=v;}public String getDepartment(){return department;}public void setDepartment(String v){department=v;}
 public String getPosition(){return position;}public void setPosition(String v){position=v;}public String getDescription(){return description;}public void setDescription(String v){description=v;}
 public Date getStartDate(){return startDate;}public void setStartDate(Date v){startDate=v;}public Date getEndDate(){return endDate;}public void setEndDate(Date v){endDate=v;}
}
