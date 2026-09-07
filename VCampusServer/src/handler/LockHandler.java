package handler;

import lock.ResourceLockManager;
import protocol.*;
import session.*;
import service.StudentService;
import service.TeacherService;

public class LockHandler {
    private final ResourceLockManager locks=ResourceLockManager.getInstance();
    public Message handle(Message q) {
        Message r=new Message(MessageType.RESPONSE,"lock",q.getAction());
        r.setUID(q.getUID());r.setRequestId(q.getRequestId());
        try {
            UserSession user=SessionManager.getInstance().getSession(q.getToken());
            if(user==null) { r.setCode(MessageCode.UNAUTHORIZED);r.setMessage("请先登录");return r; }
            LockRequest proof=q.getLock();
            if(proof==null)throw new IllegalArgumentException("缺少占用参数");
            LockResponse result;
            switch(q.getType()) {
                case LOCK_ACQUIRE -> {
                    try(var guard=locks.guard(proof.resourceKey())) {
                        switch(proof.resourceType()) {
                            case "STUDENT", "STUDENT_RECORDS", "STUDENT_CHANGE_REQUEST" -> new StudentService().authorizeLock(user,proof);
                            case "TEACHER", "TEACHER_RECORDS", "TEACHER_CHANGE_REQUEST" -> new TeacherService().authorizeLock(user,proof);
                            default -> throw new IllegalArgumentException("未知资源类型");
                        }
                        result=locks.acquire(user,proof);
                    }
                }
                case LOCK_RENEW -> result=locks.renew(user,proof);
                case LOCK_RELEASE -> {locks.release(user,proof);result=new LockResponse(true,null,0,"已释放占用");}
                default -> throw new IllegalArgumentException("不支持的占用操作");
            }
            r.putData("lock",result);r.setMessage(result.message());
        } catch(SecurityException e) {r.setCode(MessageCode.FORBIDDEN);r.setMessage(e.getMessage());}
        catch(IllegalStateException e) {r.setCode(MessageCode.CONFLICT);r.setMessage(e.getMessage());}
        catch(IllegalArgumentException e) {r.setCode(MessageCode.BAD_REQUEST);r.setMessage(e.getMessage());}
        catch(Exception e) {r.setCode(MessageCode.ERROR);r.setMessage("占用服务暂不可用");}
        return r;
    }
}
