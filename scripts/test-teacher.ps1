# 教师端阶段验证入口（TDD 提交流程使用）。
#
# 用法：
#   pwsh -File scripts/test-teacher.ps1
#       不带参数时只列出已知套件并退出 0，不编译、不连接数据库。
#   pwsh -File scripts/test-teacher.ps1 -Suite Foundation -WithMySql -TestConfigPath <db.properties>
#       分目录编译 Common/Server/Client，串行运行 Foundation 套件的全部测试类（含受保护的 MySQL 用例）。
#   pwsh -File scripts/test-teacher.ps1 -Suite <name> -WithMySql [-WithTcp] [-WithGui]
#       [-TestConfigPath <db.properties>] [-JavaFxHome <sdk>]
#       三个开关可以任意组合，但每个都必须被所选套件声明过（见下面的约定）。`-WithGui` 用完整
#       JavaFX SDK 启动工具包；`-JavaFxHome` 默认指向本机已解压的 openjfx-25.0.4 SDK，缺少原生 DLL
#       时立即报错而不是静默降级。
#
# **两个参数实际上是必需的**（两条都实测过）：
#   * `-WithMySql` —— 六个套件都声明了 MySQL 门控的类，少传它会在选测试的阶段直接报错退出
#     （`Suite <name> declares MySQL-gated tests (…); without -WithMySql they would print SKIP and
#     still exit 0`），而不是跑完再让你误以为通过。
#   * `-TestConfigPath` —— 默认值是 `VCampusServer/src/resources/db.properties`，而那份 git-ignored
#     文件在本工作树里被另一个会话指向了**演示库** `virtual_campus`。不传它时，连库的用例会以
#     `AssertionError: Refusing live migration test: JDBC database must be exactly
#     virtual_campus_course_test` 硬失败——它们拒绝跑演示库，也不会把这种失败降级成 SKIP。
#     跑受保护测试库请传 `-TestConfigPath .codex-tmp/t1config/resources/db.properties`。
#
# 约定：javac/java 非零退出立即停止；SKIP 永远不等于 PASS；每次运行在 .codex-tmp/teacher/<套件>-<唯一值>
# 下用独立输出目录；不自动创建或删除数据库。
#
# 三列开关（MySql / Tcp / Gui）一律「双向」校验：
#   * 套件声明了该类测试却没传开关 → 报错退出：那些类根本不会出现在运行列表里，一次「全绿」实际上
#     什么都没跑；
#   * 传了开关而套件一个都没声明 → 同样报错退出，避免「开了开关看起来跑了什么」。
# MySQL 这一列是 Task 4 补上的：MySQL 门控的类自带 `mysql` 开关，未传时打印 SKIP 并以 0 退出，而脚本
# 只看退出码，于是「每个库断言都被跳过」会被记成 Suite passed —— 这是这套脚本里最贵的一种假绿。把
# 门控的类声明出来之后，少传 `-WithMySql` 在选测试的阶段就会失败，而不是跑完才让人误以为通过。

[CmdletBinding()]
param(
    [string]$Suite = '',
    [switch]$WithMySql,
    [switch]$WithTcp,
    [switch]$WithGui,
    [string]$TestConfigPath = '',
    [string]$JavaFxHome = 'D:\DevTools\Java\javaFX\openjfx-25.0.4_windows-x64_bin-sdk\javafx-sdk-25.0.4'
)

$ErrorActionPreference = 'Stop'
# 脚本依赖 $LASTEXITCODE 判断 Java 非零退出；PowerShell 7.4+ 默认把原生命令的
# stderr 输出升级为终止错误，会在检查退出码之前先抛出，这里显式关闭。
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    Set-Variable -Name PSNativeCommandUseErrorActionPreference -Value $false -Scope Global
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $repoRoot
# 原生命令子进程继承 [Environment]::CurrentDirectory；显式设置，保证测试里的相对路径可解析。
[Environment]::CurrentDirectory = $repoRoot

$javac = 'D:\DevTools\Java\jdk25\bin\javac.exe'
$java = 'D:\DevTools\Java\jdk25\bin\java.exe'
if (-not (Test-Path -LiteralPath $javac)) {
    if (-not $env:JAVA_HOME) {
        throw "javac not found at $javac and JAVA_HOME is not set"
    }
    $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
}

