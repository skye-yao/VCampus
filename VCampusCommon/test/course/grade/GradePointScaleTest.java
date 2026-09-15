package course.grade;

import java.math.BigDecimal;

/**
 * 学校绩点表的连续区间映射测试（设计文档 §1 的绩点表）。
 *
 * <p>覆盖计划点名的五个换算：95.90→4.5、96.00→4.8、59.99→0.0、60.00→1.0、100→4.8，
 * 以及每个分界自身、加 0.01、减 0.01 的区间归属：分界点属于上方区间，分界减 0.01 落回下方区间。
 * 总评已由 GradeCalculator HALF_UP 保留两位小数，这里验证的是“不先取整、按连续区间比较”，
 * 并钉住 NULL 与越界总评必须被拒绝，而不是猜一个绩点。
 */
public final class GradePointScaleTest {
    /** 绩点表下界（从高到低）与对应绩点；测试里按设计文档独立重写一遍，不复用实现的数组。 */
    private static final int[] LOWER_BOUNDS = {96, 93, 90, 86, 83, 80, 76, 73, 70, 66, 63, 60, 0};
    private static final String[] POINTS =
            {"4.8", "4.5", "4.0", "3.8", "3.5", "3.0", "2.8", "2.5", "2.0", "1.8", "1.5", "1.0", "0.0"};

    public static void main(String[] args) {
        briefConversions();
        everyBoundaryAndItsNeighbours();
        gradePointsKeepOneDecimalPlace();
        nullAndOutOfRangeTotalsAreRejected();
        System.out.println("GradePointScaleTest passed (4 scenarios)");
    }

    /** 计划点名的五个换算；95.90 不允许被四舍五入成整数 96 后拿到 4.8。 */
    private static void briefConversions() {
        if (GradePointScale.gradePointFor(new BigDecimal("95.90")).compareTo(new BigDecimal("4.5")) != 0)
            throw new AssertionError("95.90 must not be rounded to integer 96");
        requirePoint("96.00", "4.8");
        requirePoint("59.99", "0.0");
        requirePoint("60.00", "1.0");
        requirePoint("100", "4.8");
        require(GradePointScale.gradePointFor(new BigDecimal("95.90")).compareTo(new BigDecimal("4.8")) != 0,
                "95.90 must stay in the 93..96 band, not be promoted to the 4.8 band");
    }

    /** 每个分界自身、加 0.01、减 0.01：区间是连续的，分界值归上方区间。 */
    private static void everyBoundaryAndItsNeighbours() {
        for (int i = 0; i < LOWER_BOUNDS.length; i++) {
            int bound = LOWER_BOUNDS[i];
            requirePoint(bound + ".00", POINTS[i]);
            requirePoint(bound + ".01", POINTS[i]);
            if (bound > 0) {
                requirePoint((bound - 1) + ".99", POINTS[i + 1]);
            }
        }
    }

    /** 绩点按一位小数返回，4.0/0.0 不能被规范化成 4/0。 */
    private static void gradePointsKeepOneDecimalPlace() {
        require(GradePointScale.gradePointFor(new BigDecimal("91.00")).scale() == 1,
                "4.0 must stay at one decimal place, not be normalised to 4");
        require(GradePointScale.gradePointFor(new BigDecimal("0.00")).scale() == 1,
                "0.0 must stay at one decimal place, not be normalised to 0");
    }

    /** NULL 与越界总评没有对应绩点，必须拒绝而不是回退到某个区间。 */
    private static void nullAndOutOfRangeTotalsAreRejected() {
        expectIllegalArgument(() -> GradePointScale.gradePointFor(null),
                "a null total has no grade point and must be rejected");
        expectIllegalArgument(() -> GradePointScale.gradePointFor(new BigDecimal("100.01")),
                "a total above 100 must be rejected");
        expectIllegalArgument(() -> GradePointScale.gradePointFor(new BigDecimal("-0.01")),
                "a negative total must be rejected");
    }

    private static void requirePoint(String total, String expectedPoint) {
        BigDecimal actual = GradePointScale.gradePointFor(new BigDecimal(total));
        require(actual.compareTo(new BigDecimal(expectedPoint)) == 0,
                "total " + total + " must map to " + expectedPoint + ", got " + actual.toPlainString());
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
