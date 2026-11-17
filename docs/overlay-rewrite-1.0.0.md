# 悬浮窗重做 1.0.0：改动总览

本文档记录 `CesiumOverlayMod` 从上游 `v1.3.0` 基线重做到 1.0.0 的**全部改动**、设计取舍、踩过的坑和验证状态。
配套阅读：

- [`overlay-customization.md`](overlay-customization.md) —— 外观、六页内容、`toys-overlay.txt` 协议（面向使用者/调参）
- [`overlay-shortcuts.md`](overlay-shortcuts.md) —— `AndroidApi.ShortcutItem` 开发者接口（面向模组作者）
- [`win-key-mapping.md`](win-key-mapping.md) —— Win 模组按键映射的兼容边界

---

## 1. 基线与产物

| 项 | 值 |
| --- | --- |
| 基线提交 | `a58db09`（= tag `v1.3.0`，`git merge --ff-only v1.3.0` 得来） |
| 模组版本 | `ModManifest("Cesium Android FloatingWindow", "1.0.0", "TenkinMori/囚鱼玖", "为CesiumLoader安卓端定制的悬浮窗口")` |
| `CesiumOverlayMod.dll` | 106,496 B，MD5 `67166E146F583F25A89137C5CBA3C8DE` |
| `CesiumAndroidApi.dll` | 11,264 B，MD5 `DF1772CC62E91C00025D56E40777C8AB`（上游 10,240 B，因新增接口变大） |
| `app-debug.apk` | 44,862,385 B |
| 单元测试 | `:app:testDebugUnitTest` 255 项全部通过（25 个结果文件，0 失败） |

> **所有改动尚未提交 git**，`git status` 即可看到完整差异（24 个修改 + 5 个新增 + 1 个删除）。

---

## 2. 文件级变更总表

### 2.1 新增

| 文件 | 行数 | 作用 |
| --- | --- | --- |
| `mods/CesiumOverlayMod/OverlayRaster.cs` | 156 | CPU 侧 SDF 抗锯齿圆角矩形栅格化器，输出 RGBA32 字节数组 + `NTexture` |
| `mods/CesiumOverlayMod/OverlaySkin.cs` | 118 | 把一整块圆角面烘焙成单张纹理，两级烘焙（动画 / 静置） |
| `mods/CesiumOverlayMod/icons/*.png` | 10 | Material Symbols 图标：`apps` `speed` `tune` `settings` `terminal` `info` `home` `article` `chevron_right` `close` |
| `app/.../ui/theme/OverlayPalette.kt` | — | 导出浅/深两套 Material 3 色板为 `light.role=#AARRGGBB` 文本 |
| `docs/overlay-shortcuts.md` | 193 | 快捷项开发接口技术文档 |

### 2.2 删除

| 文件 | 原因 |
| --- | --- |
| `mods/CesiumOverlayMod/OpacityControl.cs` | 上游 1.3.0 已删除拖拽式不透明度控制，改为设置页四档；本仓同步删除 |

### 2.3 修改（按改动量）

| 文件 | +/- 行 | 摘要 |
| --- | --- | --- |
| `mods/CesiumOverlayMod/OverlayWindow.cs` | 896 | 六个页面主体全部重写；内容缓存键；选项轮询与原子保存 |
| `mods/CesiumOverlayMod/ModEntry.cs` | 887 | 外壳：贴边 blob、六页导航、展开/收起动画、闲置淡出、拖拽吸附 |
| `mods/CesiumOverlayMod/BuiltinActions.cs` | 238 | 内置快捷项注册、反射读运行时字段、latch / press 语义 |
| `mods/CesiumOverlayMod/OverlayTheme.cs` | 206 | 双方案 token 解析、M3 角色色、`Poll()` 热重载 |
| `mods/CesiumOverlayMod/OverlayPresentation.cs` | 151 | 页面元数据表 `OverlayPage[]`、`ModCatalog` |
| `mods/CesiumOverlayMod/OverlayMotion.cs` | 134 | 手写补间驱动（替代 GTween）+ 缓动函数表 |
| `mods/CesiumOverlayMod/KeyMappingPanel.cs` | 110 | 默认键表；删除手动映射编辑器 |
| `mods/CesiumAndroidApi/AndroidApi.cs` | 91 | 新增 `ShortcutItem` + `RegisterShortcut` / `RegisterShortcutButton` |
| `mods/CesiumOverlayMod/VirtualKeyboard.cs` | 37 | `TapFrames()` 0.55 秒脉冲；输入阻塞判定改写 |
| `mods/CesiumOverlayMod/OverlayOptions.cs` | 22 | 新增 `font` / `idle` / `idleOpacity` / `previousSpeed` / `center` |
| `mods/CesiumOverlayMod/VirtualKeyState.cs` | 15 | 新增「按帧数按住」的 `Press(..., int frames)` 重载 |
| `mods/build.py` | 13 | 拷贝 `icons/`；清单改名 1.0.0 + `platforms: ["android"]` |
| `app/build.gradle.kts` | 18 | 产物守卫（清单版本 + DLL 内容特征） |
| `app/.../data/settings/OverlayOptions.kt` | 38 | 与 C# 侧同协议的新字段与归一化 |
| `app/.../data/loader/BuiltinOverlay.kt` | 27 | `install/syncAppearance` 增加 `tokens: () -> String` |
| `app/.../ui/settings/OverlaySettings.kt` | 11 | 新增字体、闲置淡出、闲置浓度滑杆 |
| `app/.../ui/theme/OverlayAppearanceSync.kt` | 3 | 同步时带上色板 |
| `app/.../installer/LoaderInstaller.kt` | 5 | 安装时带上色板 |
| `app/.../ui/theme/Theme.kt` | 4 | `LightColorScheme` / `DarkColorScheme` 由 `private` 改 `internal` |
| `app/src/test/...OverlayOptionsTest.kt` | 50 | 新增 5 个协议往返/钳制测试 |
| `app/src/test/...BuiltinOverlayTest.kt` | 18 | 新增 2 个 appearance 文件测试 |
| `docs/overlay-customization.md` | 95 | 按 1.0.0 实际形态重写 |
| `docs/win-key-mapping.md` | 2 | 删除「用户自定义按键」两条 |

