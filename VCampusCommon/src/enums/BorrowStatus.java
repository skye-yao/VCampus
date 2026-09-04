package enums;

/**
 * 借阅状态
 */
public enum BorrowStatus {

    BORROWING(0, "借阅中"),
    RETURNED(1, "已归还"),
    OVERDUE(2, "逾期");

    private final int code;
    private final String description;

    BorrowStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static BorrowStatus fromCode(int code) {
        for (BorrowStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的借阅状态码: " + code);
    }
}