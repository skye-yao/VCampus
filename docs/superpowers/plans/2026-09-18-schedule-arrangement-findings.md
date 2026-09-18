# 排课（教学安排）子系统 —— 问题清单与修复线索

> **这份文档是给"接手修复的会话"看的**：只描述**问题、证据、根因、建议修法、验证方式**，
> 不包含实现。每条结论都标了 `文件:行号`，可逐条复核。
>
> **基线**：分支 `integration/course-management-client-main`，commit `fd42fbd`（2026-09-18）。
> 行号以该 commit 为准；若之后有改动，先用 `git log --oneline` 对齐。
>
> **来源**：2026-09-18 一次"讲解排课操作逻辑"的会话 —— 用户实际使用时反馈"看不懂界面在说什么"，
> 逐条对着代码核实后整理成文。**未经修复、未经评审。**

---

## 0. 接手前必读：这个仓库怎么跑

- 工作目录是 worktree `D:\JavaProject\VCampus\.worktrees\course-management-client-integration`，**不要 `cd` 到主仓库**。
- 本机**没有 `rg`**，没有 Maven/Gradle。用 Grep/Glob 工具或 `grep`（bash）。
- **测试入口只有一个**：
  ```bash
  pwsh -File scripts/test-teacher.ps1 -Suite Course -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
  ```
  `-WithMySql` 与其他 `-WithTcp/-WithGui` 是**门控**：不传就不跑，而套件**照样打印 passed**。
- **唯一的通过判据是退出码**（`scripts/test-teacher.ps1:600` 附近的 `$LASTEXITCODE -ne 0`），
  **没有 PASS 行解析**。想知道"哪些类真的跑了"，数日志里的 `Running <class>` 行（`:592` 附近打印）。
- **只登记在门控列（`MySql`/`Tcp`/`Gui`）里的类永远不会被执行** —— 运行循环不读这三列。
  新增测试类必须同时出现在 `Common`/`Client`/`Server` 某一列。
- **不要动 `.gitignore` 和 `VCampusServer/src/resources/seed/seed-course-demo.sql`** —— 另一个会话的在途工作。
- 既有 7 个红是**预先存在**的，别为了让它们变绿改产品代码：
  `integration.AdminScheduleSocketEndToEndTest`、`integration.AdminEnrollmentSocketEndToEndTest`、
  `network.MessageDispatcherTest`、`ui.AdminCourseUiSmokeTest`、`database.CourseMigrationMySqlTest`、
  `integration.AdminCourseCatalogSocketEndToEndTest`、`integration.CourseModuleSocketEndToEndTest`。

---

## 1. 背景：这套子系统在数据上是四层

修复前必须先建立这个心智模型，否则会改错层。

| 层 | 表 | 说明 |
|---|---|---|
| ① 教学日历 | `teaching_calendar` + `day_template` / `period_definition` / `calendar_date` | 定义"第几周是几号""一天有哪几节"。**按学期一份**，目前**没有维护界面**（见 丙2） |
| ② 排课方案 | `schedule_plan` | 容器，状态 `DRAFT` / `PUBLISHED`。日历上有指针 `current_schedule_plan_id` 指向**当前生效的那一份** |
| ③ 教学安排 | `course_schedule_arrangement` | 一条 = 一个教学班 + 教师 + 助教 + 教室 + **起止周** + 若干**时间段**。`teacher_uid`/`classroom_id` 允许为 NULL（`V004:47/49` 写明"历史迁移行允许为空"） |
| ④ 展开实例 | `course_occurrence` + `resource_booking` | 保存③时按日历展开成"具体哪天的哪一节" |

**关键事实**：**学生端和教师端都直接读 `current_schedule_plan_id` 这根指针**，
所以"发布"不是保存，是**替换全校看到的那份课表**。

**关键事实 2**：一份 `schedule_plan` 是**学期级**的，装的是该学期**所有教学班**的安排。
排课对话框是从**某个教学班**打开的，但它顶部显示的冲突是**整份学期方案**的。
这正是下面 甲1 的根源，也是用户最困惑的地方。

---

## 2. 问题索引

