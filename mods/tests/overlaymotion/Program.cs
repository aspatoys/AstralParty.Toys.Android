// Unit tests compiled against the REAL production file mods/CesiumOverlayMod/OverlayMotion.cs
// (pure C#; no Unity/FairyGUI reference needed).
using System;
using CesiumOverlayMod;

namespace OverlayMotionTests
{
    static class Program
    {
        static int checks, failures;
        static void Assert(bool cond, string msg)
        {
            checks++;
            if (!cond) { failures++; Console.Error.WriteLine("FAIL: " + msg); }
        }
        static void Near(float expected, float actual, float eps, string msg)
        {
            checks++;
            if (!(Math.Abs(expected - actual) <= eps))
            { failures++; Console.Error.WriteLine($"FAIL: {msg}: expected {expected} got {actual}"); }
        }

        static OverlayMotion Opened(float now)
        { var m = new OverlayMotion(); m.SetOpen(true, now); return m; }

        // ── rail leads on open ────────────────────────────────────────────────
        static void TestOpenStartsAtZero()
        {
            var m = Opened(0);
            Near(0, m.Rail(0), 1e-6f, "open rail starts 0");
            Near(0, m.Main(0), 1e-6f, "open main starts 0");
        }
        static void TestMainDelayedOnOpen()
        {
            var m = Opened(0);
            Assert(m.Rail(.05f) > 0, "rail advances immediately (no delay)");
            Near(0, m.Main(.05f), 1e-6f, "main still 0 before .07s delay");
            Assert(m.Main(.08f) > 0, "main advances after .07s delay");
        }
        static void TestRailLeadsMainOnOpen()
        {
            var m = Opened(0);
            // Only while main is still travelling; past .22s main's spring overshoots above rail.
            foreach (float t in new[] { .08f, .10f, .14f, .18f, .20f, .22f })
                Assert(m.Rail(t) >= m.Main(t), $"rail ahead of main at {t}s (rail {m.Rail(t)} main {m.Main(t)})");
        }
        static void TestRailCompletesBeforeMainOnOpen()
        {
            var m = Opened(0);
            Near(1, m.Rail(.22f), 1e-6f, "rail reaches 1 at .22s");
            Assert(m.Main(.22f) < 1, "main not yet finished at .22s");
            Near(1, m.Main(.35f), 1e-6f, "main reaches 1 at .35s");
        }
        static void TestOpenMonotonic()
        {
            var m = Opened(0);
            float lastRail = -1, lastMain = -1;
            bool settled = false;
            for (int i = 0; i <= 40; i++)
            {
                float t = i * .01f;
                float rail = m.Rail(t), main = m.Main(t);
                Assert(rail >= lastRail - 1e-6f, $"open rail monotonic at {t}s");
                Assert(rail <= 1 + 1e-6f, $"open rail never exceeds 1 at {t}s");
                // The spring track rises until it overshoots 1, then settles back down to 1.
                if (!settled) Assert(main >= lastMain - 1e-6f, $"open main rising at {t}s");
                if (main > 1) settled = true;
                Assert(main <= 1.02f, $"open main overshoot bounded at {t}s");
                lastRail = rail; lastMain = main;
            }
            Near(1, m.Main(1f), 1e-5f, "open main settles back to exactly 1");
        }
        static void TestOpenTerminalState()
        {
            var m = Opened(0);
            Near(1, m.Rail(5f), 1e-6f, "open rail terminal 1");
            Near(1, m.Main(5f), 1e-6f, "open main terminal 1");
        }

