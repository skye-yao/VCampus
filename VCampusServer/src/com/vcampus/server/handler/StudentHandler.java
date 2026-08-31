package com.vcampus.server.handler;

import com.google.gson.Gson;
import com.vcampus.common.entity.*;
import com.vcampus.common.protocol.*;
import com.vcampus.common.vo.StudentReviewVO;
import com.vcampus.server.service.StudentService;
import com.vcampus.server.session.*;

/** 将学籍协议请求适配到业务服务。 */
public class StudentHandler {
    private final StudentService service = new StudentService();
    private final Gson gson = new Gson();

    public Message handle(Message request) {
        Message response = new Message(MessageType.RESPONSE, "student", request.getAction());
        try {
            UserSession session = SessionManager.getInstance().getSession(request.getToken());
            if (session == null) return fail(response, MessageCode.UNAUTHORIZED, "请先登录");
            boolean admin = "ADMIN".equalsIgnoreCase(session.getRole());
            switch (request.getType()) {
                case STUDENT_OVERVIEW_QUERY:
                    response.putData("overview", service.queryByUserId(session.getUsername())); break;
                case STUDENT_CHANGE_SUBMIT:
                    response.putData("requestId", service.submit(session.getUsername(), value(request,"request",StudentChangeRequest.class))); break;
                case STUDENT_CHANGE_LIST:
                    response.putData("requests", service.listMyRequests(session.getUsername())); break;
                case STUDENT_CHANGE_CANCEL:
                    service.cancel(session.getUsername(), number(request,"requestId")); break;
                case STUDENT_LIST:
                    requireAdmin(admin); response.putData("students", service.listStudents()); break;
                case STUDENT_QUERY:
                    requireAdmin(admin); response.putData("overview", service.queryByStudentId(string(request,"studentId"))); break;
                case STUDENT_REVIEW_LIST:
                    requireAdmin(admin); response.putData("requests", service.listPending()); break;
                case STUDENT_REVIEW_QUERY:
                    requireAdmin(admin); response.putData("request", service.queryRequest(number(request,"requestId"))); break;
                case STUDENT_REVIEW:
                    requireAdmin(admin); StudentReviewVO review=value(request,"review",StudentReviewVO.class); service.review(review.getRequestId(),review.getReviewResult(),session.getUsername(),review.getReviewRemark()); break;
                case STUDENT_ADMIN_UPDATE:
                    requireAdmin(admin); response.putData("updated",service.updateByAdmin(value(request,"student",Student.class))); break;
                case STUDENT_AWARD_ADD:
                    requireAdmin(admin); response.putData("updated",service.addAward(value(request,"award",StudentAward.class))); break;
                case STUDENT_AWARD_UPDATE:
                    requireAdmin(admin); response.putData("updated",service.updateAward(value(request,"award",StudentAward.class))); break;
                case STUDENT_AWARD_DELETE:
                    requireAdmin(admin); response.putData("updated",service.deleteAward(number(request,"awardId"))); break;
                case STUDENT_AID_ADD:
                    requireAdmin(admin); response.putData("updated",service.addAid(value(request,"aid",StudentAid.class))); break;
                case STUDENT_AID_UPDATE:
                    requireAdmin(admin); response.putData("updated",service.updateAid(value(request,"aid",StudentAid.class))); break;
                case STUDENT_AID_DELETE:
                    requireAdmin(admin); response.putData("updated",service.deleteAid(number(request,"aidId"))); break;
                default: return fail(response,MessageCode.BAD_REQUEST,"不支持的学籍操作");
            }
            response.setCode(MessageCode.SUCCESS); response.setMessage("操作成功"); return response;
        } catch (SecurityException e) { return fail(response,MessageCode.FORBIDDEN,e.getMessage());
        } catch (IllegalStateException e) { return fail(response,MessageCode.CONFLICT,e.getMessage());
        } catch (IllegalArgumentException e) { return fail(response,MessageCode.BAD_REQUEST,e.getMessage());
        } catch (Exception e) { return fail(response,MessageCode.ERROR,"学籍服务异常: "+e.getMessage()); }
    }
    private void requireAdmin(boolean admin){if(!admin)throw new SecurityException("仅管理员可执行该操作");}
    private String string(Message m,String k){Object v=m.getData().get(k);if(v==null)throw new IllegalArgumentException("缺少参数: "+k);return String.valueOf(v);}
    private long number(Message m,String k){Object v=m.getData().get(k);if(v instanceof Number)return ((Number)v).longValue();return Long.parseLong(string(m,k));}
    private <T>T value(Message m,String k,Class<T> type){Object v=m.getData().get(k);if(v==null)throw new IllegalArgumentException("缺少参数: "+k);return gson.fromJson(gson.toJson(v),type);}
    private Message fail(Message m,MessageCode c,String text){m.setCode(c);m.setMessage(text);return m;}
}
