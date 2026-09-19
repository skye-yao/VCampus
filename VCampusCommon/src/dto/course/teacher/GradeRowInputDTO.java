package dto.course.teacher;

/**
 * 提交内容里的一行：只接收 enrollmentId 与四项分数。
 *
 * <p>{@code enrollmentId} 是数据库 BIGINT 的十进制字符串。总评与绩点由服务端按当前方案重新计算，
 * 所以这个请求 DTO 里没有 totalScore/gradePoint 字段，客户端传来的计算结果一律不信任。
 */
public final class GradeRowInputDTO {
    private final String enrollmentId;
    private final GradeScoresDTO scores;

    public GradeRowInputDTO(String enrollmentId, GradeScoresDTO scores) {
        this.enrollmentId = enrollmentId;
        this.scores = scores;
    }

    /** 获取 EnrollmentId。 */
    public String getEnrollmentId() {
        return enrollmentId;
    }

    /** 获取 Scores。 */
    public GradeScoresDTO getScores() {
        return scores;
    }
}
