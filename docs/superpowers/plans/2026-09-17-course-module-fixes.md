# 课程模块三处缺陷修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修掉三个已证实的缺陷：学生端课表硬编码节次/星期与教师端不一致；管理员「添加学生」搜不到没有学籍档案的学生；管理员「排课」读不到方案且永远无法新增。

**Architecture:** 三件事彼此独立，可并行。① 把学生课表的网格几何从客户端常量改为服务端教学日历驱动（与 `TeacherScheduleWeekDTO` 同一套规则），响应从裸数组改为包装对象但**保持 `schedule` 这一个 key**，以免破坏 `CourseHandlerTest` 的 key 集合断言。② `AdminEnrollmentDAO` 的 `LEFT JOIN` + `WHERE sap.status='ACTIVE'` 退化成 INNER JOIN，改为允许无档案，并把学号搜索从全等改成 LIKE。③ 把 `CourseConflictService.checkPlan` 拆成"宽松读"与"严格发布门"两个语义，并新增「创建草稿方案」动作（可从已发布方案复制），补上"运行时代码无法创建 schedule_plan"这个缺口。

**Tech Stack:** Java 25（无构建工具，`javac`/`java` 直调）、JavaFX、Gson、MySQL、纯 `main()` 断言式测试，由 `scripts/test-teacher.ps1` 编排。

**Spec:** 无独立 spec；本计划的依据是 2026-09-17 的两轮只读侦察结论（见每个 Task 的"为什么"段），以及既有设计文档 `docs/superpowers/plans/2026-09-12-admin-course-scheduling.md`。

## Global Constraints

- **禁止 `git commit` / `git push`。** 本仓库根目录 `COLLAB-PROTOCOL.md` 第 47-55 行明确禁止 Claude 执行这些操作。每个 Task 结束时不提交，把改动留在工作区，由用户或 Codex 统一提交。
- 工作目录固定为 worktree `D:\JavaProject\VCampus\.worktrees\course-management-client-integration`，不要 `cd` 到主仓库。
- 本机**没有 `rg`**，也没有 Maven/Gradle。搜索用 Grep/Glob 工具或 `grep`（bash）。
- **测试入口只有一个**：`pwsh -File scripts/test-teacher.ps1`。跑任何套件都必须同时传 `-WithMySql` 和 `-TestConfigPath .codex-tmp/t1config/resources/db.properties`。理由：① 六个套件都声明了 MySQL 门控类，漏传会在选测试阶段直接报错；② 本工作树的 `VCampusServer/src/resources/db.properties` 被另一个会话指向了**演示库** `virtual_campus`，不覆盖会让连库用例以 `AssertionError: Refusing live migration test` 硬失败（它们拒绝跑演示库，也不会降级成 SKIP）。
- **SKIP 永远不等于 PASS。** `-WithTcp` / `-WithGui` 按需传，不传就不跑，别把没跑当成通过。
- 一次运行一个独立输出目录 `.codex-tmp/teacher/<套件>-<时间戳>/`，互不干扰。
- **不要动 `.gitignore` 和 `VCampusServer/src/resources/seed/seed-course-demo.sql` 的未提交改动**——那是另一个会话的在途工作（补 `teacher_uid`、把 `classroom` 插入上移到 FK 依赖之前、补回 `current_schedule_plan_id` 指针）。本计划**不需要**再改种子。
- 既有的 7 个红是**预先存在的**，不在本计划范围内：`integration.AdminScheduleSocketEndToEndTest`、`integration.AdminEnrollmentSocketEndToEndTest`、`network.MessageDispatcherTest`、`ui.AdminCourseUiSmokeTest`、`database.CourseMigrationMySqlTest`、`integration.AdminCourseCatalogSocketEndToEndTest`、`integration.CourseModuleSocketEndToEndTest`。不要为了让它们变绿而改产品代码。

---

## 背景：三处缺陷的根因（执行者必读）

**缺陷 1 —— 学生端与教师端一天课次不一致。**
`VCampusClient/src/controller/ScheduleController.java:32-33` 写死 `FIRST_PERIOD = 1; LAST_PERIOD = 13;`，`:181`/`:186`/`:191` 又写死 5 个星期列。教师端 `TeacherScheduleController.rebuildGrid()`（`:357-412`）则完全由服务端 `TeacherScheduleWeekDTO` 的 `dates`（列）和 `periods`（行）驱动，行数 = `periodNumbers(week.getPeriods())` 的去重并集。服务端 `TeacherScheduleDAO.periods()`（`:176-198`）从 `calendar_date ⋈ period_definition` 取节次，所以**一天几节 = 该周日历模板 `period_definition` 的行数**。演示种子 `day_template` 3101 只有 8 节、测试夹具只有 4 节，而学生端恒定 13 行 —— 这就是不一致。

**缺陷 2 —— 管理员「添加学生」敲学号搜不到。**（**已修正过一轮**：第一轮只读侦察的结论被真库复核证伪，此处是复核后的版本，Task 4 也按此重写。）
`VCampusServer/src/dao/AdminEnrollmentDAO.java:25` 的 `SEARCH` 是
```java
" AND (?='' OR u.UID=? OR u.name LIKE ? ESCAPE '=')"
```
**学号是全等匹配，只有姓名走 LIKE。** 而客户端 `AddOfferingStudentDialogController.java:170-177` 在关键词为空时直接返回、提示"请输入学号或姓名"——管理员**必须先打字**。于是在真库 `virtual_campus` 上实测：

| 输入 | 命中 |
|---|---|
| `张`（姓名片段） | 1 ✓ |
| `213242789`（完整学号） | 1 ✓ |
| `2132427`（学号前缀） | **0 ✗** |

管理员最自然的动作是敲学号前缀 → "没有学生"。这就是用户报的现象。

**而被我第一轮判成病因的 `sap.status='ACTIVE'` 过滤，经查是刻意设计，不改**：`AdminEnrollmentMySqlTest.java:555` 专门插了一个 `role=2` 且无学籍档案的 `ae2-no-profile`，`:94`/`:101` 断言搜索结果总数**恰为 3** 且文案写死 "exclude non-students/**inactive** profiles"；`:209-211` 又断言该学生进教学班时返回 `INVALID_STUDENT_STATUS` BLOCKING。再加 `CourseSelectionDAO.java:193`、`CourseQueryDAO.java:25`、`CourseWaitlistDAO.java:15`、`CourseAcademicDAO.java:61` 全部 JOIN 这张表——"无 ACTIVE 档案即不可操作"是贯穿选课的不变量。动它既会打破一条现在是绿的测试，也会让不该选课的人混进教学班。

**缺陷 3 —— 管理员「排课」看不到方案、也加不了。**
两个独立原因：
(a) `ScheduleManagementService.loadPlan`（`:89-100`）在**读**接口里跑了 `conflicts.checkPlan(...)`，而 `CourseConflictService.checkPlan`（`:165-185`）遇到任何一条"缺任课教师或无时间段"的安排就抛 `IllegalArgumentException("教学安排缺少任课教师或时间段，无法发布")`。种子 `seed-course-demo.sql:374` 的 arrangement 4104（offering 2004）教师为 NULL（种子注释明说这是**预期**的），于是整个 `loadPlan` 失败。客户端 `ScheduleArrangementDialogController.java:266-289` 收到失败后把 `plan` 置空并丢掉服务端真实错误，`:296` 直接 return，`loadArrangements()` 永不执行 → 方案永远卡在"加载中"、已有安排列表全空。
(b) 编辑只允许 `DRAFT`（客户端 `editablePlan()` `:834-839`，服务端 `requireDraftPlan` `:372-378`），而**全 `VCampusServer/src` 没有一处 `INSERT INTO schedule_plan`**（`AdminScheduleDAO` 里没有 `insertPlan`），也没有任何创建方案的 action/UI。DRAFT 在运行时永远无法诞生。

**已复核的真库实录（2026-09-17，库 `virtual_campus`，只读查询）**——执行者不必再自己推：

```
tbl_user               : 16 行 = 5 管理员 + 2 教师 + 9 学生
student_academic_profile: 7 行，全部 ACTIVE（即 9 个学生里 2 个没有档案：213242792、213242794）
schedule_plan          : 只有 1 行 → id=4001, status=PUBLISHED, revision=1, created_by=NULL
teaching_calendar      : 只有 1 行 → id=3001, 2026/2, current_schedule_plan_id=4001, Asia/Shanghai
course_schedule_arrangement: 20 行，全部 plan_id=4001；其中 arrangement 4104 的 teacher_uid 为 NULL
day_template           : 只有 1 个 → id=3101「标准工作日课表」，80 个 calendar_date，8 个 period_definition
```

这组数字同时钉死三件事：① 学生端画 13 行、教师端画 8 行，**差 5 行**，必须修；② 唯一的方案是 PUBLISHED，**没有草稿**，且 `4104` 教师为 NULL——`loadPlan` 必炸，排课界面必然空白；③ 学号前缀搜不到是查询写法的锅，不是数据的锅（7 个可搜学生都在）。

**关键判据：客户端和对话框本身已经是容忍的。** `resourceName(null)` 返回 `"待定"`（`:1392-1399`），`arrangementSummary` 三个资源全走它，`getSlots()` 在两个类型上都把 null 归一成空列表。所以缺陷 3(a) **只需要修服务端**。

---

## 文件结构

**新建（Common，缺陷 1）**
- `VCampusCommon/src/dto/course/CourseCalendarDateDTO.java` — 教学日历某一天的日期信息（列）
- `VCampusCommon/src/dto/course/CoursePeriodDTO.java` — 某一天的一个节次（行）
- `VCampusCommon/src/dto/course/CourseScheduleWeekDTO.java` — 学生一周课表响应（dates + periods + entries）

**新建（Client，缺陷 3）**
- `VCampusClient/src/model/course/ScheduleWeekView.java` — 客户端的一周课表视图（entries 为视图对象，dates/periods 直接复用 Common 的 DTO，与教师端 `TeacherScheduleController` 的做法一致）

**修改（Server）**
- `VCampusServer/src/dao/CourseScheduleDAO.java` — 新增 `calendarId()` / `dates()` / `periods()`，`loadSchedule` 改返回 `CourseScheduleWeekDTO`
- `VCampusServer/src/service/CourseQueryService.java:61` — 返回类型跟着改
- `VCampusServer/src/handler/CourseHandler.java:99-104` — 同一个 `schedule` key，值从数组变成对象
- `VCampusServer/src/dao/AdminEnrollmentDAO.java` — 学号改成 LIKE（**学籍过滤保持原样**）
- `VCampusServer/src/service/CourseConflictService.java` — 拆宽松读 / 严格发布门
- `VCampusServer/src/service/ScheduleManagementService.java` — `loadPlan` 用宽松读；`publish` 先过严格门；新增 `createDraftPlan`
- `VCampusServer/src/dao/AdminScheduleDAO.java` — 新增 `findCalendarIdByTerm` / `findPublishedPlanId` / `findDraftPlanId` / `nextRevision` / `insertPlan`
- `VCampusServer/src/handler/AdminCourseHandler.java` — 新增 `CREATE_SCHEDULE_PLAN` 分支

**修改（Common）**
- `VCampusCommon/src/dto/course/admin/AdminCourseActions.java` — 新增 `CREATE_SCHEDULE_PLAN`

**修改（Client）**
- `VCampusClient/src/controller/ScheduleController.java` — 网格几何改为数据驱动
- `VCampusClient/src/service/CourseService.java` + `SocketCourseService.java` + `MockCourseService.java` — `loadSchedule` 返回 `ScheduleWeekView`
- `VCampusClient/src/service/AdminCourseService.java` + `SocketAdminCourseService.java` + `MockAdminCourseService.java` — 新增 `createSchedulePlan`
- `VCampusClient/src/controller/ScheduleArrangementDialogController.java` + `resources/fxml/ScheduleArrangementDialog.fxml` — 新增「创建草稿方案」按钮

**修改（测试编排）**
- `scripts/test-teacher.ps1` — 新增一个 `Course` 套件，把本计划改到却不在任何套件里的测试类登记进去

---

### Task 1: Common —— 学生课表一周响应的三个 DTO

**为什么**：学生端要有和教师端同源的"行数/列数"，就必须先把节次字典和教学日送到客户端。教师端对应的三个类型在 `dto.course.teacher` 包（`TeacherCalendarDateDTO` / `TeacherPeriodDTO` / `TeacherScheduleWeekDTO`），但本仓库的既有约定是学生端与教师端各有一套平行的 DTO（对照 `ScheduleEntryDTO` 与 `TeacherScheduleEntryDTO`），所以**新建学生侧版本，不改动教师侧的**。

**Files:**
- Create: `VCampusCommon/src/dto/course/CourseCalendarDateDTO.java`
- Create: `VCampusCommon/src/dto/course/CoursePeriodDTO.java`
- Create: `VCampusCommon/src/dto/course/CourseScheduleWeekDTO.java`
- Test: `VCampusCommon/test/dto/course/CourseDtoJsonTest.java`（已登记在 Timetable 套件 `.Common`，直接加方法）

**Interfaces:**
- Produces（Task 2、Task 3 依赖，签名必须逐字一致）：
  - `public CoursePeriodDTO(String date, int period, String startTime, String endTime)`；getter `getDate()/getPeriod()/getStartTime()/getEndTime()`
  - `public CourseCalendarDateDTO(String date, int week, int teachingWeekday, boolean teachingDay)`；getter `getDate()/getWeek()/getTeachingWeekday()/isTeachingDay()`
  - `public CourseScheduleWeekDTO(int week, List<CourseCalendarDateDTO> dates, List<CoursePeriodDTO> periods, List<ScheduleEntryDTO> entries)`；getter `getWeek()/getDates()/getPeriods()/getEntries()`

