package dto.course.admin.schedule;

/** 教务模块的 ScheduleSlotDTO 数据传输对象。 */
public final class ScheduleSlotDTO {
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;

    public ScheduleSlotDTO(int dayOfWeek, int startPeriod, int endPeriod) {
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
    }

    /** 获取 DayOfWeek。 */
    public int getDayOfWeek() {
        return dayOfWeek;
    }

    /** 获取 StartPeriod。 */
    public int getStartPeriod() {
        return startPeriod;
    }

    /** 获取 EndPeriod。 */
    public int getEndPeriod() {
        return endPeriod;
    }
}
