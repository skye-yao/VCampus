package enums;

/**
 * 图书预约状态
 */
public enum ReservationStatus {

    RESERVING(0, "预约中"),
    CANCELLED(1, "已取消"),
    BORROWED(2, "已借阅");

    private final int code;
    private final String description;

    ReservationStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ReservationStatus fromCode(int code) {
        for (ReservationStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的预约状态码: " + code);
    }
}