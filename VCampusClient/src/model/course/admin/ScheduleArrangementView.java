package model.course.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleResourceDTO;
import dto.course.admin.schedule.ScheduleSlotDTO;

public final class ScheduleArrangementView {
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

    public ScheduleArrangementView(String arrangementId, String planId, String offeringId,
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

    public String getArrangementId() { return arrangementId; }
    public String getPlanId() { return planId; }
    public String getOfferingId() { return offeringId; }
    public ScheduleResourceDTO getTeacher() { return teacher; }
    public ScheduleResourceDTO getAssistant() { return assistant; }
    public ScheduleResourceDTO getClassroom() { return classroom; }
    public int getStartWeek() { return startWeek; }
    public int getEndWeek() { return endWeek; }
    public String getStatus() { return status; }
    public int getVersion() { return version; }

    public List<ScheduleSlotDTO> getSlots() {
        return slots == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(slots);
    }
}