| # | 问题 | 严重度 | 类型 | 主要文件 |
|---|---|---|---|---|
| **甲1** | 方案级冲突**没有归属标识**，用户无法判断某条冲突与自己有没有关系 | **高** | 缺陷 | `CourseConflictService` / `ScheduleArrangementDialogController` |
| **甲2** | 「预检查冲突」**不刷新**方案级冲突，改完数据以为"改了没用" | **高** | 缺陷 | `ScheduleArrangementDialogController:501-533` |
| **甲3** | 「该教学班已有安排」是**写死标题**，与「暂无排课安排」空状态正面打架 | 中 | 缺陷（文案） | `ScheduleArrangementDialog.fxml:38` |
| **甲4** | 同一条冲突**按周展开**，跨 9 周就刷 9 条一模一样的长文本 | 中 | 缺陷（显示） | `CourseConflictService:105-119` |
| **甲5** | 冲突的**严重度（红/黄）没有图例**，可绕过/阻断的区别只体现在颜色和按钮文字上 | 中 | UX 缺陷 | `ScheduleArrangementDialog.fxml` / `.java:1268-1276` |
| **乙1** | 全部写控件的可用性由 `plan.status` 单向决定，而**这个状态在界面上只有一行小字** | 高 | 结构性 | `ScheduleArrangementDialogController:837` |
| **乙2** | 「发布方案」会**变身**为「填写原因并发布」，同一个原因框服务两个完全不同的按钮 | 中 | 结构性 | `.java:1293-1298` / `.fxml:84` |
| **乙3** | 「保存安排」有**隐藏前置条件**（必须先预检查，改一个字段就作废），界面无任何提示 | 高 | 结构性 | `.java:565-576` / `451-456` |
| **乙4** | **不可逆**的「发布方案」与普通的「保存安排」并排放在同一行、样式相近 | 高 | 结构性 | `.fxml:93-101` |
| **乙5** | 「创建草稿方案」会**静默复制**已有方案的全部安排，点之前界面上无提示 | 中 | 结构性 | `.java:1018-1035` |
| **丙1** | **没有删除方案、没有撤回发布** —— 发布是单向门 | **高** | 功能缺口 | 全仓 |
| **丙2** | **没有维护教学日历的界面**，改不了"一天几节""第几周是几号" | 中 | 功能缺口 | 全仓 |
| **丙3** | 学生端周次选择器**写死 1..20**，与日历实际周数脱节 | 中 | 功能缺口 | `ScheduleController:100-103` |
| **丙4** | `schedule_conflict` 表**从不写入**；`READY` 只是发布事务里的短暂中间态 | 低 | 死代码 | 全仓 |
| **丙5** | "一份学期能有几份 `PUBLISHED` 方案"**没有明确语义**（隐式取 revision 最大） | 中 | 设计缺口 | `AdminScheduleDAO` |

---

## 3. 甲 —— 已核实的缺陷（有明确修法）

### 甲1. 方案级冲突没有归属标识 ★ 最值得先修

**现象（用户原话）**：对话框顶部一排黄条写着
`教室容量 40 小于教学班容量 45（第 8 周）`…`（第 16 周）`，
同时界面下方写着「**该教学班暂无排课安排**」。
用户问："这个提示针对的是什么？我的教学班容量改了也没用。"

**根因（三段证据，缺一不可）**：

1. **服务端算的是整份方案的冲突**（`CourseConflictService.java:180-197`）：
   ```java
   public List<ScheduleConflictDTO> checkPlan(Connection connection, long planId) {
       ...
       for (ScheduleArrangementDTO arrangement : scheduleDAO.listArrangements(connection, planId, null)) {
   ```
   第三个参数 `offeringId` 传 **null** —— 遍历**该学期所有教学班的全部安排**。

2. **客户端不做任何过滤**（`ScheduleArrangementDialogController.java:741-743`）：
   ```java
   private List<ScheduleConflictDTO> planConflicts(ScheduleConflictSeverityDTO severity) {
       return plan == null ? List.of() : filterSeverity(plan.getConflicts(), severity);
   }
   ```

