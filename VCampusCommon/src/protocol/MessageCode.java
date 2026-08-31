package protocol;

/**
 * 消息状态码枚举
 * 统一管理所有响应状态码
 */
public enum MessageCode {
    
    // ===== 通用状态码 (200-599) =====
    SUCCESS(200, "成功"),
    ERROR(500, "系统错误"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未认证，请先登录"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409,"当前业务状态冲突");
    
    // ===== 业务状态码 (1000-1999: 用户模块) =====
    
    
    // ===== 业务状态码 (2000-2999: 图书模块) =====
    
    
    // ===== 业务状态码 (3000-3999: 银行模块) =====
   
    
    // ===== 业务状态码 (4000-4999: 商店模块) =====
    
    
    // ===== 业务状态码 (5000-5999: 选课模块) =====
    
    
    // ===== 业务状态码 (6000-6999: AI模块) =====
    
    
    private final int code;//状态码数字，final一旦赋值就不能再改
    private final String message;//状态码描述
    
    MessageCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
    
    public int getCode() {
        return code;
    }
    
    public String getMessage() {
        return message;
    }
    
    /**
     * 根据状态码查找枚举
     */
    public static MessageCode fromCode(int code) {
        for (MessageCode mc : MessageCode.values()) {
            if (mc.getCode() == code) {
                return mc;
            }
        }
        return ERROR;
    }
}