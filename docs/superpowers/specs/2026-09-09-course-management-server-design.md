# 选课管理系统服务端接入设计

## 1. 目标

在 `feature/course-management-client` 分支中，将现有学生端选课管理界面从纯内存演示服务接入真实 Socket 服务端和 MySQL 数据库，同时保留无需服务端即可运行的 UI 预览能力。

本设计覆盖课程列表、选课计划、确认选课、候补、退课、课表、课程通知、成绩和培养方案。教师录入成绩、管理员排课、培养方案维护和抽签执行后台不在本阶段范围内。

`feature/courses` 只作为只读数据库参考。实现过程中不切换到该分支写入，不修改其提交，不整体 cherry-pick `d71ef18`。可复用的 SQL 会选择性整理到当前分支。

## 2. 总体架构

真实数据链路为：

```text
JavaFX Controller
  -> CourseService
  -> SocketCourseService
  -> SocketClient
  -> Message(module="course")
  -> CourseHandler
  -> CourseApplicationService
  -> CourseQueryDAO / EnrollmentDAO / GradeDAO / TrainingPlanDAO
  -> MySQL
```

各层职责如下：

- `VCampusCommon` 定义课程协议常量、传输 DTO、学期键和状态枚举。
- `VCampusClient` 将 DTO 转换为客户端展示模型，不直接依赖数据库实体。
- `CourseHandler` 负责参数解析、会话认证、角色校验和响应封装。
- `CourseApplicationService` 负责资格、窗口、容量、冲突及状态转换规则。
- DAO 只负责 SQL 和结果映射；同一业务事务中的 DAO 方法共享一个 JDBC `Connection`。
- `MockCourseService` 继续供测试和直接 UI 预览使用，正常登录流程默认使用 `SocketCourseService`。

## 3. 数据库迁移

### 3.1 迁移整理

当前分支新增并维护一套确定顺序的迁移：

- `V001`：`course`、`course_major`、`course_year`、`course_offering`、`course_offering_teacher`、`enrollment`、`grade`。
- `V002`：教学日历、日模板、节次、排课方案、排课规则、课程实例、排课资源、教室和冲突表。
- `V003`：本次服务端接入所需的补充表和索引。

参考分支的 `v001` 与 `V002` 重复建立整套排课表，并且部分外键的 `ON UPDATE` 行为不一致。当前分支不会照搬该重复关系，而是让核心课程表只属于 `V001`，排课表只属于 `V002`。迁移文件使用统一的大写版本前缀。

### 3.2 V003 新增结构

- `major`：专业 ID、专业代码和专业名称。
- `student_academic_profile`：学生 UID、专业 ID、入学年份。
- `course_selection_window`：学年、学期、计划提交时间、正式选课时间、退课截止时间和状态。
- `course_waitlist`：教学班、学生、排队时间和有效状态；同一学生在同一教学班只能有一个有效候补位置。
- `training_plan`：专业、入学年份、版本和发布状态。
- `training_plan_group`：方案分组、最低学分及显示顺序。
- `training_plan_course`：分组课程、建议修读学期和要求类型。
- `course_notice`：教学班、可选课程实例、周次、通知类型、标题、内容、发布状态和发布时间。

另外为 `course_major` 增加按专业反查课程的索引，为 `course_year` 增加按建议学期反查课程的索引。专业、年级和培养方案关系均使用关系表，不使用 JSON。

### 3.3 当前状态映射

- 无有效 `enrollment` 且无有效候补记录：`AVAILABLE`。
- `enrollment.status = 1`：`PLANNED`。
- `enrollment.status = 2`：`ENROLLED`。
- `enrollment.status = 3`：历史退课。
- `enrollment.status = 4`：历史未选中。
- 存在有效 `course_waitlist`：`WAITLISTED`。

候补使用独立表，不复用已有 `enrollment.status`，避免改变参考结构中已经定义的状态含义。已修学分从已发布成绩计算，不在培养方案表重复保存。

## 4. 通信协议

所有课程请求使用 `MessageType.REQUEST`、`module = "course"`，按 `action` 分派：

- `listTerms`
- `listOfferings`
- `addToPlan`
- `removeFromPlan`
- `confirmPlan`
- `joinWaitlist`
- `leaveWaitlist`
- `dropCourse`
- `loadSchedule`
- `loadNotices`
- `loadGrades`
- `loadTrainingPlan`

请求只携带学年、学期、周次、教学班 ID 等业务参数，不携带可被客户端指定的学生 UID。所有数据库 `BIGINT` 标识符在 JSON 协议中使用十进制字符串，服务端显式校验并转换为 `long`，避免 Gson 通过 `Double` 造成精度损失。

响应数据使用 `VCampusCommon` 中的 DTO。由于 `Message.data` 是 `Map<String, Object>`，客户端和服务端均通过 Gson 的目标类型或 `TypeToken` 转换数据，禁止直接把 `LinkedTreeMap` 强制转换为 DTO。

## 5. 身份和权限

`CourseHandler` 对每个 action 执行以下步骤：

1. 使用 `request.token` 查询 `SessionManager`。
2. 会话不存在时返回 `UNAUTHORIZED`。
3. 从 `UserSession.username` 获取真实 UID，忽略请求中的 `sender` 或 `uid`。
4. 学生接口要求会话角色为学生，否则返回 `FORBIDDEN`。
5. 解析并校验业务参数后调用应用服务。

