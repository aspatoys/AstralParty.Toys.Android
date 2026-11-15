using System;
using System.Reflection;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;
using CesiumLoader.SDK.Speed;
using UnityEngine;

namespace CesiumOverlayMod
{
    /// <summary>The direct installer's Unity backend, used only without a native speed engine.</summary>
    internal static class LocalSpeed
    {
        private static readonly AbsoluteSpeedScale scale = new AbsoluteSpeedScale(UnityScaleAccess.Read, UnityScaleAccess.Write);
        private static readonly FixedStepBudget fixedStep = new FixedStepBudget(UnityFixedStepAccess.ReadStep, UnityFixedStepAccess.ReadMaximum, UnityFixedStepAccess.WriteStep);
        private static readonly FairyUiTimeScaleBridge ui = new FairyUiTimeScaleBridge();
        private static readonly IndependentAnimationBridge independent = new IndependentAnimationBridge(UnityAnimationDiscovery.FindType, UnityAnimationDiscovery.Find);
        private static double target = 1;
        private static float nextEngineCheck;
        private static bool active, uiFailed, independentFailed, fixedFailed, faulted;
        private static Action<double> delegatedSetter;
        private static ModBase delegatedOwner;
        private static bool wasFocused = true, waitingForNative;

        internal static double SelectedSpeed => target;

        internal static void Choose(double speed)
        {
            if (double.IsNaN(speed) || double.IsInfinity(speed)) throw new ArgumentOutOfRangeException(nameof(speed));
            target = Math.Max(1, Math.Min(10, speed));
            if (TryUseExistingController(target)) return;
            delegatedSetter = null;
            delegatedOwner = null;
            active = true;
            faulted = false;
            independent.RegisterUiRoot = root => ui.RegisterExternalRoot(root);
            Tick();
            if (!faulted && (active || target <= 1.0001)) AndroidApi.ShowNotice("游戏倍率 " + target + "×（Unity）");
        }

        private static bool TryUseExistingController(double speed)
        {
            bool native = SpeedHack.IsAvailable;
            // The direct APK already owns a Unity backend and registers its own API controls.
            // Route every panel preset to that same controller instead of starting another writer.
            foreach (var context in ModRegistry.All)
            {
                var direct = context.Mod as ModBase;
                if (direct == null || direct.GetType().FullName != "SpeedHackMod.AndroidSpeed") continue;
                var choose = direct.GetType().GetMethod("Choose", BindingFlags.Instance | BindingFlags.NonPublic,
                    null, new[] { typeof(double) }, null);
                if (choose == null) continue;
                ReleaseLocal();
                choose.Invoke(direct, new object[] { 1d });
                NormalizeUnity();
                if (!NormalizeNative(native)) return true;
                var previous = ModRegistry.Get("SpeedHackMod")?.Mod as ModBase;
                if (previous != null && !ReferenceEquals(previous, direct)) ModHost.Stop(previous);
                Action<double> setter = value => SetAbsolute(direct, choose, value);
                setter(speed);
                if (UnityScaleAccess.Read() != 0 && Math.Abs(UnityScaleAccess.Read() - speed) >= .0001)
                {
                    ModHost.Stop(direct);
                    continue;
                }
                SetDelegated(direct, setter, speed);
                AndroidApi.ShowNotice("游戏倍率 " + speed + "×（原变速控制器）");
                return true;
            }

            var current = ModRegistry.Get("SpeedHackMod");
            var instance = current?.Mod as ModBase;
            // The bundled Android adapter is a passive panel entry, not another speed engine.
            if (instance?.GetType().FullName == "SpeedHackMod.PanelOnlySpeed") instance = null;
            if (instance != null)
            {
                var apply = instance.GetType().GetMethod("Apply", BindingFlags.Instance | BindingFlags.NonPublic,
                    null, new[] { typeof(double) }, null);
                if (apply != null && (native || UnityScaleAccess.Read() != 0))
                {
                    ReleaseLocal();
                    apply.Invoke(instance, new object[] { 1d });
                    NormalizeUnity();
                    if (!NormalizeNative(native)) return true;
                    current.Config.Set("accelerationMode", "unity");
                    current.Config.SaveIfDirty();
                    instance.OnConfigChanged();
                    var result = SetAbsolute(instance, apply, speed);
                    // An uninitialised desktop backend may accept a target without applying it.
                    // In that case stop its future callbacks before starting the Android fallback.
                    if (!(result is bool && !(bool)result) &&
                        (UnityScaleAccess.Read() == 0 || Math.Abs(UnityScaleAccess.Read() - speed) < .0001))
                    {
                        SetDelegated(instance, value => SetAbsolute(instance, apply, value), speed);
                        AndroidApi.ShowNotice("游戏倍率 " + speed + "×（原变速控制器）");
                        return true;
                    }
                }
                ModHost.Stop(instance);
            }
            if (native)
            {
                ReleaseLocal();
                NormalizeUnity();
                bool applied = SpeedHack.SetSpeed(speed);
                if (!applied && speed <= 1.0001)
                {
                    if (NormalizeNative(true)) AndroidApi.ShowNotice("原生倍率 1×");
                    return true;
                }
                if (applied) SetDelegated(null, value =>
                {
                    NormalizeUnity();
                    if (!SpeedHack.SetSpeed(value)) throw new InvalidOperationException("原生倍率写入失败");
                }, speed);
                if (applied && speed <= 1.0001 && Math.Abs(SpeedHack.Speed - 1) >= .0001)
                {
                    waitingForNative = true;
                    AndroidApi.ShowNotice("正在将原生时钟恢复为 1×，确认后应用所选倍率");
                    return true;
                }
                AndroidApi.ShowNotice(applied ? "原生倍率 " + speed + "×" : "原生变速尚未就绪，请稍后重试");
                return true;
            }
            return false;
        }

