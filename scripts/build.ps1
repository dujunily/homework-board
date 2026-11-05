#Requires -Version 5.1
<#
.SYNOPSIS
    作业板 · 一键构建（Windows）

.DESCRIPTION
    找到 JDK 17+ 和 Android SDK → 写 local.properties → 用 gradlew.bat 打两个 release 包
    → 按版本号改名放进 dist\；带 web 参数时把网页端打成一个可直接托管的目录。

.EXAMPLE
    .\scripts\build.ps1
    .\scripts\build.ps1 -Target tv
    .\scripts\build.ps1 -Target web

    执行策略拦住了的话，用 build.cmd（已经带 -ExecutionPolicy Bypass）：
    .\scripts\build.cmd tv
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'apk', 'tv', 'phone', 'web')]
    [string]$Target = 'all'
)

$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
if (-not $Root) { $Root = (Get-Location).Path }
$Dist = Join-Path $Root 'dist'

function Write-Say { param([string]$Text) Write-Host $Text }
function Write-Hr { Write-Host ('-' * 52) }
function Stop-WithError {
    param([string]$Text)
    Write-Host ''
    Write-Host "x $Text" -ForegroundColor Red
    exit 1
}

# ---------- 版本号 ----------
function Get-ModuleVersion {
    param([string]$Module)
    $file = Join-Path $Root "$Module\build.gradle.kts"
    if (-not (Test-Path $file)) { return $null }
    $text = Get-Content -LiteralPath $file -Raw -Encoding UTF8
    $m = [regex]::Match($text, 'versionName\s*=\s*"([^"]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# ---------- JDK ----------
function Get-JavaMajor {
    param([string]$JavaExe)
    try {
        $out = (& $JavaExe -version 2>&1) | Out-String
    } catch {
        return 0
    }
    $m = [regex]::Match($out, 'version "([^"]+)"')
    if (-not $m.Success) { return 0 }
    $v = $m.Groups[1].Value
    if ($v.StartsWith('1.')) { $v = $v.Substring(2) }
    $n = [regex]::Match($v, '^(\d+)')
    if ($n.Success) { return [int]$n.Groups[1].Value }
    return 0
}

function Find-Jdk {
    $candidates = New-Object System.Collections.ArrayList

    if ($env:JAVA_HOME) { [void]$candidates.Add($env:JAVA_HOME) }

    foreach ($base in @(
            'C:\Program Files\Android\Android Studio\jbr',
            'C:\Program Files\Android\Android Studio\jre',
            "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
            'C:\Program Files\Eclipse Adoptium',
            'C:\Program Files\Java',
            'C:\Program Files\Microsoft',
            'C:\Program Files\Amazon Corretto',
            'C:\Program Files\Zulu',
            'C:\Program Files\BellSoft'
        )) {
        if (Test-Path -LiteralPath $base) {
            if (Test-Path -LiteralPath (Join-Path $base 'bin\java.exe')) {
                [void]$candidates.Add($base)
            }
            Get-ChildItem -LiteralPath $base -Directory -ErrorAction SilentlyContinue |
                Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
                ForEach-Object { [void]$candidates.Add($_.FullName) }
        }
    }

    $best = $null
    $bestRank = -1
    $bestMajor = 0
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $java = Join-Path $c 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $java)) { continue }
        $major = Get-JavaMajor $java
        if ($major -lt 17) { continue }

        # Gradle 8.10 跑不动 JDK 24+，所以不是「越大越好」，按稳的顺序挑
        $rank = 10
        if ($major -eq 21) { $rank = 100 }
        elseif ($major -eq 17) { $rank = 95 }
        elseif ($major -eq 22) { $rank = 80 }
        elseif ($major -eq 23) { $rank = 70 }
        elseif ($major -ge 18 -and $major -le 20) { $rank = 60 }

        if ($rank -gt $bestRank -or ($rank -eq $bestRank -and $major -gt $bestMajor)) {
            $bestRank = $rank
            $bestMajor = $major
            $best = $c
        }
    }
    return $best
}

# ---------- Android SDK ----------
function Find-AndroidSdk {
    $propsFile = Join-Path $Root 'local.properties'
    $fromProps = $null
    if (Test-Path -LiteralPath $propsFile) {
        $line = (Get-Content -LiteralPath $propsFile -Encoding ASCII | Where-Object { $_ -match '^\s*sdk\.dir\s*=' } | Select-Object -First 1)
        if ($line) {
            $fromProps = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\(.)', '$1'
        }
    }

    foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $fromProps,
            "$env:LOCALAPPDATA\Android\Sdk", 'C:\Android\Sdk', 'D:\Android\Sdk')) {
        if (-not $c) { continue }
        if (Test-Path -LiteralPath (Join-Path $c 'platforms')) { return $c }
    }
    return $null
}