- [ ] **Step 1: 写 `CoursePeriodDTO`**

照 `VCampusCommon/src/dto/course/teacher/TeacherPeriodDTO.java` 的写法（`final` 类、全 `final` 字段、无 no-arg 构造，Gson 走 Unsafe 反序列化，教师端已验证可行）。Javadoc 照抄该文件的语义：

```java
package dto.course;

/**
 * 教学日历中某一天的一个节次。
 *
 * <p>一行对应一个 {@code (date, period)}；{@code startTime}/{@code endTime} 是教学日历时区的
 * 本地墙上时钟 ISO 字符串（如 {@code 08:00:00}），不是 UTC 时刻。节次编号不硬编码上限，
 * 覆盖到现有第 13 节。日期可能落在周末。
 */
public final class CoursePeriodDTO {
    private final String date;
    private final int period;
    private final String startTime;
    private final String endTime;

    public CoursePeriodDTO(String date, int period, String startTime, String endTime) {
        this.date = date;
        this.period = period;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public String getDate() { return date; }
    public int getPeriod() { return period; }
    public String getStartTime() { return startTime; }
    public String getEndTime() { return endTime; }
}
```

- [ ] **Step 2: 写 `CourseCalendarDateDTO`**

逐字照抄 `VCampusCommon/src/dto/course/teacher/TeacherCalendarDateDTO.java`（全文 38 行，只改包名与类名）。**字段名必须叫 `week` 不叫 `weekNo`**——教师端就是 `week`（`TeacherCalendarDateDTO.java:12`、`:27`），行数据 `week_no` 只在 DAO 的 `rows.getInt("week_no")` 处出现。Javadoc 同样照抄，包括"`teachingWeekday` 是日历表的 `teaching_weekday`，1..7 表示周一..周日，不能由 UTC 星期直接推算"。原样给出：

```java
package dto.course;

/**
 * 教学日历里的一个具体日期。
 *
 * <p>{@code date} 是所属教学日历时区的 ISO 本地日期字符串（{@code 2026-09-14}），不是 UTC 时刻；
 * {@code teachingWeekday} 是日历表的 {@code teaching_weekday}，1..7 表示周一..周日，不能由 UTC
 * 星期直接推算。{@code teachingDay} 表示该日是否为有效教学日（假期或非教学周末为 false）。
 */
public final class CourseCalendarDateDTO {
    private final String date;
    private final int week;
    private final int teachingWeekday;
    private final boolean teachingDay;

    public CourseCalendarDateDTO(String date, int week, int teachingWeekday, boolean teachingDay) {
        this.date = date;
        this.week = week;
        this.teachingWeekday = teachingWeekday;
        this.teachingDay = teachingDay;
    }

    public String getDate() { return date; }
    public int getWeek() { return week; }
    public int getTeachingWeekday() { return teachingWeekday; }
    public boolean isTeachingDay() { return teachingDay; }
}
```

- [ ] **Step 3: 写 `CourseScheduleWeekDTO`**

照 `TeacherScheduleWeekDTO` 的防御性不可变复制模式（构造与反序列化两条路径都不可变，null 视为空列表，getter 返回不可修改视图）：

```java
package dto.course;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 学生按教学日历某周查看自己课表的响应。
 *
 * <p>{@code week} 是实际查看的教学周；{@code dates} 是该周的教学日（可含周末），
 * {@code periods} 是一行一个 {@code (date, period)}，{@code entries} 是该周生效的课次。
 * 课表网格的行数由 {@code periods} 里出现过的节次决定，列数由 {@code dates} 决定——
 * 与教师端 {@code TeacherScheduleWeekDTO} 用同一套规则，两边都不硬编码。
 *
 * <p>三个列表在构造与反序列化两条路径上都做防御性不可变复制，null 视为空列表，
 * getter 返回不可修改视图。
 */
public final class CourseScheduleWeekDTO {
    private final int week;
    private final List<CourseCalendarDateDTO> dates;
    private final List<CoursePeriodDTO> periods;
    private final List<ScheduleEntryDTO> entries;

    public CourseScheduleWeekDTO(int week, List<CourseCalendarDateDTO> dates,
            List<CoursePeriodDTO> periods, List<ScheduleEntryDTO> entries) {
        this.week = week;
        this.dates = immutableCopy(dates);
        this.periods = immutableCopy(periods);
        this.entries = immutableCopy(entries);
    }

    public int getWeek() { return week; }
    public List<CourseCalendarDateDTO> getDates() { return unmodifiable(dates); }
    public List<CoursePeriodDTO> getPeriods() { return unmodifiable(periods); }
    public List<ScheduleEntryDTO> getEntries() { return unmodifiable(entries); }

    private static <T> List<T> immutableCopy(List<T> values) {
        return values == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> unmodifiable(List<T> values) {
        return values == null ? Collections.emptyList()
                : Collections.unmodifiableList(values);
    }
}
```

- [ ] **Step 4: 在 `CourseDtoJsonTest` 加契约断言**

打开 `VCampusCommon/test/dto/course/CourseDtoJsonTest.java`，照该文件里已有的 `TeacherScheduleWeekDTO` 类似断言（若没有则在 `main` 里新增一个私有方法并在 main 中调用，风格与 `roundTripsScheduleEntryDisplayFields` 一致）：

```java
    private static void roundTripsCourseScheduleWeek() {
        CourseScheduleWeekDTO week = new CourseScheduleWeekDTO(3,
                List.of(new CourseCalendarDateDTO("2026-09-14", 3, 1, true)),
                List.of(new CoursePeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00")),
                List.of());
        CourseScheduleWeekDTO decoded = GSON.fromJson(GSON.toJson(week), CourseScheduleWeekDTO.class);
        require(decoded.getWeek() == 3, "week must round-trip");
        require(decoded.getDates().size() == 1
                && decoded.getDates().get(0).getTeachingWeekday() == 1
                && decoded.getDates().get(0).isTeachingDay(),
                "dates must round-trip");
        require(decoded.getPeriods().size() == 1
                && decoded.getPeriods().get(0).getPeriod() == 1
                && "08:00:00".equals(decoded.getPeriods().get(0).getStartTime()),
                "periods must round-trip with fixed-width clock strings");
        require(new CourseScheduleWeekDTO(1, null, null, null).getPeriods().isEmpty(),
                "a null list must normalize to an empty one");
    }
```

- [ ] **Step 5: 编译并跑 Common 契约**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Timetable -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译通过，`dto.course.CourseDtoJsonTest` 与 `dto.course.teacher.TeacherScheduleDtoJsonTest` 均 PASS。
（本 Task 只关心 Common 断言；套件里其余类此时仍应全绿，若出现与 Task 1 无关的红，先记录不要顺手改。）

---

### Task 2: Server —— 学生课表读路径返回日历驱动的一周

**为什么**：行数/列数的真值只能在服务端算（`calendar_date ⋈ period_definition`）。`CourseHandlerTest.java:99-108` 的 `assertSuccessKey` 断言 `response.getData().size() == 1 && containsKey(key)`，所以**必须保持 `schedule` 这一个 key**，把值从裸数组换成 `CourseScheduleWeekDTO` 对象——这正是教师端 `TeacherCourseHandler.java:213-216` 的做法，该断言因此无需修改。

**Files:**
- Modify: `VCampusServer/src/dao/CourseScheduleDAO.java`
- Modify: `VCampusServer/src/service/CourseQueryService.java:61-65`
- Modify: `VCampusServer/src/handler/CourseHandler.java:99-104`
- Test: `VCampusServer/test/dao/CourseQueryMappingTest.java`（Adjustment 套件 `.Server`）
- Test: `VCampusServer/test/service/CourseQueryMySqlTest.java`（Adjustment 套件 `.Server`）
- Test: `VCampusServer/test/handler/CourseHandlerTest.java`（**当前不在任何套件**，Task 8 登记）
- Test（**契约同步**，见 Step 6）：`VCampusServer/test/integration/ScheduleAdjustmentSocketEndToEndTest.java:373`、`TeacherAdjustmentSocketEndToEndTest.java:722`（均属 Adjustment `.Tcp`，本 Task 会跑到）、`TeacherCourseWorkflowEndToEndTest.java:1014`（Applications `.Server`，本 Task **不跑**）、`AdminScheduleSocketEndToEndTest.java:275`、`CourseModuleSocketEndToEndTest.java:283`（均不在任何套件）

**Interfaces:**
- Consumes: Task 1 的三个 DTO
- Produces:
  - `public CourseScheduleWeekDTO loadSchedule(Connection connection, String studentUid, int academicYear, int semester, int week)`（`CourseScheduleDAO`，签名同旧方法只改返回类型）
  - `public CourseScheduleWeekDTO loadSchedule(String uid, int academicYear, int semester, int week)`（`CourseQueryService`）

- [ ] **Step 1: 写失败的测试（先钉住行为，再改实现）**

在 `CourseQueryMySqlTest` 里，把现有对 `service.loadSchedule(...)` 的调用改为从 `CourseScheduleWeekDTO.getEntries()` 取列表，并**新增**断言：该周存在 `dates` 与 `periods`。示例（按该文件既有风格改写 `seededWeek` 那一处）：

```java
        CourseScheduleWeekDTO seededWeek = service.loadSchedule("student-alpha", 2026, 2, 1);
        require(!seededWeek.getPeriods().isEmpty(),
                "a teaching week must publish its period dictionary");
        require(!seededWeek.getDates().isEmpty(),
                "a teaching week must publish its teaching days");
        require(seededWeek.getPeriods().stream().anyMatch(p -> p.getPeriod() == 1),
                "period 1 must be defined");
        // 原有对 entries 的断言改为 seededWeek.getEntries()
```

在 `CourseQueryMappingTest` 里同样把 `mapScheduleRows(...)` 的断言保持不动（该测试针对映射函数本身，不受影响），只需确认编译仍通过。

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败（`loadSchedule` 仍返回 `List<ScheduleEntryDTO>`，取不到 `getPeriods()`）。这就是我们要的"红"。

- [ ] **Step 3: 给 `CourseScheduleDAO` 加日历查询**

在 `CourseScheduleDAO` 末尾（`publishedPlanId` 附近）加三个私有静态方法。`dates()` / `periods()` 逐字照抄 `TeacherScheduleDAO.java:151-198` 的两个方法体（SQL 与 ORDER BY 完全一致），只把类型换成 `CourseCalendarDateDTO` / `CoursePeriodDTO`，并复用同样的定宽时钟格式化器：

```java
    /** Period clock strings are a fixed wire shape; {@code LocalTime.toString()} drops zero seconds. */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
```

```java
    private static long calendarId(Connection connection, long planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT calendar_id FROM schedule_plan WHERE id=?")) {
            statement.setLong(1, planId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Schedule plan is unavailable");
                return rows.getLong(1);
            }
        }
    }

    private static List<CourseCalendarDateDTO> dates(Connection connection, long calendarId, int week)
            throws SQLException {
        String sql = "SELECT local_date, week_no, teaching_weekday, is_teaching_day"
                + " FROM calendar_date WHERE calendar_id = ? AND week_no = ?"
                + " ORDER BY teaching_weekday";
        List<CourseCalendarDateDTO> dates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    dates.add(new CourseCalendarDateDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("week_no"), rows.getInt("teaching_weekday"),
                            rows.getBoolean("is_teaching_day")));
                }
            }
        }
        return dates;
    }

    /**
     * Periods are per date, because two dates of the same week may use different day templates.
     * {@code start_time}/{@code end_time} are local wall clocks and must never be read as UTC.
     */
    private static List<CoursePeriodDTO> periods(Connection connection, long calendarId, int week)
            throws SQLException {
        String sql = "SELECT cd.local_date, pd.period_no, pd.start_time, pd.end_time"
                + " FROM calendar_date cd"
                + " JOIN period_definition pd ON pd.day_template_id = cd.day_template_id"
                + " WHERE cd.calendar_id = ? AND cd.week_no = ?"
                + " ORDER BY cd.teaching_weekday, pd.period_no";
        List<CoursePeriodDTO> periods = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setInt(2, week);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    periods.add(new CoursePeriodDTO(
                            rows.getDate("local_date").toLocalDate().toString(),
                            rows.getInt("period_no"),
                            rows.getTime("start_time").toLocalTime().format(CLOCK),
                            rows.getTime("end_time").toLocalTime().format(CLOCK)));
                }
            }
        }
        return periods;
    }
```

以上两段与 `TeacherScheduleDAO.java:151-198` 逐字同构（SQL、ORDER BY、`CLOCK` 定宽格式化完全一致），只把 DTO 类型换掉——两边必须继续用同一套规则，否则行／列又会漂移。

- [ ] **Step 4: 改 `loadSchedule` 返回一周对象**

把 `CourseScheduleDAO.loadSchedule`（`:36-50`）的返回类型改为 `CourseScheduleWeekDTO`，在原有 entries 逻辑之外补上日历：

```java
    public CourseScheduleWeekDTO loadSchedule(Connection connection, String studentUid,
                                              int academicYear, int semester, int week)
            throws SQLException {
        long planId = publishedPlanId(connection, academicYear, semester);
        long calendar = calendarId(connection, planId);
        String term = CourseQueryDAO.term(academicYear, semester).getDisplayName();
        List<ScheduleEntryDTO> entries = new ArrayList<>(publishedEntries(connection, studentUid,
                academicYear, semester, week, planId, term));
        entries.removeIf(entry -> ScheduleDisplayKindDTO.ADJUSTED_TARGET == entry.getDisplayKind());
        entries.addAll(adjustedTargets(connection, studentUid, academicYear, semester, week, planId,
                term));
        entries.sort(ENTRY_ORDER);
        return new CourseScheduleWeekDTO(week, dates(connection, calendar, week),
                periods(connection, calendar, week), entries);
    }
```

