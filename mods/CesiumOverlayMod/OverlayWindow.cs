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
        private bool resizing;
        private int resizeTouch = -1;
        private Vector2 resizeStart;
        private float resizeWidth, resizeHeight, previewWidth, previewHeight;
        private string OptionsPath { get { return AndroidApi.GetLoaderPath("data", "toys-overlay.txt"); } }

        private bool PollOptions()
        {
            if (resizing) return false;
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
        private void SaveSize(float w, float h)
        {
            // Re-read before saving size so a concurrent APK edit does not lose its groups/opacity.
            try
            {
                var latest = File.Exists(OptionsPath) ? OverlayOptions.Parse(File.ReadAllText(OptionsPath)) : options;
                latest.Width = OverlayOptions.Clamp(w, .65f, 1.6f, 1);
                latest.Height = OverlayOptions.Clamp(h, .65f, 1.6f, 1);
                string raw = latest.Encode();
                Directory.CreateDirectory(Path.GetDirectoryName(OptionsPath));
                string temp = OptionsPath + ".game-tmp";
                File.WriteAllText(temp, raw);
                // Root is game-owned; replacing is safe and readers only see complete snapshots.
                if (File.Exists(OptionsPath)) File.Replace(temp, OptionsPath, null);
                else File.Move(temp, OptionsPath);
                options = latest; optionsRaw = raw;
            }
            catch (Exception error) { AndroidApi.ShowNotice("大小保存失败：" + error.Message); }
        }
        private void AddResizeHandle()
        {
            var handle = Button(window, "◢", "overlay.resize", presentation.FromRight ? 0 : panelWidth - 42,
                panelHeight - 42, 42, 42, () => { });
            handle.onTouchBegin.Add(context =>
            {
                if (!presentation.Open || presentation.Progress(Time.realtimeSinceStartup) < .99f) return;
                resizing = true; resizeTouch = context.inputEvent.touchId;
                resizeStart = GRoot.inst.GlobalToLocal(context.inputEvent.position);
                resizeWidth = previewWidth = panelWidth * uiScale;
                resizeHeight = previewHeight = panelHeight * uiScale;
                VirtualKeyboard.Instance?.Clear();
                context.CaptureTouch(); context.StopPropagation();
            });
            handle.onTouchMove.Add(context =>
            {
                if (!resizing || context.inputEvent.touchId != resizeTouch) return;
                var current = GRoot.inst.GlobalToLocal(context.inputEvent.position);
                var baseline = OverlayPresentation.Bounds(width, height);
                float dx = (current.x - resizeStart.x) * (presentation.FromRight ? -1 : 1);
                previewWidth = Math.Max(Math.Min(440 * uiScale, width - 24), Math.Min(width - 24,
                    Math.Max(baseline.Width * .65f, Math.Min(baseline.Width * 1.6f, resizeWidth + dx))));
                previewHeight = Math.Max(Math.Min(300 * uiScale, height - 24), Math.Min(height - 24,
                    Math.Max(baseline.Height * .65f, Math.Min(baseline.Height * 1.6f, resizeHeight + current.y - resizeStart.y))));
                context.StopPropagation();
            });
            handle.onTouchEnd.Add(context =>
            {
                if (!resizing || context.inputEvent.touchId != resizeTouch) return;
                resizing = false; resizeTouch = -1;
                var baseline = OverlayPresentation.Bounds(width, height);
                SaveSize(previewWidth / baseline.Width, previewHeight / baseline.Height);
                Build();
                context.StopPropagation();
            });
        }
        private void RenderSidebar()
        {
            var sidebar = new OverlayScrollView(94, Math.Max(1, panelHeight - 24));
            sidebar.name = "overlay.sidebar"; sidebar.SetXY(12, 12); window.AddChild(sidebar);
            Text(sidebar, "工具箱", 18, 2, 0, 90, 36, OverlayTheme.Muted, true);
            float y = 44;
            for (int i = 0; i <= options.Groups.Count + 1; i++)
            {
                int tab = i;
                string label = i == 0 ? "变速" : i == options.Groups.Count + 1 ? "其他" : options.Groups[i - 1].Name;
                Button(sidebar, label, "overlay.tab." + i, 0, y, 90, 58,
                    () => { VirtualKeyboard.Instance?.Clear(); presentation.SelectTab(tab); contentKey = null; Render(); },
                    presentation.Tab == i ? OverlayTheme.Selected : OverlayTheme.Panel,
                    presentation.Tab == i ? OverlayTheme.Accent : OverlayTheme.Muted);
                y += 66;
            }
            Button(sidebar, "默认\n大小", "overlay.reset-size", 0, y + 8, 90, 64, () => { SaveSize(1, 1); Build(); });
            Button(sidebar, "收起", "overlay.close", 0, y + 80, 90, 58, Close);
            sidebar.EnsureBoundsCorrect();
        }
    }
}
