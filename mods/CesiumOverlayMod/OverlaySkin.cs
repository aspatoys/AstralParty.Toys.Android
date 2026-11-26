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
    ///   * Splitting the shape into four corner tiles plus five flat blocks avoided the resampling
    ///     but the pieces butted together at the tangent points, which left hairline seams — worst
    ///     while the shape was moving.
    ///   * Baking the whole shape at a fixed small cap and stretching it for every animation frame
    ///     was cheap, but a 400 x 480 box drawn from a 192 px texture is a 2.5x upscale, so the
    ///     corners read as a smear for the whole expand animation.
    ///
    /// This version bakes the whole shape, at the size it is about to be drawn:
    ///   * A bake is reused until the box outgrows it by <see cref="GrowTolerance"/>, so an
    ///     animation re-bakes a dozen times instead of once per frame. At most 15% upscale is
    ///     invisible; the old fixed cap was a 250% upscale.
    ///   * Radii are baked from the box size of that same moment and live in texture space, so an
    ///     arc stays a true circle on screen even while the texture is stretched unevenly.
    ///   * The resting state always re-bakes at exactly one texture pixel per screen pixel.
    /// </summary>
    internal sealed class OverlaySkin
    {
        /// <summary>
        /// A bake is reused until the box outgrows it by this factor: 15% upscale is invisible, and
        /// because the box grows in steps this size an animation needs about a dozen bakes rather
        /// than one per frame.
        /// </summary>
        private const float GrowTolerance = 1.15f;

        /// <summary>A corner radius may drift this far before the bake is rebuilt, in pixels.</summary>
        private const float RadiusTolerance = 2f;

        /// <summary>Longest side of any bake. Guards against a 40 MB texture on a huge window.</summary>
        private const int MaxSide = 1600;

        private readonly GLoader piece;
        private NTexture texture;
        private byte[] buffer;

        private string colour;
        private int bakedWidth;
        private int bakedHeight;
        private float bakedTopLeft, bakedTopRight, bakedBottomLeft, bakedBottomRight;

        internal OverlaySkin(GComponent host, string name)
        {
            piece = new GLoader { name = name, touchable = false, fill = FillType.ScaleFree };
            host.AddChild(piece);
        }

        internal void Dispose()
        {
            texture?.Dispose();
            texture = null;
            buffer = null;
            colour = null;
            bakedWidth = 0;
            bakedHeight = 0;
        }

        /// <summary>
        /// Repaints the skin. Every size is in design units, which are screen pixels here.
        /// <paramref name="settled"/> must be true whenever no motion is in flight: then the shape is
        /// final, so the bake is rebuilt at exactly one texture pixel per screen pixel.
        /// </summary>
        internal void Paint(float width, float height, float topLeft, float topRight,
            float bottomLeft, float bottomRight, Color fill, bool settled)
        {
            if (width < 1f || height < 1f) return;

            float limit = Mathf.Min(width * .5f, height * .5f);
            float tl = Mathf.Clamp(topLeft, 0f, limit);
            float tr = Mathf.Clamp(topRight, 0f, limit);
            float bl = Mathf.Clamp(bottomLeft, 0f, limit);
            float br = Mathf.Clamp(bottomRight, 0f, limit);

            // One texel per screen pixel, unless the box is bigger than MaxSide; then both axes drop
            // by the same factor so the arcs stay circular.
            float w = Mathf.Min(width, MaxSide);
            float h = Mathf.Min(height, MaxSide);
            float sx = w / width;
            float sy = h / height;

            int pw = Mathf.Max(1, Mathf.RoundToInt(w));
            int ph = Mathf.Max(1, Mathf.RoundToInt(h));
            float btl = tl * sx, btr = tr * sx, bbl = bl * sx, bbr = br * sx;

            string rgb = ColorUtility.ToHtmlStringRGBA(fill);

            bool grew = texture == null
                || pw > bakedWidth * GrowTolerance || ph > bakedHeight * GrowTolerance;
            bool recoloured = !string.Equals(rgb, colour, StringComparison.Ordinal);
            bool reshaped = Moved(btl, bakedTopLeft) || Moved(btr, bakedTopRight)
                || Moved(bbl, bakedBottomLeft) || Moved(bbr, bakedBottomRight);
            // At rest the shape is what the user looks at, so it must match the geometry exactly;
            // mid-motion a stretched texture is what keeps the animation cheap.
            bool exact = settled && (pw != bakedWidth || ph != bakedHeight || recoloured
                || !Same(btl, bakedTopLeft) || !Same(btr, bakedTopRight)
                || !Same(bbl, bakedBottomLeft) || !Same(bbr, bakedBottomRight));

            if (grew || recoloured || reshaped || exact)
                Bake(pw, ph, btl, btr, bbl, bbr, fill, rgb);

            piece.SetSize(width, height);
            piece.SetXY(0f, 0f);
        }

        private void Bake(int width, int height, float tl, float tr, float bl, float br,
            Color fill, string rgb)
        {
            int size = width * height * 4;
            if (buffer == null || buffer.Length != size) buffer = new byte[size];
            try
            {
                OverlayRaster.RoundedRect(buffer, width, height,
                    new OverlayRaster.Corner(tl, tl), new OverlayRaster.Corner(tr, tr),
                    new OverlayRaster.Corner(bl, bl), new OverlayRaster.Corner(br, br), fill);
                var next = OverlayRaster.ToTexture(buffer, width, height);
                piece.texture = next;
                texture?.Dispose();
                texture = next;
                bakedWidth = width;
                bakedHeight = height;
                bakedTopLeft = tl;
                bakedTopRight = tr;
                bakedBottomLeft = bl;
                bakedBottomRight = br;
                colour = rgb;
            }
            catch (Exception error)
            {
                // Keep the shape already on screen rather than dropping the frame.
                Debug.LogWarning("overlay skin bake failed: " + error.Message);
            }
        }

        private static bool Moved(float a, float b) => Mathf.Abs(a - b) > RadiusTolerance;

        private static bool Same(float a, float b) => Mathf.Abs(a - b) < .01f;
    }
}
