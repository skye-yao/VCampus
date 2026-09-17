package service;

import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import util.DBUtil;

public final class NotificationServiceTest {
    private static final NotificationService service = new NotificationService();
    private static Connection c;

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        try (Connection connection = DBUtil.getConnection()) {
            c = connection;
            try (var s = c.createStatement()) {
                s.execute("CREATE TEMPORARY TABLE tbl_system_notification (" +
                        "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, " +
                        "recipient_uid VARCHAR(32) NOT NULL, " +
                        "category VARCHAR(32) NOT NULL DEFAULT 'SYSTEM', " +
                        "title VARCHAR(128) NOT NULL, " +
                        "content VARCHAR(512) NOT NULL, " +
                        "link_action VARCHAR(64) DEFAULT NULL, " +
                        "is_read TINYINT(1) NOT NULL DEFAULT 0, " +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            }

            // 1. 发送通知
            NotificationService.notify(c, "user01", "CHAT", "移出群聊通知", "您已被移出群聊「课设研讨组」", "CHAT");
            NotificationService.notify(c, "user01", "BANK", "转账到账提醒", "「张三 (213242790)」向您转账 50.00 元", "BANK");
            NotificationService.notify(c, "user02", "BANK", "转账到账提醒", "「李四 (213242791)」向您转账 100.00 元", "BANK");

            // 2. 统计未读
            check(NotificationService.getUnreadCount(c, "user01") == 2, "user01 should have 2 unread notices");
            check(NotificationService.getUnreadCount(c, "user02") == 1, "user02 should have 1 unread notice");
            check(NotificationService.getUnreadCount(c, "user03") == 0, "user03 should have 0 unread notices");

            // 3. 列表查询
            var listResult = service.execute(c, "user01", "LIST", Map.of("unreadOnly", true));
            List<Map<String, Object>> notifs = (List<Map<String, Object>>) listResult.get("notifications");
            check(notifs.size() == 2, "user01 should see 2 notifications");
            check("转账到账提醒".equals(notifs.get(0).get("title")), "latest notification first");
            check("BANK".equals(notifs.get(0).get("category")), "category preserved");
            check("BANK".equals(notifs.get(0).get("linkAction")), "linkAction preserved");

            // 4. 单条标记已读
            long firstId = ((Number) notifs.get(0).get("id")).longValue();
            service.execute(c, "user01", "MARK_READ", Map.of("id", firstId));
            check(NotificationService.getUnreadCount(c, "user01") == 1, "user01 unread count after 1 read");

            // 5. 一键全部已读
            service.execute(c, "user01", "MARK_ALL_READ", Map.of());
            check(NotificationService.getUnreadCount(c, "user01") == 0, "user01 unread count after mark all read");

            // 6. 查询全部历史 (包含已读)
            var allList = (List<Map<String, Object>>) service.execute(c, "user01", "LIST", Map.of("unreadOnly", false)).get("notifications");
            check(allList.size() == 2, "user01 still has 2 history notifications");

            // 7. 删除单条
            service.execute(c, "user01", "DELETE", Map.of("id", firstId));
            var afterDelete = (List<Map<String, Object>>) service.execute(c, "user01", "LIST", Map.of("unreadOnly", false)).get("notifications");
            check(afterDelete.size() == 1, "user01 should have 1 notification left after delete");

            System.out.println("PASS: NotificationServiceTest (notify, unreadCount, list, markRead, markAllRead, delete)");
        }
    }
}
