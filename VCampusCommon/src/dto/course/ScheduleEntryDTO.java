package dto.course;

public final class ScheduleEntryDTO {
    private final String offeringId;
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
    private final ScheduleDisplayKindDTO displayKind;
    private final String adjustmentId;
    private final String originalScheduleText;
    private final String adjustedScheduleText;
    private final String adjustmentReason;

    public ScheduleEntryDTO(String offeringId, String term, String courseCode,
            String courseName, String teacher, String location, int dayOfWeek,
            int startPeriod, int periodCount, int startWeek, int endWeek) {
        this(offeringId, term, courseCode, courseName, teacher, location, dayOfWeek,
                startPeriod, periodCount, startWeek, endWeek,
                ScheduleDisplayKindDTO.NORMAL, null, null, null, null);
    }

    public ScheduleEntryDTO(String offeringId, String term, String courseCode,
            String courseName, String teacher, String location, int dayOfWeek,
            int startPeriod, int periodCount, int startWeek, int endWeek,
            ScheduleDisplayKindDTO displayKind, String adjustmentId,
            String originalScheduleText, String adjustedScheduleText,
            String adjustmentReason) {
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
        this.displayKind = displayKind;
        this.adjustmentId = adjustmentId;
        this.originalScheduleText = originalScheduleText;
        this.adjustedScheduleText = adjustedScheduleText;
        this.adjustmentReason = adjustmentReason;
    }

    public String getOfferingId() {
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

    public ScheduleDisplayKindDTO getDisplayKind() {
        return displayKind;
    }

    public String getAdjustmentId() {
        return adjustmentId;
    }

    public String getOriginalScheduleText() {
        return originalScheduleText;
    }

    public String getAdjustedScheduleText() {
        return adjustedScheduleText;
    }

    public String getAdjustmentReason() {
        return adjustmentReason;
    }
}
