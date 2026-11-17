// Test-only doubles for the Unity / FairyGUI / Android surface the speed page touches.
// Everything else is the real thing: SpeedPanel.cs, DockState.cs, OverlayOptions.cs plus the
// SetSpeed / RestoreSpeed / RenderSpeed method bodies extracted verbatim from OverlayWindow.cs.
using System;
using System.Collections.Generic;

namespace UnityEngine
{
    public struct Color
    {
        public float r, g, b, a;
        public Color(float red, float green, float blue, float alpha) { r = red; g = green; b = blue; a = alpha; }
        public static Color White => new Color(1f, 1f, 1f, 1f);
    }

    public static class Mathf
    {
        public static float Abs(float value) => Math.Abs(value);
        public static float Clamp(float value, float min, float max) => Math.Min(max, Math.Max(min, value));
        public static int Clamp(int value, int min, int max) => Math.Min(max, Math.Max(min, value));
        public static float Min(float a, float b) => Math.Min(a, b);
        public static float Max(float a, float b) => Math.Max(a, b);
        public static int RoundToInt(float value) => (int)Math.Round(value);
    }
}

namespace FairyGUI
{
    public enum AutoSizeType { None, Both, Height, Shrink }
    public enum VertAlignType { Top, Middle, Bottom }
    public enum AlignType { Left, Center, Right }

    public sealed class TextFormat
    {
        public int size;
        public UnityEngine.Color color;
    }

    public sealed class EventContext
    {
        public bool propagationStopped;
        public void StopPropagation() { propagationStopped = true; }
    }

    /// <summary>Holds the real handlers production Button() registers, so tests can fire them.</summary>
    public sealed class EventDispatcher
    {
        private readonly List<Action> plain = new List<Action>();
        private readonly List<Action<EventContext>> withContext = new List<Action<EventContext>>();
        public int Count => plain.Count + withContext.Count;
        public void Add(Action listener) { if (listener != null) plain.Add(listener); }
        public void Add(Action<EventContext> listener) { if (listener != null) withContext.Add(listener); }
        public void Invoke()
        {
            var context = new EventContext();
            for (int i = 0; i < plain.Count; i++) plain[i]();
            for (int i = 0; i < withContext.Count; i++) withContext[i](context);
        }
    }

    public class GComponent
    {
        public string name = string.Empty;
        public bool opaque;
        public bool touchable = true;
        public float alpha = 1f;
        public bool isDisposed;
        public GComponent parent;
        private float w, h, px, py;

        public float width => w;
        public float height => h;
        public float x => px;
        public float y => py;
        public readonly List<GComponent> children = new List<GComponent>();
        public readonly EventDispatcher onTouchBegin = new EventDispatcher();
        public readonly EventDispatcher onTouchEnd = new EventDispatcher();
        public readonly EventDispatcher onClick = new EventDispatcher();

        public void SetSize(float width, float height) { w = width; h = height; }
        public void SetXY(float x, float y) { px = x; py = y; }
        public void AddChild(GComponent child) { child.parent = this; children.Add(child); }
        public void RemoveChildren()
        {
            for (int i = 0; i < children.Count; i++) { children[i].parent = null; children[i].isDisposed = true; }
            children.Clear();
        }
        public GComponent Child(string childName)
        {
            for (int i = 0; i < children.Count; i++) if (children[i].name == childName) return children[i];
            return null;
        }
    }

    public class GTextField : GComponent
    {
        public string text = string.Empty;
        public AutoSizeType autoSize;
        public VertAlignType verticalAlign;
        public AlignType align;
        public TextFormat textFormat = new TextFormat();
    }

    public class GGraph : GComponent
    {
        public int drawCount;
        public UnityEngine.Color drawColor;
        public void DrawRoundRect(float width, float height, UnityEngine.Color color, float[] radius)
        {
            drawCount++;
            drawColor = color;
            SetSize(width, height);
        }
    }
}

namespace CesiumAndroid
{
    public static class AndroidApi
    {
        public static readonly List<string> Notices = new List<string>();
        public static readonly List<string> Errors = new List<string>();
        public static void ShowNotice(string message) { Notices.Add(message); }
        public static void LogError(string message) { Errors.Add(message); }
        public static string GetLoaderPath(params string[] parts) => System.IO.Path.Combine(parts);
    }
}

namespace CesiumOverlayMod
{
    /// <summary>The only stand-in with real behaviour: the loader-side speed backend.</summary>
    internal static class LocalSpeed
    {
        private static double selected = 1;
        internal static double SelectedSpeed => selected;
        internal static readonly List<double> Applied = new List<double>();
        /// <summary>One-shot injection: the next Choose of this value throws like a failing native call.</summary>
        internal static double FailTarget = double.NaN;

        internal static void Choose(double speed)
        {
            if (!double.IsNaN(FailTarget) && Math.Abs(speed - FailTarget) < .0001)
            {
                FailTarget = double.NaN;
                throw new InvalidOperationException("speed backend rejected " + speed.ToString("0.0", System.Globalization.CultureInfo.InvariantCulture));
            }
            if (double.IsNaN(speed) || double.IsInfinity(speed)) throw new ArgumentOutOfRangeException(nameof(speed));
            selected = Math.Min(10, Math.Max(1, speed));
            Applied.Add(selected);
        }

        internal static void Reset() { selected = 1; Applied.Clear(); FailTarget = double.NaN; }
    }

    /// <summary>Fixed palette; the real tokens read the Android loader at runtime.</summary>
    internal static class OverlayTheme
    {
        internal static UnityEngine.Color Surface => new UnityEngine.Color(.10f, .10f, .10f, 1f);
        internal static UnityEngine.Color SurfaceContainer => new UnityEngine.Color(.12f, .12f, .12f, 1f);
        internal static UnityEngine.Color SurfaceContainerHigh => new UnityEngine.Color(.18f, .18f, .18f, 1f);
        internal static UnityEngine.Color Primary => new UnityEngine.Color(.28f, .52f, .96f, 1f);
        internal static UnityEngine.Color OnPrimary => new UnityEngine.Color(1f, 1f, 1f, 1f);
        internal static UnityEngine.Color OnSurface => new UnityEngine.Color(.92f, .92f, .92f, 1f);
        internal static UnityEngine.Color OnSurfaceVariant => new UnityEngine.Color(.66f, .66f, .66f, 1f);
        internal static UnityEngine.Color Outline => new UnityEngine.Color(.40f, .40f, .40f, 1f);
        internal static UnityEngine.Color SpeedButton => new UnityEngine.Color(.22f, .22f, .22f, 1f);
        internal static UnityEngine.Color SpeedPrimary => new UnityEngine.Color(.28f, .52f, .96f, 1f);
        internal static UnityEngine.Color SpeedPrimaryText => new UnityEngine.Color(1f, 1f, 1f, 1f);
        internal static UnityEngine.Color SpeedValue => new UnityEngine.Color(.28f, .52f, .96f, 1f);
    }
}
