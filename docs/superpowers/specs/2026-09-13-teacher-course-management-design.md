# 选课管理系统教师端实施设计

开始日期：2026-09-13；整理完成：2026-09-14。状态：规划文档，尚未实施。文件名保留开始日期以维持文档链接稳定。

业务来源：`C:/Users/29740/Desktop/选课管理系统教师端前后端业务设计.md`。本文将业务需求与当前 JavaFX、TCP、MySQL 实现及管理员规划对齐；实施入口为 `../plans/2026-09-13-teacher-course-management.md`。

## 1. 已确认的业务决策

1. 调课允许跨教学周，必须同时补齐管理员审批和学生课表对接。
2. 绩点采用下表规则。小数总评按连续区间比较，不先取整，例如 95.90 → 4.5、96.00 → 4.8。
3. 教师只能提出调课申请；管理员审批通过才产生正式调整。
4. 成绩必须经历可编辑草稿、提交锁定、管理员审批、学生可见的过程。

| 总评区间 | 绩点 |
|---|---:|
| [96,100] | 4.8 |
| [93,96) | 4.5 |
| [90,93) | 4.0 |
| [86,90) | 3.8 |
| [83,86) | 3.5 |
| [80,83) | 3.0 |
| [76,80) | 2.8 |
| [73,76) | 2.5 |
| [70,73) | 2.0 |
| [66,70) | 1.8 |
| [63,66) | 1.5 |
| [60,63) | 1.0 |
| [0,60) | 0.0 |

以下是本规划采用的实施选择，并非额外声称用户已逐项确认：总评用 BigDecimal 计算后 HALF_UP 保留两位小数，再查绩点；四种成绩组成固定为平时、期中、实验、期末，可启用/禁用，不新增任意自定义成绩列；新提交的 `grade_level` 保持 NULL，不编造等级编码；Excel 首版支持 `.xlsx`；结果通知采用进入页面、手动刷新及操作后查询，不增加实时弹窗推送。

## 2. 当前代码基线与实施前置

- 规划时工作区为 `D:/JavaProject/VCampus/.worktrees/course-management-client`，分支为 `feature/course-management-client`。这是持续变化的工作区；执行者必须重新读取 Git 状态及各阶段 progress，不能把规划时的提交当成最终管理员基线。
- `MainController.openCourseSelection()` 的教师分支目前只提示“教师端教务功能暂未开放”。
- 服务端 `MessageDispatcher` 已有 `user`、`course`、`courseAdmin`，尚无教师课程模块。
- V001 已有教学班教师关系、选课记录和四项分数；V004 已有调课申请/目标/结果、成绩提交批次/明细和管理员审计。
- V004 调课申请只有 PENDING/APPROVED/REJECTED；目标只保存原课次快照，没有明确的新日期。成绩提交表不能充当可编辑草稿表。
- 实际日历日期表叫 `calendar_date`，不是部分旧规划提到的 `teaching_calendar_date`。
- 学生课表及通知的实际读取入口是 `CourseScheduleDAO`，由 `CourseQueryService` 调用；不能只改 `CourseQueryDAO` 就认为课表已对接。
- `AdminScheduleConflictDAO` 已查询生效调课，但现有排除参数按整个 arrangement 排除；调课需要只排除申请中的具体原 occurrence。
- 现有工具链是 Java 25、Gson 2.13.2、JavaFX、本地 JAR 和无框架 main 测试；不为本任务整体迁移构建系统。

推荐先完成管理员第三、第四、第五项，再执行本计划。教师只读查询可以独立准备，但调课、成绩闭环验收分别依赖管理员第四、第五项。本文标明“管理员阶段完成后存在”的类在规划时可能尚不存在，执行者不得把它们当成当前已实现能力。

## 3. 总体架构与边界

