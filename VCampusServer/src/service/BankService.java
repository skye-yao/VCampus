package service;

import dao.BankAccountDAO;
import dao.BankTransactionDAO;
import dao.CampusFinanceDAO;
import entity.BankAccount;
import entity.BankTransaction;
import entity.FinanceBill;
import entity.Reimbursement;
import enums.BankAccountStatus;
import enums.BankTransactionType;
import exception.BusinessException;
import exception.DatabaseException;
import util.DBUtil;
import util.PasswordUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 校园银行基础业务，同时向商店提供可信的服务端内部支付接口。 */
public class BankService implements IBankPaymentService {
    private static final String FINANCE_ACCOUNT_USER_ID = "admin";
    private final BankAccountDAO accountDAO = new BankAccountDAO();
    private final BankTransactionDAO transactionDAO = new BankTransactionDAO();
    private final CampusFinanceDAO financeDAO = new CampusFinanceDAO();

    public BankAccount getAccount(String userId) {
        try (Connection conn = DBUtil.getConnection()) {
            BankAccount account = accountDAO.findByUserId(conn, userId, false);
            if (account == null) throw new BusinessException("当前用户尚未开通校园银行账户");
            return account;
        } catch (SQLException e) {
            throw new DatabaseException("查询银行账户失败", e);
        }
    }

    public List<BankTransaction> listTransactions(String userId, int limit) {
        try (Connection conn = DBUtil.getConnection()) {
            BankAccount account = requireAccount(conn, userId, false);
            return transactionDAO.findByAccountId(conn, account.getAccountId(), limit);
        } catch (SQLException e) {
            throw new DatabaseException("查询交易流水失败", e);
        }
    }

    public void setPaymentPassword(String userId, String newPassword) {
        validateNewPassword(newPassword);
        try (Connection conn = DBUtil.getConnection()) {
            BankAccount account = requireAccount(conn, userId, true);
            if (account.isPaymentPasswordSet() && account.getStatus() != BankAccountStatus.RESET_REQUIRED) {
                throw new BusinessException("支付密码已经设置，请使用修改密码功能");
            }
            savePassword(conn, account.getAccountId(), newPassword);
        } catch (SQLException e) {
            throw new DatabaseException("设置支付密码失败", e);
        }
    }

    public void changePaymentPassword(String userId, String oldPassword, String newPassword) {
        validateNewPassword(newPassword);
        try (Connection conn = DBUtil.getConnection()) {
            BankAccount account = requireAccount(conn, userId, true);
            verifyPaymentPassword(conn, account, oldPassword);
            savePassword(conn, account.getAccountId(), newPassword);
        } catch (SQLException e) {
            throw new DatabaseException("修改支付密码失败", e);
        }
    }

    public String transfer(String userId, String targetUserId, BigDecimal amount,
                           String paymentPassword, String requestId) {
        if (targetUserId == null || targetUserId.isBlank()) throw new BusinessException("收款人一卡通号不能为空");
        if (userId.equals(targetUserId.trim())) throw new BusinessException("不能向自己的账户转账");
        amount = normalizeAmount(amount);
        requireRequestId(requestId);

        Connection conn = null;
        try {
            conn = DBUtil.getConnection();
            conn.setAutoCommit(false);
            BankAccount sourcePreview = requireAccount(conn, userId, false);
            BankAccount targetPreview = requireAccount(conn, targetUserId.trim(), false);
            BankTransaction duplicate = transactionDAO.findByRequestId(conn, requestId);
            if (duplicate != null) {
                validateDuplicate(duplicate, sourcePreview.getAccountId(), BankTransactionType.TRANSFER_OUT,
                        amount.negate(), null);
                conn.commit(); return duplicate.getTransactionNo();
            }

            // 按账户编号固定顺序加锁，减少双向同时转账造成的死锁。
            String first = sourcePreview.getAccountId() < targetPreview.getAccountId() ? userId : targetUserId.trim();
            String second = first.equals(userId) ? targetUserId.trim() : userId;
            BankAccount firstLocked = requireAccount(conn, first, true);
            BankAccount secondLocked = requireAccount(conn, second, true);
            BankAccount source = first.equals(userId) ? firstLocked : secondLocked;
            BankAccount target = first.equals(userId) ? secondLocked : firstLocked;
            requireActive(source); requireActive(target);
            verifyPaymentPassword(conn, source, paymentPassword);
            if (source.getBalance().compareTo(amount) < 0) throw new BusinessException("账户余额不足");

            accountDAO.changeBalance(conn, source.getAccountId(), amount.negate());
            accountDAO.changeBalance(conn, target.getAccountId(), amount);
            String txNo = newTransactionNo();
            insertTransaction(conn, txNo, source, target.getUserId(), BankTransactionType.TRANSFER_OUT,
                    amount.negate(), source.getBalance().subtract(amount), null, requestId,
                    "转账给 " + target.getUserId());
            insertTransaction(conn, newTransactionNo(), target, source.getUserId(), BankTransactionType.TRANSFER_IN,
                    amount, target.getBalance().add(amount), null, null,
                    "收到 " + source.getUserId() + " 的转账");
            conn.commit();
            return txNo;
        } catch (BusinessException e) {
            rollback(conn); throw e;
        } catch (SQLException e) {
            rollback(conn); throw new DatabaseException("校园转账失败", e);
        } finally { resetAndClose(conn); }
    }

