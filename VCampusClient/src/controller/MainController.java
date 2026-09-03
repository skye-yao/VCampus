package controller;

import app.ClientMain;
import entity.User;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.MenuButton;
import javafx.scene.input.MouseEvent;
import network.SocketClient;
import protocol.Message;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;

public class MainController {

    @FXML private MenuButton userMenuButton;

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
    private void openCourseSelection(MouseEvent event) {
        showSubsystemNotice("选课子系统");
    }

    @FXML
    private void openStore(MouseEvent event) {
        ClientMain.switchScene("/resources/fxml/ShopView.fxml");
    }

    @FXML
    private void openBank(MouseEvent event) {
        ClientMain.switchScene("/resources/fxml/BankView.fxml");
    }

    @FXML
    private void openHospital(MouseEvent event) {
        ClientMain.switchScene("/resources/fxml/AIview.fxml");
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
