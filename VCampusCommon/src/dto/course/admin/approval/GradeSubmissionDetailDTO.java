package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.teacher.GradeSchemeDTO;

/** 教务模块的 GradeSubmissionDetailDTO 数据传输对象。 */
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
    private final GradeCorrectionComparisonDTO correctionComparison;

    /**
     * 旧构造方法保留：没有方案快照与批次来源信息时按“历史批次”渲染，新字段不破坏既有调用方。
     */
    public GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
            List<GradeDistributionBucketDTO> distribution,
            List<GradeSubmissionItemDTO> items, String reviewedBy,
            String reviewedAt, String reviewComment) {
        this(summary, distribution, items, reviewedBy, reviewedAt, reviewComment, null, null, 0,
                null);
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
        this(summary, distribution, items, reviewedBy, reviewedAt, reviewComment, schemeSnapshot,
                baseSubmissionId, uncoveredCount, null);
    }

    /**
     * 最新重载：比上一个多出与基础批次的比较。旧重载一律传 {@code null}，既有调用方（管理员
     * 审批的模拟实现与各测试）因此逐字不变，不会因为多了一个字段而集体修改。
     *
     * @param correctionComparison 服务端从两个批次自己的明细行算出的差异；普通批次、历史批次
     *                             或基础批次已不可读时为 {@code null}
     */
    public GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
            List<GradeDistributionBucketDTO> distribution,
            List<GradeSubmissionItemDTO> items, String reviewedBy,
            String reviewedAt, String reviewComment, GradeSchemeDTO schemeSnapshot,
            String baseSubmissionId, int uncoveredCount,
            GradeCorrectionComparisonDTO correctionComparison) {
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
        this.correctionComparison = correctionComparison;
    }

    /** 获取 Summary。 */
    public GradeSubmissionSummaryDTO getSummary() {
        return summary;
    }

    /** 获取 Distribution。 */
    public List<GradeDistributionBucketDTO> getDistribution() {
        return distribution == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(distribution);
    }

    /** 获取 Items。 */
    public List<GradeSubmissionItemDTO> getItems() {
        return items == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(items);
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

    /**
     * 本次批次与它的基础批次之间的比较；普通批次、历史批次以及基础批次已不可读时为 {@code null}。
     * 界面据此显示原批准版本、本次提交版本、更正原因与真的改变了的学生及其旧/新值。
     */
    public GradeCorrectionComparisonDTO getCorrectionComparison() {
        return correctionComparison;
    }
}
