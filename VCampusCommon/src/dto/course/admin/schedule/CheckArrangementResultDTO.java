package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 预检查的一次往返结果：表单级冲突（候选安排）与方案级冲突（整方案权威快照）。
 * 让「预检查冲突」在一次请求里同时刷新对话框顶部与底部的冲突区。
 */
public final class CheckArrangementResultDTO {
    private final List<ScheduleConflictDTO> arrangementConflicts;
    private final List<ScheduleConflictDTO> planConflicts;

    public CheckArrangementResultDTO(List<ScheduleConflictDTO> arrangementConflicts,
            List<ScheduleConflictDTO> planConflicts) {
        this.arrangementConflicts = copy(arrangementConflicts);
        this.planConflicts = copy(planConflicts);
    }

    /** 获取 ArrangementConflicts。 */
    public List<ScheduleConflictDTO> getArrangementConflicts() {
        return arrangementConflicts;
    }

    /** 获取 PlanConflicts。 */
    public List<ScheduleConflictDTO> getPlanConflicts() {
        return planConflicts;
    }

    private static List<ScheduleConflictDTO> copy(List<ScheduleConflictDTO> conflicts) {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
    }
}
