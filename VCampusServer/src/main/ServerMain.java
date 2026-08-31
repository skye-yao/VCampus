package main;

import network.Server;

/**
 * 虚拟校园系统服务端启动入口。
 *
 * <p>
 * 负责创建并启动 Socket 服务器。
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class ServerMain {

    /**
     * 服务端程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {

        System.out.println("=================================");
        System.out.println("      VCampus 服务端启动");
        System.out.println("=================================");

        // 创建服务器
        Server server = new Server();

        // 启动服务器
        server.start();
    }
}