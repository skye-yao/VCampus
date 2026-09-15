package dto.course.admin.schedule;

public final class ScheduleConflictDTO {
    private final String type;
    private final ScheduleConflictSeverityDTO severity;
    private final String subjectId;
    private final String relatedOfferingId;
    private final int week;
    private final int dayOfWeek;
    private final int startPeriod;
    private final int endPeriod;
    private final String message;

    public ScheduleConflictDTO(String type, ScheduleConflictSeverityDTO severity,
            String subjectId, String relatedOfferingId, int week,
            int dayOfWeek, int startPeriod, int endPeriod, String message) {
        this.type = type;
        this.severity = severity;
        this.subjectId = subjectId;
        this.relatedOfferingId = relatedOfferingId;
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
