package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import com.google.gson.Gson;
import protocol.Message;
import protocol.MessageType;
import protocol.MessageCode;
import session.SessionManager;
import session.UserSession;

/**
 * 客户端连接处理器
 *
 * <p>每个客户端连接对应一个 ClientHandler 实例，负责处理该连接的所有通信。
 * 所有普通响应统一经 {@link ClientConnection#send} 写出；认证绑定只信任
 * {@link SessionManager} 中的有效会话，并在连接断开始或登出时精确解绑。
 *
 * @author VirtualCampus 架构组
 * @version 2.0
 */
public class ClientHandler implements Runnable {

    /** 客户端 Socket */
    private final Socket socket;

    /** 在线连接注册表（服务器共享） */
    private final OnlineConnectionRegistry registry;

    /** 消息分发器（服务器共享） */
    private final MessageDispatcher dispatcher;

    /** JSON 转换器 */
    private final Gson gson = new Gson();

    public ClientHandler(Socket socket, OnlineConnectionRegistry registry,
                         MessageDispatcher dispatcher) {
        this.socket = socket;
        this.registry = registry;
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {
        System.out.println("客户端已连接: " + socket.getRemoteSocketAddress());

        String boundUid = null;
        ClientConnection connection = null;

        try {
            connection = new ClientConnection(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), socket);

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Message request = null;
                    try {
                        request = gson.fromJson(line, Message.class);
                    } catch (Exception e) {
                        System.out.println("消息解析失败: " + e.getMessage());
                    }
                    System.out.println("收到请求: " + request);

                    Message response = respond(request);
                    connection.send(response);
                    System.out.println("发送响应: " + response);

                    boundUid = rebind(connection, boundUid, resolveUid(request, response));
                }
            }
        } catch (Throwable e) {
            System.out.println("客户端连接异常: " + e);
        } finally {
            if (boundUid != null) {
                registry.unbind(boundUid, connection);
            }
            if (connection != null) {
                connection.close();
            }
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.println("客户端已断开: " + socket.getRemoteSocketAddress());
        }
    }

    /**
     * 处理一条请求并返回响应；解析失败与处理异常都转换为错误响应。
     */
    private Message respond(Message request) {
        if (request == null) {
            Message response = new Message(MessageType.RESPONSE, "system", "parse");
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("请求消息格式错误");
            return response;
        }

        if (request.getType() != MessageType.REQUEST) {
            Message response = new Message(MessageType.RESPONSE,
                    request.getModule(), request.getAction());
            response.setUID(request.getUID());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("不支持的消息类型: " + request.getType());
            return response;
        }

        try {
            return dispatcher.dispatch(request);
        } catch (Throwable e) {
            System.out.println("处理请求异常: " + e);
            Message response = new Message(MessageType.RESPONSE,
                    request.getModule(), request.getAction());
            response.setUID(request.getUID());
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误: " + e.getMessage());
            return response;
        }
    }

    /**
     * 依据有效会话解析本连接应当绑定的 UID：登录响应 token 优先，其次请求 token。
     * 绝不信任 request.sender 或 data 中的 UID。
     */
    private String resolveUid(Message request, Message response) {
        if (response != null && response.getToken() != null) {
            UserSession session = SessionManager.getInstance().getSession(response.getToken());
            if (session != null) {
                return session.getUsername();
            }
        }
        if (request != null && request.getToken() != null) {
            UserSession session = SessionManager.getInstance().getSession(request.getToken());
            if (session != null) {
                return session.getUsername();
            }
        }
        return null;
    }

    /**
     * 精确解绑旧 UID 并绑定新 UID；同一 socket 换账号时不会残留旧绑定。
     */
    private String rebind(ClientConnection connection, String boundUid, String desiredUid) {
        if (Objects.equals(boundUid, desiredUid)) {
            return boundUid;
        }
        if (boundUid != null) {
            registry.unbind(boundUid, connection);
        }
        if (desiredUid != null) {
            registry.bind(desiredUid, connection);
        }
        return desiredUid;
    }
}
