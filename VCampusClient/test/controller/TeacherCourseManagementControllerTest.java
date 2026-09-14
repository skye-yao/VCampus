package controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import service.MockTeacherCourseService;
import service.TeacherCourseService;
import service.TeacherCourseServices;

/**
 * 无 JavaFX 工具包依赖的教师工作台外壳测试。
 *
 * <p>不启动 toolkit、不构造控件：FXML 中的节点一律保持 null，仅断言外壳契约（返回首页、服务注入、
 * 四个分阶段入口在无节点时也安全），并对 FXML/CSS 文本做静态校验——包括必须使用可写属性
 * {@code disable="true"} 而不是只读的 {@code disabled}，后者只在加载期抛
 * {@code PropertyNotFoundException}，静态字段检查抓不到。
 */
public final class TeacherCourseManagementControllerTest {
    private static final String VIEW = "/resources/fxml/TeacherCourseManagementView.fxml";
    private static final String CSS = "/resources/css/teacher-course.css";
    private static final String STAGING_NOTICE = "该功能将在后续阶段接入";
    private static final String HOME_VIEW = "/resources/fxml/MainView.fxml";
    private static final String LONG_OFFERING_ID = "9007199254740993";

    private TeacherCourseManagementControllerTest() {
    }

    public static void main(String[] args) throws Exception {
        defaultConstructorUsesTheSharedTeacherService();
        injectedServiceIsRetainedForLaterSubpages();
        returningHomeTargetsTheMainView();
        returningHomeRunsTheInjectedActionSafely();
        stagedEntriesAreSafeWithoutNodes();
        viewIsTheTeacherWorkspaceShell();
        stylesheetExists();
        entriesUseTheWritableDisableAttribute();
        System.out.println("TeacherCourseManagementControllerTest: PASS");
    }

    private static void defaultConstructorUsesTheSharedTeacherService() {
        TeacherCourseService previous = TeacherCourseServices.current();
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseServices.install(mock);
        try {
            TeacherCourseManagementController controller = new TeacherCourseManagementController();
            require(controller.service() == mock,
                    "the default constructor must take the shared teacher service");
        } finally {
            TeacherCourseServices.install(previous);
        }
    }

    private static void injectedServiceIsRetainedForLaterSubpages() {
        MockTeacherCourseService mock = new MockTeacherCourseService();
        TeacherCourseManagementController controller = new TeacherCourseManagementController(mock);
        require(controller.service() == mock,
                "the injected teacher service must be retained for later subpages");
    }

    private static void returningHomeTargetsTheMainView() {
        require(HOME_VIEW.equals(TeacherCourseManagementController.HOME_VIEW),
                "the workspace must return to the main view, saw "
                        + TeacherCourseManagementController.HOME_VIEW);
    }

    private static void returningHomeRunsTheInjectedActionSafely() {
        TeacherCourseManagementController controller = controller();
        List<Integer> backs = new ArrayList<>();
        controller.setBackAction(() -> backs.add(backs.size()));
        controller.handleBack();
        controller.handleBack();
        require(backs.size() == 2, "every 返回首页 must run the back action, saw " + backs);

        controller.setBackAction(null);
        controller.handleBack();
    }

    private static void stagedEntriesAreSafeWithoutNodes() {
        TeacherCourseManagementController controller = controller();
        controller.initialize();
        controller.openOfferings();
        controller.openTimetable();
        controller.openGrades(LONG_OFFERING_ID);
        controller.openGrades(null);
        controller.openApplications();
    }

    private static void viewIsTheTeacherWorkspaceShell() throws IOException {
        String fxml = readResource(VIEW);
        require(fxml.contains("fx:controller=\"controller.TeacherCourseManagementController\""),
                "the view must be controlled by TeacherCourseManagementController");
        require(fxml.contains("stylesheets=\"@../css/teacher-course.css\""),
                "the view must link teacher-course.css the way the other views link stylesheets");
        require(fxml.contains("prefWidth=\"860.0\"") && fxml.contains("prefHeight=\"580.0\""),
                "the workspace must reuse the 860x580 main window");
        require(fxml.contains("text=\"返回首页\"") && fxml.contains("onAction=\"#handleBack\""),
                "the top-left entry must be a working 返回首页 bound to #handleBack");
        for (String entry : List.of("教学课程表", "教学班", "成绩录入", "我的申请")) {
            require(fxml.contains("text=\"" + entry + "\""),
                    "the top-right entries must include " + entry);
        }
        require(fxml.contains("fx:id=\"statusLabel\""),
                "the view must expose the status label the staged entries report into");
        require(fxml.contains(STAGING_NOTICE),
                "the view must show the staging notice for the not-yet-delivered entries");
    }

    private static void stylesheetExists() throws IOException {
        require(!readResource(CSS).isBlank(), "teacher-course.css must not be empty");
    }

    private static void entriesUseTheWritableDisableAttribute() throws IOException {
        String fxml = readResource(VIEW);
        require(!fxml.contains("disabled="),
                "FXML must not use the read-only disabled attribute; use disable=\"true\"");
        require(countOccurrences(fxml, "disable=\"true\"") == 4,
                "all four staged entries must be disabled with disable=\"true\", saw "
                        + countOccurrences(fxml, "disable=\"true\""));
    }

    private static TeacherCourseManagementController controller() {
        return new TeacherCourseManagementController(new MockTeacherCourseService());
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String readResource(String path) throws IOException {
        try (var stream = TeacherCourseManagementControllerTest.class.getResourceAsStream(path)) {
            if (stream == null) throw new IOException("Missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
