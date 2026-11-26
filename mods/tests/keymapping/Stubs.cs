// Stubs for external types used by VirtualKeyState.cs and VirtualKeyboard.cs
// Only type signatures needed for compilation — no business logic copied.
using System;

namespace UnityEngine
{
    public struct Vector2 { public float x, y; public Vector2(float x, float y) { this.x = x; this.y = y; } public static Vector2 zero => new Vector2(0, 0); }
    public static class Application { public static bool isFocused = true; }
    public static class Time { public static int frameCount = 0; public static float realtimeSinceStartup = 0f; public static float unscaledDeltaTime = 1f / 60f; }
    public static class Mathf
    {
        public static float Max(float a, float b) => a > b ? a : b;
        public static float Min(float a, float b) => a < b ? a : b;
        public static float Clamp(float v, float lo, float hi) => v < lo ? lo : v > hi ? hi : v;
        public static int Clamp(int v, int lo, int hi) => v < lo ? lo : v > hi ? hi : v;
        public static int RoundToInt(float v) => (int)System.Math.Round(v, System.MidpointRounding.AwayFromZero);
    }
    public enum KeyCode
    {
        None = 0, BackQuote = 96, Escape = 27, Space = 32,
        LeftControl = 306, LeftAlt = 308, LeftShift = 304,
        H = 104, F = 102, O = 111, T = 116,
        F1 = 282, F2 = 283, F3 = 284, F4 = 285, F5 = 286, F8 = 289,
        Mouse0 = 323, Mouse1 = 324, Mouse2 = 325,
        A = 97, B = 98, C = 99, D = 100, E = 101, G = 103, P = 112,
        R = 114, S = 115, U = 117, V = 118, W = 119, X = 120, Y = 121, Z = 122
    }
}

namespace CesiumLoader.SDK.Inputs
{
    public interface IInputBackend
    {
        string Name { get; }
        bool IsAvailable { get; }
        bool GetKey(UnityEngine.KeyCode key);
        bool GetKeyDown(UnityEngine.KeyCode key);
        bool GetKeyUp(UnityEngine.KeyCode key);
        bool GetMouseButton(int button);
        bool GetMouseButtonDown(int button);
        bool GetMouseButtonUp(int button);
        float GetAxis(string name);
        UnityEngine.Vector2 GetMouseScrollDelta();
        bool TryGetMousePosition(out UnityEngine.Vector2 position);
        bool TryGetMouseDelta(out UnityEngine.Vector2 delta);
    }
    public static class InputService
    {
        public static IInputBackend Backend;
        public static bool IsKeyboardCaptured = false;
        public static void SetBackend(IInputBackend backend) { Backend = backend; }
    }
}

namespace CesiumLoader.SDK.UserInterface
{
    public static class UiService { public static bool ShouldBlockGameInput = false; }
}

namespace FairyGUI
{
    public class GComponent { public bool isDisposed; public float width, height; }
    public class GRoot
    {
        public static GRoot inst;
        public float width, height;
        public object focus; // can be GTextInput or null
        public GComponent GCom => new GComponent();
    }
    public class GTextInput { public bool singleLine; public int maxLength; public string text, promptText; public float x, y; public object textFormat; }
}
