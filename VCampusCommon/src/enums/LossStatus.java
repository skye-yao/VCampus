package enums;

/**
 * 图书挂失状态
 */
public enum LossStatus {

    LOST(0, "挂失中"),
    CANCELLED(1, "已解除");

    private final int code;
    private final String description;

    LossStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static LossStatus fromCode(int code) {
        for (LossStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的挂失状态码: " + code);
    }
}