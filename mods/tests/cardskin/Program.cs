using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using CardSkinMod;

internal static class Program
{
    private static int _checks;
    private static int _failures;
    private static readonly List<string> _log = new List<string>();

    private static void Check(bool value, string label)
    {
        _checks++;
        if (value)
        {
            _log.Add("PASS " + label);
            return;
        }
        _failures++;
        _log.Add("FAIL " + label);
        Console.WriteLine("FAIL " + label);
    }

    private static int Main(string[] args)
    {
        string fixtures = Path.GetFullPath(args[0]);
        string output = args.Length > 1 ? Path.GetFullPath(args[1]) : null;
        string skins = Path.Combine(fixtures, "skins");

        Console.WriteLine("fixtures=" + fixtures);

        FileUriChecks();
        AnimationChecks(skins);
        ModelChecks(skins);
        CatalogResolutionChecks(fixtures, skins);

        string summary = $"checks={_checks} failures={_failures}";
        Console.WriteLine(summary);
        if (output != null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(output));
            File.WriteAllText(output, string.Join("\n", _log) + "\n" + summary + "\n", new UTF8Encoding(false));
        }
        return _failures == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ URI
    private static void FileUriChecks()
    {
        // 回归：旧实现在 Android 上拼出四个斜杠 file:////data/...
        string android = CardFileUri.Compose("/data/user/0/com.feimo.astralparty/files/CesiumLoader/skins/fine_turning/UT_HandCard_10001.png");
        Check(android == "file:///data/user/0/com.feimo.astralparty/files/CesiumLoader/skins/fine_turning/UT_HandCard_10001.png",
            "android absolute path yields exactly three slashes");
        Check(!android.Contains("////"), "no four-slash file URI");

        string windows = CardFileUri.Compose("C:/Games/AstralParty/skins/default/a.png");
        Check(windows == "file:///C:/Games/AstralParty/skins/default/a.png", "windows drive path yields file:///C:/...");

        string unc = CardFileUri.Compose("//server/share/a.png");
        Check(unc == "file://server/share/a.png", "unc path keeps two slashes after scheme");

        Check(CardFileUri.Compose("") == string.Empty, "empty path yields empty uri");
        Check(CardFileUri.FromPath(@"C:\Games\a b\c.png") == "file:///C:/Games/a b/c.png", "windows backslashes normalized");
    }

