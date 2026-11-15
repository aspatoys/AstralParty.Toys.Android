using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using CesiumLoader.SDK.Speed;
using CesiumOverlayMod;
using SpeedHackMod;
using UnityEngine;

namespace SpeedRegression
{
    internal static class Program
    {
        private static readonly string[] Scenarios =
        {
            "theme-alpha",
            "sequence-2-3-1",
            "residue-1x",
            "residue-3-1",
            "pause-boundary",
            "focus-boundary",
            "live-direct-legacy",
            "handoff-legacy-unity",
            "delegate-engine",
            "notice-1x",
            "pause-1x",
            "fault-notice",
            "direct-single",
            "direct-other-id",
            "direct-native-risk",
            "native-only",
            "native-async",
            "delegate-engine-stale",
            "handoff-reject",
            "post-stop-refresh",
            "focus-delegated",
            "alternate-old-ui",
            "pause-1x-delegated"
        };

        private static int Main(string[] args)
        {
            if (args.Length == 0 || args[0] == "list")
            {
                Console.WriteLine(string.Join("\n", Scenarios));
                return 0;
            }

            string scenario = args[0];
            string sandbox = Path.GetFullPath(args.Length > 1 ? args[1] : Path.Combine(AppContext.BaseDirectory, "sandbox"));
            ResetWorld(sandbox);
            var checks = new Checks(scenario);

            try
            {
                switch (scenario)
                {
                    case "theme-alpha": Theme(checks); break;
                    case "sequence-2-3-1": Sequence(checks); break;
                    case "residue-1x": ResidueOne(checks); break;
                    case "residue-3-1": ResidueThreeOne(checks); break;
                    case "pause-boundary": Pause(checks); break;
                    case "focus-boundary": Focus(checks); break;
                    case "live-direct-legacy": LiveDirectLegacy(checks); break;
                    case "handoff-legacy-unity": HandoffLegacy(checks); break;
                    case "delegate-engine": DelegateEngine(checks); break;
                    case "notice-1x": NoticeOne(checks); break;
                    case "pause-1x": PauseOne(checks); break;
                    case "fault-notice": FaultNotice(checks); break;
                    case "direct-single": DirectSingle(checks); break;
                    case "direct-other-id": DirectOtherId(checks); break;
                    case "direct-native-risk": DirectNativeRisk(checks); break;
                    case "native-only": NativeOnly(checks); break;
                    case "native-async": NativeAsync(checks); break;
                    case "delegate-engine-stale": DelegateEngineStale(checks); break;
                    case "handoff-reject": HandoffReject(checks); break;
                    case "post-stop-refresh": PostStopRefresh(checks); break;
                    case "focus-delegated": FocusDelegated(checks); break;
                    case "alternate-old-ui": AlternateOldUi(checks); break;
                    case "pause-1x-delegated": PauseOneDelegated(checks); break;
                    default:
                        Console.WriteLine("unknown scenario: " + scenario);
                        return 2;
                }
            }
            catch (Exception error)
            {
                Console.WriteLine("ERROR " + error);
                return 2;
            }

            return checks.Finish();
        }

        private static void ResetWorld(string sandbox)
        {
            Directory.CreateDirectory(sandbox);
            Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", sandbox);
            Application.persistentDataPath = sandbox;
            Application.isFocused = true;
            Time.timeScale = 1f;
            Time.fixedDeltaTime = .02f;
            Time.maximumDeltaTime = .333f;
            Time.realtimeSinceStartup = 10f;
            SpeedHack.IsAvailable = false;
            SpeedHack.Speed = 1;
            SpeedHack.HoldNativeReset = false;
            AndroidApi.ShowNotice("");
            AndroidApi.ClearActions();
            ModRegistry.Clear();
        }

        private static ModConfig QuietConfig()
        {
            var config = new ModConfig();
            config.Set("optimizeFixedUpdates", false);
            config.Set("accelerateIndependentAnimations", false);
            config.Set("accelerateUiAnimations", false);
            config.Set("accelerationMode", "unity");
            return config;
        }

