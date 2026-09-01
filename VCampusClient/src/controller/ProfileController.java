package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.User;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.ClientSession;
import util.AlertUtil;


import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

public class ProfileController {

    @FXML private ToggleButton tabInfoBtn;
    @FXML private ToggleButton tabPwdBtn;
    @FXML private ToggleButton tabAvatarBtn;
    @FXML private ToggleButton tabWalletBtn;

    @FXML private ScrollPane panelInfo;
    @FXML private Node panelPwd;
    @FXML private Node panelAvatar;
    @FXML private Node panelWallet;

    @FXML private Label headerNameLabel;
    @FXML private Label headerTagLabel;
    @FXML private ImageView headerAvatarView;

    @FXML private TextField profUIDField;
    @FXML private TextField profNameField;
    @FXML private ComboBox<String> profSexCombo;
    @FXML private TextField profCollegeField;
    @FXML private TextField profMajorField;
    @FXML private TextField profPhoneField;
    @FXML private TextField profEmailField;

    @FXML private PasswordField oldPwdField;
    @FXML private PasswordField newPwdField;
    @FXML private PasswordField confirmPwdField;

    @FXML private ImageView updateAvatarView;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        // 加载当前登录用户数据
        loadUserData();
    }

    private void loadUserData() {
        User user = ClientSession.getInstance().getCurrentUser();
        String roleStr = ClientSession.getInstance().getRole();
        if (roleStr == null) roleStr = "学生";

        if (user != null) {
            if (headerNameLabel != null) headerNameLabel.setText(user.getName() != null ? user.getName() : "");
            if (headerTagLabel != null) {
                String college = user.getCollege() != null ? user.getCollege() : "";
                headerTagLabel.setText(college + " · " + roleStr);
                showAvatar(headerAvatarView, user.getAvatar());
                showAvatar(updateAvatarView, user.getAvatar());
            }

            if (profUIDField != null) profUIDField.setText(user.getUID() != null ? user.getUID() : "");
            if (profNameField != null) profNameField.setText(user.getName() != null ? user.getName() : "");
            if (profSexCombo != null) {
                String gender = user.getGender() != null ? user.getGender() : "男";
                profSexCombo.getSelectionModel().select(gender);
            }
            if (profCollegeField != null) profCollegeField.setText(user.getCollege() != null ? user.getCollege() : "");
            if (profMajorField != null) profMajorField.setText(user.getMajor() != null ? user.getMajor() : "");
            if (profPhoneField != null) profPhoneField.setText(user.getPhone() != null ? user.getPhone() : "");
            if (profEmailField != null) profEmailField.setText(user.getEmail() != null ? user.getEmail() : "");
        } else {
            if (profUIDField != null) profUIDField.setText(ClientSession.getInstance().getUsername());
        }
    }

    private void showAvatar(ImageView view, String base64) {
        if (view == null) return;
        if (base64 == null || base64.isEmpty()) {
            view.setImage(null);
            return;
        }
        try {
            view.setImage(new Image(new ByteArrayInputStream(Base64.getDecoder().decode(base64))));
        } catch (Exception e) {
            view.setImage(null);
        }
    }

    @FXML
    private void handleBackToMain(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }

    @FXML
    private void switchSubView(ActionEvent event) {
        // 不依赖 ToggleGroup，避免 Scene Builder / FXMLLoader 对
        // toggleGroup="$profileNavGroup" 的引用解析兼容性问题。
        ToggleButton source = (ToggleButton) event.getSource();

        if (tabInfoBtn != null) tabInfoBtn.setSelected(source == tabInfoBtn);
        if (tabPwdBtn != null) tabPwdBtn.setSelected(source == tabPwdBtn);
        if (tabAvatarBtn != null) tabAvatarBtn.setSelected(source == tabAvatarBtn);
        if (tabWalletBtn != null) tabWalletBtn.setSelected(source == tabWalletBtn);

        if (panelInfo != null) panelInfo.setVisible(source == tabInfoBtn);
        if (panelPwd != null) panelPwd.setVisible(source == tabPwdBtn);
        if (panelAvatar != null) panelAvatar.setVisible(source == tabAvatarBtn);
        if (panelWallet != null) panelWallet.setVisible(source == tabWalletBtn);
    }

    /**
    //侧边栏子面板切换
    @FXML
    private void switchSubView(ActionEvent event) {
        if (panelInfo != null) panelInfo.setVisible(false);
        if (panelPwd != null) panelPwd.setVisible(false);
        if (panelAvatar != null) panelAvatar.setVisible(false);
        if (panelWallet != null) panelWallet.setVisible(false);

        if (tabInfoBtn != null && tabInfoBtn.isSelected()) {
            if (panelInfo != null) panelInfo.setVisible(true);
        } else if (tabPwdBtn != null && tabPwdBtn.isSelected()) {
            if (panelPwd != null) panelPwd.setVisible(true);
        } else if (tabAvatarBtn != null && tabAvatarBtn.isSelected()) {
            if (panelAvatar != null) panelAvatar.setVisible(true);
        } else if (tabWalletBtn != null && tabWalletBtn.isSelected()) {
            if (panelWallet != null) panelWallet.setVisible(true);
        }
    }
     */

    @FXML
    private void handleSaveProfile(ActionEvent event) {
        String name = profNameField != null ? profNameField.getText() : "";
        String gender = profSexCombo != null ? profSexCombo.getValue() : "男";
        String college = profCollegeField != null ? profCollegeField.getText() : "";
        String major = profMajorField != null ? profMajorField.getText() : "";
        String phone = profPhoneField != null ? profPhoneField.getText() : "";
        String email = profEmailField != null ? profEmailField.getText() : "";

        Message request = new Message(MessageType.REQUEST, "user", "updateprofile");
        request.putData("name", name);
        request.putData("gender", gender);
        request.putData("college", college);
        request.putData("major", major);
        request.putData("phone", phone);
        request.putData("email", email);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        User currentUser = ClientSession.getInstance().getCurrentUser();
                        if (currentUser != null) {
                            currentUser.setName(name);
                            currentUser.setGender(gender);
                            currentUser.setCollege(college);
                            currentUser.setMajor(major);
                            currentUser.setPhone(phone);
                            currentUser.setEmail(email);
                        }
                        if (headerNameLabel != null) headerNameLabel.setText(name);
                        AlertUtil.showInfo("操作提示", "个人信息修改已保存成功！");
                    } else {
                        AlertUtil.showError("保存失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> AlertUtil.showError("网络异常", "保存个人信息失败: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    private void handleChangePassword(ActionEvent event) {
        String oldPwd = oldPwdField != null ? oldPwdField.getText() : "";
        String newPwd = newPwdField != null ? newPwdField.getText() : "";
        String confirmPwd = confirmPwdField != null ? confirmPwdField.getText() : "";

        if (oldPwd.isEmpty() || newPwd.isEmpty() || confirmPwd.isEmpty()) {
            AlertUtil.showWarning("提示", "请填写完整的密码信息！");
            return;
        }

        if (!newPwd.equals(confirmPwd)) {
            AlertUtil.showWarning("提示", "两次输入的新密码不一致！");
            return;
        }

        if (newPwd.length() < 6) {
            AlertUtil.showWarning("提示", "新密码长度不能少于 6 位！");
            return;
        }

        Message request = new Message(MessageType.REQUEST, "user", "changepassword");
        request.putData("oldPassword", oldPwd);
        request.putData("newPassword", newPwd);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        if (oldPwdField != null) oldPwdField.clear();
                        if (newPwdField != null) newPwdField.clear();
                        if (confirmPwdField != null) confirmPwdField.clear();
                        AlertUtil.showInfo("密码修改", "密码修改成功！下次登录生效。");
                    } else {
                        AlertUtil.showError("修改失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> AlertUtil.showError("网络异常", "请求修改密码失败: " + ex.getMessage()));
                    return null;
                });
    }

    @FXML
    private void handleChooseAvatar(ActionEvent event) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择本地头像文件");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("图片文件", "*.jpg", "*.png", "*.jpeg"));
        File selectedFile = fileChooser.showOpenDialog(null);
        if (selectedFile == null) return;

        byte[] fileBytes;
        try {
            fileBytes = Files.readAllBytes(selectedFile.toPath());
        } catch (IOException e) {
            AlertUtil.showError("读取失败", "无法读取所选图片文件");
            return;
        }

        final long MAX_AVATAR_BYTES = 2 * 1024 * 1024;
        if (fileBytes.length > MAX_AVATAR_BYTES) {
            AlertUtil.showWarning("提示", "图片过大，请选择 2MB 以内的图片");
            return;
        }

        String base64 = Base64.getEncoder().encodeToString(fileBytes);

        // 先本地预览
        showAvatar(updateAvatarView, base64);

        Message request = new Message(MessageType.REQUEST, "user", "updateavatar");
        request.putData("avatar", base64);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        User currentUser = ClientSession.getInstance().getCurrentUser();
                        if (currentUser != null) {
                            currentUser.setAvatar(base64);
                        }
                        showAvatar(headerAvatarView, base64);
                        AlertUtil.showInfo("操作提示", "头像更换成功！");
                    } else {
                        rollbackAvatarPreview();
                        AlertUtil.showError("更换失败", response.getMessage());
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        rollbackAvatarPreview();
                        AlertUtil.showError("网络异常", "更换头像失败: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void rollbackAvatarPreview() {
        User currentUser = ClientSession.getInstance().getCurrentUser();
        showAvatar(updateAvatarView, currentUser != null ? currentUser.getAvatar() : null);
    }

    @FXML
    private void handleRecharge(ActionEvent event) {
        AlertUtil.showInfo("账户充值", "一卡通充值通道正在建设中，请前往校园网银或人工服务窗口。");
    }

    @FXML
    private void handleViewBills(ActionEvent event) {
        AlertUtil.showInfo("消费明细", "正在查询近 30 天一卡通账单流水...");
    }
}
