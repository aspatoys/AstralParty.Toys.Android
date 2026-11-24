# 悬浮窗「功能」页快捷项开发接口

悬浮窗（`CesiumOverlayMod`，1.0.0 起）的「功能」页不再按 APK 分组罗列全部模组，只显示两类内容：

1. **快捷开关** —— mod 通过本文档描述的接口主动注册的开关或按钮。
2. **按键映射** —— 该 mod 自带 Win 模组快捷键，且能用 `CesiumAndroid.VirtualKeyboard` 发送的。
   这一段会跳过已经在第一段出现过的 `ModId`，同一个模组不会占两行。

「模组配置」页则是纯导航列表，点一行进入该 mod 自己的选项与按键映射，页面上不再有开关。

因此一个 mod 想在「功能」页出现，只有两条路：**注册一条快捷项**，或者**拥有可映射的快捷键**。

---

## 1. 前提

```xml
<ProjectReference Include="../CesiumLoader.Mobile.API/CesiumLoader.Mobile.API.csproj" />
```

`CesiumLoader.Mobile.API.dll` 与模组 DLL 一起放在加载器根的 mods 目录，由悬浮窗负责装载。接口命名空间是 `CesiumAndroid`。

所有注册都是**幂等**的：同一个 `ModId + Id` 重复注册会覆盖上一条，因此可以在 `OnUpdate()` 里放心地反复注册（注册表本身不会变长）。

## 2. 注册一条快捷开关

```csharp
using CesiumAndroid;

public sealed class MyMod : ModBase
{
    public override void OnInitialize()
    {
        // 开关：Get 决定行是否高亮，Run 负责真正切换
        AndroidApi.RegisterShortcut(
            id: "panel.visible",
            label: "我的面板",
            get: () => Config.GetBool("showPanel", true),
            run: () => Flip("showPanel"),
            detail: "按键 F9");
    }

    private void Flip(string field)
    {
        bool value = !Config.GetBool(field, false);
        Config.Set(field, value);
        Config.SaveIfDirty();
        AndroidApi.ShowNotice("我的面板：" + (value ? "已开启" : "已关闭"));
    }
}
```

签名：

```csharp
void RegisterShortcut(string id, string label, Func<bool> get, Action run, string detail = null);
void RegisterShortcut(string modId, string id, string label, Func<bool> get, Action run, string detail = null);
```

| 参数 | 说明 |
| --- | --- |
| `id` | 模组内唯一即可，用于覆盖与日志，不显示给玩家。 |
| `label` | 主标题，建议 6 个汉字以内，行宽有限。 |
| `get` | 每帧轮询当前状态。`true` 整行染成 `primaryContainer` 并显示「开」。传 `null` 会退化成普通按钮（推荐改用 `RegisterShortcutButton`）。 |
| `run` | 点击回调。**开关行的点击也会走 `run`，不会替你改状态** —— 状态由你自己的配置决定，`get` 必须与 `run` 的写入保持一致。 |
| `detail` | 副标题，显示当前状态或说明（如「按键 F9」「隐藏 HUD · 手牌」）。留空时回退成 mod 的中文名。 |
| `modId` | 省略时通过 `Assembly.GetCallingAssembly()` 反查自己在 `ModRegistry` 里的 id，动态加载的程序集也能正确归属。 |

## 3. 注册一条普通按钮

```csharp
AndroidApi.RegisterShortcutButton("town.panel", "小镇修改器面板", () => SendF8(), "按键 F8");
```

```csharp
void RegisterShortcutButton(string id, string label, Action run, string detail = null);
void RegisterShortcutButton(string modId, string id, string label, Action run, string detail = null);
```

不可切换、也没有状态，整行不染色。适合「触发一次动作」类功能。

## 4. 读取别人的快捷项

```csharp
List<ShortcutItem> all = AndroidApi.Shortcuts;          // 全部
List<ShortcutItem> mine = AndroidApi.GetShortcuts("MyMod"); // 按 mod 过滤（忽略大小写）
```

