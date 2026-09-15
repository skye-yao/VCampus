package model.course;

public final class CourseSelectionItemView {
    private final CourseView course;
    private final CourseOfferingView offering;

    public CourseSelectionItemView(CourseView course, CourseOfferingView offering) {
        this.course = course;
        this.offering = offering;
    }

    public CourseView getCourse() { return course; }
    public CourseOfferingView getOffering() { return offering; }
    public SelectionStatus getStatus() { return offering.getSelectionStatus(); }
}
