package network;

import protocol.Message;
import protocol.MessageCode;
import protocol.MessageType;
import handler.AdminCourseHandler;
import handler.CourseHandler;
import handler.TeacherCourseHandler;
import handler.UserHandler;
import handler.StudentHandler;
import handler.TeacherHandler;
import handler.ShopHandler;
import handler.BankHandler;
import service.BankService;
import handler.LibraryHandler;

/**
 * 服务端消息分发器
 *
 * <p>根据消息的 module 字段，将请求分发到对应的 Handler 处理。
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class MessageDispatcher {

    // 各模块 Handler
    private final UserHandler userHandler;
    private final handler.ChatHandler chatHandler = new handler.ChatHandler();
    private final StudentHandler studentHandler;
    private final TeacherHandler teacherHandler;
    private final ShopHandler shopHandler;
    private final BankHandler bankHandler;
    private final handler.AiHandler aiHandler;
    private final LibraryHandler libraryHandler;
    private final CourseHandler courseHandler;
    private final AdminCourseHandler adminCourseHandler;
    private final TeacherCourseHandler teacherCourseHandler;
    public MessageDispatcher() {
        this(new CourseHandler(), new AdminCourseHandler(), new TeacherCourseHandler());
    }

    public MessageDispatcher(CourseHandler courseHandler) {
        this(courseHandler, new AdminCourseHandler(), new TeacherCourseHandler());
    }

    public MessageDispatcher(CourseHandler courseHandler,
                             AdminCourseHandler adminCourseHandler) {
        this(courseHandler, adminCourseHandler, new TeacherCourseHandler());
    }

    /** 注入教师端 Handler 的构造方法；其余模块仍按默认实现装配。 */
    public MessageDispatcher(TeacherCourseHandler teacherCourseHandler) {
        this(new CourseHandler(), new AdminCourseHandler(), teacherCourseHandler);
    }

    public MessageDispatcher(CourseHandler courseHandler,
                              AdminCourseHandler adminCourseHandler,
                              TeacherCourseHandler teacherCourseHandler) {
        this.userHandler = new UserHandler();
        this.studentHandler = new StudentHandler();
        this.teacherHandler = new TeacherHandler();
        BankService bankService = new BankService();
        this.shopHandler = new ShopHandler(bankService);
        this.bankHandler = new BankHandler(bankService);
        this.aiHandler = new handler.AiHandler(bankService);
        this.libraryHandler = new LibraryHandler();
        this.courseHandler = courseHandler == null ? new CourseHandler() : courseHandler;
        this.adminCourseHandler = adminCourseHandler == null
                ? new AdminCourseHandler() : adminCourseHandler;
        this.teacherCourseHandler = teacherCourseHandler == null
                ? new TeacherCourseHandler() : teacherCourseHandler;
    }

    /**
     * 分发消息到对应的 Handler
     *
     * @param request 请求消息
     * @return 响应消息
     */
    public Message dispatch(Message request) {
        if (request == null) {
            Message response = new Message(MessageType.RESPONSE, "system", "unknown");
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("请求不能为空");
            return response;
        }

        if (request.getType()==MessageType.LOCK_ACQUIRE || request.getType()==MessageType.LOCK_RENEW || request.getType()==MessageType.LOCK_RELEASE) return new handler.LockHandler().handle(request);
        String module = request.getModule();

        // 根据模块分发
        if ("user".equalsIgnoreCase(module) || "permission".equalsIgnoreCase(module) || "admin".equalsIgnoreCase(module)) {
            return userHandler.handle(request);
        } else if ("chat".equalsIgnoreCase(module)) {
            return chatHandler.handle(request);
        } else if ("library".equalsIgnoreCase(module)) {
                return libraryHandler.handle(request);
        } else if ("student".equalsIgnoreCase(module)) {
            return studentHandler.handle(request);
        } else if ("teacher".equalsIgnoreCase(module)) {
            return teacherHandler.handle(request);
        } else if ("shop".equalsIgnoreCase(module)) {
            return shopHandler.handle(request);
        } else if ("bank".equalsIgnoreCase(module)) {
            return bankHandler.handle(request);
        } else if ("ai".equalsIgnoreCase(module)) {
            return aiHandler.handle(request);
        } else if ("course".equalsIgnoreCase(module)) {
            return courseHandler.handle(request);
        } else if ("courseAdmin".equalsIgnoreCase(module)) {
            return adminCourseHandler.handle(request);
        } else if ("courseTeacher".equalsIgnoreCase(module)) {
            return teacherCourseHandler.handle(request);
        } else {
            // 未知模块或未实现的模块
            Message response = new Message(MessageType.RESPONSE, module, request.getAction());
            response.setUID(request.getUID());
            response.setCode(MessageCode.BAD_REQUEST);
            response.setMessage("未知或尚未开放的业务模块: " + module);
            return response;
        }
    }
}
