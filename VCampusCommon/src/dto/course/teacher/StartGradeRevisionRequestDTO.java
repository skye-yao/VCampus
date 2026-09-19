package dto.course.teacher;

/**
 * 教师为一个教学班开始一次新的成绩版本（驳回重提 / 发起更正）的写请求体，位于
 * {@code data.request}。
 *
 * <p>{@code sourceSubmissionId} 是来源批次的数据库 BIGINT 十进制字符串：驳回重开必须是本班最后一次
 * <b>被驳回</b>的批次，发起更正必须是本班最后一次<b>已通过</b>的批次；服务端在同一把
 * offering 锁内核对它是不是最后一次批次，别的批次一概不接受。{@code expectedRevision} 是客户端读到
 * 的 {@link TeacherGradeBookDTO#getRevision()}，与 {@code operationId} 一起保证并发的第二次点击
 * 只会拿到冲突而不是把已经打开的草稿再重置一遍。
 *
 * <p>{@code reason} 是更正原因：只有更正必填（服务端拒绝空白），驳回重提是普通重提，不要求原因，
 * 也不会沿用上一轮的更正原因。
 */
public final class StartGradeRevisionRequestDTO {
    private final String operationId;
    private final String offeringId;
    private final String sourceSubmissionId;
    private final long expectedRevision;
    private final String reason;

    public StartGradeRevisionRequestDTO(String operationId, String offeringId,
            String sourceSubmissionId, long expectedRevision, String reason) {
        this.operationId = operationId;
        this.offeringId = offeringId;
        this.sourceSubmissionId = sourceSubmissionId;
        this.expectedRevision = expectedRevision;
        this.reason = reason;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 SourceSubmissionId。 */
    public String getSourceSubmissionId() {
        return sourceSubmissionId;
    }

    /** 获取 ExpectedRevision。 */
    public long getExpectedRevision() {
        return expectedRevision;
    }

    /** 获取 Reason。 */
    public String getReason() {
        return reason;
    }
}
