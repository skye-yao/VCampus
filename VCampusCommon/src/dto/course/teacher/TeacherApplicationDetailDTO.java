package dto.course.teacher;

import dto.course.admin.approval.AdjustmentRequestDetailDTO;
import dto.course.admin.approval.GradeSubmissionDetailDTO;

/**
 * 「我的申请」的详情：一行摘要加**恰好一个**类型化详情。
 *
 * <p>设计 §11 要求「根据 summary.type 恰好一个详情非空，不使用 Object 或未约束 Map」。两个详情
 * DTO 都是既有类型、各有各的语义：{@code adjustment} 是调课申请（含原/新安排与 PENDING 的实时冲突
 * 快照），{@code grade} 是成绩提交批次的**只读快照**（提交那一刻的名单、分数与总评，之后教师的
 * 工作副本怎么改都不会回写它）。
 *
 * <p>这里没有「用一个 Map 装两种详情」的第三条路：字段是类型化的，客户端只按
 * {@code summary.getType()} 取其中一个，另一个必然是 {@code null}。
 */
public final class TeacherApplicationDetailDTO {
    private final TeacherApplicationDTO summary;
    private final AdjustmentRequestDetailDTO adjustment;
    private final GradeSubmissionDetailDTO grade;

    public TeacherApplicationDetailDTO(TeacherApplicationDTO summary,
            AdjustmentRequestDetailDTO adjustment, GradeSubmissionDetailDTO grade) {
        this.summary = summary;
        this.adjustment = adjustment;
        this.grade = grade;
    }

    /** 获取 Summary。 */
    public TeacherApplicationDTO getSummary() {
        return summary;
    }

    /** 调课申请详情；{@code summary.type} 不是调课申请时为 null。 */
    public AdjustmentRequestDetailDTO getAdjustment() {
        return adjustment;
    }

    /** 成绩提交详情的只读快照；{@code summary.type} 不是成绩提交时为 null。 */
    public GradeSubmissionDetailDTO getGrade() {
        return grade;
    }
}
