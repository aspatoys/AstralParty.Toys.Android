// Test-only stubs for the CesiumLoader SDK surface used by the real overlay and
// legacy SpeedHackMod controllers. Nothing here ships; the production files compiled
// alongside this stub set are the real mods/CesiumOverlayMod sources.
using System;
using System.Collections.Generic;
using System.Globalization;

namespace UnityEngine
{
    public enum RuntimePlatform { Android }
    public enum KeyCode { None, Delete, Equals, Minus, KeypadPlus, KeypadMinus, LeftAlt, RightAlt }

    public static class Application
    {
        public static string persistentDataPath = "";
        public static RuntimePlatform platform = RuntimePlatform.Android;
        public static bool isFocused = true;
    }

    public static class Screen { public static int width = 2400, height = 1080; }

    public static class Time
    {
        private static float _timeScale = 1f;
        // Keep a write journal so startup tests can prove that no transient
        // acceleration was written and then reverted in the same frame.
        public static readonly List<float> TimeScaleWrites = new List<float>();
        public static float timeScale
        {
            get { return _timeScale; }
            set { _timeScale = value; TimeScaleWrites.Add(value); }
        }
        public static float fixedDeltaTime = .02f;
        public static float maximumDeltaTime = .333f;
        public static float realtimeSinceStartup = 10f;
    }

    public struct Color
    {
        public float r, g, b, a;
        public Color(float red, float green, float blue, float alpha) { r = red; g = green; b = blue; a = alpha; }
    }

    public static class Mathf
    {
        public static float Clamp(float value, float min, float max) => Math.Min(max, Math.Max(min, value));
        public static int Clamp(int value, int min, int max) => Math.Min(max, Math.Max(min, value));
    }
}

namespace FairyGUI { internal static class __Stub { } }

namespace CesiumLoader.SDK.Mobile { internal static class __Stub { } }

namespace CesiumLoader.SDK.Mods
{
    public enum ModState { Disabled = 0, Enabled = 1, Faulted = 2, Stopping = 3, Stopped = 4 }

    public sealed class ModContext
    {
        public string ModId;
        public ModBase Mod;
        public ModConfig Config;
        public ModState State;
        private bool _cleaned;

        // Mirrors the real ModContext.Cleanup contract: idempotent, unsubscribes
        // (nothing is subscribed in this harness), then marks the context stopped.
        public void Cleanup()
        {
            if (_cleaned) return;
            _cleaned = true;
            State = ModState.Stopped;
        }
    }

    public sealed class ModConfig
    {
        private readonly Dictionary<string, object> _values = new Dictionary<string, object>(StringComparer.Ordinal);
        public string Path = "";
        public bool Dirty;

        public int Count => _values.Count;
        public bool Has(string key) => _values.ContainsKey(key);

        public object Get(string key)
        {
            object value;
            return _values.TryGetValue(key, out value) ? value : null;
        }

        public bool GetBool(string key, bool fallback)
        {
            object value;
            if (!_values.TryGetValue(key, out value) || value == null) return fallback;
            if (value is bool) return (bool)value;
            return Convert.ToBoolean(value, CultureInfo.InvariantCulture);
        }

        public double GetDouble(string key, double fallback)
        {
            object value;
            if (!_values.TryGetValue(key, out value) || value == null) return fallback;
            return Convert.ToDouble(value, CultureInfo.InvariantCulture);
        }

        public float GetFloat(string key, float fallback)
        {
            return (float)GetDouble(key, fallback);
        }

        public string GetString(string key, string fallback)
        {
            object value;
            return _values.TryGetValue(key, out value) && value != null ? Convert.ToString(value, CultureInfo.InvariantCulture) : fallback;
        }

        public void Set(string key, bool value) { _values[key] = value; Dirty = true; }
        public void Set(string key, double value) { _values[key] = value; Dirty = true; }
        public void Set(string key, float value) { _values[key] = (double)value; Dirty = true; }
        public void Set(string key, string value) { _values[key] = value; Dirty = true; }
        public void Set(string key, object value) { _values[key] = value; Dirty = true; }

