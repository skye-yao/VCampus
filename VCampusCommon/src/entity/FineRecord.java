package entity;

import java.io.Serializable;
import java.math.BigDecimal;

public class FineRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String userId;
    private BigDecimal amount;
    private String reason;

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