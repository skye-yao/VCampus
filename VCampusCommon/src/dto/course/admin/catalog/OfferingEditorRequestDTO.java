package dto.course.admin.catalog;

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

    public String getOperationId() {
        return operationId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public String getCourseId() {
        return courseId;
    }

    public String getOfferingCode() {
        return offeringCode;
    }

    public int getAcademicYear() {
        return academicYear;
    }

    public int getSemester() {
        return semester;
    }

    public int getCapacity() {
        return capacity;
    }

    public String getTeacherUid() {
        return teacherUid;
    }

    public String getAssistantUid() {
        return assistantUid;
    }

    public int getStatus() {
        return status;
    }
}
