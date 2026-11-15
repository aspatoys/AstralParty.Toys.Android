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
    /// Android 端开发者接口。mod 引用 CesiumAndroidApi.dll 后即可使用。
    /// </summary>
    public static class AndroidApi
    {
        // Adapted for the Shizuku/root installer: the official game's own writable directory.
        public static string Root { get { return Environment.GetEnvironmentVariable("CESIUM_LOADER_DIR") ?? Path.Combine(Application.persistentDataPath, "CesiumLoader"); } }

        private static readonly List<ActionItem> _actions = new List<ActionItem>();
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
