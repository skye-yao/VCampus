package network;

import protocol.Message;
import protocol.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端消息分发器。
 *
 * 根据服务器返回的 MessageType 以及 UID，
 * 将响应消息通知给对应的异步等待者 CompletableFuture。
 */
public class MessageDispatcher {

    /** 请求 UID -> 对应的 CompletableFuture */
    private final Map<Long, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    /** 注册与关闭操作共用的锁，保证关闭后不会再接受请求 */
    private final Object pendingLock = new Object();

    /** 连接代际关闭后，该分发器不再接受新请求 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 注册待接收响应的异步任务
     */
    public boolean registerPendingRequest(Long UID, CompletableFuture<Message> future) {
        if (UID == null || future == null) {
            return false;
        }

        synchronized (pendingLock) {
            return !closed.get()
                    && pendingRequests.putIfAbsent(UID, future) == null;
        }
    }

    /**
     * 仅当 UID 仍映射到同一个异步任务时移除。
     */
    public boolean removePendingRequest(Long UID, CompletableFuture<Message> future) {
        if (UID == null || future == null) {
            return false;
        }

        synchronized (pendingLock) {
            return pendingRequests.remove(UID, future);
        }
    }

    /**
     * 仅当 UID 仍映射到指定任务时，让该请求以异常结束。
     */
    public boolean failPending(
            Long UID,
            CompletableFuture<Message> expected,
            Throwable cause) {
        if (UID == null || expected == null || cause == null) {
            return false;
        }

        boolean removed;
        synchronized (pendingLock) {
            removed = pendingRequests.remove(UID, expected);
        }

        if (removed) {
            expected.completeExceptionally(cause);
        }
        return removed;
    }

    /**
     * 连接断开时，让所有等待中的请求以异常结束
     */
    public void failAllPending(Throwable cause) {
        List<CompletableFuture<Message>> futures;
        synchronized (pendingLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }

            futures = new ArrayList<>(pendingRequests.values());
            pendingRequests.clear();
        }

        futures.forEach(future -> future.completeExceptionally(cause));
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
        Long UID = message.getUID();
        if (UID != null) {
            CompletableFuture<Message> future;
            synchronized (pendingLock) {
                future = pendingRequests.remove(UID);
            }
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