```csharp
public sealed class ShortcutItem
{
    public string ModId;
    public string Id;
    public string Label;
    public string Detail;
    public Func<bool> Get;
    public Action Run;
    public bool IsToggle { get; }   // => Get != null
}
```

动作项（`ActionItem`，走 `RegisterButton` / `RegisterToggle`）仍然存在，但只出现在**该 mod 的详情页**，不会进「功能」页。取舍是：

- 需要「随手按」的开关 → `RegisterShortcut*`，进「功能」页。
- 需要「先找到这个 mod 再调」的参数 → `RegisterToggle` / `RegisterButton`，留在详情页。

## 5. 行的视觉规则

| 状态 | 背景 | 文字 | 右侧 |
| --- | --- | --- | --- |
| `IsToggle && Get() == true` | `primaryContainer` | `onPrimaryContainer` | 「开」 |
| `IsToggle && Get() == false` | `surfaceContainerHigh` | `onSurface` / `onSurfaceVariant` | 「关」 |
| 非开关（`Run` only） | `surfaceContainerHigh` | `onSurface` / `onSurfaceVariant` | 无 |

行高 58 设计单位，主标题 15u、副标题 11u。`get` 抛异常会被吞掉并当作 `false`，不会让整页渲染失败；但**不要**在 `get` 里做耗时操作——它每帧都会被调用。

列表本身是可垂直滚动的；滚动缓存键包含 `Id`、`Label`、`get()` 与 `Detail`，其中任一项变化都会触发重绘，所以状态文字可以直接写在 `Detail` 里。

## 6. 内置快捷项（参考实现）

`mods/CesiumOverlayMod/BuiltinActions.cs` 的 `RegisterKnownShortcuts` 为随加载器分发的模组注册了以下快捷项，可直接当作范例：

| 模组 | 快捷项 | 类型 | 实际动作 |
| --- | --- | --- | --- |
| `HideHudMod` | 隐藏 / 恢复 HUD | 开关（latch） | 第一次点按下 `toggleKey`，第二次点松开 |
| `CharacterTransparencyMod` | 角色半透明 | 开关（latch） | 同上，键为 `toggleKey`（默认 H） |
| `PveWaveForecastMod` | 波次预告 | 按钮 | 点一次发 `toggleKey`（默认 O） |
| `TeammateCheckMod` | 队友战绩面板 | 开关 | 点一次发 `toggleKey`（默认 F9） |
| `TownTrainer` | 小镇修改器面板 | 按钮 | 点一次发 F8 |

`TownTrainer` 没有 `mods/TownTrainer/config.json`，面板开关在程序集里写死为 `Input.GetKeyDown(289)`（`KeyCode.F8`）。这一结论来自对 `TownTrainer.dll` 的 IL 反汇编：`TownTrainerPanel::Tick` 里紧跟 `ldc.i4 289` 就是 `GetKeyDown` 调用。没有配置文件的模组只能这样取证。

### 6.1 为什么两个模组是「latch」而不是普通开关

`HideHudMod` 出厂是 `pressMode: "Hold"` + `holdSeconds: 0.35`，`CharacterTransparencyMod` 是 `holdMode: true`。这两个模组在 `Update()` 里**轮询 `GetKey` 并持续一段时间**才动作，翻转配置里的布尔值不会让它们立刻响应——配置只决定「按下去时隐藏什么」。

所以这两行不做配置翻转，而是把虚拟键盘的键**按住不放**：第一次点 `VirtualKeyboard.Send(source, chord, true)` 按下并保持，第二次点同一个 source 再调用一次即松开。`get` 返回 `VirtualKeyboard.Instance.Holding(source)`，行颜色因此等同于「键现在是否被我们按住」。这是触屏上驱动 hold 型模组的唯一可靠办法。

对应的辅助函数是 `Latch(source, chord)`；普通一次性按键用 `Press(modId, source, chord)`，它在发送前会检查该模组的 `enabled` / `Enabled` 总开关，为 false 就先打开再发送。

## 7. 按键映射（「功能」页第二段）

