package controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import entity.AdminPermission;
import session.ClientSession;

/**
 * 无 JavaFX 工具包依赖的角色路由测试：不打开 Stage，仅通过注入的路由/提示回调断言。
 */
public final class MainControllerRoleRoutingTest {
    private static final String ADMIN_VIEW = "/resources/fxml/AdminCourseManagementView.fxml";
    private static final String STUDENT_VIEW = "/resources/fxml/CourseManagementView.fxml";
    private static final String TEACHER_VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String COURSE_NOTICE = "系统提示|当前身份无法进入教务模块";

    public static void main(String[] args) throws Exception {
        try {
            testAdministratorRoutesToAdministratorShell();
            testAdministratorWithoutPermissionIsDenied();
            testStudentRoutesToStudentCourseShell();
            testTeacherRoutesToTeacherWorkspace();
            testRoleWithoutSessionSeesNotice();
            testCourseCardTitleIsTheSameForEveryRole();
            testMainViewExposesCourseNavigationEntry();
            System.out.println("MainControllerRoleRoutingTest: PASS");
        } finally {
            ClientSession.getInstance().logout();
        }
    }

    private static void testAdministratorRoutesToAdministratorShell() {
        Harness harness = route("管理员");
        require(harness.switched.size() == 1,
                "administrator must navigate exactly once, saw " + harness.switched);
        require(ADMIN_VIEW.equals(harness.switched.get(0)),
                "administrator must open the administrator shell, saw " + harness.switched);
        require(harness.notices.isEmpty(),
                "administrator must not receive the teacher notice");
    }

    private static void testStudentRoutesToStudentCourseShell() {
        Harness harness = route("学生");
        require(harness.switched.size() == 1,
                "student must navigate exactly once, saw " + harness.switched);
        require(STUDENT_VIEW.equals(harness.switched.get(0)),
                "student must keep the existing course shell, saw " + harness.switched);
        require(harness.notices.isEmpty(), "student must not receive the teacher notice");
    }

    private static void testAdministratorWithoutPermissionIsDenied() {
        ClientSession.getInstance().logout();
        ClientSession.getInstance().login("tester", "管理员", "token", null);
        Harness harness = new Harness();
        MainController controller = instrumented(harness);
        controller.openCourseSelection(null);
        require(harness.switched.isEmpty(),
                "administrator without course permission must not navigate");
        require(harness.warnings.equals(List.of("权限不足|您没有该模块的管理权限")),
                "administrator without course permission must see the permission warning");
    }

    private static void testTeacherRoutesToTeacherWorkspace() {
        Harness harness = route("教师");
        require(harness.switched.size() == 1,
                "teacher must navigate exactly once, saw " + harness.switched);
        require(TEACHER_VIEW.equals(harness.switched.get(0)),
                "teacher must open the teacher workspace, saw " + harness.switched);
        require(harness.notices.isEmpty(),
                "teacher must not receive the not-open notice, saw " + harness.notices);
    }

    private static void testRoleWithoutSessionSeesNotice() {
        ClientSession.getInstance().logout();
        Harness harness = new Harness();
        MainController controller = instrumented(harness);
        controller.openCourseSelection(null);
        require(harness.switched.isEmpty(),
                "an unknown role must not navigate, saw " + harness.switched);
        require(COURSE_NOTICE.equals(harness.notices.get(0)),
                "an unknown role must receive the same notice, saw " + harness.notices);
    }

    /**
     * 课程入口标题已统一：任何角色（含未知/缺失角色）都得到“教务管理”，不再区分学生与教师。
     */
    private static void testCourseCardTitleIsTheSameForEveryRole() {
        require("教务管理".equals(MainController.courseCardTitleText("管理员")),
                "administrator course card title must be 教务管理");
        require("教务管理".equals(MainController.courseCardTitleText("学生")),
                "student course card title must be 教务管理 too");
        require("教务管理".equals(MainController.courseCardTitleText("教师")),
                "teacher course card title must be 教务管理 too");
        require("教务管理".equals(MainController.courseCardTitleText(null)),
                "a missing role must yield 教务管理 as well");
    }

    private static void testMainViewExposesCourseNavigationEntry() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"navCourseBtn\""),
                "MainView.fxml must expose the course navigation button");
        require(fxml.contains("onAction=\"#openCourseSelection\""),
                "the course navigation button must route through MainController");
    }

    private static Harness route(String role) {
        ClientSession.getInstance().logout();
        ClientSession.getInstance().login("tester", role, "token", null);
        if ("管理员".equals(role)) {
            ClientSession.getInstance().setAdminPermission(
                    new AdminPermission("tester", "测试管理员", false, false, true, false, false));
        }
        Harness harness = new Harness();
        MainController controller = instrumented(harness);
        controller.openCourseSelection(null);
        return harness;
    }

    private static MainController instrumented(Harness harness) {
        MainController controller = new MainController();
        controller.setSceneSwitcher(harness.switched::add);
        controller.setInfoReporter((title, message) ->
                harness.notices.add(title + "|" + message));
        controller.setWarningReporter((title, message) ->
                harness.warnings.add(title + "|" + message));
        return controller;
    }

    private static String readResource(String path) throws IOException {
        try (var stream = MainControllerRoleRoutingTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Harness {
        private final List<String> switched = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
    }
}
