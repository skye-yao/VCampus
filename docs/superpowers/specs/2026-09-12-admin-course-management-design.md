# 管理员端课程管理与审批系统设计

## 1. 目标

在 `feature/course-management-client` 分支上新增独立的管理员端教务管理子系统，覆盖课程目录、教学班、排课、教学班学生、调课审批和成绩审批，并与现有学生端选课、课表、通知和成绩查询形成完整闭环。

本设计的核心目标是：

- 管理员端拥有独立的信息结构和客户端 Service，不污染现有学生端页面及 `CourseService`。
- 管理员请求通过独立的 TCP `courseAdmin` 模块进入服务端，并在服务端重新校验 Session 和管理员角色。
- 课程、教学班、排课和审批写操作具有事务、幂等、并发版本和审计保护。
- 已发布排课方案保持不可变，临时调课作为具体课次的例外记录直接生效。
- 成绩审批通过即发布，所有被驳回、重新提交和更正版本均可追溯。
- 现有学生端 TCP 课程模块、选课事务、候补流程和查询行为保持兼容。

## 2. 现有基线与范围

### 2.1 实施基线

- 工作树：`D:/JavaProject/VCampus/.worktrees/course-management-client`
- 分支：`feature/course-management-client`
- `feature/courses` 只作为早期数据库结构参考，不作为本功能的写入位置。
- 当前分支已经具备学生端课程页面、公共课程 DTO、真实 Socket Service、服务端课程 Handler/Service/DAO、V001-V003 迁移、候补推送和端到端测试。
- 当前 `CourseHandler` 只接受学生角色，管理员功能尚未接入。
- 当前工作树存在用户未提交的课表、成绩和查询性能相关修改；实施时必须保留这些修改并进行窄范围编辑。

### 2.2 本次包含

- 管理员角色进入独立后台。
- 课程查询、新增、修改、归档和恢复。
- 教学班查询、新增、修改、取消，以及满足条件时物理删除空白草稿教学班。
- 草稿排课方案中的教学安排新增、修改、删除、冲突检查和发布。
- 管理员搜索、添加和移除教学班学生。
- 调课申请查询、冲突检查、通过和驳回。
- 临时调课记录、调课通知以及学生课表的双位置展示。
- 成绩批次查询、统计、明细、通过、驳回和更正版本。
- 管理员写操作的幂等、乐观并发和审计记录。
- 管理员端无服务器预览、自动化测试和真实 MySQL/TCP/JavaFX 验证。

### 2.3 本次不包含

- 教师端调课申请表单和教师端成绩录入页面；本阶段只定义并实现管理员处理已提交申请所需的公共服务端契约。
- 培养方案维护。
- 选课窗口管理界面。
- 教师或学生账户管理。
- 自动排课算法。
- 对现有学生选课、候补算法和推送协议进行无关重构。

## 3. 已确认的业务决策

### 3.1 页面与模块边界

- 学生端继续使用现有 `CourseManagementView.fxml`。
- 管理员端新增 `AdminCourseManagementView.fxml`，一级导航只有“课程”和“审批”。
- 管理员协议使用独立的 `module="courseAdmin"`。
- 管理员使用独立的 `AdminCourseService`、公共管理员 DTO 和服务端 `AdminCourseHandler`。

### 3.2 删除语义

- 删除课程实际执行归档，历史教学班、成绩和审批记录继续保留。
- 删除已有业务关联的教学班实际执行取消。
- 只有未开放、无排课、无学生、无成绩、无申请和无审计依赖的空白教学班可以物理删除。
- 移除学生实际将 enrollment 更新为退课状态，不删除历史行。

### 3.3 强制操作边界

始终阻断：

- 时间段或周次范围非法。
- 对象不存在、课程已归档或教学班已取消。
- 同一教学班自身排课重叠。
- 已进入成绩提交或已经发布成绩的学生被直接移出教学班。
- 违反无法通过业务补偿修复的数据库唯一性和引用完整性。

允许管理员带原因绕过：

- 教师冲突。
- 助教冲突。
- 教室冲突。
- 教室容量小于教学班容量。
- 管理员添加学生时的容量、时间和先修要求警告。

`force=true` 时 `overrideReason` 必填，服务端记录管理员、冲突快照、原因、时间和最终结果。

### 3.4 排课与调课

