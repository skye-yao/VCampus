package dao;

import entity.CartItem;
import enums.ProductStatus;
import util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 购物车数据访问对象。 */
public class CartItemDAO {

    public List<CartItem> findByUserId(String userId) throws SQLException {
        String sql = "SELECT c.cart_item_id,c.user_id,c.product_id,c.quantity," +
                "p.product_name,p.price,p.stock,p.status " +
                "FROM tbl_cart_item c JOIN tbl_product p ON p.product_id=c.product_id " +
                "WHERE c.user_id=? ORDER BY c.updated_at DESC";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<CartItem> items = new ArrayList<>();
                while (rs.next()) items.add(map(rs));
                return items;
            }
        }
    }

    public List<CartItem> findByUserId(Connection conn, String userId, boolean forUpdate) throws SQLException {
        String sql = "SELECT c.cart_item_id,c.user_id,c.product_id,c.quantity," +
                "p.product_name,p.price,p.stock,p.status " +
                "FROM tbl_cart_item c JOIN tbl_product p ON p.product_id=c.product_id " +
                "WHERE c.user_id=? ORDER BY c.cart_item_id" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<CartItem> items = new ArrayList<>();
                while (rs.next()) items.add(map(rs));
                return items;
            }
        }
    }

    /** 仅查询并锁定当前用户明确选择的购物车项目。 */
    public List<CartItem> findSelected(Connection conn, String userId, List<Long> cartItemIds,
                                       boolean forUpdate) throws SQLException {
        if (cartItemIds == null || cartItemIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(cartItemIds.size(), "?"));
        String sql = "SELECT c.cart_item_id,c.user_id,c.product_id,c.quantity," +
                "p.product_name,p.price,p.stock,p.status " +
                "FROM tbl_cart_item c JOIN tbl_product p ON p.product_id=c.product_id " +
                "WHERE c.user_id=? AND c.cart_item_id IN (" + placeholders + ") " +
                "ORDER BY c.cart_item_id" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            for (int i = 0; i < cartItemIds.size(); i++) stmt.setLong(i + 2, cartItemIds.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                List<CartItem> items = new ArrayList<>();
                while (rs.next()) items.add(map(rs));
                return items;
            }
        }
    }

    public void addOrIncrease(String userId, long productId, int quantity) throws SQLException {
        String sql = "INSERT INTO tbl_cart_item(user_id,product_id,quantity) VALUES(?,?,?) " +
                "ON DUPLICATE KEY UPDATE quantity=quantity+VALUES(quantity), updated_at=CURRENT_TIMESTAMP";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setLong(2, productId);
            stmt.setInt(3, quantity);
            stmt.executeUpdate();
        }
    }

    public boolean updateQuantity(String userId, long cartItemId, int quantity) throws SQLException {
        String sql = "UPDATE tbl_cart_item SET quantity=?,updated_at=CURRENT_TIMESTAMP " +
                "WHERE cart_item_id=? AND user_id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantity);
            stmt.setLong(2, cartItemId);
            stmt.setString(3, userId);
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean remove(String userId, long cartItemId) throws SQLException {
        String sql = "DELETE FROM tbl_cart_item WHERE cart_item_id=? AND user_id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, cartItemId);
            stmt.setString(2, userId);
            return stmt.executeUpdate() == 1;
        }
    }

    public void clear(Connection conn, String userId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM tbl_cart_item WHERE user_id=?")) {
            stmt.setString(1, userId);
            stmt.executeUpdate();
        }
    }

    public void removeSelected(Connection conn, String userId, List<Long> cartItemIds) throws SQLException {
        if (cartItemIds == null || cartItemIds.isEmpty()) return;
        String placeholders = String.join(",", java.util.Collections.nCopies(cartItemIds.size(), "?"));
        String sql = "DELETE FROM tbl_cart_item WHERE user_id=? AND cart_item_id IN (" + placeholders + ")";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            for (int i = 0; i < cartItemIds.size(); i++) stmt.setLong(i + 2, cartItemIds.get(i));
            if (stmt.executeUpdate() != cartItemIds.size()) {
                throw new SQLException("所选购物车记录已发生变化");
            }
        }
    }

    private CartItem map(ResultSet rs) throws SQLException {
        CartItem item = new CartItem();
        item.setCartItemId(rs.getLong("cart_item_id"));
        item.setUserId(rs.getString("user_id"));
        item.setProductId(rs.getLong("product_id"));
        item.setQuantity(rs.getInt("quantity"));
        item.setProductName(rs.getString("product_name"));
        item.setUnitPrice(rs.getBigDecimal("price"));
        item.setSubtotal(item.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
        item.setAvailableStock(rs.getInt("stock"));
        item.setProductStatus(ProductStatus.fromCode(rs.getString("status")));
        return item;
    }
}
