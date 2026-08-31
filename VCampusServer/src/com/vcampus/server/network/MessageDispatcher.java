package com.vcampus.server.network;

import com.vcampus.common.protocol.Message;
import com.vcampus.common.protocol.MessageCode;
import com.vcampus.common.protocol.MessageType;
import com.vcampus.server.handler.UserHandler;
import com.vcampus.server.handler.StudentHandler;
//import com.vcampus.server.handler.StudentHandler;
//import com.vcampus.server.handler.CourseHandler;
//import com.vcampus.server.handler.LibraryHandler;
//import com.vcampus.server.handler.BankHandler;
//import com.vcampus.server.handler.ShopHandler;
//import com.vcampus.server.handler.AIHandler;

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
    private UserHandler userHandler;
    private StudentHandler studentHandler;
    //private CourseHandler courseHandler;
    //private LibraryHandler libraryHandler;
    //private BankHandler bankHandler;
    //private ShopHandler shopHandler;
    //private AIHandler aiHandler;

    public MessageDispatcher() {
        this.userHandler = new UserHandler();
        this.studentHandler = new StudentHandler();
        //this.courseHandler = new CourseHandler();
        //this.libraryHandler = new LibraryHandler();
        //this.bankHandler = new BankHandler();
        //this.shopHandler = new ShopHandler();
        //this.aiHandler = new AIHandler();
    }

    /**
     * 分发消息到对应的 Handler
     *
     * @param request 请求消息
     * @return 响应消息
     */
    public Message dispatch(Message request) {
        String module = request.getModule();

        // 根据模块分发
        if ("user".equalsIgnoreCase(module)) {
            return userHandler.handle(request);
        }
        else if ("student".equalsIgnoreCase(module)) {
            return studentHandler.handle(request);
      //  } else if ("course".equalsIgnoreCase(module)) {
       //     return courseHandler.handle(request);
      //  } else if ("library".equalsIgnoreCase(module)) {
       //     return libraryHandler.handle(request);
       // } else if ("bank".equalsIgnoreCase(module)) {
      //      return bankHandler.handle(request);
      //  } else if ("shop".equalsIgnoreCase(module)) {
       //     return shopHandler.handle(request);
      //  } else if ("ai".equalsIgnoreCase(module)) {
       //     return aiHandler.handle(request);
        //} else {
            // 未知模块
         //   Message response = new Message();
         //   response.setType(MessageType.RESPONSE);
          //  response.setModule(request.getModule());
          //  response.setAction(request.getAction());
         //   response.setCode(MessageCode.BAD_REQUEST);
          //  response.setMessage("未知模块: " + module);
       //     return response;
        }
        Message response = new Message(MessageType.RESPONSE, module, request.getAction(), MessageCode.BAD_REQUEST);
        response.setMessage("未知模块: " + module);
        return response;
    }
}
