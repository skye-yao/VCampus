package model.course;

/** 将课程基本信息与一个教学班组合为可选课的界面条目。 */
public final class CourseSelectionItemView {
    private final CourseView course;
    private final CourseOfferingView offering;

    /** 创建课程与教学班的组合条目。 */
    public CourseSelectionItemView(CourseView course, CourseOfferingView offering) {
        this.course = course;
        this.offering = offering;
    }

    public CourseView getCourse() { return course; }
    public CourseOfferingView getOffering() { return offering; }
    public SelectionStatus getStatus() { return offering.getSelectionStatus(); }
}
