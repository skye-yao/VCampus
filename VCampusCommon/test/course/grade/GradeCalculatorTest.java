package course.grade;

import java.math.BigDecimal;
import java.util.List;

import com.google.gson.Gson;

import dto.course.teacher.GradeComponentCodeDTO;
import dto.course.teacher.GradeComponentDTO;
import dto.course.teacher.GradeSchemeDTO;
import dto.course.teacher.GradeScoresDTO;

/**
 * 教师总评的纯计算测试：BigDecimal、整数万分比权重、最终 HALF_UP 两位小数。
 *
 * <p>覆盖计划点名的算术：30%×80 + 70%×90 = 87.00、四项权重、禁用值不参与、缺项不算零、
 * 总评精确舍入、总权重错误；另按既定约束覆盖非法分数（越界 / 超过两位小数）与
 * 反序列化路径上的方案结构校验——Gson 不经过 GradeSchemeDTO 构造器，validateScheme 是唯一防线。
 */
public final class GradeCalculatorTest {
    private static final Gson GSON = new Gson();

    public static void main(String[] args) {
        briefThirtySeventyExample();
        fourComponentWeights();
        disabledScoresDoNotParticipate();
        missingEnabledScoreStaysNull();
        totalRoundingIsHalfUpAtTwoDecimals();
        illegalScoresAreRejected();
        incompleteWeightsHaveNoTotal();
        malformedSchemesAreRejectedOnTheDeserializedPath();
        System.out.println("GradeCalculatorTest passed (8 scenarios)");
    }

    /** 计划点名的样例：30%×80 + 70%×90 = 87.00，两位小数返回。 */
    private static void briefThirtySeventyExample() {
        GradeSchemeDTO scheme = thirtySeventyScheme();
        GradeCalculator.validateScheme(scheme, true);

        BigDecimal total = GradeCalculator.total(scheme, scores("80", null, null, "90"));
        require(total != null && total.compareTo(new BigDecimal("87.00")) == 0,
                "30% x 80 + 70% x 90 must be 87.00, got " + total);
        require(total.scale() == 2,
                "the total must keep exactly two decimals, got " + total.toPlainString());
    }

    /** 四项都启用时的权重算术：20%×80 + 20%×85 + 10%×90 + 50%×70 = 77.00。 */
    private static void fourComponentWeights() {
        GradeSchemeDTO scheme = scheme(
                component(GradeComponentCodeDTO.DAILY, true, 2000),
                component(GradeComponentCodeDTO.MIDTERM, true, 2000),
                component(GradeComponentCodeDTO.EXPERIMENT, true, 1000),
                component(GradeComponentCodeDTO.FINALTERM, true, 5000));

        BigDecimal total = GradeCalculator.total(scheme, scores("80.00", "85.00", "90.00", "70.00"));
        require(total != null && total.compareTo(new BigDecimal("77.00")) == 0,
                "the four weighted components must total 77.00, got " + total);
    }

    /** 0.00 是真实零分、要参与计算；禁用项即使带着分数也不参与。 */
    private static void disabledScoresDoNotParticipate() {
        GradeSchemeDTO scheme = thirtySeventyScheme();
        BigDecimal total = GradeCalculator.total(scheme, scores("0.00", "100.00", "100.00", "90.00"));
        require(total != null && total.compareTo(new BigDecimal("63.00")) == 0,
                "0.00 must count as zero while the disabled 100.00 scores are ignored, got " + total);

        BigDecimal withoutDisabledValues = GradeCalculator.total(scheme, scores("0.00", null, null, "90.00"));
        require(total.compareTo(withoutDisabledValues) == 0,
                "disabled components must not change the total whether or not they carry a score");
    }

    /** 缺少启用项分数时总评是 NULL：缺失不是 0 分；整行缺失同理。 */
    private static void missingEnabledScoreStaysNull() {
        GradeSchemeDTO scheme = thirtySeventyScheme();
        require(GradeCalculator.total(scheme, scores(null, null, null, "90.00")) == null,
                "a missing enabled daily score must keep the total NULL, not treat it as zero");
        require(GradeCalculator.total(scheme, null) == null,
                "a row without scores must keep the total NULL");
    }

    /** 84.10×50% + 89.91×50% = 87.005 必须 HALF_UP 成 87.01；截断或提前取整都会得到 87.00。 */
    private static void totalRoundingIsHalfUpAtTwoDecimals() {
        GradeSchemeDTO scheme = scheme(
                component(GradeComponentCodeDTO.DAILY, true, 5000),
                component(GradeComponentCodeDTO.MIDTERM, false, 0),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 5000));

