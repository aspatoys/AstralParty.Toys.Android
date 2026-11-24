<#
.SYNOPSIS
    把 CesiumLoader 的托管引导注入游戏的热更缓存包（Addressables `__data`），并可选地写回设备。

.DESCRIPTION
    数据版加载器的入口只有一条：热更程序集 `AstralParty.Runtime.dll` 的 `<Module>.cctor`
    里调一次 `CesiumLoader.Mobile.Bootstrap.Boot::Load()`。这一步就是把那条调用写进去。

    流水线（每一步都校验，任何一步失败都停下并保留中间产物）：

        1. 取包    设备上按 bundleKey 找 `__data`，或用 -BundlePath 指定本地文件
        2. 解包    tools/bundle_assets.py extract  ->  19 个 TextAsset（含 AstralParty.Runtime.dll）
        3. 注入    tools/CesiumPatcher inject-bootstrap（写 cctor + 异常护栏）
        4. 回填    tools/bundle_assets.py replace  ->  新的 `__data`
        5. 复核    重新解包新包，比对程序集 SHA-256
        6. 回写    -Apply 时通过 adb 覆盖设备上的 `__data`（先记录原始 SHA-256）

.PARAMETER Serial
    设备序列号。给了就从设备抓包、按需回写；不给就必须给 -BundlePath。

.PARAMETER BundlePath
    本地 `__data` 路径（离线模式）。

.PARAMETER BundleKey
    Addressables 缓存里的 bundle key 目录名，默认是当前游戏的
    `4da1f23c112208b0f8df2a1327b4e4ce`。游戏热更换包后需要更新。

.PARAMETER LoaderRoot
    设备上的加载器根目录，默认
    `/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader`。
    注入进 cctor 的引导 DLL 绝对路径由它拼出，所以必须与实际部署位置一致。

.PARAMETER Apply
    把生成的新包写回设备（会先 force-stop 目标应用）。

.PARAMETER Restore
    把 `-WorkDir` 里保存的原始包写回设备（用于一键回滚）。

.PARAMETER WorkDir
    中间产物目录，默认 `<仓库>\build\inject`。

.EXAMPLE
    .\cesium-inject.ps1 -Serial 127.0.0.1:5555 -Apply

.EXAMPLE
    .\cesium-inject.ps1 -BundlePath D:\dump\__data -LoaderRoot /sdcard/CesiumLoader
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$BundlePath,
    [switch]$Restore,

    [string]$BundleKey = '4da1f23c112208b0f8df2a1327b4e4ce',
    [string]$Package = 'com.feimo.astralparty',
    [string]$LoaderRoot = '/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader',
    [switch]$Apply,
    [string]$WorkDir
)

$ErrorActionPreference = 'Stop'

$mode = if ($Restore) { 'Restore' } elseif ($BundlePath) { 'Local' } elseif ($Serial) { 'Device' } else { '' }
if (-not $mode) { Write-Host '需要 -Serial（设备模式）、-BundlePath（离线模式）或 -Restore（还原）之一' -ForegroundColor Red; exit 2 }
if ($mode -ne 'Local' -and -not $Serial) { Write-Host '这个模式需要 -Serial' -ForegroundColor Red; exit 2 }
if ($mode -eq 'Local' -and -not $BundlePath) { Write-Host '离线模式需要 -BundlePath' -ForegroundColor Red; exit 2 }

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir

if (-not $WorkDir) { $WorkDir = Join-Path $repoRoot 'build\inject' }
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

$bundleAssets = Join-Path $scriptDir 'bundle_assets.py'
$patcher = Join-Path $scriptDir 'CesiumPatcher\bin\Release\net8.0\cesium-patcher.exe'
$bootstrapFileName = 'CesiumLoader.Mobile.Bootstrap.dll'
$bootstrapPath = "$LoaderRoot/bootstrap/$bootstrapFileName"
$assetName = 'AstralParty.Runtime.dll'

function Write-Step([string]$text) { Write-Host "[inject] $text" -ForegroundColor Cyan }
function Write-Ok([string]$text) { Write-Host "[ok]     $text" -ForegroundColor Green }
function Fail([string]$text) { Write-Host "[fail]   $text" -ForegroundColor Red; exit 1 }

