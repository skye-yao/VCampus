package network;

import java.io.BufferedReader;
import java.io.IOException;

import com.google.gson.Gson;

import protocol.Message;

/**
 * 客户端消息接收线程。
 *
 * 持续读取服务器通过 Socket 发送的消息，
 * 将 JSON 转换为 Message 后交给 MessageDispatcher。
 */
public class MessageReceiver implements Runnable {

    /** Socket 输入流 */
    private final BufferedReader reader;

    /** JSON 转换器 */
    private final Gson gson;

    /** 消息分发器 */
    private final MessageDispatcher dispatcher;

    /** 是否继续运行 */
    private volatile boolean running = true;

    public MessageReceiver(
            BufferedReader reader,
            Gson gson,
            MessageDispatcher dispatcher) {

        this.reader = reader;
        this.gson = gson;
        this.dispatcher = dispatcher;
    }

    @Override
    public void run() {

        System.out.println("消息接收线程已启动");

        try {

            String line;

            while (running && (line = reader.readLine()) != null) {

                try {

                    Message message =
                            gson.fromJson(line, Message.class);

                    System.out.println(
                            "收到服务器消息: " + message
                    );

                    dispatcher.dispatch(message);

                } catch (Exception e) {

                    System.out.println(
                            "消息解析失败: " + e.getMessage()
                    );
                }
            }

        } catch (IOException e) {

            if (running) {
                System.out.println(
                        "接收服务器消息失败: " + e.getMessage()
                );
            }

        } finally {

            running = false;

            // 连接断开后，未收到响应的请求全部以异常结束，
            // 避免界面永远停留在“正在登录...”等无响应状态
            dispatcher.failAllPending(new IOException("与服务器的连接已断开"));

            System.out.println("消息接收线程已结束");
        }
    }

    /**
     * 判断接收线程是否仍在运行。
     */
    public boolean isRunning() {

        return running;
    }

    /**
     * 停止消息接收线程。
     */
    public void stop() {

        running = false;
    }
}