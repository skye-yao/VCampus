package handler;
import com.google.gson.Gson; import entity.*; import protocol.*; import service.TeacherService; import session.*; import vo.TeacherReviewVO;
public class TeacherHandler {private final TeacherService service=new TeacherService();private final Gson gson=new Gson();
 public Message handle(Message q){Message r=new Message(MessageType.RESPONSE,"teacher",q.getAction());r.setUID(q.getUID());try{UserSession s=SessionManager.getInstance().getSession(q.getToken());if(s==null)return fail(r,MessageCode.UNAUTHORIZED,"请先登录");boolean admin="管理员".equals(s.getRole())||"ADMIN".equalsIgnoreCase(s.getRole());switch(q.getType()){
 case TEACHER_OVERVIEW_QUERY,TEACHER_DETAIL_QUERY->r.putData("overview",service.queryByUID(s.getUsername()));
 case TEACHER_CHANGE_SUBMIT->r.putData("requestId",service.submit(s.getUsername(),value(q,"request",TeacherChangeRequest.class)));
 case TEACHER_LIST->{needAdmin(admin);r.putData("teachers",service.listTeachers());} case TEACHER_QUERY->{needAdmin(admin);r.putData("overview",service.queryByTeacherId(string(q,"teacherId")));}
 case TEACHER_REVIEW_LIST->{needAdmin(admin);r.putData("requests",service.listAll());} case TEACHER_REVIEW_QUERY->{needAdmin(admin);r.putData("request",service.queryRequest(number(q,"requestId")));}
 case TEACHER_REVIEW->{needAdmin(admin);TeacherReviewVO v=value(q,"review",TeacherReviewVO.class);service.review(v.getRequestId(),v.getReviewResult(),s.getUsername(),v.getReviewRemark());}
 case TEACHER_ADMIN_UPDATE->{needAdmin(admin);r.putData("updated",service.updateByAdmin(value(q,"teacher",Teacher.class)));}
 case TEACHER_FAMILY_MEMBER_ADD->r.putData("updated",service.addFamilyMember(s.getUsername(),value(q,"familyMember",TeacherFamilyMember.class)));
 case TEACHER_FAMILY_MEMBER_UPDATE->r.putData("updated",service.updateFamilyMember(s.getUsername(),value(q,"familyMember",TeacherFamilyMember.class)));
 case TEACHER_FAMILY_MEMBER_DELETE->r.putData("updated",service.deleteFamilyMember(s.getUsername(),number(q,"memberId")));
 case TEACHER_WORK_EXPERIENCE_ADD->r.putData("updated",service.addWorkExperience(s.getUsername(),value(q,"experience",TeacherWorkExperience.class)));
 case TEACHER_WORK_EXPERIENCE_UPDATE->r.putData("updated",service.updateWorkExperience(s.getUsername(),value(q,"experience",TeacherWorkExperience.class)));
 case TEACHER_WORK_EXPERIENCE_DELETE->r.putData("updated",service.deleteWorkExperience(s.getUsername(),number(q,"experienceId")));
 default->{return fail(r,MessageCode.BAD_REQUEST,"不支持的教师信息操作");}}
 r.setCode(MessageCode.SUCCESS);r.setMessage("操作成功");return r;}catch(SecurityException e){return fail(r,MessageCode.FORBIDDEN,e.getMessage());}catch(IllegalArgumentException e){return fail(r,MessageCode.BAD_REQUEST,e.getMessage());}catch(IllegalStateException e){return fail(r,MessageCode.CONFLICT,e.getMessage());}catch(Exception e){e.printStackTrace();return fail(r,MessageCode.ERROR,"教师信息服务异常: "+e.getMessage());}}
 private void needAdmin(boolean b){if(!b)throw new SecurityException("仅管理员可操作");} private String string(Message m,String k){Object v=m.getData().get(k);if(v==null)throw new IllegalArgumentException("缺少参数: "+k);return String.valueOf(v);} private long number(Message m,String k){try{return new java.math.BigDecimal(string(m,k)).longValueExact();}catch(NumberFormatException|ArithmeticException e){throw new IllegalArgumentException("参数必须为整数: "+k);}}
 private<T>T value(Message m,String k,Class<T> c){Object v=m.getData().get(k);if(v==null)throw new IllegalArgumentException("缺少参数: "+k);return gson.fromJson(gson.toJson(v),c);} private Message fail(Message m,MessageCode c,String x){m.setCode(c);m.setMessage(x);return m;}}
