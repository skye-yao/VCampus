param(
    [string]$JavaHome = ""
)

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  VCampus Automated Build & Package     " -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

$root = (Get-Location).Path

# 1. Detect JDK
$javacExe = "javac"
$jarExe = "jar"

if ($JavaHome -and (Test-Path "$JavaHome\bin\jar.exe")) {
    $javacExe = "$JavaHome\bin\javac.exe"
    $jarExe = "$JavaHome\bin\jar.exe"
} elseif (Test-Path "C:\Program Files\Java\latest\jdk-25\bin\jar.exe") {
    $javacExe = "C:\Program Files\Java\latest\jdk-25\bin\javac.exe"
    $jarExe = "C:\Program Files\Java\latest\jdk-25\bin\jar.exe"
} elseif ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\jar.exe")) {
    $javacExe = "$env:JAVA_HOME\bin\javac.exe"
    $jarExe = "$env:JAVA_HOME\bin\jar.exe"
}

Write-Host "[1/5] Checking JDK environment..." -ForegroundColor Yellow
Write-Host "  javac: $javacExe"
Write-Host "  jar:   $jarExe"

# 2. Compile modules
Write-Host "[2/5] Compiling modules..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path (Join-Path $root "out\production\VCampusCommon") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root "out\production\VCampusServer") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $root "out\production\VCampusClient") | Out-Null

$commonFiles = (Get-ChildItem -Recurse -Path (Join-Path $root "VCampusCommon\src") -Filter "*.java").FullName
if ($commonFiles) {
    & $javacExe -encoding UTF-8 -cp "VCampusServer/lib/*;VCampusClient/lib/*" -d (Join-Path $root "out\production\VCampusCommon") $commonFiles
}

$serverFiles = (Get-ChildItem -Recurse -Path (Join-Path $root "VCampusServer\src") -Filter "*.java").FullName
if ($serverFiles) {
    & $javacExe -encoding UTF-8 -cp "VCampusServer/lib/*;out/production/VCampusCommon" -d (Join-Path $root "out\production\VCampusServer") $serverFiles
}

$clientFiles = (Get-ChildItem -Recurse -Path (Join-Path $root "VCampusClient\src") -Filter "*.java").FullName
if ($clientFiles) {
    & $javacExe -encoding UTF-8 -cp "VCampusClient/lib/*;out/production/VCampusCommon" -d (Join-Path $root "out\production\VCampusClient") $clientFiles
}

