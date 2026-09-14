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
    [pscustomobject]@{ Name = 'Timetable'; Common = @(); Client = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'Adjustment'; Common = @(); Client = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'GradeBook'; Common = @(); Client = @(); Server = @(); Tcp = @(); Gui = @() }
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
