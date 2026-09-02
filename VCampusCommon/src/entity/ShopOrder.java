package entity;

import enums.OrderStatus;

import java.io.Serializable;
import java.math.BigDecimal;

/** 商店订单。 */
public class ShopOrder implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long orderId;
    private String orderNo;
    private String userId;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private String paymentTransactionNo;
    private String expiresAt;
    private String paidAt;
    private String cancelledAt;
    private Integer version;
    private String createdAt;
    private String updatedAt;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public String getPaymentTransactionNo() { return paymentTransactionNo; }
    public void setPaymentTransactionNo(String paymentTransactionNo) { this.paymentTransactionNo = paymentTransactionNo; }
    public String getExpiresAt() { return expiresAt; }
    public void setExpiresAt(String expiresAt) { this.expiresAt = expiresAt; }
    public String getPaidAt() { return paidAt; }
    public void setPaidAt(String paidAt) { this.paidAt = paidAt; }
    public String getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(String cancelledAt) { this.cancelledAt = cancelledAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
}
