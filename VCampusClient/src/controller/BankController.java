package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.BankAccount;
import entity.BankTransaction;
import entity.FinanceBill;
import entity.FinanceChargeTarget;
import entity.Reimbursement;
import enums.BankTransactionType;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import network.SocketClient;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import util.AlertUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.function.Consumer;

/** 校园银行基础版JavaFX控制器。 */
public class BankController {
    @FXML private Label balanceLabel;
    @FXML private Label balanceCaptionLabel;
    @FXML private Label accountStatusLabel;
    @FXML private Label passwordStatusLabel;
    @FXML private TableView<BankTransaction> transactionTable;
    @FXML private TableColumn<BankTransaction, String> txNoColumn;
    @FXML private TableColumn<BankTransaction, BankTransactionType> txTypeColumn;
    @FXML private TableColumn<BankTransaction, BigDecimal> txAmountColumn;
    @FXML private TableColumn<BankTransaction, BigDecimal> txBalanceColumn;
    @FXML private TableColumn<BankTransaction, String> txCounterpartyColumn;
    @FXML private TableColumn<BankTransaction, String> txRemarkColumn;
    @FXML private TableColumn<BankTransaction, String> txTimeColumn;
    @FXML private TextField targetUserField;
    @FXML private TextField transferAmountField;
    @FXML private PasswordField transferPasswordField;
    @FXML private VBox singleTransferPanel;
    @FXML private VBox adminBatchTransferPanel;
    @FXML private TextField transferTargetKeyword;
    @FXML private ComboBox<String> transferRoleFilter;
    @FXML private ComboBox<String> transferCollegeFilter;
    @FXML private TableView<FinanceChargeTarget> transferTargetTable;
    @FXML private TableColumn<FinanceChargeTarget, FinanceChargeTarget> transferSelectColumn;
    @FXML private TableColumn<FinanceChargeTarget, String> transferUserColumn;
    @FXML private TableColumn<FinanceChargeTarget, String> transferNameColumn;
    @FXML private TableColumn<FinanceChargeTarget, String> transferRoleColumn;
    @FXML private TableColumn<FinanceChargeTarget, String> transferCollegeColumn;
    @FXML private TableColumn<FinanceChargeTarget, String> transferMajorColumn;
    @FXML private Label transferSelectionSummary;
    @FXML private TextField batchTransferAmountField;
    @FXML private TextField batchTransferRemarkField;
    @FXML private PasswordField batchTransferPasswordField;
    @FXML private PasswordField oldPasswordField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private TableView<FinanceBill> billTable;
    @FXML private Tab billTab;
    @FXML private TableColumn<FinanceBill, String> billUserColumn;
    @FXML private TableColumn<FinanceBill, String> billUserNameColumn;
    @FXML private TableColumn<FinanceBill, String> billTitleColumn;
    @FXML private TableColumn<FinanceBill, String> billTypeColumn;
    @FXML private TableColumn<FinanceBill, BigDecimal> billAmountColumn;
    @FXML private TableColumn<FinanceBill, String> billStatusColumn;
    @FXML private TableColumn<FinanceBill, String> billDueColumn;
    @FXML private HBox payBillBox;
    @FXML private VBox billAdminPanel;
    @FXML private TextField billKeywordField;
    @FXML private ComboBox<String> billTypeFilter;
    @FXML private ComboBox<String> billStatusFilter;
    @FXML private Label billPeopleSummaryLabel;
    @FXML private Label billCountSummaryLabel;
    @FXML private TableView<Reimbursement> reimbursementTable;
    @FXML private TableColumn<Reimbursement, String> reimbursementTitleColumn;
    @FXML private TableColumn<Reimbursement, BigDecimal> reimbursementAmountColumn;
    @FXML private TableColumn<Reimbursement, String> reimbursementStatusColumn;
    @FXML private TableColumn<Reimbursement, String> reimbursementApplicantColumn;
    @FXML private TableColumn<Reimbursement, String> reimbursementTransactionColumn;
    @FXML private TextField reimbursementTitleField;
    @FXML private TextField reimbursementAmountField;
    @FXML private TextArea reimbursementReasonArea;
    @FXML private HBox adminReviewBox;
    @FXML private SplitPane reimbursementSplitPane;
    @FXML private VBox reimbursementApplyPanel;
    @FXML private HBox adminPasswordResetBox;
    @FXML private TextField resetPasswordUserField;

