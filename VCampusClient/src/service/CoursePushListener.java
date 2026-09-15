package service;

import model.course.CoursePushEventView;

public interface CoursePushListener {
    void onCourseEvent(CoursePushEventView event);

    /**
     * 成功建立新的连接代际后触发一次，用于权威状态对账。
     */
    default void onReconnect() {
    }
}
