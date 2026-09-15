package dto.course.teacher;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 成绩表里的一行：学生身份、四项分数与服务端重算的总评/绩点。
 *
 * <p>{@code totalScore} 与 {@code gradePoint} 全部由服务端计算，草稿权重未配齐或启用项缺分时保持 null，
 * 不能把缺分当零。{@code complete} 为 true 表示该行当前已能算出完整总评。
 * {@code errors} 是行级错误文本（非法分数、学生不在本班名单等），没有错误时是空列表；
 * 列表在构造时防御性复制并对外只读。
 */
public final class TeacherGradeRowDTO {
    private final String enrollmentId;
    private final String studentUid;
    private final String studentName;
    private final GradeScoresDTO scores;
    private final BigDecimal totalScore;
    private final BigDecimal gradePoint;
    private final boolean complete;
    private final List<String> errors;

    public TeacherGradeRowDTO(String enrollmentId, String studentUid, String studentName,
            GradeScoresDTO scores, BigDecimal totalScore, BigDecimal gradePoint, boolean complete,
            List<String> errors) {
        this.enrollmentId = enrollmentId;
        this.studentUid = studentUid;
        this.studentName = studentName;
        this.scores = scores;
        this.totalScore = totalScore;
        this.gradePoint = gradePoint;
        this.complete = complete;
        this.errors = immutableCopy(errors);
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

    public GradeScoresDTO getScores() {
        return scores;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public BigDecimal getGradePoint() {
        return gradePoint;
    }

    public boolean isComplete() {
        return complete;
    }

    /** 构造时复制、反序列化后也返回不可修改视图，避免界面侧改写错误列表。 */
    public List<String> getErrors() {
        return unmodifiable(errors);
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
