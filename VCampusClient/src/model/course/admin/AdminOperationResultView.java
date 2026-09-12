package model.course.admin;

public final class AdminOperationResultView<T> {
    private final String operationId;
    private final String outcomeCode;
    private final String message;
    private final T entity;

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