注意保持既有语义：`publishedPlanId` 在无正式方案时仍抛 `SQLException`（`CourseQueryService.read` 会包成 `DatabaseException`），不要顺手改成返回空周。

- [ ] **Step 5: 改 service 与 handler**

`CourseQueryService.java:61-65` 返回类型改 `CourseScheduleWeekDTO`。`CourseHandler.java:99-104` 保持 `response.putData("schedule", ...)` **不变**（值类型自动跟着变）。

- [ ] **Step 6: 同步 `schedule` 这个 key 的全部消费者（契约同步，不是放宽断言）**

`data.schedule` 从数组变成对象之后，**所有**把它当列表读的地方都会在运行期炸（Gson 报 `Expected BEGIN_ARRAY but was BEGIN_OBJECT`）。这些取值是**非类型化**的（`fromJson(JsonTree, Type)`），所以**编译期一律通过**——不主动同步，它们会在后面某个套件里毫无预警地变红。这与 Step 8 里 `CourseHandlerTest` 的处理是同一条规则。

本 Task 的 `-Suite Adjustment -WithMySql -WithTcp`（Step 7）只会跑到前两个；`TeacherCourseWorkflowEndToEndTest` 属 Applications 套件，本 Task 不跑它，由收尾的全分支回归覆盖；最后两个当前不在任何套件里，**没有任何自动检查会替你发现漏改**。五个都必须在本次提交里改完。

要改的只有取值表达式一个形状：先从对象里取 `entries`，再喂给原来的目标类型。**断言一个字都不动。**

1. `VCampusServer/test/integration/ScheduleAdjustmentSocketEndToEndTest.java:373-374`（Adjustment `.Tcp`，本 Task 跑到）

```java
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule"))
                .getAsJsonObject().get("entries"),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
```

2. `VCampusServer/test/integration/TeacherAdjustmentSocketEndToEndTest.java:722-723`（Adjustment `.Tcp`，本 Task 跑到）

```java
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule"))
                .getAsJsonObject().get("entries"),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
```

3. `VCampusServer/test/integration/TeacherCourseWorkflowEndToEndTest.java:1014`（Applications `.Server`，本 Task **不跑**）

```java
        return GSON.fromJson(GSON.toJsonTree(response.getData("schedule"))
                .getAsJsonObject().get("entries"), SCHEDULE_ENTRIES);
```

4. `VCampusServer/test/integration/AdminScheduleSocketEndToEndTest.java:275-276`（不在任何套件）

```java
        List<ScheduleEntryDTO> entries = GSON.fromJson(GSON.toJsonTree(response.getData("schedule"))
                .getAsJsonObject().get("entries"),
                new TypeToken<List<ScheduleEntryDTO>>() { }.getType());
```

5. `VCampusServer/test/integration/CourseModuleSocketEndToEndTest.java:283`（不在任何套件）——`asList` 的入参换成 `entries` 节点。

**注意这里不能直接把 Gson 的 `JsonArray` 节点喂给 `asList`**：`asList`（`:700-703`）第一行是
`require(value instanceof List<?>, "expected a list but was " + value)` 然后才强转，而 `JsonArray`
**不是** `java.util.List`，直接传会在运行期抛 `AssertionError`。必须先反序列化成真正的 `ArrayList`：

```java
            require(!asList(GSON.fromJson(GSON.toJsonTree(schedule.getData("schedule"))
                            .getAsJsonObject().get("entries"), List.class)).isEmpty(),
                    "beta schedule must contain the newly selected meeting");
```

自查：

```bash
grep -rn 'getData("schedule")' VCampusServer/test/integration/
```

结果里只剩教师侧 `LOAD_TEACHING_SCHEDULE` 的三处（`TeacherScheduleSocketEndToEndTest:586`、`TeacherAdjustmentSocketEndToEndTest:712`、`TeacherCourseWorkflowEndToEndTest:1005`），才算改完。

- [ ] **Step 7: 跑测试确认转绿**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -WithTcp -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `service.CourseQueryMySqlTest`、`dao.CourseQueryMappingTest`、`service.CourseConflictMySqlTest`，以及 Step 6 改到的两个 Tcp 闭环（`integration.ScheduleAdjustmentSocketEndToEndTest`、`integration.TeacherAdjustmentSocketEndToEndTest`）全 PASS。

`-WithTcp` 是本 Task 唯一一次跑自己的套件（两个类会各自重建受保护的测试架构、串行执行，慢是正常的）；**不要**顺手去跑 Applications 套件。

- [ ] **Step 8: 单独跑 handler 契约**

`handler.CourseHandlerTest` 此刻不在任何套件里（Task 8 会把它登记进 `Course` 套件，那之后改跑套件即可），先用直接调用跑（照 `docs/superpowers/plans/2026-09-12-admin-course-scheduling.md:132-133` 的既有配方）：

```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
# 编译产物在 .codex-tmp/teacher/Adjustment-<runId>/server，用它做 classpath 手工跑：
java -cp ".codex-tmp/teacher/Adjustment-<runId>/server;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusServer/lib/*;VCampusCommon/lib/*" handler.CourseHandlerTest
```
Expected: 打印 `Course handler test passed.`。若 `CourseHandlerTest` 里对 `schedule` 的取值方式假设了数组（`getData("schedule")` 后直接当 List 用），改成从对象里取 `entries`——这属于**同步契约**而非放宽断言。

---

### Task 3: Client —— 学生课表网格改为数据驱动

**为什么**：`ScheduleController` 的 `FIRST_PERIOD`/`LAST_PERIOD` 与 5 列写死是缺陷 1 的客户端一半。改法与 `TeacherScheduleController.rebuildGrid()`（`:357-412`）对齐：行 = `periodNumbers(week.getPeriods())`，列 = `week.getDates()`。

**Files:**
- Create: `VCampusClient/src/model/course/ScheduleWeekView.java`
- Modify: `VCampusClient/src/controller/ScheduleController.java`
- Modify: `VCampusClient/src/service/CourseService.java:52`
- Modify: `VCampusClient/src/service/SocketCourseService.java:183-195`
- Modify: `VCampusClient/src/service/MockCourseService.java:259-280`
- Modify: `VCampusClient/src/resources/fxml/ScheduleView.fxml:35-40`（**原计划漏了这个文件**——见 Step 2 末段：那 6 条写死的列约束必须删掉，否则"星期列也服务端驱动"这半边没有真正交付）
- Test: `VCampusClient/test/controller/ScheduleControllerTest.java`（Timetable 套件）
- Test: `VCampusClient/test/service/SocketCourseServiceTest.java`、`MockCourseServiceTest.java`、`MockCourseScheduleTest.java`（**均不在套件**，Task 8 登记）
- Test fakes 需同步改签名：`CourseSelectionControllerTest:498`、`GradeControllerTest:198`、`TrainingPlanControllerTest:243`、`CourseManagementControllerTest:139`、`CoursePushCoordinatorTest:403`

**Interfaces:**
- Consumes: Task 1 的 `CourseScheduleWeekDTO` / `CourseCalendarDateDTO` / `CoursePeriodDTO`
- Produces:
  - `public ScheduleWeekView(int week, List<CourseCalendarDateDTO> dates, List<CoursePeriodDTO> periods, List<ScheduleEntryView> entries)`；getter `getWeek()/getDates()/getPeriods()/getEntries()`
  - `CompletableFuture<ScheduleWeekView> loadSchedule(CourseTermView term, int week)`（`CourseService`）

- [ ] **Step 1: 写 `ScheduleWeekView`**

与 `CourseScheduleWeekDTO` 同形，但 `entries` 是 `ScheduleEntryView`；`dates`/`periods` **直接持有 Common 的 DTO**，不另建视图类型——教师端 `TeacherScheduleController` 就是直接消费 `TeacherPeriodDTO`/`TeacherCalendarDateDTO` 的（`:358`、`:369`），学生端照此。同样做防御性不可变复制。

- [ ] **Step 2: 改 `ScheduleController` 的网格几何**

删除 `FIRST_PERIOD`/`LAST_PERIOD` 两个常量。把 `renderSchedule(List<ScheduleEntryView>)` 换成接受 `ScheduleWeekView`，算法逐条对齐教师端：

```java
    /** 本周节次行的编号：响应里出现过的节次（按教学日模板可不同）的升序并集。 */
    static List<Integer> periodNumbers(List<CoursePeriodDTO> periods) {
        List<Integer> numbers = new ArrayList<>();
        if (periods == null) return List.of();
        for (CoursePeriodDTO period : periods) {
            if (period == null || numbers.contains(period.getPeriod())) continue;
            numbers.add(period.getPeriod());
        }
        numbers.sort(Comparator.naturalOrder());
        return List.copyOf(numbers);
    }

    /** 节次行头：{@code 第 N 节} 加上该节次的时间区间（原样使用 DTO 的定宽 HH:mm:ss）。 */
    static String periodHeader(int period, CoursePeriodDTO definition) {
        String header = "第 " + period + " 节";
        if (definition == null || definition.getStartTime() == null
                || definition.getEndTime() == null) {
            return header;
        }
        return header + " " + definition.getStartTime() + "-" + definition.getEndTime();
    }

    /** 日期列头：星期名 + 该日期的 MM-dd；非教学日照样成列。 */
    static String dayHeader(CourseCalendarDateDTO date) {
        if (date == null) return "";
        String value = date.getDate() == null ? "" : date.getDate();
        return weekdayName(date.getTeachingWeekday())
                + (value.length() > 5 ? " " + value.substring(value.length() - 5) : "");
    }

    private static String weekdayName(int teachingWeekday) {
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return teachingWeekday >= 1 && teachingWeekday <= 7
                ? weekdays[teachingWeekday - 1] : "周" + teachingWeekday;
    }

    /** 节次 → 网格行号（表头占第 0 行）。与教师端 `:538-544` 逐字同构。 */
    private int rowIndex(int period) {
        for (int index = 0; index < periodRows.size(); index++) {
            if (periodRows.get(index) >= period) return index + 1;
        }
        return periodRows.size();
    }
```

`renderSchedule` 重写为：清空 → 建 `periodRows` → 第 0 行表头（`"节次"` + 每个 date 的 `dayHeader`）→ 每个节次一行（行头 `periodHeader`，每列一个 `addGridCell`）→ 按 `date.getTeachingWeekday()` 调 `ScheduleLayout.layoutDay(entries, weekday, firstPeriod, lastPeriod)`，`firstPeriod`/`lastPeriod` 取 `periodRows` 的首尾。列坐标 = `column + 1`。

**注意 `ScheduleLayout` 本身不用改**（它的 `layoutDay(entries, dayOfWeek, firstPeriod, lastPeriod)` 本来就接受任意边界），只是不再传 `FIRST_PERIOD/LAST_PERIOD`。

**边界**：`periodRows` 为空（该周没有节次定义）时直接清空网格并返回，不要画出只有表头的空表。

> **2026-09-17 更正（Task 3 review 发现这里的事实陈述是错的）**：原句写着"教师端 `:414-417` 就是这个处理"——**不成立**。教师端 `:414-417` 确实在 `periods` 为空时 `return`，但它在 `return` **之前**已经把表头行画完了（`:395-417` 先建行约束、再 `addLabel("节次")` 与星期表头，最后才早退），所以教师端留下的是**一张只有表头的空表**。学生端按本条要求**提前**在画任何东西之前就返回（`ScheduleController.java:190-193`），两边因此**有意不同**。
>
> **执行者的选择是对的，不要把学生端"改回"与教师端一致。** 登记这条只是为了让后来读这两段代码的人知道差异是刻意的，而不是一次漏改。让两个方法长得不像的那个人是教师端。

**`rowIndex` 必须与教师端 `:538-544` 逐字同义**：用 `>=` 找第一个不小于 `period` 的节次行，**末尾 `return periodRows.size()` 而不是 `-1`**。两处差别都是要命的——`-1` 会被当成网格行号传进 `scheduleGrid.add(node, column, -1)`，JavaFX 直接抛 `IllegalArgumentException`；而 `==` 在节次字典**有缺口**（例如只有 1、2、4 节）且某条安排在缺口那节时找不到行。教师端用 `>=` + 夹紧到 `size()` 正是为了容忍这种数据，学生端照抄即可。

**列约束必须由控制器按 `dates.size()` 重建（原计划漏掉的一半）。** 这个 Task 做的是"节次行 **+ 星期列**都服务端驱动"，但 `ScheduleView.fxml:35-40` 把列约束写死成 1 条表头列 + **5** 条日期列，而 `ScheduleController` 只清了 `getChildren()` 与 `getRowConstraints()`，从没碰过 `getColumnConstraints()`。后果两个方向都是错的：`dates` 多于 5 天时第 6 列起没有任何约束、不 `hgrow`、宽度与对齐全垮；少于 5 天时那几条空约束**仍然撐出一列**，表格右侧多一条空白列。

照教师端做：`TeacherScheduleView.fxml:37` 的 `GridPane` 是**裸的、一条 `ColumnConstraints` 都不声明**，全部在 `TeacherScheduleController:373-383` 里重建——`getColumnConstraints().clear()` → 加 1 条定宽表头列 → `for (int column = 0; column < dates.size(); column++)` 加一条 `hgrow=ALWAYS` 的日期列。学生端同形（常量名按 `ScheduleController` 既有的来；教师端是 `PERIOD_COLUMN_WIDTH` / `DAY_COLUMN_MIN_WIDTH` / `DAY_COLUMN_PREF_WIDTH`，见 `:78`、`:85-86`）。**`ScheduleLayout` 仍然不改。**

