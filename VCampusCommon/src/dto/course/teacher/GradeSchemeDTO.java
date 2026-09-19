package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 一个教学班的完整成绩方案，固定包含 DAILY/MIDTERM/EXPERIMENT/FINALTERM 四项组成。
 *
 * <p>构造时要求恰好四项且代码互不重复：重复或缺失的组成说明调用方把结构搞错了，
 * 直接拒绝而不是静默合并，避免一份残缺方案被保存成工作副本或写进提交快照。
 * 组成顺序保持调用方传入的顺序，DTO 不做归一化排序。
 *
 * <p>JSON 反序列化不经过构造器（Gson 直接写字段），所以结构校验不是线上唯一防线：
 * 服务端在保存/提交时仍会用 GradeCalculator.validateScheme 复核，并拒绝非法枚举值。
 */
public final class GradeSchemeDTO {
    private static final int REQUIRED_COMPONENTS = 4;

    private final List<GradeComponentDTO> components;

    public GradeSchemeDTO(List<GradeComponentDTO> components) {
        this.components = immutableCopy(components);
        requireFourDistinctComponents(this.components);
    }

    /** 获取 Components。 */
    public List<GradeComponentDTO> getComponents() {
        return unmodifiable(components);
    }

    private static void requireFourDistinctComponents(List<GradeComponentDTO> components) {
        if (components.size() != REQUIRED_COMPONENTS) {
            throw new IllegalArgumentException(
                    "成绩方案必须恰好包含四项组成，收到 " + components.size() + " 项");
        }
        Set<GradeComponentCodeDTO> codes = EnumSet.noneOf(GradeComponentCodeDTO.class);
        for (GradeComponentDTO component : components) {
            if (component == null || component.getCode() == null) {
                throw new IllegalArgumentException("成绩组成的代码不能为空");
            }
            if (!codes.add(component.getCode())) {
                throw new IllegalArgumentException(
                        "成绩组成的代码不能重复: " + component.getCode());
            }
        }
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
