package dto.course.admin.schedule;

public final class ScheduleConflictDTO {
    private final String type;
    private final ScheduleConflictSeverityDTO severity;
    private final String subjectId;
    private final String relatedOfferingId;
    private final String offeringId;
    private final String offeringLabel;
    private final int week;
    /** 合并后的周次区间止；缺失 endWeek 的旧 journal JSON 反序列化为 0，渲染端按单周归一。 */
    private final int endWeek;
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;
    private final String message;

    /** 旧构造：不携带产生冲突的所属教学班，两个归属字段为 null。 */
    public ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, int week,
            int dayOfWeek, int startPeriod, int endPeriod, String message) {
        this(type, severity, subjectId, relatedOfferingId, null, null, week, dayOfWeek,
                startPeriod, endPeriod, message);
    }

    /** 单周构造：区间起止同为 {@code week}；缺失 endWeek 的旧 journal JSON 反序列化后也是单周。 */
    public ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, String offeringId, String offeringLabel,
            int week, int dayOfWeek, int startPeriod, int endPeriod, String message) {
        this(type, severity, subjectId, relatedOfferingId, offeringId, offeringLabel, week, week,
                dayOfWeek, startPeriod, endPeriod, message);
    }

    /** 周次区间构造：{@code week..endWeek} 连续覆盖；渲染端一律以 {@code max(week, endWeek)} 归一。 */
    public ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, String offeringId, String offeringLabel,
            int week, int endWeek, int dayOfWeek, int startPeriod, int endPeriod, String message) {
        this.type = type;
        this.severity = severity;
        this.subjectId = subjectId;
        this.relatedOfferingId = relatedOfferingId;
        this.offeringId = offeringId;
        this.offeringLabel = offeringLabel;
        this.week = week;
        this.endWeek = endWeek;
        this.dayOfWeek = dayOfWeek;
        this.startPeriod = startPeriod;
        this.endPeriod = endPeriod;
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public ScheduleConflictSeverityDTO getSeverity() {
        return severity;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getRelatedOfferingId() {
        return relatedOfferingId;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getOfferingLabel() {
        return offeringLabel;
    }

    public int getWeek() {
        return week;
    }

    public int getEndWeek() {
        return endWeek;
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

    public String getMessage() {
        return message;
    }
}
