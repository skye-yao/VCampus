package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 SaveArrangementRequestDTO 数据传输对象。 */
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

    /** 获取 OperationId。 */
    public String getOperationId() {
        return operationId;
    }

    /** 获取 ArrangementId。 */
    public String getArrangementId() {
        return arrangementId;
    }

    /** 获取 ExpectedVersion。 */
    public int getExpectedVersion() {
        return expectedVersion;
    }

    /** 获取 PlanId。 */
    public String getPlanId() {
        return planId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 TeacherUid。 */
    public String getTeacherUid() {
        return teacherUid;
    }

    /** 获取 AssistantUid。 */
    public String getAssistantUid() {
        return assistantUid;
    }

    /** 获取 ClassroomId。 */
    public String getClassroomId() {
        return classroomId;
    }

    /** 获取 Slots。 */
    public List<ScheduleSlotDTO> getSlots() {
        return slots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(slots);
    }

    /** 获取 StartWeek。 */
    public int getStartWeek() {
        return startWeek;
    }

    /** 获取 EndWeek。 */
    public int getEndWeek() {
        return endWeek;
    }

    /** 判断 Force 是否成立。 */
    public boolean isForce() {
        return force;
    }

    /** 获取 OverrideReason。 */
    public String getOverrideReason() {
        return overrideReason;
    }
}
