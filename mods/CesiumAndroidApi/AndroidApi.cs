using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Reflection;
using UnityEngine;

namespace CesiumAndroid
{
    public sealed class ActionItem
    {
        public string ModId;
        public string Id;
        public string Label;
        public Func<bool> Get;
        public Action<bool> Set;
        public Action Run;
        public bool IsToggle { get { return Get != null; } }
    }

    /// <summary>
    /// 悬浮窗「功能」页里的一条快捷项。
    /// <para>
    /// 和 <see cref="ActionItem"/> 的区别：动作项挂在某个 mod 的详情页里，必须先找到那个 mod
    /// 才能看见；快捷项直接出现在「功能」页，适合「面板显示 / 隐藏」这类随时要按的开关。
    /// </para>
    /// </summary>
    public sealed class ShortcutItem
    {
        public string ModId;
        public string Id;
        public string Label;
        /// <summary>副标题，例如当前状态或说明，留空则不显示。</summary>
        public string Detail;
        /// <summary>非空时这一行渲染成开关，并按返回值着色。</summary>
        public Func<bool> Get;
        /// <summary>点击时执行；开关行的点击也会调用它（如果给了）。</summary>
        public Action Run;
        public bool IsToggle { get { return Get != null; } }
    }

    /// <summary>
    /// Android 端开发者接口。mod 引用 CesiumAndroidApi.dll 后即可使用。
    /// </summary>
    public static class AndroidApi
    {
        // Adapted for the Shizuku/root installer: the official game's own writable directory.
        public static string Root { get { return Environment.GetEnvironmentVariable("CESIUM_LOADER_DIR") ?? Path.Combine(Application.persistentDataPath, "CesiumLoader"); } }

        private static readonly List<ActionItem> _actions = new List<ActionItem>();
        private static readonly List<ShortcutItem> _shortcuts = new List<ShortcutItem>();
        private static readonly Dictionary<string, string> _modNames = new Dictionary<string, string>();
        private static string _currentModId = "";

        public static string Notice { get; private set; } = "";
        public static void ShowNotice(string message) { Notice = message ?? ""; }

        public static bool IsAndroid
        {
            get { return Application.platform == RuntimePlatform.Android; }
        }

        public static string ModsDirectory { get { return Path.Combine(Root, "mods"); } }
        public static string ConfigDirectory { get { return Path.Combine(Root, "configs"); } }
        public static string LogPath { get { return Path.Combine(Root, "developer.log"); } }

        public static float ScreenWidth { get { return Screen.width; } }
        public static float ScreenHeight { get { return Screen.height; } }

        public static List<ActionItem> Actions { get { return _actions; } }

        private static void PutAction(ActionItem item)
        {
            for (int i = 0; i < _actions.Count; i++)
                if (string.Equals(_actions[i].ModId, item.ModId, StringComparison.OrdinalIgnoreCase) && _actions[i].Id == item.Id)
                { _actions[i] = item; return; }
            _actions.Add(item);
        }

        /// <summary>由 loader 在加载每个 mod 前设置，用于把开关归属到对应 mod。</summary>
        public static void SetCurrentMod(string modId)
        {
            _currentModId = string.IsNullOrEmpty(modId) ? "other" : modId;
        }

        public static void RegisterMod(string modId, string displayName)
        {
            if (string.IsNullOrEmpty(modId)) return;
            _modNames[modId] = string.IsNullOrEmpty(displayName) ? modId : displayName;
        }

        private static string CallerModId(Assembly caller)
        {
            try
            {
                return caller != null ? caller.GetName().Name : _currentModId;
            }
            catch { return _currentModId; }
        }

        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        public static void RegisterToggle(string id, string label, Func<bool> get, Action<bool> set)
        {
            PutAction(new ActionItem
            {
                ModId = CallerModId(Assembly.GetCallingAssembly()),
                Id = id,
                Label = label,
                Get = get,
                Set = set
            });
            Log("[api] toggle " + _currentModId + "/" + id);
        }

