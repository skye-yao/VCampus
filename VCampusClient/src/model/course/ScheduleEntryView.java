package model.course;

public final class ScheduleEntryView {
    private final long offeringId;
    private final String term;
    private final String courseCode;
    private final String courseName;
    private final String teacher;
    private final String location;
    private final int dayOfWeek;
    private final int startPeriod;
    private final int periodCount;
    private final int startWeek;
    private final int endWeek;

    public ScheduleEntryView(long offeringId, String term, String courseCode,
            String courseName, String teacher, String location, int dayOfWeek,
            int startPeriod, int periodCount, int startWeek, int endWeek) {
        this.offeringId = offeringId;
        this.term = term;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.teacher = teacher;
        this.location = location;
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.periodCount = periodCount;
        this.startWeek = startWeek;
        this.endWeek = endWeek;
    }

    public long getOfferingId() {
        return offeringId;
    }

    public String getTerm() {
        return term;
    }

    public String getCourseCode() {
        return courseCode;
    }

    public String getCourseName() {
        return courseName;
    }

    public String getTeacher() {
        return teacher;
    }

    public String getLocation() {
        return location;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public int getStartPeriod() {
        return startPeriod;
    }

    public int getPeriodCount() {
        return periodCount;
    }

    public int getStartWeek() {
        return startWeek;
    }

    public int getEndWeek() {
        return endWeek;
    }

    public boolean isActiveInWeek(int week) {
        return week >= startWeek && week <= endWeek;
    }
}
