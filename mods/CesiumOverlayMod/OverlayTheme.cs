using System;
using System.IO;
using System.Reflection;
using CesiumAndroid;
using UnityEngine;

namespace CesiumOverlayMod
{
    internal static class OverlayTheme
    {
        internal static bool Dark = true;
        internal static bool Poll()
        {
            bool dark = Dark;
            try
            {
                string path = AndroidApi.GetLoaderPath("data", "toys-appearance.txt");
                string[] values = File.Exists(path) ? File.ReadAllText(path).Trim().Split('\n') : new[] { "system", "dark" };
                string mode = values[0].Trim();
                dark = mode == "dark" || (mode != "light" && values.Length > 1 && values[1].Trim() == "dark");
                if (mode != "dark" && mode != "light")
                {
                    // Read the game's Android configuration so SYSTEM keeps working while Toys is closed.
                    dark = SystemDark();
                }
            }
            catch { /* JNI/AOT unavailable: retain the last resolved theme supplied by Toys. */ }
            bool changed = dark != Dark;
            Dark = dark;
            return changed;
        }

        internal static Color Background => Dark ? Hex(0x121420, .94f) : Hex(0xF6F5F9, .96f);
        internal static Color Panel => Dark ? Hex(0x242839, .78f) : Hex(0xFFFFFF, .90f);
        internal static Color Selected => Dark ? Hex(0x493048, .55f) : Hex(0xF9DAE8, .58f);
        internal static Color Border => Dark ? Hex(0x464B61, .65f) : Hex(0xD6D1DC, .72f);
        internal static Color Text => Dark ? Hex(0xF1EDF7) : Hex(0x1F2233);
        internal static Color Accent => Dark ? Hex(0xFF4A96) : Hex(0xD6005F);
        internal static Color Muted => Dark ? Hex(0xB2B6C9) : Hex(0x626579);
        internal static Color SpeedButton => Dark ? Hex(0x2D4E75) : Hex(0xDDEBFA);
        internal static Color SpeedPrimary => Dark ? Hex(0x3D94F4) : Hex(0x2275CF);
        internal static Color SpeedPrimaryText => Hex(0xFFFFFF);
        internal static Color SpeedValue => Dark ? Hex(0x77D49B) : Hex(0x187449);
        private static Color Hex(int rgb, float alpha = 1f) => new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);

        private static bool SystemDark()
        {
            // Resolve the Android-only module at runtime; desktop reference DLLs need not include it.
            Type javaClass = Type.GetType("UnityEngine.AndroidJavaClass, UnityEngine.AndroidJNIModule");
            Type javaObject = Type.GetType("UnityEngine.AndroidJavaObject, UnityEngine.AndroidJNIModule");
            using (var player = (IDisposable)Activator.CreateInstance(javaClass, new object[] { "com.unity3d.player.UnityPlayer" }))
            using (var activity = (IDisposable)JavaGeneric(player, "GetStatic", javaObject, "currentActivity"))
            using (var resources = (IDisposable)JavaGeneric(activity, "Call", javaObject, "getResources", new object[0]))
            using (var configuration = (IDisposable)JavaGeneric(resources, "Call", javaObject, "getConfiguration", new object[0]))
                return ((int)JavaGeneric(configuration, "Get", typeof(int), "uiMode") & 0x30) == 0x20;
        }

        private static object JavaGeneric(object instance, string name, Type result, params object[] arguments)
        {
            foreach (var method in instance.GetType().GetMethods(BindingFlags.Public | BindingFlags.Instance))
            {
                var parameters = method.GetParameters();
                if (method.Name != name || !method.IsGenericMethodDefinition || parameters.Length != arguments.Length) continue;
                if (parameters[0].ParameterType != typeof(string)) continue;
                if (parameters.Length == 2 && parameters[1].ParameterType != typeof(object[])) continue;
                return method.MakeGenericMethod(result).Invoke(instance, arguments);
            }
            throw new MissingMethodException(name);
        }
    }
}