- 常规排课采用 `DRAFT -> READY -> PUBLISHED`。
- 只有草稿方案允许编辑；发布前执行全量冲突检查。
- 选课窗口开放期间不得切换其绑定的排课方案。
- 已发布方案不可原地改写。
- 临时调课不进入排课草稿，而是针对已发布方案中的具体 occurrence 创建 adjustment。
- 一张调课申请可选择多个具体教学周，但所有目标周共享同一套新安排。
- 若不同周的新时间、教师、助教或教室不同，必须拆分申请。
- 多周申请只能整体通过或整体驳回。
- 审批通过后直接写入临时调整，并同步发布 `RESCHEDULED` 通知。
- 学生课表在原时间保留灰色“已调课”占位，并在新时间展示带“调入”标签的实际课程块。

### 3.5 成绩审批

- 教师按教学班和版本提交完整成绩批次。
- 提交后的批次及其明细冻结。
- 审批通过后立即更新当前成绩投影并向学生发布。
- 驳回后保留原提交快照；教师修改后创建新版本。
- 已发布成绩需要更正时，必须创建新的更正批次并重新审批。
- 成绩批次整体通过或整体驳回，不支持只审批部分学生。

## 4. 总体架构

```text
MainController
└── ClientSession.role
    ├── 学生 -> CourseManagementView -> CourseService -> module=course
    └── 管理员 -> AdminCourseManagementView
                  ├── AdminCourseCatalogController
                  ├── AdminApprovalController
                  └── Dialog Controllers
                          |
                    AdminCourseService
                          |
                  SocketAdminCourseService
                          |
                    SocketClient (复用现有单连接)
                          |
                 module=courseAdmin
                          |
                  AdminCourseHandler
                          |
       +------------------+-------------------+
       |                  |                   |
 Catalog/Offering   Schedule/Enrollment   Approval Services
       |                  |                   |
       +------------------+-------------------+
                          |
                     Focused DAOs
                          |
                         MySQL
```

### 4.1 客户端边界

- `AdminCourseManagementController` 只负责一级导航、页面刷新协调和退出时资源释放。
- 页面 Controller 负责读取控件、调用 Service、切回 JavaFX Application Thread、渲染结果和错误提示。
- Controller 不构造 TCP `Message`，不直接操作 DTO，不保存权威业务状态。
- `AdminCourseService` 使用客户端 ViewModel；`SocketAdminCourseService` 负责 ViewModel 与公共 DTO 的转换。
- `MockAdminCourseService` 为无服务器预览提供确定性数据，不参与正式运行。

### 4.2 服务端边界

- `AdminCourseHandler` 负责格式校验、认证、授权、DTO 解析、action 分发和响应封装。
- Handler 不写 SQL，不自行处理事务，不信任客户端传入的管理员 UID 或角色。
- Service 负责业务规则和事务边界。
- DAO 负责 SQL 和行映射，不决定管理员是否可以强制操作。
- 所有管理员身份均来自 `SessionManager.getSession(request.getToken())`。

## 5. 管理员客户端页面设计

### 5.1 入口

`MainController.openCourseSelection()` 根据 `ClientSession.getRole()` 路由：

- `学生`：进入现有 `/resources/fxml/CourseManagementView.fxml`。
- `管理员`：进入 `/resources/fxml/AdminCourseManagementView.fxml`。
- 其他角色：显示暂未开放提示，等待教师端单独设计。

主界面课程卡片需要 `fx:id`。管理员登录时卡片文字显示“教务管理”，学生登录时继续显示“选课”。

### 5.2 管理员页面外壳

`AdminCourseManagementView.fxml` 包含：

- 返回主菜单。
- “管理员后台”标题。
- “课程”和“审批”两个一级导航按钮。
- 通过 `fx:include` 引入的课程页和审批页。

页面切换使用 `visible + managed`；激活页面时调用其 `refresh()`。页面离开时取消本页面发起的监听或忽略已经过期的异步响应。

### 5.3 课程与教学班页面

`AdminCourseCatalogView.fxml` 提供固定骨架：

- 搜索框。
- 课程状态筛选。
- 刷新按钮。
- 新增课程按钮。
- 课程列表滚动区域。
- 加载、空结果和错误状态。

课程行显示：

- 课程编号。
- 课程名称。
- 学分。
- 课程类型。
- 非取消教学班数量。
- 详情、修改、添加教学班和删除操作。

