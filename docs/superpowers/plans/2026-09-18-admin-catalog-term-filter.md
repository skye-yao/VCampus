# 管理员课程目录：学期维度与教学班筛选 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让管理员课程目录页有一个全局学期下拉，选中学期后每一行的"教学班 N 个"与展开后的教学班列表都只显示该学期的内容，从而能"针对本学期排课"；同时把学期标签收敛到一个来源。

**Architecture:** 学期作为**查询参数**下推到 SQL，而不是客户端过滤——照教师端已有的 `TeacherCourseService.listOfferings(academicYear, semester, ...)` 先例。课程列表本身**不过滤**（刚建的、本学期还没排班的课程必须留在列表里，否则"开设教学班"入口不可达）；学期只决定 (a) 每行教学班**计数**、(b) 展开后的教学班**列表**、(c) 新增教学班时的学期默认值。空学期（two null params）保留"不按学期限定"的旧行为，这是客户端在下拉为空时的退化状态，也是既有服务端测试保持绿色的前提。

**Tech Stack:** Java 25 + JavaFX（客户端 FXML/CSS）、原生 JDBC + MySQL（服务端 DAO/Service/Handler）、Gson JSON-line socket 协议、手写 `main()` 断言脚本（无 JUnit、无构建工具）。

**Spec:** `docs/superpowers/plans/2026-09-18-semester-and-lifecycle-findings.md` —— 本计划实现它的 **丁1（教学班没有学期筛选）**、**丁2（列表不过滤 status，与课程行计数口径打架）**、**丁3（没有学期概念，学期只能手打数字）**，并把 **丙6（学期标签三处不一致）** 收敛到共享函数。用户对该文的六点裁决见 `## 背景与决策`。

---

## 背景与决策（动手前必须知道）

用户在 2026-09-18 对 `2026-09-18-semester-and-lifecycle-findings.md` 第 9 节六问的裁决，逐条约束本计划：

1. **取消教学班 = 严格未开放(status=1)** —— 属于**批次③**，本计划不实现，但本计划把它的禁用条件预留成一行代码（Task 8 注）。
2. **归档教学班不做按钮，靠学期切换看历史** —— 本计划实现的学期下拉就是"历史视图"本身。**不要**新增状态，**不要**新增归档按钮。
3. **"当前学期"沿用学生/教师端课表那个下拉的概念** —— 不引入 `current_semester` 表/列/配置。默认值 = **最近学期**（`ORDER BY academic_year DESC, semester DESC` 的第一项），与教师端 `terms.get(0)` 一致。
4. **学期是工具栏上一个全局控件，只作用于教学班** —— 课程列表**不过滤**。
5. （删除课程，属批次③）
6. **分批 ①→②→③→④**，每批单独跑自己的套件。

**本计划 = 批次①，仅此。** 批次②（布局与"管理学生"合并）、批次③（生命周期守卫收紧 + 删除课程）、批次④（教师可见性收紧）、批次⑤（方案命名与解析收敛）各自单独出计划。

### 基线

- 分支 `integration/course-management-client-main`，commit **`e6d70ec`**。若 HEAD 已推进，**先用 `git log --oneline` 对齐再改行号**。
- 工作树：`D:\JavaProject\VCampus\.worktrees\course-management-client-integration`。**不要 `cd` 出这个目录。**

---

## Global Constraints

这些约束每个任务都隐含适用，不再逐条重复：

- **测试唯一入口**：`pwsh -File scripts/test-teacher.ps1 -Suite <名字> -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties`。
  - 必须用 **`pwsh`（PowerShell 7）**，不能用 `powershell.exe`（javac argfile 的 BOM 会让 javac 报 `Invalid filename`）。
  - **`-WithMySql` 实测必需**：本计划的套件声明了 MySQL 门控类，不传它会在选测试阶段直接报错退出。
  - **`-TestConfigPath` 实测必需**：默认值 `VCampusServer/src/resources/db.properties` 在本工作树里指向**演示库 `virtual_campus`**（已核实，该文件第 5 行），连库用例会以 `Refusing … must be exactly virtual_campus_course_test` 硬失败。
  - **通过判据只有退出码。** 脚本不解析 PASS 行。要证明"真的跑了"，**数输出里 `Running <类名>` 的行数**——套件声明不算数。
- **门控列不执行任何测试。** 一个类若**只**出现在 `MySql`/`Tcp`/`Gui` 列，它**永远不会被跑**，而套件照样打印 passed。一个类必须出现在 `Common`/`Client`/`Server` 列才会执行；同名类在两个列各出现一次是**合法的**（门控列只追加 argv）。
- **不碰在途文件**：`.gitignore`、`VCampusServer/src/resources/seed/seed-course-demo.sql`、`VCampusClient/src/controller/ScheduleController.java`、`VCampusClient/test/controller/ScheduleControllerTest.java` 正被别的会话修改。本计划**不需要**改它们。
- **既有红不要顺手修绿。** Task 1 会把一批从未注册过的类首次跑起来，若其中有的本来就红，**记录下来**，不要在本计划里修它（除非它是本计划自己改红的）。
- **学期标签唯一来源**：`VCampusCommon` 的 `TermLabels`。本计划之后，任何把 `(academicYear, semester)` 渲染成中文的地方都必须走它。
- **Commit**：英文 conventional commit，结尾带
  `Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>`。
- **UI 改动必须重打包后才可见**：用户跑的是 `dist/` 里的 jar。改完 `VCampusClient/src` 必须 `pwsh -File package.ps1 -JavaHome D:\DevTools\Java\jdk25`，且 `package.ps1` 会在 JavaFX DLL 复制失败时**静默成功**（它硬编码的 `D:\JavaFX\javafx-sdk-25.0.4\bin` 在本机不存在），所以重打包后要按 Task 10 的步骤确认。

---

## 文件结构

**新建**

| 文件 | 职责 |
|---|---|
| `VCampusCommon/src/dto/course/TermLabels.java` | 唯一的学期标签函数：`label(semester)` 与 `displayName(year, semester)` |
| `VCampusCommon/test/dto/course/TermLabelsTest.java` | 钉住标签格式与未知学期的回退 |
| （服务端新增的都是既有文件里的方法，无新文件） | |

**修改**

| 文件 | 改动 |
|---|---|
| `scripts/test-teacher.ps1` | 新增 `AdminCatalog` 套件（`:365` 的 `)` 之前插入） |
| `VCampusServer/src/dao/CourseQueryDAO.java` | `term(...)` 改为委托 `TermLabels`（行为不变） |
| `VCampusServer/src/dao/AdminOfferingDAO.java` | 新增 `listTerms`；`list` 加学期参数与 `status<>4` |
| `VCampusServer/src/dao/AdminCourseCatalogDAO.java` | `list` 加学期参数；`offering_count` 学期化 |
| `VCampusServer/src/service/AdminOfferingService.java` | `list(courseId, year, semester)`；新增 `listTerms()` |
| `VCampusServer/src/service/AdminCourseCatalogService.java` | `list(query, status, year, semester)`；`archive` 守卫加学期 |
| `VCampusServer/src/handler/AdminCourseHandler.java` | 新增 `LIST_OFFERING_TERMS` 分发；两个 action 读可选学期；新增 `optionalInteger` |
| `VCampusCommon/src/dto/course/admin/AdminCourseActions.java` | 新增 `LIST_OFFERING_TERMS` |
| `VCampusClient/src/service/AdminCourseService.java` | `listOfferings`/`listCourses` 加学期参数；新增 `listOfferingTerms()` |
| `VCampusClient/src/service/SocketAdminCourseService.java` | 三个方法实现 |
| `VCampusClient/src/service/MockAdminCourseService.java` | 三个方法实现 |
| `VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml` | 工具栏加学期 `ComboBox` |
| `VCampusClient/src/resources/css/style.css` | `.course-admin-term-filter` |
| `VCampusClient/src/controller/AdminCourseCatalogController.java` | 学期下拉状态机、学期化加载、文案走 `TermLabels` |
| `VCampusClient/src/controller/OfferingEditorDialogController.java` + `OfferingEditorDialog.fxml` | 学期输入框 → 下拉；学年提示改近 N 年 |
| `VCampusClient/test/controller/AdminCourseCatalogControllerTest.java` | 既有断言适配 + 新增学期下拉用例 |
| `VCampusServer/test/service/AdminOfferingMySqlTest.java` | 既有调用适配 + 新增跨学期筛选用例 |
| `VCampusServer/test/service/AdminCourseCatalogMySqlTest.java` | 既有调用适配 + 新增学期化计数用例 |

**明确不改**：`ScheduleManagementService`（方案名标签属批次⑤）、任何教师/学生端读路径（属批次④）、`AdminOfferingDAO.hasDependencies`（属批次④）。

---

### Task 1: 共享学期标签 + 新测试套件

本计划改的四个测试类**一个套件都没有**（`AdminCourseCatalogControllerTest`、`AdminCourseCatalogMySqlTest`、`AdminOfferingMySqlTest`、`AdminCourseCatalogSocketEndToEndTest`）——改了也永远跑不到。先把套件建起来并量出基线，否则后面每一步的"绿"都没有意义。

**Files:**
- Create: `VCampusCommon/src/dto/course/TermLabels.java`
- Create: `VCampusCommon/test/dto/course/TermLabelsTest.java`
- Modify: `scripts/test-teacher.ps1:363-365`
- Modify: `VCampusServer/src/dao/CourseQueryDAO.java:292-301`

**Interfaces:**
- Consumes: 无
- Produces: `TermLabels.displayName(int academicYear, int semester) -> String`（形如 `2026-2027 秋学期`）；`TermLabels.label(int semester) -> String`（形如 `秋学期`）

- [ ] **Step 1: 写失败的测试**

Create `VCampusCommon/test/dto/course/TermLabelsTest.java`：

```java
package dto.course;

public final class TermLabelsTest {

    private TermLabelsTest() {
    }

    public static void main(String[] args) {
        require("2026-2027 秋学期".equals(TermLabels.displayName(2026, 2)),
                "semester 2 must read as 秋学期 on the academic-year range");
        require("2026-2027 暑期学校".equals(TermLabels.displayName(2026, 1)),
                "season 1 is the summer school");
        require("2026-2027 春学期".equals(TermLabels.displayName(2026, 3)),
                "season 3 is the spring term");
        require("2026-2027 第4学期".equals(TermLabels.displayName(2026, 4)),
                "an out-of-range semester must degrade to 第N学期, not throw");
        require("秋学期".equals(TermLabels.label(2)),
                "the bare season label must be available without the year range");
        System.out.println("Term labels test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

- [ ] **Step 2: 跑测试，确认它因为类不存在而失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Course -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译阶段失败，报 `cannot find symbol: class TermLabels`（或类似）。**这一步只需要看到"红"，不用管 Course 套件里别的类。**

- [ ] **Step 3: 写实现**

Create `VCampusCommon/src/dto/course/TermLabels.java`：

```java
package dto.course;

/**
 * 学期标签的唯一来源。管理员目录、选课、课表、教学班编辑器都必须走这里，
 * 否则同一个 (academicYear, semester) 会在不同页面上写出不同的中文。
 */
public final class TermLabels {

    private TermLabels() {
    }

    /** 形如 {@code 秋学期}。未知值退化为 {@code 第N学期}，不抛异常。 */
    public static String label(int semester) {
        return switch (semester) {
            case 1 -> "暑期学校";
            case 2 -> "秋学期";
            case 3 -> "春学期";
            default -> "第" + semester + "学期";
        };
    }

    /** 形如 {@code 2026-2027 秋学期}。学年区间是 {@code academicYear..academicYear+1}。 */
    public static String displayName(int academicYear, int semester) {
        return academicYear + "-" + (academicYear + 1) + " " + label(semester);
    }
}
```

- [ ] **Step 4: 把 `CourseQueryDAO.term` 改成委托，保证行为不变**

Modify `VCampusServer/src/dao/CourseQueryDAO.java:292-301`。把整个 `term` 方法体换成：

```java
    public static CourseTermDTO term(int academicYear, int semester) {
        return new CourseTermDTO(academicYear, semester,
                TermLabels.displayName(academicYear, semester));
    }
