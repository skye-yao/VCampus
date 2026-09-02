package handler;

import exception.BusinessException;
import exception.DatabaseException;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import service.BankService;
import session.SessionManager;
import session.UserSession;

import java.math.BigDecimal;

/** 校园银行请求处理器。 */
public class BankHandler {
    private final BankService bankService;
    public BankHandler(BankService bankService) { this.bankService = bankService; }

    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE, "bank", request.getAction());
        response.setUID(request.getUID());
        UserSession session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录会话已失效，请重新登录");
            return response;
        }
        try {
            String action = request.getAction() == null ? "" : request.getAction().toUpperCase();
            boolean admin = "管理员".equals(session.getRole());
            switch (action) {
                case "BANK_ACCOUNT_QUERY" -> response.putData("account", bankService.getAccount(session.getUsername()));
                case "BANK_PASSWORD_STATUS_QUERY" -> response.putData("account", bankService.getAccount(session.getUsername()));
                case "BANK_PASSWORD_SET" -> bankService.setPaymentPassword(session.getUsername(), string(request, "newPassword"));
                case "BANK_PASSWORD_CHANGE" -> bankService.changePaymentPassword(session.getUsername(),
                        string(request, "oldPassword"), string(request, "newPassword"));
                case "BANK_TRANSACTION_LIST" -> response.putData("transactions",
                        bankService.listTransactions(session.getUsername(), integer(request, "limit", 100)));
                case "BANK_TRANSFER" -> response.putData("transactionNo", bankService.transfer(
                        session.getUsername(), string(request, "targetUserId"),
                        new BigDecimal(string(request, "amount")), string(request, "paymentPassword"),
                        string(request, "requestId")));
                case "FINANCE_BILL_MY_LIST", "FINANCE_BILL_ALL_LIST" -> response.putData("bills",
                        bankService.listBills(session.getUsername(), admin && "FINANCE_BILL_ALL_LIST".equals(action)));
                case "FINANCE_BILL_PAY" -> response.putData("transactionNo", bankService.payBill(
                        session.getUsername(), number(request, "billId"), string(request, "paymentPassword"),
                        string(request, "requestId")));
                case "FINANCE_REIMBURSEMENT_APPLY" -> response.putData("reimbursementId",
                        bankService.applyReimbursement(session.getUsername(), string(request, "title"),
                                new BigDecimal(string(request, "amount")), string(request, "reason")));
                case "FINANCE_REIMBURSEMENT_MY_LIST", "FINANCE_REIMBURSEMENT_DETAIL" ->
                        response.putData("reimbursements", bankService.listReimbursements(session.getUsername(), admin));
                case "FINANCE_REIMBURSEMENT_REVIEW" -> response.setData(bankService.reviewReimbursement(
                        session.getUsername(), admin, number(request, "reimbursementId"),
                        Boolean.parseBoolean(string(request, "approved")), string(request, "comment")));
                default -> throw new BusinessException("暂不支持的银行操作：" + request.getAction());
            }
            response.setCode(MessageCode.SUCCESS);
            response.setMessage("操作成功");
        } catch (BusinessException | NumberFormatException e) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(e instanceof NumberFormatException ? "金额格式不正确" : e.getMessage());
        } catch (DatabaseException e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端数据库异常：" + e.getMessage());
        } catch (Exception e) {
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误：" + e.getMessage());
        }
        return response;
    }

    private String string(Message request, String key) {
        Object value = request.getData(key);
        return value == null ? null : String.valueOf(value);
    }
    private int integer(Message request, String key, int defaultValue) {
        Object value = request.getData(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private long number(Message request, String key) {
        Object value = request.getData(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(key + "不能为空");
        return Long.parseLong(String.valueOf(value));
    }
}
