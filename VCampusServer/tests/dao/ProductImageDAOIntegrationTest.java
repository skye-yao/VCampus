package dao;

import entity.Product;
import enums.ProductStatus;
import util.DBUtil;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

/** 在现有数据库中用事务回滚的临时商品验证图片读写，不保留测试商品。 */
public class ProductImageDAOIntegrationTest {
    public static void main(String[] args) throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            // 为现有数据库执行与 init.sql 一致的、可重复的增量建表。
            String createImageTable = "CREATE TABLE IF NOT EXISTS tbl_product_image ("
                    + "product_id BIGINT NOT NULL PRIMARY KEY,"
                    + "mime_type VARCHAR(20) NOT NULL,"
                    + "image_data MEDIUMBLOB NOT NULL,"
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                    + "CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) "
                    + "REFERENCES tbl_product(product_id) ON DELETE CASCADE"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createImageTable);
                stmt.execute(createImageTable);
            }
            conn.setAutoCommit(false);
            try {
                Product product = new Product();
                product.setProductName("图片测试-" + UUID.randomUUID());
                product.setDescription("事务回滚测试商品");
                product.setCategory("文具");
                product.setPrice(new BigDecimal("1.00"));
                product.setStock(1);
                product.setStatus(ProductStatus.ON_SALE);
                long productId = new ProductDAO().insert(conn, product);

                ProductImageDAO dao = new ProductImageDAO();
                byte[] first = new byte[]{1, 2, 3};
                dao.upsert(conn, productId, "image/png", first);
                ProductImageDAO.ImageRow saved = dao.findByProductId(conn, productId);
                if (saved == null || !"image/png".equals(saved.mimeType())
                        || !Arrays.equals(first, saved.bytes())) {
                    throw new AssertionError("新增图片未正确写入并读取");
                }

                byte[] replacement = new byte[]{9, 8, 7};
                dao.upsert(conn, productId, "image/jpeg", replacement);
                ProductImageDAO.ImageRow replaced = dao.findByProductId(conn, productId);
                if (replaced == null || !"image/jpeg".equals(replaced.mimeType())
                        || !Arrays.equals(replacement, replaced.bytes())) {
                    throw new AssertionError("替换图片未正确覆盖");
                }
                System.out.println("ProductImageDAOIntegrationTest PASS");
            } finally {
                conn.rollback();
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("数据库连接或商品图片表不可用；请先执行 init.sql 中的 tbl_product_image 建表语句", e);
        }
    }
}
