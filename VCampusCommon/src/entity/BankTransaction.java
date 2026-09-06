package entity;

import enums.BankTransactionType;
import java.io.Serializable;
import java.math.BigDecimal;

/** 校园银行交易流水。 */
public class BankTransaction implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long transactionId;
    private String transactionNo;
    private Long accountId;
    private String counterpartyUserId;
    private BankTransactionType transactionType;
    private BigDecimal amount;
    private BigDecimal balanceAfter;
    private Long relatedOrderId;
    private String remark;
    private String createdAt;

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }
    public String getTransactionNo() { return transactionNo; }
    public void setTransactionNo(String transactionNo) { this.transactionNo = transactionNo; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public String getCounterpartyUserId() { return counterpartyUserId; }
    public void setCounterpartyUserId(String counterpartyUserId) { this.counterpartyUserId = counterpartyUserId; }
    public BankTransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(BankTransactionType transactionType) { this.transactionType = transactionType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public Long getRelatedOrderId() { return relatedOrderId; }
    public void setRelatedOrderId(Long relatedOrderId) { this.relatedOrderId = relatedOrderId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