```

并在该文件的 import 区加 `import dto.course.TermLabels;`。

> 这一步是**行为保持**的：`TermLabels` 的三个 case 逐字复制了原来 `CourseQueryDAO.term` 的 `"暑期学校"/"秋学期"/"春学期"` 与拼接方式，`default` 分支也一致。学生端/教师端显示的字符串**一个字符都不变**。

- [ ] **Step 5: 注册 `AdminCatalog` 套件**

Modify `scripts/test-teacher.ps1`，在 `:363` 那个 Course 套件对象的**后面**、`:365` 的 `)` **之前**插入：

```powershell
    # 管理员课程目录套件（2026-09-18）。下面四个类此前一个套件都没有——改了也永远跑不到，
    # 等于不会失败的测试。Admin*MySqlTest 两个类同时出现在 Server 与 MySql：门控列只追加
    # `mysql --config=` 参数、不产生运行项，放进 Server 列它们才真的会跑。
    # 刻意不收已在 Adjustment 套件里的 handler.AdminCourseHandlerTest，避免同一批用例跑两遍。
    [pscustomobject]@{ Name = 'AdminCatalog'
        Common = @('dto.course.TermLabelsTest')
        Client = @('controller.AdminCourseCatalogControllerTest')
        Server = @('service.AdminOfferingMySqlTest',
            'service.AdminCourseCatalogMySqlTest')
        Tcp = @()
        Gui = @()
        MySql = @('service.AdminOfferingMySqlTest',
            'service.AdminCourseCatalogMySqlTest') }
```

- [ ] **Step 6: 跑套件并记录基线**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 输出里出现 4 行 `Running …`（`dto.course.TermLabelsTest`、`controller.AdminCourseCatalogControllerTest`、`service.AdminOfferingMySqlTest`、`service.AdminCourseCatalogMySqlTest`），最后 `Suite AdminCatalog passed`（退出码 0）。

**如果它红了**：先判断红的是不是本计划引入的。`TermLabelsTest` 红 = 本计划的（回去改 Step 3）。另外三个类**从未运行过**，完全可能本来就有既有红。把红的类名与错误原文记进 `.superpowers/sdd/2026-09-18-admin-catalog-term-filter/baseline.md`，**不要在本计划里修它们**；把该文件路径写进最终报告，让批次②/③ 的会话知道自己在什么地基上改。

- [ ] **Step 7: Commit**

```bash
git add VCampusCommon/src/dto/course/TermLabels.java \
        VCampusCommon/test/dto/course/TermLabelsTest.java \
        VCampusServer/src/dao/CourseQueryDAO.java \
        scripts/test-teacher.ps1
git commit -m "$(cat <<'EOF'
feat: add one shared semester label source and register the admin catalog suite

CourseQueryDAO.term now delegates to dto.course.TermLabels, which is a
behaviour-preserving move that gives the admin catalog the same wording the
student and teacher pages already show.

The new AdminCatalog suite registers four test classes that no suite ran
before, so the rest of this plan is verified against something real.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: 服务端教学班学期列表

管理员需要"这门课/这个库有哪些学期"来填下拉。**取全局所有教学班的学期**，不按人、不按选课窗口——因为管理员要看的是整个学院的开课情况。

**Files:**
- Modify: `VCampusServer/src/dao/AdminOfferingDAO.java`（在 `list` 之后加方法）
- Modify: `VCampusServer/src/service/AdminOfferingService.java:53-60`
- Modify: `VCampusCommon/src/dto/course/admin/AdminCourseActions.java:9`
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java:132-133`

**Interfaces:**
- Consumes: `TermLabels.displayName(int, int)`（Task 1）
- Produces: `AdminOfferingService.listTerms() -> List<CourseTermDTO>`；协议 action 常量 `AdminCourseActions.LIST_OFFERING_TERMS`，响应键 `terms`

- [ ] **Step 1: 写失败的测试**

Modify `VCampusServer/test/service/AdminOfferingMySqlTest.java`。在 `main` 的 `verifyCancelAndDelete(offerings, offeringId);` 之后加一行：

```java
            verifyTermList(offerings);
```

并在类里加这个方法（放在 `verifyCancelAndDelete` 之前）：

```java
    /**
     * 学期下拉的取值来源必须是库里真实存在的 (academic_year, semester)，按最近优先排列，
     * 并且不受教学班状态影响——一个学期只要有过教学班就永远可选，否则下拉项会随时间消失。
     */
    private static void verifyTermList(AdminOfferingService offerings) throws Exception {
        List<CourseTermDTO> terms = offerings.listTerms();
        require(!terms.isEmpty(), "the seeded database must expose at least one term");
        for (int i = 1; i < terms.size(); i++) {
            CourseTermDTO previous = terms.get(i - 1);
            CourseTermDTO current = terms.get(i);
            boolean ordered = previous.getAcademicYear() > current.getAcademicYear()
                    || (previous.getAcademicYear() == current.getAcademicYear()
                    && previous.getSemester() > current.getSemester());
            require(ordered, "terms must be ordered most recent first, saw " + terms);
        }
        for (CourseTermDTO term : terms) {
            require(term.getDisplayName() != null && !term.getDisplayName().isBlank(),
                    "every term must carry a display name, saw " + term);
            require(count("SELECT COUNT(*) FROM course_offering WHERE academic_year="
                    + term.getAcademicYear() + " AND semester=" + term.getSemester()) > 0,
                    "every listed term must exist in course_offering, saw " + term);
        }
        execute("INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,status) VALUES('CS999-T-Z',1001,2029,1,10,1)");
        List<CourseTermDTO> withDraft = offerings.listTerms();
        require(withDraft.stream().anyMatch(term -> term.getAcademicYear() == 2029
                        && term.getSemester() == 1),
                "a brand new term must appear in the list, saw " + withDraft);
        require(withDraft.get(0).getAcademicYear() == 2029,
                "the newest term must sort first, saw " + withDraft);
    }
```

加 import：`import dto.course.CourseTermDTO;`（放在 `dto.course.admin.catalog.AdminOfferingDTO` 之前，保持字母序）。

`cleanup()` 已经用 `offering_code LIKE 'CS999-T-%'` 删行，所以 `CS999-T-Z` 不需要额外的清理语句。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Running service.AdminOfferingMySqlTest` 后失败，错误是 `AdminOfferingService` 里没有 `listTerms`，编译不过。

- [ ] **Step 3: 写实现**

Modify `VCampusServer/src/dao/AdminOfferingDAO.java`，在 `list` 方法之后加：

```java
    /**
     * 学期下拉的取值来源：全局所有教学班出现过的 (academic_year, semester)，最近优先。
     * 不过滤 status——取消掉最后一个教学班的学期也要留在下拉里，否则下拉项会随时间消失，
     * 管理员再也回不到那个学期。
     */
    public List<CourseTermDTO> listTerms(Connection connection) throws SQLException {
        String sql = "SELECT DISTINCT academic_year, semester FROM course_offering"
                + " ORDER BY academic_year DESC, semester DESC";
        List<CourseTermDTO> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                int academicYear = rows.getInt("academic_year");
                int semester = rows.getInt("semester");
                terms.add(new CourseTermDTO(academicYear, semester,
                        TermLabels.displayName(academicYear, semester)));
            }
        }
        return List.copyOf(terms);
    }
```

加 import：`import dto.course.CourseTermDTO;` 和 `import dto.course.TermLabels;`。

Modify `VCampusServer/src/service/AdminOfferingService.java`，在 `list` 方法之后加：

```java
    public List<CourseTermDTO> listTerms() {
        try (Connection connection = DBUtil.getConnection()) {
            return offeringDAO.listTerms(connection);
        } catch (SQLException failure) {
            throw new DatabaseException("学期列表查询失败", failure);
        }
    }
```

加 import：`import dto.course.CourseTermDTO;`。

Modify `VCampusCommon/src/dto/course/admin/AdminCourseActions.java`，在 `LIST_OFFERINGS` 之后加：

```java
    public static final String LIST_OFFERING_TERMS = "listOfferingTerms";
```

Modify `VCampusServer/src/handler/AdminCourseHandler.java:132-133`，在 `LIST_OFFERINGS` 的 case 之后加：

```java
                case AdminCourseActions.LIST_OFFERING_TERMS -> response.putData("terms",
                        offerings.listTerms());
```

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 4 行 `Running …`，`Suite AdminCatalog passed`。

- [ ] **Step 5: Commit**

```bash
git add VCampusCommon/src/dto/course/admin/AdminCourseActions.java \
        VCampusServer/src/dao/AdminOfferingDAO.java \
        VCampusServer/src/service/AdminOfferingService.java \
        VCampusServer/src/handler/AdminCourseHandler.java \
        VCampusServer/test/service/AdminOfferingMySqlTest.java
git commit -m "$(cat <<'EOF'
feat: expose the terms the admin catalog can filter teaching offerings by

The list comes from course_offering rather than a person's offerings or a
selection window, because the admin page has to show every term the college
has ever opened a class in. Cancelled offerings still count: a term must not
disappear from the dropdown once its last offering is cancelled.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: 教学班列表按学期筛选，并统一 status 口径

这是用户诉求的正身："管理员端不能根据学期来筛选教学班"。同时修掉丁2——列表**不过滤** `status=4`（已取消）而课程行计数**过滤**了，导致课程行写"教学班 2 个"、点开看到 3 行。

**Files:**
- Modify: `VCampusServer/src/dao/AdminOfferingDAO.java:35-46`
- Modify: `VCampusServer/src/service/AdminOfferingService.java:53-60`
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java:132-133`（只把调用点补成三参，参数先给 `null`）
- Modify: `VCampusServer/test/service/AdminOfferingMySqlTest.java`（两处调用 + 新用例）

> **为什么 handler 要在这里跟着改**：`AdminOfferingService.list` 的签名是本任务改的，而 handler 是它的唯一生产调用方。不一起改，本任务结束时 `VCampusServer/src` 编译不过，套件会红。这里只传 `null, null`（等价于旧行为）；**把请求里的学期真正读出来是 Task 5 的事**。

**Interfaces:**
- Consumes: 无
- Produces: `AdminOfferingService.list(String courseId, Integer academicYear, Integer semester) -> List<AdminOfferingDTO>`。**两个学期参数同时为 `null` 表示不按学期限定**（保留旧行为）。

- [ ] **Step 1: 写失败的测试**

Modify `VCampusServer/test/service/AdminOfferingMySqlTest.java`：

(a) 两处旧调用改成四参形式（`list` 现在要求学期）。
- `:112` 的 `List<AdminOfferingDTO> listed = offerings.list("1001");` → `offerings.list("1001", null, null);`
- `:229` 的 `require(offerings.list("1001").stream()` → `require(offerings.list("1001", null, null).stream()`

(b) 在 `main` 里 `verifyCreateAndReplay` **之前**插入新调用（用已有 course 1001，它的种子教学班跨学期）：

```java
            verifyTermFilter(offerings);
```

(c) 加入新方法：

