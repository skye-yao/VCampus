package course.grade;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;

/**
 * 教师成绩总评的纯计算：全程 BigDecimal，权重是整数万分比（10000 表示 100.00%）。
 *
 * <p>总评 = sum(启用项分数 × weightBasisPoints) / 10000，最后 HALF_UP 保留两位小数；
 * 禁用项即使带着分数也不参与计算；启用项缺分数时总评是 NULL，不能把缺失当 0。
 * 这里不碰 JDBC / JavaFX / IO：服务端在提交事务内重新执行同一份规则，客户端只用它做预览，
 * 双方算出的一定是同一个数。
 *
 * <p>分数规则：非空分数必须在 0..100 且最多两位小数。位数超限直接拒绝而不是四舍五入——
 * 成绩列是 DECIMAL(5,2)，MySQL 插入时会静默取整，接受 88.555 等于让教师录入的值被悄悄改掉。
 */
public final class GradeCalculator {
    private static final int REQUIRED_COMPONENT_COUNT = 4;
    private static final int TOTAL_BASIS_POINTS = 10000;
    private static final BigDecimal BASIS_POINTS = new BigDecimal(TOTAL_BASIS_POINTS);
    private static final int SCORE_SCALE = 2;
    private static final BigDecimal MIN_SCORE = BigDecimal.ZERO;
    private static final BigDecimal MAX_SCORE = new BigDecimal("100");

    private GradeCalculator() {
    }

    /**
     * 校验成绩方案：结构问题永远拒绝，权重是否配齐由 {@code requireComplete} 决定。
     *
     * <p>结构检查（恰好四项固定组成 DAILY/MIDTERM/EXPERIMENT/FINALTERM、组成与代码非空、代码不重复、
     * 权重不为负）是反序列化路径上的唯一防线：Gson 直接写字段、不经过 GradeSchemeDTO 的构造器。
     *
     * <p>{@code requireComplete=true} 用于正式提交：至少一个启用项、启用项权重大于 0、
     * 禁用项权重为 0、启用项权重合计 10000。草稿传 false，允许权重未配齐（含禁用项残留的权重），
     * 但结构错误同样拒绝。
     *
     * @throws IllegalArgumentException 方案为 null、结构非法，或 requireComplete 时权重未配齐
     */
    public static void validateScheme(GradeSchemeDTO scheme, boolean requireComplete) {
        List<GradeComponentDTO> components = requireFourFixedComponents(scheme);
        if (requireComplete) {
            String problem = weightProblem(components);
            if (problem != null) {
                throw new IllegalArgumentException(problem);
            }
        }
    }

