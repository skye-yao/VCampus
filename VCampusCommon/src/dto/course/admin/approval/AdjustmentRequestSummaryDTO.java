package dto.course.admin.approval;

import dto.course.AdjustmentRequestStatusDTO;

/**
 * 调课申请列表行。教师端“我的申请”和管理员审批列表共用同一个视图对象。
 *
 * <p>状态是调课专用的四态 {@link AdjustmentRequestStatusDTO}，教师撤销的 WITHDRAWN 必须能在列表上
 * 显示，不能被解析成管理员驳回。
 */
public final class AdjustmentRequestSummaryDTO {
    private final String requestId;
    private final String courseName;
    private final String offeringCode;
    private final String applicantUid;
    private final String applicantName;
    private final int targetWeekCount;
    private final AdjustmentRequestStatusDTO status;
    private final String submittedAt;

    public AdjustmentRequestSummaryDTO(String requestId, String courseName,
            String offeringCode, String applicantUid, String applicantName,
            int targetWeekCount, AdjustmentRequestStatusDTO status, String submittedAt) {
        this.requestId = requestId;
        this.courseName = courseName;
        this.offeringCode = offeringCode;
        this.applicantUid = applicantUid;
        this.applicantName = applicantName;
        this.targetWeekCount = targetWeekCount;
        this.status = status;
        this.submittedAt = submittedAt;
    }

    /**
     * 兼容重载：迁移前的旧调用点只认识三态枚举，按枚举名映射到四态。
     * 成绩审批枚举不增加 WITHDRAWN，映射只覆盖两者共有的三个状态。
     */
    public AdjustmentRequestSummaryDTO(String requestId, String courseName,
            String offeringCode, String applicantUid, String applicantName,
            int targetWeekCount, ApprovalStatusDTO status, String submittedAt) {
        this(requestId, courseName, offeringCode, applicantUid, applicantName, targetWeekCount,
                status == null ? null : AdjustmentRequestStatusDTO.valueOf(status.name()),
                submittedAt);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getOfferingCode() {
        return offeringCode;
    }

    public String getApplicantUid() {
        return applicantUid;
    }

    public String getApplicantName() {
        return applicantName;
    }

    public int getTargetWeekCount() {
        return targetWeekCount;
    }

    public AdjustmentRequestStatusDTO getStatus() {
        return status;
    }

    public String getSubmittedAt() {
        return submittedAt;
    }
}
