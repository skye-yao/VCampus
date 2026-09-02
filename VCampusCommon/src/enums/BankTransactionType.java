package enums;

/** 校园银行流水类型。 */
public enum BankTransactionType {
    INITIAL_BALANCE("初始入账"),
    ACCOUNT_RECHARGE("历史账户入账"), // 仅兼容已有演示流水，不再提供充值入口
    TRANSFER_OUT("转账支出"),
    TRANSFER_IN("转账收入"),
    SHOP_PAYMENT("商店消费"),
    SHOP_INCOME("商店收入"),
    SHOP_REFUND("商店退款"),
    SHOP_REFUND_PAYOUT("商店退款支出"),
    TUITION_PAYMENT("学费缴纳"),
    REIMBURSEMENT_PAYOUT("报销支出"),
    REIMBURSEMENT("报销入账"),
    GRANT("补助发放");

    private final String description;

    BankTransactionType(String description) { this.description = description; }
    public String getDescription() { return description; }
    public static BankTransactionType fromCode(String code) { return valueOf(code.toUpperCase()); }
}
