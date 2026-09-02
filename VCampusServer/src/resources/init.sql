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
('213242789', '张三', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 32850),
('admin', '系统管理员', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '网络信息中心', '系统管理', '13900139000', 'admin@seu.edu.cn', 88888),
('teacher01', '李老师', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 50000)
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
    INDEX `idx_product_name` (`product_name`),
    INDEX `idx_product_category_status` (`category`, `status`),
    CONSTRAINT `chk_product_price` CHECK (`price` > 0),
    CONSTRAINT `chk_product_stock` CHECK (`stock` >= 0),
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
        ('WAIT_PAY','PAID','PROCESSING','COMPLETED','CANCELLED','EXPIRED','REFUNDING','REFUNDED'))
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
(4,'校园帆布袋','简洁耐用的校园纪念帆布袋','生活用品',29.90,45,'ON_SALE')
ON DUPLICATE KEY UPDATE `product_name`=VALUES(`product_name`);
