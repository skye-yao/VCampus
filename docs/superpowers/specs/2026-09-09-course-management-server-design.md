# 选课管理系统客户端与服务端接入设计

## 1. 目标与范围

在 `feature/course-management-client` 分支中，将现有 JavaFX 选课界面改造成“课程 -> 教学班”两级结构，并通过现有 TCP/JSON Socket 接入 MySQL 服务端。系统当前只实现先到先得，不实现抽签。

本阶段交付学生端以下完整链路：

- 按学期查看可选课程，展开课程后按需加载教学班。
- 将同一课程的多个教学班分别加入或移出选课计划。
- 对单个教学班发起选择、加入或取消候补、处理候补席位、退选。
- 查看课表、已发布成绩、培养方案和课程通知。
- 服务端主动推送候补到位、自动选中和候补席位失效事件。
- 断线重连或请求超时后，从服务端重新加载权威状态。

教师录入成绩、管理员排课和培养方案维护、抽签选课不在本阶段范围内。`feature/courses` 只作为数据库参考，不切换到该分支写入，也不整体 cherry-pick 其提交。

## 2. 总体架构

请求链路：

```text
JavaFX Controller
  -> CourseService
  -> SocketCourseService
  -> SocketClient
  -> Message(type=REQUEST, module="course")
  -> CourseHandler
  -> CourseApplicationService
  -> Course DAO
  -> MySQL
```

推送链路：

```text
事务写入 course_event_outbox
  -> CourseEventDispatcher
  -> OnlineConnectionRegistry
  -> ClientConnection 的共享发送锁
  -> Message(type=PUSH, module="course")
  -> 客户端 MessageDispatcher
  -> CoursePushCoordinator
  -> 重新加载服务端权威状态
```

各层职责：

- `VCampusCommon` 定义 action、状态枚举和传输 DTO。数据库 `BIGINT` ID 在线路上统一使用十进制字符串。
- `VCampusClient` 使用独立展示模型。Controller 只负责界面状态和交互，不直接解释数据库记录。
- `CourseHandler` 负责 action 分派、参数解析、会话和学生角色校验、响应封装。
- `CourseApplicationService` 负责选课窗口、资格、容量、同课和时间冲突、事务及状态转换。
- DAO 只负责 SQL 和结果映射。同一事务内的 DAO 方法共享调用方持有的 JDBC `Connection`。
- `MockCourseService` 保留给无服务端预览和客户端测试；正常登录流程使用 `SocketCourseService`。
- Socket 推送只用于及时通知，MySQL 中的选课状态始终是最终事实来源。

## 3. 客户端信息结构

### 3.1 “全部”标签

“全部”首先显示课程大条目，而不是教学班大条目。课程条目显示课程代码、课程名称、类型、学分、学时、简介和先修要求；点击详情后才加载并展开该课程在当前学期的教学班。

教学班小条目显示教师、时间、地点、容量和当前学生状态。按钮规则：

- `AVAILABLE`：`加入计划`。
- `PLANNED` 或 `FULL`：`移除计划`。
- `WAITLISTED`：禁用的 `候补中`。
- `WAITLIST_OFFERED`：禁用的 `待处理`。
- `ENROLLED`：禁用的 `已选`。

同一课程的多个教学班可以同时加入计划。课程展开状态、搜索词和课程类型筛选属于客户端显示状态，不作为服务端资格判断依据。

### 3.2 “计划”“候补”“已选”标签

这三个标签都以教学班作为大条目，并辅助显示课程名称和课程代码：

- “计划”包含 `PLANNED`、`FULL`、`WAITLISTED`、`WAITLIST_OFFERED`。成功选中的教学班自动移出；同课程的其他计划或候补项不受影响。
- “候补”只包含 `WAITLISTED` 和 `WAITLIST_OFFERED`。
- “已选”只包含 `ENROLLED`。

`PLANNED` 行左侧允许移出计划，右侧按钮为 `选择`。`FULL` 行可移出计划，右侧按钮为 `候补`。`WAITLISTED` 行不能移出计划，右侧按钮为 `取消候补`。`WAITLIST_OFFERED` 行不能移出计划，右侧按钮为 `处理`。`ENROLLED` 行右侧按钮为 `退选`。

### 3.3 客户端操作保护

- 同一 `offeringId` 的所有标签页共享一个进行中标记。请求完成前，相关按钮全部禁用。
- 每次用户点击产生新的 UUID `operationId`；因界面重复点击不得产生第二次请求。
- 请求超时后不直接假定成功或失败，而是重新加载当前学期的权威状态，完成后才恢复按钮。
- Push 回调只安排短小的 JavaFX 更新或刷新任务，不在 Socket 接收线程执行耗时查询或直接修改控件。

