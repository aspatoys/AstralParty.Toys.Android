// Adaptation of TenkinMori/CesiumLoader-Android-MOD's overlay: same AndroidApi action registry,
// icon, mod list and per-mod action view. FairyGUI hosts the view instead of a hot-update MonoBehaviour.
// Upstream MIT notice is included in the installed licenses directory.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using CesiumLoader.SDK.Manifests;
using FairyGUI;
using FairyGUI.Utils;
using UnityEngine;

[assembly: ModManifest("Cesium 安卓悬浮窗", "0.7.0", "TenkinMori / Android adaptation", "半屏侧边工具箱、中文分组与变速面板", Permissions = ModPermission.ReadGameState | ModPermission.UI | ModPermission.FileWrite | ModPermission.SpeedHack)]
namespace CesiumOverlayMod
{
    public static class ModEntry
    {
        public static void Main() { ModBase.Run(new OverlayController(), tag: "CesiumOverlay"); }
    }

    public sealed partial class OverlayController : ModBase
    {
        private readonly OverlayPresentation presentation = new OverlayPresentation();
        private GComponent root, window, icon, body;
        private NTexture iconTexture;
        private GTextField notice;
        private float width, height, uiScale, panelWidth, panelHeight;
        private float nextBuild, nextRefresh, nextTheme;
        private string contentKey;
        private int renderedTab = -1;
        private string renderedSelection;

        public override string Name => "Cesium 安卓悬浮窗";
        public override string Version => "0.7.0";
        public override void OnLoad() { SpeedPanel.BeginSession(); }
        public override void OnInitialize()
        {
            VirtualKeyboard.Install();
            SpeedPanel.BeginSession();
            AndroidApi.RegisterMod("CesiumOverlayMod", "工具箱信息");
            Log.Info("半屏侧边工具箱：首次打开变速页，功能类与其他类分组。");
        }

        public override void OnUpdate()
        {
            VirtualKeyboard.Instance?.Tick();
            float now = Time.realtimeSinceStartup;
            if (now >= nextTheme)
            {
                nextTheme = now + 1;
                bool changed = OverlayTheme.Poll();
                BuiltinActions.Refresh();
                if (changed && GRoot.inst != null && root != null && !root.isDisposed) Build();
            }
            if (GRoot.inst == null) return;
            if (root == null || root.isDisposed || root.parent != GRoot.inst || width != GRoot.inst.width || height != GRoot.inst.height)
            {
                if (now < nextBuild) return;
                nextBuild = now + 1;
                Build();
            }
            Animate(now);
            if (presentation.Open && now >= nextRefresh)
            {
                nextRefresh = now + .25f;
                Render();
            }
        }

        private void Build()
        {
            VirtualKeyboard.Instance?.Clear();
            float iconY = icon == null ? GRoot.inst.height * .16f : icon.y;
            if (root != null && !root.isDisposed) root.Dispose();
            contentKey = null;
            body = null;
            width = GRoot.inst.width;
            height = GRoot.inst.height;
            root = new GComponent { name = "CesiumAndroid_RepositoryOverlay", sortingOrder = 32740, opaque = false };
            root.SetSize(width, height);
            GRoot.inst.AddChild(root);

            var bounds = OverlayPresentation.Bounds(width, height);
            uiScale = Math.Min(1.5f, bounds.Width / 560f);
            panelWidth = bounds.Width / uiScale;
            panelHeight = bounds.Height / uiScale;
            window = new GComponent { name = "CesiumAndroid_Window", opaque = true };
            window.SetSize(panelWidth, panelHeight);
            window.SetScale(uiScale, uiScale);
            root.AddChild(window);
            Rounded(window, panelWidth, panelHeight, OverlayTheme.Background, 24, true);
            window.onTouchBegin.Add(BlockGestures);
            Text(window, "功能助手", 26, 24, 18, panelWidth - 100, 36);
            Text(window, "吉星工具箱", 16, 24, 54, panelWidth - 100, 24, OverlayTheme.Muted);
            Button(window, "收起", "关闭侧边工具箱", panelWidth - 84, 22, 64, 46, Close);
            notice = Text(window, "", 16, 24, panelHeight - 43, panelWidth - 48, 30, OverlayTheme.Muted);
            notice.name = "overlay.notice";

            float iconSize = Math.Max(56, Math.Min(100, Math.Min(width, height) * .09f));
            icon = new GComponent { name = "CesiumAndroid_Avatar", opaque = true, draggable = true };
            icon.SetSize(iconSize, iconSize);
            icon.SetXY(presentation.FromRight ? width - iconSize - 12 : 12,
                Mathf.Clamp(iconY, 12, Math.Max(12, height - iconSize - 12)));
            root.AddChild(icon);
            Rounded(icon, iconSize, iconSize, OverlayTheme.Panel, 20, true);
            AddAvatar(iconSize);
            icon.onTouchBegin.Add(BlockGestures);
            icon.onClick.Add(() =>
            {
                presentation.Toggle(Time.realtimeSinceStartup);
                contentKey = null;
                Render();
                Animate(Time.realtimeSinceStartup);
            });
            icon.onDragEnd.Add(() =>
            {
                presentation.FromRight = icon.x + iconSize / 2 > width / 2;
                icon.SetXY(presentation.FromRight ? width - iconSize - 12 : 12,
                    Mathf.Clamp(icon.y, 12, Math.Max(12, height - iconSize - 12)));
            });
            Render();
            Animate(Time.realtimeSinceStartup);
        }

