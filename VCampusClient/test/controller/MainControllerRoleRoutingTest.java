package controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import session.ClientSession;

/**
 * 无 JavaFX 工具包依赖的角色路由测试：不打开 Stage，仅通过注入的路由/提示回调断言。
 */
public final class MainControllerRoleRoutingTest {
    private static final String ADMIN_VIEW = "/resources/fxml/AdminCourseManagementView.fxml";
    private static final String STUDENT_VIEW = "/resources/fxml/CourseManagementView.fxml";
    private static final String TEACHER_VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String TEACHER_NOTICE = "系统提示|教师端教务功能暂未开放";

    public static void main(String[] args) throws Exception {
        try {
            testAdministratorRoutesToAdministratorShell();
            testStudentRoutesToStudentCourseShell();
            testTeacherRoutesToTeacherWorkspace();
            testRoleWithoutSessionSeesNotice();
            testCourseCardTitleFollowsRole();
            testMainViewExposesCourseCardTitleId();
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
        require(TEACHER_NOTICE.equals(harness.notices.get(0)),
                "an unknown role must receive the same notice, saw " + harness.notices);
    }

    private static void testCourseCardTitleFollowsRole() {
        require("教务管理".equals(MainController.courseCardTitleText("管理员")),
                "administrator course card title must be 教务管理");
        require("选课".equals(MainController.courseCardTitleText("学生")),
                "student course card title must stay 选课");
        require("选课".equals(MainController.courseCardTitleText("教师")),
                "teacher course card title must stay 选课");
        require("选课".equals(MainController.courseCardTitleText(null)),
                "a missing role must fall back to 选课");
    }

    private static void testMainViewExposesCourseCardTitleId() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"courseCardTitle\""),
                "MainView.fxml must expose the course card title id");
        require(fxml.contains("text=\"选课\""),
                "the course card title must keep its default 选课 label");
    }

    private static Harness route(String role) {
        ClientSession.getInstance().login("tester", role, "token", null);
        Harness harness = new Harness();
        MainController controller = instrumented(harness);
        // initialize() must work without a Stage or a started JavaFX toolkit.
        controller.initialize();
        controller.openCourseSelection(null);
        return harness;
    }

    private static MainController instrumented(Harness harness) {
        MainController controller = new MainController();
        controller.setSceneSwitcher(harness.switched::add);
        controller.setInfoReporter((title, message) ->
                harness.notices.add(title + "|" + message));
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
    }
}
