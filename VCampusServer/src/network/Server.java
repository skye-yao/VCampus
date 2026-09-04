package network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import service.ShopService;

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
 * @version 1.0
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

    /** 定期关闭超时未支付订单，使用单独守护线程，不阻塞客户端请求。 */
    private final ScheduledExecutorService maintenanceExecutor;
    private final ShopService shopMaintenanceService;

    /**
     * 服务端是否正在运行。
     */
    private volatile boolean running;

    /**
     * 使用默认端口创建服务器。
     */
    public Server() {
        this(DEFAULT_PORT);
    }

    /**
     * 根据指定端口创建服务器。
     *
     * @param port 服务端监听端口
     */
    public Server(int port) {
        this.threadPool = ServerThreadPool.getInstance();
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shop-order-expiry");
            thread.setDaemon(true);
            return thread;
        });
        this.shopMaintenanceService = new ShopService();

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
            System.out.println("用户学籍与银行数据自动同步完成");
        } catch (Exception e) {
            System.err.println("用户数据同步警告: " + e.getMessage());
        }

        maintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                int count = shopMaintenanceService.expireUnpaidOrders();
                if (count > 0) System.out.println("已自动关闭超时未支付订单：" + count + " 笔");
            } catch (Throwable e) {
                System.err.println("清理超时订单失败：" + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
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

                // 创建客户端连接处理器
                ClientHandler clientHandler =
                        new ClientHandler(clientSocket);

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
     * 关闭 ServerSocket，并关闭服务端线程池。
     */
    public void stop() {

        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {

            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("关闭服务器 Socket 失败：" + e.getMessage());
            }
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