    // ------------------------------------------------------------- animation
    private static void AnimationChecks(string skins)
    {
        string root = Path.Combine(skins, "__animation__");
        if (Directory.Exists(root)) Directory.Delete(root, true);
        Directory.CreateDirectory(root);

        string binding = "UT_HandCard_10001";
        string frames = Path.Combine(root, binding + ".frames");
        Directory.CreateDirectory(frames);
        byte[] png = MakePng(404, 400);
        for (int i = 0; i < 3; i++) File.WriteAllBytes(Path.Combine(frames, i.ToString("D6", CultureInfo.InvariantCulture) + ".png"), png);
        string manifest = Path.Combine(root, binding + ".cardanim");
        File.WriteAllText(manifest, "version=1\nfps=15\nframeCount=3\nwidth=404\nheight=400\n", new UTF8Encoding(false));

        Check(CardAnimation.TryLoad(manifest, out CardAnimation animation), "v1 cardanim manifest accepted");
        Check(animation != null && animation.Fps == 15 && animation.FrameCount == 3 && animation.Width == 404 && animation.Height == 400,
            "manifest fields parsed");
        Check(animation.FramePath(0).EndsWith("UT_HandCard_10001.frames" + Path.DirectorySeparatorChar + "000000.png", StringComparison.Ordinal),
            "frame path derives NNNNNN.png inside .frames");
        Check(animation.FrameAt(0) == 0 && animation.FrameAt(15 * 3 + 5) == animation.FrameAt(5), "frame index wraps over loop length");
        Check(CardAnimation.ValidPngHeader(png, 404, 400), "png header matches declared size");
        Check(!CardAnimation.ValidPngHeader(png, 400, 404), "png header rejects swapped size");

        Check(!CardAnimation.TryLoad(WriteManifest(root, "bad-fps", "version=1\nfps=99\nframeCount=3\nwidth=404\nheight=400\n"), out _),
            "fps above 30 rejected");
        Check(!CardAnimation.TryLoad(WriteManifest(root, "bad-version", "version=2\nfps=15\nframeCount=3\nwidth=404\nheight=400\n"), out _),
            "version other than 1 rejected");
        Check(!CardAnimation.TryLoad(WriteManifest(root, "bad-pixels", "version=1\nfps=15\nframeCount=300\nwidth=560\nheight=560\n"), out _),
            "over 60,000,000 total pixels rejected");
        Check(!CardAnimation.TryLoad(WriteManifest(root, "missing-keys", "version=1\nfps=15\nframeCount=3\nwidth=404\n"), out _),
            "incomplete manifest rejected");
        Check(!CardAnimation.TryLoad(WriteManifest(root, "dup-key", "version=1\nfps=15\nfps=15\nframeCount=3\nwidth=404\nheight=400\n"), out _),
            "duplicate key rejected");
        Check(!CardAnimation.TryLoad(WriteManifest(root, "missing-frame", "version=1\nfps=15\nframeCount=4\nwidth=404\nheight=400\n"), out _),
            "manifest whose frames are missing rejected");

        // 60,000,000 像素上限正好等于 300*404*400=48,480,000 之内的合法例子
        Directory.CreateDirectory(Path.Combine(root, "big.frames"));
        for (int i = 0; i < 2; i++) File.WriteAllBytes(Path.Combine(root, "big.frames", i.ToString("D6", CultureInfo.InvariantCulture) + ".png"), MakePng(560, 560));
        Check(CardAnimation.TryLoad(WriteManifest(root, "big", "version=1\nfps=30\nframeCount=2\nwidth=560\nheight=560\n"), out _),
            "560x560 frames within pixel budget accepted");
    }

    private static string WriteManifest(string root, string name, string text)
    {
        string path = Path.Combine(root, name + ".cardanim");
        File.WriteAllText(path, text, new UTF8Encoding(false));
        return path;
    }

