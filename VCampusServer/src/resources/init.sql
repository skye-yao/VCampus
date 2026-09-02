-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `virtual_campus` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `virtual_campus`;

-- 用户表
CREATE TABLE IF NOT EXISTS `tbl_user` (
    `UID` VARCHAR(32) NOT NULL COMMENT '一卡通号',
    `name` VARCHAR(50) NOT NULL COMMENT '姓名',
    `gender` VARCHAR(10) DEFAULT '男' COMMENT '性别',
    `password` VARCHAR(128) NOT NULL COMMENT '密码哈希值',
    `salt` VARCHAR(64) NOT NULL COMMENT '密码盐值',
    `role` INT NOT NULL DEFAULT 2 COMMENT '角色: 0-管理员, 1-教师, 2-学生',
    `college` VARCHAR(100) DEFAULT '' COMMENT '学院',
    `major` VARCHAR(100) DEFAULT '' COMMENT '专业/职称/职务',
    `phone` VARCHAR(20) DEFAULT '' COMMENT '电话',
    `email` VARCHAR(100) DEFAULT '' COMMENT '邮箱',
    `avatar` LONGTEXT DEFAULT NULL COMMENT '头像图片Base64编码',
    `balance` DECIMAL(10,2) DEFAULT 0.00 COMMENT '一卡通余额',
    `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`UID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本信息表';

-- 兼容已经用旧版脚本创建的数据库：CREATE TABLE IF NOT EXISTS不会自动补字段。
SET @add_avatar_sql = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tbl_user' AND COLUMN_NAME = 'avatar') = 0,
    'ALTER TABLE `tbl_user` ADD COLUMN `avatar` LONGTEXT DEFAULT NULL AFTER `email`',
    'SELECT 1'
);
PREPARE add_avatar_stmt FROM @add_avatar_sql;
EXECUTE add_avatar_stmt;
DEALLOCATE PREPARE add_avatar_stmt;

-- 插入默认测试数据（明文密码统一为 123456）
-- salt: 'dGVzdHNhbHQxMjM0NTY='
-- hash: PasswordUtil.hashPassword("123456", "dGVzdHNhbHQxMjM0NTY=")
INSERT INTO `tbl_user` (`UID`, `name`, `gender`, `password`, `salt`, `role`, `college`, `major`, `phone`, `email`, `balance`)
VALUES 
('213242789', '张三', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 328.50),
('admin', '系统管理员', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '网络信息中心', '系统管理', '13900139000', 'admin@seu.edu.cn', 888.88),
('teacher01', '李老师', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 500.00)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- ==================== 商店模块 ====================
CREATE TABLE IF NOT EXISTS `tbl_product` (
    `product_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '商品主键',
    `product_name` VARCHAR(100) NOT NULL COMMENT '商品名称',
    `description` VARCHAR(500) DEFAULT '' COMMENT '商品说明',
    `category` VARCHAR(50) NOT NULL COMMENT '商品分类',
    `price` DECIMAL(10,2) NOT NULL COMMENT '当前售价',
    `stock` INT NOT NULL DEFAULT 0 COMMENT '可用库存',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ON_SALE' COMMENT 'ON_SALE/OFF_SALE',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`product_id`),
    UNIQUE KEY `uk_product_name` (`product_name`),
    INDEX `idx_product_category_status` (`category`, `status`),
    CONSTRAINT `chk_product_price` CHECK (`price` > 0),
    CONSTRAINT `chk_product_stock` CHECK (`stock` >= 0),
    CONSTRAINT `chk_product_category` CHECK (`category` IN ('文具','教材资料','校园纪念品','生活用品')),
    CONSTRAINT `chk_product_status` CHECK (`status` IN ('ON_SALE','OFF_SALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店商品表';

CREATE TABLE IF NOT EXISTS `tbl_cart_item` (
    `cart_item_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '购物车项目主键',
    `user_id` VARCHAR(32) NOT NULL COMMENT '用户一卡通号',
    `product_id` BIGINT NOT NULL COMMENT '商品编号',
    `quantity` INT NOT NULL COMMENT '购买数量',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`cart_item_id`),
    UNIQUE KEY `uk_cart_user_product` (`user_id`, `product_id`),
    CONSTRAINT `fk_cart_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_cart_product` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`product_id`),
    CONSTRAINT `chk_cart_quantity` CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户购物车表';

