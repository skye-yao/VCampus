package service;

import java.util.Objects;

public final class CourseServices {
    private static volatile CourseService current = new SocketCourseService();

    private CourseServices() {
    }

    public static CourseService current() {
        return current;
    }

    public static void install(CourseService service) {
        current = Objects.requireNonNull(service, "Course service is required");
    }

    public static void resetToSocket() {
        current = new SocketCourseService();
    }
}
