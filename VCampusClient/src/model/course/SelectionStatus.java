package model.course;

/** 学生对一个教学班的可选、计划、候补或已选状态。 */
public enum SelectionStatus {
    AVAILABLE, PLANNED, FULL, WAITLISTED, WAITLIST_OFFERED, ENROLLED
}
