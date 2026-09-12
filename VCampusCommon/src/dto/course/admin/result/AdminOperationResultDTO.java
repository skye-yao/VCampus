package dto.course.admin.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleConflictDTO;

public final class AdminOperationResultDTO<T> {
    private final String operationId;
    private final String outcomeCode;
    private final String message;
    private final T entity;
    private final List<ScheduleConflictDTO> conflicts;

    public AdminOperationResultDTO(String operationId, String outcomeCode, String message,
            T entity, List<ScheduleConflictDTO> conflicts) {
        this.operationId = operationId;
        this.outcomeCode = outcomeCode;
        this.message = message;
        this.entity = entity;
        this.conflicts = conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
    }

    public String getOperationId() {
        return operationId;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public String getMessage() {
        return message;
    }

    public T getEntity() {
        return entity;
    }

    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }
}