        private static LegacyUnityController SetupButtons(bool engineAvailable, bool active, double legacyTarget)
        {
            SpeedHack.IsAvailable = engineAvailable;
            var config = QuietConfig();
            var legacy = new LegacyUnityController { Config = config };
            ModRegistry.Add("SpeedHackMod", legacy, config);
            if (active) legacy.Activate(legacyTarget);
            BuiltinActions.Refresh();
            return legacy;
        }

        private static void Click(double target)
        {
            string id = "toys.speed." + target.ToString(CultureInfo.InvariantCulture);
            var actions = AndroidApi.GetActions("SpeedHackMod");
            var action = actions.Find(item => item.Id == id);
            if (action == null)
                throw new InvalidOperationException("missing action " + id + " (present: " + string.Join(", ", actions.ConvertAll(item => item.Id).ToArray()) + ")");
            action.Run();
        }

        private static void Pump(int frames, Action each = null)
        {
            for (int i = 0; i < frames; i++)
            {
                Time.realtimeSinceStartup += .25f;
                each?.Invoke();
                LocalSpeed.Tick();
            }
        }

        private static void Theme(Checks checks)
        {
            string directory = Path.Combine(AndroidApi.Root, "data");
            Directory.CreateDirectory(directory);
            string appearance = Path.Combine(directory, "toys-appearance.txt");

            File.WriteAllText(appearance, "dark\ndark\n");
            OverlayTheme.Poll();
            checks.True("dark mode selected", OverlayTheme.Dark);
            checks.Near("dark window background alpha", .94, OverlayTheme.Background.a);
            checks.Near("dark button panel alpha", .78, OverlayTheme.Panel.a);
            checks.Near("dark selected panel alpha", .55, OverlayTheme.Selected.a);
            checks.Near("dark border alpha", .65, OverlayTheme.Border.a);
            checks.Near("dark text remains opaque", 1.0, OverlayTheme.Text.a);
            checks.Near("dark background red", 0x12 / 255.0, OverlayTheme.Background.r);
            checks.Near("dark background blue", 0x20 / 255.0, OverlayTheme.Background.b);

            File.WriteAllText(appearance, "light\nlight\n");
            OverlayTheme.Poll();
            checks.True("light mode selected", !OverlayTheme.Dark);
            checks.Near("light window background alpha", .96, OverlayTheme.Background.a);
            checks.Near("light button panel alpha", .90, OverlayTheme.Panel.a);
            checks.Near("light selected panel alpha", .58, OverlayTheme.Selected.a);
            checks.Near("light border alpha", .72, OverlayTheme.Border.a);
            checks.Near("light text remains opaque", 1.0, OverlayTheme.Text.a);
            checks.Near("light background red", 0xF6 / 255.0, OverlayTheme.Background.r);
        }

        private static void Sequence(Checks checks)
        {
            SetupButtons(false, false, 1);
            Click(2);
            checks.Near("click 2x from 1x", 2, Time.timeScale);
            Pump(3);
            checks.Near("2x remains 2x after frames", 2, Time.timeScale);
            Click(3);
            checks.Near("click 3x after 2x", 3, Time.timeScale);
            Pump(3);
            checks.Near("3x remains 3x after frames", 3, Time.timeScale);
            Click(1);
            checks.Near("click 1x after 3x", 1, Time.timeScale);
            Pump(3);
            checks.Near("1x remains 1x after frames", 1, Time.timeScale);
        }

        private static void ResidueOne(Checks checks)
        {
            SetupButtons(false, false, 1);
            Time.timeScale = 2.5f;
            Click(1);
            checks.Near("1x must not restore a stale accelerated baseline", 1, Time.timeScale);
            Pump(3);
            checks.Near("1x stays 1x after frames", 1, Time.timeScale);
        }

        private static void ResidueThreeOne(Checks checks)
        {
            SetupButtons(false, false, 1);
            Time.timeScale = 2.5f;
            Click(3);
            checks.Near("3x is absolute over a stale 2.5 baseline", 3, Time.timeScale);
            Pump(3);
            checks.Near("3x stays 3x after frames", 3, Time.timeScale);
            Click(1);
            checks.Near("1x is absolute after the stale-baseline 3x", 1, Time.timeScale);
            Pump(3);
            checks.Near("1x stays 1x after frames", 1, Time.timeScale);
        }

