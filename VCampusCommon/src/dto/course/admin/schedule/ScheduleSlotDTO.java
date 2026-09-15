package dto.course.admin.schedule;

public final class ScheduleSlotDTO {
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;

    public ScheduleSlotDTO(int dayOfWeek, int startPeriod, int endPeriod) {
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartPeriod() {
        return startPeriod;
    }

    public int getEndPeriod() {
        return endPeriod;
    }
}
