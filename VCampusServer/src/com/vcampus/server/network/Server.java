package com.vcampus.server.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

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