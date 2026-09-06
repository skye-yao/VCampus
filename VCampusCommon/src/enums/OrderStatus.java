package enums;

/** 商店订单状态。 */
public enum OrderStatus {
    WAIT_PAY("WAIT_PAY", "待支付"),
    PAID("PAID", "已支付"),
    CANCELLED("CANCELLED", "已取消"),
    EXPIRED("EXPIRED", "已过期"),
    REFUNDING("REFUNDING", "退款中"),
    REFUNDED("REFUNDED", "已退款");

    private final String code;
    private final String description;

    OrderStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static OrderStatus fromCode(String code) {
        if ("PROCESSING".equalsIgnoreCase(code) || "COMPLETED".equalsIgnoreCase(code)) {
            return PAID;
        }
        for (OrderStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
