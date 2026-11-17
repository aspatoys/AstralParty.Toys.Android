using System;
using System.Reflection;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;

namespace CesiumOverlayMod
{
    internal static class BuiltinActions
    {
        private static int count = -1;
        internal static void Refresh()
        {
            int signature = ModRegistry.Count;
            foreach (var context in ModRegistry.All) signature = unchecked(signature * 31 + context.Config.Count + (int)context.State);
            if (count == signature) return;
            count = signature;
            foreach (var context in ModRegistry.All)
            {
                var current = context;
                var instance = current.Mod as ModBase;
                AndroidApi.RegisterMod(current.ModId, ModCatalog.Name(current.ModId, instance?.Name));
                AndroidApi.RegisterButton(current.ModId, "toys.status", "运行状态", () => AndroidApi.ShowNotice(ModCatalog.Name(current.ModId, instance?.Name) + "：" + current.State));
                var changed = instance?.GetType().GetMethod("OnConfigChanged");
                string key = current.Config.Has("enabled") ? "enabled" : current.Config.Has("Enabled") ? "Enabled" : null;
                // Register the master switch whenever the mod keeps one. Most mods poll their config
                // every frame, so saving the value is enough; the reflection call is only there for
                // mods that prefer to be notified. Requiring an override left HideHudMod and
                // CharacterTransparencyMod without a switch at all, which is why their rows could
                // not be turned on from the overlay.
                if (key != null)
                    AndroidApi.RegisterToggle(current.ModId, "toys.enabled", "模组总开关", () => current.Config.GetBool(key, true), value =>
                    {
                        current.Config.Set(key, value);
                        current.Config.SaveIfDirty();
                        if (changed != null && changed.DeclaringType != typeof(ModBase)) instance.OnConfigChanged();
                    });
                if (current.ModId == "SpeedHackMod")
                {
                    // Reuse the bundled controller's Unity backend, including its fixed-step/UI compensation.
                    foreach (double speed in new[] { 1d, 1.5d, 2d, 2.5d, 3d })
                    {
                        double target = speed;
                        AndroidApi.RegisterButton(current.ModId, "toys.speed." + target, target + "×", () =>
                        {
                            SpeedPanel.Choose(target);
                        });
                    }
                }
                RegisterKnownShortcuts(current, key, changed);
            }
        }

