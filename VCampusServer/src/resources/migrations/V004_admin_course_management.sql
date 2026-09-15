-- V004: 管理员端课程管理增量迁移。
-- 仅扩展 V001（课程/教学班/选课/成绩）、V002（排课）与 V003（教务扩展）已建立的结构。

-- 7.1 课程与教学班：归档状态、乐观锁版本号与审计列。
ALTER TABLE `course`
    ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN `version` INT NOT NULL DEFAULT 1,
    ADD COLUMN `archived_by` VARCHAR(32) NULL,
    ADD COLUMN `archived_at` DATETIME(6) NULL,
    ADD CONSTRAINT `fk_course_archived_by` FOREIGN KEY (`archived_by`)
        REFERENCES `tbl_user` (`UID`) ON DELETE RESTRICT ON UPDATE CASCADE,
    ADD CONSTRAINT `chk_course_status` CHECK (`status` IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT `chk_course_version` CHECK (`version` > 0);

-- 放开超容量硬约束：管理员带原因加课可以超出容量，普通选课仍在事务内拦截。
ALTER TABLE `course_offering`
    DROP CHECK `chk_course_offering_enrolled_count`,
    ADD COLUMN `version` INT NOT NULL DEFAULT 1,
    ADD COLUMN `created_by` VARCHAR(32) NULL,
    ADD COLUMN `cancelled_by` VARCHAR(32) NULL,
    ADD COLUMN `cancelled_at` DATETIME(6) NULL,
    ADD CONSTRAINT `chk_course_offering_enrolled_nonnegative` CHECK (`enrolled_count` >= 0);

-- 7.2 排课方案审计列。
ALTER TABLE `schedule_plan`
    ADD COLUMN `created_by` VARCHAR(32) NULL COMMENT '创建人UID',
    ADD COLUMN `published_by` VARCHAR(32) NULL COMMENT '发布人UID',
    ADD COLUMN `published_at` DATETIME(6) NULL COMMENT '发布时间',
    ADD CONSTRAINT `fk_schedule_plan_created_by` FOREIGN KEY (`created_by`)
        REFERENCES `tbl_user` (`UID`) ON DELETE RESTRICT ON UPDATE CASCADE,
    ADD CONSTRAINT `fk_schedule_plan_published_by` FOREIGN KEY (`published_by`)
        REFERENCES `tbl_user` (`UID`) ON DELETE RESTRICT ON UPDATE CASCADE;

-- 教学日历当前正式方案指针，发布事务更新；旧方案保留为不可变历史。
ALTER TABLE `teaching_calendar`
    ADD COLUMN `current_schedule_plan_id` BIGINT NULL COMMENT '当前正式排课方案ID',
    ADD KEY `idx_teaching_calendar_current_plan` (`current_schedule_plan_id`),
    ADD CONSTRAINT `fk_teaching_calendar_current_plan`
        FOREIGN KEY (`current_schedule_plan_id`) REFERENCES `schedule_plan` (`id`)
        ON DELETE SET NULL ON UPDATE CASCADE;

-- 7.3 教学安排聚合。
CREATE TABLE IF NOT EXISTS `course_schedule_arrangement` (
    `arrangement_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '教学安排唯一标识',
    `plan_id` BIGINT NOT NULL COMMENT '所属排课方案ID',
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `teacher_uid` VARCHAR(32) NULL COMMENT '任课教师UID，历史迁移行允许为空',
    `assistant_uid` VARCHAR(32) NULL COMMENT '助教UID',
    `classroom_id` BIGINT NULL COMMENT '教室ID，历史迁移行允许为空',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-生效, DISABLED-停用',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `created_by` VARCHAR(32) NULL COMMENT '创建人UID',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6) COMMENT '更新时间',
    PRIMARY KEY (`arrangement_id`),
    UNIQUE KEY `uk_course_schedule_arrangement_plan` (`arrangement_id`, `plan_id`),
    KEY `idx_course_schedule_arrangement_plan_offering` (`plan_id`, `offering_id`, `status`),
    KEY `idx_course_schedule_arrangement_teacher` (`teacher_uid`),
    KEY `idx_course_schedule_arrangement_assistant` (`assistant_uid`),
    KEY `idx_course_schedule_arrangement_classroom` (`classroom_id`),
    CONSTRAINT `fk_course_schedule_arrangement_plan`
        FOREIGN KEY (`plan_id`) REFERENCES `schedule_plan` (`id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_arrangement_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_arrangement_teacher`
        FOREIGN KEY (`teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_arrangement_assistant`
        FOREIGN KEY (`assistant_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_arrangement_classroom`
        FOREIGN KEY (`classroom_id`) REFERENCES `classroom` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_arrangement_status`
        CHECK (`status` IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT `chk_course_schedule_arrangement_version` CHECK (`version` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教学安排聚合表';

-- 每条旧 rule 迁移为一个独立 arrangement，再回填并收紧为非空。
ALTER TABLE `course_schedule_rule`
    ADD COLUMN `arrangement_id` BIGINT NULL COMMENT '教学安排ID，迁移期间可空';

INSERT INTO `course_schedule_arrangement`
    (`arrangement_id`, `plan_id`, `offering_id`, `teacher_uid`, `assistant_uid`,
     `classroom_id`, `status`, `version`, `created_by`, `created_at`, `updated_at`)
SELECT `r`.`id`, `r`.`plan_id`, `r`.`course_offering_id`, NULL, NULL, NULL,
       `r`.`status`, 1, NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM `course_schedule_rule` `r`;

UPDATE `course_schedule_rule`
SET `arrangement_id` = `id`
WHERE `arrangement_id` IS NULL;

ALTER TABLE `course_schedule_rule`
    MODIFY COLUMN `arrangement_id` BIGINT NOT NULL COMMENT '教学安排ID',
    ADD CONSTRAINT `fk_course_schedule_rule_arrangement`
        FOREIGN KEY (`arrangement_id`, `plan_id`)
        REFERENCES `course_schedule_arrangement` (`arrangement_id`, `plan_id`)
        ON DELETE CASCADE ON UPDATE CASCADE;

-- 7.4 临时调课。
CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_request` (
    `request_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '调课申请唯一标识',
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `requested_by` VARCHAR(32) NOT NULL COMMENT '申请教师UID',
    `reason` VARCHAR(500) NOT NULL COMMENT '调课原因',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待审批, APPROVED-通过, REJECTED-驳回',
    `new_weekday` TINYINT NOT NULL COMMENT '新上课星期: 1-星期一, 7-星期日',
    `new_start_period` SMALLINT NOT NULL COMMENT '新起始节次',
    `new_end_period` SMALLINT NOT NULL COMMENT '新结束节次',
    `new_teacher_uid` VARCHAR(32) NULL COMMENT '新教师UID',
    `new_assistant_uid` VARCHAR(32) NULL COMMENT '新助教UID',
    `new_classroom_id` BIGINT NULL COMMENT '新教室ID',
    `submitted_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交时间',
    `reviewed_by` VARCHAR(32) NULL COMMENT '审批人UID',
    `reviewed_at` DATETIME(6) NULL COMMENT '审批时间',
    `review_comment` VARCHAR(500) NULL COMMENT '审批意见',
    PRIMARY KEY (`request_id`),
    KEY `idx_course_schedule_adjustment_request_offering`
        (`offering_id`, `status`, `submitted_at`),
    KEY `idx_course_schedule_adjustment_request_review`
        (`status`, `submitted_at`),
    KEY `idx_course_schedule_adjustment_request_teacher` (`requested_by`),
    KEY `idx_course_schedule_adjustment_request_new_teacher` (`new_teacher_uid`),
    KEY `idx_course_schedule_adjustment_request_new_assistant` (`new_assistant_uid`),
    KEY `idx_course_schedule_adjustment_request_new_classroom` (`new_classroom_id`),
    KEY `idx_course_schedule_adjustment_request_reviewer` (`reviewed_by`),
    CONSTRAINT `fk_course_schedule_adjustment_request_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_request_requester`
        FOREIGN KEY (`requested_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_request_new_teacher`
        FOREIGN KEY (`new_teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_request_new_assistant`
        FOREIGN KEY (`new_assistant_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_request_new_classroom`
        FOREIGN KEY (`new_classroom_id`) REFERENCES `classroom` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_request_reviewer`
        FOREIGN KEY (`reviewed_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_adjustment_request_version` CHECK (`version` > 0),
    CONSTRAINT `chk_course_schedule_adjustment_request_status`
        CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT `chk_course_schedule_adjustment_request_weekday`
        CHECK (`new_weekday` BETWEEN 1 AND 7),
    CONSTRAINT `chk_course_schedule_adjustment_request_period`
        CHECK (`new_start_period` > 0 AND `new_end_period` >= `new_start_period`),
    CONSTRAINT `chk_course_schedule_adjustment_request_review`
        CHECK ((`status` = 'PENDING' AND `reviewed_at` IS NULL)
            OR (`status` <> 'PENDING' AND `reviewed_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课申请头，一张申请只保存一套新安排';

CREATE TABLE IF NOT EXISTS `course_schedule_adjustment_target` (
    `target_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '调课目标唯一标识',
    `request_id` BIGINT NOT NULL COMMENT '调课申请ID',
    `original_occurrence_id` BIGINT NOT NULL COMMENT '原课程实例ID',
    `original_week_no` SMALLINT NOT NULL COMMENT '原教学周次快照',
    `original_start_at` DATETIME(6) NOT NULL COMMENT '原开始时间快照（UTC）',
    `original_end_at` DATETIME(6) NOT NULL COMMENT '原结束时间快照（UTC）',
    `original_teacher_uid` VARCHAR(32) NULL COMMENT '原教师UID快照',
    `original_assistant_uid` VARCHAR(32) NULL COMMENT '原助教UID快照',
    `original_classroom_id` BIGINT NULL COMMENT '原教室ID快照',
    PRIMARY KEY (`target_id`),
    UNIQUE KEY `uk_course_schedule_adjustment_target`
        (`request_id`, `original_occurrence_id`),
    KEY `idx_course_schedule_adjustment_target_occurrence` (`original_occurrence_id`),
    KEY `idx_course_schedule_adjustment_target_teacher` (`original_teacher_uid`),
    KEY `idx_course_schedule_adjustment_target_assistant` (`original_assistant_uid`),
    KEY `idx_course_schedule_adjustment_target_classroom` (`original_classroom_id`),
    CONSTRAINT `fk_course_schedule_adjustment_target_request`
        FOREIGN KEY (`request_id`) REFERENCES `course_schedule_adjustment_request` (`request_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_target_occurrence`
        FOREIGN KEY (`original_occurrence_id`) REFERENCES `course_occurrence` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_target_teacher`
        FOREIGN KEY (`original_teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_target_assistant`
        FOREIGN KEY (`original_assistant_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_target_classroom`
        FOREIGN KEY (`original_classroom_id`) REFERENCES `classroom` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_adjustment_target_week` CHECK (`original_week_no` > 0),
    CONSTRAINT `chk_course_schedule_adjustment_target_time`
        CHECK (`original_start_at` < `original_end_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课目标周及其原安排快照';

CREATE TABLE IF NOT EXISTS `course_schedule_adjustment` (
    `adjustment_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '调课结果唯一标识',
    `request_id` BIGINT NOT NULL COMMENT '调课申请ID',
    `original_occurrence_id` BIGINT NOT NULL COMMENT '原课程实例ID',
    `start_at_utc` DATETIME(6) NOT NULL COMMENT '调整后开始时间（UTC）',
    `end_at_utc` DATETIME(6) NOT NULL COMMENT '调整后结束时间（UTC）',
    `teacher_uid` VARCHAR(32) NOT NULL COMMENT '调整后教师UID',
    `assistant_uid` VARCHAR(32) NULL COMMENT '调整后助教UID',
    `classroom_id` BIGINT NULL COMMENT '调整后教室ID',
    `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-生效, CANCELLED-已撤销',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '审批创建时间',
    `active_original_occurrence_id` BIGINT GENERATED ALWAYS AS
        (CASE WHEN `status` = 'ACTIVE' THEN `original_occurrence_id` ELSE NULL END) STORED,
    PRIMARY KEY (`adjustment_id`),
    UNIQUE KEY `uk_active_adjustment_occurrence` (`active_original_occurrence_id`),
    KEY `idx_course_schedule_adjustment_request` (`request_id`, `status`),
    KEY `idx_course_schedule_adjustment_occurrence` (`original_occurrence_id`, `status`),
    KEY `idx_course_schedule_adjustment_teacher` (`teacher_uid`),
    KEY `idx_course_schedule_adjustment_assistant` (`assistant_uid`),
    KEY `idx_course_schedule_adjustment_classroom` (`classroom_id`),
    CONSTRAINT `fk_course_schedule_adjustment_request`
        FOREIGN KEY (`request_id`) REFERENCES `course_schedule_adjustment_request` (`request_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_occurrence`
        FOREIGN KEY (`original_occurrence_id`) REFERENCES `course_occurrence` (`id`)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT `fk_course_schedule_adjustment_teacher`
        FOREIGN KEY (`teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_assistant`
        FOREIGN KEY (`assistant_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_course_schedule_adjustment_classroom`
        FOREIGN KEY (`classroom_id`) REFERENCES `classroom` (`id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_course_schedule_adjustment_status`
        CHECK (`status` IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT `chk_course_schedule_adjustment_time` CHECK (`start_at_utc` < `end_at_utc`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调课结果，同一实例最多一条生效记录';

-- 一张多周申请只创建一条汇总通知。
ALTER TABLE `course_notice`
    ADD COLUMN `adjustment_request_id` BIGINT NULL COMMENT '关联调课申请ID',
    ADD KEY `idx_course_notice_adjustment_request` (`adjustment_request_id`),
    ADD CONSTRAINT `fk_course_notice_adjustment_request`
        FOREIGN KEY (`adjustment_request_id`)
        REFERENCES `course_schedule_adjustment_request` (`request_id`)
        ON DELETE SET NULL ON UPDATE CASCADE;

-- 7.5 成绩提交与审批。
CREATE TABLE IF NOT EXISTS `grade_submission` (
    `submission_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成绩提交唯一标识',
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '提交版本号',
    `submitted_by` VARCHAR(32) NOT NULL COMMENT '提交教师UID',
    `submitted_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '提交时间',
    `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待审批, APPROVED-通过, REJECTED-驳回',
    `reviewed_by` VARCHAR(32) NULL COMMENT '审批人UID',
    `reviewed_at` DATETIME(6) NULL COMMENT '审批时间',
    `review_comment` VARCHAR(500) NULL COMMENT '审批意见',
    `average_score` DECIMAL(5,2) NULL COMMENT '平均分快照',
    `max_score` DECIMAL(5,2) NULL COMMENT '最高分快照',
    `min_score` DECIMAL(5,2) NULL COMMENT '最低分快照',
    `failed_count` INT NOT NULL DEFAULT 0 COMMENT '不及格人数快照',
    `total_count` INT NOT NULL DEFAULT 0 COMMENT '总人数快照',
    PRIMARY KEY (`submission_id`),
    UNIQUE KEY `uk_grade_submission_offering_version` (`offering_id`, `version`),
    KEY `idx_grade_submission_review` (`status`, `submitted_at`),
    KEY `idx_grade_submission_submitter` (`submitted_by`),
    KEY `idx_grade_submission_reviewer` (`reviewed_by`),
    CONSTRAINT `fk_grade_submission_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_grade_submission_submitter`
        FOREIGN KEY (`submitted_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_grade_submission_reviewer`
        FOREIGN KEY (`reviewed_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_grade_submission_version` CHECK (`version` > 0),
    CONSTRAINT `chk_grade_submission_status`
        CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT `chk_grade_submission_review`
        CHECK ((`status` = 'PENDING' AND `reviewed_at` IS NULL)
            OR (`status` <> 'PENDING' AND `reviewed_at` IS NOT NULL)),
    CONSTRAINT `chk_grade_submission_average`
        CHECK (`average_score` IS NULL OR (`average_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_max`
        CHECK (`max_score` IS NULL OR (`max_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_min`
        CHECK (`min_score` IS NULL OR (`min_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_counts`
        CHECK (`failed_count` >= 0 AND `total_count` >= 0 AND `failed_count` <= `total_count`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成绩提交头，提交后不可修改';

CREATE TABLE IF NOT EXISTS `grade_submission_item` (
    `item_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '成绩提交明细唯一标识',
    `submission_id` BIGINT NOT NULL COMMENT '成绩提交ID',
    `enrollment_id` BIGINT NOT NULL COMMENT '选课记录ID',
    `daily_score` DECIMAL(5,2) NULL COMMENT '平时成绩快照',
    `midterm_score` DECIMAL(5,2) NULL COMMENT '期中成绩快照',
    `experiment_score` DECIMAL(5,2) NULL COMMENT '实验成绩快照',
    `finalterm_score` DECIMAL(5,2) NULL COMMENT '期末成绩快照',
    `score` DECIMAL(5,2) NULL COMMENT '总评成绩快照',
    `grade_level` TINYINT NULL COMMENT '等级成绩快照',
    `grade_point` DECIMAL(2,1) NULL COMMENT '绩点快照',
    PRIMARY KEY (`item_id`),
    UNIQUE KEY `uk_grade_submission_item` (`submission_id`, `enrollment_id`),
    KEY `idx_grade_submission_item_enrollment` (`enrollment_id`, `submission_id`),
    CONSTRAINT `fk_grade_submission_item_submission`
        FOREIGN KEY (`submission_id`) REFERENCES `grade_submission` (`submission_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_grade_submission_item_enrollment`
        FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`enrollment_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_grade_submission_item_daily_score`
        CHECK (`daily_score` IS NULL OR (`daily_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_item_midterm_score`
        CHECK (`midterm_score` IS NULL OR (`midterm_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_item_experiment_score`
        CHECK (`experiment_score` IS NULL OR (`experiment_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_item_finalterm_score`
        CHECK (`finalterm_score` IS NULL OR (`finalterm_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_item_score`
        CHECK (`score` IS NULL OR (`score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_grade_submission_item_grade_point`
        CHECK (`grade_point` IS NULL OR (`grade_point` BETWEEN 0 AND 5))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成绩提交明细历史事实';

-- 7.6 管理员操作幂等日志。
CREATE TABLE IF NOT EXISTS `admin_course_operation_log` (
    `admin_uid` VARCHAR(32) NOT NULL COMMENT '管理员UID',
    `operation_id` CHAR(36) NOT NULL COMMENT '幂等操作ID',
    `action` VARCHAR(64) NOT NULL COMMENT '管理员动作',
    `target_type` VARCHAR(32) NOT NULL COMMENT '目标类型',
    `target_id` VARCHAR(64) NOT NULL COMMENT '目标ID',
    `request_digest` CHAR(64) NOT NULL COMMENT '请求摘要',
    `request_json` JSON NOT NULL COMMENT '请求快照',
    `conflict_snapshot_json` JSON NULL COMMENT '冲突快照',
    `forced` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否强制覆盖',
    `override_reason` VARCHAR(500) NULL COMMENT '强制原因',
    `result_code` VARCHAR(48) NOT NULL COMMENT '结果码',
    `response_json` JSON NOT NULL COMMENT '响应快照',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    `completed_at` DATETIME(6) NULL COMMENT '完成时间',
    PRIMARY KEY (`admin_uid`, `operation_id`),
    KEY `idx_admin_course_operation_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_admin_course_operation_created` (`created_at`),
    CONSTRAINT `fk_admin_course_operation_admin`
        FOREIGN KEY (`admin_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_admin_course_operation_digest` CHECK (`request_digest` <> ''),
    CONSTRAINT `chk_admin_course_operation_action` CHECK (`action` <> ''),
    CONSTRAINT `chk_admin_course_operation_forced` CHECK (`forced` IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员幂等操作审计日志';
