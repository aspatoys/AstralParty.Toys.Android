// Startup regression for the shipped passive panel adapter (PanelOnlySpeed),
// SpeedPanel.BeginSession, LocalSpeed and the overlay's toys.speed presets.
// The controller never reads a saved speed or keyboard hotkeys at startup.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using CesiumAndroid;
using CesiumLoader.SDK.Inputs;
using CesiumLoader.SDK.Mods;
using CesiumLoader.SDK.Speed;
using CesiumLoader.SDK.UserInterface;
using CesiumOverlayMod;
using SpeedHackMod;
using UnityEngine;

namespace Startup1x
{
    internal static class Program
    {
        private static string sandbox = "";

        private static readonly string[] Scenarios =
        {
            "cold-unity-1", "cold-unity-2", "cold-unity-3", "cold-unity-10",
            "cold-native-3", "no-input-120-frames",
            "hotkey-alt-plus", "hotkey-delete",
            "config-reload-10", "overlay-2-1", "overlay-native-3-1",
            "restart-after-3", "beginsession-repeat-after-3",
            "late-panel-init-after-selection", "native-reset-wait",
        };

        private static int Main(string[] args)
        {
            if (args.Length == 1 && args[0] == "list")
            {
                foreach (var name in Scenarios) Console.WriteLine(name);
                return 0;
            }
            if (args.Length < 1)
            {
                Console.Error.WriteLine("usage: Startup1x <scenario|list> [sandbox]");
                return 2;
            }
            sandbox = System.IO.Path.GetFullPath(args.Length > 1
                ? args[1]
                : System.IO.Path.Combine(AppContext.BaseDirectory, "sandbox"));
            try
            {
                Run(args[0]);
                Console.WriteLine("RESULT " + args[0] + " PASS");
                return 0;
            }
            catch (Exception error)
            {
                Console.WriteLine("RESULT " + args[0] + " FAIL " + error.Message);
                return 1;
            }
        }

        private static void Run(string scenario)
        {
            switch (scenario)
            {
                case "cold-unity-1": Cold("unity", 1); break;
                case "cold-unity-2": Cold("unity", 2); break;
                case "cold-unity-3": Cold("unity", 3); break;
                case "cold-unity-10": Cold("unity", 10); break;
                case "cold-native-3": Cold("native", 3); break;
                case "no-input-120-frames": NoInputFrames(); break;
                case "hotkey-alt-plus": HotkeyAltPlus(); break;
                case "hotkey-delete": HotkeyDelete(); break;
                case "config-reload-10": ConfigReload(); break;
                case "overlay-2-1": OverlayTwoThenOne(false); break;
                case "overlay-native-3-1": OverlayTwoThenOne(true); break;
                case "restart-after-3": RestartAfterThree(); break;
                case "beginsession-repeat-after-3": BeginSessionRepeatAfterThree(); break;
                case "late-panel-init-after-selection": LatePanelInitAfterSelection(); break;
                case "native-reset-wait": NativeResetWait(); break;
                default: throw new ArgumentException("unknown scenario " + scenario);
            }
        }

        // mode "unity" = no native engine (the actual direct/cached install), so
        // the local Unity scale is the observable clock. mode "native" = engine
        // available; the effective speed is native x timeScale and timeScale
        // itself must stay at 1 for native-only control.
        private static void Cold(string mode, double speed)
        {
            bool native = mode == "native";
            var panel = Start(speed, native);
            ExpectEffectiveOne("after-init", 0);
            Pump(panel, 60);
            ExpectEffectiveOne("after-60-frames", 60);
            ExpectNoAccelerationWrites();
        }

        private static void NoInputFrames()
        {
            var panel = Start(3, false);
            for (int frame = 1; frame <= 120; frame++)
            {
                Pump(panel, 1);
                ExpectEffectiveOne("frame-" + frame, frame);
            }
            ExpectNoAccelerationWrites();
        }

