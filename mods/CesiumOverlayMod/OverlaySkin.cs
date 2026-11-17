using System;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    /// <summary>
    /// Draws one rounded rectangle as a single seamless baked fill.
    ///
    /// History, because each earlier design broke in a visible way:
    ///   * <c>GGraph.DrawRoundRect</c> fills via a triangle mesh with no edge filter, so corners
    ///     showed hard stair steps.
    ///   * Baking at full size every frame fixed the steps but uploaded a <c>Texture2D</c> per
    ///     frame, which made the expand animation stutter.
    ///   * Baking once and stretching distorted corners, because the stretch differed per axis while
    ///     width and height animate at different rates.
    ///   * Splitting the shape into corner tiles plus straight rectangles avoided both, but every
    ///     piece boundary was a rounded seam, so hairline gaps showed up — worst while moving.
    ///   * Baking a stroked outline into that downscaled texture made the border thick and blurry.
    ///   * Downscaling everything to a fixed cap kept the frame cost down but left the *steady*
    ///     shape soft: a 36 px corner baked into an 8 px arc and stretched back reads as a smear.
    ///
    /// So the bake runs in two tiers:
    ///   * While a motion is in flight the shape changes every frame and nobody can see the edge
    ///     closely, so it bakes at a small cap — cheap enough to keep the animation smooth.
    ///   * The moment the motion settles it re-bakes at one texture pixel per screen pixel, which
    ///     restores a crisp one-pixel anti-aliased arc for the state the user actually looks at.
    ///
    /// The fill is a flat colour, so the SDF is only there to shade the edge; that is what makes
    /// the coarse tier survive without visible artefacts mid-flight.
    /// </summary>
    internal sealed class OverlaySkin
    {
        /// <summary>Bake cap while a motion is running. Small on purpose: cheap uploads.</summary>
        private const int AnimatedCap = 192;

        /// <summary>
        /// Bake cap at rest. One texture pixel per screen pixel is the whole point of the sharp
        /// tier; the clamp only guards against absurd window sizes (1024² RGBA is 4 MB).
        /// </summary>
        private const int RestingCap = 1024;

        private readonly GLoader piece;
        private NTexture texture;
        private string key;

        internal OverlaySkin(GComponent host, string name)
        {
            piece = new GLoader { name = name, touchable = false, fill = FillType.ScaleFree };
            host.AddChild(piece);
        }

        internal void Dispose()
        {
            texture?.Dispose();
            texture = null;
            key = null;
        }

        /// <summary>
        /// Repaints the skin. <paramref name="settled"/> selects the sharp tier: pass <c>true</c>
        /// whenever no motion is in flight so the corners are baked at native resolution.
        /// </summary>
        internal void Paint(float width, float height, float topLeft, float topRight, float bottomLeft,
            float bottomRight, Color fill, bool settled)
        {
            if (width < 1f || height < 1f) return;

            int cap = settled ? RestingCap : AnimatedCap;
            float longest = Mathf.Max(width, height);
            float scale = longest > cap ? cap / longest : 1f;
            int bakedWidth = Mathf.Clamp(Mathf.RoundToInt(width * scale), 1, RestingCap);
            int bakedHeight = Mathf.Clamp(Mathf.RoundToInt(height * scale), 1, RestingCap);

            // Radii go into the key as ratios of the longest side, not as absolute pixels. Keyed on
            // pixels the animated tier re-baked on every frame — the size changes each frame — and
            // that upload was the entire cost of the animation. As ratios the key only moves when
            // the corner *shape* actually changes, so a running animation reuses one texture and
            // simply stretches it. The settled tier adds its own marker so it always re-bakes once
            // the motion stops, restoring a native-resolution arc.
            string rgb = ColorUtility.ToHtmlStringRGBA(fill);
            float unit = Mathf.Max(1f, longest);
            string wanted = string.Format("{0}|{1}|{2}|{3}|{4}|{5}|{6}",
                Mathf.RoundToInt(Mathf.Max(0f, topLeft) / unit * 64f),
                Mathf.RoundToInt(Mathf.Max(0f, topRight) / unit * 64f),
                Mathf.RoundToInt(Mathf.Max(0f, bottomLeft) / unit * 64f),
                Mathf.RoundToInt(Mathf.Max(0f, bottomRight) / unit * 64f),
                rgb, settled ? 1 : 0,
                // Only the resting tier is pixel-exact, so only it needs to notice a resize.
                settled ? bakedWidth + "x" + bakedHeight : "free");

            if (texture == null || !string.Equals(wanted, key, StringComparison.Ordinal))
            {
                try
                {
                    var rgba = OverlayRaster.RoundedRect(bakedWidth, bakedHeight,
                        Mathf.Max(0f, topLeft) * scale, Mathf.Max(0f, topRight) * scale,
                        Mathf.Max(0f, bottomLeft) * scale, Mathf.Max(0f, bottomRight) * scale,
                        fill, fill, 0f);
                    var next = OverlayRaster.ToTexture(rgba, bakedWidth, bakedHeight);
                    piece.texture = next;
                    texture?.Dispose();
                    texture = next;
                    key = wanted;
                }
                catch (Exception error)
                {
                    // Keep the shape already on screen rather than dropping the frame.
                    Debug.LogWarning("overlay skin bake failed: " + error.Message);
                }
            }

            piece.SetSize(width, height);
            piece.SetXY(0f, 0f);
        }
    }
}
