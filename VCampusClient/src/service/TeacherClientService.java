package service;
import entity.*;
import network.SocketClient;
import protocol.*;
import vo.TeacherReviewVO;
import java.util.*;
import java.util.function.Consumer;

public class TeacherClientService implements ITeacherClientService {
    private final SocketClient socket=SocketClient.getInstance();
    private final LeaseClient editLease=new LeaseClient(), reviewLease=new LeaseClient(), recordLease=new LeaseClient();
    private final Map<String,Long> queryVersions=new HashMap<>();
    private boolean disposed,recordInFlight,editInFlight;
    private String currentTeacherId;
    @Override public void cancel(long id,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_CANCEL,"cancel","requestId",id,c);}
    private boolean record(MessageType type){return type.name().contains("_WORK_EXPERIENCE_")||type.name().contains("_FAMILY_MEMBER_");}
    private void send(MessageType type,String action,String key,Object value,Consumer<Message> callback){
        if(disposed)return;
        Message message=new Message(type,"teacher",action);
        if(key!=null)message.putData(key,value);
        if(record(type)) {
            if(recordInFlight){callback.accept(reply(false,"记录正在保存，请等待完成"));return;}
            if(currentTeacherId==null){callback.accept(reply(false,"请刷新教师详情后重试"));return;}
            recordInFlight=true;
            recordLease.acquire("TEACHER",currentTeacherId,()->{if(!disposed)dispatch(message,callback);},error->{recordInFlight=false;if(!disposed)callback.accept(reply(false,error));});
        }else dispatch(message,callback);
    }
    private void dispatch(Message message,Consumer<Message> callback){
        if(disposed)return;
        MessageType type=message.getType();
        boolean record=record(type),review=type==MessageType.TEACHER_REVIEW;
        boolean edit=type==MessageType.TEACHER_CHANGE_SUBMIT||type==MessageType.TEACHER_ADMIN_UPDATE;
        boolean query=type.name().endsWith("QUERY")||type.name().endsWith("LIST");
        if(edit&&editInFlight){callback.accept(reply(false,"资料正在保存，请等待完成"));return;}
        LeaseClient lease=record?recordLease:review?reviewLease:editLease;
        if(record||review||edit){
            message.setLock(lease.proof());
            if(message.getLock()==null){recordInFlight=false;callback.accept(reply(false,LeaseClient.LOST));return;}
        }
        if(edit)editInFlight=true;
        String channel=type==MessageType.TEACHER_QUERY||type==MessageType.TEACHER_OVERVIEW_QUERY?"overview":type.name();
        long version=query?queryVersions.merge(channel,1L,Long::sum):0;
        String token=session.ClientSession.getInstance().getToken();
        socket.sendAsync(message).whenComplete((response,error)->util.Fx.run(()->{
            if(!Objects.equals(token,session.ClientSession.getInstance().getToken())){dispose();return;}
            if(record){recordInFlight=false;recordLease.closeIfOwned(message.getLock());}
            if(review)reviewLease.closeIfOwned(message.getLock());
            if(edit)editInFlight=false;
            if(disposed)return;
            if(query&&!Objects.equals(queryVersions.get(channel),version))return;
            Message result=response;
            if(error!=null){
                result=new Message(MessageType.RESPONSE,"teacher",message.getAction());result.setCode(MessageCode.ERROR);
                result.setMessage(!query?"操作结果未确认，请刷新后检查再决定是否重试":"网络请求未完成，请稍后重试");
                if(message.getLock()!=null)lease.invalidateIfOwned(message.getLock());
            }else if(message.getLock()!=null&&(result.getCode()==MessageCode.CONFLICT||result.getCode()==MessageCode.UNAUTHORIZED||result.getCode()==MessageCode.FORBIDDEN))lease.invalidateIfOwned(message.getLock());
            if(edit&&result.getCode()==MessageCode.SUCCESS)editLease.closeIfOwned(message.getLock());
            if(query&&result.getCode()==MessageCode.SUCCESS&&result.getData().containsKey("overview")){
                com.google.gson.Gson gson=new com.google.gson.Gson();
                vo.TeacherOverviewVO overview=gson.fromJson(gson.toJson(result.getData().get("overview")),vo.TeacherOverviewVO.class);
                currentTeacherId=overview==null||overview.getTeacher()==null?null:overview.getTeacher().getTeacherId();
            }
            callback.accept(result);
        }));
    }
 @Override
    public void overview(Consumer<Message> c){send(MessageType.TEACHER_OVERVIEW_QUERY,"overview",null,null,c);} @Override
    public void submit(TeacherChangeRequest r,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_SUBMIT,"submit","request",r,c);}
 @Override
    public void list(Consumer<Message> c){send(MessageType.TEACHER_LIST,"list",null,null,c);} @Override
    public void query(String id,Consumer<Message> c){send(MessageType.TEACHER_QUERY,"query","teacherId",id,c);} @Override
    public void update(Teacher t,Teacher original,Consumer<Message> c){
        Message m=new Message(MessageType.TEACHER_ADMIN_UPDATE,"teacher","update");m.putData("teacher",t);m.putData("original",original);dispatch(m,c);
    }
 @Override
    public void reviews(Consumer<Message> c){send(MessageType.TEACHER_REVIEW_LIST,"reviews",null,null,c);} @Override
    public void reviewQuery(long id,Consumer<Message> c){send(MessageType.TEACHER_REVIEW_QUERY,"reviewQuery","requestId",id,c);} @Override
    public void review(TeacherReviewVO v,Consumer<Message> c){
        if(disposed)return;
        if(reviewLease.busy()){c.accept(reply(false,"审核正在提交，请等待完成"));return;}
        reviewLease.acquire("TEACHER_CHANGE_REQUEST",String.valueOf(v.getRequestId()),
            ()->{if(!disposed)send(MessageType.TEACHER_REVIEW,"review","review",v,c);},
            error->{if(!disposed)c.accept(reply(false,error));});
    }
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
        queryVersions.replaceAll((k,v)->v+1);recordLease.close();
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