### 2.4 未改动（沿用上游 1.3.0）

`LocalSpeed.cs`、`SpeedPanel.cs`、`SpeedResumeState`（经由上游单通道）、`AbsoluteSpeedScale.cs`、`UnitySpeedScale.cs`、`FairyUiTimeScaleBridge.cs`、`IndependentAnimationBridge.cs`、`FixedStepBudget.cs`、`HudVisibility.cs`、`PngDecoder.cs`、`CesiumOverlayMod.csproj`、`overlay.png`。
**没有修改 SDK、没有修改任何第三方 Win 模组 DLL。**

---

## 3. 架构

```
OverlayController : ModBase            (ModEntry.cs，partial class 拆三个文件)
├── 外壳 shell（ModEntry.cs）
│   ├── root（GComponent，sortingOrder 32740）
│   ├── backdrop（全屏 opaque，点击收起，A = 不透传）
│   ├── panel + panelSkin + panelHead / panelIcon / panelTitle / notice
│   ├── blob + blobSkin + logo + navRoot(6 个 nav item)
│   └── OverlayMotion（手写补间）+ OverlaySkin（两级烘焙）
├── 页面体（OverlayWindow.cs）
│   ├── body = OverlayScrollView（每页重建，内容缓存键脏检查）
│   ├── RenderApps / RenderSpeed / RenderConfig / RenderSettings / RenderConsole / RenderInfo
│   └── PollOptions / Save（.game-tmp + File.Replace）
├── 按键（KeyMappingPanel.cs + VirtualKeyboard.cs + VirtualKeyState.cs）
└── 内置动作（BuiltinActions.cs）→ AndroidApi 注册表
```

`OverlayController` 是 **partial class 拆在 `ModEntry.cs` / `OverlayWindow.cs` / `KeyMappingPanel.cs` 三个文件**里，改的时候注意字段是共享的。

驱动方式是 `OnUpdate()` 里推进一切，没有协程：

```csharp
VirtualKeyboard.Instance?.Tick();
TickNotice(now);
if (now >= nextBuild) { ...1 秒一次：PollOptions / OverlayTheme.Poll / BuiltinActions.Refresh / 重建检查... }
motion.Update(now);
TickLongPress();
ApplyLayout();                 // 每帧把状态写进 FairyGUI 对象
if (renderPending && !FingerDown) Render();
if (rebuildPending && !FingerDown && mode == ShellMode.Idle) Build();
if (mode == ShellMode.Expanded && now >= nextRefresh) { nextRefresh = now + .25f; Render(); }
```

关键设计：**`Build()` 只搭骨架，`ApplyLayout()` 每帧写数值**。动画只改 `vX/vCY/vW/vH/rTL..rBR/logoSize/...` 这些普通字段，`ApplyLayout()` 把字段同步到 `GObject`。这样动画期间不需要重建节点树，也不会踩 FairyGUI 的脏标记。

---

## 4. 形态与交互

### 4.1 状态机

```csharp
private enum ShellMode { Idle, Drag, Expanded, Busy }
```

| 转换 | 触发 | 备注 |
| --- | --- | --- |
| Idle → Drag | 位移超过 `Px(6)` | 抬起时 `docked=false`，四角恢复全圆角 |
| Drag → Busy → Idle | 松手 | 按 `vX + vW/2 < width/2` 吸附左/右；`QuartOut 240ms` 后 `docked=true` 并写 `center=` |
| Idle → Busy → Expanded | 点击（未拖动） | 面板滑入完成后才置 `Expanded` |
| Expanded/Busy → Busy → Idle | 点 backdrop 或收起 | **不重置 `page`**（见 §4.4） |

### 4.2 圆角规则

```csharp
bool left = edge == "left";
rTL = rBL = docked && left  ? 0 : target;
rTR = rBR = docked && !left ? 0 : target;
```

即贴边侧上下两角为直角，另一侧保留圆角；拖动到半空（`docked=false`）四角全部恢复。过渡 180ms `SineInOut`。

### 4.3 闲置淡出

