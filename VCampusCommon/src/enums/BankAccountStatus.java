package enums;

/** 校园银行账户状态。 */
public enum BankAccountStatus {
    ACTIVE("正常"),
    LOCKED("已锁定"),
    RESET_REQUIRED("需要重设支付密码");

    private final String description;

    BankAccountStatus(String description) { this.description = description; }
    public String getDescription() { return description; }
    public static BankAccountStatus fromCode(String code) { return valueOf(code.toUpperCase()); }
}
