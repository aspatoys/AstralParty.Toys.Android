using System;
using System.Collections.Generic;
using System.Globalization;
using System.Text;

namespace CesiumOverlayMod
{
    internal sealed class OverlayGroup
    {
        internal string Name;
        internal List<string> Mods = new List<string>();
    }
    /// <summary>Wire-compatible with the APK; parsing is independent of Unity and file IO.</summary>
    internal sealed class OverlayOptions
    {
        internal float Opacity = .5f, Width = 1, Height = 1;
        /// <summary>Panel text scale, 0.8x to 1.4x.</summary>
        internal float FontScale = 1f;
        /// <summary>Vertical centre as a fraction of screen height, -1 when never moved.</summary>
        internal float Center = -1f;
        /// <summary>Idle seconds before the floating blob fades out; 0 keeps it fully opaque.</summary>
        internal float IdleSeconds = 5f;
        /// <summary>Opacity the idle blob fades to, 0.15 to 1.</summary>
        internal float IdleOpacity = .35f;
        /// <summary>Last speed above 1.0x, restored by the 恢复倍速 button.</summary>
        internal float PreviousSpeed = 1f;
        internal readonly List<OverlayGroup> Groups = new List<OverlayGroup>();
        internal static OverlayOptions Defaults()
        {
            var result = new OverlayOptions();
            result.Groups.Add(new OverlayGroup { Name = "功能类", Mods = new List<string> {
                "HideHudMod", "AutoThanksMod", "ShopRelicPeekMod", "PveWaveForecastMod" } });
            return result;
        }
        internal static float Clamp(float value, float low, float high, float fallback)
        { return float.IsNaN(value) || float.IsInfinity(value) ? fallback : Math.Max(low, Math.Min(high, value)); }
        private static string Number(float value) { return value.ToString("0.######", CultureInfo.InvariantCulture); }
        internal string Encode()
        {
            var text = new StringBuilder("version=1\n");
            text.Append("opacity=").Append(Number(Opacity)).Append('\n');
            text.Append("width=").Append(Number(Width)).Append('\n');
            text.Append("height=").Append(Number(Height)).Append('\n');
            text.Append("font=").Append(Number(FontScale)).Append('\n');
            text.Append("idle=").Append(Number(IdleSeconds)).Append('\n');
            text.Append("idleOpacity=").Append(Number(IdleOpacity)).Append('\n');
            text.Append("previousSpeed=").Append(Number(PreviousSpeed)).Append('\n');
            if (Center >= 0f) text.Append("center=").Append(Number(Center)).Append('\n');
            foreach (var group in Groups)
                text.Append("group=").Append(Convert.ToBase64String(Encoding.UTF8.GetBytes(group.Name)))
                    .Append('|').Append(string.Join(",", group.Mods)).Append('\n');
            return text.ToString();
        }
        internal static OverlayOptions Parse(string raw)
        {
            if (raw == null || raw.Length > 65536) throw new FormatException("悬浮窗设置过大");
            var lines = raw.Trim().Split('\n');
            if (lines.Length == 0 || lines[0].Trim() != "version=1") throw new FormatException("悬浮窗设置版本不支持");
            var value = new OverlayOptions();
            var assigned = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach (var source in lines)
            {
                string line = source.Trim();
                int at = line.IndexOf('='); if (at < 0) continue;
                string key = line.Substring(0, at), data = line.Substring(at + 1);
                float number;
                if (key == "opacity") value.Opacity = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, .25f, 1, .5f) : .5f;
                if (key == "width") value.Width = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, .65f, 1.6f, 1) : 1;
                if (key == "height") value.Height = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, .65f, 1.6f, 1) : 1;
                if (key == "font") value.FontScale = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, .8f, 1.4f, 1) : 1;
                if (key == "idle") value.IdleSeconds = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, 0f, 60f, 5f) : 5f;
                if (key == "idleOpacity") value.IdleOpacity = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, .15f, 1f, .35f) : .35f;
                if (key == "previousSpeed") value.PreviousSpeed = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) ? Clamp(number, 1f, 10f, 1f) : 1f;
                // A negative centre means "never moved"; clamping it to 0 would pin the bar to the top.
                if (key == "center") value.Center = float.TryParse(data, NumberStyles.Float, CultureInfo.InvariantCulture, out number) && !float.IsNaN(number) && number >= 0f
                    ? Math.Min(1f, number) : -1f;
                if (key != "group" || value.Groups.Count >= 8) continue;
                var parts = data.Split('|');
                if (parts.Length != 2) throw new FormatException("悬浮窗分组无效");
                string name = Encoding.UTF8.GetString(Convert.FromBase64String(parts[0])).Trim();
                if (name.Length > 30) name = name.Substring(0, 30);
                var group = new OverlayGroup { Name = name.Length == 0 ? "分组" : name };
                foreach (string id in parts[1].Split(','))
                {
                    if (id.Length == 0 || id.Length > 128 || id == "SpeedHackMod" || group.Mods.Count >= 128) continue;
                    bool valid = true;
                    foreach (char c in id) if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-')) valid = false;
                    if (valid && assigned.Add(id)) group.Mods.Add(id);
                }
                value.Groups.Add(group);
            }
            return value;
        }
        internal bool Assigned(string id)
        {
            foreach (var group in Groups) foreach (string mod in group.Mods)
                if (string.Equals(mod, id, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }
    }
}
