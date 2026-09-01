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

-- 学籍管理
CREATE TABLE IF NOT EXISTS tblStudent (
    studentId VARCHAR(20) PRIMARY KEY, userId VARCHAR(32) NOT NULL UNIQUE,
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
    CONSTRAINT fk_student_user FOREIGN KEY(userId) REFERENCES tbl_user(uid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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

CREATE TABLE IF NOT EXISTS tblStudentChangeItem (
    itemId BIGINT PRIMARY KEY AUTO_INCREMENT,
    requestId BIGINT NOT NULL,
    fieldName VARCHAR(50) NOT NULL,
    oldValue VARCHAR(255),
    newValue VARCHAR(255) NOT NULL,
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

INSERT INTO tblStudent(
 studentId,userId,name,gender,politicalStatus,nationality,idType,idNumber,idIssueDate,birthDate,
 nativePlace,householdType,birthPlace,sourcePlace,registeredResidence,leagueMember,leagueJoinDate,
 partyMember,partyJoinDate,healthStatus,studentCategory,registered,inSchool,studentStatus,campus,
 grade,college,major,className,educationLevel,trainingMode,schoolingLength,counselorName,
 counselorPhone,candidateCategory,admissionDate,admissionMethod,graduationSchool,middleSchoolClass,
 middleSchoolTeacher,telephone,mobile,email,qq,wechat,campusAddress,emergencyContact,emergencyPhone
) VALUES (
 '213242789','213242789','张三','男','共青团员','汉族','居民身份证','320100200801010001',
 '2024-01-01','2008-01-01','江苏南京','城镇','江苏南京','江苏南京','江苏省南京市',
 1,'2022-05-04',0,NULL,'健康','本科生',1,1,'在籍','九龙湖校区','2026级',
 '计算机科学与工程学院','计算机科学与技术','计科一班','本科','全日制',4,
 '王老师','13900000000','普通类','2026-09-01','普通高考','南京市第一中学',
 '高三一班','李老师','025-00000000','13800138000','zhangsan@seu.edu.cn',
 '213242789','zhangsan_seu','九龙湖校区学生宿舍','张家长','13700000000'
)
ON DUPLICATE KEY UPDATE
 name=VALUES(name),grade=VALUES(grade),college=VALUES(college),major=VALUES(major);
