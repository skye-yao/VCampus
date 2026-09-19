package dto.course.admin.catalog;

/** 教务模块的 AdminOfferingDTO 数据传输对象。 */
public final class AdminOfferingDTO {
    private final String offeringId;
    private final String offeringCode;
    private final String courseId;
    private final int academicYear;
    private final int semester;
    private final int capacity;
    private final int enrolledCount;
    private final String status;
    private final String teacherUid;
    private final String teacherName;
    private final String assistantUid;
    private final String assistantName;
    private final String scheduleStatus;
    private final int version;

    public AdminOfferingDTO(String offeringId, String offeringCode, String courseId,
            int academicYear, int semester, int capacity, int enrolledCount,
            String status, String teacherUid, String teacherName,
            String assistantUid, String assistantName, String scheduleStatus,
            int version) {
        this.offeringId = offeringId;
        this.offeringCode = offeringCode;
        this.courseId = courseId;
        this.academicYear = academicYear;
        this.semester = semester;
        this.capacity = capacity;
        this.enrolledCount = enrolledCount;
        this.status = status;
        this.teacherUid = teacherUid;
        this.teacherName = teacherName;
        this.assistantUid = assistantUid;
        this.assistantName = assistantName;
        this.scheduleStatus = scheduleStatus;
        this.version = version;
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

    /** 获取 AcademicYear。 */
    public int getAcademicYear() {
        return academicYear;
    }

    /** 获取 Semester。 */
    public int getSemester() {
        return semester;
    }

    /** 获取 Capacity。 */
    public int getCapacity() {
        return capacity;
    }

    /** 获取 EnrolledCount。 */
    public int getEnrolledCount() {
        return enrolledCount;
    }

    /** 获取 Status。 */
    public String getStatus() {
        return status;
    }

    /** 获取 TeacherUid。 */
    public String getTeacherUid() {
        return teacherUid;
    }

    /** 获取 TeacherName。 */
    public String getTeacherName() {
        return teacherName;
    }

    /** 获取 AssistantUid。 */
    public String getAssistantUid() {
        return assistantUid;
    }

    /** 获取 AssistantName。 */
    public String getAssistantName() {
        return assistantName;
    }

    /** 获取 ScheduleStatus。 */
    public String getScheduleStatus() {
        return scheduleStatus;
    }

    /** 获取 Version。 */
    public int getVersion() {
        return version;
    }
}
