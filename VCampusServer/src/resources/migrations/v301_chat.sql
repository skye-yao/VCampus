-- 好友与文字私聊；可重复执行，不修改已有业务数据。手工执行前选择 virtual_campus。
CREATE TABLE IF NOT EXISTS tbl_chat_friend (
 user_low VARCHAR(32) NOT NULL,
 user_high VARCHAR(32) NOT NULL,
 requester VARCHAR(32) NOT NULL,
 status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 PRIMARY KEY(user_low,user_high)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS tbl_chat_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 sender VARCHAR(32) NOT NULL,
 recipient VARCHAR(32) NOT NULL,
 client_id VARCHAR(36) NOT NULL,
 content TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 read_at TIMESTAMP NULL,
 UNIQUE KEY uq_chat_retry(sender,client_id),
 KEY ix_chat_inbox(recipient,read_at,id),
 KEY ix_chat_history(sender,recipient,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS tbl_chat_group (
 group_id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 owner_uid VARCHAR(32) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 KEY ix_chat_group_owner(owner_uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS tbl_chat_group_member (
 group_id BIGINT NOT NULL,
 uid VARCHAR(32) NOT NULL,
 role VARCHAR(12) NOT NULL DEFAULT 'MEMBER',
 last_read_id BIGINT NOT NULL DEFAULT 0,
 joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(group_id,uid),
 KEY ix_chat_group_member_uid(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS tbl_chat_group_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 group_id BIGINT NOT NULL,
 sender VARCHAR(32) NOT NULL,
 client_id VARCHAR(36) NOT NULL,
 content TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uq_group_msg_retry(sender,client_id),
 KEY ix_group_msg_history(group_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
