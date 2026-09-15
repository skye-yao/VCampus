package dto.course.teacher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dto.course.admin.schedule.ScheduleResourceDTO;

/**
 * 某个课次可以选择的调课目标（响应键 {@code options}）。
 *
 * <p>只包含该课次所属教学日历的日期、节次和教室资源：日期给出真实教学日，节次来自当日模板，
 * 教师据此填写目标日期与节次，服务端仍会独立校验学期、教学日与冲突。
 */
public final class TeacherAdjustmentOptionsDTO {
    private final String calendarId;
    private final String timezone;
    private final List<TeacherCalendarDateDTO> dates;
    private final List<TeacherPeriodDTO> periods;
    private final List<ScheduleResourceDTO> classrooms;

    public TeacherAdjustmentOptionsDTO(String calendarId, String timezone,
            List<TeacherCalendarDateDTO> dates, List<TeacherPeriodDTO> periods,
            List<ScheduleResourceDTO> classrooms) {
        this.calendarId = calendarId;
        this.timezone = timezone;
        this.dates = immutable(dates);
        this.periods = immutable(periods);
        this.classrooms = immutable(classrooms);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    public String getCalendarId() {
        return calendarId;
    }

    public String getTimezone() {
        return timezone;
    }

    public List<TeacherCalendarDateDTO> getDates() {
        return unmodifiable(dates);
    }

    public List<TeacherPeriodDTO> getPeriods() {
        return unmodifiable(periods);
    }

    public List<ScheduleResourceDTO> getClassrooms() {
        return unmodifiable(classrooms);
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
