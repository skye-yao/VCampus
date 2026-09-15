package model.course.admin;

public final class AdminOfferingView {
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

    public AdminOfferingView(String offeringId, String offeringCode, String courseId,
            int academicYear, int semester, int capacity, int enrolledCount, String status,
            String teacherUid, String teacherName, String assistantUid, String assistantName,
            String scheduleStatus, int version) {
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

    public String getOfferingId() { return offeringId; }
    public String getOfferingCode() { return offeringCode; }
    public String getCourseId() { return courseId; }
    public int getAcademicYear() { return academicYear; }
    public int getSemester() { return semester; }
    public int getCapacity() { return capacity; }
    public int getEnrolledCount() { return enrolledCount; }
    public String getStatus() { return status; }
    public String getTeacherUid() { return teacherUid; }
    public String getTeacherName() { return teacherName; }
    public String getAssistantUid() { return assistantUid; }
    public String getAssistantName() { return assistantName; }
    public String getScheduleStatus() { return scheduleStatus; }
    public int getVersion() { return version; }
}