## 4. 公共 DTO 与协议

### 4.1 两级 DTO

- `CourseDTO`：`courseId`、代码、名称、类型、学分、学时、简介、先修要求等课程固有信息。
- `CourseOfferingDTO`：`offeringId`、`courseId`、教师列表、上课时段列表、容量、当前人数、`SelectionStateDTO`，以及可空的 `offeredAt`、`expiresAt`。
- `CourseTeacherDTO`：教师 UID 和显示姓名。
- `CourseMeetingDTO`：星期、开始/结束节次、单双周或周次范围、教室和 UTC 时间边界；界面需要的“周二 3-4 节”等文字由客户端格式化。
- `CoursePlanSnapshotDTO`：当前学期计划、候补和已选教学班的权威快照；其中 `WAITLIST_OFFERED` 项必须包含 `offeredAt` 和 `expiresAt`，使漏掉 push 后仍能恢复倒计时。
- `CourseMutationResultDTO`：`operationId`、教学班、最终状态、结果码和稳定的用户提示。
- `CoursePushEventDTO`：事件 ID、事件类型、学期、教学班 ID、发生时间和可选的处理截止时间。

课程与教学班 ID 均为 MySQL `BIGINT`，在 JSON 中使用十进制字符串。学生和教师 UID 保持 `VARCHAR(32)`。时间戳统一按 UTC 传输为 ISO-8601 字符串，客户端再转换为本地显示时间。

### 4.2 选课状态

```text
AVAILABLE
PLANNED
FULL
WAITLISTED
WAITLIST_OFFERED
ENROLLED
```

`FULL` 表示计划项仍存在，但最近一次选择确认教学班已满；它不是教学班永久满员标志。重新选择或加入候补时，服务端必须再次检查实时容量。

### 4.3 请求 action

查询：

- `listTerms`
- `listCourses`
- `listCourseOfferings`
- `loadSelectionSnapshot`
- `loadSchedule`
- `loadNotices`
- `loadGrades`
- `loadTrainingPlan`

变更：

- `addToPlan`
- `removeFromPlan`
- `selectOffering`
- `joinWaitlist`
- `cancelWaitlist`
- `resolveWaitlistOffer`
- `dropOffering`
- `ackCourseEvent`

所有选课业务变更请求必须包含 `operationId`。`ackCourseEvent` 只确认事件投递，本身按 `eventId + uid` 天然幂等，不要求 operation ID。`resolveWaitlistOffer` 的决定只能是 `ACCEPT` 或 `ABANDON`。不再提供 `confirmPlan` 批量提交，也不保留抽签相关 action 或结果。

请求只携带学期、课程 ID、教学班 ID、operation ID 和决定等业务参数，不接受客户端指定学生 UID。由于 `Message.data` 是 `Map<String, Object>`，两端必须通过 Gson 目标类型或 `TypeToken` 转换，禁止直接将 `LinkedTreeMap` 强制转换为 DTO。

## 5. 数据库存储

### 5.1 迁移归属

当前分支维护一套有确定顺序的迁移：

- `V001`：课程、专业/年级适用关系、教学班、任课教师、正式选课和成绩核心表。
- `V002`：教学日历、排课规则、课程实例、教室、资源预约和教学班冲突表。
- `V003`：学生学籍、选课窗口、计划、候补、培养方案、通知、操作幂等和事件 outbox。

参考分支中重复建立核心表和排课表的部分不会原样复制。`tbl_user.UID` 在当前项目中为 `VARCHAR(32)`，所有用户外键必须保持同类型，不采用参考分支不兼容的 `BIGINT UID`。

### 5.2 选课相关表

`course_plan_item` 保存购物车条目：

- 业务键：`uid + offering_id`。
- 状态：`PLANNED` 或 `FULL`。
- 保存创建时间、更新时间和最近失败原因。
- 加入候补后条目仍保留；正式选中后只删除当前教学班的计划项。

界面状态由三张表按固定优先级投影：有效 enrollment 为 `ENROLLED`；否则有效 `OFFERED` 为 `WAITLIST_OFFERED`；否则有效 `WAITING` 为 `WAITLISTED`；否则计划项按自身状态显示 `PLANNED` 或 `FULL`；均不存在时为 `AVAILABLE`。DAO 查询统一生成该投影，客户端不自行猜测组合状态。

`enrollment` 只保存真实选中和历史退课记录，不再用 `enrollment.status` 表示计划：

