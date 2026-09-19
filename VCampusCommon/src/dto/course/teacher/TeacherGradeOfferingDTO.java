package dto.course.teacher;

/**
 * 成绩录入列表里的一行：教学班摘要 + 成绩状态与完成情况。
 *
 * <p>{@code offering} 复用教学班列表的摘要，{@code state} 与 {@code TeacherGradeBookDTO} 同一套取值
 * （DRAFT/PENDING/APPROVED/REJECTED）。{@code enteredCount} 是已录入分数的学生数，
 * {@code missingCount} 是仍缺启用项分数的学生数；两者按当前正常 enrollment 统计，不含退课历史。
 * {@code lastSubmissionId} 是最后一次提交批次，尚未提交时为 null。
 */
public final class TeacherGradeOfferingDTO {
    private final TeacherOfferingDTO offering;
    private final String state;
    private final int enteredCount;
    private final int missingCount;
    private final String lastSubmissionId;

    public TeacherGradeOfferingDTO(TeacherOfferingDTO offering, String state, int enteredCount,
            int missingCount, String lastSubmissionId) {
        this.offering = offering;
        this.state = state;
        this.enteredCount = enteredCount;
        this.missingCount = missingCount;
        this.lastSubmissionId = lastSubmissionId;
    }

    /** 获取 Offering。 */
    public TeacherOfferingDTO getOffering() {
        return offering;
    }

    /** 获取 State。 */
    public String getState() {
        return state;
    }

    /** 获取 EnteredCount。 */
    public int getEnteredCount() {
        return enteredCount;
    }

    /** 获取 MissingCount。 */
    public int getMissingCount() {
        return missingCount;
    }

    /** 获取 LastSubmissionId。 */
    public String getLastSubmissionId() {
        return lastSubmissionId;
    }
}
