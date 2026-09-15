package service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import network.SocketClient;
import protocol.Message;

public final class SocketCourseTransport implements CourseTransport {
    private final SocketClient client;

    public SocketCourseTransport() {
        this(SocketClient.getInstance());
    }

    SocketCourseTransport(SocketClient client) {
        this.client = client;
    }

    @Override
    public CompletableFuture<Message> send(Message request) {
        return client.sendAsync(request);
    }

    @Override
    public CourseSubscription subscribePush(String module, String action,
            Consumer<Message> listener) {
        return client.subscribePush(module, action, listener);
    }

    @Override
    public CourseSubscription subscribeReconnect(Runnable listener) {
        return client.subscribeReconnect(listener);
    }
}