function Initialize-Env {
    $jdk = Find-Jdk
    if (-not $jdk) {
        Stop-WithError @"
没找到 JDK 17 以上。
  装一个即可：
    winget install EclipseAdoptium.Temurin.21.JDK
  或装 Android Studio（自带 JBR，脚本会自动认出来）。
  已经有 JDK 的话，把 JAVA_HOME 指过去再跑。
"@
    }
    $env:JAVA_HOME = $jdk

    $sdk = Find-AndroidSdk
    if (-not $sdk) {
        Stop-WithError @"
没找到 Android SDK（目录下要有 platforms\）。
  Android Studio → SDK Manager 里勾一个 Android 15 (API 35)，
  或者设个环境变量：setx ANDROID_HOME "C:\Users\你\AppData\Local\Android\Sdk"
"@
    }
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk

    # local.properties 不进仓库，每人本地一份
    $propsFile = Join-Path $Root 'local.properties'
    $escaped = $sdk -replace '\\', '\\' -replace ':', '\:'
    $want = "sdk.dir=$escaped"
    $have = $null
    if (Test-Path -LiteralPath $propsFile) {
        $have = (Get-Content -LiteralPath $propsFile -Encoding ASCII -Raw)
        if ($have) { $have = $have.Trim() }
    }
    if ($have -ne $want) {
        Set-Content -LiteralPath $propsFile -Value $want -Encoding ASCII
    }

    Write-Say ("  JDK   {0} ({1})" -f $env:JAVA_HOME, (Get-JavaMajor (Join-Path $env:JAVA_HOME 'bin\java.exe')))
    Write-Say ("  SDK   {0}" -f $env:ANDROID_HOME)
}

# ---------- 构建 ----------
function Invoke-Gradle {
    param([string[]]$Tasks)
    $bat = Join-Path $Root 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $bat)) { Stop-WithError "找不到 gradlew.bat，确认在仓库根目录。" }
    Push-Location $Root
    try {
        & $bat @Tasks
        if ($LASTEXITCODE -ne 0) { Stop-WithError 'Gradle 构建失败，上面的报错就是原因。' }
    } finally {
        Pop-Location
    }
}

function Build-Apks {
    $modules = switch ($Target) {
        'tv' { @('tvapp') }
        'phone' { @('phoneapp') }
        default { @('tvapp', 'phoneapp') }
    }

    Write-Hr
    Write-Say (" 构建 APK：{0}" -f ($modules -join ', '))
    Initialize-Env

    $tasks = @()
    foreach ($m in $modules) { $tasks += ":$m:assembleRelease" }

    Write-Hr
    Invoke-Gradle -Tasks $tasks

    if (-not (Test-Path -LiteralPath $Dist)) { New-Item -ItemType Directory -Path $Dist | Out-Null }

    foreach ($m in $modules) {
        $label = 'TV'
        if ($m -eq 'phoneapp') { $label = '手机' }

        $ver = Get-ModuleVersion $m
        if (-not $ver) { $ver = 'unknown' }

        $apkDir = Join-Path $Root "$m\build\outputs\apk\release"
        $apk = Join-Path $apkDir "$m-release.apk"
        if (-not (Test-Path -LiteralPath $apk)) {
            $apk = Get-ChildItem -LiteralPath $apkDir -Filter '*.apk' -ErrorAction SilentlyContinue |
                Select-Object -First 1 -ExpandProperty FullName
        }
        if (-not $apk) { Stop-WithError "构建完成了但没找到 $m 的 APK，去 $m\build\outputs\apk\ 看看。" }

        $outFile = Join-Path $Dist ("作业板-{0}-{1}.apk" -f $label, $ver)
        Copy-Item -LiteralPath $apk -Destination $outFile -Force
        $size = [math]::Round((Get-Item -LiteralPath $outFile).Length / 1MB, 2)
        Write-Say ("  OK  {0}  ({1} MB)" -f $outFile, $size)
    }
}

function Package-Web {
    Write-Hr
    Write-Say ' 打包网页端'

    $out = Join-Path $Dist '网页版'
    if (Test-Path -LiteralPath $out) { Remove-Item -LiteralPath $out -Recurse -Force }
    New-Item -ItemType Directory -Path $out -Force | Out-Null

    $web = Join-Path $Root 'webapp'
    foreach ($f in @('index.html', 'serve.py', 'serve.ps1', 'start.sh',
            '启动-网页版.command', '启动-网页版.bat')) {
        $src = Join-Path $web $f
        if (Test-Path -LiteralPath $src) { Copy-Item -LiteralPath $src -Destination (Join-Path $out $f) -Force }
    }
    $readme = Join-Path $web 'README.md'
    if (Test-Path -LiteralPath $readme) {
        Copy-Item -LiteralPath $readme -Destination (Join-Path $out '使用说明.md') -Force
    }

    Write-Say ("  OK  {0}" -f $out)

    # 自己写 zip，而不是 Compress-Archive：后者不保留 Unix 权限位，
    # 拷到 macOS 上解压后 .command 就丢了可执行位、双击没反应。
    $zip = Join-Path $Dist '作业板-网页版.zip'
    if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    Add-Type -AssemblyName System.IO.Compression

    $files = Get-ChildItem -LiteralPath $out -File
    $archive = [System.IO.Compression.ZipFile]::Open($zip, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($f in $files) {
            $entryName = '网页版/' + $f.Name
            $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = $f.LastWriteTime

            # 可执行位：解压后 macOS / Linux 上能直接双击或 ./ 运行
            $mode = 0x81A4 # 0o100644
            if ($f.Extension -eq '.sh' -or $f.Extension -eq '.command') { $mode = 0x81ED } # 0o100755
            $entry.ExternalAttributes = $mode -shl 16

            $in = [System.IO.File]::OpenRead($f.FullName)
            try {
                $dst = $entry.Open()
                try { $in.CopyTo($dst) } finally { $dst.Dispose() }
            } finally { $in.Close() }
        }
    } finally {
        $archive.Dispose()
    }
    Write-Say ("  OK  {0}" -f $zip)
}

# ---------- 入口 ----------
switch ($Target) {
    'all' { Build-Apks; Package-Web }
    'apk' { Build-Apks }
    'tv' { Build-Apks }
    'phone' { Build-Apks }
    'web' { Package-Web }
}

Write-Hr
Write-Say (" 完成。产物在 {0}" -f $Dist)
Write-Hr
