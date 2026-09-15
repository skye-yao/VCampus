-- V007: 教师成绩工作副本、草稿明细、变更审计与提交快照的增量迁移。
-- 只新增：不回写已经执行的 V001-V006，也不修改 V004 的 grade_submission/grade_submission_item 结构，
-- 只追加可空快照列与新表；历史批次没有权重快照就保持 NULL 与 INITIAL，不猜测、不回填虚构权重。
-- 每个 DDL 都用 information_schema 守卫后动态执行：本机 MySQL 8.0 的 ADD COLUMN / ADD KEY /
-- ADD CONSTRAINT 都不支持 IF NOT EXISTS 子句，守卫让同一迁移可以安全重复执行。

-- 1. 草稿行必须属于本教学班：先给 enrollment 补 (enrollment_id, offering_id) 唯一键，
--    再让 teacher_grade_draft_item 用复合外键引用它，最后这个键逐字指向该选课记录的教学班。
--    enrollment_id 本来就是主键，这个组合键天然唯一，不可能拒绝任何合法历史行；
--    它存在只是为了让“选课记录属于哪个教学班”成为可被外键引用的键。
--    不删除 enrollment 历史：退课行只是状态变化（status=3），仍然可以被草稿引用。
SET @v007_has_enrollment_class_key = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'enrollment'
      AND INDEX_NAME = 'uk_enrollment_id_offering'
);
SET @v007_add_enrollment_class_key = IF(@v007_has_enrollment_class_key = 0,
    'ALTER TABLE `enrollment` ADD UNIQUE KEY `uk_enrollment_id_offering` (`enrollment_id`, `offering_id`)',
    'SELECT 1');
PREPARE v007_enrollment_class_key FROM @v007_add_enrollment_class_key;
EXECUTE v007_enrollment_class_key;
DEALLOCATE PREPARE v007_enrollment_class_key;