3. **渲染时丢掉了几乎所有定位信息**（`:1268-1276`）：
   ```java
   private Label conflictLabel(ScheduleConflictDTO conflict, String styleClass) {
       String message = conflict.getMessage() == null ? conflict.getType() : conflict.getMessage();
       String position = conflict.getWeek() > 0 ? "（第 " + conflict.getWeek() + " 周）" : "";
       Label label = new Label(message + position);
   ```
   **`dayOfWeek` / `startPeriod` / `endPeriod` 明明在 DTO 里却没用；教学班标识根本没进 message。**

**而且 DTO 里本来就有位置放它**（`VCampusCommon/.../ScheduleConflictDTO.java`）：
```java
private final String subjectId;
private final String relatedOfferingId;   // ← 存在，但容量冲突传的是 null
```
对比 `classify()`（`CourseConflictService.java:246-268`）—— 那几类冲突**填了** `relatedOfferingId`
（填的是"对方"教学班）。只有教室容量那条没填（`:113-117`）：
```java
new ScheduleConflictDTO(CLASSROOM_CAPACITY, OVERRIDABLE,
        Long.toString(candidate.classroomId()), null, week, ...);
//                                            ^^^^ relatedOfferingId 为 null
```
注意 `check()` 里 `candidate.offeringId()` 就在作用域内，填它是顺手的事。

**影响**：用户完全无法判断某条冲突与自己正在排的课是否相关。上面的例子就是：
用户改自己教学班的容量，而那 9 条属于**别的教学班**，当然一点变化都没有。
这不是"用户没看懂"，是**信息压根没显示**。

**建议修法（二选一或并用）**：
- **(a) 带上标识**：把 `candidate.offeringId()` 填进 `relatedOfferingId`（容量冲突那条），
  并在 `conflictLabel` 里渲染出教学班号/课程名（需要一次 offering 名称查询，资源列表 `resources` 里可能已有）。
- **(b) 分区**：对话框顶部把"本教学班的冲突"与"其他教学班的冲突"**分开**，
  后者折叠成一行「本方案还有 N 条其他教学班的冲突」，点开才展开。

**(b) 更贴近用户心智**（他们打开这个对话框是为了排**一门课**），但改动更大。
无论选哪个，**`conflictLabel` 都该把 `dayOfWeek`/`startPeriod`/`endPeriod` 渲染出来** —— 数据已经在手上。

**验证**：`ScheduleArrangementDialogControllerTest`（离屏，假 service）可覆盖过滤/分区逻辑；
服务端侧在 `ScheduleManagementMySqlTest` 或 `CourseConflictMySqlTest` 断言
"容量冲突的 `relatedOfferingId` 非空且等于产生它的那个 offering"。
**别两边都加**，选更合适的那个（容量判据在 `CourseConflictMySqlTest` 更自然）。

---

### 甲2. 「预检查冲突」不刷新方案级冲突

**现象**：对话框开着的时候，在别处改了教学班容量，回来点「预检查冲突」，顶部黄条**一动不动**。
用户据此认为"改了没用"。（实际根因见 甲1，但这条**独立存在**，即使冲突属于本教学班也一样。）

**根因**：两条完全独立的通道。

- 方案级列表 `planConflictArea` **只在 `loadPlan()` 时重建**（`:1322-1342` 渲染，
  数据来自 `plan.getConflicts()`，由服务端 `checkPlan` 计算）。
- 「预检查冲突」走 `previewArrangement()`（`:501-533`），只写**表单级**的 `conflicts` 字段：
  ```java
  service.checkArrangement(request).whenComplete((found, failure) -> fxExecutor.accept(() -> {
      ...
      conflicts = found == null ? List.of() : List.copyOf(found);   // 只更新下面那个 conflictArea
      previewCurrent = true;
      render();
  }));
  ```
  **它从不碰 `plan`。**

**影响**：用户必须**关掉对话框重新打开**才能看到方案级冲突更新 —— 而这个操作他们不可能猜到。

**建议修法**：预检查成功后一并刷新方案级冲突。**但注意成本**：现有一条 `checkArrangement` 往返，
再发一次 `loadPlan` 就是两次往返。更干净的做法是让服务端在 `checkArrangement` 的回包里
**顺带**带回该方案的冲突列表（一次往返），或者给 `LOAD_SCHEDULE_PLAN` 加一个"只取冲突"的轻量 action。
**不要**为了省事在客户端本地拼接 —— 冲突的权威计算在服务端。