# 每个套件声明自己的测试类；尚未实现的套件保持空列表，运行时会明确报错而不是静默通过。
$suites = @(
    [pscustomobject]@{
        Name = 'Foundation'
        Common = @('dto.course.teacher.TeacherQueryDtoJsonTest')
        # Client 测试用假 Transport / 假服务离屏运行，不需要真实服务器与 JavaFX 工具包。
        # 控制器测试还会读取 FXML 资源，因此运行期 classpath 需要 VCampusClient/src（见下）。
        Client = @('service.SocketTeacherCourseServiceTest',
            'service.MockTeacherCourseServiceTest',
            'controller.MainControllerRoleRoutingTest',
            'controller.TeacherCourseManagementControllerTest',
            'controller.TeacherOfferingControllerTest',
            'controller.TeacherOfferingDetailControllerTest')
        # TeacherCourseQueryMySqlTest self-gates on the `mysql` argument, so it runs as a real
        # MySQL test only with -WithMySql and prints SKIP otherwise.
        Server = @('database.TeacherFoundationMigrationTest', 'service.TeacherCourseQueryMySqlTest',
            'handler.TeacherCourseHandlerTest', 'main.ServerMainTimeZoneTest')
        # 真实 TCP 端到端：登录教师 → courseTeacher 查询 → DTO 映射。它会重建受保护的测试架构，
        # 所以必须串行单独运行；-WithTcp 才跑，未传时不会被当作已通过。
        Tcp = @('integration.TeacherCourseQuerySocketEndToEndTest')
        # GUI 冒烟与截图：真实 JavaFX 工具包装入教师工作台外壳，用 MockTeacherCourseService 驱动。
        # 只登记冒烟类：ui.TeacherCourseUiPreview 是人工预览工具，只有收到 `--smoke` 才自动关闭，
        # 套件运行传的是 --config，登记它会让一次无人值守运行停在打开的窗口上永不退出。
        Gui = @('ui.TeacherCourseUiSmokeTest')
        # MySQL 门控的类：它们自带 `mysql` 开关，未传时只打印 SKIP 并以 0 退出。这里也包含 Tcp 列里
        # 同样门控的那一个——两处都声明出来，「少传 -WithMySql」才在所有运行形态下都拦得住。
        MySql = @('database.TeacherFoundationMigrationTest',
            'service.TeacherCourseQueryMySqlTest',
            'integration.TeacherCourseQuerySocketEndToEndTest')
    }
    # 课表套件：DTO 契约与既有 ScheduleEntryDTO 回归，加上编译期依赖教师课程接口的客户端测试。
    # 学生端的 ScheduleControllerTest / ScheduleLayoutTest 之前不属于任何套件，这里追加进来，
    # 让本计划“没有触碰学生端课表”的回归断言真的被执行（它们本来就是无工具包的客户端测试）。
    [pscustomobject]@{ Name = 'Timetable'
        Common = @('dto.course.teacher.TeacherScheduleDtoJsonTest', 'dto.course.CourseDtoJsonTest')
        Client = @('service.SocketTeacherCourseServiceTest',
            'service.MockTeacherCourseServiceTest',
            'controller.TeacherCourseManagementControllerTest',
            'controller.TeacherOfferingControllerTest',
            'controller.TeacherOfferingDetailControllerTest',
            'controller.TeacherScheduleControllerTest',
            'controller.TeacherScheduleLayoutTest',
            'service.MockTeacherScheduleTest',
            'controller.ScheduleControllerTest',
            'controller.ScheduleLayoutTest')
        # TeacherScheduleMySqlTest also self-gates on the `mysql` argument: real MySQL only with
        # -WithMySql, otherwise it prints SKIP and is never reported as passing. TeacherCourseHandlerTest
        # is repeated from Foundation on purpose: Task 3 added the loadTeachingSchedule branch to that
        # handler, and without this line the Timetable suite would never exercise it. Cross-suite
        # duplication already has precedent (ui.TeacherCourseUiSmokeTest sits in both Gui lists).
        Server = @('service.TeacherScheduleMySqlTest', 'handler.TeacherCourseHandlerTest')
        # 真实 TCP 端到端：登录教师 → courseTeacher/loadTeachingSchedule → DTO 映射。它会重建受保护的
        # 测试架构，所以必须串行单独运行；-WithTcp 才跑，未传时不会被当作已通过。
        Tcp = @('integration.TeacherScheduleSocketEndToEndTest')
        # GUI 冒烟：真实 JavaFX 工具包装入教师外壳，用 MockTeacherCourseService 驱动课表页与课次详情。
        # 只登记冒烟类：ui.TeacherCourseUiPreview 是人工预览工具且不注册进任何套件。同一个冒烟类
        # 同时出现在 Foundation.Gui 与这里是有意的重复，跨套件重复有先例。
        Gui = @('ui.TeacherCourseUiSmokeTest')
        MySql = @('service.TeacherScheduleMySqlTest',
            'integration.TeacherScheduleSocketEndToEndTest') }
    # 调课套件：先建立公共契约（跨周目标日期、四态状态、精确 ID、不可变 targets）与 V006 迁移契约。
    # ScheduleAdjustmentApprovalHandlerTest 是 DB-free 的旧审批回归：调课 DTO 迁到四态后必须证明
    # 管理员审批接口仍然可用。V006 迁移测试自带 `mysql` 开关，只有 -WithMySql 才跑真实库。
    [pscustomobject]@{ Name = 'Adjustment'
        Common = @('dto.course.teacher.TeacherAdjustmentDtoJsonTest')
        # T4 的教师调课客户端契约：Socket 服务的六个调课方法、泛型 applications 页、CONFLICT 形状，
        # 以及 MockTeacherCourseService 的四态夹具/提交/撤销快照。管理员客户端测试（Socket/Mock）
        # 在 T4 增加了 listAdjustmentRequestsByStatus 主名与旧名别名、WITHDRAWN 夹具，一起登记。
        # T5 的调课表单与“我的申请”两个控制器测试（无工具包）同样登记；管理员审批页与详情弹窗的
        # 无工具包测试在 T1 迁移到四态后一直没有套件保护，T5 正好改动它们（已撤销筛选、目标日期显示），
        # 一并登记，避免“写了测试却永不执行”。GradeApprovalControllerTest 覆盖审批外壳与成绩子页
        # 的共享筛选：T5 把外壳改用四态主名后它的替身必须跟着覆写，不登记就等于没有任何回归保护
        # （成绩子页当时还没有自己的套件；GradeBook 套件在 T6 起才登记 Tcp/Gui 两列）。
        Client = @('service.SocketTeacherCourseServiceTest',
            'service.MockTeacherCourseServiceTest',
            'service.SocketAdminCourseServiceTest',
            'service.MockAdminCourseServiceTest',
            'controller.TeacherAdjustmentDialogControllerTest',
            'controller.TeacherApplicationsControllerTest',
            'controller.AdminApprovalControllerTest',
            'controller.AdjustmentApprovalDialogControllerTest',
            'controller.GradeApprovalControllerTest',
            # T4 补登记：controller.GradeApprovalDialogControllerTest 此前不在任何套件里（grep 计数为 0），
            # 写了却永远跑不到，等于一个不能失败的测试。它的主题正是管理员审批详情弹窗，与上面那一行的
            # 审批外壳属于同一个入口，因此就登记在这里；既有登记一律不挪动。
            'controller.GradeApprovalDialogControllerTest')
        # TeacherAdjustmentConflictMySqlTest 自带 `mysql` 开关（-WithMySql 才跑真实库）。
        # CourseConflictMySqlTest 是既有排课冲突引擎的回归：它不解析 `mysql` 参数，只按
        # db.properties 指向受保护测试库来运行，登记它是为了证明共用检查没有改动旧行为。
        # TeacherAdjustmentApplicationMySqlTest（T3 提交/撤销）自带 `mysql` 开关，只有 -WithMySql
        # 才跑真实库，未传时打印 SKIP 且不算通过。ScheduleAdjustmentApprovalMySqlTest（T1 迁到四态后
        # 一直没有套件保护）与 CourseConflictMySqlTest 一样不解析 `mysql`，只要 db.properties 指向
        # 受保护测试库就会真跑，登记它即代表每次运行都真正执行。
        # TeacherAdjustmentHandlerTest（T4 新增）是 DB-free 的教师调课入口回归；AdminCourseHandlerTest
        # 一直没进任何套件，T4 在它里面钉住“调课四态可筛 / 成绩筛选仍拒绝 WITHDRAWN”，必须登记。
        # CourseQueryMySqlTest（T6 扩展为跨周与通知周次查询）一直是学生课表查询的唯一真实库覆盖，
        # 但此前不在任何套件里：不登记等于 T6 改过的学生查询没有任何回归保护。它与
        # CourseConflictMySqlTest 同形——不解析 `mysql`，只要 db.properties 指向受保护测试库就
        # 真跑，并自带 `DATABASE()=virtual_campus_course_test` 守卫，绝不把 SKIP 当 PASS。
        # CourseQueryMappingTest 同样一直不在任何套件里，但它是 DB-free 的（CachedRowSet 夹具）：
        # 它钉住 mapScheduleRows 的“原行 + 生效目标成对”契约，正是学生跨周组合所依赖的服务端
        # 映射，与 CourseQueryMySqlTest 一起登记。
        Server = @('database.TeacherAdjustmentMigrationTest',
            'handler.ScheduleAdjustmentApprovalHandlerTest',
            'handler.TeacherAdjustmentHandlerTest',
            'handler.AdminCourseHandlerTest',
            'service.TeacherAdjustmentConflictMySqlTest',
            'service.ScheduleAdjustmentApprovalMySqlTest',
            'service.TeacherAdjustmentApplicationMySqlTest',
            'service.CourseConflictMySqlTest',
            'service.CourseQueryMySqlTest',
            'dao.CourseQueryMappingTest')
        # T6 的真实 TCP 闭环（教师第 8 周申请 → 管理员审批 → 教师/学生查两周）。它同样会重建
        # 受保护的测试架构，所以必须串行单独运行；-WithTcp 才跑，未传时不会被当作已通过。
        # ScheduleAdjustmentSocketEndToEndTest 是 T6 之前的管理员调课 TCP 闭环，此前不在任何
        # 套件里（既有债）：T6 按新语义改写了它的通知断言，不登记就等于改过的断言没有回归保护。
        # 两个类各自重建受保护测试架构，列表顺序串行执行，同一次 -WithTcp 运行内必须都通过。
        Tcp = @('integration.ScheduleAdjustmentSocketEndToEndTest',
            'integration.TeacherAdjustmentSocketEndToEndTest')
        # GUI 冒烟：真实 JavaFX 工具包装入教师外壳，走调课表单与“我的申请”并产出四张主题截图
        # （跨周 / 冲突 / 撤销 / 长原因）。同一个冒烟类也登记在 Foundation.Gui 与 Timetable.Gui，
        # 跨套件重复有先例（各套件跑各自的入口，冒烟内部覆盖全部教师页面）。
        Gui = @('ui.TeacherCourseUiSmokeTest')
        MySql = @('database.TeacherAdjustmentMigrationTest',
            'service.TeacherAdjustmentConflictMySqlTest',
            'service.TeacherAdjustmentApplicationMySqlTest',
            'integration.TeacherAdjustmentSocketEndToEndTest') }
    # 成绩套件：先建立公共契约（草稿/方案/快照的 JSON 保真、精确 ID、不可变列表）与 V007 迁移契约。
    # GradeApprovalDtoJsonTest 是管理员成绩审批的既有 DTO 契约，T1 扩充提交快照后必须继续通过，
    # 它此前不在任何套件里，登记它是为了让本计划“不破坏管理员成绩审批”的断言真的被执行。
    # T2 的两个纯计算测试（总评 BigDecimal 舍入与学校绩点连续区间）是服务端提交事务与客户端预览
    # 共用的同一份规则，不碰数据库和界面，但必须每次都跟着跑；它们也是 T4 重算总评前的唯一保护。
    # T5 追加两个离屏客户端测试：编辑模型（原文/解析结果分离、非法文本与未配齐权重）与成绩页
    # 控制器（保存失败保留编辑、提交二次确认与重复点击、离开守卫、FXML 结构）。
    # TeacherGradeMigrationTest 自带 `mysql` 开关，只有 -WithMySql 才跑真实库，未传时打印 SKIP
    # 且不算通过。
    [pscustomobject]@{ Name = 'GradeBook'
        Common = @('dto.course.teacher.TeacherGradeDtoJsonTest',
            'dto.course.admin.GradeApprovalDtoJsonTest',
            'course.grade.GradeCalculatorTest',
            'course.grade.GradePointScaleTest')
        # Excel 式录入（选中即编辑 / 方向键导航 / 批量粘贴）把两个纯判定抽成了无工具包的类：
        # GradeBookNavigator（往哪一格走、←/→ 是挪光标还是换格子）与 GradeClipboardParser
        # （剪贴板的三种行分隔符与末尾换行）。它们是键盘交互里唯一能用普通断言钉死的部分，
        # 必须和编辑模型一样每次运行都跟着跑。
        Client = @('model.course.teacher.GradeBookEditorModelTest',
            'model.course.teacher.GradeBookNavigatorTest',
            'model.course.teacher.GradeClipboardParserTest',
            'controller.TeacherGradeBookControllerTest',
            'service.SocketTeacherCourseServiceTest',
            'controller.TeacherCourseManagementControllerTest',
            # 学生成绩页的回归：T6 之前它不在任何套件里（本计划改的是它的读取路径——服务端
            # loadGrades 的投影与发布），既无工具包也不碰数据库，正好放进客户端列。
            'controller.GradeControllerTest')
        # T3 的 TeacherGradeDraftMySqlTest 与 T4 的 TeacherGradeSubmissionMySqlTest 自带 `mysql`
        # 开关，只有 -WithMySql 才跑真实库，未传时打印 SKIP 且不算通过；AdminEnrollmentMySqlTest
        # 是管理员学生维护的既有回归（V007 给 enrollment 加了唯一键与复合外键），它不解析 `mysql`，
        # 只要 db.properties 指向受保护测试库就真跑，登记它是为了让“草稿写入没有破坏管理员学生维护”
        # 这条验收证据真的被执行。T4 同时登记 GradeApprovalMySqlTest：它是管理员成绩审批的既有回归，
        # 此前不在任何套件里，而 T4 改了审批路径的方案快照核验，不登记就等于没有回归保护；
        # 它同样不解析 `mysql`，只要配置指向受保护测试库就真跑。
        # T5 登记 TeacherGradeHandlerTest（DB-free 的教师成绩入口回归）与 TeacherCourseHandlerTest：
        # 后者覆盖 T5 改动的构造函数与共享解析路径（调课写请求与成绩写请求共用同一套伪造字段防线）。
        # SocketTeacherCourseServiceTest 与 TeacherCourseManagementControllerTest 在 T5 被扩展
        # （成绩动作映射、成绩入口不再是占位），它们也出现在 Foundation/Timetable 套件里，
        # 跨套件重复有先例。
        Server = @('database.TeacherGradeMigrationTest', 'service.TeacherGradeDraftMySqlTest',
            'service.TeacherGradeSubmissionMySqlTest', 'service.GradeApprovalMySqlTest',
            'service.AdminEnrollmentMySqlTest', 'handler.TeacherGradeHandlerTest',
            'handler.TeacherCourseHandlerTest')
        # T6 的真实 TCP 闭环：教师保存部分草稿 → 补齐提交 → 管理员审批同一条批次 → 学生 loadGrades
        # 读回四项组成（含禁用项的 NULL）、总评、13 档绩点与按学分加权 GPA。教师侧与管理员侧此前
        # 各自对着自己写的夹具断言，只有这一条跨过接缝的链路能证明两侧没有漂移。
        # integration.GradeApprovalSocketEndToEndTest 是管理员成绩审批的既有 TCP 端到端，此前不在
        # 任何套件里（既有债）：T6 登记它是为了让“管理员成绩审批”这条回归真的被执行。
        # 两个类都会重建受保护的测试架构，必须串行、且只在 -WithTcp 下运行。
        Tcp = @('integration.TeacherGradeSocketEndToEndTest',
            'integration.GradeApprovalSocketEndToEndTest')
        # T6 的 GUI 列：真实 JavaFX 工具包装入教师工作台与管理员审批页。
        # ui.TeacherCourseUiSmokeTest 覆盖成绩录入页（列表 / 部分填写 / 非法值 / 灰列 / 未保存提示 /
        # 二次确认提交 / 只读 / 驳回），也就是“FXML 里一个未转义的 % 会让整页加载失败”这类只有真实
        # 工具包能抓住的缺陷；ui.AdminApprovalUiSmokeTest 覆盖管理员成绩审批详情的新显示（组成与权重、
        # 提交人数、基础批次、未纳入批次的新成员），它同时钉住 MockAdminCourseService 必须给出快照字段。
        # ui.TeacherCourseUiPreview / ui.AdminCourseUiPreview 是人工预览工具（只有收到 --smoke 才自动
        # 关闭，而套件运行传的是 --config），登记它们会让一次无人值守运行停在打开的窗口上永不退出。
        Gui = @('ui.TeacherCourseUiSmokeTest', 'ui.AdminApprovalUiSmokeTest')
        MySql = @('database.TeacherGradeMigrationTest',
            'service.TeacherGradeDraftMySqlTest',
            'service.TeacherGradeSubmissionMySqlTest') }
    # 成绩导入导出套件：T1 先建立文件票据与短连接传输，T2 再加上服务端的表格读写，T3 加上预览与确认，
    # T5 补上跨两个真实端口的端到端闭环与名单导出的真实库覆盖。
    # 两个 T1 测试都是 DB-free 的：CourseFileServerTest 在端口 0 上起真实文件监听器并用原始 Socket
    # 逐条验证票据矩阵（无效/过期/他人 token、错误方向、超限、截断、SHA 不符、重复领取、停服），
    # SocketTeacherFileTransportTest 自带一个端口 0 的对端 ServerSocket（客户端 classpath 里没有
    # 服务端类，也不该有）。T2 的 TeacherSpreadsheetServiceTest 同样 DB-free：它用 POI 写真实临时
    # .xlsx 再读回来，并核对生成的模板/导出文件，因此依赖 VCampusServer/lib 下的 POI 闭包
    # （见该目录的 teacher-excel-dependencies.md）。T3 的 TeacherGradeImportMySqlTest 自带 `mysql`
    # 开关（-WithMySql 才跑真实库，未传时打印 SKIP 且不算通过）：预览不写库、缺列/空白保留原草稿值、
    # 非法值保留原文本、未知与重复学号、修正与排除、名单/版本冲突、令牌过期与串用、确认重放都必须
    # 对着真实库和真实外键验证。T5 的 TeacherCourseExportMySqlTest 同样自带 `mysql` 开关，补上此前
    # 完全没有覆盖的名单导出路径：归属校验（别人的教学班导不出）、5001 行明确报错而 5000 行整份导出
    # （上限判定一次查询内完成，绝不静默截断）、导出与列表共用同一份过滤与排序。
    [pscustomobject]@{ Name = 'ImportExport'
        Common = @()
        Client = @('service.SocketTeacherFileTransportTest',
            'controller.TeacherGradeImportControllerTest')
        Server = @('service.TeacherSpreadsheetServiceTest', 'network.CourseFileServerTest',
            'service.TeacherGradeImportMySqlTest', 'service.TeacherCourseExportMySqlTest')
        # T5 的真实闭环：下载模板 → 填表（缺列/空白/105 越界/未知学号）→ 文件端口上传 → 预览不写库
        # → 修正与排除 → 确认只写草稿 → 补齐提交 → 管理员审批 → 学生查成绩；再加取消、上传中断、
        # 票据复用、确认重放、超过一页的名单导出、他人兑换票据被拒与停服后的线程/临时文件回收。
        # 它同时起业务端口与文件端口，并会重建受保护的测试架构，所以必须串行单独运行；-WithTcp 才跑，
        # 未传时不会出现在运行列表里，也就不会被当作通过。
        Tcp = @('integration.TeacherGradeImportSocketEndToEndTest')
        # GUI 冒烟：真实 JavaFX 工具包装入教师外壳，T5 追加的两步验证导入区三个入口的接线与
        # ui.TeacherGradeImportFeedback 弹窗真实加载（弹窗的加载失败在控制器里被吞掉，只有真实工具包
        # 能抓住 FXML 里的 fx:id/onAction/controller 错误）。只登记冒烟类：ui.TeacherCourseUiPreview
        # 是人工预览工具且不注册进任何套件（详见 Foundation.Gui 的说明）。同一个冒烟类同时出现在
        # Foundation/Timetable/GradeBook 的 Gui 列，跨套件重复有先例。
        Gui = @('ui.TeacherCourseUiSmokeTest')
        MySql = @('service.TeacherGradeImportMySqlTest',
            'service.TeacherCourseExportMySqlTest') }
    # 教师端「我的申请与结果通知」套件：T1 建立驳回重开与发起更正两个版本链入口（真实库），T2 补上客户端更正入口，
    # T3 补上统一申请列表与已读状态，T4 补上端到端闭环与整分支回归。
    # service.TeacherGradeRevisionMySqlTest 自带 `mysql` 开关（-WithMySql 才跑真实库，未传时打印 SKIP 且不算通过）：
    # 来源批次不是最后一次提交或状态不符、PENDING 批次、已打开的草稿、非任课教师、空更正原因、重复开始重放、
    # 名单新增与退课、两个教师竞争、事务中途失败整笔回滚、惰性重开与显式重开同形、纯权重更正只记实际变化的行，
    # 以及 v1 通过 → 更正草稿 → v2 驳回 → 重提 → v3 通过的整条版本链（学生可见成绩一律读已发布的 grade 投影），
    # 全部对着真实库验证。
    # T2 的客户端更正入口：controller.TeacherGradeCorrectionDialogControllerTest 是不需要工具包的成绩更正
    # 表单测试（姓名/学号/原分数、空原因本地拒绝、取消不建草稿、确认建立草稿并交回拟修改值）；
    # controller.TeacherGradeBookControllerTest 钉住同一页上的两个版本入口（被驳回→重新编辑、已通过→申请修改、
    # 待审核一个都不给）以及它解析的那份 FXML 的 fx:id/onAction 绑定——不经它注册，改坏 FXML 的绑定在本套件里
    # 看不见；service.SocketTeacherCourseServiceTest 覆盖两个新 Socket 方法（动作常量、request/result 形状、
    # CONFLICT 里的最新成绩表），T3 又在同一个类里补上统一申请列表/详情/标记已读三个方法与
    # CONFLICT 里的当前申请行。三者在别的套件里也有注记，跨套件重复在本文件里是有先例的。
    # T3 的统一「我的申请」：service.TeacherApplicationsMySqlTest 自带 `mysql` 开关（-WithMySql 才跑真实库，
    # 未传时打印 SKIP 且不算通过），对着真实库验证两张事实表在 SQL 里的合并分页与总数、类型/状态的按表白名单、
    # 不同类型里数字相同的两条申请互不影响、读过 PENDING 之后 APPROVED 重新未读、过期的已读确认被拒且不写回执、
    # 别人的申请不可见，以及教学班成员关系解除后自己的历史仍可读。
    # controller.TeacherApplicationsControllerTest 是同一页的无工具包控制器测试（它同时是 FXML 的 fx:id/
    # onAction/样式类契约测试）。**它已经在 Adjustment 套件里登记过**：那是调课计划 T5 当时的我的申请页测试，
    # 不是空类，也不能从 Adjustment 挪走；这里按本文件的既有先例在第二个套件里再登记一次，因为 T3 改写的正是
    # 这一页。三者的解释都在各自套件里各写一份。
    # handler.TeacherCourseHandlerTest（T4 新增）是 DB-free 的教师课程入口回归，T3 在里面补上三个「我的申请」
    # 动作的响应键、写请求体位置与两条 catch 分支的 MessageCode 断言（Ruling G 要的正是「断言 code 本身」，
    # 否则异常一旦没被映射就会掉进 RuntimeException 分支变成 ERROR / 服务端内部错误，而所有套件照样全绿）。
    # **它已经在 Foundation 套件里登记过**（:62），与 controller.TeacherApplicationsControllerTest 同一先例：
    # 不挪走，在第二个套件里再登记一次，因为 T3 依赖的正是这个证据。
    # T4 把整条闭环与整条迁移链补上：integration.TeacherCourseWorkflowEndToEndTest 是一条真实 TCP/MySQL
    # 链路（教师查询 → 跨周申请 → 撤销/审批竞争 → 学生两周课表；手工草稿 → 模板下载/真实短连接上传/
    # 预览/修正/确认 → 提交 → 驳回 → 重提批准 → 发起更正 → 再批准 → 学生 GPA；以及合并申请列表与未读同步），
    # database.TeacherCourseMigrationMySqlTest 是七个迁移按文档顺序跑完的两条安装路径。两者都自带 `mysql`
    # 开关并会重建受保护的测试架构，所以都登记在 Server 列的最后，且必须在 MySql 列里声明出来。
    # 它们是「这个套件真的证明过什么」的最终证据：前三个 T 各自只跑过一个套件，只有这里把六套回归之外的
    # 跨角色链路跑齐。
    [pscustomobject]@{ Name = 'Applications'
        Common = @()
        Client = @('controller.TeacherGradeCorrectionDialogControllerTest',
            'controller.TeacherGradeBookControllerTest',
            'service.SocketTeacherCourseServiceTest',
            'controller.TeacherApplicationsControllerTest')
        Server = @('service.TeacherGradeRevisionMySqlTest',
            'service.TeacherApplicationsMySqlTest',
            'handler.TeacherCourseHandlerTest',
            'integration.TeacherCourseWorkflowEndToEndTest',
            'database.TeacherCourseMigrationMySqlTest')
        Tcp = @()
        Gui = @()
        MySql = @('service.TeacherGradeRevisionMySqlTest',
            'service.TeacherApplicationsMySqlTest',
            'integration.TeacherCourseWorkflowEndToEndTest',
            'database.TeacherCourseMigrationMySqlTest') }
    # 课程模块修复套件（2026-09-17）。本计划的三个缺陷分别落在学生课表读路径、管理员学生搜索、
    # 管理员排课，下面这 13 个类此前一个套件都没有——改了也永远跑不到，等于不会失败的测试。
    # 注意这里刻意不收已经在 Timetable/Adjustment/GradeBook 里的类，避免同一批 MySQL 用例跑两遍。
    # MySql 列 = Server 列里需要活库的类。这里的 `service.ScheduleManagementMySqlTest` **不解析**
    # `mysql` 参数：它从 classpath 读 `resources/db.properties`，库不对时抛 `Refusing schedule test`
    # （同形做法见 Adjustment 套件的 `service.CourseConflictMySqlTest`），所以必须靠 `-TestConfigPath`
    # 指到受保护库，它不会因为漏传 `-WithMySql` 就降级成 SKIP。
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

)

