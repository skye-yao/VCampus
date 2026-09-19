package dto.course;

/** 教务模块的课程数据传输对象。 */
public final class CourseDTO {
    private final String courseId;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String description;
    private final String prerequisites;

    public CourseDTO(String courseId, String courseCode, String courseName, String courseType,
            double credit, int creditHours, String description, String prerequisites) {
        this.courseId = courseId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseType = courseType;
        this.credit = credit;
        this.creditHours = creditHours;
        this.description = description;
        this.prerequisites = prerequisites;
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
}
