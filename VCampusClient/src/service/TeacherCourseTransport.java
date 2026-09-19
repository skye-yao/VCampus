package service;

import java.util.concurrent.CompletableFuture;

import protocol.Message;

/** 教师课程请求的传输抽象；真实实现走现有 TCP 连接，测试用假实现离线驱动。 */
public interface TeacherCourseTransport {
    /** 异步发送教师教务请求并返回响应消息。 */
    CompletableFuture<Message> send(Message request);
}
