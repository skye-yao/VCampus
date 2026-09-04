import handler.LibraryHandler;
import protocol.*;
import session.SessionManager;

/** 只读名单权限回归，不删除书评、不修改数据库。 */
public class LibraryAdminAccessCheck {
    public static void main(String[] args) {
        LibraryHandler handler = new LibraryHandler();
        String studentToken = SessionManager.getInstance().createSession("test-reader", "学生").getToken();
        String adminToken = SessionManager.getInstance().createSession("test-admin", "管理员").getToken();
        for (String kind : new String[]{"borrow", "fine", "loss", "reservation"}) {
            Message request = new Message(MessageType.REQUEST,"library","getadminrecords");
            request.putData("kind",kind);
            if(handler.handle(request).getCode()!=MessageCode.UNAUTHORIZED) throw new AssertionError("Anonymous access");
            request.setToken(studentToken);
            request.putData("role","管理员");
            if(handler.handle(request).getCode()!=MessageCode.FORBIDDEN) throw new AssertionError("Forged admin role accepted");
            request.setToken(adminToken);
            Message response=handler.handle(request);
            if(response.getCode()!=MessageCode.SUCCESS) throw new AssertionError(response.getMessage());
            System.out.println("PASS: "+kind+" query; anonymous and non-admin denied");
        }
    }
}
