package dto.course.teacher;

/**
 * 教师端所有写操作的统一幂等结果。
 *
 * <p>{@code operationId} 是客户端生成的 UUID；相同操作 ID 且规范化请求相同时服务端重放
 * 已提交结果，此时 {@code replayed} 为 true。Task 3 起的教师调课/成绩写接口使用该契约，
 * T1 阶段先冻结签名，避免后续改动公共协议。
 */
public final class TeacherOperationResultDTO<T> {
    private final String operationId;
    private final String message;
    private final T value;
    private final boolean replayed;

    public TeacherOperationResultDTO(String operationId, String message, T value, boolean replayed) {
        this.operationId = operationId;
        this.message = message;
        this.value = value;
        this.replayed = replayed;
    }

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 Message。 */
    public String getMessage() {
        return message;
    }

    /** 获取 Value。 */
    public T getValue() {
        return value;
    }

    /** 判断 Replayed 是否成立。 */
    public boolean isReplayed() {
        return replayed;
    }
}