        private static object SetAbsolute(ModBase owner, MethodInfo setter, double value)
        {
            // Release the previous controller multiplier before requesting an absolute target.
            setter.Invoke(owner, new object[] { 1d });
            NormalizeUnity();
            return setter.Invoke(owner, new object[] { value });
        }

        private static void NormalizeUnity()
        {
            if (UnityScaleAccess.Read() != 0) UnityScaleAccess.Write(1);
        }

        private static bool NormalizeNative(bool available)
        {
            if (!available) { waitingForNative = false; return true; }
            double current = SpeedHack.Speed;
            if (Math.Abs(current - 1) < .0001) { waitingForNative = false; return true; }
            // While the native reset is awaiting acknowledgement, suspend the Unity owner too.
            // Otherwise its previous target would still multiply the outstanding native clock.
            if (delegatedOwner != null && delegatedSetter != null) delegatedSetter(1);
            SpeedHack.SetSpeed(1);
            if (Math.Abs(SpeedHack.Speed - 1) < .0001) { waitingForNative = false; return true; }
            waitingForNative = true;
            AndroidApi.ShowNotice("正在将原生时钟恢复为 1×，确认后应用所选倍率");
            return false;
        }

        private static void SetDelegated(ModBase owner, Action<double> setter, double speed)
        {
            delegatedOwner = owner;
            delegatedSetter = setter;
            waitingForNative = false;
            wasFocused = Application.isFocused;
            if (!wasFocused) setter(1);
        }

        private static double ReadDelegatedTarget()
        {
            if (delegatedOwner == null) return SpeedHack.Speed > 0 ? SpeedHack.Speed : target;
            foreach (string name in new[] { "speed", "_unityTargetSpeed", "_target" })
            {
                var field = delegatedOwner.GetType().GetField(name, BindingFlags.Instance | BindingFlags.NonPublic);
                if (field == null || field.FieldType != typeof(double)) continue;
                double value = (double)field.GetValue(delegatedOwner);
                if (!double.IsNaN(value) && !double.IsInfinity(value) && value >= 1 && value <= 100) return value;
            }
            return target;
        }

        private static void ReleaseLocal()
        {
            if (!active) return;
            Restore();
            active = false;
        }

        internal static void Tick()
        {
            if (waitingForNative) { TryUseExistingController(target); return; }
            if (delegatedSetter != null)
            {
                bool focused = Application.isFocused;
                if (focused != wasFocused)
                {
                    if (!focused) target = ReadDelegatedTarget();
                    delegatedSetter(focused ? target : 1);
                    wasFocused = focused;
                }
                if (delegatedOwner != null && Time.realtimeSinceStartup >= nextEngineCheck)
                {
                    nextEngineCheck = Time.realtimeSinceStartup + 1;
                    NormalizeNative(SpeedHack.IsAvailable);
                }
                return;
            }
            if (!active) return;
            // Never combine native clock acceleration with our Unity multiplier.
            if (Time.realtimeSinceStartup >= nextEngineCheck)
            {
                nextEngineCheck = Time.realtimeSinceStartup + 1;
                if (TryUseExistingController(target)) return;
            }
            if (!Application.isFocused) { Restore(); return; }
            try
            {
                scale.Synchronize(target);
                var config = ModRegistry.Get("SpeedHackMod")?.Config;
                if (!fixedFailed) try
                {
                    if (config?.GetBool("optimizeFixedUpdates", true) ?? true) fixedStep.Synchronize(target);
                    else fixedStep.Restore();
                }
                catch (Exception error) { fixedFailed = true; fixedStep.Restore(); AndroidApi.LogError(error.ToString()); }
                if (target <= 1.0001 || UnityScaleAccess.Read() == 0)
                {
                    ui.Restore(); independent.Restore();
                    if (target <= 1.0001 && UnityScaleAccess.Read() != 0) active = false;
                    return;
                }
                if (!independentFailed) try
                {
                    if (config?.GetBool("accelerateIndependentAnimations", true) ?? true) independent.Tick(Time.realtimeSinceStartup, target);
                    else independent.Restore();
                }
                catch (Exception error) { independentFailed = true; independent.Restore(); AndroidApi.LogError(error.ToString()); }
                if (!uiFailed) try
                {
                    if (config?.GetBool("accelerateUiAnimations", true) ?? true) ui.Tick(Time.realtimeSinceStartup);
                    else ui.Restore();
                }
                catch (Exception error) { uiFailed = true; ui.Restore(); AndroidApi.LogError(error.ToString()); }
            }
            catch (Exception error)
            {
                active = false;
                faulted = true;
                Restore();
                AndroidApi.LogError(error.ToString());
                AndroidApi.ShowNotice("变速失败，已尝试恢复，详见模组日志");
            }
        }

        internal static void Restore()
        {
            try { ui.Restore(); }
            finally { try { independent.Restore(); } finally { try { fixedStep.Restore(); } finally { scale.Restore(); } } }
        }
    }
}