`:341-360` 的 `detailText` 里 `weekdayName(entry.getDayOfWeek())` 现在认 1..7 了，不用改。

- [ ] **Step 3: 改 service 三件套与所有 fake**

`CourseService.loadSchedule` 返回 `CompletableFuture<ScheduleWeekView>`；`SocketCourseService` 改为读 `schedule` 对象（用 `SocketCourseService` 里既有的把 `data.<key>` 反序列化为类的方法，对照教师端 `SocketTeacherCourseService` 读 `TeacherScheduleWeekDTO` 的写法），把 entries 映射成 `ScheduleEntryView` 后组装 `ScheduleWeekView`；`MockCourseService` 补一份节次夹具（照它的 `scheduleTemplates` 风格，给 1..8 节与周一至周五的 `CourseCalendarDateDTO`）。六个测试 fake 只改返回类型与构造。

- [ ] **Step 4: 跑客户端课表测试**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Timetable -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `controller.ScheduleControllerTest`、`controller.ScheduleLayoutTest`、`service.SocketTeacherCourseServiceTest`、`service.MockTeacherCourseServiceTest` 全 PASS。

**但那一次运行跑不到本 Task 最要紧的三个类**：`service.SocketCourseServiceTest`、`service.MockCourseServiceTest`、`service.MockCourseScheduleTest` 当前不在任何套件里（Task 8 才登记），而 `SocketCourseService` 读 `schedule` 对象的写法正是本 Task 的核心改动——只靠编译通过，等于没验证。用与 Task 2 Step 8 同一条手工配方单独跑它们（三个类都有 `main`，全是离屏测试，不碰数据库）：

```bash
java -cp ".codex-tmp/teacher/Timetable-<runId>/client;.codex-tmp/teacher/Timetable-<runId>/common;VCampusClient/lib/*;VCampusClient/src" service.SocketCourseServiceTest
java -cp ".codex-tmp/teacher/Timetable-<runId>/client;.codex-tmp/teacher/Timetable-<runId>/common;VCampusClient/lib/*;VCampusClient/src" service.MockCourseServiceTest
java -cp ".codex-tmp/teacher/Timetable-<runId>/client;.codex-tmp/teacher/Timetable-<runId>/common;VCampusClient/lib/*;VCampusClient/src" service.MockCourseScheduleTest
```

`<runId>` 用上面那次 Timetable 运行打印在 `Output:` 行里的目录名。Expected: 三个都打印各自的通过信息、无异常。

- [ ] **Step 5: 加一条"两边行数一致"的回归断言**

在 `ScheduleControllerTest` 里加一个方法，钉住"节次行来自字典而不是常量"：

```java
    static void periodRowsComeFromTheDictionary() {
        List<Integer> rows = ScheduleController.periodNumbers(List.of(
                new CoursePeriodDTO("2026-09-14", 1, "08:00:00", "08:45:00"),
                new CoursePeriodDTO("2026-09-14", 2, "08:55:00", "09:40:00"),
                new CoursePeriodDTO("2026-09-15", 1, "08:00:00", "08:45:00"),
                new CoursePeriodDTO("2026-09-15", 2, "08:55:00", "09:40:00")));
        require(rows.equals(List.of(1, 2)),
                "两天的同一节次必须并成一行，实际 " + rows);
        require(ScheduleController.periodNumbers(List.of()).isEmpty(),
                "没有节次字典时不得回退到硬编码 13 行");
        require(ScheduleController.periodHeader(3,
                        new CoursePeriodDTO("2026-09-14", 3, "10:00:00", "10:45:00"))
                        .equals("第 3 节 10:00:00-10:45:00"),
                "节次行头必须带上定宽时刻");
    }
```

- [ ] **Step 6: 跑该测试确认通过**

Run: 同 Step 4。
Expected: 新增断言 PASS。

---

### Task 4: Server —— 管理员搜索学生：学号必须支持前缀匹配

> **这一条与最初的口头结论不同，务必按本节执行。** 第一轮只读侦察把病因判成"学籍档案过滤把没档案的学生挡掉了"，直接查库后被**证伪**：那个过滤是系统级不变量，动它会破坏一条现在是绿的既有测试。下面是复核后的事实与真正该改的地方。

**为什么（已用真库复核）**：`AdminEnrollmentDAO.SEARCH`（`:25`）是
```java
" AND (?='' OR u.UID=? OR u.name LIKE ? ESCAPE '=')"
```
**学号是全等匹配，只有姓名是 LIKE。** 而客户端 `AddOfferingStudentDialogController.java:170-177` 在关键词为空时直接返回并提示"请输入学号或姓名"——也就是说管理员**必须先打字**。于是在真库上：

| 输入 | 命中 |
|---|---|
| `张`（姓名片段） | 1 ✓ |
| `213242789`（完整学号） | 1 ✓ |
| `2132427`（学号前缀） | **0 ✗** |

管理员最自然的动作就是敲学号前缀，得到的是"没有学生"。这与用户报的"找不到任何学生"完全吻合。修法：让学号也走 LIKE。

**不要动 `sap.status='ACTIVE'`。** 这不是搜索的 bug，是贯穿整个选课设计的"必须有 ACTIVE 学籍档案才可操作学生"不变量，证据有四条：
- `AdminEnrollmentMySqlTest.java:555` 专门插了一个 `role=2` 且**没有档案**的 `ae2-no-profile`，`:94`+`:101` 断言搜索总数**恰为 3**、文案写死"exclude non-students/**inactive** profiles"——放宽过滤会让它立刻变红；
- 同文件 `:209-211` 断言 `previewAdminEnrollment` 对 `ae2-no-profile` 返回 `INVALID_STUDENT_STATUS` **BLOCKING**；
- `CourseSelectionDAO.java:193`、`CourseQueryDAO.java:25`、`CourseWaitlistDAO.java:15` 全部 `JOIN student_academic_profile`；
- `CourseAcademicDAO.java:61` 同理。

副作用（**本次不修，只登记**）：一个只跑 `init.sql`、没有任何 `student_academic_profile` 行的库上，搜索会返回 0 —— 因为档案表**没有任何生产写入路径**（全 `src` 只有读，没有 `INSERT INTO student_academic_profile`）。那是数据供给缺口，不是查询缺陷；演示库有 7/9 个学生带档案，所以修好学号匹配后功能即可用。真要根治得单独安排（注册链路补写档案 / 迁移回填），**不要塞进这个任务**。

**Files:**
- Modify: `VCampusServer/src/dao/AdminEnrollmentDAO.java:25`、`:278-282`
- Test: `VCampusServer/test/service/AdminEnrollmentMySqlTest.java:79-113`（`verifySearchAndPagination`，GradeBook 套件 `.Server`）
- Test: `VCampusServer/test/handler/AdminEnrollmentHandlerTest.java`（**不在套件**，Task 8 登记）

**Interfaces:**
- Produces: 无新签名，只改 `AdminEnrollmentDAO` 内部语义。`SEARCH` 同时被 `searchStudents`（`:44`）与 `listOfferingStudents`（`:65`）使用，两处一起受益；`STUDENT_FROM` 与 `WHERE ... sap.status='ACTIVE'` **保持原样**。

- [ ] **Step 1: 写失败的测试**

在 `AdminEnrollmentMySqlTest.verifySearchAndPagination` 末尾（`:104` 那一带，紧跟既有的整名断言 `:102-103` 之后）加断言。注意用既有常量 `A`/`B`/`C`，它们的 UID 形如 `ae2-a`；为了测前缀，先加一个长学号夹具更贴真实——在 `fixtures()` 的 `:551-555` 那几行 `user(...)` 旁边补一条：

```java
        user(D, "Delta Student Nine", 2, true, "ACTIVE");
```

> **名字必须叫 `"Delta Student Nine"` 这一类，绝不能用 `"Enrollment Student Delta"`。** 这不是风格问题：既有夹具族**故意**全叫 `"Enrollment Student *"`——连 `TEACHER`（`"Enrollment Student Teacher"`，role=1）、`ae2-suspended`（`"Enrollment Student Suspended"`）、`ae2-no-profile`（`"Enrollment Student Missing"`）都算在里面。`:93` 那个查询是 `searchStudents("Enrollment Student", ...)`，按**姓名**匹配，能命中 5 行，最后只剩 3 行——`:94`/`:101` 的 `== 3` **就是这个 ACTIVE 门禁过滤的证据本身**。
>
> 如果新夹具叫 `"Enrollment Student Delta"` 且 `role=2`+`ACTIVE`，它会成为第 4 个存活行，`:94`/`:101` 立刻变红，而且**红的原因和"放宽了学籍过滤"长得一模一样**——两件事再也分不开。所以把 D 放在这个族外面：它对 `"Enrollment Student"` 查询不可见（三条既有断言一个字都不用改），但对 `21324` 这个学号前缀可见（新断言成立）。
并在类常量区（`:39-41` 附近）加 `private static final String D = "213242798";`，同时把 `cleanup()` 的 `:651-652` 那条 `DELETE FROM tbl_user WHERE UID IN (?,?,?,?,?,?,?,?)` 改成 9 个占位符并在参数里补上 `D`——**漏改这里会让夹具泄漏到下一个测试**。然后在 `verifySearchAndPagination` 里加：

```java
        require(service.searchStudents(D.substring(0, 5), 1, 10).getItems().stream()
                        .anyMatch(item -> item.getUid().equals(D)),
                "a student-ID prefix must find its student, not just an exact ID");
        require(service.searchStudents(D, 1, 10).getTotalCount() == 1,
                "the full student ID must keep working");
        require(service.searchStudents(D + "9", 1, 10).getTotalCount() == 0,
                "a non-matching ID must stay empty");
```

（前 5 位 `21324` 故意同时命不中任何姓名，确保测的是学号那一支。）

> **`2026-09-17 Task 4 review 后改写：断言从"恰好命中 1 条"改成"命中里含 `D`"。** 原写法是
> `getTotalCount() == 1 && getItems().get(0).getUid().equals(D)`，它有两个毛病：
>
> 1. **它断言的是本功能并不承诺的东西。** 前缀搜索的语义就是"学号以 `21324` 开头的学生全都返回"。
>    库里真有 5 个这样的学生时，返回 5 条是**正确行为**，`== 1` 却会判它失败——而且失败信息
>    （`"a student-ID prefix must find its student…"`）会把矛头指向 DAO，尽管 DAO 一个字没改。
> 2. **它依赖一份任何已跟踪 SQL 都复现不出来的库状态。** 现库里的 6 个 `21324*` 常驻学生
>    **没有**学籍档案，所以被 ACTIVE 门挡住，可见命中才只剩 `D`；但 `docs/数据库构建.md:126-133`
>    记载的建库流程正是把 `seed-course-demo.sql` 灌进 `virtual_campus_course_test`，而那份种子的
>    `:518-527` **恰好给 `213242789`/`213242790`/`213242791`/`213242793` 四条插了 ACTIVE 档案**。
>    照文档建一次库，这条断言就会红成"DAO 坏了"的样子——而排查它要花的时间，正是本计划最不该浪费的。
>
> 改成"结果集里含 `D`"之后：**RED 依然成立**（改 DAO 之前前缀返回 0 条，`anyMatch` 为假），
> 而且与常驻学生有没有档案**完全解耦**。同时它把原写法里"调两次 `searchStudents`"的毛病一并去掉
> （两次调用之间结果集理论上可以变，失败信息会指错方向）。
>
> **顺带保留一个有用的事实**（复核后仍然成立）：受保护库里那 9 个常驻学生
> `213242789`…`213242794`、`223242801`、`223242802`、`233242815` **现在都没有学籍档案**，所以
> `sap.status='ACTIVE'` 那道 JOIN 把他们全挡在搜索之外。手动查库看到 6 个 `21324*` 学生时不必惊慌：
> 它们搜不到。**这条不变量本身一个字都不要动。**

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite GradeBook -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 新断言 FAIL（学号前缀 `getTotalCount()` 为 0）；同套件其它类应仍绿。

- [ ] **Step 3: 改 `SEARCH` 常量**

只动 `:25` 这一行——把 `u.UID=?` 换成 `u.UID LIKE ? ESCAPE '='`，学籍过滤一个字都不碰：

```java
    private static final String SEARCH = " AND (?='' OR u.UID LIKE ? ESCAPE '=' OR u.name LIKE ? ESCAPE '=')";
```

- [ ] **Step 4: 改 `bindSearch` 绑定 LIKE 模式**

第 2 个占位符现在也是 `LIKE` 了，必须绑 `%…%`。`=` 是这两个 LIKE 的转义符，`bindSearch` 既有的替换链原样保留：

```java
    private static void bindSearch(PreparedStatement statement, int first, String query) throws SQLException {
        String pattern = "%" + query.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
        statement.setString(first, query);
        statement.setString(first + 1, pattern);
        statement.setString(first + 2, pattern);
    }
