package main;

import dao.CourseEventOutboxDAO;
import handler.CourseHandler;
import network.MessageDispatcher;
import network.OnlineConnectionRegistry;
import network.Server;
import service.CourseEventDispatcher;
import service.CourseWaitlistScheduler;
import service.CourseWaitlistService;
import util.DBUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.TimeZone;

/**
 * 虚拟校园系统服务端启动入口。
 *
 * <p>
 * 先把 JVM 默认时区设为 UTC，再做数据库就绪检查，最后组装并启动共享依赖。
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

        configureTimeZone();

        System.out.println("=================================");
        System.out.println("      VCampus 服务端启动");
        System.out.println("=================================");

        if (!databaseReady()) {
            System.err.println("数据库不可用，服务端启动中止。");
            return;
        }

        OnlineConnectionRegistry registry = new OnlineConnectionRegistry();
        CourseWaitlistService waitlistService = new CourseWaitlistService();
        CourseWaitlistScheduler waitlistScheduler =
                new CourseWaitlistScheduler(waitlistService);
        CourseEventDispatcher eventDispatcher = new CourseEventDispatcher(
                registry, new CourseEventOutboxDAO(), ServerMain::openConnection);
        CourseHandler courseHandler = new CourseHandler(waitlistService, eventDispatcher);
        MessageDispatcher dispatcher = new MessageDispatcher(courseHandler);

        Server server = new Server(registry, dispatcher, eventDispatcher, waitlistScheduler);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "vcampus-shutdown"));

        server.start();
    }

    /**
     * 在数据库与后台服务初始化之前，将 JVM 默认时区设为 UTC。
     */
    public static void configureTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
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
