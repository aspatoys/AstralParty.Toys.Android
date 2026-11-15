using CesiumLoader.SDK.Manifests;
using CesiumLoader.SDK.Mods;
using CesiumOverlayMod;

[assembly: ModManifest("变速（仅悬浮窗控制）", "2.5.5-android-panel", "Cesium Android",
    "每次启动保持 1 倍，忽略历史倍率和键盘热键，仅通过游戏内悬浮窗控制。",
    Permissions = ModPermission.UI | ModPermission.SpeedHack)]

namespace SpeedHackMod
{
    public static class ModEntry
    {
        public static void Main() => ModBase.Run(new PanelOnlySpeed(), delayMs: 0, tag: "PanelOnlySpeed");
    }

    /// <summary>No independent clock writer, startup preset, keyboard polling or config-driven acceleration.</summary>
    public sealed class PanelOnlySpeed : ModBase
    {
        public override string Name => "变速（仅悬浮窗控制）";
        public override string Version => "2.5.5-android-panel";
        public override void OnLoad() => SpeedPanel.BeginSession();
        public override void OnInitialize() => SpeedPanel.BeginSession();
    }
}
