CREATE TABLE IF NOT EXISTS `course` (
    `course_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '课程唯一标识',
    `course_code` VARCHAR(32) NOT NULL COMMENT '课程代码',
    `course_name` VARCHAR(100) NOT NULL COMMENT '课程名称',
    `credit` DECIMAL(3,2) NOT NULL COMMENT '课程学分',
    `credit_hours` INT NOT NULL COMMENT '课程总学时',
    `course_type` TINYINT NOT NULL COMMENT '课程类型: 1-必修, 2-限选, 3-选修, 4-通选',
    `allow_cross_major` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否允许跨专业选课',
    `description` TEXT DEFAULT NULL COMMENT '课程简介',
    `prerequisites` TEXT DEFAULT NULL COMMENT '先修课程要求',
    `final_exam` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否设置结课考试',
    `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`course_id`),
    UNIQUE KEY `uk_course_code` (`course_code`),
    CONSTRAINT `chk_course_credit` CHECK (`credit` > 0),
    CONSTRAINT `chk_course_credit_hours` CHECK (`credit_hours` > 0),
    CONSTRAINT `chk_course_type` CHECK (`course_type` IN (1, 2, 3, 4)),
    CONSTRAINT `chk_course_allow_cross_major` CHECK (`allow_cross_major` IN (0, 1)),
    CONSTRAINT `chk_course_final_exam` CHECK (`final_exam` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程基本信息表';

CREATE TABLE IF NOT EXISTS `course_major` (
    `course_id` BIGINT NOT NULL COMMENT '课程ID',
    `major_id` INT NOT NULL COMMENT '适用专业ID',
    PRIMARY KEY (`course_id`, `major_id`),
    CONSTRAINT `fk_course_major_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`course_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程适用专业限制表';

CREATE TABLE IF NOT EXISTS `course_year` (
    `course_id` BIGINT NOT NULL COMMENT '课程ID',
    `year` TINYINT NOT NULL COMMENT '适用学期序号',
    PRIMARY KEY (`course_id`, `year`),
    CONSTRAINT `chk_course_year` CHECK (`year` > 0),
    CONSTRAINT `fk_course_year_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`course_id`)
        ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程适用学期表';

CREATE TABLE IF NOT EXISTS `course_offering` (
    `offering_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学班唯一标识',
    `offering_code` VARCHAR(64) NOT NULL COMMENT '教学班代码',
    `course_id` BIGINT NOT NULL COMMENT '对应课程ID',
    `academic_year` INT NOT NULL COMMENT '开课年份',
    `semester` TINYINT NOT NULL COMMENT '开课学期: 1-暑期学校, 2-秋学期, 3-春学期',
    `capacity` INT NOT NULL COMMENT '教学班容量',
    `wanted_count` INT NOT NULL DEFAULT 0 COMMENT '加入计划的人数',
    `enrolled_count` INT NOT NULL DEFAULT 0 COMMENT '正式选中人数',
    `website` VARCHAR(255) DEFAULT NULL COMMENT '课程网站',
    `status` TINYINT NOT NULL COMMENT '教学班状态: 1-未开放, 2-开放, 3-停止, 4-取消',
    `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`offering_id`),
    UNIQUE KEY `uk_course_offering_code` (`offering_code`),
    UNIQUE KEY `uk_course_offering_identity` (`offering_id`, `course_id`, `academic_year`, `semester`),
    KEY `idx_course_offering_course` (`course_id`),
    KEY `idx_course_offering_term_status` (`academic_year`, `semester`, `status`),
    CONSTRAINT `fk_course_offering_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`course_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_offering_academic_year` CHECK (`academic_year` > 0),
    CONSTRAINT `chk_course_offering_semester` CHECK (`semester` IN (1, 2, 3)),
    CONSTRAINT `chk_course_offering_capacity` CHECK (`capacity` >= 0),
    CONSTRAINT `chk_course_offering_wanted_count` CHECK (`wanted_count` >= 0),
    CONSTRAINT `chk_course_offering_enrolled_count`
        CHECK (`enrolled_count` >= 0 AND `enrolled_count` <= `capacity`),
    CONSTRAINT `chk_course_offering_status` CHECK (`status` IN (1, 2, 3, 4))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程教学班表';

CREATE TABLE IF NOT EXISTS `course_offering_teacher` (
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `uid` VARCHAR(32) NOT NULL COMMENT '教师或助教一卡通号',
    `role` TINYINT NOT NULL COMMENT '教学班人员角色: 0-任课老师, 1-助教',
    PRIMARY KEY (`offering_id`, `uid`),
    KEY `idx_course_offering_teacher_uid` (`uid`),
    CONSTRAINT `fk_course_offering_teacher_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_offering_teacher_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_offering_teacher_role` CHECK (`role` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班教师与助教关联表';

CREATE TABLE IF NOT EXISTS `enrollment` (
    `enrollment_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '选课记录唯一标识',
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `course_id` BIGINT NOT NULL COMMENT '课程ID',
    `academic_year` INT NOT NULL COMMENT '学年',
    `semester` TINYINT NOT NULL COMMENT '学期',
    `uid` VARCHAR(32) NOT NULL COMMENT '学生唯一标识',
    `status` TINYINT NOT NULL COMMENT '选课状态: 2-已选中, 3-已退课',
    `active_course_id` BIGINT GENERATED ALWAYS AS
        (CASE WHEN `status` = 2 THEN `course_id` ELSE NULL END) STORED,
    `select_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '选课时间',
    `drop_time` DATETIME(6) NULL DEFAULT NULL COMMENT '退课时间',
    PRIMARY KEY (`enrollment_id`),
    UNIQUE KEY `uk_enrollment_uid_offering` (`uid`, `offering_id`),
    UNIQUE KEY `uk_enrollment_active_course` (`uid`, `academic_year`, `semester`, `active_course_id`),
    KEY `idx_enrollment_offering_status` (`offering_id`, `status`),
    KEY `idx_enrollment_uid_status` (`uid`, `status`),
    CONSTRAINT `fk_enrollment_offering_identity`
        FOREIGN KEY (`offering_id`, `course_id`, `academic_year`, `semester`)
        REFERENCES `course_offering` (`offering_id`, `course_id`, `academic_year`, `semester`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_enrollment_uid`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_enrollment_status` CHECK (`status` IN (2, 3)),
    CONSTRAINT `chk_enrollment_drop_time`
        CHECK ((`status` = 2 AND `drop_time` IS NULL) OR (`status` = 3 AND `drop_time` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生正式选课及退课历史表';

CREATE TABLE IF NOT EXISTS `grade` (
    `grade_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成绩记录唯一标识',
    `enrollment_id` BIGINT NOT NULL COMMENT '对应选课记录ID',
    `daily_score` DECIMAL(5,2) DEFAULT NULL COMMENT '平时成绩',
    `midterm_score` DECIMAL(5,2) DEFAULT NULL COMMENT '期中成绩',
    `finalterm_score` DECIMAL(5,2) DEFAULT NULL COMMENT '期末成绩',
    `experiment_score` DECIMAL(5,2) DEFAULT NULL COMMENT '实验成绩',
    `score` DECIMAL(5,2) DEFAULT NULL COMMENT '总成绩',
    `grade_level` TINYINT DEFAULT NULL COMMENT '等级成绩',
    `grade_point` DECIMAL(2,1) DEFAULT NULL COMMENT '绩点',
    `is_published` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '成绩是否发布',
    `publish_time` DATETIME(6) NULL DEFAULT NULL COMMENT '发布时间',
    PRIMARY KEY (`grade_id`),
    UNIQUE KEY `uk_grade_enrollment` (`enrollment_id`),
    CONSTRAINT `fk_grade_enrollment`
        FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`enrollment_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_grade_daily_score` CHECK (`daily_score` IS NULL OR (`daily_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_midterm_score` CHECK (`midterm_score` IS NULL OR (`midterm_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_finalterm_score` CHECK (`finalterm_score` IS NULL OR (`finalterm_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_experiment_score` CHECK (`experiment_score` IS NULL OR (`experiment_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_score` CHECK (`score` IS NULL OR (`score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_point` CHECK (`grade_point` IS NULL OR (`grade_point` BETWEEN 0 AND 5)),
    CONSTRAINT `chk_grade_is_published` CHECK (`is_published` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生课程成绩表';
