package model.course;

/** 课程候补流程通过推送通知客户端的事件类型。 */
public enum CoursePushEventType {
    WAITLIST_OFFERED,
    WAITLIST_AUTO_ENROLLED,
    WAITLIST_OFFER_EXPIRED,
    WAITLIST_OFFER_ABANDONED
}
