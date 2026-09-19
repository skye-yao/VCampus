package dto.course.teacher;

/**
 * 教师调课申请的一个目标课次。
 *
 * <p>{@code originalOccurrenceId} 是数据库 BIGINT 的十进制字符串；{@code targetDate} 是目标教学日的
 * ISO 本地日期（如 {@code 2026-10-08}），不是 UTC 时刻。它允许跨教学周，但必须仍属于原课次所在的
 * 教学日历。历史目标没有明确日期时为 null，由服务端退回 {@code original_week_no + new_weekday}
 * 的旧推导逻辑，绝不猜测一个日期。
 */
public final class TeacherAdjustmentTargetInputDTO {
    private final String originalOccurrenceId;
    private final String targetDate;

    public TeacherAdjustmentTargetInputDTO(String originalOccurrenceId, String targetDate) {
        this.originalOccurrenceId = originalOccurrenceId;
        this.targetDate = targetDate;
    }

    /** 获取 OriginalOccurrenceId。 */
    public String getOriginalOccurrenceId() {
        return originalOccurrenceId;
    }

    /** 获取 TargetDate。 */
    public String getTargetDate() {
        return targetDate;
    }
}