```

三个绑定一个都不能少，也不能改顺序：SQL 里三个占位符依次是 `?=''`、`u.UID LIKE ?`、`u.name LIKE ?`。用 `%…%` 而非 `query%`，是为了与本仓库其余学号搜索一致（`UserDAO.java:352`、`AdminScheduleDAO.java:43`、`TeacherCourseQueryDAO.java:257` 一律 `LIKE '%kw%'`）。

**保持住 `WHERE ... AND sap.status='ACTIVE'` 不变**——见本节开头的四条证据。同时**不要**去改 `listOfferingStudents` 的 `:67`：它列的是已经选上这个教学班的人，与"能不能招进来"是两回事。

- [ ] **Step 5: 跑测试确认转绿**

Run: 同 Step 2。
Expected: `service.AdminEnrollmentMySqlTest` 全 PASS —— **特别是 `:94`/`:101` 那两条"总数为 3、排除 inactive profiles"的既有断言必须仍然绿**。

若这两条变红，按下面的顺序排查，**不要**直接回退 Step 3/4：

1. **先看是不是 `D` 的名字问题**（Step 1 那个警告）。若 `== 3` 变成 `== 4`、且 `searchStudents(D, ...)` 是绿的，那就是 `D` 混进了 `"Enrollment Student"` 这个名字族——把它的名字改成族外的（如 `"Delta Student Nine"`），**不是**去改 `== 3`。
2. 只有当 `== 3` 变成 **小于 3**、或 `ae2-no-profile`/`ae2-suspended` 出现在了结果里，才是真的放宽了学籍过滤，那才回退 Step 3/4。

**`== 3` 这个数字本身永远不许改**：它是"存在 ACTIVE 学籍档案才可操作"这条不变量的唯一直接证据（见本节开头四条证据），改成 4 等于把这条证据悄悄删掉，而过滤已经坏掉也照样全绿。

GradeBook 套件其余类不受影响。

---

### Task 5: Server —— 读方案不再跑发布校验

**为什么**：见"背景 · 缺陷 3(a)"。读接口不能执行写门禁。但**发布时的拒绝必须保留**——一条缺教师的安排本来就该拦住发布（`PublishSchedulePlan` 的前置校验也要一并带上），否则我们会把一个真实的发布校验悄悄删掉。

**Files:**
- Modify: `VCampusServer/src/service/CourseConflictService.java:156-196`
- Modify: `VCampusServer/src/service/ScheduleManagementService.java:96`、`:275`
- Test: `VCampusServer/test/service/ScheduleManagementMySqlTest.java`（**不在套件**，Task 8 登记）
- Test: `VCampusServer/test/service/CourseConflictMySqlTest.java`（Adjustment 套件）

**Interfaces:**
- Produces:
  - `public List<ScheduleConflictDTO> checkPlan(Connection connection, long planId)` —— **语义改变**：遇到无法构成候选（缺教师或无时间段）的安排**跳过**，不再抛异常
  - `public void requirePublishable(Connection connection, long planId) throws SQLException` —— 发布门：任一条不完整即抛 `IllegalArgumentException("教学安排缺少任课教师或时间段，无法发布")`
  - `public void requirePublishable(long planId)` —— 自建连接的公开重载，与既有 `checkPlan(long)`（`:157-163`）同形（同样的 `DatabaseException` 包装）。`CourseConflictMySqlTest` 只拿得到 1 参形式；没有它，那条发布门断言就只能被删掉或放宽

- [ ] **Step 1: 写失败的测试**

在 `ScheduleManagementMySqlTest` 里加两个用例：
```java
    // 用例 A：方案里放入一条 teacher 为 NULL 的安排，loadPlan 必须成功返回，
    //         且返回的 arrangements 里那条仍在（客户端要用 "待定" 渲染它）。
    // 用例 B：同一方案调 publish 必须仍然被拒绝，消息为
    //         "教学安排缺少任课教师或时间段，无法发布"。
```

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
（`ScheduleManagementMySqlTest` 不在套件，先用 Task 2 Step 7 的手工 `java -cp` 配方单独跑。）
Expected: 用例 A 此刻 FAIL（`loadPlan` 抛异常）。

- [ ] **Step 3: 拆开 `checkPlan`**

`CourseConflictService`：把现 `checkPlan(Connection, long)` 里的抛异常分支改为 `continue`，并抽出严格门：

```java
    /**
     * Full-plan effective check used by reads and by publication. An arrangement that cannot form a
     * candidate — no teacher, or no slots yet — is skipped rather than fatal: a plan that is being
     * edited, or merely displayed, may legitimately contain unfinished rows. Publication still
     * rejects them; see {@link #requirePublishable}.
     */
    public List<ScheduleConflictDTO> checkPlan(Connection connection, long planId)
            throws SQLException {
        AdminScheduleDAO.PlanRow plan = scheduleDAO.findPlan(connection, planId);
        if (plan == null) throw new IllegalArgumentException("排课方案不存在");
        AdminScheduleDAO.CalendarContext calendar =
                scheduleDAO.loadCalendar(connection, plan.calendarId());
        if (calendar == null) throw new IllegalArgumentException("教学日历不存在");
        List<ScheduleConflictDTO> conflicts = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ScheduleArrangementDTO arrangement : scheduleDAO.listArrangements(connection, planId,
                null)) {
            Candidate candidate = candidate(planId, arrangement);
            if (candidate == null) {
                continue;
            }
            for (ScheduleConflictDTO conflict : check(connection, candidate, calendar)) {
                add(conflicts, seen, conflict);
            }
        }
        return List.copyOf(conflicts);
    }

    /**
     * Publication gate: every arrangement must name a teacher and carry at least one slot. This is
     * the only place the incompleteness is fatal — {@link #checkPlan} deliberately tolerates it so
     * that reading a half-finished plan does not fail.
     */
    public void requirePublishable(Connection connection, long planId) throws SQLException {
        for (ScheduleArrangementDTO arrangement : scheduleDAO.listArrangements(connection, planId,
                null)) {
            if (candidate(planId, arrangement) == null) {
                throw new IllegalArgumentException("教学安排缺少任课教师或时间段，无法发布");
            }
        }
    }
```

注意 `candidate(...)`（`:187-196`）在没有任课教师或 `getSlots()` 为空时返回 `null`，所以"不完整"的判据一个字都没变，只是**从致命改成了跳过**。这条消息文本必须原样保留（`"教学安排缺少任课教师或时间段，无法发布"`）：既有测试和种子注释都引用它。

同时保留 `checkPlan(long planId)` 那个自建连接的公开重载（`:157-163`），但把它的 javadoc 从 `/** Full-plan effective check used before publication. */` 改掉——它已不再只服务发布，改成例如 `/** 读路径用的整方案有效检查；不完整的安排会被跳过。 */`。

再加一个与它同形的 1 参重载（同样的自建连接与 `DatabaseException` 包装）：

```java
    /** Publication gate for callers that are not already inside a transaction. */
    public void requirePublishable(long planId) {
        try (Connection connection = DBUtil.getConnection()) {
            requirePublishable(connection, planId);
        } catch (SQLException failure) {
            throw new DatabaseException("排课方案发布校验失败", failure);
        }
    }
```

**为什么必须有它**：`CourseConflictMySqlTest:191-194` 的 `verifySlotlessArrangementBlocksPublication` 现在调的是 1 参的 `conflicts.checkPlan(SLOTLESS_PLAN)`。Step 3 一改语义这条断言就会红，而它**必须继续被断言**，只是落点从 `checkPlan` 搬到 `requirePublishable`。没有这个重载，实现者面对一条红的测试，唯一能做的就是删断言或放宽它——那正好丢掉本 Task 明说要保留的发布门。

**迁移那条断言（不是删掉它）**：把 `CourseConflictMySqlTest:191-194` 改成

```java
    private static void verifySlotlessArrangementBlocksPublication(CourseConflictService conflicts) {
        // 读路径容忍不完整的安排，发布门不容忍——两半语义各自钉一条。
        require(conflicts.checkPlan(SLOTLESS_PLAN).isEmpty(),
                "a slotless arrangement must be skipped, not fatal, on the read path");
        expect(IllegalArgumentException.class, () -> conflicts.requirePublishable(SLOTLESS_PLAN),
                "an arrangement with no slots must not be invisible to the publication gate");
    }
```

原来的消息文本一个字不改，`require` / `expect` 两个既有 helper 照用。**Step 5 的 Expected 里那句「`CourseConflictMySqlTest` 全 PASS（证明既有冲突分类没被改动）」说的是其余十条 `verify*`——它们确实一个字都不动；这一条是语义迁移，不是"既有冲突分类被改动"。**

- [ ] **Step 4: 在 `publish` 里补回严格门**

`ScheduleManagementService.publish` 的 mutation 内，在 `List<ScheduleConflictDTO> found = conflicts.checkPlan(connection, id);`（`:275`）**之前**插入：

```java
            conflicts.requirePublishable(connection, id);
```

`loadPlan`（`:96`）保持调用 `conflicts.checkPlan(connection, plan.planId())` 不变——它的语义已经变宽松了。

- [ ] **Step 5: 跑测试确认转绿**

Run: 同 Step 2（含 `Adjustment` 套件里的 `service.CourseConflictMySqlTest`）。
Expected: 用例 A、B 均 PASS；`CourseConflictMySqlTest` 全 PASS。注意其中 `verifySlotlessArrangementBlocksPublication` 是 Step 3 迁移过的**两半断言**（读路径不抛 / 发布门仍抛）——它红说明迁移没做对，**不要**靠删断言或放宽它来"转绿"；其余十条 `verify*` 一个字未动，那才是"既有冲突分类没被改动"的证据。

---

### Task 6: Common + Server —— 新增「创建草稿方案」

**为什么**：见"背景 · 缺陷 3(b)"。这是本计划唯一的新功能。范围经用户确认：**可从已发布方案复制**——即新建的草稿把该学期当前正式方案的教学安排连规则、周次、课次（occurrence）与资源预订一起复制过来，复制后即可编辑。

**Files:**
- Modify: `VCampusCommon/src/dto/course/admin/AdminCourseActions.java`
- Modify: `VCampusCommon/test/dto/course/admin/AdminCatalogDtoJsonTest.java:388-432`
- Modify: `VCampusServer/src/dao/AdminScheduleDAO.java`
- Modify: `VCampusServer/src/service/ScheduleManagementService.java`
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java:159-179`
- Test: `VCampusServer/test/handler/AdminScheduleHandlerTest.java`（**不在套件**，Task 8 登记）
- Test: `VCampusServer/test/service/ScheduleManagementMySqlTest.java`（**不在套件**，Task 8 登记）

**Interfaces:**
- Produces:
  - `AdminCourseActions.CREATE_SCHEDULE_PLAN = "createSchedulePlan"`
  - `public AdminOperationResultDTO<SchedulePlanDTO> createDraftPlan(String adminUid, int academicYear, int semester, boolean copyPublished, String operationId)`
  - request 数据键：`academicYear`、`semester`、`copyPublished`、`operationId`；**响应键 `result`**——这是写路径，必须走既有的 `mutation(response, result)`（`AdminCourseHandler.java:251-256`，它 `putData("result", result)`）。**不要**照 `LOAD_SCHEDULE_PLAN` 的 `plan` 键（`:162`）走，那是读路径的 `response.putData("plan", ...)`，两者不是一回事

- [ ] **Step 1: 写失败的契约测试**

在 `AdminCatalogDtoJsonTest.exposesExactAdminCourseActions()` 的 `expected` map 里加 `expected.put("CREATE_SCHEDULE_PLAN", "createSchedulePlan");`，并把 `require(actual.size() == 28, ...)` 改成 `29`、消息同步改。

- [ ] **Step 2: 跑测试确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
# 该 Common 测试不在套件，手工跑（配方见 2026-09-12-admin-course-catalog.md:220）：
java -cp ".codex-tmp/teacher/Adjustment-<runId>/common-test;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusCommon/lib/*" dto.course.admin.AdminCatalogDtoJsonTest
```
Expected: FAIL（实际 28 个，期望 29）。

- [ ] **Step 3: 加常量**

`AdminCourseActions` 里在 `PUBLISH_SCHEDULE_PLAN` 之后加：
```java
    public static final String CREATE_SCHEDULE_PLAN = "createSchedulePlan";
```
这是**新增**常量，不得改动任何既有常量的字面值（`CourseDtoJsonTest.exposesExactCourseActions` 与 `AdminCatalogDtoJsonTest` 都钉死了它们）。

- [ ] **Step 4: 加 DAO 方法**

`AdminScheduleDAO`：

加在 `// ------------------------------------------------------------------- plans` 段里（`findPlanByTerm` 之后、`lockPlan` 之前）。**注意没有 `CalendarRow` 这个类型**——`loadCalendar` 返回的是 `AdminScheduleDAO.CalendarContext`（`:659-696`），别去造一个新 record：

```java
    /** 该学期最新的教学日历 id；null 表示该学期尚未创建教学日历，排课无处容身。 */
    public Long findCalendarIdByTerm(Connection connection, int academicYear, int semester)
            throws SQLException {
        String sql = "SELECT id FROM teaching_calendar WHERE academic_year=? AND semester=?"
                + " ORDER BY version DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, academicYear);
            statement.setInt(2, semester);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 该日历下已发布的方案 id；null 表示还没有可复制的来源。 */
    public Long findPublishedPlanId(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT id FROM schedule_plan WHERE calendar_id=? AND status='PUBLISHED'"
                + " ORDER BY revision DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 同一日历下已有的草稿方案 id；用于"已有草稿则拒绝重复创建"。 */
    public Long findDraftPlanId(Connection connection, long calendarId) throws SQLException {
        String sql = "SELECT id FROM schedule_plan WHERE calendar_id=? AND status='DRAFT'"
                + " ORDER BY revision DESC,id DESC LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    /** 同名方案的下一个可用修订号；唯一键是 (calendar_id, name, revision)。 */
    public int nextRevision(Connection connection, long calendarId, String name)
            throws SQLException {
        String sql = "SELECT COALESCE(MAX(revision),0)+1 FROM schedule_plan"
                + " WHERE calendar_id=? AND name=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, calendarId);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    public long insertPlan(Connection connection, String name, long calendarId, int revision,
                           String createdBy) throws SQLException {
        String sql = "INSERT INTO schedule_plan(name,calendar_id,revision,status,created_by)"
                + " VALUES(?,?,?,'DRAFT',?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setLong(2, calendarId);
            statement.setInt(3, revision);
            statement.setString(4, createdBy);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }
```

