CREATE TABLE IF NOT EXISTS `major` (
    `major_id` INT NOT NULL AUTO_INCREMENT COMMENT '专业唯一标识',
    `major_code` VARCHAR(32) NOT NULL COMMENT '专业代码',
    `major_name` VARCHAR(100) NOT NULL COMMENT '专业名称',
    `college` VARCHAR(100) NOT NULL COMMENT '所属学院',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`major_id`),
    UNIQUE KEY `uk_major_code` (`major_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='专业定义表';

ALTER TABLE `course_major`
    ADD KEY `idx_course_major_major` (`major_id`, `course_id`),
    ADD CONSTRAINT `fk_course_major_major`
        FOREIGN KEY (`major_id`) REFERENCES `major` (`major_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE `course_year`
    ADD KEY `idx_course_year_year` (`year`, `course_id`);

ALTER TABLE `teaching_calendar`
    ADD COLUMN `academic_year` INT NULL AFTER `name`,
    ADD COLUMN `semester` TINYINT NULL AFTER `academic_year`,
    ADD UNIQUE KEY `uk_teaching_calendar_term_version`
        (`academic_year`, `semester`, `version`),
    ADD CONSTRAINT `chk_teaching_calendar_academic_year`
        CHECK (`academic_year` IS NULL OR `academic_year` > 0),
    ADD CONSTRAINT `chk_teaching_calendar_semester`
        CHECK (`semester` IS NULL OR `semester` IN (1, 2, 3));

CREATE TABLE IF NOT EXISTS `student_academic_profile` (
    `profile_id` BIGINT NOT NULL AUTO_INCREMENT,
    `uid` VARCHAR(32) NOT NULL,
    `major_id` INT NOT NULL,
    `cohort_year` INT NOT NULL COMMENT '入学年份',
    `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`profile_id`),
    UNIQUE KEY `uk_student_academic_profile_uid` (`uid`),
    KEY `idx_student_academic_profile_major` (`major_id`, `cohort_year`),
    CONSTRAINT `fk_student_academic_profile_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_student_academic_profile_major`
        FOREIGN KEY (`major_id`) REFERENCES `major` (`major_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_student_academic_profile_cohort` CHECK (`cohort_year` > 0),
    CONSTRAINT `chk_student_academic_profile_status`
        CHECK (`status` IN ('ACTIVE', 'SUSPENDED', 'GRADUATED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生学籍表';

CREATE TABLE IF NOT EXISTS `course_selection_window` (
    `window_id` BIGINT NOT NULL AUTO_INCREMENT,
    `academic_year` INT NOT NULL,
    `semester` TINYINT NOT NULL,
    `schedule_plan_id` BIGINT NOT NULL,
    `plan_open_at` DATETIME(6) NOT NULL,
    `plan_close_at` DATETIME(6) NOT NULL,
    `selection_open_at` DATETIME(6) NOT NULL,
    `selection_close_at` DATETIME(6) NOT NULL,
    `drop_deadline` DATETIME(6) NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`window_id`),
    UNIQUE KEY `uk_selection_window_term` (`academic_year`, `semester`),
    KEY `idx_selection_window_schedule_plan` (`schedule_plan_id`),
    CONSTRAINT `fk_selection_window_schedule_plan`
        FOREIGN KEY (`schedule_plan_id`) REFERENCES `schedule_plan` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_selection_window_term`
        CHECK (`academic_year` > 0 AND `semester` IN (1, 2, 3)),
    CONSTRAINT `chk_selection_window_order` CHECK (
        `plan_open_at` <= `plan_close_at`
        AND `plan_close_at` <= `selection_open_at`
        AND `selection_open_at` < `selection_close_at`
        AND `selection_close_at` <= `drop_deadline`
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程计划、选择和退选窗口';

CREATE TABLE IF NOT EXISTS `course_plan_item` (
    `plan_item_id` BIGINT NOT NULL AUTO_INCREMENT,
    `uid` VARCHAR(32) NOT NULL,
    `offering_id` BIGINT NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'PLANNED',
    `last_failure_reason` VARCHAR(255) DEFAULT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`plan_item_id`),
    UNIQUE KEY `uk_course_plan_item_uid_offering` (`uid`, `offering_id`),
    KEY `idx_course_plan_item_offering_status` (`offering_id`, `status`),
    CONSTRAINT `fk_course_plan_item_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_plan_item_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_course_plan_item_status`
        CHECK (`status` IN ('PLANNED', 'FULL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生单教学班选课计划';

CREATE TABLE IF NOT EXISTS `course_waitlist` (
    `waitlist_id` BIGINT NOT NULL AUTO_INCREMENT,
    `uid` VARCHAR(32) NOT NULL,
    `offering_id` BIGINT NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'WAITING',
    `queue_time` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `offered_at` DATETIME(6) NULL DEFAULT NULL,
    `expires_at` DATETIME(6) NULL DEFAULT NULL,
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`waitlist_id`),
    UNIQUE KEY `uk_course_waitlist_uid_offering` (`uid`, `offering_id`),
    KEY `idx_course_waitlist_queue` (`offering_id`, `status`, `queue_time`, `waitlist_id`),
    KEY `idx_course_waitlist_expiry` (`status`, `expires_at`),
    CONSTRAINT `fk_course_waitlist_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_waitlist_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_course_waitlist_status`
        CHECK (`status` IN ('WAITING', 'OFFERED', 'CANCELLED', 'EXPIRED', 'ENROLLED')),
    CONSTRAINT `chk_course_waitlist_offer_times` CHECK (
        (`status` <> 'OFFERED')
        OR (`offered_at` IS NOT NULL AND `expires_at` IS NOT NULL AND `offered_at` < `expires_at`)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班候补生命周期';

CREATE TABLE IF NOT EXISTS `training_plan` (
    `plan_id` BIGINT NOT NULL AUTO_INCREMENT,
    `major_id` INT NOT NULL,
    `cohort_year` INT NOT NULL,
    `version` INT NOT NULL DEFAULT 1,
    `plan_name` VARCHAR(120) NOT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`plan_id`),
    UNIQUE KEY `uk_training_plan_identity` (`major_id`, `cohort_year`, `version`),
    CONSTRAINT `fk_training_plan_major`
        FOREIGN KEY (`major_id`) REFERENCES `major` (`major_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_training_plan_cohort` CHECK (`cohort_year` > 0),
    CONSTRAINT `chk_training_plan_version` CHECK (`version` > 0),
    CONSTRAINT `chk_training_plan_status` CHECK (`status` IN ('DRAFT', 'PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='培养方案';

CREATE TABLE IF NOT EXISTS `training_plan_group` (
    `group_id` BIGINT NOT NULL AUTO_INCREMENT,
    `plan_id` BIGINT NOT NULL,
    `group_name` VARCHAR(100) NOT NULL,
    `required_credits` DECIMAL(5,2) NOT NULL DEFAULT 0,
    `sort_order` INT NOT NULL DEFAULT 0,
    PRIMARY KEY (`group_id`),
    UNIQUE KEY `uk_training_plan_group_name` (`plan_id`, `group_name`),
    CONSTRAINT `fk_training_plan_group_plan`
        FOREIGN KEY (`plan_id`) REFERENCES `training_plan` (`plan_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_training_plan_group_credits` CHECK (`required_credits` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='培养方案课程组';

CREATE TABLE IF NOT EXISTS `training_plan_course` (
    `group_id` BIGINT NOT NULL,
    `course_id` BIGINT NOT NULL,
    `required` TINYINT(1) NOT NULL DEFAULT 1,
    `recommended_semester` TINYINT DEFAULT NULL,
    PRIMARY KEY (`group_id`, `course_id`),
    KEY `idx_training_plan_course_course` (`course_id`, `group_id`),
    CONSTRAINT `fk_training_plan_course_group`
        FOREIGN KEY (`group_id`) REFERENCES `training_plan_group` (`group_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_training_plan_course_course`
        FOREIGN KEY (`course_id`) REFERENCES `course` (`course_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_training_plan_course_required` CHECK (`required` IN (0, 1)),
    CONSTRAINT `chk_training_plan_course_semester`
        CHECK (`recommended_semester` IS NULL OR `recommended_semester` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='培养方案课程';

CREATE TABLE IF NOT EXISTS `course_notice` (
    `notice_id` BIGINT NOT NULL AUTO_INCREMENT,
    `offering_id` BIGINT NOT NULL,
    `title` VARCHAR(160) NOT NULL,
    `content` TEXT NOT NULL,
    `notice_type` VARCHAR(20) NOT NULL DEFAULT 'GENERAL',
    `week_no` SMALLINT DEFAULT NULL,
    `status` VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    `created_by` VARCHAR(32) NOT NULL,
    `published_at` DATETIME(6) NULL DEFAULT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`notice_id`),
    KEY `idx_course_notice_offering_week` (`offering_id`, `week_no`, `status`),
    CONSTRAINT `fk_course_notice_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_notice_creator`
        FOREIGN KEY (`created_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_notice_week` CHECK (`week_no` IS NULL OR `week_no` > 0),
    CONSTRAINT `chk_course_notice_status` CHECK (`status` IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT `chk_course_notice_type`
        CHECK (`notice_type` IN ('GENERAL', 'CANCELLED', 'RESCHEDULED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学班课程通知';

CREATE TABLE IF NOT EXISTS `course_operation_log` (
    `uid` VARCHAR(32) NOT NULL,
    `operation_id` CHAR(36) NOT NULL,
    `action` VARCHAR(48) NOT NULL,
    `offering_id` BIGINT DEFAULT NULL,
    `request_digest` CHAR(64) NOT NULL,
    `result_code` VARCHAR(48) NOT NULL,
    `response_json` JSON NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `completed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`uid`, `operation_id`),
    KEY `idx_course_operation_offering` (`offering_id`, `created_at`),
    CONSTRAINT `fk_course_operation_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_operation_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='选课变更幂等结果';

CREATE TABLE IF NOT EXISTS `course_event_outbox` (
    `event_id` BIGINT NOT NULL AUTO_INCREMENT,
    `uid` VARCHAR(32) NOT NULL,
    `event_type` VARCHAR(48) NOT NULL,
    `academic_year` INT NOT NULL,
    `semester` TINYINT NOT NULL,
    `offering_id` BIGINT DEFAULT NULL,
    `payload` JSON NOT NULL,
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    `last_sent_at` DATETIME(6) NULL DEFAULT NULL,
    `attempt_count` INT NOT NULL DEFAULT 0,
    `acked_at` DATETIME(6) NULL DEFAULT NULL,
    PRIMARY KEY (`event_id`),
    KEY `idx_course_event_outbox_delivery` (`uid`, `acked_at`, `event_id`),
    KEY `idx_course_event_outbox_pending` (`acked_at`, `created_at`),
    CONSTRAINT `fk_course_event_outbox_user`
        FOREIGN KEY (`uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_event_outbox_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `chk_course_event_outbox_term`
        CHECK (`academic_year` > 0 AND `semester` IN (1, 2, 3)),
    CONSTRAINT `chk_course_event_outbox_attempts` CHECK (`attempt_count` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程推送事件outbox';
