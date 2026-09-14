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
#
# 约定：javac/java 非零退出立即停止；SKIP 永远不等于 PASS；每次运行使用独立输出目录；
# 不自动创建或删除数据库。`-WithTcp`/`-WithGui` 只有在所选套件确实声明了对应测试时才允许使用，
# 否则报错退出，避免“什么都没跑却看起来通过”。

[CmdletBinding()]
param(
    [string]$Suite = '',
    [switch]$WithMySql,
    [switch]$WithTcp,
    [switch]$WithGui,
    [string]$TestConfigPath = ''
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
        # TeacherCourseQueryMySqlTest self-gates on the `mysql` argument, so it runs as a real
        # MySQL test only with -WithMySql and prints SKIP otherwise.
        Server = @('database.TeacherFoundationMigrationTest', 'service.TeacherCourseQueryMySqlTest')
        Tcp = @()
        Gui = @()
    }
    [pscustomobject]@{ Name = 'Timetable'; Common = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'Adjustment'; Common = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'GradeBook'; Common = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'ImportExport'; Common = @(); Server = @(); Tcp = @(); Gui = @() }
    [pscustomobject]@{ Name = 'Applications'; Common = @(); Server = @(); Tcp = @(); Gui = @() }
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
if ($WithGui -and $selected.Gui.Count -eq 0) {
    throw "Suite $Suite declares no GUI tests; -WithGui would silently pass."
}
if ($selected.Common.Count -eq 0 -and $selected.Server.Count -eq 0 -and
        $selected.Tcp.Count -eq 0 -and $selected.Gui.Count -eq 0) {
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
$buildRoot = Join-Path $repoRoot (Join-Path 'build\test-teacher' $runId)
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
$serverTestClasspath = @(
    $serverOutput, $commonOutput, $serverLibPattern, (Join-Path $repoRoot 'VCampusServer/src')
) -join ';'

# 服务端迁移测试始终收到配置路径；只有 -WithMySql 才追加 `mysql` 开关。
$serverArguments = @($configArgument)
if ($WithMySql) { $serverArguments = @('mysql') + $serverArguments }

$guiArguments = @()
if ($WithGui) {
    $guiArguments = @(
        '--enable-native-access=javafx.graphics',
        '--module-path', $clientLib,
        '--add-modules', 'javafx.controls,javafx.fxml,javafx.swing'
    )
}

$runs = @()
foreach ($testClass in $selected.Common) {
    $runs += [pscustomobject]@{
        Class = $testClass; Classpath = $commonTestClasspath; Gui = $false; Arguments = @()
    }
}
foreach ($testClass in ($selected.Server + $selected.Tcp + $selected.Gui)) {
    $gui = $selected.Gui -contains $testClass
    $runs += [pscustomobject]@{
        Class = $testClass; Classpath = $serverTestClasspath; Gui = $gui
        Arguments = $serverArguments
    }
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
