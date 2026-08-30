package com.vcampus.server.network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import com.google.gson.Gson;
import com.vcampus.common.protocol.Message;
import com.vcampus.common.protocol.MessageType;
import com.vcampus.common.protocol.MessageCode;

/**
 * 客户端连接处理器
 *
 * <p>每个客户端连接对应一个 ClientHandler 实例，负责处理该连接的所有通信。
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class ClientHandler implements Runnable {

    /** 客户端 Socket */
    private Socket socket;

    /** 消息分发器 */
    private MessageDispatcher dispatcher;

    /** JSON 转换器 */
    private Gson gson;

    /** 当前登录用户 */
    private String currentUser;

    public ClientHandler(Socket socket) {
        this.socket = socket;
        this.dispatcher = new MessageDispatcher();
        this.gson = new Gson();
    }

    @Override
    public void run() {
        System.out.println("客户端已连接: " + socket.getRemoteSocketAddress());

        try (
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                // 1. 解析 JSON 为 Message
                Message request = gson.fromJson(line, Message.class);
                System.out.println("收到请求: " + request);

                // 2. 处理消息
                Message response = processMessage(request);

                // 3. 响应消息写入发送队列
                String jsonResponse = gson.toJson(response);
                writer.println(jsonResponse);
                System.out.println("发送响应: " + response);
            }
        } catch (Exception e) {
            System.out.println("客户端连接异常: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            System.out.println("客户端已断开: " + socket.getRemoteSocketAddress());
        }
    }

    /**
     * 处理消息
     */
    private Message processMessage(Message request) {
        // 检查消息类型
        if (request.getType() != MessageType.REQUEST) {
            Message response = new Message();
            response.setType(MessageType.RESPONSE);
            response.setModule(request.getModule());
            response.setAction(request.getAction());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("不支持的消息类型: " + request.getType());
            return response;
        }

        // 保存当前用户
        if (request.getSender() != null) {
            this.currentUser = request.getSender();
        }

        // 分发到对应的 Handler
        return dispatcher.dispatch(request);
    }

    /**
     * 获取当前登录用户
     */
    public String getCurrentUser() {
        return currentUser;
    }
}