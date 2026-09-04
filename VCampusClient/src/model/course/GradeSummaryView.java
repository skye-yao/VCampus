package model.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GradeSummaryView {
    private final String term;
    private final double termGpa;
    private final double termAverage;
    private final double cumulativeAverage;
    private final double cumulativeGpa;
    private final List<GradeRecordView> records;

    public GradeSummaryView(String term, double termGpa, double termAverage,
            double cumulativeAverage, double cumulativeGpa,
            List<GradeRecordView> records) {
        this.term = term;
        this.termGpa = termGpa;
        this.termAverage = termAverage;
        this.cumulativeAverage = cumulativeAverage;
        this.cumulativeGpa = cumulativeGpa;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    public String getTerm() {
        return term;
    }

    public double getTermGpa() {
        return termGpa;
    }

    public double getTermAverage() {
        return termAverage;
    }

    public double getCumulativeAverage() {
        return cumulativeAverage;
    }

    public double getCumulativeGpa() {
        return cumulativeGpa;
    }

    public List<GradeRecordView> getRecords() {
        return records;
    }
}
