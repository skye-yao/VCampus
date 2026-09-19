package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 教务模块的 GradeSummaryDTO 数据传输对象。 */
public final class GradeSummaryDTO {
    private final String term;
    private final double termGpa;
    private final double termAverage;
    private final double cumulativeAverage;
    private final double cumulativeGpa;
    private final List<GradeRecordDTO> records;

    public GradeSummaryDTO(String term, double termGpa, double termAverage,
            double cumulativeAverage, double cumulativeGpa,
            List<GradeRecordDTO> records) {
        this.term = term;
        this.termGpa = termGpa;
        this.termAverage = termAverage;
        this.cumulativeAverage = cumulativeAverage;
        this.cumulativeGpa = cumulativeGpa;
        this.records = Collections.unmodifiableList(new ArrayList<>(records));
    }

    /** 获取 Term。 */
    public String getTerm() {
        return term;
    }

    /** 获取 TermGpa。 */
    public double getTermGpa() {
        return termGpa;
    }

    /** 获取 TermAverage。 */
    public double getTermAverage() {
        return termAverage;
    }

    /** 获取 CumulativeAverage。 */
    public double getCumulativeAverage() {
        return cumulativeAverage;
    }

    /** 获取 CumulativeGpa。 */
    public double getCumulativeGpa() {
        return cumulativeGpa;
    }

    /** 获取 Records。 */
    public List<GradeRecordDTO> getRecords() {
        return Collections.unmodifiableList(records);
    }
}
