package controller;

import app.ClientMain;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import entity.User;
import enums.Role;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.AlertUtil;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;

/**
 * 用户账号管理控制器
 *
 * 供具有用户管理权限的管理员进行全校用户账号的检索、信息修改、状态管理（冻结/解冻/注销）与密码重置。
 */
public class UserController {

    @FXML private TextField searchField;
    @FXML private ComboBox<String> roleFilterComboBox;
    @FXML private ComboBox<String> statusFilterComboBox;
    @FXML private TableView<User> userTable;
    @FXML private TableColumn<User, String> colUid;
    @FXML private TableColumn<User, String> colName;
    @FXML private TableColumn<User, String> colRole;
    @FXML private TableColumn<User, String> colGender;
    @FXML private TableColumn<User, String> colCollege;
    @FXML private TableColumn<User, String> colMajor;
    @FXML private TableColumn<User, String> colPhone;
    @FXML private TableColumn<User, String> colEmail;
    @FXML private TableColumn<User, String> colStatus;
    @FXML private TableColumn<User, Void> colActions;
    @FXML private Label statusLabel;

    private final ObservableList<User> userObservableList = FXCollections.observableArrayList();
    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        initFilterControls();
        setupTableColumns();
        loadUsers();
    }

    private void initFilterControls() {
        roleFilterComboBox.setItems(FXCollections.observableArrayList("全部角色", "学生", "教师", "管理员"));
        roleFilterComboBox.getSelectionModel().selectFirst();

        statusFilterComboBox.setItems(FXCollections.observableArrayList("全部状态", "正常", "已冻结", "已注销"));
        statusFilterComboBox.getSelectionModel().selectFirst();
    }

    private void setupTableColumns() {
        userTable.setItems(userObservableList);

        colUid.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getUID() != null ? data.getValue().getUID() : ""));
        colUid.setStyle("-fx-alignment: CENTER;");

        colName.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getName() != null ? data.getValue().getName() : ""));
        colName.setStyle("-fx-alignment: CENTER;");

        colRole.setCellValueFactory(data -> {
            Role role = data.getValue().getRole();
            return new SimpleStringProperty(role != null ? role.getDescription() : "学生");
        });
        colRole.setCellFactory(col -> new TableCell<User, String>() {
            private final Label badge = new Label();
            @Override
            protected void updateItem(String roleDesc, boolean empty) {
                super.updateItem(roleDesc, empty);
                if (empty || roleDesc == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    badge.setText(roleDesc);
                    badge.getStyleClass().removeAll("tag-role-student", "tag-role-teacher", "tag-role-admin");
                    if ("教师".equals(roleDesc)) {
                        badge.getStyleClass().add("tag-role-teacher");
                    } else if ("管理员".equals(roleDesc)) {
                        badge.getStyleClass().add("tag-role-admin");
                    } else {
                        badge.getStyleClass().add("tag-role-student");
                    }
                    setGraphic(badge);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        colGender.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getGender() != null ? data.getValue().getGender() : ""));
        colGender.setStyle("-fx-alignment: CENTER;");

        colCollege.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getCollege() != null ? data.getValue().getCollege() : ""));
        colCollege.setStyle("-fx-alignment: CENTER;");

        colMajor.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getMajor() != null ? data.getValue().getMajor() : ""));
        colMajor.setStyle("-fx-alignment: CENTER;");

        colPhone.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getPhone() != null ? data.getValue().getPhone() : ""));
        colPhone.setStyle("-fx-alignment: CENTER;");

        colEmail.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getEmail() != null ? data.getValue().getEmail() : ""));
        colEmail.setStyle("-fx-alignment: CENTER;");

        colStatus.setCellValueFactory(data -> {
            String s = data.getValue().getStatus();
            return new SimpleStringProperty(s != null ? s : "ACTIVE");
        });
        colStatus.setCellFactory(col -> new TableCell<User, String>() {
            private final Label badge = new Label();
            @Override
            protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                if (empty || status == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    badge.getStyleClass().removeAll("tag-status-active", "tag-status-frozen", "tag-status-deleted");
                    if ("FROZEN".equalsIgnoreCase(status)) {
                        badge.setText("已冻结");
                        badge.getStyleClass().add("tag-status-frozen");
                    } else if ("DELETED".equalsIgnoreCase(status)) {
                        badge.setText("已注销");
                        badge.getStyleClass().add("tag-status-deleted");
                    } else {
                        badge.setText("正常");
                        badge.getStyleClass().add("tag-status-active");
                    }
                    setGraphic(badge);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setAlignment(Pos.CENTER);
                }
            }
        });

        colActions.setCellFactory(col -> new TableCell<User, Void>() {
            private final Button editBtn = new Button("编辑");
            private final Button freezeBtn = new Button();
            private final Button resetBtn = new Button("重置密码");
            private final Button deleteBtn = new Button("注销");
            private final HBox container = new HBox(6, editBtn, freezeBtn, resetBtn, deleteBtn);

            {
                container.setAlignment(Pos.CENTER);
                editBtn.getStyleClass().addAll("btn-table-action", "btn-table-edit");
                resetBtn.getStyleClass().addAll("btn-table-action", "btn-table-reset");
                deleteBtn.getStyleClass().addAll("btn-table-action", "btn-table-delete");

                editBtn.setOnAction(e -> {
                    User u = getTableRowUser();
                    if (u != null) openEditDialog(u);
                });

                freezeBtn.setOnAction(e -> {
                    User u = getTableRowUser();
                    if (u != null) handleToggleFreeze(u);
                });

                resetBtn.setOnAction(e -> {
                    User u = getTableRowUser();
                    if (u != null) handleResetPassword(u);
                });

                deleteBtn.setOnAction(e -> {
                    User u = getTableRowUser();
                    if (u != null) handleDeleteUser(u);
                });
            }

            private User getTableRowUser() {
                int idx = getIndex();
                if (idx >= 0 && getTableView() != null && idx < getTableView().getItems().size()) {
                    return getTableView().getItems().get(idx);
                }
                return null;
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                User user = getTableRowUser();
                if (empty || user == null) {
                    setGraphic(null);
                } else {
                    String status = user.getStatus();
                    boolean isFrozen = "FROZEN".equalsIgnoreCase(status);
                    boolean isDeleted = "DELETED".equalsIgnoreCase(status);

                    freezeBtn.getStyleClass().removeAll("btn-table-action", "btn-table-freeze", "btn-table-unfreeze");
                    if (isFrozen) {
                        freezeBtn.setText("解冻");
                        freezeBtn.getStyleClass().addAll("btn-table-action", "btn-table-unfreeze");
                        freezeBtn.setDisable(false);
                    } else if (isDeleted) {
                        freezeBtn.setText("冻结");
                        freezeBtn.getStyleClass().addAll("btn-table-action", "btn-table-freeze");
                        freezeBtn.setDisable(true);
                    } else {
                        freezeBtn.setText("冻结");
                        freezeBtn.getStyleClass().addAll("btn-table-action", "btn-table-freeze");
                        freezeBtn.setDisable(false);
                    }

                    if (isDeleted) {
                        deleteBtn.setText("已注销");
                        deleteBtn.setDisable(true);
                        editBtn.setDisable(true);
                        resetBtn.setDisable(true);
                    } else {
                        deleteBtn.setText("注销");
                        deleteBtn.setDisable(false);
                        editBtn.setDisable(false);
                        resetBtn.setDisable(false);
                    }

                    setGraphic(container);
                }
            }
        });
    }

    @FXML
    public void handleSearch(ActionEvent event) {
        loadUsers();
    }

    @FXML
    public void handleResetFilter(ActionEvent event) {
        searchField.clear();
        roleFilterComboBox.getSelectionModel().selectFirst();
        statusFilterComboBox.getSelectionModel().selectFirst();
        loadUsers();
    }

    @FXML
    public void handleRefresh(ActionEvent event) {
        loadUsers();
    }

    private void loadUsers() {
        if (statusLabel != null) {
            statusLabel.setText("正在加载数据...");
        }

        String keyword = searchField.getText() != null ? searchField.getText().trim() : "";
        String roleVal = roleFilterComboBox.getValue();
        String statusVal = statusFilterComboBox.getValue();

        String roleParam = null;
        if (roleVal != null && !"全部角色".equals(roleVal)) {
            roleParam = roleVal;
        }

        String statusParam = null;
        if ("正常".equals(statusVal)) {
            statusParam = "ACTIVE";
        } else if ("已冻结".equals(statusVal)) {
            statusParam = "FROZEN";
        } else if ("已注销".equals(statusVal)) {
            statusParam = "DELETED";
        }

        Message request = new Message(MessageType.REQUEST, "user", "admin_list_users");
        if (!keyword.isEmpty()) request.putData("keyword", keyword);
        if (roleParam != null) request.putData("role", roleParam);
        if (statusParam != null) request.putData("status", statusParam);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        Object obj = response.getData("users");
                        if (obj != null) {
                            Type type = new TypeToken<List<User>>() {}.getType();
                            List<User> list = gson.fromJson(gson.toJson(obj), type);
                            userObservableList.setAll(list);
                            if (statusLabel != null) {
                                statusLabel.setText("共查询到 " + list.size() + " 位用户");
                            }
                        }
                    } else {
                        if (statusLabel != null) {
                            statusLabel.setText("加载失败: " + response.getMessage());
                        }
                        AlertUtil.showError("获取数据失败", response.getMessage() != null ? response.getMessage() : "请求异常");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (statusLabel != null) {
                            statusLabel.setText("网络连接失败");
                        }
                        AlertUtil.showError("网络异常", "无法连接服务端: " + ex.getMessage());
                    });
                    return null;
                });
    }

    private void openEditDialog(User user) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("编辑用户基本资料");
        dialog.setHeaderText("正在编辑用户：" + (user.getName() != null ? user.getName() : "") + " (" + user.getUID() + ")");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        grid.setPadding(new Insets(20, 24, 10, 24));

        TextField uidField = new TextField(user.getUID());
        uidField.setDisable(true);
        uidField.setPrefWidth(220);

        TextField nameField = new TextField(user.getName() != null ? user.getName() : "");
        ComboBox<String> genderBox = new ComboBox<>(FXCollections.observableArrayList("男", "女"));
        genderBox.setValue(user.getGender() != null && !user.getGender().isEmpty() ? user.getGender() : "男");
        genderBox.setPrefWidth(220);

        TextField collegeField = new TextField(user.getCollege() != null ? user.getCollege() : "");
        TextField majorField = new TextField(user.getMajor() != null ? user.getMajor() : "");
        TextField phoneField = new TextField(user.getPhone() != null ? user.getPhone() : "");
        TextField emailField = new TextField(user.getEmail() != null ? user.getEmail() : "");

        grid.add(new Label("一卡通号:"), 0, 0);
        grid.add(uidField, 1, 0);

        grid.add(new Label("姓名:"), 0, 1);
        grid.add(nameField, 1, 1);

        grid.add(new Label("性别:"), 0, 2);
        grid.add(genderBox, 1, 2);

        grid.add(new Label("学院 / 部门:"), 0, 3);
        grid.add(collegeField, 1, 3);

        grid.add(new Label("专业 / 职务:"), 0, 4);
        grid.add(majorField, 1, 4);

        grid.add(new Label("手机号码:"), 0, 5);
        grid.add(phoneField, 1, 5);

        grid.add(new Label("电子邮箱:"), 0, 6);
        grid.add(emailField, 1, 6);

        dialog.getDialogPane().setContent(grid);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            String newName = nameField.getText().trim();
            if (newName.isEmpty()) {
                AlertUtil.showWarning("校验失败", "姓名不能为空");
                return;
            }

            User updatedUser = new User();
            updatedUser.setUID(user.getUID());
            updatedUser.setName(newName);
            updatedUser.setGender(genderBox.getValue());
            updatedUser.setCollege(collegeField.getText().trim());
            updatedUser.setMajor(majorField.getText().trim());
            updatedUser.setPhone(phoneField.getText().trim());
            updatedUser.setEmail(emailField.getText().trim());
            updatedUser.setRole(user.getRole());

            Message request = new Message(MessageType.REQUEST, "user", "admin_update_user");
            request.putData("user", updatedUser);

            SocketClient.getInstance().sendAsync(request)
                    .thenAccept(response -> Platform.runLater(() -> {
                        if (response.getCode() == MessageCode.SUCCESS) {
                            AlertUtil.showInfo("成功", "用户资料更新成功！");
                            loadUsers();
                        } else {
                            AlertUtil.showError("更新失败", response.getMessage() != null ? response.getMessage() : "未知错误");
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtil.showError("网络异常", "更新请求失败: " + ex.getMessage()));
                        return null;
                    });
        }
    }

    private void handleToggleFreeze(User user) {
        boolean isFrozen = "FROZEN".equalsIgnoreCase(user.getStatus());
        String targetStatus = isFrozen ? "ACTIVE" : "FROZEN";
        String prompt = isFrozen
                ? "确定要解冻账号 " + user.getUID() + " (" + user.getName() + ") 吗？解冻后用户可重新正常登录。"
                : "确定要冻结账号 " + user.getUID() + " (" + user.getName() + ") 吗？\n冻结后该账号若当前在线将立即被强制踢下线并禁止登录。";

        if (AlertUtil.showConfirm("确认操作", prompt) == ButtonType.OK) {
            Message request = new Message(MessageType.REQUEST, "user", "admin_update_status");
            request.putData("targetUid", user.getUID());
            request.putData("status", targetStatus);

            SocketClient.getInstance().sendAsync(request)
                    .thenAccept(response -> Platform.runLater(() -> {
                        if (response.getCode() == MessageCode.SUCCESS) {
                            AlertUtil.showInfo("成功", isFrozen ? "账号已成功解冻！" : "账号已冻结，在线连接已强制下线！");
                            loadUsers();
                        } else {
                            AlertUtil.showError("操作失败", response.getMessage() != null ? response.getMessage() : "更新状态失败");
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtil.showError("网络异常", "请求失败: " + ex.getMessage()));
                        return null;
                    });
        }
    }

    private void handleResetPassword(User user) {
        String prompt = "确定要将用户 " + user.getUID() + " (" + user.getName() + ") 的密码重置为默认密码 123456 吗？";
        if (AlertUtil.showConfirm("重置密码确认", prompt) == ButtonType.OK) {
            Message request = new Message(MessageType.REQUEST, "user", "admin_reset_password");
            request.putData("targetUid", user.getUID());

            SocketClient.getInstance().sendAsync(request)
                    .thenAccept(response -> Platform.runLater(() -> {
                        if (response.getCode() == MessageCode.SUCCESS) {
                            AlertUtil.showInfo("密码重置成功", "用户 " + user.getUID() + " 的密码已重置为默认密码：123456");
                        } else {
                            AlertUtil.showError("重置失败", response.getMessage() != null ? response.getMessage() : "服务端错误");
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtil.showError("网络异常", "请求失败: " + ex.getMessage()));
                        return null;
                    });
        }
    }

    private void handleDeleteUser(User user) {
        String prompt = "警告：确定要注销账号 " + user.getUID() + " (" + user.getName() + ") 吗？\n注销后该账号将无法再登录系统，若当前在线将被强制踢下线。";
        if (AlertUtil.showConfirm("注销确认", prompt) == ButtonType.OK) {
            Message request = new Message(MessageType.REQUEST, "user", "admin_update_status");
            request.putData("targetUid", user.getUID());
            request.putData("status", "DELETED");

            SocketClient.getInstance().sendAsync(request)
                    .thenAccept(response -> Platform.runLater(() -> {
                        if (response.getCode() == MessageCode.SUCCESS) {
                            AlertUtil.showInfo("注销成功", "用户账号已注销，若在线已强制下线！");
                            loadUsers();
                        } else {
                            AlertUtil.showError("注销失败", response.getMessage() != null ? response.getMessage() : "服务端错误");
                        }
                    }))
                    .exceptionally(ex -> {
                        Platform.runLater(() -> AlertUtil.showError("网络异常", "请求失败: " + ex.getMessage()));
                        return null;
                    });
        }
    }

    @FXML
    public void handleBack(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }
}
