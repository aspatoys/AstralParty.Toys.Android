// Behavioural regression for the overlay speed page. Every case here drives the components the
// REAL RenderSpeed method builds: buttons are located by their production names
// (speed.preset.0..4, speed.restore, speed.step.0/1) and their registered onClick handlers are
// fired, so a page that merely *looks* right still fails.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.Reflection;

namespace CesiumOverlayMod
{
    internal static class Program
    {
        private static int checks;
        private static int failed;
        private static string test;

        private static void Check(bool condition, string what, string actual)
        {
            checks++;
            if (condition) { Console.WriteLine("    ok   " + what); return; }
            failed++;
            Console.WriteLine("    FAIL " + what + "  -> actual: " + actual);
        }

        private static string X(double speed) => speed.ToString("0.0#", CultureInfo.InvariantCulture) + "x";

        private static void Near(double actual, double expected, string what) =>
            Check(Math.Abs(actual - expected) < .001, what + " (expected " + X(expected) + ")", X(actual));

        // --- label helpers: no wording is locked in, only the direction the button promises ---
        private static string Flat(string label) => (label ?? string.Empty).ToLowerInvariant().Replace(" ", string.Empty);

        private static bool Mentions(string label, string token) => Flat(label).Contains(token);

        private static bool PromisesOriginalSpeed(string label) =>
            Mentions(label, "原速") || Mentions(label, "默认") || Mentions(label, "正常") || Mentions(label, "1.0x") || Mentions(label, "1x");

        // Either naming the number or saying "last time" is a legitimate way to promise the
        // stored multiplier, so the check accepts both and locks no exact wording.
        private static bool PromisesMultiplier(string label, double multiplier) =>
            Mentions(label, multiplier.ToString("0.#", CultureInfo.InvariantCulture) + "x")
            || Mentions(label, multiplier.ToString("0.0", CultureInfo.InvariantCulture) + "x")
            || Mentions(label, multiplier.ToString("0.#", CultureInfo.InvariantCulture) + "倍")
            || Mentions(label, "上次") || Mentions(label, "上一");

        // --- harness plumbing ---------------------------------------------------------------
        // SpeedPanel holds its SpeedResumeState in a readonly static, so each case resets the
        // instance state instead of swapping the field (which the runtime forbids).
        private static readonly object Session = typeof(SpeedPanel)
            .GetField("resume", BindingFlags.Static | BindingFlags.NonPublic).GetValue(null);

        private static readonly FieldInfo SuspendedField = Session.GetType()
            .GetField("<Suspended>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);

        private static readonly FieldInfo SavedField = Session.GetType()
            .GetField("<Saved>k__BackingField", BindingFlags.Instance | BindingFlags.NonPublic);

        private static void ResetSession()
        {
            SuspendedField.SetValue(Session, false);
            SavedField.SetValue(Session, 1d);
        }

        private static OverlayController Start(double persistedPrevious)
        {
            ResetSession();
            LocalSpeed.Reset();
            CesiumAndroid.AndroidApi.Notices.Clear();
            CesiumAndroid.AndroidApi.Errors.Clear();
            var page = new OverlayController();
            page.TestPrepare((float)persistedPrevious);
            return page;
        }

        private static void Preset(OverlayController page, int index) => page.TestClick("speed.preset." + index);

        private static void Run(string name, Action<OverlayController> body, double persistedPrevious = 1)
        {
            test = name;
            Console.WriteLine(name);
            try { body(Start(persistedPrevious)); }
            catch (Exception error)
            {
                failed++;
                Console.WriteLine("    FAIL threw " + error.GetType().Name + ": " + error.Message);
            }
        }

        private static int Main()
        {
            Console.WriteLine("speed page UI regression (real OverlayWindow.SetSpeed/RestoreSpeed/RenderSpeed)");
            Console.WriteLine();

            Run("1. at 3x the restore button stays usable and one tap returns to 1.0x", page =>
            {
                Preset(page, 3);
                Near(LocalSpeed.SelectedSpeed, 3, "preset 3x applied");
                int persistedCalls = page.PersistedPrevious.Count;
                var restore = page.TestFind("speed.restore");
                Check(restore != null, "restore button exists at 3x", "missing");
                Check(OverlayController.TestClickable(restore), "restore button is clickable at 3x",
                    "touchable=" + (restore?.touchable) + " alpha=" + (restore?.alpha.ToString("0.00", CultureInfo.InvariantCulture)));
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 1, "restore applied original speed");
            });

            Run("2. the next restore tap re-applies the last multiplier", page =>
            {
                Preset(page, 3);
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 1, "first tap returned to 1.0x");
                var restore = page.TestFind("speed.restore");
                Check(OverlayController.TestClickable(restore), "restore button is clickable at 1.0x",
                    "touchable=" + (restore?.touchable) + " alpha=" + (restore?.alpha.ToString("0.00", CultureInfo.InvariantCulture)));
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 3, "second tap re-applied 3x");
            });

