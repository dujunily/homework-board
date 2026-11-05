#Requires -Version 5.1
<#
.SYNOPSIS
    作业板 · 电脑端录入页 —— Windows 免 Python 的本地静态服务。

.DESCRIPTION
    只在「没装 Python」时被 启动-网页版.bat 调起来兜底。
    用 .NET 的 TcpListener 直接说 HTTP，不依赖 http.sys，
    因此不需要管理员权限，也不会去动系统里的 URL 保留项。

    为什么非要起服务而不用 file:// 打开 index.html：
    Chrome / Edge 在 file:// 下不把页面当安全上下文，「选择文件夹」拿不到写入权限。

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File serve.ps1
    powershell -ExecutionPolicy Bypass -File serve.ps1 8123
    powershell -ExecutionPolicy Bypass -File serve.ps1 -NoBrowser
#>
[CmdletBinding()]
param(
    [int]$Port = 0,
    [switch]$NoBrowser
)

$ErrorActionPreference = 'Stop'

$Root = $PSScriptRoot
if (-not $Root) { $Root = Split-Path -Parent $MyInvocation.MyCommand.Definition }
$Root = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
$RootPrefix = $Root + [System.IO.Path]::DirectorySeparatorChar

$Mime = @{
    '.html' = 'text/html; charset=utf-8'
    '.htm'  = 'text/html; charset=utf-8'
    '.js'   = 'application/javascript; charset=utf-8'
    '.css'  = 'text/css; charset=utf-8'
    '.json' = 'application/json; charset=utf-8'
    '.txt'  = 'text/plain; charset=utf-8'
    '.md'   = 'text/plain; charset=utf-8'
    '.svg'  = 'image/svg+xml'
    '.png'  = 'image/png'
    '.jpg'  = 'image/jpeg'
    '.jpeg' = 'image/jpeg'
    '.gif'  = 'image/gif'
    '.webp' = 'image/webp'
    '.ico'  = 'image/x-icon'
}

function Find-FreePort {
    param([int]$Start = 8123, [int]$End = 8160)
    for ($p = $Start; $p -le $End; $p++) {
        $probe = $null
        try {
            $probe = New-Object System.Net.Sockets.TcpListener -ArgumentList @([System.Net.IPAddress]::Loopback, $p)
            $probe.Start()
            $probe.Stop()
            return $p
        } catch {
            if ($probe) { try { $probe.Stop() } catch { } }
        }
    }
    return 0
}

function Write-Response {
    param(
        $Stream,
        [int]$Status,
        [string]$ContentType,
        [byte[]]$Body,
        [string]$Method
    )

    $reason = 'OK'
    if ($Status -eq 404) { $reason = 'Not Found' }
    elseif ($Status -eq 400) { $reason = 'Bad Request' }
    elseif ($Status -eq 405) { $reason = 'Method Not Allowed' }

    $head = @(
        "HTTP/1.1 $Status $reason"
        "Content-Type: $ContentType"
        "Content-Length: $($Body.Length)"
        'Cache-Control: no-store, must-revalidate'
        'Connection: close'
        ''
        ''
    ) -join "`r`n"

    $headBytes = [System.Text.Encoding]::ASCII.GetBytes($head)
    $Stream.Write($headBytes, 0, $headBytes.Length)
    if ($Method -ne 'HEAD' -and $Body.Length -gt 0) {
        $Stream.Write($Body, 0, $Body.Length)
    }
    $Stream.Flush()
}

function Send-Plain {
    param($Stream, [int]$Status, [string]$Text, [string]$Method)
    $body = [System.Text.Encoding]::UTF8.GetBytes($Text)
    Write-Response -Stream $Stream -Status $Status -ContentType 'text/plain; charset=utf-8' -Body $body -Method $Method
}

function Serve-One {
    param($Client)

    $stream = $Client.GetStream()
    $stream.ReadTimeout = 5000
    $stream.WriteTimeout = 15000

    $reader = New-Object System.IO.StreamReader -ArgumentList @($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)

    $requestLine = $reader.ReadLine()
    if ([string]::IsNullOrWhiteSpace($requestLine)) { return }

    # 把请求头读干净（不读会污染下一个请求）
    while ($true) {
        $line = $reader.ReadLine()
        if ($null -eq $line -or $line -eq '') { break }
    }

    $tokens = $requestLine.Split(' ')
    if ($tokens.Length -lt 2) { Send-Plain $stream 400 'Bad Request' 'GET'; return }

    $method = $tokens[0].ToUpperInvariant()
    if ($method -ne 'GET' -and $method -ne 'HEAD') {
        Send-Plain $stream 405 'Method Not Allowed' $method
        return
    }

    $target = $tokens[1]
    $q = $target.IndexOf('?')
    if ($q -ge 0) { $target = $target.Substring(0, $q) }
    $target = [System.Uri]::UnescapeDataString($target)
    if ($target -eq '/' -or $target -eq '') { $target = '/index.html' }

    $wantDirectory = $target.EndsWith('/')
    $relative = $target.TrimStart('/')
    $relative = $relative -replace '/', [System.IO.Path]::DirectorySeparatorChar

    $full = [System.IO.Path]::GetFullPath((Join-Path $Root $relative))
    if ($wantDirectory) { $full = Join-Path $full 'index.html' }

    # 目录穿越防护
    if (-not ($full.StartsWith($RootPrefix, [System.StringComparison]::OrdinalIgnoreCase))) {
        Send-Plain $stream 404 'Not Found' $method
        return
    }

    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        Send-Plain $stream 404 ("Not Found: " + $target) $method
        return
    }

    $ext = [System.IO.Path]::GetExtension($full).ToLowerInvariant()
    $type = $Mime[$ext]
    if (-not $type) { $type = 'application/octet-stream' }

    $bytes = [System.IO.File]::ReadAllBytes($full)
    Write-Response -Stream $stream -Status 200 -ContentType $type -Body $bytes -Method $method
}

# ---------------- 启动 ----------------

if ($Port -le 0) { $Port = Find-FreePort }
if ($Port -le 0) {
    Write-Host '端口 8123~8160 都被占用了，先把别的服务关掉再试。'
    exit 1
}

$listener = $null
try {
    $listener = New-Object System.Net.Sockets.TcpListener -ArgumentList @([System.Net.IPAddress]::Loopback, $Port)
    $listener.Start()
} catch {
    Write-Host ("端口 {0} 起不来：{1}" -f $Port, $_.Exception.Message)
    exit 1
}

$url = "http://127.0.0.1:$Port/index.html"
$line = '-' * 52
Write-Host $line
Write-Host " 作业板录入页已启动： $url"
Write-Host ' 首次使用点「选择文件夹」，选中坚果云里的 作业板 目录'
Write-Host ''
Write-Host ' 关闭这个窗口（或按 Ctrl+C）即停止服务，录入过程中别关'
Write-Host $line

if (-not $NoBrowser) {
    Start-Process $url
}

try {
    while ($true) {
        $client = $listener.AcceptTcpClient()
        try {
            Serve-One -Client $client
        } catch {
            # 单个请求出错不影响服务
        } finally {
            try { $client.Close() } catch { }
        }
    }
} finally {
    if ($listener) { try { $listener.Stop() } catch { } }
}
