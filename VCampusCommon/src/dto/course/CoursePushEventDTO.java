package dto.course;

/** 教务模块的 CoursePushEventDTO 数据传输对象。 */
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

    /** 获取 EventId。 */
    public String getEventId() {
        return eventId;
    }

    /** 获取 EventType。 */
    public CoursePushEventTypeDTO getEventType() {
        return eventType;
    }

    /** 获取 Term。 */
    public CourseTermDTO getTerm() {
        return term;
    }

    /** 获取 OfferingId。 */
    public String getOfferingId() {
        return offeringId;
    }

    /** 获取 OccurredAt。 */
    public String getOccurredAt() {
        return occurredAt;
    }

    /** 获取 ExpiresAt。 */
    public String getExpiresAt() {
        return expiresAt;
    }

    /** 获取 Message。 */
    public String getMessage() {
        return message;
    }
}