**不要**在这个事务里碰 `teaching_calendar.current_schedule_plan_id`：`TeacherScheduleDAO.currentCalendar`（`:86-107`）要求该指针非空且指向本日历的 **PUBLISHED** 方案，草稿改了指针会让教师课表当场变空。

- [ ] **Step 5: 加 service 方法**

在 `ScheduleManagementService` 的 `// ----------------------------------------------------------------- writes` 段里、`publish` 之后加。`execute(...)`（`:462-509`）是**本类的私有方法**，签名 `execute(adminUid, operationId, action, Object request, Type resultType, Lock lock, Mutation<T> mutation)`；`Outcome<T>` 是 `(result, targetType, targetId, conflicts, forced, overrideReason)`（`:545-548`）；`PLAN_TARGET`/`OK`/`DRAFT`/`PLAN_RESULT_TYPE`/`planDTO`/`writeChildren` 都已存在，直接用：

```java
    public AdminOperationResultDTO<SchedulePlanDTO> createDraftPlan(String adminUid, int academicYear,
                                                                    int semester, boolean copyPublished,
                                                                    String operationId) {
        if (academicYear <= 0) throw new IllegalArgumentException("学年无效");
        if (semester < 1 || semester > 3) throw new IllegalArgumentException("学期无效");
        Lock lock = connection -> {
            Long calendarId = scheduleDAO.findCalendarIdByTerm(connection, academicYear, semester);
            if (calendarId != null) scheduleDAO.lockCalendar(connection, calendarId);
        };
        Mutation<SchedulePlanDTO> mutation = connection -> {
            Long calendarId = scheduleDAO.findCalendarIdByTerm(connection, academicYear, semester);
            if (calendarId == null) throw new NotFoundException("该学期尚未创建教学日历");
            if (scheduleDAO.findDraftPlanId(connection, calendarId) != null) {
                throw new ConflictException("该学期已有草稿方案");
            }
            AdminScheduleDAO.CalendarContext calendar = scheduleDAO.loadCalendar(connection, calendarId);
            if (calendar == null) throw new NotFoundException("教学日历不存在");
            // Name is the term itself, so nextRevision lands on 1 the first time and the
            // (calendar_id,name,revision) unique key can never collide.
            String name = academicYear + "-" + (academicYear + 1) + " 学年"
                    + switch (semester) {
                        case 1 -> "第一学期";
                        case 2 -> "第二学期";
                        default -> "第三学期";
                    } + "排课方案";
            int revision = scheduleDAO.nextRevision(connection, calendarId, name);
            long planId = scheduleDAO.insertPlan(connection, name, calendarId, revision, adminUid);
            if (copyPublished) {
                Long sourcePlanId = scheduleDAO.findPublishedPlanId(connection, calendarId);
                if (sourcePlanId != null) {
                    copyArrangements(connection, sourcePlanId, planId, calendar, adminUid);
                }
            }
            AdminScheduleDAO.PlanRow created = scheduleDAO.findPlan(connection, planId);
            return new Outcome<>(new AdminOperationResultDTO<>(operationId, OK, "草稿方案已创建",
                    planDTO(connection, created, List.of()), List.of()), PLAN_TARGET,
                    Long.toString(planId), List.of(), false, null);
        };
        return execute(adminUid, operationId, AdminCourseActions.CREATE_SCHEDULE_PLAN,
                AdminOperationTransaction.termRequest(academicYear, semester, copyPublished),
                PLAN_RESULT_TYPE, lock, mutation);
    }

    /**
     * Copies every complete arrangement from {@code sourcePlanId} into {@code targetPlanId} by
     * re-running the same child writer {@code save} uses, so the copied {@code course_occurrence}
     * UTC windows are derived from the calendar exactly as a hand-edited arrangement would be.
     *
     * <p>Rows that cannot form a candidate — no teacher, or no slots — are skipped rather than
     * fatal. The demo seed's arrangement 4104 is deliberately teacher-less, and
     * {@code writeChildren} would hand a null business id to {@code ensureResource}. The skip also
     * keeps this consistent with the read path, which now tolerates exactly the same rows.
     *
     * <p>{@code writeChildren} reads only offeringId/teacherUid/assistantUid/classroomId/slots/
     * startWeek/endWeek off the candidate — never {@code arrangementId()}, which it takes as its own
     * parameter. Reusing the source row's candidate is therefore safe; do not "fix" it by passing the
     * source arrangement id into {@code writeChildren}.
     */
    private int copyArrangements(Connection connection, long sourcePlanId, long targetPlanId,
                                 AdminScheduleDAO.CalendarContext calendar, String adminUid)
            throws SQLException {
        int copied = 0;
        for (ScheduleArrangementDTO arrangement : scheduleDAO.listArrangements(connection,
                sourcePlanId, null)) {
            CourseConflictService.Candidate candidate =
                    CourseConflictService.candidate(sourcePlanId, arrangement);
            if (candidate == null) continue;
            long id = scheduleDAO.insertArrangement(connection, targetPlanId, candidate.offeringId(),
                    candidate.teacherUid(), candidate.assistantUid(), candidate.classroomId(),
                    adminUid);
            writeChildren(connection, id, targetPlanId, candidate, calendar);
            copied++;
        }
        return copied;
    }
```

配套两处小改：

1. **`CourseConflictService.candidate(long, ScheduleArrangementDTO)`（`:187`）从 `private static` 放宽成包级 `static`**——`ScheduleManagementService` 与它同在 `service` 包，去掉 `private` 即可复用；**不要**把这段判据抄第二份，抄了就会和读路径漂移。

2. **`AdminOperationTransaction` 加一个请求对象工厂**，位置紧挨 `targetRequest`（`:76-81`），照它的写法。这个对象同时喂给 `operationDAO.digest(action, request)` 和审计日志的 `request_json`，所以字段名要能自解释：

```java
    static Map<String, Object> termRequest(int academicYear, int semester, boolean copyPublished) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("academicYear", academicYear);
        request.put("semester", semester);
        request.put("copyPublished", copyPublished);
        return request;
    }
```

- [ ] **Step 6: 加 handler 分支**

`AdminCourseHandler.java` 的 switch 里，`PUBLISH_SCHEDULE_PLAN` 之后加：

```java
            case AdminCourseActions.CREATE_SCHEDULE_PLAN -> mutation(response,
                    scheduling().createDraftPlan(uid, integer(request, "academicYear"),
                            integer(request, "semester"), flag(request, "copyPublished"),
                            text(request, "operationId")));
```

`mutation(...)` 与 `flag(...)`/`integer(...)`/`text(...)` 都是该 handler 里已有的私有辅助方法（`:251`、`:524`、`:563`、`:601`）。开关落地点在 `:176-179` 的 `PUBLISH_SCHEDULE_PLAN` 分支之后。`mutation(...)` 把结果放在 `result` 键下，所以客户端要读的是 `data.result.data`。

- [ ] **Step 7: 跑契约与 handler 测试**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
# Common 契约（不在套件）
java -cp ".codex-tmp/teacher/Adjustment-<runId>/common-test;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusCommon/lib/*" dto.course.admin.AdminCatalogDtoJsonTest
# handler（不在套件）
java -cp ".codex-tmp/teacher/Adjustment-<runId>/server;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusServer/lib/*;VCampusCommon/lib/*" handler.AdminScheduleHandlerTest
```
Expected: 全部 PASS；`AdminScheduleHandlerTest` 里新增一条 `createSchedulePlan` 的键映射断言（响应键 `result`，与 `publishSchedulePlan` 同形）。

- [ ] **Step 8: 跑端到端**

在 `ScheduleManagementMySqlTest` 加用例：无草稿时 `createDraftPlan(..., true, opId)` 成功 → 新方案 `status=DRAFT` → 其安排数与已发布方案一致 → 用同一 `operationId` 重放返回同一结果（重放保护）。再断言 `teaching_calendar.current_schedule_plan_id` **未变**。

手工跑：`java -cp ".codex-tmp/teacher/Adjustment-<runId>/server;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusServer/lib/*;VCampusCommon/lib/*" service.ScheduleManagementMySqlTest`

---

### Task 7: Client —— 「排课」对话框的创建草稿入口

**为什么**：服务端有了能力，入口还得有。当前对话框在 `plan == null` 时整屏置灰（`editablePlan()` `:834-839`、`renderWriteControls()` 禁用所有写控件），用户看到的就是"加不了排课"。

**Files:**
- Modify: `VCampusClient/src/service/AdminCourseService.java`（加 `default` 方法）
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java`
- Modify: `VCampusClient/src/controller/ScheduleArrangementDialogController.java`
- Modify: `VCampusClient/src/resources/fxml/ScheduleArrangementDialog.fxml`
- Test: `VCampusClient/test/controller/ScheduleArrangementDialogControllerTest.java`（**不在套件**，Task 8 登记）
- Test: `VCampusClient/test/service/SocketAdminCourseServiceTest.java`、`MockAdminCourseServiceTest.java`（Adjustment 套件 `.Client`）

**Interfaces:**
- Consumes: Task 6 的 `createSchedulePlan` action
- Produces: `default CompletableFuture<AdminOperationResultView<SchedulePlanView>> createSchedulePlan(int academicYear, int semester, boolean copyPublished, String operationId)`，默认实现抛 `UnsupportedOperationException("createSchedulePlan")`——与 `AdminCourseService` 里其余排课方法同形，**因此既有 fake 不会被破坏**

> **2026-09-17 更正（Task 7 派发前实查）：返回值类型原写的是 `CompletableFuture<SchedulePlanView>`（无信封），与本文 Step 1 自相矛盾，已改为上面带信封的版本。**
>
> 实读 `AdminCourseService.java`：**该接口的写方法一律返回 `AdminOperationResultView<T>` 信封，读方法才返回裸 DTO。**
> 写：`addStudentToOffering` `:88`、`removeStudentFromOffering` `:93`、`saveArrangement` `:120`、
> `deleteArrangement` `:125`、`publishSchedulePlan` `:130`、`reviewAdjustmentRequest` `:164`——全部带信封。
> 读：`loadSchedulePlan` `:105`（`SchedulePlanDTO`）、`listScheduleResources` `:100` 等——全部裸露。
>
> `createSchedulePlan` 是**写**：服务端走 `mutation(...)`，返回 `AdminOperationResultDTO`，与 `publishSchedulePlan` 同形。
> 原文的无信封类型只与 `loadSchedulePlan` 的形状一致，而 Step 1 又要求"照既有 `publishSchedulePlan` 的解析代码抄"——
> 那句解析（`SocketAdminCourseService.java:334`）产出的正是信封 `planResult(read(response, "result", PLAN_RESULT_TYPE))`。
> **两条指示只有在带回信封时才彼此相容**，所以按信封改。调用方不受影响：Step 3 的 `handleCreateDraft`
> 拿到 `(created, failure)` 后并不读 `created`，成功即 `loadPlan()`。
> 附带好处：信封里的 `outcomeCode`/`message` 不会像原文那样被静默丢弃——这恰是本计划 Task 3/7 反复在修的同一类
> "把服务端信息丢掉"的毛病。

- [ ] **Step 1: 加 service 三件套**

`AdminCourseService` 加 `default` 方法（照 `loadSchedulePlan` 的写法）；`SocketAdminCourseService` 实现它：发 `CREATE_SCHEDULE_PLAN`，带 `academicYear`/`semester`/`copyPublished`/`operationId`，**读 `result` 键**（服务端 `mutation(...)` 把它放在 `result` 下，与 `publishSchedulePlan` 完全同形——照既有的 `publishSchedulePlan` 的解析代码抄，不要照 `loadSchedulePlan` 抄），取出 `AdminOperationResultDTO.getData()` 映射成 `SchedulePlanView`；`MockAdminCourseService` 实现一份内存版（照它既有的 DRAFT/PUBLISHED 夹具行为）。

`operationId` 用 `UUID.randomUUID().toString()`，与对话框里既有的 `lastSaveOperationId`/`lastPreviewOperationId` 同一约定。

- [ ] **Step 2: FXML 加入口**

在 `ScheduleArrangementDialog.fxml` 的方案区域加一个按钮（`fx:id="createDraftButton"`，`onAction="#handleCreateDraft"`，文案「创建草稿方案」），摆在现有方案信息标签旁边。**先打开 FXML 确认那一带的实际控件与 `fx:id`，别照抄计划里猜的名字。** 所有 `fx:id` 必须在控制器里有对应字段（`@FXML private Button createDraftButton;`）——但注意下面这条更正，**失败机制与原文说的不一样**。

