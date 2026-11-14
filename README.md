# Astral Party Toys（Android）

吉星派对（Astral Party）的安卓版模组管理器：浏览并安装市场里的模组、管理已装模组、把 CesiumLoader 装进游戏。

## 功能

- **模组市场**：默认地址 `https://forge.hiynet.com`，支持搜索、标签、排序；下载边下边校验 SHA-256，检查通过后直接写进加载器。
- **已装模组**：列表、启用/停用、版本与依赖检查；每个模组可以改配置（表单或整段文本），保存后支持热重载的模组立即生效。
- **模组设置**：字段说明取自模组自带的 `_comments`（简中/繁中/英文/日文），改值只改对应字节，注释与键顺序都会保留。
- **加载器安装**：把引导注入游戏的热更缓存、投放 SDK 与内置模组，可一键还原原包。
- 骨架屏加载、竖屏、深浅色主题。

## 使用前提

1. 设备已安装游戏 `com.feimo.astralparty`（3.3.0）。
2. 已安装并运行 **Shizuku**，且给本应用授权。加载器目录在游戏的私有目录里（`/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader`），普通应用即使有「所有文件访问权限」也读不到，只能以 `shell` 身份读写。
3. 首次使用：加载器页 → 授权 Shizuku → 安装加载器。

## 构建

需要 JDK 21 与 Android SDK（compileSdk 37）。

```bash
# 1) 托管引导（把 SDK 与模组装进游戏的桥）
dotnet build loader/managed/CesiumLoader.Android.Bootstrap -c Release

# 2) 载荷：SDK / 内置模组来自 CesiumLoader 的发行版 zip，
#    注入后的游戏程序集是本地产物（与游戏版本绑定）
./gradlew :app:assembleDebug \
  -PcesiumReleaseZip=<cesium-loader-<版本>.zip> \
  -PcesiumSdkDll=<.../CesiumLoader.SDK.dll> \
  -PcesiumBootstrapDll=<.../CesiumLoader.Android.Bootstrap.dll> \
  -PcesiumInjectedDll=<.../AstralParty.Runtime.injected.dll>
```

`syncLoaderAssets` 会把这些产物拷进 `app/src/main/assets/cesium/`，并从发行版清单里取版本号。这些产物都不进版本库。

## 加载器与模组

- 加载器本体、SDK 与内置模组：[CesiumLoader](https://github.com/higashitaniyume/CesiumLoader)
- 桌面版模组管理器：[AstralParty.Toys](https://github.com/higashitaniyume/AstralParty.Toys)

## 许可

AGPL-3.0，见 [LICENSE](LICENSE)。
