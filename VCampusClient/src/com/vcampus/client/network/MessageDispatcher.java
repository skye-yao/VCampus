package com.vcampus.client.network;

import com.vcampus.common.protocol.Message;
import com.vcampus.common.protocol.MessageType;

/**
 * 客户端消息分发器。
 *
 * 根据服务器返回的 MessageType，
 * 将消息分发给对应的处理逻辑。
 */
public class MessageDispatcher {

    /**
     * 分发服务器消息。
     */
    public void dispatch(Message message) {

        if (message == null) {
            return;
        }

        MessageType type = message.getType();

        if (type == MessageType.RESPONSE) {

            handleResponse(message);

        } else if (type == MessageType.PUSH) {

            handlePush(message);

        } else {

            System.out.println(
                    "收到无法处理的消息类型: " + type
            );
        }
    }

    /**
     * 处理普通响应。
     */
    private void handleResponse(Message message) {

        System.out.println(
                "收到服务器响应: " +
                "module=" + message.getModule() +
                ", action=" + message.getAction() +
                ", code=" + message.getCode() +
                ", message=" + message.getMessage()
        );
    }

    /**
     * 处理服务器主动推送。
     */
    private void handlePush(Message message) {

        System.out.println(
                "收到服务器推送: " + message
        );
    }
}