`mods/CesiumOverlayMod/KeyMappingPanel.cs` 的 `KeyBindings(id)` 决定某个 mod 有哪些可映射键。默认表：

| 模组 | 配置字段 | 默认键 | 说明 |
| --- | --- | --- | --- |
| `HideHudMod` | `toggleKey` | `BackQuote` | 隐藏 / 恢复 HUD |
| `CharacterTransparencyMod` | `toggleKey` | `H` | 角色半透明 |
| `PveWaveForecastMod` | `toggleKey` | `O` | 波次预告 |
| `ShopRelicPeekMod` | `clearKey` | `T` | 商店和筹码看板 |
| `StarCoinShopMod` | `quickBuyKey` | `F` | 星币商店助手 |
| `PvePanelMod` | `LeftToggleKey` / `HandToggleKey` / `RightToggleKey` | `F3` / `F4` / `F5` | 左侧信息栏 / 手牌抽屉 / 右侧情报栏 |
| `TeammateCheckMod` | `toggleKey` | `F9` | 队友战绩面板 |
| `TownTrainer` | `panelKey` | `F8` | 小镇修改器面板（硬编码，见上） |
| `UpdateNoticeMod` | `toggleKey` | `F8` | 更新提示 |


`CanMap(id)` 显式排除三类：`SpeedHackMod`（有独立变速页）、`FreeCameraMod`（单指操作会与游戏冲突）、`CesiumOverlayMod`（自身）。要接入新的可映射模组，在 `KeyBindings` 里加一个 `case` 即可，模组自身的 `Config` 无需改动。

发送链路：

```
按键映射按钮
  → KeyMappingPanel.SendKey(id, source, chord, hold)
      · mod 未运行 / 状态非 Initialized        → 提示并中止
      · mod 的总开关是 false                    → 先自动开启再发送
      · VirtualKeyboard.Instance == null        → 提示并中止
  → VirtualKeyboard.Send(source, chord, hold)
      · AcceptsKeys 为 false（失焦 / 正在输入文字）→ 丢弃这一帧
      → VirtualKeyState.Press(source, keys, frame, frames)
          · hold=true → 无限期按住（再调一次即松开）
          · 点按     → TapFrames() 帧，约 0.55 秒（clamp 6–180）
            ↑ 不是 1 帧：hold 型模组要求按住足够久才动作，
              单帧脉冲对它们是完全不可见的。
  → 模组每帧轮询 Input.GetKeyDown / GetKey
```

`VirtualKeyboard` 通过 `InputService.SetBackend(...)` 装饰官方输入后端，不替换 SDK，也不改写模组自身的快捷键配置。

**总开关自动开启**是有意的：玩家按下映射就是在要求这个模组动作，而 `enabled=false` 的模组会直接忽略热键，表现成「按钮没反应」。`KeyMappingPanel.Enable` 会写入 `enabled` / `Enabled`（哪个存在写哪个）并调用 `SaveIfDirty()`，必要时反射调用模组的 `OnConfigChanged`。

## 8. 调试

- 注册与覆盖都会写进 `logs/developer.log`，形如 `[api] shortcut ModId/Id`、`[api] shortcut button ModId/Id`。
- 状态不对时优先看该模组的 `mods/<Id>/config.json`（停用模组在 `disabled_mods/<Id>/config.json`）：`get` 读的就是这里。
- 游戏内「控制台」页可导出日志：点击「导出日志」会在 `data/toys-log-<时间戳>.log` 生成一份含屏幕尺寸、倍速、分组数与全部模组状态的快照。

## 9. 与旧版接口的关系

`ActionItem` / `RegisterMod` / `RegisterToggle` / `RegisterButton` / `GetActions` / `GetActionsOf` 全部保留，语义不变，只是不再影响「功能」页的内容。已有 mod 不改代码也不会失效，只是不会自动出现在「功能」页——除非它本来就有可映射的快捷键。

`AndroidApi.Notice` 的语义也没有变：写入后由悬浮窗计时 4 秒自动清空（`NoticeSeconds`）。不要自己清空它，否则计时会错乱。
