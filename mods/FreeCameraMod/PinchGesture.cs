using System;
namespace FreeCameraMod
{
    internal sealed class PinchGesture
    {
        private bool tracking, blocked;
        private int first, second;
        private float span;
        internal bool Step(int count, int a, int b, float ax, float ay, float bx, float by, float scale, bool overUi, out float zoom)
        {
            zoom = 0;
            if (count == 0) { tracking = blocked = false; return false; }
            if (blocked) return true;
            if (count != 2) { if (tracking || count > 2) blocked = true; return blocked; }
            if (overUi) { tracking = false; blocked = true; return true; }
            if (a > b) { int id = a; a = b; b = id; }
            float dx = bx - ax, dy = by - ay;
            float distance = (float)Math.Sqrt(dx * dx + dy * dy);
            if (!tracking || a != first || b != second) { tracking = true; first = a; second = b; span = distance; return true; }
            zoom = Math.Max(-1, Math.Min(1, (distance - span) / Math.Max(1, scale) * 8));
            span = distance;
            return true;
        }
        internal void Block() { blocked = true; tracking = false; }
    }
}
