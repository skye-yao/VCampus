package controller;

import app.ClientMain;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.AlertUtil;

/**
 * 忘记密码/找回密码控制器
 */
public class ForgotPasswordController {

    @FXML private TextField uidField;
    @FXML private TextField phoneField;
    @FXML private TextField codeField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private Button sendCodeBtn;
    @FXML private Button resetBtn;

    private Timeline countdownTimeline;
    private int countdownSeconds = 60;

    /**
     * 发送重置密码短信验证码
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
            AlertUtil.showWarning("提示", "请输入绑定的有效11位手机号码！");
            if (phoneField != null) phoneField.requestFocus();
            return;
        }

        sendCodeBtn.setDisable(true);
        sendCodeBtn.setText("发送中...");

        Message request = new Message(MessageType.REQUEST, "user", "sendresetcode");
        request.putData("phone", phone);
        request.putData("uid", uid);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        String code = response.getData("code");
                        AlertUtil.showInfo("验证码已发送", "【东南大学】重置密码验证码为：" + code + "\n（测试环境已为您自动填入输入框，5分钟内有效）");
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
     * 开始 60 秒倒计时
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
     * 提交密码重置
     */
    @FXML
    private void handleResetPassword(ActionEvent event) {
        String uid = uidField != null ? uidField.getText().trim() : "";
        String phone = phoneField != null ? phoneField.getText().trim() : "";
        String code = codeField != null ? codeField.getText().trim() : "";
        String newPassword = newPasswordField != null ? newPasswordField.getText() : "";
        String confirmPassword = confirmPasswordField != null ? confirmPasswordField.getText() : "";

        // 1. 基础非空校验
        if (uid.isEmpty()) {
            AlertUtil.showWarning("提示", "一卡通号不能为空！");
            if (uidField != null) uidField.requestFocus();
            return;
        }
        if (phone.isEmpty() || !phone.matches("^1[3-9]\\d{9}$")) {
            AlertUtil.showWarning("提示", "请输入绑定的有效11位手机号码！");
            if (phoneField != null) phoneField.requestFocus();
            return;
        }
        if (code.isEmpty()) {
            AlertUtil.showWarning("提示", "短信验证码不能为空！");
            if (codeField != null) codeField.requestFocus();
            return;
        }
        if (newPassword.isEmpty()) {
            AlertUtil.showWarning("提示", "新密码不能为空！");
            if (newPasswordField != null) newPasswordField.requestFocus();
            return;
        }

        // 2. 密码一致性校验
        if (!newPassword.equals(confirmPassword)) {
            AlertUtil.showWarning("提示", "两次输入的新密码不一致，请核对！");
            if (confirmPasswordField != null) confirmPasswordField.requestFocus();
            return;
        }

        // 3. 密码长度校验（至少6位）
        if (newPassword.length() < 6) {
            AlertUtil.showWarning("提示", "新密码长度不能少于6位！");
            if (newPasswordField != null) newPasswordField.requestFocus();
            return;
        }

        resetBtn.setDisable(true);
        resetBtn.setText("正在重置...");

        Message request = new Message(MessageType.REQUEST, "user", "resetpassword");
        request.putData("uid", uid);
        request.putData("phone", phone);
        request.putData("code", code);
        request.putData("newPassword", newPassword);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    resetBtn.setDisable(false);
                    resetBtn.setText("重 置 密 码");

                    if (response.getCode() == MessageCode.SUCCESS) {
                        if (countdownTimeline != null) {
                            countdownTimeline.stop();
                        }
                        AlertUtil.showInfo("密码修改成功", "恭喜，一卡通账号 " + uid + " 的密码已重置成功！\n请使用新密码重新登录。");
                        ClientMain.switchScene("/resources/fxml/LoginView.fxml");
                    } else {
                        AlertUtil.showError("重置失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        resetBtn.setDisable(false);
                        resetBtn.setText("重 置 密 码");
                        AlertUtil.showError("网络异常", "重置密码请求失败: " + ex.getMessage());
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
