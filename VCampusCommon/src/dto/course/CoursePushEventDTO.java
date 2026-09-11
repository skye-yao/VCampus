package dto.course;

public final class CoursePushEventDTO {
    private final String eventId;
    private final CoursePushEventTypeDTO eventType;
    private final CourseTermDTO term;
    private final String offeringId;
    private final String occurredAt;
    private final String expiresAt;
    private final String message;

    public CoursePushEventDTO(String eventId, CoursePushEventTypeDTO eventType,
            CourseTermDTO term, String offeringId, String occurredAt,
            String expiresAt, String message) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.term = term;
        this.offeringId = offeringId;
        this.occurredAt = occurredAt;
        this.expiresAt = expiresAt;
        this.message = message;
    }

    public String getEventId() {
        return eventId;
    }

    public CoursePushEventTypeDTO getEventType() {
        return eventType;
    }

    public CourseTermDTO getTerm() {
        return term;
    }

    public String getOfferingId() {
        return offeringId;
    }

    public String getOccurredAt() {
        return occurredAt;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getMessage() {
        return message;
    }
}