```java
    /**
     * 学期筛选只返回该学期的教学班；不传学期时仍然是"全部学期"的旧行为；
     * 已取消的教学班不出现在列表里——课程行的计数口径也是 status<>4，两边必须说同一个数。
     *
     * 注意 try/finally：这个 fixture 的代码前缀 `CS999-T-` 与 verifyCreateAndReplay 里
     * "被拒的 create 什么都不插" 那条断言（`LIKE 'CS999-T-%'` 计数 == 1）共用，
     * 不删掉它那条断言必红。cleanup() 只在 main 的两端跑，救不了中间这一步。
     */
    private static void verifyTermFilter(AdminOfferingService offerings) throws Exception {
        execute("INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,status) VALUES('CS999-T-Y',1001,2031,2,10,4)");
        try {
            List<AdminOfferingDTO> unfiltered = offerings.list("1001", null, null);
            require(unfiltered.stream().anyMatch(item -> "2001".equals(item.getOfferingId()))
                            && unfiltered.stream().anyMatch(
                                    item -> "2003".equals(item.getOfferingId())),
                    "without a term the list stays unscoped: every term's offerings are in it, saw "
                            + unfiltered);
            require(unfiltered.stream().noneMatch(
                            item -> "CS999-T-Y".equals(item.getOfferingCode())),
                    "the cancelled offering stays hidden without a term too, so the list and the"
                            + " course row's count agree on the default screen as well, saw "
                            + unfiltered);

            List<AdminOfferingDTO> scoped = offerings.list("1001", 2027, 3);
            require(scoped.stream().allMatch(item -> item.getAcademicYear() == 2027
                            && item.getSemester() == 3),
                    "a term filter must return that term only, saw " + scoped);
            require(scoped.stream().noneMatch(item -> "2001".equals(item.getOfferingId())),
                    "the seeded 2026-2 offering must not leak into a 2027-3 filter, saw " + scoped);

            List<AdminOfferingDTO> cancelledOnly = offerings.list("1001", 2031, 2);
            require(cancelledOnly.isEmpty(),
                    "a cancelled offering is not part of its course's manageable offerings, saw "
                            + cancelledOnly);
            require(count("SELECT COUNT(*) FROM course_offering WHERE offering_code='CS999-T-Y'")
                            == 1,
                    "hiding a cancelled offering must not delete the row");

            // 2031/2 这个学期**只**有上面那一个教学班，而它是已取消的。若 listTerms 带上 status 条件，
            // 这个学期会从下拉里消失、管理员再也回不去——这正是 Task 2 那条"不过滤 status"的需求，
            // 而 Task 2 自己的用例不可能失败地证明它（它插的行 status=1）。在这里用现成的 fixture 钉死。
            require(offerings.listTerms().stream().anyMatch(term ->
                            term.getAcademicYear() == 2031 && term.getSemester() == 2),
                    "a term whose only offering is cancelled must stay in the term list, saw "
                            + offerings.listTerms());
        } finally {
            execute("DELETE FROM course_offering WHERE offering_code='CS999-T-Y'");
        }
    }
```

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败，`list(String,int,int)` 在 `AdminOfferingService` 上不存在。

- [ ] **Step 3: 写实现**

Modify `VCampusServer/src/dao/AdminOfferingDAO.java:35-46`，把整个 `list` 方法替换为：

```java
    /**
     * 教学班列表。学期参数同时为 {@code null} 时不按学期限定（旧行为）；
     * 已取消（status=4）的行一律不返回——课程行的"教学班 N 个"也按 status<>4 计数，
     * 两处口径必须一致，否则同一屏上会出现两个打架的数字。
     */
    public List<AdminOfferingDTO> list(Connection connection, long courseId, Integer academicYear,
                                       Integer semester) throws SQLException {
        boolean scoped = academicYear != null && semester != null;
        String sql = SELECT + " WHERE o.course_id=? AND o.status<>4"
                + (scoped ? " AND o.academic_year=? AND o.semester=?" : "")
                + " ORDER BY o.academic_year DESC, o.semester DESC, o.offering_code";
        List<AdminOfferingDTO> offerings = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, courseId);
            if (scoped) {
                statement.setInt(index++, academicYear);
                statement.setInt(index, semester);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) offerings.add(map(rows));
            }
        }
        return List.copyOf(offerings);
    }
```

Modify `VCampusServer/src/service/AdminOfferingService.java:53-60`，把 `list` 替换为：

```java
    public List<AdminOfferingDTO> list(String courseId, Integer academicYear, Integer semester) {
        long id = AdminOperationTransaction.parseId(courseId, "courseId");
        // 两个学期参数必须同时给或同时不给。只给一半时 DAO 会因为要求两者都非 null 而**静默地**
        // 不按学期限定——客户端拿到 200 却看到没筛过的列表，正是本任务要消灭的那类"数字对不上"。
        if ((academicYear == null) != (semester == null)) {
            throw new IllegalArgumentException("学期无效");
        }
        if (academicYear != null && (academicYear <= 0 || semester < 1 || semester > 3)) {
            throw new IllegalArgumentException("学期无效");
        }
        try (Connection connection = DBUtil.getConnection()) {
            return offeringDAO.list(connection, id, academicYear, semester);
        } catch (SQLException failure) {
            throw new DatabaseException("教学班列表查询失败", failure);
        }
    }
```

Modify `VCampusServer/src/handler/AdminCourseHandler.java:132-133`（唯一生产调用方，不补会编译不过）：

```java
                case AdminCourseActions.LIST_OFFERINGS -> response.putData("offerings",
                        offerings.list(decimalId(request, "courseId"), null, null));
```

`null, null` 就是"不按学期限定"，等价于改动前的行为。**Task 5 会把这两个 `null` 换成从请求里读出的 `optionalInteger(request, "academicYear")` / `optionalInteger(request, "semester")`——本任务不要提前做那一步。**

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 4 行 `Running …`，`Suite AdminCatalog passed`。

- [ ] **Step 5: Commit**

```bash
git add VCampusServer/src/dao/AdminOfferingDAO.java \
        VCampusServer/src/service/AdminOfferingService.java \
        VCampusServer/src/handler/AdminCourseHandler.java \
        VCampusServer/test/service/AdminOfferingMySqlTest.java \
        VCampusServer/test/handler/AdminCourseHandlerTest.java
git commit -m "$(cat <<'EOF'
feat: let the teaching-offering list be scoped by term and hide cancelled rows

The list and the course row's offering count now agree: both ignore
status=4. The dropped term parameters keep the old "every term" behaviour so
the degenerate empty-dropdown state still has a meaning.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: 课程行的"教学班 N 个"按学期计数

用户裁决的是"学期只作用于教学班，**包括**每行那个计数"。不这么做的话，选了 2027 春学期、行上仍写着全部学期的 3、展开只有 2 条，丁2 那个"两个数字打架"原样复发。

**Files:**
- Modify: `VCampusServer/src/dao/AdminCourseCatalogDAO.java:18-48, 60-68`
- Modify: `VCampusServer/src/service/AdminCourseCatalogService.java:48-60`
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java:120-121`（只把调用点补成四参，参数先给 `null`）
- Modify: `VCampusServer/test/service/AdminCourseCatalogMySqlTest.java`

> 同 Task 3：`AdminCourseCatalogService.list` 的签名是本任务改的，handler 是唯一生产调用方，必须一起改成四参（先给 `null, null`），否则本任务结束时 `VCampusServer/src` 编译不过。**读请求里的学期是 Task 5 的事。**

**Interfaces:**
- Consumes: 无
- Produces: `AdminCourseCatalogService.list(String query, String status, Integer academicYear, Integer semester) -> List<AdminCourseDTO>`。学期为 `null` 时 `offeringCount` 是**全部学期**的计数（旧行为）。

- [ ] **Step 1: 写失败的测试**

Modify `VCampusServer/test/service/AdminCourseCatalogMySqlTest.java`：

(a) 四处旧调用补两个 `null`：
- `:192` `catalog.list("CS99", "ARCHIVED")` → `catalog.list("CS99", "ARCHIVED", null, null)`
- `:199` `catalog.list("CS99_", null)` → `catalog.list("CS99_", null, null, null)`
- `:202` `catalog.list("测试课程", null)` → `catalog.list("测试课程", null, null, null)`
- `:207` `catalog.list(null, null)` → `catalog.list(null, null, null, null)`
- `:211` `catalog.list(null, "BOGUS")` → `catalog.list(null, "BOGUS", null, null)`

> 这些断言**期望值不用改**：传 `null` 学期就是旧的全量计数语义。

(b) 在 `verifyReplayAndList` 的末尾（`:211` 那行之后）加：

```java
        verifyTermScopedOfferingCount(catalog);
```

(c) 加入新方法：

```java
    /**
     * 按学期筛选时，课程行的 "教学班 N 个" 必须只数该学期的非取消教学班，
     * 与展开后看到的行数一致——这正是丁2 里两个数字打架的地方。
     */
    private static void verifyTermScopedOfferingCount(AdminCourseCatalogService catalog)
            throws Exception {
        execute("INSERT INTO course_offering(offering_code,course_id,academic_year,semester,"
                + "capacity,status) VALUES('CS999-C-2033',1001,2033,1,10,2)");
        try {
            int scoped = count("SELECT COUNT(*) FROM course_offering WHERE course_id=1001"
                    + " AND status<>4 AND academic_year=2033 AND semester=1");
            AdminCourseDTO scopedRow = catalog.list(null, null, 2033, 1).stream()
                    .filter(course -> "1001".equals(course.getCourseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("course 1001 must be listed"));
            require(scopedRow.getOfferingCount() == scoped,
                    "a term-scoped list must count only that term, expected " + scoped
                            + " but saw " + scopedRow.getOfferingCount());

            int allTerms = count("SELECT COUNT(*) FROM course_offering WHERE course_id=1001"
                    + " AND status<>4");
            require(allTerms > scoped, "the fixture must add a term the course did not have");
            AdminCourseDTO unscopedRow = catalog.list(null, null, null, null).stream()
                    .filter(course -> "1001".equals(course.getCourseId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("course 1001 must be listed"));
            require(unscopedRow.getOfferingCount() == allTerms,
                    "a list without a term must keep counting every term, expected " + allTerms
                            + " but saw " + unscopedRow.getOfferingCount());
        } finally {
            execute("DELETE FROM course_offering WHERE offering_code='CS999-C-2033'");
        }
    }
```

(d) 在同一个方法末尾（`finally` **之前**）补三条"拒绝形状"断言。理由同 Task 3 的修复轮：`list` 的学期校验是一条之后会被真实请求参数接进去的错误路径，没有测试就是没有牙齿。用本文件已有的 `expect` 助手：

```java
            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, 2027, null),
                    "an academic year without a semester must be rejected");
            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, null, 3),
                    "a semester without an academic year must be rejected");
            expect(IllegalArgumentException.class,
                    () -> catalog.list(null, null, 2027, 4),
                    "a semester outside 1..3 must be rejected");
```

这三条必须在任何查询之前抛，所以不能改动 fixture 状态。

`cleanup()` 里现有的 `DELETE FROM course WHERE course_code IN (...)` 不覆盖这条 fixture，所以上面用 `try/finally` 自带清理。`AdminCourseDTO` 已在该测试的 import 里。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败，`list(String,String,Integer,Integer)` 不存在。

- [ ] **Step 3: 写实现**

Modify `VCampusServer/src/dao/AdminCourseCatalogDAO.java`。把 `:18-22` 的 `SELECT` 常量与 `:24-48` 的 `list` 替换为下面两块（`find` 也用它，见下）：

```java
    /**
     * {@code offering_count} 的口径：只看非取消的教学班。学期化与否取决于调用方——
     * 课程列表带学期参数时，行上的计数必须和展开后看到的行数一致。
     */
    private static String offeringCountExpression(boolean termScoped) {
        return "(SELECT COUNT(*) FROM course_offering o WHERE o.course_id=c.course_id"
                + " AND o.status<>4"
                + (termScoped ? " AND o.academic_year=? AND o.semester=?" : "")
                + ") AS offering_count";
    }

    private static String select(boolean termScoped) {
        return "SELECT c.course_id,c.course_code,c.course_name,"
                + "c.course_type,c.credit,c.credit_hours,c.description,c.prerequisites,"
                + "c.allow_cross_major,c.final_exam,c.status,c.version,"
                + offeringCountExpression(termScoped) + " FROM course c";
    }

    public List<AdminCourseDTO> list(Connection connection, String query, String status,
                                     Integer academicYear, Integer semester) throws SQLException {
        boolean termScoped = academicYear != null && semester != null;
        List<String> clauses = new ArrayList<>();
        List<String> params = new ArrayList<>();
        if (status != null) {
            clauses.add("c.status=?");
            params.add(status);
        }
        if (query != null) {
            clauses.add("(c.course_code LIKE ? ESCAPE '!' OR c.course_name LIKE ? ESCAPE '!')");
            String pattern = "%" + escapeLike(query) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        String sql = select(termScoped)
                + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
                + " ORDER BY c.course_code";
        List<AdminCourseDTO> courses = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            // 子查询写在 SELECT 列表里，位置参数先于 WHERE 的参数，绑定顺序必须先学期后筛选。
            int index = 1;
            if (termScoped) {
                statement.setInt(index++, academicYear);
                statement.setInt(index++, semester);
            }
            for (String param : params) statement.setString(index++, param);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) courses.add(map(rows));
            }
        }
        return List.copyOf(courses);
    }
```