        /// <summary>
        /// The built-in shortcuts the 功能 page shows for the mods that ship with the loader.
        /// <para>
        /// These also serve as the reference example for mod authors: a shortcut is registered with
        /// <see cref="AndroidApi.RegisterShortcut(string, string, Func{bool}, Action, string)"/>, gets its own
        /// row on the 功能 page, and may describe its current state in the detail line. Publishing one
        /// is enough — nothing in the overlay needs to know the mod in advance.
        /// </para>
        /// </summary>
        private static void RegisterKnownShortcuts(ModContext mod, string key, MethodInfo changed)
        {
            var current = mod;
            string id = current.ModId;
            if (string.Equals(id, "HideHudMod", StringComparison.OrdinalIgnoreCase))
            {
                // HideHudMod runs in hold mode (pressMode=Hold, holdSeconds=0.35): flipping its
                // config booleans changes what *would* be hidden but never hides anything now.
                // The shortcut therefore presses the mod's own hotkey, which is the action the
                // player is asking for, and mirrors the switch state it just took.
                // HideHudMod only hides while its key is held, so the row latches instead of
                // pulsing: the first tap holds the key down and the second tap releases it, which
                // is the only way a hold-mode mod can be driven from a touch screen.
                string hudSource = id + ":hud.hide";
                AndroidApi.RegisterShortcut(id, "hud.hide", "隐藏 / 恢复 HUD",
                    () => VirtualKeyboard.Instance != null && VirtualKeyboard.Instance.Holding(hudSource),
                    () => Latch(hudSource, current.Config.GetString("toggleKey", "BackQuote")),
                    "按键 " + current.Config.GetString("toggleKey", "BackQuote") + " · 点一下隐藏，再点一下恢复");
                return;
            }
            if (string.Equals(id, "CharacterTransparencyMod", StringComparison.OrdinalIgnoreCase))
            {
                // Same story: holdMode=true means only a held key makes the characters fade.
                string fadeSource = id + ":body.fade";
                AndroidApi.RegisterShortcut(id, "body.fade", "角色半透明",
                    () => VirtualKeyboard.Instance != null && VirtualKeyboard.Instance.Holding(fadeSource),
                    () => Latch(fadeSource, current.Config.GetString("toggleKey", "H")),
                    "按键 " + current.Config.GetString("toggleKey", "H") + " · 点一下半透明，再点一下恢复");
                return;
            }
            if (string.Equals(id, "PveWaveForecastMod", StringComparison.OrdinalIgnoreCase))
            {
                // The O hotkey drives a runtime panel and never writes the config back, so the
                // saved value would always be stale. Read the controller's live _visible field
                // instead, and the row reports what is really on screen.
                string waveKey = current.Config.GetString("toggleKey", "O");
                AndroidApi.RegisterShortcut(id, "wave.forecast", "波次预告",
                    () => LiveBool(current, "_visible", false),
                    () => Press(current, "wave.forecast", waveKey, "波次预告"),
                    "按键 " + waveKey + " · 显示中 / 已隐藏");
                return;
            }
            if (string.Equals(id, "TeammateCheckMod", StringComparison.OrdinalIgnoreCase))
            {
                // F9 flips the panel's runtime flag and leaves showPanel alone, which is why the
                // row never followed the panel. _panel.Wanted is the flag the panel itself obeys.
                string teamKey = current.Config.GetString("toggleKey", "F9");
                AndroidApi.RegisterShortcut(id, "team.panel", "队友战绩面板",
                    () => LiveBool(current, "_panel.Wanted", current.Config.GetBool("showPanel", true)),
                    () => Press(current, "team.panel", teamKey, "队友战绩面板"),
                    "按键 " + teamKey + " · 显示中 / 已隐藏");
                return;
            }
            if (string.Equals(id, "TownTrainer", StringComparison.OrdinalIgnoreCase))
            {
                // No config file at all, and the panel only listens on Unity's own
                // Input.GetKeyDown(289) — a key the SDK keyboard cannot reach. Flip the panel's
                // own _visible flag instead; its Tick applies it on the next frame.
                AndroidApi.RegisterShortcut(id, "town.panel", "小镇修改器面板",
                    () => LiveBool(current, "_panel._visible", false),
                    () => ToggleTownPanel(current),
                    "按键 F8 · 点一下开关面板");
            }
        }

        /// <summary>
        /// Drives a hold-mode mod from a tap: presses and keeps the chord down on the first call,
        /// releases it on the next one. The parking is what such a mod reacts to, so a single
        /// frame or a short pulse would never leave it in the requested state.
        /// </summary>
        private static void Latch(string source, string chord)
        {
            var keyboard = VirtualKeyboard.Instance;
            if (keyboard == null) throw new InvalidOperationException("按键兼容层尚未初始化。");
            keyboard.Send(source, chord, true);
        }

        // Mods keep their hotkey state in plain fields (one of them nested a level down), so a
        // dotted path walked with reflection is the only way to read or flip it.
        private const BindingFlags Live = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;

        private static MemberInfo Member(object owner, string name)
        {
            if (owner == null) return null;
            for (Type type = owner.GetType(); type != null; type = type.BaseType)
            {
                FieldInfo field = type.GetField(name, Live);
                if (field != null) return field;
                PropertyInfo property = type.GetProperty(name, Live);
                if (property != null) return property;
            }
            return null;
        }

        private static bool TryLive(ModContext mod, string path, out object value)
        {
            value = null;
            object owner = mod != null ? mod.Mod : null;
            foreach (string part in path.Split('.'))
            {
                MemberInfo member = Member(owner, part);
                if (member == null) return false;
                var field = member as FieldInfo;
                owner = field != null ? field.GetValue(owner) : ((PropertyInfo)member).GetValue(owner, null);
                if (owner == null) return false;
            }
            value = owner;
            return true;
        }

        private static bool SetLive(ModContext mod, string path, object value)
        {
            int cut = path.LastIndexOf('.');
            object owner = mod != null ? mod.Mod : null;
            if (cut >= 0 && !TryLive(mod, path.Substring(0, cut), out owner)) return false;
            MemberInfo member = Member(owner, cut >= 0 ? path.Substring(cut + 1) : path);
            if (member == null) return false;
            var field = member as FieldInfo;
            if (field != null) field.SetValue(owner, value); else ((PropertyInfo)member).SetValue(owner, value, null);
            return true;
        }

