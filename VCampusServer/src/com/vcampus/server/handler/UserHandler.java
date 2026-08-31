package com.vcampus.server.handler;
import com.vcampus.common.protocol.*;
public class UserHandler {
    public Message handle(Message request) {
        Message response=new Message(MessageType.RESPONSE,"user",request.getAction(),MessageCode.ERROR);
        response.setMessage("用户模块尚未实现"); return response;
    }
}
