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

-- CREATE TABLE IF NOT EXISTS 不会为旧表补列；以下迁移可重复执行。
SET @avatar_column_missing = (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tbl_user' AND COLUMN_NAME = 'avatar'
);
SET @avatar_ddl = IF(@avatar_column_missing,
    'ALTER TABLE `tbl_user` ADD COLUMN `avatar` LONGTEXT DEFAULT NULL COMMENT ''头像图片Base64编码'' AFTER `email`',
    'SELECT 1');
PREPARE avatar_stmt FROM @avatar_ddl;
EXECUTE avatar_stmt;
DEALLOCATE PREPARE avatar_stmt;

-- 插入默认测试数据（明文密码统一为 123456）
-- salt: 'dGVzdHNhbHQxMjM0NTY='
-- hash: PasswordUtil.hashPassword("123456", "dGVzdHNhbHQxMjM0NTY=")
INSERT INTO `tbl_user` (`UID`, `name`, `gender`, `password`, `salt`, `role`, `college`, `major`, `phone`, `email`, `balance`)
VALUES 
('213242789', '张三', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 32850),
('213242790', '李雨桐', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '电子科学与工程学院', '信息工程', '13800138001', 'liyutong@seu.edu.cn', 1200),
('213242791', '王浩然', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '机械工程学院', '机器人工程', '13800138002', 'wanghaoran@seu.edu.cn', 860),
('213242792', '陈思远', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '建筑学院', '城乡规划', '13800138003', 'chensiyuan@seu.edu.cn', 2300),
('213242793', '周可欣', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '经济管理学院', '金融学', '13800138004', 'zhouke@seu.edu.cn', 500),
('213242794', '赵子墨', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '交通学院', '交通运输', '13800138005', 'zhaozimo@seu.edu.cn', 1100),
('223242801', '孙婉清', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '外国语学院', '英语', '13800138006', 'sunwanqing@seu.edu.cn', 760),
('223242802', '吴承宇', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '人工智能', '13800138007', 'wuchengyu@seu.edu.cn', 980),
('233242815', '郑晓彤', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '医学院', '临床医学', '13800138008', 'zhengxiaotong@seu.edu.cn', 1500),
('admin', '系统管理员', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '网络信息中心', '系统管理', '13900139000', 'admin@seu.edu.cn', 88888),
('teacher01', '李老师', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 50000)
ON DUPLICATE KEY UPDATE `name`=VALUES(`name`);

