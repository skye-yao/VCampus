package dto.course.admin.approval;

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

    public String getOperationId() {
        return operationId;
    }

    public String getRequestId() {
        return requestId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public boolean isApproved() {
        return approved;
    }

    public boolean isForce() {
        return force;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public String getReviewComment() {
        return reviewComment;
    }
}
