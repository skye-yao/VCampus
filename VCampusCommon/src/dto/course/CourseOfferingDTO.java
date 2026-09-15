package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CourseOfferingDTO {
    private final String offeringId;
    private final String courseId;
    private final List<CourseTeacherDTO> teachers;
    private final List<CourseMeetingDTO> meetings;
    private final int enrolledCount;
    private final int capacity;
    private final SelectionStateDTO selectionState;
    private final String failureReason;
    private final String offeredAt;
    private final String expiresAt;

    public CourseOfferingDTO(String offeringId, String courseId,
            List<CourseTeacherDTO> teachers, List<CourseMeetingDTO> meetings,
            int enrolledCount, int capacity, SelectionStateDTO selectionState,
            String failureReason, String offeredAt, String expiresAt) {
        this.offeringId = offeringId;
        this.courseId = courseId;
        this.teachers = Collections.unmodifiableList(new ArrayList<>(teachers));
        this.meetings = Collections.unmodifiableList(new ArrayList<>(meetings));
        this.enrolledCount = enrolledCount;
        this.capacity = capacity;
        this.selectionState = selectionState;
        this.failureReason = failureReason;
        this.offeredAt = offeredAt;
        this.expiresAt = expiresAt;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getCourseId() {
        return courseId;
    }

    public List<CourseTeacherDTO> getTeachers() {
        return Collections.unmodifiableList(teachers);
    }

    public List<CourseMeetingDTO> getMeetings() {
        return Collections.unmodifiableList(meetings);
    }

    public int getEnrolledCount() {
        return enrolledCount;
    }

    public int getCapacity() {
        return capacity;
    }

    public SelectionStateDTO getSelectionState() {
        return selectionState;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getOfferedAt() {
        return offeredAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }
}
