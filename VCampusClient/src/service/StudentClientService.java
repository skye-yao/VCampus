package service;
import java.util.function.Consumer;
import network.SocketClient;
import entity.*;
import protocol.*;
import vo.StudentReviewVO;
public class StudentClientService implements IStudentClientService {
    public void beginEdit(String studentId,Consumer<Message> callback){send("beginEdit","studentId",studentId==null?"":studentId,callback);}
    public void endEdit(String studentId,Consumer<Message> callback){send("endEdit","studentId",studentId==null?"":studentId,callback);}
    private final SocketClient socket;
    public StudentClientService(SocketClient s) {
        socket=s;
    }
    private void send(String action,String key,Object value,Consumer<Message> c) {
        Message m=new Message(typeOf(action),"student",action);
        if(key!=null)m.putData(key,value);
        socket.sendAsync(m).whenComplete((r,e)-> {
            if(e==null)c.accept(r);else {
                Message f=new Message(MessageType.RESPONSE,"student",action);f.setCode(MessageCode.ERROR);f.setMessage("连接学籍服务失败: "+e.getMessage());c.accept(f);
            }
        }
        );
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
        send("reviewChangeRequest","review",r,c);
    }
    public void updateStudentByAdmin(Student s,Consumer<Message> c) {
        send("updateStudentByAdmin","student",s,c);
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
}
