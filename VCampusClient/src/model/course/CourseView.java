package model.course;

/**
 * 教务模块的课程基础资料视图，作为服务层 DTO 与 JavaFX 课程页面之间的只读数据边界。
 */
public final class CourseView {
    private final long courseId;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String description;
    private final String prerequisites;

    /** 创建课程基础资料视图。 */
    public CourseView(long courseId, String courseCode, String courseName,
            String courseType, double credit, int creditHours,
            String description, String prerequisites) {
        this.courseId = courseId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseType = courseType;
        this.credit = credit;
        this.creditHours = creditHours;
        this.description = description;
        this.prerequisites = prerequisites;
    }

    /** 返回课程唯一标识。 */
    public long getCourseId() { return courseId; }
    /** 返回课程代码。 */
    public String getCourseCode() { return courseCode; }
    /** 返回课程名称。 */
    public String getCourseName() { return courseName; }
    /** 返回课程类别。 */
    public String getCourseType() { return courseType; }
    /** 返回课程学分。 */
    public double getCredit() { return credit; }
    /** 返回课程学时。 */
    public int getCreditHours() { return creditHours; }
    /** 返回课程简介。 */
    public String getDescription() { return description; }
    /** 返回先修要求文本。 */
    public String getPrerequisites() { return prerequisites; }
}
