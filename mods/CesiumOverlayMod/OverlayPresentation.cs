// Page metadata and the Chinese catalogue of known mods.
using System;
using System.Collections.Generic;

namespace CesiumOverlayMod
{
    internal sealed class OverlayPage
    {
        internal readonly string Key;
        internal readonly string Icon;
        internal readonly string Title;
        internal readonly string Subtitle;

        internal OverlayPage(string key, string icon, string title, string subtitle)
        {
            Key = key; Icon = icon; Title = title; Subtitle = subtitle;
        }
    }

    internal static class OverlayPresentation
    {
        internal const string SpeedId = "SpeedHackMod";

        internal static readonly OverlayPage[] Pages =
        {
            new OverlayPage("apps", "apps", "功能", "已加载模组与分组"),
            new OverlayPage("speed", "speed", "变速", "游戏全局倍速"),
            new OverlayPage("config", "tune", "模组配置", "开关与参数"),
            new OverlayPage("settings", "settings", "设置", "外观与悬浮窗选项"),
            new OverlayPage("console", "terminal", "控制台", "运行日志与导出"),
            new OverlayPage("info", "info", "关于", "版本与说明"),
        };

        /// <summary>Index of the page that lists mods; used by the long-press shortcut.</summary>
        internal const int AppsPage = 0;
        internal const int SpeedPage = 1;
        internal const int ConfigPage = 2;
        internal const int SettingsPage = 3;
        internal const int ConsolePage = 4;
        internal const int InfoPage = 5;
    }

    internal static class ModCatalog
    {
        internal static readonly string[] Functions =
        {
            "SpeedHackMod", "HideHudMod", "AutoThanksMod", "ShopRelicPeekMod", "PveWaveForecastMod",
        };

        internal static readonly Dictionary<string, string> Names = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            { "SpeedHackMod", "游戏全局变速" },
            { "HideHudMod", "隐藏 HUD" },
            { "AutoThanksMod", "自动感谢" },
            { "ShopRelicPeekMod", "商店和筹码看板" },
            { "PveWaveForecastMod", "PVE 波次预告" },
            { "ActivityLogMod", "行为日志" },
            { "AngelModeUnlockMod", "天使模式解锁" },
            { "AudioSkinMod", "音效方案替换" },
            { "CardSkinMod", "手牌卡面皮肤" },
            { "CesiumOverlayMod", "工具箱信息" },
            { "CharacterTransparencyMod", "角色半透明" },
            { "ChatDurationMod", "消息停留时间" },
            { "CombatOddsMod", "战斗胜率助手" },
            { "ForkDirectionMod", "岔路地块标记" },
            { "FreeCameraMod", "自由相机" },
            { "HomeBottomBarMod", "主页底栏调整" },
            { "LanguageUnlockMod", "语言解锁" },
            { "MapPeekMod", "选人地图预览" },
            { "PinnedRelicsMod", "关键筹码置顶" },
            { "PvePanelMod", "PVE 对局面板" },
            { "QuickChatPlusMod", "快捷短语扩展" },
            { "StarCoinShopMod", "星币商店助手" },
            { "TurnClockMod", "回合时钟" },
            { "TurnFocusMod", "回合置前" },
            { "UnlockFrameRateMod", "帧率解锁" },
            { "UpdateNoticeMod", "更新提示" },
        };

        internal static bool IsFunction(string id)
        {
            for (int i = 0; i < Functions.Length; i++)
                if (string.Equals(Functions[i], id, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        internal static string Name(string id, string registered)
        {
            if (Names.TryGetValue(id, out string name)) return name;
            if (!string.IsNullOrEmpty(registered) && HasChinese(registered)) return registered;
            return "扩展模组";
        }

        internal static bool HasChinese(string value)
        {
            if (string.IsNullOrEmpty(value)) return false;
            foreach (char c in value)
                if (c >= '\u3400' && c <= '\u9fff') return true;
            return false;
        }

        internal static string Description(string id)
        {
            switch (id)
            {
                case "SpeedHackMod": return "调整客户端逻辑与动画倍速";
                case "HideHudMod": return "隐藏或显示游戏界面元素";
                case "AutoThanksMod": return "自动发送游戏内感谢";
                case "ShopRelicPeekMod": return "查看商店与筹码信息";
                case "PveWaveForecastMod": return "显示后续波次预告";
                case "FreeCameraMod": return "双指缩放，保留游戏单指操作";
                case "CardSkinMod": return "替换手牌与卡面皮肤";
                case "CharacterTransparencyMod": return "降低角色遮挡，看清地块";
                case "CombatOddsMod": return "估算战斗胜负概率";
                case "LanguageUnlockMod": return "解锁更多界面语言";
                case "CesiumOverlayMod": return "侧边功能助手与游戏内操作入口";
                default: return "点击查看可用操作";
            }
        }

        internal static string ActionName(string label, bool toggle)
        {
            string value = (label ?? string.Empty).Trim().ToLowerInvariant();
            if (value.StartsWith("enable")) return "启用功能";
            if (value.StartsWith("disable")) return "停用功能";
            if (value.StartsWith("reset") || value.StartsWith("restore")) return "恢复默认";
            if (value.StartsWith("status")) return "运行状态";
            if (string.IsNullOrEmpty(label)) return toggle ? "功能开关" : "执行操作";
            return label;
        }
    }
}
