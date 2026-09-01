package vo;
import java.io.Serializable;
import enums.StudentChangeStatus;
public class StudentReviewVO implements Serializable {
    private static final long serialVersionUID=1L;
    private long requestId;
    private StudentChangeStatus reviewResult;
    private String reviewRemark;
    public long getRequestId() {
        return requestId;
    }
    public void setRequestId(long v) {
        requestId=v;
    }
    public StudentChangeStatus getReviewResult() {
        return reviewResult;
    }
    public void setReviewResult(StudentChangeStatus v) {
        reviewResult=v;
    }
    public String getReviewRemark() {
        return reviewRemark;
    }
    public void setReviewRemark(String v) {
        reviewRemark=v;
    }
}
