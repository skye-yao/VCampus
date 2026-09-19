package dto.course.admin.catalog;

/** 教务模块的 AdminCourseDTO 数据传输对象。 */
public final class AdminCourseDTO {
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

    public AdminCourseDTO(String courseId, String courseCode, String courseName,
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

    /** 获取 CourseId。 */
    public String getCourseId() {
        return courseId;
    }

    /** 获取 CourseCode。 */
    public String getCourseCode() {
        return courseCode;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 CourseType。 */
    public String getCourseType() {
        return courseType;
    }

    /** 获取 Credit。 */
    public double getCredit() {
        return credit;
    }

    /** 获取 CreditHours。 */
    public int getCreditHours() {
        return creditHours;
    }

    /** 获取 Description。 */
    public String getDescription() {
        return description;
    }

    /** 获取 Prerequisites。 */
    public String getPrerequisites() {
        return prerequisites;
    }

    /** 判断 AllowCrossMajor 是否成立。 */
    public boolean isAllowCrossMajor() {
        return allowCrossMajor;
    }

    /** 判断 FinalExam 是否成立。 */
    public boolean isFinalExam() {
        return finalExam;
    }

    /** 获取 Status。 */
    public String getStatus() {
        return status;
    }

    /** 获取 OfferingCount。 */
    public int getOfferingCount() {
        return offeringCount;
    }

    /** 获取 Version。 */
    public int getVersion() {
        return version;
    }
}