    // ----------------------------------------------------------------- model
    private static void ModelChecks(string skins)
    {
        string fine = Path.Combine(skins, "fine_turning");
        var model = new CardSkinModel();
        Check(model.LoadSkinDirectory(fine, "fine_turning"), "real user skin directory loads");
        Check(model.FileCount == 185, $"real user skin indexes 185 asset keys (got {model.FileCount})");
        Check(model.AnimationCount == 0, "static-only user skin has no animations");
        // 真实 skin.json 用 \uXXXX 转义，旧实现会显示字面量。
        Check(model.Metadata.Name == "星趴fine_turning卡牌", $"skin.json unicode escapes decoded (got '{model.Metadata.Name}')");
        Check(model.Metadata.Author == "Aperfish", "skin.json author decoded");
        Check(model.Metadata.Description.Length > 0 && !model.Metadata.Description.Contains("\\u"), "skin.json description decoded without escapes");

        Check(model.TryGetSkinForCard(10001, "加速", "UT_HandCard_10001", out string file) && file.EndsWith("UT_HandCard_10001.png", StringComparison.Ordinal),
            "asset key resolves to its own file");
        Check(model.TryGetSkinForCard(10006, "防御(特大)", "UT_HandCard_10006_sfw", out string sfw) && sfw.EndsWith("UT_HandCard_10006_sfw.png", StringComparison.Ordinal),
            "sfw asset key keeps the sfw file instead of the normal one");
        Check(model.TryGetSkinForCard(10006, "防御(特大)", "UT_HandCard_10006", out string normal) && normal.EndsWith("UT_HandCard_10006.png", StringComparison.Ordinal),
            "non-sfw asset key resolves the non-sfw file");

        var byId = new CardSkinModel();
        // 目录里只有素材名文件：ID/中文名索引必须能从素材名派生。
        Check(byId.LoadSkinDirectory(fine, "fine_turning"), "reload for id lookup");
        Check(byId.TryGetSkinFile(20033, null, out string idFile) && idFile.EndsWith("UT_HandCard_20033.png", StringComparison.Ordinal),
            "bare numeric key resolves through UT_HandCard_ prefix");
        Check(byId.TryGetSkinFile(12702, null, out string eventFile) && eventFile.EndsWith("UT_HandCard_12702.png", StringComparison.Ordinal) == false
            && eventFile.EndsWith("UT_Event_12702.png", StringComparison.Ordinal),
            "event id resolves through UT_Event_ prefix");

        Check(CardSkinModel.ExtractCardId("UT_HandCard_10006_sfw", out bool isSfw) == 10006 && isSfw, "extract id and sfw flag");
        Check(CardSkinModel.ExtractCardId("card_12345", out bool plain) == 12345 && !plain, "lowercase card_ prefix accepted");
        Check(CardSkinModel.ExtractCardId("UT_Item_AltArt_13020001", out _) == 13020001, "alt-art prefix accepted");
        Check(CardSkinModel.ExtractCardId("加速卡", out _) == -1, "chinese-only name has no id");
        Check(CardSkinModel.ExtractCardId("UT_HandCard_10001", out _) == 10001, "id keeps leading digits only");

        // 目录不存在 / 空目录时的回退语义
        var missing = new CardSkinModel();
        Check(!missing.LoadSkinDirectory(Path.Combine(skins, "does-not-exist")), "missing skin directory reports failure");
        Check(!missing.TryGetSkinForCard(10001, "加速", "UT_HandCard_10001", out _), "missing directory falls back to original art");

        // 默认方案目录 (Win default-skins.zip 提取)
        var defaultModel = new CardSkinModel();
        Check(defaultModel.LoadSkinDirectory(Path.Combine(skins, "default"), "default"), "default skin directory loads");
        Check(defaultModel.FileCount > 150, $"default skin indexes many assets (got {defaultModel.FileCount})");
        Check(defaultModel.Metadata.Name == "原版卡面(默认方案)", "default skin metadata decoded");

        // 根平铺布局 (Win 导入形态 C)
        var flat = new CardSkinModel();
        Check(flat.LoadSkinDirectory(Path.Combine(skins, "flat-shape-c"), "flat"), "root-flat layout loads");
        Check(flat.FileCount == 3, $"root-flat layout indexes three asset keys (got {flat.FileCount})");

        // 中文名文件回退 (目录里用人名命名的图片)
        string named = Path.Combine(skins, "__names__");
        if (Directory.Exists(named)) Directory.Delete(named, true);
        Directory.CreateDirectory(named);
        File.WriteAllBytes(Path.Combine(named, "加速卡.png"), MakePng(64, 64));
        var nameModel = new CardSkinModel();
        Check(nameModel.LoadSkinDirectory(named, "names"), "name-keyed directory loads");
        Check(nameModel.TryGetSkinFile(0, "加速卡", out string nameFile) && nameFile.EndsWith("加速卡.png", StringComparison.Ordinal),
            "chinese card name resolves to file");

        // 重复 ID 的非 SFW 优先
        string dup = Path.Combine(skins, "__dup__");
        if (Directory.Exists(dup)) Directory.Delete(dup, true);
        Directory.CreateDirectory(dup);
        File.WriteAllBytes(Path.Combine(dup, "UT_HandCard_10001_sfw.png"), MakePng(64, 64));
        File.WriteAllBytes(Path.Combine(dup, "UT_HandCard_10001.png"), MakePng(64, 64));
        var dupModel = new CardSkinModel();
        Check(dupModel.LoadSkinDirectory(dup, "dup"), "duplicate id directory loads");
        Check(dupModel.TryGetSkinFile(10001, null, out string chosen) && chosen.EndsWith("UT_HandCard_10001.png", StringComparison.Ordinal),
            "non-sfw file wins the shared id when both exist");
    }

