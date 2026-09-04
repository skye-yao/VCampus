package model.course;

public final class GradeRecordView {
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

    public GradeRecordView(String term, String courseCode, String courseName,
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

    public String getTerm() {
        return term;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public double getCredit() {
        return credit;
    }

    public double getScore() {
        return score;
    }

    public double getGradePoint() {
        return gradePoint;
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

    public Double getFinalScore() {
        return finalScore;
    }
}