function Get-SourceFiles {
    param([string[]]$Directories)
    $files = New-Object System.Collections.Generic.List[string]
    foreach ($directory in $Directories) {
        $full = Join-Path $repoRoot $directory
        if (-not (Test-Path -LiteralPath $full)) {
            throw "Missing source directory: $directory"
        }
        # 本环境没有 rg，使用 Get-ChildItem 枚举源文件。
        foreach ($file in (Get-ChildItem -LiteralPath $full -Recurse -Filter '*.java' -File)) {
            if ($file.Name -eq 'module-info.java') { continue }
            $files.Add($file.FullName)
        }
    }
    return @($files | Sort-Object -Unique)
}

function Write-SourceArgFile {
    param([string]$Path, [string[]]$SourceFiles)
    # Windows PowerShell 5.1 的 Set-Content -Encoding utf8 会写入 BOM，javac 会以
    # "Invalid filename" 中止；这里用无 BOM 的 UTF8Encoding 显式写文件。
    $lines = @($SourceFiles | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllLines($Path, [string[]]$lines, $utf8NoBom)
}

if ([string]::IsNullOrWhiteSpace($Suite)) {
    Write-Output 'Known teacher suites:'
    foreach ($entry in $suites) { Write-Output ('  ' + $entry.Name) }
    Write-Output ''
    Write-Output 'Usage: pwsh -File scripts/test-teacher.ps1 -Suite <name> -WithMySql [-WithTcp] [-WithGui] [-TestConfigPath <path>]'
    Write-Output '       -WithMySql is required: every suite declares MySQL-gated tests, and without it'
    Write-Output '       the suite fails at selection instead of silently skipping its database assertions.'
    exit 0
}

$selected = @($suites | Where-Object { $_.Name -eq $Suite })
if ($selected.Count -ne 1) { throw "Unknown suite: $Suite" }
$selected = $selected[0]

if ($WithTcp -and $selected.Tcp.Count -eq 0) {
    throw "Suite $Suite declares no TCP tests; -WithTcp would silently pass."
}
# JavaFX 看门狗：VCampusClient/lib 只有 JavaFX 的 jar，缺 Windows 原生 DLL，起不了 toolkit；
# GUI 测试必须把完整 SDK（含 bin/*.dll）的 lib 放到 module path 上。这里在选测试之前就检查，
# 让 -WithGui 无论选中哪个套件都先对运行环境给出明确结论。
$guiArguments = @()
if ($WithGui) {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaFxHome 'bin\prism_d3d.dll'))) {
        throw ("JavaFX SDK not found at $JavaFxHome (missing bin\prism_d3d.dll); " +
            "-WithGui cannot start a toolkit. Pass -JavaFxHome <complete JavaFX SDK>.")
    }
    $guiArguments = @(
        '--enable-native-access=javafx.graphics',
        '--module-path', (Join-Path $JavaFxHome 'lib'),
        '--add-modules', 'javafx.controls,javafx.fxml,javafx.swing'
    )
}
if ($WithGui -and $selected.Gui.Count -eq 0) {
    throw "Suite $Suite declares no GUI tests; -WithGui would silently pass."
}
# MySQL 门控的类未传 `mysql` 时只打印 SKIP 并以 0 退出，脚本按退出码判断就会把「一次库断言都没跑」
# 记成通过。这里选测试之前就报错，让 SKIP 永远不可能被当成 PASS（与上面的 Tcp/Gui 两列同一条规则）。
if ($selected.MySql.Count -gt 0 -and -not $WithMySql) {
    throw ("Suite $Suite declares MySQL-gated tests (" + ($selected.MySql -join ', ') +
        "); without -WithMySql they would print SKIP and still exit 0, so the suite would be" +
        " reported as passed with every database assertion skipped. Pass -WithMySql.")
}
if ($WithMySql -and $selected.MySql.Count -eq 0) {
    throw "Suite $Suite declares no MySQL-gated tests; -WithMySql would silently pass."
}
if ($selected.Common.Count -eq 0 -and $selected.Client.Count -eq 0 -and
        $selected.Server.Count -eq 0 -and $selected.Tcp.Count -eq 0 -and
        $selected.Gui.Count -eq 0 -and $selected.MySql.Count -eq 0) {
    throw "Suite $Suite is declared but has no tests yet; nothing was run."
}