```text
教师 FXML / Controller / 编辑模型
    → TeacherCourseService / SocketTeacherCourseService
    → 现有 SocketClient：module=courseTeacher
    → TeacherCourseHandler：token、角色、参数、响应
    → 查询 / 调课申请 / 成绩草稿 / 导入 Service
    → DAO + MySQL 事务

教师提交调课 → 共用 adjustment_request/target → 管理员审批
教师提交成绩 → 共用 grade_submission/item → 管理员审批
管理员通过 → adjustment 或 published grade → 教师/学生查询
```

教师专用 DTO 放在 `VCampusCommon/src/dto/course/teacher/`；纯计算放在 `VCampusCommon/src/course/grade/`。客户端服务返回不可变业务 DTO，成绩编辑状态放在 `model/course/teacher/GradeBookEditorModel`，不让 DTO 承担 JavaFX 控件职责。

服务端分为 `TeacherCourseQueryService`、`TeacherAdjustmentApplicationService`、`TeacherGradeBookService`、`TeacherGradeImportService`、`TeacherApplicationService`。保留一个教师模块 Handler，按业务委托，不把所有 SQL 和事务塞进 Handler。业务 JSON 继续走已有 TCP 连接；Excel 原文件使用有票据的独立短 TCP 连接。

### 全局约束

- 新业务模块为 `courseTeacher`；保持 `user`、`course`、`courseAdmin` 兼容。
- 教师 UID 从有效 Session token 获取，不信任客户端 teacherId、sender 或 UID。
- 数据库 BIGINT 的网络表示为十进制 String；用户 UID 保持 VARCHAR(32) 字符串。
- 数据库会话与持久化时刻使用 UTC；业务日期经 `calendar_date` 和所属教学日历时区换算。
- 学期编号保持 1=暑期学校、2=秋学期、3=春学期；沿用 academicYear 与现有学年显示约定，例如 2025/3 表示 2025-2026 春学期，不重新编码数据。
- 分页 page 从 1 开始，size 为 1..100，响应含 items、totalCount、page、size；排序稳定且含唯一 ID。
- 写请求包含 operationId UUID 与对应版本。相同操作 ID、相同规范化请求重放结果；同 ID 不同内容返回 CONFLICT。
- 教师没有 force 权限；客户端预检查不能替代提交时和审批时的服务端检查。
- 不更新已发布基础排课，不让教师直接发布 grade，不删除申请或成绩历史。
- 真实数据库测试仅允许 `virtual_campus_course_test`，所有共享库重置、迁移及写入集成测试串行运行。
- 不改动或提交无关的现有未提交内容；特别保留学生课表、成绩页、查询优化和管理员已有改动。

## 4. 教师权限

系统角色首先必须是“教师”。再按教学班关系与具体课次判定权限。

| 关系 | 教学班/名单只读 | 成绩编辑提交 | 调课申请 |
|---|---|---|---|
| `course_offering_teacher.role=0` 任课教师 | 允许 | 允许 | 允许申请其负责的课次 |
| role=1 助教，且系统角色为教师 | 允许 | 不允许 | 不允许 |
| 仅在当前正式安排或生效调课中担任任课教师 | 允许相关教学班查询 | 不允许 | 仅允许自己实际授课的课次 |
| 无关联 | 不允许 | 不允许 | 不允许 |

每次查询和写操作重新检查关系；先查权限再返回学生信息或错误详情。只读详情返回 `canEditGrades`、`canRequestAdjustment` 等能力，GUI 据此显示按钮，服务端仍独立验证。自己历史申请通过 requested_by/submitted_by 查阅，不因后续换教师而丢失；撤销只校验当前登录者是申请人且申请仍为 PENDING。

## 5. 页面与交互

### 5.1 工作台和教学班

工作台左上“返回首页”，右上四入口：教学课程表、教学班、成绩录入、我的申请。沿用 860×580 主窗口，较宽表格横向滚动；不用缩小所有文字强行塞满。

教学班页：学期、课程/教学班搜索、分页列表。详情含基本信息、学生名单、上课安排、成绩情况四个 Tab。名单支持姓名/学号筛选、正常/退课状态、导出当前筛选的全部结果。成绩编辑只包含当前正常 enrollment，退课历史只读。

