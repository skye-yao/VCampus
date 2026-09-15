package dto.course.teacher;

/**
 * 成绩组成代码，固定四项：平时、期中、实验、期末。
 *
 * <p>本期不新增任意自定义成绩列，所以这里不是可扩展的字符串：方案的组成集合必须由这四个代码构成。
 * 反序列化遇到未知代码时保持 null，由服务端校验拒绝，不能静默回退成某一固定组成。
 */
public enum GradeComponentCodeDTO {
    DAILY,
    MIDTERM,
    EXPERIMENT,
    FINALTERM
}