function Get-Sha256([string]$path) {
    return (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Resolve-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    # 没在 PATH 上时按常见位置找，省掉让使用者先配环境变量的一步
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    if ($env:LOCALAPPDATA) { $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe') }

    foreach ($path in $candidates) {
        if (Test-Path $path) { return $path }
    }

    Fail '找不到 adb：请把 platform-tools 加进 PATH，或设置 ANDROID_HOME'
}

$adbPath = Resolve-Adb

function Invoke-Adb([string]$serial, [string]$arguments) {
    $output = & $adbPath -s $serial shell $arguments 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "adb 失败（$arguments）：$output" }
    return $output
}

# ---------------------------------------------------------------- 依赖检查

if (-not (Get-Command python -ErrorAction SilentlyContinue)) { Fail '找不到 python（需要 3.10+ 和 UnityPy）' }
python -c "import UnityPy" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'python 里没有 UnityPy，先执行：python -m pip install UnityPy' }

if (-not (Test-Path $patcher)) {
    Write-Step 'CesiumPatcher 未构建，正在构建'
    & dotnet build (Join-Path $scriptDir 'CesiumPatcher\CesiumPatcher.csproj') -c Release --nologo -v q | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $patcher)) { Fail "CesiumPatcher 构建失败：$patcher" }
}

# ---------------------------------------------------------------- 还原模式

if ($Restore) {
    $backup = Join-Path $WorkDir 'device-__data.orig'
    if (-not (Test-Path $backup)) { Fail "没有备份：$backup（先跑一次设备模式）" }
    if (-not $Serial) { Fail '还原需要 -Serial' }

    Write-Step "force-stop $Package"
    Invoke-Adb $Serial "am force-stop $Package" | Out-Null

    $target = Invoke-Adb $Serial "ls -d /sdcard/Android/data/$Package/files/com.unity.addressables/AssetBundles/$BundleKey/*/__data"
    $target = ($target | Select-Object -First 1).ToString().Trim()
    if (-not $target) { Fail "设备上找不到 $BundleKey 的 __data" }

    & $adbPath -s $Serial push $backup /sdcard/Download/__data.restore | Out-Null
    Invoke-Adb $Serial "cat /sdcard/Download/__data.restore > '$target' && rm -f /sdcard/Download/__data.restore" | Out-Null
    Write-Ok "已还原：$target"
    Write-Ok ("设备 SHA-256 = " + (Invoke-Adb $Serial "sha256sum '$target'").Split(' ')[0])
    exit 0
}

# ---------------------------------------------------------------- 1. 取包

$original = Join-Path $WorkDir '__data.orig'
if ($mode -eq 'Device') {
    Write-Step "在 $Serial 上定位 AssetBundles/$BundleKey/*/__data"
    $remote = Invoke-Adb $Serial "ls /sdcard/Android/data/$Package/files/com.unity.addressables/AssetBundles/$BundleKey/*/__data"
    $remote = ($remote | Select-Object -First 1).ToString().Trim()
    if (-not $remote) { Fail "设备上找不到 $BundleKey 的 __data（游戏必须先启动过一次）" }

    Write-Step "拉取 $remote"
    & $adbPath -s $Serial pull $remote $original 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail '拉取失败' }

    Copy-Item $original (Join-Path $WorkDir 'device-__data.orig') -Force
}
else {
    if (-not (Test-Path $BundlePath)) { Fail "找不到 -BundlePath：$BundlePath" }
    Copy-Item $BundlePath $original -Force
}

$magic = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($original), 0, 7)
if ($magic -ne 'UnityFS') { Fail "不是一个 UnityFS 包（前 7 字节：$magic）" }

$origSha = Get-Sha256 $original
$origSize = (Get-Item $original).Length
Write-Ok "原始包 $origSize B  sha256=$origSha"

# ---------------------------------------------------------------- 2. 解包

$extractDir = Join-Path $WorkDir 'extract'
if (Test-Path $extractDir) { Remove-Item $extractDir -Recurse -Force }
Write-Step '解包 TextAsset'
& python $bundleAssets extract $original $extractDir 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail '解包失败' }

$runtimeDll = Join-Path $extractDir $assetName
if (-not (Test-Path $runtimeDll)) { Fail "包里没有 $assetName" }
Write-Ok "$assetName $((Get-Item $runtimeDll).Length) B"

