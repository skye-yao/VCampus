package service;

import java.util.concurrent.CompletableFuture;

import protocol.Message;

public interface AdminCourseTransport {
    CompletableFuture<Message> send(Message request);
}
