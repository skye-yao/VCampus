package handler;
import com.google.gson.Gson;
import entity.*;
import protocol.*;
import service.StudentService;
import session.*;
import vo.StudentReviewVO;
public class StudentHandler {
    private final StudentService service=new StudentService();
    private final Gson gson=new Gson();
    public Message handle(Message q) {
        Message r=new Message(MessageType.RESPONSE,"student",q.getAction());
        r.setUID(q.getUID());
        try {
            UserSession s=SessionManager.getInstance().getSession(q.getToken());
            if(s==null)return fail(r,MessageCode.UNAUTHORIZED,"请先登录");
            boolean admin="管理员".equals(s.getRole())||"ADMIN".equalsIgnoreCase(s.getRole());
            if(q.getType()==null)return fail(r,MessageCode.BAD_REQUEST,"缺少消息类型");
            switch(q.getType()) {
                case STUDENT_OVERVIEW_QUERY -> {r.putData("overview",service.queryByUID(s.getUsername()));}
                case STUDENT_CHANGE_SUBMIT -> {r.putData("requestId",service.submit(s.getUsername(),value(q,"request",StudentChangeRequest.class)));}
                case STUDENT_CHANGE_LIST -> {r.putData("requests",service.listMyRequests(s.getUsername()));}
                case STUDENT_CHANGE_CANCEL -> {service.cancel(s.getUsername(),number(q,"requestId"));}
                case STUDENT_EDIT_BEGIN -> {r.putData("studentId",service.beginEdit(s.getUsername(),admin,admin?string(q,"studentId"):null));}
                case STUDENT_EDIT_END -> {service.endEdit(s.getUsername(),admin,admin?string(q,"studentId"):null);}
                case STUDENT_LIST -> {
                    needAdmin(admin);
                    r.putData("students",service.listStudents());
                }
                case STUDENT_QUERY -> {
                    needAdmin(admin);
                    r.putData("overview",service.queryByStudentId(string(q,"studentId")));
                }
                case STUDENT_REVIEW_LIST -> {
                    needAdmin(admin);
                    r.putData("requests",service.listPending());
                }
                case STUDENT_REVIEW_QUERY -> {
                    needAdmin(admin);
                    r.putData("request",service.queryRequest(number(q,"requestId")));
                }
                case STUDENT_REVIEW -> {
                    needAdmin(admin);
                    StudentReviewVO v=value(q,"review",StudentReviewVO.class);
                    service.review(v.getRequestId(),v.getReviewResult(),s.getUsername(),v.getReviewRemark());
                }
                case STUDENT_ADMIN_UPDATE -> {
                    needAdmin(admin);
                    r.putData("updated",service.updateByAdmin(s.getUsername(),value(q,"student",Student.class)));
                }
                case STUDENT_AWARD_ADD -> {
                    needAdmin(admin);
                    r.putData("updated",service.addAward(value(q,"award",StudentAward.class)));
                }
                case STUDENT_AWARD_UPDATE -> {
                    needAdmin(admin);
                    r.putData("updated",service.updateAward(value(q,"award",StudentAward.class)));
                }
                case STUDENT_AWARD_DELETE -> {
                    needAdmin(admin);
                    r.putData("updated",service.deleteAward(number(q,"awardId")));
                }
                case STUDENT_AID_ADD -> {
                    needAdmin(admin);
                    r.putData("updated",service.addAid(value(q,"aid",StudentAid.class)));
                }
                case STUDENT_AID_UPDATE -> {
                    needAdmin(admin);
                    r.putData("updated",service.updateAid(value(q,"aid",StudentAid.class)));
                }
                case STUDENT_AID_DELETE -> {
                    needAdmin(admin);
                    r.putData("updated",service.deleteAid(number(q,"aidId")));
                }
                case STUDENT_EXPERIENCE_ADD -> {r.putData("updated",service.addExperience(s.getUsername(),value(q,"experience",StudentExperience.class)));}
                case STUDENT_FAMILY_MEMBER_ADD -> {r.putData("updated",service.addFamilyMember(s.getUsername(),value(q,"member",StudentFamilyMember.class)));}
                case STUDENT_EXPERIENCE_UPDATE -> {r.putData("updated",service.updateExperience(s.getUsername(),value(q,"experience",StudentExperience.class)));}
                case STUDENT_EXPERIENCE_DELETE -> {r.putData("updated",service.deleteExperience(s.getUsername(),number(q,"experienceId")));}
                case STUDENT_FAMILY_MEMBER_UPDATE -> {r.putData("updated",service.updateFamilyMember(s.getUsername(),value(q,"member",StudentFamilyMember.class)));}
                case STUDENT_FAMILY_MEMBER_DELETE -> {r.putData("updated",service.deleteFamilyMember(s.getUsername(),number(q,"memberId")));}
                default -> {
                    return fail(r,MessageCode.BAD_REQUEST,"不支持的学籍操作");
                }
            }
            r.setCode(MessageCode.SUCCESS);
            r.setMessage("操作成功");
            return r;
        }
        catch(SecurityException e) {
            return fail(r,MessageCode.FORBIDDEN,e.getMessage());
        }
        catch(IllegalStateException e) {
            return fail(r,MessageCode.CONFLICT,e.getMessage());
        }
        catch(IllegalArgumentException e) {
            return fail(r,MessageCode.BAD_REQUEST,e.getMessage());
        }
        catch(Exception e) {
            e.printStackTrace(); // DEBUG: 打印完整堆栈
            return fail(r,MessageCode.ERROR,"学籍服务异常: "+e.getClass().getSimpleName()+": "+e.getMessage());
        }
    }
    private void needAdmin(boolean b) {
        if(!b)throw new SecurityException("仅管理员可操作");
    }
    private String string(Message m,String k) {
        Object v=m.getData().get(k);
        if(v==null)throw new IllegalArgumentException("缺少参数: "+k);
        return String.valueOf(v);
    }
    private long number(Message m,String k) {
        Object v=m.getData().get(k);
        return v instanceof Number?((Number)v).longValue():Long.parseLong(string(m,k));
    }
    private<T>T value(Message m,String k,Class<T>c) {
        Object v=m.getData().get(k);
        if(v==null)throw new IllegalArgumentException("缺少参数: "+k);
        return gson.fromJson(gson.toJson(v),c);
    }
    private Message fail(Message m,MessageCode c,String x) {
        m.setCode(c);
        m.setMessage(x);
        return m;
    }
}