        public static void RegisterToggle(string modId, string id, string label, Func<bool> get, Action<bool> set)
        {
            PutAction(new ActionItem
            {
                ModId = string.IsNullOrEmpty(modId) ? CallerModId(Assembly.GetCallingAssembly()) : modId,
                Id = id,
                Label = label,
                Get = get,
                Set = set
            });
            Log("[api] toggle " + modId + "/" + id);
        }

        public static void RegisterButton(string modId, string id, string label, Action run)
        {
            PutAction(new ActionItem
            {
                ModId = string.IsNullOrEmpty(modId) ? CallerModId(Assembly.GetCallingAssembly()) : modId,
                Id = id,
                Label = label,
                Run = run
            });
            Log("[api] button " + modId + "/" + id);
        }

        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        public static void RegisterButton(string id, string label, Action run)
        {
            PutAction(new ActionItem
            {
                ModId = CallerModId(Assembly.GetCallingAssembly()),
                Id = id,
                Label = label,
                Run = run
            });
            Log("[api] button " + _currentModId + "/" + id);
        }

        public static void ClearActions()
        {
            _actions.Clear();
            _shortcuts.Clear();
            _modNames.Clear();
        }

        /// <summary>mod 列表：mods 目录下的 DLL + 已注册动作的 mod。</summary>
        public static List<string> GetModIds()
        {
            List<string> ids = new List<string>();
            try
            {
                if (Directory.Exists(ModsDirectory))
                {
                    foreach (string dir in Directory.GetDirectories(ModsDirectory))
                    {
                        if (Directory.GetFiles(dir, "*.dll").Length == 0) continue;
                        string id = Path.GetFileName(dir);
                        if (!ids.Contains(id)) ids.Add(id);
                    }
                    foreach (string file in Directory.GetFiles(ModsDirectory, "*.dll"))
                    {
                        string id = Path.GetFileNameWithoutExtension(file);
                        if (!ids.Contains(id)) ids.Add(id);
                    }
                }
            }
            catch { }

            for (int i = 0; i < _actions.Count; i++)
            {
                string id = _actions[i].ModId;
                if (!string.IsNullOrEmpty(id) && !ids.Contains(id)) ids.Add(id);
            }

            ids.Sort(StringComparer.OrdinalIgnoreCase);
            return ids;
        }

        public static string GetModName(string modId)
        {
            string name;
            return _modNames.TryGetValue(modId, out name) ? name : modId;
        }

        public static List<ActionItem> GetActions(string modId)
        {
            List<ActionItem> result = new List<ActionItem>();
            for (int i = 0; i < _actions.Count; i++)
            {
                if (string.Equals(_actions[i].ModId, modId, StringComparison.OrdinalIgnoreCase))
                    result.Add(_actions[i]);
            }
            return result;
        }

        public static string LogsDirectory { get { return Path.Combine(Root, "logs"); } }
        public static string DataDirectory { get { return Path.Combine(Root, "data"); } }

        /// <summary>已注册的动作项（每个 mod 的详情页内容）。</summary>
        public static List<ActionItem> GetActionsOf(string modId) { return GetActions(modId); }

        /// <summary>已注册的「功能」页快捷项。</summary>
        public static List<ShortcutItem> Shortcuts { get { return _shortcuts; } }

        public static List<ShortcutItem> GetShortcuts(string modId)
        {
            List<ShortcutItem> result = new List<ShortcutItem>();
            for (int i = 0; i < _shortcuts.Count; i++)
            {
                if (string.Equals(_shortcuts[i].ModId, modId, StringComparison.OrdinalIgnoreCase))
                    result.Add(_shortcuts[i]);
            }
            return result;
        }

        private static void PutShortcut(ShortcutItem item)
        {
            for (int i = 0; i < _shortcuts.Count; i++)
                if (string.Equals(_shortcuts[i].ModId, item.ModId, StringComparison.OrdinalIgnoreCase) && _shortcuts[i].Id == item.Id)
                { _shortcuts[i] = item; return; }
            _shortcuts.Add(item);
        }

