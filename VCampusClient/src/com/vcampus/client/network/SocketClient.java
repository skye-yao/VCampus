package com.vcampus.client.network;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import com.google.gson.Gson;

import com.vcampus.common.protocol.Message;
import com.vcampus.common.protocol.MessageType;
import com.vcampus.client.session.ClientSession;

/**
 * 客户端 Socket 通信类。
 *
 * 负责与服务器建立长连接以及发送 Message。
 */
public class SocketClient {

    /** 服务器地址 */
    private final String host;

    /** 服务器端口 */
    private final int port;

    /** Socket */
    private Socket socket;

    /** 输出流 */
    private PrintWriter writer;

    /** 输入流 */
    private BufferedReader reader;

    /** JSON 转换器 */
    private final Gson gson;

    /** 消息接收线程 */
    private MessageReceiver receiver;

    /** 消息分发器 */
    private MessageDispatcher dispatcher;

    public SocketClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.gson = new Gson();
    }

    /**
     * 连接服务器。
     */
    public void connect() throws IOException {

        socket = new Socket(host, port);

        writer = new PrintWriter(
                socket.getOutputStream(),
                true
        );

        reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream())
        );

        dispatcher = new MessageDispatcher();

        receiver = new MessageReceiver(
                reader,
                gson,
                dispatcher
        );

        Thread receiverThread = new Thread(
                receiver,
                "MessageReceiver"
        );

        receiverThread.start();

        System.out.println("已连接服务器: " + host + ":" + port);
    }

    /**
     * 发送消息。
     */
    public synchronized void send(Message message) {

        if (writer == null) {
            throw new IllegalStateException("尚未连接服务器");
        }

        // 自动添加当前用户信息
        ClientSession session = ClientSession.getInstance();

        if (session.isLoggedIn()) {
            message.setSender(session.getUsername());
            message.setToken(session.getToken());
        }

        String json = gson.toJson(message);

        writer.println(json);

        System.out.println("发送请求: " + message);
    }

    /**
     * 发送请求消息。
     */
    public void sendRequest(String module, String action) {

        Message request = new Message(
                MessageType.REQUEST,
                module,
                action
        );

        send(request);
    }

    /**
     * 关闭连接。
     */
    public void disconnect() {

        if (receiver != null) {
            receiver.stop();
        }

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

        System.out.println("已断开服务器连接");
    }

    /**
     * 判断当前是否连接服务器。
     */
    public boolean isConnected() {

        return socket != null
                && socket.isConnected()
                && !socket.isClosed();
    }
}