“详情”在当前行下方展开教学班，再次点击收起。教学班行显示：

- 教学班编号。
- 学期。
- 默认主讲教师。
- 默认助教。
- 当前人数/容量。
- 排课状态。
- 排课、添加学生和更多操作。

“更多”使用 `MenuButton`，包含删除学生和删除教学班。新增与修改分别复用：

- `CourseEditorDialog.fxml`
- `OfferingEditorDialog.fxml`

课程编号创建后不可修改。教学班已有选课数据后，所属课程和学期不可修改；容量、默认教师、默认助教和开放状态按服务端规则决定是否可编辑。

### 5.4 排课窗口

`ScheduleArrangementDialog.fxml` 上方展示已有教学安排，下方为新增或修改编辑器。

一张教学安排卡片对应一个 `course_schedule_arrangement`，显示：

- 教师。
- 助教。
- 教室。
- 一个或多个时间段。
- 共享生效周数。
- 修改和删除。

时间段编辑器使用可重复的 `ScheduleSlotEditor`：

```text
星期 ComboBox | 开始节次 ComboBox | 结束节次 ComboBox | 删除
+ 添加时间段
```

第一版周次编辑采用起始周和结束周，形成连续周集合。非连续周需求通过多个教学安排表示；后续若出现真实的跳周需求，再引入多选周控件。

保存顺序：本地格式校验、服务端预检查、冲突展示、确认强制原因、正式保存。正式保存必须重新检查冲突，预检查结果不作为写入依据。

### 5.5 教学班学生窗口

`AddOfferingStudentDialog.fxml`：

- 按学号或姓名搜索。
- 展示候选学生。
- 选择学生后加载容量、时间和先修风险预览。
- 存在可绕过警告时，要求再次确认并填写原因。

`RemoveOfferingStudentDialog.fxml`：

- 展示教学班当前学生。
- 支持学号和姓名过滤。
- 每行提供删除按钮。
- 删除前二次确认。
- 不允许移除时显示明确原因并禁用按钮。

### 5.6 审批页面

`AdminApprovalView.fxml` 包含：

- “调课审批”和“成绩审批”Tab。
- “待审批、已通过、已驳回、全部”状态筛选。
- 默认选择“待审批”。
- 分页列表、刷新、加载、空结果和错误状态。

`AdjustmentApprovalDialog.fxml` 并列展示原安排和新安排，列出全部目标周、调课原因和冲突。管理员可以通过、带原因强制通过或驳回。

`GradeApprovalDialog.fxml` 展示平均分、最高分、最低分、不及格人数、成绩分布和学生成绩明细。管理员可以通过或填写原因驳回。

### 5.7 学生课表联动

扩展 `ScheduleEntryDTO` 和 `ScheduleEntryView`：

- `displayKind = NORMAL | ADJUSTED_ORIGINAL | ADJUSTED_TARGET`
- `adjustmentId`
- `originalScheduleText`
- `adjustedScheduleText`
- `adjustmentReason`

`ScheduleController` 的渲染规则：

- `NORMAL`：沿用现有课程块。
- `ADJUSTED_ORIGINAL`：在原时间显示灰色课程块，顶部标记“已调课”，该位置不再视为有效占用。
- `ADJUSTED_TARGET`：在新时间显示实际课程块，顶部标记“调入”。
- 点击原位置或新位置均展示原安排、新安排和原因。
- 右侧继续通过 `course_notice` 展示完整调课通知。

## 6. TCP 协议与公共 DTO

### 6.1 请求信封

```text
type   = MessageType.REQUEST
module = "courseAdmin"
action = AdminCourseActions 常量
token  = 当前 ClientSession token
```

客户端可以携带查询目标 ID，但不携带可信管理员身份。服务端从 token 取得管理员 UID。

### 6.2 Action

课程目录：

- `listCourses`
- `createCourse`
- `updateCourse`
- `archiveCourse`
- `restoreCourse`

教学班：

- `listOfferings`
- `createOffering`
- `updateOffering`
- `cancelOffering`
- `deleteDraftOffering`

排课：

- `listScheduleResources`
- `loadSchedulePlan`
- `loadOfferingArrangements`
- `checkArrangement`
- `saveArrangement`
- `deleteArrangement`
- `publishSchedulePlan`

教学班学生：

- `searchStudents`
- `listOfferingStudents`
- `previewAdminEnrollment`
- `addStudentToOffering`
- `removeStudentFromOffering`

