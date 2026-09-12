package service;

import java.util.concurrent.CompletableFuture;

import network.SocketClient;
import protocol.Message;

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
