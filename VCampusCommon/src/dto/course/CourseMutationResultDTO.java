package dto.course;

public final class CourseMutationResultDTO {
    private final String operationId;
    private final CourseSelectionItemDTO item;
    private final SelectionStateDTO finalState;
    private final String outcomeCode;
    private final String message;
    private final CoursePlanSnapshotDTO snapshot;

    public CourseMutationResultDTO(String operationId, CourseSelectionItemDTO item,
            SelectionStateDTO finalState, String outcomeCode, String message,
            CoursePlanSnapshotDTO snapshot) {
        this.operationId = operationId;
        this.item = item;
        this.finalState = finalState;
        this.outcomeCode = outcomeCode;
        this.message = message;
        this.snapshot = snapshot;
    }

    public String getOperationId() {
        return operationId;
    }

    public CourseSelectionItemDTO getItem() {
        return item;
    }

    public SelectionStateDTO getFinalState() {
        return finalState;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public String getMessage() {
        return message;
    }

    public CoursePlanSnapshotDTO getSnapshot() {
        return snapshot;
    }
}