        // ── main leads on close, rail delayed .08 ─────────────────────────────
        static void TestCloseStartsAtOne()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, 1f);
            Near(1, m.Rail(1f), 1e-6f, "close rail starts 1");
            Near(1, m.Main(1f), 1e-6f, "close main starts 1");
        }
        static void TestRailDelayedOnClose()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, 1f);
            Assert(m.Main(1.04f) < 1, "main already closing at +.04s");
            Near(1, m.Rail(1.04f), 1e-6f, "rail still 1 during its .08s delay");
            Assert(m.Rail(1.10f) < 1, "rail starts closing after .08s");
        }
        static void TestMainLeadsRailOnClose()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, 1f);
            foreach (float d in new[] { .05f, .10f, .14f, .18f, .20f })
                Assert(m.Main(1f + d) < m.Rail(1f + d), $"main leads close at +{d}s");
        }
        static void TestCloseMonotonic()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, 1f);
            float lastRail = 2, lastMain = 2;
            for (int i = 0; i <= 40; i++)
            {
                float t = 1f + i * .01f;
                float rail = m.Rail(t), main = m.Main(t);
                Assert(rail <= lastRail + 1e-6f, $"close rail monotonic at +{(t - 1f)}s");
                Assert(main <= lastMain + 1e-6f, $"close main monotonic at +{(t - 1f)}s");
                lastRail = rail; lastMain = main;
            }
        }
        static void TestCloseTerminalState()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, 1f);
            Near(0, m.Main(2f), 1e-6f, "close main terminal 0");
            Near(0, m.Rail(2f), 1e-6f, "close rail terminal 0");
        }

        // ── interruptible reversal ────────────────────────────────────────────
        static void TestReversalContinuity()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0);
            float railBefore = m.Rail(.12f), mainBefore = m.Main(.12f);
            m.SetOpen(false, .12f);
            Near(railBefore, m.Rail(.12f), 1e-5f, "rail continuous on reverse");
            Near(mainBefore, m.Main(.12f), 1e-5f, "main continuous on reverse");
        }
        static void TestConsecutiveReversals()
        {
            var m = new OverlayMotion();
            float t = 0; bool open = true;
            m.SetOpen(true, 0);
            for (int i = 0; i < 12; i++)
            {
                t += .031f; open = !open;
                float rail = m.Rail(t), main = m.Main(t);
                m.SetOpen(open, t);
                Near(rail, m.Rail(t), 1e-5f, $"rail continuous at reversal {i}");
                Near(main, m.Main(t), 1e-5f, $"main continuous at reversal {i}");
            }
        }
        static void TestFastReverseSettlesToTarget()
        {
            var m = new OverlayMotion();
            m.SetOpen(true, 0); m.SetOpen(false, .10f); m.SetOpen(true, .12f);
            Near(1, m.Rail(2f), 1e-6f, "rail settles to 1 after reverse-open");
            Near(1, m.Main(2f), 1e-6f, "main settles to 1 after reverse-open");
            m.SetOpen(false, 3f);
            Near(0, m.Rail(5f), 1e-6f, "rail settles to 0 after reverse-close");
            Near(0, m.Main(5f), 1e-6f, "main settles to 0 after reverse-close");
        }
        static void TestManyTogglesNoNaN()
        {
            var m = new OverlayMotion();
            float t = 0; float maxRail = 0, maxMain = 0;
            m.SetOpen(true, 0);
            for (int i = 0; i < 200; i++)
            {
                t += .013f;
                m.SetOpen((i & 1) == 0, t);
                foreach (float now in new[] { t, t + .004f, t + .05f, t + .5f })
                {
                    float rail = m.Rail(now), main = m.Main(now);
                    Assert(!float.IsNaN(rail) && !float.IsInfinity(rail), $"rail finite at toggle {i}");
                    Assert(!float.IsNaN(main) && !float.IsInfinity(main), $"main finite at toggle {i}");
                    Assert(rail >= -1e-4f && rail <= 1.02f, $"rail in range at toggle {i}: {rail}");
                    Assert(main >= -1e-4f && main <= 1.02f, $"main in range at toggle {i}: {main}");
                    maxRail = Math.Max(maxRail, rail); maxMain = Math.Max(maxMain, main);
                }
            }
            Console.WriteLine($"  motion peak: rail={maxRail:F4} main={maxMain:F4} (main >1 is the intended open bounce, clamped by Mathf.Clamp01)");
        }
        static void TestSpringOvershootIsBounded()
        {
            var m = Opened(0);
            float peak = 0;
            for (int i = 0; i <= 100; i++) peak = Math.Max(peak, m.Main(i * .005f));
            Assert(peak > 1f, "open main intentionally overshoots slightly (bounce)");
            Assert(peak <= 1.02f, $"open main overshoot stays under 2%: {peak}");
            Console.WriteLine($"  open main peak = {peak:F5}");
        }

        // ── OpacitySlider pure mapping ────────────────────────────────────────
        const float Start = 20f, Span = 160f;
        static void TestSliderDefault()
        {
            Near(.5f, OpacitySlider.Default, 1e-6f, "default opacity 50%");
            Near(.5f, OpacitySlider.At(float.NaN, Start, Span), 1e-6f, "NaN x falls back to default");
            Near(.5f, OpacitySlider.At(float.PositiveInfinity, Start, Span), 1e-6f, "+Inf x falls back to default");
            Near(.5f, OpacitySlider.At(100, Start, 0), 1e-6f, "zero width falls back to default");
            Near(.5f, OpacitySlider.At(100, Start, -5), 1e-6f, "negative width falls back to default");
        }
        static void TestSliderEdges()
        {
            Near(.25f, OpacitySlider.At(Start, Start, Span), 1e-6f, "left edge = 25%");
            Near(1f, OpacitySlider.At(Start + Span, Start, Span), 1e-6f, "right edge = 100%");
            Near(.25f, OpacitySlider.At(Start - 40, Start, Span), 1e-6f, "left overflow clamps to 25%");
            Near(1f, OpacitySlider.At(Start + Span + 40, Start, Span), 1e-6f, "right overflow clamps to 100%");
        }
        static void TestSliderCenter()
        {
            // track center = 62.5%; Math.Round is banker's rounding, so the midpoint snaps to 62%.
            Near(.62f, OpacitySlider.At(Start + Span * .5f, Start, Span), 1e-6f, "bar center = 62.5% rounded to even 62%");
        }
        static void TestSliderMonotonic()
        {
            float last = -1;
            for (int i = 0; i <= 200; i++)
            {
                float v = OpacitySlider.At(Start + Span * i / 200f, Start, Span);
                Assert(v >= last, $"slider monotonic at sample {i}: {v} < {last}");
                last = v;
            }
        }
        static void TestSliderOnePercentSteps()
        {
            for (int i = 0; i <= 200; i++)
            {
                float v = OpacitySlider.At(Start + Span * i / 200f, Start, Span);
                Near(0, v * 100 - (float)Math.Round(v * 100), 1e-3f, $"1% step at sample {i}: {v}");
            }
        }
        static void TestSliderRoundTrip()
        {
            for (int pct = 25; pct <= 100; pct++)
            {
                float value = pct / 100f;
                float pos = OpacitySlider.Position(value, Start, Span);
                Near(value, OpacitySlider.At(pos, Start, Span), 1e-6f, $"round-trip {pct}%");
            }
        }
        static void TestDefaultHalfPosition()
        {
            float pos = OpacitySlider.Position(OpacitySlider.Default, Start, Span);
            Near(Start + Span / 3f, pos, 1e-4f, "50% sits one third along the 25..100 track");
            Near(.5f, OpacitySlider.At(pos, Start, Span), 1e-6f, "thumb of 50% maps back to 50%");
        }
        static void TestSliderPositionClamp()
        {
            Near(Start, OpacitySlider.Position(.1f, Start, Span), 1e-6f, "position clamps below 25%");
            Near(Start + Span, OpacitySlider.Position(1.5f, Start, Span), 1e-6f, "position clamps above 100%");
            Near(Start, OpacitySlider.Position(.5f, Start, 0), 1e-6f, "zero-width position stays at start");
        }
        static void TestSliderPositionFinite()
        {
            foreach (float x in new[] { 0f, 20f, 100f, 180f, 200f })
            {
                float pos = OpacitySlider.Position(OpacitySlider.At(x, Start, Span), Start, Span);
                Assert(!float.IsNaN(pos) && !float.IsInfinity(pos), $"position finite for x={x}");
                Assert(pos >= Start - 1e-4f && pos <= Start + Span + 1e-4f, $"position in track for x={x}: {pos}");
            }
        }

        static void Main()
        {
            Console.WriteLine("OverlayMotion / OpacitySlider (production sources: mods/CesiumOverlayMod/OverlayMotion.cs)");
            TestOpenStartsAtZero();
            TestMainDelayedOnOpen();
            TestRailLeadsMainOnOpen();
            TestRailCompletesBeforeMainOnOpen();
            TestOpenMonotonic();
            TestOpenTerminalState();
            TestCloseStartsAtOne();
            TestRailDelayedOnClose();
            TestMainLeadsRailOnClose();
            TestCloseMonotonic();
            TestCloseTerminalState();
            TestReversalContinuity();
            TestConsecutiveReversals();
            TestFastReverseSettlesToTarget();
            TestManyTogglesNoNaN();
            TestSpringOvershootIsBounded();
            TestSliderDefault();
            TestSliderEdges();
            TestSliderCenter();
            TestSliderMonotonic();
            TestSliderOnePercentSteps();
            TestSliderRoundTrip();
            TestDefaultHalfPosition();
            TestSliderPositionClamp();
            TestSliderPositionFinite();

            if (failures > 0)
            {
                Console.Error.WriteLine($"{failures} of {checks} checks FAILED.");
                Environment.Exit(1);
            }
            Console.WriteLine($"All overlay motion/slider checks passed ({checks} assertions, 25 test methods).");
        }
    }
}