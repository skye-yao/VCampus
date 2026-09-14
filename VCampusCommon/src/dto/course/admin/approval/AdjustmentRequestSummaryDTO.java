package dto.course.admin.approval;

public final class AdjustmentRequestSummaryDTO {
    private final String requestId;
    private final String courseName;
    private final String offeringCode;
    private final String applicantUid;
    private final String applicantName;
    private final int targetWeekCount;
    private final ApprovalStatusDTO status;
    private final String submittedAt;

    public AdjustmentRequestSummaryDTO(String requestId, String courseName,
            String offeringCode, String applicantUid, String applicantName,
            int targetWeekCount, ApprovalStatusDTO status, String submittedAt) {
        this.requestId = requestId;
        this.courseName = courseName;
        this.offeringCode = offeringCode;
        this.applicantUid = applicantUid;
        this.applicantName = applicantName;
        this.targetWeekCount = targetWeekCount;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getOfferingCode() {
        return offeringCode;
    }

    public String getApplicantUid() {
        return applicantUid;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public int getTargetWeekCount() {
        return targetWeekCount;
    }

    public ApprovalStatusDTO getStatus() {
        return status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }
}