教学班名称先由课程名与 offering_code 组成展示名称；不虚构一个已有数据库名称字段。开课学院新增可空 `course.offering_college`，教师只读；历史数据没有值时显示“未维护”，不能用教师个人学院冒充开课学院。基础数据导入和开发种子可维护该值，本期不扩展学院管理子系统。

### 5.2 教学课表

学期、上一周/下一周、回到本周；周范围和实际日期来自服务器日历，周一至周日均可显示。节次来自日历模板，覆盖现有第 13 节。课程卡片显示课程名、教室，点击显示编号、教学班、教师、时间、周次和人数，并可进入教学班或申请调课。

课程占用以实际生效教师/助教、时间、教室为准。NORMAL 和 ADJUSTED_TARGET 参与实际占用布局，ADJUSTED_ORIGINAL 只是灰色提示。

跨周规则：原周返回原位置灰色标记；目标周返回新位置；同周才在同一个周视图返回一对标记。按原周单独过滤再 JOIN adjustment 会漏掉“调入本周”的课程，查询必须覆盖两个日期范围。

### 5.3 调课与我的申请

从一个具体课次打开表单，输入新日期、开始/结束节次、教室和原因。教师不修改任课教师和助教。GUI 首版每次申请一个课次；服务端 targets 保持列表形态，与管理员多周申请兼容。

日期可跨周但必须属于同一学期、同一教学日历的有效教学日；原课次和目标开始时间都不能已经过去。相同日期/节次/教室的无变化申请被拒绝。已有生效调课的原课次本期不再次申请，显示明确原因；再次调课的替换生命周期不混入本次审批实现。

预检查随日期/节次/教室变化触发，使用 generation 忽略旧响应。只有内容完整、原因非空、最新检查通过且当前无提交时才可提交。

“我的申请”显示课程、原/新时间地点、提交时间、状态和处理意见。PENDING 可撤销，终态只能查看；撤销与审批竞争只能一个状态转换成功。

### 5.4 成绩编辑

按学期列出教学班与成绩状态，详情显示人数、完整/缺失数量。四个组成列始终存在，禁用时置灰且不能输入；草稿保留此前输入值，重新启用可恢复。禁用项不计总评，提交快照及正式 grade 的该项写 NULL。

权重以万分比整数保存，10000 表示 100.00%。正式提交要求至少一个启用项，启用项权重大于 0、禁用项权重为 0、合计 10000。草稿可暂存未配齐的权重和缺失分数，此时不显示伪造的总评。非空分数必须为 0..100，最多两位小数。

`总评 = sum(启用项分数 × weightBasisPoints) / 10000`，使用 BigDecimal，最终 HALF_UP 保留两位小数。绩点根据这个总评查已确认的连续区间。缺失启用项时总评/绩点为 NULL，不能把缺失当 0。

本地修改设置 dirty。保存/提交成功用服务端快照更新；失败保留编辑。导航、返回首页、切换教学班和关闭窗口都走同一个离开确认机制。请求在后台执行，FXML 更新在 FX 线程；页面关闭后不再更新控件。

## 6. 数据扩展

迁移只新增，不回写已经执行的 V001-V004。规划分配 V005/V006/V007；执行前检查仓库是否已占用编号，若已占用须整体顺延并同步全部引用，不能覆盖其他任务迁移。

### V005_teacher_course_foundation.sql

- `course.offering_college VARCHAR(100) NULL`。
- `teacher_course_operation_log`：主键 `(teacher_uid, operation_id)`；action、target_type、target_id、request_digest、request_json、response_json、result_code、created_at。teacher_uid 外键到 tbl_user，摘要 SHA-256。成功写入与业务变更在同一事务；请求体按字段固定顺序规范化。
- `teacher_application_read`：主键 `(teacher_uid, application_type, application_id)`；seen_state_key、read_at。application_type 限定 SCHEDULE_ADJUSTMENT/GRADE_SUBMISSION；所有访问先验证真实申请归属。

### V006_teacher_adjustment_requests.sql

