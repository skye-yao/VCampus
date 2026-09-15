-- V006: 教师调课申请增量迁移。
-- 只新增列、索引与约束，不回写已执行的 V001-V005，也不修改 V004 的申请/目标/结果结构。
-- 目标：跨周目标日期（目标教学日）、教师撤销状态 WITHDRAWN，以及升级前的历史数据审计。
-- 每个 DDL 都用 information_schema 守卫后动态执行：本机 MySQL 8.0 不接受
-- “ADD COLUMN IF NOT EXISTS”“约束 IF EXISTS”这类子句，守卫让同一迁移可以安全重复执行。

-- 1. 升级前历史数据审计（只读，不修改任何历史行）。
-- 已终结（APPROVED/REJECTED）的记录必须有审核身份：reviewed_at 与 reviewed_by 都不能为空。
-- V006 之前不可能存在 WITHDRAWN，这里的口径与升级后完全一致。
-- 本查询在升级前逐行报告违规记录（request_id、status、缺哪个字段），
-- 由操作者用真实审核人补齐后再重跑，绝不伪造审核人、也不静默修补历史数据。
-- 审计结果必须为空；非空即中止升级。
SELECT `request_id`, `status`,
       CASE WHEN `reviewed_by` IS NULL THEN 'reviewed_by' ELSE '' END AS `missing_reviewed_by`,
       CASE WHEN `reviewed_at` IS NULL THEN 'reviewed_at' ELSE '' END AS `missing_reviewed_at`
FROM `course_schedule_adjustment_request`
WHERE `status` IN ('APPROVED', 'REJECTED')
    AND (`reviewed_at` IS NULL OR `reviewed_by` IS NULL)
ORDER BY `request_id`;

-- 2. 撤销状态：教师撤销是申请人自己的终态，不是管理员驳回。
SET @v006_has_withdrawn_at = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_schedule_adjustment_request'
      AND COLUMN_NAME = 'withdrawn_at'
);
SET @v006_add_withdrawn_at = IF(@v006_has_withdrawn_at = 0,
    'ALTER TABLE `course_schedule_adjustment_request` ADD COLUMN `withdrawn_at` DATETIME(6) NULL COMMENT ''教师撤销时间（UTC）''',
    'SELECT 1');
PREPARE v006_withdrawn_at FROM @v006_add_withdrawn_at;
EXECUTE v006_withdrawn_at;
DEALLOCATE PREPARE v006_withdrawn_at;

-- 替换 V004 的三态状态约束，换成四态（先删后加，顺序固定）。
SET @v006_has_status_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_schedule_adjustment_request'
      AND CONSTRAINT_NAME = 'chk_course_schedule_adjustment_request_status'
);
SET @v006_drop_status_check = IF(@v006_has_status_check > 0,
    'ALTER TABLE `course_schedule_adjustment_request` DROP CHECK `chk_course_schedule_adjustment_request_status`',
    'SELECT 1');
PREPARE v006_drop_status FROM @v006_drop_status_check;
EXECUTE v006_drop_status;
DEALLOCATE PREPARE v006_drop_status;
ALTER TABLE `course_schedule_adjustment_request`
    ADD CONSTRAINT `chk_course_schedule_adjustment_request_status`
        CHECK (`status` IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'));

-- 四态下的状态-审核-撤销组合（替换 V004 的三态版本）：
--   PENDING           未审批、未撤销
--   APPROVED/REJECTED 有 reviewed_at、未撤销（reviewed_by 由第 1 节审计与写入方保证）
--   WITHDRAWN         有 withdrawn_at、无 reviewed_at/reviewed_by
-- 这样教师撤销不会被写成管理员驳回，已终结记录也不会同时带撤销时间。
SET @v006_has_review_check = (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_schedule_adjustment_request'
      AND CONSTRAINT_NAME = 'chk_course_schedule_adjustment_request_review'
);
SET @v006_drop_review_check = IF(@v006_has_review_check > 0,
    'ALTER TABLE `course_schedule_adjustment_request` DROP CHECK `chk_course_schedule_adjustment_request_review`',
    'SELECT 1');
PREPARE v006_drop_review FROM @v006_drop_review_check;
EXECUTE v006_drop_review;
DEALLOCATE PREPARE v006_drop_review;
ALTER TABLE `course_schedule_adjustment_request`
    ADD CONSTRAINT `chk_course_schedule_adjustment_request_review`
        CHECK ((`status` = 'PENDING' AND `withdrawn_at` IS NULL AND `reviewed_at` IS NULL)
            OR (`status` IN ('APPROVED', 'REJECTED') AND `reviewed_at` IS NOT NULL
                AND `withdrawn_at` IS NULL)
            OR (`status` = 'WITHDRAWN' AND `withdrawn_at` IS NOT NULL AND `reviewed_at` IS NULL));

-- 3. 目标教学日：新申请必须写入真实教学日，跨周由它表达。
-- 历史 NULL 行继续由 original_week_no + request.new_weekday 推导日期，
-- 保持管理员原有的同周/多周语义，绝不猜测一个日期。
SET @v006_has_target_date = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_schedule_adjustment_target'
      AND COLUMN_NAME = 'target_calendar_date_id'
);
SET @v006_add_target_date = IF(@v006_has_target_date = 0,
    'ALTER TABLE `course_schedule_adjustment_target` ADD COLUMN `target_calendar_date_id` BIGINT NULL COMMENT ''目标教学日ID，历史行NULL'', ADD KEY `idx_course_schedule_adjustment_target_calendar_date` (`target_calendar_date_id`), ADD CONSTRAINT `fk_course_schedule_adjustment_target_calendar_date` FOREIGN KEY (`target_calendar_date_id`) REFERENCES `calendar_date` (`id`) ON DELETE SET NULL ON UPDATE CASCADE',
    'SELECT 1');
PREPARE v006_target_date FROM @v006_add_target_date;
EXECUTE v006_target_date;
DEALLOCATE PREPARE v006_target_date;