    // ------------------------------------------------- catalog ↔ real model
    private static void CatalogResolutionChecks(string fixtures, string skins)
    {
        string catalog = Path.Combine(fixtures, "catalog", "catalog.json");
        if (!File.Exists(catalog))
        {
            Check(false, "catalog fixture missing at " + catalog);
            return;
        }

        var model = new CardSkinModel();
        model.LoadSkinDirectory(Path.Combine(skins, "fine_turning"), "fine_turning");

        string text = File.ReadAllText(catalog, Encoding.UTF8);
        var catalogKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        int total = 0, resolved = 0;
        var unresolved = new List<string>();
        var byCategory = new Dictionary<string, int[]>(StringComparer.Ordinal);
        foreach (var entry in MiniJson.Array(text))
        {
            string key = MiniJson.Get(entry, "key");
            string name = MiniJson.Get(entry, "name");
            string category = MiniJson.Get(entry, "category");
            if (string.IsNullOrEmpty(key)) continue;
            total++;
            catalogKeys.Add(key);
            int id = CardSkinModel.ExtractCardId(key, out _);
            bool found = model.TryGetSkinForCard(id, name, key, out _);
            if (found) resolved++; else unresolved.Add(key);
            if (!byCategory.TryGetValue(category ?? "?", out int[] counters)) byCategory[category ?? "?"] = counters = new int[2];
            counters[0]++;
            if (found) counters[1]++;
        }

        // The user ZIP ships 185 card images + skin.json; its stems must all be listed in
        // the catalog (the catalog is a superset: official tables also carry keys the
        // supplied package does not override, which must keep the original art).
        string zipKeysFile = Path.Combine(fixtures, "catalog", "zip-keys.txt");
        Check(File.Exists(zipKeysFile), "user zip key fixture present");
        var zipKeys = File.Exists(zipKeysFile)
            ? new List<string>(File.ReadAllText(zipKeysFile, Encoding.UTF8).Split('\n', StringSplitOptions.RemoveEmptyEntries).Select(line => line.Trim()))
            : new List<string>();
        int missingFromCatalog = zipKeys.Count(key => key != "skin" && !catalogKeys.Contains(key));
        Check(zipKeys.Count == 186, $"user zip fixture carries 186 stems (got {zipKeys.Count})");
        Check(missingFromCatalog == 0, $"every supplied skin key is listed in the catalog (missing {missingFromCatalog})");

        // The model indexes by asset key, so the supplied images resolve exactly; the
        // remainder are official keys with no supplied file and must fall back.
        int suppliedCardKeys = zipKeys.Count(key => key != "skin");
        Check(suppliedCardKeys == 185, $"supplied card images = 185 (got {suppliedCardKeys})");
        var resolvedKeys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var entry in MiniJson.Array(text))
        {
            string key = MiniJson.Get(entry, "key");
            string name = MiniJson.Get(entry, "name");
            if (string.IsNullOrEmpty(key)) continue;
            int id = CardSkinModel.ExtractCardId(key, out _);
            if (model.TryGetSkinForCard(id, name, key, out _)) resolvedKeys.Add(key);
        }
        var suppliedUnresolved = zipKeys
            .Where(key => key != "skin" && !resolvedKeys.Contains(key))
            .ToList();
        Console.WriteLine("resolved catalog keys: " + resolvedKeys.Count);
        Console.WriteLine("supplied unresolved: " + (suppliedUnresolved.Count == 0 ? "(none)" : string.Join(",", suppliedUnresolved)));
        Check(suppliedUnresolved.Count == 0, $"every supplied card image resolves against the real model (unresolved {suppliedUnresolved.Count})");
        Check(unresolved.Count == total - resolvedKeys.Count, "every remaining catalog key falls back to original art");
        Check(unresolved.All(key => !zipKeys.Contains(key, StringComparer.OrdinalIgnoreCase)),
            "no supplied card image is among the unresolved keys");
        foreach (var pair in byCategory)
            Console.WriteLine($"category {pair.Key}: {pair.Value[1]}/{pair.Value[0]} covered");
        if (unresolved.Count > 0) Console.WriteLine("unresolved sample: " + string.Join(",", unresolved.GetRange(0, Math.Min(8, unresolved.Count))));
    }

    // ---------------------------------------------------------------- helpers
    private static byte[] MakePng(int width, int height)
    {
        var bytes = new List<byte>();
        bytes.AddRange(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 });
        bytes.AddRange(new byte[] { 0, 0, 0, 13, 73, 72, 68, 82 });
        bytes.AddRange(BigEndian(width));
        bytes.AddRange(BigEndian(height));
        bytes.AddRange(new byte[] { 8, 6, 0, 0, 0 });
        bytes.AddRange(new byte[] { 0, 0, 0, 0 });
        bytes.AddRange(new byte[] { 0, 0, 0, 0, 73, 69, 78, 68, 0, 0, 0, 0 });
        return bytes.ToArray();
    }

    private static byte[] BigEndian(int value) => new[]
    {
        (byte)(value >> 24), (byte)(value >> 16), (byte)(value >> 8), (byte)value
    };
}

