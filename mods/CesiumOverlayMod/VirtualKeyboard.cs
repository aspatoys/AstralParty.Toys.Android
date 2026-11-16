using System;
using System.Collections.Generic;
using CesiumLoader.SDK.Inputs;
using CesiumLoader.SDK.UserInterface;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    /// <summary>Installs an SDK input decorator; neither the SDK nor desktop mod DLLs are rewritten.</summary>
    internal sealed class VirtualKeyboard : IInputBackend
    {
        private readonly IInputBackend original;
        private readonly VirtualKeyState state = new VirtualKeyState();
        private VirtualKeyboard(IInputBackend original) { this.original = original; }
        internal static VirtualKeyboard Instance { get; private set; }
        internal static void Install()
        {
            if (Instance != null) return;
            var keyboard = new VirtualKeyboard(InputService.Backend);
            InputService.SetBackend(keyboard);
            Instance = keyboard;
        }
        internal static void Uninstall()
        {
            var keyboard = Instance;
            if (keyboard == null) return;
            keyboard.Clear();
            if (ReferenceEquals(InputService.Backend, keyboard)) InputService.SetBackend(keyboard.original);
            Instance = null;
        }
        internal bool Installed { get { return ReferenceEquals(InputService.Backend, this); } }
        private static bool Blocked
        {
            get { return !Application.isFocused || InputService.IsKeyboardCaptured || UiService.ShouldBlockGameInput ||
                (GRoot.inst != null && GRoot.inst.focus is GTextInput); }
        }
        internal void Tick()
        {
            if (!Installed || Blocked) { state.Clear(); return; }
            state.Advance(Time.frameCount);
        }
        internal void Clear() { state.Clear(); }
        internal bool Holding(string source) { Tick(); return state.HasSource(source); }
        internal void Send(string source, string chord, bool hold)
        {
            if (!Installed) throw new InvalidOperationException("输入后端已被其他模组替换，请重启游戏。");
            if (Blocked) throw new InvalidOperationException("请结束文字输入或关闭占用输入的窗口，再使用映射。");
            int[] keys;
            if (!TryParse(chord, out keys)) throw new InvalidOperationException("键名无效，例如 H、F3 或 LeftAlt+H；None 表示不绑定。");
            if (hold && state.HasSource(source)) state.Release(source, Time.frameCount);
            else state.Press(source, keys, Time.frameCount, !hold);
        }
        internal static bool TryParse(string chord, out int[] keys)
        {
            var result = new List<int>(); keys = new int[0];
            if (string.IsNullOrWhiteSpace(chord) || chord.Equals("None", StringComparison.OrdinalIgnoreCase)) return false;
            string[] parts = chord.Split('+');
            if (parts.Length > 4) return false;
            foreach (string part in parts)
            {
                string value = part.Trim();
                if (value == "`" || value == "~" || value == "·") value = "BackQuote";
                if (value.Equals("Ctrl", StringComparison.OrdinalIgnoreCase)) value = "LeftControl";
                if (value.Equals("Alt", StringComparison.OrdinalIgnoreCase)) value = "LeftAlt";
                if (value.Equals("Shift", StringComparison.OrdinalIgnoreCase)) value = "LeftShift";
                if (value.Equals("Esc", StringComparison.OrdinalIgnoreCase)) value = "Escape";
                if (value == "空格") value = "Space";
                KeyCode key;
                if (value.Length == 0 || char.IsDigit(value[0]) || !Enum.TryParse(value, true, out key) ||
                    !Enum.IsDefined(typeof(KeyCode), key) || key == KeyCode.None || (int)key >= (int)KeyCode.Mouse0) return false;
                if (!result.Contains((int)key)) result.Add((int)key);
            }
            keys = result.ToArray(); return keys.Length > 0;
        }

        public string Name { get { return "Android virtual keyboard + original input"; } }
        public bool IsAvailable { get { return true; } }
        private bool Read(Func<bool> read) { try { return original != null && read(); } catch { return false; } }
        public bool GetKey(KeyCode key) { Tick(); return state.Held((int)key) || Read(() => original.GetKey(key)); }
        public bool GetKeyDown(KeyCode key)
        {
            Tick();
            bool realHeld = Read(() => original.GetKey(key)), realDown = Read(() => original.GetKeyDown(key));
            bool virtualHeld = state.Held((int)key), virtualDown = state.Down((int)key);
            return (virtualDown && (!realHeld || realDown)) || (realDown && (!virtualHeld || virtualDown));
        }
        public bool GetKeyUp(KeyCode key)
        {
            Tick();
            if (state.Held((int)key) || Read(() => original.GetKey(key))) return false;
            return state.Up((int)key) || Read(() => original.GetKeyUp(key));
        }
        public bool GetMouseButton(int button) { return Read(() => original.GetMouseButton(button)); }
        public bool GetMouseButtonDown(int button) { return Read(() => original.GetMouseButtonDown(button)); }
        public bool GetMouseButtonUp(int button) { return Read(() => original.GetMouseButtonUp(button)); }
        public float GetAxis(string name) { try { return original == null ? 0 : original.GetAxis(name); } catch { return 0; } }
        public Vector2 GetMouseScrollDelta() { try { return original == null ? Vector2.zero : original.GetMouseScrollDelta(); } catch { return Vector2.zero; } }
        public bool TryGetMousePosition(out Vector2 position)
        {
            position = Vector2.zero; try { return original != null && original.TryGetMousePosition(out position); } catch { return false; }
        }
        public bool TryGetMouseDelta(out Vector2 delta)
        {
            delta = Vector2.zero; try { return original != null && original.TryGetMouseDelta(out delta); } catch { return false; }
        }
    }
}