    public List<FinanceBill> listBills(String userId, boolean admin) {
        try (Connection conn = DBUtil.getConnection()) {
            return financeDAO.findBills(conn, userId, admin);
        } catch (SQLException e) { throw new DatabaseException("查询校园账单失败", e); }
    }

    public String payBill(String userId, long billId, String paymentPassword, String requestId) {
        Connection conn = null;
        try {
            conn = DBUtil.getConnection(); conn.setAutoCommit(false);
            FinanceBill bill = financeDAO.findBillForUpdate(conn, billId);
            if (bill == null || !userId.equals(bill.getUserId())) throw new BusinessException("账单不存在或无权支付");
            BankAccount account = requireAccount(conn, userId, true); requireActive(account);
            BankTransaction duplicate = transactionDAO.findByRequestId(conn, requestId);
            if (duplicate != null) {
                validateDuplicate(duplicate, account.getAccountId(), BankTransactionType.TUITION_PAYMENT,
                        bill.getAmount().negate(), null);
                conn.commit(); return duplicate.getTransactionNo();
            }
            if (!"UNPAID".equals(bill.getStatus())) throw new BusinessException("该账单不是待缴费状态");
            verifyPaymentPassword(conn, account, paymentPassword);
            BigDecimal amount = normalizeAmount(bill.getAmount());
            if (account.getBalance().compareTo(amount) < 0) throw new BusinessException("校园银行账户余额不足");
            if (!accountDAO.changeBalance(conn, account.getAccountId(), amount.negate())) throw new BusinessException("账单扣款失败");
            String txNo = newTransactionNo();
            insertTransaction(conn, txNo, account, null, BankTransactionType.TUITION_PAYMENT,
                    amount.negate(), account.getBalance().subtract(amount), null, requestId, bill.getTitle());
            if (!financeDAO.markBillPaid(conn, billId, txNo)) throw new BusinessException("账单状态已经变化");
            conn.commit(); return txNo;
        } catch (BusinessException e) { rollback(conn); throw e;
        } catch (SQLException e) { rollback(conn); throw new DatabaseException("缴纳校园费用失败", e);
        } finally { resetAndClose(conn); }
    }

    public long applyReimbursement(String userId, String title, BigDecimal amount, String reason) {
        if (title == null || title.isBlank()) throw new BusinessException("报销事项不能为空");
        if (reason == null || reason.isBlank()) throw new BusinessException("报销说明不能为空");
        Reimbursement item = new Reimbursement();
        item.setApplicantId(userId); item.setTitle(title.trim());
        item.setAmount(normalizeAmount(amount)); item.setReason(reason.trim());
        try (Connection conn = DBUtil.getConnection()) {
            return financeDAO.insertReimbursement(conn, item);
        } catch (SQLException e) { throw new DatabaseException("提交报销申请失败", e); }
    }

    public List<Reimbursement> listReimbursements(String userId, boolean admin) {
        try (Connection conn = DBUtil.getConnection()) {
            return financeDAO.findReimbursements(conn, userId, admin);
        } catch (SQLException e) { throw new DatabaseException("查询报销申请失败", e); }
    }

