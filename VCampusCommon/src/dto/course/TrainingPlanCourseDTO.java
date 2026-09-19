package dto.course;

/** 教务模块的 TrainingPlanCourseDTO 数据传输对象。 */
public final class TrainingPlanCourseDTO {
    private final String courseCode;
    private final String courseName;
    private final double credit;
    private final String completionStatus;

    public TrainingPlanCourseDTO(String courseCode, String courseName,
            double credit, String completionStatus) {
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.credit = credit;
        this.completionStatus = completionStatus;
    }

    /** 获取 CourseCode。 */
    public String getCourseCode() {
        return courseCode;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 Credit。 */
    public double getCredit() {
        return credit;
    }

    /** 获取 CompletionStatus。 */
    public String getCompletionStatus() {
        return completionStatus;
    }
}
