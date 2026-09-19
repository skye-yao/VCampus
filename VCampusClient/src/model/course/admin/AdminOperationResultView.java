package model.course.admin;

/** 管理端写操作的结果及其可选实体载荷。 */
public final class AdminOperationResultView<T> {
    private final String operationId;
    private final String outcomeCode;
    private final String message;
    private final T entity;

    /** 创建管理端操作结果视图。 */
    public AdminOperationResultView(String operationId, String outcomeCode, String message,
            T entity) {
        this.operationId = operationId;
        this.outcomeCode = outcomeCode;
        this.message = message;
        this.entity = entity;
    }

    public String getOperationId() { return operationId; }
    public String getOutcomeCode() { return outcomeCode; }
    public String getMessage() { return message; }
    public T getEntity() { return entity; }
}