        private static void HotkeyAltPlus()
        {
            var panel = Start(3, false);
            ExpectEffectiveOne("after-init", 0);
            InputService.SetHeld(KeyCode.LeftAlt, true);
            InputService.SetHeld(KeyCode.Equals, true);
            for (int frame = 1; frame <= 10; frame++)
            {
                Pump(panel, 1);
                ExpectEffectiveOne("alt-plus-frame-" + frame, frame);
            }
            InputService.ClearKeys();
            Pump(panel, 10);
            ExpectEffectiveOne("after-release", 20);
            ExpectNoAccelerationWrites();
        }

        private static void HotkeyDelete()
        {
            var panel = Start(3, true);
            ExpectEffectiveOne("after-init", 0);
            InputService.Press(KeyCode.Delete);
            InputService.SetHeld(KeyCode.Delete, true);
            Pump(panel, 1);
            InputService.SetHeld(KeyCode.Delete, false);
            Pump(panel, 10);
            ExpectEffectiveOne("after-delete", 11);
            ExpectNoAccelerationWrites();
        }

        private static void ConfigReload()
        {
            var panel = Start(1, false);
            ExpectEffectiveOne("after-init", 0);
            panel.Config.Set("speed", 10d);
            panel.OnConfigChanged();
            Pump(panel, 30);
            ExpectEffectiveOne("after-reload", 30);
            ExpectNoAccelerationWrites();
        }

        private static void OverlayTwoThenOne(bool native)
        {
            var panel = Start(1, native);
            ExpectEffectiveOne("after-init", 0);
            BuiltinActions.Refresh();
            Click("toys.speed.2");
            Pump(panel, 5);
            Expect("overlay-2", 2);
            if (native)
            {
                if (Math.Abs(Time.timeScale - 1f) > .0001f)
                    throw new InvalidOperationException("FAIL native-timeScale-stays-1 actual=" + Time.timeScale);
            }
            Click("toys.speed.1");
            Pump(panel, 5);
            ExpectEffectiveOne("overlay-1", 10);
        }

        private static void RestartAfterThree()
        {
            // Simulates a fresh process whose saved config remembers 3x.
            var panel = Start(3, false);
            ExpectEffectiveOne("cold-after-previous-3", 0);
            Pump(panel, 30);
            ExpectEffectiveOne("cold-after-previous-3-frames", 30);
            ExpectNoAccelerationWrites();
        }

        private static void BeginSessionRepeatAfterThree()
        {
            var panel = Start(1, false);
            BuiltinActions.Refresh();
            Click("toys.speed.3");
            Pump(panel, 3);
            Expect("after-user-3", 3);
            // Initialization may arrive late from the loader; the process-wide
            // guard must not reset an already selected 3x back to 1x.
            SpeedPanel.BeginSession();
            SpeedPanel.BeginSession();
            Pump(panel, 10);
            Expect("after-repeat-beginsession", 3);
        }

        private static void LatePanelInitAfterSelection()
        {
            ResetWorld(false);
            var config = NewConfig(1);
            var panel = new PanelOnlySpeed { Config = config };
            var context = ModRegistry.Add("SpeedHackMod", panel, config);
            panel.Context = context;
            // The overlay initializes first (guard set), the user selects 3x, and
            // only then does the loader deliver PanelOnlySpeed.OnLoad/OnInitialize.
            SpeedPanel.BeginSession();
            BuiltinActions.Refresh();
            Click("toys.speed.3");
            LocalSpeed.Tick();
            Expect("before-panel-init", 3);
            panel.OnLoad();
            panel.OnInitialize();
            Pump(panel, 10);
            Expect("after-late-panel-init", 3);
        }

        private static void NativeResetWait()
        {
            var panel = StartCore(1, true, nativeSpeed: 3, holdNativeReset: true);
            // The engine cannot confirm the reset yet: Unity must already be 1x,
            // while the effective clock is still the outstanding native 3x.
            if (Math.Abs(Time.timeScale - 1f) > .0001f)
                throw new InvalidOperationException("FAIL waiting-unity-not-1 actual=" + Time.timeScale);
            if (SpeedHack.Speed < 3)
                throw new InvalidOperationException("FAIL waiting-native-lost actual=" + SpeedHack.Speed);
            if (!AndroidApi.Notice.Contains("正在将原生时钟恢复为 1×"))
                throw new InvalidOperationException("FAIL waiting-notice actual=" + AndroidApi.Notice);
            SpeedHack.CompleteNativeReset();
            Pump(panel, 6);
            ExpectEffectiveOne("after-native-confirmed", 6);
        }

