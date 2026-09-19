package service;

import java.util.Objects;

/** 教师课程服务的进程内单例入口，默认走 Socket，预览可替换为确定性 Mock。 */
public final class TeacherCourseServices {
    private static volatile TeacherCourseService current = new SocketTeacherCourseService();

    private TeacherCourseServices() {
    }

    /** 返回当前安装的教师教务服务。 */
    public static TeacherCourseService current() {
        return current;
    }

    /** 为预览或测试安装教师教务服务实现。 */
    public static void install(TeacherCourseService service) {
        current = Objects.requireNonNull(service, "Teacher course service is required");
    }

    /** 恢复默认的 Socket 教师教务服务。 */
    public static void resetToSocket() {
        current = new SocketTeacherCourseService();
    }
}
