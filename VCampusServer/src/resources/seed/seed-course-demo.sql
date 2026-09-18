-- =============================================================================
-- 课程模块演示数据（course-module demo fixtures）
--
-- 用途：灌进真实库 / 演示库 `virtual_campus`，让人工演示有课可看。可重复执行：
--   1. 先按 docs/数据库构建.md 把库建好（init.sql + migrations/V001…V007 全部跑完）；
--   2. 再灌本文件：
--        mysql --default-character-set=utf8mb4 -uroot -p virtual_campus < seed-course-demo.sql
--   3. 重跑只会刷新下面列出的 fixture 区间，区间之外（真实业务数据）一律不动。
--
-- 与 seed-course-test.sql 的关系：那份是自动化测试用的夹具（账号是 course-test-only 的
-- 合成账号，测试按名字引用），本文件是人工演示用的数据（账号与 init.sql 对齐）。
-- 两份文件的 ID 区间有重叠，**不要灌进同一个库**。
--
-- 账号：UID / 姓名 / 学院 / 专业 / 密码盐与哈希全部与 init.sql 的 tbl_user 一致
--      （盐 dGVzdHNhbHQxMjM0NTY=，明文密码 123456）。冲突时只更新 name，不改 password，
--      因此往真实库里重灌不会改掉任何人的登录密码。
--      teacher02 是本文件相对 init.sql 新增的账号（init.sql 只有一位教师），
--      它没有 tblTeacher 人事档案行——课程模块不需要，人事页面查不到属预期。
--
-- 本文件负责的 ID 区间：
--   major 10-19；course 1001-1099；course_major/course_year 课程侧同区间；
--   course_offering 2000-2099；course_offering_teacher 教学班侧同区间；
--   teaching_calendar 3000-3099；day_template 3100-3199；period_definition 3201-3208；
--   calendar_date 3300-3399（3301/3302 为第 1 周周二/周四，其余按公式生成）；
--   schedule_plan 4001；course_schedule_arrangement / course_schedule_rule 4101-4199；
--   course_occurrence 4201-4299 与 5200-5599；classroom 4300-4399；schedule_resource 4401-4499；
--   resource_booking 4501-4599 与 52000-55999；student_academic_profile 5001-5002、5010-5099；
--   course_selection_window 5101；enrollment 6001-6099；grade 6101-6199；
--   course_plan_item 6201-6299；course_waitlist 6301-6399；training_plan 7000-7099；
--   training_plan_group / training_plan_course 7100-7299；course_notice 8001-8099；
--   course_operation_log operation_id 00000000-0000-0000-0000-0000000000{01,02}；
--   course_event_outbox 9001-9099。
--
-- 时间：course_occurrence.start_at/end_at 存 UTC。夹具日历为 Asia/Shanghai（UTC+8），
--      因此由"本地日期 + 节次起止"减去 8 小时得到。
-- =============================================================================

-- V004 给 course_schedule_rule 加了 NOT NULL 的 arrangement_id，并约束每个 rule 对应一条
-- course_schedule_arrangement。正常情况下演示库会先跑完 V001…V007 再灌本文件，此时列已存在；
-- 但为了能灌进"还没跑 V004"的库（迁移测试就是这么装的），这里探测该列并据此准备对应语句。
SET @seed_has_arrangement = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_schedule_rule'
      AND COLUMN_NAME = 'arrangement_id'
);

-- -----------------------------------------------------------------------------
-- 1. 幂等清理：只删除本文件负责的 fixture 区间，子表在前。
-- -----------------------------------------------------------------------------
DELETE FROM `resource_booking`
 WHERE `id` BETWEEN 4501 AND 4599
    OR `id` BETWEEN 52000 AND 55999;
DELETE FROM `course_occurrence`
 WHERE `id` BETWEEN 4201 AND 4299
    OR `id` BETWEEN 5200 AND 5599;
DELETE FROM `course_schedule_rule_week` WHERE `rule_id` BETWEEN 4101 AND 4199;
DELETE FROM `course_schedule_rule` WHERE `id` BETWEEN 4101 AND 4199;
SET @seed_arrangement_delete = IF(@seed_has_arrangement > 0,
    'DELETE FROM `course_schedule_arrangement` WHERE `arrangement_id` BETWEEN 4101 AND 4199',
    'SELECT 1');
PREPARE seed_arrangement_delete FROM @seed_arrangement_delete;
EXECUTE seed_arrangement_delete;
DEALLOCATE PREPARE seed_arrangement_delete;
DELETE FROM `course_offering_conflict`
 WHERE `plan_id` = 4001
   AND `course_offering_a_id` BETWEEN 2000 AND 2099
   AND `course_offering_b_id` BETWEEN 2000 AND 2099;
-- 先松开教学日历上的"当前方案"指针，否则重跑时指针会挡住下面方案的删除。
-- current_schedule_plan_id 是 V004 加的列，旧装载顺序（seed 在 V004 之前）里还不存在。
SET @seed_has_calendar_pointer = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'teaching_calendar'
      AND COLUMN_NAME = 'current_schedule_plan_id'
);
SET @seed_pointer_sql = IF(@seed_has_calendar_pointer > 0,
    'UPDATE `teaching_calendar` SET `current_schedule_plan_id` = NULL WHERE `current_schedule_plan_id` BETWEEN 4000 AND 4099',
    'SELECT 1');
PREPARE seed_pointer FROM @seed_pointer_sql;
EXECUTE seed_pointer;
DEALLOCATE PREPARE seed_pointer;
DELETE FROM `course_waitlist` WHERE `waitlist_id` BETWEEN 6301 AND 6399;
DELETE FROM `course_plan_item` WHERE `plan_item_id` BETWEEN 6201 AND 6299;
DELETE FROM `grade` WHERE `grade_id` BETWEEN 6101 AND 6199;
DELETE FROM `enrollment` WHERE `enrollment_id` BETWEEN 6001 AND 6099;
DELETE FROM `course_selection_window` WHERE `window_id` BETWEEN 5100 AND 5199;
-- 方案的所有子表（规则、实例、预订、安排、冲突、选课窗口）都已清空，这里才能删方案本身。
DELETE FROM `schedule_plan` WHERE `id` BETWEEN 4000 AND 4099;
DELETE FROM `student_academic_profile`
 WHERE `profile_id` BETWEEN 5001 AND 5002
    OR `profile_id` BETWEEN 5010 AND 5099;
