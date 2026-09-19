package dto.course.teacher;

import dto.course.ScheduleDisplayKindDTO;

/**
 * 教师周课表里的一个具体课次。
 *
 * <p>与 {@link dto.course.ScheduleEntryDTO} 的学期级课差不同，这里定位到某个真实日期：
 * {@code localDate} 是教学日历时区的 ISO <b>本地</b>日期（绝不是 UTC 时刻），{@code dayOfWeek}
 * 是日历的 {@code teaching_weekday}（1..7，周一..周日），不能由 UTC 星期推算。跨周调课时，
 * 原周返回 {@link ScheduleDisplayKindDTO#ADJUSTED_ORIGINAL} 的灰色提示，目标周返回
 * {@link ScheduleDisplayKindDTO#ADJUSTED_TARGET}，两者靠各自的 {@code localDate}+{@code week} 区分。
 *
 * <p>{@code adjustmentId} 仅在课次来自调课时非空；其余调课文本字段可空。复用既有的
 * {@link ScheduleDisplayKindDTO}，不创建同义枚举。
 */
public final class TeacherScheduleEntryDTO {
    private final String occurrenceId;
    private final String offeringId;
    private final String courseCode;
    private final String courseName;
    private final String teacher;
    private final String location;
    private final String localDate;
    private final int week;
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;
    private final ScheduleDisplayKindDTO displayKind;
    private final String adjustmentId;
    private final String originalScheduleText;
    private final String adjustedScheduleText;
    private final String adjustmentReason;
    private final boolean canRequestAdjustment;

    public TeacherScheduleEntryDTO(String occurrenceId, String offeringId, String courseCode,
            String courseName, String teacher, String location, String localDate, int week,
            int dayOfWeek, int startPeriod, int endPeriod, ScheduleDisplayKindDTO displayKind,
            String adjustmentId, String originalScheduleText, String adjustedScheduleText,
            String adjustmentReason, boolean canRequestAdjustment) {
        this.occurrenceId = occurrenceId;
        this.offeringId = offeringId;
        this.courseCode = courseCode;
        this.courseName = courseName;
        this.teacher = teacher;
        this.location = location;
        this.localDate = localDate;
        this.week = week;
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.displayKind = displayKind;
        this.adjustmentId = adjustmentId;
        this.originalScheduleText = originalScheduleText;
        this.adjustedScheduleText = adjustedScheduleText;
        this.adjustmentReason = adjustmentReason;
        this.canRequestAdjustment = canRequestAdjustment;
    }

    /** 获取 OccurrenceId。 */
    public String getOccurrenceId() {
        return occurrenceId;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
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

    /** 获取 LocalDate。 */
    public String getLocalDate() {
        return localDate;
    }

    /** 获取 Week。 */
    public int getWeek() {
        return week;
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

    /** 判断 CanRequestAdjustment 是否成立。 */
    public boolean isCanRequestAdjustment() {
        return canRequestAdjustment;
    }
}
