package service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import protocol.Message;

public interface CourseTransport {
    CompletableFuture<Message> send(Message request);

    CourseSubscription subscribePush(String module, String action, Consumer<Message> listener);

    CourseSubscription subscribeReconnect(Runnable listener);
}
