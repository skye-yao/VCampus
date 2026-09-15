package model.course;

public final class TrainingPlanCourseView {
    private final String courseCode;
    private final String courseName;
    private final double credit;
    private final String completionStatus;

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
