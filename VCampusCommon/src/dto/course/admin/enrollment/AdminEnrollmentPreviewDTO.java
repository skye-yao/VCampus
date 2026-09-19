package dto.course.admin.enrollment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleConflictDTO;

/** 教务模块的 AdminEnrollmentPreviewDTO 数据传输对象。 */
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

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 StudentUid。 */
    public String getStudentUid() {
        return studentUid;
    }

    /** 获取 Risks。 */
    public List<ScheduleConflictDTO> getRisks() {
        return risks == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(risks);
    }
}
