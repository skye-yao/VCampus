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