**验证**：`ScheduleArrangementDialogControllerTest` 断言"预检查成功回调后 `planConflicts` 用的是新值"。

---

### 甲3. 「该教学班已有安排」与「该教学班暂无排课安排」正面打架

**现象（用户截图）**：同一个界面上同时显示

```
该教学班已有安排                    [新增安排]
该教学班暂无排课安排
```

**根因**：前者是**完全写死的静态分区标题**，没有 `fx:id`，控制器永远不会改它：

`ScheduleArrangementDialog.fxml:38`
```xml
<Label styleClass="course-admin-schedule-section" text="该教学班已有安排" />
```

真正表达状态的是下面那行（`:44`）：
```xml
<Label fx:id="emptyArrangementLabel" ... text="该教学班暂无排课安排" visible="false" />
```
由代码控制显隐（`.java:1086-1090`）：
```java
boolean empty = !loadingArrangements && errorText() == null && arrangements.isEmpty();
emptyArrangementLabel.setVisible(empty);
emptyArrangementLabel.setManaged(empty);
```

**零条安排时两行同时出现** → 读起来就是"又有安排又没有"。

**建议修法**：给 `:38` 那个 Label 加 `fx:id`（例如 `arrangementSectionLabel`），
控制器按 `arrangements.isEmpty()` 切换文案：空时写「该教学班暂无排课安排」并隐藏 `emptyArrangementLabel`，
非空时恢复「该教学班已有安排」+ 显示列表。
**注意**：`ScheduleArrangementDialog.fxml:29` 的 `fx:id="scheduleContentScroll"` 是**故意**没有控制器字段的
（`AdminCourseUiSmokeTest:293` 拿它当 CSS 查找句柄），**别照那个样子推断"fx:id 可以随便加"** ——
加 `fx:id` 不会报错（`FXMLLoader` 对找不到字段的 `fx:id` 是**静默跳过**），但加错了就是死代码。

**验证**：`ScheduleArrangementDialogControllerTest` 里断言两种状态下的文案；
`ui.AdminCourseUiSmokeTest` 目前**不在任何套件里**（是 7 个既有红之一），别指望它。

---

### 甲4. 同一条冲突按周展开，刷屏

**现象**：跨 8–16 周的一段安排，产生 **9 条一模一样**的
`教室容量 40 小于教学班容量 45（第 N 周）`。

**根因**（`CourseConflictService.java:105-119`）：冲突在**周循环内部**生成：
```java
for (ScheduleSlotDTO slot : candidate.slots()) {
    for (int week = candidate.startWeek(); week <= candidate.endWeek(); week++) {
        ...
        if (roomCapacity != null && roomCapacity < offering.capacity()) {
            add(conflicts, seen, new ScheduleConflictDTO(CLASSROOM_CAPACITY, OVERRIDABLE, ...));
        }
```
去重键里**含 `week`**（`:278-284`），所以每周都是一条新记录：
```java
String key = conflict.getType() + "|" + conflict.getWeek() + "|" + conflict.getDayOfWeek()
        + "|" + conflict.getStartPeriod() + "|" + conflict.getEndPeriod() + "|"
        + conflict.getSubjectId() + "|" + conflict.getRelatedOfferingId();
```

**影响**：真正**不同**的问题会被同一条问题的 9 份拷贝淹没，且把发布阻断与否的判断
（`.java:1294-1297` 数 `planConflicts(...).isEmpty()`）暴露在一个巨大的列表上。

**建议修法**：对"周次连续且其余字段相同"的同类冲突**合并成一条**，周次渲染成区间
（「第 8-16 周」）。**不能简单地去掉 `week` 去重** —— 那样会把"第 3 周和第 9 周"
两个**不相邻**的周次错误地并成一条。正确做法是先按 (type, subjectId, relatedOfferingId,
dayOfWeek, startPeriod, endPeriod) 分组，再对组内的周次集合求连续区间。
**注意**：这会同时影响**表单级**和**方案级**两个列表（都走 `check`），是**跨端契约的变化**，
客户端渲染和 `ScheduleArrangementDialogControllerTest` 的断言都要一起改。

