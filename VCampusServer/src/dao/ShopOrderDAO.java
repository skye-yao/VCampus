package dao;

import entity.ShopOrder;
import enums.OrderStatus;
import util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 商店订单数据访问对象。 */
public class ShopOrderDAO {

    public long insert(Connection conn, ShopOrder order) throws SQLException {
        String sql = "INSERT INTO tbl_shop_order(order_no,user_id,total_amount,status,expires_at) " +
                "VALUES(?,?,?,'WAIT_PAY',?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, order.getOrderNo());
            stmt.setString(2, order.getUserId());
            stmt.setBigDecimal(3, order.getTotalAmount());
            stmt.setTimestamp(4, Timestamp.valueOf(order.getExpiresAt()));
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("创建订单后未获取到订单编号");
    }

    public List<ShopOrder> findByUserId(String userId, boolean admin) throws SQLException {
        String sql = "SELECT * FROM tbl_shop_order " + (admin ? "" : "WHERE user_id=? ") +
                "ORDER BY created_at DESC";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!admin) stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<ShopOrder> orders = new ArrayList<>();
                while (rs.next()) orders.add(map(rs));
                return orders;
            }
        }
    }

    public ShopOrder findById(Connection conn, long orderId, boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM tbl_shop_order WHERE order_id=?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public ShopOrder findById(long orderId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            return findById(conn, orderId, false);
        }
    }

    public boolean changeStatus(Connection conn, long orderId, OrderStatus expected, OrderStatus target) throws SQLException {
        String sql = "UPDATE tbl_shop_order SET status=?,version=version+1," +
                (target == OrderStatus.CANCELLED ? "cancelled_at=CURRENT_TIMESTAMP," : "") +
                "updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND status=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, target.getCode());
            stmt.setLong(2, orderId);
            stmt.setString(3, expected.getCode());
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean markPaid(Connection conn, long orderId, String transactionNo) throws SQLException {
        String sql = "UPDATE tbl_shop_order SET status='PAID',payment_transaction_no=?," +
                "paid_at=CURRENT_TIMESTAMP,version=version+1,updated_at=CURRENT_TIMESTAMP " +
                "WHERE order_id=? AND status='WAIT_PAY'";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, transactionNo);
            stmt.setLong(2, orderId);
            return stmt.executeUpdate() == 1;
        }
    }

    private ShopOrder map(ResultSet rs) throws SQLException {
        ShopOrder order = new ShopOrder();
        order.setOrderId(rs.getLong("order_id"));
        order.setOrderNo(rs.getString("order_no"));
        order.setUserId(rs.getString("user_id"));
        order.setTotalAmount(rs.getBigDecimal("total_amount"));
        order.setStatus(OrderStatus.fromCode(rs.getString("status")));
        order.setPaymentTransactionNo(rs.getString("payment_transaction_no"));
        order.setExpiresAt(text(rs.getTimestamp("expires_at")));
        order.setPaidAt(text(rs.getTimestamp("paid_at")));
        order.setCancelledAt(text(rs.getTimestamp("cancelled_at")));
        order.setVersion(rs.getInt("version"));
        order.setCreatedAt(text(rs.getTimestamp("created_at")));
        order.setUpdatedAt(text(rs.getTimestamp("updated_at")));
        return order;
    }

    private String text(Timestamp value) {
        return value == null ? null : value.toLocalDateTime().toString().replace('T', ' ');
    }
}
