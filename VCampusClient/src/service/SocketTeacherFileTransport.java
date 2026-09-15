package service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dto.course.teacher.TeacherFileTicketDTO;
import network.SocketClient;
import session.ClientSession;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 走独立文件端口的 {@link TeacherFileTransport} 实现。
 *
 * <p>服务器地址复用业务连接的 {@link SocketClient#getServerHost()}，端口取自票据；两者都不额外配置，
 * 因此“客户端连的业务服务器”和“票据是那台服务器签发的”永远一致。
 *
 * <p>线协议与服务端 {@code CourseFileConnection} 严格同形（元数据字段名就是契约，两侧各写一次）：
 * 请求是长度前缀 JSON {@code {ticket,token,direction,size,sha256}} 加原始字节，响应是长度前缀 JSON
 * {@code {status,message}}。文件绝不进业务 JSON，也绝不与业务 readLine 连接混用。
 *
 * <p>两个方向都做完整核对：上传前先在本地核对长度与摘要，绝不上传一个与票据不符的文件；下载写进
 * 目标同目录的临时文件，长度与摘要都通过后才移动成用户选定的文件，失败则删除临时文件、目标文件
 * 保持原样。是否覆盖由调用方（FileChooser 之后的确认）决定，传输层只负责原子替换。
 */
public final class SocketTeacherFileTransport implements TeacherFileTransport {

    /** 传输缓冲，与服务端一致。 */
    public static final int BUFFER_BYTES = 64 * 1024;

    /** 元数据与回执的长度上限。 */
    public static final int MAX_METADATA_BYTES = 4096;

    private static final Gson GSON = new Gson();
    private static final String PARTIAL_SUFFIX = ".part";

    private static final ExecutorService EXECUTOR =
            Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "teacher-file-transfer");
                thread.setDaemon(true);
                return thread;
            });

    private final SocketClient client;

    public SocketTeacherFileTransport() {
        this(SocketClient.getInstance());
    }

    SocketTeacherFileTransport(SocketClient client) {
        this.client = client == null ? SocketClient.getInstance() : client;
    }

    @Override
    public CompletableFuture<Void> upload(TeacherFileTicketDTO ticket, Path file) {
        return transfer(handle -> {
            verifyLocalFile(ticket, file);
            exchange(handle, TeacherFileTicketDTO.DIRECTION_UPLOAD, ticket,
                    (in, out) -> sendFile(file, out));
        });
    }

    @Override
    public CompletableFuture<Void> download(TeacherFileTicketDTO ticket, Path file) {
        return transfer(handle -> {
            Objects.requireNonNull(file, "下载目标不能为空");
            Path target = file.toAbsolutePath();
            Path directory = target.getParent();
            if (directory == null || !Files.isDirectory(directory)) {
                throw new IOException("下载目标目录不存在");
            }
            Path partial = Files.createTempFile(directory, ".vcampus-" + target.getFileName() + "-",
                    PARTIAL_SUFFIX);
            try {
                exchange(handle, TeacherFileTicketDTO.DIRECTION_DOWNLOAD, ticket,
                        (in, out) -> receiveFile(ticket, in, partial));
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                deleteQuietly(partial);
                throw failure;
            }
        });
    }

    /**
     * 在后台线程执行一次短连接；Future 被取消（离开上传页）时立即关闭 Socket，让阻塞读写立刻结束。
     */
    private CompletableFuture<Void> transfer(Work work) {
        SocketHandle handle = new SocketHandle();
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
                work.run(handle);
            } catch (Exception failure) {
                throw new CompletionException(failure);
            }
        }, EXECUTOR);
        future.whenComplete((ignored, failure) -> handle.cancel());
        return future;
    }

    /**
     * 打开短连接、发送元数据、执行本方向的载荷、读取服务端回执。
     */
    private void exchange(SocketHandle handle, String direction, TeacherFileTicketDTO ticket,
            Payload payload) throws IOException {
        if (ticket == null || ticket.getTicket() == null || ticket.getTicket().isBlank()) {
            throw new IOException("文件票据无效");
        }
        if (ticket.getPort() <= 0 || ticket.getPort() > 65535) {
            throw new IOException("文件票据端口无效");
        }
        String token = ClientSession.getInstance().getToken();
        if (token == null || token.isBlank()) {
            throw new IOException("登录会话已失效，请重新登录");
        }
        byte[] metadata = metadata(direction, ticket, token);
        try (Socket socket = new Socket(client.getServerHost(), ticket.getPort())) {
            handle.attach(socket);
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), BUFFER_BYTES));
            DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES));
            out.writeInt(metadata.length);
            out.write(metadata);
            out.flush();
            payload.transfer(in, out);
            readResponse(in);
        }
    }

    /** 元数据字段名就是线协议契约，与服务端 CourseFileConnection 一一对应。 */
    private static byte[] metadata(String direction, TeacherFileTicketDTO ticket, String token)
            throws IOException {
        String json = GSON.toJson(Map.of(
                "ticket", ticket.getTicket(),
                "token", token,
                "direction", direction,
                "size", ticket.getByteLength(),
                "sha256", ticket.getSha256()));
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) {
            throw new IOException("文件元数据超出长度上限");
        }
        return bytes;
    }

    /**
     * 服务端回执：只有 OK 才算成功，其余把服务端给出的安全文案原样抛出。
     */
    private static void readResponse(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException closed) {
            throw new IOException("文件传输被服务端中断");
        }
        if (length <= 0 || length > MAX_METADATA_BYTES) {
            throw new IOException("服务端回执无效");
        }
        byte[] body = new byte[length];
        in.readFully(body);
        JsonObject response;
        try {
            response = JsonParser.parseString(new String(body, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("服务端回执无效");
        }
        if (!"OK".equals(response.get("status").getAsString())) {
            throw new IOException(response.get("message").getAsString());
        }
    }

    /** 上传前本地核对：长度与摘要任一不符都直接失败，不浪费一次上传。 */
    private static void verifyLocalFile(TeacherFileTicketDTO ticket, Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IOException("待上传文件不存在");
        }
        if (Files.size(file) != ticket.getByteLength()) {
            throw new IOException("待上传文件与票据声明的长度不一致");
        }
        if (!sha256(file).equalsIgnoreCase(ticket.getSha256())) {
            throw new IOException("待上传文件与票据声明的摘要不一致");
        }
    }

    private static void sendFile(Path file, DataOutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = Files.size(file);
        try (InputStream source = Files.newInputStream(file)) {
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("待上传文件在发送过程中被截断");
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        }
        out.flush();
    }

    /**
     * 下载载荷：按票据长度精确读取，提前 EOF 立即失败（临时文件由调用方删除）。
     */
    private static void receiveFile(TeacherFileTicketDTO ticket, DataInputStream in, Path partial)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = ticket.getByteLength();
        try (OutputStream sink = Files.newOutputStream(partial,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new IOException("文件下载在收到全部字节前中断");
                }
                sink.write(buffer, 0, read);
                remaining -= read;
            }
        }
        if (!sha256(partial).equalsIgnoreCase(ticket.getSha256())) {
            throw new IOException("下载内容校验失败");
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("运行环境缺少 SHA-256", failure);
        }
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream source = Files.newInputStream(file)) {
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件删除失败不影响本次下载的失败语义。
        }
    }

    @FunctionalInterface
    private interface Payload {
        void transfer(DataInputStream in, DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    private interface Work {
        void run(SocketHandle handle) throws IOException;
    }

    /**
     * 短连接的关闭句柄：取消与正常结束共用同一把锁，保证取消之后不会再挂上一个仍然打开的 Socket。
     */
    private static final class SocketHandle {
        private Socket socket;
        private boolean cancelled;

        synchronized void attach(Socket candidate) throws IOException {
            if (cancelled) {
                closeQuietly(candidate);
                throw new IOException("文件传输已取消");
            }
            socket = candidate;
        }

        synchronized void cancel() {
            cancelled = true;
            closeQuietly(socket);
            socket = null;
        }

        private static void closeQuietly(Socket socket) {
            if (socket == null) {
                return;
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // 连接已经结束：关闭是幂等的。
            }
        }
    }
}
