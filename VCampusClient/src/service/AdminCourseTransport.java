package service;

import java.util.concurrent.CompletableFuture;

import protocol.Message;

/** 管理员教务请求使用的底层异步协议传输契约。 */
public interface AdminCourseTransport {
    /** 异步发送管理员教务请求并返回响应消息。 */
    CompletableFuture<Message> send(Message request);
}
