<#
.SYNOPSIS
P0 只读发现脚本：在不改动设备任何文件的前提下，摸清游戏热更缓存的结构。

.DESCRIPTION
回答三个决定架构的问题：
  1. 已安装哪个游戏包（官方 / 共存版 / 渠道服），版本号与 ABI；
  2. 热更缓存里有哪些 bundle，`__data` / `__info` 的格式与大小；
  3. `AstralParty.Runtime.dll` 到底在 bundle 里，还是落盘成独立文件。

全程只执行 `pm` / `ls` / `stat` / `find` / `grep` / `head` 这类只读命令；
真正拉样本要显式加 -Pull。

.PARAMETER Serial
adb 序列号。省略时若只有一台设备则自动使用，多台会报错列出候选。

.PARAMETER GamePackage
游戏包名。省略时按 com.feimo.astralparty / com.feimo.jxpd 前缀自动挑选。

.PARAMETER OutDir
报告目录，默认 tests/fixtures/real/p0-<时间戳>（已在 .gitignore 中）。

.PARAMETER Pull
把命中的 __data / __info 拉回本地，供 pe_blob_scan.py 离线提取 DLL。

.EXAMPLE
pwsh tools/p0-discovery.ps1 -Serial 127.0.0.1:5555 -Pull
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$GamePackage = '',
    [string]$OutDir = '',
    [switch]$Pull
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$gameRoots = @('com.feimo.astralparty', 'com.feimo.jxpd')

if (-not $OutDir) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $OutDir = Join-Path $repoRoot "tests/fixtures/real/p0-$stamp"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

function Write-Section([string]$name, [string]$content) {
    $path = Join-Path $OutDir "$name.txt"
    $content | Set-Content -Path $path -Encoding utf8
    return $path
}

function Invoke-Adb {
    param([string[]]$Arguments)
    $result = & adb @Arguments 2>&1
    return @($result | ForEach-Object { "$_".TrimEnd("`r") })
}

function Invoke-AdbShell([string]$command) {
    return Invoke-Adb @('-s', $script:target, 'shell', $command)
}

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    throw 'adb 不在 PATH 上'
}

# ---- 设备 ----------------------------------------------------------------
$deviceLines = (Invoke-Adb @('devices', '-l')) | Where-Object { $_ -and $_ -notmatch '^List of devices' -and $_ -match '\sdevice\b' }
if (-not $deviceLines) {
    throw '没有处于 device 状态的 adb 设备'
}

$serials = @($deviceLines | ForEach-Object { ($_ -split '\s+')[0] })
if ($Serial) {
    if ($serials -notcontains $Serial) { throw "指定的设备不在线：$Serial（在线：$($serials -join ', ')）" }
    $script:target = $Serial
} elseif ($serials.Count -eq 1) {
    $script:target = $serials[0]
} else {
    throw "有多台设备，请用 -Serial 指定：$($serials -join ', ')"
}

"设备: $script:target" | Write-Host
Write-Section 'devices' (($deviceLines -join "`n")) | Out-Null

# ---- 包 ------------------------------------------------------------------
$packageLines = Invoke-AdbShell 'pm list packages'
Write-Section 'packages' (($packageLines | Where-Object { $_ -match 'feimo|astralparty|jxpd|cesium' }) -join "`n") | Out-Null

if (-not $GamePackage) {
    foreach ($root in $gameRoots) {
        $hit = $packageLines | Where-Object { $_ -match "^package:$([regex]::Escape($root))(\.[A-Za-z0-9_]+)*$" } | Select-Object -First 1
        if ($hit) { $GamePackage = $hit -replace '^package:', ''; break }
    }
}

$filesDir = ''
if (-not $GamePackage) {
    "未找到游戏包（查找 $($gameRoots -join ' / ')）。" | Write-Host -ForegroundColor Yellow
    Write-Section 'summary' '未找到游戏包，无法继续。' | Out-Null
    return
}

"游戏包: $GamePackage" | Write-Host
$apkLines = Invoke-AdbShell "pm path $GamePackage"
Write-Section 'apk' (($apkLines -join "`n")) | Out-Null

$dumpLines = Invoke-AdbShell "dumpsys package $GamePackage"
Write-Section 'dumpsys' (($dumpLines | Select-Object -First 120) -join "`n") | Out-Null
$dumpText = ($dumpLines -join "`n")
$versionName = ''
$versionCode = ''
if ($dumpText -match 'versionName=(\S+)') { $versionName = $matches[1] }
if ($dumpText -match 'versionCode=(\d+)') { $versionCode = $matches[1] }
"版本: $versionName ($versionCode)" | Write-Host

$filesDir = "/sdcard/Android/data/$GamePackage/files"
$filesExists = (Invoke-AdbShell "test -d '$filesDir' && echo yes || echo no") -contains 'yes'
if (-not $filesExists) {
    "游戏数据目录不存在：$filesDir（先启动一次游戏完成热更）" | Write-Host -ForegroundColor Yellow
    Write-Section 'summary' "游戏数据目录不存在：$filesDir" | Out-Null
    return
}

# ---- 目录结构 -------------------------------------------------------------
$treeLines = Invoke-AdbShell "find '$filesDir' -maxdepth 2 -type d 2>/dev/null | head -60"
Write-Section 'tree' (($treeLines -join "`n")) | Out-Null
$topLines = Invoke-AdbShell "ls -la '$filesDir'"
Write-Section 'files-root' (($topLines -join "`n")) | Out-Null

