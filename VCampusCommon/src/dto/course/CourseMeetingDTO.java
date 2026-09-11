package dto.course;

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

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartPeriod() {
        return startPeriod;
    }

    public int getEndPeriod() {
        return endPeriod;
    }

    public int getStartWeek() {
        return startWeek;
    }

    public int getEndWeek() {
        return endWeek;
    }

    public String getWeekPattern() {
        return weekPattern;
    }

    public String getLocation() {
        return location;
    }

    public String getStartsAtUtc() {
        return startsAtUtc;
    }

    public String getEndsAtUtc() {
        return endsAtUtc;
    }
}
