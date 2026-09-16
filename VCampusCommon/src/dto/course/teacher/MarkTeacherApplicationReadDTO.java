package dto.course.teacher;

/**
 * 标记一条申请结果为已读的写请求体（位于 {@code data.request}）。
 *
 * <p>它是 compare-and-set，不是无条件的「设成已读」：{@code expectedStateKey} 是客户端**看到**的那
 * 个结果的状态键（{@link TeacherApplicationDTO#getStateKey()}），服务端把它和库里的当前状态键比对，
 * 不一致就什么都不写并返回冲突。这正是设计 §10 的「过期读取确认不能把更新后的结果标成已读」——
 * 教师打开了 PENDING 的页面，管理员随后驳回，此时那次「已读」确认不能把 REJECTED 的结果吞掉。
 *
 * <p>类型与 ID 一起构成主键（{@code teacher_application_read} 的主键是
 * {@code (teacher_uid, application_type, application_id)}），所以两张事实表里数字相同的两个 ID
 * 是两条互不影响的回执，绝不互相覆盖。
 */
public final class MarkTeacherApplicationReadDTO {
    private final String type;
    private final String id;
    private final String expectedStateKey;

    public MarkTeacherApplicationReadDTO(String type, String id, String expectedStateKey) {
        this.type = type;
        this.id = id;
        this.expectedStateKey = expectedStateKey;
    }

    /** {@code SCHEDULE_ADJUSTMENT} 或 {@code GRADE_SUBMISSION}；不允许为空。 */
    public String getType() {
        return type;
    }

    /** 申请 ID（十进制字符串）。 */
    public String getId() {
        return id;
    }

    /** 客户端看到的结果状态键；与库中当前键不一致时服务端拒绝写入。 */
    public String getExpectedStateKey() {
        return expectedStateKey;
    }
}
