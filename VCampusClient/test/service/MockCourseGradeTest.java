package service;

import model.course.GradeRecordView;
import model.course.GradeSummaryView;

public final class MockCourseGradeTest {
    public static void main(String[] args) throws Exception {
        GradeSummaryView summary =
                new MockCourseService().loadGrades("2026-2027 秋学期").get();
        require(summary.getRecords().size() == 2, "two grade records expected");
        require(summary.getTermGpa() == 3.85, "term GPA must be deterministic");
        require(summary.getTermAverage() == 91.5, "term average must be deterministic");
        require(summary.getCumulativeAverage() == 90.8,
                "cumulative average must be meaningful");
        require(summary.getCumulativeGpa() == 3.78,
                "cumulative GPA must be meaningful");
        GradeRecordView record = summary.getRecords().stream()
                .filter(item -> item.getExperimentScore() == null)
                .findFirst().orElseThrow();
        require(record.getExperimentScore() == null, "missing component remains null");

        require("CS101".equals(record.getCourseCode()),
                "programming course code must be deterministic");
        require("程序设计基础".equals(record.getCourseName()),
                "programming course name must be deterministic");
        require(record.getCredit() == 4.0, "programming credit must be deterministic");
        require(record.getScore() == 94.0, "programming total score must be complete");
        require(record.getGradePoint() == 4.0,
                "programming GPA must be deterministic");
        require(record.getDailyScore() == 95.0,
                "programming daily score must be deterministic");
        require(record.getMidtermScore() == 92.0,
                "programming midterm score must be deterministic");
        require(record.getFinalScore() == 95.0,
                "programming final score must be deterministic");

        GradeRecordView math = summary.getRecords().stream()
                .filter(item -> "高等数学".equals(item.getCourseName()))
                .findFirst().orElseThrow();
        require("MA101".equals(math.getCourseCode()),
                "mathematics course code must be deterministic");
        require(math.getCredit() == 5.0, "mathematics credit must be deterministic");
        require(math.getScore() == 89.0, "mathematics total score must be complete");
        require(math.getGradePoint() == 3.7,
                "mathematics GPA must be deterministic");
        require(math.getDailyScore() == 90.0,
                "mathematics daily score must be deterministic");
        require(math.getMidtermScore() == 88.0,
                "mathematics midterm score must be deterministic");
        require(math.getExperimentScore() == 87.0,
                "mathematics experiment score must be deterministic");
        require(math.getFinalScore() == 90.0,
                "mathematics final score must be deterministic");

        expectUnsupported(() -> summary.getRecords().add(math),
                "grade records must be immutable");

        GradeSummaryView unknown =
                new MockCourseService().loadGrades("unknown term").get();
        require(unknown.getTermGpa() == 0.0, "unknown term GPA must be zero");
        require(unknown.getTermAverage() == 0.0, "unknown term average must be zero");
        require(unknown.getCumulativeAverage() == 0.0,
                "unknown cumulative average must be zero");
        require(unknown.getCumulativeGpa() == 0.0,
                "unknown cumulative GPA must be zero");
        require(unknown.getRecords().isEmpty(), "unknown term records must be empty");
        expectUnsupported(() -> unknown.getRecords().add(record),
                "unknown term records must be immutable");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectUnsupported(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
            // Expected immutable view.
        }
    }
}