        private void AddAvatar(float size)
        {
            try
            {
                if (iconTexture == null)
                {
                    int w, h;
                    byte[] rgba;
                    if (PngDecoder.TryDecode(File.ReadAllBytes(AndroidApi.GetLoaderPath("mods", "CesiumOverlayMod", "overlay.png")), out w, out h, out rgba))
                    {
                        for (int y = 0; y < h / 2; y++) for (int x = 0; x < w * 4; x++)
                        {
                            int a = y * w * 4 + x, b = (h - 1 - y) * w * 4 + x;
                            byte t = rgba[a]; rgba[a] = rgba[b]; rgba[b] = t;
                        }
                        var texture = new Texture2D(w, h, TextureFormat.RGBA32, false);
                        texture.LoadRawTextureData(rgba);
                        texture.Apply(false, false);
                        iconTexture = new NTexture(texture);
                    }
                }
                if (iconTexture != null)
                {
                    var image = new GLoader { touchable = false, texture = iconTexture, fill = FillType.ScaleFree };
                    image.SetSize(size, size);
                    icon.AddChild(image);
                }
                else Text(icon, "工具", 22, 4, 0, size - 8, size, null, true);
            }
            catch (Exception error)
            {
                Text(icon, "工具", 22, 4, 0, size - 8, size, null, true);
                Log.Warn("头像读取失败，使用文字入口：" + error.Message);
            }
        }

        private void Animate(float now)
        {
            if (window == null) return;
            float progress = presentation.Progress(now);
            float physicalWidth = panelWidth * uiScale;
            window.SetXY(presentation.FromRight
                ? width + 12 - (physicalWidth + 24) * progress
                : -physicalWidth - 12 + (physicalWidth + 24) * progress, 12);
            window.alpha = .72f + .28f * progress;
            window.visible = presentation.Open || progress > .001f;
            window.touchable = presentation.Open;
            icon.visible = !presentation.Open && progress <= .001f;
        }

        private void Close()
        {
            VirtualKeyboard.Instance?.Clear();
            if (presentation.Open) presentation.Toggle(Time.realtimeSinceStartup);
        }

