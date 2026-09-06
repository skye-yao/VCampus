package service;

import java.math.BigDecimal;
import java.sql.Connection;

/**
 * 银行模块向商店提供的服务器内部接口。
 * 客户端不能直接调用，也不能自行指定订单金额。
 */
public interface IBankPaymentService {
    String payShopOrder(Connection connection, String userId, long orderId,
                        BigDecimal actualAmount, String paymentPassword,
                        String requestId);

    String refundShopOrder(Connection connection, long orderId,
                           String originalTransactionNo, BigDecimal actualAmount,
                           String requestId);

    /**
     * 扣除 AI 助手 Token 服务费（服务端内部调用，带事务控制）
     */
    String deductAiFee(Connection connection, String userId,
                       BigDecimal actualAmount, String requestId, String remark);
}
