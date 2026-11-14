<#
.SYNOPSIS
    把加载器载荷（SDK / 引导 DLL / 模组 / 配置）部署到设备上的加载器根目录。

.DESCRIPTION
    只做「文件搬运 + 校验」，不碰热更缓存包（那是 cesium-inject.ps1 的事）。

    目标设备上加载器根目录形如
        /sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/
    权限是 `2770`（shell 组可写）、末级目录 `2750`（只能覆盖已有文件，不能新建），
    所以统一用「推到 /sdcard/Download 再灌进目标」的方式，避免 adb push 直接写叶子目录失败。

.PARAMETER Serial
    设备序列号（如 `127.0.0.1:5555`）。

.PARAMETER Payload
    本地载荷目录，至少包含：
        CesiumLoader.SDK.dll
        CesiumLoader.Android.Bootstrap.dll
    可选（存在就一起推）：
        CesiumAndroidApi.dll、overlay.png、mods/、configs/、skins/、audio/、audio-schemes/

.PARAMETER LoaderRoot
    设备上的加载器根目录。

.PARAMETER Mirror
    连 `mods/` 一起同步（默认开）。加 `-NoMods` 只推加载器本体。

.EXAMPLE
    .\cesium-deploy.ps1 -Serial 127.0.0.1:5555 -Payload ..\..\outputs\payload
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [Parameter(Mandatory = $true)][string]$Payload,
    [string]$Package = 'com.feimo.astralparty',
    [string]$LoaderRoot = '/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader',
    [switch]$NoMods
)

$ErrorActionPreference = 'Stop'

function Write-Step([string]$text) { Write-Host "[deploy] $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "[ok]     $text" -ForegroundColor Green }
function Fail([string]$text) { Write-Host "[fail]   $text" -ForegroundColor Red; exit 1 }

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }
    foreach ($path in $candidates) { if (Test-Path $path) { return $path } }
    Fail '找不到 adb：请把 platform-tools 加进 PATH，或设置 ANDROID_HOME'
}

$adbPath = Resolve-Adb

function Invoke-Adb([string]$arguments, [switch]$AllowFail) {
    $output = & $adbPath -s $Serial shell $arguments 2>&1
    if ($LASTEXITCODE -ne 0 -and -not $AllowFail) { Fail "adb 失败（$arguments）：$output" }
    return $output
}

# ---------------------------------------------------------------- 前置检查

foreach ($required in @('CesiumLoader.SDK.dll', 'CesiumLoader.Android.Bootstrap.dll')) {
    if (-not (Test-Path (Join-Path $Payload $required))) { Fail "载荷目录缺少 $required：$Payload" }
}

$devices = & $adbPath devices
if (($devices -join "`n") -notmatch [regex]::Escape($Serial)) { Fail "设备不在线：$Serial" }

Write-Step "加载器根目录 $LoaderRoot"
Invoke-Adb "mkdir -p '$LoaderRoot'" | Out-Null
foreach ($dir in @('mods', 'configs', 'logs', 'data', 'disabled_mods', 'skins')) {
    Invoke-Adb "mkdir -p '$LoaderRoot/$dir'" | Out-Null
}

# ---------------------------------------------------------------- 加载器本体

$rootFiles = Get-ChildItem -LiteralPath $Payload -File |
    Where-Object { $_.Extension -in @('.dll', '.png', '.json') -and $_.Name -ne 'enabled.json' }
if ($rootFiles.Count -eq 0) { Fail "载荷目录里没有可部署的文件：$Payload" }

foreach ($file in $rootFiles) {
    $remote = "$LoaderRoot/$($file.Name)"
    & $adbPath -s $Serial push $file.FullName "/sdcard/Download/$($file.Name)" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "推送失败：$($file.Name)" }
    Invoke-Adb "cat '/sdcard/Download/$($file.Name)' > '$remote' && rm -f '/sdcard/Download/$($file.Name)'" | Out-Null

    $localSha = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $remoteSha = (Invoke-Adb "sha256sum '$remote'").Split(' ')[0]
    if ($remoteSha -ne $localSha) { Fail "$($file.Name) 校验失败：$remoteSha != $localSha" }
    Write-Ok "$($file.Name)  $($file.Length) B  $($localSha.Substring(0, 8))"
}

# ---------------------------------------------------------------- 模组

if (-not $NoMods -and (Test-Path (Join-Path $Payload 'mods'))) {
    # 打包成一个 tar 传：模组是 40+ 个文件，逐个 push 太慢
    $tar = Join-Path ([System.IO.Path]::GetTempPath()) "cesium-mods-$([guid]::NewGuid().ToString('N')).tar"
    $modsDir = Join-Path $Payload 'mods'
    & tar -cf $tar -C $modsDir . 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail '本地打包 mods 失败（需要 Windows 自带的 tar.exe）' }

    Write-Step "推送模组包 $((Get-Item $tar).Length) B"
    & $adbPath -s $Serial push $tar /sdcard/Download/cesium-mods.tar 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail '模组包推送失败' }

    Invoke-Adb "cd '$LoaderRoot/mods' && cat /sdcard/Download/cesium-mods.tar | tar x && rm -f /sdcard/Download/cesium-mods.tar" | Out-Null
    Remove-Item $tar -Force

    $count = (Invoke-Adb "ls '$LoaderRoot/mods'/*.dll | wc -l").ToString().Trim()
    Write-Ok "设备上模组 $count 个"
}

# ---------------------------------------------------------------- 汇总

Write-Host ''
Invoke-Adb "ls -la '$LoaderRoot' | head -14" | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host '下一步：如果还没注入过引导调用，跑 cesium-inject.ps1 -Serial <serial> -Apply' -ForegroundColor Yellow
