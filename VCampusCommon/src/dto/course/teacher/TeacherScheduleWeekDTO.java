package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 教师按教学日历某周查看自己课表的响应。
 *
 * <p>{@code calendarId} 是教学日历的 BIGINT 十进制字符串；{@code timezone} 是日历的 IANA 名称，
 * 显示日期与时刻都由它决定，不能用 UTC 推算。{@code dates} 覆盖周一..周日（含周末与第 13 节），
 * {@code periods} 是一行一个 {@code (date, period)}。
 *
 * <p>{@code currentWeek} 可空：null 表示今天不在本学期的有效教学周内，GUI 应禁用“回到本周”；
 * 此时 {@code week} 仍必须落在 {@code minWeek..maxWeek}。三个列表在构造与反序列化两条路径上都做
 * 防御性不可变复制，null 视为空列表，getter 返回不可修改视图。
 */
public final class TeacherScheduleWeekDTO {
    private final String calendarId;
    private final String timezone;
    private final int week;
    private final int minWeek;
    private final int maxWeek;
    private final Integer currentWeek;
    private final List<TeacherCalendarDateDTO> dates;
    private final List<TeacherPeriodDTO> periods;
    private final List<TeacherScheduleEntryDTO> entries;

    public TeacherScheduleWeekDTO(String calendarId, String timezone, int week, int minWeek,
            int maxWeek, Integer currentWeek, List<TeacherCalendarDateDTO> dates,
            List<TeacherPeriodDTO> periods, List<TeacherScheduleEntryDTO> entries) {
        this.calendarId = calendarId;
        this.timezone = timezone;
        this.week = week;
        this.minWeek = minWeek;
        this.maxWeek = maxWeek;
        this.currentWeek = currentWeek;
        this.dates = immutableCopy(dates);
        this.periods = immutableCopy(periods);
        this.entries = immutableCopy(entries);
    }

    /** 获取 CalendarId。 */
    public String getCalendarId() {
        return calendarId;
    }

    /** 获取 Timezone。 */
    public String getTimezone() {
        return timezone;
    }

    /** 获取 Week。 */
    public int getWeek() {
        return week;
    }

    /** 获取 MinWeek。 */
    public int getMinWeek() {
        return minWeek;
    }

    /** 获取 MaxWeek。 */
    public int getMaxWeek() {
        return maxWeek;
    }

    /** 获取 CurrentWeek。 */
    public Integer getCurrentWeek() {
        return currentWeek;
    }

    /** 获取 Dates。 */
    public List<TeacherCalendarDateDTO> getDates() {
        return unmodifiable(dates);
    }

    /** 获取 Periods。 */
    public List<TeacherPeriodDTO> getPeriods() {
        return unmodifiable(periods);
    }

    /** 获取 Entries。 */
    public List<TeacherScheduleEntryDTO> getEntries() {
        return unmodifiable(entries);
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
