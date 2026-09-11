-- Deterministic fixtures for virtual_campus_course_test only.
-- Application login password for all four accounts: course-test-only
-- Test-only salt: Y291cnNlLXRlc3Qtc2FsdC12MQ==
-- PasswordUtil.hashPassword result: J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=
-- Never reuse these application credentials outside the dedicated test schema.

INSERT INTO `tbl_user` (`UID`, `name`, `password`, `salt`, `role`, `college`, `major`)
VALUES
    ('student-alpha', 'Course Test Student A',
     'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=', 'Y291cnNlLXRlc3Qtc2FsdC12MQ==', 2,
     'Engineering', 'Computer Science'),
    ('student-beta', 'Course Test Student B',
     'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=', 'Y291cnNlLXRlc3Qtc2FsdC12MQ==', 2,
     'Engineering', 'Computer Science'),
    ('teacher-alpha', 'Course Test Teacher A',
     'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=', 'Y291cnNlLXRlc3Qtc2FsdC12MQ==', 1,
     'Engineering', 'Professor'),
    ('teacher-beta', 'Course Test Teacher B',
     'J38xndyip6HSrAYWERZsw0nzctYaMzc2lGgKFxrORJo=', 'Y291cnNlLXRlc3Qtc2FsdC12MQ==', 1,
     'Engineering', 'Lecturer');

INSERT INTO `major` (`major_id`, `major_code`, `major_name`, `college`)
VALUES (10, 'CS', 'Computer Science', 'Engineering');

INSERT INTO `course`
    (`course_id`, `course_code`, `course_name`, `credit`, `credit_hours`,
     `course_type`, `allow_cross_major`, `description`, `prerequisites`, `final_exam`)
VALUES
    (1001, 'CS101', 'Programming Fundamentals', 3.00, 48, 1, 1,
     'Programming and problem solving', NULL, 1),
    (1002, 'CS202', 'Database Systems', 3.00, 48, 1, 0,
     'Relational data management', 'CS101', 1);

INSERT INTO `course_major` (`course_id`, `major_id`)
VALUES (1001, 10), (1002, 10);

INSERT INTO `course_year` (`course_id`, `year`)
VALUES (1001, 1), (1002, 3);

INSERT INTO `course_offering`
    (`offering_id`, `offering_code`, `course_id`, `academic_year`, `semester`,
     `capacity`, `wanted_count`, `enrolled_count`, `website`, `status`)
VALUES
    (2001, 'CS101-2026-2-A', 1001, 2026, 2, 30, 2, 1, NULL, 2),
    (2002, 'CS101-2026-2-B', 1001, 2026, 2, 30, 2, 0, NULL, 2),
    (2003, 'CS101-2027-3-A', 1001, 2027, 3, 30, 0, 0, NULL, 2),
    (2004, 'CS202-2026-2-A', 1002, 2026, 2, 30, 1, 1, NULL, 2);

INSERT INTO `course_offering_teacher` (`offering_id`, `uid`, `role`)
VALUES
    (2001, 'teacher-alpha', 0),
    (2002, 'teacher-beta', 0);

INSERT INTO `teaching_calendar`
    (`id`, `name`, `academic_year`, `semester`, `week1_start_date`, `timezone`, `version`, `status`)
VALUES
    (3001, 'Course test calendar', 2026, 2, '2026-09-07', 'Asia/Shanghai', 1, 'PUBLISHED');

INSERT INTO `day_template` (`id`, `name`, `version`)
VALUES (3101, 'Course test weekday', 1);

INSERT INTO `period_definition`
    (`id`, `day_template_id`, `period_no`, `start_time`, `end_time`)
VALUES
    (3201, 3101, 1, '08:00:00', '08:45:00'),
    (3202, 3101, 2, '08:50:00', '09:35:00'),
    (3203, 3101, 3, '10:00:00', '10:45:00'),
    (3204, 3101, 4, '10:50:00', '11:35:00');

INSERT INTO `calendar_date`
    (`id`, `calendar_id`, `local_date`, `week_no`, `teaching_weekday`,
     `day_template_id`, `is_teaching_day`)
VALUES
    (3301, 3001, '2026-09-08', 1, 2, 3101, 1),
    (3302, 3001, '2026-09-10', 1, 4, 3101, 1);

INSERT INTO `schedule_plan`
    (`id`, `name`, `calendar_id`, `revision`, `status`, `created_at`, `updated_at`)
VALUES
    (4001, 'Course test published plan', 3001, 1, 'PUBLISHED',
     '2026-08-01 00:00:00', '2026-08-01 00:00:00');

INSERT INTO `course_schedule_rule`
    (`id`, `plan_id`, `course_offering_id`, `weekday`, `start_period`, `end_period`, `status`)
VALUES
    (4101, 4001, 2001, 2, 1, 2, 'ACTIVE'),
    (4102, 4001, 2001, 4, 3, 4, 'ACTIVE'),
    (4103, 4001, 2002, 2, 1, 2, 'ACTIVE'),
    (4104, 4001, 2004, 4, 1, 2, 'ACTIVE');

INSERT INTO `course_schedule_rule_week` (`rule_id`, `week_no`)
VALUES (4101, 1), (4102, 1), (4103, 1), (4104, 1);

INSERT INTO `course_occurrence`
    (`id`, `rule_id`, `plan_id`, `start_at`, `end_at`, `week_no`, `teaching_weekday`)
