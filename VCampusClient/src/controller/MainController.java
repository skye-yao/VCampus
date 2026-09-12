package controller;

import java.util.Objects;
import java.util.function.BiConsumer;
import app.ClientMain;
import entity.User;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.input.MouseEvent;
import network.SocketClient;
import protocol.Message;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;

public class MainController {

    static final String ADMIN_COURSE_VIEW = "/resources/fxml/AdminCourseManagementView.fxml";
    static final String STUDENT_COURSE_VIEW = "/resources/fxml/CourseManagementView.fxml";
    static final String TEACHER_NOTICE_TITLE = "系统提示";
    static final String TEACHER_NOTICE_MESSAGE = "教师端教务功能暂未开放";

    interface SceneSwitcher {
        void switchTo(String fxmlPath);
    }

    private SceneSwitcher sceneSwitcher = ClientMain::switchScene;
    private BiConsumer<String, String> infoReporter = AlertUtil::showInfo;

    @FXML private MenuButton userMenuButton;
    @FXML private Label courseCardTitle;

    @FXML
    public void initialize() {
        ClientSession session = ClientSession.getInstance();
        if (userMenuButton != null) {
            User user = session.getCurrentUser();
            String displayName = user != null && user.getName() != null ? user.getName() : session.getUsername();
            if (displayName != null) {
                userMenuButton.setText("你好，" + displayName);
            }
        }
        if (courseCardTitle != null) {
            courseCardTitle.setText(courseCardTitleText(session.getRole()));
        }
    }

    /**
     * 教务入口卡片标题：管理员进入教务管理，其余角色保持选课。
     */
    static String courseCardTitleText(String role) {
        return "管理员".equals(role) ? "教务管理" : "选课";
    }

    void setSceneSwitcher(SceneSwitcher switcher) {
        this.sceneSwitcher = Objects.requireNonNull(switcher, "Scene switcher is required");
    }

    void setInfoReporter(BiConsumer<String, String> reporter) {
        this.infoReporter = Objects.requireNonNull(reporter, "Info reporter is required");
    }

    @FXML
    private void handleNavigateProfile(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/ProfileView.fxml");
    }

    @FXML
    private void handleLogout(ActionEvent event) {
        // 向服务端发送登出请求
        Message logoutMsg = new Message(MessageType.REQUEST, "user", "logout");
        SocketClient.getInstance().sendAsync(logoutMsg);

        // 清除本地 Session
        ClientSession.getInstance().logout();

        // 返回登录页
        ClientMain.switchScene("/resources/fxml/LoginView.fxml");
    }

    @FXML
    private void openStudentAffairs(MouseEvent event) {
        showSubsystemNotice("学籍管理子系统");
    }

    @FXML
    private void openLibrary(MouseEvent event) {
        showSubsystemNotice("图书馆子系统");
    }

    @FXML
    void openCourseSelection(MouseEvent event) {
        if ("管理员".equals(ClientSession.getInstance().getRole())) {
            sceneSwitcher.switchTo(ADMIN_COURSE_VIEW);
        } else if ("学生".equals(ClientSession.getInstance().getRole())) {
            sceneSwitcher.switchTo(STUDENT_COURSE_VIEW);
        } else {
            infoReporter.accept(TEACHER_NOTICE_TITLE, TEACHER_NOTICE_MESSAGE);
        }
    }

    @FXML
    private void openStore(MouseEvent event) {
        showSubsystemNotice("校园商店子系统");
    }

    @FXML
    private void openBank(MouseEvent event) {
        showSubsystemNotice("校园银行子系统");
    }

    @FXML
    private void openHospital(MouseEvent event) {
        showSubsystemNotice("校医院挂号系统");
    }

    @FXML
    private void openGym(MouseEvent event) {
        showSubsystemNotice("体育场馆预约系统");
    }

    @FXML
    private void openDormitory(MouseEvent event) {
        showSubsystemNotice("宿舍管理子系统");
    }

    private void showSubsystemNotice(String name) {
        AlertUtil.showInfo("系统提示", "正在载入 " + name + " 模块...");
    }
}
