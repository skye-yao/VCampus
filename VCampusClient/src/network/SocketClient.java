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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.Gson;

import protocol.Message;
import protocol.MessageType;
import service.CourseSubscription;
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

    /** 跨连接代际持久的 PUSH 与重连监听注册表 */
    private final PushListenerRegistry pushListeners = new PushListenerRegistry();

    /** 连接代际令牌：每次真正建立连接时递增 */
    private final AtomicLong generationCounter = new AtomicLong();

    /** 重连通知声明与代际校验共用的锁 */
    private final Object reconnectLock = new Object();

    /** 已通知过的代际，保证每个有效代际至多通知一次 */
    private long notifiedGeneration;

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
    public void connect() throws IOException {
        long generation;
        synchronized (this) {
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

            MessageDispatcher connectionDispatcher = new MessageDispatcher(pushListeners);
            dispatcher = connectionDispatcher;
            receiver = new MessageReceiver(reader, gson, connectionDispatcher);
            Thread receiverThread = new Thread(receiver, "MessageReceiver");
            receiverThread.setDaemon(true);
            receiverThread.start();

            // 代际令牌按连接建立顺序分配；pending future 不跨代际（旧 dispatcher 已 failAllPending）
            generation = generationCounter.incrementAndGet();

            System.out.println("已连接服务器: " + host + ":" + port);
        }

        // 只有该代际仍是当前代际且尚未通知过才触发；冗余、失败或被更新代际取代的 connect 不通知
        fireReconnectIfCurrent(generation);
    }

    /**
     * 当前连接代际令牌；从未连接过为 0。
     */
    long connectionGeneration() {
        return generationCounter.get();
    }

    /**
     * 仅当给定代际仍是最新代际且未通知过时，才通知一次重连监听。
     */
    void fireReconnectIfCurrent(long generation) {
        synchronized (reconnectLock) {
            if (generationCounter.get() != generation || notifiedGeneration == generation) {
                return;
            }
            notifiedGeneration = generation;
        }
        pushListeners.fireReconnect();
    }

    /**
     * 注册持久课程推送监听，重新连接后仍然有效。
     */
    public CourseSubscription subscribePush(String module, String action,
            Consumer<Message> listener) {
        PushListenerRegistry.Subscription subscription =
                pushListeners.registerPush(module, action, listener);
        return subscription::cancel;
    }

    /**
     * 注册持久重连监听，只在成功建立新连接代际后触发。
     */
    public CourseSubscription subscribeReconnect(Runnable listener) {
        PushListenerRegistry.Subscription subscription =
                pushListeners.registerReconnect(listener);
        return subscription::cancel;
    }

    /**
     * 异步发送请求并返回 CompletableFuture
     */
    public CompletableFuture<Message> sendAsync(Message request) {
        return sendAsync(request, REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * 异步发送请求，并按调用方指定的时间清理未完成请求。
     */
    public CompletableFuture<Message> sendAsync(
            Message request,
            long timeoutDuration,
            TimeUnit timeoutUnit) {
        CompletableFuture<Message> future = new CompletableFuture<>();
        MessageDispatcher requestDispatcher = null;
        PrintWriter requestWriter = null;
        Long requestUID = null;
        boolean registered = false;

        try {
            if (request.getUID() == null) {
                request.setUID(Message.nextUID());
            }

            // 附加 Session 认证信息
            ClientSession session = ClientSession.getInstance();
            if (session.isLoggedIn()) {
                request.setSender(session.getUsername());
                request.setToken(session.getToken());
            }

            synchronized (this) {
                if (!isConnected() || receiver == null || !receiver.isRunning()) {
                    connect();
                }

                requestDispatcher = dispatcher;
                requestWriter = writer;
            }

            requestUID = request.getUID();
            registered = requestDispatcher.registerPendingRequest(requestUID, future);
            if (!registered) {
                throw new IllegalStateException("请求 UID 已在等待响应: " + requestUID);
            }

            MessageDispatcher timeoutDispatcher = requestDispatcher;
            Long timeoutUID = requestUID;
            ScheduledFuture<?> timeoutTask = TIMEOUT_EXECUTOR.schedule(
                    () -> timeoutDispatcher.failPending(
                            timeoutUID,
                            future,
                            new TimeoutException("请求超时: " + timeoutUID)),
                    timeoutDuration,
                    timeoutUnit);
            future.whenComplete((response, error) -> {
                timeoutTask.cancel(false);
                timeoutDispatcher.removePendingRequest(timeoutUID, future);
            });

            // 序列化并发送
            String json = gson.toJson(request);
            synchronized (sendLock) {
                requestWriter.println(json);

                // PrintWriter 不会抛 IOException，需主动检查发送是否失败
                if (requestWriter.checkError()) {
                    throw new IOException("消息发送失败，连接已断开");
                }
            }
        } catch (Exception e) {
            if (registered) {
                requestDispatcher.failPending(requestUID, future, e);
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
        return sendAsync(request, timeoutSeconds, TimeUnit.SECONDS).get();
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
