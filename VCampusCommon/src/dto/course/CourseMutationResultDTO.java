package dto.course;

/** 教务模块的 CourseMutationResultDTO 数据传输对象。 */
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

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 Item。 */
    public CourseSelectionItemDTO getItem() {
        return item;
    }

    /** 获取 FinalState。 */
    public SelectionStateDTO getFinalState() {
        return finalState;
    }

    /** 获取 OutcomeCode。 */
    public String getOutcomeCode() {
        return outcomeCode;
    }

    /** 获取 Message。 */
    public String getMessage() {
        return message;
    }

    /** 获取 Snapshot。 */
    public CoursePlanSnapshotDTO getSnapshot() {
        return snapshot;
    }
}
