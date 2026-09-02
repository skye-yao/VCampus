package dao;

import entity.ShopRefund;
import util.DBUtil;

import java.sql.*;

/** 整单退款申请数据访问对象。 */
public class ShopRefundDAO {

    public long insert(ShopRefund refund) throws SQLException {
        String sql = "INSERT INTO tbl_shop_refund(refund_no,order_id,user_id,refund_amount,reason,status," +
                "original_transaction_no) VALUES(?,?,?,?,?,'APPLIED',?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, refund.getRefundNo());
            stmt.setLong(2, refund.getOrderId());
            stmt.setString(3, refund.getUserId());
            stmt.setBigDecimal(4, refund.getRefundAmount());
            stmt.setString(5, refund.getReason());
            stmt.setString(6, refund.getOriginalTransactionNo());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }
}
