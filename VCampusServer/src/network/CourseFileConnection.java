package network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dto.course.teacher.TeacherFileTicketDTO;
import service.TeacherFileTicketService;
import service.TeacherFileTicketService.Ticket;
import session.SessionManager;
import session.UserSession;

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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一条文件短连接的完整生命周期：读元数据 → 兑换票据 → 传输原始字节 → 回执 → 关闭。
 *
 * <p>线协议（两个方向同形，绝不与业务 readLine 连接混用）：
 *
 * <pre>
 * 请求：int   metadataLength（1..4096）
 *       byte  metadataLength 个 UTF-8 JSON：{"ticket","token","direction","size","sha256"}
 *       byte  上传时才有的 payload，长度恰好等于已声明并核对过的 size
 * 响应：int   responseLength
 *       byte  responseLength 个 UTF-8 JSON：{"status":"OK"|"ERROR","message":...}
 * </pre>
 *
 * <p>元数据里的 token 必须解析出仍然有效的 Session，且该会话就是票据签发时的那个（教师 UID 与
 * token 都要一致），否则一个教师可以兑换别人的票据。方向、长度、摘要三者都要与票据逐项核对：
 * 方向不符是把下载票当上传用，长度不符是伪造声明，摘要不符是内容被替换。
 *
 * <p>上传先写 {@code .part} 临时文件，长度与 SHA-256 都核对通过后才原子改名到票据落点；提前
 * EOF、摘要不符、写盘失败都会删除半截文件，服务端不会留下半个工作簿。落盘成功之后才向票据服务
 * 交接「已落地」状态，业务侧随后的导入预览据此拿到同一个文件；失败的上传不会交接。所有外部可见
 * 的错误文案都是固定中文短语，异常堆栈只进服务端日志。
 */
public final class CourseFileConnection implements AutoCloseable {

    /** 元数据长度上限，与设计第 9 节一致。 */
    public static final int MAX_METADATA_BYTES = 4096;

    /** 传输缓冲。 */
    public static final int BUFFER_BYTES = 64 * 1024;

    /** 单个文件字节上限。 */
    public static final long MAX_PAYLOAD_BYTES = TeacherFileTicketService.MAX_FILE_BYTES;

    private static final String PARTIAL_SUFFIX = ".part";

    private static final Gson GSON = new Gson();

    private final Socket socket;
    private final TeacherFileTicketService tickets;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CourseFileConnection(Socket socket, TeacherFileTicketService tickets) {
        if (socket == null || tickets == null) {
            throw new IllegalArgumentException("文件连接缺少 Socket 或票据服务");
        }
        this.socket = socket;
        this.tickets = tickets;
    }

