using System;
using System.Collections.Generic;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    public sealed partial class OverlayController
    {
        private sealed class KeyBinding
        {
            internal string Id, Label, Keys;
            internal KeyBinding(string id, string label, string keys) { Id = id; Label = label; Keys = keys; }
        }

        private static bool CanMap(string id)
        {
            return id != "SpeedHackMod" && id != "FreeCameraMod" && id != "CesiumOverlayMod";
        }

        private List<KeyBinding> KeyBindings(string id)
        {
            var result = new List<KeyBinding>();
            var mod = ModRegistry.Get(id);
            if (mod == null || !CanMap(id)) return result;
            Action<string, string, string> add = (field, label, fallback) =>
                result.Add(new KeyBinding(field, label, mod.Config.GetString(field, fallback)));
            switch (id)
            {
                case "HideHudMod": add("toggleKey", "隐藏 / 恢复 HUD", "BackQuote"); break;
                case "CharacterTransparencyMod": add("toggleKey", "角色半透明", "H"); break;
                case "PveWaveForecastMod": add("toggleKey", "显示 / 隐藏波次", "O"); break;
                case "ShopRelicPeekMod": add("clearKey", "清空商店和筹码看板", "T"); break;
                case "StarCoinShopMod": add("quickBuyKey", "一键购买", "F"); break;
                case "PvePanelMod":
                    add("LeftToggleKey", "左侧信息栏", "F3");
                    add("HandToggleKey", "手牌抽屉", "F4");
                    add("RightToggleKey", "右侧情报栏", "F5"); break;
                case "UpdateNoticeMod": add("toggleKey", "更新通知", "F8"); break;
                case "TeammateCheckMod": add("toggleKey", "队友战绩面板", "F9"); break;
                // TownTrainer ships no config file: the panel toggle is hard-coded to
                // Input.GetKeyDown(289) inside the assembly, i.e. KeyCode.F8.
                case "TownTrainer": add("panelKey", "小镇修改器面板", "F8"); break;
            }
            return result;
        }

        /// <summary>
        /// Mods that publish a Win key binding, in catalogue order, each with its chords joined
        /// for display. The apps page lists these in their own section: the shortcuts are the only
        /// way to reach some mods on a touch device, so they must not hide inside a group the
        /// player never opened.
        /// </summary>
        private List<KeyValuePair<string, string>> MappedMods()
        {
            var result = new List<KeyValuePair<string, string>>();
            foreach (var id in AndroidApi.GetModIds())
            {
                if (!CanMap(id)) continue;
                var chords = new List<string>();
                foreach (var binding in KeyBindings(id))
                    if (!string.IsNullOrWhiteSpace(binding.Keys)) chords.Add(binding.Keys.Trim());
                if (chords.Count == 0) continue;
                result.Add(new KeyValuePair<string, string>(id, string.Join(" / ", chords.ToArray())));
            }
            return result;
        }

        private string MappingSignature(string id)
        {
            if (id == null || !CanMap(id)) return string.Empty;
            string value = string.Empty;
            foreach (var binding in KeyBindings(id))
                value += "|" + binding.Keys + ":" + (VirtualKeyboard.Instance?.Holding(id + ":" + binding.Id) == true);
            return value;
        }

        private float RenderKeyMappings(string id, float y)
        {
            if (!CanMap(id)) return y;
            Text(body, "Win 模组按键映射", Font(17), 0, y, body.width, Px(30)); y += Px(34);
            Text(body, "点按发送一次；保持按住适用于长按功能，再点松开。关闭或切页会释放。键位取自模组自身的配置。",
                Font(13), 0, y, body.width, Px(48), OverlayTheme.OnSurfaceVariant); y += Px(54);
            foreach (var item in KeyBindings(id))
            {
                var binding = item;
                string source = id + ":" + binding.Id;
                bool valid = VirtualKeyboard.TryParse(binding.Keys, out int[] _);
                Text(body, binding.Label + "  [" + binding.Keys + "]", Font(14), 0, y, body.width, Px(24)); y += Px(28);
                float half = (body.width - Px(8)) / 2f;
                var tap = Button(body, valid ? "点按" : "未绑定 / 键名无效", "keymap.tap." + source, 0, y, half, Px(38),
                    () => Run(() => SendKey(id, source + ":tap", binding.Keys, false)));
                var hold = Button(body, VirtualKeyboard.Instance?.Holding(source) == true ? "松开" : "保持按住",
                    "keymap.hold." + source, half + Px(8), y, half, Px(38),
                    () => Run(() => SendKey(id, source, binding.Keys, true)));
                tap.touchable = hold.touchable = valid;
                y += Px(46);
            }

                        return y + Px(8);
        }

        private static void SendKey(string id, string source, string chord, bool hold)
        {
            var mod = ModRegistry.Get(id);
            if (mod == null || (mod.State != ModLifecycleState.Initialized && mod.State.ToString() != "Enabled"))
                throw new InvalidOperationException("模组尚未运行，请先启用并重启游戏。");
            if (VirtualKeyboard.Instance == null) throw new InvalidOperationException("按键兼容层尚未初始化。");
            // A disabled master switch means the mod itself ignores the hotkey, so sending the
            // key would look like a dead button. Turn the switch on first — the player pressing
            // the mapping is clearly asking for the mod to act.
            Enable(mod);
            VirtualKeyboard.Instance.Send(source, chord, hold);
        }

        /// <summary>
        /// Flips the mod's own master switch on and lets it re-read its configuration.
        /// <para>
        /// Mods like HideHudMod and CharacterTransparencyMod ship with "enabled": false, so their
        /// hotkey handlers return early. Overlay key mappings are meaningless until the switch is
        /// on, which is why enabling here is preferable to refusing to send the key.
        /// </para>
        /// </summary>
        private static void Enable(ModContext mod)
        {
            string key = mod.Config.Has("enabled") ? "enabled" : mod.Config.Has("Enabled") ? "Enabled" : null;
            if (key == null || mod.Config.GetBool(key, true)) return;
            try
            {
                mod.Config.Set(key, true);
                mod.Config.SaveIfDirty();
                var instance = mod.Mod as ModBase;
                var changed = instance?.GetType().GetMethod("OnConfigChanged");
                if (changed != null && changed.DeclaringType != typeof(ModBase))
                    changed.Invoke(instance, null);
            }
            catch (Exception error)
            {
                Debug.LogWarning("overlay could not enable " + mod.ModId + ": " + error.Message);
            }
        }
    }
}