```csharp
float idleWait  = Options.IdleSeconds;                        // 0 关闭
float idleAlpha = Mathf.Clamp(Options.IdleOpacity, .15f, 1f);  // 默认 .35
bool asleep = mode == ShellMode.Idle && idleWait > 0f
              && Time.realtimeSinceStartup - lastTouched >= idleWait;
blobAlpha = Mathf.MoveTowards(blobAlpha, asleep ? idleAlpha : 1f, Time.unscaledDeltaTime * 2.6f);
blob.alpha = Mathf.Max(.02f, blobAlpha);   // ← 不能到 0
```

> **必须保留 `.02f` 下限**：FairyGUI 里完全透明的 `GObject` 不参与命中测试，`alpha = 0` 之后用户就点不醒它了。
> `lastTouched` 在 `blob.onTouchBegin`、`Expand()`、`Collapse()` 的 onComplete 三处刷新。

### 4.4 页面记忆

`Collapse()` 的 onComplete 只做 `mode = ShellMode.Idle; PaintChrome();`，**不再 `page = 0`**。
历史上这里写了 `page = 0`，导致每次重开都回到「功能」页。

---

## 5. 几何与动画参数

```
uiScale = clamp(min(width, height) / 600, .4, 1.5)
Px(design) = design * uiScale
Font(design) = round(design * uiScale * clamp(Options.FontScale, .8, 1.4))

S     = clamp(Px(56), Px(88), min(width, height) * .09)   // Idle 方块边长
R     = S * .30                                          // Idle 圆角
Wbar  = max(S, Px(96))
Rbar  = Px(28)                                           // 长条圆角
Hbar  = PAD + LOGO_B + GAP1 + 6*NAV + 5*NAV_GAP + PAD    // 平板再 +Px(72)
PAD=Px(16) LOGO_B=Px(56) GAP1=Px(18) NAV=Px(56) NAV_GAP=Px(8)
MARGIN=Px(12) PGAP=Px(10) Wpanel=min(Px(400), width - Wbar - Px(36))
Compact = min(width, height) <= 1200                     // 手机：居中固定；平板：保留拖拽位置
```

展开/收起时序（全部 `Time.unscaledDeltaTime` 驱动）：

| 属性 | 展开 | 收起 |
| --- | --- | --- |
| 宽 `vW` | 340ms `QuartOut` | 260ms `CubicIn` |
| 高 `vH` | 400ms `QuartOut` | 260ms `CubicIn` |
| 垂直中心 `vCY` | 400ms `QuartOut` | 260ms `CubicIn` |
| logo（尺寸/XY） | 400ms `QuartOut` | 260ms `CubicIn` |
| navAlpha | 240ms `CubicOut`，延迟 160ms | 100ms `Linear` |
| panelAlpha | 300ms `QuartOut`，延迟 220ms | 120ms `CubicIn` |
| backdropAlpha | 300ms `CubicOut` | 240ms `CubicIn` |
| 圆角 | 180ms `SineInOut` | 同 |
| 拖动抬起 scale 1.04→1 | 160ms `CubicOut` | — |

**为什么不用 GTween**（`OverlayMotion.cs` 文件头已注明）：①`Build()` 会整体 `root.Dispose()` 重建，GTween 会留悬空引用；②GTween 走 `Time.timeScale`，而本模组的功能之一就是改 `timeScale`。

缓动表：`Linear` / `SineInOut` / `CubicOut` / `CubicIn` / `QuartOut`，`OverlayEasing.Apply(ease, t)`。

---

## 6. 圆角渲染：为什么自己写 SDF

这块是踩坑最多的地方，六代方案全部失败过（`OverlaySkin.cs` 文件头有完整记录）：

| 代 | 方案 | 失败表现 |
| --- | --- | --- |
| 1 | `GGraph.DrawRoundRect` | 四角数组顺序是 **[TL, TR, BL, BR]**（不是 TL,TR,BR,BL），贴边圆角错位；且 GPU 三角网格无边缘过滤，1.5× 缩放下有明显锯齿 |
| 2 | 用 GGraph 半透明叠「阴影」 | `GGraph` 经 `Shape` 栅格化时**丢弃 fill 的 alpha**，半透明块变实心白板 |
| 3 | 九宫格角贴片 + 直角矩形 | 块边界是圆弧接缝，移动时有发丝缝隙 |
| 4 | 每帧全尺寸烘焙 | 展开动画卡顿（每帧一次 `Texture2D` 上传） |
| 5 | 烘焙一次后逐轴拉伸 | 宽高动画速率不同 → 圆弧变椭圆 |
| 6 | 统一缩放到固定 cap | 静置状态糊（36px 圆角烘成 8px 再拉伸） |

**最终方案**：`OverlayRaster.RoundedRect(w, h, tl, tr, bl, br, fill, outline, outlineWidth)` 在 CPU 侧用**有符号距离场**算覆盖率，一像素解析抗锯齿；`OverlaySkin` 把它包成单张纹理挂到 `GLoader`（`FillType.ScaleFree`），**等比缩放下采样**、**无描边**（用户明确要求去掉边框，`outlineWidth = 0`）。

两级烘焙：

```csharp
private const int AnimatedCap = 192;   // 动画中：最贵的一次上传要省
private const int RestingCap  = 1024;  // 静置：1 纹理像素 = 1 屏幕像素
```

