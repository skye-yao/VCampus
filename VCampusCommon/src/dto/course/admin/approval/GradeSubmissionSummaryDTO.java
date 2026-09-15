package dto.course.admin.approval;

public final class GradeSubmissionSummaryDTO {
    private final String submissionId;
    private final String offeringId;
    private final String courseName;
    private final String offeringCode;
    private final int version;
    private final String teacherUid;
    private final String teacherName;
    private final int studentCount;
    private final double average;
    private final double highest;
    private final double lowest;
    private final int failCount;
    private final ApprovalStatusDTO status;
    private final String submittedAt;

    public GradeSubmissionSummaryDTO(String submissionId, String offeringId,
            String courseName, String offeringCode, int version,
            String teacherUid, String teacherName, int studentCount,
            double average, double highest, double lowest, int failCount,
            ApprovalStatusDTO status, String submittedAt) {
        this.submissionId = submissionId;
        this.offeringId = offeringId;
        this.courseName = courseName;
        this.offeringCode = offeringCode;
        this.version = version;
        this.teacherUid = teacherUid;
        this.teacherName = teacherName;
        this.studentCount = studentCount;
        this.average = average;
        this.highest = highest;
        this.lowest = lowest;
        this.failCount = failCount;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    public String getSubmissionId() {
        return submissionId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getOfferingCode() {
        return offeringCode;
    }

    public int getVersion() {
        return version;
    }

    public String getTeacherUid() {
        return teacherUid;
    }

    public String getTeacherName() {
        return teacherName;
    }

    public int getStudentCount() {
        return studentCount;
    }

    public double getAverage() {
        return average;
    }

    public double getHighest() {
        return highest;
    }

    public double getLowest() {
        return lowest;
    }

    public int getFailCount() {
        return failCount;
    }

    public ApprovalStatusDTO getStatus() {
        return status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }
}
