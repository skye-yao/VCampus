package dto.course;

/**
 * 教学日历里的一个具体日期。
 *
 * <p>{@code date} 是所属教学日历时区的 ISO 本地日期字符串（{@code 2026-09-14}），不是 UTC 时刻；
 * {@code teachingWeekday} 是日历表的 {@code teaching_weekday}，1..7 表示周一..周日，不能由 UTC
 * 星期直接推算。{@code teachingDay} 表示该日是否为有效教学日（假期或非教学周末为 false）。
 */
public final class CourseCalendarDateDTO {
    private final String date;
    private final int week;
    private final int teachingWeekday;
    private final boolean teachingDay;

    public CourseCalendarDateDTO(String date, int week, int teachingWeekday, boolean teachingDay) {
        this.date = date;
        this.week = week;
        this.teachingWeekday = teachingWeekday;
        this.teachingDay = teachingDay;
    }

    /** 获取 Date。 */
    public String getDate() {
        return date;
    }

    /** 获取 Week。 */
    public int getWeek() {
        return week;
    }

    /** 获取 TeachingWeekday。 */
    public int getTeachingWeekday() {
        return teachingWeekday;
    }

    /** 判断 TeachingDay 是否成立。 */
    public boolean isTeachingDay() {
        return teachingDay;
    }
}
