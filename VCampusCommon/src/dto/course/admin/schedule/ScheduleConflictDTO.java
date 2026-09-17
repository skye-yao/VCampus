package dto.course.admin.schedule;

public final class ScheduleConflictDTO {
    private final String type;
    private final ScheduleConflictSeverityDTO severity;
    private final String subjectId;
    private final String relatedOfferingId;
    private final String offeringId;
    private final String offeringLabel;
    private final int week;
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

    public ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, String offeringId, String offeringLabel,
            int week, int dayOfWeek, int startPeriod, int endPeriod, String message) {
        this.type = type;
        this.severity = severity;
        this.subjectId = subjectId;
        this.relatedOfferingId = relatedOfferingId;
        this.offeringId = offeringId;
        this.offeringLabel = offeringLabel;
        this.week = week;
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
