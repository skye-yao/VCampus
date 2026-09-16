-- 商店模块：新增“食品”分类 + 商品评价表
-- 前提：基础库已建好（已运行过 ../init.sql）。本文件可重复执行。
-- 手工执行前请先选库：USE `virtual_campus`;

-- 1) 商品分类约束加入“食品”
SET @drop_check = IF((SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
        WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tbl_product'
          AND CONSTRAINT_NAME = 'chk_product_category' AND CONSTRAINT_TYPE = 'CHECK') > 0,
        'ALTER TABLE `tbl_product` DROP CHECK `chk_product_category`', 'SELECT 1');
PREPARE stmt FROM @drop_check;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE `tbl_product`
    ADD CONSTRAINT `chk_product_category`
    CHECK (`category` IN ('文具','教材资料','校园纪念品','生活用品','食品'));

-- 2) 商品评价表
CREATE TABLE IF NOT EXISTS `tbl_product_review` (
    `review_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '评价主键',
    `product_id` BIGINT NOT NULL COMMENT '商品编号',
    `user_id` VARCHAR(32) NOT NULL COMMENT '评价用户一卡通号',
    `rating` TINYINT NOT NULL COMMENT '评分 1-5',
    `content` VARCHAR(500) NOT NULL COMMENT '评价内容',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发表时间',
    PRIMARY KEY (`review_id`),
    UNIQUE KEY `uk_product_review_user` (`product_id`,`user_id`),
    INDEX `idx_product_review_product` (`product_id`,`created_at`),
    CONSTRAINT `fk_product_review_product` FOREIGN KEY (`product_id`) REFERENCES `tbl_product` (`product_id`) ON DELETE CASCADE,
    CONSTRAINT `fk_product_review_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`) ON DELETE CASCADE,
    CONSTRAINT `chk_product_review_rating` CHECK (`rating` BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商品评价表';
