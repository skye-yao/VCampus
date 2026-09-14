package service;

import entity.Product;
import exception.BusinessException;
import handler.ShopHandler;
import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import session.SessionManager;
import session.UserSession;
import util.DBUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/** 用临时商品检验管理员权限、图片读取和并发版本冲突。 */
public class ShopProductImageIntegrationTest {
    public static void main(String[] args) throws Exception {
        ShopService shop = new ShopService();
        String uniqueName = "图片服务测试-" + UUID.randomUUID();
        Product product = new Product();
        product.setProductName(uniqueName);
        product.setDescription("临时商品");
        product.setCategory("文具");
        product.setPrice(new BigDecimal("1.00"));
        product.setStock(1);
        byte[] png = picture("png");
        byte[] jpeg = picture("jpg");

        expectRejected(() -> shop.createProduct("admin", product, Base64.getEncoder().encodeToString(png), false));
        expectRejected(() -> shop.createProduct("admin", product, "%%broken%%", true));
        expectRejected(() -> shop.createProduct("admin", product, "", true));
        if (productExists(uniqueName)) throw new AssertionError("无效上传不应创建商品");

        long productId = -1;
        UserSession studentSession = SessionManager.getInstance().createSession("213242789", "学生");
        try {
            productId = ((Number) shop.createProduct("admin", product,
                    Base64.getEncoder().encodeToString(png), true).get("productId")).longValue();
            Map<String, Object> detail = shop.getProductDetail(productId);
            if (!java.util.Arrays.equals(png, Base64.getDecoder().decode((String) detail.get("imageBase64")))) {
                throw new AssertionError("新商品图片无法读取");
            }
            if (!"image/png".equals(detail.get("imageMimeType"))) throw new AssertionError("PNG MIME 错误");
            ShopHandler reader = new ShopHandler();
            Message detailRequest = new Message(MessageType.REQUEST, "shop", "SHOP_PRODUCT_DETAIL");
            detailRequest.setToken(studentSession.getToken());
            detailRequest.putData("productId", productId);
            Message firstClientResponse = reader.handle(detailRequest);
            if (firstClientResponse.getCode() != MessageCode.SUCCESS
                    || !java.util.Arrays.equals(png, Base64.getDecoder().decode(
                    (String) firstClientResponse.getData("imageBase64")))) {
                throw new AssertionError("客户端不能通过商店协议读取新商品图片");
            }

            long id = productId;
            expectRejected(() -> shop.replaceProductImage("admin", id, 0,
                    Base64.getEncoder().encodeToString(jpeg), false));
            Message deniedRequest = new Message(MessageType.REQUEST, "shop", "SHOP_PRODUCT_IMAGE_SET");
            deniedRequest.setToken(studentSession.getToken());
            deniedRequest.putData("productId", id);
            deniedRequest.putData("version", 0);
            deniedRequest.putData("imageBase64", Base64.getEncoder().encodeToString(jpeg));
            if (reader.handle(deniedRequest).getCode() != MessageCode.BAD_REQUEST) {
                throw new AssertionError("普通用户不应能通过商店协议上传图片");
            }
            shop.replaceProductImage("admin", id, 0, Base64.getEncoder().encodeToString(jpeg), true);
            expectRejected(() -> shop.replaceProductImage("admin", id, 0,
                    Base64.getEncoder().encodeToString(png), true));
            detail = shop.getProductDetail(id);
            if (!java.util.Arrays.equals(jpeg, Base64.getDecoder().decode((String) detail.get("imageBase64")))) {
                throw new AssertionError("过期版本不应覆盖较新的图片");
            }
            if (!"image/jpeg".equals(detail.get("imageMimeType"))) throw new AssertionError("JPEG MIME 错误");
            Message secondClientResponse = reader.handle(detailRequest);
            if (secondClientResponse.getCode() != MessageCode.SUCCESS
                    || !java.util.Arrays.equals(jpeg, Base64.getDecoder().decode(
                    (String) secondClientResponse.getData("imageBase64")))) {
                throw new AssertionError("另一客户端重新请求后应看到新图片");
            }
        } finally {
            SessionManager.getInstance().removeSession(studentSession.getToken());
            if (productId > 0) cleanup(productId);
        }
        if (productExists(uniqueName)) throw new AssertionError("临时测试商品未清理");
        System.out.println("ShopProductImageIntegrationTest PASS");
    }

    private static byte[] picture(String format) throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0x33aa55);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(source, format, output)) throw new AssertionError("无法生成测试图片：" + format);
        return output.toByteArray();
    }

    private static boolean productExists(String name) throws Exception {
        try (Connection conn = DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT 1 FROM tbl_product WHERE product_name=?")) {
            stmt.setString(1, name);
            try (var rs = stmt.executeQuery()) { return rs.next(); }
        }
    }

    private static void cleanup(long productId) throws Exception {
        try (Connection conn = DBUtil.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement logs = conn.prepareStatement(
                    "DELETE FROM tbl_shop_operation_log WHERE target_type='PRODUCT' AND target_id=?");
                 PreparedStatement product = conn.prepareStatement("DELETE FROM tbl_product WHERE product_id=?")) {
                logs.setLong(1, productId);
                logs.executeUpdate();
                product.setLong(1, productId);
                product.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private static void expectRejected(Runnable action) {
        try { action.run(); }
        catch (BusinessException expected) { return; }
        throw new AssertionError("操作应被拒绝");
    }
}