if ([string]::IsNullOrWhiteSpace($TestConfigPath)) {
    $TestConfigPath = 'VCampusServer/src/resources/db.properties'
}
$resolvedConfig = $TestConfigPath
if (-not [System.IO.Path]::IsPathRooted($resolvedConfig)) {
    $resolvedConfig = Join-Path $repoRoot $resolvedConfig
}
if ($WithMySql -and -not (Test-Path -LiteralPath $resolvedConfig -PathType Leaf)) {
    throw "MySQL test config not found: $TestConfigPath"
}
$configArgument = '--config=' + $resolvedConfig.Replace('\', '/')
$configResourceRoot = Split-Path -Parent (Split-Path -Parent $resolvedConfig)

$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
# 每次运行一个独立目录，路径里带套件名，便于按套件分辨产物（伞形计划的统一约定）。
$buildRoot = Join-Path $repoRoot (Join-Path '.codex-tmp\teacher' ($Suite + '-' + $runId))
$commonOutput = Join-Path $buildRoot 'common'
$commonTestOutput = Join-Path $buildRoot 'common-test'
$clientOutput = Join-Path $buildRoot 'client'
$serverOutput = Join-Path $buildRoot 'server'
New-Item -ItemType Directory -Force -Path $buildRoot | Out-Null

$clientLib = Join-Path $repoRoot 'VCampusClient\lib'
$serverLib = Join-Path $repoRoot 'VCampusServer\lib'
$clientLibPattern = Join-Path $clientLib '*'
$serverLibPattern = Join-Path $serverLib '*'
$clientModulePath = (Get-ChildItem -LiteralPath $clientLib -File |
    Where-Object { $_.Name -like 'javafx*.jar' -or $_.Name -eq 'jdk.jsobject.jar' } |
    ForEach-Object { $_.FullName }) -join ';'

# Common 的测试目录引用了少量客户端类（model.course.admin/service.AdminCourseService），
# 所以按真实依赖顺序分四个阶段编译，每个阶段一个独立输出目录：
# Common 主源码 → Client → Common 测试 → Server。
$stages = @(
    [pscustomobject]@{
        Label = 'Common compilation'
        Key = 'common'
        Directories = @('VCampusCommon/src')
        Output = $commonOutput
        Classpath = $clientLibPattern
        JavaFx = $false
    }
    [pscustomobject]@{
        Label = 'Client compilation'
        Key = 'client'
        Directories = @('VCampusClient/src', 'VCampusClient/test')
        Output = $clientOutput
        Classpath = @($commonOutput, $clientLibPattern) -join ';'
        JavaFx = $true
    }
    [pscustomobject]@{
        Label = 'Common test compilation'
        Key = 'common-test'
        Directories = @('VCampusCommon/test')
        Output = $commonTestOutput
        Classpath = @($commonOutput, $clientOutput, $clientLibPattern) -join ';'
        JavaFx = $false
    }
    [pscustomobject]@{
        Label = 'Server compilation'
        Key = 'server'
        Directories = @('VCampusServer/src', 'VCampusServer/test')
        Output = $serverOutput
        Classpath = @($commonOutput, $serverLibPattern) -join ';'
        JavaFx = $false
    }
)

Write-Output "Suite: $Suite"
Write-Output "Output: $buildRoot"

foreach ($stage in $stages) {
    New-Item -ItemType Directory -Force -Path $stage.Output | Out-Null
    $argFile = Join-Path $buildRoot ($stage.Key + '-sources.txt')
    Write-SourceArgFile -Path $argFile -SourceFiles (Get-SourceFiles $stage.Directories)
    if ($stage.JavaFx) {
        & $javac '-J-Duser.language=en' '-J-Duser.country=US' -encoding UTF-8 -Xmaxerrs 20 `
            --module-path $clientModulePath --add-modules javafx.controls,javafx.fxml,javafx.swing `
            -cp $stage.Classpath -d $stage.Output ('@' + $argFile)
    } else {
        & $javac '-J-Duser.language=en' '-J-Duser.country=US' -encoding UTF-8 -Xmaxerrs 20 `
            -cp $stage.Classpath -d $stage.Output ('@' + $argFile)
    }
    if ($LASTEXITCODE -ne 0) { throw ($stage.Label + ' failed') }
    Write-Output ($stage.Label + ' OK')
}

$commonTestClasspath = @(
    $commonTestOutput, $commonOutput, $clientOutput, $clientLibPattern
) -join ';'
# 客户端测试与 Client 主源码同目录编译，运行期需要 Common 主输出、Client 输出、客户端 lib，
# 以及 VCampusClient/src 才能读到 FXML/CSS 资源（与 Server 测试加载迁移脚本的方式一致）。
$clientTestClasspath = @(
    $clientOutput, $commonOutput, $clientLibPattern, (Join-Path $repoRoot 'VCampusClient/src')
) -join ';'
$serverTestClasspath = @(
    $serverOutput, $commonOutput, $serverLibPattern, $configResourceRoot,
    (Join-Path $repoRoot 'VCampusServer/src')
) -join ';'
# GUI 测试要真正起 toolkit 的是客户端页面，它的类与 FXML/CSS 都在客户端输出/源码目录里，
# 所以 GUI 运行用客户端与服务端 classpath 的并集，而不是只有服务端那一份。
$guiTestClasspath = @(
    $clientOutput, $serverOutput, $commonOutput, $clientLibPattern, $serverLibPattern,
    (Join-Path $repoRoot 'VCampusClient/src'), $configResourceRoot,
    (Join-Path $repoRoot 'VCampusServer/src')
) -join ';'

# 服务端迁移测试始终收到配置路径；只有 -WithMySql 才追加 `mysql` 开关。
$serverArguments = @($configArgument)
if ($WithMySql) { $serverArguments = @('mysql') + $serverArguments }

$runs = @()
foreach ($testClass in $selected.Common) {
    $runs += [pscustomobject]@{
        Class = $testClass; Classpath = $commonTestClasspath; Gui = $false; Arguments = @()
    }
}
foreach ($testClass in $selected.Client) {
    $runs += [pscustomobject]@{
        Class = $testClass; Classpath = $clientTestClasspath; Gui = $false; Arguments = @()
    }
}
foreach ($testClass in $selected.Server) {
    $runs += [pscustomobject]@{
        Class = $testClass; Classpath = $serverTestClasspath; Gui = $false
        Arguments = $serverArguments
    }
}
# TCP 与 GUI 用例默认不跑，必须由调用者显式开启：前者会重建受保护的测试架构，后者需要完整
# JavaFX SDK。声明了却不跑时它们不出现在运行列表里，所以不会被当成通过。
if ($WithTcp) {
    foreach ($testClass in $selected.Tcp) {
        $runs += [pscustomobject]@{
            Class = $testClass; Classpath = $serverTestClasspath; Gui = $false
            Arguments = $serverArguments
        }
    }
}
if ($WithGui) {
    foreach ($testClass in $selected.Gui) {
        $runs += [pscustomobject]@{
            Class = $testClass; Classpath = $guiTestClasspath; Gui = $true
            Arguments = $serverArguments
        }
    }
}
if ($runs.Count -eq 0) {
    throw "Suite $Suite selected no tests to run; nothing was run. Pass -WithTcp/-WithGui if the suite only declares those."
}

foreach ($run in $runs) {
    Write-Output ('Running ' + $run.Class)
    if ($run.Gui) {
        & $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' @guiArguments `
            -cp $run.Classpath $run.Class @($run.Arguments)
    } else {
        & $java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp $run.Classpath `
            $run.Class @($run.Arguments)
    }
    if ($LASTEXITCODE -ne 0) { throw ('Test ' + $run.Class + ' failed') }
}

Write-Output "Suite $Suite passed"
Write-Output "Verification output: $buildRoot"
