namespace UnityEngine
{
    public enum RuntimePlatform { Android }
    public static class Application { public static string persistentDataPath; public static RuntimePlatform platform = RuntimePlatform.Android; }
    public static class Screen { public static int width = 2400, height = 1080; }
    public static class Time { public static float timeScale = 1, fixedDeltaTime = .02f, maximumDeltaTime = .333f; }
    public struct Color
    {
        public float r, g, b, a;
        public Color(float red, float green, float blue, float alpha) { r = red; g = green; b = blue; a = alpha; }
    }
}