Modify `:60-68` 的 `find`，只改 SQL 那一行（`find` 没有学期上下文，保持全量计数）：

```java
    public AdminCourseDTO find(Connection connection, long courseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                select(false) + " WHERE c.course_id=?")) {
            statement.setLong(1, courseId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? map(rows) : null;
            }
        }
    }
```

Modify `VCampusServer/src/service/AdminCourseCatalogService.java:48-60`，把 `list` 替换为：

```java
    public List<AdminCourseDTO> list(String query, String status, Integer academicYear,
                                     Integer semester) {
        String statusFilter = AdminOperationTransaction.blankToNull(status);
        if (statusFilter != null && !"ACTIVE".equals(statusFilter)
                && !"ARCHIVED".equals(statusFilter)) {
            throw new IllegalArgumentException("课程状态无效");
        }
        if (academicYear != null && semester == null
                || academicYear == null && semester != null) {
            throw new IllegalArgumentException("学期无效");
        }
        if (academicYear != null && (academicYear <= 0 || semester < 1 || semester > 3)) {
            throw new IllegalArgumentException("学期无效");
        }
        try (Connection connection = DBUtil.getConnection()) {
            return catalogDAO.list(connection, AdminOperationTransaction.blankToNull(query),
                    statusFilter, academicYear, semester);
        } catch (SQLException failure) {
            throw new DatabaseException("课程列表查询失败", failure);
        }
    }
```

Modify `VCampusServer/src/handler/AdminCourseHandler.java:120-121`（唯一生产调用方，不补会编译不过）：

```java
                case AdminCourseActions.LIST_COURSES -> response.putData("courses", catalog.list(
                        optionalText(request, "query"), optionalText(request, "status"),
                        null, null));
```

`null, null` 就是"不按学期限定"，等价于改动前的行为。**Task 5 会把这两个 `null` 换成 `optionalInteger(request, "academicYear")` / `optionalInteger(request, "semester")`——本任务不要提前做那一步。**

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 4 行 `Running …`，`Suite AdminCatalog passed`。

- [ ] **Step 5: Commit**

```bash
git add VCampusServer/src/dao/AdminCourseCatalogDAO.java \
        VCampusServer/src/service/AdminCourseCatalogService.java \
        VCampusServer/src/handler/AdminCourseHandler.java \
        VCampusServer/test/service/AdminCourseCatalogMySqlTest.java
git commit -m "$(cat <<'EOF'
feat: count a course's teaching offerings within the selected term

Without this the row kept saying "教学班 3 个" while the expanded list showed
the two that belong to the term the admin had picked, which is exactly the
mismatch this batch sets out to remove. A null term keeps the old all-terms
count so the empty-dropdown state is still meaningful.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Handler 分发（两个 action 读可选学期）

**Files:**
- Modify: `VCampusServer/src/handler/AdminCourseHandler.java:120-121, 132-133`
- Modify: `VCampusServer/test/handler/AdminCourseHandlerTest.java`

**Interfaces:**
- Consumes: `AdminCourseCatalogService.list(String, String, Integer, Integer)`（Task 4）、`AdminOfferingService.list(String, Integer, Integer)`（Task 3）
- Produces: 协议层 `listCourses` / `listOfferings` 接受可选 `academicYear`（整数）与 `semester`（整数）；两者都缺省时行为不变

- [ ] **Step 1: 写失败的测试**

Modify `VCampusServer/test/handler/AdminCourseHandlerTest.java`。先读文件确认 `FakeOfferingService extends AdminOfferingService` 是怎么覆写 `list` 的，然后：

(a) **先看清现状**：Task 3 与 Task 4 已经改过这个文件里两个假实现的**签名**（只改签名、body 不动），所以现在：

- `FakeOfferingService.list(String, Integer, Integer)` 已存在，body 仍是 `return List.of(offering());`
- `FakeCatalogService.list(String, String, Integer, Integer)` 已存在，body 仍是 `return List.of(course());`

本任务要在这个基础上**加记录与 `listTerms`**，不要再去改签名（那已经做完了）：

```java
    private static final class FakeOfferingService extends AdminOfferingService {
        private String lastAdminUid;
        private OfferingEditorRequestDTO lastRequest;
        final List<String> listCalls = new ArrayList<>();

        @Override
        public List<AdminOfferingDTO> list(String courseId, Integer academicYear,
                                           Integer semester) {
            listCalls.add(courseId + "|" + academicYear + "|" + semester);
            return List.of(offering());
        }

        @Override
        public List<CourseTermDTO> listTerms() {
            return List.of(new CourseTermDTO(2027, 3, "2027-2028 春学期"));
        }
        // create(...) 保持原样不动
    }
```

加 import `dto.course.CourseTermDTO;` 与 `java.util.ArrayList;`（若缺）。**不要**用 `super.list(...)`——那会把这个离屏测试变成连库测试。

(b) 找到 `listOfferings` 那个用例（约 `:116-123`），在它之后加新用例：

```java
    private static void testListOfferingsPassesTheTermThrough() {
        // 请求不带学期时，服务端必须收到两个 null，而不是 0——0 会被当成"学年无效"拒掉。
        Message unscoped = request("listOfferings", administrator.getToken());
        unscoped.putData("courseId", "1001");
        require(handler.handle(unscoped).getCode() == MessageCode.SUCCESS,
                "listOfferings without a term must succeed");
        require(offerings.listCalls.contains("1001|null|null"),
                "an absent term must reach the service as null, saw " + offerings.listCalls);

        Message scoped = request("listOfferings", administrator.getToken());
        scoped.putData("courseId", "1001");
        scoped.putData("academicYear", 2027);
        scoped.putData("semester", 3);
        require(handler.handle(scoped).getCode() == MessageCode.SUCCESS,
                "listOfferings with a term must succeed");
        require(offerings.listCalls.contains("1001|2027|3"),
                "the term must reach the service unchanged, saw " + offerings.listCalls);
    }

    private static void testListOfferingTermsReturnsTheTermKey() {
        Message terms = request("listOfferingTerms", administrator.getToken());
        Message response = handler.handle(terms);
        require(response.getCode() == MessageCode.SUCCESS, "listOfferingTerms must succeed");
        require(response.getData().size() == 1 && response.getData().containsKey("terms"),
                "listOfferingTerms must use response key terms");
    }
```

把这两个方法名加进该测试类 `main` 的调用序列。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败或新用例失败（`listOfferingTerms` 未分发时 handler 返回 BAD_REQUEST）。

- [ ] **Step 3: 写实现**

Modify `VCampusServer/src/handler/AdminCourseHandler.java:120-121`：

```java
                case AdminCourseActions.LIST_COURSES -> response.putData("courses", catalog.list(
                        optionalText(request, "query"), optionalText(request, "status"),
                        optionalInteger(request, "academicYear"),
                        optionalInteger(request, "semester")));
```

Modify `:132-136`（`LIST_OFFERINGS` 与它后面的 `LIST_OFFERING_TERMS`）：

```java
                case AdminCourseActions.LIST_OFFERINGS -> response.putData("offerings",
                        offerings.list(decimalId(request, "courseId"),
                                optionalInteger(request, "academicYear"),
                                optionalInteger(request, "semester")));
                case AdminCourseActions.LIST_OFFERING_TERMS -> response.putData("terms",
                        offerings.listTerms());
```

在该 handler 的 `optionalText` 旁边加一个兄弟方法（`integer(request, key)` 要求字段存在，缺省会抛，所以需要这个）：

```java
    /**
     * 可选整数：字段缺席或为 null 时返回 {@code null}，让调用方按"不限定"处理。
     * 字段在但格式不对仍然抛——那是客户端 bug，不能静默降级成"不限定"。
     */
    private static Integer optionalInteger(Message request, String key) {
        Map<String, Object> data = request.getData();
        if (data == null || data.get(key) == null) return null;
        return integer(request, key);
    }
```

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Suite Adjustment passed`。

再跑一次确认没有连带破坏：
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Suite AdminCatalog passed`。

> 本任务是唯一改到 `AdminCourseHandlerTest` 的（它注册在 Adjustment 套件），所以这一步要跑 **Adjustment**，不是 AdminCatalog。

- [ ] **Step 5: Commit**

```bash
git add VCampusServer/src/handler/AdminCourseHandler.java \
        VCampusServer/test/handler/AdminCourseHandlerTest.java
git commit -m "$(cat <<'EOF'
feat: accept an optional term on the admin catalog list actions

An absent term is read as null rather than 0, so "no term selected" stays a
distinct state from "year zero"; a malformed one still throws.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: 客户端服务层

**Files:**
- Modify: `VCampusClient/src/service/AdminCourseService.java`
- Modify: `VCampusClient/src/service/SocketAdminCourseService.java:81-93, 121-132`
- Modify: `VCampusClient/src/service/MockAdminCourseService.java:126-140, 237-244`
- Modify: `VCampusClient/test/controller/AdminCourseCatalogControllerTest.java`（假服务**覆写新签名**）

**Interfaces:**
- Consumes: 协议 action `LIST_OFFERING_TERMS`、响应键 `terms`（Task 2）；`listCourses`/`listOfferings` 的新参数（Task 5）
- Produces: `AdminCourseService.listCourses(String query, String status, Integer academicYear, Integer semester)`；`AdminCourseService.listOfferings(String courseId, Integer academicYear, Integer semester)`；`AdminCourseService.listOfferingTerms() -> CompletableFuture<List<CourseTermView>>`

> **旧签名保留，新签名用 `default` 委托。** `grep -rn "implements AdminCourseService"` 数出**七个**测试假实现（`AdminCourseCatalogControllerTest.ControlledService`、`ui.AdminCourseUiSmokeTest`、`AdminApprovalControllerTest`、`GradeApprovalControllerTest`、`AddOfferingStudentDialogControllerTest`、`RemoveOfferingStudentDialogControllerTest`、`ScheduleArrangementDialogControllerTest`）。改签名会把 diff 铺到不相干的文件上，而这个接口本来就用 default 做渐进迁移（原文注释：`defaults keep existing implementations compatible`）。所以：**两参/一参的旧方法原样不动**，新增三参/四参的 `default` 方法委托给它（丢弃学期），`Socket` 与 `Mock` **覆写**新方法。
>
> 需要跟着改的假实现只有两个：`AdminCourseCatalogControllerTest.ControlledService`（必须覆写新签名才能观察到学期），以及 `ui.AdminCourseUiSmokeTest`（它的学期下拉要靠自己回答案 `listOfferingTerms()` 才有选项，在 Task 7 修）。**其余五个假实现一行都不用动**，它们继承 default。如果编译报出别的假实现，说明你对"新方法"用了抽象而非 default，回去改成 default。
>
> **数字订正**：早先的"十一个假实现、八个在 GradeBook/Adjustment"来自一次误配的 grep——它同时匹配上了教师端那个**与本接口无关**的 `TeacherOfferingDTO`。实际实现 `AdminCourseService` 的测试假实现是上面那七个。

- [ ] **Step 1: 写失败的测试**

改 `AdminCourseCatalogControllerTest.ControlledService`，把两个旧签名的覆写**换**成新签名（旧签名由接口 default 提供，不必也不该再覆写）：
- `listCourses(String query, String status)` → `listCourses(String query, String status, Integer academicYear, Integer semester)`，记录 `listCalls.add(query + "|" + status + "|" + academicYear + "|" + semester);`
- `listOfferings(String courseId)` → `listOfferings(String courseId, Integer academicYear, Integer semester)`

