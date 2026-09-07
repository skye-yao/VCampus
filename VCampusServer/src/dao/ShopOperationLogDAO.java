package dao;

import entity.ShopOperationLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** 商店后台操作审计数据访问。 */
public class ShopOperationLogDAO {
    public void insert(Connection conn, String operatorId, String action, String targetType,
                       long targetId, String beforeData, String afterData, String reason) throws SQLException {
        String sql = "INSERT INTO tbl_shop_operation_log" +
                "(operator_id,action,target_type,target_id,before_data,after_data,reason) VALUES(?,?,?,?,?,?,?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, operatorId);
            stmt.setString(2, action);
            stmt.setString(3, targetType);
            stmt.setLong(4, targetId);
            stmt.setString(5, beforeData);
            stmt.setString(6, afterData);
            stmt.setString(7, reason);
            stmt.executeUpdate();
        }
    }

    public List<ShopOperationLog> findLatest(int limit) throws SQLException {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT * FROM tbl_shop_operation_log ORDER BY log_id DESC LIMIT ?";
        try (Connection conn = util.DBUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, safeLimit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<ShopOperationLog> result = new ArrayList<>();
                while (rs.next()) {
                    ShopOperationLog log = new ShopOperationLog();
                    log.setLogId(rs.getLong("log_id"));
                    log.setOperatorId(rs.getString("operator_id"));
                    log.setAction(rs.getString("action"));
                    log.setTargetType(rs.getString("target_type"));
                    log.setTargetId(rs.getLong("target_id"));
                    log.setBeforeData(rs.getString("before_data"));
                    log.setAfterData(rs.getString("after_data"));
                    log.setReason(rs.getString("reason"));
                    log.setCreatedAt(String.valueOf(rs.getTimestamp("created_at")));
                    result.add(log);
                }
                return result;
            }
        }
    }
}
