package service;

import java.util.Objects;

public final class AdminCourseServices {
    private static volatile AdminCourseService current = new SocketAdminCourseService();

    private AdminCourseServices() {
    }

    public static AdminCourseService current() {
        return current;
    }

    public static void install(AdminCourseService service) {
        current = Objects.requireNonNull(service, "Admin course service is required");
    }

    public static void resetToSocket() {
        current = new SocketAdminCourseService();
    }
}
