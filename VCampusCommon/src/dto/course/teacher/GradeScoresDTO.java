package dto.course.teacher;

import java.math.BigDecimal;

/**
 * 一名学生的四项成绩组成：平时、期中、实验、期末，均为 0..100 的两位小数或 null。
 *
 * <p>null 表示尚未录入，必须与 0.00 区分：缺失的启用项让总评/绩点保持 NULL，不能当成零分参与计算。
 * 分数用 BigDecimal 传值并保留原始小数位（0.00 不会被规范化成 0），
 * 范围、位数与方案一致性由服务端和数据库 CHECK 校验，DTO 只负责如实搬运。
 */
public final class GradeScoresDTO {
    private final BigDecimal dailyScore;
    private final BigDecimal midtermScore;
    private final BigDecimal experimentScore;
    private final BigDecimal finaltermScore;

    public GradeScoresDTO(BigDecimal dailyScore, BigDecimal midtermScore,
            BigDecimal experimentScore, BigDecimal finaltermScore) {
        this.dailyScore = dailyScore;
        this.midtermScore = midtermScore;
        this.experimentScore = experimentScore;
        this.finaltermScore = finaltermScore;
    }

    /** 获取 DailyScore。 */
    public BigDecimal getDailyScore() {
        return dailyScore;
    }

    /** 获取 MidtermScore。 */
    public BigDecimal getMidtermScore() {
        return midtermScore;
    }

    /** 获取 ExperimentScore。 */
    public BigDecimal getExperimentScore() {
        return experimentScore;
    }

    /** 获取 FinaltermScore。 */
    public BigDecimal getFinaltermScore() {
        return finaltermScore;
    }
}