    private final Gson gson = new Gson();
    private final ObservableList<FinanceChargeTarget> allTransferTargets = FXCollections.observableArrayList();
    private BankAccount currentAccount;

    @FXML
    public void initialize() {
        txNoColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("transactionNo"));
        txTypeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("transactionType"));
        txAmountColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        txBalanceColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("balanceAfter"));
        txCounterpartyColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("counterpartyUserId"));
        txRemarkColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("remark"));
        txTimeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("createdAt"));
        billUserColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("userId"));
        billUserNameColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("userName"));
        billTitleColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("title"));
        billTypeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("billType"));
        billAmountColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        billStatusColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("status"));
        billDueColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("dueDate"));
        billTypeColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String type, boolean empty) {
                super.updateItem(type, empty);
                setText(empty || type == null ? null : switch (type) {
                    case "TUITION" -> "学费";
                    case "ACCOMMODATION" -> "住宿费";
                    case "OTHER" -> "其他费用";
                    default -> type;
                });
            }
        });
        billStatusColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : switch (status) {
                    case "UNPAID" -> "待缴费";
                    case "PAID" -> "已缴费";
                    case "CANCELLED" -> "已取消";
                    default -> status;
                });
            }
        });
        reimbursementTitleColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("title"));
        reimbursementAmountColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        reimbursementStatusColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("status"));
        reimbursementApplicantColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("applicantId"));
        reimbursementTransactionColumn.setCellValueFactory(
                new javafx.scene.control.cell.PropertyValueFactory<>("paymentTransactionNo"));
        reimbursementStatusColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String status, boolean empty) {
                super.updateItem(status, empty);
                setText(empty || status == null ? null : switch (status) {
                    case "APPLIED" -> "待审核";
                    case "APPROVED" -> "已通过并入账";
                    case "REJECTED" -> "已驳回";
                    default -> status;
                });
            }
        });
        txTypeColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(BankTransactionType type, boolean empty) {
                super.updateItem(type, empty);
                setText(empty || type == null ? null : type.getDescription());
            }
        });
        txAmountColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(BigDecimal amount, boolean empty) {
                super.updateItem(amount, empty);
                setText(empty || amount == null ? null : (amount.signum() > 0 ? "+¥" : "-¥") + amount.abs());
                setStyle(empty || amount == null ? "" : amount.signum() > 0
                        ? "-fx-text-fill:#2e7d32;-fx-font-weight:bold;"
                        : "-fx-text-fill:#b23b32;-fx-font-weight:bold;");
            }
        });
        boolean admin = isAdmin();
        billTypeFilter.setItems(FXCollections.observableArrayList("全部类型", "学费", "住宿费", "其他费用"));
        billStatusFilter.setItems(FXCollections.observableArrayList("全部状态", "待缴费", "已缴费", "已取消"));
        billTypeFilter.getSelectionModel().selectFirst();
        billStatusFilter.getSelectionModel().selectFirst();
        balanceCaptionLabel.setText(admin ? "校园财务账户可用余额（管理员操作）" : "当前校园账户余额");
        adminReviewBox.setVisible(admin); adminReviewBox.setManaged(admin);
        billUserColumn.setVisible(admin);
        billUserNameColumn.setVisible(admin);
        billAdminPanel.setVisible(admin); billAdminPanel.setManaged(admin);
        billTab.setText(admin ? "缴费账单管理" : "校园缴费");
        payBillBox.setVisible(!admin); payBillBox.setManaged(!admin);
        adminPasswordResetBox.setVisible(admin); adminPasswordResetBox.setManaged(admin);
        singleTransferPanel.setVisible(!admin); singleTransferPanel.setManaged(!admin);
        adminBatchTransferPanel.setVisible(admin); adminBatchTransferPanel.setManaged(admin);
        if (admin) initializeAdminBatchTransfer();
        if (admin) reimbursementSplitPane.getItems().remove(reimbursementApplyPanel);
        refreshAll();
    }

    @FXML private void handleBack() { ClientMain.switchScene("/resources/fxml/MainView.fxml"); }
    @FXML private void handleRefresh() { refreshAll(); }

    @FXML private void handleSearchBills() { refreshBills(); }

    @FXML
    private void handleCreateCharge() {
        if (!isAdmin()) {
            AlertUtil.showWarning("发起收费", "仅管理员可以发起收费");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("管理员发起收费");
        dialog.setHeaderText("选择收费对象并创建校园缴费账单");
        dialog.setResizable(true);
        ButtonType createType = new ButtonType("确认发起", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createType, ButtonType.CANCEL);
        if (getClass().getResource("/resources/css/style.css") != null) {
            dialog.getDialogPane().getStylesheets().add(
                    getClass().getResource("/resources/css/style.css").toExternalForm());
        }

        ComboBox<String> typeBox = new ComboBox<>(
                FXCollections.observableArrayList("学费", "住宿费", "其他费用"));
        typeBox.getSelectionModel().select("其他费用");
        typeBox.getStyleClass().add("form-control");
        TextField titleField = new TextField();
        titleField.setPromptText("例如：2026年校园体检费");
        titleField.getStyleClass().add("form-control");
        TextField amountField = new TextField();
        amountField.setPromptText("例如：120.00");
        amountField.getStyleClass().add("form-control");
        DatePicker dueDatePicker = new DatePicker(LocalDate.now().plusDays(30));
        dueDatePicker.getStyleClass().add("form-control");

        GridPane form = new GridPane();
        form.setHgap(10); form.setVgap(10);
        form.add(new Label("收费类型"), 0, 0); form.add(typeBox, 1, 0);
        form.add(new Label("收费名称"), 2, 0); form.add(titleField, 3, 0);
        form.add(new Label("金额（元）"), 0, 1); form.add(amountField, 1, 1);
        form.add(new Label("截止日期"), 2, 1); form.add(dueDatePicker, 3, 1);
        GridPane.setHgrow(titleField, javafx.scene.layout.Priority.ALWAYS);

        TextField targetKeyword = new TextField();
        targetKeyword.setPromptText("筛选编号、姓名、学院或专业");
        targetKeyword.getStyleClass().add("form-control");
        targetKeyword.setPrefWidth(260);
        ComboBox<String> roleFilter = new ComboBox<>(
                FXCollections.observableArrayList("全部身份", "学生", "教师"));
        roleFilter.getSelectionModel().selectFirst();
        roleFilter.getStyleClass().add("form-control");
        ComboBox<String> collegeFilter = new ComboBox<>();
        collegeFilter.getItems().add("全部学院");
        collegeFilter.getSelectionModel().selectFirst();
        collegeFilter.getStyleClass().add("form-control");
        collegeFilter.setPrefWidth(210);
        HBox filters = new HBox(10, targetKeyword, roleFilter, collegeFilter);

        ObservableList<FinanceChargeTarget> allTargets = FXCollections.observableArrayList();
        TableView<FinanceChargeTarget> targetTable = new TableView<>();
        targetTable.getStyleClass().add("shop-table");
        targetTable.setPrefHeight(360);
        targetTable.setPlaceholder(new Label("正在加载可收费用户……"));
        TableColumn<FinanceChargeTarget, FinanceChargeTarget> selectedColumn = new TableColumn<>("选择");
        selectedColumn.setPrefWidth(70);
        TableColumn<FinanceChargeTarget, String> userColumn = new TableColumn<>("用户编号");
        userColumn.setPrefWidth(130);
        TableColumn<FinanceChargeTarget, String> nameColumn = new TableColumn<>("姓名");
        nameColumn.setPrefWidth(100);
        TableColumn<FinanceChargeTarget, String> roleColumn = new TableColumn<>("身份");
        roleColumn.setPrefWidth(80);
        TableColumn<FinanceChargeTarget, String> collegeColumn = new TableColumn<>("学院");
        collegeColumn.setPrefWidth(210);
        TableColumn<FinanceChargeTarget, String> majorColumn = new TableColumn<>("专业/职称");
        majorColumn.setPrefWidth(180);
        selectedColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        userColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("userId"));
        nameColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        roleColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("roleName"));
        collegeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("college"));
        majorColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("major"));
        targetTable.getColumns().addAll(selectedColumn, userColumn, nameColumn, roleColumn, collegeColumn, majorColumn);

        Label selectionSummary = new Label("已选择 0 人 / 当前结果 0 人");
        selectionSummary.getStyleClass().add("summary-chip");
        Runnable updateSummary = () -> selectionSummary.setText("已选择 " +
                allTargets.stream().filter(FinanceChargeTarget::isSelected).count() +
                " 人 / 当前结果 " + targetTable.getItems().size() + " 人");
        selectedColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    FinanceChargeTarget target = getItem();
                    if (target != null) target.setSelected(checkBox.isSelected());
                    updateSummary.run();
                });
            }
            @Override protected void updateItem(FinanceChargeTarget target, boolean empty) {
                super.updateItem(target, empty);
                if (empty || target == null) { setGraphic(null); }
                else { checkBox.setSelected(target.isSelected()); setGraphic(checkBox); }
            }
        });

        Runnable applyTargetFilter = () -> {
            String keyword = targetKeyword.getText() == null ? "" : targetKeyword.getText().trim().toLowerCase();
            String selectedRole = roleFilter.getValue();
            String selectedCollege = collegeFilter.getValue();
            List<FinanceChargeTarget> filtered = allTargets.stream().filter(target -> {
                boolean keywordMatches = keyword.isEmpty() ||
                        containsIgnoreCase(target.getUserId(), keyword) ||
                        containsIgnoreCase(target.getName(), keyword) ||
                        containsIgnoreCase(target.getCollege(), keyword) ||
                        containsIgnoreCase(target.getMajor(), keyword);
                boolean roleMatches = selectedRole == null || "全部身份".equals(selectedRole) ||
                        selectedRole.equals(target.getRoleName());
                boolean collegeMatches = selectedCollege == null || "全部学院".equals(selectedCollege) ||
                        selectedCollege.equals(target.getCollege());
                return keywordMatches && roleMatches && collegeMatches;
            }).toList();
            targetTable.setItems(FXCollections.observableArrayList(filtered));
            updateSummary.run();
        };
        targetKeyword.textProperty().addListener((observable, oldValue, newValue) -> applyTargetFilter.run());
        roleFilter.setOnAction(event -> applyTargetFilter.run());
        collegeFilter.setOnAction(event -> applyTargetFilter.run());

        Button selectAllButton = new Button("全选当前结果");
        selectAllButton.getStyleClass().add("btn-secondary");
        selectAllButton.setOnAction(event -> {
            targetTable.getItems().forEach(target -> target.setSelected(true));
            targetTable.refresh(); updateSummary.run();
        });
        Button clearButton = new Button("清空选择");
        clearButton.getStyleClass().add("btn-secondary");
        clearButton.setOnAction(event -> {
            allTargets.forEach(target -> target.setSelected(false));
            targetTable.refresh(); updateSummary.run();
        });
        HBox selectionBar = new HBox(10, selectAllButton, clearButton, selectionSummary);

        VBox content = new VBox(12, form, new Separator(), filters, targetTable, selectionBar);
        content.setPadding(new Insets(6));
        content.setPrefWidth(900);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(940, 650);

        Button createButton = (Button) dialog.getDialogPane().lookupButton(createType);
        createButton.setDisable(true);
        createButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            List<String> targetIds = allTargets.stream()
                    .filter(FinanceChargeTarget::isSelected)
                    .map(FinanceChargeTarget::getUserId).toList();
            if (targetIds.isEmpty()) {
                AlertUtil.showWarning("发起收费", "请至少选择一名收费对象"); return;
            }
            if (titleField.getText().trim().isEmpty() || amountField.getText().trim().isEmpty() ||
                    dueDatePicker.getValue() == null) {
                AlertUtil.showWarning("发起收费", "请完整填写收费名称、金额和截止日期"); return;
            }
            Message request = request(MessageType.FINANCE_BILL_CREATE);
            request.putData("targetUserIds", targetIds);
            request.putData("billType", switch (typeBox.getValue()) {
                case "学费" -> "TUITION";
                case "住宿费" -> "ACCOMMODATION";
                default -> "OTHER";
            });
            request.putData("title", titleField.getText().trim());
            request.putData("amount", amountField.getText().trim());
            request.putData("dueDate", dueDatePicker.getValue().toString());
            createButton.setDisable(true);
            SocketClient.getInstance().sendAsync(request).thenAccept(response -> Platform.runLater(() -> {
                if (response.getCode() == MessageCode.SUCCESS) {
                    long created = wholeNumber(response.getData("createdCount"));
                    long skipped = wholeNumber(response.getData("skippedCount"));
                    dialog.close();
                    AlertUtil.showInfo("发起收费", "成功创建 " + created + " 条账单" +
                            (skipped > 0 ? "\n跳过 " + skipped + " 人（已有同名账单或用户不可收费）" : ""));
                    refreshBills();
                } else {
                    createButton.setDisable(false);
                    AlertUtil.showError("发起收费失败", response.getMessage());
                }
            })).exceptionally(ex -> {
                Platform.runLater(() -> {
                    createButton.setDisable(false);
                    AlertUtil.showError("网络异常", "发起收费请求失败：" + ex.getMessage());
                });
                return null;
            });
        });

        Message targetRequest = request(MessageType.FINANCE_BILL_TARGET_LIST);
        send(targetRequest, response -> {
            FinanceChargeTarget[] targets = gson.fromJson(
                    gson.toJson((Object) response.getData("targets")), FinanceChargeTarget[].class);
            allTargets.setAll(targets);
            allTargets.stream().map(FinanceChargeTarget::getCollege)
                    .filter(value -> value != null && !value.isBlank()).distinct().sorted()
                    .forEach(collegeFilter.getItems()::add);
            applyTargetFilter.run();
            createButton.setDisable(false);
        });
        dialog.show();
    }

    @FXML private void handleResetBillFilters() {
        billKeywordField.clear();
        billTypeFilter.getSelectionModel().selectFirst();
        billStatusFilter.getSelectionModel().selectFirst();
        refreshBills();
    }

    @FXML
    private void handleTransfer() {
        String target = targetUserField.getText().trim();
        String amount = transferAmountField.getText().trim();
        String password = transferPasswordField.getText();
        if (target.isEmpty() || amount.isEmpty() || password.isEmpty()) {
            AlertUtil.showWarning("校园转账", "请填写收款人、金额和支付密码");
            return;
        }
        Message request = request(MessageType.BANK_TRANSFER);
        request.putData("targetUserId", target);
        request.putData("amount", amount);
        request.putData("paymentPassword", password);
        request.putData("requestId", UUID.randomUUID().toString());
        transferPasswordField.clear();
        send(request, response -> {
            AlertUtil.showInfo("校园转账", "转账成功\n流水号：" + response.getData("transactionNo"));
            targetUserField.clear(); transferAmountField.clear(); refreshAll();
        });
    }

    private void initializeAdminBatchTransfer() {
        transferRoleFilter.setItems(FXCollections.observableArrayList("全部身份", "学生", "教师"));
        transferRoleFilter.getSelectionModel().selectFirst();
        transferCollegeFilter.setItems(FXCollections.observableArrayList("全部学院"));
        transferCollegeFilter.getSelectionModel().selectFirst();
        transferSelectColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        transferUserColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("userId"));
        transferNameColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("name"));
        transferRoleColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("roleName"));
        transferCollegeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("college"));
        transferMajorColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("major"));
        transferSelectColumn.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setOnAction(event -> {
                    FinanceChargeTarget target = getItem();
                    if (target != null) target.setSelected(checkBox.isSelected());
                    updateTransferSelectionSummary();
                });
            }
            @Override protected void updateItem(FinanceChargeTarget target, boolean empty) {
                super.updateItem(target, empty);
                if (empty || target == null) setGraphic(null);
                else { checkBox.setSelected(target.isSelected()); setGraphic(checkBox); }
            }
        });
        transferTargetKeyword.textProperty().addListener((observable, oldValue, newValue) -> filterTransferTargets());
        transferRoleFilter.setOnAction(event -> filterTransferTargets());
        transferCollegeFilter.setOnAction(event -> filterTransferTargets());
        loadTransferTargets();
    }

    private void loadTransferTargets() {
        transferTargetTable.setPlaceholder(new Label("正在加载可转账用户……"));
        send(request(MessageType.BANK_TRANSFER_TARGET_LIST), response -> {
            FinanceChargeTarget[] targets = gson.fromJson(
                    gson.toJson((Object) response.getData("targets")), FinanceChargeTarget[].class);
            allTransferTargets.setAll(targets);
            transferCollegeFilter.getItems().setAll("全部学院");
            allTransferTargets.stream().map(FinanceChargeTarget::getCollege)
                    .filter(value -> value != null && !value.isBlank()).distinct().sorted()
                    .forEach(transferCollegeFilter.getItems()::add);
            transferCollegeFilter.getSelectionModel().selectFirst();
            filterTransferTargets();
        });
    }

    private void filterTransferTargets() {
        String keyword = transferTargetKeyword.getText() == null
                ? "" : transferTargetKeyword.getText().trim().toLowerCase();
        String role = transferRoleFilter.getValue();
        String college = transferCollegeFilter.getValue();
        List<FinanceChargeTarget> filtered = allTransferTargets.stream().filter(target -> {
            boolean keywordMatches = keyword.isEmpty() ||
                    containsIgnoreCase(target.getUserId(), keyword) ||
                    containsIgnoreCase(target.getName(), keyword) ||
                    containsIgnoreCase(target.getCollege(), keyword) ||
                    containsIgnoreCase(target.getMajor(), keyword);
            boolean roleMatches = role == null || "全部身份".equals(role) || role.equals(target.getRoleName());
            boolean collegeMatches = college == null || "全部学院".equals(college) ||
                    college.equals(target.getCollege());
            return keywordMatches && roleMatches && collegeMatches;
        }).toList();
        transferTargetTable.setItems(FXCollections.observableArrayList(filtered));
        updateTransferSelectionSummary();
    }

    @FXML
    private void handleSelectAllTransferTargets() {
        transferTargetTable.getItems().forEach(target -> target.setSelected(true));
        transferTargetTable.refresh(); updateTransferSelectionSummary();
    }

    @FXML
    private void handleClearTransferTargets() {
        allTransferTargets.forEach(target -> target.setSelected(false));
        transferTargetTable.refresh(); updateTransferSelectionSummary();
    }

    private void updateTransferSelectionSummary() {
        if (transferSelectionSummary == null) return;
        long selected = allTransferTargets.stream().filter(FinanceChargeTarget::isSelected).count();
        int visible = transferTargetTable.getItems() == null ? 0 : transferTargetTable.getItems().size();
        transferSelectionSummary.setText("已选择 " + selected + " 人 / 当前结果 " + visible + " 人");
    }

    @FXML
    private void handleBatchTransfer() {
        List<String> targetIds = allTransferTargets.stream()
                .filter(FinanceChargeTarget::isSelected).map(FinanceChargeTarget::getUserId).toList();
        if (targetIds.isEmpty()) {
            AlertUtil.showWarning("批量转账", "请至少选择一名收款人"); return;
        }
        BigDecimal amount;
        try { amount = new BigDecimal(batchTransferAmountField.getText().trim()).setScale(2); }
        catch (Exception e) { AlertUtil.showWarning("批量转账", "请输入正确的每人转账金额"); return; }
        if (amount.signum() <= 0) { AlertUtil.showWarning("批量转账", "金额必须大于0"); return; }
        if (batchTransferPasswordField.getText().isBlank()) {
            AlertUtil.showWarning("批量转账", "请输入管理员支付密码"); return;
        }
        BigDecimal total = amount.multiply(BigDecimal.valueOf(targetIds.size()));
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将向 " + targetIds.size() + " 人每人转账 ¥" + amount + "，合计 ¥" + total + "。是否继续？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("确认批量转账");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        Message request = request(MessageType.BANK_BATCH_TRANSFER);
        request.putData("targetUserIds", targetIds);
        request.putData("amount", amount.toPlainString());
        request.putData("paymentPassword", batchTransferPasswordField.getText());
        request.putData("requestId", UUID.randomUUID().toString());
        request.putData("remark", batchTransferRemarkField.getText().trim());
        batchTransferPasswordField.clear();
        send(request, response -> {
            AlertUtil.showInfo("批量转账", "已向 " + wholeNumber(response.getData("recipientCount")) +
                    " 人完成转账\n合计金额：¥" + response.getData("totalAmount") +
                    "\n批次流水号：" + response.getData("transactionNo"));
            batchTransferAmountField.clear(); batchTransferRemarkField.clear();
            handleClearTransferTargets(); refreshAccount(); refreshTransactions();
        });
    }

    @FXML
    private void handleSavePassword() {
        String oldPassword = oldPasswordField.getText();
        String newPassword = newPasswordField.getText();
        String confirm = confirmPasswordField.getText();
        if (!newPassword.equals(confirm)) {
            AlertUtil.showWarning("支付密码", "两次输入的新密码不一致");
            return;
        }
        Message request;
        if (currentAccount != null && currentAccount.isPaymentPasswordSet()) {
            request = request(MessageType.BANK_PASSWORD_CHANGE);
            request.putData("oldPassword", oldPassword);
        } else {
            request = request(MessageType.BANK_PASSWORD_SET);
        }
        request.putData("newPassword", newPassword);
        clearPasswords();
        send(request, response -> {
            AlertUtil.showInfo("支付密码", "支付密码保存成功");
            refreshAccount();
        });
    }

    @FXML
    private void handleResetPaymentPassword() {
        String targetUserId = resetPasswordUserField.getText().trim();
        if (targetUserId.isEmpty()) {
            AlertUtil.showWarning("重置支付密码", "请输入用户编号");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "将清除用户 " + targetUserId + " 的原支付密码，并要求用户重新设置。是否继续？",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("重置支付密码");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        Message request = request(MessageType.BANK_PASSWORD_RESET);
        request.putData("targetUserId", targetUserId);
        send(request, response -> {
            AlertUtil.showInfo("重置支付密码", "已重置，用户下次需设置新的6位支付密码");
            resetPasswordUserField.clear();
        });
    }

    @FXML
    private void handlePayBill() {
        FinanceBill bill = billTable.getSelectionModel().getSelectedItem();
        if (bill == null) { AlertUtil.showWarning("校园缴费", "请先选择一条待缴费账单"); return; }
        if (!"UNPAID".equals(bill.getStatus())) { AlertUtil.showWarning("校园缴费", "该账单已经处理"); return; }
        Optional<String> password = showPaymentPassword(bill.getTitle() + "  ¥" + bill.getAmount());
        if (password.isEmpty()) return;
        Message request = request(MessageType.FINANCE_BILL_PAY);
        request.putData("billId", bill.getBillId()); request.putData("paymentPassword", password.get());
        request.putData("requestId", UUID.randomUUID().toString());
        send(request, response -> { AlertUtil.showInfo("校园缴费", "缴费成功"); refreshAll(); });
    }

    @FXML
    private void handleApplyReimbursement() {
        Message request = request(MessageType.FINANCE_REIMBURSEMENT_APPLY);
        request.putData("title", reimbursementTitleField.getText().trim());
        request.putData("amount", reimbursementAmountField.getText().trim());
        request.putData("reason", reimbursementReasonArea.getText().trim());
        send(request, response -> {
            AlertUtil.showInfo("报销申请", "报销申请已提交");
            reimbursementTitleField.clear(); reimbursementAmountField.clear(); reimbursementReasonArea.clear();
            refreshReimbursements();
        });
    }

    @FXML private void handleApproveReimbursement() { reviewSelected(true); }
    @FXML private void handleRejectReimbursement() { reviewSelected(false); }

    private void reviewSelected(boolean approved) {
        Reimbursement item = reimbursementTable.getSelectionModel().getSelectedItem();
        if (item == null) { AlertUtil.showWarning("报销审核", "请先选择一条报销申请"); return; }
        if (!"APPLIED".equals(item.getStatus())) {
            AlertUtil.showWarning("报销审核", "只有待审核的报销申请可以处理");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("报销审核"); dialog.setHeaderText((approved ? "通过：" : "驳回：") + item.getTitle());
        dialog.setContentText("审核意见：");
        Optional<String> comment = dialog.showAndWait(); if (comment.isEmpty()) return;
        String paymentPassword = "";
        if (approved) {
            Optional<String> password = showPaymentPassword(
                    "确认从校园财务账户支付报销款 ¥" + item.getAmount());
            if (password.isEmpty()) return;
            paymentPassword = password.get();
        }
        Message request = request(MessageType.FINANCE_REIMBURSEMENT_REVIEW);
        request.putData("reimbursementId", item.getReimbursementId());
        request.putData("approved", approved); request.putData("comment", comment.get());
        request.putData("paymentPassword", paymentPassword);
        send(request, response -> {
            AlertUtil.showInfo("报销审核", approved ? "报销审核通过并已入账" : "报销申请已驳回");
            refreshAll();
        });
    }

    private void refreshAll() { refreshAccount(); refreshTransactions(); refreshBills(); refreshReimbursements(); }

    private void refreshAccount() {
        send(request(MessageType.BANK_ACCOUNT_QUERY), response -> {
            currentAccount = gson.fromJson(gson.toJson((Object) response.getData("account")), BankAccount.class);
            balanceLabel.setText("¥ " + currentAccount.getBalance().setScale(2));
            accountStatusLabel.setText("账户状态：" + currentAccount.getStatus().getDescription());
            passwordStatusLabel.setText(currentAccount.isPaymentPasswordSet()
                    ? "支付密码：已设置（修改时需输入原密码）" : "支付密码：未设置");
            oldPasswordField.setDisable(!currentAccount.isPaymentPasswordSet());
        });
    }

    private void refreshTransactions() {
        Message request = request(MessageType.BANK_TRANSACTION_LIST);
        request.putData("limit", 100);
        send(request, response -> {
            BankTransaction[] transactions = gson.fromJson(
                    gson.toJson((Object) response.getData("transactions")), BankTransaction[].class);
            transactionTable.setItems(FXCollections.observableArrayList(transactions));
        });
    }

    private void refreshBills() {
        Message request = request(isAdmin() ? MessageType.FINANCE_BILL_ALL_LIST : MessageType.FINANCE_BILL_MY_LIST);
        if (isAdmin()) {
            request.putData("keyword", billKeywordField.getText().trim());
            request.putData("billType", switch (billTypeFilter.getValue()) {
                case "学费" -> "TUITION";
                case "住宿费" -> "ACCOMMODATION";
                case "其他费用" -> "OTHER";
                default -> "";
            });
            request.putData("status", switch (billStatusFilter.getValue()) {
                case "待缴费" -> "UNPAID";
                case "已缴费" -> "PAID";
                case "已取消" -> "CANCELLED";
                default -> "";
            });
        }
        send(request, response -> {
            FinanceBill[] bills = gson.fromJson(gson.toJson((Object) response.getData("bills")), FinanceBill[].class);
            billTable.setItems(FXCollections.observableArrayList(bills));
        });
        if (isAdmin()) refreshBillStatistics();
    }

    private void refreshBillStatistics() {
        send(request(MessageType.FINANCE_REPORT_QUERY), response -> {
            billPeopleSummaryLabel.setText("已全部缴清：" + whole(response.getData("fullyPaidPeople"))
                    + " / 应缴人数：" + whole(response.getData("totalPeople")));
            billCountSummaryLabel.setText("已缴账单：" + whole(response.getData("paidBills"))
                    + " / 全部账单：" + whole(response.getData("totalBills")));
        });
    }

    private String whole(Object value) {
        return value instanceof Number number ? String.valueOf(number.longValue()) : String.valueOf(value);
    }

    private long wholeNumber(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { return 0; }
    }

    private static boolean containsIgnoreCase(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword);
    }

    private void refreshReimbursements() {
        send(request(MessageType.FINANCE_REIMBURSEMENT_MY_LIST), response -> {
            Reimbursement[] items = gson.fromJson(gson.toJson((Object) response.getData("reimbursements")), Reimbursement[].class);
            reimbursementTable.setItems(FXCollections.observableArrayList(items));
        });
    }

    private Message request(MessageType action) { return new Message(MessageType.REQUEST, "bank", action.name()); }

    private void send(Message request, Consumer<Message> onSuccess) {
        SocketClient.getInstance().sendAsync(request).thenAccept(response -> Platform.runLater(() -> {
            if (response.getCode() == MessageCode.SUCCESS) onSuccess.accept(response);
            else AlertUtil.showError("银行操作失败", response.getMessage());
        })).exceptionally(ex -> {
            Platform.runLater(() -> AlertUtil.showError("网络异常", "银行请求失败：" + ex.getMessage()));
            return null;
        });
    }

    private void clearPasswords() {
        oldPasswordField.clear(); newPasswordField.clear(); confirmPasswordField.clear();
    }

    private Optional<String> showPaymentPassword(String summary) {
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("支付密码验证"); dialog.setHeaderText(summary);
        ButtonType confirm = new ButtonType("确认支付", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        PasswordField field = new PasswordField(); field.setPromptText("请输入6位支付密码");
        GridPane pane = new GridPane(); pane.setHgap(10); pane.setVgap(10);
        pane.add(new Label("支付密码："), 0, 0); pane.add(field, 1, 0);
        dialog.getDialogPane().setContent(pane);
        dialog.setResultConverter(button -> button == confirm ? field.getText() : null);
        return dialog.showAndWait();
    }

    private boolean isAdmin() { return "管理员".equals(session.ClientSession.getInstance().getRole()); }
}