---

### 甲5. 冲突严重度没有图例

**现象**：黄条 vs 红条的区别，用户只能靠猜；「阻断」和「可绕过」这两个决定
"能不能保存 / 能不能直接发布"的概念，界面上**没有任何一处解释**。

**根因**：只有样式类，没有说明文案（`.java:1334-1339`）：
```java
for (ScheduleConflictDTO conflict : blocking) {
    planConflictArea.getChildren().add(conflictLabel(conflict, "course-admin-conflict-blocking"));
}
for (ScheduleConflictDTO conflict : overridable) {
    planConflictArea.getChildren().add(conflictLabel(conflict, "course-admin-conflict-overridable"));
}
```
`conflictSummaryLabel` 的文案是 `"阻断性冲突 N 项 · 可绕过冲突 M 项"`（`:1255-1266`），
**但没说这两类意味着什么**。

**建议修法**：在冲突区上方加一行固定说明，或给每个标签加 tooltip：
「可绕过冲突：不阻止保存/发布，但需要填写原因」「阻断性冲突：必须解决才能保存/发布」。

---

## 4. 乙 —— 结构性交互问题（需要设计决策，别当 bug 顺手改）

这一组不是"代码写错了"，是**交互模型本身让人无法自解释**。
修它们要动交互，需要先定方案，**不要**在没有设计结论的情况下零散地改文案。

### 乙1. 决定一切的状态是隐形的

所有写控件（教师、助教、教室、起止周、时间段、新增、保存、发布）能否使用，
全部由这一个函数决定（`ScheduleArrangementDialogController.java:837`）：
```java
private boolean editablePlan() {
    return !closed && plan != null && DRAFT.equals(plan.getStatus())
            && !loadingPlan && !loadingArrangements
            && !readErrors.containsKey(ReadTarget.PLAN)
            && !readErrors.containsKey(ReadTarget.ARRANGEMENTS);
}
```
于是：**学期没有方案 / 方案是 `PUBLISHED` → 整屏写控件全灰**。
而界面上反映这个状态的，**只有右上角一行小字**（`:1068-1073`）：
```java
planContextLabel.setText(plan == null ? "排课方案：加载中"
        : "排课方案：" + plan.getName() + " · 修订 " + plan.getRevision() + " · " + planStatusText(plan.getStatus()));
```

用户看到的是"界面坏了"，实际是"在等你去点创建草稿方案"。

**建议**：灰掉时给出**原因**（在表单区顶部显示一行「当前方案已发布，不可编辑；点击右上角"创建草稿方案"开始修改」）。

### 乙2. 一个按钮两种身份，一个输入框两种用途

`：1293-1298`：
```java
if (publishButton != null) {
    boolean hasWarnings = !planConflicts(ScheduleConflictSeverityDTO.OVERRIDABLE).isEmpty();
    publishButton.setText(hasWarnings ? "填写原因并发布" : "发布方案");
```
而"原因"框（`.fxml:84`）的提示语是
`填写强制保存或发布原因（仅可绕过冲突需要）` —— **一个框同时服务「填写原因并保存」和「填写原因并发布」两个按钮**。

### 乙3. 「保存安排」有隐藏前置条件

`:565-576`：
```java
boolean canSave() {
    if (writeBusy() || !editablePlan() || previewPending) return false;
    if (validationMessage() != null || !previewCurrent) return false;   // ← 必须先预检查
    ...
```
而**改动任何字段都会作废预检查**（`:451-456`）：
```java
private void onFormEdited() {
    localMessage = null;
    previewAfterReload = false;
    clearPreview();      // previewCurrent = false
    render();            // 保存按钮立刻变灰
}
```
正确序列是：**改表单 → 预检查冲突 → 保存安排**；再碰一下字段，保存又灰。
**界面没有任何一处说明这一点。**

### 乙4. 不可逆操作长得像普通操作

`.fxml:93-101` 里 `取消 / 预检查冲突 / 发布方案 / 填写原因并保存 / 保存安排`
**并在同一行、样式相近**，但「发布方案」会移动 `current_schedule_plan_id`
（`ScheduleManagementService.java:290`），**学生端和教师端同时改读它**，且**没有撤回路径**（见 丙1）。
（发布确实有二次确认弹窗 —— 但在"有可绕过冲突"的分支上**直接发**，见 `:692-694`。）

