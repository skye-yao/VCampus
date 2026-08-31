package util;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * 数据库工具类
 *
 * <p>负责数据库连接的建立、关闭和资源释放。
 *
 * <p><b>使用示例：</b>
 * <pre>
 * // 获取连接
 * Connection conn = DBUtil.getConnection();
 *
 * // 执行操作...
 *
 * // 关闭连接
 * DBUtil.close(conn, null, null);
 * </pre>
 *
 * @author VirtualCampus 架构组
 * @version 1.0
 */
public class DBUtil {

    /** 数据库驱动类名 */
    private static String driver;

    /** 数据库连接URL */
    private static String url;

    /** 数据库用户名 */
    private static String username;

    /** 数据库密码 */
    private static String password;

    /** 初始化失败原因（非 null 表示初始化未成功） */
    private static String initError;

    /**
     * 静态代码块：加载配置文件，初始化数据库连接参数
     */
    static {
        try {
            // 1. 加载配置文件
            InputStream is = DBUtil.class.getClassLoader()
                    .getResourceAsStream("resources/db.properties");
            Properties props = new Properties();
            props.load(is);

            // 2. 读取配置
            driver = props.getProperty("db.driver");
            url = props.getProperty("db.url");
            username = props.getProperty("db.username");
            password = props.getProperty("db.password");

            // 3. 加载JDBC驱动
            Class.forName(driver);

        } catch (Throwable e) {
            // 初始化失败不直接抛出，否则类初始化失败会以 Error 形式
            // 蔓延到网络层导致连接被静默断开；改为在获取连接时
            // 抛出普通 SQLException，走正常的错误响应流程
            initError = "数据库初始化失败: " + e;
            System.err.println(initError);
        }
    }

    /**
     * 获取数据库连接
     *
     * @return Connection 数据库连接对象
     * @throws SQLException 连接失败时抛出
     */
    public static Connection getConnection() throws SQLException {
        if (initError != null) {
            throw new SQLException(initError);
        }
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 关闭数据库资源（Connection、Statement、ResultSet）
     *
     * @param conn 数据库连接
     * @param stmt 语句对象
     * @param rs   结果集
     */
    public static void close(Connection conn, Statement stmt, ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            if (stmt != null) {
                stmt.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        try {
            if (conn != null) {
                conn.close();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}