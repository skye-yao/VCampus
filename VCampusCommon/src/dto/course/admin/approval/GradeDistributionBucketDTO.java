package dto.course.admin.approval;

public final class GradeDistributionBucketDTO {
    private final String label;
    private final int count;

    public GradeDistributionBucketDTO(String label, int count) {
        this.label = label;
        this.count = count;
    }

    public String getLabel() {
        return label;
    }

    public int getCount() {
        return count;
    }
}
