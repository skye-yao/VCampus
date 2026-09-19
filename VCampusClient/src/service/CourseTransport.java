package service;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import protocol.Message;

/**
 * 教务服务使用的底层异步消息通道。
 *
 * <p>该接口只传递协议 {@link Message}；业务服务负责 DTO/View 转换，控制器不应直接依赖它。
 */
public interface CourseTransport {
    /** 异步发送一条教务请求并取得对应响应。 */
    CompletableFuture<Message> send(Message request);

    /** 订阅指定模块与动作的服务端推送。 */
    CourseSubscription subscribePush(String module, String action, Consumer<Message> listener);

    /** 订阅重连完成通知，以便上层重新拉取权威状态。 */
    CourseSubscription subscribeReconnect(Runnable listener);
}
