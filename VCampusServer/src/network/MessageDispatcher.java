package network;

import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import handler.UserHandler;
import handler.ShopHandler;
import handler.BankHandler;
import service.BankService;

/**
 * 服务端消息分发器
 *
 * <p>根据消息的 module 字段，将请求分发到对应的 Handler 处理。
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class MessageDispatcher {

    // 各模块 Handler
    private final UserHandler userHandler;
    private final ShopHandler shopHandler;
    private final BankHandler bankHandler;
    private final handler.AiHandler aiHandler;

    public MessageDispatcher() {
        this.userHandler = new UserHandler();
        BankService bankService = new BankService();
        this.shopHandler = new ShopHandler(bankService);
        this.bankHandler = new BankHandler(bankService);
        this.aiHandler = new handler.AiHandler(bankService);
    }

    /**
     * 分发消息到对应的 Handler
     *
     * @param request 请求消息
     * @return 响应消息
     */
    public Message dispatch(Message request) {
        if (request == null) {
            Message response = new Message(MessageType.RESPONSE, "system", "unknown");
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("请求不能为空");
            return response;
        }

        String module = request.getModule();

        // 根据模块分发
        if ("user".equalsIgnoreCase(module)) {
            return userHandler.handle(request);
        } else if ("shop".equalsIgnoreCase(module)) {
            return shopHandler.handle(request);
        } else if ("bank".equalsIgnoreCase(module)) {
            return bankHandler.handle(request);
        } else if ("ai".equalsIgnoreCase(module)) {
            return aiHandler.handle(request);
        } else {
            // 未知模块或未实现的模块
            Message response = new Message(MessageType.RESPONSE, module, request.getAction());
            response.setUID(request.getUID());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("未知或尚未开放的业务模块: " + module);
            return response;
        }
    }
}
