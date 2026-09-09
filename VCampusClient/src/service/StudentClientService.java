package service;
import java.util.function.Consumer;
import network.SocketClient;
import entity.*;
import protocol.*;
import vo.StudentReviewVO;
public class StudentClientService implements IStudentClientService {
    public final LeaseClient editLease=new LeaseClient(), reviewLease=new LeaseClient();
    private final LeaseClient recordLease=new LeaseClient();
    private final java.util.Map<String,String> recordOwners=new java.util.HashMap<>();
    private String currentStudentId;
    public boolean recordInFlight;
    public Runnable onUnconfirmed=()->{};
    private boolean disposed;
    private final java.util.Map<String,Long> queryVersions=new java.util.HashMap<>();
    public void dispose(){disposed=true;queryVersions.replaceAll((k,v)->v+1);editLease.close();reviewLease.close();recordLease.close();}

    public void beginEdit(String id,Consumer<Message> callback){
        if(disposed)return;
        editLease.acquire("STUDENT",id,()->{if(!disposed)callback.accept(reply(true,"已取得占用"));},error->{if(!disposed)callback.accept(reply(false,error));});
    }
    public void endEdit(String id,Consumer<Message> callback){editLease.close();callback.accept(reply(true,"已释放占用"));}
    private Message reply(boolean success,String text){Message m=new Message(MessageType.RESPONSE,"student","lock");m.setCode(success?MessageCode.SUCCESS:MessageCode.CONFLICT);m.setMessage(text);return m;}
    private final SocketClient socket;
    public StudentClientService(SocketClient s) {
        socket=s;
    }
    private void send(String action,String key,Object value,Consumer<Message> c) {
        if(disposed)return;
        Message m=new Message(typeOf(action),"student",action);
        if(key!=null)m.putData(key,value);
        String type=m.getType().name();
        boolean record=type.contains("_EXPERIENCE_")||type.contains("_FAMILY_MEMBER_")||type.contains("_AWARD_")||type.contains("_AID_");
        if(record) {
            if(recordInFlight){c.accept(reply(false,"记录正在保存，请等待完成"));return;}
            String id=value instanceof StudentAward a?a.getStudentId():value instanceof StudentAid a?a.getStudentId():currentStudentId;
            if("awardId".equals(key)||"aidId".equals(key))id=recordOwners.get(key+":"+value);
            if(id==null){c.accept(reply(false,"请刷新学生详情后重试"));return;}
            recordInFlight=true;
            recordLease.acquire("STUDENT",id,()->{if(!disposed)dispatch(m,c);},error->{recordInFlight=false;if(!disposed)c.accept(reply(false,error));});
        } else dispatch(m,c);
    }
    private MessageType typeOf(String action) {
        return switch(action) {
            case "queryOverview" -> MessageType.STUDENT_OVERVIEW_QUERY;
            case "submitChangeRequest" -> MessageType.STUDENT_CHANGE_SUBMIT;
            case "queryMyRequests" -> MessageType.STUDENT_CHANGE_LIST;
            case "cancelChangeRequest" -> MessageType.STUDENT_CHANGE_CANCEL;
            case "beginEdit" -> MessageType.STUDENT_EDIT_BEGIN;
            case "endEdit" -> MessageType.STUDENT_EDIT_END;
            case "listStudents" -> MessageType.STUDENT_LIST;
            case "queryStudentOverview" -> MessageType.STUDENT_QUERY;
            case "listPendingRequests" -> MessageType.STUDENT_REVIEW_LIST;
            case "queryChangeRequest" -> MessageType.STUDENT_REVIEW_QUERY;
            case "reviewChangeRequest" -> MessageType.STUDENT_REVIEW;
            case "updateStudentByAdmin" -> MessageType.STUDENT_ADMIN_UPDATE;
            case "addAward" -> MessageType.STUDENT_AWARD_ADD;
            case "updateAward" -> MessageType.STUDENT_AWARD_UPDATE;
            case "deleteAward" -> MessageType.STUDENT_AWARD_DELETE;
            case "addAid" -> MessageType.STUDENT_AID_ADD;
            case "updateAid" -> MessageType.STUDENT_AID_UPDATE;
            case "deleteAid" -> MessageType.STUDENT_AID_DELETE;
            case "addExperience" -> MessageType.STUDENT_EXPERIENCE_ADD;
            case "addFamilyMember" -> MessageType.STUDENT_FAMILY_MEMBER_ADD;
            case "updateExperience" -> MessageType.STUDENT_EXPERIENCE_UPDATE;
            case "deleteExperience" -> MessageType.STUDENT_EXPERIENCE_DELETE;
            case "updateFamilyMember" -> MessageType.STUDENT_FAMILY_MEMBER_UPDATE;
            case "deleteFamilyMember" -> MessageType.STUDENT_FAMILY_MEMBER_DELETE;
            default -> throw new IllegalArgumentException("未知学籍消息类型: "+action);
        };
    }
    public void queryOverview(Consumer<Message> c) {
        send("queryOverview",null,null,c);
    }
    public void submitChangeRequest(StudentChangeRequest r,Consumer<Message> c) {
        send("submitChangeRequest","request",r,c);
    }
    public void queryMyRequests(Consumer<Message> c) {
        send("queryMyRequests",null,null,c);
    }
    public void cancelChangeRequest(long id,Consumer<Message> c) {
        send("cancelChangeRequest","requestId",id,c);
    }
    public void listStudents(Consumer<Message> c) {
        send("listStudents",null,null,c);
    }
    public void queryStudentOverview(String id,Consumer<Message> c) {
        send("queryStudentOverview","studentId",id,c);
    }
    public void listPendingRequests(Consumer<Message> c) {
        send("listPendingRequests",null,null,c);
    }
    public void queryChangeRequest(long id,Consumer<Message> c) {
        send("queryChangeRequest","requestId",id,c);
    }
    public void reviewChangeRequest(StudentReviewVO r,Consumer<Message> c) {
        if(disposed)return;
        if(reviewLease.busy()){c.accept(reply(false,"审核正在提交，请等待完成"));return;}
        reviewLease.acquire("STUDENT_CHANGE_REQUEST",String.valueOf(r.getRequestId()),
                ()->{if(!disposed)send("reviewChangeRequest","review",r,c);},
                error->{if(!disposed)c.accept(reply(false,error));});
    }
    public void updateStudentByAdmin(Student s,Student original,Consumer<Message> c) {
        if(disposed)return;
        Message m=new Message(MessageType.STUDENT_ADMIN_UPDATE,"student","updateStudentByAdmin");
        m.putData("student",s);m.putData("original",original);dispatch(m,c);
    }
    public void addAward(StudentAward a,Consumer<Message> c) {
        send("addAward","award",a,c);
    }
    public void updateAward(StudentAward a,Consumer<Message> c) {
        send("updateAward","award",a,c);
    }
    public void deleteAward(long id,Consumer<Message> c) {
        send("deleteAward","awardId",id,c);
    }
    public void addAid(StudentAid a,Consumer<Message> c) {
        send("addAid","aid",a,c);
    }
    public void updateAid(StudentAid a,Consumer<Message> c) {
        send("updateAid","aid",a,c);
    }
    public void deleteAid(long id,Consumer<Message> c) {
        send("deleteAid","aidId",id,c);
    }
    public void addExperience(StudentExperience x,Consumer<Message> c){send("addExperience","experience",x,c);}
    public void addFamilyMember(StudentFamilyMember x,Consumer<Message> c){send("addFamilyMember","member",x,c);}
    public void updateExperience(StudentExperience x,Consumer<Message> c){send("updateExperience","experience",x,c);}
    public void deleteExperience(long id,Consumer<Message> c){send("deleteExperience","experienceId",id,c);}
    public void updateFamilyMember(StudentFamilyMember x,Consumer<Message> c){send("updateFamilyMember","member",x,c);}
    public void deleteFamilyMember(long id,Consumer<Message> c){send("deleteFamilyMember","memberId",id,c);}

