using System;

namespace CesiumOverlayMod
{
    /// <summary>Panel targets are absolute; a previous mod's accelerated value is never a baseline.</summary>
    internal sealed class AbsoluteSpeedScale
    {
        private readonly Func<float> read;
        private readonly Action<float> write;
        private bool commanded;

        internal AbsoluteSpeedScale(Func<float> read, Action<float> write)
        {
            this.read = read;
            this.write = write;
        }

        internal void Synchronize(double target)
        {
            if (double.IsNaN(target) || double.IsInfinity(target) || target < 1 || target > 10)
                throw new ArgumentOutOfRangeException(nameof(target));
            commanded = true;
            float current = read();
            if (current == 0) return; // Never turn a game pause into an unpause.
            if (float.IsNaN(current) || float.IsInfinity(current) || current < 0)
                throw new InvalidOperationException("Unity timeScale is invalid");
            if (Math.Abs(current - target) > .00001) write((float)target);
        }

        internal void Restore()
        {
            if (!commanded || read() == 0) return;
            write(1);
            commanded = false;
        }
    }
}
