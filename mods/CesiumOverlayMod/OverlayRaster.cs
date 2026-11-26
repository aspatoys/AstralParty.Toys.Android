// Anti-aliased rounded-rectangle rasteriser.
//
// FairyGUI's Shape/GGraph builds its rounded rectangle from a triangle mesh: the fill colour is
// rasterised by the GPU without any edge filter, so a corner rendered at 1.5x scale shows visible
// stair steps. Rasterising the same shape on the CPU with a signed distance field gives one-pixel
// analytic coverage for free.
//
// The whole shape is baked as one texture on purpose: splitting it into a nine-slice left hairline
// seams wherever two pieces met, and those seams were most obvious while the box was animating.
//
// Buffers come out in Unity's bottom-up row order, so the caller can hand them straight to
// LoadRawTextureData without a second full-size copy.
using System;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    internal static class OverlayRaster
    {
        /// <summary>
        /// One corner's radii in texture pixels. The two axes are tracked separately because the
        /// skin compensates for the difference between texture size and box size: a corner that has
        /// to read as a circle on screen is an ellipse in texture space.
        /// </summary>
        internal struct Corner
        {
            internal float X;
            internal float Y;

            internal Corner(float x, float y)
            {
                X = x;
                Y = y;
            }
        }

        /// <summary>
        /// Renders a rounded rectangle into a Unity-order RGBA32 buffer.
        ///
        /// The interior of every row is a flat colour, so it is copied in one block per row and only
        /// the two arcs at the ends of a row go through the distance field. A full-screen panel is
        /// well over a million pixels; evaluating all of them would be far too slow to re-bake while
        /// the shell animates.
        /// </summary>
        internal static byte[] RoundedRect(int width, int height, Corner topLeft, Corner topRight,
            Corner bottomLeft, Corner bottomRight, Color32 fill)
        {
            int w = Math.Max(1, width);
            int h = Math.Max(1, height);
            var pixels = new byte[w * h * 4];
            RoundedRect(pixels, w, h, topLeft, topRight, bottomLeft, bottomRight, fill);
            return pixels;
        }

        /// <summary>Fills a caller-owned buffer; see the overload above for the layout.</summary>
        internal static void RoundedRect(byte[] pixels, int width, int height, Corner topLeft,
            Corner topRight, Corner bottomLeft, Corner bottomRight, Color32 fill)
        {
            int w = Math.Max(1, width);
            int h = Math.Max(1, height);
            Array.Clear(pixels, 0, w * h * 4);

            float hx = w * .5f;
            float hy = h * .5f;

            var solid = new byte[w * 4];
            for (int x = 0; x < w; x++)
            {
                int at = x * 4;
                solid[at] = fill.r;
                solid[at + 1] = fill.g;
                solid[at + 2] = fill.b;
                solid[at + 3] = fill.a;
            }

            // Both bottom and top rows index the same buffer upside down: Unity stores textures
            // bottom-up, and doing it here saves a full-size flip pass before the upload.
            for (int y = 0; y < h; y++)
            {
                float py = y + .5f - hy;
                bool bottom = py > 0f;
                Corner left = bottom ? bottomLeft : topLeft;
                Corner right = bottom ? bottomRight : topRight;

                int from = SolidStart(py, hy, hx, left);
                int to = w - 1 - SolidStart(py, hy, hx, right);
                int row = (h - 1 - y) * w * 4;

                if (from < 0) from = 0;
                if (to > w - 1) to = w - 1;

                if (from <= to)
                {
                    Buffer.BlockCopy(solid, from * 4, pixels, row + from * 4, (to - from + 1) * 4);
                    for (int x = 0; x < from; x++)
                        Shade(pixels, row + x * 4, x + .5f - hx, py, hx, hy, left, fill);
                    for (int x = to + 1; x < w; x++)
                        Shade(pixels, row + x * 4, x + .5f - hx, py, hx, hy, right, fill);
                }
                else
                {
                    // Degenerate box: the two arcs of one row overlap, or the box is only a couple
                    // of pixels wide. Fall back to the distance field across the whole row.
                    for (int x = 0; x < w; x++)
                    {
                        float px = x + .5f - hx;
                        Shade(pixels, row + x * 4, px, py, hx, hy, px > 0f ? right : left, fill);
                    }
                }
            }
        }

        /// <summary>
        /// First pixel, counted in from one side, that the distance field is guaranteed to report as
        /// fully covered. Everything before it is shaded individually.
        /// </summary>
        private static int SolidStart(float py, float hy, float hx, Corner corner)
        {
            float rx = corner.X > 0f ? corner.X : 0f;
            float ry = corner.Y > 0f ? corner.Y : 0f;
            if (rx <= 0f) return 0;

            // How far into the corner the part that is certain to be fully covered begins, as a
            // fraction of the corner width. One means the row is flat and solid from the edge; it
            // has to be a separate value from "no solid part at all", because both were once
            // encoded as a zero x and the rows touching the top and bottom edge came out filled.
            float reach = 1f;
            if (ry > 0f)
            {
                float k = Math.Min(rx, ry);
                float slack = .5f / k;
                if (slack < 1f)
                {
                    float v = (Math.Abs(py) - (hy - ry)) / ry;
                    if (v > 0f)
                    {
                        // Inside the arc: the normalised distance is shortest dead ahead, so solve
                        // for the x where it is still half a pixel clear of the outline.
                        float square = (1f - slack) * (1f - slack) - v * v;
                        reach = square > 0f ? (float)Math.Sqrt(square) : 0f;
                    }
                }
            }

            double first = Math.Ceiling(rx - .5 - rx * reach);
            if (first < 0.0) return 0;
            return first > int.MaxValue ? int.MaxValue : (int)first;
        }

        private static void Shade(byte[] pixels, int at, float px, float py, float hx, float hy,
            Corner corner, Color32 fill)
        {
            float rx = corner.X > 0f ? corner.X : 0f;
            float ry = corner.Y > 0f ? corner.Y : 0f;
            float qx = Math.Abs(px) - (hx - rx);
            float qy = Math.Abs(py) - (hy - ry);

            float distance;
            if (qx > 0f && qy > 0f && rx > .01f && ry > .01f)
            {
                // Inside the arc: normalise so the ellipse becomes a unit circle, then scale back
                // by the smaller radius. Exact on the outline; deeper in it only reads saturated.
                float u = qx / rx;
                float v = qy / ry;
                distance = ((float)Math.Sqrt(u * u + v * v) - 1f) * Math.Min(rx, ry);
            }
            else
            {
                // Straight edges: the box distance is exact, and the radius has to be the one that
                // belongs to the edge that is actually nearest. Using the smaller radius on both
                // covered a sliver of the flat edge whenever the two axes differed.
                float ox = qx > 0f ? qx : 0f;
                float oy = qy > 0f ? qy : 0f;
                float outside = (float)Math.Sqrt(ox * ox + oy * oy);
                float inside = Math.Min(Math.Max(qx, qy), 0f);
                float radius = qy > 0f ? ry : qx > 0f ? rx : Math.Min(rx, ry);
                distance = outside + inside - radius;
            }

            float coverage = Clamp01(.5f - distance);
            if (coverage <= 0f) return;

            pixels[at] = fill.r;
            pixels[at + 1] = fill.g;
            pixels[at + 2] = fill.b;
            pixels[at + 3] = (byte)Clamp255(fill.a * coverage);
        }

        /// <summary>Uploads a Unity-order RGBA32 buffer into an existing texture.</summary>
        internal static void Upload(Texture2D texture, byte[] rgba, int width, int height)
        {
            texture.LoadRawTextureData(rgba);
            texture.Apply(false, false);
        }

        /// <summary>Wraps a fresh bake in an NTexture.</summary>
        internal static NTexture ToTexture(byte[] rgbaUnityOrder, int width, int height)
        {
            int w = Math.Max(1, width);
            int h = Math.Max(1, height);
            var texture = new Texture2D(w, h, TextureFormat.RGBA32, false);
            Upload(texture, rgbaUnityOrder, w, h);
            return new NTexture(texture);
        }

        private static float Clamp01(float value) => value < 0f ? 0f : value > 1f ? 1f : value;

        private static float Clamp255(float value) => value < 0f ? 0f : value > 255f ? 255f : value;
    }
}
