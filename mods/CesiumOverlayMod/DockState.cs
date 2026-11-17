using System;

namespace CesiumOverlayMod
{
    /// <summary>Real-time inactivity policy, independent of Unity's scaled game clock.</summary>
    internal sealed class DockIconState
    {
        internal const float IdleSeconds = 5;
        private float activeAt, forcedAt;
        private bool forced;
        internal void Wake(float now) { activeAt = now; forced = false; }
        internal void Rest(float now) { if (!forced) forcedAt = now; forced = true; }
        internal float RestProgress(float now)
        {
            float began = forced ? forcedAt : activeAt + IdleSeconds;
            return Math.Max(0, Math.Min(1, (now - began) / .22f));
        }
    }

    internal struct DockLayout
    {
        internal readonly float Side, Rail, Gap, MainX, RailX, Y;
        internal DockLayout(float side, float rail, float gap, float mainX, float railX, float y)
        { Side = side; Rail = rail; Gap = gap; MainX = mainX; RailX = railX; Y = y; }
        internal static DockLayout Measure(float width, float height, float scale, float size, bool right, float iconY)
        {
            float margin = Math.Min(12, Math.Min(width, height) * .02f);
            float rail = Math.Min(94 * scale, Math.Max(1, width * .16f));
            float gap = Math.Min(14 * scale, Math.Max(1, width * .025f));
            float available = Math.Max(1, Math.Min(height - margin * 2, width - margin * 2 - rail - gap));
            float side = Math.Max(Math.Min(300 * scale, available), Math.Min(available, available * .9f * size));
            float total = rail + gap + side;
            float x = right ? width - margin - total : margin;
            float y = Math.Max(margin, Math.Min(height - margin - side, iconY - side * .15f));
            return new DockLayout(side, rail, gap, right ? x : x + rail + gap, right ? x + side + gap : x, y);
        }
    }

    /// <summary>Restoring original speed retains the multiplier only within this game session.</summary>
    internal sealed class SpeedResumeState
    {
        internal bool Suspended { get; private set; }
        internal double Saved { get; private set; } = 1;
        internal void Choose(double value, Action<double> apply)
        {
            if (double.IsNaN(value) || double.IsInfinity(value) || value < 1 || value > 10)
                throw new ArgumentOutOfRangeException(nameof(value));
            apply(value); Saved = value; Suspended = false;
        }
        internal void Toggle(double current, Action<double> apply)
        {
            if (Suspended) { apply(Saved); Suspended = false; }
            else
            {
                if (double.IsNaN(current) || double.IsInfinity(current) || current < 1 || current > 10)
                    throw new ArgumentOutOfRangeException(nameof(current));
                apply(1); Saved = current; Suspended = true;
            }
        }
    }
}
