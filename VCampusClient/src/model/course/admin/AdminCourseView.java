package model.course.admin;

/** 管理端维护课程目录时使用的课程详情视图。 */
public final class AdminCourseView {
    private final String courseId;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String description;
    private final String prerequisites;
    private final boolean allowCrossMajor;
    private final boolean finalExam;
    private final String status;
    private final int offeringCount;
    private final int version;

    /** 创建管理端课程视图。 */
    public AdminCourseView(String courseId, String courseCode, String courseName,
            String courseType, double credit, int creditHours, String description,
            String prerequisites, boolean allowCrossMajor, boolean finalExam,
            String status, int offeringCount, int version) {
        this.courseId = courseId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseType = courseType;
        this.credit = credit;
        this.creditHours = creditHours;
        this.description = description;
        this.prerequisites = prerequisites;
        this.allowCrossMajor = allowCrossMajor;
        this.finalExam = finalExam;
        this.status = status;
        this.offeringCount = offeringCount;
        this.version = version;
    }

    public String getCourseId() { return courseId; }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getCourseType() { return courseType; }
    public double getCredit() { return credit; }
    public int getCreditHours() { return creditHours; }
    public String getDescription() { return description; }
    public String getPrerequisites() { return prerequisites; }
    public boolean isAllowCrossMajor() { return allowCrossMajor; }
    public boolean isFinalExam() { return finalExam; }
    public String getStatus() { return status; }
    public int getOfferingCount() { return offeringCount; }
    public int getVersion() { return version; }
}
