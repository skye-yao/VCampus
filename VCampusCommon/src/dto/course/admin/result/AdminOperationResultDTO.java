package dto.course.admin.result;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleConflictDTO;

/** 教务模块的 AdminOperationResultDTO 数据传输对象。 */
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

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 OutcomeCode。 */
    public String getOutcomeCode() {
        return outcomeCode;
    }

    /** 获取 Message。 */
    public String getMessage() {
        return message;
    }

    /** 获取 Entity。 */
    public T getEntity() {
        return entity;
    }

    /** 获取 Conflicts。 */
    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }
}
