package enums;

/**
 * 罚款缴费状态
 */
public enum FineStatus {

    UNPAID(0, "未缴费"),
    PAID(1, "已缴费");

    private final int code;
    private final String description;

    FineStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static FineStatus fromCode(int code) {
        for (FineStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的缴费状态码: " + code);
    }
}