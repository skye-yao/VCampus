package entity;

import java.io.Serializable;
import java.math.BigDecimal;

/** 校园财务报销申请。 */
public class Reimbursement implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long reimbursementId;
    private String applicantId;
    private String title;
    private BigDecimal amount;
    private String reason;
    private String status;
    private String reviewerId;
    private String reviewComment;
    private String paymentTransactionNo;
    private String createdAt;
    private String reviewedAt;

    public Long getReimbursementId() { return reimbursementId; }
    public void setReimbursementId(Long value) { reimbursementId = value; }
    public String getApplicantId() { return applicantId; }
    public void setApplicantId(String value) { applicantId = value; }
    public String getTitle() { return title; }
    public void setTitle(String value) { title = value; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal value) { amount = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { status = value; }
    public String getReviewerId() { return reviewerId; }
    public void setReviewerId(String value) { reviewerId = value; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String value) { reviewComment = value; }
    public String getPaymentTransactionNo() { return paymentTransactionNo; }
    public void setPaymentTransactionNo(String value) { paymentTransactionNo = value; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String value) { createdAt = value; }
    public String getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(String value) { reviewedAt = value; }
}
