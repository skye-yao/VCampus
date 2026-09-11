CREATE TABLE IF NOT EXISTS `teaching_calendar` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学日历唯一标识',
    `name` VARCHAR(200) NOT NULL COMMENT '教学日历名称',
    `week1_start_date` DATE NOT NULL COMMENT '第一教学周开始日期',
    `timezone` VARCHAR(64) NOT NULL COMMENT 'IANA 时区名称，如 Asia/Shanghai',
    `version` INT NOT NULL DEFAULT 1 COMMENT '教学日历版本号',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿, READY-就绪, PUBLISHED-已发布',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_teaching_calendar_name_version` (`name`, `version`),
    KEY `idx_teaching_calendar_status` (`status`),
    CONSTRAINT `chk_teaching_calendar_version` CHECK (`version` > 0),
    CONSTRAINT `chk_teaching_calendar_status` CHECK (`status` IN ('DRAFT', 'READY', 'PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学日历定义表';

CREATE TABLE IF NOT EXISTS `day_template` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日课表模板唯一标识',
    `name` VARCHAR(200) NOT NULL COMMENT '日课表模板名称',
    `version` INT NOT NULL DEFAULT 1 COMMENT '模板版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_day_template_name_version` (`name`, `version`),
    CONSTRAINT `chk_day_template_version` CHECK (`version` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日课表模板表';

CREATE TABLE IF NOT EXISTS `period_definition` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '节次定义唯一标识',
    `day_template_id` BIGINT NOT NULL COMMENT '所属日课表模板ID',
    `period_no` SMALLINT NOT NULL COMMENT '节次序号，如第3节为3',
    `start_time` TIME NOT NULL COMMENT '节次开始时间',
    `end_time` TIME NOT NULL COMMENT '节次结束时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_period_definition_template_no` (`day_template_id`, `period_no`),
    CONSTRAINT `fk_period_definition_day_template`
        FOREIGN KEY (`day_template_id`) REFERENCES `day_template` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_period_definition_no` CHECK (`period_no` > 0),
    CONSTRAINT `chk_period_definition_time` CHECK (`start_time` < `end_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日课表节次定义表';

CREATE TABLE IF NOT EXISTS `calendar_date` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学日历日期唯一标识',
    `calendar_id` BIGINT NOT NULL COMMENT '教学日历ID',
    `local_date` DATE NOT NULL COMMENT '本地日期',
    `week_no` SMALLINT NOT NULL COMMENT '教学周次',
    `teaching_weekday` TINYINT NOT NULL COMMENT '教学星期: 1-星期一, 7-星期日',
    `day_template_id` BIGINT NOT NULL COMMENT '当日使用的日课表模板ID',
    `is_teaching_day` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否为教学日: 0-否, 1-是',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_calendar_date_local_date` (`calendar_id`, `local_date`),
    UNIQUE KEY `uk_calendar_date_weekday` (`calendar_id`, `week_no`, `teaching_weekday`),
    KEY `idx_calendar_date_teaching_lookup` (`calendar_id`, `is_teaching_day`, `week_no`),
    KEY `idx_calendar_date_day_template` (`day_template_id`),
    CONSTRAINT `fk_calendar_date_calendar`
        FOREIGN KEY (`calendar_id`) REFERENCES `teaching_calendar` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_calendar_date_day_template`
        FOREIGN KEY (`day_template_id`) REFERENCES `day_template` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_calendar_date_week_no` CHECK (`week_no` > 0),
    CONSTRAINT `chk_calendar_date_weekday` CHECK (`teaching_weekday` BETWEEN 1 AND 7),
    CONSTRAINT `chk_calendar_date_is_teaching_day` CHECK (`is_teaching_day` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学日历日期表';

CREATE TABLE IF NOT EXISTS `schedule_plan` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '排课方案唯一标识',
    `name` VARCHAR(200) NOT NULL COMMENT '排课方案名称',
    `calendar_id` BIGINT NOT NULL COMMENT '关联教学日历ID',
    `revision` INT NOT NULL DEFAULT 1 COMMENT '方案修订版本号',
    `status` VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '状态: DRAFT-草稿, READY-就绪, PUBLISHED-已发布',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_schedule_plan_calendar_name_revision` (`calendar_id`, `name`, `revision`),
    KEY `idx_schedule_plan_calendar_status` (`calendar_id`, `status`),
    CONSTRAINT `fk_schedule_plan_calendar`
        FOREIGN KEY (`calendar_id`) REFERENCES `teaching_calendar` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_schedule_plan_revision` CHECK (`revision` > 0),
    CONSTRAINT `chk_schedule_plan_status` CHECK (`status` IN ('DRAFT', 'READY', 'PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排课方案表';

CREATE TABLE IF NOT EXISTS `course_schedule_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '课程排课规则唯一标识',
    `plan_id` BIGINT NOT NULL COMMENT '所属排课方案ID',
    `course_offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `weekday` TINYINT NOT NULL COMMENT '上课星期: 1-星期一, 7-星期日',
    `start_period` SMALLINT DEFAULT NULL COMMENT '起始节次，如第3节为3',
    `end_period` SMALLINT DEFAULT NULL COMMENT '结束节次，如第5节为5',
    `status` ENUM('ACTIVE', 'DISABLED') NOT NULL DEFAULT 'ACTIVE' COMMENT '规则状态',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_schedule_rule_identity` (`id`, `plan_id`, `weekday`),
    UNIQUE KEY `uk_course_schedule_rule_slot`
        (`plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`),
    KEY `idx_course_schedule_rule_plan_status_weekday` (`plan_id`, `status`, `weekday`),
    KEY `idx_course_schedule_rule_offering_plan` (`course_offering_id`, `plan_id`),
    CONSTRAINT `fk_course_schedule_rule_plan`
        FOREIGN KEY (`plan_id`) REFERENCES `schedule_plan` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_rule_offering`
        FOREIGN KEY (`course_offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_rule_weekday` CHECK (`weekday` BETWEEN 1 AND 7),
    CONSTRAINT `chk_course_schedule_rule_period_range` CHECK (
        (`start_period` IS NULL AND `end_period` IS NULL)
        OR (`start_period` IS NOT NULL AND `end_period` IS NOT NULL
            AND `start_period` > 0 AND `end_period` >= `start_period`)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程排课规则表';

CREATE TABLE IF NOT EXISTS `course_schedule_rule_week` (
    `rule_id` BIGINT NOT NULL COMMENT '课程排课规则ID',
    `week_no` SMALLINT NOT NULL COMMENT '适用教学周次',
    PRIMARY KEY (`rule_id`, `week_no`),
    KEY `idx_course_schedule_rule_week_no` (`week_no`, `rule_id`),
    CONSTRAINT `fk_course_schedule_rule_week_rule`
        FOREIGN KEY (`rule_id`) REFERENCES `course_schedule_rule` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_rule_week_no` CHECK (`week_no` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程排课规则适用周次表';

CREATE TABLE IF NOT EXISTS `schedule_resource` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '排课资源唯一标识',
    `resource_type` VARCHAR(30) NOT NULL COMMENT '资源类型: teacher, classroom, student',
    `business_id` VARCHAR(32) NOT NULL COMMENT '资源对应的业务对象ID或用户UID',
    `conflict_mode` VARCHAR(20) NOT NULL COMMENT '资源冲突处理模式',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_schedule_resource_business` (`resource_type`, `business_id`),
    KEY `idx_schedule_resource_type_mode` (`resource_type`, `conflict_mode`),
    CONSTRAINT `chk_schedule_resource_type`
        CHECK (`resource_type` IN ('teacher', 'classroom', 'student')),
    CONSTRAINT `chk_schedule_resource_business_id` CHECK (`business_id` <> ''),
    CONSTRAINT `chk_schedule_resource_conflict_mode` CHECK (`conflict_mode` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排课资源抽象表（用处不大）';

CREATE TABLE IF NOT EXISTS `course_occurrence` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '课程实例唯一标识',
    `rule_id` BIGINT NOT NULL COMMENT '课程排课规则ID',
    `plan_id` BIGINT NOT NULL COMMENT '课程实例所属排课方案ID',
    `start_at` DATETIME NOT NULL COMMENT '课程实例开始时间（UTC）',
    `end_at` DATETIME NOT NULL COMMENT '课程实例结束时间（UTC）',
    `week_no` SMALLINT NOT NULL COMMENT '教学周次',
    `teaching_weekday` TINYINT NOT NULL COMMENT '教学星期: 1-星期一, 7-星期日',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_course_occurrence_plan_identity` (`id`, `plan_id`),
    UNIQUE KEY `uk_course_occurrence_rule_week` (`rule_id`, `week_no`),
    KEY `idx_course_occurrence_time_range` (`start_at`, `end_at`),
    KEY `idx_course_occurrence_weekday_time` (`week_no`, `teaching_weekday`, `start_at`),
    CONSTRAINT `fk_course_occurrence_rule_week`
        FOREIGN KEY (`rule_id`, `week_no`)
        REFERENCES `course_schedule_rule_week` (`rule_id`, `week_no`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_course_occurrence_rule_identity`
        FOREIGN KEY (`rule_id`, `plan_id`, `teaching_weekday`)
        REFERENCES `course_schedule_rule` (`id`, `plan_id`, `weekday`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_course_occurrence_time_range` CHECK (`start_at` < `end_at`),
    CONSTRAINT `chk_course_occurrence_week_no` CHECK (`week_no` > 0),
    CONSTRAINT `chk_course_occurrence_weekday` CHECK (`teaching_weekday` BETWEEN 1 AND 7)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程实际发生实例表';

CREATE TABLE IF NOT EXISTS `resource_booking` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '资源预订唯一标识',
    `plan_id` BIGINT NOT NULL COMMENT '排课方案ID',
    `occurrence_id` BIGINT NOT NULL COMMENT '课程实例ID',
    `resource_id` BIGINT NOT NULL COMMENT '排课资源ID',
    `resource_role` VARCHAR(30) NOT NULL COMMENT '资源在课程实例中的角色',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_resource_booking_occurrence_resource` (`occurrence_id`, `resource_id`),
    UNIQUE KEY `uk_resource_booking_conflict_identity`
        (`id`, `plan_id`, `occurrence_id`, `resource_id`),
    KEY `idx_resource_booking_occurrence_plan` (`occurrence_id`, `plan_id`),
    KEY `idx_resource_booking_plan_resource` (`plan_id`, `resource_id`),
    KEY `idx_resource_booking_resource_occurrence` (`resource_id`, `occurrence_id`),
    CONSTRAINT `fk_resource_booking_occurrence_plan`
        FOREIGN KEY (`occurrence_id`, `plan_id`)
        REFERENCES `course_occurrence` (`id`, `plan_id`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_resource_booking_resource`
        FOREIGN KEY (`resource_id`) REFERENCES `schedule_resource` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_resource_booking_role` CHECK (`resource_role` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课程实例资源预订表';

CREATE TABLE IF NOT EXISTS `schedule_conflict` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '排课冲突唯一标识',
    `plan_id` BIGINT NOT NULL COMMENT '排课方案ID',
    `resource_id` BIGINT NOT NULL COMMENT '发生冲突的排课资源ID',
    `booking_a_id` BIGINT NOT NULL COMMENT '冲突预订单A ID',
    `booking_b_id` BIGINT NOT NULL COMMENT '冲突预订单B ID',
    `occurrence_a_id` BIGINT NOT NULL COMMENT '冲突课程实例A ID',
    `occurrence_b_id` BIGINT NOT NULL COMMENT '冲突课程实例B ID',
    `overlap_start_at_utc` DATETIME NOT NULL COMMENT '冲突重叠开始时间（UTC）',
    `overlap_end_at_utc` DATETIME NOT NULL COMMENT '冲突重叠结束时间（UTC）',
    `calculated_revision` INT NOT NULL COMMENT '计算冲突时的方案修订版本号',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_schedule_conflict_booking_pair_revision`
        (`plan_id`, `resource_id`, `booking_a_id`, `booking_b_id`, `calculated_revision`),
    KEY `idx_schedule_conflict_plan_revision`
        (`plan_id`, `calculated_revision`, `resource_id`),
    KEY `idx_schedule_conflict_booking_a`
        (`booking_a_id`, `plan_id`, `occurrence_a_id`, `resource_id`),
    KEY `idx_schedule_conflict_booking_b`
        (`booking_b_id`, `plan_id`, `occurrence_b_id`, `resource_id`),
    CONSTRAINT `fk_schedule_conflict_booking_a`
        FOREIGN KEY (`booking_a_id`, `plan_id`, `occurrence_a_id`, `resource_id`)
        REFERENCES `resource_booking` (`id`, `plan_id`, `occurrence_id`, `resource_id`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `fk_schedule_conflict_booking_b`
        FOREIGN KEY (`booking_b_id`, `plan_id`, `occurrence_b_id`, `resource_id`)
        REFERENCES `resource_booking` (`id`, `plan_id`, `occurrence_id`, `resource_id`)
        ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT `chk_schedule_conflict_booking_order` CHECK (`booking_a_id` < `booking_b_id`),
    CONSTRAINT `chk_schedule_conflict_occurrence_pair` CHECK (`occurrence_a_id` <> `occurrence_b_id`),
    CONSTRAINT `chk_schedule_conflict_overlap`
        CHECK (`overlap_start_at_utc` < `overlap_end_at_utc`),
    CONSTRAINT `chk_schedule_conflict_revision` CHECK (`calculated_revision` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='排课资源冲突明细表';

CREATE TABLE IF NOT EXISTS `classroom` (
    `id` BIGINT NOT NULL COMMENT '教室ID',
    `name` VARCHAR(30) NOT NULL COMMENT '教室名称',
    `capacity` INT NOT NULL COMMENT '教室容量',
    `electric` TINYINT NOT NULL COMMENT '是否有电',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name`
        (`name`),
    CONSTRAINT `chk_classroom_electric` CHECK (`electric` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教室信息表';

CREATE TABLE IF NOT EXISTS `course_offering_conflict` (
    `plan_id` BIGINT NOT NULL COMMENT '用于选课冲突查询的排课方案ID',
    `course_offering_a_id` BIGINT NOT NULL COMMENT '规范化排序后的教学班A ID',
    `course_offering_b_id` BIGINT NOT NULL COMMENT '规范化排序后的教学班B ID',
    `conflict_count` SMALLINT NOT NULL COMMENT '两教学班的实际时间冲突次数，恒大于0',
    `first_conflict_at` DATETIME DEFAULT NULL COMMENT '首次冲突开始时间（UTC）',
    `last_conflict_at` DATETIME DEFAULT NULL COMMENT '末次冲突开始时间（UTC）',
    PRIMARY KEY (`plan_id`, `course_offering_a_id`, `course_offering_b_id`),
    KEY `idx_course_offering_conflict_b`
        (`plan_id`, `course_offering_b_id`, `course_offering_a_id`),
    CONSTRAINT `fk_course_offering_conflict_plan`
        FOREIGN KEY (`plan_id`) REFERENCES `schedule_plan` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_offering_conflict_a`
        FOREIGN KEY (`course_offering_a_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,

    CONSTRAINT `fk_course_offering_conflict_b`
        FOREIGN KEY (`course_offering_b_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `chk_course_offering_conflict_pair_order`
        CHECK (`course_offering_a_id` < `course_offering_b_id`),
    CONSTRAINT `chk_course_offering_conflict_count` CHECK (`conflict_count` > 0),
    CONSTRAINT `chk_course_offering_conflict_time_range` CHECK (
        (`first_conflict_at` IS NULL AND `last_conflict_at` IS NULL)
        OR (`first_conflict_at` IS NOT NULL AND `last_conflict_at` IS NOT NULL
            AND `first_conflict_at` <= `last_conflict_at`)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='学生选课预计算冲突查询表';
