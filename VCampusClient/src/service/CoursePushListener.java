package service;

import model.course.CoursePushEventView;

/** 接收课程状态变化推送的回调契约。 */
public interface CoursePushListener {
    /** 处理已转换为 View 的课程推送事件。 */
    void onCourseEvent(CoursePushEventView event);

    /**
     * 成功建立新的连接代际后触发一次，用于权威状态对账。
     */
    default void onReconnect() {
    }
}
