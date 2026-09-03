package entity;

import java.io.Serializable;

/**
 * AI 对话会话实体类
 */
public class AiConversation implements Serializable {
    private static final long serialVersionUID = 1L;

    private String conversationId;
    private String userId;
    private String title;
    private String createdAt;
    private String updatedAt;

    public AiConversation() {}

    public AiConversation(String conversationId, String userId, String title) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.title = title;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return "AiConversation{" +
                "conversationId='" + conversationId + '\'' +
                ", userId='" + userId + '\'' +
                ", title='" + title + '\'' +
                ", createdAt='" + createdAt + '\'' +
                ", updatedAt='" + updatedAt + '\'' +
                '}';
    }
}
