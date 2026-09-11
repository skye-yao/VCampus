package model.course;

public final class CourseView {
    private final long courseId;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String description;
    private final String prerequisites;

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

    public long getCourseId() { return courseId; }
    public String getCourseCode() { return courseCode; }
    public String getCourseName() { return courseName; }
    public String getCourseType() { return courseType; }
    public double getCredit() { return credit; }
    public int getCreditHours() { return creditHours; }
    public String getDescription() { return description; }
    public String getPrerequisites() { return prerequisites; }
}
