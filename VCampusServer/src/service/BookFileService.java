package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 电子书存放在服务端，客户端只能通过图书编号访问。 */
public final class BookFileService {
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    public byte[] readPdf(int bookId) throws IOException {
        if (bookId <= 0) throw new IOException("图书编号无效");
        Path root = Path.of(System.getProperty("vcampus.library.files", "library-files")).toAbsolutePath();
        Path file = root.resolve(bookId + ".pdf");
        if (!Files.isRegularFile(file)) throw new java.io.FileNotFoundException("该图书暂未提供 PDF 电子版");
        if (!file.toRealPath().startsWith(root.toRealPath())) throw new IOException("电子书路径无效");
        if (Files.size(file) > MAX_BYTES) throw new IOException("电子书超过 10 MB，请联系管理员压缩后重新配置");
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("电子书超过 10 MB");
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
                || bytes[3] != 'F' || bytes[4] != '-') throw new IOException("电子书不是有效的 PDF 文件");
        return bytes;
    }
}
