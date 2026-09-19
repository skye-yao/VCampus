package dto.course;

/** 教务模块的 CourseMeetingDTO 数据传输对象。 */
public final class CourseMeetingDTO {
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;
    private final int startWeek;
    private final int endWeek;
    private final String weekPattern;
    private final String location;
    private final String startsAtUtc;
    private final String endsAtUtc;

    public CourseMeetingDTO(int dayOfWeek, int startPeriod, int endPeriod,
            int startWeek, int endWeek, String weekPattern, String location,
            String startsAtUtc, String endsAtUtc) {
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.weekPattern = weekPattern;
        this.location = location;
        this.startsAtUtc = startsAtUtc;
        this.endsAtUtc = endsAtUtc;
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

    /** 获取 StartWeek。 */
    public int getStartWeek() {
        return startWeek;
    }

    /** 获取 EndWeek。 */
    public int getEndWeek() {
        return endWeek;
    }

    /** 获取 WeekPattern。 */
    public String getWeekPattern() {
        return weekPattern;
    }

    /** 获取 Location。 */
    public String getLocation() {
        return location;
    }

    /** 获取 StartsAtUtc。 */
    public String getStartsAtUtc() {
        return startsAtUtc;
    }

    /** 获取 EndsAtUtc。 */
    public String getEndsAtUtc() {
        return endsAtUtc;
    }
}
