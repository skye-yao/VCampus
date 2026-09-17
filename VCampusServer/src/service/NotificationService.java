package service;

import util.DBUtil;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

/**
 * 校园统一系统事件通知服务
 */
public class NotificationService {

    private static volatile boolean schemaReady = false;

    public static void ensureSchema(Connection c) {
        if (schemaReady) return;
        try (var s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS tbl_system_notification (
                    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
                    recipient_uid VARCHAR(32) NOT NULL,
                    category VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
                    title VARCHAR(128) NOT NULL,
                    content VARCHAR(512) NOT NULL,
                    link_action VARCHAR(64) DEFAULT NULL,
                    is_read TINYINT(1) NOT NULL DEFAULT 0,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    KEY ix_notif_recipient (recipient_uid, is_read, created_at)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """);
            schemaReady = true;
        } catch (SQLException ignored) {
        }
    }

    /**
     * 在指定数据库事务连接中写入一条系统事件通知
     */
    public static void notify(Connection c, String recipientUid, String category,
                              String title, String content, String linkAction) throws SQLException {
        if (recipientUid == null || recipientUid.isBlank()) return;
        ensureSchema(c);
        if (category == null || category.isBlank()) category = "SYSTEM";
        if (title == null || title.isBlank()) title = "系统通知";
        if (content == null) content = "";
        String sql = """
            INSERT INTO tbl_system_notification(recipient_uid, category, title, content, link_action, is_read, created_at)
            VALUES(?, ?, ?, ?, ?, 0, CURRENT_TIMESTAMP)
        """;
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, recipientUid.trim());
            s.setString(2, category.trim().toUpperCase());
            s.setString(3, title.trim());
            s.setString(4, content.trim());
            s.setString(5, linkAction != null && !linkAction.isBlank() ? linkAction.trim() : null);
            s.executeUpdate();
        }
    }

    /**
     * 独立事务写入通知（安全捕获异常，不阻塞主流程）
     */
    public static void notifyAsync(String recipientUid, String category,
                                   String title, String content, String linkAction) {
        if (recipientUid == null || recipientUid.isBlank()) return;
        new Thread(() -> {
            try (Connection c = DBUtil.getConnection()) {
                notify(c, recipientUid, category, title, content, linkAction);
            } catch (Exception e) {
                System.err.println("[NotificationService] 写入通知失败: " + e.getMessage());
            }
        }, "notify-async").start();
    }

    public Map<String, Object> execute(Connection c, String me, String action, Map<String, Object> data) throws SQLException {
        ensureSchema(c);
        Map<String, Object> out = new LinkedHashMap<>();
        switch (action) {
            case "LIST" -> {
                boolean unreadOnly = Boolean.parseBoolean(String.valueOf(data.getOrDefault("unreadOnly", "false")));
                int limit = 20;
                if (data.containsKey("limit")) {
                    try { limit = Math.min(100, Math.max(1, Integer.parseInt(data.get("limit").toString()))); } catch (Exception ignored) {}
                }
                int offset = 0;
                if (data.containsKey("offset")) {
                    try { offset = Math.max(0, Integer.parseInt(data.get("offset").toString())); } catch (Exception ignored) {}
                }

                String sql = unreadOnly
                    ? """
                        SELECT id, recipient_uid AS recipientUid, category, title, content, link_action AS linkAction,
                               is_read AS isRead, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                        FROM tbl_system_notification
                        WHERE recipient_uid=? AND is_read=0
                        ORDER BY created_at DESC, id DESC
                        LIMIT ? OFFSET ?
                      """
                    : """
                        SELECT id, recipient_uid AS recipientUid, category, title, content, link_action AS linkAction,
                               is_read AS isRead, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
                        FROM tbl_system_notification
                        WHERE recipient_uid=?
                        ORDER BY created_at DESC, id DESC
                        LIMIT ? OFFSET ?
                      """;

                var list = rows(c, sql, me, limit, offset);
                long unread = getUnreadCount(c, me);
                out.put("notifications", list);
                out.put("unreadCount", unread);
            }
            case "UNREAD_COUNT" -> {
                out.put("unreadCount", getUnreadCount(c, me));
            }
            case "MARK_READ" -> {
                long id = parseLong(data.get("id"));
                int updated = update(c, "UPDATE tbl_system_notification SET is_read=1 WHERE id=? AND recipient_uid=?", id, me);
                out.put("success", updated > 0);
            }
            case "MARK_ALL_READ" -> {
                int updated = update(c, "UPDATE tbl_system_notification SET is_read=1 WHERE recipient_uid=? AND is_read=0", me);
                out.put("success", true);
                out.put("count", updated);
            }
            case "DELETE" -> {
                long id = parseLong(data.get("id"));
                int deleted = update(c, "DELETE FROM tbl_system_notification WHERE id=? AND recipient_uid=?", id, me);
                out.put("success", deleted > 0);
            }
            default -> throw new IllegalArgumentException("未知通知服务指令: " + action);
        }
        return out;
    }

    public static long getUnreadCount(Connection c, String uid) throws SQLException {
        var r = rows(c, "SELECT COUNT(*) AS n FROM tbl_system_notification WHERE recipient_uid=? AND is_read=0", uid);
        return r.isEmpty() ? 0 : ((Number) r.get(0).get("n")).longValue();
    }

    private static long parseLong(Object obj) {
        if (obj == null) throw new IllegalArgumentException("缺少 ID 参数");
        try {
            return Long.parseLong(obj.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效的 ID 参数");
        }
    }

    private static int update(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            return s.executeUpdate();
        }
    }

    private static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (var r = s.executeQuery()) {
                var list = new ArrayList<Map<String, Object>>();
                var meta = r.getMetaData();
                while (r.next()) {
                    var row = new LinkedHashMap<String, Object>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), r.getObject(i));
                    }
                    list.add(row);
                }
                return list;
            }
        }
    }
}
