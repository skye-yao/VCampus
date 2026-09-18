package main;

import dao.CourseEventOutboxDAO;
import handler.AdminCourseHandler;
import handler.CourseHandler;
import handler.TeacherCourseHandler;
import network.CourseFileServer;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import service.CourseEventDispatcher;
import service.CourseWaitlistScheduler;
import service.CourseWaitlistService;
import service.ShopService;
import service.TeacherAdjustmentApplicationService;
import service.TeacherCourseQueryService;
import service.TeacherFileTicketService;
import service.TeacherGradeBookService;
import service.TeacherGradeImportService;
import service.TeacherGradeImportStore;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 虚拟校园系统服务端启动入口。
 *
 * <p>
 * 先配置无头图像处理，再做数据库就绪检查，最后组装并启动共享依赖。
 *
 * @author VirtualCampus 架构组
 * @version 2.0
 */
public class ServerMain {

    /**
     * 服务端程序入口。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {

        configureRuntime();

        System.out.println("=================================");
        System.out.println("      VCampus 服务端启动");
        System.out.println("=================================");

        if (!databaseReady()) {
            System.err.println("数据库不可用，服务端启动中止。");
            return;
        }

        // 后台预热商品缩略图：不阻塞启动，第一位打开商品中心的用户不必等待现场生成。
        ShopService.warmThumbnailCacheAsync();

        OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        CourseWaitlistService waitlistService = new CourseWaitlistService();
        CourseWaitlistScheduler waitlistScheduler =
                new CourseWaitlistScheduler(waitlistService);
        CourseEventDispatcher eventDispatcher = new CourseEventDispatcher(
                registry, new CourseEventOutboxDAO(), ServerMain::openConnection);
        CourseHandler courseHandler = new CourseHandler(waitlistService, eventDispatcher);

        // 教师成绩文件传输：全进程只有一个票据服务，同时被业务 Handler（签发）与文件监听器
        // （兑换）使用；创建到一半失败时，先把已经创建的资源关掉再交给上层。
        TeacherFileTicketService fileTickets =
                new TeacherFileTicketService(CourseFileServer.configuredPort());
        CourseFileServer fileServer;
        try {
            fileServer = new CourseFileServer(fileTickets);
        } catch (RuntimeException failure) {
            fileTickets.close();
            throw failure;
        }

        // 成绩工作副本与导入预览各只有一份：一个票据服务的签发票据、文件连接的落盘与导入预览
        // 兑换的是同一张票，三者必须共用实例；成绩表服务同时供草稿写入与导入确认使用。
        TeacherGradeBookService gradeBookService = new TeacherGradeBookService();
        TeacherGradeImportService gradeImportService = new TeacherGradeImportService(fileTickets,
                gradeBookService, new TeacherGradeImportStore());

        MessageDispatcher dispatcher;
        Server server;
        try {
            dispatcher = new MessageDispatcher(courseHandler, new AdminCourseHandler(),
                    new TeacherCourseHandler(new TeacherCourseQueryService(),
                            new TeacherAdjustmentApplicationService(),
                            gradeBookService, fileTickets, gradeImportService));
            server = new Server(registry, dispatcher, eventDispatcher, waitlistScheduler,
                    fileServer);
        } catch (RuntimeException failure) {
            fileServer.stop();
            throw failure;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "vcampus-shutdown"));

        server.start();
    }
    /**
     * 服务端用 ImageIO/Graphics2D 生成商品缩略图，无显示器环境必须使用无头模式。
     * 数据库连接由 DBUtil 单独设置 UTC；这里不得修改 JVM 全局时区。
     */
    public static void configureRuntime() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * 用一次可关闭的真实连接检查数据库是否就绪。
     */
    private static boolean databaseReady() {
        try (Connection connection = DBUtil.getConnection()) {
            return connection != null;
        } catch (SQLException failure) {
            System.err.println("数据库连接检查失败: " + failure.getMessage());
            return false;
        }
    }

    /**
     * 为投递器提供数据库连接；把受检异常转换为非受检异常。
     */
    private static Connection openConnection() {
        try {
            return DBUtil.getConnection();
        } catch (SQLException failure) {
            throw new IllegalStateException("数据库连接获取失败", failure);
        }
    }
}
