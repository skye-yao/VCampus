package controller;

import app.ClientMain;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import entity.AdminPermission;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.AlertUtil;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 管理员权限管理控制器
 *
 * 负责展示所有管理员账户，并由 UID 为 admin 的主管理员进行细粒度子系统权限分配。
 */
public class PermissionController {

    @FXML private TableView<AdminPermission> permissionTable;
    @FXML private TableColumn<AdminPermission, String> colUid;
    @FXML private TableColumn<AdminPermission, String> colName;
    @FXML private TableColumn<AdminPermission, Boolean> colAcademic;
    @FXML private TableColumn<AdminPermission, Boolean> colLibrary;
    @FXML private TableColumn<AdminPermission, Boolean> colCourse;
    @FXML private TableColumn<AdminPermission, Boolean> colShop;
    @FXML private TableColumn<AdminPermission, Boolean> colBank;
    @FXML private Label statusLabel;

    private final Gson gson = new Gson();

    @FXML
    public void initialize() {
        setupTableColumns();
        loadAdminPermissions();
    }

    private void setupTableColumns() {
        // UID 列（只读）
        colUid.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getUid()));
        colUid.setStyle("-fx-alignment: CENTER;");

        // 姓名列（只读）
        colName.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().getName()));
        colName.setStyle("-fx-alignment: CENTER;");

        // 学籍权限
        setupCheckBoxColumn(colAcademic, AdminPermission::isAcademicPerm, AdminPermission::setAcademicPerm);

        // 图书馆权限
        setupCheckBoxColumn(colLibrary, AdminPermission::isLibraryPerm, AdminPermission::setLibraryPerm);

        // 选课权限
        setupCheckBoxColumn(colCourse, AdminPermission::isCoursePerm, AdminPermission::setCoursePerm);

        // 商店权限
        setupCheckBoxColumn(colShop, AdminPermission::isShopPerm, AdminPermission::setShopPerm);

        // 银行权限
        setupCheckBoxColumn(colBank, AdminPermission::isBankPerm, AdminPermission::setBankPerm);
    }

    /**
     * 为权限列配置 CheckBox 渲染器：
     * 主管理员（UID == admin）与各普通管理员方框均可正常打勾与取消打勾。
     */
    private void setupCheckBoxColumn(TableColumn<AdminPermission, Boolean> column,
                                    Function<AdminPermission, Boolean> getter,
                                    BiConsumer<AdminPermission, Boolean> setter) {
        column.setCellValueFactory(cellData -> new SimpleBooleanProperty(getter.apply(cellData.getValue())));
        column.setCellFactory(col -> new TableCell<AdminPermission, Boolean>() {
            private final CheckBox checkBox = new CheckBox();

            {
                checkBox.setAlignment(Pos.CENTER);
                checkBox.setOnAction(event -> {
                    AdminPermission item = null;
                    int idx = getIndex();
                    if (idx >= 0 && getTableView() != null && idx < getTableView().getItems().size()) {
                        item = getTableView().getItems().get(idx);
                    } else if (getTableRow() != null && getTableRow().getItem() != null) {
                        item = (AdminPermission) getTableRow().getItem();
                    }
                    if (item != null) {
                        setter.accept(item, checkBox.isSelected());
                    }
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                AdminPermission perm = null;
                int idx = getIndex();
                if (idx >= 0 && getTableView() != null && idx < getTableView().getItems().size()) {
                    perm = getTableView().getItems().get(idx);
                } else if (getTableRow() != null && getTableRow().getItem() != null) {
                    perm = (AdminPermission) getTableRow().getItem();
                }

                if (empty || perm == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    checkBox.setDisable(false);
                    checkBox.setSelected(getter.apply(perm));
                    setGraphic(checkBox);
                    setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    setAlignment(Pos.CENTER);
                }
            }
        });
    }

    /**
     * 异步从服务端获取所有管理员列表及当前权限
     */
    @FXML
    public void loadAdminPermissions() {
        if (statusLabel != null) {
            statusLabel.setText("正在加载数据...");
        }

        Message request = new Message(MessageType.REQUEST, "user", "list_admin_permissions");
        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        Object permObj = response.getData("permissions");
                        if (permObj != null) {
                            Type type = new TypeToken<List<AdminPermission>>() {}.getType();
                            List<AdminPermission> list = gson.fromJson(gson.toJson(permObj), type);
                            ObservableList<AdminPermission> data = FXCollections.observableArrayList(list);
                            permissionTable.setItems(data);
                            if (statusLabel != null) {
                                statusLabel.setText("共加载 " + list.size() + " 位管理员");
                            }
                        }
                    } else {
                        if (statusLabel != null) {
                            statusLabel.setText("加载失败: " + response.getMessage());
                        }
                        AlertUtil.showError("获取数据失败", response.getMessage() != null ? response.getMessage() : "网络请求异常");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> {
                        if (statusLabel != null) {
                            statusLabel.setText("连接服务端失败");
                        }
                        AlertUtil.showError("网络异常", "无法连接服务端: " + ex.getMessage());
                    });
                    return null;
                });
    }

    /**
     * 确认并通信后台修改权限
     */
    @FXML
    public void handleConfirm(ActionEvent event) {
        ObservableList<AdminPermission> items = permissionTable.getItems();
        if (items == null || items.isEmpty()) {
            AlertUtil.showInfo("提示", "当前没有需要保存的管理员信息");
            return;
        }

        List<AdminPermission> list = new ArrayList<>(items);
        Message request = new Message(MessageType.REQUEST, "user", "update_admin_permissions");
        request.putData("permissions", list);

        SocketClient.getInstance().sendAsync(request)
                .thenAccept(response -> Platform.runLater(() -> {
                    if (response.getCode() == MessageCode.SUCCESS) {
                        AlertUtil.showInfo("保存成功", "管理员权限配置已成功同步到数据库！");
                        loadAdminPermissions();
                    } else {
                        AlertUtil.showError("保存失败", response.getMessage() != null ? response.getMessage() : "未知错误");
                    }
                }))
                .exceptionally(ex -> {
                    Platform.runLater(() -> AlertUtil.showError("网络异常", "保存请求失败: " + ex.getMessage()));
                    return null;
                });
    }

    /**
     * 返回退回主页
     */
    @FXML
    public void handleBack(ActionEvent event) {
        ClientMain.switchScene("/resources/fxml/MainView.fxml");
    }
}
