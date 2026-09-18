package model.course;

import java.time.Instant;
import java.util.List;

public final class CourseOfferingView {
    private final long offeringId;
    /** 教学班代码（如 {@code CS101-2026-2-A}）：全部页签的教学班行标题显示的就是它。 */
    private final String offeringCode;
    private final long courseId;
    private final List<CourseTeacherView> teachers;
    private final List<CourseMeetingView> meetings;
    private final int enrolledCount;
    private final int capacity;
    private final SelectionStatus selectionStatus;
    private final String failureReason;
    private final Instant offeredAt;
    private final Instant expiresAt;

    public CourseOfferingView(long offeringId, String offeringCode, long courseId,
            List<CourseTeacherView> teachers, List<CourseMeetingView> meetings,
            int enrolledCount, int capacity, SelectionStatus selectionStatus,
            String failureReason, String offeredAtUtc, String expiresAtUtc) {
        this.offeringId = offeringId;
        this.offeringCode = offeringCode;
        this.courseId = courseId;
        this.teachers = List.copyOf(teachers);
        this.meetings = List.copyOf(meetings);
        this.enrolledCount = enrolledCount;
        this.capacity = capacity;
        this.selectionStatus = selectionStatus;
        this.failureReason = failureReason;
        this.offeredAt = parseOptional(offeredAtUtc);
        this.expiresAt = parseOptional(expiresAtUtc);
    }

    public long getOfferingId() { return offeringId; }
    public String getOfferingCode() { return offeringCode; }
    public long getCourseId() { return courseId; }
    public List<CourseTeacherView> getTeachers() { return teachers; }
    public List<CourseMeetingView> getMeetings() { return meetings; }
    public int getEnrolledCount() { return enrolledCount; }
    public int getCapacity() { return capacity; }
    public SelectionStatus getSelectionStatus() { return selectionStatus; }
    public String getFailureReason() { return failureReason; }
    public Instant getOfferedAt() { return offeredAt; }
    public Instant getExpiresAt() { return expiresAt; }

    private static Instant parseOptional(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
