package dto.course.admin.approval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GradeSubmissionDetailDTO {
    private final GradeSubmissionSummaryDTO summary;
    private final List<GradeDistributionBucketDTO> distribution;
    private final List<GradeSubmissionItemDTO> items;
    private final String reviewedBy;
    private final String reviewedAt;
    private final String reviewComment;

    public GradeSubmissionDetailDTO(GradeSubmissionSummaryDTO summary,
            List<GradeDistributionBucketDTO> distribution,
            List<GradeSubmissionItemDTO> items, String reviewedBy,
            String reviewedAt, String reviewComment) {
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
}
