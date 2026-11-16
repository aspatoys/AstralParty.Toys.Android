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
                AndroidApi.RegisterButton(current.ModId, "toys.status", "运行状态", () => AndroidApi.ShowNotice(ModCatalog.Name(current.ModId) + "：" + current.State));
                var changed = instance?.GetType().GetMethod("OnConfigChanged");
                string key = current.Config.Has("enabled") ? "enabled" : current.Config.Has("Enabled") ? "Enabled" : null;
                if (key != null && changed != null && changed.DeclaringType != typeof(ModBase))
                    AndroidApi.RegisterToggle(current.ModId, "toys.enabled", "模组总开关", () => current.Config.GetBool(key, true), value =>
                    {
                        current.Config.Set(key, value);
                        current.Config.SaveIfDirty();
                        instance.OnConfigChanged();
                    });
                if (current.ModId == "SpeedHackMod")
                {
                    // Reuse the bundled controller's Unity backend, including its fixed-step/UI compensation.
                    foreach (double speed in new[] { 1d, 1.5d, 2d, 2.5d, 3d })
                    {
                        double target = speed;
                        AndroidApi.RegisterButton(current.ModId, "toys.speed." + target, target + "×", () =>
                        {
                            LocalSpeed.Choose(target);
                        });
                    }
                }
            }
        }
    }
}