        // ------------------------------------------------------------------
        // Harness
        // ------------------------------------------------------------------
        private static PanelOnlySpeed Start(double savedSpeed, bool native)
        {
            return StartCore(savedSpeed, native, 1, false);
        }

        private static PanelOnlySpeed StartCore(double savedSpeed, bool native, double nativeSpeed, bool holdNativeReset)
        {
            ResetWorld(native);
            SpeedHack.Speed = nativeSpeed;
            SpeedHack.HoldNativeReset = holdNativeReset;
            var config = NewConfig(savedSpeed);
            var panel = new PanelOnlySpeed { Config = config };
            var context = ModRegistry.Add("SpeedHackMod", panel, config);
            panel.Context = context;
            panel.OnLoad();
            panel.OnInitialize();
            return panel;
        }

        private static ModConfig NewConfig(double savedSpeed)
        {
            var config = new ModConfig();
            config.Set("speed", savedSpeed);
            config.Set("accelerationMode", "unity");
            config.Set("optimizeFixedUpdates", false);
            config.Set("accelerateUiAnimations", false);
            config.Set("accelerateIndependentAnimations", false);
            config.Set("notify", false);
            return config;
        }

        private static void ResetWorld(bool native)
        {
            System.IO.Directory.CreateDirectory(sandbox);
            Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", sandbox);
            Application.persistentDataPath = sandbox;
            ModRegistry.Clear();
            AndroidApi.ClearActions();
            InputService.ClearKeys();
            InputService.IsAvailable = true;
            UiService.ShouldBlockGameInput = false;
            Application.isFocused = true;
            SpeedHack.IsAvailable = native;
            SpeedHack.Speed = 1;
            SpeedHack.HoldNativeReset = false;
            Time.realtimeSinceStartup = 10f;
            Time.fixedDeltaTime = .02f;
            Time.maximumDeltaTime = .333f;
            Time.timeScale = 1f;
            Time.TimeScaleWrites.Clear();
        }

        private static void Pump(PanelOnlySpeed panel, int frames)
        {
            for (int i = 0; i < frames; i++)
            {
                Time.realtimeSinceStartup += .25f;
                panel.OnUpdate();
                panel.OnLateUpdate();
                // The overlay controller owns this call in production; the passive
                // adapter must not tick, but LocalSpeed still needs its watchdog.
                LocalSpeed.Tick();
                InputService.EndFrame();
            }
        }

        private static void Click(string id)
        {
            var action = AndroidApi.GetActions("SpeedHackMod")
                .FirstOrDefault(item => string.Equals(item.Id, id, StringComparison.Ordinal));
            if (action == null)
                throw new InvalidOperationException("missing action " + id);
            if (action.Run == null)
                throw new InvalidOperationException("action has no callback " + id);
            action.Run();
        }

        private static double Effective()
        {
            double native = SpeedHack.IsAvailable ? SpeedHack.Speed : 1.0;
            return Time.timeScale * native;
        }

        private static void ExpectEffectiveOne(string where, int frame)
        {
            double actual = Effective();
            if (Math.Abs(actual - 1.0) > .0001)
                throw new InvalidOperationException(
                    "FAIL " + where + " frame=" + frame + " expected=1 actual=" + actual.ToString("0.###", CultureInfo.InvariantCulture));
        }

        private static void Expect(string where, double expected)
        {
            double actual = Effective();
            if (Math.Abs(actual - expected) > .0001)
                throw new InvalidOperationException(
                    "FAIL " + where + " expected=" + expected.ToString("0.###", CultureInfo.InvariantCulture) +
                    " actual=" + actual.ToString("0.###", CultureInfo.InvariantCulture));
        }

        private static void ExpectNoAccelerationWrites()
        {
            var bad = Time.TimeScaleWrites.Where(value => value > 1.0001f).ToList();
            if (bad.Count > 0)
                throw new InvalidOperationException(
                    "FAIL no-transient-acceleration expected no write >1 actual=" +
                    string.Join(",", bad.Select(value => value.ToString("0.###", CultureInfo.InvariantCulture))));
        }
    }
}