本模块不沿用“token 有效但继续信任客户端 sender”的旧模式。该限制只在课程模块内实现，不在本次范围内重构其他业务模块。

## 6. 选课业务规则

### 6.1 可选课程

服务端根据选定学期、教学班状态、选课窗口、学生专业和入学年份返回有资格查看的教学班。跨专业课程根据 `allow_cross_major` 决定是否放行。前端只做关键词、课程类型和当前状态筛选，不能决定最终资格。

### 6.2 计划和确认

`addToPlan` 创建或恢复状态为 `PLANNED` 的选课记录；`removeFromPlan` 只允许移除尚未确认的计划。

`confirmPlan` 对计划中的教学班按 ID 排序后锁定，降低并发死锁风险。每门课程返回教学班 ID、最终状态、是否成功和原因：

- 先到先得且有容量：转为 `ENROLLED`，增加已选人数。
- 先到先得但满员：进入候补队列。
- 抽签课程：保持 `PLANNED`，返回“等待抽签”。
- 资格、窗口或时间冲突：保持原计划并返回冲突原因。

批量响应允许不同课程得到不同结果，但同一批处理中的数据库状态和计数在一次事务中提交。

### 6.3 候补和退课

满员课程允许直接候补；退出候补只关闭当前学生的候补记录。退课必须在截止时间前进行，并在同一事务中减少已选人数、将选课记录改为退课状态，然后按排队时间将第一个仍具备资格且无冲突的候补学生转为正式选中。

所有容量相关写操作先通过 `SELECT ... FOR UPDATE` 锁定 `course_offering`。唯一约束和状态检查保证重复点击或重复消息不会产生重复选课记录。

## 7. 查询行为

- `listTerms` 返回数据库中可用学期及显示名称，替换客户端写死的学期选项。
- `loadSchedule` 只查询当前学生已选课程和已发布排课方案，按周返回课程块。
- `loadNotices` 只返回学生已选教学班中已发布且与学期、周次匹配的通知。
- `loadGrades` 只返回 `is_published = 1` 的成绩；数据库中的 `NULL` 分项在客户端显示为 `--`，不能转成零分。
- `loadTrainingPlan` 按学生专业和入学年份选择已发布方案；分组已修学分根据已发布且通过的成绩动态计算。

## 8. 客户端改造

`CourseService` 增加学期加载能力，并使用结构化的学期键。选课页面增加当前学期来源，课表和成绩页面从服务端加载学期列表，不再声明固定的 `2026-2027 秋学期`。

`SocketCourseService` 负责：

- 构造课程请求并调用 `SocketClient.sendAsync`。
- 校验响应状态码并转换业务异常。
- 将公共 DTO 转换为现有 `model.course` 展示对象。
- 将确认计划的逐门结果交给 Controller 展示汇总。

`CourseServices` 支持显式安装服务实现。正常应用启动时使用 Socket 实现；`CourseUiPreview` 和客户端单元测试在加载 FXML 前安装 Mock 实现。

## 9. 网络层加固

在接入课程服务前修复以下公共问题：

- 使用进程内 `AtomicLong` 生成请求 UID，避免同一毫秒请求覆盖。
- 待处理请求使用 `putIfAbsent` 注册。
- 为异步请求增加超时，超时后从分发表移除。
- Socket 写入加锁，防止并发 JSON 行交叉。
- 连接代际隔离，避免旧接收线程断开时使新连接请求失败。
- 写操作不做网络层自动重试；业务幂等由数据库唯一约束和状态转换保证。

## 10. 错误映射

- `BAD_REQUEST`：参数缺失、格式错误或未知 action。
- `UNAUTHORIZED`：token 缺失或会话失效。
- `FORBIDDEN`：角色不允许访问学生选课接口。
- `NOT_FOUND`：课程、教学班、学期或培养方案不存在。
- `CONFLICT`：重复操作、窗口关闭、资格不符、容量或课表冲突。
- `ERROR`：数据库和未预期的服务端错误。

服务端日志保留异常原因，客户端只显示稳定的业务消息，不暴露 SQL、连接信息或堆栈。

## 11. 测试与验收

自动化验证至少覆盖：

- 公共 DTO 的 JSON 往返转换，包括大于 `2^53` 的 ID。
- 请求 UID 唯一、超时清理、并发写入和断线完成 Future。
- Handler 的 token、角色、参数和错误码映射。
- 选课资格、窗口、容量锁定、重复提交、时间冲突和候补晋升。
- 抽签课程保持计划状态并返回等待抽签结果。
- 成绩查询不返回未发布成绩，空分项保持 `NULL`。
- 培养方案按专业和入学年份匹配，已修学分计算正确。
- 现有客户端模型、Controller、Mock Service 和全部 FXML 测试继续通过。
- 四个课程页面的 JavaFX 截图仍为非空且无明显布局回归。

若本机 MySQL 配置和数据库实例可用，执行真实迁移和客户端到服务端的端到端测试；若不可用，完成编译、单元测试、SQL 静态检查和可替换 DAO 测试，并在最终结果中明确记录未执行的数据库集成验证。

## 12. 交付边界

本阶段交付学生端的完整读写链路。以下能力后续单独设计：

- 教师录入、修改和发布成绩。
- 管理员维护课程、教学班、选课窗口及培养方案。
- 抽签课程的后台抽签执行和结果发布。
- 调课审批和排课方案编辑器。

这些后台能力不影响当前学生端读取已经存在的成绩、培养方案、排课和通知数据。