脏检查键（`OverlaySkin.Paint`）：

```csharp
string wanted = "{tl}|{tr}|{bl}|{br}|{rgba}|{settled?1:0}|{settled ? WxH : "free"}";
// 四个圆角按「占最长边的比例 × 64」取整 —— 动画中尺寸每帧都变，
// 用像素做键会每帧重烘焙；用比例做键则只在圆角形状真的变了才重烘焙。
```

静置判定在 `ModEntry.Settled`，带 **0.12 秒延迟**：

```csharp
private bool Settled
{
    get
    {
        if (dragging || mode == ShellMode.Busy) { settledAt = 0f; return false; }
        if (!motion.Idle) { settledAt = 0f; return false; }
        if (settledAt <= 0f) settledAt = Time.realtimeSinceStartup;
        return Time.realtimeSinceStartup - settledAt >= .12f;
    }
}
```

> 为什么要延迟：全分辨率烘焙很贵，正好落在动画结束那一帧会卡一下。延后 0.12s 让那次重烘焙落在空闲帧。

---

## 7. 主题（Material You 双方案）

`data/toys-appearance.txt` 格式：

```
第 0 行  system | light | dark      ← 用户的主题偏好
第 1 行  dark | light               ← APK 侧已解析出的方案
第 2+ 行 light.surface=#FFE6E0E9    ← 浅色方案的 token
        dark.surface=#FF141218      ← 深色方案的 token
        primary=#FFD0BCFF           ← 无前缀：覆盖两套（调试用，优先级最高）
```

**为什么两套都写**：玩家可能在游戏运行时从快捷设置切深浅色，那一刻 APK 没在跑，没法重写文件。游戏内 `OverlayTheme.Poll()` 自己解析方案，挑对应那一块。

- 只有前两行是必需的，老版本加载器照旧工作。
- `OverlayTheme` 对每个角色色都给了一份手调的 M3 基线（如 `Surface => Token("surface", 0x141218, .92f, 0xFFFBFE, .94f)`），token 缺失时用基线。
- **alpha 由悬浮窗自己给**，不从 Compose 取：Compose 的 scheme 永远是不透明的，而这里的面板是半透明的。
- `OverlayTheme.Poll()` 每帧比较文件 mtime/内容，变了就整块替换并触发重绘。

15 个角色色（Kotlin 侧 `OverlayPalette.tokens` 导出的正是这 15 个）：`surface` `surfaceContainer` `surfaceContainerHigh` `primary` `onPrimary` `primaryContainer` `onPrimaryContainer` `secondaryContainer` `tertiary` `onTertiary` `onSurface` `onSurfaceVariant` `outline` `outlineVariant` `scrim`（+ `SpeedButton`/`SpeedPrimary`/`SpeedPrimaryText`/`SpeedValue` 是 `tertiary` 的别名）。

---

## 8. 设置协议 `data/toys-overlay.txt`

`version=1`，UTF-8 无 BOM，**C# 与 Kotlin 两端同协议**（`OverlayOptions.cs` / `OverlayOptions.kt`）。

| 字段 | 范围 | 默认 | 说明 |
| --- | --- | --- | --- |
| `opacity` | 0.25 – 1.00 | **0.50** | 面板不透明度 |
| `width` | 0.65 – 1.60 | 1.00 | 统一大小（APK 侧滑杆） |
| `height` | 0.65 – 1.60 | 1.00 | 旧协议兼容字段 |
| `font` | 0.80 – 1.40 | 1.00 | 面板文字缩放 |
| `idle` | 0 – 60 | 5.00 | 闲置多少秒后淡出；0 = 不淡出 |
| `idleOpacity` | 0.15 – 1.00 | 0.35 | 淡出后的浓度 |
| `previousSpeed` | 1.0 – 10.0 | 1.0 | 供「恢复倍速」使用 |
| `center` | 0 – 1 | 不写 | 展开时的垂直位置 |
| `group` | — | — | `base64(组名)|id1,id2,...` |

坑（都写过注释）：

1. **`center` 不写 = 从未移动过，读到的是 `-1`**。把它钳到 0 会让面板钉在屏幕顶端，所以负值是有效语义。
2. `opacity` 默认值曾经在 C# 侧残留 `.94f`，上游 1.3.0 已统一为 `.5f`，两边必须一致。
3. 写入必须**先重读再改单字段**，否则会把 APK 并发写进去的分组覆盖掉；落盘走 `<path>.game-tmp` + `File.Replace` 原子替换。
4. 分组最多 8 个、每组最多 128 个 id，id 只允许 `[A-Za-z0-9._-]`，组名最长 30 字符，`SpeedHackMod` 不参与分组。

---

## 9. 六页内容