- adjustment_request 增加 `withdrawn_at DATETIME(6) NULL`，状态 CHECK 增加 WITHDRAWN。
- 审批字段约束改为：PENDING 未审批且未撤销；APPROVED/REJECTED 有 reviewed_at/reviewed_by 且未撤销；WITHDRAWN 有 withdrawn_at、无 reviewed_at/reviewed_by。不把教师撤销伪装成管理员驳回。
- adjustment_target 增加 `target_calendar_date_id BIGINT NULL`，外键到 calendar_date。新教师申请必须写入；历史 NULL 行继续由 original_week_no + request.new_weekday 解析，保持管理员原有同周/多周语义。
- 请求原始快照保持不变。目标日期字段指定真实教学日，时间由该日模板的 period_definition 生成 UTC。多个 targets 共用请求的节次/资源，新日期的 teaching_weekday 必须与请求 new_weekday 一致。
- 不修改 course_occurrence、schedule_plan、rule 和原 bookings 来实现调课。

### V007_teacher_gradebook.sql

- `teacher_grade_book`：offering_id 主键；revision；draft_open；draft_kind(INITIAL/RESUBMISSION/CORRECTION)；base_submission_id、last_submission_id 可空外键；scheme_json；correction_reason；updated_by、updated_at。每班一份工作副本。
- `teacher_grade_draft_item`：主键 `(offering_id,enrollment_id)`，四项 nullable DECIMAL(5,2)。通过 enrollment 的 `(enrollment_id,offering_id)` 唯一键及复合外键约束教学班归属；不删除 enrollment 历史。
- grade_submission 增加 nullable scheme_snapshot_json、roster_digest、base_submission_id、correction_reason，以及 submission_kind 默认 INITIAL。旧批次没有权重快照就保持 NULL，不能猜测并回填权重。
- grade_submission_item 增加 nullable student_uid_snapshot、student_name_snapshot。新批次保存提交时身份；旧批次兼容原 JOIN 显示。
- `teacher_grade_change_log`：log_id、teacher_uid、offering_id、enrollment_id 可空、operation_id、book_revision、action、before_json、after_json、reason、created_at。权重改变记班级级日志；分数改变记学生级日志。更正必须有原因，记录服务器计算的旧/新总评。
- 数值 CHECK、FK、版本正数、每班唯一工作副本均写入迁移契约测试。执行迁移前检查历史数据是否符合将收紧的约束。

## 7. 调课事务和冲突语义

`TeacherAdjustmentApplicationService` 与管理员审批复用 `ScheduleAdjustmentConflictService`。该服务使用有效课次，不使用只描述基础排课的静态冲突图。

检查内容：教师与助教作为同一人员资源检查跨角色占用；教室占用；本教学班其他课次；当前正常学生的其他有效课程；教室容量；日期/节次/当前正式方案/权限/重复待审或已生效调整。

排除的只是本申请目标原 occurrence 集合，不能排除整个 arrangement。区间采用 `[start,end)`，首尾相接不冲突。学生风险只暴露教师有权查看的本班学生及必要冲突摘要，不返回其他班完整名单。

教师只要存在冲突就不能提交，不接收 force。管理员审批时重新检查，沿用 BLOCKING 不可绕过、OVERRIDABLE 需 force + reason 的规则，包含申请后新增学生造成的冲突。

提交：规范化/校验请求 → 查同操作结果 → 锁 offering → 按 ID 锁原 occurrences → 再查归属、当前方案、时间及重叠 PENDING targets → 冲突检查 → 写申请/不可变 targets → 写教师操作日志 → commit。读取已有 PENDING 请求状态使用不加额外请求行锁的检查，避免与审批“request → offering/occurrence”的顺序反转。

撤销：锁 request → 校验本人/PENDING/expectedVersion → 更新 WITHDRAWN、withdrawn_at、version → 操作日志 → commit。

