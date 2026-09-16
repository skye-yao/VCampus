package util;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 商店与银行模块专用的数据库连接。
 *
 * <p>DBUtil 会把每条连接的会话时区统一设成 UTC（课程模块按 UTC 计算），
 * 这会让本模块的 NOW()/CURRENT_TIMESTAMP 写进 DATETIME 列时比北京时间早 8 小时，
 * 也会让 TIMESTAMP 列在客户端显示时少 8 小时；订单超时判断还会因此永远不成立。
 * 商店与银行模块不使用 UTC，因此这里在拿到连接后把会话时区改回东八区，
 * 使库里的时间与 Java 侧的 LocalDateTime.now() 保持一致。
 */
public final class LocalTimeConnection {

    /** 本地时区偏移，用于数据库会话渲染 NOW() 与 TIMESTAMP 列。 */
    private static final String LOCAL_ZONE = "+08:00";

    private LocalTimeConnection() { }

    /** 取出连接，并把该连接的会话时区设为东八区。 */
    public static Connection getConnection() throws SQLException {
        Connection connection = DBUtil.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '" + LOCAL_ZONE + "'");
            return connection;
        } catch (SQLException setupError) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                setupError.addSuppressed(closeError);
            }
            throw setupError;
        }
    }
}