            Run("3. 3x then 5x remembers 5x, not the old 3x", page =>
            {
                Preset(page, 3);
                Preset(page, 4);
                Near(LocalSpeed.SelectedSpeed, 5, "preset 5x applied");
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 1, "restore returned to 1.0x");
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 5, "restore re-applied the latest multiplier 5x");
            });

            Run("4. tapping the 1x preset still leaves the latest multiplier restorable", page =>
            {
                Preset(page, 4);
                Near(LocalSpeed.SelectedSpeed, 5, "preset 5x applied");
                Preset(page, 0);
                Near(LocalSpeed.SelectedSpeed, 1, "1x preset applied");
                var restore = page.TestFind("speed.restore");
                Check(OverlayController.TestClickable(restore), "restore button is clickable after the 1x preset",
                    "touchable=" + (restore?.touchable) + " alpha=" + (restore?.alpha.ToString("0.00", CultureInfo.InvariantCulture)));
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 5, "restore re-applied 5x");
            }, persistedPrevious: 1);

            Run("5. a persisted multiplier is not auto-applied at startup but is restorable", page =>
            {
                Near(LocalSpeed.SelectedSpeed, 1, "session starts at 1.0x");
                Check(LocalSpeed.Applied.Count == 0, "no speed was pushed to the backend on startup",
                    string.Join(",", LocalSpeed.Applied.ConvertAll(s => X(s))));
                var restore = page.TestFind("speed.restore");
                Check(OverlayController.TestClickable(restore), "restore button is clickable at startup",
                    "touchable=" + (restore?.touchable) + " alpha=" + (restore?.alpha.ToString("0.00", CultureInfo.InvariantCulture)));
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 3, "manual restore applied the persisted 3x");
            }, persistedPrevious: 3);

            Run("6. with no multiplier the restore button is not clickable", page =>
            {
                Near(LocalSpeed.SelectedSpeed, 1, "session starts at 1.0x");
                var restore = page.TestFind("speed.restore");
                Check(restore != null, "restore button exists", "missing");
                Check(!OverlayController.TestClickable(restore), "restore button is disabled / faded at 1.0x with no memory",
                    "touchable=" + (restore?.touchable) + " alpha=" + (restore?.alpha.ToString("0.00", CultureInfo.InvariantCulture)));
                // alpha only greys the button; FairyGUI still delivers the tap unless touchable is off.
                Check(restore != null && !restore.touchable, "restore button touchable == false with no memory",
                    "touchable=" + (restore?.touchable));
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 1, "tapping it changed nothing");
            });

            Run("7. a backend failure must not rewrite the remembered multiplier", page =>
            {
                Preset(page, 3);
                Near(LocalSpeed.SelectedSpeed, 3, "preset 3x applied");
                int persistedCalls = page.PersistedPrevious.Count;
                LocalSpeed.FailTarget = 5;
                Preset(page, 4);
                Near(LocalSpeed.SelectedSpeed, 3, "failed 5x left the running speed alone");
                Check(page.Failures.Count == 1, "the failure was caught by Run()", "failures=" + page.Failures.Count);
                Check(page.PersistedPrevious.Count == persistedCalls, "the failed 5x triggered no persistence write",
                    "writes=" + page.PersistedPrevious.Count + " (before=" + persistedCalls + ")");
                Check(Math.Abs(page.Options.PreviousSpeed - 3f) < .001f, "the persisted multiplier is still 3x",
                    X(page.Options.PreviousSpeed));
                Near(SpeedPanel.SavedSpeed, 3, "the session memory is still 3x");
                Preset(page, 0);
                Near(LocalSpeed.SelectedSpeed, 1, "1x preset applied");
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 3, "restore re-applied 3x, never the failed 5x");
            });

            Run("8. the restore label names the direction it will take", page =>
            {
                Preset(page, 3);
                var slowDown = OverlayController.TestLabel(page.TestFind("speed.restore"));
                Check(PromisesOriginalSpeed(slowDown), "at 3x the label promises the original speed (not a multiplier)", slowDown ?? "<no label>");
                page.TestClick("speed.restore");
                Near(LocalSpeed.SelectedSpeed, 1, "restore returned to 1.0x");
                var speedUp = OverlayController.TestLabel(page.TestFind("speed.restore"));
                Check(PromisesMultiplier(speedUp, 3), "at 1.0x the label names the remembered 3x", speedUp ?? "<no label>");
            });

            Console.WriteLine();
            Console.WriteLine("checks: " + checks + ", failed: " + failed + ", cases: 8");
            return failed == 0 ? 0 : 1;
        }
    }
}





