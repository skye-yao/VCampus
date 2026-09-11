package service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 电子书存放在服务端，客户端只能通过图书编号访问。 */
public final class BookFileService {
    public byte[] readPdf(int bookId) throws IOException {
        if (bookId <= 0) throw new IOException("图书编号无效");
        Path root = Path.of(System.getProperty("vcampus.library.files", "library-files")).toAbsolutePath();
        Path file = root.resolve(bookId + ".pdf");
        if (!Files.isRegularFile(file)) throw new java.io.FileNotFoundException("该图书暂未提供 PDF 电子版");
        if (!file.toRealPath().startsWith(root.toRealPath())) throw new IOException("电子书路径无效");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
                || bytes[3] != 'F' || bytes[4] != '-') throw new IOException("电子书不是有效的 PDF 文件");
        return bytes;
    }
}
