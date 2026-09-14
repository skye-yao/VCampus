package service;

import java.util.Objects;

/** 教师课程服务的进程内单例入口，默认走 Socket，预览可替换为确定性 Mock。 */
public final class TeacherCourseServices {
    private static volatile TeacherCourseService current = new SocketTeacherCourseService();

    private TeacherCourseServices() {
    }

    public static TeacherCourseService current() {
        return current;
    }

    public static void install(TeacherCourseService service) {
        current = Objects.requireNonNull(service, "Teacher course service is required");
    }

    public static void resetToSocket() {
        current = new SocketTeacherCourseService();
    }
}
