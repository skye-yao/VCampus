package util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** 上传前的客户端文件读取边界测试。 */
public class ShopImageClientCodecTest {
    public static void main(String[] args) throws Exception {
        Path small = Files.createTempFile("shop-image-small", ".png");
        Path large = Files.createTempFile("shop-image-large", ".png");
        try {
            byte[] payload = new byte[]{1, 2, 3, 4};
            Files.write(small, payload);
            Files.write(large, new byte[1024 * 1024 + 1]);
            byte[] encoded = Base64.getDecoder().decode(ShopImageClientCodec.encode(small));
            if (!java.util.Arrays.equals(payload, encoded)) throw new AssertionError("上传内容改变");
            try {
                ShopImageClientCodec.encode(large);
                throw new AssertionError("超过 1 MiB 的文件应被拒绝");
            } catch (IllegalArgumentException expected) { }
            System.out.println("ShopImageClientCodecTest PASS");
        } finally {
            Files.deleteIfExists(small);
            Files.deleteIfExists(large);
        }
    }
}
