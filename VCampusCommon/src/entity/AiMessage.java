package entity;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 对话消息实体类（包含 Token 消耗、扣费金额以及溯源流水号）
 */
public class AiMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long messageId;
    private String conversationId;
    private String senderType;       // USER, AI, SYSTEM
    private String content;
    private String intentType;       // GENERAL, CAMPUS_RAG, PERSONAL_DATA, SENSITIVE_BLOCKED
    private Integer promptTokens;
    private Integer completionTokens;
    private BigDecimal costAmount;
    private String transactionNo;
    private String createdAt;

    /** 关联的引用列表（方便传输到前端展示） */
    private List<AiCitation> citations = new ArrayList<>();

    public AiMessage() {
        this.promptTokens = 0;
        this.completionTokens = 0;
        this.costAmount = BigDecimal.ZERO;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getSenderType() {
        return senderType;
    }

    public void setSenderType(String senderType) {
        this.senderType = senderType;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getIntentType() {
        return intentType;
    }

    public void setIntentType(String intentType) {
        this.intentType = intentType;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public BigDecimal getCostAmount() {
        return costAmount;
    }

    public void setCostAmount(BigDecimal costAmount) {
        this.costAmount = costAmount;
    }

    public String getTransactionNo() {
        return transactionNo;
    }

    public void setTransactionNo(String transactionNo) {
        this.transactionNo = transactionNo;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public List<AiCitation> getCitations() {
        return citations;
    }

    public void setCitations(List<AiCitation> citations) {
        this.citations = citations;
    }

    @Override
    public String toString() {
        return "AiMessage{" +
                "messageId=" + messageId +
                ", conversationId='" + conversationId + '\'' +
                ", senderType='" + senderType + '\'' +
                ", intentType='" + intentType + '\'' +
                ", promptTokens=" + promptTokens +
                ", completionTokens=" + completionTokens +
                ", costAmount=" + costAmount +
                ", transactionNo='" + transactionNo + '\'' +
                '}';
    }
}
