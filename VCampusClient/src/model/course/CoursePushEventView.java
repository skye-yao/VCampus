package model.course;

import java.time.Duration;
import java.time.Instant;

/** 客户端接收的课程候补推送事件。 */
public final class CoursePushEventView {
    private final String eventId;
    private final CoursePushEventType eventType;
    private final CourseTermView term;
    private final long offeringId;
    private final Instant occurredAt;
    private final Instant expiresAt;
    private final String message;

    /** 创建推送事件视图，并解析事件和到期 UTC 时刻。 */
    public CoursePushEventView(String eventId, CoursePushEventType eventType,
            CourseTermView term, long offeringId, String occurredAtUtc,
            String expiresAtUtc, String message) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.term = term;
        this.offeringId = offeringId;
        this.occurredAt = Instant.parse(occurredAtUtc);
        this.expiresAt = expiresAtUtc == null || expiresAtUtc.isBlank()
                ? null : Instant.parse(expiresAtUtc);
        this.message = message;
    }

    public String getEventId() { return eventId; }
    public CoursePushEventType getEventType() { return eventType; }
    public CourseTermView getTerm() { return term; }
    public long getOfferingId() { return offeringId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public String getMessage() { return message; }

    /** 按给定时刻计算尚未到期的剩余时间。 */
    public Duration getRemainingTime(Instant now) {
        if (expiresAt == null || now == null) {
            return null;
        }
        Duration remaining = Duration.between(now, expiresAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }
}
