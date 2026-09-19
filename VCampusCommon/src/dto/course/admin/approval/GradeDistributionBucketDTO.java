package dto.course.admin.approval;

/** 教务模块的 GradeDistributionBucketDTO 数据传输对象。 */
public final class GradeDistributionBucketDTO {
    private final String label;
    private final int count;

    public GradeDistributionBucketDTO(String label, int count) {
        this.label = label;
        this.count = count;
    }

    /** 获取 Label。 */
    public String getLabel() {
        return label;
    }

    /** 获取 Count。 */
    public int getCount() {
        return count;
    }
}
