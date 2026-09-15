package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SaveArrangementRequestDTO {
    private final String operationId;
    private final String arrangementId;
    private final int expectedVersion;
    private final String planId;
    private final String offeringId;
    private final String teacherUid;
    private final String assistantUid;
    private final String classroomId;
    private final List<ScheduleSlotDTO> slots;
    private final int startWeek;
    private final int endWeek;
    private final boolean force;
    private final String overrideReason;

    public SaveArrangementRequestDTO(String operationId, String arrangementId,
            int expectedVersion, String planId, String offeringId,
            String teacherUid, String assistantUid, String classroomId,
            List<ScheduleSlotDTO> slots, int startWeek, int endWeek,
            boolean force, String overrideReason) {
        this.operationId = operationId;
        this.arrangementId = arrangementId;
        this.expectedVersion = expectedVersion;
        this.planId = planId;
        this.offeringId = offeringId;
        this.teacherUid = teacherUid;
        this.assistantUid = assistantUid;
        this.classroomId = classroomId;
        this.slots = slots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(slots));
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.force = force;
        this.overrideReason = overrideReason;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getArrangementId() {
        return arrangementId;
    }

    public int getExpectedVersion() {
        return expectedVersion;
    }

    public String getPlanId() {
        return planId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getTeacherUid() {
        return teacherUid;
    }

    public String getAssistantUid() {
        return assistantUid;
    }

    public String getClassroomId() {
        return classroomId;
    }

    public List<ScheduleSlotDTO> getSlots() {
        return slots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(slots);
    }

    public int getStartWeek() {
        return startWeek;
    }

    public int getEndWeek() {
        return endWeek;
    }

    public boolean isForce() {
        return force;
    }

    public String getOverrideReason() {
        return overrideReason;
    }
}
