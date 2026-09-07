package entity;

import java.io.Serializable;

/** 商店管理员操作审计记录。 */
public class ShopOperationLog implements Serializable {
    private static final long serialVersionUID = 1L;

    private Long logId;
    private String operatorId;
    private String action;
    private String targetType;
    private Long targetId;
    private String beforeData;
    private String afterData;
    private String reason;
    private String createdAt;

    public Long getLogId() { return logId; }
    public void setLogId(Long logId) { this.logId = logId; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getBeforeData() { return beforeData; }
    public void setBeforeData(String beforeData) { this.beforeData = beforeData; }
    public String getAfterData() { return afterData; }
    public void setAfterData(String afterData) { this.afterData = afterData; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