CREATE TABLE IF NOT EXISTS `tbl_shop_order` (
    `order_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单主键',
    `order_no` VARCHAR(40) NOT NULL COMMENT '对外订单号',
    `user_id` VARCHAR(32) NOT NULL COMMENT '下单用户',
    `total_amount` DECIMAL(10,2) NOT NULL COMMENT '服务器计算的订单金额',
    `status` VARCHAR(20) NOT NULL DEFAULT 'WAIT_PAY' COMMENT '订单状态',
    `payment_transaction_no` VARCHAR(50) DEFAULT NULL COMMENT '银行支付流水号',
    `expires_at` DATETIME NOT NULL COMMENT '支付截止时间',
    `paid_at` DATETIME DEFAULT NULL,
    `cancelled_at` DATETIME DEFAULT NULL,
    `version` INT NOT NULL DEFAULT 0 COMMENT '并发控制版本',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    UNIQUE KEY `uk_shop_order_no` (`order_no`),
    UNIQUE KEY `uk_shop_payment_transaction` (`payment_transaction_no`),
    INDEX `idx_shop_order_user_status_time` (`user_id`, `status`, `created_at`),
    CONSTRAINT `fk_shop_order_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `chk_shop_order_amount` CHECK (`total_amount` >= 0),
    CONSTRAINT `chk_shop_order_status` CHECK (`status` IN
        ('WAIT_PAY','PAID','CANCELLED','EXPIRED','REFUNDING','REFUNDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店订单表';

CREATE TABLE IF NOT EXISTS `tbl_order_item` (
    `order_item_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '订单明细主键',
    `order_id` BIGINT NOT NULL,
    `product_id` BIGINT NOT NULL,
    `product_name_snapshot` VARCHAR(100) NOT NULL COMMENT '下单时商品名称',
    `unit_price` DECIMAL(10,2) NOT NULL COMMENT '下单时成交单价',
    `quantity` INT NOT NULL,
    `subtotal` DECIMAL(10,2) NOT NULL,
    PRIMARY KEY (`order_item_id`),
    INDEX `idx_order_item_order` (`order_id`),
    CONSTRAINT `fk_order_item_order` FOREIGN KEY (`order_id`) REFERENCES `tbl_shop_order` (`order_id`),
    CONSTRAINT `fk_order_item_product` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`product_id`),
    CONSTRAINT `chk_order_item_price` CHECK (`unit_price` > 0),
    CONSTRAINT `chk_order_item_quantity` CHECK (`quantity` > 0),
    CONSTRAINT `chk_order_item_subtotal` CHECK (`subtotal` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店订单明细表';

CREATE TABLE IF NOT EXISTS `tbl_shop_refund` (
    `refund_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '退款主键',
    `refund_no` VARCHAR(40) NOT NULL COMMENT '退款业务号',
    `order_id` BIGINT NOT NULL COMMENT '原订单',
    `user_id` VARCHAR(32) NOT NULL COMMENT '申请用户',
    `refund_amount` DECIMAL(10,2) NOT NULL COMMENT '整单退款金额',
    `reason` VARCHAR(300) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    `original_transaction_no` VARCHAR(50) DEFAULT NULL,
    `previous_order_status` VARCHAR(20) NOT NULL DEFAULT 'PAID' COMMENT '申请退款前订单状态',
    `refund_transaction_no` VARCHAR(50) DEFAULT NULL,
    `reviewer_id` VARCHAR(32) DEFAULT NULL,
    `review_comment` VARCHAR(300) DEFAULT NULL,
    `requested_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reviewed_at` DATETIME DEFAULT NULL,
    PRIMARY KEY (`refund_id`),
    UNIQUE KEY `uk_shop_refund_no` (`refund_no`),
    UNIQUE KEY `uk_shop_refund_order` (`order_id`),
    UNIQUE KEY `uk_shop_refund_transaction` (`refund_transaction_no`),
    CONSTRAINT `fk_shop_refund_order` FOREIGN KEY (`order_id`) REFERENCES `tbl_shop_order` (`order_id`),
    CONSTRAINT `fk_shop_refund_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_shop_refund_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `chk_shop_refund_amount` CHECK (`refund_amount` > 0),
    CONSTRAINT `chk_shop_refund_status` CHECK (`status` IN
        ('APPLIED','APPROVED','REJECTED','PROCESSING','SUCCESS','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店退款申请表';

-- 兼容已创建的退款表，并修复旧版“申请退款后订单仍显示已支付”的数据。
SET @add_refund_previous_status = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tbl_shop_refund' AND COLUMN_NAME='previous_order_status')=0,
    'ALTER TABLE `tbl_shop_refund` ADD COLUMN `previous_order_status` VARCHAR(20) NOT NULL DEFAULT ''PAID'' AFTER `original_transaction_no`',
    'SELECT 1'
);
PREPARE add_refund_previous_status_stmt FROM @add_refund_previous_status;
EXECUTE add_refund_previous_status_stmt;
DEALLOCATE PREPARE add_refund_previous_status_stmt;

UPDATE `tbl_shop_order` o
JOIN `tbl_shop_refund` r ON r.order_id=o.order_id AND r.status='APPLIED'
SET o.status='REFUNDING',o.version=o.version+1,o.updated_at=CURRENT_TIMESTAMP
WHERE o.status IN ('PAID','PROCESSING','COMPLETED');

-- 兼容旧版：取消“处理中/已完成”后，其余旧记录恢复为“已支付”。
UPDATE `tbl_shop_order`
SET `status`='PAID', `version`=`version`+1, `updated_at`=CURRENT_TIMESTAMP
WHERE `status` IN ('PROCESSING','COMPLETED');

CREATE TABLE IF NOT EXISTS `tbl_shop_operation_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT,
    `operator_id` VARCHAR(32) NOT NULL,
    `action` VARCHAR(50) NOT NULL,
    `target_type` VARCHAR(30) NOT NULL,
    `target_id` BIGINT NOT NULL,
    `before_data` MEDIUMTEXT DEFAULT NULL,
    `after_data` MEDIUMTEXT DEFAULT NULL,
    `reason` VARCHAR(300) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`log_id`),
    INDEX `idx_shop_log_operator_time` (`operator_id`, `created_at`),
    CONSTRAINT `fk_shop_log_operator` FOREIGN KEY (`operator_id`) REFERENCES `tbl_user` (`UID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店后台操作日志表';

-- 商店演示商品。重复执行脚本不会重复插入。
INSERT INTO `tbl_product`
(`product_id`,`product_name`,`description`,`category`,`price`,`stock`,`status`)
VALUES
(1,'东大纪念笔记本','校园主题硬壳笔记本','校园纪念品',18.80,60,'ON_SALE'),
(2,'黑色中性笔套装','0.5mm黑色中性笔，5支装','文具',9.90,120,'ON_SALE'),
(3,'Java程序设计参考书','适合课程实训的Java基础参考资料','教材资料',56.00,30,'ON_SALE'),
(4,'校园帆布袋','简洁耐用的校园纪念帆布袋','生活用品',29.90,45,'ON_SALE'),
(5,'东大校徽徽章','金属烤漆校园纪念徽章','校园纪念品',12.00,80,'ON_SALE'),
(6,'A4横线活页本','80页可替换内芯课堂笔记本','文具',15.50,75,'ON_SALE'),
(7,'数据结构课程辅导书','包含基础算法讲解和课程练习','教材资料',48.00,35,'ON_SALE'),
(8,'便携折叠雨伞','校园生活便携晴雨两用伞','生活用品',39.90,40,'ON_SALE'),
(9,'校园马克杯','陶瓷校园建筑图案马克杯','校园纪念品',32.00,50,'ON_SALE'),
(10,'荧光笔六色套装','适合教材标记的柔和色荧光笔','文具',16.80,90,'ON_SALE'),
(11,'计算机网络实验指导','配套网络课程实验与复习','教材资料',42.00,28,'ON_SALE'),
(12,'USB桌面小风扇','宿舍桌面静音三档小风扇','生活用品',49.00,32,'ON_SALE')
ON DUPLICATE KEY UPDATE `product_name`=VALUES(`product_name`);

-- ==================== 校园银行模块（基础版） ====================
CREATE TABLE IF NOT EXISTS `tbl_bank_account` (
    `account_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '虚拟账户主键',
    `user_id` VARCHAR(32) NOT NULL COMMENT '所属用户一卡通号',
    `balance` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '账户余额',
    `payment_password_hash` VARCHAR(128) DEFAULT NULL COMMENT '支付密码摘要',
    `payment_password_salt` VARCHAR(64) DEFAULT NULL COMMENT '支付密码盐值',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/LOCKED/RESET_REQUIRED',
    `failed_attempts` INT NOT NULL DEFAULT 0 COMMENT '连续验证失败次数',
    `version` INT NOT NULL DEFAULT 0 COMMENT '并发控制版本',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`account_id`),
    UNIQUE KEY `uk_bank_account_user` (`user_id`),
    CONSTRAINT `fk_bank_account_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `chk_bank_balance` CHECK (`balance` >= 0),
    CONSTRAINT `chk_bank_status` CHECK (`status` IN ('ACTIVE','LOCKED','RESET_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园银行虚拟账户表';

CREATE TABLE IF NOT EXISTS `tbl_bank_transaction` (
    `transaction_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水主键',
    `transaction_no` VARCHAR(50) NOT NULL COMMENT '对外交易流水号',
    `account_id` BIGINT NOT NULL COMMENT '本方账户',
    `counterparty_user_id` VARCHAR(32) DEFAULT NULL COMMENT '对方一卡通号',
    `transaction_type` VARCHAR(30) NOT NULL COMMENT '交易类型',
    `amount` DECIMAL(12,2) NOT NULL COMMENT '带方向金额，收入为正、支出为负',
    `balance_after` DECIMAL(12,2) NOT NULL COMMENT '交易后余额',
    `related_order_id` BIGINT DEFAULT NULL COMMENT '关联商店订单',
    `request_id` VARCHAR(64) DEFAULT NULL COMMENT '幂等请求编号',
    `remark` VARCHAR(200) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`transaction_id`),
    UNIQUE KEY `uk_bank_transaction_no` (`transaction_no`),
    UNIQUE KEY `uk_bank_request_id` (`request_id`),
    INDEX `idx_bank_tx_account_time` (`account_id`,`created_at`),
    INDEX `idx_bank_tx_order` (`related_order_id`),
    CONSTRAINT `fk_bank_tx_account` FOREIGN KEY (`account_id`) REFERENCES `tbl_bank_account` (`account_id`),
    CONSTRAINT `fk_bank_tx_counterparty` FOREIGN KEY (`counterparty_user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_bank_tx_shop_order` FOREIGN KEY (`related_order_id`) REFERENCES `tbl_shop_order` (`order_id`),
    CONSTRAINT `chk_bank_tx_amount` CHECK (`amount` <> 0),
    CONSTRAINT `chk_bank_tx_balance` CHECK (`balance_after` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园银行交易流水表';

CREATE TABLE IF NOT EXISTS `tbl_finance_bill` (
    `bill_id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` VARCHAR(32) NOT NULL,
    `bill_type` VARCHAR(30) NOT NULL COMMENT 'TUITION/ACCOMMODATION/OTHER',
    `title` VARCHAR(100) NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'UNPAID',
    `due_date` DATE NOT NULL,
    `payment_transaction_no` VARCHAR(50) DEFAULT NULL,
    `paid_at` DATETIME DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`bill_id`),
    UNIQUE KEY `uk_finance_bill_demo` (`user_id`,`title`),
    CONSTRAINT `fk_finance_bill_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_finance_bill_tx` FOREIGN KEY (`payment_transaction_no`) REFERENCES `tbl_bank_transaction` (`transaction_no`),
    CONSTRAINT `chk_finance_bill_amount` CHECK (`amount` > 0),
    CONSTRAINT `chk_finance_bill_status` CHECK (`status` IN ('UNPAID','PAID','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园财务账单表';

CREATE TABLE IF NOT EXISTS `tbl_finance_reimbursement` (
    `reimbursement_id` BIGINT NOT NULL AUTO_INCREMENT,
    `applicant_id` VARCHAR(32) NOT NULL,
    `title` VARCHAR(100) NOT NULL,
    `amount` DECIMAL(12,2) NOT NULL,
    `reason` VARCHAR(500) NOT NULL,
    `status` VARCHAR(20) NOT NULL DEFAULT 'APPLIED',
    `reviewer_id` VARCHAR(32) DEFAULT NULL,
    `review_comment` VARCHAR(300) DEFAULT NULL,
    `payment_transaction_no` VARCHAR(50) DEFAULT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `reviewed_at` DATETIME DEFAULT NULL,
    PRIMARY KEY (`reimbursement_id`),
    CONSTRAINT `fk_reimbursement_applicant` FOREIGN KEY (`applicant_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_reimbursement_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `tbl_user` (`UID`),
    CONSTRAINT `fk_reimbursement_tx` FOREIGN KEY (`payment_transaction_no`) REFERENCES `tbl_bank_transaction` (`transaction_no`),
    CONSTRAINT `chk_reimbursement_amount` CHECK (`amount` > 0),
    CONSTRAINT `chk_reimbursement_status` CHECK (`status` IN ('APPLIED','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='校园财务报销申请表';

-- 演示账户默认支付密码均为123456；仅供课程演示，正式环境必须由用户首次设置。
INSERT INTO `tbl_bank_account`
(`user_id`,`balance`,`payment_password_hash`,`payment_password_salt`,`status`)
VALUES
('213242789',10000.00,'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=','dGVzdHNhbHQxMjM0NTY=','ACTIVE'),
('teacher01',8000.00,'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=','dGVzdHNhbHQxMjM0NTY=','ACTIVE'),
('admin',50000.00,'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=','dGVzdHNhbHQxMjM0NTY=','ACTIVE')
ON DUPLICATE KEY UPDATE `user_id`=VALUES(`user_id`);

INSERT INTO `tbl_finance_bill` (`user_id`,`bill_type`,`title`,`amount`,`status`,`due_date`)
VALUES
('213242789','TUITION','2026学年学费',5200.00,'UNPAID','2026-12-31'),
('213242789','ACCOMMODATION','2026学年住宿费',1200.00,'UNPAID','2026-12-31'),
('teacher01','OTHER','校园停车服务费',200.00,'UNPAID','2026-12-31')
ON DUPLICATE KEY UPDATE `user_id`=VALUES(`user_id`);

-- 初始余额也形成正式流水，便于演示“余额有来源”。
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`transaction_type`,`amount`,`balance_after`,`request_id`,`remark`)
SELECT CONCAT('INIT-',a.user_id),a.account_id,'INITIAL_BALANCE',a.balance,a.balance,
       CONCAT('INIT-',a.user_id),'课程演示账户初始资金'
FROM `tbl_bank_account` a
WHERE NOT EXISTS (
    SELECT 1 FROM `tbl_bank_transaction` t
    WHERE t.account_id=a.account_id AND t.transaction_type='INITIAL_BALANCE'
);
