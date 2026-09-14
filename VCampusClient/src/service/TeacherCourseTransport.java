package service;

import java.util.concurrent.CompletableFuture;

import protocol.Message;

/** 教师课程请求的传输抽象；真实实现走现有 TCP 连接，测试用假实现离线驱动。 */
public interface TeacherCourseTransport {
    CompletableFuture<Message> send(Message request);
}
