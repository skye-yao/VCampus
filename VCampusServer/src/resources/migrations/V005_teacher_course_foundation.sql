-- V005: 教师端课程基础迁移。
-- 只新增：不回写已经执行的 V001-V004，不修改任何既有行。
-- 教师 UID 一律取自服务端校验过的 Session，本迁移只保存已确认的身份。

-- 6.1 开课学院：教师只读，历史数据没有维护时保持 NULL，不能用教师个人学院冒充。
-- MySQL 8 的 ADD COLUMN 没有 IF NOT EXISTS，先查 information_schema 再执行，保证可重复运行。
SET @v005_add_offering_college := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE `course` ADD COLUMN `offering_college` VARCHAR(100) NULL',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course'
      AND COLUMN_NAME = 'offering_college');
PREPARE v005_add_offering_college FROM @v005_add_offering_college;
EXECUTE v005_add_offering_college;
DEALLOCATE PREPARE v005_add_offering_college;

-- 6.2 教师幂等操作日志：成功写入与业务变更在同一事务；请求体按字段固定顺序规范化，
-- 摘要为规范化请求的 SHA-256（CHAR(64) 十六进制小写）。
CREATE TABLE IF NOT EXISTS `teacher_course_operation_log` (
    `teacher_uid` VARCHAR(32) NOT NULL COMMENT '教师UID',
    `operation_id` CHAR(36) NOT NULL COMMENT '幂等操作ID',
    `action` VARCHAR(64) NOT NULL COMMENT '教师动作',
    `target_type` VARCHAR(32) NOT NULL COMMENT '目标类型',
    `target_id` VARCHAR(64) NOT NULL COMMENT '目标ID',
    `request_digest` CHAR(64) NOT NULL COMMENT '规范化请求的SHA-256摘要',
    `request_json` JSON NOT NULL COMMENT '规范化请求快照',
    `response_json` JSON NOT NULL COMMENT '响应快照',
    `result_code` VARCHAR(48) NOT NULL COMMENT '结果码',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (`teacher_uid`, `operation_id`),
    KEY `idx_teacher_course_operation_target` (`target_type`, `target_id`, `created_at`),
    KEY `idx_teacher_course_operation_created` (`created_at`),
    CONSTRAINT `fk_teacher_course_operation_teacher`
        FOREIGN KEY (`teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_teacher_course_operation_action` CHECK (`action` <> ''),
    CONSTRAINT `chk_teacher_course_operation_digest` CHECK (`request_digest` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师幂等操作审计日志';

-- 6.3 教师申请结果已读标记：stateKey = status + ':' + handledAt，未处理时使用 submittedAt。
-- 读过 PENDING 不代表已经读过随后到达的 APPROVED，所以状态键必须随结果变化而更新。
-- 服务端在读写前都要先校验申请确实属于该教师。
CREATE TABLE IF NOT EXISTS `teacher_application_read` (
    `teacher_uid` VARCHAR(32) NOT NULL COMMENT '教师UID',
    `application_type` VARCHAR(32) NOT NULL COMMENT '申请类型: SCHEDULE_ADJUSTMENT-调课申请, GRADE_SUBMISSION-成绩提交',
    `application_id` BIGINT NOT NULL COMMENT '申请ID，按类型指向调课申请或成绩提交',
    `seen_state_key` VARCHAR(128) NOT NULL COMMENT '已读时看到的结果状态键',
    `read_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '读取时间',
    PRIMARY KEY (`teacher_uid`, `application_type`, `application_id`),
    CONSTRAINT `fk_teacher_application_read_teacher`
        FOREIGN KEY (`teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_teacher_application_read_type`
        CHECK (`application_type` IN ('SCHEDULE_ADJUSTMENT', 'GRADE_SUBMISSION')),
    CONSTRAINT `chk_teacher_application_read_state_key` CHECK (`seen_state_key` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师申请结果已读标记';
