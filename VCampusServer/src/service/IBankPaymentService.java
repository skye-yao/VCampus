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
}
