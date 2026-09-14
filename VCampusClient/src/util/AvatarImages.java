package util;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;

/** 在解码前检查尺寸，并下采样大图，避免把手机原图全部载入内存。 */
public final class AvatarImages {
    private AvatarImages() {}

    public static BufferedImage read(File file) throws IOException {
        if (Files.size(file.toPath()) > 20L * 1024 * 1024)
            throw new IOException("请选择 20MB 以内的图片");
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            if (input == null) throw new IOException("无法读取所选图片");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("请选择有效的 JPG 或 PNG 图片");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("JPEG") && !format.equalsIgnoreCase("PNG"))
                    throw new IOException("仅支持 JPG 和 PNG 图片");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > 100_000_000L)
                    throw new IOException("图片尺寸过大，请选择不超过 1 亿像素的图片");
                ImageReadParam param = reader.getDefaultReadParam();
                int sample = Math.max(1, (Math.max(width, height) + 2047) / 2048);
                param.setSourceSubsampling(sample, sample, 0, 0);
                return reader.read(0, param);
            } finally { reader.dispose(); }
        }
    }

    public static byte[] crop(BufferedImage source, int x, int y, int side) throws IOException {
        if (side <= 0 || x < 0 || y < 0 || x > source.getWidth() - side || y > source.getHeight() - side)
            throw new IllegalArgumentException("裁剪区域超出图片范围");
        BufferedImage output = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, 512, 512, x, y, x + side, y + side, null);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(output, "png", bytes)) throw new IOException("无法生成头像图片");
        if (bytes.size() > 2 * 1024 * 1024) throw new IOException("裁剪后的头像超过 2MB，请重新选择图片");
        return bytes.toByteArray();
    }
}
