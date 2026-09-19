package dto.course.teacher;

/**
 * 教师撤销本人调课申请（courseTeacher 模块写请求体，位于 {@code data.request}）。
 *
 * <p>{@code requestId} 是数据库 BIGINT 的十进制字符串；{@code expectedVersion} 与
 * {@code operationId} 一起保证撤销与管理员审批竞争时只有一个状态转换能成功。
 */
public final class WithdrawTeacherAdjustmentRequestDTO {
    private final String operationId;
    private final String requestId;
    private final int expectedVersion;

    public WithdrawTeacherAdjustmentRequestDTO(String operationId, String requestId,
            int expectedVersion) {
        this.operationId = operationId;
        this.requestId = requestId;
        this.expectedVersion = expectedVersion;
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
}