- 同一学生同一教学班最多一个当前记录。
- 同一学生在同一学期、同一课程最多一个有效 `ENROLLED` 教学班；不同学期允许重修或再次选择。
- 沿用状态值 `2 = ENROLLED`、`3 = DROPPED`，不再创建状态 `1 = PLANNED` 或 `4 = NOT_SELECTED` 的新记录。
- 表中保存与 teaching class 一致的 `course_id`、`academic_year`、`semester`，并通过复合外键保证它们与 `offering_id` 配对正确。
- 使用仅在 `status = 2` 时等于 `course_id`、其他状态为 `NULL` 的生成列，并对 `uid + academic_year + semester + active_course_id` 建唯一索引，从数据库层阻止同一学期同时选中同一课程的两个教学班。
- 成绩仍通过 enrollment 关联，退课历史保留以支持审计。

`course_waitlist` 保存候补生命周期：

- 状态：`WAITING`、`OFFERED`、`CANCELLED`、`EXPIRED`、`ENROLLED`。
- 保存入队时间、到位时间、到期时间和更新时间。
- FIFO 顺序使用 `queue_time, waitlist_id`，取消后重新加入必须排到队尾。
- 同一学生同一教学班只能有一个活动的 `WAITING` 或 `OFFERED` 记录。

`course_operation_log` 保存变更幂等结果：

- 幂等作用域是已认证学生，主键为 `uid + operation_id`，并保存 action、教学班、规范化请求摘要、结果码和响应 JSON。不同学生偶然生成相同 UUID 互不影响。
- 请求摘要只由 action、学期、课程/教学班 ID 和决定等规范化业务字段生成，不包含传输层 `Message.UID`。
- 同一学生的 operation ID 与不同请求内容组合使用时返回参数冲突。
- 成功和稳定的业务失败与业务事务一起提交；数据库异常导致整体回滚，允许客户端刷新后重新发起新 operation。

`course_event_outbox` 保存待推送事件：

- 保存事件 ID、学生 UID、事件类型、payload、创建时间、最近发送时间、尝试次数和客户端确认时间。
- 业务状态与事件在同一事务写入。
- 采用“至少一次、账号级确认”：学生在线时向其所有当前连接发送，任一连接成功提交 `ackCourseEvent` 后设置 `acked_at`；未确认事件会重发，客户端按 `eventId` 去重。
- 离线事件在连接重新绑定后补发；已确认事件不要求在同一账号的其他设备再次消费，因为所有设备都会重新加载权威快照。
- 推送即使已发送也不能代替状态查询，客户端重连和超时后仍加载权威快照。

### 5.3 其他补充表

- `major`、`student_academic_profile`：专业及学生专业/入学年份，也是同一学生不同 operation 串行化时锁定的行。
- `course_selection_window`：计划、正式选择和退选的开放时间。
- `training_plan`、`training_plan_group`、`training_plan_course`：按专业和入学年份匹配培养方案。
- `course_notice`：教学班课程通知。

专业、年级、课程和培养方案使用关系表，不使用 JSON 字段。数据库连接和服务器 JVM 均使用 UTC；`created_time`/`updated_time` 用于审计、FIFO 排序、超时和增量事件处理。

`course_selection_window` 必须引用该学期唯一采用的 `schedule_plan_id`。打开选课窗口前，该排课方案必须为 `PUBLISHED`，其课程实例和 `course_offering_conflict` 结果在窗口期间不可修改。冲突表缺失、版本不匹配或方案未发布时，服务端按失败关闭原则拒绝选择、候补晋升和接受到位席位，不能当作“无冲突”。

## 6. 业务状态转换

### 6.1 加入和移出计划

- `AVAILABLE -> PLANNED`：创建或恢复 `course_plan_item`。
- `PLANNED/FULL -> AVAILABLE`：删除计划项。
- `WAITLISTED`、`WAITLIST_OFFERED` 不允许直接移出计划，必须先取消或放弃候补。
- `ENROLLED` 不属于计划，不能通过移出计划退课。

### 6.2 选择教学班

`selectOffering` 只处理一个 `PLANNED` 教学班：

1. 检查选课窗口、课程资格和教学班开放状态。
2. 检查是否已选同一课程的其他教学班。
3. 检查是否与已选教学班时间冲突。
4. 有容量时创建或恢复正式 enrollment、增加人数并删除当前计划项，结果为 `ENROLLED`。
5. 满员时不自动候补，保留计划项并改为 `FULL`。
6. 课程或时间冲突时保持 `PLANNED`，返回明确原因。

同课程的其他计划和候补项不自动删除。