/// <summary>够用的极简 JSON 读取器，只解析测试用 catalog 的 [{...}] 结构（对象可跨行）。</summary>
internal static class MiniJson
{
    public static List<Dictionary<string, string>> Array(string text)
    {
        var result = new List<Dictionary<string, string>>();
        int index = 0;
        while (index < text.Length)
        {
            int open = text.IndexOf('{', index);
            if (open < 0) break;
            int close = FindObjectEnd(text, open);
            if (close < 0) break;
            string body = text.Substring(open + 1, close - open - 1);
            var map = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (string field in SplitFields(body))
            {
                int colon = field.IndexOf(':');
                if (colon <= 0) continue;
                string key = field.Substring(0, colon).Trim().Trim('"');
                string value = field.Substring(colon + 1).Trim();
                if (value.StartsWith("\"", StringComparison.Ordinal) && value.EndsWith("\"", StringComparison.Ordinal) && value.Length >= 2)
                    value = value.Substring(1, value.Length - 2);
                map[key] = Unescape(value);
            }
            result.Add(map);
            index = close + 1;
        }
        return result;
    }

    public static string Get(Dictionary<string, string> map, string key) => map.TryGetValue(key, out string value) ? value : null;

    private static int FindObjectEnd(string text, int open)
    {
        bool inString = false, escape = false;
        for (int i = open + 1; i < text.Length; i++)
        {
            char c = text[i];
            if (escape) { escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '}') return i;
        }
        return -1;
    }

    private static IEnumerable<string> SplitFields(string body)
    {
        var parts = new List<string>();
        var builder = new StringBuilder();
        bool inString = false, escape = false;
        foreach (char c in body)
        {
            if (escape) { builder.Append(c); escape = false; continue; }
            if (c == '\\') { builder.Append(c); escape = true; continue; }
            if (c == '"') { inString = !inString; builder.Append(c); continue; }
            if (c == ',' && !inString) { parts.Add(builder.ToString()); builder.Clear(); continue; }
            builder.Append(c);
        }
        if (builder.Length > 0) parts.Add(builder.ToString());
        return parts;
    }

    private static string Unescape(string value)
    {
        if (value.IndexOf('\\') < 0) return value;
        var builder = new StringBuilder();
        for (int i = 0; i < value.Length; i++)
        {
            if (value[i] != '\\' || i + 1 >= value.Length) { builder.Append(value[i]); continue; }
            char next = value[++i];
            switch (next)
            {
                case 'n': builder.Append('\n'); break;
                case 'r': builder.Append('\r'); break;
                case 't': builder.Append('\t'); break;
                case '"': builder.Append('"'); break;
                case '\\': builder.Append('\\'); break;
                case '/': builder.Append('/'); break;
                case 'u':
                    if (i + 4 < value.Length && int.TryParse(value.Substring(i + 1, 4), NumberStyles.HexNumber,
                            CultureInfo.InvariantCulture, out int code))
                    {
                        builder.Append((char)code);
                        i += 4;
                    }
                    break;
                default: builder.Append(next); break;
            }
        }
        return builder.ToString();
    }
}