    /**
     * 计算总评：只累计启用项，HALF_UP 保留两位小数。
     *
     * <p>返回 null 的三种情况：权重未配齐（草稿，不显示伪造的总评）、整行没有分数、
     * 缺少启用项分数（缺失不是 0 分）。禁用项缺分不影响计算，它带着的分数也不参与计算，
     * 但非空分数仍然要过分数规则。
     *
     * @throws IllegalArgumentException 方案结构非法（同 {@link #validateScheme}），
     *         或任一非空分数越界 / 超过两位小数
     */
    public static BigDecimal total(GradeSchemeDTO scheme, GradeScoresDTO scores) {
        List<GradeComponentDTO> components = requireFourFixedComponents(scheme);
        if (weightProblem(components) != null) {
            // 草稿权重没配齐：总评没有意义，返回 NULL 而不是按当前权重凑一个数出来。
            // 这个判断放在分数校验之前是有意的：不产出总评时就不会有任何分数被静默取整。
            return null;
        }
        if (scores == null) {
            return null;
        }
        validateScores(scores);

        BigDecimal weighted = BigDecimal.ZERO;
        for (GradeComponentDTO component : components) {
            BigDecimal score = scoreOf(component.getCode(), scores);
            if (score == null) {
                if (component.isEnabled()) {
                    return null;
                }
                continue;
            }
            if (component.isEnabled()) {
                weighted = weighted.add(
                        score.multiply(new BigDecimal(component.getWeightBasisPoints())));
            }
        }
        // 10000 是 10 的幂，这个除法永远精确；四舍五入只发生在这一处。
        return weighted.divide(BASIS_POINTS).setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 只校验一行分数的合法性：非空分数必须在 0..100 且最多两位小数，null（未录入）允许。
     *
     * <p>{@link #total} 在权重未配齐时提前返回 NULL、不产出总评，也就不会校验分数；保存草稿
     * 需要在不显示总评的前提下仍然拒绝会被数据库静默取整的分数，所以分数规则有这一个独立入口。
     * 规则实现只有 {@link #requireLegalScore} 一处，提交与草稿不会漂移。
     *
     * @throws IllegalArgumentException 任一非空分数越界或超过两位小数
     */
    public static void validateScores(GradeScoresDTO scores) {
        if (scores == null) return;
        for (GradeComponentCodeDTO code : GradeComponentCodeDTO.values()) {
            BigDecimal score = scoreOf(code, scores);
            if (score != null) {
                requireLegalScore(score, code);
            }
        }
    }

    /**
     * 结构与固定四项检查；权重是否配齐不在这一层。
     *
     * @return 方案里的四项组成（保持调用方顺序），方便调用方继续遍历
     */
    private static List<GradeComponentDTO> requireFourFixedComponents(GradeSchemeDTO scheme) {
        if (scheme == null) {
            throw new IllegalArgumentException("成绩方案不能为空");
        }
        List<GradeComponentDTO> components = scheme.getComponents();
        if (components.size() != REQUIRED_COMPONENT_COUNT) {
            throw new IllegalArgumentException(
                    "成绩方案必须恰好包含四项组成，收到 " + components.size() + " 项");
        }
        Set<GradeComponentCodeDTO> codes = EnumSet.noneOf(GradeComponentCodeDTO.class);
        for (GradeComponentDTO component : components) {
            if (component == null) {
                throw new IllegalArgumentException("成绩组成不能为空");
            }
            GradeComponentCodeDTO code = component.getCode();
            if (code == null) {
                throw new IllegalArgumentException("成绩组成的代码不能为空（未知的组成代码）");
            }
            if (!codes.add(code)) {
                throw new IllegalArgumentException("成绩组成的代码不能重复: " + code);
            }
            if (component.getWeightBasisPoints() < 0) {
                throw new IllegalArgumentException(
                        "成绩组成的权重不能为负数: " + code + " " + component.getWeightBasisPoints());
            }
        }
        if (!codes.equals(EnumSet.allOf(GradeComponentCodeDTO.class))) {
            // 当前四项枚举下与重复检查等价；枚举将来扩展时这里仍然挡住“缺了一项”。
            throw new IllegalArgumentException("成绩方案必须包含平时、期中、实验、期末四项固定组成");
        }
        return components;
    }

    /**
     * 权重未配齐的原因（正式提交规则）；配齐时返回 null。
     *
     * <p>正式提交和总评共用同一份规则：提交时用它生成错误信息，total 用它在草稿阶段返回 NULL。
     */
    private static String weightProblem(List<GradeComponentDTO> components) {
        // 权重来自网络且是 int：用 long 累加，四个极大值相加在 int 里会回绕成 10000 而绕过“合计 10000”。
        long enabledWeight = 0;
        boolean hasEnabled = false;
        for (GradeComponentDTO component : components) {
            if (component.isEnabled()) {
                int weight = component.getWeightBasisPoints();
                if (weight <= 0) {
                    return "启用的成绩组成权重必须大于 0: " + component.getCode();
                }
                hasEnabled = true;
                enabledWeight += weight;
            } else if (component.getWeightBasisPoints() != 0) {
                return "禁用的成绩组成权重必须为 0: " + component.getCode();
            }
        }
        if (!hasEnabled) {
            return "正式提交的成绩方案必须至少启用一项组成";
        }
        if (enabledWeight != TOTAL_BASIS_POINTS) {
            return "启用项权重合计必须为 10000（万分比），收到 " + enabledWeight;
        }
        return null;
    }

    private static void requireLegalScore(BigDecimal score, GradeComponentCodeDTO code) {
        if (score.scale() > SCORE_SCALE) {
            throw new IllegalArgumentException("成绩组成 " + code
                    + " 的分数最多两位小数，不接受会被数据库取整的值: " + score.toPlainString());
        }
        if (score.compareTo(MIN_SCORE) < 0 || score.compareTo(MAX_SCORE) > 0) {
            throw new IllegalArgumentException(
                    "成绩组成 " + code + " 的分数必须在 0..100 之间: " + score.toPlainString());
        }
    }

    /** 按固定字段顺序取分：平时/期中/实验/期末，与 GradeScoresDTO 的构造参数一致。 */
    private static BigDecimal scoreOf(GradeComponentCodeDTO code, GradeScoresDTO scores) {
        return switch (code) {
            case DAILY -> scores.getDailyScore();
            case MIDTERM -> scores.getMidtermScore();
            case EXPERIMENT -> scores.getExperimentScore();
            case FINALTERM -> scores.getFinaltermScore();
        };
    }
}