### 乙5. 「创建草稿方案」的复制副作用无提示

`:1018-1035` 的 `handleCreateDraft` 在 `copyPublished=true` 时，
服务端会把已有 `PUBLISHED` 方案的安排**复制进新草稿**（跳过缺教师/缺时间段/缺教室的行，
见 `ScheduleManagementService.copyArrangements`）。**点之前界面上没有任何提示说它会复制。**
（好消息：复制条数已经会写进结果消息并显示在校验行上 —— 那是上一轮修复加的。）

---

## 5. 丙 —— 子系统功能缺口（需要产品决策）

### 丙1. 没有删除方案，也没有撤回发布 ★

**已核实**：全仓 `VCampusServer/src` / `VCampusClient/src` / `VCampusCommon/src` 里
**没有任何 `DELETE FROM schedule_plan`**，29 个管理员 action 里也没有删除方案的动作。

- 一份建错的草稿（比如发现复制来源选错学期）**删不掉**；
- 发布之后**撤不回** —— 只能"再造一份对的、再发布一次"覆盖。

**修法方向**：`AdminCourseActions` 加 `deleteSchedulePlan`/`discardDraftPlan`；
`AdminScheduleDAO` 加删除入口；`ScheduleManagementService` 加写方法
（**DRAFT 之外不能删**；删除要级联清 `course_occurrence`/`resource_booking`）；
客户端加按钮。若要支持"撤回已发布"，还得多一层"指针回退到上一份 PUBLISHED"的语义 ——
那**依赖 丙5 先有结论**。

### 丙2. 没有维护教学日历的界面

**已核实**：全仓**没有一处 `INSERT INTO teaching_calendar`**。
"一天有几节课""第几周是几号"只能靠 SQL 迁移/种子建立，管理员改不了。
**注意**：加这个功能会激活一条**已知的锁缺陷** ——
`ScheduleManagementService` 的锁只在日历已存在时才加，而 mutation 里会重新查一次日历；
今天不可达**正是因为**没有创建日历的路径。**加功能时必须一起修那条锁。**

### 丙3. 学生端周次选择器写死 1..20

`ScheduleController.java:100-103`：
```java
private void configureWeekSpinner() {
    weekSpinner.setValueFactory(WeekSpinner.valueFactory(1, 20, 3));
```
而教师端用服务端下发的 `minWeek`/`maxWeek`。演示库日历是 **16 周**，
所以学生端能选到第 17–20 周 —— 那几周**大概率是空表且无任何提示**。
**修法**：`CourseScheduleWeekDTO` 已经带日历信息，把 `minWeek`/`maxWeek` 一并下发，
参照 `TeacherScheduleWeekDTO`。

### 丙4. 死表与死状态

**先分清两张容易混淆的表** —— 它们名字像，用途完全不同：

| 表 | 定义 | 谁写 | 谁读 |
|---|---|---|---|
| `schedule_conflict` | `V002_create_schedule_tables.sql:183` | **没有任何 Java 引用它**（已核实） | 无 |
| `course_offering_conflict` | `V002:229` | `AdminScheduleDAO.rebuildOfferingConflicts:188-197`，**在发布时重建** | `CourseSelectionDAO:67`（选课时的 LEFT JOIN） |

- **`schedule_conflict` 是真死表**（按 booking/occurrence 对存储的细粒度冲突，0 处引用）。
  要么删表，要么在文档里明确它"预留"。
  **注意别顺手把它当成 `course_offering_conflict`** —— 后者是**活的**，
  发布时全量重建，供**学生选课**判断两个教学班会不会撞课。改动排课写入路径时**别把它漏掉**。
- **`READY` 状态**：`AdminScheduleDAO.markReady:321-324` 只在
  `ScheduleManagementService.java:287` 被调用一次，紧接着 `markPublished`（`:288`）。
  它是**发布事务里的中间态**，UI 从不展示、也从不作为可编辑判据。**别把它当成"发布前的可审核状态"。**

### 丙5. "一份学期能有几份 PUBLISHED 方案"没有明确语义

