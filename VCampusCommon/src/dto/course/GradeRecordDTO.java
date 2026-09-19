package dto.course;

/** 教务模块的 GradeRecordDTO 数据传输对象。 */
public final class GradeRecordDTO {
    private final String term;
    private final String courseCode;
    private final String courseName;
    private final double credit;
    private final double score;
    private final double gradePoint;
    private final Double dailyScore;
    private final Double midtermScore;
    private final Double experimentScore;
    private final Double finalScore;

    public GradeRecordDTO(String term, String courseCode, String courseName,
            double credit, double score, double gradePoint, Double dailyScore,
            Double midtermScore, Double experimentScore, Double finalScore) {
        this.term = term;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.credit = credit;
        this.score = score;
        this.gradePoint = gradePoint;
        this.dailyScore = dailyScore;
        this.midtermScore = midtermScore;
        this.experimentScore = experimentScore;
        this.finalScore = finalScore;
    }

    /** 获取 Term。 */
    public String getTerm() {
        return term;
    }

    /** 获取 CourseCode。 */
    public String getCourseCode() {
        return courseCode;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 Credit。 */
    public double getCredit() {
        return credit;
    }

    /** 获取 Score。 */
    public double getScore() {
        return score;
    }

    /** 获取 GradePoint。 */
    public double getGradePoint() {
        return gradePoint;
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

    /** 获取 FinalScore。 */
    public Double getFinalScore() {
        return finalScore;
    }
}
