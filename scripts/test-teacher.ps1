# 教师端阶段验证入口（TDD 提交流程使用）。
#
# 用法：
#   pwsh -File scripts/test-teacher.ps1
#       不带参数时只列出已知套件并退出 0，不编译、不连接数据库。
#   pwsh -File scripts/test-teacher.ps1 -Suite Foundation
#       分目录编译 Common/Server/Client，串行运行 Foundation 套件的全部测试类。
#   pwsh -File scripts/test-teacher.ps1 -Suite Foundation -WithMySql
#       额外把 `mysql` 传给服务端迁移测试，启用受保护的 MySQL 迁移用例。
#   pwsh -File scripts/test-teacher.ps1 -Suite Foundation -TestConfigPath <db.properties>
#       覆盖默认的 VCampusServer/src/resources/db.properties。
#   pwsh -File scripts/test-teacher.ps1 -Suite <name> -WithGui [-JavaFxHome <sdk>]
#       以完整 JavaFX SDK 启动工具包运行 GUI 测试；-JavaFxHome 默认指向本机已解压的
#       openjfx-25.0.4 SDK，缺少原生 DLL 时立即报错而不是静默降级。
#
# 约定：javac/java 非零退出立即停止；SKIP 永远不等于 PASS；每次运行在 .codex-tmp/teacher/<套件>-<唯一值>
# 下用独立输出目录；不自动创建或删除数据库。`-WithTcp`/`-WithGui` 只有在所选套件确实声明了对应测试时
# 才允许使用，否则报错退出，避免“什么都没跑却看起来通过”。

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
            'handler.TeacherCourseHandlerTest')
        # 真实 TCP 端到端：登录教师 → courseTeacher 查询 → DTO 映射。它会重建受保护的测试架构，
        # 所以必须串行单独运行；-WithTcp 才跑，未传时不会被当作已通过。
        Tcp = @('integration.TeacherCourseQuerySocketEndToEndTest')
        # GUI 冒烟与截图：真实 JavaFX 工具包装入教师工作台外壳，用 MockTeacherCourseService 驱动。
        # 只登记冒烟类：ui.TeacherCourseUiPreview 是人工预览工具，只有收到 `--smoke` 才自动关闭，
        # 套件运行传的是 --config，登记它会让一次无人值守运行停在打开的窗口上永不退出。
        Gui = @('ui.TeacherCourseUiSmokeTest')
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
        Gui = @('ui.TeacherCourseUiSmokeTest') }
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
            'controller.GradeApprovalControllerTest')
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
        Gui = @('ui.TeacherCourseUiSmokeTest') }
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
        Client = @('model.course.teacher.GradeBookEditorModelTest',
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
        Gui = @('ui.TeacherCourseUiSmokeTest', 'ui.AdminApprovalUiSmokeTest') }
    [pscustomobject]@{ Name = 'ImportExport'; Common = @(); Client = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'Applications'; Common = @(); Client = @(); Server = @(); Tcp = @(); Gui = @() }
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
    Write-Output 'Usage: pwsh -File scripts/test-teacher.ps1 -Suite <name> [-WithMySql] [-WithTcp] [-WithGui] [-TestConfigPath <path>]'
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
if ($selected.Common.Count -eq 0 -and $selected.Client.Count -eq 0 -and
        $selected.Server.Count -eq 0 -and $selected.Tcp.Count -eq 0 -and
        $selected.Gui.Count -eq 0) {
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
            --module-path $clientLib --add-modules javafx.controls,javafx.fxml,javafx.swing `
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
    $serverOutput, $commonOutput, $serverLibPattern, (Join-Path $repoRoot 'VCampusServer/src')
) -join ';'
# GUI 测试要真正起 toolkit 的是客户端页面，它的类与 FXML/CSS 都在客户端输出/源码目录里，
# 所以 GUI 运行用客户端与服务端 classpath 的并集，而不是只有服务端那一份。
$guiTestClasspath = @(
    $clientOutput, $serverOutput, $commonOutput, $clientLibPattern, $serverLibPattern,
    (Join-Path $repoRoot 'VCampusClient/src'), (Join-Path $repoRoot 'VCampusServer/src')
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
