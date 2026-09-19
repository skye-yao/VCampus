package dto.course.admin.approval;

/** 教务模块的 GradeSubmissionItemDTO 数据传输对象。 */
public final class GradeSubmissionItemDTO {
    private final String enrollmentId;
    private final String studentUid;
    private final String studentName;
    private final Double dailyScore;
    private final Double midtermScore;
    private final Double experimentScore;
    private final Double finaltermScore;
    private final Double score;
    private final Integer gradeLevel;
    private final Double gradePoint;

    public GradeSubmissionItemDTO(String enrollmentId, String studentUid,
            String studentName, Double dailyScore, Double midtermScore,
            Double experimentScore, Double finaltermScore, Double score,
            Integer gradeLevel, Double gradePoint) {
        this.enrollmentId = enrollmentId;
        this.studentUid = studentUid;
        this.studentName = studentName;
        this.dailyScore = dailyScore;
        this.midtermScore = midtermScore;
        this.experimentScore = experimentScore;
        this.finaltermScore = finaltermScore;
        this.score = score;
        this.gradeLevel = gradeLevel;
        this.gradePoint = gradePoint;
    }

    /** 获取 EnrollmentId。 */
    public String getEnrollmentId() {
        return enrollmentId;
    }

    /** 获取 StudentUid。 */
    public String getStudentUid() {
        return studentUid;
    }

    /** 获取 StudentName。 */
    public String getStudentName() {
        return studentName;
    }

    /** 获取 DailyScore。 */
    public Double getDailyScore() {
        return dailyScore;
    }

    /** 获取 MidtermScore。 */
    public Double getMidtermScore() {
        return midtermScore;
    }

    /** 获取 ExperimentScore。 */
    public Double getExperimentScore() {
        return experimentScore;
    }

    /** 获取 FinaltermScore。 */
    public Double getFinaltermScore() {
        return finaltermScore;
    }

    /** 获取 Score。 */
    public Double getScore() {
        return score;
    }

    /** 获取 GradeLevel。 */
    public Integer getGradeLevel() {
        return gradeLevel;
    }

    /** 获取 GradePoint。 */
    public Double getGradePoint() {
        return gradePoint;
    }
}