**另外五个假实现不要动**（`AdminApprovalControllerTest`、`GradeApprovalControllerTest`、`AddOfferingStudentDialogControllerTest`、`RemoveOfferingStudentDialogControllerTest`、`ScheduleArrangementDialogControllerTest`）——它们继承 default 即可。`ui.AdminCourseUiSmokeTest` 要改（Task 7 里做）。

然后改 `AdminCourseCatalogControllerTest` 里既有的断言字符串（现在是 `"CS|ARCHIVED"` 这种两段式）：
- `:56` 附近 `require("CS|ARCHIVED".equals(service.listCalls.get(0)), ...)` → `require("CS|ARCHIVED|null|null".equals(service.listCalls.get(0)), ...)`
- `:61` 附近 `"CS|ARCHIVED".equals(service.listCalls.get(1))` → `"CS|ARCHIVED|null|null".equals(...)`
- `:70` 附近 `"null|null".equals(service.listCalls.get(0))` → `"null|null|null|null".equals(...)`

最后，给 `ControlledService` 加上学期相关的记录能力（**本任务先不加测试用例**——能通过 `loadTerms()` 观察学期的那条用例依赖 Task 7 的控制器 API，本任务写它会让整个 `VCampusClient/test` 编译不过、所有套件全红）：

`ControlledService` 加：`terms` 字段 + `setTerms(...)` + `listOfferingTerms()` 返回它。**不加**平行的记录列表——`listCalls` 本任务已经改成四段式（`query|status|academicYear|semester`），再维护一份逐字重复的 `courseCalls` 就是第二份真相、必然漂移。真实的学期断言用例由 **Task 7** 写，直接读 `listCalls`。

> **给 Task 7 的接口**：`ControlledService` 的 `listCalls` 记录格式是 `query + "|" + status + "|" + academicYear + "|" + semester`；注意传进来的是**已经 `blankToNull` 过**的值，所以空搜索词记录成 `null` 而不是 `""`。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 客户端编译失败（`listTerms` / `selectTerm` / `terms()` 不存在）。

- [ ] **Step 3: 写实现**

Modify `VCampusClient/src/service/AdminCourseService.java`。**保留** `listCourses(String, String)` 与 `listOfferings(String)` 两个抽象方法**原样不动**，在它们各自后面新增 `default` 重载：

```java
    /**
     * 带学期限定的课程列表：{@code academicYear}/{@code semester} 同时为 null 时等价于
     * {@link #listCourses(String, String)}。
     *
     * <p>默认实现丢弃学期并委托给旧方法，这样十个与本批无关的测试假实现不必跟着改。
     * 真正实现学期语义的是 {@link SocketAdminCourseService} 与 {@link MockAdminCourseService}。</p>
     */
    default CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
            Integer academicYear, Integer semester) {
        return listCourses(query, status);
    }

    /** 带学期限定的教学班列表：两个学期参数同时为 null 时等价于 {@link #listOfferings(String)}。 */
    default CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId,
            Integer academicYear, Integer semester) {
        return listOfferings(courseId);
    }

    /** 学期下拉的取值来源：全局所有教学班出现过的学期，最近优先。 */
    default CompletableFuture<List<CourseTermView>> listOfferingTerms() {
        throw new UnsupportedOperationException("listOfferingTerms");
    }
```

加 import `dto.course.CourseTermDTO;` 与 `model.course.CourseTermView;`。

> `listCourses(String, String)` 与 `listOfferings(String)` 里那句"默认实现抛 UnsupportedOperationException"的老注释保持原样——本任务不清理它们。

Modify `VCampusClient/src/service/SocketAdminCourseService.java`。

**旧的两参/一参方法仍是接口上的抽象方法，`SocketAdminCourseService` 必须继续实现它们**；把它们改成一行委托，请求体只写在新方法里（否则同一份请求构造代码会出现两份，改一处漏一处）：

```java
    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
        return listCourses(query, status, null, null);
    }

    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
        return listOfferings(courseId, null, null);
    }
```

然后新方法：

```java
    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
            Integer academicYear, Integer semester) {
        Message request = request(AdminCourseActions.LIST_COURSES);
        if (query != null) request.putData("query", query);
        if (status != null) request.putData("status", status);
        // 必须整个键都不发：服务端的 optionalInteger 把"空串"当成**在但畸形** → 400，
        // 只有键缺席才是"不限定学期"。（optionalText 对空串的处理相反，别照抄它的写法。）
        if (academicYear != null) request.putData("academicYear", academicYear);
        if (semester != null) request.putData("semester", semester);
        return map(request, response -> {
            List<AdminCourseView> courses = new ArrayList<>();
            for (AdminCourseDTO dto : list(response, "courses", AdminCourseDTO.class)) {
                courses.add(course(dto));
            }
            return List.copyOf(courses);
        });
    }

    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId,
            Integer academicYear, Integer semester) {
        Message request = request(AdminCourseActions.LIST_OFFERINGS);
        request.putData("courseId", courseId);
        // 同上：不选学期时两个键都**不发**，绝不发空串或 0。
        if (academicYear != null) request.putData("academicYear", academicYear);
        if (semester != null) request.putData("semester", semester);
        return map(request, response -> {
            List<AdminOfferingView> offerings = new ArrayList<>();
            for (AdminOfferingDTO dto : list(response, "offerings", AdminOfferingDTO.class)) {
                offerings.add(offering(dto));
            }
            return List.copyOf(offerings);
        });
    }

    @Override
    public CompletableFuture<List<CourseTermView>> listOfferingTerms() {
        Message request = request(AdminCourseActions.LIST_OFFERING_TERMS);
        return map(request, response -> {
            List<CourseTermView> terms = new ArrayList<>();
            for (CourseTermDTO dto : list(response, "terms", CourseTermDTO.class)) {
                terms.add(new CourseTermView(dto.getAcademicYear(), dto.getSemester(),
                        dto.getDisplayName()));
            }
            return List.copyOf(terms);
        });
    }
```

加 import `dto.course.CourseTermDTO;`、`model.course.CourseTermView;`。

Modify `VCampusClient/src/service/MockAdminCourseService.java`。

**旧签名保留，并把它们改成委托给新签名**（否则同一份 mock 会有两套口径，测试走到哪一条取决于调用方）：

```java
    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId) {
        return listOfferings(courseId, null, null);
    }

    @Override
    public CompletableFuture<List<AdminOfferingView>> listOfferings(String courseId,
            Integer academicYear, Integer semester) {
        List<AdminOfferingView> result = new ArrayList<>();
        for (AdminOfferingView offering : offerings.values()) {
            if (!offering.getCourseId().equals(courseId)) continue;
            // 与真实 DAO 同口径：已取消的不列出；两个学期参数都给了才按学期限定。
            if ("CANCELLED".equals(offering.getStatus())) continue;
            if (academicYear != null && semester != null
                    && (offering.getAcademicYear() != academicYear
                    || offering.getSemester() != semester)) {
                continue;
            }
            result.add(offering);
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    @Override
    public CompletableFuture<List<CourseTermView>> listOfferingTerms() {
        List<CourseTermView> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AdminOfferingView offering : offerings.values()) {
            if (seen.add(offering.getAcademicYear() + "-" + offering.getSemester())) {
                terms.add(new CourseTermView(offering.getAcademicYear(), offering.getSemester(),
                        TermLabels.displayName(offering.getAcademicYear(), offering.getSemester())));
            }
        }
        terms.sort((left, right) -> left.getAcademicYear() != right.getAcademicYear()
                ? Integer.compare(right.getAcademicYear(), left.getAcademicYear())
                : Integer.compare(right.getSemester(), left.getSemester()));
        return CompletableFuture.completedFuture(List.copyOf(terms));
    }
```

Modify `MockAdminCourseService.listCourses` 成两参委托 + 四参实现：

```java
    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status) {
        return listCourses(query, status, null, null);
    }

    @Override
    public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
            Integer academicYear, Integer semester) {
        String statusFilter = blankToNull(status);
        String queryFilter = blankToNull(query);
        List<AdminCourseView> result = new ArrayList<>();
        for (AdminCourseView course : courses.values()) {
            if (statusFilter != null && !statusFilter.equals(course.getStatus())) continue;
            if (queryFilter != null && !matches(course.getCourseCode(), queryFilter)
                    && !matches(course.getCourseName(), queryFilter)) {
                continue;
            }
            result.add(academicYear == null || semester == null
                    ? course : withOfferingCount(course,
                            countOfferings(course.getCourseId(), academicYear, semester)));
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }
```

并加两个私有辅助（`AdminCourseView` 是不可变值对象，所以要复制构造）：

```java
    private int countOfferings(String courseId, int academicYear, int semester) {
        int count = 0;
        for (AdminOfferingView offering : offerings.values()) {
            if (offering.getCourseId().equals(courseId)
                    && !"CANCELLED".equals(offering.getStatus())
                    && offering.getAcademicYear() == academicYear
                    && offering.getSemester() == semester) {
                count++;
            }
        }
        return count;
    }

    private static AdminCourseView withOfferingCount(AdminCourseView course, int offeringCount) {
        return new AdminCourseView(course.getCourseId(), course.getCourseCode(),
                course.getCourseName(), course.getCourseType(), course.getCredit(),
                course.getCreditHours(), course.getDescription(), course.getPrerequisites(),
                course.isAllowCrossMajor(), course.isFinalExam(), course.getStatus(),
                offeringCount, course.getVersion());
    }
```

加 import `dto.course.TermLabels;`、`model.course.CourseTermView;`、`java.util.LinkedHashSet;`、`java.util.Set;`（按该文件已有 import 判断缺哪些）。

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Suite AdminCatalog passed`。

再跑一次确认服务层没有连带破坏：
```bash
pwsh -File scripts/test-teacher.ps1 -Suite Adjustment -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Suite Adjustment passed`。

- [ ] **Step 5: Commit**

```bash
git add VCampusClient/src/service/AdminCourseService.java \
        VCampusClient/src/service/SocketAdminCourseService.java \
        VCampusClient/src/service/MockAdminCourseService.java \
        VCampusClient/test/controller/AdminCourseCatalogControllerTest.java \
        VCampusClient/test/service/MockAdminCourseServiceTest.java \
        VCampusClient/test/service/SocketAdminCourseServiceTest.java
git commit -m "$(cat <<'EOF'
feat: carry the selected term through the admin course service

The two old signatures stay the abstract methods and gain default overloads
that carry the term, so the five unrelated test doubles keep compiling
untouched; Socket and Mock override the new ones and their old overloads become
one-line delegations, so there is only ever one request-building body and one
mock behaviour.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

> **订正**：早先这一段的 `git add` 清单把 `AddOfferingStudentDialogControllerTest` 与 `RemoveOfferingStudentDialogControllerTest` 也列了进去——那是"改签名"方案的遗留，实际交付的 default 委托方案里这两个文件**一行没动**。commit body 里的 "the three signatures changed outright rather than gaining default shims" 描述的是被裁决 R2 否决的设计，不是交付物。

---

### Task 7: 工具栏学期下拉（FXML + 控制器状态机）

**Files:**
- Modify: `VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml:18-25`
- Modify: `VCampusClient/src/resources/css/style.css`（在 `.course-admin-status-filter` 附近加镜像规则）
- Modify: `VCampusClient/src/controller/AdminCourseCatalogController.java`

**Interfaces:**
- Consumes: `AdminCourseService.listOfferingTerms()`、`listCourses(...4参)`、`listOfferings(...3参)`（Task 6）
- Produces: `AdminCourseCatalogController.loadTerms()`、`selectTerm(int index)`、`terms()`、`Objects.equals(term, currentTerm)` 形式的状态读取

- [ ] **Step 1: 写失败的测试**

**先看现状**：Task 6 **没有**给 `ControlledService` 加任何学期相关成员（它正确地没写下面那条用例）。所以本任务要**先加 fixture 成员，再加用例**。

在 `AdminCourseCatalogControllerTest.ControlledService` 里加：

