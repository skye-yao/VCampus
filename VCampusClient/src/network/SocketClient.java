package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;

import protocol.Message;
import protocol.MessageType;
import session.ClientSession;

/**
 * 客户端 Socket 通信管理类（单例模式）。
 *
 * 负责与服务器建立长连接、发送请求与异步/同步等待响应。
 */
public class SocketClient {

    private static final SocketClient INSTANCE = new SocketClient();

    /** 默认服务器地址 */
    private static final String DEFAULT_HOST = "127.0.0.1";

    /** 默认服务器端口 */
    private static final int DEFAULT_PORT = 8888;

    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;

    /** Socket */
    private Socket socket;

    /** 输出流 */
    private PrintWriter writer;

    /** 输入流 */
    private BufferedReader reader;

    /** JSON 转换器 */
    private final Gson gson = new Gson();

    /** 消息接收线程 */
    private MessageReceiver receiver;

    /** 消息分发器 */
    private final MessageDispatcher dispatcher = new MessageDispatcher();

    private SocketClient() {
    }

    public static SocketClient getInstance() {
        return INSTANCE;
    }

    public void init(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * 连接服务器。若连接有效则直接返回；若旧连接已失效则重连。
     */
    public synchronized void connect() throws IOException {
        if (isConnected() && receiver != null && receiver.isRunning()) {
            return;
        }

        // 旧连接已失效（对端关闭或接收线程已结束），清理后重连
        if (receiver != null) {
            receiver.stop();
        }
        closeSocketQuietly();

        socket = new Socket(host, port);
        writer = new PrintWriter(socket.getOutputStream(), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        receiver = new MessageReceiver(reader, gson, dispatcher);
        Thread receiverThread = new Thread(receiver, "MessageReceiver");
        receiverThread.setDaemon(true);
        receiverThread.start();

        System.out.println("已连接服务器: " + host + ":" + port);
    }

    /**
     * 异步发送请求并返回 CompletableFuture
     */
    public CompletableFuture<Message> sendAsync(Message request) {
        CompletableFuture<Message> future = new CompletableFuture<>();

        try {
            if (!isConnected() || receiver == null || !receiver.isRunning()) {
                connect();
            }

            if (request.getUID() == null) {
                request.setUID(System.currentTimeMillis());
            }

            // 附加 Session 认证信息
            ClientSession session = ClientSession.getInstance();
            if (session.isLoggedIn()) {
                request.setSender(session.getUsername());
                request.setToken(session.getToken());
            }

            // 注册等待
            dispatcher.registerPendingRequest(request.getUID(), future);

            // 序列化并发送
            String json = gson.toJson(request);
            writer.println(json);

            // PrintWriter 不会抛 IOException，需主动检查发送是否失败
            if (writer.checkError()) {
                throw new IOException("消息发送失败，连接已断开");
            }

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    /**
     * 同步发送请求并阻塞等待响应（带超时）
     */
    public Message sendSync(Message request, long timeoutSeconds) throws Exception {
        return sendAsync(request).get(timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * 关闭连接。
     */
    public synchronized void disconnect() {
        if (receiver != null) {
            receiver.stop();
        }

        closeSocketQuietly();

        System.out.println("已断开服务器连接");
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        socket = null;
        writer = null;
        reader = null;
    }

    /**
     * 判断当前是否连接服务器。
     */
    public boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }
}