        private void Render()
        {
            if (window == null || !presentation.Open) return;
            var all = AndroidApi.GetModIds();
            var ids = new List<string>();
            if (presentation.Tab == 1)
            {
                // User-defined order; unavailable mods are not represented by fake switches.
                foreach (string wanted in ModCatalog.Functions)
                    foreach (string id in all) if (string.Equals(id, wanted, StringComparison.OrdinalIgnoreCase)) { ids.Add(id); break; }
            }
            else if (presentation.Tab == 2)
                foreach (string id in all) if (!ModCatalog.IsFunction(id)) ids.Add(id);

            if (presentation.Tab != 0 && presentation.Selected != null && !all.Contains(presentation.Selected)) presentation.Selected = null;
            string key = presentation.Tab + "|" + presentation.Selected + "|" + LocalSpeed.SelectedSpeed + "|" + AndroidApi.Notice;
            key += MappingSignature(presentation.Selected);
            foreach (string id in ids)
            {
                key += "|" + id + "|" + AndroidApi.GetModName(id) + "|" + StateLabel(id);
                foreach (var action in AndroidApi.GetActions(id))
                {
                    key += "|" + action.Id + ":" + action.Label;
                    if (action.IsToggle) try { key += ":" + action.Get(); } catch { key += ":unavailable"; }
                }
            }
            if (key == contentKey) return;
            contentKey = key;
            bool samePage = renderedTab == presentation.Tab && renderedSelection == presentation.Selected;
            float scroll = samePage && body != null && body.scrollPane != null ? body.scrollPane.posY : 0;
            if (body != null && !body.isDisposed) body.Dispose();
            // The tabs are part of the disposable body so theme changes and selection share one render path.
            var oldTabs = window.GetChild("overlay.tabs");
            if (oldTabs != null) oldTabs.Dispose();
            var tabs = new GComponent { name = "overlay.tabs" };
            tabs.SetXY(20, 91); tabs.SetSize(panelWidth - 40, 48); window.AddChild(tabs);
            float tabWidth = (tabs.width - 8) / 3;
            string[] labels = { "变速", "功能类", "其他类" };
            for (int i = 0; i < labels.Length; i++)
            {
                int tab = i;
                Button(tabs, labels[i], "overlay.tab." + i, i * (tabWidth + 4), 0, tabWidth, 48,
                    () => { VirtualKeyboard.Instance?.Clear(); presentation.SelectTab(tab); contentKey = null; Render(); },
                    presentation.Tab == i ? OverlayTheme.Selected : OverlayTheme.Panel,
                    presentation.Tab == i ? OverlayTheme.Accent : OverlayTheme.Muted);
            }
            body = new OverlayScrollView(panelWidth - 40, Math.Max(1, panelHeight - 202));
            body.name = "overlay.content";
            body.SetXY(20, 156); window.AddChild(body);
            if (presentation.Tab == 0) RenderSpeed();
            else if (presentation.Selected != null) RenderActions(presentation.Selected);
            else RenderList(ids);
            body.EnsureBoundsCorrect();
            body.scrollPane.SetPosY(scroll, false);
            renderedTab = presentation.Tab;
            renderedSelection = presentation.Selected;
            notice.text = string.IsNullOrEmpty(AndroidApi.Notice) ? "关闭面板后继续游戏" : AndroidApi.Notice;
        }

        private void RenderSpeed()
        {
            float w = body.width;
            var card = new GComponent { name = "speed.controls" };
            card.SetSize(w, 216); body.AddChild(card);
            Rounded(card, w, 216, OverlayTheme.Panel, 18, true);
            Text(card, "当前倍速", 25, 18, 15, 148, 40);
            var value = Text(card, LocalSpeed.SelectedSpeed.ToString("0.0", CultureInfo.InvariantCulture) + "x", 30, 166, 15, w - 184, 40, OverlayTheme.SpeedValue);
            value.name = "speed.current";
            var line = new GGraph { touchable = false };
            line.DrawRect(w - 36, 1, 0, OverlayTheme.Border, OverlayTheme.Border); line.SetXY(18, 65); card.AddChild(line);
            double[] deltas = { -1, -.5, 1, 1.5 };
            string[] labels = { "-1", "-0.5", "+1", "+1.5" };
            float buttonWidth = (w - 66) / 4;
            for (int i = 0; i < deltas.Length; i++)
            {
                double delta = deltas[i];
                var button = Button(card, labels[i], "speed.delta." + labels[i], 18 + i * (buttonWidth + 10), 82, buttonWidth, 52,
                    () => Run(() => LocalSpeed.Choose(LocalSpeed.SelectedSpeed + delta)),
                    i == 3 ? OverlayTheme.SpeedPrimary : OverlayTheme.SpeedButton,
                    i == 3 ? OverlayTheme.SpeedPrimaryText : OverlayTheme.Text);
                bool limit = delta < 0 ? LocalSpeed.SelectedSpeed <= 1 : LocalSpeed.SelectedSpeed >= 10;
                button.touchable = !limit;
                button.alpha = limit ? .48f : 1;
            }
            Button(card, "禁用倍速 (1.0x)", "speed.reset", 18, 150, w - 36, 50,
                () => Run(() => LocalSpeed.Choose(1)), OverlayTheme.SpeedButton, OverlayTheme.Text);
            Text(body, "范围 1.0–10.0x · 每次启动默认 1.0x", 18, 6, 232, w - 12, 32, OverlayTheme.Muted);
            Text(body, "仅调整客户端逻辑与动画，不改变服务器计时。", 18, 6, 268, w - 12, 54, OverlayTheme.Muted);
        }

