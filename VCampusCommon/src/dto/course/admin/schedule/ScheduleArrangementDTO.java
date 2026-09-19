package dto.course.admin.schedule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 ScheduleArrangementDTO 数据传输对象。 */
public final class ScheduleArrangementDTO {
    private final String arrangementId;
    private final String planId;
    private final String offeringId;
    private final ScheduleResourceDTO teacher;
    private final ScheduleResourceDTO assistant;
    private final ScheduleResourceDTO classroom;
    private final List<ScheduleSlotDTO> slots;
    private final int startWeek;
    private final int endWeek;
    private final String status;
    private final int version;

    public ScheduleArrangementDTO(String arrangementId, String planId, String offeringId,
            ScheduleResourceDTO teacher, ScheduleResourceDTO assistant,
            ScheduleResourceDTO classroom, List<ScheduleSlotDTO> slots,
            int startWeek, int endWeek, String status, int version) {
        this.arrangementId = arrangementId;
        this.planId = planId;
        this.offeringId = offeringId;
        this.teacher = teacher;
        this.assistant = assistant;
        this.classroom = classroom;
        this.slots = slots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(slots));
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.status = status;
        this.version = version;
    }

    /** 获取 ArrangementId。 */
    public String getArrangementId() {
        return arrangementId;
    }

    /** 获取 PlanId。 */
    public String getPlanId() {
        return planId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 Teacher。 */
    public ScheduleResourceDTO getTeacher() {
        return teacher;
    }

    /** 获取 Assistant。 */
    public ScheduleResourceDTO getAssistant() {
        return assistant;
    }

    /** 获取 Classroom。 */
    public ScheduleResourceDTO getClassroom() {
        return classroom;
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

    /** 获取 Status。 */
    public String getStatus() {
        return status;
    }

    /** 获取 Version。 */
    public int getVersion() {
        return version;
    }
}