审批：

- `listAdjustmentRequests`
- `getAdjustmentRequest`
- `reviewAdjustmentRequest`
- `listGradeSubmissions`
- `getGradeSubmission`
- `reviewGradeSubmission`

### 6.3 写请求公共字段

- `operationId`：UUID 字符串，防止 TCP 超时重试造成重复写入。
- `expectedVersion`：实体乐观锁版本。
- `force`：是否接受可绕过冲突。
- `overrideReason`：`force=true` 时非空。

预检查和正式写入是两个请求。若正式写入时状态已经变化，服务端返回最新冲突或 `CONFLICT`，客户端必须刷新。

### 6.4 DTO 分包

```text
dto.course.admin
├── AdminCourseActions
├── catalog
│   ├── AdminCourseDTO
│   ├── CourseEditorRequestDTO
│   ├── AdminOfferingDTO
│   └── OfferingEditorRequestDTO
├── schedule
│   ├── ScheduleArrangementDTO
│   ├── ScheduleSlotDTO
│   ├── ScheduleResourceDTO
│   ├── ScheduleConflictDTO
│   └── SaveArrangementRequestDTO
├── enrollment
│   ├── StudentSearchResultDTO
│   ├── OfferingStudentDTO
│   └── AdminEnrollmentPreviewDTO
├── approval
│   ├── AdjustmentRequestSummaryDTO
│   ├── AdjustmentRequestDetailDTO
│   ├── GradeSubmissionSummaryDTO
│   ├── GradeSubmissionDetailDTO
│   └── ApprovalDecisionRequestDTO
└── result
    └── AdminOperationResultDTO
```

约束：

- 数据库 `BIGINT` ID 以十进制字符串传输。
- 时间以 ISO-8601 UTC 字符串传输。
- DTO 使用明确枚举和不可变字段，不传数据库实体。
- 列表查询包含 `pageNumber`、`pageSize` 和服务端返回的 `totalCount`。
- `ScheduleConflictDTO` 包含 type、severity、冲突对象、相关教学班、周次、时间段和用户提示。
- `severity` 只有 `BLOCKING` 和 `OVERRIDABLE`。
- `AdminOperationResultDTO` 返回结果码、提示、最新实体和冲突列表。

传输层沿用现有 `MessageCode`：

- `BAD_REQUEST`
- `UNAUTHORIZED`
- `FORBIDDEN`
- `NOT_FOUND`
- `CONFLICT`
- `ERROR`

不为每个业务结果新增重复的网络状态码。

## 7. 数据库增量设计

新增 `VCampusServer/src/resources/migrations/V004_admin_course_management.sql`。V004 只扩展 V001-V003。

### 7.1 课程和教学班

`course` 新增：

- `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'`
- `version INT NOT NULL DEFAULT 1`
- `archived_by VARCHAR(32) NULL`
- `archived_at DATETIME(6) NULL`

状态约束：`ACTIVE | ARCHIVED`。归档与恢复使用 version 条件更新。

`course_offering` 新增：

- `version INT NOT NULL DEFAULT 1`
- `created_by VARCHAR(32) NULL`
- `cancelled_by VARCHAR(32) NULL`
- `cancelled_at DATETIME(6) NULL`

沿用现有数值状态：1 未开放、2 开放、3 停止、4 取消。

现有 `chk_course_offering_enrolled_count` 改为只要求 `enrolled_count >= 0`。普通学生选课继续在事务中阻止超容量；管理员带原因添加可以使人数大于容量。

学生端课程查询必须过滤 `course.status='ACTIVE'` 和非取消教学班，避免归档对象继续显示。

### 7.2 排课方案审计

`schedule_plan` 新增：

- `created_by VARCHAR(32) NULL`
- `published_by VARCHAR(32) NULL`
- `published_at DATETIME(6) NULL`

现有 status 继续限定为 `DRAFT | READY | PUBLISHED`。正式学生课表使用教学日历当前发布方案；学生选课冲突继续使用 `course_selection_window.schedule_plan_id`。选课窗口开放期间两者不得切换到不同方案。

V004 为 `teaching_calendar` 增加可空的 `current_schedule_plan_id` 外键，作为学生课表的当前正式方案指针。发布事务更新该指针；旧方案仍保持不可变历史。若选课窗口处于开放状态且绑定其他方案，发布事务拒绝切换。