| 页 | Key / 图标 | 内容 |
| --- | --- | --- |
| 功能 | `apps` / `apps` | **两段**：「快捷开关」（`AndroidApi.Shortcuts`）+「按键映射」（`MappedMods()`，跳过已在第一段出现的 ModId） |
| 变速 | `speed` / `speed` | 大号读数 + ±0.5x + 预设 1/1.5/2/3/5x + 「恢复倍速 N.Nx」 |
| 模组配置 | `config` / `tune` | 全部模组的**纯导航**列表（一行 → 该模组详情，详情里是 action 项 + 按键映射 + 状态） |
| 设置 | `settings` / `settings` | 不透明度 4 档、字体 4 档、闲置淡出 4 档、闲置浓度 4 档、分组一览、当前深浅色 |
| 控制台 | `console` / `terminal` | 最近 40 行日志 + 「导出日志」→ `data/toys-log-<时间戳>.log` |
| 关于 | `info` / `info` | 三行：`Cesium Android FloatingWindow` / `悬浮窗 1.0.0` / `已加载模组：N 个` |

### 9.1 「功能」页的分区与去重

```csharp
var shortcuts = AndroidApi.Shortcuts;
var published = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
if (shortcuts.Count > 0)
{
    Text(body, "快捷开关", ...);
    for (int i = 0; i < shortcuts.Count; i++) { published.Add(shortcuts[i].ModId); y = RenderShortcutRow(shortcuts[i], y); }
}
foreach (var entry in MappedMods())
{
    if (published.Contains(entry.Key)) continue;   // ← 同一个模组不占两行
    ...
}
```

### 9.2 内容缓存键

`BuildContentKey()` 折进去的东西（任一变化触发重建 body）：

- `page` / `openMod` / 当前倍速 / 深浅色
- 分组数量与名字
- `opacity` `width` `height` `font` `previousSpeed` `idle` `idleOpacity`
- `MappingSignature(openMod)`（键位 + 是否被按住）
- `page == AppsPage` 时：每条 `MappedMods()` 的 id+chords；每条 shortcut 的 `ModId/Id/Label/Get()/Detail`
- 每个已加载模组的 `StateLabel` 与中文名；每个 action 的 `Id/Label/值`

`Render()` 先算 key，与上次相同就只做一次字符串比较；**但有手指按下时不会重建**（重建会销毁手指正按着的按钮，点击就丢了），改为置 `renderPending`，等 `FingerDown` 变 false 再渲染。长按进详情走 `forceRender = true` 例外。

---

## 10. 按键链路

### 10.1 装饰而不是替换

`VirtualKeyboard` 实现 `IInputBackend`，通过公开的 `InputService.SetBackend(...)` **包住**官方后端，卸载时条件恢复。不碰 SDK 二进制，不改任何 Win 模组 DLL。

### 10.2 `TapFrames()`：点按不是 1 帧

```csharp
private static int TapFrames()
{
    float step = Mathf.Max(Time.unscaledDeltaTime, .001f);
    return Mathf.Clamp(Mathf.RoundToInt(.55f / step), 6, 180);
}
```

**这是「按了没反应」的头号根因**。`HideHudMod` 出厂 `pressMode: "Hold"` + `holdSeconds: 0.35`，`CharacterTransparencyMod` 是 `holdMode: true` —— 它们在 `Update()` 里**轮询 `GetKey` 并持续一段时间**才动作。老的 1 帧脉冲（`End = frame + 2`）对它们完全不可见。

对应 `VirtualKeyState` 新增重载：

```csharp
internal void Press(string source, int[] keys, int frame, int frames)
{
    sources[source] = new Source { Keys = (int[])keys.Clone(), Start = frame + 1,
                                   End = frame + Math.Max(2, frames) };
}
```

### 10.3 输入是否可用

```csharp
private static bool AcceptsKeys
{
    get { return Application.isFocused && !(GRoot.inst != null && GRoot.inst.focus is GTextInput); }
}
```

> **不要**把 `UiService.ShouldBlockGameInput` 也算进来。它在悬浮窗显示时恒为 `true`，恰恰是玩家按映射按钮的时刻，加进去等于让所有映射静默失效（历史上就是这么坏的）。

### 10.4 三个语义

| 辅助函数 | 行为 | 用在哪 |
| --- | --- | --- |
| `Latch(source, chord)` | `Send(source, chord, hold: true)` —— 第一次按下保持，第二次松开 | hold 型模组（HideHud、角色半透明） |
| `Press(mod, source, chord, label)` | 先确保 mod 的 `enabled`/`Enabled` 为 true（`Set` + `SaveIfDirty`），再 `Send(..., hold: false)`，最后 `ShowNotice(label + "：已发送按键 " + chord)` | 一次性按键 |
| `SendKey(id, source, chord, hold)` | 校验 mod 已运行 → 校验 `VirtualKeyboard.Instance` → `Enable(mod)` → `Send` | 详情页的按键映射按钮 |

`Enable(mod)` 的存在理由：`HideHudMod` / `CharacterTransparencyMod` 出厂 `"enabled": false`，总开关关着时它们直接忽略热键，表现成死按钮。玩家按下映射就是在要求它动作，所以先自动开启（`Enabled` 大写 E 的模组也要兼容，如 `PvePanelMod`）。

---

## 11. 内置快捷项（`BuiltinActions.RegisterKnownShortcuts`）

