// Unit tests compiled against REAL production files:
//   mods/CesiumOverlayMod/VirtualKeyState.cs
//   mods/CesiumOverlayMod/VirtualKeyboard.cs
// Only external Unity/SDK/FairyGUI types are stubbed (Stubs.cs).
using System;
using System.Collections.Generic;
using CesiumOverlayMod;
using CesiumLoader.SDK.Inputs;
using CesiumLoader.SDK.UserInterface;
using UnityEngine;

namespace KeyMappingTests
{
    // Fake IInputBackend for testing real VirtualKeyboard
    internal sealed class FakeBackend : IInputBackend
    {
        public string Name { get; set; } = "Fake";
        public bool IsAvailable => true;

        public HashSet<KeyCode> HeldKeys = new HashSet<KeyCode>();
        public HashSet<KeyCode> DownKeys = new HashSet<KeyCode>();
        public HashSet<KeyCode> UpKeys = new HashSet<KeyCode>();

        public void SimulatePhysicalDown(KeyCode k) { HeldKeys.Add(k); DownKeys.Add(k); UpKeys.Remove(k); }
        public void SimulatePhysicalUp(KeyCode k) { HeldKeys.Remove(k); DownKeys.Remove(k); UpKeys.Add(k); }
        public void ClearPhysical() { HeldKeys.Clear(); DownKeys.Clear(); UpKeys.Clear(); }

        public bool GetKey(KeyCode key) => HeldKeys.Contains(key);
        public bool GetKeyDown(KeyCode key) => DownKeys.Contains(key);
        public bool GetKeyUp(KeyCode key) => UpKeys.Contains(key);
        public bool GetMouseButton(int b) => false;
        public bool GetMouseButtonDown(int b) => false;
        public bool GetMouseButtonUp(int b) => false;
        public float GetAxis(string n) => 0f;
        public Vector2 GetMouseScrollDelta() => Vector2.zero;
        public bool TryGetMousePosition(out Vector2 p) { p = Vector2.zero; return false; }
        public bool TryGetMouseDelta(out Vector2 d) { d = Vector2.zero; return false; }
    }

    internal static class Assert
    {
        public static int Pass = 0, Fail = 0;
        public static void True(bool cond, string msg)
        {
            if (cond) { Pass++; }
            else { Fail++; Console.WriteLine($"  FAIL: {msg}"); }
        }
        public static void False(bool cond, string msg)
        {
            if (!cond) { Pass++; }
            else { Fail++; Console.WriteLine($"  FAIL (expected false): {msg}"); }
        }
        public static void Equal<T>(T expected, T actual, string msg)
        {
            if (EqualityComparer<T>.Default.Equals(expected, actual)) { Pass++; }
            else { Fail++; Console.WriteLine($"  FAIL: {msg} — expected {expected}, got {actual}"); }
        }
    }

    internal class Program
    {
        static int Main(string[] args)
        {
            // ===== VirtualKeyState tests (34 tests) =====
            TestVirtualKeyState();

            // ===== TryParse tests (24 tests) =====
            TestTryParse();

            // ===== VirtualKeyboard tests with FakeBackend (18 tests) =====
            TestVirtualKeyboard();

            Console.WriteLine($"\n=== TOTAL: {Assert.Pass} pass, {Assert.Fail} fail ===");
            return Assert.Fail > 0 ? 1 : 0;
        }

