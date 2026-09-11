package service;

import model.course.CoursePushEventView;

public interface CoursePushListener {
    void onCourseEvent(CoursePushEventView event);
}
