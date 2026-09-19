package dto.course.admin.approval;

/** 教务模块的 GradeSubmissionSummaryDTO 数据传输对象。 */
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

    /** 获取 SubmissionId。 */
    public String getSubmissionId() {
        return submissionId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 OfferingCode。 */
    public String getOfferingCode() {
        return offeringCode;
    }

    /** 获取 Version。 */
    public int getVersion() {
        return version;
    }

    /** 获取 TeacherUid。 */
    public String getTeacherUid() {
        return teacherUid;
    }

    /** 获取 TeacherName。 */
    public String getTeacherName() {
        return teacherName;
    }

    /** 获取 StudentCount。 */
    public int getStudentCount() {
        return studentCount;
    }

    /** 获取 Average。 */
    public double getAverage() {
        return average;
    }

    /** 获取 Highest。 */
    public double getHighest() {
        return highest;
    }

    /** 获取 Lowest。 */
    public double getLowest() {
        return lowest;
    }

    /** 获取 FailCount。 */
    public int getFailCount() {
        return failCount;
    }

    /** 获取 Status。 */
    public ApprovalStatusDTO getStatus() {
        return status;
    }

    /** 获取 SubmittedAt。 */
    public String getSubmittedAt() {
        return submittedAt;
    }
}
