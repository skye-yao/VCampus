package protocol;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 统一通信消息
 * 
 * 客户端根据用户操作构造Message对象，通过Socket发送至服务器。
 * 服务器根据消息类型确定业务模块处理，将结果封装成Message返回客户端。
 */
public class Message implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // ===== 消息头 =====
    private Long UID;                // 消息标识符（唯一）
    private String name;             // 消息名称（便于识别）
    private MessageType type;        // 消息类型（对应MessageType枚举）
    private String module;           // 所属模块：user/student/course/library/bank/shop/ai
    private String action;           // 操作名称：login/borrow/purchase/chat...
    private String timestamp;        // 时间戳
    
    // ===== 认证 =====
    private String sender;           // 消息发送者（用户名/一卡通号）
    private String token;            // 用户令牌（登录后每次请求携带）
    
    // ===== 业务数据 =====
    private Map<String, Object> data;   // 实际传输数据（键值对）
    
    // ===== 响应信息 =====
    private MessageCode code;        // 状态码（仅响应消息使用）
    private String message;          // 附加信息（错误详情等）
    
    // ===== 构造方法 =====
    public Message() {
        this.UID = System.currentTimeMillis();
        this.data = new HashMap<>();
        this.timestamp = String.valueOf(System.currentTimeMillis());
        this.code = MessageCode.SUCCESS;
    }
    
    // ===== 便捷构造：请求消息 =====
    public Message(MessageType type, String module, String action) {
        this();
        this.type = type;
        this.module = module;
        this.action = action;
    }
    
    // ===== 便捷构造：响应消息 =====
    public Message(MessageType type, String module, String action, MessageCode code) {
        this(type, module, action);
        this.code = code;
    }
    
    // ===== Getter / Setter =====
    public Long getUID() {
        return UID;
    }
    
    public void setUID(Long UID) {
        this.UID = UID;
    }
    
    public String getName() {
        return name;
    }
    
    public void setName(String name) {
        this.name = name;
    }
    
    public MessageType getType() {
        return type;
    }
    
    public void setType(MessageType type) {
        this.type = type;
    }
    
    public String getModule() {
        return module;
    }
    
    public void setModule(String module) {
        this.module = module;
    }
    
    public String getAction() {
        return action;
    }
    
    public void setAction(String action) {
        this.action = action;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }
    
    public String getSender() {
        return sender;
    }
    
    public void setSender(String sender) {
        this.sender = sender;
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
    }
    
    public Map<String, Object> getData() {
        return data;
    }
    
    public void setData(Map<String, Object> data) {
        this.data = data;
    }
    
    public MessageCode getCode() {
        return code;
    }
    
    public void setCode(MessageCode code) {
        this.code = code;
    }
    
    public String getMessage() {
        return message;
    }
    
    public void setMessage(String message) {
        this.message = message;
    }
    
    // ===== 工具方法：向 data 中添加/获取字段 =====
    public void putData(String key, Object value) {
        this.data.put(key, value);
    }
    
    @SuppressWarnings("unchecked")
    public <T> T getData(String key) {
        return (T) this.data.get(key);
    }
    
    @Override
    public String toString() {
        return "Message{" +
                "UID=" + UID +
                ", name='" + name + '\'' +
                ", type=" + type +
                ", module='" + module + '\'' +
                ", action='" + action + '\'' +
                ", sender='" + sender + '\'' +
                ", code=" + code +
                ", message='" + message + '\'' +
                '}';
    }
}