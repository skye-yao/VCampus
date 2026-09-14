package service;

import exception.BusinessException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;

/** 校验经 Socket 传入的商品图片，数据库只存校验后的二进制内容。 */
public final class ProductImageCodec {
    public static final int MAX_IMAGE_BYTES = 1024 * 1024;
    private static final int MAX_DIMENSION = 4096;

    private ProductImageCodec() { }

    public record ValidatedImage(String mimeType, byte[] bytes) {
        public ValidatedImage { bytes = bytes.clone(); }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    public static ValidatedImage decode(String base64) {
        int maxEncodedLength = ((MAX_IMAGE_BYTES + 2) / 3) * 4;
        if (base64 == null || base64.isBlank()) throw new BusinessException("请选择商品图片");
        if (base64.length() > maxEncodedLength) throw new BusinessException("商品图片不能超过 1 MiB");

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("图片编码不正确", e);
        }
        if (bytes.length > MAX_IMAGE_BYTES) throw new BusinessException("商品图片不能超过 1 MiB");

        String mimeType = isPng(bytes) ? "image/png" : isJpeg(bytes) ? "image/jpeg" : null;
        if (mimeType == null) throw new BusinessException("仅支持 PNG 或 JPEG 图片");

        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new BusinessException("无法读取图片内容");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new BusinessException("图片内容已损坏");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String detectedFormat = reader.getFormatName();
                if (!("image/png".equals(mimeType) && "png".equalsIgnoreCase(detectedFormat))
                        && !("image/jpeg".equals(mimeType) && "jpeg".equalsIgnoreCase(detectedFormat))) {
                    throw new BusinessException("图片格式与内容不一致");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                    throw new BusinessException("图片尺寸不能超过 4096×4096");
                }
                if (reader.read(0) == null) throw new BusinessException("图片内容已损坏");
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof BusinessException businessException) throw businessException;
            throw new BusinessException("图片内容已损坏", e);
        }
        return new ValidatedImage(mimeType, bytes);
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8 && (bytes[0] & 0xff) == 137 && bytes[1] == 80
                && bytes[2] == 78 && bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10
                && bytes[6] == 26 && bytes[7] == 10;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 0xff) == 255
                && (bytes[1] & 0xff) == 216 && (bytes[2] & 0xff) == 255;
    }
}