```java
        private List<CourseTermView> terms = List.of();

        void setTerms(List<CourseTermView> next) {
            terms = List.copyOf(next);
        }

        @Override
        public CompletableFuture<List<CourseTermView>> listOfferingTerms() {
            return CompletableFuture.completedFuture(terms);
        }
```

并把它的 `listCourses` 覆写改成四参，记录进**既有的** `listCalls`——`listCalls` 在 Task 6 已经改成四段式，所以学期断言直接读它即可，**不要再加平行的 `courseCalls`**（那份副本与 `listCalls` 逐字重复，是第二份真相）：

```java
        @Override
        public CompletableFuture<List<AdminCourseView>> listCourses(String query, String status,
                Integer academicYear, Integer semester) {
            listCalls.add(query + "|" + status + "|" + academicYear + "|" + semester);
            if (!courseResults.isEmpty()) return courseResults.removeFirst();
            return CompletableFuture.completedFuture(authoritative);
        }
```

再把 `listOfferings` 覆写改成三参（body 不变）。加 import `model.course.CourseTermView`。

然后写用例——它依赖本任务的 `loadTerms()`/`terms()`/`selectTerm()`，写在 Task 6 里会让整个 `VCampusClient/test` 编译不过：

在 `AdminCourseCatalogControllerTest` 里新增：

```java
    /**
     * 学期下拉的选项来自服务端回显的 displayName；下拉变化必须带着学期重新加载。
     */
    private static void testTermFilterDrivesBothLoads() {
        ControlledService service = new ControlledService();
        service.setTerms(List.of(
                new CourseTermView(2027, 3, "2027-2028 春学期"),
                new CourseTermView(2026, 2, "2026-2027 秋学期")));
        AdminCourseCatalogController controller = controller(service, new Recorder());
        controller.loadTerms();

        require(service.listCalls.contains("null|null|2027|3"),
                "the newest term must be selected by default, saw " + service.listCalls);
        require(controller.terms().size() == 2, "both terms must be offered");

        controller.selectTerm(1);
        require(service.listCalls.contains("null|null|2026|2"),
                "selecting a term must reload the course list with that term, saw "
                        + service.listCalls);
    }
```

加进 `main` 的调用序列，并加 import `model.course.CourseTermView`。

> 断言里是 `null|null|2027|3` 而不是 `""|null|...`：控制器在调用服务前已经把空搜索词 `blankToNull` 掉了，假服务收到的是 `null`。

> **本条用例覆盖不到生产入口。** 它直接调 `controller.loadTerms()`，所以把 `refresh()` 改回 `loadCourses(query, status)`（下拉在刷新时静默失效）它照样绿。最终评审把"`refresh()` → `loadTerms()` → 带学期的课程加载"这条接线、`termLoadGeneration` 守卫、以及失败降级分支三条一起判定为**必须补**的覆盖缺口；补法是给 `ControlledService` 加 `Deque<CompletableFuture<List<CourseTermView>>>` + `enqueueTerms(...)`（照 `courseResults`/`enqueueCourseResult` 的样子），再写三条用例：①`refresh()` 到达学期加载且随后发**带学期**的课程请求；②两次学期加载乱序返回时旧的被丢弃；③学期加载异常完成时课程仍然加载、`term` 退成 null、不显示页面错误。**批次②③④⑤ 若在本文件上加学期相关行为，先确认这三条已经在树里。**

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `controller.AdminCourseCatalogControllerTest` 编译失败——`loadTerms`、`selectTerm`、`terms()` 都不存在。

- [ ] **Step 3: 加 FXML 控件**

Modify `VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml:18-25`，在 `searchField` **之前**插入学期下拉（放在最左，因为它是这一页作用域最大的筛选）：

```xml
                        <ComboBox fx:id="termFilter" prefWidth="176.0" promptText="学期" styleClass="course-admin-term-filter" />
```

`ComboBox` 已在文件顶部的 `<?import javafx.scene.control.ComboBox?>` 里，不用重复 import。

Modify `VCampusClient/src/resources/css/style.css`：找到 `.course-admin-status-filter` 的定义，把选择器列表扩成同时含 `.course-admin-term-filter`（`prefWidth` 在 FXML 里已写，CSS 不必再给宽度）：

```css
.course-admin-status-filter, .course-admin-term-filter {
```

> 该选择器是既有规则，只改选择器行、不动规则体。

- [ ] **Step 4: 加控制器状态与加载**

Modify `VCampusClient/src/controller/AdminCourseCatalogController.java`。

加字段（放在既有的 `private ComboBox<String> statusFilter;` 附近）：

```java
    @FXML private ComboBox<CourseTermView> termFilter;
```

加状态（放在既有的 `private String status = STATUS_ALL;` 附近）：

```java
    private List<CourseTermView> terms = List.of();
    private CourseTermView term;
    private boolean syncingTerms;
    private long termLoadGeneration;
```

把 `initialize()` 换成：

```java
    @FXML
    public void initialize() {
        statusFilter.getItems().setAll(STATUS_ALL, STATUS_ACTIVE, STATUS_ARCHIVED);
        statusFilter.setValue(STATUS_ALL);
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!syncingFilters) applyFilters(newValue, statusFilter.getValue());
        });
        statusFilter.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!syncingFilters) applyFilters(searchField.getText(), newValue);
        });
        if (termFilter != null) {
            termFilter.getSelectionModel().selectedIndexProperty().addListener(
                    (observable, oldValue, newValue) ->
                            selectTerm(newValue == null ? -1 : newValue.intValue()));
        }
        render();
    }
```

`refresh()` 换成（刷新时**同时**重取学期：管理员刚在别处建了教学班，下拉必须跟着长出来）：

```java
    @FXML
    public void refresh() {
        if (searchField != null) {
            query = searchField.getText() == null ? "" : searchField.getText();
        }
        if (statusFilter != null && statusFilter.getValue() != null) {
            status = statusFilter.getValue();
        }
        loadTerms();
    }
```

`loadCourses` 换成带学期，并把调用方一起改：

> **为什么 `initialize()` 里不调 `loadTerms()`**：这一页的加载入口是 `refresh()`，由外壳 `AdminCourseManagementController.showCourses()` 的 `coursePageController::refresh` 触发（`AdminCourseManagementView.fxml:29` 用 `fx:include fx:id="coursePage"`）。`initialize()` 今天也只 `render()`、不加载课程，学期同理。

```java
    private void loadCourses(String nextQuery, String nextStatus) {
        loadCourses(nextQuery, nextStatus, term);
    }

    private void loadCourses(String nextQuery, String nextStatus, CourseTermView nextTerm) {
        String sentQuery = blankToNull(nextQuery);
        String sentStatus = STATUS_ALL.equals(nextStatus) ? null : blankToNull(nextStatus);
        Integer sentYear = nextTerm == null ? null : nextTerm.getAcademicYear();
        Integer sentSemester = nextTerm == null ? null : nextTerm.getSemester();
        long generation = ++listGeneration;
        loading = true;
        errorText = null;
        render();
        service.listCourses(sentQuery, sentStatus, sentYear, sentSemester)
                .whenComplete((loaded, failure) -> fxExecutor.accept(() -> {
                    if (generation != listGeneration) return;
                    loading = false;
                    if (failure != null) {
                        errorText = "课程加载失败，请重试";
                        render();
                        return;
                    }
                    errorText = null;
                    courses = List.copyOf(loaded == null ? List.of() : loaded);
                    render();
                }));
    }
```

加学期加载与切换（放在 `loadCourses` 之前）：

```java
    /**
     * 重新取学期下拉。选择在刷新中保持不变；选中的学期消失了（只可能发生在演示库里）
     * 就退回最近学期。学期加载与课程加载各有自己的 generation，互不覆盖。
     */
    void loadTerms() {
        long generation = ++termLoadGeneration;
        service.listOfferingTerms().whenComplete((loaded, failure) ->
                fxExecutor.accept(() -> {
                    if (generation != termLoadGeneration) return;
                    if (failure != null) {
                        // 学期取不到不该把整页打红：退化成"不限定学期"，课程列表照常显示。
                        term = null;
                        renderTerms();
                        loadCourses(query, status, null);
                        return;
                    }
                    terms = List.copyOf(loaded == null ? List.of() : loaded);
                    CourseTermView keep = term;
                    if (keep == null || !terms.contains(keep)) {
                        keep = terms.isEmpty() ? null : terms.get(0);
                    }
                    term = keep;
                    renderTerms();
                    loadCourses(query, status, term);
                }));
    }

    /** 下拉变化入口；下标越界或与当前相同都不触发加载。 */
    void selectTerm(int index) {
        if (syncingTerms || index < 0 || index >= terms.size()) return;
        CourseTermView next = terms.get(index);
        if (next.equals(term)) return;
        term = next;
        loadCourses(query, status, term);
    }

    private void renderTerms() {
        if (termFilter == null) return;
        syncingTerms = true;
        try {
            termFilter.getItems().setAll(terms);
            termFilter.getSelectionModel().select(term == null ? -1 : terms.indexOf(term));
            termFilter.setDisable(terms.isEmpty());
        } finally {
            syncingTerms = false;
        }
    }

    List<CourseTermView> terms() {
        return terms;
    }
```

`toggleOfferings` 里那处 `service.listOfferings(course.getCourseId())` 换成：

```java
        service.listOfferings(course.getCourseId(),
                        term == null ? null : term.getAcademicYear(),
                        term == null ? null : term.getSemester())
```

`reloadOfferingRow` 里那一处同理。

加 import `model.course.CourseTermView;`。

- [ ] **Step 5: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 4 行 `Running …`，`Suite AdminCatalog passed`。

- [ ] **Step 6: Commit**

```bash
git add VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml \
        VCampusClient/src/resources/css/style.css \
        VCampusClient/src/controller/AdminCourseCatalogController.java
git commit -m "$(cat <<'EOF'
feat: add the global term picker to the admin course catalog toolbar

Picking a term re-scopes both the per-course offering count and the expanded
offering list while the course list itself stays complete, so a course that
has no class this term is still reachable and can be given one.

A failed term load degrades to "every term" instead of blanking the page.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: 行内学期文案走 `TermLabels`

下拉项的文字是服务端回的 `displayName`（`2026-2027 秋学期`），而教学班行上的学期是客户端拼的 `2026 学年 第 2 学期`。不统一的话，同一个页面上两种写法并存，用户没法把下拉和行对上。

**Files:**
- Modify: `VCampusClient/src/controller/AdminCourseCatalogController.java:691-693`

**Interfaces:**
- Consumes: `TermLabels.displayName(int, int)`（Task 1）
- Produces: 无

- [ ] **Step 1: 写失败的测试**

Modify `VCampusClient/test/controller/AdminCourseCatalogControllerTest.java`，加一个直接钉住文案的用例：

```java
    /**
     * 教学班行上的学期文案必须与下拉项同源，否则同一屏上会出现两种写法。
     */
    private static void testOfferingTermTextMatchesThePicker() {
        require("2026-2027 秋学期".equals(
                        AdminCourseCatalogController.termText(offering(2026, 2))),
                "the offering row must use the same wording as the term picker");
        require("2026-2027 暑期学校".equals(
                        AdminCourseCatalogController.termText(offering(2026, 1))),
                "the row must degrade with the shared labels, not a second spelling");
    }
```

其中 `offering(int academicYear, int semester)` 是文件里既有的假 `AdminOfferingView` 构造助手（学期文案只取决于学年与学期，其余字段填占位值）。加进 `main` 的调用序列。

> **不要写 `termTextForTest(int, int)` 这种"直通助手"。** 早先的草稿让测试经一个只把参数转交给 `TermLabels.displayName` 的静态助手断言——那等于把被测逻辑重写一遍，生产方法 `termText` 将来回退也不会变红（评审在 `c9bbf10` 里把这个助手删掉了）。**测试必须构造真实的 `AdminOfferingView` 并调真实的 `termText(...)`**，这才是那条行渲染路径本身。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败，`AdminCourseCatalogController.termText(AdminOfferingView)` 不存在（此时行内文案还是内联拼接）。

- [ ] **Step 3: 写实现**

Modify `VCampusClient/src/controller/AdminCourseCatalogController.java:691-693`：

```java
    /** 与学期下拉、学生端、教师端同源；不要在这里另拼一套中文。 */
    static String termText(AdminOfferingView offering) {
        return TermLabels.displayName(offering.getAcademicYear(), offering.getSemester());
    }