DELETE FROM `course_event_outbox` WHERE `event_id` BETWEEN 9001 AND 9099;
DELETE FROM `course_operation_log`
 WHERE `operation_id` IN ('00000000-0000-0000-0000-000000000001',
                          '00000000-0000-0000-0000-000000000002');
DELETE FROM `course_notice` WHERE `notice_id` BETWEEN 8001 AND 8099;
DELETE FROM `training_plan_course` WHERE `group_id` BETWEEN 7100 AND 7299;
DELETE FROM `training_plan_group` WHERE `group_id` BETWEEN 7100 AND 7299;
DELETE FROM `training_plan` WHERE `plan_id` BETWEEN 7000 AND 7099;
DELETE FROM `schedule_resource` WHERE `id` BETWEEN 4401 AND 4499;
DELETE FROM `classroom` WHERE `id` BETWEEN 4300 AND 4399;
DELETE FROM `calendar_date` WHERE `id` BETWEEN 3300 AND 3399;
DELETE FROM `period_definition` WHERE `id` BETWEEN 3201 AND 3299;
DELETE FROM `day_template` WHERE `id` BETWEEN 3100 AND 3199;
DELETE FROM `teaching_calendar` WHERE `id` BETWEEN 3000 AND 3099;
DELETE FROM `course_offering_teacher` WHERE `offering_id` BETWEEN 2000 AND 2099;
DELETE FROM `course_offering` WHERE `offering_id` BETWEEN 2000 AND 2099;
DELETE FROM `course_year` WHERE `course_id` BETWEEN 1000 AND 1099;
DELETE FROM `course_major` WHERE `course_id` BETWEEN 1000 AND 1099;
DELETE FROM `course` WHERE `course_id` BETWEEN 1000 AND 1099;
DELETE FROM `major` WHERE `major_id` BETWEEN 10 AND 19;

-- -----------------------------------------------------------------------------
-- 2. 账号：与 init.sql 的 tbl_user 逐列一致；teacher02 为本文件新增的教师。
-- -----------------------------------------------------------------------------
INSERT INTO `tbl_user`
    (`UID`, `name`, `gender`, `password`, `salt`, `role`, `college`, `major`,
     `phone`, `email`, `balance`)
