package controller;

import app.ClientMain;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.AlertUtil;

/**
 * 新用户注册控制器
 */
public class RegisterController {

    @FXML private ComboBox<String> roleComboBox;
    @FXML private TextField uidField;
    @FXML private TextField nameField;
    @FXML private TextField phoneField;
    @FXML private TextField codeField;
    @FXML private PasswordField passwordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button sendCodeBtn;
    @FXML private Button registerButton;

    private Timeline countdownTimeline;
    private int countdownSeconds = 60;

    @FXML
    public void initialize() {
        if (roleComboBox != null && roleComboBox.getValue() == null) {
            roleComboBox.getSelectionModel().select("学生");
        }
    }

    /**
     * 发送短信验证码
     */
    @FXML
    private void handleSendCode(ActionEvent event) {
        String uid = uidField != null ? uidField.getText().trim() : "";
        String phone = phoneField != null ? phoneField.getText().trim() : "";

        if (uid.isEmpty()) {
            AlertUtil.showWarning("提示", "请先输入一卡通号！");
            if (uidField != null) uidField.requestFocus();
            return;
        }

        if (phone.isEmpty() || !phone.matches("^1[3-9]\\d{9}$")) {
            AlertUtil.showWarning("提示", "请输入有效的11位手机号码！");
            if (phoneField != null) phoneField.requestFocus();
            return;
        }

        sendCodeBtn.setDisable(true);
        sendCodeBtn.setText("发送中...");

        Message request = new Message(MessageType.REQUEST, "user", "sendsmscode");
        request.putData("phone", phone);
        request.putData("uid", uid);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        String code = response.getData("code");
                        AlertUtil.showInfo("验证码已发送", "【东南大学】验证码为：" + code + "\n（测试环境已为您自动填入输入框，5分钟内有效）");
                        if (codeField != null && code != null) {
                            codeField.setText(code);
                        }
                        startCountdown();
                    } else {
                        sendCodeBtn.setDisable(false);
                        sendCodeBtn.setText("获取验证码");
                        AlertUtil.showError("发送失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        sendCodeBtn.setDisable(false);
                        sendCodeBtn.setText("获取验证码");
                        AlertUtil.showError("网络异常", "请求验证码失败: " + ex.getMessage());
                    });
                    return null;
                });
    }

    /**
     * 开始 60 秒获取验证码倒计时
     */
    private void startCountdown() {
        countdownSeconds = 60;
        sendCodeBtn.setDisable(true);
        sendCodeBtn.setText(countdownSeconds + "s后重试");

        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }

        countdownTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            countdownSeconds--;
            if (countdownSeconds > 0) {
                sendCodeBtn.setText(countdownSeconds + "s后重试");
            } else {
                sendCodeBtn.setDisable(false);
                sendCodeBtn.setText("获取验证码");
                countdownTimeline.stop();
            }
        }));
        countdownTimeline.setCycleCount(60);
        countdownTimeline.play();
    }

    /**
     * 提交注册
     */
    @FXML
    private void handleRegister(ActionEvent event) {
        String uid = uidField != null ? uidField.getText().trim() : "";
        String name = nameField != null ? nameField.getText().trim() : "";
        String phone = phoneField != null ? phoneField.getText().trim() : "";
        String code = codeField != null ? codeField.getText().trim() : "";
        String password = passwordField != null ? passwordField.getText() : "";
        String confirmPassword = confirmPasswordField != null ? confirmPasswordField.getText() : "";

        if (uid.isEmpty()) {
            AlertUtil.showWarning("提示", "一卡通号不能为空！");
            return;
        }
        if (name.isEmpty()) {
            AlertUtil.showWarning("提示", "真实姓名不能为空！");
            return;
        }
        if (phone.isEmpty() || !phone.matches("^1[3-9]\\d{9}$")) {
            AlertUtil.showWarning("提示", "请输入正确的11位手机号码！");
            return;
        }
        if (code.isEmpty()) {
            AlertUtil.showWarning("提示", "短信验证码不能为空！");
            return;
        }
        if (password.length() < 6) {
            AlertUtil.showWarning("提示", "密码长度不能少于6位！");
            return;
        }
        if (!password.equals(confirmPassword)) {
            AlertUtil.showWarning("提示", "两次输入的密码不一致！");
            return;
        }

        registerButton.setDisable(true);
        registerButton.setText("正在注册...");

        String role = roleComboBox != null && roleComboBox.getValue() != null ? roleComboBox.getValue() : "学生";

        Message request = new Message(MessageType.REQUEST, "user", "register");
        request.putData("uid", uid);
        request.putData("name", name);
        request.putData("phone", phone);
        request.putData("code", code);
        request.putData("password", password);
        request.putData("role", role);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    registerButton.setDisable(false);
                    registerButton.setText("立即注册");

                    if (response.getCode() == MessageCode.SUCCESS) {
                        AlertUtil.showInfo("注册成功", "恭喜您，一卡通账号 " + uid + "（" + role + "）注册成功！\n已为您发放 1000.00 元【新用户福利】至一卡通钱包，请返回登录。");
                        if (countdownTimeline != null) {
                            countdownTimeline.stop();
                        }
                        ClientMain.switchScene("/resources/fxml/LoginView.fxml");
                    } else {
                        AlertUtil.showError("注册失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        registerButton.setDisable(false);
                        registerButton.setText("立即注册");
                        AlertUtil.showError("网络异常", "注册请求失败: " + ex.getMessage());
                    });
                    return null;
                });
    }

    /**
     * 返回登录界面
     */
    @FXML
    private void handleBackToLogin(ActionEvent event) {
        if (countdownTimeline != null) {
            countdownTimeline.stop();
        }
        ClientMain.switchScene("/resources/fxml/LoginView.fxml");
    }
}