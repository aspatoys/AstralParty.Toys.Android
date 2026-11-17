// mods/tests/dockstate/Program.cs — DockState direct-compile tests
using System;

namespace UnityEngine { public static class Mathf { public static float Clamp01(float v) => v < 0 ? 0 : v > 1 ? 1 : v; } }

namespace CesiumOverlayMod
{
    static class Runner
    {
        static int _pass, _fail;
        static void Assert(bool cond, string label)
        {
            if (cond) { _pass++; Console.WriteLine($"  PASS  {label}"); }
            else       { _fail++; Console.Error.WriteLine($"  FAIL  {label}"); }
        }
        static void Section(string name) => Console.WriteLine($"\n=== {name} ===");

        static void Main()
        {
            // ── DockIconState: IdleSeconds const ──
            Section("DockIconState: IdleSeconds = 5");
            Assert(DockIconState.IdleSeconds == 5f, "IdleSeconds const = 5");

            var dock = new DockIconState();
            dock.Wake(0f);
            Assert(dock.RestProgress(4.9f)  == 0f, "Before 5s: RestProgress = 0");
            Assert(dock.RestProgress(4.99f) == 0f, "4.99s: RestProgress = 0");
            Assert(dock.RestProgress(5.0f)  == 0f, "5.0s: fade starts, progress = 0");
            float pa = dock.RestProgress(5.11f);
            Assert(pa > 0f && pa < 1f, "5.11s: RestProgress in (0,1)");
            // 5.22s: (5.22-5.0)/0.22 = 1.0 exactly in double, but float arithmetic rounds
            Assert(dock.RestProgress(5.22f) >= 0.999f, "5.22s: RestProgress ~= 1 (float tolerance)");
            Assert(dock.RestProgress(5.30f) == 1f, "5.30s: RestProgress clamped 1");
            Assert(dock.RestProgress(999f)  == 1f, "Long idle: RestProgress clamped 1");

            Section("DockIconState: Rest/Wake cycle");
            dock.Rest(0f);
            Assert(dock.RestProgress(0.05f) > 0f, "Forced Rest: RestProgress > 0 immediately");
            dock.Wake(0.1f);
            Assert(dock.RestProgress(0.1f) == 0f, "After Wake: RestProgress = 0");
            Assert(dock.RestProgress(0.1f + 4.9f) == 0f, "Post-wake: still 0 at 4.9s");
            Assert(dock.RestProgress(0.1f + 5.0f + 0.30f) == 1f, "Post-wake: = 1 after 5s+0.30s");

            Section("DockIconState: Rest begins fade immediately");
            var d2 = new DockIconState();
            d2.Wake(100f);
            d2.Rest(100f);
            Assert(d2.RestProgress(100f) == 0f, "Rest at t=100: same tick = 0");
            Assert(d2.RestProgress(100.11f) > 0f, "Rest: 0.11s later > 0");
            Assert(d2.RestProgress(100.30f) == 1f, "Rest: 0.30s later = 1 (past fade)");

            // ── DockLayout portrait ──
            Section("DockLayout: portrait 1080x1920 scale=3 size=160 right=false");
            var lay = DockLayout.Measure(1080, 1920, 3f, 160, false, 860);
            Assert(lay.Side > 0, "Side > 0");
            Assert(lay.Rail > 0, "Rail > 0");
            Assert(lay.Gap > 0, "Gap > 0");
            Assert(lay.MainX >= lay.RailX + lay.Rail, "MainX >= RailX+Rail (gap, left dock)");
            Assert(lay.MainX + lay.Side <= 1081, "Total width within 1080");
            Assert(lay.Y >= 0, "Y >= 0");
            Assert(lay.Y + lay.Side <= 1921, "Y + Side <= 1920");

            Section("DockLayout: portrait right=true mirror");
            var layR = DockLayout.Measure(1080, 1920, 3f, 160, true, 860);
            Assert(layR.RailX > layR.MainX, "right=true: RailX > MainX");
            Assert(layR.MainX >= 0, "right=true: MainX >= 0");
            Assert(layR.RailX + layR.Rail <= 1081, "right=true: rail fits screen");

            Section("DockLayout: landscape 1920x1080");
            var layL = DockLayout.Measure(1920, 1080, 3f, 160, false, 440);
            Assert(layL.MainX + layL.Side <= 1921, "landscape: width fits");
            Assert(layL.Y + layL.Side <= 1081, "landscape: height fits");
            Assert(layL.MainX >= layL.RailX + layL.Rail, "landscape: gap respected");

            Section("DockLayout: small screen 720x1280 scale=2 size=100");
            var layS = DockLayout.Measure(720, 1280, 2f, 100, false, 540);
            Assert(layS.Side > 0, "small: Side > 0");
            Assert(layS.MainX + layS.Side <= 721, "small: fits width");
            Assert(layS.Gap > 0, "small: gap > 0");

            Section("DockLayout: Rail < Side (sidebar width < main side length)");
            Assert(lay.Rail < lay.Side, "Rail < Side");

            Section("DockLayout: screen boundary right=true large size=200");
            var layBr = DockLayout.Measure(1080, 1920, 3f, 200, true, 860);
            Assert(layBr.RailX + layBr.Rail <= 1081, "right large: fits width");
            Assert(layBr.MainX >= 0, "right large: MainX >= 0");

            Section("DockLayout: gap separates rail from main");
            Assert(lay.MainX - (lay.RailX + lay.Rail) >= lay.Gap - 0.001f,
                "distance(rail→main) >= Gap");

            // ── SpeedResumeState ──
            Section("SpeedResumeState: Choose(2) → Toggle→1 → Toggle→2");
            var res = new SpeedResumeState();
            double cur = 1.0;
            Action<double> apply = v => cur = v;

            res.Choose(2.0, apply);
            Assert(Math.Abs(cur - 2.0) < 0.001, "Choose(2): applied 2");
            Assert(!res.Suspended, "Choose(2): not suspended");
            Assert(Math.Abs(res.Saved - 2.0) < 0.001, "Choose(2): Saved=2");

            res.Toggle(res.Saved, apply);
            Assert(Math.Abs(cur - 1.0) < 0.001, "Toggle→1: applied 1");
            Assert(res.Suspended, "Toggle→1: suspended");
            Assert(Math.Abs(res.Saved - 2.0) < 0.001, "Toggle→1: Saved still 2");

            res.Toggle(res.Saved, apply);
            Assert(Math.Abs(cur - 2.0) < 0.001, "Toggle→2: applied saved=2");
            Assert(!res.Suspended, "Toggle→2: not suspended");

            Section("SpeedResumeState: 4 toggles (even) returns to start");
            var res2 = new SpeedResumeState();
            double cur2 = 1.0;
            Action<double> apply2 = v => cur2 = v;
            res2.Choose(3.0, apply2); cur2 = 3.0;
            for (int i = 0; i < 4; i++) res2.Toggle(cur2, apply2);
            Assert(Math.Abs(cur2 - 3.0) < 0.001, "4 toggles: back to 3");
            Assert(!res2.Suspended, "4 toggles: not suspended");

            Section("SpeedResumeState: Choose(3) clears suspended");
            var res3 = new SpeedResumeState();
            double cur3 = 1.0;
            Action<double> apply3 = v => cur3 = v;
            res3.Choose(2.0, apply3);
            res3.Toggle(2.0, apply3);
            Assert(res3.Suspended, "pre-clear: suspended");
            res3.Choose(3.0, apply3);
            Assert(!res3.Suspended, "Choose(3): clears suspended");
            Assert(Math.Abs(cur3 - 3.0) < 0.001, "Choose(3): applied 3");

            Section("SpeedResumeState: apply exception propagates");
            var res4 = new SpeedResumeState();
            double cur4 = 1.0;
            res4.Choose(2.0, v => cur4 = v);
            bool threw = false;
            Action<double> bad = v => { threw = true; throw new InvalidOperationException("fail"); };
            try { res4.Toggle(2.0, bad); } catch (InvalidOperationException) { }
            Assert(threw, "apply exception propagates");

            Section("SpeedResumeState: invalid Choose throws");
            bool t0 = false, tHi = false, tNaN = false;
            try { new SpeedResumeState().Choose(0.0, v => {}); }   catch (ArgumentOutOfRangeException) { t0 = true; }
            try { new SpeedResumeState().Choose(999.0, v => {}); } catch (ArgumentOutOfRangeException) { tHi = true; }
            try { new SpeedResumeState().Choose(double.NaN, v => {}); } catch (ArgumentOutOfRangeException) { tNaN = true; }
            Assert(t0,   "Choose(0): throws");
            Assert(tHi,  "Choose(999): throws");
            Assert(tNaN, "Choose(NaN): throws");

            Section("SpeedResumeState: invalid Toggle(bad current, not suspended) throws");
            bool tToggle = false;
            try { new SpeedResumeState().Toggle(0.0, v => {}); } catch (ArgumentOutOfRangeException) { tToggle = true; }
            Assert(tToggle, "Toggle(0, not suspended): throws");

            Section("SpeedResumeState: Toggle(bad current, suspended) no throw (uses Saved)");
            var res5 = new SpeedResumeState();
            res5.Choose(2.0, v => {}); res5.Toggle(2.0, v => {});
            bool noThrow = true;
            try { res5.Toggle(0.0, v => {}); } catch { noThrow = false; }
            Assert(noThrow, "Toggle(bad, suspended): no throw");

            Console.WriteLine($"\n=== RESULTS: {_pass} pass, {_fail} fail ===");
            Environment.Exit(_fail > 0 ? 1 : 0);
        }
    }
}
