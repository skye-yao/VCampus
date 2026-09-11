package network;

import com.google.gson.Gson;
import protocol.Message;

import java.io.Closeable;
import java.io.IOException;
import java.io.Writer;

/**
 * 单个客户端连接的串行发送封装。
 *
 * <p>普通响应与 PUSH 共用同一个私有发送锁，保证每条消息被完整写成一行并刷出，
 * 绝不会出现两条消息的 JSON 片段交错。
 */
public final class ClientConnection implements AutoCloseable {

    private static final Gson GSON = new Gson();

    private final Writer writer;
    private final Closeable closeable;
    private final Object sendLock = new Object();

    private volatile boolean closed;

    public ClientConnection(Writer writer, Closeable closeable) {
        if (writer == null || closeable == null) {
            throw new IllegalArgumentException("connection writer and closeable must not be null");
        }
        this.writer = writer;
        this.closeable = closeable;
    }

    public void send(Message message) throws IOException {
        String json = GSON.toJson(message);
        synchronized (sendLock) {
            if (closed) {
                throw new IOException("连接已关闭");
            }
            writer.write(json);
            writer.write("\n");
            writer.flush();
        }
    }

    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        synchronized (sendLock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                writer.flush();
            } catch (IOException ignored) {
                // 连接正在关闭，忽略刷出失败
            }
            if (writer instanceof Closeable closeableWriter && writer != closeable) {
                try {
                    closeableWriter.close();
                } catch (IOException ignored) {
                    // 关闭失败已被整体关闭流程覆盖
                }
            }
            try {
                closeable.close();
            } catch (Exception ignored) {
                // 关闭幂等，重复关闭不应抛出
            }
        }
    }
}
