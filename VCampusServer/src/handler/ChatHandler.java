package handler;

import protocol.*;
import session.SessionManager;
import service.ChatService;
import util.DBUtil;

public final class ChatHandler {
    private final ChatService service = new ChatService();
    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE,"chat",request.getAction());
        var session=SessionManager.getInstance().getSession(request.getToken());
        if(session==null){response.setCode(MessageCode.UNAUTHORIZED);response.setMessage("登录已失效，请重新登录");return response;}
        try {
            ChatService.ensureSchema();
            try(var c=DBUtil.getConnection()) { response.setData(service.execute(c,session.getUsername(),request.getAction(),request.getData()==null?java.util.Map.of():request.getData())); }
        } catch(IllegalArgumentException e){response.setCode(MessageCode.BAD_REQUEST);response.setMessage(e.getMessage());}
        catch(Exception e){
            System.err.println("Chat operation failed: "+request.getAction());
            e.printStackTrace();
            response.setCode(MessageCode.ERROR);
            response.setMessage("聊天服务暂不可用，请稍后重试；若持续失败，请查看服务端聊天错误日志");
        }
        return response;
    }
}
