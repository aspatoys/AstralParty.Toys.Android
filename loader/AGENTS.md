# AGENTS：loader（加载器侧，位于 android_aspatoys/ 内）

安卓版 CesiumLoader。与 `android_aspatoys/`（管理器）、`modding/msvc/`（桌面加载器 + SDK + 模组源码）配套。

## 身份与包名

- 安装器/加载器 App 包名：**`com.hiynet.cesiumloader`**
- 共享库 namespace：`com.hiynet.cesiumloader.core`
- 管理器 App 包名：`com.hiynet.aspatoys`（在 `android_aspatoys/`）
- 目标游戏包名：`com.feimo.astralparty`（官方）、`com.feimo.astralparty.cesium`（共存版）、`com.feimo.jxpd`（渠道服）

## 硬约束

- **HybridCLR/安卓上不可用的 BCL**：不要用 `FileStream`、`FileInfo`/`DirectoryInfo`、`StreamReader`/`Writer`、`Flush(bool)`、`Assembly.Location`、`Assembly.GetType(string,bool)`、`Assembly.ManifestModule`。托管侧只用 `File.ReadAllBytes/WriteAllText/AppendAllText/Exists/Delete/Move`、`Directory.*`、`Path.*`。
- **入口唯一**：`CesiumLoader.Android.Bootstrap.Boot.Load()` 是唯一入口，必须幂等，异常只落盘不外抛（异常逃进游戏链会卡死整局）。数据版注入的 IL 与重打包版的 `libcesium.so` 都只调它。
- **盘上格式只有一份定义**：`core` 里的 `CesiumLayout` / `EnabledIndex` 是唯一事实来源；C# 侧与 patcher 不得另写一套路径或字段名。
- **`enabled.json` 只是提示**：判断启用状态读目录（`mods/` vs `disabled_mods/`），`enabled.json` 仅用于与其他实现互操作（格式见 `EnabledIndex`）。
- **配置热重载由引导驱动**：SDK 只提供 `ModConfig.PollAllChanged()`，没人调就不会重载。`Boot` 起后台线程每 1.5 秒调一次（失败吞掉、下轮重试），App 写完 `configs/<ModId>.json` 即生效。改 `Boot.cs` 后必须 `dotnet build managed/CesiumLoader.Android.Bootstrap -c Release` 并让 `syncLoaderAssets` 重新拷 DLL。
- **写设备文件走子进程标准输入**：宿主侧不要「落中转文件 + `cat local > remote`」——App 私有目录 `0600`/`0700`，`shell` 读不到；目标先写 `<path>.cesium-tmp` 再 `mv -f`，避免失败留下 0 字节假文件。
- **玩家配置在 `configs/<ModId>.json`**：旧版 `mods/<ModId>/config.json` 由引导迁移一次，迁移只做单向、外置已存在不覆盖、内层改名 `.bak`。
- **托管产物不入库**：`CesiumLoader.SDK.dll`、`mods/*.dll` 由 `modding/msvc/` 构建产出，`payload/` 进 `.gitignore`；不要在仓库里手放 DLL。
- **写文件必须原子**：加载器目录会被游戏进程同时读取，一律走 `AtomicFiles`（同目录 `.tmp` + fsync + rename）。

## 模块边界

| 目录 | 只做 | 不做 |
|---|---|---|
| `core/` | 路径/布局/版本/哈希/原子写 | 不碰 Shizuku、不碰 Android UI |
| `managed/...Bootstrap/` | 建目录、设 `CESIUM_*`、加载 SDK 与模组、写日志 | 不做平台门槛判断（平台门槛在安装期由 App 完成） |
| `flavor-data/`（待建） | Shizuku 通道、缓存补丁、备份/还原事务 | 不知道市场与模组元数据 |
| `flavor-apk/`（待建） | 重打包、`libcesium.so`、签名 | 不感知数据版 |

## 构建

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.13.21-hotspot'
..\gradlew.bat --console=plain :cesiumcore:testDebugUnitTest
dotnet build managed/CesiumLoader.Android.Bootstrap -c Release
# 同步到 APK assets（SDK / 引导 / 发行版内置模组 / 注入产物）
..\gradlew.bat --console=plain :app:syncLoaderAssets -PcesiumReleaseZip=<发行版 zip>
```

Gradle wrapper 与 `gradle-daemon-jvm.properties` 与 `android_aspatoys/` 保持一致（Gradle 9.6 / AGP 9.5.1 / Kotlin 由 AGP 内置提供）。

## 文档口径

面向 GitHub 的文档只写功能、用法、配置与兼容性；不写开发过程、排查经历、测试状态与通过数量。
