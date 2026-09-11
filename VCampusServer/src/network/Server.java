package network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import service.CourseEventDispatcher;
import service.CourseWaitlistScheduler;

/**
 * VCampus 服务端 Socket 服务器。
 *
 * <p>
 * 负责创建 ServerSocket、监听客户端连接，
 * 并将每个客户端连接交给 ServerThreadPool
 * 进行处理。
 *
 * <p>
 * 每个客户端连接对应一个 ClientHandler，
 * ClientHandler 负责该客户端后续的消息通信。
 *
 * @author VirtualCampus 架构组
 * @version 2.0
 */
public class Server {

    /**
     * 默认服务器端口。
     */
    private static final int DEFAULT_PORT = 8888;

    /**
     * 服务端 Socket。
     */
    private ServerSocket serverSocket;

    /**
     * 服务端线程池。
     */
    private final ServerThreadPool threadPool;

    /** 在线连接注册表（服务器共享）。 */
    private final OnlineConnectionRegistry registry;

    /** 消息分发器（服务器共享）。 */
    private final MessageDispatcher dispatcher;

    /** 课程事件投递器（服务器共享，可为 null）。 */
    private final CourseEventDispatcher eventDispatcher;

    /** 候补调度器（服务器共享，可为 null）。 */
    private final CourseWaitlistScheduler waitlistScheduler;

    /**
     * 服务端是否正在运行。
     */
    private volatile boolean running;

    /** 是否已经停止，保证关闭幂等。 */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /**
     * 使用默认端口与共享依赖创建服务器。
     */
    public Server(OnlineConnectionRegistry registry, MessageDispatcher dispatcher,
                  CourseEventDispatcher eventDispatcher,
                  CourseWaitlistScheduler waitlistScheduler) {
        this(DEFAULT_PORT, registry, dispatcher, eventDispatcher, waitlistScheduler);
    }

    /**
     * 根据指定端口与共享依赖创建服务器。
     *
     * @param port 服务端监听端口
     */
    public Server(int port, OnlineConnectionRegistry registry, MessageDispatcher dispatcher,
                  CourseEventDispatcher eventDispatcher,
                  CourseWaitlistScheduler waitlistScheduler) {
        this.threadPool = ServerThreadPool.getInstance();
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.eventDispatcher = eventDispatcher;
        this.waitlistScheduler = waitlistScheduler;

        try {
            this.serverSocket = new ServerSocket(port);
        } catch (IOException e) {
            throw new RuntimeException("创建服务器 Socket 失败，端口：" + port, e);
        }
    }

    /**
     * 启动服务器。
     *
     * <p>
     * 持续监听客户端连接。
     * 每当有新的客户端连接时，
     * 就将 ClientHandler 提交给线程池处理。
     */
    public void start() {

        running = true;

        if (eventDispatcher != null) {
            eventDispatcher.start();
        }
        if (waitlistScheduler != null) {
            waitlistScheduler.start();
        }

        System.out.println("=================================");
        System.out.println("VCampus Server 启动成功");
        System.out.println("服务器端口：" + serverSocket.getLocalPort());
        System.out.println("等待客户端连接...");
        System.out.println("=================================");

        try {

            while (running) {

                // 等待客户端连接
                Socket clientSocket = serverSocket.accept();

                System.out.println(
                        "收到客户端连接："
                        + clientSocket.getRemoteSocketAddress()
                );

                // 创建客户端连接处理器（共享注册表与分发器）
                ClientHandler clientHandler =
                        new ClientHandler(clientSocket, registry, dispatcher);

                // 交给服务端线程池处理
                threadPool.execute(clientHandler);
            }

        } catch (IOException e) {

            if (running) {
                System.err.println("服务器运行异常：" + e.getMessage());
            }

        } finally {

            stop();
        }
    }

    /**
     * 停止服务器。
     *
     * <p>
     * 幂等地关闭 ServerSocket、在线连接注册表、课程事件投递器、候补调度器与线程池。
     */
    public void stop() {

        if (!stopped.compareAndSet(false, true)) {
            return;
        }

        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {

            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("关闭服务器 Socket 失败：" + e.getMessage());
            }
        }

        if (eventDispatcher != null) {
            eventDispatcher.close();
        }
        if (waitlistScheduler != null) {
            waitlistScheduler.close();
        }
        if (registry != null) {
            registry.close();
        }

        threadPool.shutdown();

        System.out.println("VCampus Server 已停止。");
    }

    /**
     * 获取服务器监听端口。
     *
     * @return 服务器端口
     */
    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /**
     * 判断服务器是否正在运行。
     *
     * @return true 表示正在运行
     */
    public boolean isRunning() {
        return running;
    }
}