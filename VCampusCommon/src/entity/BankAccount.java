package entity;

import enums.BankAccountStatus;
import java.io.Serializable;
import java.math.BigDecimal;

/** 校园银行虚拟账户（不向客户端传输支付密码摘要和盐值）。 */
public class BankAccount implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long accountId;
    private String userId;
    private BigDecimal balance;
    private BankAccountStatus status;
    private boolean paymentPasswordSet;
    private String createdAt;
    private String updatedAt;

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public BankAccountStatus getStatus() { return status; }
    public void setStatus(BankAccountStatus status) { this.status = status; }
    public boolean isPaymentPasswordSet() { return paymentPasswordSet; }
    public void setPaymentPasswordSet(boolean paymentPasswordSet) { this.paymentPasswordSet = paymentPasswordSet; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
