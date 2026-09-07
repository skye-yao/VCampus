package service;
import entity.*; import network.SocketClient; import protocol.*; import vo.TeacherReviewVO; import java.util.function.Consumer;
public class TeacherClientService {
    public final LeaseClient editLease=new LeaseClient(), reviewLease=new LeaseClient();
    public boolean recordInFlight;
    public Runnable onUnconfirmed=()->{};
    private boolean disposed;
    private final java.util.Map<String,Long> queryVersions=new java.util.HashMap<>();
    public void dispose(){disposed=true;queryVersions.replaceAll((k,v)->v+1);editLease.close();reviewLease.close();}
private final SocketClient socket=SocketClient.getInstance();
 private void send(MessageType type,String action,String key,Object value,Consumer<Message> c){Message m=new Message(type,"teacher",action);if(key!=null)m.putData(key,value);dispatch(m,c);}
 public void cancel(long id,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_CANCEL,"cancel","requestId",Long.toString(id),c);}
 public void overview(Consumer<Message> c){send(MessageType.TEACHER_OVERVIEW_QUERY,"overview",null,null,c);} public void submit(TeacherChangeRequest r,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_SUBMIT,"submit","request",r,c);}
 public void list(Consumer<Message> c){send(MessageType.TEACHER_LIST,"list",null,null,c);} public void query(String id,Consumer<Message> c){send(MessageType.TEACHER_QUERY,"query","teacherId",id,c);} public void update(Teacher t,Consumer<Message> c){send(MessageType.TEACHER_ADMIN_UPDATE,"update","teacher",t,c);}
 public void reviews(Consumer<Message> c){send(MessageType.TEACHER_REVIEW_LIST,"reviews",null,null,c);} public void reviewQuery(long id,Consumer<Message> c){send(MessageType.TEACHER_REVIEW_QUERY,"reviewQuery","requestId",Long.toString(id),c);} public void review(TeacherReviewVO v,Consumer<Message> c){send(MessageType.TEACHER_REVIEW,"review","review",v,c);}
 public void addWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_ADD,"addWorkExperience","experience",x,c);}
 public void updateWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_UPDATE,"updateWorkExperience","experience",x,c);}
 public void deleteWorkExperience(long id,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_DELETE,"deleteWorkExperience","experienceId",Long.toString(id),c);}
 public void addFamilyMember(TeacherFamilyMember x,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_ADD,"addFamilyMember","member",x,c);}
 public void updateFamilyMember(TeacherFamilyMember x,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_UPDATE,"updateFamilyMember","member",x,c);}
 public void deleteFamilyMember(long id,Consumer<Message> c){send(MessageType.TEACHER_FAMILY_MEMBER_DELETE,"deleteFamilyMember","memberId",Long.toString(id),c);}

    private void dispatch(Message m,Consumer<Message> callback) {
        String type=m.getType().name();
        boolean record=type.contains("_EXPERIENCE_")||type.contains("_FAMILY_MEMBER_")||type.contains("_AWARD_")||type.contains("_AID_");
        if(record)recordInFlight=true;
        boolean query=type.endsWith("QUERY")||type.endsWith("LIST");
        String channel=type.contains("OVERVIEW")||type.equals("TEACHER_QUERY")||type.equals("TEACHER_DETAIL_QUERY")?"overview":type;
        long version=query?queryVersions.merge(channel,1L,Long::sum):0;
        LeaseClient lease=type.equals("TEACHER_REVIEW")||type.equals("TEACHER_REVIEW_QUERY")?reviewLease:editLease;
        if(type.equals("TEACHER_ADMIN_UPDATE")||type.equals("TEACHER_CHANGE_SUBMIT")||type.equals("TEACHER_REVIEW")||type.equals("TEACHER_REVIEW_QUERY"))m.setLock(lease.proof());
        if(record)m.setLock(editLease.proof());
        socket.sendAsync(m).whenComplete((response,error)->util.Fx.run(()->{
            boolean relevant=m.getLock()==null || !lease.busy() || lease.owns(m.getLock());
            if(record){recordInFlight=false;editLease.closeIfOwned(m.getLock());}
            if(disposed)return;
            if(query && !java.util.Objects.equals(queryVersions.get(channel),version))return;
            Message result=response;
            if(error!=null){
                result=new Message(MessageType.RESPONSE,"teacher",m.getAction());result.setCode(MessageCode.ERROR);
                boolean uncertain=!query && SocketClient.possiblySent(error);
                result.putData("resultUnconfirmed",uncertain);
                result.setMessage(uncertain?"操作结果未确认，请刷新查看最新状态后再决定是否重试。":"网络请求未完成，请稍后重新操作。");
                if(!query)lease.invalidateIfOwned(m.getLock());
            }else if((result.getCode()==MessageCode.CONFLICT || result.getCode()==MessageCode.UNAUTHORIZED || result.getCode()==MessageCode.FORBIDDEN) && m.getLock()!=null)lease.invalidateIfOwned(m.getLock());
            callback.accept(result);
            if(relevant && Boolean.TRUE.equals(result.getData().get("resultUnconfirmed")))onUnconfirmed.run();
        }));
    }
}
