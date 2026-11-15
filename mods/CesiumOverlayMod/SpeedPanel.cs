namespace CesiumOverlayMod
{
    /// <summary>Shared session entry for the passive speed mod and its in-game panel.</summary>
    public static class SpeedPanel
    {
        private static bool initialized;

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
