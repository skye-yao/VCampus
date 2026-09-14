package util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** 客户端上传前限制文件大小；实际图片格式仍由服务端验证。 */
public final class ShopImageClientCodec {
    private static final int MAX_IMAGE_BYTES = 1024 * 1024;

    private ShopImageClientCodec() { }

    public static String encode(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("请先选择图片");
        if (Files.size(path) > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片不能超过 1 MiB");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) throw new IllegalArgumentException("图片文件为空");
        if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片不能超过 1 MiB");
        return Base64.getEncoder().encodeToString(bytes);
    }
}
