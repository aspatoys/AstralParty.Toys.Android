// Options polling plus the four page bodies. The panel is rebuilt into `body` whenever the
// visible content would change; the shell (blob/panel/nav) is untouched while it scrolls.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    public sealed partial class OverlayController
    {
        private OverlayOptions options = OverlayOptions.Defaults();
        private string optionsRaw;

        /// <summary>Hold this long on a config row to jump into that mod's detail page.</summary>
        private const float LongPressSeconds = .45f;
        private const int ConsoleLines = 40;

        private string longPressRow;
        private int longPressTouch = -1;
        private float longPressAt;
        private Vector2 longPressStart;
        private bool longPressFired;

        internal OverlayOptions Options => options;

        private string OptionsPath => AndroidApi.GetLoaderPath("data", "toys-overlay.txt");

        private bool PollOptions()
        {
            try
            {
                string path = OptionsPath;
                string raw = File.Exists(path) ? File.ReadAllText(path) : string.Empty;
                if (raw == optionsRaw) return false;
                optionsRaw = raw;
                if (string.IsNullOrWhiteSpace(raw)) return false;
                options = OverlayOptions.Parse(raw);
                if (page >= OverlayPresentation.Pages.Length) page = 0;
                return true;
            }
            catch (Exception error)
            {
                AndroidApi.ShowNotice("悬浮窗设置读取失败，保留当前设置：" + error.Message);
                return false;
            }
        }

        private void SaveSize(float w, float h) => Save(current =>
        {
            current.Width = OverlayOptions.Clamp(w, .65f, 1.6f, 1f);
            current.Height = OverlayOptions.Clamp(h, .65f, 1.6f, 1f);
        });

        private void SaveOpacity(float value) => Save(current =>
            current.Opacity = OverlayOptions.Clamp(value, .25f, 1f, .5f));

        private void SaveFontScale(float value) => Save(current =>
            current.FontScale = OverlayOptions.Clamp(value, .8f, 1.4f, 1f));

        /// <summary>Idle seconds before the blob dims; 0 disables the fade entirely.</summary>
        private void SaveIdleSeconds(float value) => Save(current =>
            current.IdleSeconds = OverlayOptions.Clamp(value, 0f, 60f, 5f));

        private void SaveIdleOpacity(float value) => Save(current =>
            current.IdleOpacity = OverlayOptions.Clamp(value, .15f, 1f, .35f));

        private void SavePreviousSpeed(float value) => Save(current =>
            current.PreviousSpeed = OverlayOptions.Clamp(value, 1f, 10f, 1f));

        /// <summary>
        /// Applies a speed, then remembers the last successful non-default multiplier.
        /// Choosing 1.0x keeps the shared session memory for the resume action.
        /// </summary>
        private void SetSpeed(double target)
        {
            // Go through the shared panel entry so the loader-side speed state stays in sync.
            SpeedPanel.Choose(target);
            float remember = (float)SpeedPanel.SavedSpeed;
            if (remember > 1.0001f && Mathf.Abs(Options.PreviousSpeed - remember) > .0001f)
                SavePreviousSpeed(remember);
        }

        /// <summary>Restores original speed, or resumes the latest successful multiplier.</summary>
        private void RestoreSpeed()
        {
            if (LocalSpeed.SelectedSpeed > 1.0001) { SetSpeed(1); return; }
            float target = SpeedPanel.SavedSpeed > 1.0001 ? (float)SpeedPanel.SavedSpeed
                : Mathf.Clamp(Options.PreviousSpeed, 1f, 10f);
            if (target <= 1.0001f) { AndroidApi.ShowNotice("还没有可恢复的倍速"); return; }
            SetSpeed(target);
        }

        /// <summary>Remembers where the bar sits so reopening does not jump back to the default.</summary>
        private void SaveCenter(float centre)
        {
            if (height <= 0f) return;
            float fraction = Mathf.Clamp01(centre / height);
            Save(current => current.Center = OverlayOptions.Clamp(fraction, 0f, 1f, -1f));
        }

        /// <summary>
        /// Merges one change into the newest on-disk options and writes it back atomically.
        /// Re-reading first means a concurrent APK save (groups, opacity) is never clobbered.
        /// </summary>
        private void Save(Action<OverlayOptions> mutate)
        {
            try
            {
                var latest = OverlayOptions.Defaults();
                string path = OptionsPath;
                if (File.Exists(path)) latest = OverlayOptions.Parse(File.ReadAllText(path));
                mutate(latest);
                string encoded = latest.Encode();
                Directory.CreateDirectory(Path.GetDirectoryName(path) ?? ".");
                string temp = path + ".game-tmp";
                File.WriteAllText(temp, encoded);
                if (File.Exists(path)) File.Replace(temp, path, null);
                else File.Move(temp, path);
                optionsRaw = encoded;
                options = latest;
            }
            catch (Exception error)
            {
                AndroidApi.ShowNotice("悬浮窗设置保存失败：" + error.Message);
            }
        }

        // ---- page bodies ---------------------------------------------------------------
        /// <summary>
        /// True only while the page body hangs under a live panel. Build() disposes the whole tree,
        /// which leaves [body] pointing at a disposed component whose display object is already gone;
        /// touching visible on that throws from inside FairyGUI.
        /// </summary>
        private bool BodyAlive => body != null && !body.isDisposed && body.parent != null;

        private void Render()
        {
            if (panel == null || panel.isDisposed || mode != ShellMode.Expanded) return;
            string key = BuildContentKey();
            if (key == contentKey) { if (BodyAlive) body.visible = true; return; }
            // Rebuilding the body destroys every child, including whatever button the finger is
            // on, which reads as the text jumping and swallows the tap. Wait for the finger, unless
            // the caller is a long press that must open its page while the hold is still down.
            // Keep this ahead of the hide below: hiding first blanked the whole page under the
            // finger for a frame and made a tapped row look like it jumped upwards.
            if (FingerDown && !forceRender) { renderPending = true; if (BodyAlive) body.visible = true; return; }
            if (BodyAlive) body.visible = false;
            contentKey = key;

            float scroll = 0f;
            if (BodyAlive)
            {
                if (body.scrollPane != null) scroll = body.scrollPane.posY;
                body.Dispose();
            }
            body = null;
            // Reserve a bottom margin: the scroll view used to end 4 px above the panel edge, so
            // the last row was clipped by the panel's rounded corner. Keep 24 design units of air.
            body = new OverlayScrollView(Mathf.Max(1f, panel.width - Px(32)),
                Mathf.Max(1f, panel.height - Px(60) - Px(24)));
            body.name = "overlay.body";
            body.SetXY(Px(16), Px(60));
            panel.AddChild(body);
            // Restore the offset before the page fills the view. A scroll pane clamps a position to
            // the content size it currently knows, so applying it to an empty body threw the value
            // away and the list snapped back to the top.
            if (scroll > 0f && body.scrollPane != null) body.scrollPane.SetPosY(scroll, false);

            switch (OverlayPresentation.Pages[page].Key)
            {
                case "apps":
                    if (openModPage >= 0 && openMod != null) RenderActions(openMod);
                    else RenderApps();
                    break;
                case "speed": RenderSpeed(); break;
                case "config": RenderConfig(); break;
                case "settings": RenderSettings(); break;
                case "console": RenderConsole(); break;
                case "info": RenderInfo(); break;
            }

            body.EnsureBoundsCorrect();
            if (body.scrollPane != null) body.scrollPane.SetPosY(scroll, false);
        }

        private string BuildContentKey()
        {
            var ids = AndroidApi.GetModIds();
            string key = page + "|" + openMod + "|" + LocalSpeed.SelectedSpeed.ToString("F1", CultureInfo.InvariantCulture)
                + "|" + (OverlayTheme.Dark ? 1 : 0)
                + "|" + options.Groups.Count + "|" + string.Join(",", options.Groups.ConvertAll(g => g.Name).ToArray())
                + "|" + options.Opacity.ToString("F2", CultureInfo.InvariantCulture)
                + "|" + options.Width.ToString("F2", CultureInfo.InvariantCulture)
                + "|" + options.Height.ToString("F2", CultureInfo.InvariantCulture)
                + "|" + options.FontScale.ToString("F2", CultureInfo.InvariantCulture)
                + "|" + options.PreviousSpeed.ToString("F2", CultureInfo.InvariantCulture)
                + "|" + options.IdleSeconds.ToString("F1", CultureInfo.InvariantCulture)
                + "|" + options.IdleOpacity.ToString("F2", CultureInfo.InvariantCulture);
            // Both the config page and the apps page print key chords, so a rebind or a held key
            // has to invalidate the cached body on either page.
            if (openMod != null && (page == OverlayPresentation.ConfigPage || page == OverlayPresentation.AppsPage))
                key += MappingSignature(openMod);
            if (page == OverlayPresentation.AppsPage)
            {
                foreach (var entry in MappedMods()) key += "|map:" + entry.Key + "=" + entry.Value;
                // Developer-registered shortcuts are free to change their own detail text or
                // switch state between frames, so fold both into the cache key.
                foreach (var shortcut in AndroidApi.Shortcuts)
                {
                    bool state = false;
                    if (shortcut.IsToggle) { try { state = shortcut.Get(); } catch (Exception) { state = false; } }
                    key += "|sc:" + shortcut.ModId + "/" + shortcut.Id + "=" + shortcut.Label + ":" + (state ? 1 : 0) + ":" + shortcut.Detail;
                }
            }
            foreach (var id in ids)
                key += "|" + id + ":" + StateLabel(id) + ":" + AndroidApi.GetModName(id);
            foreach (var id in ids)
                foreach (var action in AndroidApi.GetActions(id))
                    key += "|" + action.Id + ":" + action.Label + ":" + (action.Get == null ? "a" : action.Get() ? "1" : "0");
            return key;
        }

        /// <summary>The dedicated speed page; the panel is now tall enough to spread it out.</summary>
        private void RenderSpeed()
        {
            float w = body.width;
            float y = 0f;

            Text(body, "当前倍速", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(28);
            var card = new GComponent { opaque = true };
            card.SetXY(0, y); card.SetSize(w, Px(132)); body.AddChild(card);
            RoundRect(card, w, Px(132), OverlayTheme.SurfaceContainerHigh, Px(18));
            var speedValue = Text(card, LocalSpeed.SelectedSpeed.ToString("0.0", CultureInfo.InvariantCulture) + "x",
                Font(44), 0, Px(14), w, Px(64), OverlayTheme.SpeedValue, true);
            speedValue.name = "speed.current";
            Text(card, "1.0x – 10.0x", Font(12), 0, Px(80), w, Px(22), OverlayTheme.OnSurfaceVariant, true);
            y += Px(142);

            double[] steps = { -.5, +.5 };
            float stepGap = Px(10);
            float stepWidth = (w - stepGap) / 2f;
            for (int i = 0; i < steps.Length; i++)
            {
                double delta = steps[i];
                bool enabled = LocalSpeed.SelectedSpeed + delta >= 1 && LocalSpeed.SelectedSpeed + delta <= 10;
                var button = Button(body, delta > 0 ? "+0.5x" : "-0.5x", "speed.step." + i,
                    i * (stepWidth + stepGap), y, stepWidth, Px(48),
                    () => Run(() => SetSpeed(LocalSpeed.SelectedSpeed + delta)),
                    OverlayTheme.SpeedButton, OverlayTheme.OnSurface);
                if (!enabled) { button.touchable = false; button.alpha = .48f; }
            }
            y += Px(58);

            // Preset stops read faster than repeated stepping on a small panel.
            float[] presets = { 1f, 1.5f, 2f, 3f, 5f };
            float presetGap = Px(6);
            float presetWidth = (w - presetGap * (presets.Length - 1)) / presets.Length;
            for (int i = 0; i < presets.Length; i++)
            {
                float target = presets[i];
                bool active = Mathf.Abs((float)LocalSpeed.SelectedSpeed - target) < .01f;
                Button(body, target.ToString("0.#", CultureInfo.InvariantCulture) + "x", "speed.preset." + i,
                    i * (presetWidth + presetGap), y, presetWidth, Px(40),
                    () => Run(() => SetSpeed(target)),
                    active ? OverlayTheme.SpeedPrimary : OverlayTheme.SurfaceContainerHigh,
                    active ? OverlayTheme.SpeedPrimaryText : OverlayTheme.OnSurface);
            }
            y += Px(56);

            // The label describes the next action; active acceleration must remain clickable.
            float remembered = SpeedPanel.SavedSpeed > 1.0001 ? (float)SpeedPanel.SavedSpeed
                : Mathf.Clamp(Options.PreviousSpeed, 1f, 10f);
            bool accelerated = LocalSpeed.SelectedSpeed > 1.0001;
            bool canRestore = accelerated || remembered > 1.0001f;
            string restoreLabel = accelerated ? "恢复原速（1.0x）" : "启用原变速" +
                (remembered > 1.0001f ? "（" + remembered.ToString("0.#", CultureInfo.InvariantCulture) + "x）" : "");
            var restore = Button(body, restoreLabel,
                "speed.restore", 0, y, w, Px(44),
                () => Run(RestoreSpeed),
                canRestore ? OverlayTheme.Primary : OverlayTheme.SurfaceContainerHigh,
                canRestore ? OverlayTheme.OnPrimary : OverlayTheme.OnSurfaceVariant);
            restore.touchable = canRestore;
            if (!canRestore) restore.alpha = .55f;
            y += Px(56);

            Text(body, "变速会改写 Time.timeScale，游戏内动画会同步变化；收起悬浮窗后仍保持。",
                Font(12), 0, y, w, Px(40), OverlayTheme.OnSurfaceVariant); y += Px(46);
            Text(body, "初始为 1.0x，退出游戏后恢复。", Font(12), 0, y, w, Px(24), OverlayTheme.OnSurfaceVariant);
        }

        /// <summary>
        /// Mod configuration: every row is plain navigation into that mod's own options. The
        /// on/off switch lives on the 功能 page so this list never has two jobs at once.
        /// </summary>
        private void RenderConfig()
        {
            float w = body.width;
            float y = 0f;

            if (openModPage >= 0 && openMod != null) { RenderActions(openMod); return; }

            var ids = AllMods();
            if (ids.Count == 0)
            {
                // Two different empty states: no mods at all, or every mod is one this list has
                // nothing to say about. Telling them apart saves the reader a hunt for a bug.
                string notice = AndroidApi.GetModIds().Count <= 1
                    ? "尚未加载任何模组。"
                    : "没有可调的模组。只有带选项或按键映射的模组会列在这里。";
                Text(body, notice, Font(13), 0, y, w, Px(60), OverlayTheme.OnSurfaceVariant);
                return;
            }

            Text(body, "点按进入该模组的选项与按键映射", Font(12), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(30);
            foreach (var id in ids) y = RenderConfigRow(id, y);
        }

        /// <summary>
        /// Every loaded mod that has something behind its row, grouped or not — this page ignores
        /// group membership. A mod whose detail page would only say 没有可调选项 is left out: most
        /// mods register nothing but the loader's own status/total-switch actions, which the detail
        /// page hides, so listing them all produced a column of rows that led nowhere.
        /// </summary>
        private List<string> AllMods()
        {
            var result = new List<string>();
            foreach (var id in AndroidApi.GetModIds())
            {
                if (string.Equals(id, "CesiumOverlayMod", StringComparison.OrdinalIgnoreCase)) continue;
                if (HasOptions(id)) result.Add(id);
            }
            return result;
        }

        /// <summary>
        /// Whether the detail page of [id] has anything to show: an option of its own, or a key
        /// binding. Keep this in step with RenderActions/RenderKeyMappings — it is the same filter
        /// they apply, only asked ahead of time.
        /// </summary>
        private bool HasOptions(string id)
        {
            // The loader gives every mod a status action and a master switch, and the detail page
            // hides both (the switch is the row's own tap target). Counting them here made the test
            // true for every mod, so the filter never removed anything.
            var master = FindMainToggle(id);
            foreach (var action in AndroidApi.GetActions(id))
            {
                if (action.Id == "toys.status" || action.Id.StartsWith("toys.speed.")) continue;
                if (ReferenceEquals(action, master)) continue;
                return true;
            }
            foreach (var binding in KeyBindings(id))
                if (!string.IsNullOrWhiteSpace(binding.Keys)) return true;
            return false;
        }

        private float RenderConfigRow(string id, float y)
        {
            float w = body.width;
            var row = new GComponent { opaque = true };
            row.SetXY(0, y); row.SetSize(w, Px(58)); body.AddChild(row);
            // Always the neutral dark plate: this list must not imply an on/off state it no longer owns.
            RoundRect(row, w, Px(58), OverlayTheme.SurfaceContainerHigh, Px(14));
            var label = Text(row, ModCatalog.Name(id, AndroidApi.GetModName(id)), Font(15), Px(14), Px(4),
                w - Px(60), Px(28), OverlayTheme.OnSurface);
            label.verticalAlign = VertAlignType.Top;
            Text(row, StateLabel(id), Font(11), Px(14), Px(30), w - Px(60), Px(20), OverlayTheme.OnSurfaceVariant);
            Text(row, "›", Font(16), w - Px(40), Px(14), Px(32), Px(30), OverlayTheme.OnSurfaceVariant, true);

            row.onClick.Add((EventContext context) => { context.StopPropagation(); OpenMod(id); });
            return y + Px(64);
        }

        /// <summary>
        /// Fires the long press as soon as the hold threshold passes, so the detail page opens
        /// without the user having to lift their finger first. Any vertical travel past the slop
        /// means the player is scrolling the list, so the press is abandoned.
        /// </summary>
        private void TickLongPress()
        {
            if (longPressRow == null || longPressFired) return;
            // A scroll gesture can take the touch away from the row without ever firing its
            // touch-end, so a stale hold is dropped as soon as nothing is down any more.
            int down = 0;
            Vector2 at = longPressStart;
            try
            {
                down = Stage.inst != null ? Stage.inst.touchCount : 0;
                if (down > 0) at = Stage.inst.touchPosition;
            }
            catch (Exception) { down = 0; }
            if (down == 0 || Vector2.Distance(at, longPressStart) > Px(12))
            {
                longPressRow = null; longPressTouch = -1; return;
            }
            if (Time.realtimeSinceStartup - longPressAt < LongPressSeconds) return;
            longPressFired = true;
            string id = longPressRow;
            longPressRow = null; longPressTouch = -1;
            // The player is still holding the screen, so the usual "wait for the finger" guard
            // would swallow this render and the page would only appear after release.
            forceRender = true;
            OpenMod(id);
            forceRender = false;
        }

        /// <summary>Tail of the mod log, with one tap exporting it next to the loader data folder.</summary>
        private void RenderConsole()
        {
            float w = body.width;
            float y = 0f;

            Button(body, "导出日志", "console.export", 0, y, w, Px(44),
                () => Run(ExportLog), OverlayTheme.Primary, OverlayTheme.OnPrimary);
            y += Px(52);

            var lines = ReadLogTail(ConsoleLines);
            if (lines.Count == 0)
            {
                Text(body, "暂无日志。", Font(12), 0, y, w, Px(26), OverlayTheme.OnSurfaceVariant);
                return;
            }
            Text(body, "最近 " + lines.Count + " 行", Font(12), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(28);
            foreach (var line in lines)
            {
                var text = Text(body, line, Font(10), 0, y, w, Px(18), OverlayTheme.OnSurfaceVariant);
                text.textFormat.color = OverlayTheme.OnSurfaceVariant;
                y += Px(20);
            }
        }

        private void RenderApps()
        {
            float w = body.width;
            float y = 0f;

            // Mods can publish their own shortcuts for this page. They are hand-written by the
            // mod author, so they lead: these are the switches people flip mid-match.
            var shortcuts = AndroidApi.Shortcuts;
            var published = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            if (shortcuts.Count > 0)
            {
                Text(body, "快捷开关", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
                for (int i = 0; i < shortcuts.Count; i++)
                {
                    published.Add(shortcuts[i].ModId);
                    y = RenderShortcutRow(shortcuts[i], y);
                }
                y += Px(10);
            }

            // Everything else with a Win key binding: without a hardware keyboard those chords
            // are the only way to drive such a mod. A mod that already published a shortcut is
            // skipped, otherwise both sections would show the same mod twice.
            var mapped = MappedMods();
            var rows = new List<KeyValuePair<string, string>>();
            foreach (var entry in mapped)
                if (!published.Contains(entry.Key)) rows.Add(entry);
            if (rows.Count > 0)
            {
                Text(body, "按键映射", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
                foreach (var entry in rows) y = RenderModRow(entry.Key, y, entry.Value);
            }

            if (y <= 0f)
            {
                Text(body, "暂无可用快捷项。带快捷键的模组和已注册快捷功能的模组会出现在这里。",
                    Font(13), 0, 0, w, Px(60), OverlayTheme.OnSurfaceVariant);
            }
        }

        /// <summary>
        /// One developer-registered shortcut. Toggles tint the row and flip on tap; plain buttons
        /// act as a single press. Both may carry a detail line describing the current state.
        /// </summary>
        private float RenderShortcutRow(CesiumAndroid.ShortcutItem item, float y)
        {
            float w = body.width;
            bool on = false;
            if (item.IsToggle)
            {
                try { on = item.Get(); } catch (Exception) { on = false; }
            }

            var row = new GComponent { opaque = true };
            row.SetXY(0, y); row.SetSize(w, Px(58)); body.AddChild(row);
            RoundRect(row, w, Px(58),
                on ? OverlayTheme.PrimaryContainer : OverlayTheme.SurfaceContainerHigh, Px(14));
            var name = Text(row, item.Label, Font(15), Px(14), Px(4), w - Px(92), Px(28),
                on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurface);
            name.verticalAlign = VertAlignType.Top;
            string detail = string.IsNullOrEmpty(item.Detail) ? ModCatalog.Name(item.ModId, AndroidApi.GetModName(item.ModId)) : item.Detail;
            Text(row, detail, Font(11), Px(14), Px(30), w - Px(92), Px(20),
                on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant);
            if (item.IsToggle)
                Text(row, on ? "开" : "关", Font(12), w - Px(56), Px(18), Px(42), Px(22),
                    on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant, true);

            row.onClick.Add((EventContext context) =>
            {
                context.StopPropagation();
                Run(() => { if (item.Run != null) item.Run(); });
            });
            return y + Px(64);
        }

        /// <summary>
        /// One mod as a full-width switch row: the whole row takes the accent colour while the mod
        /// is on, so the state is readable without hunting for a small knob. A tap toggles it and a
        /// long press opens that mod's own options. [chords], when given, is printed under the name
        /// so the row doubles as a shortcut reminder.
        /// </summary>
        private float RenderModRow(string id, float y, string chords = null)
        {
            float w = body.width;
            var toggle = FindMainToggle(id);
            bool on = toggle != null && SafeToggleValue(toggle);

            var row = new GComponent { opaque = true };
            row.SetXY(0, y); row.SetSize(w, Px(58)); body.AddChild(row);
            RoundRect(row, w, Px(58),
                on ? OverlayTheme.PrimaryContainer : OverlayTheme.SurfaceContainerHigh, Px(14));
            var name = Text(row, ModCatalog.Name(id, AndroidApi.GetModName(id)), Font(15), Px(14), Px(4),
                w - Px(92), Px(28), on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurface);
            name.verticalAlign = VertAlignType.Top;
            string detail = string.IsNullOrEmpty(chords) ? StateLabel(id) : StateLabel(id) + " · 按键 " + chords;
            Text(row, detail, Font(11), Px(14), Px(30), w - Px(92), Px(20),
                on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant);
            Text(row, on ? "开" : "关", Font(12), w - Px(56), Px(18), Px(42), Px(22),
                on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant, true);

            if (toggle != null)
            {
                // Tap toggles, hold opens the detail page. The press is resolved on release so a
                // captured touch (which never becomes a click) still toggles exactly once.
                row.onTouchBegin.Add((EventContext context) =>
                {
                    longPressRow = id;
                    longPressTouch = context.inputEvent.touchId;
                    longPressAt = Time.realtimeSinceStartup;
                    longPressStart = context.inputEvent.position;
                    longPressFired = false;
                });
                row.onTouchEnd.Add((EventContext context) =>
                {
                    if (context.inputEvent.touchId != longPressTouch) return;
                    bool fired = longPressFired;
                    longPressRow = null; longPressTouch = -1; longPressFired = false;
                    context.StopPropagation();
                    if (fired) return;
                    Run(() => SetToggle(toggle, !SafeToggleValue(toggle)));
                });
            }
            else
            {
                // No master switch to flip, so the row has to stand in for its hotkey instead of
                // opening a detail page nobody asked for: tapping fires the first chord, holding
                // opens the detail page where the remaining chords and options live.
                string chord = chords != null && chords.IndexOf('/') >= 0
                    ? chords.Substring(0, chords.IndexOf('/')).Trim()
                    : chords;
                row.onTouchBegin.Add((EventContext context) =>
                {
                    longPressRow = id;
                    longPressTouch = context.inputEvent.touchId;
                    longPressAt = Time.realtimeSinceStartup;
                    longPressStart = context.inputEvent.position;
                    longPressFired = false;
                });
                row.onTouchEnd.Add((EventContext context) =>
                {
                    if (context.inputEvent.touchId != longPressTouch) return;
                    bool fired = longPressFired;
                    longPressRow = null; longPressTouch = -1; longPressFired = false;
                    context.StopPropagation();
                    if (fired) return;
                    if (string.IsNullOrEmpty(chord)) { OpenMod(id); return; }
                    string source = id + ":row:tap";
                    Run(() => SendKey(id, source, chord, false));
                });
            }
            return y + Px(64);
        }

        private void OpenMod(string id)
        {
            if (string.Equals(id, OverlayPresentation.SpeedId, StringComparison.OrdinalIgnoreCase))
            {
                SelectPage(OverlayPresentation.SpeedPage);
                return;
            }
            openMod = id;
            openModPage = page;
            contentKey = null;
            Render();
        }

        private void RenderActions(string id)
        {
            float w = body.width;
            float y = 0f;
            Button(body, "‹ 返回", "actions.back", 0, y, Px(72), Px(30), () =>
            {
                // Clear the detail page before choosing a page: SelectPage() bails out when the
                // index is unchanged, so calling it while [openMod] was still set left the player
                // on the detail page and the button looked dead.
                int back = openModPage >= 0 ? openModPage : page;
                openMod = null; openModPage = -1;
                page = back;
                AndroidApi.ShowNotice(string.Empty);
                PaintChrome();
                contentKey = null;
                Render();
            }, OverlayTheme.SurfaceContainerHigh, OverlayTheme.Primary);
            y += Px(38);
            Text(body, ModCatalog.Name(id, AndroidApi.GetModName(id)), Font(20), 0, y, w, Px(32), OverlayTheme.OnSurface); y += Px(34);
            Text(body, ModCatalog.Description(id), Font(12), 0, y, w, Px(36), OverlayTheme.OnSurfaceVariant); y += Px(42);

            // The master switch lives on the row the user just held: tapping that row already turns
            // the mod on and off, so repeating it here would offer two controls for one state.
            var master = FindMainToggle(id);

            bool any = false;
            foreach (var action in AndroidApi.GetActions(id))
            {
                if (action.Id == "toys.status" || action.Id.StartsWith("toys.speed.")) continue;
                if (ReferenceEquals(action, master)) continue;
                any = true;
                var item = action;
                if (item.IsToggle)
                {
                    bool on = SafeToggleValue(item);
                    var row = new GComponent { opaque = true };
                    row.SetXY(0, y); row.SetSize(w, Px(48)); body.AddChild(row);
                    RoundRect(row, w, Px(48),
                        on ? OverlayTheme.PrimaryContainer : OverlayTheme.SurfaceContainerHigh, Px(12));
                    Text(row, ModCatalog.ActionName(item.Label, true), Font(14), Px(12), 0, w - Px(76), Px(48),
                        on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurface).verticalAlign = VertAlignType.Middle;
                    Text(row, on ? "开" : "关", Font(12), w - Px(56), Px(13), Px(42), Px(22),
                        on ? OverlayTheme.OnPrimaryContainer : OverlayTheme.OnSurfaceVariant, true);
                    row.onClick.Add((EventContext context) =>
                    {
                        context.StopPropagation();
                        Run(() => SetToggle(item, !SafeToggleValue(item)));
                    });
                }
                else
                {
                    Button(body, ModCatalog.ActionName(item.Label, false), "action." + item.Id, 0, y, w, Px(40),
                        () => Run(() => item.Run?.Invoke()), OverlayTheme.SurfaceContainerHigh, OverlayTheme.OnSurface);
                }
                y += Px(52);
            }
            // Advance y in both branches: leaving it untouched drew the next section's heading on
            // top of this note, which read as two strings printed over each other.
            if (!any)
            {
                Text(body, "此模组没有可调选项。", Font(12), 0, y, w, Px(30), OverlayTheme.OnSurfaceVariant);
                y += Px(34);
            }
            else y += Px(4);
            y = RenderKeyMappings(id, y);
            Text(body, "状态：" + StateLabel(id), Font(12), 0, y, w, Px(30), OverlayTheme.OnSurfaceVariant);
        }

        private void RenderInfo()
        {
            float w = body.width;
            float y = 0f;
            Text(body, "Cesium Android FloatingWindow", Font(20), 0, y, w, Px(32), OverlayTheme.OnSurface); y += Px(36);
            Text(body, "悬浮窗 " + Version, Font(13), 0, y, w, Px(24), OverlayTheme.OnSurfaceVariant); y += Px(30);
            Text(body, "已加载模组：" + AndroidApi.GetModIds().Count + " 个", Font(12), 0, y, w, Px(26), OverlayTheme.OnSurfaceVariant);
        }

        /// <summary>Appearance/size controls, all persisted through the shared options file.</summary>
        private void RenderSettings()
        {
            float w = body.width;
            float y = 0f;

            // No width/height controls here any more: the panel is auto-sized to the six-page
            // rail, and offering a manual scale only produced rows that no longer fit.
            Text(body, "不透明度", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
            // The APK exposes the full range; the in-game stepper only needs a few useful stops.
            float[] opacities = { .5f, .7f, .85f, 1f };
            float gap = Px(6);
            float cell = (w - gap * 3) / 4f;
            for (int i = 0; i < opacities.Length; i++)
            {
                float value = opacities[i];
                bool active = Mathf.Abs(Options.Opacity - value) < .02f;
                Button(body, Mathf.RoundToInt(value * 100f) + "%", "settings.opacity." + i,
                    i * (cell + gap), y, cell, Px(38),
                    () => Run(() => { SaveOpacity(value); }),
                    active ? OverlayTheme.Primary : OverlayTheme.SurfaceContainerHigh,
                    active ? OverlayTheme.OnPrimary : OverlayTheme.OnSurface);
            }
            y += Px(46);

            Text(body, "字体大小", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
            float[] fonts = { .85f, 1f, 1.15f, 1.3f };
            float fontGap = Px(6);
            float fontCell = (w - fontGap * 3) / 4f;
            for (int i = 0; i < fonts.Length; i++)
            {
                float value = fonts[i];
                bool active = Mathf.Abs(Options.FontScale - value) < .03f;
                Button(body, Mathf.RoundToInt(value * 100f) + "%", "settings.font." + i,
                    i * (fontCell + fontGap), y, fontCell, Px(38),
                    // Font scale only sizes text inside the panel, so repainting the page is enough.
                    // Asking for a shell rebuild here stalled the frame and made the blob flash.
                    () => Run(() => SaveFontScale(value)),
                    active ? OverlayTheme.Primary : OverlayTheme.SurfaceContainerHigh,
                    active ? OverlayTheme.OnPrimary : OverlayTheme.OnSurface);
            }
            y += Px(46);

            // Vanishing timer for the floating blob: it dims instead of staying on top of the board.
            Text(body, "闲置淡出", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
            float[] idleWaits = { 0f, 3f, 5f, 10f };
            string[] idleLabels = { "不淡出", "3 秒", "5 秒", "10 秒" };
            float idleGap = Px(6);
            float idleCell = (w - idleGap * 3) / 4f;
            for (int i = 0; i < idleWaits.Length; i++)
            {
                float value = idleWaits[i];
                bool active = Mathf.Abs(Options.IdleSeconds - value) < .01f;
                Button(body, idleLabels[i], "settings.idle." + i,
                    i * (idleCell + idleGap), y, idleCell, Px(38),
                    () => Run(() => { SaveIdleSeconds(value); }),
                    active ? OverlayTheme.Primary : OverlayTheme.SurfaceContainerHigh,
                    active ? OverlayTheme.OnPrimary : OverlayTheme.OnSurface);
            }
            y += Px(46);

            Text(body, "闲置后的浓度", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
            float[] idleAlphas = { .2f, .35f, .55f, .75f };
            float alphaGap = Px(6);
            float alphaCell = (w - alphaGap * 3) / 4f;
            for (int i = 0; i < idleAlphas.Length; i++)
            {
                float value = idleAlphas[i];
                bool active = Mathf.Abs(Options.IdleOpacity - value) < .02f;
                Button(body, Mathf.RoundToInt(value * 100f) + "%", "settings.idleOpacity." + i,
                    i * (alphaCell + alphaGap), y, alphaCell, Px(38),
                    () => Run(() => SaveIdleOpacity(value)),
                    active ? OverlayTheme.Primary : OverlayTheme.SurfaceContainerHigh,
                    active ? OverlayTheme.OnPrimary : OverlayTheme.OnSurface);
            }
            y += Px(46);

            Text(body, "分组", Font(13), 0, y, w, Px(22), OverlayTheme.OnSurfaceVariant); y += Px(26);
            if (options.Groups.Count == 0)
            {
                Text(body, "尚未分组，全部模组显示在「其他」。在 App 的「悬浮窗」页可创建最多 8 个分组。",
                    Font(12), 0, y, w, Px(44), OverlayTheme.OnSurfaceVariant);
                y += Px(50);
            }
            else
            {
                foreach (var group in options.Groups)
                {
                    Text(body, group.Name + "  ·  " + group.Mods.Count + " 个模组",
                        Font(12), 0, y, w, Px(24), OverlayTheme.OnSurfaceVariant);
                    y += Px(28);
                }
                y += Px(6);
            }

            Text(body, "深浅色跟随手机设置；配色来自 App 的 Material You 取色，"
                + "在 App 内切换主题后游戏内立即生效，无需重启。",
                Font(12), 0, y, w, Px(44), OverlayTheme.OnSurfaceVariant); y += Px(50);
            Text(body, "当前方案：" + (OverlayTheme.Dark ? "深色" : "浅色"), Font(12), 0, y, w, Px(24), OverlayTheme.OnSurfaceVariant);
        }

        // ---- catalogue helpers ---------------------------------------------------------
        private List<string> VisibleMods(bool onlyFunctions)
        {
            var ids = AndroidApi.GetModIds();
            var result = new List<string>();
            foreach (var id in ids)
            {
                if (string.Equals(id, "CesiumOverlayMod", StringComparison.OrdinalIgnoreCase)) continue;
                if (options.Assigned(id)) continue;
                if (onlyFunctions && !ModCatalog.IsFunction(id)) continue;
                result.Add(id);
            }
            return result;
        }

        private CesiumAndroid.ActionItem FindMainToggle(string id)
        {
            var actions = AndroidApi.GetActions(id);
            CesiumAndroid.ActionItem fallback = null;
            foreach (var action in actions)
            {
                if (action.Id == "toys.enabled") return action;
                if (fallback == null && action.IsToggle) fallback = action;
            }
            return fallback;
        }

        private static bool SafeToggleValue(CesiumAndroid.ActionItem item)
        {
            try { return item.Get != null && item.Get(); }
            catch { return false; }
        }

        private static void SetToggle(CesiumAndroid.ActionItem item, bool value)
        {
            item.Set?.Invoke(value);
        }

        private string StateLabel(string id)
        {
            var mod = ModRegistry.Get(id);
            if (mod == null) return "未运行";
            string state = mod.State.ToString();
            if (state == "Initialized" || state == "Enabled") return "运行中";
            if (state == "Loaded" || state == "Initializing") return "加载中";
            if (state == "Failed" || state == "Faulted") return "加载失败";
            return "未运行";
        }

        /// <summary>Last lines of the SDK log, newest last, for the console page.</summary>
        private static List<string> ReadLogTail(int limit)
        {
            var result = new List<string>();
            try
            {
                string path = AndroidApi.LogPath;
                if (string.IsNullOrEmpty(path) || !File.Exists(path)) return result;
                string[] lines = File.ReadAllLines(path);
                int start = Math.Max(0, lines.Length - limit);
                for (int i = start; i < lines.Length; i++)
                {
                    string line = lines[i];
                    if (string.IsNullOrEmpty(line)) continue;
                    if (line.Length > 96) line = line.Substring(0, 96) + "…";
                    result.Add(line);
                }
            }
            catch (Exception error) { AndroidApi.LogError(error.ToString()); }
            return result;
        }

        /// <summary>
        /// Copies the tail of every log into one file under the loader data folder, then tells the
        /// user where it landed. Written next to `toys-overlay.txt` so the APK can pull it later.
        /// </summary>
        private void ExportLog()
        {
            string stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss", CultureInfo.InvariantCulture);
            string target = AndroidApi.GetLoaderPath("data", "toys-log-" + stamp + ".log");
            var text = new System.Text.StringBuilder();
            text.Append("Cesium Android FloatingWindow ").Append(Version).Append('\n');
            text.Append("导出时间 ").Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture)).Append('\n');
            text.Append("屏幕 ").Append(Screen.width).Append('x').Append(Screen.height)
                .Append(" · 方案 ").Append(OverlayTheme.Dark ? "深色" : "浅色").Append('\n');
            text.Append("倍速 ").Append(LocalSpeed.SelectedSpeed.ToString("0.0", CultureInfo.InvariantCulture)).Append('\n');
            text.Append("分组 ").Append(options.Groups.Count).Append('\n').Append('\n');

            foreach (string line in ReadLogTail(400)) text.Append(line).Append('\n');
            foreach (var id in AndroidApi.GetModIds())
                text.Append("[mod] ").Append(id).Append(' ').Append(StateLabel(id)).Append('\n');

            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(target) ?? ".");
                File.WriteAllText(target, text.ToString());
                AndroidApi.ShowNotice("日志已导出：" + target);
            }
            catch (Exception error)
            {
                AndroidApi.LogError(error.ToString());
                AndroidApi.ShowNotice("日志导出失败：" + error.Message);
            }
        }

        private void Run(Action action)
        {
            try
            {
                action();
            }
            catch (Exception error)
            {
                AndroidApi.LogError(error.ToString());
                AndroidApi.ShowNotice(ModCatalog.HasChinese(error.Message) ? error.Message : "操作失败，请检查模组日志");
            }
            contentKey = null;
            // Rebuilding the page from inside the click that asked for it disposes the button
            // mid-dispatch, which stalls the panel and eats the following taps. Defer instead.
            if (FingerDown && !forceRender) renderPending = true;
            else Render();
        }
    }
}
