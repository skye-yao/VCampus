package entity;

public class SystemNotification {
    private Long id;
    private String recipientUid;
    private String category;
    private String title;
    private String content;
    private String linkAction;
    private Boolean isRead;
    private String createdAt;

    public SystemNotification() {}

    public SystemNotification(Long id, String recipientUid, String category, String title,
                              String content, String linkAction, Boolean isRead, String createdAt) {
        this.id = id;
        this.recipientUid = recipientUid;
        this.category = category;
        this.title = title;
        this.content = content;
        this.linkAction = linkAction;
        this.isRead = isRead;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRecipientUid() { return recipientUid; }
    public void setRecipientUid(String recipientUid) { this.recipientUid = recipientUid; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getLinkAction() { return linkAction; }
    public void setLinkAction(String linkAction) { this.linkAction = linkAction; }

    public Boolean isRead() { return Boolean.TRUE.equals(isRead); }
    public void setIsRead(Boolean isRead) { this.isRead = isRead; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
