package dao;

import entity.Product;
import enums.ProductStatus;
import util.DBUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/** 商品数据访问对象。 */
public class ProductDAO {

    public List<Product> findAll(String keyword, String category, boolean includeOffSale) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT * FROM tbl_product WHERE 1=1");
        List<Object> params = new ArrayList<>();
        if (!includeOffSale) {
            sql.append(" AND status = 'ON_SALE'");
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND product_name LIKE ?");
            params.add("%" + keyword.trim() + "%");
        }
        if (category != null && !category.trim().isEmpty()) {
            sql.append(" AND category = ?");
            params.add(category.trim());
        }
        sql.append(" ORDER BY status DESC, product_id DESC");

        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = stmt.executeQuery()) {
                List<Product> products = new ArrayList<>();
                while (rs.next()) products.add(map(rs));
                return products;
            }
        }
    }

    public Product findById(long productId) throws SQLException {
        try (Connection conn = DBUtil.getConnection()) {
            return findById(conn, productId, false);
        }
    }

    public Product findById(Connection conn, long productId, boolean forUpdate) throws SQLException {
        String sql = "SELECT * FROM tbl_product WHERE product_id = ?" + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? map(rs) : null;
            }
        }
    }

    public long insert(Product product) throws SQLException {
        String sql = "INSERT INTO tbl_product " +
                "(product_name, description, category, price, stock, status) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, product.getProductName());
            stmt.setString(2, product.getDescription());
            stmt.setString(3, product.getCategory());
            stmt.setBigDecimal(4, product.getPrice());
            stmt.setInt(5, product.getStock());
            stmt.setString(6, product.getStatus().getCode());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        throw new SQLException("新增商品后未获取到商品编号");
    }

    public boolean update(Product product) throws SQLException {
        String sql = "UPDATE tbl_product SET product_name=?, description=?, category=?, price=?, " +
                "version=version+1 WHERE product_id=? AND version=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, product.getProductName());
            stmt.setString(2, product.getDescription());
            stmt.setString(3, product.getCategory());
            stmt.setBigDecimal(4, product.getPrice());
            stmt.setLong(5, product.getProductId());
            stmt.setInt(6, product.getVersion());
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean changeStatus(long productId, ProductStatus status) throws SQLException {
        String sql = "UPDATE tbl_product SET status=?, version=version+1 WHERE product_id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, status.getCode());
            stmt.setLong(2, productId);
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean updateStock(long productId, int stock) throws SQLException {
        String sql = "UPDATE tbl_product SET stock=?, version=version+1 WHERE product_id=?";
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, stock);
            stmt.setLong(2, productId);
            return stmt.executeUpdate() == 1;
        }
    }

    public boolean decreaseStock(Connection conn, long productId, int quantity) throws SQLException {
        String sql = "UPDATE tbl_product SET stock=stock-?, version=version+1 " +
                "WHERE product_id=? AND status='ON_SALE' AND stock>=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantity);
            stmt.setLong(2, productId);
            stmt.setInt(3, quantity);
            return stmt.executeUpdate() == 1;
        }
    }

    public void increaseStock(Connection conn, long productId, int quantity) throws SQLException {
        String sql = "UPDATE tbl_product SET stock=stock+?, version=version+1 WHERE product_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, quantity);
            stmt.setLong(2, productId);
            if (stmt.executeUpdate() != 1) throw new SQLException("返还商品库存失败: " + productId);
        }
    }

    private Product map(ResultSet rs) throws SQLException {
        Product product = new Product();
        product.setProductId(rs.getLong("product_id"));
        product.setProductName(rs.getString("product_name"));
        product.setDescription(rs.getString("description"));
        product.setCategory(rs.getString("category"));
        product.setPrice(rs.getBigDecimal("price"));
        product.setStock(rs.getInt("stock"));
        product.setStatus(ProductStatus.fromCode(rs.getString("status")));
        product.setVersion(rs.getInt("version"));
        product.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
        product.setUpdatedAt(String.valueOf(rs.getTimestamp("updated_at")));
        return product;
    }
}
