using System;
using System.Reflection;
using CesiumLoader.SDK.Runtime;
using FairyGUI;
using UnityEngine;
namespace FreeCameraMod
{
    internal sealed class PinchInput
    {
        private readonly PinchGesture gesture = new PinchGesture();
        private PropertyInfo count, position, phase, finger;
        private MethodInfo getTouch;
        private float retryAt;
        internal void Block() { gesture.Block(); }
        internal bool Sample(out float zoom)
        {
            zoom = 0;
            if (!Application.isFocused) { gesture.Block(); return true; }
            try
            {
                if (count == null)
                {
                    if (Time.realtimeSinceStartup < retryAt) return false;
                    retryAt = Time.realtimeSinceStartup + 2;
                    var input = RuntimeAssemblyService.FindType("UnityEngine.Input");
                    count = input?.GetProperty("touchCount", BindingFlags.Static | BindingFlags.Public);
                    getTouch = input?.GetMethod("GetTouch", new[] { typeof(int) });
                    position = getTouch?.ReturnType.GetProperty("position");
                    phase = getTouch?.ReturnType.GetProperty("phase");
                    finger = getTouch?.ReturnType.GetProperty("fingerId");
                }
                int touches = count == null ? 0 : Convert.ToInt32(count.GetValue(null, null));
                if (touches != 2) return gesture.Step(touches, 0, 0, 0, 0, 0, 0, 1, false, out zoom);
                var a = getTouch.Invoke(null, new object[] { 0 });
                var b = getTouch.Invoke(null, new object[] { 1 });
                var pa = (Vector2)position.GetValue(a, null); var pb = (Vector2)position.GetValue(b, null);
                int ia = Convert.ToInt32(finger.GetValue(a, null)), ib = Convert.ToInt32(finger.GetValue(b, null));
                string sa = phase.GetValue(a, null).ToString(), sb = phase.GetValue(b, null).ToString();
                bool blocked = sa == "Ended" || sa == "Canceled" || sb == "Ended" || sb == "Canceled" || OverUi(ia) || OverUi(ib);
                return gesture.Step(touches, ia, ib, pa.x, pa.y, pb.x, pb.y,
                    Math.Min(Screen.width, Screen.height), blocked, out zoom);
            }
            catch { gesture.Block(); return true; }
        }
        private static bool OverUi(int id)
        {
            var target = GRoot.inst?.touchTarget;
            while (target != null)
            {
                if ((target.name ?? "").StartsWith("CesiumAndroid_") || target is GButton || target is GTextInput || target is GList) return true;
                target = target.parent;
            }
            // FairyGUI can have a different hit target for each finger.
            var stage = Stage.inst;
            var method = stage.GetType().GetMethod("GetTouchTarget", new[] { typeof(int) });
            object display = method?.Invoke(stage, new object[] { id });
            for (int depth = 0; display != null && depth < 30; depth++)
            {
                var type = display.GetType();
                var owner = (type.GetField("gOwner")?.GetValue(display) ?? type.GetProperty("gOwner")?.GetValue(display, null)) as GObject;
                while (owner != null)
                {
                    if ((owner.name ?? "").StartsWith("CesiumAndroid_") || owner is GButton || owner is GTextInput || owner is GList) return true;
                    owner = owner.parent;
                }
                display = type.GetProperty("parent")?.GetValue(display, null);
            }
            return false;
        }
    }
}
