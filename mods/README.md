# 内置安卓悬浮窗

使用直装版的 FairyGUI 悬浮入口与 CesiumLoader.Mobile.API 动作接口，保留官方 SDK、引导与既有模组。
这是游戏内 UI，不需要 Android 的「显示在其他应用上层」权限。

在工具箱「加载器 → 安装 / 更新悬浮窗」可单独部署，使用设置中的加载器目录与已选择的 Shizuku / Root 通道。
首次安装加载器也会自动部署。安装后完全重启游戏，点击可拖动头像打开模组列表与操作面板。
更新保持已有的停用状态，不覆盖其他模组或玩家配置。原 SpeedHackMod 替换为只提供悬浮窗入口的
安卓版；首次更新将旧 DLL 备份为 `.pre-panel`（旧直装版平铺 DLL 保留为 `.pre-toys`）。
每次游戏进程启动都重置为 1 倍，不应用旧 `speed` 配置或历史倍率，不监听原变速热键。
只有点击悬浮窗的倍率按钮才会变速；配置热重载不会自动改变倍率。
横屏侧边面板宽度不超过半屏，竖屏面板高度不超过半屏；从头像所在的屏幕边缘滑入。
每次启动游戏后首次打开进入变速页；同一次游戏运行中关闭再打开保留当前页面。
变速面板显示当前选择的倍率，提供 -1、-0.5、+1、+1.5 与「禁用倍速 (1.0x)」。范围为 1.0–10.0x。
功能类固定为：游戏全局变速、隐藏 HUD、自动感谢、商店和筹码看板、PVE 波次预告；其余均在其他类。
具有配置热重载回调的功能直接显示开关；没有可即时执行操作的模组明确提示去 App 配置，不显示假开关。
头像可拖动并吸附左右边缘；面板之外的游戏区域继续接收操作。
所有按钮使用同一个变速入口，旧版加载器兼容路径如下：
优先复用已初始化的直装版或官方变速控制器。官方控制器没有可用的 Android 后端时，
先通过 SDK 停止旧控制器的回调，再使用唯一的 Unity 后端，补偿固定步长与独立动画。
Unity 后备模式使用绝对倍率，不把先前的加速值当作正常基线；点 1 倍恢复 1，暂停保持 0，
失焦时恢复正常，复焦后恢复所选倍率。启动保持 1 倍，不同时启用两套倍率写入器。
第三方模组可继续引用 `CesiumLoader.Mobile.API.dll` 注册自己的按钮和开关。

FreeCameraMod 使用安卓版双指缩放：两指张开拉近、合拢拉远，双指平移和扭转不旋转镜头。
单指操作继续交给游戏；触摸 UI、取消触摸、超过两指或失焦后，先松开全部手指再开始新的缩放。
原滚轮与旋转热键不触发镜头缩放或旋转，保留原缩放边界、平滑、自动跟随与复位功能。
「安装 / 更新悬浮窗」会同时更新 FreeCameraMod，保留玩家配置和停用状态，更新后完全重启游戏。

窗口和按钮使用半透明底板，文字保持不透明，沿用工具箱的明亮 / 暗黑颜色。工具箱在已有提权连接时原子写入
`data/toys-appearance.txt`，两行分别为 `system|light|dark` 与当前解析的 `light|dark`。
游戏内每秒检查一次；`system` 通过游戏 Activity 的 Android Configuration 读取夜间模式，
即使工具箱已关闭也可跟随系统。JNI 读取失败时使用工具箱写入的解析结果。

## 构建

先准备官方加载器 SDK 到 `app/src/main/assets/cesium/sdk/CesiumLoader.SDK.dll`。
游戏引用程序集目录需要 `AstralParty.Runtime.dll`、`UnityEngine.CoreModule.dll` 与
对应版本的官方 SDK。引用程序集与游戏注入产物不放入本源码库。

安装 .NET 8 SDK 与 Python 后执行：

```powershell
python mods/build.py --refs <游戏引用程序集目录>
```

或在仅有 .NET 8 Runtime 的环境传入 Roslyn 与 .NET Standard 2.0 引用包所在工具目录：

```powershell
python mods/build.py --refs <游戏引用程序集目录> --tools <工具目录>
# 只更新本次悬浮窗，保留已打包的 API、相机与变速入口 DLL：
python mods/build.py --refs <游戏引用程序集目录> --tools <工具目录> --overlay-only
# 悬浮窗同时新增 AndroidApi 接口时，重编这两项，保留变速、相机和卡面适配：
python mods/build.py --refs <游戏引用程序集目录> --tools <工具目录> --overlay-and-api-only
```

`--tools` 目录包含 `csc/tasks/netcore/bincore/csc.dll` 与 `netstandard/build/netstandard2.0/ref/*.dll`。
脚本将产物暂存到 `build/builtin-overlay/assets`，同步到 APK assets；Gradle 的
`syncLoaderAssets` 在发行版模组同步后再次合入该目录。因此更换发行版 ZIP 不会丢失悬浮窗。
CI 应在准备 SDK 与游戏引用程序集后、构建 APK 前执行同一脚本。

App 包名维持 `com.hiynet.aspatoys`。正式覆盖更新必须沿用官方签名与递增的 versionCode，
签名配置继续使用原工程的 `ASPATOYS_KEYSTORE` 等环境变量。

## 授权

悬浮窗与 API 的上游 MIT 声明保留在 `licenses/TenkinMori-Android-MOD-MIT.txt`。
直装版衍生源码使用仓库的 AGPL-3.0，许可证一并打入模组目录。
