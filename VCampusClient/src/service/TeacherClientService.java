package service;
import entity.*; import network.SocketClient; import protocol.*; import vo.TeacherReviewVO; import java.util.function.Consumer;
public class TeacherClientService implements ITeacherClientService {private final SocketClient socket=SocketClient.getInstance();
 @Override
    public void cancel(long id,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_CANCEL,"cancel","requestId",id,c);}
 private void send(MessageType type,String action,String key,Object value,Consumer<Message> c){Message m=new Message(type,"teacher",action);if(key!=null)m.putData(key,value);String requestSession=session.ClientSession.getInstance().getToken();socket.sendAsync(m).whenComplete((r,e)->{if(!java.util.Objects.equals(requestSession,session.ClientSession.getInstance().getToken()))return;if(e==null)c.accept(r);else{Message f=new Message(MessageType.RESPONSE,"teacher",action);f.setCode(MessageCode.ERROR);f.setMessage("连接教师信息服务失败: "+e.getMessage());c.accept(f);}});}
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
}
