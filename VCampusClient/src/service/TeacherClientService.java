package service;
import entity.*; import network.SocketClient; import protocol.*; import vo.TeacherReviewVO; import java.util.function.Consumer;
public class TeacherClientService implements ITeacherClientService {
    private final SocketClient socket=SocketClient.getInstance();
    private final LeaseClient editLease = new LeaseClient();
    private final LeaseClient reviewLease = new LeaseClient();
    private boolean disposed;
 @Override
    public void cancel(long id,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_CANCEL,"cancel","requestId",id,c);}
    private void send(
            MessageType type,
            String action,
            String key,
            Object value,
            Consumer<Message> callback) {
        if (disposed)
            return;
        Message message =
                new Message(
                        type,
                        "teacher",
                        action
                );
        if (key != null) {
            message.putData(
                    key,
                    value
            );
        }
        dispatch(
                message,
                callback
        );
    }
    private void dispatch(
            Message message,
            Consumer<Message> callback) {

        if (disposed)
            return;

        MessageType type =
                message.getType();

        // ------------------------------------------------
        // 1. 判断这次写操作应该使用哪一个 Lease
        // ------------------------------------------------

        LeaseClient lease =
                type == MessageType.TEACHER_REVIEW
                        ? reviewLease
                        : editLease;


        // ------------------------------------------------
        // 2. 教师正式档案写操作
        //    必须带 TEACHER:teacherId 的编辑锁凭证
        // ------------------------------------------------

        if (type == MessageType.TEACHER_CHANGE_SUBMIT
                || type == MessageType.TEACHER_ADMIN_UPDATE) {

            message.setLock(
                    editLease.proof()
            );
        }


        // ------------------------------------------------
        // 3. 审核操作
        //    使用 TEACHER_CHANGE_REQUEST:requestId
        // ------------------------------------------------

        if (type == MessageType.TEACHER_REVIEW) {

            message.setLock(
                    reviewLease.proof()
            );
        }


        // ------------------------------------------------
        // 4. 记录当前登录 Session
        // ------------------------------------------------

        String requestSession =
                session.ClientSession
                        .getInstance()
                        .getToken();


        // ------------------------------------------------
        // 5. 真正发送
        // ------------------------------------------------

        socket.sendAsync(message)
                .whenComplete(
                        (response, error) ->
                                util.Fx.run(() -> {

                                    // 登录账号已经变化，旧请求直接丢弃
                                    if (!java.util.Objects.equals(
                                            requestSession,
                                            session.ClientSession
                                                    .getInstance()
                                                    .getToken())) {

                                        dispose();
                                        return;
                                    }

                                    if (disposed)
                                        return;


                                    Message result =
                                            response;


                                    // ------------------------------------------------
                                    // 6. 网络错误
                                    // ------------------------------------------------

                                    if (error != null) {

                                        result =
                                                new Message(
                                                        MessageType.RESPONSE,
                                                        "teacher",
                                                        message.getAction()
                                                );

                                        result.setCode(
                                                MessageCode.ERROR
                                        );

                                        result.setMessage(
                                                "连接教师信息服务失败: "
                                                        + error.getMessage()
                                        );

                                        // 如果这个请求带了锁，
                                        // 网络状态已经无法确认，
                                        // 本地不继续认为锁可靠
                                        if (message.getLock() != null) {

                                            lease.invalidateIfOwned(
                                                    message.getLock()
                                            );
                                        }
                                    }


                                    // ------------------------------------------------
                                    // 7. 服务器明确告诉锁失效/没权限
                                    // ------------------------------------------------

                                    else if (
                                            (result.getCode()
                                                    == MessageCode.CONFLICT

                                                    || result.getCode()
                                                    == MessageCode.UNAUTHORIZED

                                                    || result.getCode()
                                                    == MessageCode.FORBIDDEN)

                                                    && message.getLock() != null) {

                                        lease.invalidateIfOwned(
                                                message.getLock()
                                        );
                                    }


                                    // ------------------------------------------------
                                    // 8. 返回 Controller
                                    // ------------------------------------------------

                                    callback.accept(result);
                                }));
    }
 @Override
    public void overview(Consumer<Message> c){send(MessageType.TEACHER_OVERVIEW_QUERY,"overview",null,null,c);} @Override
    public void submit(TeacherChangeRequest r,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_SUBMIT,"submit","request",r,c);}
 @Override
    public void list(Consumer<Message> c){send(MessageType.TEACHER_LIST,"list",null,null,c);} @Override
    public void query(String id,Consumer<Message> c){send(MessageType.TEACHER_QUERY,"query","teacherId",id,c);} @Override
    public void update(Teacher t,Consumer<Message> c){send(MessageType.TEACHER_ADMIN_UPDATE,"update","teacher",t,c);}
 @Override
    public void reviews(Consumer<Message> c){send(MessageType.TEACHER_REVIEW_LIST,"reviews",null,null,c);} @Override
    public void reviewQuery(long id,Consumer<Message> c){send(MessageType.TEACHER_REVIEW_QUERY,"reviewQuery","requestId",id,c);} @Override
    public void review(TeacherReviewVO v,Consumer<Message> c){send(MessageType.TEACHER_REVIEW,"review","review",v,c);}
 @Override
    public void addWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_ADD,"addWorkExperience","experience",x,c);}
 @Override
    public void updateWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_UPDATE,"updateWorkExperience","experience",x,c);}
 @Override
    public void deleteWorkExperience(long id,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_DELETE,"deleteWorkExperience","experienceId",id,c);}
 @Override
    public void addFamilyMember(TeacherFamilyMember x,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_ADD,"addFamilyMember","member",x,c);}
 @Override
    public void updateFamilyMember(TeacherFamilyMember x,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_UPDATE,"updateFamilyMember","member",x,c);}
 @Override
    public void deleteFamilyMember(long id,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_DELETE,"deleteFamilyMember","memberId",id,c);}

    @Override
    public void onEditLeaseLost(Runnable callback) {
        editLease.onLost(callback);
    }

    @Override
    public void releaseEditLease() {
        editLease.close();
    }

    @Override
    public void dispose() {
        disposed = true;
        editLease.close();
        reviewLease.close();
    }

    @Override
    public void beginEdit(String teacherId, Consumer<Message> callback) {
        if (disposed) {
            return;
        }

        editLease.acquire(
                "TEACHER",
                teacherId,
                () -> {
                    if (!disposed) {
                        callback.accept(reply(true, "已取得占用"));
                    }
                },
                error -> {
                    if (!disposed) {
                        callback.accept(reply(false, error));
                    }
                }
        );
    }

    @Override
    public void endEdit(String teacherId, Consumer<Message> callback) {
        editLease.close();
        callback.accept(reply(true, "已释放占用"));
    }

    private Message reply(boolean success, String text) {
        Message message =
                new Message(MessageType.RESPONSE, "teacher", "lock");

        message.setCode(
                success
                        ? MessageCode.SUCCESS
                        : MessageCode.CONFLICT
        );

        message.setMessage(text);
        return message;
    }
}
