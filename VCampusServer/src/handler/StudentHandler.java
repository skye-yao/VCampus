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
                case STUDENT_OVERVIEW_QUERY->r.putData("overview",service.queryByUID(s.getUsername()));
                case STUDENT_CHANGE_SUBMIT->r.putData("requestId",service.submit(s,value(q,"request",StudentChangeRequest.class),q.getLock()));
                case STUDENT_CHANGE_LIST->r.putData("requests",service.listMyRequests(s.getUsername()));
                case STUDENT_CHANGE_CANCEL->service.cancel(s.getUsername(),number(q,"requestId"));
                case STUDENT_EDIT_BEGIN, STUDENT_EDIT_END -> throw new IllegalStateException("请更新客户端，使用公共占用协议");
                case STUDENT_LIST-> {
                    needAdmin(admin);
                    r.putData("students",service.listStudents());
                }
                case STUDENT_QUERY-> {
                    needAdmin(admin);
                    r.putData("overview",service.queryByStudentId(string(q,"studentId")));
                }
                case STUDENT_REVIEW_LIST-> {
                    needAdmin(admin);
                    r.putData("requests",service.listPending());
                }
                case STUDENT_REVIEW_QUERY-> {
                    needAdmin(admin);
                    r.putData("request",service.queryRequest(s,number(q,"requestId"),q.getLock()));
                }
                case STUDENT_REVIEW-> {
                    needAdmin(admin);
                    StudentReviewVO v=value(q,"review",StudentReviewVO.class);
                    service.review(s,v.getRequestId(),v.getReviewResult(),v.getReviewRemark(),q.getLock());
                }
                case STUDENT_ADMIN_UPDATE-> {
                    needAdmin(admin);
                    r.putData("updated",service.updateByAdmin(s,value(q,"student",Student.class),q.getLock()));
                }
                case STUDENT_AWARD_ADD-> {
                    needAdmin(admin);
                    r.putData("updated",service.addAward(s,value(q,"award",StudentAward.class),q.getLock()));
                }
                case STUDENT_AWARD_UPDATE-> {
                    needAdmin(admin);
                    r.putData("updated",service.updateAward(s,value(q,"award",StudentAward.class),q.getLock()));
                }
                case STUDENT_AWARD_DELETE-> {
                    needAdmin(admin);
                    r.putData("updated",service.deleteAward(s,number(q,"awardId"),q.getLock()));
                }
                case STUDENT_AID_ADD-> {
                    needAdmin(admin);
                    r.putData("updated",service.addAid(s,value(q,"aid",StudentAid.class),q.getLock()));
                }
                case STUDENT_AID_UPDATE-> {
                    needAdmin(admin);
                    r.putData("updated",service.updateAid(s,value(q,"aid",StudentAid.class),q.getLock()));
                }
                case STUDENT_AID_DELETE-> {
                    needAdmin(admin);
                    r.putData("updated",service.deleteAid(s,number(q,"aidId"),q.getLock()));
                }
                case STUDENT_EXPERIENCE_ADD->r.putData("updated",service.addExperience(s,value(q,"experience",StudentExperience.class),q.getLock()));
                case STUDENT_FAMILY_MEMBER_ADD->r.putData("updated",service.addFamilyMember(s,value(q,"member",StudentFamilyMember.class),q.getLock()));
                case STUDENT_EXPERIENCE_UPDATE->r.putData("updated",service.updateExperience(s,value(q,"experience",StudentExperience.class),q.getLock()));
                case STUDENT_EXPERIENCE_DELETE->r.putData("updated",service.deleteExperience(s,number(q,"experienceId"),q.getLock()));
                case STUDENT_FAMILY_MEMBER_UPDATE->r.putData("updated",service.updateFamilyMember(s,value(q,"member",StudentFamilyMember.class),q.getLock()));
                case STUDENT_FAMILY_MEMBER_DELETE->r.putData("updated",service.deleteFamilyMember(s,number(q,"memberId"),q.getLock()));
                default-> {
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
            return fail(r,MessageCode.ERROR,"学籍服务异常");
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
