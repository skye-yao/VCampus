package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 商品图片二进制存取；事务由调用方控制。 */
public class ProductImageDAO {
    public record ImageRow(String mimeType, byte[] bytes) {
        public ImageRow { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** 缩略图生成所需的原图内容与更新时间。 */
    public record ThumbSource(long productId, String mimeType, byte[] bytes, long updatedAtMillis) {
        public ThumbSource { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    /** 缩略图缓存只需要“有没有图、原图什么时候更新的”，不需要把图片二进制读出来。 */
    public record ThumbMeta(long productId, long updatedAtMillis) { }

    /** 只查元信息，供缓存命中判断；命中时完全不用碰 image_data。 */
    public List<ThumbMeta> findThumbMetas(Connection conn, List<Long> productIds) throws SQLException {
        if (productIds == null || productIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, UNIX_TIMESTAMP(updated_at)*1000 AS updated_ms "
                + "FROM tbl_product_image WHERE product_id IN (" + placeholders + ")";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < productIds.size(); i++) stmt.setLong(i + 1, productIds.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                List<ThumbMeta> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ThumbMeta(rs.getLong("product_id"), rs.getLong("updated_ms")));
                }
                return result;
            }
        }
    }

    /** 服务端启动预热用：一次取出前 limit 张图片的元信息。 */
    public List<ThumbMeta> findAllThumbMetas(Connection conn, int limit) throws SQLException {
        String sql = "SELECT product_id, UNIX_TIMESTAMP(updated_at)*1000 AS updated_ms "
                + "FROM tbl_product_image ORDER BY product_id LIMIT ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, Math.max(1, limit));
            try (ResultSet rs = stmt.executeQuery()) {
                List<ThumbMeta> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ThumbMeta(rs.getLong("product_id"), rs.getLong("updated_ms")));
                }
                return result;
            }
        }
    }

    public ImageRow findByProductId(Connection conn, long productId) throws SQLException {
        String sql = "SELECT mime_type, image_data FROM tbl_product_image WHERE product_id=?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? new ImageRow(rs.getString("mime_type"), rs.getBytes("image_data")) : null;
            }
        }
    }

    /** 一次查出多个商品的图片，供商品中心缩略图视图使用。 */
    public List<ThumbSource> findThumbSources(Connection conn, List<Long> productIds) throws SQLException {
        if (productIds == null || productIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        String sql = "SELECT product_id, mime_type, image_data, UNIX_TIMESTAMP(updated_at)*1000 AS updated_ms "
                + "FROM tbl_product_image WHERE product_id IN (" + placeholders + ")";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < productIds.size(); i++) stmt.setLong(i + 1, productIds.get(i));
            try (ResultSet rs = stmt.executeQuery()) {
                List<ThumbSource> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(new ThumbSource(rs.getLong("product_id"), rs.getString("mime_type"),
                            rs.getBytes("image_data"), rs.getLong("updated_ms")));
                }
                return result;
            }
        }
    }

    public void upsert(Connection conn, long productId, String mimeType, byte[] bytes) throws SQLException {
        String sql = "INSERT INTO tbl_product_image(product_id,mime_type,image_data) VALUES(?,?,?) "
                + "ON DUPLICATE KEY UPDATE mime_type=VALUES(mime_type),image_data=VALUES(image_data)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, productId);
            stmt.setString(2, mimeType);
            stmt.setBytes(3, bytes);
            stmt.executeUpdate();
        }
    }
}
