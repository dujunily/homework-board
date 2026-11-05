#Requires -Version 5.1
<#
.SYNOPSIS
    作业板 · 电脑端录入页 —— Windows 免 Python 的本地服务（静态页 + WebDAV 转发 + 电视通知）。

.DESCRIPTION
    只在「没装 Python」时被 启动-网页版.bat 调起来兜底。
    用 .NET 的 TcpListener 直接说 HTTP，不依赖 http.sys，
    因此不需要管理员权限，也不会去动系统里的 URL 保留项。

    起服务而不是直接双击 index.html，是因为浏览器做不到两件事：
      1. 跨域直连坚果云（对方不发 Access-Control-Allow-Origin）→ 由 /dav-proxy 代跑
      2. 发 UDP 广播通知电视 → 由 POST /ping 代跑

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

$ProxyPath = '/dav-proxy'
$PingPort = 47823
$PingMagic = 'HOMEWORK-BOARD/SYNC/1'

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

try {
    Add-Type -AssemblyName System.Net.Http | Out-Null
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
} catch { }

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
        [string]$Method,
        [hashtable]$Extra
    )

    $reason = 'OK'
    switch ($Status) {
        201 { $reason = 'Created' }
        204 { $reason = 'No Content' }
        207 { $reason = 'Multi-Status' }
        400 { $reason = 'Bad Request' }
        401 { $reason = 'Unauthorized' }
        404 { $reason = 'Not Found' }
        405 { $reason = 'Method Not Allowed' }
        502 { $reason = 'Bad Gateway' }
    }

    $lines = New-Object System.Collections.Generic.List[string]
    $lines.Add("HTTP/1.1 $Status $reason")
    $lines.Add("Content-Type: $ContentType")
    $lines.Add("Content-Length: $($Body.Length)")
    $lines.Add('Cache-Control: no-store, must-revalidate')
    if ($Extra) { foreach ($k in $Extra.Keys) { $lines.Add("${k}: $($Extra[$k])") } }
    $lines.Add('Connection: close')
    $lines.Add('')
    $lines.Add('')
    $head = $lines -join "`r`n"

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

# ---------------- 局域网广播：告诉电视有新作业 ----------------

function Get-LocalIPv4 {
    foreach ($probe in @('223.5.5.5', '8.8.8.8', '192.168.1.1')) {
        $sock = $null
        try {
            $sock = New-Object System.Net.Sockets.Socket -ArgumentList @(
                [System.Net.Sockets.AddressFamily]::InterNetwork,
                [System.Net.Sockets.SocketType]::Dgram,
                [System.Net.Sockets.ProtocolType]::Udp)
            $sock.Connect((New-Object System.Net.IPEndPoint -ArgumentList @([System.Net.IPAddress]::Parse($probe), 53)))
            $ip = $sock.LocalEndPoint.Address.ToString()
            if ($ip -and -not $ip.StartsWith('127.')) { return $ip }
        } catch { } finally { if ($sock) { try { $sock.Close() } catch { } } }
    }
    return $null
}

function Send-Ping {
    $bytes = [System.Text.Encoding]::ASCII.GetBytes($PingMagic)
    $targets = New-Object System.Collections.Generic.List[string]
    $ip = Get-LocalIPv4
    if ($ip) {
        $parts = $ip.Split('.')
        if ($parts.Length -eq 4) { $targets.Add(('{0}.{1}.{2}.255' -f $parts[0], $parts[1], $parts[2])) }
    }
    $targets.Add('255.255.255.255')

    $sent = 0
    for ($round = 0; $round -lt 3; $round++) {
        foreach ($t in $targets) {
            $udp = $null
            try {
                $udp = New-Object System.Net.Sockets.UdpClient
                $udp.EnableBroadcast = $true
                $udp.Send($bytes, $bytes.Length, $t, $PingPort) | Out-Null
                $sent++
            } catch { } finally { if ($udp) { try { $udp.Close() } catch { } } }
        }
        if ($round -lt 2) { Start-Sleep -Milliseconds 120 }
    }
    return $sent
}

# ---------------- WebDAV 转发 ----------------
# 页面和 dav.jianguoyun.com 不同源，坚果云又不发 CORS 头，只能由本机代跑。
# Authorization 头跟着浏览器的请求走，服务端不读也不存。

