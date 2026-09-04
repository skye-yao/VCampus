package network;

import protocol.Message;
import protocol.MessageType;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端消息分发器。
 *
 * 根据服务器返回的 MessageType 以及 uid，
 * 将响应消息通知给对应的异步等待者 CompletableFuture。
 */
public class MessageDispatcher {

    /** 请求 uid -> 对应的 CompletableFuture */
    private final Map<Long, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 注册待接收响应的异步任务
     */
    public void registerPendingRequest(Long uid, CompletableFuture<Message> future) {
        if (uid != null && future != null) {
            pendingRequests.put(uid, future);
        }
    }

    /**
     * 移除超时的异步任务
     */
    public void removePendingRequest(Long uid) {
        if (uid != null) {
            pendingRequests.remove(uid);
        }
    }

    /**
     * 连接断开时，让所有等待中的请求以异常结束
     */
    public void failAllPending(Throwable cause) {
        pendingRequests.values().forEach(future -> future.completeExceptionally(cause));
        pendingRequests.clear();
    }

    /**
     * 分发服务器消息。
     */
    public void dispatch(Message message) {
        if (message == null) {
            return;
        }

        MessageType type = message.getType();

        if (type == MessageType.RESPONSE) {
            handleResponse(message);
        } else if (type == MessageType.PUSH) {
            handlePush(message);
        } else {
            System.out.println("收到无法处理的消息类型: " + type);
        }
    }

    /**
     * 处理普通响应。
     */
    private void handleResponse(Message message) {
        Long uid = message.getUID();
        if (uid != null) {
            CompletableFuture<Message> future = pendingRequests.remove(uid);
            if (future != null) {
                future.complete(message);
                return;
            }
        }

        System.out.println(
                "收到未匹配到等待者的服务器响应: " +
                "module=" + message.getModule() +
                ", action=" + message.getAction() +
                ", code=" + message.getCode() +
                ", message=" + message.getMessage()
        );
    }

    /**
     * 处理服务器主动推送。
     */
    private void handlePush(Message message) {
        System.out.println("收到服务器推送: " + message);
    }
}
