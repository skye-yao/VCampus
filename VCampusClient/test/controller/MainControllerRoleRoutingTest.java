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
            testCourseCardTitleFollowsRole();
            testMainViewExposesCourseNavigationEntry();
            testMainViewExposesSchedulesAndAcademicCard();
            testAcademicMetricFormatting();
            testMainViewSidebarTwoLineSubtext();
            testMainViewExposesChatNoticeItem();
            testChatNoticeFormatting();
            testMainViewExposesNotificationCenterAndDynamicContainer();
            testNotificationCenterBadgeAndTiming();
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

    private static void testCourseCardTitleFollowsRole() {
        require("教务管理".equals(MainController.courseCardTitleText("管理员")),
                "administrator course card title must be 教务管理");
        require("教务管理".equals(MainController.courseCardTitleText("ADMIN")),
                "administrator (ADMIN) course card title must be 教务管理");
        require("教务管理".equals(MainController.courseCardTitleText("教师")),
                "teacher course card title must be 教务管理");
        require("教务管理".equals(MainController.courseCardTitleText("TEACHER")),
                "teacher (TEACHER) course card title must be 教务管理");
        require("选课".equals(MainController.courseCardTitleText("学生")),
                "student course card title must stay 选课");
        require("选课".equals(MainController.courseCardTitleText("STUDENT")),
                "student (STUDENT) course card title must stay 选课");
        require("选课".equals(MainController.courseCardTitleText(null)),
                "a missing role must fall back to 选课");
    }

    private static void testMainViewExposesCourseNavigationEntry() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"navCourseBtn\""),
                "MainView.fxml must expose the course navigation button");
        require(fxml.contains("onAction=\"#openCourseSelection\""),
                "the course navigation button must route through MainController");
    }

    private static void testMainViewExposesSchedulesAndAcademicCard() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"scheduleCard\""),
                "MainView.fxml must contain scheduleCard");
        require(fxml.contains("fx:id=\"scheduleContainer\""),
                "MainView.fxml must contain scheduleContainer");
        require(readResource("/resources/fxml/ScheduleView.fxml") != null,
                "ScheduleView.fxml must exist");
        require(readResource("/resources/fxml/TeacherScheduleView.fxml") != null,
                "TeacherScheduleView.fxml must exist");
        require(fxml.contains("fx:id=\"studentAcademicSummaryCard\""),
                "MainView.fxml must contain studentAcademicSummaryCard");
        require(fxml.contains("fx:id=\"termGpaLabel\""),
                "MainView.fxml must contain termGpaLabel");
        require(fxml.contains("fx:id=\"termAvgLabel\""),
                "MainView.fxml must contain termAvgLabel");
        require(fxml.contains("fx:id=\"cumulativeGpaLabel\""),
                "MainView.fxml must contain cumulativeGpaLabel");
        require(fxml.contains("fx:id=\"cumulativeAvgLabel\""),
                "MainView.fxml must contain cumulativeAvgLabel");
        require(fxml.contains("fx:id=\"earnedCreditsLabel\""),
                "MainView.fxml must contain earnedCreditsLabel");
        require(fxml.contains("fx:id=\"requiredCreditsLabel\""),
                "MainView.fxml must contain requiredCreditsLabel");
        require(fxml.contains("fx:id=\"creditProgressBar\""),
                "MainView.fxml must contain creditProgressBar");
        require(fxml.contains("fx:id=\"creditPercentLabel\""),
                "MainView.fxml must contain creditPercentLabel");
    }

    private static void testAcademicMetricFormatting() {
        require("--".equals(MainController.formatMetric(null)),
                "null metric must format as --");
        require("--".equals(MainController.formatMetric(Double.NaN)),
                "NaN metric must format as --");
        require("3.85".equals(MainController.formatMetric(3.854)),
                "3.854 metric must format to 2 decimal places (3.85)");
        require("88.50".equals(MainController.formatMetric(88.5)),
                "88.5 metric must format to 2 decimal places (88.50)");
    }

    private static void testMainViewSidebarTwoLineSubtext() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"sidebarCollegeLabel\""),
                "MainView.fxml must contain sidebarCollegeLabel for college line");
        require(fxml.contains("fx:id=\"sidebarMajorLabel\""),
                "MainView.fxml must contain sidebarMajorLabel for major/position line");
    }

    private static void testMainViewExposesChatNoticeItem() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"noticeItemThree\""),
                "MainView.fxml must contain noticeItemThree for chat notice entry");
        require(fxml.contains("fx:id=\"noticeBadgeThree\""),
                "MainView.fxml must contain noticeBadgeThree");
        require(fxml.contains("fx:id=\"chatNoticeLabel\""),
                "MainView.fxml must contain chatNoticeLabel");
        require(fxml.contains("fx:id=\"chatNoticeLink\""),
                "MainView.fxml must contain chatNoticeLink");
        require(fxml.contains("onAction=\"#openChat\""),
                "MainView.fxml chat notice link must route to openChat");
    }

    private static void testChatNoticeFormatting() {
        require("你有 2 条未读聊天消息，3 条好友申请待处理"
                        .equals(MainController.formatChatNoticeText(2, 3)),
                "both unread and pending should be combined");
        require("你有 5 条未读聊天消息"
                        .equals(MainController.formatChatNoticeText(5, 0)),
                "only unread messages text should be formatted correctly");
        require("你有 1 条好友申请待处理"
                        .equals(MainController.formatChatNoticeText(0, 1)),
                "only pending friend applications text should be formatted correctly");
    }

    private static void testMainViewExposesNotificationCenterAndDynamicContainer() throws IOException {
        String fxml = readResource("/resources/fxml/MainView.fxml");
        require(fxml.contains("fx:id=\"noticeUnreadCountLabel\""),
                "MainView.fxml must contain noticeUnreadCountLabel for unread pill");
        require(fxml.contains("fx:id=\"markAllReadLink\""),
                "MainView.fxml must contain markAllReadLink");
        require(fxml.contains("onAction=\"#handleMarkAllNotificationsRead\""),
                "markAllReadLink must route to handleMarkAllNotificationsRead");
        require(fxml.contains("fx:id=\"openNoticeCenterLink\""),
                "MainView.fxml must contain openNoticeCenterLink");
        require(fxml.contains("onAction=\"#openNotificationCenter\""),
                "openNoticeCenterLink must route to openNotificationCenter");
        require(fxml.contains("fx:id=\"dynamicNoticeContainer\""),
                "MainView.fxml must contain dynamicNoticeContainer for event notifications");
    }

    private static void testNotificationCenterBadgeAndTiming() {
        require("转账".equals(NotificationCenterDialog.badgeText("BANK")), "BANK badge text should be 转账");
        require("群聊".equals(NotificationCenterDialog.badgeText("CHAT")), "CHAT badge text should be 群聊");
        require("审核".equals(NotificationCenterDialog.badgeText("REVIEW")), "REVIEW badge text should be 审核");
        require("系统".equals(NotificationCenterDialog.badgeText("OTHER")), "other badge text should be 系统");

        require("notice-badge-bank".equals(NotificationCenterDialog.badgeStyleClass("BANK")), "BANK style should be notice-badge-bank");
        require("notice-badge-chat".equals(NotificationCenterDialog.badgeStyleClass("CHAT")), "CHAT style should be notice-badge-chat");

        String timeSample = "2026-09-17 11:42:15";
        String formatted = MainController.formatShortTime(timeSample);
        require(formatted != null && !formatted.isBlank(), "formatted short time must not be empty");
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
