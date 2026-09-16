package service;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
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
    private static final float JPEG_QUALITY = 0.80f;
    /** 缓存条目上限，超出后整体清空，避免长期运行占用过多内存。 */
    private static final int MAX_ENTRIES = 512;
    /** JPEG 可以按 1/N 抽样解码，最多抽到 1/4，再交给 Graphics2D 缩到目标尺寸。 */
    private static final int MAX_SUBSAMPLE = 4;

    private static final Map<Long, Entry> CACHE = new ConcurrentHashMap<>();

    static {
        // 缩略图只在内存里处理，关掉 ImageIO 的磁盘缓存可省掉临时文件读写。
        ImageIO.setUseCache(false);
    }

    private record Entry(long updatedAtMillis, byte[] jpeg) { }

    private ProductThumbnailCache() { }

    /** 缓存条目上限，供服务端启动预热时使用。 */
    public static int maxEntries() {
        return MAX_ENTRIES;
    }

    /** 命中缓存返回缩略图副本；没有缓存或原图已更新时返回 null（调用方再去读原图）。 */
    public static byte[] get(long productId, long updatedAtMillis) {
        Entry cached = CACHE.get(productId);
        return cached != null && cached.updatedAtMillis() == updatedAtMillis ? cached.jpeg().clone() : null;
    }

    /** 返回 JPEG 缩略图字节；原图不可解码时返回 null。 */
    public static byte[] thumbnail(long productId, long updatedAtMillis, byte[] source) {
        byte[] hit = get(productId, updatedAtMillis);
        if (hit != null) return hit;
        if (source == null || source.length == 0) return null;
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
        try {
            BufferedImage original = readForThumbnail(source);
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

    /**
     * 解码原图。JPEG 支持抽样解码，先按 1/N 拿一张小图再缩放，
     * 比先把整张大图解码出来再缩要快得多；PNG 等格式走普通解码。
     */
    private static BufferedImage readForThumbnail(byte[] source) throws IOException {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
            if (stream == null) return ImageIO.read(new ByteArrayInputStream(source));
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) return ImageIO.read(new ByteArrayInputStream(source));
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                int step = 1;
                while (step < MAX_SUBSAMPLE && MAX_EDGE * (step + 1) <= Math.max(width, height)) step++;
                if (step > 1) {
                    ImageReadParam param = reader.getDefaultReadParam();
                    param.setSourceSubsampling(step, step, 0, 0);
                    return reader.read(0, param);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
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