`findPublishedPlanId` 取 **revision 最大**的那份 —— 这是个**隐式约定，没有写进任何文档**。
撤回发布（丙1）依赖它先有结论。

---

## 6. 已记录在别处的问题 —— 不要重复开条目

上一轮（`docs/superpowers/plans/2026-09-17-course-module-fixes.md`）的遗留清单在
**`.superpowers/sdd/2026-09-17-course-module-fixes/follow-ups.md`**（**git-ignored，但文件在磁盘上**）。
与本子系统相关的几条（**已在那里详述，此处只列标题**）：

- **甲1**（那份清单里也叫甲1）：无删除方案 / 撤回发布 —— **与本文 丙1 是同一条，以那份为准**。
- **甲4**：学生端周次写死 —— **与本文 丙3 是同一条，以那份为准**。
- **乙7**：空方案发布拒绝门的措辞读的是 ACTIVE 过滤后的列表（措辞与事实不符）。
- **乙2**：学生端格子静默丢弃"节次超出该周日历"的课。
- **乙3**：「契约漂移 ⇒ 静默整页空白」的失败模式（空字典与加载失败长得一样）。
- **丁**：`ScheduleController.renderSchedule` 的**列重建循环没有任何自动断言** ——
  改这块只能靠人眼验收。

---

## 7. 怎么复核每一条

本文所有断言都可以用只读操作复核：

```bash
# 甲1 的三段证据
grep -n -A18 "public List<ScheduleConflictDTO> checkPlan" VCampusServer/src/service/CourseConflictService.java
grep -n -A3  "private List<ScheduleConflictDTO> planConflicts" VCampusClient/src/controller/ScheduleArrangementDialogController.java
grep -n -A9  "private Label conflictLabel" VCampusClient/src/controller/ScheduleArrangementDialogController.java

# 甲2：预检查只写表单级 conflicts
grep -n -A32 "void previewArrangement" VCampusClient/src/controller/ScheduleArrangementDialogController.java

# 甲3：静态标题无 fx:id
sed -n '36,45p' VCampusClient/src/resources/fxml/ScheduleArrangementDialog.fxml

# 丙1 / 丙2 / 丙4：确认"全仓没有"
grep -rn "DELETE FROM schedule_plan" VCampusServer/src VCampusClient/src VCampusCommon/src --include=*.java
grep -rn "INSERT INTO teaching_calendar" VCampusServer/src --include=*.java
grep -rn "schedule_conflict" VCampusServer/src --include=*.java          # 空 —— 只有这张死表是空的
grep -rn "course_offering_conflict" VCampusServer/src --include=*.java   # 非空 —— 这张是活的，别搞混
```

**用户报的那 9 条冲突到底是谁的**，可以直接查库（把 `<plan_id>` 换成那份草稿的 id）：
```sql
SELECT o.offering_id, o.offering_code, o.capacity AS 教学班容量,
       c.id AS 教室ID, c.name AS 教室, c.capacity AS 教室容量
FROM course_schedule_arrangement a
JOIN course_offering o ON o.offering_id = a.offering_id
JOIN classroom c ON c.id = a.classroom_id
WHERE a.plan_id = <plan_id> AND c.capacity < o.capacity;
```

---

## 8. 明确**不是**问题的（别去"修"）

- **`ScheduleArrangementDialog.fxml:29` 的 `fx:id="scheduleContentScroll"` 没有控制器字段** ——
  **故意的**。`AdminCourseUiSmokeTest:293` 用它当 CSS 查找句柄。
- **教师/教室/助教显示「待定」** —— 合法状态，不是"没填完"。
  `resourceName()`（`:1447-1453`）在资源为 null 或名称为空时返回「待定」，
  对应 `V004:47/49` 明确的"历史迁移行允许为空"。
- **`course_schedule_arrangement` 一条安排能有多个时间段** —— 设计如此
  （一周上两次的课是同一位老师、同一教室、同一段周次的一次安排）。
  注意：**起止周是安排级的**，一条安排里所有时间段**共用同一对起止周**（`.fxml:76` 标题已写明）。
- **同一教学班出现"多条安排"** —— 合法（例如前半学期一个教室、后半学期换一个）。
