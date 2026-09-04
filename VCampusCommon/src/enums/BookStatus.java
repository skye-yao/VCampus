package enums;

/**
 * 图书状态
 */
public enum BookStatus {

    AVAILABLE(0, "可借"),
    BORROWED(1, "已借"),
    RESERVED(2, "预约"),
    LOST(3, "遗失");

    private final int code;
    private final String description;

    BookStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据数据库中的状态码获取对应枚举
     */
    public static BookStatus fromCode(int code) {
        for (BookStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知的图书状态码: " + code);
    }
}