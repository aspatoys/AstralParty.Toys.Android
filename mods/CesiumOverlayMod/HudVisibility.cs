using System;
using System.Reflection;
using CesiumAndroid;
using CesiumLoader.SDK.Mods;

namespace CesiumOverlayMod
{
    /// <summary>
    /// Touch entry for the shipped keyboard-only HUD mod. Own a separate instance of its
    /// existing hider so its original hotkey, configuration and visibility ownership remain intact.
    /// No new game-object discovery or whole-root hiding is introduced here.
    /// </summary>
    internal static class HudVisibility
    {
        private const BindingFlags Fields = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
        private static ModBase owner;
        private static object hider;
        private static FieldInfo settings;
        private static MethodInfo apply, clear;
        internal static bool Hidden { get; private set; }

        internal static bool CanUse(ModBase instance)
        {
            if (instance == null || instance.GetType().FullName != "HideHudMod.HideHudController") return false;
            var context = ModRegistry.Get("HideHudMod");
            if (context == null || !ReferenceEquals(context.Mod, instance)) return false;
            string state = context.State.ToString().ToLowerInvariant();
            if (state != "initialized" && state != "enabled") return false;
            return instance.GetType().GetField("_hider", Fields) != null && instance.GetType().GetField("_settings", Fields) != null;
        }

        internal static bool Get(ModBase instance)
        {
            if (!CanUse(instance)) throw new InvalidOperationException("隐藏 HUD 模组尚未就绪");
            return Hidden && ReferenceEquals(owner, instance);
        }

        internal static void Set(ModBase instance, bool value)
        {
            if (!value) { Restore(); AndroidApi.ShowNotice("已关闭 HUD 隐藏"); return; }
            if (!CanUse(instance)) throw new InvalidOperationException("当前隐藏 HUD 模组不支持触屏控制");
            if (!ReferenceEquals(owner, instance))
            {
                Restore();
                Type type = instance.GetType().GetField("_hider", Fields).FieldType;
                var candidate = Activator.CreateInstance(type, true);
                var candidateApply = type.GetMethod("Apply", Fields);
                var candidateClear = type.GetMethod("Clear", Fields);
                if (candidateApply == null || candidateClear == null) throw new MissingMethodException("隐藏 HUD 操作接口不可用");
                owner = instance;
                hider = candidate;
                settings = instance.GetType().GetField("_settings", Fields);
                apply = candidateApply;
                clear = candidateClear;
            }
            ReadSettings(); // Do not light up the switch for an empty or unavailable hiding scope.
            Hidden = true;
            Tick();
            if (Hidden) AndroidApi.ShowNotice("已开启 HUD 隐藏，进入对局后按配置生效");
        }

        private static object ReadSettings()
        {
            object value = settings?.GetValue(owner);
            if (value == null) throw new InvalidOperationException("隐藏 HUD 配置尚未加载，请稍后重试");
            foreach (string name in new[] { "HideAllHud", "HideHandCards", "HideMonsterDisplay", "HideAvatars", "HideOther", "HideTeammateIdsOnly" })
            {
                var field = value.GetType().GetField(name, Fields);
                if (field != null && field.FieldType == typeof(bool) && (bool)field.GetValue(value)) return value;
            }
            throw new InvalidOperationException("隐藏范围为空，请先在工具箱中配置隐藏项目");
        }

        internal static void Tick()
        {
            if (!Hidden) return;
            try
            {
                var current = ModRegistry.Get("HideHudMod");
                if (current == null || !ReferenceEquals(current.Mod, owner)) { Restore(); return; }
                string state = current.State.ToString().ToLowerInvariant();
                if (state == "stopped" || state == "stopping" || state == "failed" || state == "faulted") { Restore(); return; }
                apply.Invoke(hider, new[] { ReadSettings(), (object)true });
            }
            catch (Exception error)
            {
                AndroidApi.LogError("隐藏 HUD：" + error);
                Restore();
                AndroidApi.ShowNotice("隐藏 HUD 暂不可用，已尝试恢复界面");
            }
        }

        internal static void Restore()
        {
            try { if (hider != null && clear != null) clear.Invoke(hider, new object[0]); }
            catch (Exception error) { AndroidApi.LogError("恢复 HUD：" + error); }
            finally { Hidden = false; owner = null; hider = null; settings = null; apply = null; clear = null; }
        }
    }
}
