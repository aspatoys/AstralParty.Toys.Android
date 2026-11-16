using System;
using System.Collections.Generic;

namespace CesiumOverlayMod
{
    /// <summary>Layout and navigation use real time, independently of the selected game speed.</summary>
    internal sealed class OverlayPresentation
    {
        internal const string SpeedId = "SpeedHackMod";
        internal const float OpenDuration = .34f;
        internal const float CloseDuration = .24f;
        internal string Selected = SpeedId;
        internal int Tab; // 0: speed, 1..N: user groups, N+1: unassigned mods
        internal bool Open { get; private set; }
        internal bool FromRight;
        private bool openedOnce;
        private float from, to, started, duration;

        internal void Toggle(float now)
        {
            float current = Progress(now);
            Open = !Open;
            if (Open && !openedOnce) { Tab = 0; Selected = SpeedId; openedOnce = true; }
            from = current;
            to = Open ? 1 : 0;
            started = now;
            duration = (Open ? OpenDuration : CloseDuration) * Math.Abs(to - from);
        }

        internal float Progress(float now)
        {
            if (duration <= 0) return to;
            float t = Math.Max(0, Math.Min(1, (now - started) / duration));
            float q = t - 1;
            float eased = Open ? 1 + 1.6f * q * q * q + .6f * q * q : t * t * (3 - 2 * t);
            return from + (to - from) * eased;
        }

        internal void SelectTab(int tab) { Tab = tab; Selected = tab == 0 ? SpeedId : null; }

        internal static OverlayBounds Bounds(float width, float height)
        {
            bool landscape = width >= height;
            float panelWidth = landscape ? width * .5f - 16 : width - 24;
            float panelHeight = landscape ? height - 24 : height * .5f - 16;
            return new OverlayBounds(Math.Max(1, panelWidth), Math.Max(1, panelHeight));
        }
    }

    internal struct OverlayBounds
    {
        internal readonly float Width, Height;
        internal OverlayBounds(float width, float height) { Width = width; Height = height; }
    }

    /// <summary>The functional group is an explicit user-owned allowlist, never inferred.</summary>
    internal static class ModCatalog
    {
        internal static readonly string[] Functions = {
            "SpeedHackMod", "HideHudMod", "AutoThanksMod", "ShopRelicPeekMod", "PveWaveForecastMod"
        };

        private static readonly Dictionary<string, string> Names = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase) {
            {"SpeedHackMod", "游戏全局变速"}, {"HideHudMod", "隐藏 HUD"},
            {"AutoThanksMod", "自动感谢"}, {"ShopRelicPeekMod", "商店和筹码看板"},
            {"PveWaveForecastMod", "PVE 波次预告"}, {"ActivityLogMod", "行为日志"},
            {"AngelModeUnlockMod", "天使模式解锁"}, {"AudioSkinMod", "音效方案替换"},
            {"CardSkinMod", "手牌卡面皮肤"}, {"CesiumOverlayMod", "工具箱信息"},
            {"CharacterTransparencyMod", "角色半透明"}, {"ChatDurationMod", "消息停留时间"},
            {"CombatOddsMod", "战斗胜率助手"}, {"ForkDirectionMod", "岔路地块标记"},
            {"FreeCameraMod", "自由相机"}, {"HomeBottomBarMod", "主页底栏调整"},
            {"MapPeekMod", "选人地图预览"}, {"PinnedRelicsMod", "关键筹码置顶"},
            {"PvePanelMod", "PVE 对局面板"}, {"QuickChatPlusMod", "快捷短语扩展"},
            {"StarCoinShopMod", "星币商店助手"}, {"TurnClockMod", "回合时钟"},
            {"TurnFocusMod", "回合置前"}, {"UnlockFrameRateMod", "帧率解锁"}
        };

        internal static bool IsFunction(string id)
        {
            foreach (string item in Functions) if (string.Equals(id, item, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        internal static string Name(string id, string registered = null)
        {
            string name;
            if (id != null && Names.TryGetValue(id, out name)) return name;
            return HasChinese(registered) ? registered : "扩展模组";
        }

        internal static bool HasChinese(string value)
        {
            if (value != null) foreach (char c in value) if (c >= '\u3400' && c <= '\u9fff') return true;
            return false;
        }

        internal static string Description(string id)
        {
            switch (id) {
                case "SpeedHackMod": return "调整客户端逻辑与动画倍速";
                case "HideHudMod": return "隐藏或显示游戏界面元素";
                case "AutoThanksMod": return "自动发送游戏内感谢";
                case "ShopRelicPeekMod": return "查看商店与筹码信息";
                case "PveWaveForecastMod": return "显示后续波次预告";
                case "FreeCameraMod": return "双指缩放，保留游戏单指操作";
                case "CesiumOverlayMod": return "侧边功能助手与游戏内操作入口";
                default: return "点击查看可用操作";
            }
        }

        internal static string ActionName(string label, bool toggle)
        {
            if (HasChinese(label)) return label;
            switch ((label ?? "").ToLowerInvariant()) {
                case "enable": return "启用功能";
                case "disable": return "停用功能";
                case "reset": case "restore": return "恢复默认";
                case "status": return "运行状态";
                default: return toggle ? "功能开关" : "执行操作";
            }
        }
    }
}
