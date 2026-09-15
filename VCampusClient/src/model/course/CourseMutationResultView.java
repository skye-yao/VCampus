package model.course;

public final class CourseMutationResultView {
    private final String operationId;
    private final CourseSelectionItemView item;
    private final SelectionStatus finalState;
    private final String outcomeCode;
    private final String message;
    private final CoursePlanSnapshotView snapshot;

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
