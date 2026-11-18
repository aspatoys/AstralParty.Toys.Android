<div align="center">

# AstralParty.Toys.Android

## 感谢您选择 AstralParty.Toys.Android！

### 反馈问题与建议：企鹅群 **1078464597** ｜ [Discord](https://discord.gg/SZaYSwGX94)

<img width="128" height="128" alt="UT_AccountHeadShot_70902" src="https://github.com/user-attachments/assets/5033cec1-86a9-41ef-8bda-af56f36a3a0e" />

MOD 管理 · 卡牌方案 · 游戏变速 · 与 Windows 版无缝协作

[![Release](https://img.shields.io/github/v/release/higashitaniyume/AstralParty.Toys.Android?include_prereleases&style=flat-square&label=release)](https://github.com/higashitaniyume/AstralParty.Toys.Android/releases)
[![Release CI](https://img.shields.io/github/actions/workflow/status/higashitaniyume/AstralParty.Toys.Android/release.yml?style=flat-square&label=Release%20CI)](https://github.com/higashitaniyume/AstralParty.Toys.Android/actions/workflows/release.yml)
[![Platform](https://img.shields.io/badge/platform-Android%2010+-3DDC84?style=flat-square)](#安装)
[![License](https://img.shields.io/badge/license-AGPL--3.0-555?style=flat-square)](LICENSE)

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=flat-square)

[下载最新版](https://github.com/higashitaniyume/AstralParty.Toys.Android/releases/latest) · [发布说明](docs/release-notes/) · [Windows 版本](https://github.com/higashitaniyume/AstralParty.Toys) · [CesiumLoader](https://github.com/higashitaniyume/CesiumLoader)

</div>

---

适配 Android 的 MOD 管理工具，与 [Windows 版本](https://github.com/higashitaniyume/AstralParty.Toys) 配套使用。在手机上浏览与安装社区 MOD、编辑配置、管理卡牌方案，并与 Windows 版本无缝同步。

## 安装

从 [Releases](https://github.com/higashitaniyume/AstralParty.Toys.Android/releases/latest) 下载最新 APK。

**系统要求**：
- Android 10 或更新版本
- 设备已获取 ROOT 权限，**或** 已安装 [Shizuku](https://shizuku.rikka.app/) 并授予权限
- 已安装 Astral Party 游戏 v3.3.0+（包名：`com.feimo.astralparty`）

> **首次使用**：安装 APK 后首次启动时需要授予必要权限，建议允许通知、文件访问等权限以获得最佳体验。

## 功能

| 模块 | 能力 |
| --- | --- |
| MOD 商城 | 浏览社区 MOD 目录：搜索、标签筛选、排序与分页；点开 MOD 查看详情、版本历史与 README；下载后校验 SHA-256 完整性；新版本发布时收通知 |
| 已装 MOD | 查看游戏中已加载的 MOD 列表、启用/禁用、编辑配置、删除或回滚；版本依赖检查 |
| MOD 配置 | 编辑每个 MOD 的 `config.json` 内容；多语言字段描述（简体中文、繁體中文、English、日本語）；支持导入/导出配置文件 |
| 卡牌方案 | 导入从 Windows 版本导出的卡牌方案文件；编辑与管理多套自定义卡面方案；一键应用到游戏 |
| 加载器管理 | 一键安装或更新 CesiumLoader；查看加载器版本与运行状态；变速率调整与热键设置 |
| 出厂重置 | 清除所有 MOD 配置与加载器设置，恢复游戏原始状态（谨慎使用） |

内置 CesiumLoader 模组支持 23 个功能：自由相机、回合倒计时、战斗胜率助手、音频替换、自动感谢、快捷短语、帧率解锁、多语言支持等。详见 [加载器文档](https://github.com/higashitaniyume/CesiumLoader#内置模组)。

变速范围为 **1–100 倍**，建议不超过 3 倍；倍率会影响动画与网络超时。

## 与 Windows 版本的协作

本项目是 [AstralParty.Toys](https://github.com/higashitaniyume/AstralParty.Toys) 的 Android 官方端口，两个版本可无缝协作：

| 操作 | Windows | Android |
| --- | --- | --- |
| 浏览 MOD 市场 | ✅ | ✅ |
| 安装与管理 MOD | ✅ | ✅ |
| 编辑 MOD 配置 | ✅ | ✅ |
| 设计卡牌方案 | ✅ | ✅ |
| 导出方案文件 | ✅ | ✅ |
| 导入方案文件 | ✅ | ✅ |

**推荐工作流**：
1. 在 Windows 版本上设计卡牌方案，调整满意后导出
2. 将方案文件通过网盘、Airdrop、蓝牙或数据线传到手机
3. 在 Android 版本上导入并应用到游戏

市场默认连接 `https://forge.hiynet.com`，可在设置中更改为其它部署地址。

## 数据与配置

| 内容 | 存储位置 |
| --- | --- |
| 应用数据与缓存 | `/data/data/com.hiynet.astrapartytoysandroid/` |
| 下载的 MOD 与方案文件 | 应用私有存储或外部存储（取决于权限） |
| 加载器与 MOD 日志 | 游戏数据目录下的 `AstralParty_ModLoader/logs/` |

应用数据包括 MOD 配置、方案文件、下载缓存与操作日志。卸载时可选择保留应用数据；清除应用数据会删除所有配置与缓存。

## 开发

技术栈：**Kotlin · Jetpack Compose · OkHttp · Protobuf**。

在本仓库根目录执行，需要 JDK 21+ 与 Android SDK（compileSdk 37+）：

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 构建 Release APK
./gradlew assembleRelease

# 运行单元测试
./gradlew test

# 运行集成测试（需要连接设备或模拟器）
./gradlew connectedAndroidTest
```

**目录结构**：
- `app/` - 主应用模块（UI 和业务逻辑）
- `cesiumcore/` - CesiumLoader 核心库
- `loader/` - 加载器实现与模组框架
- `mods/` - 内置模组源代码
- `installer/` - APK 构建工具
- `tools/` - 开发工具与脚本

更多文档见 [docs/](docs/) 目录，包括 [卡牌定制](docs/card-customization.md)、[快捷键映射](docs/win-key-mapping.md)、[发布说明](docs/release-notes/) 等。

## 故障排除

**问题**：应用无法访问 MOD 文件系统
- **解决**：确保设备已 ROOT 或安装 Shizuku，并授予应用权限

**问题**：导入卡牌方案后不生效
- **解决**：需要重启游戏；确保 CesiumLoader 已安装且版本正确

**问题**：MOD 下载失败或显示 SHA-256 校验错误
- **解决**：检查网络连接；尝试切换市场地址（设置 → 市场）；清除应用缓存后重试

运行日志保存在应用数据目录，遇到问题时可导出日志反馈。

## 交流与许可

企鹅：**1078464597** ／ Discord：<https://discord.gg/SZaYSwGX94>。反馈问题时可在应用「关于」界面复制环境信息。

原创代码以 [GNU AGPL-3.0](LICENSE) 发布。Copyright © 2026 Yume Higashitani。

内嵌的 Astral Party 协议程序集与游戏素材归原权利人所有，不属于本项目的授权范围。CesiumLoader 各组件遵循其自身许可，详见 [加载器许可说明](https://github.com/higashitaniyume/CesiumLoader#许可)。
