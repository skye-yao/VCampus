package dto.course.admin.enrollment;

/** 教务模块的 AdminEnrollmentRequestDTO 数据传输对象。 */
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

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 StudentUid。 */
    public String getStudentUid() {
        return studentUid;
    }

    /** 判断 Force 是否成立。 */
    public boolean isForce() {
        return force;
    }

    /** 获取 OverrideReason。 */
    public String getOverrideReason() {
        return overrideReason;
    }
}