| 模组 | Id | 类型 | 状态来源 | 点击动作 |
| --- | --- | --- | --- | --- |
| `HideHudMod` | `hud.hide` | 开关（latch） | `VirtualKeyboard.Instance.Holding("HideHudMod:hud.hide")` | `Latch(..., toggleKey 默认 BackQuote)` |
| `CharacterTransparencyMod` | `body.fade` | 开关（latch） | 同上 | `Latch(..., toggleKey 默认 H)` |
| `PveWaveForecastMod` | `wave.forecast` | 开关 | 反射 `_visible` | `Press(..., toggleKey 默认 O)` |
| `TeammateCheckMod` | `team.panel` | 开关 | 反射 `_panel.Wanted` | `Press(..., toggleKey 默认 F9)` |
| `TownTrainer` | `town.panel` | 开关 | 反射 `_panel._visible` | `ToggleTownPanel`（反射翻 `_panel._visible`） |

### 11.1 为什么要反射读运行时字段

后三个模组都是**只翻内存里的状态、从不回写 config**：`TeammateCheckMod.TickHotkey` 只调 `_panel.SetWanted(!Wanted)`，`PveWaveForecastController.HandleHotkey` 只翻 `_visible`，`TownTrainerPanel.Tick` 只翻 `_visible`。之前行上的开关读的是 `showPanel` / `enabled` 这种**永远不更新**的配置字段，于是看起来像「点不动」。

反射工具（`BindingFlags.Instance | Public | NonPublic`，沿 `BaseType` 链先找字段再找属性）：

```csharp
private static MemberInfo Member(object owner, string name);
private static bool TryLive(ModContext mod, string path, out object value);   // "a.b.c" 逐级走
private static bool SetLive(ModContext mod, string path, object value);
private static bool LiveBool(ModContext mod, string path, bool fallback);
private static bool ToggleFlag(ModContext mod, string path);                   // 翻一个 bool，返回新值
```

`ToggleTownPanel` 额外提示场景限制：

```csharp
bool target  = ToggleFlag(mod, "_panel._visible");
bool visible = LiveBool(mod, "_panel._toggle.visible", true);
AndroidApi.ShowNotice(visible
    ? "小镇修改器面板：" + (target ? "已打开" : "已关闭")
    : "小镇修改器面板：" + (target ? "已打开" : "已关闭") + "，进入吉星小镇后可见");
```

### 11.2 IL 取证：每个模组到底读哪个输入 API

用 `System.Reflection.Metadata` 写了个一次性反汇编探针（不进仓库），结论：

| 模组 | 调用的输入 API | 我们能否送进去 |
| --- | --- | --- |
| `PveWaveForecastMod` | `InputService.IsKeyPressed` | 能 |
| `ShopRelicPeekMod` | `InputService.IsKeyPressed` | 能 |
| `StarCoinShopMod` | `InputService.IsKeyPressed` | 能 |
| `PvePanelMod` | `InputService.IsKeyDown`（= `IsKeyPressed` 别名） | 能 |
| `HideHudMod` | `InputService.IsKeyHeld` | 能（需 latch） |
| `CharacterTransparencyMod` | `InputService.IsKeyHeld` | 能（需 latch） |
| `TeammateCheckMod` | `InputService.IsKeyHeld` | 能 |
| `TownTrainer` | **`UnityEngine.Input.GetKeyDown(289)`** | **不能** |

`TownTrainerPanel::Tick` 里紧跟 `ldc.i4 289`（= `KeyCode.F8`）就是 Unity 原生 `Input.GetKeyDown`，**绕过了 SDK 的 `InputService`**，所以我们装饰的后端收不到。这是唯一改用反射翻字段的模组。

`TeammateCheckMod.TickHotkey` 的门槛顺序（决定了它什么时候不响应）：
`_panel == null → return`；`_toggleKey != 0`；`InputService.IsAvailable`；`IsKeyHeld(_toggleKey)` 且 `_toggleHeld == false`（上升沿）；`!InputService.IsKeyboardCaptured`；最后才 `SetWanted(!Wanted)`。
`_toggleKey = ParseKey(GetString("toggleKey", "F9"), 290)`。

---

## 12. 按键映射（`KeyMappingPanel.cs`）

默认键表在 `KeyBindings(id)`：

| 模组 | 配置字段 | 默认键 |
| --- | --- | --- |
| `HideHudMod` | `toggleKey` | `BackQuote` |
| `CharacterTransparencyMod` | `toggleKey` | `H` |
| `PveWaveForecastMod` | `toggleKey` | `O` |
| `ShopRelicPeekMod` | `clearKey` | `T` |
| `StarCoinShopMod` | `quickBuyKey` | `F` |
| `PvePanelMod` | `LeftToggleKey` / `HandToggleKey` / `RightToggleKey` | `F3` / `F4` / `F5` |
| `UpdateNoticeMod` | `toggleKey` | `F8` |
| `TeammateCheckMod` | `toggleKey` | `F9` |
| `TownTrainer` | `panelKey` | `F8`（硬编码，仅作展示） |

`CanMap(id)` 排除：`SpeedHackMod`（有独立变速页）、`FreeCameraMod`（单指操作与游戏冲突）、`CesiumOverlayMod`（自身）。

