package model.course;

import java.util.List;

public final class CoursePlanSnapshotView {
    private final CourseTermView term;
    private final List<CourseSelectionItemView> planItems;
    private final List<CourseSelectionItemView> waitlistItems;
    private final List<CourseSelectionItemView> enrolledItems;

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
