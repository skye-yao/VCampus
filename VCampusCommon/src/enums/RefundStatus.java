package enums;

/** 商店整单退款状态。 */
public enum RefundStatus {
    APPLIED("APPLIED", "已申请"),
    APPROVED("APPROVED", "审核通过"),
    REJECTED("REJECTED", "审核拒绝"),
    PROCESSING("PROCESSING", "退款中"),
    SUCCESS("SUCCESS", "退款成功"),
    FAILED("FAILED", "退款失败");

    private final String code;
    private final String description;

    RefundStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RefundStatus fromCode(String code) {
        for (RefundStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