**已删除「手动映射编辑器」**：原先 `RenderKeyMappings` 里有一个 `GTextInput` 让用户自己输入键名并存进 `configs/CesiumOverlayMod.json` 的 `keymap.<ModId>`，同时 `KeyBindings` 会把它追加成「自定义按键」一行。这套东西与模组自身配置脱节，已整段移除（含 `KeyBindings` 里的 `Config.GetString("keymap." + id)` 分支）。现在提示文案是「键位取自模组自身的配置。」

`VirtualKeyboard.TryParse` 支持的别名：`` ` ``/`~`/`·` → `BackQuote`，`Ctrl`→`LeftControl`，`Alt`→`LeftAlt`，`Shift`→`LeftShift`，`Esc`→`Escape`，`空格`→`Space`；拒绝 `None`、数字开头、`(int)key >= (int)KeyCode.Mouse0`；最多 4 个键组合。

---

## 13. 开发者接口（`CesiumAndroidApi`）

新增（`mods/CesiumAndroidApi/AndroidApi.cs`）：

```csharp
public sealed class ShortcutItem
{
    public string ModId, Id, Label, Detail;
    public Func<bool> Get;     // 非空 → 这一行渲染成开关
    public Action Run;
    public bool IsToggle { get { return Get != null; } }
}

public static List<ShortcutItem> Shortcuts { get; }
public static List<ShortcutItem> GetShortcuts(string modId);
public static List<ActionItem>  GetActionsOf(string modId);

public static void RegisterShortcut(string id, string label, Func<bool> get, Action run, string detail = null);
public static void RegisterShortcut(string modId, string id, string label, Func<bool> get, Action run, string detail = null);
public static void RegisterShortcutButton(string id, string label, Action run, string detail = null);
public static void RegisterShortcutButton(string modId, string id, string label, Action run, string detail = null);
```

- 注册幂等：同 `ModId + Id` 覆盖（`PutShortcut`）。
- `modId` 省略时用 `Assembly.GetCallingAssembly()` 反查（两参重载标了 `[MethodImpl(NoInlining)]`，否则内联后会反查到调用者的调用者）。
- `ClearActions()` 里同步 `_shortcuts.Clear()`。
- 行视觉规则：`IsToggle && Get()` → `primaryContainer` + 「开」；否则 `surfaceContainerHigh` + 「关」；非开关行不显示开/关。行高 58u，主标题 15u、副标题 11u。
- `Get()` 抛异常会被吞掉并按 `false` 处理；但它每帧都会被调用，**不要在里做耗时操作**。

细则见 [`overlay-shortcuts.md`](overlay-shortcuts.md)。

---

## 14. 构建与部署

### 14.1 构建（四条命令，顺序不能错）

```powershell
# 1) 编译模组
dotnet build mods\CesiumOverlayMod -c Release --nologo `
  -p:GameRefs=D:\AstralPartyGit\apa-repo\refs-ci `
  -p:CesiumSdkDll="...\app\src\main\assets\cesium\sdk\CesiumLoader.SDK.dll"

# 2a) 只出悬浮窗（没改 AndroidApi.cs 时用这个）
py -3 mods\build.py --overlay-only --refs D:\AstralPartyGit\apa-repo\refs-ci --sdk "...\CesiumLoader.SDK.dll"

# 2b) 全量（改过 AndroidApi.cs 时必须用这个，否则 API DLL 不重编）
py -3 mods\build.py --refs ... --sdk ...

# 3) APK
cmd /c "gradlew.bat :app:assembleDebug --offline -q > log 2> err"
```

> **最大的坑**：`--overlay-only` **不拷贝 `CesiumAndroidApi.dll`**（`build.py` 里是 `if not args.overlay_only: shutil.copy2(api, ...)`）。改了 `AndroidApi.cs` 却只跑 `--overlay-only`，设备上就是旧 API DLL，游戏启动报
> `TypeLoadException: Could not load type 'CesiumAndroid.ShortcutItem' from assembly 'CesiumAndroidApi'`，
> 悬浮窗**整个消失**（`done: ok=26 failed=1`）。

游戏引用集必须用 **`D:\AstralPartyGit\apa-repo\refs-ci`**（`AstralParty.Runtime.dll` 11,777,536 B），本地 `refs` 里的版本落后。

### 14.2 产物守卫

`app/build.gradle.kts` 的 `syncLoaderAssets` 除了原有的三件套检查，还加了：

```kotlin
val overlayVersion = "1.0.0"
// 清单里的 version 是 build.py 写死的常量，只看它会把任意旧二进制放行
require(declaredVersion == overlayVersion) { ... }
val overlayBytes = File(...).readBytes()
require(String(overlayBytes, Charsets.UTF_16LE).contains("toys-overlay.txt") &&
        String(overlayBytes, Charsets.UTF_8).contains("PollOptions")) { ... }
