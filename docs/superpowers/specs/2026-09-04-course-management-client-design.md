# 选课管理系统学生端设计

## 1. 目标

在现有 VCampus JavaFX 客户端中新增学生端选课管理模块，覆盖 PPT 线框中展示的四个一级页面：选课、课表、成绩和培养方案。界面使用本地演示数据完整运行，不依赖尚未实现的课程服务端。

本次实现的成功标准：

- 从主界面的“选课”入口可以进入选课管理模块，并能返回主界面。
- 四个一级页面可以稳定切换，窗口继续使用现有的 860 x 580 固定尺寸。
- 选课页面中的计划、候补、已选状态可以通过操作即时变化。
- 课表、成绩和培养方案页面能够展示结构完整、可交互的示例内容。
- Controller 不直接构造演示数据，所有数据读取和状态修改均经过 Service。
- 代码能够编译，FXML 能够加载，核心 Service 状态转换有自动化测试。

## 2. 范围

### 2.1 本次包含

- 学生查看当前可选课程。
- 按课程名称或代码搜索，并按课程类型筛选。
- 查看全部课程、计划课程、候补课程和已选课程。
- 展开课程详情，查看教师、时间、地点、容量、简介和先修要求。
- 将课程加入或移出计划，统一确认计划中的课程。
- 满员课程加入候补，退出候补。
- 退选已选课程，并显示确认提示。
- 按学期和周次查看课表、课程详情和调课通知。
- 按学期查看课程总评、成绩组成、平均分和绩点。
- 查看培养方案分组、学分要求和完成进度。

### 2.2 本次不包含

- 服务端 DAO、Service、Handler 或数据库读写。
- 教师录入、修改或发布成绩。
- 管理员创建课程、开设教学班或维护培养方案。
- 跨客户端持久化。演示状态在客户端退出后重置。
- 真实选课并发、时间冲突、容量锁定、抽签和权限校验。这些规则最终必须由服务端执行。

## 3. 视觉与布局

PPT 是信息结构和交互状态的来源，不直接照搬其中的黑白线框。最终界面沿用现有 `style.css` 的东南大学 vCampus 视觉体系：

- 主色使用森林绿 `#587558`，强调色使用金色 `#fdd000`，深色信息使用藏青 `#151E49`。
- PPT 中纯黄色的导航选中块改为金色下划线和浅金背景，保持强调效果并与现有客户端一致。
- 页面使用白色和浅灰绿色背景、细边框、紧凑间距，避免大面积装饰。
- 卡片圆角不超过 8px；状态标签、容量信息和操作按钮保持清晰但不过度突出。
- 所有新增样式以 `course-` 开头，避免影响登录页、主界面和个人中心。

整体结构保持 PPT 的固定框架：顶部模块标题和一级导航，中部为当前页面内容。左上角增加返回主界面的明确操作。选课页保留右侧课程分类摘要；课表页保留通知区域；成绩页使用右侧或顶部指标区；培养方案填充 PPT 中未展开的主体区域。

## 4. 页面结构

### 4.1 模块外壳

`CourseManagementView.fxml` 负责：

- 返回主界面。
- 显示“选课管理系统”标题。
- 管理选课、课表、成绩、培养方案四个一级导航按钮。
- 承载四个通过 `fx:include` 引入的子页面。
- 切换页面时设置目标页面的 `visible` 和 `managed`，并调用目标 Controller 的 `refresh()`。

`CourseManagementController` 只负责导航和刷新协调，不处理具体课程业务。

### 4.2 选课页

`CourseSelectionView.fxml` 包含：

- 搜索框、课程类型筛选和刷新按钮。
- 全部、计划、候补、已选四个二级标签。
- 可滚动课程列表。
- 必修、限选/选修、通选课程的摘要区域。
- 计划页的“确认选课”操作。

课程行默认显示课程名、代码、类型、学分、学时、教师、容量和状态。展开后显示上课时间、地点、课程简介、先修要求及当前状态可执行的操作。

状态转换规则：

- `AVAILABLE -> PLANNED`：加入计划。
- `PLANNED -> AVAILABLE`：移出计划。
- 确认计划时，有余量课程变为 `ENROLLED`，满员课程变为 `WAITLISTED`。
- `AVAILABLE -> WAITLISTED`：满员课程直接候补。
- `WAITLISTED -> AVAILABLE`：退出候补。
- `ENROLLED -> AVAILABLE`：确认后退课。

同一课程在任意时刻只能处于一种状态。

### 4.3 课表页

`ScheduleView.fxml` 包含：

- 学年学期选择框。
- 周次选择控件。
- 周一至周五、按节次排列的课表网格。
- 调课或停课通知列表。

课程块显示简称、地点和教师。点击课程块后显示课程名称、代码、时间、地点、教师和备注。课表只展示已选课程；演示状态中退课后刷新课表，该课程块应消失。

### 4.4 成绩页

`GradeView.fxml` 包含：

- 学年学期筛选。
- 学期绩点、学期平均分、累计平均分和累计绩点。
- 课程成绩列表。
- 选中课程的成绩组成详情。

成绩列表显示课程名、课程代码、学分、总评和绩点。详情显示平时、期中、实验和期末成绩；没有某项成绩时显示“--”，不显示伪造的零分。

### 4.5 培养方案页

`TrainingPlanView.fxml` 包含：

- 总学分完成进度。
- 必修、限选、选修、通选四类分组。
- 每组要求学分、已获得学分和完成状态。
- 课程明细及已修、在修、未修状态。

该页面以只读方式展示，不提供培养方案编辑功能。

## 5. 客户端分层

### 5.1 展示模型