    public Map<String, Object> reviewReimbursement(String reviewerId, boolean admin, long id,
                                                    boolean approved, String comment) {
        if (!admin) throw new BusinessException("仅管理员可以审核报销");
        Connection conn = null;
        try {
            conn = DBUtil.getConnection(); conn.setAutoCommit(false);
            Reimbursement item = financeDAO.findReimbursementForUpdate(conn, id);
            if (item == null) throw new BusinessException("报销申请不存在");
            if (!"APPLIED".equals(item.getStatus())) throw new BusinessException("该报销申请已经审核");
            String txNo = null;
            String financeTransactionNo = null;
            BigDecimal balanceAfter = null;
            BigDecimal financeBalanceAfter = null;
            if (approved) {
                if (reviewerId.equals(item.getApplicantId())) {
                    throw new BusinessException("校园财务管理员不能审核自己的报销申请");
                }
                BankAccount financePreview = requireAccount(conn, reviewerId, false);
                BankAccount applicantPreview = requireAccount(conn, item.getApplicantId(), false);
                String firstUser = financePreview.getAccountId() < applicantPreview.getAccountId()
                        ? reviewerId : item.getApplicantId();
                String secondUser = firstUser.equals(reviewerId) ? item.getApplicantId() : reviewerId;
                BankAccount firstLocked = requireAccount(conn, firstUser, true);
                BankAccount secondLocked = requireAccount(conn, secondUser, true);
                BankAccount financeAccount = firstUser.equals(reviewerId) ? firstLocked : secondLocked;
                BankAccount applicantAccount = firstUser.equals(reviewerId) ? secondLocked : firstLocked;
                requireActive(financeAccount); requireActive(applicantAccount);
                if (financeAccount.getBalance().compareTo(item.getAmount()) < 0) {
                    throw new BusinessException("校园财务账户余额不足，无法支付报销款");
                }
                if (!accountDAO.changeBalance(conn, financeAccount.getAccountId(), item.getAmount().negate())) {
                    throw new BusinessException("校园财务账户扣款失败");
                }
                if (!accountDAO.changeBalance(conn, applicantAccount.getAccountId(), item.getAmount())) {
                    throw new BusinessException("报销款入账失败");
                }

                financeBalanceAfter = financeAccount.getBalance().subtract(item.getAmount());
                balanceAfter = applicantAccount.getBalance().add(item.getAmount());
                financeTransactionNo = newTransactionNo();
                txNo = newTransactionNo();
                insertTransaction(conn, financeTransactionNo, financeAccount, applicantAccount.getUserId(),
                        BankTransactionType.REIMBURSEMENT_PAYOUT, item.getAmount().negate(),
                        financeBalanceAfter, null, "REIMB-" + id + "-OUT",
                        "向 " + applicantAccount.getUserId() + " 支付报销款：" + item.getTitle());
                insertTransaction(conn, txNo, applicantAccount, financeAccount.getUserId(),
                        BankTransactionType.REIMBURSEMENT, item.getAmount(), balanceAfter, null,
                        "REIMB-" + id + "-IN", "校园财务报销入账：" + item.getTitle());
            }
            if (!financeDAO.review(conn, id, approved ? "APPROVED" : "REJECTED",
                    reviewerId, comment, txNo)) throw new BusinessException("审核状态已经变化");
            conn.commit();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("applicantId", item.getApplicantId());
            result.put("amount", item.getAmount());
            result.put("status", approved ? "APPROVED" : "REJECTED");
            result.put("transactionNo", txNo);
            result.put("financeTransactionNo", financeTransactionNo);
            result.put("balanceAfter", balanceAfter);
            result.put("financeBalanceAfter", financeBalanceAfter);
            return result;
        } catch (BusinessException e) { rollback(conn); throw e;
        } catch (SQLException e) { rollback(conn); throw new DatabaseException("审核报销失败", e);
        } finally { resetAndClose(conn); }
    }

