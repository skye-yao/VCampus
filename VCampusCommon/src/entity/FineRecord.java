package entity;

import java.io.Serializable;
import java.math.BigDecimal;

public class FineRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private BigDecimal amount;
    private String reason;
    private Integer borrowId;
    private BigDecimal overdueAmount = BigDecimal.ZERO;
    private BigDecimal lossAmount = BigDecimal.ZERO;
    private BigDecimal paidAmount = BigDecimal.ZERO;
    private BigDecimal refundedAmount = BigDecimal.ZERO;
    private boolean payable;
    public Integer getBorrowId() { return borrowId; }
    public void setBorrowId(Integer value) { borrowId = value; }
    public BigDecimal getOverdueAmount() { return overdueAmount; }
    public void setOverdueAmount(BigDecimal value) { overdueAmount = value; }
    public BigDecimal getLossAmount() { return lossAmount; }
    public void setLossAmount(BigDecimal value) { lossAmount = value; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal value) { paidAmount = value; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal value) { refundedAmount = value; }
    public boolean isPayable() { return payable; }
    public void setPayable(boolean value) { payable = value; }

    /**
     * 缴费状态：
     * 0 - 未缴费
     * 1 - 已缴费
     */
    private int status;

    public FineRecord() {
    }

    public FineRecord(int id, String userId, BigDecimal amount,
                      String reason, int status) {
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.reason = reason;
        this.status = status;
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return "FineRecord{" +
                "id=" + id +
                ", userId='" + userId + '\'' +
                ", amount=" + amount +
                ", reason='" + reason + '\'' +
                ", status=" + status +
                '}';
    }
}
