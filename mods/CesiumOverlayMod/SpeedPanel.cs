namespace CesiumOverlayMod
{
    /// <summary>Shared session entry for the passive speed mod and its in-game panel.</summary>
    public static class SpeedPanel
    {
        private static bool initialized;
        private static readonly SpeedResumeState resume = new SpeedResumeState();
        internal static bool Suspended => resume.Suspended;
        internal static double SavedSpeed => resume.Saved;
        internal static void Choose(double speed) { resume.Choose(MathClamp(speed), LocalSpeed.Choose); }
        private static double MathClamp(double speed) => System.Math.Max(1, System.Math.Min(10, speed));
        internal static void Toggle() { resume.Toggle(LocalSpeed.SelectedSpeed, LocalSpeed.Choose); }

        public static void BeginSession()
        {
            if (initialized) return;
            // Both ModBase lifecycle callbacks run on Unity's main thread.
            // Mark success last so a failed early Unity call can be retried during initialization.
            LocalSpeed.Choose(1);
            initialized = true;
        }
    }
}
