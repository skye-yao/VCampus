package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.teacher.GradeSchemeDTO;

public final class GradeSubmissionDetailDTO {
    private final GradeSubmissionSummaryDTO summary;
    private final List<GradeDistributionBucketDTO> distribution;
    private final List<GradeSubmissionItemDTO> items;
    private final String reviewedBy;
    private final String reviewedAt;
    private final String reviewComment;
    private final GradeSchemeDTO schemeSnapshot;
    private final String baseSubmissionId;
    private final int uncoveredCount;

    /**
     * 旧构造方法保留：没有方案快照与批次来源信息时按“历史批次”渲染，新字段不破坏既有调用方。
     */
    public GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
            List<GradeDistributionBucketDTO> distribution,
            List<GradeSubmissionItemDTO> items, String reviewedBy,
            String reviewedAt, String reviewComment) {
        this(summary, distribution, items, reviewedBy, reviewedAt, reviewComment, null, null, 0);
    }

    /**
     * @param schemeSnapshot 提交时捕获的成绩方案快照；历史批次为 {@code null}，不能伪造可重算性
     * @param baseSubmissionId 更正/重提批次的基础批次ID；普通批次为 {@code null}
     * @param uncoveredCount 提交之后新增、尚未纳入该批次的正常选课学生人数
     */
    public GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
            List<GradeDistributionBucketDTO> distribution,
            List<GradeSubmissionItemDTO> items, String reviewedBy,
            String reviewedAt, String reviewComment, GradeSchemeDTO schemeSnapshot,
            String baseSubmissionId, int uncoveredCount) {
        this.summary = summary;
        this.distribution = distribution == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(distribution));
        this.items = items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(items));
        this.reviewedBy = reviewedBy;
        this.reviewedAt = reviewedAt;
        this.reviewComment = reviewComment;
        this.schemeSnapshot = schemeSnapshot;
        this.baseSubmissionId = baseSubmissionId;
        this.uncoveredCount = uncoveredCount;
    }

    public GradeSubmissionSummaryDTO getSummary() {
        return summary;
    }

    public List<GradeDistributionBucketDTO> getDistribution() {
        return distribution == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(distribution);
    }

    public List<GradeSubmissionItemDTO> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
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

    /** 提交时捕获的成绩方案快照；历史批次为 {@code null}，界面据此按旧验证规则显示。 */
    public GradeSchemeDTO getSchemeSnapshot() {
        return schemeSnapshot;
    }

    /** 更正/重提批次的基础批次ID；普通批次为 {@code null}。 */
    public String getBaseSubmissionId() {
        return baseSubmissionId;
    }

    /** 提交之后新增、尚未纳入该批次的正常选课学生人数；0 表示没有未纳入的新成员。 */
    public int getUncoveredCount() {
        return uncoveredCount;
    }
}
