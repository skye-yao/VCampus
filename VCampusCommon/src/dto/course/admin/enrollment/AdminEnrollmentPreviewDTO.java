package dto.course.admin.enrollment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleConflictDTO;

public final class AdminEnrollmentPreviewDTO {
    private final String offeringId;
    private final String studentUid;
    private final List<ScheduleConflictDTO> risks;

    public AdminEnrollmentPreviewDTO(String offeringId, String studentUid,
            List<ScheduleConflictDTO> risks) {
        this.offeringId = offeringId;
        this.studentUid = studentUid;
        this.risks = risks == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(risks));
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getStudentUid() {
        return studentUid;
    }

    public List<ScheduleConflictDTO> getRisks() {
        return risks == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(risks);
    }
}
