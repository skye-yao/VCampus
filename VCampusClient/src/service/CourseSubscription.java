package service;

public interface CourseSubscription extends AutoCloseable {
    @Override
    void close();
}
