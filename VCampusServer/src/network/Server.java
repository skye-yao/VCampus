package network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import service.ShopService;
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
    private final java.util.Set<Socket> clientSockets=java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /** 定期关闭超时未支付订单，使用单独守护线程，不阻塞客户端请求。 */
    private final ScheduledExecutorService maintenanceExecutor;
    private final ShopService shopMaintenanceService;

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
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shop-order-expiry");
            thread.setDaemon(true);
            return thread;
        });
        this.shopMaintenanceService = new ShopService();
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

        try {
            new dao.UserDAO().syncAllUsers();
            System.out.println("用户学籍基本资料自动同步完成");
        } catch (Exception e) {
            System.err.println("用户数据同步警告: " + e.getMessage());
        }

        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                lock.ResourceLockManager.getInstance().removeExpired();
                int count = shopMaintenanceService.expireUnpaidOrders();
                if (count > 0) System.out.println("已自动关闭超时未支付订单：" + count + " 笔");
            } catch (Throwable e) {
                System.err.println("清理超时订单失败：" + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                dao.LibrarySchema.ensure();
                new dao.LibraryCirculationDAO().refresh(null);
            } catch (Exception e) {
                System.err.println("图书馆逾期状态更新失败：" + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);

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
                clientSockets.add(clientSocket);
                if(!running){clientSockets.remove(clientSocket);clientSocket.close();break;}

                System.out.println(
                        "收到客户端连接："
                        + clientSocket.getRemoteSocketAddress()
                );

                // 创建客户端连接处理器（共享注册表与分发器）
                ClientHandler clientHandler =
                        new ClientHandler(clientSocket, registry, dispatcher);

                // 交给服务端线程池处理
                try { threadPool.execute(()->{try{clientHandler.run();}finally{clientSockets.remove(clientSocket);}}); }
                catch(java.util.concurrent.RejectedExecutionException busy) { clientSockets.remove(clientSocket);clientSocket.close(); }
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

        for(Socket client:clientSockets){try{client.close();}catch(IOException ignored){}}
        clientSockets.clear();

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
        maintenanceExecutor.shutdownNow();

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