        private void RenderList(List<string> ids)
        {
            if (ids.Count == 0)
            {
                Text(body, "此分组暂无已加载模组", 23, 12, 24, body.width - 24, 64, OverlayTheme.Muted);
                return;
            }
            float y = 0;
            foreach (string item in ids)
            {
                string id = item;
                var row = Button(body, "", "mod." + id, 0, y, body.width, 90,
                    () => OpenMod(id), OverlayTheme.Panel);
                Text(row, ModCatalog.Name(id, AndroidApi.GetModName(id)), 24, 16, 10, body.width - 140, 34);
                Text(row, ModCatalog.Description(id), 17, 16, 48, body.width - 140, 30, OverlayTheme.Muted);
                var toggle = FindMainToggle(id);
                if (id == OverlayPresentation.SpeedId)
                    Text(row, LocalSpeed.SelectedSpeed.ToString("0.0", CultureInfo.InvariantCulture) + "x  ›", 23, body.width - 118, 20, 100, 48, OverlayTheme.SpeedValue, true);
                else if (toggle != null)
                {
                    bool enabled;
                    try { enabled = toggle.Get(); }
                    catch { Text(row, "暂不可用", 18, body.width - 116, 22, 102, 46, OverlayTheme.Muted, true); y += 102; continue; }
                    Button(row, enabled ? "已开启" : "已关闭", "toggle." + id, body.width - 116, 21, 102, 48,
                        () => Run(() => toggle.Set(!toggle.Get())),
                        enabled ? OverlayTheme.Selected : OverlayTheme.Background,
                        enabled ? OverlayTheme.Accent : OverlayTheme.Muted);
                }
                else Text(row, "查看  ›", 19, body.width - 116, 22, 102, 46, OverlayTheme.Muted, true);
                y += 102;
            }
        }

        private void OpenMod(string id)
        {
            VirtualKeyboard.Instance?.Clear();
            if (string.Equals(id, OverlayPresentation.SpeedId, StringComparison.OrdinalIgnoreCase)) presentation.SelectTab(0);
            else presentation.Selected = id;
            contentKey = null; Render();
        }

        private void RenderActions(string id)
        {
            Button(body, "‹ 返回列表", "actions.back", 0, 0, 150, 46, () => { VirtualKeyboard.Instance?.Clear(); presentation.Selected = null; contentKey = null; Render(); });
            Text(body, ModCatalog.Name(id, AndroidApi.GetModName(id)), 28, 4, 68, body.width - 8, 42);
            Text(body, ModCatalog.Description(id), 18, 4, 112, body.width - 8, 54, OverlayTheme.Muted);
            float y = 182;
            int count = 0;
            foreach (var item in AndroidApi.GetActions(id))
            {
                if (item.Id == "toys.status" || item.Id.StartsWith("toys.speed.", StringComparison.Ordinal)) continue;
                var action = item;
                bool value = false, available = true;
                if (action.IsToggle) try { value = action.Get(); } catch { available = false; }
                string label = item.Id == "toys.enabled" ? "功能开关" : ModCatalog.ActionName(item.Label, item.IsToggle);
                var row = Button(body, "", "action." + item.Id, 0, y, body.width, 70,
                    () => Run(() => { VirtualKeyboard.Instance?.Clear(); if (action.IsToggle) action.Set(!action.Get()); else action.Run?.Invoke(); }));
                row.touchable = available;
                Text(row, label, 23, 16, 14, body.width - 140, 42);
                Text(row, !available ? "暂不可用" : action.IsToggle ? (value ? "已开启" : "已关闭") : "执行  ›",
                    20, body.width - 116, 14, 102, 42, value ? OverlayTheme.Accent : OverlayTheme.Muted, true);
                y += 82; count++;
            }
            if (count == 0 && !CanMap(id))
            {
                Text(body, "暂无可即时调整的操作", 22, 6, y, body.width - 12, 40);
                Text(body, "如需修改此模组配置，请在工具箱 App 中设置。", 18, 6, y + 48, body.width - 12, 64, OverlayTheme.Muted);
                y += 126;
            }
            y = RenderKeyMappings(id, y);
            Text(body, "状态：" + StateLabel(id), 18, 6, y + 8, body.width - 12, 40, OverlayTheme.Muted);
        }

