package dto.course.admin.approval;

/** 教务模块的 ApprovalDecisionRequestDTO 数据传输对象。 */
public final class ApprovalDecisionRequestDTO {
    private final String operationId;
    private final String requestId;
    private final int expectedVersion;
    private final boolean approved;
    private final boolean force;
    private final String overrideReason;
    private final String reviewComment;

    public ApprovalDecisionRequestDTO(String operationId, String requestId,
            int expectedVersion, boolean approved, boolean force,
            String overrideReason, String reviewComment) {
        this.operationId = operationId;
        this.requestId = requestId;
        this.expectedVersion = expectedVersion;
        this.approved = approved;
        this.force = force;
        this.overrideReason = overrideReason;
        this.reviewComment = reviewComment;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 RequestId。 */
    public String getRequestId() {
        return requestId;
    }

    /** 获取 ExpectedVersion。 */
    public int getExpectedVersion() {
        return expectedVersion;
    }

    /** 判断 Approved 是否成立。 */
    public boolean isApproved() {
        return approved;
    }

    /** 判断 Force 是否成立。 */
    public boolean isForce() {
        return force;
    }

    /** 获取 OverrideReason。 */
    public String getOverrideReason() {
        return overrideReason;
    }

    /** 获取 ReviewComment。 */
    public String getReviewComment() {
        return reviewComment;
    }
}
