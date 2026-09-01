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
 studentId,userId,name,gender,politicalStatus,nationality,idType,idNumber,idIssueDate,birthDate,
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
