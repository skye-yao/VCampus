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

    /** 获取 RequestId。 */
    public String getRequestId() {
        return requestId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 ApplicantUid。 */
    public String getApplicantUid() {
        return applicantUid;
    }

    /** 获取 Reason。 */
    public String getReason() {
        return reason;
    }

    /** 获取 Status。 */
    public AdjustmentRequestStatusDTO getStatus() {
        return status;
    }

    /** 获取 Version。 */
    public int getVersion() {
        return version;
    }

    /** 获取 NewDayOfWeek。 */
    public int getNewDayOfWeek() {
        return newDayOfWeek;
    }

    /** 获取 NewStartPeriod。 */
    public int getNewStartPeriod() {
        return newStartPeriod;
    }

    /** 获取 NewEndPeriod。 */
    public int getNewEndPeriod() {
        return newEndPeriod;
    }

    /** 获取 NewTeacher。 */
    public ScheduleResourceDTO getNewTeacher() {
        return newTeacher;
    }

    /** 获取 NewAssistant。 */
    public ScheduleResourceDTO getNewAssistant() {
        return newAssistant;
    }

    /** 获取 NewClassroom。 */
    public ScheduleResourceDTO getNewClassroom() {
        return newClassroom;
    }

    /** 获取 Targets。 */
    public List<AdjustmentTargetDTO> getTargets() {
        return targets == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(targets);
    }

    /** 获取 Conflicts。 */
    public List<ScheduleConflictDTO> getConflicts() {
        return conflicts == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(conflicts);
    }

    /** 获取 SubmittedAt。 */
    public String getSubmittedAt() {
        return submittedAt;
    }

    /** 获取 ReviewedBy。 */
    public String getReviewedBy() {
        return reviewedBy;
    }

    /** 获取 ReviewedAt。 */
    public String getReviewedAt() {
        return reviewedAt;
    }

    /** 获取 ReviewComment。 */
    public String getReviewComment() {
        return reviewComment;
    }
}
