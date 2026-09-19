package dto.course;

/** 教务模块的 ScheduleEntryDTO 数据传输对象。 */
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

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 Term。 */
    public String getTerm() {
        return term;
    }

    /** 获取 CourseCode。 */
    public String getCourseCode() {
        return courseCode;
    }

    /** 获取 CourseName。 */
    public String getCourseName() {
        return courseName;
    }

    /** 获取 Teacher。 */
    public String getTeacher() {
        return teacher;
    }

    /** 获取 Location。 */
    public String getLocation() {
        return location;
    }

    /** 获取 DayOfWeek。 */
    public int getDayOfWeek() {
        return dayOfWeek;
    }

    /** 获取 StartPeriod。 */
    public int getStartPeriod() {
        return startPeriod;
    }

    /** 获取 PeriodCount。 */
    public int getPeriodCount() {
        return periodCount;
    }

    /** 获取 StartWeek。 */
    public int getStartWeek() {
        return startWeek;
    }

    /** 获取 EndWeek。 */
    public int getEndWeek() {
        return endWeek;
    }

    /** 获取 DisplayKind。 */
    public ScheduleDisplayKindDTO getDisplayKind() {
        return displayKind;
    }

    /** 获取 AdjustmentId。 */
    public String getAdjustmentId() {
        return adjustmentId;
    }

    /** 获取 OriginalScheduleText。 */
    public String getOriginalScheduleText() {
        return originalScheduleText;
    }

    /** 获取 AdjustedScheduleText。 */
    public String getAdjustedScheduleText() {
        return adjustedScheduleText;
    }

    /** 获取 AdjustmentReason。 */
    public String getAdjustmentReason() {
        return adjustmentReason;
    }
}
