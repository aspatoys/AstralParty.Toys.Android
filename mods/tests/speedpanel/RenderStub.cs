// Lightweight stand-in for the parts of ModEntry.cs / OverlayWindow.cs that the extracted
// speed-page methods reach for. SavePreviousSpeed and Save only record the persisted option
// value; Px/Font/Text/Button/RoundRect mirror the production implementations closely enough
// that the real RenderSpeed method sees the same component tree it builds on device.
using System;
using System.Collections.Generic;
using FairyGUI;
using UnityEngine;
#pragma warning disable CS0414 // the mirrored ModEntry fields are only read by other pages

namespace CesiumOverlayMod
{
    public sealed partial class OverlayController
    {
        private OverlayOptions options = OverlayOptions.Defaults();
        private GComponent body;
        private float width = 1000f, height = 800f, uiScale = 1f;
        private string contentKey;

        internal OverlayOptions Options => options;
        internal readonly List<Exception> Failures = new List<Exception>();
        internal readonly List<float> PersistedPrevious = new List<float>();

        private float Px(float design) => design * uiScale;
        private int Font(float design) => Mathf.RoundToInt(design * uiScale * Mathf.Clamp(Options.FontScale, .8f, 1.4f));

        private void SavePreviousSpeed(float value) => Save(current =>
            current.PreviousSpeed = OverlayOptions.Clamp(value, 1f, 10f, 1f));

        private void Save(Action<OverlayOptions> mutate)
        {
            mutate(options);
            PersistedPrevious.Add(options.PreviousSpeed);
        }

        private void Run(Action action)
        {
            try { action(); }
            catch (Exception error)
            {
                Failures.Add(error);
                CesiumAndroid.AndroidApi.LogError(error.Message);
                CesiumAndroid.AndroidApi.ShowNotice("出错了：" + error.Message);
            }
            contentKey = null;
            Render();
        }

        private void Render()
        {
            if (body == null) return;
            body.RemoveChildren();
            RenderSpeed();
        }

        private GTextField Text(GComponent parent, string value, int size, float x, float y, float w, float h, Color? color = null, bool center = false)
        {
            var text = new GTextField
            {
                touchable = false, text = value, autoSize = AutoSizeType.Shrink,
                verticalAlign = VertAlignType.Middle, align = center ? AlignType.Center : AlignType.Left,
            };
            var format = text.textFormat; format.size = size; format.color = color ?? OverlayTheme.OnSurface; text.textFormat = format;
            text.SetSize(w, h); text.SetXY(x, y); parent.AddChild(text); return text;
        }

        private GComponent Button(GComponent parent, string label, string name, float x, float y, float w, float h, Action action, Color? background = null, Color? foreground = null)
        {
            var button = new GComponent { name = name, opaque = true };
            button.SetXY(x, y); button.SetSize(w, h); parent.AddChild(button);
            RoundRect(button, w, h, background ?? OverlayTheme.SurfaceContainerHigh, Px(14));
            if (!string.IsNullOrEmpty(label)) Text(button, label, Font(15), Px(10), 0, w - Px(20), h, foreground, true);
            button.onTouchBegin.Add(() => { if (!button.isDisposed) button.alpha = .72f; });
            button.onTouchEnd.Add(() => { if (!button.isDisposed) button.alpha = 1f; });
            button.onClick.Add((EventContext context) => { context.StopPropagation(); action(); });
            return button;
        }

        private GGraph RoundRect(GComponent parent, float w, float h, Color color, float radius, bool border = false)
        {
            var graph = new GGraph { touchable = false };
            graph.DrawRoundRect(w, h, color, new[] { radius, radius, radius, radius });
            parent.AddChild(graph);
            return graph;
        }

        // ---- test hooks (the only non-production-shaped members) --------------------------

        internal void TestPrepare(float persistedPrevious)
        {
            options = OverlayOptions.Defaults();
            options.PreviousSpeed = persistedPrevious;
            Failures.Clear();
            PersistedPrevious.Clear();
            contentKey = null;
            body = new GComponent { name = "body" };
            body.SetSize(Px(400), height);
            Render();
        }

        internal GComponent TestFind(string name) => body == null ? null : body.Child(name);

        internal static string TestLabel(GComponent component)
        {
            if (component == null) return null;
            for (int i = 0; i < component.children.Count; i++)
            {
                var text = component.children[i] as GTextField;
                if (text != null) return text.text;
            }
            return null;
        }

        internal static bool TestClickable(GComponent component) =>
            component != null && component.touchable && component.alpha > .9f;

        internal void TestClick(string name)
        {
            var component = TestFind(name);
            if (component == null) throw new InvalidOperationException("no button named " + name + " on the speed page");
            if (!component.touchable) return; // a disabled FairyGUI button swallows the tap
            component.onClick.Invoke();
        }
    }
}

