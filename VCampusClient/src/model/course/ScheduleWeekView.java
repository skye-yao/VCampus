package model.course;

import dto.course.CourseCalendarDateDTO;
import dto.course.CoursePeriodDTO;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 学生端某教学周课表的视图：与 {@code dto.course.CourseScheduleWeekDTO} 同形，但 {@code entries}
 * 换成界面直接消费的 {@link ScheduleEntryView}。
 *
 * <p>{@code dates} 与 {@code periods} 直接持有 Common 的 DTO、不另建视图类型——教师端
 * {@code TeacherScheduleController} 就是直接消费 {@code TeacherPeriodDTO}/{@code TeacherCalendarDateDTO}
 * 的，学生端照此。网格的行来自 {@code periods} 里出现过的节次、列来自 {@code dates}，
 * 因此两端的行数/列数同源，客户端不再硬编码。
 *
 * <p>{@code minWeek}/{@code maxWeek} 是服务端教学日历的教学周范围，{@code currentWeek} 可空：
 * null 表示今天不在本学期的有效教学周内，此时“回到本周”不可用。与教师端
 * {@code TeacherScheduleWeekDTO} 的三个同名字段同义，周次控件因此两端同构。
 *
 * <p>字段在构造路径上做防御性不可变复制，null 视为空列表，getter 返回不可修改视图。
 */
public final class ScheduleWeekView {
    private final int week;
    private final int minWeek;
    private final int maxWeek;
    private final Integer currentWeek;
    private final List<CourseCalendarDateDTO> dates;
    private final List<CoursePeriodDTO> periods;
    private final List<ScheduleEntryView> entries;

    /**
     * 不带周范围的旧构造：范围退化为“只有这一周”、{@code currentWeek} 为 null（“回到本周”因此
     * 不可用）。只给不关心周导航的夹具使用；真服务与假服务一律走下面的完整构造。
     */
    public ScheduleWeekView(int week, List<CourseCalendarDateDTO> dates,
            List<CoursePeriodDTO> periods, List<ScheduleEntryView> entries) {
        this(week, week, week, null, dates, periods, entries);
    }

    public ScheduleWeekView(int week, int minWeek, int maxWeek, Integer currentWeek,
            List<CourseCalendarDateDTO> dates, List<CoursePeriodDTO> periods,
            List<ScheduleEntryView> entries) {
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
        return dates;
    }

    public List<CoursePeriodDTO> getPeriods() {
        return periods;
    }

    public List<ScheduleEntryView> getEntries() {
        return entries;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
