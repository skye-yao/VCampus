package dto.course.admin.catalog;

/** 教务模块的 OfferingEditorRequestDTO 数据传输对象。 */
public final class OfferingEditorRequestDTO {
    private final String operationId;
    private final String offeringId;
    private final int expectedVersion;
    private final String courseId;
    private final String offeringCode;
    private final int academicYear;
    private final int semester;
    private final int capacity;
    private final String teacherUid;
    private final String assistantUid;
    private final int status;

    public OfferingEditorRequestDTO(String operationId, String offeringId,
            int expectedVersion, String courseId, String offeringCode,
            int academicYear, int semester, int capacity, String teacherUid,
            String assistantUid, int status) {
        this.operationId = operationId;
        this.offeringId = offeringId;
        this.expectedVersion = expectedVersion;
        this.courseId = courseId;
        this.offeringCode = offeringCode;
        this.academicYear = academicYear;
        this.semester = semester;
        this.capacity = capacity;
        this.teacherUid = teacherUid;
        this.assistantUid = assistantUid;
        this.status = status;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 ExpectedVersion。 */
    public int getExpectedVersion() {
        return expectedVersion;
    }

    /** 获取 CourseId。 */
    public String getCourseId() {
        return courseId;
    }

    /** 获取 OfferingCode。 */
    public String getOfferingCode() {
        return offeringCode;
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

    /** 获取 TeacherUid。 */
    public String getTeacherUid() {
        return teacherUid;
    }

    /** 获取 AssistantUid。 */
    public String getAssistantUid() {
        return assistantUid;
    }

    /** 获取 Status。 */
    public int getStatus() {
        return status;
    }
}
