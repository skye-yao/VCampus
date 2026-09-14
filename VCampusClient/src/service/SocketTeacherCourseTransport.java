package service;

import java.util.concurrent.CompletableFuture;

import network.SocketClient;
import protocol.Message;

/** 复用现有 {@link SocketClient}：sendAsync 负责连接、UID 与 token 的附加。 */
public final class SocketTeacherCourseTransport implements TeacherCourseTransport {
    private final SocketClient client;

    public SocketTeacherCourseTransport() {
        this(SocketClient.getInstance());
    }

    SocketTeacherCourseTransport(SocketClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Message> send(Message request) {
        return client.sendAsync(request);
    }
}
