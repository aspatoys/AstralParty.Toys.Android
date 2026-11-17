// Hand-rolled tween driver. Deliberately avoids FairyGUI's GTween: the root component is
// disposed and rebuilt on theme/option changes (GTween would hold dangling targets), and
// GTween follows Time.timeScale which this very mod rewrites when the player changes speed.
using System;
using System.Collections.Generic;
using UnityEngine;

namespace CesiumOverlayMod
{
    internal enum OverlayEase
    {
        Linear,
        SineInOut,
        CubicOut,
        CubicIn,
        QuartOut,
    }

    internal static class OverlayEasing
    {
        internal static float Apply(OverlayEase ease, float t)
        {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            switch (ease)
            {
                case OverlayEase.SineInOut:
                    return -(Mathf.Cos(Mathf.PI * t) - 1f) * .5f;
                case OverlayEase.CubicOut:
                {
                    float q = 1f - t;
                    return 1f - q * q * q;
                }
                case OverlayEase.CubicIn:
                    return t * t * t;
                case OverlayEase.QuartOut:
                {
                    float q = 1f - t;
                    return 1f - q * q * q * q;
                }
                default:
                    return t;
            }
        }
    }

    /// <summary>Frame-driven interpolator over realtimeSinceStartup seconds.</summary>
    internal sealed class OverlayMotion
    {
        private sealed class Entry
        {
            internal float From;
            internal float To;
            internal float Duration;
            internal float DelayRemaining;
            internal float Elapsed;
            internal OverlayEase Ease;
            internal Action<float> Apply;
            internal Action Done;
        }

        private readonly List<Entry> entries = new List<Entry>();

        internal void Clear() { entries.Clear(); }

        /// <summary>True when nothing is animating, i.e. the layout has settled on its final values.</summary>
        internal bool Idle => entries.Count == 0;

        internal void To(float from, float to, float duration, OverlayEase ease, Action<float> apply, float delay = 0f, Action done = null)
        {
            apply(from);
            if (duration <= 0f)
            {
                if (delay > 0f)
                {
                    entries.Add(new Entry { From = from, To = to, Duration = 0f, DelayRemaining = delay, Ease = ease, Apply = apply, Done = done });
                    return;
                }
                apply(to);
                done?.Invoke();
                return;
            }
            entries.Add(new Entry
            {
                From = from, To = to, Duration = duration, DelayRemaining = Mathf.Max(0f, delay),
                Ease = ease, Apply = apply, Done = done,
            });
        }

        internal void Update(float now)
        {
            if (entries.Count == 0) return;
            for (int i = entries.Count - 1; i >= 0; i--)
            {
                var entry = entries[i];
                if (entry.DelayRemaining > 0f)
                {
                    entry.DelayRemaining -= Time.unscaledDeltaTime;
                    continue;
                }
                entry.Elapsed += Time.unscaledDeltaTime;
                float t = Mathf.Clamp01(entry.Elapsed / entry.Duration);
                if (t < 1f)
                {
                    entry.Apply(Mathf.LerpUnclamped(entry.From, entry.To, OverlayEasing.Apply(entry.Ease, t)));
                    continue;
                }
                entries.RemoveAt(i);
                entry.Apply(entry.To);
                entry.Done?.Invoke();
            }
        }
    }
}
