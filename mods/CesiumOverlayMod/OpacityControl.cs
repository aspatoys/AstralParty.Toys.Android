using System;
using FairyGUI;
using UnityEngine;

namespace CesiumOverlayMod
{
    public sealed partial class OverlayController
    {
        private bool opacityDragging;
        private int opacityTouch = -1;
        private float previewOpacity;
        private void AddOpacitySlider(float w, GTextField readout)
        {
            var slider = new GComponent { name = "opacity.slider", opaque = true };
            slider.SetXY(0, 122); slider.SetSize(w, 60); body.AddChild(slider);
            float left = 20, span = Math.Max(1, w - 40);
            var track = new GGraph { touchable = false };
            track.DrawRoundRect(span, 8, OverlayTheme.Border, new[] { 4f, 4f, 4f, 4f });
            track.SetXY(left, 26); slider.AddChild(track);
            var fill = new GGraph { touchable = false };
            fill.SetXY(left, 26); slider.AddChild(fill);
            var thumb = new GGraph { touchable = false };
            thumb.DrawRoundRect(32, 32, OverlayTheme.Accent, new[] { 16f, 16f, 16f, 16f });
            slider.AddChild(thumb);
            Action<float> paint = value =>
            {
                float position = OpacitySlider.Position(value, left, span);
                fill.DrawRoundRect(Math.Max(1, position - left), 8, OverlayTheme.Accent, new[] { 4f, 4f, 4f, 4f });
                thumb.SetXY(position - 16, 14);
                readout.text = "不透明度  " + Math.Round(value * 100) + "%";
            };
            Action<EventContext> sample = context =>
            {
                float x = slider.GlobalToLocal(context.inputEvent.position).x;
                previewOpacity = OpacitySlider.At(x, left, span);
                paint(previewOpacity);
            };
            paint(options.Opacity);
            slider.onTouchBegin.Add(context =>
            {
                if (opacityDragging) return;
                opacityDragging = true; opacityTouch = context.inputEvent.touchId;
                context.CaptureTouch(); context.StopPropagation(); sample(context);
                VirtualKeyboard.Instance?.Clear();
            });
            slider.onTouchMove.Add(context =>
            {
                if (!opacityDragging || context.inputEvent.touchId != opacityTouch) return;
                context.StopPropagation(); sample(context);
            });
            slider.onTouchEnd.Add(context =>
            {
                if (!opacityDragging || context.inputEvent.touchId != opacityTouch) return;
                context.StopPropagation(); sample(context);
                float value = previewOpacity;
                opacityDragging = false; opacityTouch = -1;
                Run(() => SaveOpacity(value));
            });
        }
    }
}
