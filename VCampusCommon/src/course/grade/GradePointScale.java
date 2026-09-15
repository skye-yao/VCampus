package course.grade;

import java.math.BigDecimal;

/**
 * 学校绩点表：把总评映射成一位小数的绩点，按连续区间比较，不先取整。
 *
 * <p>表来自设计文档 §1：95.90 → 4.5、96.00 → 4.8，说明区间是连续实数区间而不是把总评取整后查表。
 * 传入的总评已经由 {@link GradeCalculator#total} HALF_UP 保留两位小数；这里只做区间比较。
 * NULL（缺少启用项分数）或越界总评没有对应的绩点，直接拒绝，不能回退到某个区间。
 */
public final class GradePointScale {
    /** 各区间下界，从高到低；与 POINTS 一一对应。 */
    private static final int[] LOWER_BOUNDS = {96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 0};
    private static final BigDecimal[] POINTS = {
        new BigDecimal("4.8"), new BigDecimal("4.5"), new BigDecimal("4.0"), new BigDecimal("3.8"),
        new BigDecimal("3.5"), new BigDecimal("3.0"), new BigDecimal("2.8"), new BigDecimal("2.5"),
        new BigDecimal("2.0"), new BigDecimal("1.8"), new BigDecimal("1.5"), new BigDecimal("1.0"),
        new BigDecimal("0.0"),
    };
    private static final BigDecimal MIN_TOTAL = BigDecimal.ZERO;
    private static final BigDecimal MAX_TOTAL = new BigDecimal("100");

    private GradePointScale() {
    }

    /**
     * 按连续区间返回绩点：分界值属于上方区间（96.00 → 4.8，95.99 → 4.5）。
     *
     * @throws IllegalArgumentException 总评为 null 或不在 0..100 之间
     */
    public static BigDecimal gradePointFor(BigDecimal total) {
        if (total == null) {
            throw new IllegalArgumentException("总评为空，无法换算绩点");
        }
        if (total.compareTo(MIN_TOTAL) < 0 || total.compareTo(MAX_TOTAL) > 0) {
            throw new IllegalArgumentException("总评必须在 0..100 之间: " + total.toPlainString());
        }
        for (int i = 0; i < LOWER_BOUNDS.length; i++) {
            if (total.compareTo(new BigDecimal(LOWER_BOUNDS[i])) >= 0) {
                return POINTS[i];
            }
        }
        // 上面的范围检查已经保证 total >= 0，而下界数组以 0 收尾，所以正常走不到这里。
        throw new IllegalStateException("绩点表缺少下界 0，无法换算总评 " + total.toPlainString());
    }
}