        /// <summary>
        /// 往「功能」页放一条快捷开关。读取当前状态用于着色，[run] 负责真正切换。
        /// </summary>
        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        public static void RegisterShortcut(string id, string label, Func<bool> get, Action run, string detail = null)
        {
            RegisterShortcut(null, id, label, get, run, detail);
        }

        public static void RegisterShortcut(string modId, string id, string label, Func<bool> get, Action run, string detail = null)
        {
            PutShortcut(new ShortcutItem
            {
                ModId = string.IsNullOrEmpty(modId) ? CallerModId(Assembly.GetCallingAssembly()) : modId,
                Id = id,
                Label = label,
                Detail = detail,
                Get = get,
                Run = run
            });
            Log("[api] shortcut " + modId + "/" + id);
        }

        /// <summary>往「功能」页放一条普通按钮（非开关）。</summary>
        [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
        public static void RegisterShortcutButton(string id, string label, Action run, string detail = null)
        {
            RegisterShortcutButton(null, id, label, run, detail);
        }

        public static void RegisterShortcutButton(string modId, string id, string label, Action run, string detail = null)
        {
            PutShortcut(new ShortcutItem
            {
                ModId = string.IsNullOrEmpty(modId) ? CallerModId(Assembly.GetCallingAssembly()) : modId,
                Id = id,
                Label = label,
                Detail = detail,
                Run = run
            });
            Log("[api] shortcut button " + modId + "/" + id);
        }

        public static string GetLoaderPath(params string[] subPaths)
        {
            string result = Root;
            if (subPaths != null) for (int i = 0; i < subPaths.Length; i++) result = Path.Combine(result, subPaths[i]);
            return result;
        }

        public static string GetModDirectory(string modId) { return Path.Combine(ModsDirectory, modId); }
        public static string GetModDataDirectory(string modId) { return Path.Combine(DataDirectory, modId); }
        public static string GetModConfigPath(string modId) { return GetConfigPath(modId); }
        public static string GetConfigPath(string fileNameOrModId)
        {
            string name = fileNameOrModId.EndsWith(".json", StringComparison.OrdinalIgnoreCase) ? fileNameOrModId : fileNameOrModId + ".json";
            return Path.Combine(ConfigDirectory, name);
        }

        /// <summary>让 CesiumLoader.SDK 的 SdkPath / ModConfig 也指向 Android 目录。</summary>
        public static void ApplySdkEnvironment()
        {
            Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", Root);
            Environment.SetEnvironmentVariable("CESIUM_MODS_DIR", ModsDirectory);
            Environment.SetEnvironmentVariable("CESIUM_LOG_DIR", LogsDirectory);
            Environment.SetEnvironmentVariable("CESIUM_SDK_DIR", Root);
            Environment.SetEnvironmentVariable("CESIUM_GAME_DIR", Root);
            Environment.SetEnvironmentVariable("CESIUM_AUDIO_DIR", Path.Combine(Root, "audio"));
        }

        public static void Log(string message)
        {
            try
            {
                Directory.CreateDirectory(Root);
                File.AppendAllText(LogPath, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " " + message + "\n");
            }
            catch { }
        }

        public static void LogError(string message)
        {
            Log("[error] " + message);
        }

        public static string ReadText(string relativePath)
        {
            try
            {
                string path = Path.Combine(Root, relativePath);
                return File.Exists(path) ? File.ReadAllText(path) : null;
            }
            catch (Exception e)
            {
                LogError("ReadText " + relativePath + ": " + e.Message);
                return null;
            }
        }

        public static void WriteText(string relativePath, string contents)
        {
            try
            {
                string path = Path.Combine(Root, relativePath);
                string dir = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                File.WriteAllText(path, contents);
            }
            catch (Exception e)
            {
                LogError("WriteText " + relativePath + ": " + e.Message);
            }
        }

        public static string EnsureConfig(string name, string defaultJson)
        {
            string relative = Path.Combine("configs", name);
            string existing = ReadText(relative);
            if (existing != null) return existing;
            WriteText(relative, defaultJson);
            return defaultJson;
        }
    }
}