    @Override
    public String payShopOrder(Connection conn, String userId, long orderId,
                               BigDecimal actualAmount, String paymentPassword, String requestId) {
        try {
            requireRequestId(requestId);
            BigDecimal amount = normalizeAmount(actualAmount);
            BankAccount userPreview = requireAccount(conn, userId, false);
            BankAccount financePreview = requireAccount(conn, FINANCE_ACCOUNT_USER_ID, false);
            BankTransaction duplicate = transactionDAO.findByRequestId(conn, requestId);
            if (duplicate != null) {
                validateDuplicate(duplicate, userPreview.getAccountId(), BankTransactionType.SHOP_PAYMENT,
                        amount.negate(), orderId);
                return duplicate.getTransactionNo();
            }
            String firstUser = userPreview.getAccountId() < financePreview.getAccountId()
                    ? userId : FINANCE_ACCOUNT_USER_ID;
            String secondUser = firstUser.equals(userId) ? FINANCE_ACCOUNT_USER_ID : userId;
            BankAccount firstLocked = requireAccount(conn, firstUser, true);
            BankAccount secondLocked = requireAccount(conn, secondUser, true);
            BankAccount account = firstUser.equals(userId) ? firstLocked : secondLocked;
            BankAccount financeAccount = firstUser.equals(userId) ? secondLocked : firstLocked;
            requireActive(account); requireActive(financeAccount);
            verifyPaymentPassword(conn, account, paymentPassword);
            if (account.getBalance().compareTo(amount) < 0) throw new BusinessException("校园银行账户余额不足");
            if (!accountDAO.changeBalance(conn, account.getAccountId(), amount.negate())) {
                throw new BusinessException("扣款失败，请刷新账户后重试");
            }
            if (!accountDAO.changeBalance(conn, financeAccount.getAccountId(), amount)) {
                throw new BusinessException("商店收入记入校园财务账户失败");
            }
            String txNo = newTransactionNo();
            insertTransaction(conn, txNo, account, financeAccount.getUserId(), BankTransactionType.SHOP_PAYMENT,
                    amount.negate(), account.getBalance().subtract(amount), orderId, requestId,
                    "校园商店订单支付");
            insertTransaction(conn, newTransactionNo(), financeAccount, account.getUserId(),
                    BankTransactionType.SHOP_INCOME, amount, financeAccount.getBalance().add(amount),
                    orderId, null, "校园商店订单收入");
            return txNo;
        } catch (SQLException e) {
            throw new DatabaseException("商店支付扣款失败", e);
        }
    }

    @Override
    public String refundShopOrder(Connection conn, long orderId, String originalTransactionNo,
                                  BigDecimal actualAmount, String requestId) {
        try {
            requireRequestId(requestId);
            BankTransaction original = transactionDAO.findByTransactionNo(conn, originalTransactionNo);
            if (original == null || original.getTransactionType() != BankTransactionType.SHOP_PAYMENT
                    || original.getRelatedOrderId() == null || original.getRelatedOrderId() != orderId) {
                throw new BusinessException("未找到匹配的原支付流水");
            }
            BankTransaction duplicate = transactionDAO.findByRequestId(conn, requestId);
            if (duplicate != null) return duplicate.getTransactionNo();
            String userId = accountUserId(conn, original.getAccountId());
            BankAccount userPreview = requireAccount(conn, userId, false);
            BankAccount financePreview = requireAccount(conn, FINANCE_ACCOUNT_USER_ID, false);
            String firstUser = userPreview.getAccountId() < financePreview.getAccountId()
                    ? userId : FINANCE_ACCOUNT_USER_ID;
            String secondUser = firstUser.equals(userId) ? FINANCE_ACCOUNT_USER_ID : userId;
            BankAccount firstLocked = requireAccount(conn, firstUser, true);
            BankAccount secondLocked = requireAccount(conn, secondUser, true);
            BankAccount account = firstUser.equals(userId) ? firstLocked : secondLocked;
            BankAccount financeAccount = firstUser.equals(userId) ? secondLocked : firstLocked;
            requireActive(account); requireActive(financeAccount);
            BigDecimal amount = normalizeAmount(actualAmount);
            if (financeAccount.getBalance().compareTo(amount) < 0) {
                throw new BusinessException("校园财务账户余额不足，无法执行退款");
            }
            if (!accountDAO.changeBalance(conn, financeAccount.getAccountId(), amount.negate())
                    || !accountDAO.changeBalance(conn, account.getAccountId(), amount)) {
                throw new BusinessException("商店退款资金处理失败");
            }
            String txNo = newTransactionNo();
            insertTransaction(conn, newTransactionNo(), financeAccount, account.getUserId(),
                    BankTransactionType.SHOP_REFUND_PAYOUT, amount.negate(),
                    financeAccount.getBalance().subtract(amount), orderId, null, "校园商店退款支出");
            insertTransaction(conn, txNo, account, financeAccount.getUserId(), BankTransactionType.SHOP_REFUND,
                    amount, account.getBalance().add(amount), orderId, requestId, "校园商店订单退款");
            return txNo;
        } catch (SQLException e) {
            throw new DatabaseException("商店退款入账失败", e);
        }
    }

