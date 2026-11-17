using System;

namespace CesiumOverlayMod
{
    /// <summary>Two interruptible real-time tracks: rail leads opening and content leads closing.</summary>
    internal sealed class OverlayMotion
    {
        private sealed class Track
        {
            private float from, to, started, duration;
            private bool spring;
            internal float Value(float now)
            {
                if (duration <= 0) return to;
                float t = Math.Max(0, Math.Min(1, (now - started) / duration));
                if (t == 0) return from;
                if (t == 1) return to;
                float q = t - 1;
                float eased = spring ? 1 + 1.6f * q * q * q + .6f * q * q : 1 - (1 - t) * (1 - t) * (1 - t);
                return from + (to - from) * eased;
            }
            internal void Target(float value, float now, float delay, float seconds, bool bounce)
            {
                from = Value(now); to = value;
                started = now + delay;
                duration = seconds * Math.Abs(to - from);
                spring = bounce;
            }
        }
        private readonly Track rail = new Track(), main = new Track();
        internal void SetOpen(bool open, float now)
        {
            rail.Target(open ? 1 : 0, now, open ? 0 : .08f, open ? .22f : .18f, false);
            main.Target(open ? 1 : 0, now, open ? .07f : 0, open ? .28f : .20f, open);
        }
        internal float Rail(float now) { return rail.Value(now); }
        internal float Main(float now) { return main.Value(now); }
    }

    internal static class OpacitySlider
    {
        internal const float Default = .5f;
        internal static float At(float x, float start, float width)
        {
            if (float.IsNaN(x) || float.IsInfinity(x) || width <= 0) return Default;
            float t = Math.Max(0, Math.Min(1, (x - start) / width));
            return (float)Math.Round((.25f + .75f * t) * 100) / 100;
        }
        internal static float Position(float value, float start, float width)
        { return start + Math.Max(0, Math.Min(1, (value - .25f) / .75f)) * Math.Max(0, width); }
    }
}
