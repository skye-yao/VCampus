package network;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import com.google.gson.Gson;
import protocol.Message;
import protocol.MessageType;
import protocol.MessageCode;

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

        PrintWriter writer = null;

        try (
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()))
        ) {
            writer = new PrintWriter(socket.getOutputStream(), true);

            String line;
            while ((line = reader.readLine()) != null) {
                // 1. 解析 JSON 为 Message
                Message request = null;
                try {
                    request = gson.fromJson(line, Message.class);
                } catch (Exception e) {
                    System.out.println("消息解析失败: " + e.getMessage());
                }
                System.out.println("收到请求: " + request);

                // 2. 处理消息。任何异常都封装为错误响应返回，
                //    保证客户端一定收到回复而不是等到连接超时
                Message response;
                if (request == null) {
                    response = new Message(MessageType.RESPONSE, "system", "parse");
                    response.setCode(MessageCode.BAD_REQUEST);
                    response.setMessage("请求消息格式错误");
                } else {
                    try {
                        response = processMessage(request);
                    } catch (Throwable e) {
                        System.out.println("处理请求异常: " + e);
                        response = new Message(MessageType.RESPONSE, request.getModule(), request.getAction());
                        response.setUID(request.getUID());
                        response.setCode(MessageCode.ERROR);
                        response.setMessage("服务端内部错误: " + e.getMessage());
                    }
                }

                // 3. 响应消息写入发送队列
                String jsonResponse = gson.toJson(response);
                writer.println(jsonResponse);
                System.out.println("发送响应: " + response);
            }
        } catch (Throwable e) {
            System.out.println("客户端连接异常: " + e);
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
        if (request.getType() == null || !request.getType().isClientRequest()) {
            Message response = new Message();
            response.setUID(request.getUID());
            response.setType(MessageType.RESPONSE);
            response.setModule(request.getModule());
            response.setAction(request.getAction());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(request.getType() == null
                    ? "客户端与服务端版本不一致，请重新编译并重启服务端"
                    : "不支持的消息类型: " + request.getType());
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