        private static void Pause(Checks checks)
        {
            SetupButtons(false, false, 1);
            Click(3);
            checks.Near("3x before pause", 3, Time.timeScale);
            Time.timeScale = 0f;
            Pump(2);
            checks.Near("pause (timeScale 0) is preserved", 0, Time.timeScale);
            Time.timeScale = 1f;
            Pump(2);
            checks.Near("unpause resumes 3x", 3, Time.timeScale);
            Click(1);
            checks.Near("1x after pause cycle", 1, Time.timeScale);
        }

        private static void Focus(Checks checks)
        {
            SetupButtons(false, false, 1);
            Time.timeScale = 2.5f;
            Click(3);
            checks.Near("3x is absolute over a stale 2.5 baseline", 3, Time.timeScale);
            Application.isFocused = false;
            Pump(2);
            checks.Near("focus loss suspends to 1x, not the stale baseline", 1, Time.timeScale);
            Application.isFocused = true;
            Pump(2);
            checks.Near("focus regain resumes 3x", 3, Time.timeScale);
            Click(1);
            checks.Near("1x after focus cycle", 1, Time.timeScale);
        }

        private static void LiveDirectLegacy(Checks checks)
        {
            SetupButtons(false, false, 1);

            var config = QuietConfig();
            config.Set("speed", 2.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("AndroidSpeed", direct, config);
            direct.OnInitialize();
            InvokeChoose(direct, 2);
            BuiltinActions.Refresh();
            checks.Near("direct AndroidSpeed starts at 2x", 2, Time.timeScale);

            Click(3);
            checks.Near("new panel 3x must replace live 2x, not stack", 3, Time.timeScale);
            Pump(5, () => direct.OnLateUpdate());
            checks.Near("3x stays absolute while direct controller keeps ticking", 3, Time.timeScale);

            Click(1);
            checks.Near("new panel 1x must recover 1x from stacked controllers", 1, Time.timeScale);
            Pump(5, () => direct.OnLateUpdate());
            checks.Near("1x stays 1x while direct controller keeps ticking", 1, Time.timeScale);
        }

        private static void HandoffLegacy(Checks checks)
        {
            var legacy = SetupButtons(false, true, 2.5);
            checks.Near("legacy Unity controller is active at 2.5x", 2.5, Time.timeScale);

            Click(3);
            checks.Near("new panel 3x takes over the legacy controller", 3, Time.timeScale);
            Pump(6, () => legacy.OnUpdate());
            checks.Near("3x survives legacy controller ticks (no stacking)", 3, Time.timeScale);

            Click(1);
            checks.Near("new panel 1x takes over the legacy controller", 1, Time.timeScale);
            Pump(6, () => legacy.OnUpdate());
            checks.Near("1x survives legacy controller ticks", 1, Time.timeScale);
        }

        private static void DelegateEngine(Checks checks)
        {
            var legacy = SetupButtons(true, true, 2.5);
            checks.Near("legacy Unity controller is active at 2.5x", 2.5, Time.timeScale);

            Click(3);
            checks.Near("engine-available path delegates 3x", 3, Time.timeScale);
            Pump(4, () => legacy.OnUpdate());
            checks.Near("delegated 3x is stable", 3, Time.timeScale);

            Click(1);
            checks.Near("engine-available path delegates 1x", 1, Time.timeScale);
            Pump(4, () => legacy.OnUpdate());
            checks.Near("delegated 1x is stable", 1, Time.timeScale);
        }

        private static void NoticeOne(Checks checks)
        {
            SetupButtons(false, false, 1);
            Click(3);
            checks.True("3x notice is shown", AndroidApi.Notice.Contains("3×"));
            Click(1);
            checks.Near("1x after 3x", 1, Time.timeScale);
            checks.True("successful 1x replaces the stale 3x notice",
                AndroidApi.Notice == "游戏倍率 1×（Unity）");
            Pump(3);
            checks.Near("1x stays 1x after LocalSpeed deactivates", 1, Time.timeScale);
            checks.True("notice is not overwritten by a later tick",
                AndroidApi.Notice == "游戏倍率 1×（Unity）");
        }

        private static void PauseOne(Checks checks)
        {
            SetupButtons(false, false, 1);
            Click(3);
            Time.timeScale = 0f;
            Pump(2);
            checks.Near("pause is preserved", 0, Time.timeScale);

            Click(1);
            checks.Near("1x while paused keeps the game paused", 0, Time.timeScale);
            checks.True("paused 1x still reports the selected preset",
                AndroidApi.Notice.Contains("1×"));

            Time.timeScale = 1f;
            Pump(3);
            checks.Near("unpausing after 1x does not resume the old 3x", 1, Time.timeScale);
        }

        private static void FaultNotice(Checks checks)
        {
            // No bundled controller here: this is the fallback path where LocalSpeed itself
            // must detect the invalid clock and recover, not a controller that can repair it.
            Time.timeScale = -1f;
            LocalSpeed.Choose(3);
            checks.True("fault notice is the failure message", AndroidApi.Notice.StartsWith("变速失败"));
            checks.Near("fault restores a sane absolute 1x", 1, Time.timeScale);

            LocalSpeed.Choose(3);
            checks.Near("a later choose recovers after the fault", 3, Time.timeScale);
        }

        private static void DirectSingle(Checks checks)
        {
            var config = QuietConfig();
            config.Set("speed", 1.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("SpeedHackMod", direct, config);
            direct.OnInitialize();
            BuiltinActions.Refresh();

            checks.True("direct-only install gets all panel presets", HasAction("toys.speed.3"));
            Click(2);
            checks.Near("direct-only panel 2x", 2, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("direct-only 2x survives its own late update", 2, Time.timeScale);
            Click(3);
            checks.Near("direct-only panel 3x is absolute", 3, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("direct-only 3x survives its own late update", 3, Time.timeScale);
            Click(1);
            checks.Near("direct-only panel 1x", 1, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("direct-only 1x survives its own late update", 1, Time.timeScale);
            checks.True("direct controller remains the primary owner",
                ReferenceEquals(ModRegistry.Get("SpeedHackMod").Mod, direct));
        }

        private static void DirectOtherId(Checks checks)
        {
            var legacy = SetupButtons(false, false, 1);
            var config = QuietConfig();
            config.Set("speed", 2.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("AndroidSpeed", direct, config);
            direct.OnInitialize();
            InvokeChoose(direct, 2);
            BuiltinActions.Refresh();
            checks.Near("direct controller installed under another id starts at 2x", 2, Time.timeScale);

            Click(3);
            checks.Near("panel finds the direct controller by full name", 3, Time.timeScale);
            checks.True("old primary controller was stopped",
                legacy.Context.State == ModState.Stopped && legacy.Context.Mod == null);
            direct.OnLateUpdate();
            checks.Near("3x survives the direct controller's late update", 3, Time.timeScale);

            Click(1);
            checks.Near("panel 1x through the other-id direct controller", 1, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("1x survives the direct controller's late update", 1, Time.timeScale);
        }

        private static void DirectNativeRisk(Checks checks)
        {
            // A cached direct Unity controller plus an available native engine: the direct
            // branch currently runs first and writes timeScale without touching the native
            // clock, so the effective multiplier becomes native * timeScale.
            var config = QuietConfig();
            config.Set("speed", 3.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("AndroidSpeed", direct, config);
            direct.OnInitialize();
            SpeedHack.IsAvailable = true;
            SpeedHack.Speed = 3.0;
            LocalSpeed.Choose(3);
            // The fix deliberately hands this target to the direct Unity owner and
            // normalizes the native clock to 1; the old broken path left native at 3
            // and added Unity 3 on top (effective 9). Assert the ownership explicitly.
            checks.Near("native clock is normalized to 1 while direct Unity owns the target",
                1, SpeedHack.Speed);
            checks.Near("direct controller applies the absolute 3x", 3, Time.timeScale);
            checks.Near("effective multiplier is absolutely 3x, not native x Unity",
                3, SpeedHack.Speed * Time.timeScale);
        }

        private static void NativeOnly(Checks checks)
        {
            SpeedHack.IsAvailable = true;
            SpeedHack.Speed = 1.0;
            LocalSpeed.Choose(3);
            checks.Near("native-only 3x is set in the engine", 3, SpeedHack.Speed);
            checks.Near("native-only path leaves timeScale at 1x", 1, Time.timeScale);
            checks.True("native-only notice names the engine",
                AndroidApi.Notice == "原生倍率 3×");
            LocalSpeed.Choose(1);
            checks.Near("native-only 1x", 1, SpeedHack.Speed);
            checks.Near("native-only 1x leaves timeScale at 1x", 1, Time.timeScale);
        }

        private static void DelegateEngineStale(Checks checks)
        {
            var legacy = SetupButtons(true, true, 2.5);
            checks.Near("active Unity controller at 2.5x", 2.5, Time.timeScale);
            Time.timeScale = 5f; // another writer polluted the clock after the controller applied
            LocalSpeed.Choose(1);
            checks.Near("native-available handoff still has to verify the actual clock", 1, Time.timeScale);
            Pump(3, () => legacy.OnUpdate());
            checks.Near("verified 1x stays 1x", 1, Time.timeScale);
        }

        private static void HandoffReject(Checks checks)
        {
            var legacy = SetupButtons(false, true, 2.5);
            legacy.RejectApply = true;
            Click(3);
            checks.Near("rejected handoff falls back to an absolute 3x", 3, Time.timeScale);
            checks.True("rejected legacy controller is stopped",
                legacy.Context.State == ModState.Stopped && legacy.Context.Mod == null);
            checks.True("stopped legacy controller is inactive", !legacy.IsActive);
            legacy.OnUpdate();
            checks.Near("stopped controller cannot write again", 3, Time.timeScale);
            Click(1);
            checks.Near("fallback 1x after a rejected handoff", 1, Time.timeScale);
        }

        private static void PostStopRefresh(Checks checks)
        {
            var legacy = SetupButtons(false, true, 2.5);
            legacy.RejectApply = true;
            Click(3);
            BuiltinActions.Refresh();
            checks.True("panel presets survive a Stop + Refresh", HasAction("toys.speed.1"));
            checks.True("legacy context is still listed but has no mod",
                ModRegistry.Get("SpeedHackMod") != null && ModRegistry.Get("SpeedHackMod").Mod == null);
            Click(1);
            checks.Near("panel 1x still works after Stop + Refresh", 1, Time.timeScale);
        }

        private static void FocusDelegated(Checks checks)
        {
            var legacy = SetupButtons(false, true, 2.5);
            Click(3);
            checks.Near("delegated 3x", 3, Time.timeScale);
            Application.isFocused = false;
            Pump(3, () => legacy.OnUpdate());
            checks.Near("focus loss after delegation restores absolute 1x", 1, Time.timeScale);
            Application.isFocused = true;
            Pump(3, () => legacy.OnUpdate());
            checks.Near("focus regain after delegation resumes 3x", 3, Time.timeScale);
        }

        private static void NativeAsync(Checks checks)
        {
            // A direct controller plus a native engine whose reset is acknowledged
            // asynchronously: while waiting, the panel must not add a Unity multiplier.
            var config = QuietConfig();
            config.Set("speed", 1.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("AndroidSpeed", direct, config);
            direct.OnInitialize();
            SpeedHack.IsAvailable = true;
            SpeedHack.Speed = 3.0;
            SpeedHack.HoldNativeReset = true;

            LocalSpeed.Choose(3);
            checks.Near("native async: engine still reports 3 while reset is pending", 3, SpeedHack.Speed);
            checks.Near("native async: Unity stays 1 while native reset is pending", 1, Time.timeScale);
            checks.True("native async: reset-pending notice is shown",
                AndroidApi.Notice.Contains("恢复为 1×"));

            SpeedHack.CompleteNativeReset();
            Pump(3);
            checks.Near("native async: after acknowledgement the engine stays 1", 1, SpeedHack.Speed);
            checks.Near("native async: the direct controller applies the absolute target", 3, Time.timeScale);
            checks.Near("native async: effective multiplier is not native x Unity", 3, SpeedHack.Speed * Time.timeScale);
        }

        private static void AlternateOldUi(Checks checks)
        {
            var config = QuietConfig();
            config.Set("speed", 1.0);
            var direct = new AndroidSpeed { Config = config };
            ModRegistry.Add("SpeedHackMod", direct, config);
            direct.OnInitialize();
            BuiltinActions.Refresh();

            RunAction("speed.2");
            checks.Near("old UI 2x", 2, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("old UI 2x survives its late update", 2, Time.timeScale);

            Click(3);
            checks.Near("new panel 3x after old UI 2x", 3, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("new panel 3x survives the old controller tick", 3, Time.timeScale);

            RunAction("speed.1");
            checks.Near("old UI 1x after new panel 3x", 1, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("old UI 1x survives its late update", 1, Time.timeScale);

            Click(2.5);
            checks.Near("new panel 2.5x after old UI 1x", 2.5, Time.timeScale);
            direct.OnLateUpdate();
            checks.Near("new panel 2.5x survives the old controller tick", 2.5, Time.timeScale);
        }

        private static void PauseOneDelegated(Checks checks)
        {
            var legacy = SetupButtons(false, true, 3);
            Time.timeScale = 0f;
            Click(1);
            checks.Near("delegated 1x while paused keeps the game paused", 0, Time.timeScale);
            checks.True("delegated paused 1x reports the selected preset",
                AndroidApi.Notice.Contains("1×"));

            Time.timeScale = 1f;
            Pump(4, () => legacy.OnUpdate());
            checks.Near("unpausing after delegated 1x does not resume the old 3x", 1, Time.timeScale);
        }

        private static void RunAction(string id)
        {
            var action = AndroidApi.GetActions("SpeedHackMod").Find(item => item.Id == id);
            if (action == null) throw new InvalidOperationException("missing action " + id);
            action.Run();
        }

        private static bool HasAction(string id)
        {
            return AndroidApi.GetActions("SpeedHackMod").Exists(item => item.Id == id);
        }

        private static void InvokeChoose(AndroidSpeed direct, double speed)
        {
            var method = typeof(AndroidSpeed).GetMethod("Choose",
                BindingFlags.Instance | BindingFlags.NonPublic, null, new[] { typeof(double) }, null);
            if (method == null) throw new MissingMethodException("AndroidSpeed.Choose(double)");
            method.Invoke(direct, new object[] { speed });
        }

        private sealed class Checks
        {
            private readonly string _scenario;
            private readonly List<string> _failures = new List<string>();
            private int _count;

            public Checks(string scenario) { _scenario = scenario; }

            public void Near(string name, double expected, double actual, double tolerance = .001)
            {
                _count++;
                bool pass = Math.Abs(expected - actual) <= tolerance;
                Console.WriteLine((pass ? "ok   " : "FAIL ") + name + " expected=" + F(expected) + " actual=" + F(actual));
                if (!pass) _failures.Add(name + " expected=" + F(expected) + " actual=" + F(actual));
            }

            public void True(string name, bool value)
            {
                _count++;
                Console.WriteLine((value ? "ok   " : "FAIL ") + name);
                if (!value) _failures.Add(name);
            }

            public int Finish()
            {
                if (_failures.Count == 0)
                {
                    Console.WriteLine("RESULT " + _scenario + " PASS checks=" + _count);
                    return 0;
                }
                Console.WriteLine("RESULT " + _scenario + " FAIL checks=" + _count + " failures=" + _failures.Count);
                return 1;
            }

            private static string F(double value) => value.ToString("0.####", CultureInfo.InvariantCulture);
        }
    }
}