        BigDecimal total = GradeCalculator.total(scheme, scores("84.10", null, null, "89.91"));
        require(total != null && total.compareTo(new BigDecimal("87.01")) == 0,
                "87.005 must round HALF_UP to 87.01, got " + total);
    }

    /** 非空分数必须在 0..100 且最多两位小数：拒绝，而不是交给 DECIMAL(5,2) 静默取整。 */
    private static void illegalScoresAreRejected() {
        GradeSchemeDTO scheme = thirtySeventyScheme();
        expectIllegalArgument(() -> GradeCalculator.total(scheme, scores("88.555", null, null, "90.00")),
                "88.555 must be rejected instead of being rounded to two decimals");
        expectIllegalArgument(() -> GradeCalculator.total(scheme, scores("100.01", null, null, "90.00")),
                "a score above 100 must be rejected");
        expectIllegalArgument(() -> GradeCalculator.total(scheme, scores("-0.01", null, null, "90.00")),
                "a score below 0 must be rejected");
        expectIllegalArgument(() -> GradeCalculator.total(scheme, scores("80.00", "88.555", null, "90.00")),
                "the score rule applies to every non-null score, including disabled components");
    }

    /**
     * 正式提交要求至少一个启用项、启用项权重大于 0、禁用项权重为 0、合计 10000；
     * 草稿允许未配齐，但此时总评是 NULL，不显示按当前权重凑出来的伪造总评。
     */
    private static void incompleteWeightsHaveNoTotal() {
        GradeSchemeDTO incomplete = scheme(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.MIDTERM, false, 0),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 6000));
        GradeCalculator.validateScheme(incomplete, false);
        expectIllegalArgument(() -> GradeCalculator.validateScheme(incomplete, true),
                "weights summing to 9000 must be rejected at submission");
        require(GradeCalculator.total(incomplete, scores("80.00", null, null, "90.00")) == null,
                "an incomplete scheme must return NULL instead of a made-up total");

        GradeSchemeDTO noneEnabled = scheme(
                component(GradeComponentCodeDTO.DAILY, false, 10000),
                component(GradeComponentCodeDTO.MIDTERM, false, 0),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, false, 0));
        GradeCalculator.validateScheme(noneEnabled, false);
        expectIllegalArgument(() -> GradeCalculator.validateScheme(noneEnabled, true),
                "a scheme without any enabled component must be rejected at submission");

        GradeSchemeDTO zeroEnabledWeight = scheme(
                component(GradeComponentCodeDTO.DAILY, true, 0),
                component(GradeComponentCodeDTO.MIDTERM, false, 0),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 7000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(zeroEnabledWeight, true),
                "an enabled component must carry a positive weight");

        GradeSchemeDTO disabledWeight = scheme(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.MIDTERM, false, 2000),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 7000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(disabledWeight, true),
                "a disabled component must weigh 0 at submission");
        require(GradeCalculator.total(disabledWeight, scores("80.00", null, null, "90.00")) == null,
                "a scheme that could not be submitted must not produce a total either");
    }

    /** Gson 不经过 GradeSchemeDTO 构造器：结构错误必须由 validateScheme 独立挡住，total 同样拒绝。 */
    private static void malformedSchemesAreRejectedOnTheDeserializedPath() {
        GradeSchemeDTO valid = gsonScheme(
                componentJson("DAILY", true, 3000), componentJson("MIDTERM", true, 3000),
                componentJson("EXPERIMENT", false, 0), componentJson("FINALTERM", true, 4000));
        GradeCalculator.validateScheme(valid, true);

        GradeSchemeDTO threeComponents = gsonScheme(
                componentJson("DAILY", true, 3000), componentJson("MIDTERM", true, 3000),
                componentJson("FINALTERM", true, 4000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(threeComponents, false),
                "a deserialized scheme with three components must be rejected even for a draft");
        expectIllegalArgument(
                () -> GradeCalculator.total(threeComponents, scores("80.00", "80.00", null, "90.00")),
                "total must reject a malformed scheme instead of returning a number");

        GradeSchemeDTO duplicate = gsonScheme(
                componentJson("DAILY", true, 3000), componentJson("DAILY", true, 3000),
                componentJson("EXPERIMENT", false, 0), componentJson("FINALTERM", true, 4000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(duplicate, false),
                "a duplicate component code must be rejected");

        GradeSchemeDTO nullComponent = gsonScheme(
                "null", componentJson("MIDTERM", true, 3000),
                componentJson("EXPERIMENT", false, 0), componentJson("FINALTERM", true, 4000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(nullComponent, false),
                "a null component must be rejected");

        GradeSchemeDTO unknownCode = gsonScheme(
                componentJson("ATTENDANCE", true, 3000), componentJson("MIDTERM", true, 3000),
                componentJson("EXPERIMENT", false, 0), componentJson("FINALTERM", true, 4000));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(unknownCode, false),
                "an unknown code deserializes to null and must be rejected, not silently mapped");

        GradeSchemeDTO negativeWeight = gsonScheme(
                componentJson("DAILY", true, -1), componentJson("MIDTERM", true, 3000),
                componentJson("EXPERIMENT", false, 0), componentJson("FINALTERM", true, 7001));
        expectIllegalArgument(() -> GradeCalculator.validateScheme(negativeWeight, false),
                "a negative weight must be rejected even for a draft");
    }

    /** 30% 平时 + 70% 期末，期中/实验禁用；被多个场景复用。 */
    private static GradeSchemeDTO thirtySeventyScheme() {
        return scheme(
                component(GradeComponentCodeDTO.DAILY, true, 3000),
                component(GradeComponentCodeDTO.MIDTERM, false, 0),
                component(GradeComponentCodeDTO.EXPERIMENT, false, 0),
                component(GradeComponentCodeDTO.FINALTERM, true, 7000));
    }

    private static GradeSchemeDTO scheme(GradeComponentDTO... components) {
        return new GradeSchemeDTO(List.of(components));
    }

    private static GradeComponentDTO component(GradeComponentCodeDTO code, boolean enabled,
                                               int weightBasisPoints) {
        return new GradeComponentDTO(code, enabled, weightBasisPoints);
    }

    private static GradeScoresDTO scores(String daily, String midterm, String experiment,
                                         String finalterm) {
        return new GradeScoresDTO(decimal(daily), decimal(midterm), decimal(experiment),
                decimal(finalterm));
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static GradeSchemeDTO gsonScheme(String... componentJsons) {
        return GSON.fromJson(jsonScheme(componentJsons), GradeSchemeDTO.class);
    }

    private static String jsonScheme(String... componentJsons) {
        return "{\"components\":[" + String.join(",", componentJsons) + "]}";
    }

    private static String componentJson(String code, boolean enabled, int weightBasisPoints) {
        return "{\"code\":\"" + code + "\",\"enabled\":" + enabled
                + ",\"weightBasisPoints\":" + weightBasisPoints + "}";
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
