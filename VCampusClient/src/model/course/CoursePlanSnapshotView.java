package model.course;

import java.util.List;

/** 某学期学生计划、候补与已选教学班的不可变快照。 */
public final class CoursePlanSnapshotView {
    private final CourseTermView term;
    private final List<CourseSelectionItemView> planItems;
    private final List<CourseSelectionItemView> waitlistItems;
    private final List<CourseSelectionItemView> enrolledItems;

    /** 创建课程计划快照，并不可变复制三个分类列表。 */
    public CoursePlanSnapshotView(CourseTermView term,
            List<CourseSelectionItemView> planItems,
            List<CourseSelectionItemView> waitlistItems,
            List<CourseSelectionItemView> enrolledItems) {
        this.term = term;
        this.planItems = List.copyOf(planItems);
        this.waitlistItems = List.copyOf(waitlistItems);
        this.enrolledItems = List.copyOf(enrolledItems);
    }

    public CourseTermView getTerm() { return term; }
    public List<CourseSelectionItemView> getPlanItems() { return planItems; }
    public List<CourseSelectionItemView> getWaitlistItems() { return waitlistItems; }
    public List<CourseSelectionItemView> getEnrolledItems() { return enrolledItems; }

    /** 在计划、候补和已选列表中查找指定教学班。 */
    public CourseSelectionItemView find(long offeringId) {
        for (CourseSelectionItemView item : planItems) {
            if (item.getOffering().getOfferingId() == offeringId) return item;
        }
        for (CourseSelectionItemView item : waitlistItems) {
            if (item.getOffering().getOfferingId() == offeringId) return item;
        }
        for (CourseSelectionItemView item : enrolledItems) {
            if (item.getOffering().getOfferingId() == offeringId) return item;
        }
        return null;
    }
}
