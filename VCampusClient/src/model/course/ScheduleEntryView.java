package model.course;

/** 课表网格中一条教学班课次及其调课信息的展示视图。 */
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
    private final ScheduleDisplayKind displayKind;
    private final String adjustmentId;
    private final String originalScheduleText;
    private final String adjustedScheduleText;
    private final String adjustmentReason;

    /** 创建没有调课信息的普通课表条目。 */
    public ScheduleEntryView(long offeringId, String term, String courseCode,
            String courseName, String teacher, String location, int dayOfWeek,
            int startPeriod, int periodCount, int startWeek, int endWeek) {
        this.offeringId = offeringId;
        this.term = term;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.teacher = teacher;
        this.location = location;
        this.dayOfWeek = dayOfWeek; // 星期几
        this.startPeriod = startPeriod;
        // 注意，这里面的课程安排是按照**开始节次** + **持续节次** 而非结束节次，
        // 所以结束节次需要计算才能得到，与底层数据库存储的绝对开始结束时间
        // （结束时间是结束节次的结尾，开始节次的开头）也不同
        this.periodCount = periodCount;
        this.startWeek = startWeek;
        this.endWeek = endWeek;
        this.displayKind = ScheduleDisplayKind.NORMAL;
        this.adjustmentId = null;
        this.originalScheduleText = null;
        this.adjustedScheduleText = null;
        this.adjustmentReason = null;
    }

    /** 创建可携带调课前后文本的课表条目。 */
    public ScheduleEntryView(long offeringId, String term, String courseCode,
            String courseName, String teacher, String location, int dayOfWeek,
            int startPeriod, int periodCount, int startWeek, int endWeek,
            ScheduleDisplayKind displayKind, String adjustmentId,
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

    public ScheduleDisplayKind getDisplayKind() {
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

    /** 判断该课次是否在指定教学周生效。 */
    public boolean isActiveInWeek(int week) {
        return week >= startWeek && week <= endWeek;
    }
}