        public bool SetDefault(string key, object value)
        {
            if (_values.ContainsKey(key)) return false;
            _values[key] = value;
            Dirty = true;
            return true;
        }

        public void SaveIfDirty() { Dirty = false; }
        public string Describe() => "stub config";
    }

    public class ModBase
    {
        public ModContext Context;
        public ModConfig Config;
        public virtual string Name => GetType().Name;
        public virtual string Version => "0.0.0";

        public virtual void OnLoad() { }
        public virtual void OnInitialize() { }
        public virtual void OnUpdate() { }
        public virtual void OnLateUpdate() { }
        public virtual void OnUnload() { }
        public virtual void OnConfigChanged() { }

        public static void Run(ModBase mod, int delayMs = 0, string tag = null) { }
    }

    public static class ModRegistry
    {
        private static readonly List<ModContext> _all = new List<ModContext>();
        public static int Count => _all.Count;
        public static IReadOnlyList<ModContext> All => _all;

        public static ModContext Get(string modId)
        {
            for (int i = 0; i < _all.Count; i++)
                if (string.Equals(_all[i].ModId, modId, StringComparison.OrdinalIgnoreCase)) return _all[i];
            return null;
        }

        public static ModContext Add(string modId, ModBase mod, ModConfig config)
        {
            var context = new ModContext
            {
                ModId = modId,
                Mod = mod,
                Config = config ?? new ModConfig(),
                State = ModState.Enabled
            };
            mod.Config = context.Config;
            mod.Context = context;
            _all.Add(context);
            return context;
        }

        public static void Clear() => _all.Clear();
    }

    public static class Log
    {
        public static void Info(string message) { }
        public static void Warn(string message) { }
        public static void ReportCrash(string where, Exception error) { }
        public static void ReportCrash(string message) { }
    }

    /// <summary>
    /// Mirrors ModHost.Stop(IMod mod, string tag = null) in the real SDK:
    /// ctx.Cleanup() -> OnUnload() on the main thread -> ctx.Mod = null -> State = Stopped.
    /// The context deliberately stays in ModRegistry.All, exactly like the real host.
    /// </summary>
    public static class ModHost
    {
        public static void Stop(ModBase mod, string tag = null)
        {
            if (mod == null) return;
            var context = mod.Context;
            if (context != null && context.State == ModState.Stopped) return;
            if (context != null) context.Cleanup();
            try { mod.OnUnload(); } catch { /* the real SafeOnMain logs and swallows */ }
            if (context != null)
            {
                context.Mod = null;
                context.State = ModState.Stopped;
            }
        }
    }

    public static class SdkLog
    {
        public static void Warn(string tag, string message) { }
        public static void Info(string tag, string message) { }
    }
}

namespace CesiumLoader.SDK.Manifests
{
    [Flags]
    public enum ModPermission
    {
        None = 0,
        ReadGameState = 1,
        UI = 2,
        FileWrite = 4,
        SpeedHack = 8
    }

    [AttributeUsage(AttributeTargets.Assembly)]
    public sealed class ModManifestAttribute : Attribute
    {
        public ModManifestAttribute(string name, string version, string author, string description)
        {
            Name = name; Version = version; Author = author; Description = description;
        }
        public string Name { get; }
        public string Version { get; }
        public string Author { get; }
        public string Description { get; }
        public ModPermission Permissions { get; set; }
    }
}

namespace CesiumLoader.SDK.Speed
{
    public static class SpeedHack
    {
        public const double DefaultSpeedStep = .5;
        public const double MinSpeed = 1.0;
        public const double MaxSpeed = 10.0;
        public const double HardMaxSpeed = 10.0;

        public static bool IsAvailable { get; set; }
        public static string UnavailableReason = "test stub";
        public static double Speed { get; set; } = 1.0;

        // Test knob: mimic an asynchronous native reset where SetSpeed(1) is accepted
        // as a request but the engine still reports the old value until completion.
        public static bool HoldNativeReset;

        public static bool SetSpeed(double speed)
        {
            if (!IsAvailable || double.IsNaN(speed) || double.IsInfinity(speed) || speed < MinSpeed || speed > HardMaxSpeed) return false;
            if (HoldNativeReset && Math.Abs(speed - 1) < .0001) return false;
            Speed = speed;
            return true;
        }