> **2026-09-17 更正（Task 7 实现者实测 + 我复核）：原文说"否则 `FXMLLoader` 会直接抛 `LoadException`"，反了。**
>
> 实测：**`fx:id` 在控制器里没有对应字段时，`FXMLLoader` 是静默跳过、不抛异常的**；会抛 `LoadException` 的是解不出来的 `onAction`（找不到处理方法）。
> 也就是说这条风险的两个方向不对称：
>
> - `fx:id` → 控制器字段（`verifyBindings` 检查的方向）：缺字段**不崩**，本文件的 `fx:id="scheduleContentScroll"`（FXML `:29`）就是活证据——控制器里没有这个字段，而生产路径一直在加载它，从没报过错。
> - 控制器 `@FXML` 字段 → `fx:id`（**没有网兜的方向**）：FXML 里漏写 `fx:id` 时字段留 `null`，之后 NPE。这才是这条注释真正想防的那类事故。
> - `onAction` → 控制器方法：会抛，`verifyBindings` 检查这一项是有效的。
>
> 结论：`verifyBindings` 仍然值得加（它钉住 `onAction`，且反向也覆盖"字段改名但 FXML 没跟上"的半个面），但**它防的不是原文说的那个崩溃**；原文那句"MUST"因此不该被当作 `LoadException` 的依据。
> 同时原文附带的"`AdminCourseUiSmokeTest` 那类冒烟测试就是撞在这个上面"也是**未经验证的猜测**——既然缺字段不抛，它变红的原因就不是这条，不应再引用它当证据。

> **2026-09-17 补记：这个风险在本 Task 里没有任何自动网兜，必须自己织一个。**
>
> 实查过了：`ScheduleArrangementDialog.fxml` 全仓只有**两处**加载——生产路径
> `AdminCourseCatalogController.java`，以及 `VCampusClient/test/ui/AdminCourseUiSmokeTest.java`
> ——而后者正是 Global Constraints 里"既有 7 红"的成员、且**不在任何套件**里。而
> `ScheduleArrangementDialogControllerTest`（本 Task 要改的那个测试）**根本不加载该 FXML**
> （`grep -c "FXMLLoader\|getResourceAsStream\|ScheduleArrangementDialog.fxml"` 得 **0**），它只测控制器逻辑。
> 也就是说：`fx:id` 或 `onAction` 打错一个字，**没有任何一个能跑起来的测试会红**，直到有人手点开管理员的课程页才炸。
>
> **做法（照抄本仓库既有先例，不要自己发明）：** `VCampusClient/test/controller/TeacherGradeBookControllerTest.java`
> 里已经有一对现成的工具方法——`verifyBindings(Document view, Class<?> controller, String label)`
> （`:1112-1136`）遍历 FXML 的 DOM，对每个元素断言 `fx:id` 在控制器上**有对应字段**、`onAction` 有**对应处理方法**；
> 配 `parseView(String path)`（`:1138` 起）负责把 FXML 解析成 `Document`。整套只用 DOM + 反射，**不需要 JavaFX 工具包**，
> 因此可以放在 `ScheduleArrangementDialogControllerTest`（`Client` 列）里，也正好让 Task 8 新登记的这套件多一条真覆盖。
>
> 在 `ScheduleArrangementDialogControllerTest` 里加一条用例，对 `ScheduleArrangementDialog.fxml` 调一次
> `verifyBindings`，并**额外断言新按钮确实存在**（`fx:id="createDraftButton"` 且 `onAction="#handleCreateDraft"`）——
> 光有 `verifyBindings` 只能证明"FXML 里写的东西都有对应成员"，证明不了"那个按钮真被加上了"。
>
> **已核实这条用例在本文件上不会因无关原因变红：** `ScheduleArrangementDialog.fxml` 里 `disabled` 属性 **0** 处
> （`verifyBindings` 明确禁止该只读属性）、`fx:id` **29** 处、`onAction` **9** 处，所以它自带的
> "`ids > 0 && actions > 0`"前置断言也满足。
>
> **万一它对既有元素报红**：那说明本文件与控制器之间**本来就**有一处 fx:id/onAction 对不上（该冒烟测试长期没人跑，
> 这正是这类债的典型形态）。那种情况下**只钉新按钮那一个元素**，并把查到的既有不匹配**写进报告，不要在本 Task 里修**
> ——它是既有债，不是本计划的缺陷。

- [ ] **Step 3: 控制器加处理器**

```java
    @FXML
    public void handleCreateDraft() {
        if (createDraftInFlight || closed) return;
        if (editablePlan() != null) return;          // 已有草稿就不重复创建
        createDraftInFlight = true;
        String operationId = UUID.randomUUID().toString();
        service.createSchedulePlan(offering.getAcademicYear(), offering.getSemester(),
                        plan != null, operationId)   // 该学期已有 PUBLISHED 方案时默认复制它
                .whenComplete((created, failure) -> fxExecutor.accept(() -> {
                    createDraftInFlight = false;
                    if (failure != null) {
                        localMessage = "创建草稿方案失败：" + failureText(failure);
                        render();
                        return;
                    }
                    loadPlan();                        // 重新加载，此时拿到的是 DRAFT，可编辑
                }));
    }
```

可见性：`createDraftButton` 只在 `plan == null || editablePlan() == null` 时可见（`render()` 里设置 `setVisible`/`setManaged`），避免在已是草稿时给出一个必然报错的按钮。

**顺带修掉一个真实的可诊断性缺陷**：`loadPlan` 的失败分支（`:266-289`）把服务端消息丢掉换成了 `"排课方案加载失败，请重试"`。改为把 `failure` 的真实消息保留下来。

> **2026-09-17 更正（Task 7 派发前实查，原文两处都不对，其中一处会让人写出重复代码）：**
> 原文说"用该控制器已有的把 `Throwable` 转文案的辅助方法，**若没有就加一个**剥 `CompletionException` 的小函数，
> 与 `ScheduleController.errorMessage`（`:367-374`）同形"。实查结果：
>
> 1. **`ScheduleController.errorMessage` 不在 `:367-374`**（那里是 `course-class-title` 的样式代码），它在 **`:470`**。
> 2. **更重要的：本控制器自己就有一对现成的辅助方法，不必新增任何一个函数。**
>    `ScheduleArrangementDialogController.rootCause(Throwable)`（`:1423`）已经在剥 `CompletionException`/`ExecutionException`，
>    `ScheduleArrangementDialogController.errorMessage(Throwable)`（`:1432`）就是 `cause.getMessage() == null ? 类名 : getMessage()`。
>    所以上面 Step 3 的代码片段里的 `failureText(failure)` 应当写成 **`errorMessage(failure)`**——
>    `failureText` 这个函数**不存在**，照抄片段会诱导实现者去加一个与 `:1432` 逐字重复的函数，
>    正好撞在 review rubric 的"verbatim duplication of a logic block"上。
>
> 结论：Step 3 的 `handleCreateDraft` 里把 `failureText` 读作 `errorMessage`，
> `loadPlan` 的失败分支同理直接用 `errorMessage(failure)`；**本 Task 不新增任何 Throwable 转换函数**。

- [ ] **Step 4: 加控制器测试**

`ScheduleArrangementDialogControllerTest` 的 harness 是"把方法名当参数、反射逐个跑"，所以**先打开该文件**照它的既有假 service（它已经能驱动 loadPlan 成功/失败两条路径）加四个 `static void` 用例，再把方法名登记进它的用例清单：

```java
    /** loadPlan 失败后仍必须给出创建草稿的出路，否则用户无路可走。 */
    static void createDraftIsOfferedWhenNoPlanLoaded() {
        // 假 service 的 loadSchedulePlan 返回 failedFuture
        // 断言：plan == null && createDraftButton.isVisible() && !createDraftButton.isDisabled()
    }

    /** 成功后要重新拉一次方案，否则界面仍停在"无方案"。 */
    static void createDraftReloadsThePlanOnSuccess() {
        // 假 service 计数 loadSchedulePlan 调用次数；createSchedulePlan 返回 succeededFuture
        // 断言：调用 handleCreateDraft 后 loadSchedulePlan 计数 +1
    }

    /** 已有可编辑草稿时不该再给一个必然报"该学期已有草稿方案"的按钮。 */
    static void createDraftIsHiddenWhenADraftIsEditable() {
        // 假 service 返回一个 status=DRAFT 的方案
        // 断言：!createDraftButton.isVisible()（或 isDisabled()，与 render() 的实现保持一致）
    }

    /** 丢掉服务端消息会让"缺失任课教师"这类真实原因永远看不见。 */
    static void loadPlanFailureSurfacesTheServerMessage() {
        // 假 service 的 loadSchedulePlan 以 message="教学安排缺少任课教师或时间段，无法发布" 失败
        // 断言：界面文案包含该消息，且不等于"排课方案加载失败，请重试"
    }
```

Run（classpath 照 Task 3 Step 7 的 `java -cp` 配方换成 client 测试产物）:
```bash
java -cp ".codex-tmp/teacher/Adjustment-<runId>/client-test;.codex-tmp/teacher/Adjustment-<runId>/client;.codex-tmp/teacher/Adjustment-<runId>/common;VCampusClient/lib/*;VCampusCommon/lib/*" controller.ScheduleArrangementDialogControllerTest createDraftIsOfferedWhenNoPlanLoaded createDraftReloadsThePlanOnSuccess createDraftIsHiddenWhenADraftIsEditable loadPlanFailureSurfacesTheServerMessage
```
Expected: 每个方法打印 `NAME: PASS`。

**JavaFX 说明**：本仓库的 UI 测试在这台机器上**可以**真起 toolkit（见 `project_build_env` 记忆：需 `-JavaFxHome` 指向完整 SDK）。`AdminCourseUiSmokeTest` 那类冒烟测试属于既有 7 红，跑不起来是预期的；这四个用例要写成**不需要真实窗口**的形态（沿用该文件既有 harness 的假 `fxExecutor`，它同步执行），不要引入新的 toolkit 依赖。

- [ ] **Step 5: 跑客户端服务测试**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `service.SocketAdminCourseServiceTest`、`service.MockAdminCourseServiceTest`、`controller.AdminApprovalControllerTest`、`controller.AdjustmentApprovalDialogControllerTest`、`controller.GradeApprovalControllerTest`、`controller.GradeApprovalDialogControllerTest` 全 PASS。

---

### Task 8: 测试编排 —— 新增 `Course` 套件并登记被改到的类

**为什么**：本计划改到的测试类里有 **13 个不在任何套件里**（原写 10 个，Task 3 执行中补记了漏数的三个，见 Step 1 的补记块），按仓库自己的话就是"写了却永远跑不到，等于一个不能失败的测试"（`scripts/test-teacher.ps1:149-151`）。其中 `handler.AdminScheduleHandlerTest` 和 `service.ScheduleManagementMySqlTest` 正是覆盖"创建草稿方案"的回归——不登记，这次修复就没有回归保护。

**Files:**
- Modify: `scripts/test-teacher.ps1:68-341`（`$suites` 数组，在末尾追加一个元素）

**Interfaces:**
- Consumes: 前七个 Task 产出的全部测试类
- Produces: 套件名 `Course`，可用 `-Suite Course -WithMySql` 运行

- [ ] **Step 1: 追加套件定义**

在 `$suites` 数组的 `Applications` 元素之后、闭合 `)` 之前追加。

**只登记"本计划改到了、且当前一个套件都没有"的 13 个类**（已用 `grep -c "'<完整点号类名>'" scripts/test-teacher.ps1` 逐一核对过）。

> **2026-09-17 补记（Task 3 执行中发现，原写的 10 个是漏数）：** Task 3 改契约时同步了 **9 个**客户端测试类，其中 `controller.CourseSelectionControllerTest`、`controller.TrainingPlanControllerTest`、`service.CoursePushCoordinatorTest` 三个**同样一个套件都没有**，却不在原 10 个名单里——它们正好命中本条自己的收录标准，是漏掉的消费者，不是有意排除。已补进上面的 `Client` 列。
>
> 核对方式（执行者可直接复核）：`for c in controller.CourseSelectionControllerTest controller.TrainingPlanControllerTest service.CoursePushCoordinatorTest; do grep -c "'$c'" scripts/test-teacher.ps1; done` 三个都是 `0`。**注意必须用完整点号类名**：用简单类名会被前缀混淆（`TeacherCourseManagementControllerTest` 含 `CourseManagementControllerTest` 子串，简名 grep 会假阳性）。
>
> 三个类都**已被 Task 3 编译通过并单独跑绿**（`PASS`，exit 0，kit-free、无 DB），所以登记它们不会让新套件从第一天起变红。另外 `controller.GradeControllerTest` 同样被 Task 3 改过，但它**已登记在 GradeBook 套件**（`scripts/test-teacher.ps1:220`），收尾回归会跑到，**不要**重复登记。

**已在别处登记的一律不要重复登记**——重复只会让同一批 MySQL 用例跑两遍，与"减的是冗余不是覆盖"相违。以下这些**已经**有归属，本套件不再收：`dto.course.CourseDtoJsonTest`（Timetable）、`controller.ScheduleControllerTest`（Timetable）、`service.AdminEnrollmentMySqlTest`（GradeBook）、`service.CourseQueryMySqlTest` / `dao.CourseQueryMappingTest` / `service.CourseConflictMySqlTest`（Adjustment）、`service.SocketAdminCourseServiceTest` / `service.MockAdminCourseServiceTest`（Adjustment）。它们的验证由收尾步骤里逐个跑套件完成。

```powershell
    # 课程模块修复套件（2026-09-17）。本计划的三个缺陷分别落在学生课表读路径、管理员学生搜索、
    # 管理员排课，下面这 13 个类此前一个套件都没有——改了也永远跑不到，等于不会失败的测试。
    # 注意这里刻意不收已经在 Timetable/Adjustment/GradeBook 里的类，避免同一批 MySQL 用例跑两遍。
    # MySql 列 = Server 列里需要活库（解析 mysql 参数 / 自带受保护库守卫）的类。
    [pscustomobject]@{ Name = 'Course'
        Common = @('dto.course.admin.AdminCatalogDtoJsonTest')
        Client = @('service.SocketCourseServiceTest',
            'service.MockCourseServiceTest',
            'service.MockCourseScheduleTest',
            'controller.ScheduleArrangementDialogControllerTest',
            'controller.CourseManagementControllerTest',
            'controller.CourseSelectionControllerTest',
            'controller.TrainingPlanControllerTest',
            'service.CoursePushCoordinatorTest')
        Server = @('handler.CourseHandlerTest',
            'handler.AdminScheduleHandlerTest',
            'handler.AdminEnrollmentHandlerTest',
            'service.ScheduleManagementMySqlTest')
        Tcp = @()
        Gui = @()
        MySql = @('service.ScheduleManagementMySqlTest') }
```

