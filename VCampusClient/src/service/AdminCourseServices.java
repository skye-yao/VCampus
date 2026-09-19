package service;

import java.util.Objects;

/** 管理员教务服务的可替换全局提供者，供 JavaFX 控制器取得当前实现。 */
public final class AdminCourseServices {
    private static volatile AdminCourseService current = new SocketAdminCourseService();

    private AdminCourseServices() {
    }

    /** 返回当前安装的管理员教务服务。 */
    public static AdminCourseService current() {
        return current;
    }

    /** 为预览或测试安装管理员教务服务实现。 */
    public static void install(AdminCourseService service) {
        current = Objects.requireNonNull(service, "Admin course service is required");
    }

    /** 恢复默认的 Socket 管理员教务服务。 */
    public static void resetToSocket() {
        current = new SocketAdminCourseService();
    }
}
