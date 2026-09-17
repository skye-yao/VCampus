-- 校园统一消息通知中心（系统事件通知）；可重复执行，不修改已有业务数据。
CREATE TABLE IF NOT EXISTS tbl_system_notification (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    recipient_uid VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL DEFAULT 'SYSTEM' COMMENT 'CHAT, BANK, REVIEW, LIBRARY, SHOP, SYSTEM',
    title VARCHAR(128) NOT NULL,
    content VARCHAR(512) NOT NULL,
    link_action VARCHAR(64) DEFAULT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY ix_notif_recipient (recipient_uid, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