        private static ActionItem FindMainToggle(string id)
        {
            ActionItem fallback = null;
            foreach (var action in AndroidApi.GetActions(id))
                if (action.IsToggle)
                {
                    if (action.Id == "toys.enabled" || action.Id == "toys.hud.hidden") return action;
                    if (fallback == null && (action.Id == "enabled" || action.Id == "enable")) fallback = action;
                }
            return fallback;
        }

        private static string StateLabel(string id)
        {
            var current = ModRegistry.Get(id);
            if (current == null) return "未加载";
            switch (current.State.ToString().ToLowerInvariant())
            {
                case "initialized": case "enabled": return "运行中";
                case "loaded": case "initializing": return "加载中";
                case "failed": case "faulted": return "加载失败";
                default: return "未运行";
            }
        }

        private void Run(Action action)
        {
            try { action(); }
            catch (Exception error)
            {
                AndroidApi.LogError(error.ToString());
                AndroidApi.ShowNotice(ModCatalog.HasChinese(error.Message) ? error.Message : "操作失败，请检查模组日志");
            }
            contentKey = null; Render();
        }

        private static void BlockGestures(EventContext context) { context.StopPropagation(); }

        private static GGraph Rounded(GComponent parent, float w, float h, Color color, float radius, bool border = false)
        {
            var graph = new GGraph { touchable = false };
            graph.DrawRoundRect(w, h, border ? OverlayTheme.Border : color, new[] { radius, radius, radius, radius });
            parent.AddChild(graph);
            if (border)
            {
                var fill = new GGraph { touchable = false };
                fill.DrawRoundRect(w - 2, h - 2, color, new[] { radius - 1, radius - 1, radius - 1, radius - 1 });
                fill.SetXY(1, 1); parent.AddChild(fill);
            }
            return graph;
        }

        private static GTextField Text(GComponent parent, string value, int size, float x, float y, float w, float h, Color? color = null, bool center = false)
        {
            var text = new GTextField { touchable = false, text = value, autoSize = AutoSizeType.Shrink,
                verticalAlign = VertAlignType.Middle, align = center ? AlignType.Center : AlignType.Left };
            var format = text.textFormat; format.size = size; format.color = color ?? OverlayTheme.Text; text.textFormat = format;
            text.SetSize(w, h); text.SetXY(x, y); parent.AddChild(text); return text;
        }

        private static GComponent Button(GComponent parent, string label, string name, float x, float y, float w, float h, Action action, Color? background = null, Color? foreground = null)
        {
            var button = new GComponent { name = name, opaque = true };
            button.SetXY(x, y); button.SetSize(w, h); parent.AddChild(button);
            Rounded(button, w, h, background ?? OverlayTheme.Panel, 12);
            if (!string.IsNullOrEmpty(label)) Text(button, label, 23, 8, 0, w - 16, h, foreground, true);
            // Let touch-begin bubble to the native ScrollPane. It cancels clicks after a swipe.
            button.onTouchBegin.Add(() => { if (!button.isDisposed) button.alpha = .72f; });
            button.onTouchEnd.Add(() => { if (!button.isDisposed) button.alpha = 1; });
            button.onClick.Add((EventContext context) => { context.StopPropagation(); action(); });
            return button;
        }

        public override void OnLateUpdate() { LocalSpeed.Tick(); }
        public override void OnUnload()
        {
            VirtualKeyboard.Uninstall();
            LocalSpeed.Restore();
            HudVisibility.Restore();
            if (root != null && !root.isDisposed) root.Dispose();
            iconTexture?.Dispose(); iconTexture = null;
        }
    }

    /// <summary>Native inertial vertical scrolling, clipped to the panel; no full-screen gesture catcher.</summary>
    internal sealed class OverlayScrollView : GComponent
    {
        internal OverlayScrollView(float w, float h)
        {
            SetSize(w, h);
            // FairyGUI's scroll setup record: type, scrollbar policy, flags, optional margins,
            // and four null resource strings. Touch enabled, bounce disabled, inertia enabled.
            byte[] data = { (byte)ScrollType.Vertical, (byte)ScrollBarDisplayType.Hidden,
                0, 0, 0, 0x90, 0, 0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe, 0xff, 0xfe };
            SetupScroll(new ByteBuffer(data));
            scrollPane.mouseWheelEnabled = true;
        }
    }
}
