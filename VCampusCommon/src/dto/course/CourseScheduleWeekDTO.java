package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 学生按教学日历某周查看自己课表的响应。
 *
 * <p>{@code week} 是实际查看的教学周；{@code dates} 是该周的教学日（可含周末），
 * {@code periods} 是一行一个 {@code (date, period)}，{@code entries} 是该周生效的课次。
 * 课表网格的行数由 {@code periods} 里出现过的节次决定，列数由 {@code dates} 决定——
 * 与教师端 {@code TeacherScheduleWeekDTO} 用同一套规则，两边都不硬编码。
 *
 * <p>{@code minWeek}/{@code maxWeek} 是该学期教学日历的教学周范围，{@code currentWeek} 可空：
 * null 表示今天不在本学期的有效教学周内，GUI 应禁用“回到本周”；此时 {@code week} 仍必须落在
 * {@code minWeek..maxWeek}。三者的含义与 {@code TeacherScheduleWeekDTO} 逐字一致。
 *
 * <p>三个列表在构造与反序列化两条路径上都做防御性不可变复制，null 视为空列表，
 * getter 返回不可修改视图。
 */
public final class CourseScheduleWeekDTO {
    private final int week;
    private final int minWeek;
    private final int maxWeek;
    private final Integer currentWeek;
    private final List<CourseCalendarDateDTO> dates;
    private final List<CoursePeriodDTO> periods;
    private final List<ScheduleEntryDTO> entries;

    /**
     * 不带周范围的旧构造：范围退化为“只有这一周”，{@code currentWeek} 为 null（GUI 因此禁用
     * “回到本周”）。只给不关心周导航的夹具与旧调用点使用；服务端一律走下面的完整构造。
     */
    public CourseScheduleWeekDTO(int week, List<CourseCalendarDateDTO> dates,
            List<CoursePeriodDTO> periods, List<ScheduleEntryDTO> entries) {
        this(week, week, week, null, dates, periods, entries);
    }

    public CourseScheduleWeekDTO(int week, int minWeek, int maxWeek, Integer currentWeek,
            List<CourseCalendarDateDTO> dates, List<CoursePeriodDTO> periods,
            List<ScheduleEntryDTO> entries) {
        this.week = week;
        this.minWeek = minWeek;
        this.maxWeek = maxWeek;
        this.currentWeek = currentWeek;
        this.dates = immutableCopy(dates);
        this.periods = immutableCopy(periods);
        this.entries = immutableCopy(entries);
    }

    public int getWeek() {
        return week;
    }

    public int getMinWeek() {
        return minWeek;
    }

    public int getMaxWeek() {
        return maxWeek;
    }

    public Integer getCurrentWeek() {
        return currentWeek;
    }

    public List<CourseCalendarDateDTO> getDates() {
        return unmodifiable(dates);
    }

    public List<CoursePeriodDTO> getPeriods() {
        return unmodifiable(periods);
    }

    public List<ScheduleEntryDTO> getEntries() {
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
