package entity;

import enums.RefundStatus;
import enums.OrderStatus;

import java.io.Serializable;
import java.math.BigDecimal;

/** 商店整单退款申请。 */
public class ShopRefund implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long refundId;
    private String refundNo;
    private Long orderId;
    private String userId;
    private BigDecimal refundAmount;
    private String reason;
    private RefundStatus status;
    private String originalTransactionNo;
    private String refundTransactionNo;
    private String reviewerId;
    private String reviewComment;
    private OrderStatus previousOrderStatus;
    private String requestedAt;
    private String reviewedAt;

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }
    public String getRefundNo() { return refundNo; }
    public void setRefundNo(String refundNo) { this.refundNo = refundNo; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public RefundStatus getStatus() { return status; }
    public void setStatus(RefundStatus status) { this.status = status; }
    public String getOriginalTransactionNo() { return originalTransactionNo; }
    public void setOriginalTransactionNo(String originalTransactionNo) { this.originalTransactionNo = originalTransactionNo; }
    public String getRefundTransactionNo() { return refundTransactionNo; }
    public void setRefundTransactionNo(String refundTransactionNo) { this.refundTransactionNo = refundTransactionNo; }
    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String reviewerId) { this.reviewerId = reviewerId; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public OrderStatus getPreviousOrderStatus() { return previousOrderStatus; }
    public void setPreviousOrderStatus(OrderStatus previousOrderStatus) { this.previousOrderStatus = previousOrderStatus; }
    public String getRequestedAt() { return requestedAt; }
    public void setRequestedAt(String requestedAt) { this.requestedAt = requestedAt; }
    public String getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(String reviewedAt) { this.reviewedAt = reviewedAt; }
}
