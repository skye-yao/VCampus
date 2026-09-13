package dto.course.admin.enrollment;

public final class AdminEnrollmentRequestDTO {
    private final String operationId;
    private final String offeringId;
    private final String studentUid;
    private final boolean force;
    private final String overrideReason;

    public AdminEnrollmentRequestDTO(String operationId, String offeringId,
            String studentUid, boolean force, String overrideReason) {
        this.operationId = operationId;
        this.offeringId = offeringId;
        this.studentUid = studentUid;
        this.force = force;
        this.overrideReason = overrideReason;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getStudentUid() {
        return studentUid;
    }

    public boolean isForce() {
        return force;
    }

    public String getOverrideReason() {
        return overrideReason;
    }
}
