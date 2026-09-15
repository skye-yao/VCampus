package network;

import protocol.Message;
import protocol.MessageType;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import app.ClientMain;
import session.ClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 客户端消息分发器。
 *
 * 根据服务器返回的 MessageType 以及 requestId，
 * 将响应消息通知给对应的异步等待者 CompletableFuture。
 */
public class MessageDispatcher {

    /** 请求 requestId -> 对应的 CompletableFuture */
    private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    /** 注册与关闭操作共用的锁，保证关闭后不会再接受请求 */
    private final Object pendingLock = new Object();

    /** 连接代际关闭后，该分发器不再接受新请求 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 跨连接代际持久的 PUSH 监听注册表 */
    private final PushListenerRegistry pushListeners;

    public MessageDispatcher() {
        this(new PushListenerRegistry());
    }

    public MessageDispatcher(PushListenerRegistry pushListeners) {
        if (pushListeners == null) {
            throw new IllegalArgumentException("Push listener registry is required");
        }
        this.pushListeners = pushListeners;
    }

    /**
     * 注册待接收响应的异步任务
     */
    public boolean registerPendingRequest(String requestKey, CompletableFuture<Message> future) {
        if (requestKey == null || requestKey.isBlank() || future == null) {
            return false;
        }

        synchronized (pendingLock) {
            return !closed.get()
                    && pendingRequests.putIfAbsent(requestKey, future) == null;
        }
    }

    public boolean registerPendingRequest(Long UID, CompletableFuture<Message> future) {
        return registerPendingRequest(keyOf(UID), future);
    }

    /**
     * 仅当 UID 仍映射到同一个异步任务时移除。
     */
    public boolean removePendingRequest(String requestKey, CompletableFuture<Message> future) {
        if (requestKey == null || future == null) {
            return false;
        }

        synchronized (pendingLock) {
            return pendingRequests.remove(requestKey, future);
        }
    }

    public boolean removePendingRequest(Long UID, CompletableFuture<Message> future) {
        return removePendingRequest(keyOf(UID), future);
    }

    /**
     * 仅当 UID 仍映射到指定任务时，让该请求以异常结束。
     */
    public boolean failPending(
            Long UID,
            CompletableFuture<Message> expected,
            Throwable cause) {
        return failPending(keyOf(UID), expected, cause);
    }

    public boolean failPending(
            String requestKey,
            CompletableFuture<Message> expected,
            Throwable cause) {
        if (requestKey == null || expected == null || cause == null) {
            return false;
        }

        boolean removed;
        synchronized (pendingLock) {
            removed = pendingRequests.remove(requestKey, expected);
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
        String requestKey = message.getRequestId();
        CompletableFuture<Message> future = null;
        synchronized (pendingLock) {
            if (requestKey != null && !requestKey.isBlank()) {
                future = pendingRequests.remove(requestKey);
            }

            if (future == null) {
                String uidKey = keyOf(message.getUID());
                if (uidKey != null && !uidKey.equals(requestKey)) {
                    future = pendingRequests.remove(uidKey);
                }
            }
        }

        if (future != null) {
            future.complete(message);
            return;
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
     * 处理服务器主动推送。PUSH 只交给持久注册表，绝不触碰 pending response future。
     */
    private void handlePush(Message message) {
        pushListeners.dispatch(message);
        System.out.println("收到服务器推送: " + message);
        if ("user".equalsIgnoreCase(message.getModule()) && "kickout".equalsIgnoreCase(message.getAction())) {
            Platform.runLater(() -> {
                // 1. 本地安全登出并断开连接
                ClientSession.getInstance().logout();
                SocketClient.getInstance().disconnect();

                // 2. 返回登录页
                ClientMain.switchScene("/resources/fxml/LoginView.fxml");

                // 3. 模态弹窗提示，冻结主窗口
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("下线通知");
                alert.setHeaderText("下线通知");

                Label contentLabel = new Label(message.getMessage());
                contentLabel.setWrapText(true);
                contentLabel.setStyle("-fx-font-size: 13px; -fx-line-spacing: 4px;");
                alert.getDialogPane().setContent(contentLabel);
                alert.getDialogPane().setMinWidth(480);
                alert.getDialogPane().setPrefWidth(500);

                if (ClientMain.getPrimaryStage() != null) {
                    alert.initOwner(ClientMain.getPrimaryStage());
                    alert.initModality(Modality.APPLICATION_MODAL);
                }
                alert.show();
            });
        }
    }

    private static String keyOf(Long UID) {
        return UID == null ? null : UID.toString();
    }
}