# ---------------------------------------------------------------- 3. 注入

$patchedDll = Join-Path $WorkDir 'AstralParty.Runtime.patched.dll'
Write-Step "注入引导调用 -> $bootstrapPath"
$patchOutput = & $patcher inject-bootstrap $runtimeDll $patchedDll --dll $bootstrapPath --refs $extractDir 2>&1
$patchOutput | ForEach-Object { Write-Host "         $_" }
if ($LASTEXITCODE -ne 0) { Fail '注入失败（看上面的校验行）' }
Write-Ok '注入校验通过（引导调用 + 异常护栏 + 路径字面量）'

# ---------------------------------------------------------------- 4. 回填

$patchedBundle = Join-Path $WorkDir '__data.patched'
Write-Step '回填到包'
& python $bundleAssets replace $original $assetName $patchedDll $patchedBundle 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail '回填失败' }

# ---------------------------------------------------------------- 5. 复核

$verifyDir = Join-Path $WorkDir 'verify'
if (Test-Path $verifyDir) { Remove-Item $verifyDir -Recurse -Force }
& python $bundleAssets extract $patchedBundle $verifyDir 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail '新包解包失败，产物不可用' }

$backSha = Get-Sha256 (Join-Path $verifyDir $assetName)
$wantSha = Get-Sha256 $patchedDll
if ($backSha -ne $wantSha) { Fail "复核失败：回填后的程序集 sha256=$backSha，期望 $wantSha" }

$patchedSha = Get-Sha256 $patchedBundle
$patchedSize = (Get-Item $patchedBundle).Length
Write-Ok "新包 $patchedSize B  sha256=$patchedSha（+$($patchedSize - $origSize) B）"

$state = [ordered]@{
    generatedAt   = (Get-Date).ToString('o')
    bundleKey     = $BundleKey
    package       = $Package
    loaderRoot    = $LoaderRoot
    bootstrapPath = $bootstrapPath
    original      = @{ size = $origSize; sha256 = $origSha }
    patched       = @{ size = $patchedSize; sha256 = $patchedSha }
}
$statePath = Join-Path $WorkDir 'inject-state.json'
$state | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statePath -Encoding utf8
Write-Ok "状态写入 $statePath"

if (-not $Apply) {
    Write-Host ''
    Write-Host "产物：$patchedBundle" -ForegroundColor Yellow
    Write-Host '没加 -Apply，设备没有被改动。' -ForegroundColor Yellow
    exit 0
}

if ($mode -ne 'Device') { Fail '-Apply 需要 -Serial' }

# ---------------------------------------------------------------- 6. 回写

Write-Step "force-stop $Package（Addressables 会持有 __data）"
Invoke-Adb $Serial "am force-stop $Package" | Out-Null

$deviceTarget = Invoke-Adb $Serial "ls /sdcard/Android/data/$Package/files/com.unity.addressables/AssetBundles/$BundleKey/*/__data"
$deviceTarget = ($deviceTarget | Select-Object -First 1).ToString().Trim()
if (-not $deviceTarget) { Fail '回写时找不到设备的 __data' }

Write-Step "推送到 /sdcard/Download 再原地覆盖（叶子目录不允许创建新文件）"
& $adbPath -s $Serial push $patchedBundle /sdcard/Download/__data.patched 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail '推送失败' }

Invoke-Adb $Serial "cat /sdcard/Download/__data.patched > '$deviceTarget' && rm -f /sdcard/Download/__data.patched" | Out-Null

$deviceSha = (Invoke-Adb $Serial "sha256sum '$deviceTarget'").Split(' ')[0]
if ($deviceSha -ne $patchedSha) { Fail "设备上的包 sha256=$deviceSha，期望 $patchedSha" }

Write-Ok "已写回 $deviceTarget"
Write-Ok "设备 sha256=$deviceSha"
Write-Host ''
Write-Host '下一步：确认引导 DLL 已在位，再启动游戏' -ForegroundColor Yellow
Write-Host "    $bootstrapPath" -ForegroundColor Yellow
Write-Host "    $LoaderRoot/CesiumLoader.SDK.dll" -ForegroundColor Yellow
