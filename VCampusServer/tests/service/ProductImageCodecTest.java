package service;

import exception.BusinessException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** 商品图片内容校验，无需数据库即可运行。 */
public class ProductImageCodecTest {
    public static void main(String[] args) throws Exception {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(source, "png", output)) throw new AssertionError("无法生成测试 PNG");
        byte[] png = output.toByteArray();
        ProductImageCodec.ValidatedImage image = ProductImageCodec.decode(
                Base64.getEncoder().encodeToString(png));
        if (!"image/png".equals(image.mimeType())) throw new AssertionError("PNG 类型错误");
        if (!java.util.Arrays.equals(png, image.bytes())) throw new AssertionError("图片内容改变");

        expectRejected("损坏的 Base64", "%%%");
        expectRejected("不是图片", Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}));
        expectRejected("伪造 PNG 头", Base64.getEncoder().encodeToString(new byte[]{
                (byte) 137, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3}));
        expectRejected("超过 1 MiB", Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]));
        BufferedImage tooWide = new BufferedImage(4097, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream widePng = new ByteArrayOutputStream();
        ImageIO.write(tooWide, "png", widePng);
        expectRejected("图片宽度超过上限", Base64.getEncoder().encodeToString(widePng.toByteArray()));
        System.out.println("ProductImageCodecTest PASS");
    }

    private static void expectRejected(String label, String base64) {
        try {
            ProductImageCodec.decode(base64);
        } catch (BusinessException expected) {
            return;
        }
        throw new AssertionError(label + "应被拒绝");
    }
}
