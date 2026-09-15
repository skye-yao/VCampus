package dto.course.admin.catalog;

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

    public String getOperationId() {
        return operationId;
    }

    public String getCourseId() {
        return courseId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getCourseType() {
        return courseType;
    }

    public double getCredit() {
        return credit;
    }

    public int getCreditHours() {
        return creditHours;
    }

    public String getDescription() {
        return description;
    }

    public String getPrerequisites() {
        return prerequisites;
    }

    public boolean isAllowCrossMajor() {
        return allowCrossMajor;
    }

    public boolean isFinalExam() {
        return finalExam;
    }
}