在客户端新增 `model.course` 包，包含满足界面展示所需的最小模型：

- `CourseOfferingView`：课程及教学班展示信息和当前选课状态。
- `SelectionStatus`：`AVAILABLE`、`PLANNED`、`WAITLISTED`、`ENROLLED`。
- `ScheduleEntryView`：课表课程块。
- `CourseNoticeView`：课表通知。
- `GradeRecordView`：课程总评和分项成绩。
- `GradeSummaryView`：学期与累计指标。
- `TrainingPlanGroupView`：培养方案分组及课程项。

这些类是客户端展示模型，不与数据库实体建立一一映射。

### 5.2 Service

`CourseService` 使用 `CompletableFuture` 暴露异步接口，使 Controller 的调用方式能够兼容未来 Socket 实现：

- `loadOfferings()`
- `addToPlan(offeringId)`
- `removeFromPlan(offeringId)`
- `confirmPlan()`
- `joinWaitlist(offeringId)`
- `leaveWaitlist(offeringId)`
- `dropCourse(offeringId)`
- `loadSchedule(term, week)`
- `loadNotices(term, week)`
- `loadGrades(term)`
- `loadTrainingPlan()`

`MockCourseService` 是本次唯一实现。它持有确定性的内存数据并同步更新选课状态。所有返回集合均为副本或不可修改集合，避免 Controller 绕过 Service 修改状态。

`CourseServices` 提供当前 Service 实例。后续接入服务端时，可新增 `SocketCourseService` 并只修改该提供点。

### 5.3 Controller

每个页面拥有独立 Controller，并遵循以下边界：

- Controller 负责读取控件值、调用 Service、更新界面和展示操作结果。
- Controller 不包含示例课程常量，不直接修改模型内部状态。
- Service 的异步回调统一切回 JavaFX Application Thread 后更新控件。
- 页面刷新时保留当前筛选条件，但重新读取 Service 状态。
- 正在执行操作时禁用对应按钮，结束后恢复，避免重复点击。

## 6. 数据流

页面初始化或刷新时：

1. Controller 从筛选控件读取条件。
2. Controller 调用 `CourseService`。
3. Service 返回 `CompletableFuture`。
4. Controller 在 JavaFX 线程更新列表、统计值、空状态或错误状态。

选课状态变更时：

1. Controller 根据当前状态发起对应 Service 操作。
2. `MockCourseService` 校验课程是否存在以及状态转换是否合法。
3. Service 修改内存状态并返回更新结果。
4. Controller 刷新当前列表及分类统计。
5. 切换到课表页时，课表 Controller 重新读取已选课程状态。

## 7. 错误与空状态

- Service 操作失败时显示简短错误提示，并保持原列表状态。
- 搜索无结果时显示“没有符合条件的课程”，而不是空白区域。
- 计划为空时禁用“确认选课”。
- 候补或已选为空时展示对应空状态说明。
- 退课、退出候补和确认计划使用确认对话框，防止误操作。
- 演示 Service 不静默模拟网络异常；只有非法状态转换和不存在的数据会失败。

## 8. 文件变更

计划新增：

- `VCampusClient/src/resources/fxml/CourseManagementView.fxml`
- `VCampusClient/src/resources/fxml/CourseSelectionView.fxml`
- `VCampusClient/src/resources/fxml/ScheduleView.fxml`
- `VCampusClient/src/resources/fxml/GradeView.fxml`
- `VCampusClient/src/resources/fxml/TrainingPlanView.fxml`
- `VCampusClient/src/controller/CourseManagementController.java`
- `VCampusClient/src/controller/CourseSelectionController.java`
- `VCampusClient/src/controller/ScheduleController.java`
- `VCampusClient/src/controller/GradeController.java`
- `VCampusClient/src/controller/TrainingPlanController.java`
- `VCampusClient/src/model/course/*`
- `VCampusClient/src/service/CourseService.java`
- `VCampusClient/src/service/MockCourseService.java`
- `VCampusClient/src/service/CourseServices.java`
- `VCampusClient/test/service/MockCourseServiceTest.java`

计划修改：

- `VCampusClient/src/resources/css/style.css`
- `VCampusClient/src/controller/MainController.java`

不修改服务端、数据库迁移文件和现有用户模块。

## 9. 测试与验收

### 9.1 Service 自动化测试

在没有测试框架的现有项目中，使用可直接运行的 Java 测试入口验证：

- 初始课程状态和数量。
- 加入、移出计划。
- 确认计划后可用课程进入已选、满员课程进入候补。
- 退出候补和退课。
- 非法状态转换返回失败且不破坏原状态。
- 返回集合不能从 Service 外部修改内部状态。

### 9.2 静态与编译验证

- 编译 `VCampusCommon` 和 `VCampusClient` 源码。
- 解析全部新增 FXML，检查 XML 有效性。
- 启动 JavaFX 预览入口加载 `CourseManagementView.fxml`，确保所有 `fx:id`、事件方法和 `fx:controller` 可解析。

### 9.3 视觉验证

- 截取选课、课表、成绩和培养方案四个页面。
- 检查 860 x 580 下文字无截断、控件无重叠、滚动区域正常。
- 检查导航选中状态、按钮状态、空状态和展开详情。
- 确认新增样式未改变登录页、主界面和个人中心的既有样式。

## 10. 后续服务端接入约束

未来新增 `SocketCourseService` 时，服务端必须重新校验用户身份、年级、专业、培养方案、容量、时间冲突和选课窗口。客户端筛选只用于展示，不能作为选课资格判断。服务端返回的数据应转换为本设计中的展示模型，FXML 和各页面 Controller 不因传输协议改变而重写。
