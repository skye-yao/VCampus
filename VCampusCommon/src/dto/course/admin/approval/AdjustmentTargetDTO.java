package dto.course.admin.approval;

public final class AdjustmentTargetDTO {
    private final String originalOccurrenceId;
    private final int week;
    private final String originalStartAt;
    private final String originalEndAt;
    private final String originalTeacher;
    private final String originalAssistant;
    private final String originalClassroom;

    public AdjustmentTargetDTO(String originalOccurrenceId, int week,
            String originalStartAt, String originalEndAt, String originalTeacher,
            String originalAssistant, String originalClassroom) {
        this.originalOccurrenceId = originalOccurrenceId;
        this.week = week;
        this.originalStartAt = originalStartAt;
        this.originalEndAt = originalEndAt;
        this.originalTeacher = originalTeacher;
        this.originalAssistant = originalAssistant;
        this.originalClassroom = originalClassroom;
    }

    public String getOriginalOccurrenceId() {
        return originalOccurrenceId;
    }

    public int getWeek() {
        return week;
    }

    public String getOriginalStartAt() {
        return originalStartAt;
    }

    public String getOriginalEndAt() {
        return originalEndAt;
    }

    public String getOriginalTeacher() {
        return originalTeacher;
    }

    public String getOriginalAssistant() {
        return originalAssistant;
    }

    public String getOriginalClassroom() {
        return originalClassroom;
    }
}
