package dao;

import entity.OrderItem;
import util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 订单明细数据访问对象。 */
public class OrderItemDAO {

    public void insert(Connection conn, OrderItem item) throws SQLException {
        String sql = "INSERT INTO tbl_order_item(order_id,product_id,product_name_snapshot,unit_price,quantity,subtotal) " +
                "VALUES(?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, item.getOrderId());
            stmt.setLong(2, item.getProductId());
            stmt.setString(3, item.getProductNameSnapshot());
            stmt.setBigDecimal(4, item.getUnitPrice());
            stmt.setInt(5, item.getQuantity());
            stmt.setBigDecimal(6, item.getSubtotal());
            stmt.executeUpdate();
        }
    }

    public List<OrderItem> findByOrderId(long orderId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            return findByOrderId(conn, orderId);
        }
    }

    public List<OrderItem> findByOrderId(Connection conn, long orderId) throws SQLException {
        String sql = "SELECT * FROM tbl_order_item WHERE order_id=? ORDER BY order_item_id";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, orderId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<OrderItem> items = new ArrayList<>();
                while (rs.next()) {
                    OrderItem item = new OrderItem();
                    item.setOrderItemId(rs.getLong("order_item_id"));
                    item.setOrderId(rs.getLong("order_id"));
                    item.setProductId(rs.getLong("product_id"));
                    item.setProductNameSnapshot(rs.getString("product_name_snapshot"));
                    item.setUnitPrice(rs.getBigDecimal("unit_price"));
                    item.setQuantity(rs.getInt("quantity"));
                    item.setSubtotal(rs.getBigDecimal("subtotal"));
                    items.add(item);
                }
                return items;
            }
        }
    }
}
