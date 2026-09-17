package handler;

import protocol.*;
import session.SessionManager;
import service.NotificationService;
import util.DBUtil;

public final class NotificationHandler {
    private final NotificationService service = new NotificationService();

    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE, "notification", request.getAction());
        var session = SessionManager.getInstance().getSession(request.getToken());
        if (session == null) {
            response.setCode(MessageCode.UNAUTHORIZED);
            response.setMessage("登录已失效，请重新登录");
            return response;
        }
        try {
            try (var c = DBUtil.getConnection()) {
                response.setData(service.execute(c, session.getUsername(), request.getAction(),
                        request.getData() == null ? java.util.Map.of() : request.getData()));
            }
            response.setCode(MessageCode.SUCCESS);
        } catch (IllegalArgumentException e) {
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage(e.getMessage());
        } catch (Exception e) {
            System.err.println("Notification operation failed: " + request.getAction());
            e.printStackTrace();
            response.setCode(MessageCode.ERROR);
            response.setMessage("通知服务暂不可用: " + e.getMessage());
        }
        return response;
    }
}