        static void TestVirtualKeyState()
        {
            Console.WriteLine("--- VirtualKeyState tests ---");
            var s = new VirtualKeyState();

            // 1. next-frame delivery: Press at frame 0, not active at frame 0
            s.Press("src", new[] { (int)KeyCode.H }, 0, true);
            s.Advance(1);
            Assert.True(s.Down((int)KeyCode.H), "tap Down at frame+1");
            Assert.True(s.Held((int)KeyCode.H), "tap Held at frame+1");
            Assert.False(s.Up((int)KeyCode.H), "no Up during Down frame");

            // 2. pulse expires: frame+2 produces Up
            s.Advance(2);
            Assert.True(s.Up((int)KeyCode.H), "tap Up at frame+2 (pulse expired)");
            Assert.False(s.Down((int)KeyCode.H), "no Down at frame+2");
            Assert.False(s.Held((int)KeyCode.H), "no Held at frame+2");

            // 3. Up only triggers once
            s.Advance(3);
            Assert.False(s.Up((int)KeyCode.H), "Up not repeated next frame");
            Assert.False(s.Held((int)KeyCode.H), "Held gone after expiry");

            // 4. hold never expires (100 frames)
            var s2 = new VirtualKeyState();
            s2.Press("src", new[] { (int)KeyCode.F }, 0, false);
            for (int f = 1; f <= 100; f++) s2.Advance(f);
            Assert.True(s2.Held((int)KeyCode.F), "hold Held after 100 frames");
            Assert.False(s2.Up((int)KeyCode.F), "hold no Up after 100 frames");

            // 5. Release produces Up next frame
            var s3 = new VirtualKeyState();
            s3.Press("src", new[] { (int)KeyCode.O }, 0, false);
            s3.Advance(1);
            Assert.True(s3.Held((int)KeyCode.O), "hold Held before Release");
            s3.Release("src", 1);
            s3.Advance(2);
            Assert.True(s3.Up((int)KeyCode.O), "Release → Up at frame+1");
            Assert.False(s3.Held((int)KeyCode.O), "Release → not Held");

            // 6. same-frame Advance idempotent
            var s4 = new VirtualKeyState();
            s4.Press("a", new[] { (int)KeyCode.T }, 0, true);
            s4.Advance(1);
            var d1 = s4.Down((int)KeyCode.T);
            s4.Advance(1);
            Assert.Equal(d1, s4.Down((int)KeyCode.T), "Advance idempotent same frame");

            // 7. multi-source shared modifier: releasing one doesn't release other
            var s5 = new VirtualKeyState();
            s5.Press("src1", new[] { (int)KeyCode.LeftControl, (int)KeyCode.H }, 0, false);
            s5.Press("src2", new[] { (int)KeyCode.LeftControl, (int)KeyCode.O }, 0, false);
            s5.Advance(1);
            Assert.True(s5.Held((int)KeyCode.LeftControl), "multi-source shared Ctrl Held");
            Assert.True(s5.Held((int)KeyCode.H), "src1 H Held");
            Assert.True(s5.Held((int)KeyCode.O), "src2 O Held");
            s5.Release("src1", 1);
            s5.Advance(2);
            // LeftControl should still be held by src2
            Assert.True(s5.Held((int)KeyCode.LeftControl), "Ctrl still Held after src1 release (src2 active)");
            Assert.False(s5.Held((int)KeyCode.H), "src1 H gone after release");
            Assert.True(s5.Held((int)KeyCode.O), "src2 O still Held");
            Assert.True(s5.Up((int)KeyCode.H), "src1 H Up after release");

            // 8. Clear resets everything
            var s6 = new VirtualKeyState();
            s6.Press("a", new[] { (int)KeyCode.H }, 0, false);
            s6.Advance(1);
            s6.Clear();
            Assert.False(s6.Held((int)KeyCode.H), "Clear: not Held");
            Assert.False(s6.Down((int)KeyCode.H), "Clear: not Down");
            Assert.False(s6.Up((int)KeyCode.H), "Clear: not Up");
            Assert.False(s6.HasSource("a"), "Clear: source removed");

            // 9. single Press multiple keys
            var s7 = new VirtualKeyState();
            s7.Press("src", new[] { (int)KeyCode.H, (int)KeyCode.O }, 0, false);
            s7.Advance(1);
            Assert.True(s7.Held((int)KeyCode.H), "multi-key Press H Held");
            Assert.True(s7.Held((int)KeyCode.O), "multi-key Press O Held");

            // 10. same source overwrites previous keys
            var s8 = new VirtualKeyState();
            s8.Press("src", new[] { (int)KeyCode.H }, 0, false);
            s8.Advance(1);
            s8.Press("src", new[] { (int)KeyCode.O }, 1, false);
            s8.Advance(2);
            Assert.False(s8.Held((int)KeyCode.H), "overwrite: old key gone");
            Assert.True(s8.Held((int)KeyCode.O), "overwrite: new key Held");

            // 11. Advance skips expired sources
            var s9 = new VirtualKeyState();
            s9.Press("src", new[] { (int)KeyCode.T }, 0, true);
            s9.Advance(1); // active
            s9.Advance(2); // expired
            Assert.False(s9.HasSource("src"), "expired source removed");
            Assert.False(s9.Held((int)KeyCode.T), "expired: not Held");

            // 12. Down edge: only new keys produce Down (not still-held)
            var s10 = new VirtualKeyState();
            s10.Press("a", new[] { (int)KeyCode.H }, 0, false);
            s10.Advance(1);
            Assert.True(s10.Down((int)KeyCode.H), "Down edge first frame");
            s10.Press("b", new[] { (int)KeyCode.H }, 0, false); // H already held
            s10.Advance(2);
            Assert.False(s10.Down((int)KeyCode.H), "no re-Down for already-held key");
            Assert.True(s10.Held((int)KeyCode.H), "H still Held from two sources");

            Console.WriteLine("  (VirtualKeyState: done)");
        }

