using System;
using System.IO;
using CesiumAndroid;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    public sealed partial class OverlayController
    {
        private OverlayOptions options = OverlayOptions.Defaults();
        private string optionsRaw;
        private GComponent sidePanel;
        private readonly DockIconState dock = new DockIconState();
        private bool iconDragging, iconPressed;
        private const int SettingsTab = -1;
        private string OptionsPath { get { return AndroidApi.GetLoaderPath("data", "toys-overlay.txt"); } }

        private bool PollOptions()
        {
            try
            {
                string raw = File.Exists(OptionsPath) ? File.ReadAllText(OptionsPath) : null;
                if (raw == optionsRaw) return false;
                var next = raw == null ? OverlayOptions.Defaults() : OverlayOptions.Parse(raw);
                options = next; optionsRaw = raw;
                if (presentation.Tab > options.Groups.Count + 1) presentation.SelectTab(0);
                return true;
            }
            catch (Exception error) { AndroidApi.ShowNotice("悬浮窗设置读取失败，保留当前设置：" + error.Message); return false; }
        }

        private void SaveOpacity(float opacity)
        {
            // Re-read before saving so a concurrent APK edit does not lose its groups or size.
            var latest = File.Exists(OptionsPath) ? OverlayOptions.Parse(File.ReadAllText(OptionsPath)) : options;
            latest.Opacity = OverlayOptions.Clamp(opacity, .25f, 1, .94f);
            string raw = latest.Encode();
            Directory.CreateDirectory(Path.GetDirectoryName(OptionsPath));
            string temp = OptionsPath + ".game-tmp";
            File.WriteAllText(temp, raw);
            // Root is game-owned; replacing is safe and readers only see complete snapshots.
            if (File.Exists(OptionsPath)) File.Replace(temp, OptionsPath, null);
            else File.Move(temp, OptionsPath);
            options = latest; optionsRaw = raw;
        }

        private void RenderSidebar()
        {
            if (sidePanel != null && !sidePanel.isDisposed) sidePanel.Dispose();
            var layout = DockLayout.Measure(width, height, uiScale, options.Width, presentation.FromRight, icon.y);
            float railWidth = layout.Rail / uiScale;
            sidePanel = new GComponent { name = "overlay.sidebar-shell", opaque = true };
            sidePanel.SetSize(railWidth, panelHeight);
            root.AddChild(sidePanel);
            Rounded(sidePanel, railWidth, panelHeight, OverlayTheme.Background, 28, true);
            sidePanel.onTouchBegin.Add(BlockGestures);
            var sidebar = new OverlayScrollView(railWidth - 12, Math.Max(1, panelHeight - 16));
            sidebar.name = "overlay.sidebar"; sidebar.SetXY(6, 8); sidePanel.AddChild(sidebar);
            float y = 0;
            for (int i = 0; i <= options.Groups.Count + 1; i++)
            {
                int tab = i;
                string label = i == 0 ? "变速" : i == options.Groups.Count + 1 ? "其他" : options.Groups[i - 1].Name;
                Button(sidebar, label, "overlay.tab." + i, 0, y, sidebar.width, 64,
                    () => { VirtualKeyboard.Instance?.Clear(); presentation.SelectTab(tab); contentKey = null; Render(); },
                    presentation.Tab == i ? OverlayTheme.Selected : OverlayTheme.Panel,
                    presentation.Tab == i ? OverlayTheme.Accent : OverlayTheme.Muted);
                y += 74;
            }
            Button(sidebar, "设置", "overlay.settings", 0, y + 8, sidebar.width, 64,
                () => { VirtualKeyboard.Instance?.Clear(); presentation.SelectTab(SettingsTab); contentKey = null; Render(); },
                presentation.Tab == SettingsTab ? OverlayTheme.Selected : OverlayTheme.Panel,
                presentation.Tab == SettingsTab ? OverlayTheme.Accent : OverlayTheme.Muted);
            sidebar.EnsureBoundsCorrect();
        }

        private void RenderOverlaySettings()
        {
            float w = body.width;
            Text(body, "悬浮窗设置", 28, 4, 4, w - 8, 44);
            Text(body, "不透明度  " + Math.Round(options.Opacity * 100) + "%", 24, 4, 64, w - 8, 44);
            float half = (w - 12) / 2;
            Button(body, "−10%", "opacity.minus", 0, 124, half, 58,
                () => Run(() => SaveOpacity(options.Opacity - .1f)));
            Button(body, "＋10%", "opacity.plus", half + 12, 124, half, 58,
                () => Run(() => SaveOpacity(options.Opacity + .1f)));
            Button(body, "恢复默认透明度", "opacity.default", 0, 198, w, 58,
                () => Run(() => SaveOpacity(.94f)));
            Text(body, "即时保存；主框和侧栏同步变化。分组及统一大小仍可在 APK 中设置。", 18, 4, 278, w - 8, 88, OverlayTheme.Muted);
            Text(body, "点击窗外空白处收起。圆形入口闲置 5 秒或点击外部后，自动淡化并贴边半隐藏。", 18, 4, 378, w - 8, 88, OverlayTheme.Muted);
        }

        private static bool Contains(GComponent component, Vector2 point)
        {
            if (component == null || component.isDisposed || !component.visible) return false;
            var local = component.GlobalToLocal(point);
            return local.x >= 0 && local.y >= 0 && local.x <= component.width && local.y <= component.height;
        }
        private void OnOutsideTouch(EventContext context)
        {
            var point = context.inputEvent.position;
            if (Contains(icon, point) || (presentation.Open && (Contains(window, point) || Contains(sidePanel, point)))) return;
            if (iconDragging) return;
            if (presentation.Open) Close();
            dock.Rest(Time.realtimeSinceStartup);
            // Observe without consuming: the game keeps receiving the same touch.
        }

        private void RoundAvatar(float size)
        {
            var mask = new GGraph { name = "overlay.avatar-mask", touchable = false };
            mask.DrawRoundRect(size, size, Color.white, new[] { size / 2, size / 2, size / 2, size / 2 });
            icon.AddChild(mask);
            icon.mask = mask.displayObject;
        }
    }
}
