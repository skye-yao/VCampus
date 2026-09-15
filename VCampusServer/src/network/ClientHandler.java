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
import protocol.MessageCode;
import protocol.MessageType;
import session.SessionManager;
import session.UserSession;

/** 处理单个客户端连接，并把响应与推送统一交给串行写连接。 */
public class ClientHandler implements Runnable {

    /** 1 MiB 图片经 Base64/JSON 编码约 1.4 MiB，额外预留请求字段空间。 */
    static final int MAX_REQUEST_LINE_CHARS = 2_000_000;

    /** 登录创建会话时绑定当前连接，供防重登推送使用。 */
    public static final ThreadLocal<ClientHandler> CURRENT_HANDLER = new ThreadLocal<>();

    private final Socket socket;
    private final OnlineConnectionRegistry registry;
    private final MessageDispatcher dispatcher;
    private final Gson gson = util.JsonUtil.createGson();
    private volatile ClientConnection connection;

    public ClientHandler(Socket socket, OnlineConnectionRegistry registry,
                         MessageDispatcher dispatcher) {
        this.socket = Objects.requireNonNull(socket, "socket");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    static String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') break;
            if (next == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') reader.reset();
                break;
            }
            if (line.length() >= maxChars) throw new IOException("请求消息超过长度限制");
            line.append((char) next);
        }
        return next == -1 && line.isEmpty() ? null : line.toString();
    }

    @Override
    public void run() {
        CURRENT_HANDLER.set(this);
        String boundUid = null;
        System.out.println("客户端已连接: " + socket.getRemoteSocketAddress());

        try {
            ClientConnection activeConnection = new ClientConnection(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), socket);
            connection = activeConnection;

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(reader, MAX_REQUEST_LINE_CHARS)) != null) {
                    Message request = parse(line);
                    System.out.println("收到请求: " + request);

                    Message response = correlate(request, respond(request));
                    activeConnection.send(response);
                    System.out.println("发送响应: " + response);

                    boundUid = rebind(activeConnection, boundUid, resolveUid(request, response));
                }
            }
        } catch (Throwable failure) {
            System.out.println("客户端连接异常: " + failure);
        } finally {
            CURRENT_HANDLER.remove();
            SessionManager.getInstance().unbindHandler(this);
            ClientConnection activeConnection = connection;
            if (boundUid != null && activeConnection != null) {
                registry.unbind(boundUid, activeConnection);
            }
            close();
            connection = null;
            System.out.println("客户端已断开: " + socket.getRemoteSocketAddress());
        }
    }

    private Message parse(String line) {
        try {
            return gson.fromJson(line, Message.class);
        } catch (RuntimeException failure) {
            System.out.println("消息解析失败: " + failure.getMessage());
            return null;
        }
    }

    /** 解析失败与处理异常都转换为错误响应。 */
    private Message respond(Message request) {
        if (request == null) {
            Message response = new Message(MessageType.RESPONSE, "system", "parse");
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("请求消息格式错误");
            return response;
        }

        if (request.getType() == null || !request.getType().isClientRequest()) {
            Message response = new Message(MessageType.RESPONSE,
                    request.getModule(), request.getAction());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(request.getType() == null
                    ? "客户端与服务端版本不一致，请重新编译并重启服务端"
                    : "不支持的消息类型: " + request.getType());
            return response;
        }

        try {
            return dispatcher.dispatch(request);
        } catch (Throwable failure) {
            System.out.println("处理请求异常: " + failure);
            Message response = new Message(MessageType.RESPONSE,
                    request.getModule(), request.getAction());
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端内部错误: " + failure.getMessage());
            return response;
        }
    }

    private static Message correlate(Message request, Message response) {
        if (response == null) {
            response = new Message(MessageType.RESPONSE,
                    request == null ? "system" : request.getModule(),
                    request == null ? "unknown" : request.getAction());
            response.setCode(MessageCode.ERROR);
            response.setMessage("服务端未生成响应");
        }
        if (request != null) {
            response.setUID(request.getUID());
            response.setRequestId(request.getRequestId());
        }
        return response;
    }

    /** 登录响应 token 优先，其次请求 token；绝不信任客户端 sender。 */
    private static String resolveUid(Message request, Message response) {
        if (response != null && response.getToken() != null) {
            UserSession session = SessionManager.getInstance().getSession(response.getToken());
            if (session != null) return session.getUsername();
        }
        if (request != null && request.getToken() != null) {
            UserSession session = SessionManager.getInstance().getSession(request.getToken());
            if (session != null) return session.getUsername();
        }
        return null;
    }

    private String rebind(ClientConnection activeConnection, String boundUid, String desiredUid) {
        if (Objects.equals(boundUid, desiredUid)) return boundUid;
        if (boundUid != null) registry.unbind(boundUid, activeConnection);
        if (desiredUid != null) registry.bind(desiredUid, activeConnection);
        return desiredUid;
    }

    /** 防重登等服务端主动推送与普通响应共用 ClientConnection 写锁。 */
    public void sendMessage(Message message) {
        ClientConnection activeConnection = connection;
        if (activeConnection == null) return;
        try {
            activeConnection.send(message);
            System.out.println("已向客户端发送推送消息: " + message);
        } catch (Exception failure) {
            System.err.println("发送推送消息异常: " + failure.getMessage());
        }
    }

    public void close() {
        ClientConnection activeConnection = connection;
        if (activeConnection != null) {
            activeConnection.close();
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭幂等。
        }
    }
}
