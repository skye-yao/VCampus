package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.AdjustmentRequestStatusDTO;
import dto.course.admin.schedule.ScheduleConflictDTO;
import dto.course.admin.schedule.ScheduleResourceDTO;

/**
 * 调课申请详情。教师端“我的申请”和管理员审批页共用同一个服务端事实。
 *
 * <p>状态是调课专用的四态 {@link AdjustmentRequestStatusDTO}：教师撤销（WITHDRAWN）是申请人的终态，
 * 不占用成绩审批的三态 {@link ApprovalStatusDTO}。每个目标自带目标教学日
 * （{@link AdjustmentTargetDTO#getTargetDate()}），历史行没有明确日期时为 null。
 */
public final class AdjustmentRequestDetailDTO {
    private final String requestId;
    private final String offeringId;
    private final String applicantUid;
    private final String reason;
    private final AdjustmentRequestStatusDTO status;
    private final int version;
    private final int newDayOfWeek;
    private final int newStartPeriod;
    private final int newEndPeriod;
    private final ScheduleResourceDTO newTeacher;
    private final ScheduleResourceDTO newAssistant;
    private final ScheduleResourceDTO newClassroom;
    private final List<AdjustmentTargetDTO> targets;
    private final List<ScheduleConflictDTO> conflicts;
    private final String submittedAt;
    private final String reviewedBy;
    private final String reviewedAt;
    private final String reviewComment;

    public AdjustmentRequestDetailDTO(String requestId, String offeringId,
            String applicantUid, String reason, AdjustmentRequestStatusDTO status,
            int version, int newDayOfWeek, int newStartPeriod, int newEndPeriod,
            ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
            ScheduleResourceDTO newClassroom, List<AdjustmentTargetDTO> targets,
            List<ScheduleConflictDTO> conflicts, String submittedAt,
            String reviewedBy, String reviewedAt, String reviewComment) {
        this.requestId = requestId;
        this.offeringId = offeringId;
        this.applicantUid = applicantUid;
        this.reason = reason;
        this.status = status;
        this.version = version;
        this.newDayOfWeek = newDayOfWeek;
        this.newStartPeriod = newStartPeriod;
        this.newEndPeriod = newEndPeriod;
        this.newTeacher = newTeacher;
        this.newAssistant = newAssistant;
        this.newClassroom = newClassroom;
        this.targets = targets == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(targets));
        this.conflicts = conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(conflicts));
        this.submittedAt = submittedAt;
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.reviewComment = reviewComment;
    }

    /**
     * 兼容重载：迁移前的旧调用点只认识三态枚举，按枚举名映射到四态。
     * 成绩审批枚举不增加 WITHDRAWN，映射只覆盖两者共有的三个状态。
     */
    public AdjustmentRequestDetailDTO(String requestId, String offeringId,
            String applicantUid, String reason, ApprovalStatusDTO status,
            int version, int newDayOfWeek, int newStartPeriod, int newEndPeriod,
            ScheduleResourceDTO newTeacher, ScheduleResourceDTO newAssistant,
            ScheduleResourceDTO newClassroom, List<AdjustmentTargetDTO> targets,
            List<ScheduleConflictDTO> conflicts, String submittedAt,
            String reviewedBy, String reviewedAt, String reviewComment) {
        this(requestId, offeringId, applicantUid, reason,
                status == null ? null : AdjustmentRequestStatusDTO.valueOf(status.name()),
                version, newDayOfWeek, newStartPeriod, newEndPeriod, newTeacher, newAssistant,
                newClassroom, targets, conflicts, submittedAt, reviewedBy, reviewedAt,
                reviewComment);
    }

    public String getRequestId() {
        return requestId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getApplicantUid() {
        return applicantUid;
    }

    public String getReason() {
        return reason;
    }

    public AdjustmentRequestStatusDTO getStatus() {
        return status;
    }

    public int getVersion() {
        return version;
    }

    public int getNewDayOfWeek() {
        return newDayOfWeek;
    }

    public int getNewStartPeriod() {
        return newStartPeriod;
    }

    public int getNewEndPeriod() {
        return newEndPeriod;
    }

    public ScheduleResourceDTO getNewTeacher() {
        return newTeacher;
    }

    public ScheduleResourceDTO getNewAssistant() {
        return newAssistant;
    }

    public ScheduleResourceDTO getNewClassroom() {
        return newClassroom;
    }

    public List<AdjustmentTargetDTO> getTargets() {
        return targets == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(targets);
    }

    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }

    public String getSubmittedAt() {
        return submittedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public String getReviewedAt() {
        return reviewedAt;
    }

    public String getReviewComment() {
        return reviewComment;
    }
}
