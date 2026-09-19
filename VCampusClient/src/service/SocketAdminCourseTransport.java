package service;

import java.util.concurrent.CompletableFuture;

import network.SocketClient;
import protocol.Message;

/** 基于共享 SocketClient 的管理员教务协议传输实现。 */
public final class SocketAdminCourseTransport implements AdminCourseTransport {
    private final SocketClient client;

    public SocketAdminCourseTransport() {
        this(SocketClient.getInstance());
    }

    SocketAdminCourseTransport(SocketClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Message> send(Message request) {
        return client.sendAsync(request);
    }
}