-- 2. 每班一份成绩工作副本：主键就是 offering_id，“每班唯一”由主键而不是应用代码保证。
--    revision 从 1 起递增；0 只出现在“尚无工作副本”的虚拟响应里，所以迁移就把正数约束写进 CHECK。
CREATE TABLE IF NOT EXISTS `teacher_grade_book` (
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID，每班一份工作副本',
    `revision` INT NOT NULL DEFAULT 1 COMMENT '工作副本版本，从1开始递增',
    `draft_open` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '草稿是否可编辑',
    `draft_kind` VARCHAR(16) NOT NULL DEFAULT 'INITIAL' COMMENT '草稿类型: INITIAL-首次, RESUBMISSION-驳回重提, CORRECTION-更正',
    `base_submission_id` BIGINT NULL COMMENT '更正草稿的基础提交ID，普通草稿NULL',
    `last_submission_id` BIGINT NULL COMMENT '最后一次提交批次ID，尚未提交NULL',
    `scheme_json` JSON NOT NULL COMMENT '当前成绩方案：四项组成、启用与万分比权重',
    `correction_reason` VARCHAR(500) NULL COMMENT '更正原因，更正草稿必填',
    `updated_by` VARCHAR(32) NOT NULL COMMENT '最后修改教师UID',
    `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最后修改时间（UTC）',
    PRIMARY KEY (`offering_id`),
    KEY `idx_teacher_grade_book_base_submission` (`base_submission_id`),
    KEY `idx_teacher_grade_book_last_submission` (`last_submission_id`),
    KEY `idx_teacher_grade_book_updated_by` (`updated_by`),
    CONSTRAINT `fk_teacher_grade_book_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_teacher_grade_book_updated_by`
        FOREIGN KEY (`updated_by`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_teacher_grade_book_base_submission`
        FOREIGN KEY (`base_submission_id`) REFERENCES `grade_submission` (`submission_id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `fk_teacher_grade_book_last_submission`
        FOREIGN KEY (`last_submission_id`) REFERENCES `grade_submission` (`submission_id`)
        ON DELETE SET NULL ON UPDATE CASCADE,
    CONSTRAINT `chk_teacher_grade_book_revision` CHECK (`revision` > 0),
    CONSTRAINT `chk_teacher_grade_book_draft_open` CHECK (`draft_open` IN (0, 1)),
    CONSTRAINT `chk_teacher_grade_book_draft_kind`
        CHECK (`draft_kind` IN ('INITIAL', 'RESUBMISSION', 'CORRECTION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师成绩工作副本，每班一份';

-- 3. 草稿明细：主键 (offering_id, enrollment_id)，四项分数可空（NULL 是“尚未录入”，不是 0 分）。
--    复合外键把 (enrollment_id, offering_id) 一起送到 enrollment：
--    只写 enrollment_id 会让草稿行能被塞进别的教学班，复合外键让这种行根本无法落库。
--    ON DELETE RESTRICT：enrollment 是历史事实，不因为草稿存在被删除，也不允许删出孤儿草稿。
CREATE TABLE IF NOT EXISTS `teacher_grade_draft_item` (
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `enrollment_id` BIGINT NOT NULL COMMENT '选课记录ID，必须属于同一教学班',
    `daily_score` DECIMAL(5,2) NULL COMMENT '平时成绩草稿',
    `midterm_score` DECIMAL(5,2) NULL COMMENT '期中成绩草稿',
    `experiment_score` DECIMAL(5,2) NULL COMMENT '实验成绩草稿',
    `finalterm_score` DECIMAL(5,2) NULL COMMENT '期末成绩草稿',
    PRIMARY KEY (`offering_id`, `enrollment_id`),
    KEY `idx_teacher_grade_draft_item_enrollment` (`enrollment_id`, `offering_id`),
    CONSTRAINT `fk_teacher_grade_draft_item_enrollment`
        FOREIGN KEY (`enrollment_id`, `offering_id`)
        REFERENCES `enrollment` (`enrollment_id`, `offering_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_teacher_grade_draft_item_daily_score`
        CHECK (`daily_score` IS NULL OR (`daily_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_teacher_grade_draft_item_midterm_score`
        CHECK (`midterm_score` IS NULL OR (`midterm_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_teacher_grade_draft_item_experiment_score`
        CHECK (`experiment_score` IS NULL OR (`experiment_score` BETWEEN 0 AND 100)),
    CONSTRAINT `chk_teacher_grade_draft_item_finalterm_score`
        CHECK (`finalterm_score` IS NULL OR (`finalterm_score` BETWEEN 0 AND 100))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师成绩草稿明细，复合外键拒绝跨教学班的行';

-- 4. 成绩变更审计：权重/启用变化记班级级日志（enrollment_id 为 NULL），
--    分数变化记学生级日志（带 enrollment_id 与服务器计算的旧/新总评）。
--    更正必须有原因（reason），before/after 保存规范化前后快照；首次创建没有 before_json。
CREATE TABLE IF NOT EXISTS `teacher_grade_change_log` (
    `log_id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '变更日志唯一标识',
    `teacher_uid` VARCHAR(32) NOT NULL COMMENT '操作教师UID',
    `offering_id` BIGINT NOT NULL COMMENT '教学班ID',
    `enrollment_id` BIGINT NULL COMMENT '选课记录ID，班级级变更（如权重）NULL',
    `operation_id` CHAR(36) NOT NULL COMMENT '触发本次变更的幂等操作ID',
    `book_revision` INT NOT NULL COMMENT '变更后的工作副本版本',
    `action` VARCHAR(32) NOT NULL COMMENT '变更动作',
    `before_json` JSON NULL COMMENT '变更前快照，首次创建NULL',
    `after_json` JSON NULL COMMENT '变更后快照',
    `reason` VARCHAR(500) NULL COMMENT '更正原因',
    `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间（UTC）',
    PRIMARY KEY (`log_id`),
    KEY `idx_teacher_grade_change_log_offering` (`offering_id`, `created_at`),
    KEY `idx_teacher_grade_change_log_enrollment` (`enrollment_id`, `created_at`),
    KEY `idx_teacher_grade_change_log_teacher` (`teacher_uid`, `created_at`),
    CONSTRAINT `fk_teacher_grade_change_log_teacher`
        FOREIGN KEY (`teacher_uid`) REFERENCES `tbl_user` (`UID`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `fk_teacher_grade_change_log_offering`
        FOREIGN KEY (`offering_id`) REFERENCES `course_offering` (`offering_id`)
        ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT `fk_teacher_grade_change_log_enrollment`
        FOREIGN KEY (`enrollment_id`) REFERENCES `enrollment` (`enrollment_id`)
        ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT `chk_teacher_grade_change_log_revision` CHECK (`book_revision` > 0),
    CONSTRAINT `chk_teacher_grade_change_log_action` CHECK (`action` <> '')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='教师成绩变更审计日志';

-- 5. grade_submission 快照扩充：全部可空，旧批次保持 NULL，不为了“可重算”补一份当时的方案。
--    submission_kind 默认 INITIAL：历史批次就是普通首次提交，不会被标成重提或更正。
--    MySQL 8.0 没有无条件的 ADD COLUMN，这里先查 information_schema 再动态执行。
SET @v007_has_scheme_snapshot = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grade_submission'
      AND COLUMN_NAME = 'scheme_snapshot_json'
);
SET @v007_add_submission_snapshot = IF(@v007_has_scheme_snapshot = 0,
    'ALTER TABLE `grade_submission`
        ADD COLUMN `scheme_snapshot_json` JSON NULL COMMENT ''提交时的成绩方案快照，历史批次保持NULL'',
        ADD COLUMN `roster_digest` CHAR(64) NULL COMMENT ''提交时的正常名单ID集合SHA-256摘要，历史批次保持NULL'',
        ADD COLUMN `base_submission_id` BIGINT NULL COMMENT ''更正批次的基础提交ID，普通提交NULL'',
        ADD COLUMN `correction_reason` VARCHAR(500) NULL COMMENT ''更正原因，更正批次必填'',
        ADD COLUMN `submission_kind` VARCHAR(16) NOT NULL DEFAULT ''INITIAL'' COMMENT ''提交类型: INITIAL-首次提交, RESUBMISSION-驳回重提, CORRECTION-更正'',
        ADD KEY `idx_grade_submission_base` (`base_submission_id`),
        ADD CONSTRAINT `fk_grade_submission_base`
            FOREIGN KEY (`base_submission_id`) REFERENCES `grade_submission` (`submission_id`)
            ON DELETE SET NULL ON UPDATE CASCADE,
        ADD CONSTRAINT `chk_grade_submission_kind`
            CHECK (`submission_kind` IN (''INITIAL'', ''RESUBMISSION'', ''CORRECTION''))',
    'SELECT 1');
PREPARE v007_submission_snapshot FROM @v007_add_submission_snapshot;
EXECUTE v007_submission_snapshot;
DEALLOCATE PREPARE v007_submission_snapshot;

-- 6. grade_submission_item 身份快照：新批次保存提交时的学号与姓名，
--    旧批次保持 NULL，继续按 enrollment 的旧 JOIN 显示，不伪造身份。
SET @v007_has_student_snapshot = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'grade_submission_item'
      AND COLUMN_NAME = 'student_uid_snapshot'
);
SET @v007_add_student_snapshot = IF(@v007_has_student_snapshot = 0,
    'ALTER TABLE `grade_submission_item`
        ADD COLUMN `student_uid_snapshot` VARCHAR(32) NULL COMMENT ''提交时的学生UID快照，历史批次保持NULL'',
        ADD COLUMN `student_name_snapshot` VARCHAR(50) NULL COMMENT ''提交时的学生姓名快照，历史批次保持NULL''',
    'SELECT 1');
PREPARE v007_student_snapshot FROM @v007_add_student_snapshot;
EXECUTE v007_student_snapshot;
DEALLOCATE PREPARE v007_student_snapshot;
