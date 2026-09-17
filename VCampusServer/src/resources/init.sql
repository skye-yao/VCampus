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
    `balance` DECIMAL(12,2) DEFAULT 10000.00 COMMENT '校园账户余额镜像，主余额见tbl_bank_account',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常, FROZEN-已冻结, DELETED-已注销',
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

SET @status_column_missing = (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tbl_user' AND COLUMN_NAME = 'status'
);
SET @status_ddl = IF(@status_column_missing,
    'ALTER TABLE `tbl_user` ADD COLUMN `status` VARCHAR(20) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''状态: ACTIVE-正常, FROZEN-已冻结, DELETED-已注销'' AFTER `balance`',
    'SELECT 1');
PREPARE status_stmt FROM @status_ddl;
EXECUTE status_stmt;
DEALLOCATE PREPARE status_stmt;

-- 普通用户的校园账户统一采用10000元开户余额，并让镜像字段与银行主余额精度一致。
ALTER TABLE `tbl_user` MODIFY COLUMN `balance` DECIMAL(12,2) DEFAULT 10000.00
    COMMENT '校园账户余额镜像，主余额见tbl_bank_account';

-- 插入默认测试数据（明文密码统一为 123456）
-- salt: 'dGVzdHNhbHQxMjM0NTY='
-- hash: PasswordUtil.hashPassword("123456", "dGVzdHNhbHQxMjM0NTY=")
INSERT INTO `tbl_user` (`UID`, `name`, `gender`, `password`, `salt`, `role`, `college`, `major`, `phone`, `email`, `balance`)
VALUES 
('213242789', '张三', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 10000.00),
('213242790', '李雨桐', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '电子科学与工程学院', '信息工程', '13800138001', 'liyutong@seu.edu.cn', 10000.00),
('213242791', '王浩然', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '机械工程学院', '机器人工程', '13800138002', 'wanghaoran@seu.edu.cn', 10000.00),
('213242792', '陈思远', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '建筑学院', '城乡规划', '13800138003', 'chensiyuan@seu.edu.cn', 10000.00),
('213242793', '周可欣', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '经济管理学院', '金融学', '13800138004', 'zhouke@seu.edu.cn', 10000.00),
('213242794', '赵子墨', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '交通学院', '交通运输', '13800138005', 'zhaozimo@seu.edu.cn', 10000.00),
('223242801', '孙婉清', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '外国语学院', '英语', '13800138006', 'sunwanqing@seu.edu.cn', 10000.00),
('223242802', '吴承宇', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '计算机科学与工程学院', '人工智能', '13800138007', 'wuchengyu@seu.edu.cn', 10000.00),
('233242815', '郑晓彤', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2, '医学院', '临床医学', '13800138008', 'zhengxiaotong@seu.edu.cn', 10000.00),
('admin', '主管理员', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '网络信息中心', '系统管理', '13900139000', 'admin@seu.edu.cn', 50000.00),
('admin1', '管理员1', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '教务处', '学籍管理', '18800000001', 'alice.jwc@seu.edu.cn', 50000.00),
('admin2', '管理员2', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '校图书馆', '系统管理', '18800000002', 'andrew.lib@seu.edu.cn', 50000.00),
('admin3', '管理员3', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '教务处', '课程管理', '18800000003', 'alexander.jwc@seu.edu.cn', 50000.00),
('admin4', '管理员4', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0, '财务处', '系统管理', '18800000004', 'arthur.cwc@seu.edu.cn', 50000.00),
('teacher01', '李老师', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 10000.00),
('teacher02', '王明哲', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '计算机科学与工程学院', '讲师', '13700137002', 'demo.teacher02@example.com', 10000.00),
('teacher03', '陈静怡', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '电子科学与工程学院', '副教授', '13700137003', 'demo.teacher03@example.com', 10000.00),
('teacher04', '刘建华', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '机械工程学院', '教授', '13700137004', 'demo.teacher04@example.com', 10000.00),
('teacher05', '周雅琴', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '建筑学院', '讲师', '13700137005', 'demo.teacher05@example.com', 10000.00),
('teacher06', '张志远', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '经济管理学院', '副教授', '13700137006', 'demo.teacher06@example.com', 10000.00),
('teacher07', '孙晓岚', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '交通学院', '教授', '13700137007', 'demo.teacher07@example.com', 10000.00),
('teacher08', '赵文清', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '外国语学院', '讲师', '13700137008', 'demo.teacher08@example.com', 10000.00),
('teacher09', '吴思敏', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '医学院', '副教授', '13700137009', 'demo.teacher09@example.com', 10000.00),
('teacher10', '郑启航', '男', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '土木工程学院', '教授', '13700137010', 'demo.teacher10@example.com', 10000.00),
('teacher11', '徐若兰', '女', 'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1, '数学学院', '讲师', '13700137011', 'demo.teacher11@example.com', 10000.00)
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
 department VARCHAR(100) NOT NULL, title VARCHAR(50), position VARCHAR(50), education VARCHAR(50) NOT NULL,
 employmentStartDate DATE NOT NULL, telephone VARCHAR(30), mobile VARCHAR(30),
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
-- 兼容旧数据库，确保教师学历和入职日期字段存在。
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblTeacher' AND COLUMN_NAME='education')=0,'ALTER TABLE tblTeacher ADD education VARCHAR(50)','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tblTeacher' AND COLUMN_NAME='employmentStartDate')=0,'ALTER TABLE tblTeacher ADD employmentStartDate DATE','SELECT 1');PREPARE s FROM @ddl;EXECUTE s;DEALLOCATE PREPARE s;
INSERT INTO tblTeacher(teacherId,UID,name,politicalStatus,nationality,gender,idType,idNumber,idIssueDate,birthDate,nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,partyMember,partyJoinDate,healthStatus,employed,employmentStatus,campus,college,department,title,position,education,employmentStartDate,telephone,mobile,email,officeAddress,emergencyContact,emergencyPhone)
VALUES
('T00001','teacher01','李老师','中共党员','汉族','女','居民身份证','320100198001010001','2015-01-01','1980-01-01','江苏南京','城镇户口','江苏南京','江苏南京','江苏省南京市',1,'2005-07-01','健康',1,'在职','九龙湖校区','计算机科学与工程学院','计算机科学系','副教授','教师','博士研究生','2010-09-01','025-52090001','13700137000','teacher@seu.edu.cn','九龙湖校区计算机楼','李家属','13600136000'),
('T00002','teacher02','王明哲','群众','汉族','男','居民身份证','320100198502020002','2016-02-01','1985-02-02','江苏南京','城镇户口','江苏南京','江苏南京','江苏省南京市',0,NULL,'健康',1,'在职','九龙湖校区','计算机科学与工程学院','计算机科学系','讲师','教师','博士研究生','2016-09-01','025-52090002','13700137002','demo.teacher02@example.com','九龙湖校区计算机楼','王家属','13600136002'),
('T00003','teacher03','陈静怡','中共党员','汉族','女','居民身份证','320100198403030003','2016-03-01','1984-03-03','江苏苏州','城镇户口','江苏苏州','江苏苏州','江苏省苏州市',1,'2006-07-01','健康',1,'在职','九龙湖校区','电子科学与工程学院','电子工程系','副教授','教师','博士研究生','2012-09-01','025-52090003','13700137003','demo.teacher03@example.com','九龙湖校区电子楼','陈家属','13600136003'),
('T00004','teacher04','刘建华','中共党员','汉族','男','居民身份证','320100197504040004','2014-04-01','1975-04-04','江苏无锡','城镇户口','江苏无锡','江苏无锡','江苏省无锡市',1,'1998-07-01','健康',1,'在职','九龙湖校区','机械工程学院','机械工程系','教授','教师','博士研究生','2003-09-01','025-52090004','13700137004','demo.teacher04@example.com','九龙湖校区机械楼','刘家属','13600136004'),
('T00005','teacher05','周雅琴','群众','汉族','女','居民身份证','320100198805050005','2017-05-01','1988-05-05','江苏常州','城镇户口','江苏常州','江苏常州','江苏省常州市',0,NULL,'健康',1,'在职','四牌楼校区','建筑学院','建筑系','讲师','教师','硕士研究生','2017-09-01','025-52090005','13700137005','demo.teacher05@example.com','四牌楼校区建筑楼','周家属','13600136005'),
('T00006','teacher06','张志远','中共党员','汉族','男','居民身份证','320100198206060006','2015-06-01','1982-06-06','江苏南通','城镇户口','江苏南通','江苏南通','江苏省南通市',1,'2004-07-01','健康',1,'在职','九龙湖校区','经济管理学院','工商管理系','副教授','教师','博士研究生','2011-09-01','025-52090006','13700137006','demo.teacher06@example.com','九龙湖校区经管楼','张家属','13600136006'),
('T00007','teacher07','孙晓岚','中共党员','汉族','女','居民身份证','320100197807070007','2014-07-01','1978-07-07','江苏扬州','城镇户口','江苏扬州','江苏扬州','江苏省扬州市',1,'2000-07-01','健康',1,'在职','九龙湖校区','交通学院','交通运输系','教授','教师','博士研究生','2006-09-01','025-52090007','13700137007','demo.teacher07@example.com','九龙湖校区交通楼','孙家属','13600136007'),
('T00008','teacher08','赵文清','群众','汉族','男','居民身份证','320100198908080008','2018-08-01','1989-08-08','江苏镇江','城镇户口','江苏镇江','江苏镇江','江苏省镇江市',0,NULL,'健康',1,'在职','九龙湖校区','外国语学院','英语系','讲师','教师','硕士研究生','2018-09-01','025-52090008','13700137008','demo.teacher08@example.com','九龙湖校区外语楼','赵家属','13600136008'),
('T00009','teacher09','吴思敏','中共党员','汉族','女','居民身份证','320100198309090009','2015-09-01','1983-09-09','江苏泰州','城镇户口','江苏泰州','江苏泰州','江苏省泰州市',1,'2005-07-01','健康',1,'在职','丁家桥校区','医学院','临床医学系','副教授','教师','博士研究生','2010-09-01','025-52090009','13700137009','demo.teacher09@example.com','丁家桥校区医学楼','吴家属','13600136009'),
('T00010','teacher10','郑启航','中共党员','汉族','男','居民身份证','320100197610100010','2014-10-01','1976-10-10','江苏盐城','城镇户口','江苏盐城','江苏盐城','江苏省盐城市',1,'1999-07-01','健康',1,'在职','九龙湖校区','土木工程学院','土木工程系','教授','教师','博士研究生','2005-09-01','025-52090010','13700137010','demo.teacher10@example.com','九龙湖校区土木楼','郑家属','13600136010'),
('T00011','teacher11','徐若兰','群众','汉族','女','居民身份证','320100199011110011','2019-11-01','1990-11-11','江苏南京','城镇户口','江苏南京','江苏南京','江苏省南京市',0,NULL,'健康',1,'在职','九龙湖校区','数学学院','应用数学系','讲师','教师','博士研究生','2019-09-01','025-52090011','13700137011','demo.teacher11@example.com','九龙湖校区数学楼','徐家属','13600136011')
ON DUPLICATE KEY UPDATE name=VALUES(name);

CREATE TABLE IF NOT EXISTS tblStudentChangeItem (
    itemId BIGINT PRIMARY KEY AUTO_INCREMENT,
    requestId BIGINT NOT NULL,
    fieldName VARCHAR(50) NOT NULL,
    oldValue TEXT,
    newValue TEXT NOT NULL,
    FOREIGN KEY(requestId) REFERENCES tblStudentChangeRequest(requestId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Run against the existing VCampus database. This migration only creates the receipt table.
CREATE TABLE IF NOT EXISTS tblStudentInformationBatch (
    operationId CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    adminId VARCHAR(32) NOT NULL,
    operationType VARCHAR(10) NOT NULL,
    payloadDigest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    successCount INT,
    resultJson TEXT,
    createdAt DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completedAt DATETIME
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
    experienceId BIGINT PRIMARY KEY AUTO_INCREMENT,
    studentId VARCHAR(20)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_ai_ci NOT NULL,
    startDate DATE NOT NULL,
    endDate DATE,
    schoolName VARCHAR(150) NOT NULL,
    educationLevel VARCHAR(50),
    description VARCHAR(255),
    FOREIGN KEY(studentId)
        REFERENCES tblStudent(studentId)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS tblTeacherWorkExperience (
 experienceId BIGINT PRIMARY KEY AUTO_INCREMENT, teacherId VARCHAR(20) COLLATE utf8mb4_0900_ai_ci NOT NULL,
 startDate DATE NOT NULL, endDate DATE, organization VARCHAR(150) NOT NULL,
 department VARCHAR(100), position VARCHAR(100) NOT NULL, description VARCHAR(255),
 FOREIGN KEY(teacherId) REFERENCES tblTeacher(teacherId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS tblTeacherFamilyMember (
 memberId BIGINT PRIMARY KEY AUTO_INCREMENT, teacherId VARCHAR(20) COLLATE utf8mb4_0900_ai_ci NOT NULL,
 name VARCHAR(50) NOT NULL, relationship VARCHAR(30) NOT NULL, birthDate DATE,
 registeredResidence VARCHAR(150), workplace VARCHAR(150), workplaceAddress VARCHAR(200),
 healthStatus VARCHAR(50), phone VARCHAR(30),
 FOREIGN KEY(teacherId) REFERENCES tblTeacher(teacherId) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS tblStudentFamilyMember (
    memberId BIGINT PRIMARY KEY AUTO_INCREMENT,
    studentId VARCHAR(20)
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_ai_ci NOT NULL,
    name VARCHAR(50) NOT NULL,
    relationship VARCHAR(30) NOT NULL,
    birthDate DATE,
    registeredResidence VARCHAR(150),
    workplace VARCHAR(150),
    workplaceAddress VARCHAR(200),
    healthStatus VARCHAR(50),
    phone VARCHAR(30),
    FOREIGN KEY(studentId)
        REFERENCES tblStudent(studentId)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;
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
    CONSTRAINT `chk_product_category` CHECK (`category` IN ('文具','教材资料','校园纪念品','生活用品','食品')),
    CONSTRAINT `chk_product_status` CHECK (`status` IN ('ON_SALE','OFF_SALE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店商品表';

-- 商品图片与商品列表分表存储；服务端更换电脑后连接同一个数据库即可继续读取图片。
CREATE TABLE IF NOT EXISTS `tbl_product_image` (
    `product_id` BIGINT NOT NULL COMMENT '商品编号',
    `mime_type` VARCHAR(20) NOT NULL COMMENT 'image/png 或 image/jpeg',
    `image_data` MEDIUMBLOB NOT NULL COMMENT '图片二进制内容，最大由服务端限制为 1 MiB',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`product_id`),
    CONSTRAINT `fk_product_image_product` FOREIGN KEY (`product_id`)
        REFERENCES `tbl_product` (`product_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商店商品图片表';

-- 商品评价：一名用户对一件商品最多一条，且需购买过。
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

-- 商店演示商品。重复执行脚本不会重复插入；价格、分类和库存与课程演示库保持一致
-- （库存是演示订单成交并扣减之后的数值）。
INSERT INTO `tbl_product`
(`product_id`,`product_name`,`description`,`category`,`price`,`stock`,`status`)
VALUES
(1,'东大纪念笔记本','校园主题硬壳笔记本','校园纪念品',18.80,60,'ON_SALE'),
(2,'黑色中性笔套装','0.5mm黑色中性笔，5支装','文具',9.90,120,'ON_SALE'),
(3,'Java程序设计参考书','适合课程实训的Java基础参考资料','教材资料',56.00,30,'ON_SALE'),
(4,'校园帆布袋','简洁耐用的校园纪念帆布袋','校园纪念品',29.90,44,'ON_SALE'),
(5,'东大校徽徽章','金属烤漆校园纪念徽章','校园纪念品',12.00,81,'ON_SALE'),
(6,'A4横线活页本','80页可替换内芯课堂笔记本','文具',15.50,75,'ON_SALE'),
(7,'数据结构课程辅导书','包含基础算法讲解和课程练习','教材资料',48.00,37,'ON_SALE'),
(8,'便携折叠雨伞','校园生活便携晴雨两用伞','生活用品',39.90,39,'ON_SALE'),
(9,'校园马克杯','陶瓷校园建筑图案马克杯','生活用品',32.00,50,'ON_SALE'),
(10,'荧光笔六色套装','适合教材标记的柔和色荧光笔','文具',16.80,86,'ON_SALE'),
(11,'计算机网络实验指导','配套网络课程实验与复习','教材资料',42.00,28,'ON_SALE'),
(12,'USB桌面小风扇','宿舍桌面静音三档小风扇','生活用品',48.00,28,'ON_SALE')
ON DUPLICATE KEY UPDATE `product_name`=VALUES(`product_name`);

-- 商品图片不在本文件里：图片以二进制存在 tbl_product_image，体积较大，
-- 单独放在 seed/seed-product-images.sql（按商品名匹配，可重复执行）。
-- 商店演示订单与对应银行流水见 seed/seed-shop-demo-orders.sql。

-- 演示库中另行上架的三种日用品：商店历史订单和后台日志会引用它们；
-- 同名商品已存在时跳过，不指定主键，不会覆盖已有商品。
INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '便携折叠雨伞2', '校园生活便携晴雨两用伞', '生活用品', 39.90, 17, 'OFF_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='便携折叠雨伞2');

INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '牙刷', '软毛牙刷，独立包装，刷毛柔韧，适合日常清洁。', '生活用品', 15.00, 29, 'ON_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='牙刷');

INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '牙膏', '薄荷香型牙膏，清洁口腔、清新口气，适合学生宿舍日常使用。', '生活用品', 10.00, 20, 'ON_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='牙膏');

-- 食品分类的三件商品：同名商品已存在时跳过，不指定主键，不会覆盖已有商品。
INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '全脂纯牛奶（250ml×12盒）', '250ml×12盒装。配料为生牛乳，蛋白质≥3.2g/100ml，口感醇厚，适合早餐饮用。保质期6个月，生产日期见包装喷码，未开封常温避光保存，开封后需冷藏并尽快喝完。', '食品', 45.00, 60, 'ON_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='全脂纯牛奶（250ml×12盒）');

INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '原味黄油饼干（200g袋装）', '200g袋装，内含独立小包装。由小麦粉、黄油、鸡蛋制成，奶香浓郁、口感酥脆，适合课间加餐。保质期9个月，生产日期见包装喷码，开封后密封保存。含小麦、乳制品和蛋类。', '食品', 12.50, 80, 'ON_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='原味黄油饼干（200g袋装）');

INSERT INTO `tbl_product` (`product_name`,`description`,`category`,`price`,`stock`,`status`)
SELECT '可乐汽水（330ml×6罐）', '330ml×6罐一提，含气碳酸饮料。经典口味，冰镇后更清爽，适合聚餐或运动后饮用。保质期12个月，生产日期见包装喷码，常温避光保存，开封后尽快饮用。含糖，请适量饮用。', '食品', 18.00, 70, 'ON_SALE'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `tbl_product` WHERE `product_name`='可乐汽水（330ml×6罐）');


-- ==================== 校园银行模块（基础版） ====================
CREATE TABLE IF NOT EXISTS `tbl_bank_account` (
    `account_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '虚拟账户主键',
    `user_id` VARCHAR(32) NOT NULL COMMENT '所属用户一卡通号',
    `balance` DECIMAL(12,2) NOT NULL DEFAULT 10000.00 COMMENT '校园账户主余额',
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

-- CREATE TABLE IF NOT EXISTS 不会修改旧表默认值，显式同步为10000元。
ALTER TABLE `tbl_bank_account` ALTER COLUMN `balance` SET DEFAULT 10000.00;

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

-- 所有演示用户都会开通校园账户，默认支付密码为123456。
-- 普通用户开户余额统一为10000元；admin作为校园财务账户保留50000元初始资金。
INSERT INTO `tbl_bank_account`
(`user_id`,`balance`,`payment_password_hash`,`payment_password_salt`,`status`)
SELECT `UID`,IF(`UID`='admin',50000.00,10000.00),
       'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=',
       'dGVzdHNhbHQxMjM0NTY=','ACTIVE'
FROM `tbl_user`
ON DUPLICATE KEY UPDATE `user_id`=VALUES(`user_id`);

-- 先为旧账户补齐原始开户流水，再执行1500→10000的一次性余额升级。
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`transaction_type`,`amount`,`balance_after`,`request_id`,`remark`)
SELECT CONCAT('INIT-',a.`user_id`),a.`account_id`,'INITIAL_BALANCE',a.`balance`,a.`balance`,
       CONCAT('INIT-',a.`user_id`),'课程演示账户初始资金'
FROM `tbl_bank_account` a
WHERE NOT EXISTS (
    SELECT 1 FROM `tbl_bank_transaction` t
    WHERE t.`account_id`=a.`account_id` AND t.`transaction_type`='INITIAL_BALANCE'
);

DROP TEMPORARY TABLE IF EXISTS `tmp_balance_upgrade_10000`;
CREATE TEMPORARY TABLE `tmp_balance_upgrade_10000` (
    `account_id` BIGINT NOT NULL,
    `user_id` VARCHAR(32) NOT NULL,
    PRIMARY KEY (`account_id`)
) ENGINE=MEMORY;

INSERT INTO `tmp_balance_upgrade_10000` (`account_id`,`user_id`)
SELECT a.`account_id`,a.`user_id`
FROM `tbl_bank_account` a
JOIN `tbl_user` u ON u.`UID`=a.`user_id`
WHERE u.`role`<>0
  AND a.`balance`=1500.00
  AND NOT EXISTS (
      SELECT 1 FROM `tbl_bank_transaction` t
      WHERE t.`request_id`=CONCAT('OPENING-UPGRADE-10000-',a.`user_id`)
  );

START TRANSACTION;
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`transaction_type`,`amount`,`balance_after`,`request_id`,`remark`)
SELECT CONCAT('UPGRADE-10000-',e.`user_id`),e.`account_id`,'ACCOUNT_RECHARGE',8500.00,10000.00,
       CONCAT('OPENING-UPGRADE-10000-',e.`user_id`),'初始余额由1500元统一调整为10000元'
FROM `tmp_balance_upgrade_10000` e;

UPDATE `tbl_bank_account` a
JOIN `tmp_balance_upgrade_10000` e ON e.`account_id`=a.`account_id`
SET a.`balance`=10000.00,
    a.`version`=a.`version`+1
WHERE a.`account_id`>0;
COMMIT;

DROP TEMPORARY TABLE `tmp_balance_upgrade_10000`;

-- 银行表是余额主数据，用户表余额仅供用户资料等旧接口展示。
UPDATE `tbl_user` u
JOIN `tbl_bank_account` b ON b.`user_id`=u.`UID`
SET u.`balance`=b.`balance`
WHERE u.`UID`<>'';

INSERT INTO `tbl_finance_bill` (`user_id`,`bill_type`,`title`,`amount`,`status`,`due_date`)
SELECT `UID`,'TUITION','2026学年学费',5200.00,'UNPAID','2026-12-31'
FROM `tbl_user` WHERE `role`=2
ON DUPLICATE KEY UPDATE `user_id`=VALUES(`user_id`);

INSERT INTO `tbl_finance_bill` (`user_id`,`bill_type`,`title`,`amount`,`status`,`due_date`)
SELECT `UID`,'ACCOMMODATION','2026学年住宿费',1200.00,'UNPAID','2026-12-31'
FROM `tbl_user` WHERE `role`=2
ON DUPLICATE KEY UPDATE `user_id`=VALUES(`user_id`);

INSERT INTO `tbl_finance_bill` (`user_id`,`bill_type`,`title`,`amount`,`status`,`due_date`)
VALUES ('teacher01','OTHER','校园停车服务费',200.00,'UNPAID','2026-12-31')
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

-- 以下四类流水都发生在用户与校园财务账户之间，对方固定是财务账户（admin）：
-- 商店消费、商店退款、报销入账、学费缴纳。2026-09-02 及更早的开发版本把对方写成了 NULL，
-- 导致历史流水在“对方用户编号”一列显示空白；这里做一次性回填，可重复执行。
-- 开户初始资金（INITIAL_BALANCE）和历史余额调整（ACCOUNT_RECHARGE）本身没有对方用户，不在此列。
UPDATE `tbl_bank_transaction` t
JOIN `tbl_user` u ON u.`UID`='admin'
SET t.`counterparty_user_id`=u.`UID`
WHERE t.`counterparty_user_id` IS NULL
  AND t.`transaction_type` IN
      ('TUITION_PAYMENT','SHOP_PAYMENT','SHOP_REFUND','REIMBURSEMENT');

-- ==================== 商店与银行演示业务数据 ====================
-- 下面的订单、退款、后台日志、商品评价、购物车、资金流水、报销和账单，
-- 全部取自课程演示库 virtual_campus（2026-09-16 的快照），
-- 目的是让“克隆仓库 + 只跑 init.sql”的新环境也能看到与演示时一致的商店、银行记录。
-- 三条约定：
--   1) 只有商店业务表和银行资金流水都还是空库时才会导入，已经用过的数据库原样保留；
--   2) 商品、订单一律用业务编号（商品名称、订单号、流水号）关联，不依赖自增主键，
--      因此新库的自增编号与演示库不同也能正确挂上关系；
--   3) 每条语句都带 ON DUPLICATE KEY UPDATE，重复执行不会产生重复记录。

SET @seed_demo_shop = (SELECT CASE WHEN
    (SELECT COUNT(*) FROM `tbl_shop_order`) = 0
    AND (SELECT COUNT(*) FROM `tbl_shop_refund`) = 0
    AND (SELECT COUNT(*) FROM `tbl_shop_operation_log`) = 0
    AND (SELECT COUNT(*) FROM `tbl_product_review`) = 0
    THEN 1 ELSE 0 END);

SET @seed_demo_bank = (SELECT CASE WHEN
    (SELECT COUNT(*) FROM `tbl_bank_transaction`
     WHERE `transaction_type` NOT IN ('INITIAL_BALANCE','ACCOUNT_RECHARGE')) = 0
    THEN 1 ELSE 0 END);

-- 1. 商店订单：12 笔，覆盖已支付、已取消、已过期、已退款四种状态
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021044243027EA','213242789',253.90,'REFUNDED','BT2026090210460545446757','2026-09-02 11:14:24','2026-09-02 10:46:05',NULL,3,'2026-09-02 10:44:24','2026-09-02 11:46:39'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021057407F5733','213242789',15.50,'CANCELLED',NULL,'2026-09-02 11:27:40',NULL,'2026-09-02 10:58:02',1,'2026-09-02 10:57:40','2026-09-02 10:58:02'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021144429CBB40','213242789',90.30,'PAID','BT20260902114454A2A6BFEA','2026-09-02 12:14:42','2026-09-02 11:44:54',NULL,1,'2026-09-02 11:44:42','2026-09-02 11:44:54'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021148478C83C2','213242789',39.90,'PAID','BT20260902114859D00271CC','2026-09-02 12:18:47','2026-09-02 11:48:59',NULL,4,'2026-09-02 11:48:47','2026-09-02 13:31:45'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021151259FF74F','213242789',837.90,'EXPIRED',NULL,'2026-09-02 12:21:25',NULL,NULL,1,'2026-09-02 11:51:25','2026-09-02 17:04:00'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO2026090216545044CCDB','213242789',15.00,'PAID','BT20260902171132EFE33960','2026-09-02 17:24:50','2026-09-02 17:11:32',NULL,1,'2026-09-02 16:54:50','2026-09-02 17:11:32'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO20260902171212AFDFFF','teacher01',29.90,'PAID','BT20260902171414529E7FFE','2026-09-02 17:42:12','2026-09-02 17:14:14',NULL,1,'2026-09-02 17:12:12','2026-09-02 17:14:14'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO202609021714321DA6AF','teacher01',48.00,'EXPIRED',NULL,'2026-09-02 17:44:32',NULL,NULL,1,'2026-09-02 17:14:32','2026-09-02 17:44:33'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO2026090218075548C676','teacher01',192.00,'PAID','BT202609021808059F2BC247','2026-09-02 18:37:55','2026-09-02 18:08:05',NULL,1,'2026-09-02 18:07:55','2026-09-02 18:08:05'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO20260911165437406535','213242790',12.00,'PAID','BT20260911165446222006AA','2026-09-11 17:24:37','2026-09-11 16:54:46',NULL,1,'2026-09-11 16:54:37','2026-09-11 16:54:46'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO20260916092548115DFA','213242789',128.00,'REFUNDED','BT202609160926101B51F4B3','2026-09-16 09:55:48','2026-09-16 09:26:10',NULL,3,'2026-09-16 09:25:48','2026-09-16 09:46:25'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);
INSERT INTO `tbl_shop_order`
(`order_no`,`user_id`,`total_amount`,`status`,`payment_transaction_no`,
 `expires_at`,`paid_at`,`cancelled_at`,`version`,`created_at`,`updated_at`)
SELECT 'SO20260916115437B83C03','213242790',16.80,'PAID','BT20260916115445913F8FFF','2026-09-16 12:24:37','2026-09-16 11:54:45',NULL,1,'2026-09-16 11:54:37','2026-09-16 11:54:45'
FROM DUAL WHERE @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `order_no`=VALUES(`order_no`);

-- 2. 订单明细：订单用订单号关联，商品用商品名称关联
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 1,o.`order_id`,p.`product_id`,'校园帆布袋',29.90,1,29.90
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '校园帆布袋'
WHERE o.`order_no` = 'SO202609021044243027EA' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 2,o.`order_id`,p.`product_id`,'Java程序设计参考书',56.00,4,224.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = 'Java程序设计参考书'
WHERE o.`order_no` = 'SO202609021044243027EA' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 3,o.`order_id`,p.`product_id`,'A4横线活页本',15.50,1,15.50
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = 'A4横线活页本'
WHERE o.`order_no` = 'SO202609021057407F5733' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 4,o.`order_id`,p.`product_id`,'荧光笔六色套装',16.80,3,50.40
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '荧光笔六色套装'
WHERE o.`order_no` = 'SO202609021144429CBB40' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 5,o.`order_id`,p.`product_id`,'便携折叠雨伞',39.90,1,39.90
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '便携折叠雨伞'
WHERE o.`order_no` = 'SO202609021144429CBB40' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 6,o.`order_id`,p.`product_id`,'便携折叠雨伞',39.90,1,39.90
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '便携折叠雨伞'
WHERE o.`order_no` = 'SO202609021148478C83C2' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 7,o.`order_id`,p.`product_id`,'便携折叠雨伞',39.90,21,837.90
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '便携折叠雨伞'
WHERE o.`order_no` = 'SO202609021151259FF74F' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 8,o.`order_id`,p.`product_id`,'牙刷',15.00,1,15.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '牙刷'
WHERE o.`order_no` = 'SO2026090216545044CCDB' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 9,o.`order_id`,p.`product_id`,'校园帆布袋',29.90,1,29.90
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '校园帆布袋'
WHERE o.`order_no` = 'SO20260902171212AFDFFF' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 10,o.`order_id`,p.`product_id`,'USB桌面小风扇',48.00,1,48.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = 'USB桌面小风扇'
WHERE o.`order_no` = 'SO202609021714321DA6AF' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 11,o.`order_id`,p.`product_id`,'USB桌面小风扇',48.00,4,192.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = 'USB桌面小风扇'
WHERE o.`order_no` = 'SO2026090218075548C676' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 12,o.`order_id`,p.`product_id`,'东大校徽徽章',12.00,1,12.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '东大校徽徽章'
WHERE o.`order_no` = 'SO20260911165437406535' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 13,o.`order_id`,p.`product_id`,'校园马克杯',32.00,4,128.00
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '校园马克杯'
WHERE o.`order_no` = 'SO20260916092548115DFA' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_order_item`
(`order_item_id`,`order_id`,`product_id`,`product_name_snapshot`,`unit_price`,`quantity`,`subtotal`)
SELECT 14,o.`order_id`,p.`product_id`,'荧光笔六色套装',16.80,1,16.80
FROM `tbl_shop_order` o
JOIN `tbl_product` p ON p.`product_name` = '荧光笔六色套装'
WHERE o.`order_no` = 'SO20260916115437B83C03' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);

-- 3. 退款申请：一笔已同意退款的订单
INSERT INTO `tbl_shop_refund`
(`refund_id`,`refund_no`,`order_id`,`user_id`,`refund_amount`,`reason`,`status`,
 `original_transaction_no`,`previous_order_status`,`refund_transaction_no`,`reviewer_id`,
 `review_comment`,`requested_at`,`reviewed_at`)
SELECT 1,'RF20260902105823075FB2',o.`order_id`,'213242789',253.90,'质量差','SUCCESS','BT2026090210460545446757','PAID','BT202609021146395681E2B7','admin','同意','2026-09-02 10:58:23','2026-09-02 11:46:39'
FROM `tbl_shop_order` o
WHERE o.`order_no` = 'SO202609021044243027EA' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `refund_no`=VALUES(`refund_no`);
INSERT INTO `tbl_shop_refund`
(`refund_id`,`refund_no`,`order_id`,`user_id`,`refund_amount`,`reason`,`status`,
 `original_transaction_no`,`previous_order_status`,`refund_transaction_no`,`reviewer_id`,
 `review_comment`,`requested_at`,`reviewed_at`)
SELECT 2,'RF20260916094535EB1588',o.`order_id`,'213242789',128.00,'质量差','SUCCESS','BT202609160926101B51F4B3','PAID','BT2026091609462525B6DC93','admin','同意','2026-09-16 09:45:35','2026-09-16 09:46:25'
FROM `tbl_shop_order` o
WHERE o.`order_no` = 'SO20260916092548115DFA' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `refund_no`=VALUES(`refund_no`);

-- 4. 商店后台操作日志：改价、改库存、上下架、换图和退款审核的留痕
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 1,'admin','PRODUCT_STOCK_UPDATE','PRODUCT',p.`product_id`,'stock=80','stock=82','调整商品库存','2026-09-07 22:08:07'
FROM `tbl_product` p
WHERE p.`product_name` = '东大校徽徽章' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 2,'admin','PRODUCT_STOCK_UPDATE','PRODUCT',p.`product_id`,'stock=36','stock=37','调整商品库存','2026-09-07 22:24:14'
FROM `tbl_product` p
WHERE p.`product_name` = '数据结构课程辅导书' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 11,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:35:18'
FROM `tbl_product` p
WHERE p.`product_name` = '东大纪念笔记本' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 12,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:35:29'
FROM `tbl_product` p
WHERE p.`product_name` = '黑色中性笔套装' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 13,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:40:03'
FROM `tbl_product` p
WHERE p.`product_name` = 'Java程序设计参考书' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 14,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:40:19'
FROM `tbl_product` p
WHERE p.`product_name` = '校园帆布袋' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 15,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:40:38'
FROM `tbl_product` p
WHERE p.`product_name` = '东大校徽徽章' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 16,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:40:51'
FROM `tbl_product` p
WHERE p.`product_name` = 'A4横线活页本' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 17,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:41:09'
FROM `tbl_product` p
WHERE p.`product_name` = '数据结构课程辅导书' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 18,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:41:21'
FROM `tbl_product` p
WHERE p.`product_name` = '便携折叠雨伞' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 19,'admin','PRODUCT_UPDATE','PRODUCT',p.`product_id`,'name=校园帆布袋,category=生活用品,price=29.90,stock=44,status=ON_SALE,version=7','name=校园帆布袋,category=校园纪念品,price=29.9,stock=44,status=ON_SALE,version=7','修改商品基本信息','2026-09-14 15:41:41'
FROM `tbl_product` p
WHERE p.`product_name` = '校园帆布袋' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 20,'admin','PRODUCT_UPDATE','PRODUCT',p.`product_id`,'name=校园马克杯,category=校园纪念品,price=32.00,stock=50,status=ON_SALE,version=9','name=校园马克杯,category=生活用品,price=32.0,stock=50,status=ON_SALE,version=9','修改商品基本信息','2026-09-14 15:41:56'
FROM `tbl_product` p
WHERE p.`product_name` = '校园马克杯' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 21,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:42:13'
FROM `tbl_product` p
WHERE p.`product_name` = '校园马克杯' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 22,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:42:37'
FROM `tbl_product` p
WHERE p.`product_name` = '荧光笔六色套装' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 23,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:42:47'
FROM `tbl_product` p
WHERE p.`product_name` = '计算机网络实验指导' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 24,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:43:02'
FROM `tbl_product` p
WHERE p.`product_name` = 'USB桌面小风扇' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 25,'admin','PRODUCT_STATUS_CHANGE','PRODUCT',p.`product_id`,'status=ON_SALE','status=OFF_SALE','下架商品','2026-09-14 15:43:06'
FROM `tbl_product` p
WHERE p.`product_name` = '便携折叠雨伞2' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 26,'admin','PRODUCT_UPDATE','PRODUCT',p.`product_id`,'name=牙刷,category=生活用品,price=15.00,stock=29,status=ON_SALE,version=1','name=牙刷,category=生活用品,price=15.0,stock=29,status=ON_SALE,version=1','修改商品基本信息','2026-09-14 15:43:09'
FROM `tbl_product` p
WHERE p.`product_name` = '牙刷' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 27,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:43:19'
FROM `tbl_product` p
WHERE p.`product_name` = '牙刷' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 28,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-14 15:43:34'
FROM `tbl_product` p
WHERE p.`product_name` = '牙膏' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 29,'admin','REFUND_APPROVE','REFUND',r.`refund_id`,'status=APPLIED,orderStatus=REFUNDING','status=SUCCESS,orderStatus=REFUNDED','同意','2026-09-16 09:46:25'
FROM `tbl_shop_refund` r
WHERE r.`refund_no` = 'RF20260916094535EB1588' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 31,'admin','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-16 13:51:04'
FROM `tbl_product` p
WHERE p.`product_name` = '全脂纯牛奶（250ml×12盒）' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 32,'admin1','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-16 13:52:03'
FROM `tbl_product` p
WHERE p.`product_name` = '原味黄油饼干（200g袋装）' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);
INSERT INTO `tbl_shop_operation_log`
(`log_id`,`operator_id`,`action`,`target_type`,`target_id`,`before_data`,`after_data`,`reason`,`created_at`)
SELECT 33,'admin1','PRODUCT_IMAGE_UPDATE','PRODUCT',p.`product_id`,'image=none','image=image/png','更换商品图片','2026-09-16 13:52:21'
FROM `tbl_product` p
WHERE p.`product_name` = '可乐汽水（330ml×6罐）' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `log_id`=VALUES(`log_id`);

-- 5. 商品评价：已购买用户的评价（一卡通号在查询时由服务端脱敏）
INSERT INTO `tbl_product_review` (`product_id`,`user_id`,`rating`,`content`,`created_at`)
SELECT p.`product_id`,'213242789',5,'不错','2026-09-16 11:53:07'
FROM `tbl_product` p
WHERE p.`product_name` = '荧光笔六色套装' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `rating`=VALUES(`rating`);

-- 6. 购物车：演示库中留下的几件待结算商品
INSERT INTO `tbl_cart_item` (`user_id`,`product_id`,`quantity`,`created_at`)
SELECT '213242789',p.`product_id`,1,'2026-09-02 11:47:58'
FROM `tbl_product` p
WHERE p.`product_name` = '计算机网络实验指导' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_cart_item` (`user_id`,`product_id`,`quantity`,`created_at`)
SELECT 'teacher01',p.`product_id`,1,'2026-09-02 18:10:15'
FROM `tbl_product` p
WHERE p.`product_name` = '校园帆布袋' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_cart_item` (`user_id`,`product_id`,`quantity`,`created_at`)
SELECT 'teacher01',p.`product_id`,1,'2026-09-02 18:10:39'
FROM `tbl_product` p
WHERE p.`product_name` = 'A4横线活页本' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);
INSERT INTO `tbl_cart_item` (`user_id`,`product_id`,`quantity`,`created_at`)
SELECT '213242789',p.`product_id`,1,'2026-09-16 09:25:41'
FROM `tbl_product` p
WHERE p.`product_name` = '校园帆布袋' AND @seed_demo_shop = 1
ON DUPLICATE KEY UPDATE `quantity`=VALUES(`quantity`);

-- 7. 银行资金流水：商店支付与退款、转账、报销、学费缴纳（开户流水已在上面的银行模块生成）
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT2026090210460545446757',a.`account_id`,'admin','SHOP_PAYMENT',-253.90,9746.10,o.`order_id`,'26b12458-16db-45ab-aa89-2843cd8145c5','校园商店订单支付','2026-09-02 10:46:05'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021044243027EA'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902114454A2A6BFEA',a.`account_id`,'admin','SHOP_PAYMENT',-90.30,9655.80,o.`order_id`,'e1c2200e-5879-4e76-95a4-5d0919dc83cf','校园商店订单支付','2026-09-02 11:44:54'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021144429CBB40'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609021146395681E2B7',a.`account_id`,'admin','SHOP_REFUND',253.90,9909.70,o.`order_id`,'ddf2b6f4-8791-47d7-b3b0-9db1a1fe7a99','校园商店订单退款','2026-09-02 11:46:39'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021044243027EA'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902114859D00271CC',a.`account_id`,'admin','SHOP_PAYMENT',-39.90,9869.80,o.`order_id`,'db502736-6585-4949-bd53-2c897d4bd8cf','校园商店订单支付','2026-09-02 11:48:59'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021148478C83C2'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902171132EFE33960',a.`account_id`,'admin','SHOP_PAYMENT',-15.00,9854.80,o.`order_id`,'0729ceff-8e72-4e6e-8d8f-719253256759','校园商店订单支付','2026-09-02 17:11:32'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO2026090216545044CCDB'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902171414529E7FFE',a.`account_id`,'admin','SHOP_PAYMENT',-29.90,7970.10,o.`order_id`,'095e38c6-cda0-4db1-aaa6-10c1db5f38d3','校园商店订单支付','2026-09-02 17:14:14'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260902171212AFDFFF'
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902171701112E43C4',a.`account_id`,'admin','REIMBURSEMENT',1100.00,9070.10,NULL,'REIMB-1','报销入账：科研','2026-09-02 17:17:01'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902171806161E019C',a.`account_id`,'teacher01','TRANSFER_OUT',-100.00,49900.00,NULL,'cc7c3ef8-44fe-4589-bdb4-2c73141c5e8a','转账给 teacher01','2026-09-02 17:18:06'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902171806CE4FD6DB',a.`account_id`,'admin','TRANSFER_IN',100.00,9170.10,NULL,NULL,'收到 admin 的转账','2026-09-02 17:18:06'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902173219FD286FF4',a.`account_id`,'admin','REIMBURSEMENT',270.00,9440.10,NULL,'REIMB-2','报销入账：科研','2026-09-02 17:32:19'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFREIMB1OUT',a.`account_id`,'teacher01','REIMBURSEMENT_PAYOUT',-1100.00,48800.00,NULL,'REIMB-1-OUT','向 teacher01 支付报销款：科研','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFREIMB2OUT',a.`account_id`,'teacher01','REIMBURSEMENT_PAYOUT',-270.00,48530.00,NULL,'REIMB-2-OUT','向 teacher01 支付报销款：科研','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP2IN',a.`account_id`,'213242789','SHOP_INCOME',253.90,48783.90,o.`order_id`,'SHOP-2-IN','校园商店历史订单收入补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021044243027EA'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP2REFUNDOUT',a.`account_id`,'213242789','SHOP_REFUND_PAYOUT',-253.90,48530.00,o.`order_id`,'SHOP-2-REFUND-OUT','校园商店历史退款支出补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021044243027EA'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP4IN',a.`account_id`,'213242789','SHOP_INCOME',90.30,48620.30,o.`order_id`,'SHOP-4-IN','校园商店历史订单收入补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021144429CBB40'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP5IN',a.`account_id`,'213242789','SHOP_INCOME',39.90,48660.20,o.`order_id`,'SHOP-5-IN','校园商店历史订单收入补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO202609021148478C83C2'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP7IN',a.`account_id`,'213242789','SHOP_INCOME',15.00,48675.20,o.`order_id`,'SHOP-7-IN','校园商店历史订单收入补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO2026090216545044CCDB'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BFSHOP8IN',a.`account_id`,'teacher01','SHOP_INCOME',29.90,48705.10,o.`order_id`,'SHOP-8-IN','校园商店历史订单收入补记','2026-09-02 18:02:33'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260902171212AFDFFF'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609021808059F2BC247',a.`account_id`,'admin','SHOP_PAYMENT',-192.00,9248.10,o.`order_id`,'e05c5a5b-a816-400a-a914-d48e56e50db7','校园商店订单支付','2026-09-02 18:08:05'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO2026090218075548C676'
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902180805BB7DF81B',a.`account_id`,'teacher01','SHOP_INCOME',192.00,48897.10,o.`order_id`,NULL,'校园商店订单收入','2026-09-02 18:08:05'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO2026090218075548C676'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260902180934D796F62B',a.`account_id`,'teacher01','REIMBURSEMENT_PAYOUT',-140.00,48757.10,NULL,'REIMB-3-OUT','向 teacher01 支付报销款：科研','2026-09-02 18:09:34'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609021809341B5B8A17',a.`account_id`,'admin','REIMBURSEMENT',140.00,9388.10,NULL,'REIMB-3-IN','校园财务报销入账：科研','2026-09-02 18:09:34'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260907221454C10AAD47',a.`account_id`,'213242789','REIMBURSEMENT_PAYOUT',-100.00,48657.10,NULL,'REIMB-4-OUT','向 213242789 支付报销款：科研','2026-09-07 22:14:54'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260907221454BDAA1688',a.`account_id`,'admin','REIMBURSEMENT',100.00,9954.80,NULL,'REIMB-4-IN','校园财务报销入账：科研','2026-09-07 22:14:54'
FROM `tbl_bank_account` a
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260907230416D6B73642',a.`account_id`,'admin','TUITION_PAYMENT',-1200.00,8754.80,NULL,'119f23cd-6dd0-4906-a35e-cd25ce4320de','2026学年住宿费','2026-09-07 23:04:16'
FROM `tbl_bank_account` a
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260907230416B0B0CCD3',a.`account_id`,'213242789','CAMPUS_FEE_INCOME',1200.00,49857.10,NULL,NULL,'收到 213242789 缴纳：2026学年住宿费','2026-09-07 23:04:16'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260911164458D440E048',a.`account_id`,'213242790','TRANSFER_OUT',-500.00,49357.10,NULL,'22029d7b-6cd4-4750-a48d-60c5084b00f2','奖学金；转给 213242790（历史批量转账明细）','2026-09-11 16:44:58'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609111644580C7AB12A',a.`account_id`,'admin','TRANSFER_IN',500.00,10500.00,NULL,NULL,'奖学金（批次BT20260911164458D440E048）','2026-09-11 16:44:58'
FROM `tbl_bank_account` a
WHERE a.`user_id` = '213242790' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260911164458A8CAF6C1',a.`account_id`,'admin','TRANSFER_IN',500.00,10500.00,NULL,NULL,'奖学金（批次BT20260911164458D440E048）','2026-09-11 16:44:58'
FROM `tbl_bank_account` a
WHERE a.`user_id` = '213242792' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260911164458HIST0002',a.`account_id`,'213242792','TRANSFER_OUT',-500.00,48857.10,NULL,NULL,'奖学金；转给 213242792（历史批量转账明细）','2026-09-11 16:44:58'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260911165446222006AA',a.`account_id`,'admin','SHOP_PAYMENT',-12.00,10488.00,o.`order_id`,'d29b39e2-c38e-4e72-a7f9-f084b1657d0b','校园商店订单支付','2026-09-11 16:54:46'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260911165437406535'
WHERE a.`user_id` = '213242790' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260911165446FAD0A57D',a.`account_id`,'213242790','SHOP_INCOME',12.00,48869.10,o.`order_id`,NULL,'校园商店订单收入','2026-09-11 16:54:46'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260911165437406535'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609160926101B51F4B3',a.`account_id`,'admin','SHOP_PAYMENT',-128.00,8626.80,o.`order_id`,'5f1927f8-fa7a-423c-986b-ea669b552bfc','校园商店订单支付','2026-09-16 09:26:10'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916092548115DFA'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT2026091609261053BC073A',a.`account_id`,'213242789','SHOP_INCOME',128.00,48997.10,o.`order_id`,NULL,'校园商店订单收入','2026-09-16 09:26:10'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916092548115DFA'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260916094625EF8AC431',a.`account_id`,'213242789','SHOP_REFUND_PAYOUT',-128.00,48869.10,o.`order_id`,NULL,'校园商店退款支出','2026-09-16 09:46:25'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916092548115DFA'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT2026091609462525B6DC93',a.`account_id`,'admin','SHOP_REFUND',128.00,8754.80,o.`order_id`,'0ce18130-46bd-44df-8374-55157c82c6be','校园商店订单退款','2026-09-16 09:46:25'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916092548115DFA'
WHERE a.`user_id` = '213242789' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260916115445913F8FFF',a.`account_id`,'admin','SHOP_PAYMENT',-16.80,10471.20,o.`order_id`,'a77491ca-109d-4dc7-ac26-e0512356d504','校园商店订单支付','2026-09-16 11:54:45'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916115437B83C03'
WHERE a.`user_id` = '213242790' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT20260916115445574413ED',a.`account_id`,'213242790','SHOP_INCOME',16.80,48885.90,o.`order_id`,NULL,'校园商店订单收入','2026-09-16 11:54:45'
FROM `tbl_bank_account` a
LEFT JOIN `tbl_shop_order` o ON o.`order_no` = 'SO20260916115437B83C03'
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609161321256791E8C1',a.`account_id`,'teacher01','TRANSFER_OUT',-500.00,48385.90,NULL,'aa0954ec-6478-4b73-870f-c160444109b2-0','讲座酬金（操作人 admin1）；转给 teacher01','2026-09-16 13:21:25'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'admin' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);
INSERT INTO `tbl_bank_transaction`
(`transaction_no`,`account_id`,`counterparty_user_id`,`transaction_type`,`amount`,
 `balance_after`,`related_order_id`,`request_id`,`remark`,`created_at`)
SELECT 'BT202609161321251081575B',a.`account_id`,'admin','TRANSFER_IN',500.00,9888.10,NULL,NULL,'讲座酬金（操作人 admin1）（批次BT202609161321256791E8C1）','2026-09-16 13:21:25'
FROM `tbl_bank_account` a
WHERE a.`user_id` = 'teacher01' AND @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `transaction_no`=VALUES(`transaction_no`);

-- 8. 财务报销与账单状态：4 笔已通过的报销、1 张已缴清的住宿费
INSERT INTO `tbl_finance_reimbursement`
(`reimbursement_id`,`applicant_id`,`title`,`amount`,`reason`,`status`,`reviewer_id`,
 `review_comment`,`payment_transaction_no`,`created_at`,`reviewed_at`)
SELECT 1,'teacher01','科研',1100.00,'科研','APPROVED','admin','同意','BT20260902171701112E43C4','2026-09-02 17:15:18','2026-09-02 17:17:01'
FROM DUAL WHERE @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `reimbursement_id`=VALUES(`reimbursement_id`);
INSERT INTO `tbl_finance_reimbursement`
(`reimbursement_id`,`applicant_id`,`title`,`amount`,`reason`,`status`,`reviewer_id`,
 `review_comment`,`payment_transaction_no`,`created_at`,`reviewed_at`)
SELECT 2,'teacher01','科研',270.00,'科研','APPROVED','admin','同意','BT20260902173219FD286FF4','2026-09-02 17:31:10','2026-09-02 17:32:19'
FROM DUAL WHERE @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `reimbursement_id`=VALUES(`reimbursement_id`);
INSERT INTO `tbl_finance_reimbursement`
(`reimbursement_id`,`applicant_id`,`title`,`amount`,`reason`,`status`,`reviewer_id`,
 `review_comment`,`payment_transaction_no`,`created_at`,`reviewed_at`)
SELECT 3,'teacher01','科研',140.00,'科研','APPROVED','admin','','BT202609021809341B5B8A17','2026-09-02 18:08:52','2026-09-02 18:09:34'
FROM DUAL WHERE @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `reimbursement_id`=VALUES(`reimbursement_id`);
INSERT INTO `tbl_finance_reimbursement`
(`reimbursement_id`,`applicant_id`,`title`,`amount`,`reason`,`status`,`reviewer_id`,
 `review_comment`,`payment_transaction_no`,`created_at`,`reviewed_at`)
SELECT 4,'213242789','科研',100.00,'科研','APPROVED','admin','通过','BT20260907221454BDAA1688','2026-09-07 22:14:17','2026-09-07 22:14:54'
FROM DUAL WHERE @seed_demo_bank = 1
ON DUPLICATE KEY UPDATE `reimbursement_id`=VALUES(`reimbursement_id`);

UPDATE `tbl_finance_bill` SET `status`='PAID', `payment_transaction_no`='BT20260907230416D6B73642', `paid_at`='2026-09-07 23:04:16'
WHERE `user_id`='213242789' AND `bill_type`='ACCOMMODATION' AND @seed_demo_bank = 1;

-- 9. 账户余额收尾：让余额与上面的流水最后一笔 balance_after 对齐，并同步用户表镜像字段
UPDATE `tbl_bank_account` SET `balance`=8754.80, `version`=`version`+1
WHERE `user_id`='213242789' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=9888.10, `version`=`version`+1
WHERE `user_id`='teacher01' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=48385.90, `version`=`version`+1
WHERE `user_id`='admin' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10471.20, `version`=`version`+1
WHERE `user_id`='213242790' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='213242791' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10500.00, `version`=`version`+1
WHERE `user_id`='213242792' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='213242793' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='213242794' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='223242801' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='223242802' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='233242815' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='admin1' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='admin2' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='admin3' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='admin4' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher02' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher03' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher04' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher05' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher06' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher07' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher08' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher09' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher10' AND @seed_demo_bank = 1;
UPDATE `tbl_bank_account` SET `balance`=10000.00, `version`=`version`+1
WHERE `user_id`='teacher11' AND @seed_demo_bank = 1;

UPDATE `tbl_user` u
JOIN `tbl_bank_account` b ON b.`user_id` = u.`UID`
SET u.`balance` = b.`balance`
WHERE @seed_demo_bank = 1;

-- ==================== 商店与银行演示业务数据结束 ====================

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
'【校园银行与缴费指南】\n1. 一卡通余额与校园银行余额统一为同一个校园账户余额，可用于转账、账单缴费和校园消费。\n2. 缴费流程：师生登录虚拟校园系统后，在主页点击“银行”进入校园银行模块；在“我的账单”中可查看未缴费用（包含学年学费、住宿费等）；勾选账单并核对金额后，输入6位数字支付密码即可完成缴费扣款。\n3. 账户安全：用户忘记支付密码时可联系管理员重置；转账时需输入收款人一卡通号，确认转账金额后完成实时转账。\n4. 财务报销：教职工可在“财务报销”提交报销申请，填写事项、金额及事由，由管理员审核通过后资金将自动发放至申请人校园账户。',
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
(2, 1, 1, '【校园银行安全与转账】一卡通余额与校园银行余额统一为校园账户余额。忘记支付密码时可联系管理员重置；转账时需输入收款人一卡通号，确认金额后完成实时转账。教职工可提交报销申请，管理员审核通过后资金自动发放至申请人校园账户。', 155),
(3, 2, 0, '【学籍管理规程】学籍查询：学生可在“学籍”模块查看本人基本信息、院系、专业、学号、学籍状态以及所获荣誉奖励与资助记录。信息修改申请：若个人姓名、政治面貌或联系方式等发生变更，需在系统中提交修改申请并上传佐证材料，由院系管理员或校教务处管理员审核通过后方可生效。', 150),
(4, 2, 1, '【转专业与休学】转专业申请通常在每学年春季学期第10-12周开放，学生在学籍系统中提交意向申请；因病休学或复学需提交医院证明并由教务处统一审批。对学籍处理决定有异议的，可在收到通知起5个工作日内向学生申诉处理委员会提起书面申诉。', 130),
(5, 3, 0, '【图书馆借阅规范】借阅权限：学生用户最多可同时借阅10本图书，借期为30天；教师用户最多可借阅20本图书，借期为60天。续借规则：在图书未超期且无他人预约的情况下，可在线续借1次，续借期限为30天。超期未还图书将按每天每本0.10元收取超期违约金，逾期未缴费将暂停借阅与预约权限。', 160),
(6, 3, 1, '【图书预约与挂失】当所需图书处于“借出”状态时，可在系统中点击“预约”，图书归还后系统将保留3天，并发送通知提醒读者前往总服务台借取。若图书不慎遗失，应及时在图书馆系统办理挂失并按规定进行赔偿。', 115),
(7, 4, 0, '【选课流程与制度】选课轮次：每学期选课分为三轮。第一轮为预选（抽签制，不分先后）；第二轮为正选（先到先得，即选即中）；第三轮为退补选（开学前两周开放）。学分限制：学生每学期选修课程总学分原则上不低于15学分，最高不超过32学分。退选截止时间为开学第二周周日24:00。', 155),
(8, 5, 0, '【校园商店操作指引】选购与下单：在商店首页浏览商品加入购物车结算，待支付订单有效期为30分钟，超时未支付订单将自动取消并释放库存。商店支持使用校园银行虚拟账户结账。针对已支付订单可提交退款申请，经管理员审核后资金原路退回校园银行账户。', 150)
ON DUPLICATE KEY UPDATE `content`=VALUES(`content`);

-- ============================================================
-- 1. 图书表 tblBook
-- ============================================================
CREATE TABLE IF NOT EXISTS `tblBook` (
`id` INT NOT NULL AUTO_INCREMENT COMMENT '图书编号',
`isbn` VARCHAR(20) NOT NULL COMMENT 'ISBN编号',
`name` VARCHAR(100) NOT NULL COMMENT '图书名称',
`author` VARCHAR(100) NOT NULL COMMENT '图书作者',
`publisher` VARCHAR(100) DEFAULT '' COMMENT '出版社',
`price` DECIMAL(10,2) DEFAULT NULL COMMENT '图书赔偿价格，借出前录入',
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
`bookPrice` DECIMAL(10,2) DEFAULT NULL COMMENT '借出时的书价',
`feeStopTime` DATETIME DEFAULT NULL COMMENT '首次挂失时冻结逾期计费',
`settledTime` DATETIME DEFAULT NULL COMMENT '遗失赔偿结清时间',
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
                                               `borrowId` INT DEFAULT NULL COMMENT '关联借阅，历史手工罚款为空',
                                               `overdueAmount` DECIMAL(10,2) NOT NULL DEFAULT 0,
                                               `lossAmount` DECIMAL(10,2) NOT NULL DEFAULT 0,
                                               `paidAmount` DECIMAL(10,2) NOT NULL DEFAULT 0,
                                               `refundedAmount` DECIMAL(10,2) NOT NULL DEFAULT 0,
                                               `transactionNo` VARCHAR(64) DEFAULT NULL,
                                               UNIQUE KEY `uk_library_fine_borrow` (`borrowId`),
                                               `status` INT NOT NULL DEFAULT 0 COMMENT '缴费状态: 0-未缴费, 1-已缴费',
                                               PRIMARY KEY (`id`),
                                               KEY `idx_userid` (`userid`),
                                               KEY `idx_status` (`status`),
                                               CONSTRAINT `fk_fine_user` FOREIGN KEY (`userid`) REFERENCES `tbl_user` (`uid`) ON DELETE RESTRICT ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='罚款记录表';

-- 旧库新增字段由 LibrarySchema 在图书馆首次请求时原地升级；不创建新数据库。

-- 图书馆演示数据已移到 sample_library_data.sql。
-- 正常启动或重新构建不需要重新导入演示数据。

-- 主初始化脚本至少提供基础书目，避免只执行 init.sql 后图书馆为空。
-- 已存在的 ISBN 只更新基础信息，不覆盖借阅、预约或挂失产生的状态。
INSERT INTO `tblBook` (`isbn`,`name`,`author`,`publisher`,`status`) VALUES
('978-7-302-12345-6','数据库系统概论','王珊','清华大学出版社',0),
('978-7-111-67890-1','深入理解计算机系统','Randal E. Bryant','机械工业出版社',0),
('978-7-121-34567-8','算法导论','Thomas H. Cormen','电子工业出版社',0),
('978-7-302-98765-4','软件工程','Roger S. Pressman','清华大学出版社',0),
('978-7-111-54321-0','计算机网络：自顶向下方法','James F. Kurose','机械工业出版社',0),
('978-7-302-11111-1','操作系统概念','Abraham Silberschatz','清华大学出版社',0),
('978-7-121-22222-2','Python编程从入门到实践','Eric Matthes','电子工业出版社',0),
('978-7-111-33333-3','数据结构与算法分析','Mark Allen Weiss','机械工业出版社',0)
ON DUPLICATE KEY UPDATE
`name`=VALUES(`name`),
`author`=VALUES(`author`),
`publisher`=VALUES(`publisher`);

-- ============================================================
-- 7. 管理员子系统分权表 tbl_admin_permission
-- ============================================================
CREATE TABLE IF NOT EXISTS `tbl_admin_permission` (
    `uid` VARCHAR(64) NOT NULL COMMENT '管理员一卡通号/账号UID',
    `academic_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '学籍管理权限: 0-无, 1-有',
    `library_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '图书馆管理权限: 0-无, 1-有',
    `course_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '选课管理权限: 0-无, 1-有',
    `shop_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '商店管理权限: 0-无, 1-有',
    `bank_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '银行管理权限: 0-无, 1-有',
    `user_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '用户管理权限: 0-无, 1-有',
    `updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`uid`),
    CONSTRAINT `fk_admin_perm_user` FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员子系统分权表';

SET @user_perm_column_missing = (
    SELECT COUNT(*) = 0 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tbl_admin_permission' AND COLUMN_NAME = 'user_perm'
);
SET @user_perm_ddl = IF(@user_perm_column_missing,
    'ALTER TABLE `tbl_admin_permission` ADD COLUMN `user_perm` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''用户管理权限: 0-无, 1-有'' AFTER `bank_perm`',
    'SELECT 1');
PREPARE user_perm_stmt FROM @user_perm_ddl;
EXECUTE user_perm_stmt;
DEALLOCATE PREPARE user_perm_stmt;

-- 主管理员 admin 默认全为 0 (无业务权限且只读)
INSERT INTO `tbl_admin_permission` (`uid`, `academic_perm`, `library_perm`, `course_perm`, `shop_perm`, `bank_perm`, `user_perm`)
VALUES ('admin', 0, 0, 0, 0, 0, 0)
ON DUPLICATE KEY UPDATE `academic_perm`=0, `library_perm`=0, `course_perm`=0, `shop_perm`=0, `bank_perm`=0, `user_perm`=0;



USE virtual_campus;

ALTER TABLE tbl_product
    DROP CHECK chk_product_category,
    ADD CONSTRAINT chk_product_category
        CHECK (category IN ('文具', '教材资料', '校园纪念品', '生活用品', '食品'));

-- ============================================================
-- 8. 聊天与群聊系统表
-- ============================================================
CREATE TABLE IF NOT EXISTS `tbl_chat_friend` (
    `user_low` VARCHAR(32) NOT NULL,
    `user_high` VARCHAR(32) NOT NULL,
    `requester` VARCHAR(32) NOT NULL,
    `status` VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`user_low`, `user_high`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `tbl_chat_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `sender` VARCHAR(32) NOT NULL,
    `recipient` VARCHAR(32) NOT NULL,
    `client_id` VARCHAR(36) NOT NULL,
    `content` TEXT NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `read_at` TIMESTAMP NULL,
    UNIQUE KEY `uq_chat_retry` (`sender`, `client_id`),
    KEY `ix_chat_inbox` (`recipient`, `read_at`, `id`),
    KEY `ix_chat_history` (`sender`, `recipient`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `tbl_chat_group` (
 offering_id BIGINT NULL,
 UNIQUE KEY uq_chat_group_offering(offering_id),
    `group_id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `name` VARCHAR(100) NOT NULL COMMENT '群聊名称',
    `owner_uid` VARCHAR(32) NOT NULL COMMENT '群主一卡通号',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY `ix_chat_group_owner` (`owner_uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `tbl_chat_group_member` (
    `group_id` BIGINT NOT NULL,
    `uid` VARCHAR(32) NOT NULL,
    `role` VARCHAR(12) NOT NULL DEFAULT 'MEMBER' COMMENT 'OWNER 或 MEMBER',
    `last_read_id` BIGINT NOT NULL DEFAULT 0 COMMENT '已读最新消息ID',
    `joined_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`group_id`, `uid`),
    KEY `ix_chat_group_member_uid` (`uid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS `tbl_chat_group_message` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `group_id` BIGINT NOT NULL,
    `sender` VARCHAR(32) NOT NULL,
    `client_id` VARCHAR(36) NOT NULL,
    `content` TEXT NOT NULL,
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY `uq_group_msg_retry` (`sender`, `client_id`),
    KEY `ix_group_msg_history` (`group_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. 校园统一消息通知中心
CREATE TABLE IF NOT EXISTS `tbl_system_notification` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `recipient_uid` VARCHAR(32) NOT NULL COMMENT '接收人一卡通号',
    `category` VARCHAR(32) NOT NULL DEFAULT 'SYSTEM' COMMENT '分类: CHAT, BANK, REVIEW, LIBRARY, SHOP, SYSTEM',
    `title` VARCHAR(128) NOT NULL COMMENT '通知标题',
    `content` VARCHAR(512) NOT NULL COMMENT '通知内容',
    `link_action` VARCHAR(64) DEFAULT NULL COMMENT '跳转目标: CHAT, BANK, STUDENT_STATUS, TEACHER_STATUS, LIBRARY, SHOP',
    `is_read` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '0-未读, 1-已读',
    `created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY `ix_notif_recipient` (`recipient_uid`, `is_read`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