        /// <summary>Reads a live boolean off the mod instance; [fallback] while the panel is not built.</summary>
        private static bool LiveBool(ModContext mod, string path, bool fallback)
        {
            object value;
            return TryLive(mod, path, out value) && value is bool ? (bool)value : fallback;
        }

        /// <summary>
        /// Flips a boolean the mod keeps in memory and lets its own Tick apply it. This is the only
        /// route for the mod that reads Unity's Input directly, where no injected key can arrive.
        /// The caller gets the value it asked for back, so it can adjust what it reports.
        /// </summary>
        private static bool ToggleFlag(ModContext mod, string path)
        {
            object value;
            if (!TryLive(mod, path, out value) || !(value is bool))
                throw new InvalidOperationException("面板尚未就绪，请进入游戏后重试。");
            bool target = !(bool)value;
            if (!SetLive(mod, path, target)) throw new InvalidOperationException("面板状态不可写。");
            return target;
        }

        /// <summary>
        /// The town trainer paints nothing outside the town scene: its Tick hides the panel every
        /// frame unless the current scene is the one it cares about, and it advertises that through
        /// the entry button it owns. Say which of the two happened instead of leaving the player
        /// with an unchanged screen.
        /// </summary>
        private static void ToggleTownPanel(ModContext mod)
        {
            bool target = ToggleFlag(mod, "_panel._visible");
            bool visible = LiveBool(mod, "_panel._toggle.visible", true);
            AndroidApi.ShowNotice(visible
                ? "小镇修改器面板：" + (target ? "已打开" : "已关闭")
                : "小镇修改器面板：" + (target ? "已打开" : "已关闭") + "，进入吉星小镇后可见");
        }

        /// <summary>
        /// Pulses a mod's own hotkey. Its master switch is turned on first, because several mods
        /// ship disabled and ignore their key entirely until it is enabled. [label] only feeds the
        /// footer notice: a plain button has no state of its own to show, so saying the key went
        /// out is the difference between "nothing happened" and "it worked".
        /// </summary>
        private static void Press(ModContext mod, string source, string chord, string label)
        {
            var keyboard = VirtualKeyboard.Instance;
            if (keyboard == null) throw new InvalidOperationException("按键兼容层尚未初始化。");
            if (mod != null)
            {
                string switchKey = mod.Config.Has("enabled") ? "enabled" : mod.Config.Has("Enabled") ? "Enabled" : null;
                if (switchKey != null && !mod.Config.GetBool(switchKey, true))
                {
                    mod.Config.Set(switchKey, true);
                    mod.Config.SaveIfDirty();
                }
            }
            keyboard.Send(mod != null ? mod.ModId + ":" + source : source, chord, false);
            if (!string.IsNullOrEmpty(label)) AndroidApi.ShowNotice(label + "：已发送按键 " + chord);
        }

        /// <summary>Flips a plain boolean field in the mod's own config and persists it.</summary>
        private static void Flip(ModContext mod, string field)
        {
            try
            {
                bool value = !mod.Config.GetBool(field, false);
                mod.Config.Set(field, value);
                mod.Config.SaveIfDirty();
                AndroidApi.ShowNotice(ModCatalog.Name(mod.ModId, null) + "：" + (value ? "已开启" : "已关闭"));
            }
            catch (Exception error)
            {
                AndroidApi.LogError("shortcut " + mod.ModId + "/" + field + ": " + error.Message);
            }
        }

        /// <summary>Toggles the mod's master switch, notifying it when it asks to be notified.</summary>
        private static void Toggle(ModContext mod, string key, MethodInfo changed)
        {
            if (string.IsNullOrEmpty(key)) { Flip(mod, "enabled"); return; }
            try
            {
                bool value = !mod.Config.GetBool(key, true);
                mod.Config.Set(key, value);
                mod.Config.SaveIfDirty();
                var instance = mod.Mod as ModBase;
                if (changed != null && changed.DeclaringType != typeof(ModBase)) instance.OnConfigChanged();
                AndroidApi.ShowNotice(ModCatalog.Name(mod.ModId, instance?.Name) + "：" + (value ? "已开启" : "已关闭"));
            }
            catch (Exception error)
            {
                AndroidApi.LogError("shortcut " + mod.ModId + "/" + key + ": " + error.Message);
            }
        }
    }
}
