using System;
using System.Collections.Generic;

namespace CesiumOverlayMod
{
    /// <summary>Frame-stable virtual keyboard. Sources share modifiers without releasing each other.</summary>
    internal sealed class VirtualKeyState
    {
        private sealed class Source
        {
            internal int[] Keys;
            internal int Start;
            internal int End;
        }
        private readonly Dictionary<string, Source> sources = new Dictionary<string, Source>();
        private HashSet<int> held = new HashSet<int>();
        private HashSet<int> down = new HashSet<int>();
        private HashSet<int> up = new HashSet<int>();
        private int sampled = int.MinValue;

        internal void Press(string source, int[] keys, int frame, bool pulse)
        {
            if (keys == null || keys.Length == 0) return;
            // Never change the input snapshot midway through the mod update loop.
            sources[source] = new Source { Keys = (int[])keys.Clone(), Start = frame + 1,
                End = pulse ? frame + 2 : int.MaxValue };
        }
        internal void Release(string source, int frame)
        {
            Source value;
            if (sources.TryGetValue(source, out value)) value.End = frame + 1;
        }
        internal bool HasSource(string source) { return sources.ContainsKey(source); }
        internal void Advance(int frame)
        {
            if (sampled == frame) return;
            sampled = frame;
            var next = new HashSet<int>();
            var expired = new List<string>();
            foreach (var item in sources)
            {
                if (frame >= item.Value.End) { expired.Add(item.Key); continue; }
                if (frame >= item.Value.Start) foreach (int key in item.Value.Keys) next.Add(key);
            }
            foreach (string source in expired) sources.Remove(source);
            down = new HashSet<int>(next); down.ExceptWith(held);
            up = new HashSet<int>(held); up.ExceptWith(next);
            held = next;
        }
        internal bool Held(int key) { return held.Contains(key); }
        internal bool Down(int key) { return down.Contains(key); }
        internal bool Up(int key) { return up.Contains(key); }
        internal void Clear()
        {
            sources.Clear(); held.Clear(); down.Clear(); up.Clear(); sampled = int.MinValue;
        }
    }
}
