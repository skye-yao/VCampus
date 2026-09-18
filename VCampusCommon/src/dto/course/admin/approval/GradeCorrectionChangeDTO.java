package dto.course.admin.approval;

/**
 * 一次更正里<b>真的改变了</b>的一名学生：他在基础批次里的那一项与在本次批次里的那一项。
 *
 * <p>两个快照都由服务端从 {@code grade_submission_item} 读取，绝不取当前可变草稿，因此管理员看到的
 * 旧值是历史事实，而不是“教师现在正在改的那一版”。任一端的分数都可能是 {@code null}（未录入或该
 * 组成未启用），界面按占位符显示，不折成 0。
 *
 * <p>两个方向的不同在 getter 上说明：{@link #isAdded()} 是本次补录进来的学生（基础批次没有他），
 * {@link #isRemoved()} 是两次提交之间退出名单、本次批次不再收录的学生。
 */
public final class GradeCorrectionChangeDTO {
    private final GradeSubmissionItemDTO previous;
    private final GradeSubmissionItemDTO current;

    public GradeCorrectionChangeDTO(GradeSubmissionItemDTO previous,
            GradeSubmissionItemDTO current) {
        this.previous = previous;
        this.current = current;
    }

    /** 基础批次里的该项；本次补录的学生为 {@code null}。 */
    public GradeSubmissionItemDTO getPrevious() {
        return previous;
    }

    /** 本次批次里的该项；本次不再收录的学生为 {@code null}。 */
    public GradeSubmissionItemDTO getCurrent() {
        return current;
    }

    /** 学生学号：优先取本次，补录方向反过来取基础批次；两端都缺失时为 {@code null}。 */
    public String getStudentUid() {
        GradeSubmissionItemDTO item = current != null ? current : previous;
        return item == null ? null : item.getStudentUid();
    }

    /** 学生姓名：与 {@link #getStudentUid()} 同一取舍。 */
    public String getStudentName() {
        GradeSubmissionItemDTO item = current != null ? current : previous;
        return item == null ? null : item.getStudentName();
    }

    /** 基础批次没有这名学生：本次更正把他补录进来了。 */
    public boolean isAdded() {
        return previous == null && current != null;
    }

    /** 本次批次不再收录这名学生（两次提交之间退课）。 */
    public boolean isRemoved() {
        return previous != null && current == null;
    }
}
