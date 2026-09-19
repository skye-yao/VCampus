package service;

/** 可关闭的课程事件订阅句柄。 */
public interface CourseSubscription extends AutoCloseable {
    /** 解除订阅并释放关联的监听器。 */
    @Override
    void close();
}