VALUES
    ('213242789', '张三', '男',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '计算机科学与工程学院', '计算机科学与技术', '13800138000', 'zhangsan@seu.edu.cn', 10000.00),
    ('213242790', '李雨桐', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '电子科学与工程学院', '信息工程', '13800138001', 'liyutong@seu.edu.cn', 10000.00),
    ('213242791', '王浩然', '男',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '机械工程学院', '机器人工程', '13800138002', 'wanghaoran@seu.edu.cn', 10000.00),
    ('213242793', '周可欣', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '经济管理学院', '金融学', '13800138004', 'zhouke@seu.edu.cn', 10000.00),
    ('223242801', '孙婉清', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '外国语学院', '英语', '13800138006', 'sunwanqing@seu.edu.cn', 10000.00),
    ('223242802', '吴承宇', '男',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '计算机科学与工程学院', '人工智能', '13800138007', 'wuchengyu@seu.edu.cn', 10000.00),
    ('233242815', '郑晓彤', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 2,
     '医学院', '临床医学', '13800138008', 'zhengxiaotong@seu.edu.cn', 10000.00),
    ('teacher01', '李老师', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1,
     '计算机科学与工程学院', '副教授', '13700137000', 'teacher@seu.edu.cn', 10000.00),
    ('teacher02', '王建国', '男',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 1,
     '计算机科学与工程学院', '讲师', '13700137001', 'teacher02@seu.edu.cn', 10000.00),
    ('admin3', '管理员3', '女',
     'tECnNTmvtuITz4kN9fLAhO+T9HYBzxnCIqiBpldvAfM=', 'dGVzdHNhbHQxMjM0NTY=', 0,
     '教务处', '课程管理', '18800000003', 'alexander.jwc@seu.edu.cn', 50000.00)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`);

-- -----------------------------------------------------------------------------
-- 3. 专业与课程
-- -----------------------------------------------------------------------------
INSERT INTO `major` (`major_id`, `major_code`, `major_name`, `college`)
VALUES
    (10, 'CS', '计算机科学与技术', '计算机科学与工程学院'),
    (11, 'IE', '信息工程', '电子科学与工程学院'),
    (12, 'ME', '机器人工程', '机械工程学院'),
    (13, 'AI', '人工智能', '计算机科学与工程学院'),
    (14, 'FE', '金融学', '经济管理学院'),
    (15, 'EN', '英语', '外国语学院'),
    (16, 'CM', '临床医学', '医学院');

INSERT INTO `course`
    (`course_id`, `course_code`, `course_name`, `credit`, `credit_hours`,
     `course_type`, `allow_cross_major`, `description`, `prerequisites`, `final_exam`)
VALUES
    (1001, 'CS101', '程序设计基础', 3.00, 48, 1, 1,
     '程序设计方法与问题求解', NULL, 1),
    (1002, 'CS202', '数据库系统原理', 3.00, 48, 1, 0,
     '关系数据模型、SQL 与事务管理', 'CS101', 1),
    (1003, 'CS201', '数据结构', 3.00, 48, 1, 0,
     '线性表、树、图与常用算法分析', 'CS101', 1),
    (1004, 'CS301', '操作系统', 3.00, 48, 1, 0,
     '进程、内存、文件系统与并发控制', 'CS201', 1),
    (1005, 'CS302', '计算机网络', 2.50, 40, 2, 1,
     '分层网络模型、TCP/IP 与网络编程', 'CS201', 1),
    (1006, 'MA101', '高等数学（上）', 5.00, 80, 1, 1,
     '极限、导数、积分与级数', NULL, 1),
    (1007, 'MA201', '线性代数', 3.00, 48, 1, 1,
     '矩阵、线性方程组与特征值', NULL, 1),
    (1008, 'EE201', '信号与系统', 3.50, 56, 1, 0,
     '连续与离散信号的时频域分析', 'MA101', 1),
    (1009, 'ME201', '机器人学基础', 2.00, 32, 3, 1,
     '位姿描述、运动学与轨迹规划', NULL, 0),
    (1010, 'EC201', '微观经济学', 3.00, 48, 2, 1,
     '供求、消费者与厂商行为', NULL, 1),
    (1011, 'AI201', '机器学习导论', 3.00, 48, 3, 1,
     '监督学习、模型评估与特征工程', 'CS201', 1),
    (1012, 'EN101', '学术英语写作', 2.00, 32, 4, 1,
     '学术论文结构与写作规范', NULL, 0);

INSERT INTO `course_major` (`course_id`, `major_id`)
VALUES
    (1001, 10), (1002, 10),
    (1003, 10), (1003, 13),
    (1004, 10), (1004, 13),
    (1005, 10), (1005, 11),
    (1006, 10), (1006, 11), (1006, 12), (1006, 14),
    (1007, 10), (1007, 11), (1007, 14),
    (1008, 11),
    (1009, 12),
    (1010, 14),
    (1011, 13), (1011, 10),
    (1012, 15);

INSERT INTO `course_year` (`course_id`, `year`)
VALUES
    (1001, 1), (1002, 3),
    (1003, 2), (1004, 3), (1005, 3),
    (1006, 1), (1007, 2),
    (1008, 2), (1009, 3),
    (1010, 2), (1011, 3), (1012, 1);

-- -----------------------------------------------------------------------------
-- 4. 教学班（2001-2004 语义保持不变，仅新增）
-- -----------------------------------------------------------------------------
INSERT INTO `course_offering`
    (`offering_id`, `offering_code`, `course_id`, `academic_year`, `semester`,
     `capacity`, `wanted_count`, `enrolled_count`, `website`, `status`)
VALUES
    (2001, 'CS101-2026-2-A', 1001, 2026, 2, 30, 2, 2, NULL, 2),
    (2002, 'CS101-2026-2-B', 1001, 2026, 2, 30, 2, 2, NULL, 2),
    (2003, 'CS101-2027-3-A', 1001, 2027, 3, 30, 0, 0, NULL, 2),
    (2004, 'CS202-2026-2-A', 1002, 2026, 2, 30, 2, 2, NULL, 2),
    (2010, 'CS201-2026-2-A', 1003, 2026, 2, 60, 7, 4, 'https://seu.edu.cn/course/cs201', 2),
    (2011, 'CS201-2026-2-B', 1003, 2026, 2, 60, 5, 3, NULL, 2),
    (2012, 'CS301-2026-2-A', 1004, 2026, 2, 50, 4, 2, 'https://seu.edu.cn/course/cs301', 2),
    (2013, 'CS302-2026-2-A', 1005, 2026, 2, 40, 6, 4, NULL, 2),
    (2014, 'MA101-2026-2-A', 1006, 2026, 2, 120, 9, 6, NULL, 2),
    (2015, 'MA201-2026-2-A', 1007, 2026, 2, 80, 6, 4, NULL, 2),
    (2016, 'EE201-2026-2-A', 1008, 2026, 2, 45, 5, 3, 'https://seu.edu.cn/course/ee201', 2),
    (2017, 'ME201-2026-2-A', 1009, 2026, 2, 30, 3, 2, NULL, 2),
    (2018, 'EC201-2026-2-A', 1010, 2026, 2, 90, 6, 4, NULL, 2),
    (2019, 'AI201-2026-2-A', 1011, 2026, 2, 40, 5, 3, 'https://seu.edu.cn/course/ai201', 2),
    (2020, 'EN101-2026-2-A', 1012, 2026, 2, 25, 4, 2, NULL, 2),
    (2024, 'CS201-2027-3-A', 1003, 2027, 3, 60, 0, 0, NULL, 1);

INSERT INTO `course_offering_teacher` (`offering_id`, `uid`, `role`)
VALUES
    (2001, 'teacher01', 0),
    (2002, 'teacher02', 0),
    (2004, 'teacher01', 0),
    (2001, 'teacher02', 1),
    (2010, 'teacher01', 0),
    (2011, 'teacher02', 0),
    (2012, 'teacher01', 0),
    (2013, 'teacher02', 0),
    (2014, 'teacher02', 0),
    (2015, 'teacher01', 0),
    (2016, 'teacher02', 0),
    (2017, 'teacher01', 0),
    (2018, 'teacher02', 0),
    (2019, 'teacher01', 0),
    (2020, 'teacher02', 0);

-- -----------------------------------------------------------------------------
-- 5. 教学日历：第 1 周起若干教学日 + 每天 8 节课
-- -----------------------------------------------------------------------------
INSERT INTO `teaching_calendar`
    (`id`, `name`, `academic_year`, `semester`, `week1_start_date`, `timezone`, `version`, `status`)
VALUES
    (3001, '2026-2027 学年第一学期教学日历', 2026, 2, '2026-09-07', 'Asia/Shanghai', 1, 'PUBLISHED');

INSERT INTO `day_template` (`id`, `name`, `version`)
VALUES (3101, '标准工作日课表', 1);

INSERT INTO `period_definition`
    (`id`, `day_template_id`, `period_no`, `start_time`, `end_time`)
VALUES
    (3201, 3101, 1, '08:00:00', '08:45:00'),
    (3202, 3101, 2, '08:50:00', '09:35:00'),
    (3203, 3101, 3, '10:00:00', '10:45:00'),
    (3204, 3101, 4, '10:50:00', '11:35:00'),
    (3205, 3101, 5, '13:30:00', '14:15:00'),
    (3206, 3101, 6, '14:20:00', '15:05:00'),
    (3207, 3101, 7, '15:30:00', '16:15:00'),
    (3208, 3101, 8, '16:20:00', '17:05:00');

-- 第 1 周的周二/周四（3301/3302）保持固定，其余教学日按公式补齐：
--   id = 3300 + 5 * 周次 + 星期，local_date = 第 1 周周一 + (周次-1)*7 + (星期-1) 天
INSERT INTO `calendar_date`
    (`id`, `calendar_id`, `local_date`, `week_no`, `teaching_weekday`,
     `day_template_id`, `is_teaching_day`)
VALUES
    (3301, 3001, '2026-09-08', 1, 2, 3101, 1),
    (3302, 3001, '2026-09-10', 1, 4, 3101, 1);

-- -----------------------------------------------------------------------------
-- 6. 排课方案与教学安排
-- -----------------------------------------------------------------------------
INSERT INTO `schedule_plan`
    (`id`, `name`, `calendar_id`, `revision`, `status`, `created_at`, `updated_at`)
VALUES
    (4001, '2026-2027 学年第一学期排课方案', 3001, 1, 'PUBLISHED',
     '2026-08-01 00:00:00', '2026-08-01 00:00:00');

-- 把教学日历的"当前方案"指针指回 4001。上面第 2 节的幂等清理必须先把它置空（否则指针的外键会挡住
-- schedule_plan 的删除），所以这里必须补回来——只置空不补，教师课表就会解析不到日历：
-- TeacherScheduleDAO.currentCalendar 要求 current_schedule_plan_id 非空且指向本日历的 PUBLISHED 方案，
-- 否则抛「该学期暂无已发布的教学日历」，教师端课程表就是空的。管理端排课视图也读同一个指针。
-- 真实环境里这个指针由管理端"发布方案"写入（AdminScheduleDAO.setCurrentPlan），seed 直接插 PUBLISHED 方案，
-- 就得自己补这一步。FK 指向 schedule_plan，所以只能放在上面 INSERT 之后。
SET @seed_pointer_restore = IF(@seed_has_calendar_pointer > 0,
    'UPDATE `teaching_calendar` SET `current_schedule_plan_id` = 4001 WHERE `id` = 3001',
    'SELECT 1');
PREPARE seed_pointer_restore FROM @seed_pointer_restore;
EXECUTE seed_pointer_restore;
DEALLOCATE PREPARE seed_pointer_restore;

-- 供下面各生成块复用的临时表：周次表、新规则表、教学班-教室对应表。
DROP TEMPORARY TABLE IF EXISTS `seed_weeks`;
CREATE TEMPORARY TABLE `seed_weeks` (
    `week_no` SMALLINT NOT NULL,
    PRIMARY KEY (`week_no`)
) ENGINE = InnoDB;
INSERT INTO `seed_weeks` (`week_no`)
VALUES (1), (2), (3), (4), (5), (6), (7), (8),
       (9), (10), (11), (12), (13), (14), (15), (16);

DROP TEMPORARY TABLE IF EXISTS `seed_new_rules`;
CREATE TEMPORARY TABLE `seed_new_rules` (
    `id` BIGINT NOT NULL,
    `offering_id` BIGINT NOT NULL,
    `weekday` TINYINT NOT NULL,
    `start_period` SMALLINT NOT NULL,
    `end_period` SMALLINT NOT NULL,
    PRIMARY KEY (`id`)
) ENGINE = InnoDB;
INSERT INTO `seed_new_rules` (`id`, `offering_id`, `weekday`, `start_period`, `end_period`)
VALUES
    (4110, 2010, 1, 1, 2), (4111, 2010, 3, 3, 4),
    (4112, 2011, 3, 1, 2), (4113, 2011, 5, 1, 2),
    (4114, 2012, 2, 3, 4), (4115, 2012, 4, 5, 6),
    (4116, 2013, 5, 3, 4),
    (4117, 2014, 1, 3, 4), (4118, 2014, 3, 5, 6),
    (4119, 2015, 5, 5, 6),
    (4120, 2016, 2, 5, 6), (4121, 2016, 4, 7, 8),
    (4122, 2017, 3, 7, 8),
    (4123, 2018, 1, 7, 8),
    (4124, 2019, 5, 7, 8),
    (4125, 2020, 2, 7, 8);

DROP TEMPORARY TABLE IF EXISTS `seed_classrooms`;
CREATE TEMPORARY TABLE `seed_classrooms` (
    `offering_id` BIGINT NOT NULL,
    `classroom_id` BIGINT NOT NULL,
    PRIMARY KEY (`offering_id`)
) ENGINE = InnoDB;
INSERT INTO `seed_classrooms` (`offering_id`, `classroom_id`)
VALUES (2010, 4301), (2011, 4303), (2012, 4301), (2013, 4302), (2014, 4303),
       (2015, 4304), (2016, 4302), (2017, 4303), (2018, 4304), (2019, 4301),
       (2020, 4302);

-- 教室是排课安排的引用数据：course_schedule_arrangement.classroom_id 外键指向 classroom，
-- 所以必须先于下面的安排插入。原先这段在第 8 节，正是安排填不进教室的原因。
INSERT INTO `classroom` (`id`, `name`, `capacity`, `electric`)
VALUES
    (4301, '教一-101', 60, 1),
    (4302, '教二-305', 40, 1),
    (4303, '计算中心-机房 A', 90, 1),
    (4304, '教三-报告厅', 120, 1);

-- 4101-4104 是原有固定夹具（第 1 周，与 course_offering_conflict 里那一条 2001/2002 冲突
-- 记录保持一致），继续单独插入；4110-4125 走种子新规则表，两种装载顺序共用一份数据。
-- arrangement 的 teacher_uid / classroom_id 是教师端课表唯一的取数来源：TeacherScheduleDAO
-- 按 `a.teacher_uid = ? OR a.assistant_uid = ?` 过滤当周课次，并按 a.classroom_id 取教室
-- （见 VCampusServer/src/dao/TeacherScheduleDAO.java）。只写 offering_id 会让教师端课表恒为空。
-- teacher_uid 从 course_offering_teacher（role=0 即任课老师）反查补齐；classroom_id 取自
-- seed_classrooms；4101-4104 不在 seed_classrooms 里，其教室与第 8 节 resource_booking 固定
-- 夹具 4502/4504/4506/4508 保持一致（2001→4301，2002/2004→4302）。
-- 2004 在教学班关联表里没有任课老师，teacher_uid 保持 NULL 属预期。
SET @seed_arrangement_sql = IF(@seed_has_arrangement > 0,
    'INSERT INTO `course_schedule_arrangement` (`arrangement_id`, `plan_id`, `offering_id`, `teacher_uid`, `classroom_id`) VALUES (4101,4001,2001,(SELECT `uid` FROM `course_offering_teacher` WHERE `offering_id`=2001 AND `role`=0),4301),(4102,4001,2001,(SELECT `uid` FROM `course_offering_teacher` WHERE `offering_id`=2001 AND `role`=0),4301),(4103,4001,2002,(SELECT `uid` FROM `course_offering_teacher` WHERE `offering_id`=2002 AND `role`=0),4302),(4104,4001,2004,(SELECT `uid` FROM `course_offering_teacher` WHERE `offering_id`=2004 AND `role`=0),4302)',
    'SELECT 1');
PREPARE seed_arrangement FROM @seed_arrangement_sql;
EXECUTE seed_arrangement;
DEALLOCATE PREPARE seed_arrangement;
SET @seed_arrangement_new_sql = IF(@seed_has_arrangement > 0,
    'INSERT INTO `course_schedule_arrangement` (`arrangement_id`, `plan_id`, `offering_id`, `teacher_uid`, `classroom_id`) SELECT r.`id`, 4001, r.`offering_id`, (SELECT ot.`uid` FROM `course_offering_teacher` ot WHERE ot.`offering_id` = r.`offering_id` AND ot.`role` = 0), sc.`classroom_id` FROM `seed_new_rules` r LEFT JOIN `seed_classrooms` sc ON sc.`offering_id` = r.`offering_id`',
    'SELECT 1');
PREPARE seed_arrangement_new FROM @seed_arrangement_new_sql;
EXECUTE seed_arrangement_new;
DEALLOCATE PREPARE seed_arrangement_new;
SET @seed_rule_sql = IF(@seed_has_arrangement > 0,
    'INSERT INTO `course_schedule_rule` (`id`, `plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`, `arrangement_id`) VALUES (4101,4001,2001,2,1,2,4101),(4102,4001,2001,4,3,4,4102),(4103,4001,2002,2,1,2,4103),(4104,4001,2004,4,1,2,4104)',
    'INSERT INTO `course_schedule_rule` (`id`, `plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`, `status`) VALUES (4101,4001,2001,2,1,2,''ACTIVE''),(4102,4001,2001,4,3,4,''ACTIVE''),(4103,4001,2002,2,1,2,''ACTIVE''),(4104,4001,2004,4,1,2,''ACTIVE'')');
PREPARE seed_rule FROM @seed_rule_sql;
EXECUTE seed_rule;
DEALLOCATE PREPARE seed_rule;
SET @seed_rule_new_sql = IF(@seed_has_arrangement > 0,
    'INSERT INTO `course_schedule_rule` (`id`, `plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`, `arrangement_id`) SELECT `id`, 4001, `offering_id`, `weekday`, `start_period`, `end_period`, `id` FROM `seed_new_rules`',
    'INSERT INTO `course_schedule_rule` (`id`, `plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`, `status`) SELECT `id`, 4001, `offering_id`, `weekday`, `start_period`, `end_period`, ''ACTIVE'' FROM `seed_new_rules`');
PREPARE seed_rule_new FROM @seed_rule_new_sql;
EXECUTE seed_rule_new;
DEALLOCATE PREPARE seed_rule_new;

-- 固定夹具仍是"仅第 1 周"（冲突矩阵里 2001/2002 的 conflict_count=1 依赖这一点）；
-- 新规则覆盖第 1-16 周。
INSERT INTO `course_schedule_rule_week` (`rule_id`, `week_no`)
VALUES (4101, 1), (4102, 1), (4103, 1), (4104, 1);

INSERT INTO `course_schedule_rule_week` (`rule_id`, `week_no`)
SELECT r.`id`, w.`week_no`
FROM `course_schedule_rule` r
JOIN `seed_new_rules` nr ON nr.`id` = r.`id`
CROSS JOIN `seed_weeks` w;

-- -----------------------------------------------------------------------------
-- 7. 课程实例（course_occurrence）：固定 4 条 + 新教学班 × 第 1-16 周
--    固定 4 条的 id 为 4201-4204；生成部分 id = 5000 + (rule_id-4100)*20 + 周次
--    （4110 周 1 → 5201，4125 周 16 → 5516）。
-- -----------------------------------------------------------------------------
INSERT INTO `course_occurrence`
    (`id`, `rule_id`, `plan_id`, `start_at`, `end_at`, `week_no`, `teaching_weekday`)
VALUES
    (4201, 4101, 4001, '2026-09-08 00:00:00', '2026-09-08 01:35:00', 1, 2),
    (4202, 4102, 4001, '2026-09-10 02:00:00', '2026-09-10 03:35:00', 1, 4),
    (4203, 4103, 4001, '2026-09-08 00:00:00', '2026-09-08 01:35:00', 1, 2),
    (4204, 4104, 4001, '2026-09-10 00:00:00', '2026-09-10 01:35:00', 1, 4);

-- 第 1-16 周、周一至周五的教学日；第 1 周周二/周四已由 3301/3302 覆盖，不再重复生成。
-- 必须先建教学日，下面的课程实例生成要按 (周次, 星期) 关联到它。
INSERT INTO `calendar_date`
    (`id`, `calendar_id`, `local_date`, `week_no`, `teaching_weekday`,
     `day_template_id`, `is_teaching_day`)
SELECT 3300 + 5 * w.`week_no` + d.`weekday`,
       3001,
       DATE_ADD('2026-09-07', INTERVAL ((w.`week_no` - 1) * 7 + d.`weekday` - 1) DAY),
       w.`week_no`, d.`weekday`, 3101, 1
FROM `seed_weeks` w
JOIN (SELECT 1 AS `weekday` UNION ALL SELECT 2 UNION ALL SELECT 3
      UNION ALL SELECT 4 UNION ALL SELECT 5) d
WHERE NOT (w.`week_no` = 1 AND d.`weekday` IN (2, 4));

INSERT INTO `course_occurrence`
    (`id`, `rule_id`, `plan_id`, `start_at`, `end_at`, `week_no`, `teaching_weekday`)
SELECT 5000 + (r.`id` - 4100) * 20 + rw.`week_no`,
       r.`id`, r.`plan_id`,
       DATE_SUB(TIMESTAMP(cd.`local_date`, p1.`start_time`), INTERVAL 8 HOUR),
       DATE_SUB(TIMESTAMP(cd.`local_date`, p2.`end_time`), INTERVAL 8 HOUR),
       rw.`week_no`, r.`weekday`
FROM `course_schedule_rule` r
JOIN `seed_new_rules` nr ON nr.`id` = r.`id`
JOIN `course_schedule_rule_week` rw ON rw.`rule_id` = r.`id`
JOIN `schedule_plan` sp ON sp.`id` = r.`plan_id`
JOIN `teaching_calendar` tc ON tc.`id` = sp.`calendar_id`
JOIN `calendar_date` cd
  ON cd.`calendar_id` = tc.`id`
 AND cd.`week_no` = rw.`week_no`
 AND cd.`teaching_weekday` = r.`weekday`
 AND cd.`is_teaching_day` = 1
JOIN `period_definition` p1
  ON p1.`day_template_id` = cd.`day_template_id`
 AND p1.`period_no` = r.`start_period`
JOIN `period_definition` p2
  ON p2.`day_template_id` = cd.`day_template_id`
 AND p2.`period_no` = r.`end_period`;

-- -----------------------------------------------------------------------------
-- 8. 资源与资源预订（classroom 已上移到第 6 节：排课安排要引用它）
-- -----------------------------------------------------------------------------
INSERT INTO `schedule_resource` (`id`, `resource_type`, `business_id`, `conflict_mode`)
VALUES
    (4401, 'teacher', 'teacher01', 'EXCLUSIVE'),
    (4402, 'teacher', 'teacher02', 'EXCLUSIVE'),
    (4403, 'classroom', '4301', 'EXCLUSIVE'),
    (4404, 'classroom', '4302', 'EXCLUSIVE'),
    (4405, 'classroom', '4303', 'EXCLUSIVE'),
    (4406, 'classroom', '4304', 'EXCLUSIVE');

INSERT INTO `resource_booking`
    (`id`, `plan_id`, `occurrence_id`, `resource_id`, `resource_role`)
VALUES
    (4501, 4001, 4201, 4401, 'TEACHER'),
    (4502, 4001, 4201, 4403, 'CLASSROOM'),
    (4503, 4001, 4202, 4401, 'TEACHER'),
    (4504, 4001, 4202, 4403, 'CLASSROOM'),
    (4505, 4001, 4203, 4402, 'TEACHER'),
    (4506, 4001, 4203, 4404, 'CLASSROOM'),
    (4507, 4001, 4204, 4402, 'TEACHER'),
    (4508, 4001, 4204, 4404, 'CLASSROOM');

-- 生成部分：id = 课程实例 id * 10 + 1（教师）/ + 2（教室）
INSERT INTO `resource_booking`
    (`id`, `plan_id`, `occurrence_id`, `resource_id`, `resource_role`)
SELECT o.`id` * 10 + 1, o.`plan_id`, o.`id`, sr.`id`, 'TEACHER'
FROM `course_occurrence` o
JOIN `course_schedule_rule` r ON r.`id` = o.`rule_id`
JOIN `course_offering_teacher` t
  ON t.`offering_id` = r.`course_offering_id` AND t.`role` = 0
JOIN `schedule_resource` sr
  ON sr.`resource_type` = 'teacher' AND sr.`business_id` = t.`uid`
WHERE o.`id` BETWEEN 5200 AND 5599;

INSERT INTO `resource_booking`
    (`id`, `plan_id`, `occurrence_id`, `resource_id`, `resource_role`)
SELECT o.`id` * 10 + 2, o.`plan_id`, o.`id`, sr.`id`, 'CLASSROOM'
FROM `course_occurrence` o
JOIN `course_schedule_rule` r ON r.`id` = o.`rule_id`
JOIN `seed_classrooms` sc ON sc.`offering_id` = r.`course_offering_id`
JOIN `schedule_resource` sr
  ON sr.`resource_type` = 'classroom'
 AND sr.`business_id` = CAST(sc.`classroom_id` AS CHAR)
WHERE o.`id` BETWEEN 5200 AND 5599;

-- 选课冲突矩阵：只保留固定夹具这一条（2001 与 2002 同为周二 1-2 节）。
-- 新增教学班的时段两两不重叠，也不与 2001-2004 重叠，因此不需要新增冲突行。
INSERT INTO `course_offering_conflict`
    (`plan_id`, `course_offering_a_id`, `course_offering_b_id`, `conflict_count`,
     `first_conflict_at`, `last_conflict_at`)
VALUES
    (4001, 2001, 2002, 1, '2026-09-08 00:00:00', '2026-09-08 00:00:00');

-- -----------------------------------------------------------------------------
-- 9. 学籍、选课窗口、选课与成绩
-- -----------------------------------------------------------------------------
INSERT INTO `student_academic_profile`
    (`profile_id`, `uid`, `major_id`, `cohort_year`, `status`)
VALUES
    (5001, '213242789', 10, 2026, 'ACTIVE'),
    (5002, '213242790', 11, 2026, 'ACTIVE'),
    (5010, '213242791', 12, 2026, 'ACTIVE'),
    (5011, '213242793', 14, 2026, 'ACTIVE'),
    (5012, '223242801', 15, 2025, 'ACTIVE'),
    (5013, '223242802', 13, 2025, 'ACTIVE'),
    (5014, '233242815', 16, 2024, 'ACTIVE');

INSERT INTO `course_selection_window`
    (`window_id`, `academic_year`, `semester`, `schedule_plan_id`,
     `plan_open_at`, `plan_close_at`, `selection_open_at`, `selection_close_at`, `drop_deadline`)
VALUES
    (5101, 2026, 2, 4001,
     '2020-01-01 00:00:00.000000', '2021-01-01 00:00:00.000000',
     '2021-01-01 00:00:00.000001', '2098-01-01 00:00:00.000000',
     '2099-01-01 00:00:00.000000');

INSERT INTO `enrollment`
    (`enrollment_id`, `offering_id`, `course_id`, `academic_year`, `semester`,
     `uid`, `status`, `select_time`, `drop_time`)
VALUES
    (6001, 2001, 1001, 2026, 2, '213242789', 2, '2026-09-01 00:00:00.100001', NULL),
    (6002, 2004, 1002, 2026, 2, '213242790', 2, '2026-09-01 00:00:00.100002', NULL),
    (6003, 2001, 1001, 2026, 2, '213242790', 2, '2026-09-01 00:00:00.100003', NULL),
    (6004, 2002, 1001, 2026, 2, '213242791', 2, '2026-09-01 00:00:00.100004', NULL),
    (6005, 2002, 1001, 2026, 2, '213242793', 2, '2026-09-01 00:00:00.100005', NULL),
    (6006, 2004, 1002, 2026, 2, '213242789', 2, '2026-09-01 00:00:00.100006', NULL),
    (6007, 2001, 1001, 2026, 2, '223242801', 3, '2026-09-01 00:00:00.100007', '2026-09-02 00:00:00'),
    (6010, 2010, 1003, 2026, 2, '213242789', 2, '2026-09-01 00:00:01.100001', NULL),
    (6011, 2010, 1003, 2026, 2, '213242790', 2, '2026-09-01 00:00:01.100002', NULL),
    (6012, 2010, 1003, 2026, 2, '213242791', 2, '2026-09-01 00:00:01.100003', NULL),
    (6013, 2010, 1003, 2026, 2, '213242793', 2, '2026-09-01 00:00:01.100004', NULL),
    (6014, 2011, 1003, 2026, 2, '223242801', 2, '2026-09-01 00:00:02.100001', NULL),
    (6015, 2011, 1003, 2026, 2, '223242802', 2, '2026-09-01 00:00:02.100002', NULL),
    (6016, 2011, 1003, 2026, 2, '233242815', 2, '2026-09-01 00:00:02.100003', NULL),
    (6017, 2012, 1004, 2026, 2, '213242789', 2, '2026-09-01 00:00:03.100001', NULL),
    (6018, 2012, 1004, 2026, 2, '223242802', 2, '2026-09-01 00:00:03.100002', NULL),
    (6019, 2013, 1005, 2026, 2, '213242790', 2, '2026-09-01 00:00:04.100001', NULL),
    (6020, 2013, 1005, 2026, 2, '213242791', 2, '2026-09-01 00:00:04.100002', NULL),
    (6021, 2013, 1005, 2026, 2, '213242793', 2, '2026-09-01 00:00:04.100003', NULL),
    (6022, 2013, 1005, 2026, 2, '223242801', 2, '2026-09-01 00:00:04.100004', NULL),
    (6023, 2014, 1006, 2026, 2, '213242789', 2, '2026-09-01 00:00:05.100001', NULL),
    (6024, 2014, 1006, 2026, 2, '213242790', 2, '2026-09-01 00:00:05.100002', NULL),
    (6025, 2014, 1006, 2026, 2, '213242791', 2, '2026-09-01 00:00:05.100003', NULL),
    (6026, 2014, 1006, 2026, 2, '213242793', 2, '2026-09-01 00:00:05.100004', NULL),
    (6027, 2014, 1006, 2026, 2, '223242801', 2, '2026-09-01 00:00:05.100005', NULL),
    (6028, 2014, 1006, 2026, 2, '223242802', 2, '2026-09-01 00:00:05.100006', NULL),
    (6029, 2015, 1007, 2026, 2, '213242791', 2, '2026-09-01 00:00:06.100001', NULL),
    (6030, 2015, 1007, 2026, 2, '213242793', 2, '2026-09-01 00:00:06.100002', NULL),
    (6031, 2015, 1007, 2026, 2, '233242815', 2, '2026-09-01 00:00:06.100003', NULL),
    (6032, 2015, 1007, 2026, 2, '223242802', 2, '2026-09-01 00:00:06.100004', NULL),
    (6033, 2016, 1008, 2026, 2, '213242789', 2, '2026-09-01 00:00:07.100001', NULL),
    (6034, 2016, 1008, 2026, 2, '213242790', 2, '2026-09-01 00:00:07.100002', NULL),
    (6035, 2016, 1008, 2026, 2, '223242801', 2, '2026-09-01 00:00:07.100003', NULL),
    (6036, 2017, 1009, 2026, 2, '213242793', 2, '2026-09-01 00:00:08.100001', NULL),
    (6037, 2017, 1009, 2026, 2, '223242802', 2, '2026-09-01 00:00:08.100002', NULL),
    (6038, 2018, 1010, 2026, 2, '213242789', 2, '2026-09-01 00:00:09.100001', NULL),
    (6039, 2018, 1010, 2026, 2, '213242791', 2, '2026-09-01 00:00:09.100002', NULL),
    (6040, 2018, 1010, 2026, 2, '223242801', 2, '2026-09-01 00:00:09.100003', NULL),
    (6041, 2018, 1010, 2026, 2, '233242815', 2, '2026-09-01 00:00:09.100004', NULL),
    (6042, 2019, 1011, 2026, 2, '213242790', 2, '2026-09-01 00:00:10.100001', NULL),
    (6043, 2019, 1011, 2026, 2, '213242793', 2, '2026-09-01 00:00:10.100002', NULL),
    (6044, 2019, 1011, 2026, 2, '223242802', 2, '2026-09-01 00:00:10.100003', NULL),
    (6045, 2020, 1012, 2026, 2, '223242801', 2, '2026-09-01 00:00:11.100001', NULL),
    (6046, 2020, 1012, 2026, 2, '233242815', 2, '2026-09-01 00:00:11.100002', NULL),
    (6047, 2013, 1005, 2026, 2, '213242789', 3, '2026-09-01 00:00:04.100005',
     '2026-09-20 00:00:00.200001'),
    (6048, 2020, 1012, 2026, 2, '213242790', 3, '2026-09-01 00:00:11.100003',
     '2026-09-21 00:00:00.200002');

INSERT INTO `grade`
    (`grade_id`, `enrollment_id`, `daily_score`, `midterm_score`, `finalterm_score`,
     `experiment_score`, `score`, `grade_level`, `grade_point`, `is_published`, `publish_time`)
VALUES
    (6101, 6002, 88.00, 86.00, 92.00, 90.00, 89.50, 4, 4.0, 1,
     '2026-09-05 00:00:00.123456'),
    (6110, 6010, 90.00, 85.00, 88.00, NULL, 87.50, 4, 3.7, 1,
     '2026-09-05 00:00:01.000001'),
    (6111, 6011, 82.00, 78.00, 80.00, NULL, 79.80, 3, 3.0, 1,
     '2026-09-05 00:00:01.000002'),
    (6112, 6012, 76.00, 81.00, 74.00, NULL, 76.90, 2, 2.5, 1,
     '2026-09-05 00:00:01.000003'),
    (6113, 6013, 95.00, 92.00, 94.00, NULL, 93.70, 4, 4.0, 1,
     '2026-09-05 00:00:01.000004'),
    (6114, 6019, 85.00, 88.00, 90.00, NULL, 87.90, 4, 3.7, 1,
     '2026-09-05 00:00:02.000001'),
    (6115, 6020, 70.00, 68.00, 72.00, NULL, 70.40, 2, 2.5, 1,
     '2026-09-05 00:00:02.000002'),
    (6116, 6021, 91.00, 89.00, 93.00, NULL, 91.30, 4, 4.0, 1,
     '2026-09-05 00:00:02.000003'),
    (6117, 6022, 64.00, 58.00, 61.00, NULL, 60.70, 1, 1.5, 1,
     '2026-09-05 00:00:02.000004'),
    (6118, 6023, 84.00, 80.00, NULL, NULL, NULL, NULL, NULL, 0, NULL),
    (6119, 6024, 79.00, 83.00, NULL, NULL, NULL, NULL, NULL, 0, NULL),
    (6120, 6025, 92.00, 90.00, NULL, NULL, NULL, NULL, NULL, 0, NULL),
    (6121, 6026, 68.00, 72.00, NULL, NULL, NULL, NULL, NULL, 0, NULL),
    (6122, 6027, 88.00, 91.00, NULL, NULL, NULL, NULL, NULL, 0, NULL),
    (6123, 6028, 75.00, 77.00, NULL, NULL, NULL, NULL, NULL, 0, NULL);

INSERT INTO `course_plan_item`
    (`plan_item_id`, `uid`, `offering_id`, `status`, `last_failure_reason`)
VALUES
    (6201, '213242789', 2002, 'PLANNED', NULL),
    (6210, '213242790', 2013, 'PLANNED', NULL),
    (6211, '223242802', 2020, 'PLANNED', NULL),
    (6212, '233242815', 2010, 'FULL', '教学班已满'),
    (6213, '213242791', 2011, 'PLANNED', NULL),
    (6214, '223242801', 2019, 'PLANNED', NULL);

INSERT INTO `course_waitlist`
    (`waitlist_id`, `uid`, `offering_id`, `status`, `queue_time`, `offered_at`, `expires_at`)
VALUES
    (6301, '213242790', 2002, 'WAITING', '2026-09-01 00:00:00.654321', NULL, NULL),
    (6310, '233242815', 2020, 'WAITING', '2026-09-01 00:00:01.654321', NULL, NULL),
    (6311, '213242793', 2017, 'WAITING', '2026-09-01 00:00:02.654321', NULL, NULL),
    (6312, '223242801', 2016, 'OFFERED', '2026-09-01 00:00:03.654321',
     '2026-09-02 00:00:00.000000', '2099-01-15 00:00:00.000000');

-- -----------------------------------------------------------------------------
-- 10. 培养方案、课程通知、操作日志与 outbox
-- -----------------------------------------------------------------------------
INSERT INTO `training_plan`
    (`plan_id`, `major_id`, `cohort_year`, `version`, `plan_name`, `status`)
VALUES
    (7001, 10, 2026, 1, '计算机科学与技术 2026 培养方案', 'PUBLISHED');

INSERT INTO `training_plan_group`
    (`group_id`, `plan_id`, `group_name`, `required_credits`, `sort_order`)
VALUES
    (7101, 7001, '专业必修', 12.00, 1),
    (7102, 7001, '专业选修', 6.00, 2),
    (7103, 7001, '通识教育', 6.00, 3);

INSERT INTO `training_plan_course`
    (`group_id`, `course_id`, `required`, `recommended_semester`)
VALUES
    (7101, 1001, 1, 1), (7101, 1002, 1, 3), (7101, 1003, 1, 2), (7101, 1004, 1, 3),
    (7102, 1005, 0, 4), (7102, 1010, 0, 4), (7102, 1011, 0, 5),
    (7103, 1006, 1, 1), (7103, 1007, 1, 2), (7103, 1012, 0, 3);

-- 第二个专业也有一份已发布方案：学籍 5002（213242790）读的是信息工程，
-- 只给计算机专业建方案会让"学生的培养方案"在别的专业上永远是空的。
INSERT INTO `training_plan`
    (`plan_id`, `major_id`, `cohort_year`, `version`, `plan_name`, `status`)
VALUES
    (7002, 11, 2026, 1, '信息工程 2026 培养方案', 'PUBLISHED');

INSERT INTO `training_plan_group`
    (`group_id`, `plan_id`, `group_name`, `required_credits`, `sort_order`)
VALUES
    (7201, 7002, '学科基础', 10.00, 1),
    (7202, 7002, '专业方向', 8.00, 2);

INSERT INTO `training_plan_course`
    (`group_id`, `course_id`, `required`, `recommended_semester`)
VALUES
    (7201, 1006, 1, 1), (7201, 1007, 1, 2), (7201, 1008, 1, 2),
    (7202, 1005, 0, 4), (7202, 1011, 0, 5);

INSERT INTO `course_notice`
    (`notice_id`, `offering_id`, `title`, `content`, `notice_type`, `week_no`,
     `status`, `created_by`, `published_at`)
VALUES
    (8001, 2001, '程序设计基础 开课通知', '第 1 周周二 1-2 节于教一-101 开课。',
     'GENERAL', 1, 'PUBLISHED', 'teacher01', '2026-09-01 00:00:00.123456'),
    (8010, 2010, '数据结构 开课通知', '第 1 周周一 1-2 节，教学楼 A101 开课。',
     'GENERAL', 1, 'PUBLISHED', 'teacher01', '2026-09-01 00:00:01.123456'),
    (8011, 2010, '第 3 周机房检修调课', '第 3 周周三 3-4 节调整到 4-5 节。',
     'RESCHEDULED', 3, 'DRAFT', 'teacher01', NULL),
    (8012, 2012, '操作系统实验安排', '实验分两组，第 4 周开始。',
     'GENERAL', 4, 'PUBLISHED', 'teacher01', '2026-09-03 00:00:00.123456'),
    (8013, 2011, '数据结构作业提交说明', '每周日晚 23:59 前提交。',
     'GENERAL', 1, 'PUBLISHED', 'teacher02', '2026-09-01 00:00:02.123456'),
    (8014, 2016, '信号与系统停课通知', '第 5 周周二停课一次。',
     'CANCELLED', 5, 'PUBLISHED', 'teacher02', '2026-09-08 00:00:00.123456');

INSERT INTO `course_operation_log`
    (`uid`, `operation_id`, `action`, `offering_id`, `request_digest`,
     `result_code`, `response_json`, `created_at`, `completed_at`)
VALUES
    ('213242789', '00000000-0000-0000-0000-000000000001', 'addToPlan', 2002,
     REPEAT('a', 64), 'OK', JSON_OBJECT('state', 'PLANNED'),
     '2026-09-01 00:00:00.100001', '2026-09-01 00:00:00.100002'),
    ('213242790', '00000000-0000-0000-0000-000000000002', 'addToPlan', 2013,
     REPEAT('b', 64), 'OK', JSON_OBJECT('state', 'PLANNED'),
     '2026-09-01 00:00:01.100001', '2026-09-01 00:00:01.100002');

INSERT INTO `course_event_outbox`
    (`event_id`, `uid`, `event_type`, `academic_year`, `semester`, `offering_id`,
     `payload`, `created_at`, `last_sent_at`, `attempt_count`, `acked_at`)
VALUES
    (9001, '213242790', 'WAITLIST_OFFERED', 2026, 2, 2002,
     JSON_OBJECT('offeringId', '2002'), '2026-09-01 00:00:00.100003', NULL, 0, NULL),
    (9010, '233242815', 'WAITLIST_OFFERED', 2026, 2, 2020,
     JSON_OBJECT('offeringId', '2020'), '2026-09-01 00:00:01.100003', NULL, 0, NULL),
    (9011, '223242801', 'WAITLIST_OFFERED', 2026, 2, 2016,
     JSON_OBJECT('offeringId', '2016'), '2026-09-01 00:00:02.100003',
     '2026-09-01 00:00:02.200000', 1, '2026-09-01 00:00:02.300000');

DROP TEMPORARY TABLE IF EXISTS `seed_weeks`;
DROP TEMPORARY TABLE IF EXISTS `seed_new_rules`;
DROP TEMPORARY TABLE IF EXISTS `seed_classrooms`;
