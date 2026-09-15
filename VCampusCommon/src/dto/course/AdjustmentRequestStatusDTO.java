package dto.course;

/**
 * 调课申请的状态，独立于成绩审批使用的
 * {@link dto.course.admin.approval.ApprovalStatusDTO}。
 *
 * <p>教师撤销是申请人的终态，不是管理员的审批结果：{@code WITHDRAWN} 只出现在调课申请上，
 * 绝不能混入成绩提交的枚举，否则成绩列表会承认一个业务上不存在的状态。
 */
public enum AdjustmentRequestStatusDTO {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
