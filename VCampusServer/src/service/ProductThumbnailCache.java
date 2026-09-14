package service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按需把商品原图缩放为缩略图，并按图片更新时间缓存。
 *
 * <p>商品中心切换到缩略图视图时，服务端只对请求中的商品生成小图；
 * 原图内容不变时直接复用缓存，避免反复解码大图。图片被替换后，
 * {@code updated_at} 变化会让旧缓存自然失效。
 */
public final class ProductThumbnailCache {

    /** 缩略图最长边像素。 */
    private static final int MAX_EDGE = 180;
    private static final float JPEG_QUALITY = 0.85f;
    /** 缓存条目上限，超出后整体清空，避免长期运行占用过多内存。 */
    private static final int MAX_ENTRIES = 512;

    private static final Map<Long, Entry> CACHE = new ConcurrentHashMap<>();

    private record Entry(long updatedAtMillis, byte[] jpeg) { }

    private ProductThumbnailCache() { }

    /** 返回 JPEG 缩略图字节；原图不可解码时返回 null。 */
    public static byte[] thumbnail(long productId, long updatedAtMillis, byte[] source) {
        if (source == null || source.length == 0) return null;
        Entry cached = CACHE.get(productId);
        if (cached != null && cached.updatedAtMillis() == updatedAtMillis) return cached.jpeg().clone();
        byte[] generated = scale(source);
        if (generated == null) return null;
        if (CACHE.size() >= MAX_ENTRIES) CACHE.clear();
        CACHE.put(productId, new Entry(updatedAtMillis, generated));
        return generated.clone();
    }

    /** 替换图片后主动失效，保证下一次读取拿到新图。 */
    public static void invalidate(long productId) {
        CACHE.remove(productId);
    }

    private static byte[] scale(byte[] source) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(source)) {
            BufferedImage original = ImageIO.read(input);
            if (original == null || original.getWidth() < 1 || original.getHeight() < 1) return null;
            int width = original.getWidth();
            int height = original.getHeight();
            double ratio = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
            int targetWidth = Math.max(1, (int) Math.round(width * ratio));
            int targetHeight = Math.max(1, (int) Math.round(height * ratio));
            BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                // JPEG 不支持透明，先铺白底，避免 PNG 透明区域变黑。
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, targetWidth, targetHeight);
                graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            return writeJpeg(target);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
            stream.flush();
            return output.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