-- 学籍管理
CREATE TABLE IF NOT EXISTS tblStudent (
    studentId VARCHAR(20) PRIMARY KEY, UID VARCHAR(32) NOT NULL UNIQUE,
    name VARCHAR(50) NOT NULL, gender VARCHAR(10) NOT NULL,
    politicalStatus VARCHAR(30), nationality VARCHAR(30),
    idType VARCHAR(30),
    idNumber VARCHAR(30),
    idIssueDate DATE,
    birthDate DATE,
    nativePlace VARCHAR(100),
    householdType VARCHAR(30),
    birthPlace VARCHAR(100),
    sourcePlace VARCHAR(100),
    registeredResidence VARCHAR(150),
    leagueMember TINYINT(1) NOT NULL DEFAULT 0,
    leagueJoinDate DATE,
    partyMember TINYINT(1) NOT NULL DEFAULT 0,
    partyJoinDate DATE, healthStatus VARCHAR(50),
    studentCategory VARCHAR(30),
    registered TINYINT(1) NOT NULL DEFAULT 1,
    inSchool TINYINT(1) NOT NULL DEFAULT 1,
    studentStatus VARCHAR(30),
    campus VARCHAR(50),
    grade VARCHAR(20),
    college VARCHAR(100),
    major VARCHAR(100),
    className VARCHAR(100),
    educationLevel VARCHAR(30),
    trainingMode VARCHAR(30),
    schoolingLength INT NOT NULL DEFAULT 4,
    counselorName VARCHAR(50),
    counselorPhone VARCHAR(30),
    candidateCategory VARCHAR(30),
    admissionDate DATE,
    admissionMethod VARCHAR(50),
    graduationSchool VARCHAR(150),
    middleSchoolClass VARCHAR(100),
    middleSchoolTeacher VARCHAR(50),
    telephone VARCHAR(30),
    mobile VARCHAR(30),
    email VARCHAR(100),
    qq VARCHAR(30),
    wechat VARCHAR(50),

    campusAddress VARCHAR(150),
    emergencyContact VARCHAR(50),
    emergencyPhone VARCHAR(30),
    CONSTRAINT fk_student_user FOREIGN KEY(UID) REFERENCES tbl_user(UID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 兼容已经使用旧列名 userId 创建的数据库；BINARY 用于区分列名大小写。
SET @has_legacy_student_user_id = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tblStudent'
      AND BINARY COLUMN_NAME = BINARY 'userId'
);
SET @student_uid_migration = IF(
    @has_legacy_student_user_id > 0,
    'ALTER TABLE tblStudent CHANGE COLUMN userId UID VARCHAR(32) NOT NULL',
    'SELECT 1'
);
PREPARE student_uid_statement FROM @student_uid_migration;
EXECUTE student_uid_statement;
DEALLOCATE PREPARE student_uid_statement;

CREATE TABLE IF NOT EXISTS tblStudentChangeRequest (
    requestId BIGINT PRIMARY KEY AUTO_INCREMENT,
    studentId VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    submitTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewerId VARCHAR(32),
    reviewTime DATETIME,
    reviewRemark VARCHAR(255),
    INDEX idx_change_student_status(studentId,status),
    FOREIGN KEY(studentId) REFERENCES tblStudent(studentId),
    FOREIGN KEY(reviewerId) REFERENCES tbl_user(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 教师个人信息管理
CREATE TABLE IF NOT EXISTS tblTeacher (
 teacherId VARCHAR(20) PRIMARY KEY, UID VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(50) NOT NULL,
 politicalStatus VARCHAR(30) NOT NULL, nationality VARCHAR(30) NOT NULL, gender VARCHAR(10) NOT NULL,
 idType VARCHAR(30) NOT NULL, idNumber VARCHAR(30) NOT NULL UNIQUE, idIssueDate DATE NOT NULL, birthDate DATE NOT NULL,
 nativePlace VARCHAR(100) NOT NULL, householdType VARCHAR(30) NOT NULL, birthPlace VARCHAR(100) NOT NULL,
 sourcePlace VARCHAR(100), registeredResidence VARCHAR(150) NOT NULL, partyMember TINYINT(1) NOT NULL DEFAULT 0,
 partyJoinDate DATE, healthStatus VARCHAR(50) NOT NULL, employed TINYINT(1) NOT NULL DEFAULT 1,
 employmentStatus VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', campus VARCHAR(50), college VARCHAR(100) NOT NULL,
 department VARCHAR(100), title VARCHAR(50), position VARCHAR(50), telephone VARCHAR(30), mobile VARCHAR(30),
 email VARCHAR(100), qq VARCHAR(30), wechat VARCHAR(50), officeAddress VARCHAR(150), emergencyContact VARCHAR(50), emergencyPhone VARCHAR(30),
 CONSTRAINT fk_teacher_user FOREIGN KEY(UID) REFERENCES tbl_user(UID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tblTeacherChangeRequest (
 requestId BIGINT PRIMARY KEY AUTO_INCREMENT, teacherId VARCHAR(20) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
 submitTime DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, reviewerId VARCHAR(32), reviewTime DATETIME, reviewRemark VARCHAR(255),
 FOREIGN KEY(teacherId) REFERENCES tblTeacher(teacherId), FOREIGN KEY(reviewerId) REFERENCES tbl_user(UID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tblTeacherChangeItem (
 itemId BIGINT PRIMARY KEY AUTO_INCREMENT, requestId BIGINT NOT NULL, fieldName VARCHAR(50) NOT NULL,
 oldValue VARCHAR(255), newValue VARCHAR(255), FOREIGN KEY(requestId) REFERENCES tblTeacherChangeRequest(requestId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tblTeacher(teacherId,UID,name,politicalStatus,nationality,gender,idType,idNumber,idIssueDate,birthDate,nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,partyMember,partyJoinDate,healthStatus,employed,employmentStatus,campus,college,department,title,position,telephone,mobile,email,officeAddress,emergencyContact,emergencyPhone)
VALUES('T00001','teacher01','李老师','中共党员','汉族','女','居民身份证','320100198001010001','2015-01-01','1980-01-01','江苏南京','城镇户口','江苏南京','江苏南京','江苏省南京市',1,'2005-07-01','健康',1,'在职','九龙湖校区','计算机科学与工程学院','计算机科学系','副教授','教师','025-52090001','13700137000','teacher@seu.edu.cn','九龙湖校区计算机楼','李家属','13600136000')
ON DUPLICATE KEY UPDATE name=VALUES(name);

CREATE TABLE IF NOT EXISTS tblStudentChangeItem (
    itemId BIGINT PRIMARY KEY AUTO_INCREMENT,
    requestId BIGINT NOT NULL,
    fieldName VARCHAR(50) NOT NULL,
    oldValue TEXT,
    newValue TEXT NOT NULL,
    FOREIGN KEY(requestId) REFERENCES tblStudentChangeRequest(requestId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tblStudentAward (
    awardId BIGINT PRIMARY KEY AUTO_INCREMENT,
    studentId VARCHAR(20) NOT NULL,
    awardName VARCHAR(100) NOT NULL,
    awardType VARCHAR(50) NOT NULL,
    awardLevel VARCHAR(50), awardDate DATE,
    organization VARCHAR(100),
    description VARCHAR(255), FOREIGN KEY(studentId) REFERENCES tblStudent(studentId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS tblStudentAid (
    aidId BIGINT PRIMARY KEY AUTO_INCREMENT,
    studentId VARCHAR(20) NOT NULL,
    aidName VARCHAR(100) NOT NULL,
    aidType VARCHAR(50),

    amount DECIMAL(10,2),
    aidDate DATE, provider VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    description VARCHAR(255),
    FOREIGN KEY(studentId) REFERENCES tblStudent(studentId)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE tblStudentChangeItem MODIFY oldValue TEXT NULL, MODIFY newValue TEXT NOT NULL;

CREATE TABLE IF NOT EXISTS tblStudentExperience (
 experienceId BIGINT PRIMARY KEY AUTO_INCREMENT, studentId VARCHAR(20) NOT NULL,
 startDate DATE NOT NULL, endDate DATE, schoolName VARCHAR(150) NOT NULL,
 educationLevel VARCHAR(50), description VARCHAR(255),
 FOREIGN KEY(studentId) REFERENCES tblStudent(studentId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS tblTeacherWorkExperience (
 experienceId BIGINT PRIMARY KEY AUTO_INCREMENT, teacherId VARCHAR(20) COLLATE utf8mb4_0900_ai_ci NOT NULL,
 startDate DATE NOT NULL, endDate DATE, organization VARCHAR(150) NOT NULL,
 department VARCHAR(100), position VARCHAR(100) NOT NULL, description VARCHAR(255),
 FOREIGN KEY(teacherId) REFERENCES tblTeacher(teacherId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS tblStudentFamilyMember (
 memberId BIGINT PRIMARY KEY AUTO_INCREMENT, studentId VARCHAR(20) NOT NULL,
 name VARCHAR(50) NOT NULL, relationship VARCHAR(30) NOT NULL, birthDate DATE,
 registeredResidence VARCHAR(150), workplace VARCHAR(150), workplaceAddress VARCHAR(200),
 healthStatus VARCHAR(50), phone VARCHAR(30),
 FOREIGN KEY(studentId) REFERENCES tblStudent(studentId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 兼容已创建的旧版家庭成员表，按需补充新增字段。
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblStudentFamilyMember' AND COLUMN_NAME='birthDate')=0,'ALTER TABLE tblStudentFamilyMember ADD birthDate DATE','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblStudentFamilyMember' AND COLUMN_NAME='registeredResidence')=0,'ALTER TABLE tblStudentFamilyMember ADD registeredResidence VARCHAR(150)','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblStudentFamilyMember' AND COLUMN_NAME='workplaceAddress')=0,'ALTER TABLE tblStudentFamilyMember ADD workplaceAddress VARCHAR(200)','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblStudentFamilyMember' AND COLUMN_NAME='healthStatus')=0,'ALTER TABLE tblStudentFamilyMember ADD healthStatus VARCHAR(50)','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;

INSERT INTO tblStudent(
 studentId,UID,name,gender,politicalStatus,nationality,idType,idNumber,idIssueDate,birthDate,
 nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,leagueMember,leagueJoinDate,
 partyMember,partyJoinDate,healthStatus,studentCategory,registered,inSchool,studentStatus,campus,
 grade,college,major,className,educationLevel,trainingMode,schoolingLength,counselorName,
 counselorPhone,candidateCategory,admissionDate,admissionMethod,graduationSchool,middleSchoolClass,
 middleSchoolTeacher,telephone,mobile,email,qq,wechat,campusAddress,emergencyContact,emergencyPhone
) VALUES (
 '213242789','213242789','张三','男','共青团员','汉族','居民身份证','320100200801010001',
 '2024-01-01','2008-01-01','江苏南京','城镇户口','江苏南京','江苏南京','江苏省南京市',
 1,'2022-05-04',0,NULL,'健康','本科生',1,1,'在籍','九龙湖校区','2026级',
 '计算机科学与工程学院','计算机科学与技术','计科一班','本科','全日制',4,
 '王老师','13900000000','普通类','2026-09-01','普通高考','南京市第一中学',
 '高三一班','李老师','025-00000000','13800138000','zhangsan@seu.edu.cn',
 '213242789','zhangsan_seu','九龙湖校区学生宿舍','张家长','13700000000'
)
ON DUPLICATE KEY UPDATE
 name=VALUES(name),grade=VALUES(grade),college=VALUES(college),major=VALUES(major);

-- 兼容旧数据中的户口性质简称，统一到客户端下拉框的标准枚举值。
UPDATE tblStudent SET householdType='城镇户口'
WHERE studentId > '' AND householdType='城镇';
UPDATE tblStudent SET householdType='农村居民户口'
WHERE studentId > '' AND householdType IN ('农村','农村户口');

-- 搜索与分页功能测试学生。测试账号密码均为 123456。
INSERT INTO tblStudent(
 studentId,UID,name,gender,politicalStatus,nationality,idType,idNumber,idIssueDate,birthDate,
 nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,leagueMember,leagueJoinDate,
 partyMember,partyJoinDate,healthStatus,studentCategory,registered,inSchool,studentStatus,campus,
 grade,college,major,className,educationLevel,trainingMode,schoolingLength,counselorName,
 counselorPhone,candidateCategory,admissionDate,admissionMethod,graduationSchool,telephone,mobile,
 email,emergencyContact,emergencyPhone
) VALUES
('213242790','213242790','李雨桐','女','共青团员','汉族','居民身份证','320100200801010002','2024-01-02','2008-02-16','江苏苏州','城镇户口','江苏苏州','江苏苏州','江苏省苏州市',1,'2021-05-04',0,NULL,'健康','本科生',1,1,'在籍','九龙湖校区','2026级','电子科学与工程学院','信息工程','信息一班','本科','全日制',4,'刘老师','13900000001','普通类','2026-09-01','普通高考','苏州中学','0512-10000001','13800138001','liyutong@seu.edu.cn','李建国','13700000001'),
('213242791','213242791','王浩然','男','群众','汉族','居民身份证','320100200801010003','2024-01-03','2008-03-08','山东青岛','农村居民户口','山东青岛','山东青岛','山东省青岛市',0,NULL,0,NULL,'健康','本科生',1,1,'在籍','九龙湖校区','2026级','机械工程学院','机器人工程','机器人一班','本科','全日制',4,'陈老师','13900000002','普通类','2026-09-01','普通高考','青岛第二中学','0532-10000002','13800138002','wanghaoran@seu.edu.cn','王海峰','13700000002'),
('213242792','213242792','陈思远','男','共青团员','汉族','居民身份证','320100200801010004','2024-01-04','2007-11-21','浙江杭州','城镇户口','浙江杭州','浙江杭州','浙江省杭州市',1,'2020-12-09',0,NULL,'健康','本科生',1,1,'在籍','四牌楼校区','2026级','建筑学院','城乡规划','规划一班','本科','全日制',5,'徐老师','13900000003','艺术类','2026-09-01','综合评价','杭州高级中学','0571-10000003','13800138003','chensiyuan@seu.edu.cn','陈明','13700000003'),
('213242793','213242793','周可欣','女','共青团员','汉族','居民身份证','320100200801010005','2024-01-05','2008-06-12','安徽合肥','集体户口','安徽合肥','安徽合肥','安徽省合肥市',1,'2022-05-04',0,NULL,'良好','本科生',1,1,'在籍','九龙湖校区','2026级','经济管理学院','金融学','金融二班','本科','全日制',4,'孙老师','13900000004','普通类','2026-09-01','普通高考','合肥第一中学','0551-10000004','13800138004','zhouke@seu.edu.cn','周志强','13700000004'),
('213242794','213242794','赵子墨','男','群众','汉族','居民身份证','320100200801010006','2024-01-06','2008-09-30','河南郑州','农村居民户口','河南郑州','河南郑州','河南省郑州市',0,NULL,0,NULL,'健康','本科生',1,0,'休学','九龙湖校区','2026级','交通学院','交通运输','交通一班','本科','全日制',4,'高老师','13900000005','普通类','2026-09-01','普通高考','郑州外国语学校','0371-10000005','13800138005','zhaozimo@seu.edu.cn','赵国华','13700000005'),
('223242801','223242801','孙婉清','女','中共预备党员','汉族','居民身份证','320100200701010007','2024-01-07','2007-04-18','福建厦门','城镇户口','福建厦门','福建厦门','福建省厦门市',0,NULL,1,'2025-07-01','健康','本科生',1,1,'在籍','九龙湖校区','2025级','外国语学院','英语','英语一班','本科','全日制',4,'郑老师','13900000006','外语类','2025-09-01','普通高考','厦门双十中学','0592-10000006','13800138006','sunwanqing@seu.edu.cn','孙立新','13700000006'),
('223242802','223242802','吴承宇','男','共青团员','汉族','居民身份证','320100200701010008','2024-01-08','2007-08-09','江苏无锡','城镇户口','江苏无锡','江苏无锡','江苏省无锡市',1,'2021-05-04',0,NULL,'健康','本科生',1,1,'在籍','九龙湖校区','2025级','计算机科学与工程学院','人工智能','人工智能二班','本科','全日制',4,'王老师','13900000000','强基类','2025-09-01','强基计划','无锡市第一中学','0510-10000007','13800138007','wuchengyu@seu.edu.cn','吴卫东','13700000007'),
('233242815','233242815','郑晓彤','女','共青团员','汉族','居民身份证','320100200601010009','2024-01-09','2006-12-03','湖北武汉','城镇户口','湖北武汉','湖北武汉','湖北省武汉市',1,'2020-05-04',0,NULL,'健康','本科生',1,1,'在籍','丁家桥校区','2024级','医学院','临床医学','临床三班','本科','全日制',5,'胡老师','13900000008','普通类','2024-09-01','普通高考','华中师大一附中','027-10000008','13800138008','zhengxiaotong@seu.edu.cn','郑伟','13700000008')
ON DUPLICATE KEY UPDATE
 name=VALUES(name),gender=VALUES(gender),grade=VALUES(grade),college=VALUES(college),
 major=VALUES(major),studentStatus=VALUES(studentStatus),inSchool=VALUES(inSchool);

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

-- ==================== 虚拟校园 AI 助手模块 ====================

-- 1. AI 对话会话表
CREATE TABLE IF NOT EXISTS `tbl_ai_conversation` (
    `conversation_id` VARCHAR(64) NOT NULL COMMENT '会话唯一ID(UUID)',
    `user_id` VARCHAR(32) NOT NULL COMMENT '所属用户一卡通号',
    `title` VARCHAR(200) NOT NULL DEFAULT '新对话' COMMENT '会话标题',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`conversation_id`),
    INDEX `idx_ai_conv_user_time` (`user_id`, `updated_at`),
    CONSTRAINT `fk_ai_conv_user` FOREIGN KEY (`user_id`) REFERENCES `tbl_user` (`UID`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话会话表';

-- 2. AI 对话消息表（增加 Token 消耗与计费金额字段支持账单溯源）
CREATE TABLE IF NOT EXISTS `tbl_ai_message` (
    `message_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '消息主键',
    `conversation_id` VARCHAR(64) NOT NULL COMMENT '关联会话ID',
    `sender_type` VARCHAR(20) NOT NULL COMMENT '发送者角色: USER / AI / SYSTEM',
    `content` MEDIUMTEXT NOT NULL COMMENT '消息文本内容',
    `intent_type` VARCHAR(30) DEFAULT 'GENERAL' COMMENT '意图类型: GENERAL / CAMPUS_RAG / PERSONAL_DATA / SENSITIVE_BLOCKED',
    `prompt_tokens` INT NOT NULL DEFAULT 0 COMMENT '输入提示词Token用量',
    `completion_tokens` INT NOT NULL DEFAULT 0 COMMENT '输出回答Token用量',
    `cost_amount` DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '消费虚拟货币金额',
    `transaction_no` VARCHAR(50) DEFAULT NULL COMMENT '关联银行交易流水号',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
    PRIMARY KEY (`message_id`),
    INDEX `idx_ai_msg_conv_time` (`conversation_id`, `created_at`),
    CONSTRAINT `fk_ai_msg_conv` FOREIGN KEY (`conversation_id`) REFERENCES `tbl_ai_conversation` (`conversation_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI对话消息表';

-- 3. 知识库文档表
CREATE TABLE IF NOT EXISTS `tbl_knowledge_document` (
    `doc_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '文档主键',
    `title` VARCHAR(200) NOT NULL COMMENT '文档标题',
    `category` VARCHAR(50) NOT NULL DEFAULT '校园知识' COMMENT '分类: 银行财务/学籍管理/图书服务/校园生活/选课规程',
    `content` LONGTEXT NOT NULL COMMENT '文档原始内容全文',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE/DISABLED',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '导入时间',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`doc_id`),
    INDEX `idx_knowledge_category_status` (`category`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库文档表';

-- 4. 知识库分块切片表 (Chunk)
CREATE TABLE IF NOT EXISTS `tbl_knowledge_chunk` (
    `chunk_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '分块主键',
    `doc_id` BIGINT NOT NULL COMMENT '关联文档主键',
    `chunk_index` INT NOT NULL COMMENT '切片顺序索引(从0开始)',
    `content` TEXT NOT NULL COMMENT '切片文本内容',
    `token_count` INT NOT NULL DEFAULT 0 COMMENT '分块字数/Token估算',
    `embedding` MEDIUMTEXT DEFAULT NULL COMMENT '嵌入向量JSON数据',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分块生成时间',
    PRIMARY KEY (`chunk_id`),
    INDEX `idx_chunk_doc_index` (`doc_id`, `chunk_index`),
    CONSTRAINT `fk_chunk_doc` FOREIGN KEY (`doc_id`) REFERENCES `tbl_knowledge_document` (`doc_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库分块表';

-- 5. AI 回答引用来源表 (Citation)
CREATE TABLE IF NOT EXISTS `tbl_ai_citation` (
    `citation_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '引用记录主键',
    `message_id` BIGINT NOT NULL COMMENT '关联的消息ID',
    `chunk_id` BIGINT DEFAULT NULL COMMENT '关联的知识分块ID',
    `doc_title` VARCHAR(200) NOT NULL COMMENT '来源文档标题',
    `similarity_score` DECIMAL(5,4) NOT NULL DEFAULT 0.0000 COMMENT '检索相关度打分',
    `excerpt` TEXT DEFAULT NULL COMMENT '引文摘要片段',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
    PRIMARY KEY (`citation_id`),
    INDEX `idx_citation_message` (`message_id`),
    CONSTRAINT `fk_citation_msg` FOREIGN KEY (`message_id`) REFERENCES `tbl_ai_message` (`message_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI回答引用来源表';

-- ==================== 预置校园知识库精选语料 ====================
INSERT INTO `tbl_knowledge_document` (`doc_id`, `title`, `category`, `content`, `status`)
VALUES
(1, '校园银行与费用缴纳操作规程', '银行财务',
'【校园银行与缴费指南】\n1. 校园银行提供虚拟货币资金管理、转账、账单缴费和财务报销服务。\n2. 缴费流程：师生登录虚拟校园系统后，在主页点击“银行”进入校园银行模块；在“我的账单”中可查看未缴费用（包含学年学费、住宿费等）；勾选账单并核对金额后，输入6位数字支付密码即可完成缴费扣款。\n3. 账户安全：支付密码连续输错3次将锁定支付功能，需联系管理员重置；转账时需输入收款人一卡通号，确认转账金额后完成实时转账。\n4. 财务报销：教职工可在“财务报销”提交报销申请，填写事项、金额及事由，由管理员审核通过后资金将自动发放至申请人一卡通账户。',
'ACTIVE'),

(2, '学生学籍管理与信息变更细则', '学籍管理',
'【学籍管理规程】\n1. 学籍查询：学生可在“学籍”模块查看本人基本信息、院系、专业、学号、学籍状态以及所获荣誉奖励与资助记录。\n2. 信息修改申请：若个人姓名、政治面貌或联系方式等发生变更，需在系统中提交修改申请并上传佐证材料，由院系管理员或校教务处管理员审核通过后方可生效。\n3. 转专业与休复学：转专业申请通常在每学年春季学期第10-12周开放，学生在学籍系统中提交意向申请；因病休学或复学需提交医院证明并由教务处统一审批。\n4. 违纪与申诉：对学籍处理决定有异议的，可在收到通知起5个工作日内向学生申诉处理委员会提起书面申诉。',
'ACTIVE'),

(3, '图书馆借阅规则与图书预约说明', '图书服务',
'【图书馆借阅规范】\n1. 借阅权限：学生用户最多可同时借阅10本图书，借期为30天；教师用户最多可借阅20本图书，借期为60天。\n2. 续借规则：在图书未超期且无他人预约的情况下，可在线续借1次，续借期限为30天。\n3. 超期违约金：超期未还图书将按每天每本0.10元收取超期违约金，逾期未缴费将暂停借阅与预约权限。\n4. 图书预约：当所需图书处于“借出”状态时，可在系统中点击“预约”，图书归还后系统将保留3天，并发送通知提醒读者前往总服务台借取。\n5. 图书挂失：若图书不慎遗失，应及时在图书馆系统办理挂失并按规定进行赔偿。',
'ACTIVE'),

(4, '本科生选课制度与流程指引', '选课规程',
'【选课流程与制度】\n1. 选课轮次：每学期选课分为三轮。第一轮为预选（抽签制，不分先后）；第二轮为正选（先到先得，即选即中）；第三轮为退补选（开学前两周开放）。\n2. 学分限制：学生每学期选修课程总学分原则上不低于15学分，最高不超过32学分。\n3. 选课退选：退选截止时间为开学第二周周日24:00，逾期不得退选，未退选且未参加考核者成绩记为0分或旷考。\n4. 重修与补考：必修课不及格者可在开学初参加补考或在后续学期申请跟班重修；选修课不及格可申请重修或改选其他同类型课程。',
'ACTIVE'),

(5, '校园商店购物与售后退款指引', '校园生活',
'【校园商店操作指引】\n1. 选购与下单：在商店首页浏览商品，将心仪商品加入购物车后前往结算；下单成功后生成待支付订单。\n2. 支付时效：待支付订单有效期为30分钟，超时未支付订单将自动取消并释放占用的商品库存。\n3. 订单支付：商店支持使用校园银行虚拟账户进行在线结账，扣款成功后订单状态变更为“已支付”。\n4. 申请退款：针对“已支付”状态的订单，用户可提交整单退款申请并填写真实退款理由，经商店管理员审核通过后，资金将在第一时间原路退回至用户校园银行账户中。',
'ACTIVE')
ON DUPLICATE KEY UPDATE `title`=VALUES(`title`);

-- 分块切片示例数据 (对应文档1-5的前置切片，供检索系统冷启动)
INSERT INTO `tbl_knowledge_chunk` (`chunk_id`, `doc_id`, `chunk_index`, `content`, `token_count`)
VALUES
(1, 1, 0, '【校园银行与缴费指南】校园银行提供虚拟货币资金管理、转账、账单缴费和财务报销服务。缴费流程：师生登录虚拟校园系统后，在主页点击“银行”进入校园银行模块；在“我的账单”中可查看未缴费用（包含学年学费、住宿费等）；勾选账单并核对金额后，输入6位数字支付密码即可完成缴费扣款。', 160),
(2, 1, 1, '【校园银行安全与转账】账户安全：支付密码连续输错3次将锁定支付功能，需联系管理员重置；转账时需输入收款人一卡通号，确认转账金额后完成实时转账。财务报销：教职工可在“财务报销”提交报销申请，填写事项、金额及事由，由管理员审核通过后资金将自动发放至申请人一卡通账户。', 155),
(3, 2, 0, '【学籍管理规程】学籍查询：学生可在“学籍”模块查看本人基本信息、院系、专业、学号、学籍状态以及所获荣誉奖励与资助记录。信息修改申请：若个人姓名、政治面貌或联系方式等发生变更，需在系统中提交修改申请并上传佐证材料，由院系管理员或校教务处管理员审核通过后方可生效。', 150),
(4, 2, 1, '【转专业与休学】转专业申请通常在每学年春季学期第10-12周开放，学生在学籍系统中提交意向申请；因病休学或复学需提交医院证明并由教务处统一审批。对学籍处理决定有异议的，可在收到通知起5个工作日内向学生申诉处理委员会提起书面申诉。', 130),
(5, 3, 0, '【图书馆借阅规范】借阅权限：学生用户最多可同时借阅10本图书，借期为30天；教师用户最多可借阅20本图书，借期为60天。续借规则：在图书未超期且无他人预约的情况下，可在线续借1次，续借期限为30天。超期未还图书将按每天每本0.10元收取超期违约金，逾期未缴费将暂停借阅与预约权限。', 160),
(6, 3, 1, '【图书预约与挂失】当所需图书处于“借出”状态时，可在系统中点击“预约”，图书归还后系统将保留3天，并发送通知提醒读者前往总服务台借取。若图书不慎遗失，应及时在图书馆系统办理挂失并按规定进行赔偿。', 115),
(7, 4, 0, '【选课流程与制度】选课轮次：每学期选课分为三轮。第一轮为预选（抽签制，不分先后）；第二轮为正选（先到先得，即选即中）；第三轮为退补选（开学前两周开放）。学分限制：学生每学期选修课程总学分原则上不低于15学分，最高不超过32学分。退选截止时间为开学第二周周日24:00。', 155),
(8, 5, 0, '【校园商店操作指引】选购与下单：在商店首页浏览商品加入购物车结算，待支付订单有效期为30分钟，超时未支付订单将自动取消并释放库存。商店支持使用校园银行虚拟账户结账。针对已支付订单可提交退款申请，经管理员审核后资金原路退回校园银行账户。', 150)
ON DUPLICATE KEY UPDATE `content`=VALUES(`content`);