```

**只加这一个方法——不要为测试另外开一个收 `int, int` 的直通重载。**

加 import `dto.course.TermLabels;`。

> **批次③ 的预留**：同一行的"取消教学班"按钮现在只按 `CANCELLED.equals(status)` 禁用（`:428`）。批次③ 会把它收紧成 `!"NOT_OPEN".equals(status)`。**本任务不要动它。**

- [ ] **Step 4: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: `Suite AdminCatalog passed`。

- [ ] **Step 5: Commit**

```bash
git add VCampusClient/src/controller/AdminCourseCatalogController.java \
        VCampusClient/test/controller/AdminCourseCatalogControllerTest.java
git commit -m "$(cat <<'EOF'
refactor: render offering terms with the shared label so they match the picker

The row used to say "2026 学年 第 2 学期" while the dropdown said
"2026-2027 秋学期" on the same screen.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: 教学班编辑器：学期改下拉，修掉越界

丁3。"学期"现在是个文本框，提示写 **`1 或 2`**（定义域其实是 1..3），而且 `positiveIntOrNull` **只校验 `> 0`、没有上界**——界面上能填 99，服务端才用"学期无效"拒掉。

**Files:**
- Modify: `VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml:27-30`
- Modify: `VCampusClient/src/controller/OfferingEditorDialogController.java`

**Interfaces:**
- Consumes: `TermLabels.label(int)`（Task 1）
- Produces: `OfferingEditorDialogController.prepareForCreate(String courseId, int academicYear, int semester)` —— **签名变更**，多带一个默认学期，好让"开设教学班"从当前选中的学期起步

- [ ] **Step 1: 写失败的测试**

**新建** `VCampusClient/test/controller/OfferingEditorDialogControllerTest.java`。它**只测静态纯逻辑**，**不实例化控制器**——`new OfferingEditorDialogController()` 时所有 `@FXML` 字段都是 null（没走 FXMLLoader），`prepareForCreate`/`collectRequest` 会立刻 NPE：

```java
package controller;

import java.util.List;

public final class OfferingEditorDialogControllerTest {

    private OfferingEditorDialogControllerTest() {
    }

    public static void main(String[] args) {
        List<String> options = OfferingEditorDialogController.semesterOptions();
        require(options.size() == 3,
                "the term picker must offer exactly the three terms the server accepts, saw "
                        + options);
        require(options.equals(List.of("暑期学校", "秋学期", "春学期")),
                "term options must use the shared labels in semester order, saw " + options);

        for (int semester = 1; semester <= 3; semester++) {
            require(OfferingEditorDialogController.semesterCode(
                            OfferingEditorDialogController.semesterLabel(semester)) == semester,
                    "label and code must round-trip for semester " + semester);
        }
        require(OfferingEditorDialogController.semesterCode(null) == 0,
                "an unselected term must not be read as semester 1");
        require(OfferingEditorDialogController.semesterCode("99") == 0,
                "an out-of-range term must be rejected rather than accepted");
        require(OfferingEditorDialogController.semesterCode("第4学期") == 0,
                "TermLabels' fallback wording must not be a selectable term");

        System.out.println("Offering editor dialog test passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

把 `controller.OfferingEditorDialogControllerTest` 加进 `scripts/test-teacher.ps1` 里 `AdminCatalog` 套件的 `Client` 列。

> `prepareForCreate` 的学期预填**没有**自动化覆盖（它需要真 FXML），由 Task 10 的真机验收确认。这是**有意**的取舍，不要为了凑覆盖率去起 JavaFX 工具包。

- [ ] **Step 2: 跑测试，确认失败**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 编译失败——`OfferingEditorDialogController.semesterOptions()`、`semesterLabel(int)`、`semesterCode(String)` 三个都还不存在。
（**注意**：这里**不会**出现 `prepareForCreate(String,int,int)` 的报错——那个用例不构造控制器、不调它。若你看到它，说明测试写歪了。）

- [ ] **Step 3: 改 FXML**

Modify `VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml:27-30`，把学期那一行换掉：

```xml
                <Label styleClass="course-admin-field-label" text="学期" GridPane.rowIndex="2" />
                <ComboBox fx:id="semesterField" maxWidth="Infinity" styleClass="course-admin-dialog-field" GridPane.columnIndex="1" GridPane.rowIndex="2" />
```

同时把学年的提示从 `如 2026` 改成 `如 2027`（旧值 2026 已经是上一个学年，提示本身在教用户填过期的年份）：

```xml
                <TextField fx:id="academicYearField" promptText="如 2027" styleClass="course-admin-dialog-field" GridPane.columnIndex="1" GridPane.rowIndex="1" />
```

`ComboBox` 已在该 FXML 的 import 里。

- [ ] **Step 4: 改控制器**

Modify `VCampusClient/src/controller/OfferingEditorDialogController.java`：

- 把 `@FXML private TextField semesterField;` 换成 `@FXML private ComboBox<String> semesterField;`
- `initialize()` 里补上选项（字段可能为 null——该控制器的既有写法就是这么防御的）：

```java
        if (semesterField != null) {
            semesterField.getItems().setAll(TERM_LABELS);
            semesterField.setValue(TERM_LABELS.get(1));
        }
```

并在类顶部加常量：

```java
    private static final List<String> TERM_LABELS =
            List.of(TermLabels.label(1), TermLabels.label(2), TermLabels.label(3));
```

- `prepareForCreate` 换成两参：

```java
    public void prepareForCreate(String courseId, int academicYear, int semester) {
        this.courseId = courseId;
        editing = null;
        dialogTitleLabel.setText("新增教学班");
        statusField.getItems().setAll(NOT_OPEN_LABEL, OPEN_LABEL);
        statusField.setValue(NOT_OPEN_LABEL);
        offeringCodeField.clear();
        academicYearField.setText(String.valueOf(academicYear));
        semesterField.setValue(TermLabels.label(semester));
        capacityField.setText("60");
        teacherUidField.clear();
        assistantUidField.clear();
        setValidationMessage(null);
    }
```

- `prepareForEdit` 里 `semesterField.setText(String.valueOf(offering.getSemester()));` → `semesterField.setValue(TermLabels.label(offering.getSemester()));`

- `collectRequest` 里 `parseInt(semesterField.getText())` → `semesterCode(semesterField.getValue())`

- `validate()` 里原来那两段：

```java
        Integer year = positiveIntOrNull(academicYearField.getText());
        ...
        Integer semester = positiveIntOrNull(semesterField.getText());
        if (semester == null) {
            setValidationMessage("学期必须是大于 0 的整数");
            return false;
        }
```
换成：

```java
        Integer year = positiveIntOrNull(academicYearField.getText());
        ...
        if (semesterCode(semesterField.getValue()) == 0) {
            setValidationMessage("请选择学期");
            return false;
        }
```

- 把标签↔代码的映射做成 **static package-private**（这样测试不必构造控制器、不必起 FXML——控制器实例化时 `@FXML` 字段全为 null，任何碰控件的方法都会 NPE）：

```java
    /** 学期下拉的选项，学期序。测试与界面共用同一个来源。 */
    static List<String> semesterOptions() {
        return TERM_LABELS;
    }

    static String semesterLabel(int semester) {
        return TermLabels.label(semester);
    }

    static int semesterCode(String label) {
        for (int semester = 1; semester <= 3; semester++) {
            if (TERM_LABELS.get(semester - 1).equals(label)) return semester;
        }
        return 0;
    }
```

加 import `dto.course.TermLabels;`、`java.util.List;`。

- 最后改**唯一的调用方** `AdminCourseCatalogController.openOfferingEditor` 里的：

```java
            dialogController.prepareForCreate(course.getCourseId());
```
→
```java
            dialogController.prepareForCreate(course.getCourseId(),
                    term == null ? java.time.Year.now().getValue() : term.getAcademicYear(),
                    term == null ? 2 : term.getSemester());
```

然后确认没有第二个调用方：

```bash
grep -rn "prepareForCreate" VCampusClient/src VCampusClient/test
```
Expected: 只有 `AdminCourseCatalogController.java` 一处调用、`OfferingEditorDialogController.java` 一处定义，加上新建的对话框测试里的那一处。**若出现别的调用方，一并按新签名改掉**（`CourseEditorDialogController.prepareForCreate()` 是另一个类的无参版本，别改错）。

- [ ] **Step 5: 跑测试，确认通过**

Run:
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 5 行 `Running …`（多了新对话框测试），`Suite AdminCatalog passed`。

- [ ] **Step 6: Commit**

```bash
git add VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml \
        VCampusClient/src/controller/OfferingEditorDialogController.java \
        VCampusClient/src/controller/AdminCourseCatalogController.java \
        VCampusClient/test/controller/OfferingEditorDialogControllerTest.java \
        scripts/test-teacher.ps1
git commit -m "$(cat <<'EOF'
fix: replace the free-text semester field with a picker

The old field hinted "1 或 2" while the domain is 1..3, and it only validated
"greater than zero", so 99 reached the server before being rejected. Creating
an offering now starts from the term the admin is looking at.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: 真机验收（不可跳过）

本批改的是 FXML 与控制器渲染路径。**带 `main` 的断言脚本只证明逻辑，不证明界面能加载**——FXML 的属性名只在运行期校验，一个 `fx:id` 拼错或属性不可写会在这里才炸。跑不够这一段就不能说完成。

**Files:**
- Create: `VCampusClient/test/ui/AdminCatalogFxmlLoadTest.java`
- Modify: `scripts/test-teacher.ps1`（`AdminCatalog` 套件的 `Gui` 列）
- Modify: `.superpowers/sdd/2026-09-18-admin-catalog-term-filter/acceptance.md`（Step 5 写的验收记录）

**Interfaces:**
- Consumes: Task 1–9 的全部产物
- Produces: 一份人工验收记录

- [ ] **Step 1: FXML 装载门（自动化证明两个改过的 FXML 能装载）**

静态核对**不足**：FXML 的属性名只在运行期校验，一个不可写的属性名会一路骗过 `grep`，直到 `FXMLLoader.load()` 抛 `PropertyNotFoundException`。而本计划改了**两个** FXML，`AdminCatalog` 套件的 `Gui` 列是空的，`ui.AdminCourseUiSmokeTest` 又有**既有红**——也就是说，这九轮下来 FXML 接线**一秒钟都没被机器验证过**。

新建 `VCampusClient/test/ui/AdminCatalogFxmlLoadTest.java`（package `ui`；**不要**写成 `ui.*Preview`，那种类不会自己退出、会挂住套件）：

