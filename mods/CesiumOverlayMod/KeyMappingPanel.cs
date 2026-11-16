using System;
using System.Collections.Generic;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using FairyGUI;

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
            }
            string custom = Config.GetString("keymap." + id, "");
            if (!string.IsNullOrWhiteSpace(custom)) result.Add(new KeyBinding("custom", "自定义按键", custom));
            return result;
        }
        private string MappingSignature(string id)
        {
            if (id == null || !CanMap(id)) return "";
            string value = "";
            foreach (var binding in KeyBindings(id))
                value += "|" + binding.Keys + ":" + (VirtualKeyboard.Instance?.Holding(id + ":" + binding.Id) == true);
            return value;
        }
        private float RenderKeyMappings(string id, float y)
        {
            if (!CanMap(id)) return y;
            Text(body, "Win 模组按键映射", 24, 6, y, body.width - 12, 40); y += 46;
            Text(body, "点按发送一次；保持按住适用于长按功能，再点松开。关闭或切页会释放。相同按键会同时触发其他模组。",
                17, 6, y, body.width - 12, 92, OverlayTheme.Muted); y += 98;
            foreach (var item in KeyBindings(id))
            {
                var binding = item;
                string source = id + ":" + binding.Id;
                int[] keys;
                bool valid = VirtualKeyboard.TryParse(binding.Keys, out keys);
                Text(body, binding.Label + "  [" + binding.Keys + "]", 20, 6, y, body.width - 12, 38); y += 42;
                float half = (body.width - 10) / 2;
                var tap = Button(body, valid ? "点按" : "未绑定 / 键名无效", "keymap.tap." + source, 0, y, half, 54,
                    () => Run(() => SendKey(id, source + ":tap", binding.Keys, false)));
                var hold = Button(body, VirtualKeyboard.Instance?.Holding(source) == true ? "松开" : "保持按住",
                    "keymap.hold." + source, half + 10, y, half, 54,
                    () => Run(() => SendKey(id, source, binding.Keys, true)));
                tap.touchable = hold.touchable = valid;
                y += 66;
            }
            Text(body, "手动映射（需与模组自身配置一致）", 19, 6, y, body.width - 12, 40); y += 44;
            var input = new GTextInput { name = "keymap.input", singleLine = true, maxLength = 100,
                text = Config.GetString("keymap." + id, ""), promptText = "例如 H、F3、Ctrl+H；留空删除" };
            input.SetXY(8, y + 8); input.SetSize(body.width - 16, 42);
            var format = input.textFormat; format.size = 20; format.color = OverlayTheme.Text; input.textFormat = format;
            var background = new GComponent(); background.SetXY(0, y); background.SetSize(body.width, 58);
            body.AddChild(background); Rounded(background, body.width, 58, OverlayTheme.Panel, 10);
            body.AddChild(input); y += 70;
            Button(body, "保存映射", "keymap.save", 0, y, body.width, 52, () => Run(() =>
            {
                string value = input.text.Trim(); int[] parsed;
                if (value.Length != 0 && !VirtualKeyboard.TryParse(value, out parsed))
                    throw new InvalidOperationException("键名无效，请使用 H、F3 或 LeftAlt+H 等名称。");
                VirtualKeyboard.Instance?.Clear();
                Config.Set("keymap." + id, value); Config.SaveIfDirty();
                GRoot.inst.focus = null;
                AndroidApi.ShowNotice("映射已保存；不会修改模组自身的快捷键配置。");
            }));
            return y + 66;
        }
        private static void SendKey(string id, string source, string chord, bool hold)
        {
            var mod = ModRegistry.Get(id);
            if (mod == null || (mod.State != ModLifecycleState.Initialized && mod.State.ToString() != "Enabled"))
                throw new InvalidOperationException("模组尚未运行，请先启用并重启游戏。");
            if (!mod.Config.GetBool("enabled", true) || !mod.Config.GetBool("Enabled", true))
                throw new InvalidOperationException("模组功能已停用，请先打开功能开关。");
            if (VirtualKeyboard.Instance == null) throw new InvalidOperationException("按键兼容层尚未初始化。");
            VirtualKeyboard.Instance.Send(source, chord, hold);
        }
    }
}
