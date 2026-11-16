// Unit tests compiled against REAL production files:
//   mods/CesiumOverlayMod/OverlayOptions.cs
//   mods/CesiumOverlayMod/OverlayPresentation.cs
// No Unity/FairyGUI/SDK types are needed; both files have no such dependencies.
using System;
using CesiumOverlayMod;

namespace OverlayOptionsTests
{
    static class Program
    {
        static int failures;

        static void Fail(string msg) { Console.Error.WriteLine("FAIL: " + msg); failures++; }
        static void Assert(bool cond, string msg) { if (!cond) Fail(msg); }
        static void AssertEq(string expected, string actual, string msg)
        { if (expected != actual) Fail(msg + $": expected [{expected}] got [{actual}]"); }
        static void AssertEq(float expected, float actual, float eps, string msg)
        { if (Math.Abs(expected - actual) > eps) Fail(msg + $": expected {expected} got {actual}"); }

        // ─── OverlayOptions.Clamp ────────────────────────────────────────────
        static void TestClampNaN()
        {
            float r = OverlayOptions.Clamp(float.NaN, .25f, 1f, .94f);
            AssertEq(.94f, r, 1e-6f, "Clamp NaN→fallback");
        }
        static void TestClampInfinity()
        {
            AssertEq(.94f, OverlayOptions.Clamp(float.PositiveInfinity, .25f, 1f, .94f), 1e-6f, "Clamp +Inf→fallback");
            AssertEq(.94f, OverlayOptions.Clamp(float.NegativeInfinity, .25f, 1f, .94f), 1e-6f, "Clamp -Inf→fallback");
        }
        static void TestClampBelow()
        {
            AssertEq(.25f, OverlayOptions.Clamp(.1f, .25f, 1f, .94f), 1e-6f, "Clamp below→low");
        }
        static void TestClampAbove()
        {
            AssertEq(1.6f, OverlayOptions.Clamp(2f, .65f, 1.6f, 1f), 1e-6f, "Clamp above→high");
        }
        static void TestClampInRange()
        {
            AssertEq(.8f, OverlayOptions.Clamp(.8f, .25f, 1f, .94f), 1e-6f, "Clamp in-range passthrough");
        }

        // ─── OverlayOptions.Encode / Parse round-trip ────────────────────────
        static void TestEncodeDecodeRoundtrip()
        {
            var opts = new OverlayOptions { Opacity = .75f, Width = 1.2f, Height = .9f };
            opts.Groups.Add(new OverlayGroup { Name = "测试组", Mods = { "HideHudMod", "AutoThanksMod" } });
            string encoded = opts.Encode();
            var parsed = OverlayOptions.Parse(encoded);
            AssertEq(.75f, parsed.Opacity, 1e-5f, "RT opacity");
            AssertEq(1.2f, parsed.Width,   1e-5f, "RT width");
            AssertEq(.9f,  parsed.Height,  1e-5f, "RT height");
            Assert(parsed.Groups.Count == 1, "RT group count");
            AssertEq("测试组", parsed.Groups[0].Name, "RT group name");
            Assert(parsed.Groups[0].Mods.Count == 2, "RT mod count");
            AssertEq("HideHudMod",    parsed.Groups[0].Mods[0], "RT mod[0]");
            AssertEq("AutoThanksMod", parsed.Groups[0].Mods[1], "RT mod[1]");
        }

        // ─── Chinese group name round-trip via base64 (UTF-8) ────────────────
        static void TestChineseGroupName()
        {
            var opts = new OverlayOptions();
            opts.Groups.Add(new OverlayGroup { Name = "功能类", Mods = { "HideHudMod" } });
            var parsed = OverlayOptions.Parse(opts.Encode());
            AssertEq("功能类", parsed.Groups[0].Name, "Chinese name round-trip");
        }

        // ─── Empty group list ─────────────────────────────────────────────────
        static void TestEmptyGroups()
        {
            var opts = new OverlayOptions { Opacity = .5f };
            string encoded = opts.Encode();
            var parsed = OverlayOptions.Parse(encoded);
            Assert(parsed.Groups.Count == 0, "empty groups after parse");
        }

        // ─── SpeedHackMod is stripped on parse ───────────────────────────────
        static void TestSpeedHackModExcluded()
        {
            var opts = new OverlayOptions();
            var group = new OverlayGroup { Name = "g" };
            // Insert SpeedHackMod manually bypassing Defaults
            group.Mods.Add("SpeedHackMod");
            group.Mods.Add("HideHudMod");
            opts.Groups.Add(group);
            var parsed = OverlayOptions.Parse(opts.Encode());
            foreach (string mod in parsed.Groups[0].Mods)
                Assert(mod != "SpeedHackMod", "SpeedHackMod must be stripped");
            Assert(parsed.Groups[0].Mods.Contains("HideHudMod"), "valid mod retained");
        }

