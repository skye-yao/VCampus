package network;

import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;

import handler.UserHandler;
import handler.LibraryHandler;

/**
 * 服务端消息分发器
 *
 * <p>根据消息的 module 字段，
 * 将请求分发到对应的 Handler 处理。
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class MessageDispatcher {

    // 用户模块 Handler
    private final UserHandler userHandler;

    // 图书馆模块 Handler
    private final LibraryHandler libraryHandler;

    public MessageDispatcher() {

        this.userHandler =
                new UserHandler();

        this.libraryHandler =
                new LibraryHandler();
    }

    /**
     * 分发消息到对应的 Handler
     *
     * @param request 请求消息
     * @return 响应消息
     */
    public Message dispatch(Message request) {

        if (request == null) {

            Message response =
                    new Message(
                            MessageType.RESPONSE,
                            "system",
                            "unknown"
                    );

            response.setCode(
                    MessageCode.BAD_REQUEST
            );

            response.setMessage(
                    "请求不能为空"
            );

            return response;
        }

        String module =
                request.getModule();

        if (module == null
                || module.isBlank()) {

            Message response =
                    new Message(
                            MessageType.RESPONSE,
                            "system",
                            request.getAction()
                    );

            response.setUID(
                    request.getUID()
            );

            response.setCode(
                    MessageCode.BAD_REQUEST
            );

            response.setMessage(
                    "业务模块不能为空"
            );

            return response;
        }

        // =============================
        // 根据 module 分发
        // =============================

        if ("user".equalsIgnoreCase(module)) {

            return userHandler.handle(request);

        } else if (
                "library".equalsIgnoreCase(module)
        ) {

            return libraryHandler.handle(request);

        } else {

            Message response =
                    new Message(
                            MessageType.RESPONSE,
                            module,
                            request.getAction()
                    );

            response.setUID(
                    request.getUID()
            );

            response.setCode(
                    MessageCode.BAD_REQUEST
            );

            response.setMessage(
                    "未知或尚未开放的业务模块: "
                            + module
            );

            return response;
        }
    }
}