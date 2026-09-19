package dto.course.admin.catalog;

/** 教务模块的 CourseEditorRequestDTO 数据传输对象。 */
public final class CourseEditorRequestDTO {
    private final String operationId;
    private final String courseId;
    private final int expectedVersion;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String description;
    private final String prerequisites;
    private final boolean allowCrossMajor;
    private final boolean finalExam;

    public CourseEditorRequestDTO(String operationId, String courseId, int expectedVersion,
            String courseCode, String courseName, String courseType, double credit,
            int creditHours, String description, String prerequisites,
            boolean allowCrossMajor, boolean finalExam) {
        this.operationId = operationId;
        this.courseId = courseId;
        this.expectedVersion = expectedVersion;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseType = courseType;
        this.credit = credit;
        this.creditHours = creditHours;
        this.description = description;
        this.prerequisites = prerequisites;
        this.allowCrossMajor = allowCrossMajor;
        this.finalExam = finalExam;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 CourseId。 */
    public String getCourseId() {
        return courseId;
    }

    /** 获取 ExpectedVersion。 */
    public int getExpectedVersion() {
        return expectedVersion;
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
}
