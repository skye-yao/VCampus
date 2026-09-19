package model.course;

/** 培养方案中一门课程及其修读完成状态的视图。 */
public final class TrainingPlanCourseView {
    private final String courseCode;
    private final String courseName;
    private final double credit;
    private final String completionStatus;

    /** 创建培养方案课程视图。 */
    public TrainingPlanCourseView(String courseCode, String courseName,
            double credit, String completionStatus) {
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.credit = credit;
        this.completionStatus = completionStatus;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public double getCredit() {
        return credit;
    }

    public String getCompletionStatus() {
        return completionStatus;
    }
}