    /**
     * 处理这条短连接；任何失败都只回一条 ERROR 回执并关闭连接，不向调用方抛异常。
     */
    public void run() {
        DataOutputStream out = null;
        try {
            DataInputStream in = new DataInputStream(
                    new BufferedInputStream(socket.getInputStream(), BUFFER_BYTES));
            out = new DataOutputStream(
                    new BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES));

            Metadata metadata = readMetadata(in);
            Ticket ticket = claim(metadata);
            if (TeacherFileTicketDTO.DIRECTION_UPLOAD.equals(ticket.direction())) {
                receive(ticket, in);
                // 只有长度与 SHA-256 都核对通过、.part 已经改名之后才交接：业务侧随后的预览
                // 兑换到的必须是真正落盘的那个文件，中途失败的上传不许留下一张“已落地”的票。
                tickets.markUploaded(metadata.ticket());
            } else {
                send(ticket, out);
            }
            respond(out, true, "OK");
        } catch (TransferFailure expected) {
            respondQuietly(out, expected.getMessage());
        } catch (IllegalArgumentException rejected) {
            respondQuietly(out, rejected.getMessage());
        } catch (Exception failure) {
            System.err.println("文件短连接处理失败：" + failure);
            failure.printStackTrace(System.err);
            respondQuietly(out, "文件传输失败");
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 连接已经断开：关闭是幂等的。
        }
    }

    /**
     * 长度前缀元数据。长度必须落在 1..4096，越界立即拒绝，绝不按声明分配内存。
     */
    private static Metadata readMetadata(DataInputStream in) throws IOException {
        int metadataLength;
        try {
            metadataLength = in.readInt();
        } catch (EOFException missing) {
            throw new TransferFailure("文件元数据缺失");
        }
        if (metadataLength <= 0 || metadataLength > MAX_METADATA_BYTES) {
            // 越界的长度绝不用于分配数组；文案与设计第 9 节给出的实现一致。
            throw new TransferFailure("invalid metadata length");
        }
        byte[] metadata = new byte[metadataLength];
        in.readFully(metadata);
        return Metadata.parse(new String(metadata, StandardCharsets.UTF_8));
    }

    /**
     * 先按 Session 与票据核对身份，再逐项核对方向、长度与摘要；全部通过才消费票据。
     */
    private Ticket claim(Metadata metadata) throws TransferFailure {
        UserSession session = SessionManager.getInstance().getSession(metadata.token());
        if (session == null) {
            throw new TransferFailure("登录会话已失效，请重新登录");
        }
        Ticket ticket;
        try {
            ticket = tickets.claim(metadata.ticket(), session, metadata.direction());
        } catch (IllegalArgumentException rejected) {
            throw new TransferFailure(rejected.getMessage());
        }
        if (metadata.size() < 1 || metadata.size() > MAX_PAYLOAD_BYTES) {
            throw new TransferFailure("文件大小超出上限");
        }
        if (metadata.size() != ticket.byteLength()) {
            throw new TransferFailure("文件长度与票据不一致");
        }
        if (metadata.sha256() == null
                || !metadata.sha256().equalsIgnoreCase(ticket.sha256())) {
            throw new TransferFailure("文件摘要与票据不一致");
        }
        return ticket;
    }

    /** 上传：按声明且核对过的长度精确读取，先落 .part，校验通过后改名到票据落点。 */
    private void receive(Ticket ticket, DataInputStream in) throws TransferFailure {
        Path target = ticket.path();
        Path partial = target.resolveSibling(target.getFileName() + PARTIAL_SUFFIX);
        try {
            copyExactly(in, partial, ticket.byteLength());
            if (!TeacherFileTicketService.sha256(partial).equalsIgnoreCase(ticket.sha256())) {
                throw new TransferFailure("文件内容校验失败");
            }
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            TeacherFileTicketService.deleteQuietly(partial);
            if (failure instanceof TransferFailure transfer) {
                throw transfer;
            }
            throw new TransferFailure("写入临时文件失败");
        }
    }

    /** 下载：把服务端自己生成的文件按票据长度整份写出。 */
    private void send(Ticket ticket, DataOutputStream out) throws TransferFailure {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = ticket.byteLength();
        try (InputStream source = Files.newInputStream(ticket.path())) {
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new TransferFailure("待下载文件已不完整");
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (IOException failure) {
            if (failure instanceof TransferFailure transfer) {
                throw transfer;
            }
            throw new TransferFailure("读取待下载文件失败");
        }
    }

    private static void copyExactly(InputStream source, Path target, long length)
            throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = length;
        try (OutputStream sink = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (remaining > 0) {
                int read = source.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    throw new TransferFailure("文件上传在收到全部字节前中断");
                }
                sink.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    private static void respond(DataOutputStream out, boolean ok, String message)
            throws IOException {
        String body = GSON.toJson(Map.of("status", ok ? "OK" : "ERROR",
                "message", message == null ? "" : message));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private static void respondQuietly(DataOutputStream out, String message) {
        if (out == null) {
            return;
        }
        try {
            respond(out, false, message);
        } catch (IOException ignored) {
            // 对端已经断开：回执尽力而为，失败不再升级。
        }
    }

    private record Metadata(String ticket, String token, String direction, long size, String sha256) {

        static Metadata parse(String json) throws IOException {
            try {
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                return new Metadata(text(object, "ticket"), text(object, "token"),
                        text(object, "direction"), object.get("size").getAsLong(),
                        text(object, "sha256"));
            } catch (RuntimeException malformed) {
                throw new TransferFailure("文件元数据无效");
            }
        }

        private static String text(JsonObject object, String field) {
            var value = object.get(field);
            if (value == null || value.isJsonNull() || !value.isJsonPrimitive()
                    || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("元数据缺少 " + field);
            }
            return value.getAsString();
        }
    }

    /** 传输层的预期失败：消息是固定的安全文案，可以直接回给客户端。 */
    private static final class TransferFailure extends IOException {
        private static final long serialVersionUID = 1L;

        TransferFailure(String message) {
            super(message);
        }
    }
}