        static void TestTryParse()
        {
            Console.WriteLine("--- TryParse tests ---");
            int[] keys;

            // 1. single key H
            Assert.True(VirtualKeyboard.TryParse("H", out keys), "Parse H");
            Assert.Equal(1, keys.Length, "H → 1 key");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.H) >= 0, "H code present");

            // 2. Ctrl alias → LeftControl
            Assert.True(VirtualKeyboard.TryParse("Ctrl+H", out keys), "Parse Ctrl+H");
            Assert.Equal(2, keys.Length, "Ctrl+H → 2 keys");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.LeftControl) >= 0, "Ctrl → LeftControl");

            // 3. Alt alias → LeftAlt
            Assert.True(VirtualKeyboard.TryParse("Alt+H", out keys), "Parse Alt+H");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.LeftAlt) >= 0, "Alt → LeftAlt");

            // 4. Shift alias → LeftShift
            Assert.True(VirtualKeyboard.TryParse("Shift+H", out keys), "Parse Shift+H");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.LeftShift) >= 0, "Shift → LeftShift");

            // 5. Esc alias → Escape
            Assert.True(VirtualKeyboard.TryParse("Esc", out keys), "Parse Esc");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.Escape) >= 0, "Esc → Escape");

            // 6. backquote alias → BackQuote
            Assert.True(VirtualKeyboard.TryParse("`", out keys), "Parse backtick");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.BackQuote) >= 0, "` → BackQuote");

            // 7. Ctrl+H combo has correct codes
            Assert.True(VirtualKeyboard.TryParse("Ctrl+H", out keys), "Parse Ctrl+H again");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.LeftControl) >= 0 && Array.IndexOf(keys, (int)KeyCode.H) >= 0, "Ctrl+H codes present");

            // 8. None rejected
            Assert.False(VirtualKeyboard.TryParse("None", out keys), "None rejected");

            // 9. Mouse0 rejected
            Assert.False(VirtualKeyboard.TryParse("Mouse0", out keys), "Mouse0 rejected");

            // 10. >4 segments rejected
            Assert.False(VirtualKeyboard.TryParse("Ctrl+Alt+Shift+H+O", out keys), "5 segments rejected");

            // 11. empty string rejected
            Assert.False(VirtualKeyboard.TryParse("", out keys), "empty rejected");

            // 12. whitespace only rejected
            Assert.False(VirtualKeyboard.TryParse("   ", out keys), "whitespace rejected");

            // 13. case insensitive (ctrl+h)
            Assert.True(VirtualKeyboard.TryParse("ctrl+h", out keys), "lowercase ctrl+h");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.LeftControl) >= 0, "lowercase ctrl → LeftControl");

            // 14. duplicate key dedup (H+H → 1 key)
            Assert.True(VirtualKeyboard.TryParse("H+H", out keys), "H+H parse ok");
            Assert.Equal(1, keys.Length, "H+H → dedup to 1");

            // 15. invalid key name rejected
            Assert.False(VirtualKeyboard.TryParse("NotAKey", out keys), "invalid name rejected");

            // 16. leading digit rejected
            Assert.False(VirtualKeyboard.TryParse("9+H", out keys), "leading digit rejected");

            // 17. Space (Chinese 空格) accepted
            Assert.True(VirtualKeyboard.TryParse("空格", out keys), "空格 parse ok");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.Space) >= 0, "空格 → Space");

            // 18. F3 accepted
            Assert.True(VirtualKeyboard.TryParse("F3", out keys), "F3 parse ok");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.F3) >= 0, "F3 code present");

            // 19. F4 accepted
            Assert.True(VirtualKeyboard.TryParse("F4", out keys), "F4 parse ok");

            // 20. F5 accepted
            Assert.True(VirtualKeyboard.TryParse("F5", out keys), "F5 parse ok");

            // 21. F8 accepted
            Assert.True(VirtualKeyboard.TryParse("F8", out keys), "F8 parse ok");

            // 22. tilde → BackQuote
            Assert.True(VirtualKeyboard.TryParse("~", out keys), "tilde parse ok");
            Assert.True(Array.IndexOf(keys, (int)KeyCode.BackQuote) >= 0, "~ → BackQuote");

            // 23. 4-segment combo accepted
            Assert.True(VirtualKeyboard.TryParse("Ctrl+Alt+Shift+H", out keys), "4-segment ok");
            Assert.Equal(4, keys.Length, "4-segment → 4 keys");

            // 24. None case-insensitive
            Assert.False(VirtualKeyboard.TryParse("none", out keys), "lowercase none rejected");

            Console.WriteLine("  (TryParse: done)");
        }

        static void TestVirtualKeyboard()
        {
            Console.WriteLine("--- VirtualKeyboard tests (real production code + FakeBackend) ---");

            // Setup: Install VirtualKeyboard with FakeBackend as original
            InputService.Backend = null;
            InputService.IsKeyboardCaptured = false;
            UiService.ShouldBlockGameInput = false;
            Application.isFocused = true;
            FairyGUI.GRoot.inst = null;
            Time.frameCount = 0;

            var fake = new FakeBackend();
            InputService.Backend = fake;

            // Install VirtualKeyboard — wraps the fake
            VirtualKeyboard.Install();
            Assert.True(VirtualKeyboard.Instance != null, "Install: Instance set");
            Assert.True(ReferenceEquals(InputService.Backend, VirtualKeyboard.Instance), "Install: Backend is VirtualKeyboard");
            Assert.True(VirtualKeyboard.Instance.Installed, "Install: Installed=true");

            var kb = VirtualKeyboard.Instance;

            // 1. Physical key pass-through: GetKey, GetKeyDown, GetKeyUp
            fake.SimulatePhysicalDown(KeyCode.A);
            Time.frameCount = 1;
            Assert.True(kb.GetKeyDown(KeyCode.A), "physical GetKeyDown A");
            Assert.True(kb.GetKey(KeyCode.A), "physical GetKey A");
            fake.SimulatePhysicalUp(KeyCode.A);
            Time.frameCount = 2;
            Assert.True(kb.GetKeyUp(KeyCode.A), "physical GetKeyUp A");

            // 2. Virtual key tap: Send pulse, check Down then Up
            fake.ClearPhysical();
            Time.frameCount = 10;
            kb.Send("test:tap", "H", false); // pulse
            Time.frameCount = 11;
            Assert.True(kb.GetKeyDown(KeyCode.H), "virtual tap GetKeyDown H at frame+1");
            Assert.True(kb.GetKey(KeyCode.H), "virtual tap GetKey H at frame+1");
            // A tap is not one frame: TapFrames() holds the chord for ~0.55s so mods that poll
            // GetKey in "hold to act" mode still react. At 1/60 that is 33 frames.
            Time.frameCount = 12;
            Assert.True(kb.GetKey(KeyCode.H), "virtual tap still Held at frame+2");
            Time.frameCount = 42;
            Assert.True(kb.GetKey(KeyCode.H), "virtual tap still Held at frame+32");
            Time.frameCount = 43;
            Assert.True(kb.GetKeyUp(KeyCode.H), "virtual tap GetKeyUp after TapFrames");
            Assert.False(kb.GetKey(KeyCode.H), "virtual tap not Held after expiry");

            // 3. Virtual key hold: Send hold, held stays, Release → Up
            fake.ClearPhysical();
            Time.frameCount = 20;
            kb.Send("test:hold", "O", true); // hold
            Time.frameCount = 21;
            Assert.True(kb.GetKey(KeyCode.O), "virtual hold GetKey O");
            Time.frameCount = 50;
            Assert.True(kb.GetKey(KeyCode.O), "virtual hold still Held at frame 50");
            // Release by sending hold again (toggles)
            kb.Send("test:hold", "O", true);
            Time.frameCount = 51;
            Assert.True(kb.GetKeyUp(KeyCode.O), "virtual hold Released → Up");
            Assert.False(kb.GetKey(KeyCode.O), "virtual hold not Held after Release");

            // 4. Physical+virtual edge merge: physical key down + virtual key down same key
            fake.ClearPhysical();
            Time.frameCount = 60;
            fake.SimulatePhysicalDown(KeyCode.T);
            kb.Send("test:merge", "T", false); // virtual pulse too
            Time.frameCount = 61;
            // Both physical and virtual are down — should see Down
            Assert.True(kb.GetKeyDown(KeyCode.T), "merge: GetKeyDown T (physical+virtual)");
            Assert.True(kb.GetKey(KeyCode.T), "merge: GetKey T");
            Time.frameCount = 62;
            // Physical still held and the virtual pulse is still running — no Up yet.
            Assert.False(kb.GetKeyUp(KeyCode.T), "merge: no Up while physical still Held");
            Assert.True(kb.GetKey(KeyCode.T), "merge: still Held by physical");
            fake.SimulatePhysicalUp(KeyCode.T);
            Time.frameCount = 63;
            // The virtual chord keeps the key down on its own until TapFrames() runs out.
            Assert.False(kb.GetKeyUp(KeyCode.T), "merge: no Up while virtual pulse still Held");
            Assert.True(kb.GetKey(KeyCode.T), "merge: still Held by virtual pulse");
            Time.frameCount = 94; // 60 + TapFrames(33) + 1
            Assert.True(kb.GetKeyUp(KeyCode.T), "merge: Up after both expired");
            Assert.False(kb.GetKey(KeyCode.T), "merge: not Held after both expired");

            // 5. Blocked clear: when Application.isFocused=false, state clears
            fake.ClearPhysical();
            Time.frameCount = 70;
            kb.Send("test:blocked", "H", true);
            Time.frameCount = 71;
            Assert.True(kb.GetKey(KeyCode.H), "pre-block: Held");
            Application.isFocused = false;
            Time.frameCount = 72;
            // Tick() calls state.Clear() when Blocked
            kb.Tick();
            Assert.False(kb.GetKey(KeyCode.H), "Blocked: state cleared, not Held");
            Application.isFocused = true; // restore

            // 6. focus GTextInput blocks
            fake.ClearPhysical();
            Time.frameCount = 80;
            kb.Send("test:text", "H", true);
            Time.frameCount = 81;
            Assert.True(kb.GetKey(KeyCode.H), "pre-textinput: Held");
            var groot = new FairyGUI.GRoot { focus = new FairyGUI.GTextInput() };
            FairyGUI.GRoot.inst = groot;
            Time.frameCount = 82;
            kb.Tick();
            Assert.False(kb.GetKey(KeyCode.H), "GTextInput focus: state cleared");
            FairyGUI.GRoot.inst = null; // restore

            // 7. IsKeyboardCaptured is NOT a blocking condition: the SDK sets it while the
            //    soft keyboard is up, which is also when the mapping buttons are pressed. Only
            //    focus loss and GTextInput focus block (see VirtualKeyboard.AcceptsKeys).
            fake.ClearPhysical();
            Time.frameCount = 90;
            kb.Send("test:cap", "H", true);
            Time.frameCount = 91;
            Assert.True(kb.GetKey(KeyCode.H), "pre-capture: Held");
            InputService.IsKeyboardCaptured = true;
            Time.frameCount = 92;
            Assert.True(kb.GetKey(KeyCode.H), "IsKeyboardCaptured: synthetic key still Held");
            InputService.IsKeyboardCaptured = false; // restore
            kb.Send("test:cap", "H", true);          // release
            Time.frameCount = 93;
            Assert.False(kb.GetKey(KeyCode.H), "capture case: released");

            // 8. ShouldBlockGameInput is deliberately NOT consulted either: it is true whenever
            //    the overlay itself is on screen, which is exactly when a mapping is pressed.
            //    Including it made every mapping a silent no-op.
            fake.ClearPhysical();
            Time.frameCount = 100;
            kb.Send("test:ubi", "H", true);
            Time.frameCount = 101;
            Assert.True(kb.GetKey(KeyCode.H), "pre-ubi: Held");
            UiService.ShouldBlockGameInput = true;
            Time.frameCount = 102;
            Assert.True(kb.GetKey(KeyCode.H), "ShouldBlockGameInput: synthetic key still Held");
            UiService.ShouldBlockGameInput = false; // restore
            kb.Send("test:ubi", "H", true);         // release
            Time.frameCount = 103;
            Assert.False(kb.GetKey(KeyCode.H), "ubi case: released");

            // 9. Send while Blocked throws
            Application.isFocused = false;
            bool threwBlocked = false;
            try { kb.Send("test:blk", "H", false); } catch (InvalidOperationException) { threwBlocked = true; }
            Assert.True(threwBlocked, "Send while Blocked throws");
            Application.isFocused = true;

            // 10. Uninstall restores only own backend (not someone else's)
            // First, simulate another mod replacing the backend
            var otherBackend = new FakeBackend { Name = "OtherMod" };
            InputService.SetBackend(otherBackend);
            Assert.True(ReferenceEquals(InputService.Backend, otherBackend), "other mod set backend");
            // Uninstall should NOT restore (backend was replaced)
            VirtualKeyboard.Uninstall();
            Assert.True(ReferenceEquals(InputService.Backend, otherBackend), "Uninstall: did not overwrite other mod's backend");
            Assert.True(VirtualKeyboard.Instance == null, "Uninstall: Instance null");

            // Now reinstall and uninstall properly
            InputService.SetBackend(fake);
            VirtualKeyboard.Install();
            Assert.True(VirtualKeyboard.Instance != null, "Reinstall: Instance set");
            Assert.True(ReferenceEquals(InputService.Backend, VirtualKeyboard.Instance), "Reinstall: Backend is kb");
            VirtualKeyboard.Uninstall();
            Assert.True(ReferenceEquals(InputService.Backend, fake), "Proper Uninstall: restores original");
            Assert.True(VirtualKeyboard.Instance == null, "Proper Uninstall: Instance null");

            // 11. Install idempotent
            VirtualKeyboard.Install();
            var first = VirtualKeyboard.Instance;
            VirtualKeyboard.Install();
            Assert.True(ReferenceEquals(first, VirtualKeyboard.Instance), "Install idempotent");
            VirtualKeyboard.Uninstall();

            // 12. Holding() returns source presence
            InputService.SetBackend(fake);
            VirtualKeyboard.Install();
            Time.frameCount = 200;
            kb = VirtualKeyboard.Instance;
            kb.Send("test:hold2", "H", true);
            Time.frameCount = 201;
            Assert.True(kb.Holding("test:hold2"), "Holding returns true for active source");
            kb.Send("test:hold2", "H", true); // release
            Time.frameCount = 202;
            Assert.False(kb.Holding("test:hold2"), "Holding false after release");
            VirtualKeyboard.Uninstall();

            Console.WriteLine("  (VirtualKeyboard: done)");
        }
    }
}
