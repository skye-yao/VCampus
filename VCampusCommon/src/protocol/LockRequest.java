package protocol;

import java.io.Serializable;

/** 客户端实例与一次页面会话的凭证；用户身份由服务端登录会话确定。 */
public record LockRequest(String resourceType, String resourceId, String clientId,
                          String editSessionId, String lockToken) implements Serializable {
    public String resourceKey() { return resourceType + ":" + resourceId; }
    public LockRequest withToken(String token) {
        return new LockRequest(resourceType, resourceId, clientId, editSessionId, token);
    }
}
