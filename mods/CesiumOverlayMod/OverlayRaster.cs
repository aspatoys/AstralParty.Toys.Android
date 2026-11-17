// Anti-aliased rounded-rectangle rasteriser.
//
// FairyGUI's Shape/GGraph builds its rounded rectangle from a triangle mesh: the fill colour is
// rasterised by the GPU without any edge filter, so a corner rendered at 1.5x scale shows visible
// stair steps. Rasterising the same shape on the CPU with a signed distance field gives one-pixel
// analytic coverage for free, and the result can be shown through a GLoader that simply stretches
// a texture. The same pass draws the 1px outline, which keeps the stroke aligned with the fill.
using System;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    internal static class OverlayRaster
    {
        /// <summary>
        /// Renders a rounded rectangle into a top-down RGBA32 buffer (the caller flips it before
        /// uploading, because Unity textures are stored bottom-up).
        /// </summary>
        internal static byte[] RoundedRect(int width, int height,
            float topLeft, float topRight, float bottomLeft, float bottomRight,
            Color32 fill, Color32 outline, float outlineWidth)
        {
            int w = Math.Max(1, width);
            int h = Math.Max(1, height);
            var pixels = new byte[w * h * 4];

            float hx = w * .5f;
            float hy = h * .5f;
            float far = Math.Max(hx, hy) + 2f;

            for (int y = 0; y < h; y++)
            {
                float py = y + .5f - hy;
                bool lowRow = py > 0f;
                float rLeft = lowRow ? bottomLeft : topLeft;
                float rRight = lowRow ? bottomRight : topRight;

                int row = y * w * 4;
                for (int x = 0; x < w; x++)
                {
                    float px = x + .5f - hx;
                    float r = px > 0f ? rRight : rLeft;
                    if (r < 0f) r = 0f;
                    float rx = hx - r;
                    float ry = hy - r;
                    if (rx < 0f) rx = 0f;
                    if (ry < 0f) ry = 0f;

                    float qx = Math.Abs(px) - rx;
                    float qy = Math.Abs(py) - ry;
                    float outside = (float)Math.Sqrt((qx > 0f ? qx : 0f) * (qx > 0f ? qx : 0f)
                                                   + (qy > 0f ? qy : 0f) * (qy > 0f ? qy : 0f));
                    float inside = Math.Min(Math.Max(qx, qy), 0f);
                    float distance = outside + inside - r;

                    float coverage = Clamp01(.5f - distance);
                    if (coverage <= 0f) continue;

                    float inner = Clamp01(.5f - (distance + outlineWidth));
                    float ring = coverage - inner;

                    float red = fill.r * inner + outline.r * ring;
                    float green = fill.g * inner + outline.g * ring;
                    float blue = fill.b * inner + outline.b * ring;
                    float alpha = fill.a * inner + outline.a * ring;

                    int at = row + x * 4;
                    pixels[at] = (byte)Clamp255(red / coverage);
                    pixels[at + 1] = (byte)Clamp255(green / coverage);
                    pixels[at + 2] = (byte)Clamp255(blue / coverage);
                    pixels[at + 3] = (byte)Clamp255(alpha);
                }
            }

            // A distance field never reaches past `far`; the guard keeps the compiler from
            // flagging an unused local when the shape is degenerate.
            if (far < 0f) return pixels;
            return pixels;
        }

        /// <summary>
        /// Renders one anti-aliased corner arc into a square tile of side <paramref name="size"/>.
        ///
        /// The arc keeps its own <paramref name="radius"/> and is centred <c>radius</c> from the
        /// rectangle's two edges, so when <c>size</c> is larger than the radius the extra pixels
        /// beyond the tangent points come out solid. That margin is what lets the nine-slice
        /// overlap its straight neighbours instead of butting against them and leaving a hairline
        /// seam (rounding the radius down to an integer used to expose exactly such a gap).
        ///
        /// Do not reuse <see cref="RoundedRect"/> for this: a radius equal to half the tile clamps
        /// the inner extents to zero and degenerates into a full square.
        /// </summary>
        /// <param name="corner">0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right.</param>
        internal static byte[] CornerTile(int size, int corner, float radius, Color32 fill, Color32 outline, float outlineWidth)
        {
            int s = Math.Max(2, size);
            var pixels = new byte[s * s * 4];
            bool right = corner == 1 || corner == 3;
            bool bottom = corner == 2 || corner == 3;
            float cx = right ? s - radius : radius;
            float cy = bottom ? s - radius : radius;
            float outer = Math.Max(0f, radius);
            float inner = Math.Max(0f, outer - Math.Max(0f, outlineWidth));

            for (int y = 0; y < s; y++)
            {
                int row = y * s * 4;
                float dy = y + .5f - cy;
                for (int x = 0; x < s; x++)
                {
                    float dx = x + .5f - cx;
                    float distance = (float)Math.Sqrt(dx * dx + dy * dy);

                    float coverage = Clamp01(.5f - (distance - outer));
                    if (coverage <= 0f) continue;

                    float solid = Clamp01(.5f - (distance - inner));
                    float ring = coverage - solid;

                    float red = fill.r * solid + outline.r * ring;
                    float green = fill.g * solid + outline.g * ring;
                    float blue = fill.b * solid + outline.b * ring;
                    float alpha = fill.a * solid + outline.a * ring;

                    int at = row + x * 4;
                    pixels[at] = (byte)Clamp255(red / coverage);
                    pixels[at + 1] = (byte)Clamp255(green / coverage);
                    pixels[at + 2] = (byte)Clamp255(blue / coverage);
                    pixels[at + 3] = (byte)Clamp255(alpha);
                }
            }
            return pixels;
        }

        /// <summary>Uploads a top-down RGBA32 buffer as a Unity texture wrapped in an NTexture.</summary>
        internal static NTexture ToTexture(byte[] rgbaTopDown, int width, int height)
        {
            int w = Math.Max(1, width);
            int h = Math.Max(1, height);
            int stride = w * 4;
            var flipped = new byte[rgbaTopDown.Length];
            for (int y = 0; y < h; y++)
                Buffer.BlockCopy(rgbaTopDown, y * stride, flipped, (h - 1 - y) * stride, stride);
            var texture = new Texture2D(w, h, TextureFormat.RGBA32, false);
            texture.LoadRawTextureData(flipped);
            texture.Apply(false, false);
            return new NTexture(texture);
        }

        private static float Clamp01(float value) => value < 0f ? 0f : value > 1f ? 1f : value;

        private static float Clamp255(float value) => value < 0f ? 0f : value > 255f ? 255f : value;
    }
}