# 3. Synchronize resources
Write-Host "[3/5] Syncing resources..." -ForegroundColor Yellow
$serverRes = Join-Path $root "VCampusServer\src\resources"
if (Test-Path $serverRes) {
    Copy-Item -Recurse -Force $serverRes (Join-Path $root "out\production\VCampusServer\")
}
$clientRes = Join-Path $root "VCampusClient\src\resources"
if (Test-Path $clientRes) {
    Copy-Item -Recurse -Force $clientRes (Join-Path $root "out\production\VCampusClient\")
}

function Format-ManifestHeader($name, $value) {
    $line = "$name`: $value"
    $sb = New-Object System.Text.StringBuilder
    $firstLen = [Math]::Min(70, $line.Length)
    $sb.Append($line.Substring(0, $firstLen)).Append("`r`n") | Out-Null
    $pos = $firstLen
    while ($pos -lt $line.Length) {
        $remaining = $line.Length - $pos
        $chunkLen = [Math]::Min(69, $remaining)
        $sb.Append(" ").Append($line.Substring($pos, $chunkLen)).Append("`r`n") | Out-Null
        $pos += $chunkLen
    }
    return $sb.ToString()
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# 4. Package VCampusServer.jar
Write-Host "[4/5] Building VCampusServer.jar..." -ForegroundColor Yellow
$serverDist = Join-Path $root "dist\VCampusServer"
New-Item -ItemType Directory -Force -Path (Join-Path $serverDist "lib") | Out-Null
Copy-Item -Force (Join-Path $root "VCampusServer\lib\*.jar") (Join-Path $serverDist "lib\")

$serverJars = (Get-ChildItem (Join-Path $serverDist "lib\*.jar") | ForEach-Object { "lib/" + $_.Name }) -join " "
$serverMf = "Manifest-Version: 1.0`r`n"
$serverMf += "Main-Class: main.ServerMain`r`n"
$serverMf += Format-ManifestHeader "Class-Path" $serverJars
$serverMf += "`r`n"

$serverMfPath = Join-Path $serverDist "manifest.tmp"
[System.IO.File]::WriteAllText($serverMfPath, $serverMf, $utf8NoBom)
& $jarExe -cfm (Join-Path $serverDist "VCampusServer.jar") $serverMfPath -C (Join-Path $root "out\production\VCampusCommon") . -C (Join-Path $root "out\production\VCampusServer") .
Remove-Item $serverMfPath -Force

$startServerBat = "@echo off`r`nchcp 65001 >nul`r`ntitle VCampus Server`r`necho ====================================`r`necho   Starting VCampus Server...`r`necho ====================================`r`njava -jar VCampusServer.jar`r`npause`r`n"
[System.IO.File]::WriteAllText((Join-Path $serverDist "start_server.bat"), $startServerBat, [System.Text.Encoding]::ASCII)
$serverCnBat = [System.Text.Encoding]::UTF8.GetString([byte[]]@(0xE5,0x90,0xAF,0xE5,0x8A,0xA8,0xE6,0x9C,0x8D,0xE5,0x8A,0xA1,0xE7,0xAB,0xAF,0x2E,0x62,0x61,0x74))
[System.IO.File]::WriteAllText((Join-Path $serverDist $serverCnBat), $startServerBat, [System.Text.Encoding]::ASCII)

# 5. Package VCampusClient.jar
Write-Host "[5/5] Building VCampusClient.jar..." -ForegroundColor Yellow
$clientDist = Join-Path $root "dist\VCampusClient"
New-Item -ItemType Directory -Force -Path (Join-Path $clientDist "lib") | Out-Null
Copy-Item -Force (Join-Path $root "VCampusClient\lib\*.jar") (Join-Path $clientDist "lib\")

$javafxBin = "D:\JavaFX\javafx-sdk-25.0.4\bin"
if (Test-Path -LiteralPath $javafxBin) {
    Copy-Item -Force "$javafxBin\*.dll" "$clientDist\"
}

$clientJars = (Get-ChildItem (Join-Path $clientDist "lib\*.jar") | ForEach-Object { "lib/" + $_.Name }) -join " "
$clientMf = "Manifest-Version: 1.0`r`n"
$clientMf += "Main-Class: app.Launcher`r`n"
$clientMf += Format-ManifestHeader "Class-Path" $clientJars
$clientMf += "`r`n"

$clientMfPath = Join-Path $clientDist "manifest.tmp"
[System.IO.File]::WriteAllText($clientMfPath, $clientMf, $utf8NoBom)
& $jarExe -cfm (Join-Path $clientDist "VCampusClient.jar") $clientMfPath -C (Join-Path $root "out\production\VCampusCommon") . -C (Join-Path $root "out\production\VCampusClient") .
Remove-Item $clientMfPath -Force

$startClientBat = "@echo off`r`nchcp 65001 >nul`r`ntitle VCampus Client`r`necho ====================================`r`necho   Starting VCampus Client...`r`necho ====================================`r`njava -jar VCampusClient.jar`r`npause`r`n"
[System.IO.File]::WriteAllText((Join-Path $clientDist "start_client.bat"), $startClientBat, [System.Text.Encoding]::ASCII)
$clientCnBat = [System.Text.Encoding]::UTF8.GetString([byte[]]@(0xE5,0x90,0xAF,0xE5,0x8A,0xA8,0xE5,0xAE,0xA2,0xE6,0x88,0xB7,0xE7,0xAB,0xAF,0x2E,0x62,0x61,0x74))
[System.IO.File]::WriteAllText((Join-Path $clientDist $clientCnBat), $startClientBat, [System.Text.Encoding]::ASCII)

Write-Host "`n========================================" -ForegroundColor Green
Write-Host " [SUCCESS] Build and packaging complete!" -ForegroundColor Green
Write-Host " Output directories (dist/):" -ForegroundColor Green
Write-Host "  dist/VCampusServer/ (VCampusServer.jar, lib/, start_server.bat)"
Write-Host "  dist/VCampusClient/ (VCampusClient.jar, lib/, native DLLs, start_client.bat)"
Write-Host "========================================`n" -ForegroundColor Green
