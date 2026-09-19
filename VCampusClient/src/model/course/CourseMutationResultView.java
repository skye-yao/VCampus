package model.course;

/** 课程计划或选课操作完成后供界面消费的结果模型。 */
public final class CourseMutationResultView {
    private final String operationId;
    private final CourseSelectionItemView item;
    private final SelectionStatus finalState;
    private final String outcomeCode;
    private final String message;
    private final CoursePlanSnapshotView snapshot;

    /** 创建一次课程操作的结果视图。 */
    public CourseMutationResultView(String operationId, CourseSelectionItemView item,
            SelectionStatus finalState, String outcomeCode, String message,
            CoursePlanSnapshotView snapshot) {
        this.operationId = operationId;
        this.item = item;
        this.finalState = finalState;
        this.outcomeCode = outcomeCode;
        this.message = message;
        this.snapshot = snapshot;
    }

    public String getOperationId() { return operationId; }
    public CourseSelectionItemView getItem() { return item; }
    public SelectionStatus getFinalState() { return finalState; }
    public String getOutcomeCode() { return outcomeCode; }
    public String getMessage() { return message; }
    public CoursePlanSnapshotView getSnapshot() { return snapshot; }
}