如果该教学班存在有效 `WAITING` 队列，即使计数暂时显示有空位，普通 `selectOffering` 也不得插队；当前计划项转为或保持 `FULL`，空位只由候补推进器分配。

### 6.3 加入和取消候补

`joinWaitlist` 必须重新检查容量：

- 不存在其他有效 `WAITING` 且有空位、无同课/时间冲突：直接选中，删除当前计划项。
- 不存在其他有效 `WAITING` 且有空位、但存在冲突：创建五分钟 `OFFERED` 席位，结果为 `WAITLIST_OFFERED`。
- 已有有效 `WAITING` 或仍然满员：创建队尾 `WAITING` 记录，结果为 `WAITLISTED`。

`cancelWaitlist` 将 `WAITING` 记录改为 `CANCELLED`，计划项恢复为 `FULL`。学生随后可以移出计划，或以新的排队时间重新加入队尾。

### 6.4 候补晋升与五分钟处理

教学班出现空位后，候补推进器按 FIFO 读取队首候选人并重新校验资格：

- 无同课和时间冲突：自动转为 `ENROLLED`，删除计划项并写入自动选中事件。
- 存在冲突：为该学生保留一个席位，状态转为 `OFFERED`，写入到位事件。
- 已失去资格或状态已变化：结束该条候补记录并继续检查下一人。

五分钟从服务端写入 `offered_at` 时开始，即使学生离线也不暂停。`expires_at = offered_at + 5 minutes`，服务端调度器负责超时处理。

`resolveWaitlistOffer(ACCEPT)` 在教学班锁内再次验证 `status = OFFERED` 且服务端当前 UTC 时间早于 `expires_at`，然后在一个事务中退掉所有发生同课或时间冲突的已选教学班，再将到位教学班转为 `ENROLLED`。`ABANDON` 或超时将候补记录改为 `CANCELLED`/`EXPIRED`，释放保留席位，计划项恢复 `FULL`，学生可以重新排到队尾；提交后立即触发同一教学班的下一次候补推进。

`dropOffering` 只允许在退选截止时间前处理 `ENROLLED`：事务内改为 `DROPPED`、减少人数，不自动恢复计划项；提交后若正式选课窗口仍开放则推进该教学班候补，否则不产生新的到位或自动选中。通过接受候补席位而退掉的冲突教学班遵循相同计数规则，并在事务提交后分别触发候补推进。

正式选课窗口关闭后不再创建新的 `WAITING`、`OFFERED` 或自动 enrollment，并将尚在等待的队列项结束为 `EXPIRED`；关闭前已经产生的 `OFFERED` 仍可在自身 `expires_at` 前接受或放弃。服务启动和调度器恢复时先扫描所有已过期 `OFFERED`，按超时流程释放席位；若窗口仍开放，继续推进队列。

任何时刻都必须满足：

```text
enrolled_count + active WAITLIST_OFFERED reservations <= capacity
```

正式 enrollment 的人数计数只包含 `ENROLLED`；容量判断额外统计尚未到期的 `OFFERED` 记录。

## 7. 并发、锁与幂等

所有变更使用单个 JDBC 事务。已认证 UID 和 operation ID 先用于幂等快速查询；未找到结果时按以下业务锁顺序执行：

1. 锁定 `student_academic_profile` 行，使同一学生使用不同 operation ID 的请求串行。
2. 在学生锁内再次查询 `uid + operation_id`，避免两个相同请求都通过快速查询；若已存在则验证摘要并返回保存结果。
3. 锁定所有受影响的 `course_offering` 行，多个 ID 时按升序锁定。
4. 锁定或修改 enrollment、plan 和 waitlist 行。
5. 写入 operation log 和 outbox 后提交。

容量相关变更必须使用 `SELECT ... FOR UPDATE`。唯一约束防止重复 enrollment、活动候补和 operation ID；锁与状态条件更新防止超卖和人数漂移。

候补推进器先无锁读取可能的队首 UID，再按上述顺序进入事务并复核该记录仍是有效队首；若已变化则重试。接受候补席位时释放的其他教学班，在当前事务提交后分别触发新的候补推进任务，避免跨学生反向加锁。

重复 operation ID 返回已保存的首次业务结果，不重复变更数据；外层响应的 `Message.UID` 始终使用本次传输请求 UID，不能复用第一次请求 UID。不同 operation ID 的重复点击即使绕过客户端禁用，也会被学生行锁和当前状态校验安全处理。

## 8. 服务端主动推送

`OnlineConnectionRegistry` 支持一个 UID 对应一个或多个 `ClientConnection`。连接在服务端通过有效 token 解析出 UID 后绑定；退出登录、token 失效或 Socket 断开时解绑。

