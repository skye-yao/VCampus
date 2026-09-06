package service;
import entity.*; import network.SocketClient; import protocol.*; import vo.TeacherReviewVO; import java.util.function.Consumer;
public class TeacherClientService {private final SocketClient socket=SocketClient.getInstance();
 private void send(MessageType type,String action,String key,Object value,Consumer<Message> c){Message m=new Message(type,"teacher",action);if(key!=null)m.putData(key,value);socket.sendAsync(m).whenComplete((r,e)->{if(e==null)c.accept(r);else{Message f=new Message(MessageType.RESPONSE,"teacher",action);f.setCode(MessageCode.ERROR);f.setMessage("连接教师信息服务失败: "+e.getMessage());c.accept(f);}});}
 public void overview(Consumer<Message> c){send(MessageType.TEACHER_OVERVIEW_QUERY,"overview",null,null,c);} public void submit(TeacherChangeRequest r,Consumer<Message> c){send(MessageType.TEACHER_CHANGE_SUBMIT,"submit","request",r,c);}
 public void list(Consumer<Message> c){send(MessageType.TEACHER_LIST,"list",null,null,c);} public void query(String id,Consumer<Message> c){send(MessageType.TEACHER_QUERY,"query","teacherId",id,c);} public void update(Teacher t,Consumer<Message> c){send(MessageType.TEACHER_ADMIN_UPDATE,"update","teacher",t,c);}
 public void reviews(Consumer<Message> c){send(MessageType.TEACHER_REVIEW_LIST,"reviews",null,null,c);} public void reviewQuery(long id,Consumer<Message> c){send(MessageType.TEACHER_REVIEW_QUERY,"reviewQuery","requestId",id,c);} public void review(TeacherReviewVO v,Consumer<Message> c){send(MessageType.TEACHER_REVIEW,"review","review",v,c);}
 public void addWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_ADD,"addWorkExperience","experience",x,c);}
 public void updateWorkExperience(TeacherWorkExperience x,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_UPDATE,"updateWorkExperience","experience",x,c);}
 public void deleteWorkExperience(long id,Consumer<Message> c){send(MessageType.TEACHER_WORK_EXPERIENCE_DELETE,"deleteWorkExperience","experienceId",id,c);}
}
