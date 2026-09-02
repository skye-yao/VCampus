package enums;

/** 商品上下架状态。 */
public enum ProductStatus {
    ON_SALE("ON_SALE", "销售中"),
    OFF_SALE("OFF_SALE", "已下架");

    private final String code;
    private final String description;

    ProductStatus(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ProductStatus fromCode(String code) {
        for (ProductStatus status : values()) {
            if (status.code.equalsIgnoreCase(code)) {
                return status;
            }
        }
        return null;
    }
}
