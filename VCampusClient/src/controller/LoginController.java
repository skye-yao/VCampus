package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.User;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;

public class LoginController {

    @FXML private ComboBox<String> roleComboBox;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button loginButton;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        if (roleComboBox != null && !roleComboBox.getItems().isEmpty()) {
            roleComboBox.getSelectionModel().select(0);
        }
    }

    @FXML
    private void handleLogin(ActionEvent event) {
        String role = roleComboBox.getValue();
        String username = usernameField.getText();
        String password = passwordField.getText();

        if (username == null || username.trim().isEmpty()) {
            AlertUtil.showWarning("提示", "请输入一卡通号 / 学工号！");
            return;
        }

        if (password == null || password.trim().isEmpty()) {
            AlertUtil.showWarning("提示", "请输入登录密码！");
            return;
        }

        // 构造登录请求消息
        Message request = new Message(MessageType.REQUEST, "user", "login");
        request.putData("cardNo", username.trim());
        request.putData("password", password.trim());
        request.putData("role", role);

        if (loginButton != null) {
            loginButton.setDisable(true);
            loginButton.setText("正在登录...");
        }

        // 发送网络请求并异步处理响应
        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (loginButton != null) {
                        loginButton.setDisable(false);
                        loginButton.setText("登 录");
                    }

                    if (response.getCode() == MessageCode.SUCCESS) {
                        String token = response.getToken();
                        if (token == null) {
                            token = response.getData("token");
                        }
                        
                        // 提取 User 对象
                        Object userObj = response.getData("user");
                        User user = null;
                        if (userObj != null) {
                            String userJson = gson.toJson(userObj);
                            user = gson.fromJson(userJson, User.class);
                        }

                        // 公共登录信息必须采用服务端认证结果，不能信任登录表单中选择的身份。
                        String authenticatedUsername = response.getData("username");
                        String authenticatedRole = response.getData("role");
                        if (authenticatedUsername == null || authenticatedUsername.isBlank()) {
                            authenticatedUsername = user != null && user.getUID() != null ? user.getUID() : username.trim();
                        }
                        if (authenticatedRole == null || authenticatedRole.isBlank()) {
                            authenticatedRole = user != null && user.getRole() != null ? user.getRole().name() : "";
                        }
                        ClientSession.getInstance().login(authenticatedUsername, authenticatedRole, token, user);

                        // 跳转到主控制台
                        ClientMain.switchScene("/resources/fxml/MainView.fxml");
                    } else {
                        AlertUtil.showError("登录失败", response.getMessage() != null ? response.getMessage() : "用户名或密码错误");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (loginButton != null) {
                            loginButton.setDisable(false);
                            loginButton.setText("登 录");
                        }
                        AlertUtil.showError("网络异常", "连接服务端失败，请确认服务端已启动！\n" + ex.getMessage());
                    });
                    return null;
                });
    }

    @FXML
    private void handleOpenForgotPassword(ActionEvent event) {
        AlertUtil.showInfo("找回密码", "请联系管理员或使用绑定的预留手机重置密码。");
    }

    @FXML
    private void handleOpenRegister(ActionEvent event) {
        AlertUtil.showInfo("新用户注册", "请联系管理员开通一卡通账号。");
    }
}
