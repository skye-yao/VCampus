package dto.course.admin.approval;

/**
 * 调课申请的一个目标课次：原安排快照加明确的目标教学日。
 *
 * <p>{@code originalOccurrenceId} 是数据库 BIGINT 的十进制字符串；{@code targetDate} 是目标教学日的
 * ISO 本地日期（如 {@code 2026-10-08}），不是 UTC 时刻。V006 之前的旧数据没有明确日期，
 * 反序列化为 null，由服务端退回 {@code original_week_no + new_weekday} 的旧推导逻辑，
 * 绝不猜测一个日期。
 */
public final class AdjustmentTargetDTO {
    private final String originalOccurrenceId;
    private final int week;
    private final String originalStartAt;
    private final String originalEndAt;
    private final String originalTeacher;
    private final String originalAssistant;
    private final String originalClassroom;
    private final String targetDate;

    /** 新增目标日期的构造方法；历史兼容调用继续使用下面的旧重载。 */
    public AdjustmentTargetDTO(String originalOccurrenceId, int week,
            String originalStartAt, String originalEndAt, String originalTeacher,
            String originalAssistant, String originalClassroom, String targetDate) {
        this.originalOccurrenceId = originalOccurrenceId;
        this.week = week;
        this.originalStartAt = originalStartAt;
        this.originalEndAt = originalEndAt;
        this.originalTeacher = originalTeacher;
        this.originalAssistant = originalAssistant;
        this.originalClassroom = originalClassroom;
        this.targetDate = targetDate;
    }

    /**
     * 兼容重载：V006 之前的旧数据没有明确目标日期，默认 null。
     * 保留它可以让既有调用点与测试不改语义地继续工作。
     */
    public AdjustmentTargetDTO(String originalOccurrenceId, int week,
            String originalStartAt, String originalEndAt, String originalTeacher,
            String originalAssistant, String originalClassroom) {
        this(originalOccurrenceId, week, originalStartAt, originalEndAt, originalTeacher,
                originalAssistant, originalClassroom, null);
    }

    /** 获取 OriginalOccurrenceId。 */
    public String getOriginalOccurrenceId() {
        return originalOccurrenceId;
    }

    /** 获取 Week。 */
    public int getWeek() {
        return week;
    }

    /** 获取 OriginalStartAt。 */
    public String getOriginalStartAt() {
        return originalStartAt;
    }

    /** 获取 OriginalEndAt。 */
    public String getOriginalEndAt() {
        return originalEndAt;
    }

    /** 获取 OriginalTeacher。 */
    public String getOriginalTeacher() {
        return originalTeacher;
    }

    /** 获取 OriginalAssistant。 */
    public String getOriginalAssistant() {
        return originalAssistant;
    }

    /** 获取 OriginalClassroom。 */
    public String getOriginalClassroom() {
        return originalClassroom;
    }

    /** 目标教学日的 ISO 本地日期；历史目标为 null。 */
    public String getTargetDate() {
        return targetDate;
    }
}
