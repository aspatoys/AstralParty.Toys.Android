// Material You palette for the overlay. The APK side writes data/toys-appearance.txt:
//   line 0 : system | light | dark          (theme preference)
//   line 1 : dark | light                   (resolved scheme, already accounting for system)
//   line n : light.role=#AARRGGBB           (Material 3 token, light scheme)
//            dark.role=#AARRGGBB            (same token, dark scheme)
//            role=#AARRGGBB                 (legacy prefixless form: applies to both)
// Only the first two lines are required, so older loaders keep working; every token falls
// back to a hand-tuned Material 3 baseline built from the app's own colour scheme.
//
// Both schemes' tokens are normally present. The overlay keeps polling while the player flips the
// system dark toggle from quick settings, and the app cannot rewrite this file at that moment, so
// resolving the scheme here and picking the matching block is what makes the toggle take effect
// immediately. Prefixless keys win so an older APK can still override everything.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using CesiumAndroid;
using UnityEngine;

namespace CesiumOverlayMod
{
    internal static class OverlayTheme
    {
        private static readonly Dictionary<string, Color> lightTokens = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);
        private static readonly Dictionary<string, Color> darkTokens = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);
        private static readonly Dictionary<string, Color> anyTokens = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);

        internal static bool Dark = true;

        internal static bool Poll()
        {
            string path = AndroidApi.GetLoaderPath("data", "toys-appearance.txt");
            try
            {
                var parsedLight = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);
                var parsedDark = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);
                var parsedAny = new Dictionary<string, Color>(StringComparer.OrdinalIgnoreCase);
                string mode = "system";
                bool dark = true;
                if (File.Exists(path))
                {
                    string[] values = File.ReadAllText(path).Replace("\r", string.Empty).Split('\n');
                    if (values.Length > 0 && values[0].Trim().Length > 0) mode = values[0].Trim().ToLowerInvariant();
                    if (values.Length > 1 && values[1].Trim().Length > 0) dark = values[1].Trim() != "light";
                    for (int i = 2; i < values.Length; i++)
                    {
                        string line = values[i].Trim();
                        int split = line.IndexOf('=');
                        if (split <= 0) continue;
                        string name = line.Substring(0, split).Trim();
                        if (!TryParse(line.Substring(split + 1).Trim(), out Color colour)) continue;
                        if (name.StartsWith("light.", StringComparison.OrdinalIgnoreCase))
                            parsedLight[name.Substring(6)] = colour;
                        else if (name.StartsWith("dark.", StringComparison.OrdinalIgnoreCase))
                            parsedDark[name.Substring(5)] = colour;
                        else
                            parsedAny[name] = colour;
                    }
                    if (mode == "dark") dark = true;
                    else if (mode == "light") dark = false;
                    else dark = SystemDark();
                }
                else
                {
                    dark = SystemDark();
                }

                bool changed = dark != Dark;
                Dark = dark;
                if (Replace(lightTokens, parsedLight)) changed = true;
                if (Replace(darkTokens, parsedDark)) changed = true;
                if (Replace(anyTokens, parsedAny)) changed = true;
                return changed;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>Copies <paramref name="source"/> over <paramref name="target"/>; true when it differed.</summary>
        private static bool Replace(Dictionary<string, Color> target, Dictionary<string, Color> source)
        {
            bool same = target.Count == source.Count;
            if (same)
            {
                foreach (var pair in source)
                {
                    if (!target.TryGetValue(pair.Key, out Color existing) || !Same(existing, pair.Value)) { same = false; break; }
                }
            }
            if (same) return false;
            target.Clear();
            foreach (var pair in source) target[pair.Key] = pair.Value;
            return true;
        }

        private static bool Same(Color left, Color right)
        {
            return Mathf.Abs(left.r - right.r) <= .001f && Mathf.Abs(left.g - right.g) <= .001f
                && Mathf.Abs(left.b - right.b) <= .001f && Mathf.Abs(left.a - right.a) <= .001f;
        }

        private static bool TryParse(string value, out Color colour)
        {
            colour = default;
            if (string.IsNullOrEmpty(value)) return false;
            if (value[0] == '#') value = value.Substring(1);
            if (value.Length != 6 && value.Length != 8) return false;
            if (!uint.TryParse(value, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out uint packed)) return false;
            if (value.Length == 6)
            {
                colour = Hex((int)packed, 1f);
                return true;
            }
            colour = new Color(((packed >> 16) & 255) / 255f, ((packed >> 8) & 255) / 255f, (packed & 255) / 255f, ((packed >> 24) & 255) / 255f);
            return true;
        }

        private static Color Token(string name, int darkRgb, float darkAlpha, int lightRgb, float lightAlpha)
        {
            // A prefixless key is a deliberate override for both schemes and outranks the per-scheme one.
            if (anyTokens.TryGetValue(name, out Color value)) return value;
            if (Dark)
            {
                if (darkTokens.TryGetValue(name, out value)) return value;
            }
            else if (lightTokens.TryGetValue(name, out value)) return value;
            return Dark ? Hex(darkRgb, darkAlpha) : Hex(lightRgb, lightAlpha);
        }

        // ---- Material 3 roles -------------------------------------------------------------
        internal static Color Surface => Token("surface", 0x141218, .92f, 0xFFFBFE, .94f);
        internal static Color SurfaceContainer => Token("surfaceContainer", 0x2B292F, .95f, 0xECE6F0, .96f);
        internal static Color SurfaceContainerHigh => Token("surfaceContainerHigh", 0x36343B, .96f, 0xE6E0E9, .97f);
        internal static Color Primary => Token("primary", 0xD0BCFF, 1f, 0x6750A4, 1f);
        internal static Color OnPrimary => Token("onPrimary", 0x381E72, 1f, 0xFFFFFF, 1f);
        internal static Color PrimaryContainer => Token("primaryContainer", 0x4F378B, 1f, 0xEADDFF, 1f);
        internal static Color OnPrimaryContainer => Token("onPrimaryContainer", 0xEADDFF, 1f, 0x21005D, 1f);
        internal static Color OnSurface => Token("onSurface", 0xE6E0E9, 1f, 0x1C1B1F, 1f);
        internal static Color OnSurfaceVariant => Token("onSurfaceVariant", 0xCAC4D0, 1f, 0x49454F, 1f);
        internal static Color Outline => Token("outline", 0x938F99, .5f, 0x79747E, .5f);
        internal static Color OutlineVariant => Token("outlineVariant", 0x49454F, .7f, 0xCAC4D0, .7f);
        internal static Color Scrim => Token("scrim", 0x000000, .32f, 0x000000, .18f);

        // ---- legacy aliases still referenced by the toolbox pages -------------------------
        internal static Color Background => Surface;
        internal static Color Panel => SurfaceContainer;
        internal static Color Selected => PrimaryContainer;
        internal static Color Border => Outline;
        internal static Color Text => OnSurface;
        internal static Color Accent => Primary;
        internal static Color Muted => OnSurfaceVariant;
        internal static Color SpeedButton => Token("secondaryContainer", 0x4A4458, 1f, 0xE8DEF8, 1f);
        internal static Color SpeedPrimary => Token("tertiary", 0xEFB8C8, 1f, 0x7D5260, 1f);
        internal static Color SpeedPrimaryText => Token("onTertiary", 0x492532, 1f, 0xFFFFFF, 1f);
        internal static Color SpeedValue => Token("tertiary", 0xEFB8C8, 1f, 0x7D5260, 1f);

        private static Color Hex(int rgb, float alpha = 1f) => new Color(((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f, alpha);

        private static bool SystemDark()
        {
            try
            {
                Type type = Type.GetType("UnityEngine.AndroidJavaClass, UnityEngine.AndroidJNIModule");
                if (type == null) return Dark;
                object player = Activator.CreateInstance(type, new object[] { "com.unity3d.player.UnityPlayer" });
                object activity = JavaGeneric(player, "GetStatic", "currentActivity");
                if (activity == null) return Dark;
                object resources = JavaGeneric(activity, "Call", "getResources");
                object configuration = JavaGeneric(resources, "Call", "getConfiguration");
                var uiMode = (int)JavaGeneric(configuration, "Get", "uiMode", typeof(int));
                return (uiMode & 0x30) == 0x20;
            }
            catch
            {
                return Dark;
            }
        }

        private static object JavaGeneric(object instance, string method, params object[] args)
        {
            if (instance == null) return null;
            // AndroidJavaObject 只在 UnityEngine.AndroidJNIModule 里，编译期没有引用，按名字取。
            Type javaObject = Type.GetType("UnityEngine.AndroidJavaObject, UnityEngine.AndroidJNIModule");
            if (javaObject == null) return null;
            Type type = instance.GetType();
            foreach (var candidate in type.GetMethods())
            {
                if (candidate.Name != method || !candidate.IsGenericMethodDefinition) continue;
                var parameters = candidate.GetParameters();
                if (parameters.Length != args.Length + 1) continue;
                if (parameters[0].ParameterType != typeof(string)) continue;
                try { return candidate.MakeGenericMethod(javaObject).Invoke(instance, args); }
                catch { }
            }
            return null;
        }
    }
}
