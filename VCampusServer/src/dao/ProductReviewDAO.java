package dao;

import entity.ProductReview;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** 商品评价数据访问；连接由调用方提供，事务由调用方控制。 */
public class ProductReviewDAO {

    /** 是否购买过该商品：只要付过款就算（含退款中与已退款）。 */
    public boolean hasPurchased(Connection conn, String userId, long productId) throws SQLException {
        String sql = "SELECT 1 FROM tbl_shop_order o JOIN tbl_order_item i ON i.order_id=o.order_id "
                + "WHERE o.user_id=? AND i.product_id=? AND o.status IN ('PAID','REFUNDING','REFUNDED') LIMIT 1";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, userId);
            stmt.setLong(2, productId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next(); }
        }
    }

    /** 该用户是否已经评价过这件商品。 */
    public boolean existsForUser(Connection conn, long productId, String userId) throws SQLException {
        String sql = "SELECT 1 FROM tbl_product_review WHERE product_id=? AND user_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            stmt.setString(2, userId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next(); }
        }
    }

    public List<ProductReview> findByProductId(Connection conn, long productId) throws SQLException {
        String sql = "SELECT * FROM tbl_product_review WHERE product_id=? ORDER BY created_at DESC, review_id DESC";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                List<ProductReview> reviews = new ArrayList<>();
                while (rs.next()) reviews.add(map(rs));
                return reviews;
            }
        }
    }

    public ProductReview findById(Connection conn, long reviewId) throws SQLException {
        String sql = "SELECT * FROM tbl_product_review WHERE review_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, reviewId);
            try (ResultSet rs = stmt.executeQuery()) { return rs.next() ? map(rs) : null; }
        }
    }

    public long insert(Connection conn, ProductReview review) throws SQLException {
        String sql = "INSERT INTO tbl_product_review(product_id,user_id,rating,content) VALUES(?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setLong(1, review.getProductId());
            stmt.setString(2, review.getUserId());
            stmt.setInt(3, review.getRating());
            stmt.setString(4, review.getContent());
            stmt.executeUpdate();
            try (ResultSet keys = stmt.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        }
    }

    public boolean delete(Connection conn, long reviewId) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM tbl_product_review WHERE review_id=?")) {
            stmt.setLong(1, reviewId);
            return stmt.executeUpdate() == 1;
        }
    }

    /** 返回 [评价条数, 评分总和]，用于计算平均分。 */
    public long[] statistics(Connection conn, long productId) throws SQLException {
        String sql = "SELECT COUNT(*), IFNULL(SUM(rating),0) FROM tbl_product_review WHERE product_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0L, 0L};
            }
        }
    }

    private ProductReview map(ResultSet rs) throws SQLException {
        ProductReview review = new ProductReview();
        review.setReviewId(rs.getLong("review_id"));
        review.setProductId(rs.getLong("product_id"));
        review.setUserId(rs.getString("user_id"));
        review.setRating(rs.getInt("rating"));
        review.setContent(rs.getString("content"));
        review.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
        return review;
    }
}