# ---- 松散 DLL ------------------------------------------------------------
$dllLines = Invoke-AdbShell "find '$filesDir' -maxdepth 5 -iname '*.dll' 2>/dev/null | head -40"
Write-Section 'loose-dlls' (($dllLines -join "`n")) | Out-Null
$looseDlls = @($dllLines | Where-Object { $_ -match '\.dll$' })

# ---- bundle 缓存 ----------------------------------------------------------
$cacheDir = "$filesDir/com.unity.addressables/AssetBundles"
$cacheExists = (Invoke-AdbShell "test -d '$cacheDir' && echo yes || echo no") -contains 'yes'

$entries = @()
if ($cacheExists) {
    $cacheLines = Invoke-AdbShell "ls -la '$cacheDir'"
    Write-Section 'cache-root' (($cacheLines -join "`n")) | Out-Null

    $dirs = @($cacheLines | Where-Object { $_ -match '^d' } | ForEach-Object { ($_ -split '\s+')[-1] } | Where-Object { $_ -match '^[0-9a-f]{32}$' })
    "bundle 缓存条目: $($dirs.Count)" | Write-Host

    $rows = New-Object System.Collections.Generic.List[string]
    $rows.Add("key`tdataBytes`tinfoBytes`tdataMtime`thasDllMarker")

    foreach ($dir in $dirs) {
        $statLine = (Invoke-AdbShell "stat -c '%s %Y' '$cacheDir/$dir/__data' 2>/dev/null") -join ' '
        $infoStat = (Invoke-AdbShell "stat -c '%s' '$cacheDir/$dir/__info' 2>/dev/null") -join ' '
        $dataBytes = ($statLine -split '\s+')[0]
        $dataMtime = ($statLine -split '\s+')[1]
        $infoBytes = ($infoStat -split '\s+')[0]

        $marker = Invoke-AdbShell "grep -a -c 'AstralParty.Runtime.dll' '$cacheDir/$dir/__data' 2>/dev/null"
        $hit = ($marker | Select-Object -First 1)
        if (-not $hit) { $hit = '0' }

        $rows.Add("$dir`t$dataBytes`t$infoBytes`t$dataMtime`t$hit")
        $entries += [pscustomobject]@{
            Key       = $dir
            DataBytes = $dataBytes
            InfoBytes = $infoBytes
            Mtime     = $dataMtime
            Marker    = $hit
        }
    }

    Write-Section 'assetbundles' (($rows -join "`n")) | Out-Null

    # __info 头（ASCII 文本头，用来确认 -1 / size / 1 / __data 格式）
    $infoHeads = New-Object System.Collections.Generic.List[string]
    foreach ($entry in ($entries | Where-Object { $_.Marker -match '^[1-9]' } | Select-Object -First 2)) {
        $head = (Invoke-AdbShell "head -c 120 '$cacheDir/$($entry.Key)/__info' 2>/dev/null") -join "`n"
        $infoHeads.Add("== $($entry.Key) ==")
        $infoHeads.Add($head)
    }
    Write-Section 'cache-info-heads' (($infoHeads -join "`n")) | Out-Null

    if ($Pull) {
        foreach ($entry in ($entries | Where-Object { $_.Marker -match '^[1-9]' })) {
            $target = Join-Path $OutDir $entry.Key
            New-Item -ItemType Directory -Force -Path $target | Out-Null
            & adb -s $script:target pull "$cacheDir/$($entry.Key)/__data" (Join-Path $target '__data') | Out-Null
            & adb -s $script:target pull "$cacheDir/$($entry.Key)/__info" (Join-Path $target '__info') | Out-Null
            "已拉取: $($entry.Key)" | Write-Host
        }
    }
}

# ---- 结论 ----------------------------------------------------------------
$hits = @($entries | Where-Object { $_.Marker -match '^[1-9]' })
$summary = New-Object System.Collections.Generic.List[string]
$summary.Add("设备: $script:target")
$summary.Add("游戏包: $GamePackage")
$summary.Add("版本: $versionName ($versionCode)")
$summary.Add("数据目录: $filesDir")
$summary.Add("bundle 缓存: " + $(if ($cacheExists) { "$cacheDir（$($entries.Count) 条）" } else { '不存在' }))
$summary.Add("含 AstralParty.Runtime.dll 标记的 bundle: $($hits.Count)")
$summary.Add("松散 DLL: $($looseDlls.Count)")

if ($looseDlls.Count -gt 0) {
    $summary.Add('')
    $summary.Add('松散 DLL 列表（若其中有 AstralParty.Runtime.dll，走「复制文件」路线最省事）：')
    foreach ($item in $looseDlls) { $summary.Add("  $item") }
}
if ($hits.Count -gt 0) {
    $summary.Add('')
    $summary.Add('候选 bundle（走「bundle 手术」路线）：')
    foreach ($item in $hits) { $summary.Add("  $($item.Key)  __data=$($item.DataBytes)  __info=$($item.InfoBytes)") }
}

Write-Section 'summary' (($summary -join "`n")) | Out-Null
$summary | Select-Object -Skip 3 | Write-Host
"报告目录: $OutDir" | Write-Host
if ($hits.Count -gt 0 -and -not $Pull) {
    "提示：加 -Pull 可把候选 __data 拉回本地，再用 tools/pe_blob_scan.py 提取 DLL。" | Write-Host
}
