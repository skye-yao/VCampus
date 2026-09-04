-- 创建数据库（如果不存在）
CREATE DATABASE IF NOT EXISTS `virtual_campus` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE `virtual_campus`;

-- 用户表
CREATE TABLE IF NOT EXISTS `tbl_user` (
    `uid` VARCHAR(32) NOT NULL COMMENT '一卡通号',
    `name` VARCHAR(50) NOT NULL COMMENT '姓名',
    `gender` VARCHAR(10) DEFAULT '男' COMMENT '性别',
    `password` VARCHAR(128) NOT NULL COMMENT '密码哈希值',
    `salt` VARCHAR(64) NOT NULL COMMENT '密码盐值',
    `role` INT NOT NULL DEFAULT 2 COMMENT '角色: 0-管理员, 1-教师, 2-学生',
    `college` VARCHAR(100) DEFAULT '' COMMENT '学院',
    `major` VARCHAR(100) DEFAULT '' COMMENT '专业/职称/职务',
    `phone` VARCHAR(20) DEFAULT '' COMMENT '电话',
    `email` VARCHAR(100) DEFAULT '' COMMENT '邮箱',
    `balance` DECIMAL(10,2) DEFAULT 0.00 COMMENT '一卡通余额',
    `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户基本信息表';

-- 插入默认测试数据（明文密码统一为 123456）
-- salt: 'dGVzdHNhbHQxMjM0NTY='
-- hash: PasswordUtil.hashPassword("123456", "dGVzdHNhbHQxMjM0NTY=")
INSERT INTO `tbl_user` (`uid`, `name`, `gender`, `password`, `salt`, `role`, `college`, `major`, `phone`, `email`, `balance`)
VALUES 
('213242789', '张三', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 32850),
('admin', '系统管理员', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '网络信息中心', '系统管理', '13900139000', 'admin@seu.edu.cn', 88888),
('teacher01', '李老师', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 50000)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);


-- ============================================================
-- 1. 图书表 tblBook
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblBook` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '图书编号',
    `isbn` VARCHAR(20) NOT NULL COMMENT 'ISBN编号',
    `name` VARCHAR(100) NOT NULL COMMENT '图书名称',
    `author` VARCHAR(100) NOT NULL COMMENT '图书作者',
    `publisher` VARCHAR(100) DEFAULT '' COMMENT '出版社',
    `status` INT NOT NULL DEFAULT 0 COMMENT '状态: 0-可借, 1-已借, 2-预约, 3-遗失',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_isbn` (`isbn`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图书基本信息表';

-- ============================================================
-- 2. 借阅记录表 tblBorrowRecord
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblBorrowRecord` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '借阅记录编号',
    `userid` VARCHAR(32) NOT NULL COMMENT '用户编号(关联tbl_user.uid)',
    `bookid` INT NOT NULL COMMENT '图书编号(关联tblBook.id)',
    `borrowTime` DATETIME NOT NULL COMMENT '借阅时间',
    `returnTime` DATETIME DEFAULT NULL COMMENT '实际归还时间',
    `dueTime` DATETIME NOT NULL COMMENT '最迟归还时间',
    `status` INT NOT NULL DEFAULT 0 COMMENT '借阅状态: 0-借阅中, 1-已归还, 2-逾期',
    PRIMARY KEY (`id`),
    KEY `idx_userid` (`userid`),
    KEY `idx_bookid` (`bookid`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_borrow_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_borrow_book` FOREIGN KEY (`bookid`) REFERENCES `tblBook` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='借阅记录表';

-- ============================================================
-- 3. 预约记录表 tblReservation
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblReservation` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '预约编号',
    `userid` VARCHAR(32) NOT NULL COMMENT '用户编号(关联tbl_user.uid)',
    `bookid` INT NOT NULL COMMENT '图书编号(关联tblBook.id)',
    `reserveTime` DATETIME NOT NULL COMMENT '预约时间',
    `status` INT NOT NULL DEFAULT 0 COMMENT '预约状态: 0-预约中, 1-已取消, 2-已借阅',
    PRIMARY KEY (`id`),
    KEY `idx_userid` (`userid`),
    KEY `idx_bookid` (`bookid`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_reserve_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_reserve_book` FOREIGN KEY (`bookid`) REFERENCES `tblBook` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约记录表';

-- ============================================================
-- 4. 书评表 tblBookReview
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblBookReview` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '书评编号',
    `userid` VARCHAR(32) NOT NULL COMMENT '用户编号(关联tbl_user.uid)',
    `bookid` INT NOT NULL COMMENT '图书编号(关联tblBook.id)',
    `content` TEXT NOT NULL COMMENT '书评内容',
    `createTime` DATETIME NOT NULL COMMENT '发表时间',
    PRIMARY KEY (`id`),
    KEY `idx_userid` (`userid`),
    KEY `idx_bookid` (`bookid`),
    CONSTRAINT `fk_review_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_review_book` FOREIGN KEY (`bookid`) REFERENCES `tblBook` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图书书评表';

-- ============================================================
-- 5. 挂失记录表 tblLossRecord
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblLossRecord` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '挂失记录编号',
    `userid` VARCHAR(32) NOT NULL COMMENT '用户编号(关联tbl_user.uid)',
    `bookid` INT NOT NULL COMMENT '图书编号(关联tblBook.id)',
    `lossTime` DATETIME NOT NULL COMMENT '挂失时间',
    `status` INT NOT NULL DEFAULT 0 COMMENT '挂失状态: 0-挂失中, 1-已解除',
    PRIMARY KEY (`id`),
    KEY `idx_userid` (`userid`),
    KEY `idx_bookid` (`bookid`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_loss_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_loss_book` FOREIGN KEY (`bookid`) REFERENCES `tblBook` (`id`) ON DELETE RESTRICT ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图书挂失记录表';

-- ============================================================
-- 6. 罚款记录表 tblFineRecord
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblFineRecord` (
    `id` INT NOT NULL AUTO_INCREMENT COMMENT '缴费记录编号',
    `userid` VARCHAR(32) NOT NULL COMMENT '用户编号(关联tbl_user.uid)',
    `amount` DECIMAL(10,2) NOT NULL COMMENT '罚金金额',
    `reason` VARCHAR(200) NOT NULL COMMENT '违章原因',
    `status` INT NOT NULL DEFAULT 0 COMMENT '缴费状态: 0-未缴费, 1-已缴费',
    PRIMARY KEY (`id`),
    KEY `idx_userid` (`userid`),
    KEY `idx_status` (`status`),
    CONSTRAINT `fk_fine_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='罚款记录表';

-- 图书馆演示数据已移到 sample_library_data.sql。
-- 正常启动或重新构建不需要重新导入演示数据。
