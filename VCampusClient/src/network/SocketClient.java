package network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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

    /** 单个异步请求的默认超时时间 */
    private static final long REQUEST_TIMEOUT_SECONDS = 10;

    private static final ScheduledExecutorService TIMEOUT_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "SocketRequestTimeout");
                thread.setDaemon(true);
                return thread;
            });

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

    /** 当前连接代际对应的消息分发器 */
    private MessageDispatcher dispatcher;

    /** 防止多个线程写出的 JSON 行互相穿插 */
    private final Object sendLock = new Object();

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

        MessageDispatcher previousDispatcher = dispatcher;

        // 旧连接已失效（对端关闭或接收线程已结束），清理后重连
        if (receiver != null) {
            receiver.stop();
        }
        closeSocketQuietly();

        if (previousDispatcher != null) {
            previousDispatcher.failAllPending(new IOException("与服务器的连接已断开"));
        }

        socket = new Socket(host, port);
        writer = new PrintWriter(socket.getOutputStream(), true);
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        MessageDispatcher connectionDispatcher = new MessageDispatcher();
        dispatcher = connectionDispatcher;
        receiver = new MessageReceiver(reader, gson, connectionDispatcher);
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
        MessageDispatcher requestDispatcher = null;
        Long requestUID = null;
        boolean registered = false;

        try {
            synchronized (this) {
                if (!isConnected() || receiver == null || !receiver.isRunning()) {
                    connect();
                }

                if (request.getUID() == null) {
                    request.setUID(Message.nextUID());
                }

                // 附加 Session 认证信息
                ClientSession session = ClientSession.getInstance();
                if (session.isLoggedIn()) {
                    request.setSender(session.getUsername());
                    request.setToken(session.getToken());
                }

                requestUID = request.getUID();
                requestDispatcher = dispatcher;
                registered = requestDispatcher.registerPendingRequest(requestUID, future);
                if (!registered) {
                    throw new IllegalStateException("请求 UID 已在等待响应: " + requestUID);
                }

                MessageDispatcher timeoutDispatcher = requestDispatcher;
                Long timeoutUID = requestUID;
                ScheduledFuture<?> timeout = TIMEOUT_EXECUTOR.schedule(
                        () -> timeoutDispatcher.failPending(
                                timeoutUID,
                                new TimeoutException("请求超时: " + timeoutUID)),
                        REQUEST_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);
                future.whenComplete((response, error) -> timeout.cancel(false));

                // 序列化并发送
                String json = gson.toJson(request);
                synchronized (sendLock) {
                    writer.println(json);

                    // PrintWriter 不会抛 IOException，需主动检查发送是否失败
                    if (writer.checkError()) {
                        throw new IOException("消息发送失败，连接已断开");
                    }
                }
            }
        } catch (Exception e) {
            if (registered) {
                requestDispatcher.failPending(requestUID, e);
            } else {
                future.completeExceptionally(e);
            }
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
