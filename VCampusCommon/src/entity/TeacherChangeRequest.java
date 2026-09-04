package entity;
import enums.StudentChangeStatus;
import java.io.Serializable; import java.sql.Timestamp; import java.util.*;
public class TeacherChangeRequest implements Serializable {
 private static final long serialVersionUID=1L; private Long requestId; private String teacherId,reviewerId,reviewRemark; private StudentChangeStatus status=StudentChangeStatus.PENDING; private Timestamp submitTime,reviewTime; private List<TeacherChangeItem> items=new ArrayList<>();
 public Long getRequestId(){return requestId;} public void setRequestId(Long v){requestId=v;} public String getTeacherId(){return teacherId;} public void setTeacherId(String v){teacherId=v;}
 public StudentChangeStatus getStatus(){return status;} public void setStatus(StudentChangeStatus v){status=v;} public Timestamp getSubmitTime(){return submitTime;} public void setSubmitTime(Timestamp v){submitTime=v;}
 public String getReviewerId(){return reviewerId;} public void setReviewerId(String v){reviewerId=v;} public Timestamp getReviewTime(){return reviewTime;} public void setReviewTime(Timestamp v){reviewTime=v;} public String getReviewRemark(){return reviewRemark;} public void setReviewRemark(String v){reviewRemark=v;}
 public List<TeacherChangeItem> getItems(){return items;} public void setItems(List<TeacherChangeItem> v){items=v==null?new ArrayList<>():v;}
}