### 7.3 教学安排聚合

新增 `course_schedule_arrangement`：

- `arrangement_id BIGINT`
- `plan_id BIGINT`
- `offering_id BIGINT`
- `teacher_uid VARCHAR(32)`
- `assistant_uid VARCHAR(32) NULL`
- `classroom_id BIGINT`
- `status VARCHAR(16)`：`ACTIVE | DISABLED`
- `version INT`
- `created_by VARCHAR(32)`
- `created_at DATETIME(6)`
- `updated_at DATETIME(6)`

给 `course_schedule_rule` 增加 `arrangement_id` 外键。一条 arrangement 可以拥有多条 rule，每条 rule 表示一个星期和节次范围；同一 arrangement 下所有 rule 的周次集合必须相同。

现有数据迁移时，每条旧 rule 创建一个独立 arrangement，再回填 `arrangement_id`，最后改为非空。现有 occurrence 和 resource booking 不删除。

### 7.4 临时调课

新增 `course_schedule_adjustment_request`：

- 申请 ID、教学班 ID、申请教师 UID。
- 原因、版本和状态。
- 申请的新星期、开始节次、结束节次、新教师、新助教和新教室。
- 提交时间、审批人、审批时间和审批意见。
- 状态：`PENDING | APPROVED | REJECTED`。

一张申请头只保存一套新安排。多个目标周通过 target 表关联各自的原 occurrence；审批通过后，服务端根据教学日历把申请头中的星期和节次展开为每周的 UTC 起止时间。

新增 `course_schedule_adjustment_target`：

- request ID。
- original occurrence ID。
- 原周次、原 UTC 起止时间、原教师、原助教和原教室快照。
- `(request_id, original_occurrence_id)` 唯一。

新增 `course_schedule_adjustment`：

- adjustment ID。
- request ID 和 original occurrence ID。
- 调整后的 UTC 起止时间。
- 新教师、助教和教室。
- `status = ACTIVE | CANCELLED`。
- 审批创建时间。

同一 original occurrence 同时最多存在一个 ACTIVE adjustment。

`course_notice` 增加可空的 `adjustment_request_id` 外键。一张多周申请创建一条汇总通知。

### 7.5 成绩审批

新增 `grade_submission`：

- submission ID、offering ID、version。
- submitted_by、submitted_at。
- status：`PENDING | APPROVED | REJECTED`。
- reviewed_by、reviewed_at、review_comment。
- 平均分、最高分、最低分、不及格人数和总人数快照。
- `(offering_id, version)` 唯一。

新增 `grade_submission_item`：

- submission ID、enrollment ID。
- 平时、期中、实验、期末、总评、等级和绩点快照。
- `(submission_id, enrollment_id)` 唯一。

提交后的 header 和 item 不允许更新成绩内容。审批通过时，将 item 投影到现有 `grade` 表，设置 `is_published=1` 和 `publish_time`。现有 `grade` 是学生当前查询投影，submission item 是历史事实。

### 7.6 管理员审计与幂等

新增 `admin_course_operation_log`：

- `admin_uid VARCHAR(32)`
- `operation_id CHAR(36)`
- `action VARCHAR(64)`
- `target_type VARCHAR(32)`
- `target_id VARCHAR(64)`
- `request_digest CHAR(64)`
- `request_json JSON`
- `conflict_snapshot_json JSON NULL`
- `forced TINYINT(1)`
- `override_reason VARCHAR(500) NULL`
- `result_code VARCHAR(48)`
- `response_json JSON`
- `created_at DATETIME(6)`
- `completed_at DATETIME(6)`

主键为 `(admin_uid, operation_id)`。相同 operationId 和相同请求摘要返回已保存响应；摘要不同则返回 `CONFLICT`。

## 8. 服务端组件

### 8.1 `AdminCourseCatalogService`

- 课程列表、创建、更新、归档和恢复。
- 校验课程编号唯一且创建后不可修改。
- 归档前检查活动教学班。
- 使用 expectedVersion 防止丢失更新。

### 8.2 `AdminOfferingService`

- 教学班列表、创建、更新、取消和草稿物理删除。
- 教学班默认教师与助教保存在 `course_offering_teacher`。
- 新教学安排默认复制教学班人员，但允许在安排级覆盖。
- 有关联数据时拒绝物理删除。

