package network;

import protocol.Message;
import protocol.MessageType;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.stage.Modality;
import app.ClientMain;
import session.ClientSession;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端消息分发器。
 *
 * 根据服务器返回的 MessageType 以及 requestId，
 * 将响应消息通知给对应的异步等待者 CompletableFuture。
 */
public class MessageDispatcher {

    /** 请求 requestId -> 对应的 CompletableFuture */
    private final Map<String, CompletableFuture<Message>> pendingRequests = new ConcurrentHashMap<>();

    /**
     * 注册待接收响应的异步任务
     */
    public void registerPendingRequest(String UID, CompletableFuture<Message> future) {
        if (UID != null && future != null) {
            pendingRequests.put(UID, future);
        }
    }

    /**
     * 移除超时的异步任务
     */
    public void removePendingRequest(String UID) {
        if (UID != null) {
            pendingRequests.remove(UID);
        }
    }

    /**
     * 连接断开时，让所有等待中的请求以异常结束
     */
    public void failAllPending(Throwable cause) {
        pendingRequests.forEach((id,future) -> { if(pendingRequests.remove(id,future)) future.completeExceptionally(cause); });
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
        String UID = message.getRequestId();
        if (UID != null) {
            CompletableFuture<Message> future = pendingRequests.remove(UID);
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
}
