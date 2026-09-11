package dto.course;

public final class CourseSelectionItemDTO {
    private final CourseDTO course;
    private final CourseOfferingDTO offering;

    public CourseSelectionItemDTO(CourseDTO course, CourseOfferingDTO offering) {
        this.course = course;
        this.offering = offering;
    }

    public CourseDTO getCourse() {
        return course;
    }

    public CourseOfferingDTO getOffering() {
        return offering;
    }
}