        public static void CompleteNativeReset()
        {
            HoldNativeReset = false;
            Speed = 1.0;
        }

        public static double ClampSpeed(double speed, double min, double max)
        {
            if (double.IsNaN(speed) || double.IsInfinity(speed)) return min;
            return Math.Min(max, Math.Max(min, speed));
        }

        public static double StepSpeed(double current, int direction, double step, double min, double max)
        {
            double next = current + direction * step;
            next = Math.Round(next, 2);
            return ClampSpeed(next, min, max);
        }

        public static string Describe() => "test stub speed engine";
    }
}

namespace CesiumLoader.SDK.Engine
{
    public static class UnityTime
    {
        public static float RealtimeSinceStartup => UnityEngine.Time.realtimeSinceStartup;
    }
}

namespace CesiumLoader.SDK.Inputs
{
    public static class InputService
    {
        public static string BackendName = "stub";
        public static bool IsAvailable = true;
        public static readonly HashSet<UnityEngine.KeyCode> HeldKeys = new HashSet<UnityEngine.KeyCode>();
        public static readonly HashSet<UnityEngine.KeyCode> PressedKeys = new HashSet<UnityEngine.KeyCode>();
        public static void SetHeld(UnityEngine.KeyCode key, bool held)
        {
            if (held) HeldKeys.Add(key); else HeldKeys.Remove(key);
        }
        public static void Press(UnityEngine.KeyCode key) { PressedKeys.Add(key); }
        public static void EndFrame() { PressedKeys.Clear(); }
        public static void ClearKeys() { HeldKeys.Clear(); PressedKeys.Clear(); }
        public static bool IsKeyHeld(UnityEngine.KeyCode key) => HeldKeys.Contains(key);
        public static bool IsKeyPressed(UnityEngine.KeyCode key) => PressedKeys.Contains(key);
    }
}

namespace CesiumLoader.SDK.UserInterface
{
    public enum UiNotificationLevel { Info, Success, Warning, Error }

    public static class UiService
    {
        public static bool ShouldBlockGameInput = false;
        public static void Notify(string text, UiNotificationLevel level, float seconds, CesiumLoader.SDK.Mods.ModContext context) { }
        public static void Notify(string text, UiNotificationLevel level) { }
    }
}

namespace CesiumLoader.SDK.Shared.Speed
{
    public static class NativeSpeedState
    {
        public static double? Read() => CesiumLoader.SDK.Speed.SpeedHack.IsAvailable
            ? (double?)CesiumLoader.SDK.Speed.SpeedHack.Speed
            : null;
    }
}

// Bridges are stubbed: they do not touch Time.timeScale, which is the quantity under test.
namespace SpeedHackMod
{
    public sealed class FairyUiTimeScaleBridge
    {
        public Action<object> RegisterExternalRoot;
        public void Tick(float now) { }
        public void Restore() { }
    }

    public sealed class IndependentAnimationBridge
    {
        public IndependentAnimationBridge(Func<string, Type> findType, Func<Type, object[]> find) { }
        public Action<string> Report;
        public Action<object> RegisterUiRoot;
        public void Tick(float now, double speed) { }
        public void Restore() { }
    }

    public static class UnityAnimationDiscovery
    {
        public static Type FindType(string name) => null;
        public static object[] Find(Type type) => Array.Empty<object>();
    }
}

namespace CesiumOverlayMod
{
    public sealed class FairyUiTimeScaleBridge
    {
        public FairyUiTimeScaleBridge(Type tweenManagerOverride = null) { }
        public void RegisterExternalRoot(object value) { }
        public void Tick(float now) { }
        public void Restore() { }
    }

    public sealed class IndependentAnimationBridge
    {
        public IndependentAnimationBridge(Func<string, Type> findType, Func<Type, object[]> find) { }
        public Action<string> Report;
        public Action<object> RegisterUiRoot;
        public void Tick(float now, double speed) { }
        public void Restore() { }
    }

    public static class UnityAnimationDiscovery
    {
        public static Type FindType(string name) => null;
        public static object[] Find(Type type) => Array.Empty<object>();
    }
}
