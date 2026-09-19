package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 SchedulePlanDTO 数据传输对象。 */
public final class SchedulePlanDTO {
    private final String planId;
    private final String name;
    private final int revision;
    private final String status;
    private final boolean current;
    private final List<ScheduleConflictDTO> conflicts;

    public SchedulePlanDTO(String planId, String name, int revision, String status,
            boolean current, List<ScheduleConflictDTO> conflicts) {
        this.planId = planId;
        this.name = name;
        this.revision = revision;
        this.status = status;
        this.current = current;
        this.conflicts = conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
    }

    /** 获取 PlanId。 */
    public String getPlanId() {
        return planId;
    }

    /** 获取 Name。 */
    public String getName() {
        return name;
    }

    /** 获取 Revision。 */
    public int getRevision() {
        return revision;
    }

    /** 获取 Status。 */
    public String getStatus() {
        return status;
    }

    /** 判断 Current 是否成立。 */
    public boolean isCurrent() {
        return current;
    }

    /** 获取 Conflicts。 */
    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }
}