审批：锁 request → 锁 offering → 按 ID 锁 targets 的原 occurrences → 解析明确目标日期或历史兼容日期 → 重新校验并检查冲突 → 原子写 ACTIVE adjustments、一条关联 RESCHEDULED notice、审核字段与管理员操作日志 → commit。撤销和审批一旦遇到终态返回 CONFLICT。

通知按关联 request 的原/目标周查询并去重；跨周不依赖单个 notice.week_no。任一目标失败全部回滚。

## 8. 成绩工作流

```text
无工作副本 → 保存草稿 DRAFT → 提交 PENDING → 审批 APPROVED
                                      └→ 驳回 REJECTED → 重开草稿 → 新版本提交
APPROVED → 发起更正草稿 → 新版本 PENDING → 审批后替换正式投影
```

GUI 的“已提交待审核”对应数据库 PENDING，不新增与管理员不兼容的 SUBMITTED 数据库状态。`draft_open=true` 时显示 DRAFT；否则依据 last_submission 的状态显示。管理员审批无需另外更新一份容易失同步的工作副本状态。

读取成绩表不隐式写库。尚无工作副本时返回 revision=0 的虚拟草稿；首次保存/提交在 offering 锁内创建。写入均锁 offering → grade_book → 按 enrollment_id 排序的记录，校验任课关系、expectedRevision 和当前 rosterDigest。

rosterDigest 为排序后的正常 enrollment ID 集合摘要。保存允许缺项，但拒绝非法分数、越权学生和失效名单；响应返回最新名单以供人工合并，不能静默丢弃用户输入。

提交请求包含当前完整编辑内容，在一个事务中重新计算、保存工作副本、建立 immutable submission/items、权重/身份/名单快照和统计、关闭 draft_open、记录操作与修改日志。不要求先保存再发送第二个提交操作，避免两个请求之间的版本间隙。

一个教学班至多一个 PENDING 成绩批次；锁 offering 后检查。版本为该班已有 submission.version 的最大值加 1，不能用可编辑草稿 revision 代替提交版本。

管理员批准只发布本批次捕获的学生集合。提交之后新增的学生不被偷偷塞进旧批次，也不使其必然失效；这些学生显示“尚未纳入已提交批次”，待该批结束后进入后续完整版本补录。管理员移除学生的既有成绩工作流限制继续生效。

新版批次有 scheme_snapshot_json 时，审批重新核对权重、分数、总评、绩点和统计；旧批次无方案快照则按旧管理员规则验证，不伪造可重算性。审批成功才更新 `grade.is_published=1`；驳回和更正草稿不能影响学生当前成绩。

更正从最后一个 APPROVED 批次复制为工作副本，保留原版本；被驳回重提从 REJECTED 副本开始。界面可以从某位学生“申请修改”打开，但最终仍提交一个完整教学班版本，展示本次改变的学生及旧/新值。更正原因必须非空，PENDING 期间不可编辑。

## 9. Excel 模板、导入、名单导出

服务端采用 Apache POI `poi-ooxml:5.4.1` 作为明确的起始依赖版本，实施时检查兼容性与安全补丁并将最终版本、传递依赖、许可证写入依赖清单。不能只复制一个主 JAR 就认为依赖完整。

文件约束：`.xlsx`，最多 5 MiB、5000 个数据行；读取第一张工作表。学号必需、姓名可选但提供时须匹配；四项成绩列可以缺失。模板包含全部四项列，并标明启用项和权重，学号使用文本单元格。导出包含当前筛选的全部名单，不能只导出当前页。

业务 TCP 申请短时票据；独立文件 TCP 默认 8889，可配置，测试用端口 0。上传头为长度前缀 JSON 元数据（ticket、token、direction、size、sha256），之后精确读取 size 个原始字节；`direction` 是客户端对本次短连接用途的显式声明（UPLOAD/DOWNLOAD），服务端必须与票据上的用途逐项核对，不匹配即拒绝；票据虽已携带用途，但只有客户端能声明方向时，「错误方向」这条防线才可被验证。不把文件 Base64 塞进业务 JSON，不在现有 readLine 连接混入二进制。文件票据绑定当前 Session、教师、教学班、用途和长度；有效期 2 分钟、单次领取，断线重试重新申请票据。