    private void dispatch(Message m,Consumer<Message> callback) {
        if(disposed)return;
        String type=m.getType().name();
        boolean record=type.contains("_EXPERIENCE_")||type.contains("_FAMILY_MEMBER_")||type.contains("_AWARD_")||type.contains("_AID_");
        if(record)recordInFlight=true;
        boolean query=type.endsWith("QUERY")||type.endsWith("LIST");
        String channel=type.contains("OVERVIEW")||type.equals("STUDENT_QUERY")||type.equals("STUDENT_DETAIL_QUERY")?"overview":type;
        long version=query?queryVersions.merge(channel,1L,Long::sum):0;
        LeaseClient lease=record?recordLease:type.equals("STUDENT_REVIEW")?reviewLease:editLease;
        if(type.equals("STUDENT_ADMIN_UPDATE")||type.equals("STUDENT_CHANGE_SUBMIT")||type.equals("STUDENT_REVIEW"))m.setLock(lease.proof());
        if(record)m.setLock(recordLease.proof());
        socket.sendAsync(m).whenComplete((response,error)->util.Fx.run(()->{
            boolean relevant=m.getLock()==null || !lease.busy() || lease.owns(m.getLock());
            if(record){recordInFlight=false;recordLease.closeIfOwned(m.getLock());}
            if(type.equals("STUDENT_REVIEW"))reviewLease.closeIfOwned(m.getLock());
            if(disposed)return;
            if(query && !java.util.Objects.equals(queryVersions.get(channel),version))return;
            Message result=response;
            if(error!=null){
                result=new Message(MessageType.RESPONSE,"student",m.getAction());result.setCode(MessageCode.ERROR);
                boolean uncertain=!query && SocketClient.possiblySent(error);
                result.putData("resultUnconfirmed",uncertain);
                result.setMessage(uncertain?"操作结果未确认，请刷新查看最新状态后再决定是否重试。":"网络请求未完成，请稍后重新操作。");
                if(!query)lease.invalidateIfOwned(m.getLock());
            }else if((result.getCode()==MessageCode.CONFLICT || result.getCode()==MessageCode.UNAUTHORIZED || result.getCode()==MessageCode.FORBIDDEN) && m.getLock()!=null)lease.invalidateIfOwned(m.getLock());
            if(query && result.getCode()==MessageCode.SUCCESS && result.getData().containsKey("overview")) {
                com.google.gson.Gson gson=new com.google.gson.Gson();
                vo.StudentOverviewVO overview=gson.fromJson(gson.toJson(result.getData().get("overview")),vo.StudentOverviewVO.class);
                if(overview!=null&&overview.getStudent()!=null) {
                    currentStudentId=overview.getStudent().getStudentId();
                    if(overview.getAwards()!=null)for(StudentAward a:overview.getAwards())recordOwners.put("awardId:"+a.getAwardId(),currentStudentId);
                    if(overview.getAids()!=null)for(StudentAid a:overview.getAids())recordOwners.put("aidId:"+a.getAidId(),currentStudentId);
                }
            }
            callback.accept(result);
            if(relevant && Boolean.TRUE.equals(result.getData().get("resultUnconfirmed")))onUnconfirmed.run();
        }));
    }
}