        // ─── Cross-group dedup (case-insensitive) ────────────────────────────
        static void TestDedup()
        {
            // Encode two groups both containing HideHudMod (case variation in second)
            string raw = "version=1\nopacity=0.94\nwidth=1\nheight=1\n";
            // group A: HideHudMod  group B: hidehudmod (lowercase dup)
            string b64A = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("A"));
            string b64B = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("B"));
            raw += "group=" + b64A + "|HideHudMod\n";
            raw += "group=" + b64B + "|hidehudmod,AutoThanksMod\n";
            var parsed = OverlayOptions.Parse(raw);
            // HideHudMod in group A, group B should NOT have it again
            Assert(parsed.Groups[0].Mods.Contains("HideHudMod"), "first group has HideHudMod");
            Assert(!parsed.Groups[1].Mods.Contains("hidehudmod") && !parsed.Groups[1].Mods.Contains("HideHudMod"),
                "dup stripped from second group");
            Assert(parsed.Groups[1].Mods.Contains("AutoThanksMod"), "non-dup retained in second group");
        }

        // ─── Invalid version → FormatException ───────────────────────────────
        static void TestBadVersion()
        {
            bool threw = false;
            try { OverlayOptions.Parse("version=2\nopacity=0.5\n"); }
            catch (FormatException) { threw = true; }
            Assert(threw, "bad version must throw FormatException");
        }

        // ─── Null input → FormatException ────────────────────────────────────
        static void TestNullThrows()
        {
            bool threw = false;
            try { OverlayOptions.Parse(null); }
            catch (FormatException) { threw = true; }
            Assert(threw, "null must throw FormatException");
        }

        // ─── Oversized input → FormatException ───────────────────────────────
        static void TestTooLarge()
        {
            bool threw = false;
            try { OverlayOptions.Parse(new string('x', 65537)); }
            catch (FormatException) { threw = true; }
            Assert(threw, "oversized input must throw FormatException");
        }

        // ─── Opacity NaN in wire → fallback .94 ──────────────────────────────
        static void TestOpacityNaNInWire()
        {
            string raw = "version=1\nopacity=NaN\nwidth=1\nheight=1\n";
            var parsed = OverlayOptions.Parse(raw);
            AssertEq(.94f, parsed.Opacity, 1e-5f, "NaN opacity fallback");
        }

        // ─── Width/Height boundary values ────────────────────────────────────
        static void TestWidthHeightBoundary()
        {
            var opts = new OverlayOptions { Width = .65f, Height = 1.6f };
            var parsed = OverlayOptions.Parse(opts.Encode());
            AssertEq(.65f, parsed.Width,  1e-5f, "width boundary low");
            AssertEq(1.6f, parsed.Height, 1e-5f, "height boundary high");
        }

        // ─── Blank group name → "分组" ────────────────────────────────────────
        static void TestBlankGroupName()
        {
            string b64 = Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes("   "));
            string raw = "version=1\nopacity=0.94\nwidth=1\nheight=1\ngroup=" + b64 + "|HideHudMod\n";
            var parsed = OverlayOptions.Parse(raw);
            AssertEq("分组", parsed.Groups[0].Name, "blank name → 分组");
        }

        // ─── Assigned() case-insensitive ─────────────────────────────────────
        static void TestAssigned()
        {
            var opts = OverlayOptions.Defaults();
            Assert(opts.Assigned("HideHudMod"), "Assigned exact case");
            Assert(opts.Assigned("hidehudmod"), "Assigned lower case");
            Assert(!opts.Assigned("UnknownMod"), "not Assigned");
        }

        // ─── Defaults ────────────────────────────────────────────────────────
        static void TestDefaults()
        {
            var d = OverlayOptions.Defaults();
            AssertEq(.94f, d.Opacity, 1e-5f, "default opacity");
            AssertEq(1f,   d.Width,   1e-5f, "default width");
            AssertEq(1f,   d.Height,  1e-5f, "default height");
            Assert(d.Groups.Count == 1, "default one group");
            AssertEq("功能类", d.Groups[0].Name, "default group name");
        }

        // ─── OverlayPresentation animation ────────────────────────────────────
        static void TestAnimationOpenClosedTerminalState()
        {
            var p = new OverlayPresentation();
            // Before first toggle, should be closed, progress=0
            float prog0 = p.Progress(0f);
            Assert(prog0 == 0f || prog0 <= 0f, "initial progress 0");
            Assert(!p.Open, "initial closed");

            // Toggle open at t=0, then sample at t >> duration → should reach ~1
            p.Toggle(0f);
            Assert(p.Open, "open after toggle");
            float progFull = p.Progress(100f); // t >> OpenDuration
            AssertEq(1f, progFull, 1e-4f, "progress reaches 1 at open end");
        }

        static void TestAnimationCloseTerminalState()
        {
            var p = new OverlayPresentation();
            p.Toggle(0f);  // open
            p.Toggle(100f); // close after open fully settled
            Assert(!p.Open, "closed after second toggle");
            float prog = p.Progress(200f);
            AssertEq(0f, prog, 1e-4f, "progress reaches 0 at close end");
        }

        static void TestAnimationMidwayReversal()
        {
            var p = new OverlayPresentation();
            // Open, then immediately reverse at progress ~0.5
            p.Toggle(0f); // open, duration = OpenDuration * 1 = 0.34
            float midT = OverlayPresentation.OpenDuration * 0.5f;
            float midProgress = p.Progress(midT);
            Assert(midProgress > 0f && midProgress < 1f, "mid progress in (0,1)");

            // Reverse: close from mid
            p.Toggle(midT);
            Assert(!p.Open, "reversed to closed");
            // The from should be midProgress, to should be 0
            // After another long wait, should settle at 0
            float finalProg = p.Progress(midT + 100f);
            AssertEq(0f, finalProg, 1e-4f, "reversed close settles at 0");
        }

        static void TestAnimationMidwayReversalConsecutive()
        {
            var p = new OverlayPresentation();
            p.Toggle(0f);  // open
            p.Toggle(OverlayPresentation.OpenDuration * 0.5f); // close mid-open
            float t2 = OverlayPresentation.OpenDuration * 0.5f + OverlayPresentation.CloseDuration * 0.3f;
            float midClose = p.Progress(t2);
            // Reverse again: open from mid-close
            p.Toggle(t2);
            Assert(p.Open, "re-opened mid-close");
            float finalProg = p.Progress(t2 + 100f);
            AssertEq(1f, finalProg, 1e-4f, "consecutive reversal settles at 1");
        }

        // ─── OverlayPresentation.Bounds layout ────────────────────────────────
        static void TestBoundsLandscape()
        {
            var b = OverlayPresentation.Bounds(1280f, 720f); // landscape
            // Expected: w=1280*.5-16=624, h=720-24=696
            AssertEq(624f, b.Width,  .01f, "landscape width");
            AssertEq(696f, b.Height, .01f, "landscape height");
        }

        static void TestBoundsPortrait()
        {
            var b = OverlayPresentation.Bounds(720f, 1280f); // portrait
            // Expected: w=720-24=696, h=1280*.5-16=624
            AssertEq(696f, b.Width,  .01f, "portrait width");
            AssertEq(624f, b.Height, .01f, "portrait height");
        }

        static void TestBoundsSquare()
        {
            // Square = landscape (w>=h), so w*.5-16, h-24
            var b = OverlayPresentation.Bounds(600f, 600f);
            AssertEq(284f, b.Width,  .01f, "square width (600*.5-16)");
            AssertEq(576f, b.Height, .01f, "square height (600-24)");
        }

        static void TestBoundsMinimum()
        {
            // Very small screen → clamp to 1
            var b = OverlayPresentation.Bounds(1f, 1f); // landscape
            Assert(b.Width >= 1f, "min width >= 1");
            Assert(b.Height >= 1f, "min height >= 1");
        }

        // ─── SelectTab ────────────────────────────────────────────────────────
        static void TestSelectTab()
        {
            var p = new OverlayPresentation();
            p.SelectTab(0);
            Assert(p.Tab == 0, "tab=0");
            AssertEq(OverlayPresentation.SpeedId, p.Selected, "tab0 selected = SpeedId");
            p.SelectTab(1);
            Assert(p.Tab == 1, "tab=1");
            Assert(p.Selected == null, "tab!=0 selected = null");
        }

        static void Main(string[] args)
        {
            TestClampNaN();
            TestClampInfinity();
            TestClampBelow();
            TestClampAbove();
            TestClampInRange();
            TestEncodeDecodeRoundtrip();
            TestChineseGroupName();
            TestEmptyGroups();
            TestSpeedHackModExcluded();
            TestDedup();
            TestBadVersion();
            TestNullThrows();
            TestTooLarge();
            TestOpacityNaNInWire();
            TestWidthHeightBoundary();
            TestBlankGroupName();
            TestAssigned();
            TestDefaults();
            TestAnimationOpenClosedTerminalState();
            TestAnimationCloseTerminalState();
            TestAnimationMidwayReversal();
            TestAnimationMidwayReversalConsecutive();
            TestBoundsLandscape();
            TestBoundsPortrait();
            TestBoundsSquare();
            TestBoundsMinimum();
            TestSelectTab();

            if (failures > 0)
            {
                Console.Error.WriteLine($"{failures} test(s) FAILED.");
                Environment.Exit(1);
            }
            else
            {
                Console.WriteLine($"All OverlayOptions/Presentation tests passed (27 assertions checked).");
            }
        }
    }
}
