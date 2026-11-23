using System;
using System.IO;
using CesiumAndroid;
using CesiumOverlayMod;

internal static class Program
{
    private static int checks;
    private static void Check(bool value, string label) { checks++; if (!value) throw new Exception(label); }
    private static void Main(string[] args)
    {
        string root = Path.GetFullPath(args[0]);
        Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", root);
        Directory.CreateDirectory(Path.Combine(root, "data"));
        Check(AndroidApi.Root == root, "bootstrap root is authoritative");
        Check(AndroidApi.GetModConfigPath("Test") == Path.Combine(root, "mods", "Test", "config.json"), "canonical config layout");
        bool enabled = false;
        AndroidApi.RegisterToggle("Test", "enabled", "开关", () => enabled, value => enabled = value);
        AndroidApi.GetActions("test")[0].Set(true);
        Check(enabled, "API toggle dispatch");
        int clicked = 0;
        AndroidApi.RegisterButton("Test", "button", "按钮", () => clicked++);
        AndroidApi.RegisterButton("Test", "button", "更新按钮", () => clicked += 2);
        AndroidApi.GetActions("Test")[1].Run();
        Check(clicked == 2 && AndroidApi.GetActions("Test").Count == 2, "registration replaces callbacks without duplicate rows");
        string appearance = Path.Combine(root, "data", "toys-appearance.txt");
        File.WriteAllText(appearance, "light\nlight\n");
        OverlayTheme.Poll();
        Check(!OverlayTheme.Dark && OverlayTheme.Background.r > .9f && OverlayTheme.Text.r < .2f, "light surface and readable text");
        File.WriteAllText(appearance, "dark\ndark\n");
        Check(OverlayTheme.Poll() && OverlayTheme.Dark && OverlayTheme.Background.r < .2f, "theme updates live");
        File.WriteAllText(appearance, "system\nlight\n");
        OverlayTheme.Poll();
        Check(!OverlayTheme.Dark, "system falls back to last resolved light theme when JNI absent");
        File.WriteAllText(appearance, "system\ndark\n");
        OverlayTheme.Poll();
        Check(OverlayTheme.Dark, "system falls back to last resolved dark theme when JNI absent");
        float scale = 1;
        var speed = new UnitySpeedScale(() => scale, value => scale = value);
        speed.Synchronize(3); speed.Synchronize(3);
        Check(scale == 3, "repeated updates do not multiply speed exponentially");
        scale = 0; speed.Synchronize(3); speed.Restore();
        Check(scale == 0, "pause remains paused");
        scale = 3; speed.Restore();
        Check(scale == 1, "resume restores deferred baseline");
        scale = .5f; speed.Synchronize(2); scale = .25f; speed.Restore();
        Check(scale == .25f, "restoration preserves later game writes");
        float step = .02f;
        var budget = new FixedStepBudget(() => step, () => .333f, value => step = value);
        budget.Synchronize(3); budget.Synchronize(3);
        Check(Math.Abs(step - .06f) < .00001, "fixed step remains stable");
        budget.Restore();
        Check(Math.Abs(step - .02f) < .00001, "fixed step restores original value");
        Console.WriteLine("PASS " + checks + " overlay theme/API/speed assertions");
    }
}