VALUES
    (4201, 4101, 4001, '2026-09-08 00:00:00', '2026-09-08 01:35:00', 1, 2),
    (4202, 4102, 4001, '2026-09-10 02:00:00', '2026-09-10 03:35:00', 1, 4),
    (4203, 4103, 4001, '2026-09-08 00:00:00', '2026-09-08 01:35:00', 1, 2),
    (4204, 4104, 4001, '2026-09-10 00:00:00', '2026-09-10 01:35:00', 1, 4);

INSERT INTO `classroom` (`id`, `name`, `capacity`, `electric`)
VALUES (4301, 'Test Room 101', 60, 1), (4302, 'Test Room 102', 40, 1);

INSERT INTO `schedule_resource` (`id`, `resource_type`, `business_id`, `conflict_mode`)
VALUES
    (4401, 'teacher', 'teacher-alpha', 'EXCLUSIVE'),
    (4402, 'teacher', 'teacher-beta', 'EXCLUSIVE'),
    (4403, 'classroom', '4301', 'EXCLUSIVE'),
    (4404, 'classroom', '4302', 'EXCLUSIVE');

INSERT INTO `resource_booking`
    (`id`, `plan_id`, `occurrence_id`, `resource_id`, `resource_role`)
VALUES
    (4501, 4001, 4201, 4401, 'TEACHER'),
    (4502, 4001, 4201, 4403, 'CLASSROOM'),
    (4503, 4001, 4202, 4401, 'TEACHER'),
    (4504, 4001, 4202, 4403, 'CLASSROOM'),
    (4505, 4001, 4203, 4402, 'TEACHER'),
    (4506, 4001, 4203, 4404, 'CLASSROOM');

INSERT INTO `course_offering_conflict`
    (`plan_id`, `course_offering_a_id`, `course_offering_b_id`, `conflict_count`,
     `first_conflict_at`, `last_conflict_at`)
VALUES
    (4001, 2001, 2002, 1, '2026-09-08 00:00:00', '2026-09-08 00:00:00');

INSERT INTO `student_academic_profile`
    (`profile_id`, `uid`, `major_id`, `cohort_year`, `status`)
VALUES
    (5001, 'student-alpha', 10, 2026, 'ACTIVE'),
    (5002, 'student-beta', 10, 2026, 'ACTIVE');

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
     `uid`, `status`, `select_time`)
VALUES
    (6001, 2001, 1001, 2026, 2, 'student-alpha', 2, '2026-09-01 00:00:00.100001'),
    (6002, 2004, 1002, 2026, 2, 'student-beta', 2, '2026-09-01 00:00:00.100002');

INSERT INTO `grade`
    (`grade_id`, `enrollment_id`, `daily_score`, `midterm_score`, `finalterm_score`,
     `experiment_score`, `score`, `grade_level`, `grade_point`, `is_published`, `publish_time`)
VALUES
    (6101, 6002, 88.00, 86.00, 92.00, 90.00, 89.50, 4, 4.0, 1,
     '2026-09-05 00:00:00.123456');

INSERT INTO `course_plan_item`
    (`plan_item_id`, `uid`, `offering_id`, `status`, `last_failure_reason`)
VALUES (6201, 'student-alpha', 2002, 'PLANNED', NULL);

INSERT INTO `course_waitlist`
    (`waitlist_id`, `uid`, `offering_id`, `status`, `queue_time`)
VALUES (6301, 'student-beta', 2002, 'WAITING', '2026-09-01 00:00:00.654321');

INSERT INTO `training_plan`
    (`plan_id`, `major_id`, `cohort_year`, `version`, `plan_name`, `status`)
VALUES (7001, 10, 2026, 1, 'CS 2026 Training Plan', 'PUBLISHED');

INSERT INTO `training_plan_group`
    (`group_id`, `plan_id`, `group_name`, `required_credits`, `sort_order`)
VALUES (7101, 7001, 'Core Courses', 6.00, 1);

INSERT INTO `training_plan_course`
    (`group_id`, `course_id`, `required`, `recommended_semester`)
VALUES (7101, 1001, 1, 1), (7101, 1002, 1, 3);

INSERT INTO `course_notice`
    (`notice_id`, `offering_id`, `title`, `content`, `notice_type`, `week_no`,
     `status`, `created_by`, `published_at`)
VALUES
    (8001, 2001, 'Course test notice', 'Deterministic published notice.',
     'GENERAL', 1, 'PUBLISHED', 'teacher-alpha', '2026-09-01 00:00:00.123456');

INSERT INTO `course_operation_log`
    (`uid`, `operation_id`, `action`, `offering_id`, `request_digest`,
     `result_code`, `response_json`, `created_at`, `completed_at`)
VALUES
    ('student-alpha', '00000000-0000-0000-0000-000000000001', 'addToPlan', 2002,
     REPEAT('a', 64), 'OK', JSON_OBJECT('state', 'PLANNED'),
     '2026-09-01 00:00:00.100001', '2026-09-01 00:00:00.100002');

INSERT INTO `course_event_outbox`
    (`event_id`, `uid`, `event_type`, `academic_year`, `semester`, `offering_id`,
     `payload`, `created_at`, `attempt_count`)
VALUES
    (9001, 'student-beta', 'WAITLIST_OFFERED', 2026, 2, 2002,
     JSON_OBJECT('offeringId', '2002'), '2026-09-01 00:00:00.100003', 0);