    private String accountUserId(Connection conn, long accountId) throws SQLException {
        try (var stmt = conn.prepareStatement("SELECT user_id FROM tbl_bank_account WHERE account_id=?")) {
            stmt.setLong(1, accountId);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) throw new BusinessException("原支付账户不存在");
                return rs.getString(1);
            }
        }
    }

    private BankAccount requireAccount(Connection conn, String userId, boolean lock) throws SQLException {
        BankAccount account = accountDAO.findByUserId(conn, userId, lock);
        if (account == null) throw new BusinessException("校园银行账户不存在：" + userId);
        return account;
    }

    private void requireActive(BankAccount account) {
        if (account.getStatus() != BankAccountStatus.ACTIVE) throw new BusinessException("账户当前不可交易：" + account.getStatus().getDescription());
    }

    private void verifyPaymentPassword(Connection conn, BankAccount account, String password) throws SQLException {
        if (password == null || password.isBlank()) throw new BusinessException("请输入支付密码");
        if (!account.isPaymentPasswordSet()) throw new BusinessException("请先设置6位支付密码");
        String[] stored = accountDAO.findPassword(conn, account.getAccountId());
        if (stored == null || stored[0] == null || !PasswordUtil.verifyPassword(password, stored[1], stored[0])) {
            throw new BusinessException("支付密码错误");
        }
    }

    private void savePassword(Connection conn, long accountId, String password) throws SQLException {
        String salt = PasswordUtil.generateSalt();
        if (!accountDAO.setPassword(conn, accountId, PasswordUtil.hashPassword(password, salt), salt)) {
            throw new BusinessException("保存支付密码失败");
        }
    }

    private void validateNewPassword(String password) {
        if (password == null || !password.matches("\\d{6}")) throw new BusinessException("支付密码必须是6位数字");
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) throw new BusinessException("金额不能为空");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException("金额必须大于0");
        return amount;
    }

    private void requireRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) throw new BusinessException("请求编号不能为空");
    }

    private void insertTransaction(Connection conn, String txNo, BankAccount account, String counterparty,
                                   BankTransactionType type, BigDecimal amount, BigDecimal balanceAfter,
                                   Long orderId, String requestId, String remark) throws SQLException {
        BankTransaction tx = new BankTransaction();
        tx.setTransactionNo(txNo); tx.setAccountId(account.getAccountId());
        tx.setCounterpartyUserId(counterparty); tx.setTransactionType(type);
        tx.setAmount(amount); tx.setBalanceAfter(balanceAfter); tx.setRelatedOrderId(orderId); tx.setRemark(remark);
        transactionDAO.insert(conn, tx, requestId);
    }

    private void validateDuplicate(BankTransaction tx, long accountId, BankTransactionType type,
                                   BigDecimal amount, Long orderId) {
        boolean orderMatches = orderId == null ? tx.getRelatedOrderId() == null
                : orderId.equals(tx.getRelatedOrderId());
        if (tx.getAccountId() != accountId || tx.getTransactionType() != type
                || tx.getAmount().compareTo(amount.setScale(2, RoundingMode.HALF_UP)) != 0 || !orderMatches) {
            throw new BusinessException("请求编号已被其他交易使用，请重新发起请求");
        }
    }

    private String newTransactionNo() {
        return "BT" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void rollback(Connection conn) { if (conn != null) try { conn.rollback(); } catch (SQLException ignored) { } }
    private void resetAndClose(Connection conn) {
        if (conn != null) {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
            DBUtil.close(conn, null, null);
        }
    }
}