服务端按自生成文件名写临时目录，不使用客户端路径；限长、校验 SHA-256，拒绝公式成绩、损坏/不支持的工作簿及超限压缩内容。文件解析在后台工作线程，停服关闭监听器、活跃连接及临时文件。客户端离开上传页取消 Future 并关闭短连接。

导入状态：上传 → 后端解析校验 → importToken 预览 → 教师修改/排除异常行 → 后端重新校验预览 → 确认写草稿。importToken 与上传票据不同，绑定教师、offering、baseRevision、rosterDigest、方案和候选内容，10 分钟过期；服务重启后提示重新导入，不丢弃客户端原编辑副本。

导入前保留当前编辑副本并作为 baseDraft 发送校验；不强迫先把它写库。文件缺少的列、空白成绩单元格保留原草稿值；尚无值的保持 NULL。要清空已有成绩使用表格手工清除，不能把空白转换成 0。

预览仍使用成绩编辑表格。非法单元格保留原文本并显示错误，不能在解析时吞掉；未知/重复学号等无法正常合并的行在错误区域显示原行号、学号和姓名。教师须修正或明确排除异常行，后端返回递增的 previewRevision；不默认只导入有效行。

确认导入校验最新 previewRevision、权限、草稿 revision、名单、无未解决错误，在单事务保存部分成绩草稿与操作日志。预览阶段不写正式成绩或草稿；确认导入也不是提交审批。提交成绩仍要求所有启用项完整。

取消导入恢复导入前编辑副本。导入令牌消费在提交成功后完成；若响应丢失，同 operationId 重试先重放已提交结果，不因令牌已消费而报失败。

## 10. 我的申请与结果通知

合并展示本人调课申请及成绩提交/更正批次，按 `(submittedAt DESC,type,id DESC)` 稳定分页；按类型和状态筛选。详情展示管理员意见、处理时间与版本。管理员待审批页面从同一申请/提交表查询，新提交无需额外复制一条审批记录。

结果未读采用 `stateKey = status + ':' + handledAt`，未处理时使用 submittedAt。读过 PENDING 不代表已经读过随后 APPROVED。markRead 校验本人及当前 stateKey；过期读取确认不能把更新后的结果标成已读。课程通知与私人申请结果区分展示，不把学生通知当教师私信。

首版页面进入、手动刷新和本次写操作后查询；不扩展现有学生推送协议。若以后需要主动通知，可在这个查询和已读模型之上补推送，不能把推送作为数据库事实来源。

## 11. 公共契约索引

所有以下类型在相应分项计划首次使用前定义。数据列表做防御性不可变复制，nullable 分数保留 NULL。

