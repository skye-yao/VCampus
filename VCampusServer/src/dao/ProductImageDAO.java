package dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 商品图片二进制存取；事务由调用方控制。 */
public class ProductImageDAO {
    public record ImageRow(String mimeType, byte[] bytes) {
        public ImageRow { bytes = bytes.clone(); }
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