```

即：**清单版本 + DLL 里真的含 `toys-overlay.txt`（UTF-16LE 字符串）与 `PollOptions`（UTF-8 标识符）** 才算通过。

### 14.3 部署到设备

```powershell
$adb = "D:\Android\sdk\platform-tools\adb.exe"   # 不在 PATH
& $adb -s <serial> push <file> /data/local/tmp/x
& $adb -s <serial> shell "su -c 'cp /data/local/tmp/x <目标> && chown u0_a331:ext_data_rw <目标> && chmod 660 <目标>'"
& $adb -s <serial> shell "md5sum <目标>"     # 必须与本地一致
```

- 加载器根：`/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader`（`configs/ data/ logs/ mods/ sdk/`）
- **直接 push 到 `/sdcard/Android/data/...` 会静默失败**，必须先 push 到 `/data/local/tmp` 再 `su cp`。
- 重启：`am force-stop com.feimo.astralparty` + `am start -n com.feimo.astralparty/prj.iyinghun.platform.sdk.SplashScreenActivity`，等 **70–80 秒**。
- 看启动结果：`logs/bootstrap-*.log` 里应是 `mods found: 27` / `done: ok=27 failed=0`。
- 截图：`screencap -p /sdcard/x.png` + `adb pull`。**注意 `read_image` 会把 3048×2032 缩成 2508×1672，`input tap` 坐标要 ×1.22**。

---

## 15. 踩坑清单

1. **`GGraph.DrawRoundRect` 的四角数组顺序是 `[TL, TR, BL, BR]`**，不是 `TL,TR,BR,BL`。
2. **`GGraph` 会丢弃 fill 的 alpha**，做不出半透明。
3. **零尺寸的 `GComponent` 不参与命中测试**。`blob` 创建后必须 `SetSize(...)`，否则点击全落到 backdrop，展开不了。
4. **完全透明的 `GObject` 也不参与命中测试**。闲置淡出下限必须留 `.02f`。
5. **`GTextInput` 获得焦点时必须停止送键**，否则输入框里会乱跳。
6. **不要用 `UiService.ShouldBlockGameInput` 判输入**，它在悬浮窗显示时恒 true。
7. **`GRoot.inst` 没有 `onTouchBegin/Move/End`**，全屏点击捕获要自己加 `opaque = true` 的全屏 `GComponent`。
8. **`BodyAlive`**：`Render()` 里碰 `body.visible` 前必须判 `body != null && !body.isDisposed && body.parent != null`，否则每帧 NRE。
9. **手指按下时不重建节点树**（`FingerDown` / `renderPending`），否则会销毁正被按的按钮，点击丢失。
10. **几何类设置（字号/统一大小）不能在展开时重建**，`rebuildPending` 只在 `mode == ShellMode.Idle` 时执行 `Build()`。
11. **`SelectPage` 的短路条件必须带 `openModPage < 0`**（`if (page == index && openModPage < 0) return;`）。早期只比 `index`，导致详情页的返回按钮（回到同一个 page）直接 return，返回键失效。
12. **`Collapse()` 不能重置 `page`**，否则每次重开都回「功能」页。
13. **改含中文的源码不能用 `edit` 工具**（匹配会失败），Windows 上 `read` 也会把 UTF-8 中文解成乱码；用 `py -3` + `io.open(..., encoding='utf-8', newline='')` 脚本读写。
14. **`dotnet run ... > file` 在 Windows 上写 UTF-16LE**，读回必须 `encoding='utf-16'`。
15. Android 上 `input tap` 的坐标系是「设备物理像素」，截图被缩放过就要按比例换算。
16. 点导航最下方的图标容易点到系统手势区（会把别的 app 拉到前台），操作前先 `am start -n ...` 把游戏前台化。

---

## 16. 验证状态

### 已在实机确认（平板 `1435f0be`，Android，游戏 `com.feimo.astralparty`）

- 27 个模组全部加载：`done: ok=27 failed=0`
- 「功能」页两段结构与去重生效：HideHud / 波次预告 / 队友战绩面板 / 小镇修改器面板 在「快捷开关」，PVE 对局面板 / 商店和筹码看板 / 星币商店助手 在「按键映射」
- 快捷行实时变色：点「隐藏 / 恢复 HUD」后该行由「关」变紫色「开」
- 「波次预告」「队友战绩面板」显示真实运行时状态（紫色「开」）
- 「收起 → 重开」保留原页面（不再跳回「功能」页）
- 「变速」页：1.0x 读数、±0.5x、五档预设、「恢复倍速 1.5x」
- 圆角在动画中无锯齿、收起无卡顿（两级烘焙 + 0.12s 延迟）

### 尚未验证 / 已知遗留

- **关于页新三行形态**未在实机截图确认（只确认了代码）。
- **小镇修改器面板**：行状态能翻，但面板本体只在「吉星小镇」场景（`SceneManager.getCurrentType().Value == 6`）才可见，未在场景内实测。
- **浅色主题分支**未实测（只验证了深色）。
- **面板不透明度默认 0.5**，游戏文字会透过面板（设计如此，但用户未最终确认是否提高默认值）。
- 上游的 `DockState.cs`（`DockIconState`）在重做后**已无引用**，属于死代码，可删。
- `OverlayWindow.VisibleMods(bool)` 也已无调用者（分组过滤逻辑保留但不再用于功能页），可删。
- `ModCatalog.Functions` / `IsFunction` 只被 `VisibleMods` 用，随之一起变死代码。
- 设置页的「分组」区还展示分组名与模组数量，但功能页已不按分组显示——文案需要再斟酌。
- 改动**尚未提交 git**。
