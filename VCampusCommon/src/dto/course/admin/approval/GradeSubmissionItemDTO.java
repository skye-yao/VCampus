package dto.course.admin.approval;

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

    public String getEnrollmentId() {
        return enrollmentId;
    }

    public String getStudentUid() {
        return studentUid;
    }

    public String getStudentName() {
        return studentName;
    }

    public Double getDailyScore() {
        return dailyScore;
    }

    public Double getMidtermScore() {
        return midtermScore;
    }

    public Double getExperimentScore() {
        return experimentScore;
    }

    public Double getFinaltermScore() {
        return finaltermScore;
    }

    public Double getScore() {
        return score;
    }

    public Integer getGradeLevel() {
        return gradeLevel;
    }

    public Double getGradePoint() {
        return gradePoint;
    }
}