| 类型 | 关键字段 / 含义 |
|---|---|
| TeacherPageDTO<T> | items,totalCount,page,size |
| TeacherOperationResultDTO<T> | operationId,message,value,replayed |
| TeacherOfferingDTO | offeringId,offeringCode,offeringName,courseId,courseCode,courseName,credit,academicYear,semester,enrolledCount,capacity,status,canEditGrades,canRequestAdjustment |
| TeacherOfferingDetailDTO | offering,teachers,offeringCollege,description；所有班级 Schedule 由独立查询加载 |
| TeacherRosterRowDTO | enrollmentId,studentUid,studentName,major,enrollmentStatus,selectedAt,droppedAt |
| TeacherScheduleEntryDTO | occurrenceId,offeringId,courseCode,courseName,teacher,location,localDate,week,dayOfWeek,startPeriod,endPeriod,displayKind,adjustmentId,originalScheduleText,adjustedScheduleText,adjustmentReason,canRequestAdjustment |
| TeacherScheduleWeekDTO | calendarId,timezone,week,minWeek,maxWeek,currentWeek,dates,periods,entries |
| TeacherAdjustmentTargetInputDTO | originalOccurrenceId,targetDate（ISO LocalDate） |
| TeacherAdjustmentOptionsDTO | calendarId,timezone,dates,periods,classrooms；仅返回该课次所属日历的可选日期/节次与教室资源 |
| TeacherAdjustmentWriteDTO | operationId,offeringId,targets,newStartPeriod,newEndPeriod,newClassroomId,reason |
| AdjustmentRequestStatusDTO | PENDING,APPROVED,REJECTED,WITHDRAWN；不扩充成绩专用 ApprovalStatusDTO |
| GradeComponentDTO / GradeSchemeDTO | code(DAILY/MIDTERM/EXPERIMENT/FINALTERM),enabled,weightBasisPoints；scheme 含恰好四项 |
| GradeScoresDTO | dailyScore,midtermScore,experimentScore,finaltermScore，均 BigDecimal 可空 |
| TeacherGradeRowDTO | enrollmentId,studentUid,studentName,scores,totalScore,gradePoint,complete,errors |
| GradeBookContentDTO | offeringId,expectedRevision,rosterDigest,scheme,rows（提交内容只收 enrollmentId 与 scores，不信任总评/绩点） |
| WriteGradeBookRequestDTO | operationId,content |
| TeacherGradeBookDTO | offeringId,revision,rosterDigest,state,scheme,rows,lastSubmissionId,baseSubmissionId,canEdit,correctionReason,rosterChangedSinceSubmission |
| GradeImportPreviewDTO | importToken,previewRevision,candidate,totalRows,validRows,errorRows,issues,expiresAt |
| GradeImportRowIssueDTO | rowNumber,studentUid,studentName,field,rawValue,message,excluded |
| TeacherApplicationDTO | type,id,offeringId,title,status,submittedAt,handledAt,reviewComment,canWithdraw,stateKey,unread |
| TeacherApplicationDetailDTO | summary,adjustment,grade；根据 summary.type 恰好一个详情非空，不使用 Object 或未约束 Map |

查询标量置于 data；写 DTO 位于 data.request。响应命名分别为 terms、offerings、offering、students、schedule、conflicts、adjustmentRequest、gradeBook、preview、applications、result。Handler 使用 TypeToken/显式 DTO 解析，拒绝非法枚举、非整数 ID、越界页码和伪造对象归属。

## 12. 验收矩阵与需求覆盖

| 原需求章节 | 负责阶段 | 必须可观察的结果 |
|---|---|---|
| 1、2、27 | T1 | 教师实际进入工作台；伪造其他教师/学生/班级 ID 被服务端拒绝 |
| 10–14 | T1，导出在 T5 | 本人教学班、详情四 Tab、正常/退课名单、所有安排、学院缺省显示 |
| 4、5 | T2 | 学期/周切换、回本周、课程详情、13 节和周末、跨周双位置 |
| 6、7、25 | T3 | 最新预检查、提交再次检查、无教师 force、管理员审批才生效 |
| 9 | T3、T6 | 本人申请明细、PENDING 撤销、审批竞争、处理意见 |
| 15、16、18–21、26 | T4 | 动态启用列、权重、部分草稿、服务端重算、原子提交、只读状态、离开保护 |
| 22、23 | T5 | 上传后端解析、缺列、同表预览、异常姓名/行号、明确确认后写草稿、取消恢复 |
| 24 | T6 | 更正新版本、旧/新值与原因审计、审批前学生成绩不变 |
| 我的申请/消息通知、完整闭环 | T6 | 结果查询、未读更新、教师→管理员→学生三角色验证 |

单元测试覆盖全部绩点分界的下方/等于/上方、权重 10000、缺项与零分差异、精度/舍入。数据库测试覆盖重复请求、同 ID 不同内容、失效版本、两教师并发、提交/增删名单竞争、撤销/审批竞争和中途失败回滚。UI 测试覆盖过期响应、关闭后回调、双击、取消、未保存数据和真实 FXML。

分别报告源码编译、DTO/纯函数、真实 MySQL、真实 TCP、JavaFX 加载和截图检查；本次规划本身不代表这些实现验证已完成。
