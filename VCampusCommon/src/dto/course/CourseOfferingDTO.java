package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 CourseOfferingDTO 数据传输对象。 */
public final class CourseOfferingDTO {
    private final String offeringId;
    /** 教学班代码（如 {@code CS101-2026-2-A}），与教师端 {@code TeacherOfferingDTO} 同义同名。 */
    private final String offeringCode;
    private final String courseId;
    private final List<CourseTeacherDTO> teachers;
    private final List<CourseMeetingDTO> meetings;
    private final int enrolledCount;
    private final int capacity;
    private final SelectionStateDTO selectionState;
    private final String failureReason;
    private final String offeredAt;
    private final String expiresAt;

    public CourseOfferingDTO(String offeringId, String offeringCode, String courseId,
            List<CourseTeacherDTO> teachers, List<CourseMeetingDTO> meetings,
            int enrolledCount, int capacity, SelectionStateDTO selectionState,
            String failureReason, String offeredAt, String expiresAt) {
        this.offeringId = offeringId;
        this.offeringCode = offeringCode;
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

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 OfferingCode。 */
    public String getOfferingCode() {
        return offeringCode;
    }

    /** 获取 CourseId。 */
    public String getCourseId() {
        return courseId;
    }

    /** 获取 Teachers。 */
    public List<CourseTeacherDTO> getTeachers() {
        return Collections.unmodifiableList(teachers);
    }

    /** 获取 Meetings。 */
    public List<CourseMeetingDTO> getMeetings() {
        return Collections.unmodifiableList(meetings);
    }

    /** 获取 EnrolledCount。 */
    public int getEnrolledCount() {
        return enrolledCount;
    }

    /** 获取 Capacity。 */
    public int getCapacity() {
        return capacity;
    }

    /** 获取 SelectionState。 */
    public SelectionStateDTO getSelectionState() {
        return selectionState;
    }

    /** 获取 FailureReason。 */
    public String getFailureReason() {
        return failureReason;
    }

    /** 获取 OfferedAt。 */
    public String getOfferedAt() {
        return offeredAt;
    }

    /** 获取 ExpiresAt。 */
    public String getExpiresAt() {
        return expiresAt;
    }
}