`ClientConnection` 封装 Socket 和唯一发送锁。普通响应与主动推送必须调用同一个发送方法，防止多线程 JSON 行交叉。注册表不直接持有裸 `PrintWriter`。

课程事件类型至少包括：

- `WAITLIST_OFFERED`
- `WAITLIST_AUTO_ENROLLED`
- `WAITLIST_OFFER_EXPIRED`
- `WAITLIST_OFFER_ABANDONED`

客户端 `MessageDispatcher` 按 module/action 注册 push listener。`CoursePushCoordinator` 按 `eventId` 去重，收到事件后先提交 ACK，再显示必要提示并刷新当前学期权威快照；到位事件使用服务端 `expiresAt` 展示剩余时间，不从客户端收包时重新计时。即使 ACK 或刷新暂时失败，重复事件也只能触发幂等刷新，不能重复执行业务变更。

Push 发送失败不回滚已经提交的选课事务。outbox 调度器会保留未成功发送的事件，连接重新建立后补发；客户端重连时无条件刷新一次快照，处理“服务端已提交但 push 丢失”的情况。

## 9. 查询、权限与错误

- 服务端按学期、教学班开放状态、学生专业和入学年份决定可见课程；跨专业权限由课程关系决定。
- 前端只做关键词、课程类型和本地标签筛选，不能决定最终资格。
- `loadSchedule` 只返回当前学生已选课程和选课窗口引用的已发布排课方案。
- `loadNotices` 只返回已选教学班的已发布通知。
- `loadGrades` 只返回已发布成绩；数据库 `NULL` 分项在客户端显示 `--`。
- `loadTrainingPlan` 按学生专业和入学年份选择已发布版本，已修学分从已发布且通过的成绩计算。

`CourseHandler` 必须使用 `request.token` 查询 `SessionManager`，从会话取得真实 UID，并校验学生角色。客户端的 `sender`、`data.uid` 或其他身份字段不可信。

错误码约定：参数错误为 `BAD_REQUEST`，无会话为 `UNAUTHORIZED`，角色不符为 `FORBIDDEN`，资源不存在为 `NOT_FOUND`，窗口/资格/冲突/状态不允许为 `CONFLICT`，数据库和未预期异常为 `ERROR`。响应不暴露 SQL、连接信息或堆栈。

## 10. 测试与验收

自动化验证至少覆盖：

- `CourseDTO` 与 `CourseOfferingDTO` 分离，超过 `2^53` 的 ID JSON 往返无精度损失。
- “全部”按课程分组和懒加载教学班；其他三个标签按教学班显示正确状态和操作。
- 同一教学班跨标签按钮联动禁用，请求超时后刷新权威状态。
- 同课程多个教学班可同时计划，选中一个后其他计划/候补保持不变。
- 满员只转 `FULL`，不会自动候补；取消候补回到 `FULL` 并可重新排队。
- 已有候补队列时普通选择不能插队，空位按 `queue_time, waitlist_id` 分配。
- 同课冲突、时间冲突、容量锁、FIFO、五分钟到期和接受时原子退课。
- 同一课程跨学期可重修，同一学期不能同时选中两个教学班。
- 并发下容量不变量成立，重复 operation ID 返回相同业务结果，不同 operation ID 不造成重复记录或计数漂移。
- token、角色和参数错误映射正确，客户端 UID 不能冒充其他学生。
- 响应和 push 共用发送锁，多连接注册/解绑无泄漏，事件至少一次投递且可 ACK/去重，离线后重连能恢复状态和候补截止时间。
- 服务重启可处理过期 `OFFERED`；选课窗口关闭后不再晋升新候补，但关闭前的有效席位可在截止前处理。
- 多教师、多时段、单双周和多教室信息通过结构化 DTO 往返，不依赖拼接文本。
- 成绩、培养方案、课表和通知查询规则正确。
- 无服务端 `CourseUiPreview` 可运行，所有 FXML 可解析，四个页面截图非空且无重叠或裁切。
- 在专用 `virtual_campus_course_test` 数据库执行真实迁移和端到端验证，不修改生产库。

编译、静态 SQL 检查和 Mock 测试不能代替真实 MySQL 与 Socket 验证；最终报告必须明确区分已执行的验证层级。

## 11. 交付边界

后续单独设计：

- 教师录入、修改和发布成绩。
- 管理员维护课程、教学班、选课窗口和培养方案。
- 抽签策略、抽签批次和结果发布。
- 调课审批和排课方案编辑器。

当前表和协议保留扩展空间，但不提前实现这些后台功能。