### 8.3 `ScheduleManagementService`

`saveArrangement()` 的单事务顺序：

```text
读取幂等记录
-> 锁定 DRAFT 方案和目标 arrangement
-> 校验 expectedVersion
-> 校验结构和对象状态
-> 写 arrangement、rules 和共享 weeks
-> 生成逐周 occurrences
-> 写教师、助教和教室 bookings
-> 重新执行冲突检查
-> 按 force 决定回滚或保存
-> 写审计/幂等结果
-> 提交
```

`publishSchedulePlan()` 锁定方案和教学日历，重新检查所有安排，更新方案审计字段及 `teaching_calendar.current_schedule_plan_id`。阻断冲突导致回滚；可绕过冲突要求强制原因。选课窗口开放且绑定其他方案时拒绝切换。

### 8.4 `CourseConflictService`

集中检查：

- 教师时间冲突。
- 助教时间冲突。
- 教室时间冲突。
- 同一教学班自身时间冲突。
- 教室容量小于教学班容量。
- 管理员添加学生时的容量、时间和先修警告。

排课与调课冲突必须基于有效课表：被 adjustment 替代的原 occurrence 不再占用资源，ACTIVE adjustment 的新时间和资源参与检查。

### 8.5 `AdminEnrollmentService`

`previewAdminEnrollment()` 返回风险，不写数据库。

`addStudentToOffering()`：

```text
读取幂等记录
-> 锁定 offering 和学生现有 enrollment
-> 校验结构性规则
-> 重新计算警告
-> 校验 force 和原因
-> 新建或恢复 enrollment
-> 原子更新 enrolled_count
-> 写审计/幂等结果
-> 提交
```

重复加入同一教学班返回幂等成功，不重复增加人数。数据库唯一约束禁止同一学生同时选中同一课程的两个教学班，此规则不允许强制绕过。

`removeStudentFromOffering()` 将 enrollment 改为退课状态并减少人数。存在 PENDING/APPROVED 成绩批次或已发布成绩时拒绝移除。事务提交后调用现有候补推进逻辑处理新空位。

### 8.6 `ScheduleAdjustmentApprovalService`

```text
读取幂等记录
-> 锁定 PENDING 申请及全部 targets
-> 验证 original occurrences
-> 按有效课表重新检查每个目标周
-> 驳回：记录原因
-> 通过：逐周创建 ACTIVE adjustments
-> 创建并发布一条 RESCHEDULED notice
-> 写审计/幂等结果
-> 提交
```

任一目标周出现阻断错误则整个申请不能通过。审批过程中不修改 schedule plan、rule 或 original occurrence。

### 8.7 `GradeApprovalService`

```text
读取幂等记录
-> 锁定 PENDING submission 和 items
-> 校验学生集合及成绩范围
-> 驳回：记录原因
-> 通过：投影到 grade 并发布
-> 写审计/幂等结果
-> 提交
```

同一批次只能发生一次最终审批。两个管理员并发审批时只有第一个状态转换成功，第二个收到 `CONFLICT` 和最新状态。

## 9. 客户端 Service 与异步规则

新增：

- `AdminCourseService`
- `AdminCourseServices`
- `AdminCourseTransport`
- `SocketAdminCourseService`
- `SocketAdminCourseTransport`
- `MockAdminCourseService`

底层复用 `SocketClient`，不建立第二条 TCP 连接。`SocketAdminCourseTransport` 只封装 request/response 发送；暂不需要新的推送通道。

Controller 规则：

- 网络 Future 完成后通过 `Platform.runLater` 或现有 FX 工具执行短 UI 更新。
- 每个列表加载维护 generation，忽略已经过期的响应。
- 写操作期间禁用相关控件，完成后恢复。
- 写操作成功后重新加载服务端快照，不手工猜测本地最终状态。
- 版本冲突提示“数据已被其他管理员修改”，刷新后保留可安全保留的筛选条件。
- 页面关闭后到达的响应不再更新已卸载控件。

## 10. 错误与空状态

- 搜索无结果时展示明确空状态。
- 网络错误保留当前已显示数据并提供重试。
- 参数错误定位到具体字段。
- 阻断冲突只提供返回修改。
- 可绕过冲突提供“返回修改”和“填写原因并继续”。
- 强制原因去除首尾空白后不能为空。
- 归档、取消、物理删除、移除学生、发布和审批均二次确认。
- 服务端日志保留异常和 SQL 上下文，客户端响应不泄露 SQL、数据库地址、凭据或堆栈。

