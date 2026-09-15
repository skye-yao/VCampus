package dto.course.teacher;

/**
 * 成绩方案中的一个组成列：代码、是否启用与权重。
 *
 * <p>{@code weightBasisPoints} 是整数万分比，10000 表示 100.00%，避免用 double 保存权重带来的舍入误差。
 * {@code enabled} 为 false 的组成不参与总评计算，提交快照及正式成绩里该项分数写 NULL。
 * 这里不校验权重范围：草稿允许尚未配齐的权重，启用/合计规则由服务端在提交时统一校验。
 */
public final class GradeComponentDTO {
    private final GradeComponentCodeDTO code;
    private final boolean enabled;
    private final int weightBasisPoints;

    public GradeComponentDTO(GradeComponentCodeDTO code, boolean enabled, int weightBasisPoints) {
        this.code = code;
        this.enabled = enabled;
        this.weightBasisPoints = weightBasisPoints;
    }

    public GradeComponentCodeDTO getCode() {
        return code;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getWeightBasisPoints() {
        return weightBasisPoints;
    }
}
