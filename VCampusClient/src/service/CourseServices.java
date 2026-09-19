package service;

import java.util.Objects;

/** 学生教务服务的可替换全局提供者，供 JavaFX 控制器取得当前实现。 */
public final class CourseServices {
    private static volatile CourseService current = new SocketCourseService();

    private CourseServices() {
    }

    /** 返回当前安装的学生教务服务。 */
    public static CourseService current() {
        return current;
    }

    /** 为预览或测试安装学生教务服务实现。 */
    public static void install(CourseService service) {
        current = Objects.requireNonNull(service, "Course service is required");
    }

    /** 恢复默认的 Socket 学生教务服务。 */
    public static void resetToSocket() {
        current = new SocketCourseService();
    }
}