`CourseUiSmokeTest` / `AdminCourseUiSmokeTest` **不要**登记进 `Gui`。

> **2026-09-17 更正（Task 3 review 触发复核，原文的归类是错的）：** 原文写"它们属于上面'既有 7 红'里的成员"——**只有 `ui.AdminCourseUiSmokeTest` 是**（见本文 Global Constraints 的 7 红名单）；`ui.CourseUiSmokeTest` 不在那份名单里，`grep -c "'ui.CourseUiSmokeTest'" scripts/test-teacher.ps1` 是 0，但"没登记"不等于"红"，两个理由不能混为一谈。结论不变（仍然不收），真正的理由是另外两条：
>
> 1. **收进来也永远不会跑。** 它是 `Gui` 列的类——`Application.start(Stage)` + `requireImageWritten`（截图落盘），必须真起窗口。而本 Task 的 Step 2 与收尾回归命令都**没有** `-WithGui`，登记了也是一条不执行的条目；"登记了一个永远不跑的类"比"诚实地不登记"更坏，因为前者看上去像有覆盖。
> 2. **它根本不看格子。** 实读 `VCampusClient/test/ui/CourseUiSmokeTest.java`：断的只有 `#termFilter`、一个 `ButtonBase`、`.course-offering-row` 与截图已写出，全文没有一处提到节次、行列或 `13`。它只是把 `CourseManagementView.fxml`（内含 `fx:include ScheduleView.fxml`）加载起来。
>
> 真正被它盖住的只有"FXML 能不能加载"（binding 写错会 `LoadException`），而 Task 3 只删了 6 个 `<ColumnConstraints>` 元素，风险很低。列重建循环的覆盖真空仍由收尾的人工验收兜底，见"人工验收"第 1 条。

- [ ] **Step 2: 跑新套件**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Course -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 全部 PASS，无 SKIP。若 `service.ScheduleManagementMySqlTest` 报 `Refusing schedule test`，说明 `-TestConfigPath` 没传对——它拒绝跑演示库。

---

## 收尾检查（全部 Task 完成后）

- [ ] **回归：把改动波及但本计划没亲自跑的套件跑一遍**，确认没有连带破坏：
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Timetable -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -WithTcp -TestConfigPath .codex-tmp/t1config/resources/db.properties
pwsh -File scripts/test-teacher.ps1 -Suite GradeBook -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
pwsh -File scripts/test-teacher.ps1 -Suite Course -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
（Apps/ImportExport 与本计划无交集，不跑——少跑冗余，不是少跑覆盖。）

- [ ] **人工验收（需要用户参与）**：用演示库 `virtual_campus`，走一遍：

  > **2026-09-18 更正（收尾前实测，原文漏了这一步，漏掉的后果是整轮验收白做）：必须先在 worktree 里重新打包，否则你看到的是旧界面。**
  >
  > **实测证据**（HEAD `8427a93`，打包产物在 `dist/`）：`dist/VCampusClient/VCampusClient.jar` 与
  > `dist/VCampusServer/VCampusServer.jar` 的时间戳都是 **9月17 12:49**，早于本计划的全部改动，且——
  >
  > ```
  > unzip -p dist/VCampusClient/VCampusClient.jar resources/fxml/ScheduleArrangementDialog.fxml | grep -c createDraftButton
  >   → 0        （「创建草稿方案」按钮不在里面）
  > unzip -l dist/VCampusClient/VCampusClient.jar | grep -c ScheduleWeekView
  >   → 0        （Task 3 的视图类不在里面）
  > unzip -l dist/VCampusServer/VCampusServer.jar | grep -c CourseScheduleWeekDTO
  >   → 0        （Task 1/2 的新 DTO 不在里面）
  > ```
  >
  > 也就是说：**照原文直接 `start_server`/`start_client` 跑起来，三个缺陷一个都不会变好**——
  > 学生端仍是 13 行 × 5 列、学号前缀仍搜不到、排课对话框仍然空白且没有创建草稿的按钮——
  > 验收者会合理地得出"这个计划整个没生效"的结论，而代码其实全对。
  > 这条 2026-09-16 已经坑过一次，原文没有把它写进验收步骤。
  >
  > **打包命令**（在 worktree 根目录）：
  > ```
  > pwsh -File package.ps1 -JavaHome D:\DevTools\Java\jdk25
  > ```
  >
  > **打包前后各有两条必须做的事，否则打包会"假成功"：**
  >
  > 1. **先关掉正在跑的客户端和服务器。** 它们占着自己的 jar，`jar -cfm` 会以
  >    `FileSystemException: …另一个程序正在使用此文件` 失败，而 **`package.ps1` 照样打印
  >    `[SUCCESS] Build and packaging complete!`**，jar 保持旧时间戳不变。**不要信横幅，打包后自己看
  >    `ls -la dist/*/*.jar` 的时间戳。**
  > 2. `package.ps1:109` 把 JavaFX 的 bin 目录**硬编码**成 `D:\JavaFX\javafx-sdk-25.0.4\bin`，
  >    本机不存在该路径，于是 `*.dll` 的复制被**静默跳过**。**本 worktree 已经绕过这个问题**：
  >    `dist/VCampusClient/bin/` 下已有 **59 个 DLL**（这是 JavaFX 认得的 SDK 布局，与启动 cwd 无关），
  >    而 `package.ps1` 只往 dist 根写，不会清掉 `bin/`。打包后确认 `bin/` 里的 DLL 还在即可。
  >
  > 另：原文括号里"注意它当前混了测试夹具"这句**已被证伪**（见下方清单），演示库是干净的，已删去。

  起服务器与客户端（打包之后），然后：
  1. 学生端「课表」节次行数 == 教师端「课程表」节次行数，**且星期列数也 == 教师端的星期列数**（切换几个周次，列数应当随该周日历变化，不是恒定 5 列）；
     > 这一条**必须人工数**：列与行的重建循环（`ScheduleController.java:198-205`）是全计划唯一没有任何自动断言的产码路径——`ScheduleControllerTest` 在离屏下 `scheduleGrid` 为 null，`renderSchedule` 根本不执行；`CourseUiSmokeTest` 是唯一真正加载该 FXML 的测试，却没登记进任何套件（`-WithGui` 也跑不到它）。把 `DAY_COLUMN_MIN_WIDTH`/`PREF` 对调、或者漏掉 `setHgrow(ALWAYS)`，全仓测试依然全绿，只有这一眼能看出来。
  2. 管理员「课程」→ 某教学班 →「添加学生」→ 输入学号前缀能搜到；
  3. 管理员「课程」→「排课」→ 能看到方案与既有安排（含一条「教师 待定」）→「创建草稿方案」→ 变为可编辑 → 新增一条排课并保存。

  第 3 步的预期细节（对上真库）：进去应看到方案 `4001`（PUBLISHED）、**20 条安排**，其中 arrangement `4104`（offering 2004）显示为「教师 待定」——这条能在界面上正常渲染本身就是缺陷 3(a) 修好的证据（修复前这里整页报"排课方案加载失败，请重试"、安排列表全空）。点「创建草稿方案」后应出现一份新 DRAFT，复制到 19 条完整安排（4104 因缺教师被跳过，见 Task 6 `copyArrangements` 注释），并在界面上看到结果文案 **「草稿方案已创建：已复制 19 条 / 跳过 1 条」**（修复轮的项 2/3 加的；看不到计数说明那两项目标未达成）。

  > **2026-09-18 更正（收尾前实查代码，原文这条写反了，而且后果是破坏性的）：**
  >
  > 原文写"**发布这条草稿会被拒绝**，消息是'教学安排缺少任课教师或时间段，无法发布'"。**这句是错的**，而且它把验收者引向一个会改坏演示库的动作。
  >
  > 实读代码：草稿里**只有**通过 `CourseConflictService.candidate()` 且教室非空、且时段有日历窗口的行
  > （`ScheduleManagementService.copyArrangements:392-394` 两条 `continue`），而 `requirePublishable`
  > （`CourseConflictService.java:214-229`）**拒绝的正是 `candidate() == null` 的行**。也就是说
  > **草稿里的每一行按构造都必然通过那道门**，加上非空检查也过（19 ≠ 0）——所以
  > **发布这份草稿会成功**。原文的预期来自更早的设计（那时草稿被认为会带着 4104 一起复制过来），
  > Task 6 改成"跳过不完整行"之后这条预期就**失效了**，但没人回头改。
  >
  > **后果**：发布成功会把 `teaching_calendar.current_schedule_plan_id` 挪到这份新方案上
  > （`ScheduleManagementService.java:288-290`），**学生端和教师端的课表都读这个指针**，
  > 于是 offering 2004 的那一节会从两边**同时消失**。而全仓**没有任何删除/撤回方案的路径**
  > （见 `follow-ups.md` 甲 1），演示库上**撤不回来**。
  >
  > **所以第 3 步的验收范围到此为止：创建草稿、看到计数、新增一条排课并保存。不要点「发布」。**
  > "发布被拒"这条路径**不要**在演示库上试——它已由自动化覆盖，不必也不能靠手点验证：
  > 不完整行被拒由 `CourseConflictMySqlTest` 钉着，空方案被拒由
  > `ScheduleManagementMySqlTest` 的零安排用例钉着（修复轮项 4）；而**非空且全部合规**的草稿
  > 本来就该发布成功，没有可拒绝的理由。
  >
  > 若确实想在演示库上验证发布链路，**先备份** `teaching_calendar` 与 `schedule_plan` 两张表，
  > 并在心里默认"这一步之后演示库的课表少一节、且无法用界面恢复"。

- [ ] **未纳入本次范围、但已确认存在的问题**（不要在这轮里顺手改，留作后续）：
  > **2026-09-18 收尾核对**：下面这条清单逐条复核过。第 1 条**已在统一修复轮里改掉**（标了 ✅），
  > 其余各条在 `follow-ups.md` 里有对应的甲/乙编号与"为什么这轮不修"。
  - ✅ **已修（修复轮项 5）** — `ScheduleController.java:37-45` 的 `PERIOD_COLUMN_WIDTH` 注释原写"11px 字号约 120px 字形 **+ 12px 内边距**"——**那句内边距是从教师端常量文档抄来的，学生端没有**：`.course-period-label`（`style.css:1561-1569`）根本没声明 `-fx-padding`，有 padding 的是教师端的 `.teacher-schedule-period-label`（`teacher-course.css:326-332`，`4px 6px 4px 6px`）。**宽度 150 本身不受影响**（光字形就约 120px，远超原来的 50px，结论不变），错的只是理由里多算了 12px。纯注释错误，Task 3 review 的 Minor 1；现已改为只声明字形宽度，并写明两侧 padding 的真实情况。
  - `ScheduleController.java:168,175,190-193`：若服务端把 `periods` 这个 key 改名或漏发，Gson 会把字段留成 null → `CourseScheduleWeekDTO.getPeriods()` 返回空列表 → `periodRows.isEmpty()` 提前返回 → **学生端整页空白**（无表头行、无行、无列），而修复前的学生端无论如何都会画出 13×5、教师端至少还留着星期表头；右侧通知栏仍显示"本周暂无调课通知"，界面上与"加载失败"（`:175` 的错误路径）无法区分。这是**本 Task 的 brief 明确要求的边界行为**（见 Task 3 步骤里的"边界"段），不是实现缺陷；但"契约漂移 ⇒ 静默空白"这个失败模式值得单独安排：一个可选项是空字典时也给一句可见提示。Task 3 review 的 Minor 2。
  - 学生端周次选择器仍写死 `1..20`（`ScheduleController.configureWeekSpinner` `:84-87`），教师端用服务端 `minWeek/maxWeek`。这是"两边不一致"的第三个轴，本轮只对齐了行与列。
  - `student_academic_profile` 没有任何生产写入路径——全 `VCampusServer/src` 只有读它的 SQL，没有一处 `INSERT INTO student_academic_profile`（注册流程写的是 `tblStudent`）。因此在一个只跑 `init.sql`、没有任何档案行的库上，「添加学生」会**合法地**返回 0 条（没有 ACTIVE 档案 = 不可选课，这是刻意的不变量，见"背景 · 缺陷 2"）。本轮只修了搜索的学号匹配，没有补建档案；根治要单独安排（注册链路补写 / 迁移回填）。演示库有 7/9 个学生带档案，所以修完即可用。
  - ~~开发库混灌了测试夹具~~ —— **已证伪，不必处理**：真库 `virtual_campus` 查下来只有 **1 个** `day_template`（`id=3101`「标准工作日课表」，80 个 `calendar_date`，8 个 `period_definition`），并没有 4 节的测试夹具残留。第一轮侦察里"4 节 vs 8 节"的分歧，答案是**演示库 8 节、测试库 4 节**，两边各自干净。
  - `CourseConflictService.candidate()` 的 `Long.valueOf(classroom.getBusinessId())`（`:194`）对非数字教室 id 会抛未捕获的 `NumberFormatException`。
  - `seed-course-demo.sql:300` 的注释提到的 `AdminScheduleDAO.setCurrentPlan` **并不存在**（真实方法名是 `updateCalendarPointer`），注释该修。
