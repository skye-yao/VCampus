package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public String getPlanId() {
        return planId;
    }

    public String getName() {
        return name;
    }

    public int getRevision() {
        return revision;
    }

    public String getStatus() {
        return status;
    }

    public boolean isCurrent() {
        return current;
    }

    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }
}
