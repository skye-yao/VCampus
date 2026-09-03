package controller;

import app.ClientMain;
import com.google.gson.Gson;
import entity.BankAccount;
import entity.BankTransaction;
import entity.FinanceBill;
import entity.Reimbursement;
import enums.BankTransactionType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
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
    @FXML private TableColumn<BankTransaction, String> txTimeColumn;
    @FXML private TextField targetUserField;
    @FXML private TextField transferAmountField;
    @FXML private PasswordField transferPasswordField;
    @FXML private PasswordField oldPasswordField;
    @FXML private PasswordField newPasswordField;
    @FXML private PasswordField confirmPasswordField;
    @FXML private TableView<FinanceBill> billTable;
    @FXML private Tab billTab;
    @FXML private TableColumn<FinanceBill, String> billUserColumn;
    @FXML private TableColumn<FinanceBill, String> billTitleColumn;
    @FXML private TableColumn<FinanceBill, String> billTypeColumn;
    @FXML private TableColumn<FinanceBill, BigDecimal> billAmountColumn;
    @FXML private TableColumn<FinanceBill, String> billStatusColumn;
    @FXML private TableColumn<FinanceBill, String> billDueColumn;
    @FXML private HBox payBillBox;
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

    private final Gson gson = new Gson();
    private BankAccount currentAccount;

    @FXML
    public void initialize() {
        txNoColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("transactionNo"));
        txTypeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("transactionType"));
        txAmountColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("amount"));
        txBalanceColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("balanceAfter"));
        txCounterpartyColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("counterpartyUserId"));
        txTimeColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("createdAt"));
        billUserColumn.setCellValueFactory(new javafx.scene.control.cell.PropertyValueFactory<>("userId"));
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
        balanceCaptionLabel.setText(admin ? "校园财务账户可用余额（管理员操作）" : "当前校园银行账户余额");
        adminReviewBox.setVisible(admin); adminReviewBox.setManaged(admin);
        billUserColumn.setVisible(admin);
        billTab.setText(admin ? "缴费账单管理" : "校园缴费");
        payBillBox.setVisible(!admin); payBillBox.setManaged(!admin);
        if (admin) reimbursementSplitPane.getItems().remove(reimbursementApplyPanel);
        refreshAll();
    }

    @FXML private void handleBack() { ClientMain.switchScene("/resources/fxml/MainView.fxml"); }
    @FXML private void handleRefresh() { refreshAll(); }

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
        Message request = request(MessageType.FINANCE_REIMBURSEMENT_REVIEW);
        request.putData("reimbursementId", item.getReimbursementId());
        request.putData("approved", approved); request.putData("comment", comment.get());
        send(request, response -> {
            if (approved) {
                String applicant = String.valueOf((Object) response.getData("applicantId"));
                String amount = String.valueOf((Object) response.getData("amount"));
                String transactionNo = String.valueOf((Object) response.getData("transactionNo"));
                String balanceAfter = String.valueOf((Object) response.getData("balanceAfter"));
                String financeTransactionNo = String.valueOf((Object) response.getData("financeTransactionNo"));
                String financeBalanceAfter = String.valueOf((Object) response.getData("financeBalanceAfter"));
                AlertUtil.showInfo("报销审核", "已向 " + applicant + " 的校园银行账户入账 ¥" + amount
                        + "\n申请人入账后余额：¥" + balanceAfter + "\n申请人流水号：" + transactionNo
                        + "\n校园财务扣款后余额：¥" + financeBalanceAfter
                        + "\n校园财务流水号：" + financeTransactionNo);
            } else {
                AlertUtil.showInfo("报销审核", "报销申请已驳回，未发生资金变动");
            }
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
        send(request, response -> {
            FinanceBill[] bills = gson.fromJson(gson.toJson((Object) response.getData("bills")), FinanceBill[].class);
            billTable.setItems(FXCollections.observableArrayList(bills));
        });
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
        dialog.setTitle("校园缴费"); dialog.setHeaderText(summary);
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
