package model.course;

public final class CourseOfferingView {
    private final long offeringId;
    private final String courseCode;
    private final String courseName;
    private final String courseType;
    private final double credit;
    private final int creditHours;
    private final String teacher;
    private final String schedule;
    private final String location;
    private final String description;
    private final String prerequisites;
    private final int enrolledCount;
    private final int capacity;
    private final SelectionStatus selectionStatus;

    public CourseOfferingView(long offeringId, String courseCode, String courseName,
            String courseType, double credit, int creditHours, String teacher,
            String schedule, String location, String description,
            String prerequisites, int enrolledCount, int capacity,
            SelectionStatus selectionStatus) {
        this.offeringId = offeringId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.courseType = courseType;
        this.credit = credit;
        this.creditHours = creditHours;
        this.teacher = teacher;
        this.schedule = schedule;
        this.location = location;
        this.description = description;
        this.prerequisites = prerequisites;
        this.enrolledCount = enrolledCount;
        this.capacity = capacity;
        this.selectionStatus = selectionStatus;
    }

    public long getOfferingId() {
        return offeringId;
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

    public String getTeacher() {
        return teacher;
    }

    public String getSchedule() {
        return schedule;
    }

    public String getLocation() {
        return location;
    }

    public String getDescription() {
        return description;
    }

    public String getPrerequisites() {
        return prerequisites;
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    public int getCapacity() {
        return capacity;
    }

    public SelectionStatus getSelectionStatus() {
        return selectionStatus;
    }

    public CourseOfferingView withSelectionStatus(SelectionStatus selectionStatus) {
        return new CourseOfferingView(offeringId, courseCode, courseName, courseType,
                credit, creditHours, teacher, schedule, location, description,
                prerequisites, enrolledCount, capacity, selectionStatus);
    }
}
