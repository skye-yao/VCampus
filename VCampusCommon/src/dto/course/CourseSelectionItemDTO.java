package dto.course;

/** 教务模块的 CourseSelectionItemDTO 数据传输对象。 */
public final class CourseSelectionItemDTO {
    private final CourseDTO course;
    private final CourseOfferingDTO offering;

    public CourseSelectionItemDTO(CourseDTO course, CourseOfferingDTO offering) {
        this.course = course;
        this.offering = offering;
    }

    /** 获取 Course。 */
    public CourseDTO getCourse() {
        return course;
    }

    /** 获取 Offering。 */
    public CourseOfferingDTO getOffering() {
        return offering;
    }
}