function Invoke-Proxy {
    param($Stream, [string]$Method, [string]$Target, [hashtable]$Headers, [byte[]]$Body)

    $queryIndex = $Target.IndexOf('?')
    if ($queryIndex -lt 0) { Send-Plain $Stream 400 'missing query' $Method; return }
    $query = $Target.Substring($queryIndex + 1)

    $base = $null
    $sub = ''
    foreach ($pair in $query.Split('&')) {
        $eq = $pair.IndexOf('=')
        if ($eq -lt 0) { continue }
        $k = $pair.Substring(0, $eq)
        $v = [System.Uri]::UnescapeDataString($pair.Substring($eq + 1).Replace('+', ' '))
        if ($k -eq 'base') { $base = $v.TrimEnd('/') }
        elseif ($k -eq 'path') { $sub = $v }
    }

    if (-not $base -or -not ($base -match '^https?://')) {
        Send-Plain $Stream 400 'base must be an absolute http(s) url' $Method
        return
    }

    $url = $base
    foreach ($seg in $sub.Split('/')) {
        if ($seg) { $url += '/' + [System.Uri]::EscapeDataString($seg) }
    }

    $auth = $Headers['authorization']
    if (-not $auth) { Send-Plain $Stream 401 'missing Authorization' $Method; return }

    try {
        $client = New-Object System.Net.Http.HttpClient
        $client.Timeout = [TimeSpan]::FromSeconds(60)

        $request = New-Object System.Net.Http.HttpRequestMessage -ArgumentList @(
            (New-Object System.Net.Http.HttpMethod -ArgumentList $Method), $url)
        $request.Headers.TryAddWithoutValidation('Authorization', $auth) | Out-Null
        foreach ($h in @('depth', 'if-none-match', 'if-match', 'destination', 'overwrite', 'accept', 'content-range')) {
            if ($Headers[$h]) { $request.Headers.TryAddWithoutValidation($h, $Headers[$h]) | Out-Null }
        }
        if ($Body -and $Body.Length -gt 0) {
            $request.Content = New-Object System.Net.Http.ByteArrayContent -ArgumentList @(, $Body)
            $ct = $Headers['content-type']
            if (-not $ct) { $ct = 'application/octet-stream' }
            $request.Content.Headers.TryAddWithoutValidation('Content-Type', $ct) | Out-Null
        }

        $resp = $client.SendAsync($request).GetAwaiter().GetResult()
        $status = [int]$resp.StatusCode
        $respBody = $resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        if (-not $respBody) { $respBody = New-Object byte[] 0 }

        $extra = @{}
        $contentType = 'application/octet-stream'
        $ctype = $resp.Content.Headers.ContentType
        if ($ctype) { $contentType = [string]$ctype }
        $etag = $resp.Headers.ETag
        if ($etag) { $extra['ETag'] = $etag.ToString() }

        $client.Dispose()
        Write-Response -Stream $Stream -Status $status -ContentType $contentType -Body $respBody -Method $Method -Extra $extra
    } catch {
        Send-Plain $Stream 502 ("upstream failed: " + $_.Exception.Message) $Method
    }
}

# ---------------- 请求解析 ----------------

function Read-Request {
    param($Stream)
    $buf = New-Object byte[] 16384
    $acc = New-Object System.IO.MemoryStream
    $headerEnd = -1

    while ($headerEnd -lt 0) {
        $n = $Stream.Read($buf, 0, $buf.Length)
        if ($n -le 0) { break }
        $acc.Write($buf, 0, $n)

        $arr = $acc.GetBuffer()
        $len = [int]$acc.Length
        $from = [Math]::Max(0, $len - $n - 3)
        for ($i = $from; $i -lt $len - 3; $i++) {
            if ($arr[$i] -eq 13 -and $arr[$i + 1] -eq 10 -and $arr[$i + 2] -eq 13 -and $arr[$i + 3] -eq 10) {
                $headerEnd = $i + 4
                break
            }
        }
    }

    if ($headerEnd -le 0) { return $null }

    $all = $acc.ToArray()
    $headText = [System.Text.Encoding]::ASCII.GetString($all, 0, $headerEnd - 4)
    $lines = $headText -split "`r`n"
    if ($lines.Length -lt 1) { return $null }

    $tokens = $lines[0].Split(' ')
    if ($tokens.Length -lt 2) { return $null }

    $headers = @{}
    for ($i = 1; $i -lt $lines.Length; $i++) {
        $colon = $lines[$i].IndexOf(':')
        if ($colon -gt 0) {
            $name = $lines[$i].Substring(0, $colon).Trim().ToLowerInvariant()
            $headers[$name] = $lines[$i].Substring($colon + 1).Trim()
        }
    }

    $bodyLen = 0
    if ($headers['content-length']) { [int]::TryParse($headers['content-length'], [ref]$bodyLen) | Out-Null }

    $already = $all.Length - $headerEnd
    $body = New-Object byte[] $bodyLen
    if ($already -gt 0) {
        $copy = [Math]::Min($already, $bodyLen)
        [Array]::Copy($all, $headerEnd, $body, 0, $copy)
    }
    $got = [Math]::Min($already, $bodyLen)
    while ($got -lt $bodyLen) {
        $n = $Stream.Read($body, $got, $bodyLen - $got)
        if ($n -le 0) { break }
        $got += $n
    }

    return @{
        Method  = $tokens[0].ToUpperInvariant()
        Target  = $tokens[1]
        Headers = $headers
        Body    = $body
    }
}

function Serve-One {
    param($Client)

    $stream = $Client.GetStream()
    $stream.ReadTimeout = 15000
    $stream.WriteTimeout = 60000

    $req = Read-Request -Stream $stream
    if (-not $req) { return }

    $method = $req.Method
    $target = $req.Target
    $q = $target.IndexOf('?')
    $pathOnly = if ($q -ge 0) { $target.Substring(0, $q) } else { $target }

    # 1) WebDAV 转发
    if ($pathOnly -eq $ProxyPath) {
        Invoke-Proxy -Stream $stream -Method $method -Target $target -Headers $req.Headers -Body $req.Body
        return
    }

    # 2) 通知电视
    if ($pathOnly -eq '/ping' -and $method -eq 'POST') {
        $sent = 0
        try { $sent = Send-Ping } catch { }
        $body = [System.Text.Encoding]::UTF8.GetBytes(('{"ok":true,"packets":' + $sent + '}'))
        Write-Response -Stream $stream -Status 200 -ContentType 'application/json; charset=utf-8' -Body $body -Method $method
        return
    }

    # 3) 静态文件
    if ($method -ne 'GET' -and $method -ne 'HEAD') {
        Send-Plain $stream 405 'Method Not Allowed' $method
        return
    }

    $target = $pathOnly
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
Write-Host ' 首次使用填坚果云账号 + 应用专用密码（不是登录密码）'
Write-Host " 保存后会自动通知局域网里的电视去拉（端口 $PingPort）"
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