## 11. 测试与验证

### 11.1 公共层

- 每个请求和响应 DTO 的 Gson JSON 往返测试。
- BIGINT 十进制字符串、nullable 字段、UTC 时间和枚举非法值测试。
- 学生端现有 DTO 回归测试。

### 11.2 客户端

- `SocketAdminCourseService` 使用假 Transport 验证 action、token、字段映射和错误传播。
- Controller 测试覆盖加载、空状态、过期响应、按钮禁用、确认取消和版本冲突。
- `MockAdminCourseService` 覆盖核心状态转换和冲突展示。
- 所有新增 FXML 做 XML 解析与 FXMLLoader smoke test。
- 无服务器预览生成课程、排课、学生、调课审批和成绩审批截图并人工检查 860 x 580 布局。
- 学生课表验证 NORMAL、ADJUSTED_ORIGINAL 和 ADJUSTED_TARGET 的布局与详情。

### 11.3 服务端

- Handler 权限矩阵：无 token、失效 token、学生、教师、管理员。
- DAO 行映射与分页边界测试。
- 课程和教学班乐观锁测试。
- 排课多时间段、共享周次、课次生成和资源预订测试。
- 五类排课冲突及强制原因测试。
- 管理员添加学生的超容量、时间、先修、重复请求和计数测试。
- 移除学生、成绩阻断和候补推进测试。
- 调课多周整体审批、原课次不变、adjustment 和 notice 原子性测试。
- 成绩通过、驳回、更正、并发审批和发布投影测试。
- 每个写事务注入中途失败，验证无半成品提交。

### 11.4 数据库与端到端

- V004 静态结构契约测试。
- 在目标 MySQL 版本真实执行 V001 -> V004。
- 验证 V004 对现有种子数据的回填和重复执行策略。
- 运行真实 MySQL Service/DAO 集成测试。
- 运行真实 TCP 管理员端到端测试。
- 运行完整学生端课程、选课、候补、推送、课表和成绩回归测试。
- 运行生产源码编译、FXML smoke、JavaFX 无服务器预览和 `git diff --check`。

编译、FXML 加载、静态 SQL 检查、真实 MySQL、TCP 和 JavaFX 可视验证必须分别报告，不能互相替代。

## 12. 实施阶段

### 阶段 1：管理员基础、课程和教学班

- 管理员入口和页面外壳。
- `courseAdmin` 公共协议、客户端 Service 和服务端 Handler 基础。
- V004 迁移和管理员审计基础。
- 课程与教学班查询和写操作。
- 无服务器管理员课程页预览。

### 阶段 2：排课管理

- 教学安排聚合。
- 多时间段与共享周数编辑。
- 冲突检查和强制保存。
- 草稿方案发布。

### 阶段 3：管理员维护教学班学生

- 学生搜索和风险预览。
- 强制添加、移除和审计。
- 人数计数和候补联动。

### 阶段 4：调课审批与学生课表联动

- 调课审批列表和详情。
- 多周 adjustment 和调课通知。
- 学生课表双位置展示。

### 阶段 5：成绩审批

- 成绩批次统计和明细。
- 通过、驳回、更正版本和学生端发布。

每个阶段必须形成可独立运行和测试的纵向闭环；不得同时打开五个阶段后再集中补测试。

## 13. 完成标准

- 管理员从主界面进入独立后台，学生入口及行为不变。
- 非管理员无法通过构造请求调用任何 `courseAdmin` 写 action。
- 课程和教学班的创建、编辑、归档、恢复、取消和受限物理删除符合状态规则。
- 一条教学安排可以包含多个时间段并共享教师、助教、教室和周数。
- 排课保存和发布的冲突分类、强制原因和审计完整。
- 管理员添加学生可以越过允许的业务警告，但不能破坏结构约束。
- 调课审批不改动已发布基础排课，并原子地产生逐周调整及学生通知。
- 学生课表同时正确展示原位置“已调课”和新位置“调入”。
- 成绩只有在审批通过后对学生可见，历史版本可追溯。
- 重复请求、并发管理员和事务中途失败不会产生重复记录、丢失更新或部分提交。
- 新增测试通过，现有学生课程系统回归通过，并明确报告尚未具备的运行环境验证。