```java
package ui;

import controller.OfferingEditorDialogController;
import dto.course.TermLabels;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.stage.Stage;
import service.AdminCourseServices;
import service.MockAdminCourseService;

/**
 * FXML 装载门：本计划改过的两个界面必须能真的装载出来，且工具栏上的学期下拉是控件、
 * 有选项、并且默认选中了一个。只看 FXML 能不能装载——不驱动交互。
 */
public final class AdminCatalogFxmlLoadTest extends Application {

    public static void main(String[] args) {
        AdminCourseServices.install(new MockAdminCourseService());
        Application.launch(AdminCatalogFxmlLoadTest.class, args);
    }

    /**
     * 学期下拉是**异步**装上的：外壳 {@code initialize()} → {@code showCourses()} →
     * {@code refresh()} → {@code loadTerms()}，而 {@code loadTerms()} 把回调经
     * {@code Platform.runLater} 投进 FX 事件队列——{@code start()} 返回前那些回调不会执行。
     * 所以断言要再投一个 {@code runLater}：它排在已排队的那批回调之后，看到的是加载完成后的
     * 控件状态。这是把断言对准真实时序，不是放宽断言。
     */
    @Override
    public void start(Stage stage) throws Exception {
        Parent shell = new FXMLLoader(
                AdminCatalogFxmlLoadTest.class.getResource("/resources/fxml/AdminCourseManagementView.fxml"))
                .load();
        require(shell != null, "管理员外壳必须装载成功");
        Scene scene = new Scene(shell);

        Platform.runLater(() -> {
            try {
                verifyLoadedShell(scene);
            } catch (Throwable failure) {
                failure.printStackTrace();
                Platform.exit();
                System.exit(1);
                return;
            }
            System.out.println("Admin catalog FXML load test passed.");
            Platform.exit();
        });
    }

    private static void verifyLoadedShell(Scene scene) throws Exception {
        ComboBox<?> picker = (ComboBox<?>) scene.lookup("#termFilter");
        require(picker != null, "外壳里必须存在 #termFilter（学期下拉）");
        require(!picker.getItems().isEmpty(),
                "学期下拉必须有选项——没有选项说明 loadTerms() 没跑到，或假服务没被装上");
        require(picker.getValue() != null, "学期下拉必须默认选中一个学期");

        FXMLLoader editorLoader = new FXMLLoader(
                AdminCatalogFxmlLoadTest.class.getResource("/resources/fxml/OfferingEditorDialog.fxml"));
        Parent editor = editorLoader.load();
        require(editor != null, "教学班编辑器必须装载成功");
        Scene editorScene = new Scene(editor);
        require(editorScene.lookup("#semesterField") instanceof ComboBox,
                "教学班编辑器的学期必须已经是下拉，不再是文本框");

        // 「开设教学班」从当前选中的学期起步——这条是生产路径，不是只测 static 助手。
        // Task 9 的评审指出：`semesterLabel(int)` 在生产侧没有调用方，所以那条往返断言
        // 走的是测试专用路径，将来 prepareForCreate 不再用 TermLabels 也照样绿。这里把它钉住。
        OfferingEditorDialogController editorController = editorLoader.getController();
        editorController.prepareForCreate("101", 2027, 3);
        ComboBox<?> semesterPicker = (ComboBox<?>) editorScene.lookup("#semesterField");
        require(TermLabels.label(3).equals(semesterPicker.getValue()),
                "prepareForCreate 必须把学期下拉预选到传入的学期，saw " + semesterPicker.getValue());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
```

> **为什么不能在 `start()` 里直接断言。** 早先的草稿是同步版本：`start()` 里 `load()` 完立刻 `scene.lookup("#termFilter")` 并断言 `!picker.getItems().isEmpty()`。那段代码**第一次跑就会红**——学期下拉的选项是 `loadTerms()` 里经 `Platform.runLater` 投进事件队列的，`start()` 返回前不会执行，所以那一刻下拉还是空的。上面的形状把断言再投一次 `runLater`。
>
> **这次重排队是确定的，不是"赌它跑得够快"。** FX 事件队列是单线程 FIFO：`start()` 在 FX 线程上运行时，`loadTerms()` 排入的回调**已经在队里**，我们新投的这一个必然排在它们**之后**；`start()` 返回后队列按序执行，所以断言看到的一定是"学期已装好"的状态。唯一的前提是"恰好一跳"——将来 `loadTerms()` 里多加一层跳转会让它**假红**（方向安全：只会红，不会假绿），那时把断言再多投一跳即可。



把 `ui.AdminCatalogFxmlLoadTest` 加进 `AdminCatalog` 套件的 **`Gui` 列**（`Gui = @('ui.AdminCatalogFxmlLoadTest')`）。

> **注意 `Gui` 与 `MySql` 列不同**：`MySql` 列只追加 `mysql` 参数、**不产生运行项**（所以才要把 MySQL 类同时放进 `Server` 列）；而 `Tcp`/`Gui` 列在传了对应开关时**会**产生运行项。所以这个类的运行方式就是 `-WithGui`。

Run（本步需要完整 JavaFX SDK，`-JavaFxHome` 的默认值在本机就是对的）：
```bash
pwsh -File scripts/test-teacher.ps1 -Suite AdminCatalog -WithMySql -WithGui -TestConfigPath .codex-tmp/t1config/resources/db.properties
```
Expected: 5 行 `Running …` **再加 1 行** `Running ui.AdminCatalogFxmlLoadTest`（共 6 行），退出码 0，末行 `Suite AdminCatalog passed`。

**若它红了**，那就是这一步的价值所在：FXML 接线或学期下拉的装载路径真的有问题，回去修对应任务（大概率是 Task 7 的 FXML/CSS 或 Task 9 的编辑器），不要绕过去。

---

- [ ] **Step 1b: 静态复核（在装载门之后做，作为补充而不是替代）**

`.codex-tmp/` 下**已经没有任何 FXML 校验工具**（`ParseFxml.java` / `CheckFxmlWiring.java` 都已不在），所以只能手工核对：

```bash
grep -n "termFilter\|semesterField" \
  VCampusClient/src/controller/AdminCourseCatalogController.java \
  VCampusClient/src/controller/OfferingEditorDialogController.java \
  VCampusClient/src/resources/fxml/AdminCourseCatalogView.fxml \
  VCampusClient/src/resources/fxml/OfferingEditorDialog.fxml
```

逐条确认：FXML 里的 `fx:id` 与控制器 `@FXML` 字段名**逐字相同**；FXML 用到的属性都是**可写**的（**`disable` 可写、`disabled` 不可写**——这个坑踩过一次）；新增的 `ComboBox` 在两个 FXML 的 `<?import?>` 里都已存在。

> 静态核对**不足以**证明它能加载，FXML 的属性名只在运行期校验——所以 Step 2/3 必须真的跑起来。

- [ ] **Step 2: 重打包**

```bash
pwsh -File package.ps1 -JavaHome D:\DevTools\Java\jdk25
```
**打包前先关掉正在运行的服务端与客户端**（jar 被占用时 `jar -cfm` 会失败而脚本**仍然打印 SUCCESS**）。

打包后确认 jar 真的更新了、且带上 JavaFX 原生 DLL：
```bash
ls -l dist/VCampusClient/VCampusClient.jar dist/VCampusServer/VCampusServer.jar
unzip -p dist/VCampusClient/VCampusClient.jar resources/fxml/AdminCourseCatalogView.fxml | grep termFilter
ls dist/VCampusClient/bin/*.dll 2>/dev/null | head
```
Expected: 两个 jar 的时间戳是刚才的；`grep termFilter` 有输出；`bin/*.dll` 非空。**若 DLL 为空**，手动把 `D:\DevTools\Java\javaFX\openjfx-25.0.4_windows-x64_bin-sdk\javafx-sdk-25.0.4\bin\*.dll` 复制到 `dist/VCampusClient/bin/`（`package.ps1:109` 硬编码的路径在本机不存在，它静默跳过了复制）。

- [ ] **Step 3: 起服务端与客户端**

先确认 `VCampusServer/src/resources/db.properties` 指向你要看的那个库（本工作树里它指向演示库 `virtual_campus`，那正是要看界面时该用的库）。

```bash
cd dist/VCampusServer && java -Dvcampus.library.files="D:/JavaProject/VCampus/.worktrees/course-management-client-integration/library-files" -jar VCampusServer.jar
```
另开一个终端：
```bash
cd dist/VCampusClient && java "-Djava.library.path=D:/DevTools/Java/javaFX/openjfx-25.0.4_windows-x64_bin-sdk/javafx-sdk-25.0.4/bin" -jar VCampusClient.jar
```
Expected: 服务端打印监听 8888 而不是「数据库不可用」；客户端窗口打开（`Unsupported JavaFX configuration: classes were loaded from unnamed module` 只是警告）。

- [ ] **Step 4: 按验收清单逐条点**

以管理员登录，进入课程目录页。演示库里 CS101(1001) 有 2001/2002(2026-2) 和 2003(2027-3)。

| # | 操作 | 期望 |
|---|---|---|
| 1 | 打开页面 | 工具栏最左出现学期下拉，默认选中**最近学期**（2027-2028 春学期） |
| 2 | 看 CS101 那行 | "教学班 1 个"（只有 2003 属于 2027-3） |
| 3 | 展开 CS101 | 只有 2003 一行 |
| 4 | 下拉切到 **2026-2027 秋学期** | CS101 变成"教学班 2 个"，展开只有 2001/2002 |
| 5 | 随便选一门本学期没课的课程 | 它**仍在列表里**、写"教学班 0 个"——**不能被过滤掉** |
| 6 | 点某门课的"开设教学班" | 编辑器里学年前缀是当前学期、学期下拉已选中当前学期 |
| 7 | 学期下拉的选项 | 只有 3 个中文项（暑期学校/秋学期/春学期），**没有任何数字输入框** |
| 8 | 在行上对比文案 | 行的学期文字与下拉项**同一种写法**（如 `2026-2027 秋学期`） |
| 9 | 搜索框 + 状态筛选 + 切学期 | 切学期时搜索词与状态筛选**保持不变** |
| 10 | 快速连点学期下拉 | 最终显示的是最后选中的那个学期（无过期响应覆盖） |

任何一条不符，**回到对应任务改并重跑该任务的套件**，不要在这里打补丁。

- [ ] **Step 5: 记录验收结果**

把上面 10 条的实测结果写进 `.superpowers/sdd/2026-09-18-admin-catalog-term-filter/acceptance.md`（含：跑的是哪个库、截图路径、未通过项）。这份记录要能被下一个会话复核。

- [ ] **Step 6: Commit**

```bash
git add .superpowers/sdd/2026-09-18-admin-catalog-term-filter/
git commit -m "$(cat <<'EOF'
docs: record the admin catalog term picker acceptance run

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## 自检清单（写完计划后跑的，不是给执行者的）

**Spec 覆盖**

| findings 条目 | 落在哪个任务 |
|---|---|
| 丁1 教学班列表没有学期筛选 | Task 2（学期来源）+ Task 3（筛选）+ Task 7（下拉） |
| 丁2 列表不过滤 status、与计数口径打架 | Task 3（列表统一 `status<>4`）+ Task 4（计数学期化） |
| 丁3 没有学期概念、学期只能手打数字、提示与定义域不符 | Task 7（默认最近学期）+ Task 9（编辑器改下拉、修越界） |
| 丙6 学期标签多处不一致 | Task 1（共享函数）+ Task 8（目录页同源）。**排课方案名那处刻意留给批次⑤** |

**明确不在本计划**：戊2/戊3（批次④）、丙7/丙8、删除方案、删除课程、取消教学班收紧、布局与"管理学生"合并（批次②/③）。执行者看到别去动。

**已知风险**

1. `AdminCourseCatalogControllerTest` / `AdminOfferingMySqlTest` / `AdminCourseCatalogMySqlTest` 三个类**从未运行过**，Task 1 Step 6 量出的既有红不属于本计划。批次②/③ 的会话必须读到那份基线。
2. `AdminCourseCatalogSocketEndToEndTest` **仍然没有注册**：它的 JDBC 配置是硬编码的 `VCampusServer/src/resources/db.properties`（不读 `--config=`），在本工作树里指向演示库，会以 `Refusing …` 硬失败。修它需要改测试自己的配置解析，属独立小改动，**本计划不做**，但要写进最终报告的遗留项。
3. `ui.AdminCourseUiSmokeTest` 有**既有红**（它的 `TogglableAdminCourseService` 没有委派 6 个审批方法，接口 default 抛 `UnsupportedOperationException`），所以它**不能**被注册进 `Gui` 列当作本批的验收手段——Task 10 走人工验收。
4. 学期下拉的来源是 `course_offering` 已有的学期：**一个还没有任何教学班的学期不在下拉里**。与教师端现状一致。要"凭空新建学期"得先建第一个教学班，或等将来加教学日历维护界面。
5. 学期化计数让"教学班 N 个"的含义依赖当前选中的学期。若实际用起来有歧义，把它改成"本学期教学班 N 个"是一行字符串改动——但那是**用户看得见的产品用词**，改动前先问。
