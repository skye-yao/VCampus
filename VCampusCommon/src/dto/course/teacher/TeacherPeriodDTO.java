package dto.course.teacher;

/**
 * 教学日历中某一天的一个节次。
 *
 * <p>一行对应一个 {@code (date, period)}；{@code startTime}/{@code endTime} 是教学日历时区的
 * 本地墙上时钟 ISO 字符串（如 {@code 08:00:00}），不是 UTC 时刻。节次编号不硬编码上限，
 * 覆盖到现有第 13 节。日期可能落在周末。
 */
public final class TeacherPeriodDTO {
    private final String date;
    private final int period;
    private final String startTime;
    private final String endTime;

    public TeacherPeriodDTO(String date, int period, String startTime, String endTime) {
        this.date = date;
        this.period = period;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getDate() {
        return date;
    }

    public int getPeriod() {
        return period;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getEndTime() {
        return endTime;
    }
}
