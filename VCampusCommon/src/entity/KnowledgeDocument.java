package entity;

import java.io.Serializable;

/**
 * 校园知识库文档实体类
 */
public class KnowledgeDocument implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long docId;
    private String title;
    private String category;
    private String content;
    private String status;        // ACTIVE / DISABLED
    private String createdAt;
    private String updatedAt;

    public KnowledgeDocument() {}

    public KnowledgeDocument(Long docId, String title, String category, String content) {
        this.docId = docId;
        this.title = title;
        this.category = category;
        this.content = content;
        this.status = "ACTIVE";
    }

    public Long getDocId() {
        return docId;
    }

    public void setDocId(Long docId) {
        this.docId = docId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
        return "KnowledgeDocument{" +
                "docId=" + docId +
                ", title='" + title + '\'' +
                ", category='" + category + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}
