package dao;

import entity.ShopRefund;
import enums.RefundStatus;
import enums.OrderStatus;
import java.util.ArrayList;
import java.util.List;

import java.sql.*;

/** 整单退款申请数据访问对象。 */
public class ShopRefundDAO {

    public long insert(Connection conn, ShopRefund refund) throws SQLException {
        String sql = "INSERT INTO tbl_shop_refund(refund_no,order_id,user_id,refund_amount,reason,status," +
                "original_transaction_no,previous_order_status) VALUES(?,?,?,?,?,'APPLIED',?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, refund.getRefundNo());
            stmt.setLong(2, refund.getOrderId());
            stmt.setString(3, refund.getUserId());
            stmt.setBigDecimal(4, refund.getRefundAmount());
            stmt.setString(5, refund.getReason());
            stmt.setString(6, refund.getOriginalTransactionNo());
            stmt.setString(7, refund.getPreviousOrderStatus().getCode());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    public List<ShopRefund> findAll() throws SQLException {
        try (Connection conn = util.DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tbl_shop_refund ORDER BY refund_id DESC");
             ResultSet rs = stmt.executeQuery()) {
            List<ShopRefund> result = new ArrayList<>();
            while (rs.next()) result.add(map(rs));
            return result;
        }
    }

    public ShopRefund findById(Connection conn, long refundId, boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM tbl_shop_refund WHERE refund_id=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, refundId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public boolean review(Connection conn, long refundId, RefundStatus status, String reviewerId,
                          String comment, String refundTransactionNo) throws SQLException {
        String sql = "UPDATE tbl_shop_refund SET status=?,reviewer_id=?,review_comment=?," +
                "refund_transaction_no=?,reviewed_at=NOW() WHERE refund_id=? AND status='APPLIED'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.getCode()); stmt.setString(2, reviewerId); stmt.setString(3, comment);
            stmt.setString(4, refundTransactionNo); stmt.setLong(5, refundId);
            return stmt.executeUpdate() == 1;
        }
    }

    private ShopRefund map(ResultSet rs) throws SQLException {
        ShopRefund item = new ShopRefund();
        item.setRefundId(rs.getLong("refund_id")); item.setRefundNo(rs.getString("refund_no"));
        item.setOrderId(rs.getLong("order_id")); item.setUserId(rs.getString("user_id"));
        item.setRefundAmount(rs.getBigDecimal("refund_amount")); item.setReason(rs.getString("reason"));
        item.setStatus(RefundStatus.fromCode(rs.getString("status")));
        item.setOriginalTransactionNo(rs.getString("original_transaction_no"));
        item.setRefundTransactionNo(rs.getString("refund_transaction_no"));
        item.setReviewerId(rs.getString("reviewer_id")); item.setReviewComment(rs.getString("review_comment"));
        item.setPreviousOrderStatus(OrderStatus.fromCode(rs.getString("previous_order_status")));
        item.setRequestedAt(String.valueOf(rs.getTimestamp("requested_at")));
        Timestamp reviewed = rs.getTimestamp("reviewed_at"); item.setReviewedAt(reviewed == null ? null : reviewed.toString());
        return item;
    }
}
