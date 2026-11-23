using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;

namespace CesiumLoader.Android.Bootstrap
{
    /// <summary>
    /// 安卓加载器的唯一托管入口。
    ///
    /// 两个投递方式都只调这一个方法：
    /// <list type="bullet">
    /// <item>数据版：注入进 AstralParty.Runtime.dll 的 IL（或它的调用方）直接调用 <see cref="Load"/>；</item>
    /// <item>重打包版：libcesium.so 等到 il2cpp 就绪后，用反射调用同一个方法。</item>
    /// </list>
    ///
    /// 因此这里不能依赖调用方上下文：路径、目录、依赖解析、日志全部自给自足。
    /// 同时必须避开 HybridCLR/安卓上不可用的 BCL：不要用 FileStream、FileInfo/DirectoryInfo、
    /// StreamReader/Writer、Flush(bool)，也不要依赖 Assembly.Location。
    /// </summary>
    public static class Boot
    {
        public const string DefaultRoot = "/sdcard/CesiumLoader";

        private const string ModsFolder = "mods";
        private const string ConfigsFolder = "configs";
        private const string SdkFolder = "sdk";
        private const string SdkDll = "CesiumLoader.SDK.dll";
        private const string ApiDll = "CesiumAndroidApi.dll";
        private const string AndroidApiType = "CesiumAndroid.AndroidApi";

        private static readonly string[] Directories =
        {
            "mods", "disabled_mods", "logs", "data",
            "skins", "audio", "audio-schemes", "sdk", "bootstrap", "speed", "window",
        };

        private static readonly object Gate = new object();
        private static bool _entered;
        private static string _root;
        private static string _mods;

        /// <summary>本次启动的时间戳，用来把每次启动的日志分成独立文件。</summary>
        private static string _stamp;
        private static string _logsDir;
        private static string _bootstrapLog;

        /// <summary>每类日志保留的份数；多了就按文件名里的时间戳删最旧的。</summary>
        private const int KeepLogFiles = 20;

        /// <summary>是否已经跑过（重复调用直接返回，不重复加载模组）。</summary>
        public static bool IsLoaded
        {
            get { lock (Gate) { return _entered; } }
        }

        public static string Root
        {
            get { return _root ?? ResolveRoot(); }
        }

        /// <summary>入口：幂等，异常只落盘不外抛（异常逃进游戏链会把整局卡死）。</summary>
        public static void Load()
        {
            LoadCore(null);
        }

        /// <summary>Cache installs explicitly pass the game package's own loader directory.</summary>
        public static void LoadForGame(string root)
        {
            LoadCore(root);
        }

        private static void LoadCore(string root)
        {
            lock (Gate)
            {
                if (_entered) return;
                _entered = true;
            }

            try
            {
                string resolved = !string.IsNullOrEmpty(root) && TryPrepare(root) ? root : ResolveRoot();
                Run(resolved);
            }
            catch (Exception error)
            {
                string dir = Path.Combine(_root ?? root ?? ResolveRoot(), "logs");
                string stamp = _stamp ?? DateTime.Now.ToString("yyyyMMdd-HHmmss");
                TryAppend(Path.Combine(dir, "bootstrap-error-" + stamp + ".txt"), Describe(error));
            }
        }

        private static void Run(string root)
        {
            _root = root;
            _mods = Path.Combine(_root, ModsFolder);
            _logsDir = Path.Combine(_root, "logs");
            _stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
            _bootstrapLog = Path.Combine(_logsDir, "bootstrap-" + _stamp + ".log");

            EnsureDirectories();
            RetireLegacyConfigs();

            Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", _root);
            Environment.SetEnvironmentVariable("CESIUM_MODS_DIR", _mods);
            Environment.SetEnvironmentVariable("CESIUM_LOG_DIR", _logsDir);

            // SDK 只拿到目录的话只能写死 activity-mod.log / mod-errors.log，几次启动会混进同一个文件。
            // 桌面版是加载器按「每次启动一份」把完整路径传进去，这里照同样口径给。
            Environment.SetEnvironmentVariable("CESIUM_ACTIVITY_LOG_FILE", Path.Combine(_logsDir, "activity-mod-" + _stamp + ".log"));
            Environment.SetEnvironmentVariable("CESIUM_ERROR_LOG_FILE", Path.Combine(_logsDir, "mod-errors-" + _stamp + ".log"));
            PruneLogs();
            Environment.SetEnvironmentVariable("CESIUM_SDK_DIR", Path.Combine(_root, SdkFolder));
            Environment.SetEnvironmentVariable("CESIUM_GAME_DIR", _root);
            Environment.SetEnvironmentVariable("CESIUM_AUDIO_DIR", Path.Combine(_root, "audio"));

            AppDomain.CurrentDomain.AssemblyResolve += ResolveAssembly;

            var log = new StringBuilder();
            log.AppendLine("bootstrap " + DateTime.UtcNow.ToString("o"));
            log.AppendLine("root: " + _root);

            LoadOptionalAssembly(log, Path.Combine(_root, ApiDll), "api");

            // SDK 统一放在 sdk/ 下（与发行版一致）；根目录那份是旧布局，保留兼容。
            string sdkPath = Path.Combine(_root, SdkFolder, SdkDll);
            if (!File.Exists(sdkPath)) sdkPath = Path.Combine(_root, SdkDll);
            LoadOptionalAssembly(log, sdkPath, "sdk");

            List<string> files = CollectModDlls(_mods);
            files.Sort(StringComparer.OrdinalIgnoreCase);
            log.AppendLine("mods found: " + files.Count);

            int ok = 0;
            int failed = 0;
            for (int index = 0; index < files.Count; index++)
            {
                string file = files[index];
                try
                {
                    Assembly assembly = Assembly.Load(File.ReadAllBytes(file));
                    log.AppendLine("loaded: " + assembly.GetName().Name);

                    Type[] types = GetTypes(assembly);
                    for (int t = 0; t < types.Length; t++)
                    {
                        Type type = types[t];
                        MethodInfo main = type.GetMethod(
                            "Main",
                            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
                        if (main == null) continue;
                        if (main.GetParameters().Length != 0) continue;

                        TagCurrentMod(type, file);
                        try
                        {
                            main.Invoke(null, null);
                            ok++;
                            log.AppendLine("ok: " + type.FullName);
                        }
                        catch (Exception error)
                        {
                            failed++;
                            log.AppendLine("invoke error: " + type.FullName + " -> " + Describe(error));
                        }
                    }
                }
                catch (Exception error)
                {
                    failed++;
                    log.AppendLine("load error: " + file + " -> " + Describe(error));
                }
            }

            log.AppendLine("done: ok=" + ok + " failed=" + failed);
            StartConfigPoller(log);
            File.WriteAllText(_bootstrapLog, log.ToString());
        }

        /// <summary>
        /// 配置热重载的驱动。
        ///
        /// SDK 里有 `ModConfig.PollAllChanged()`（比对文件时间戳、重新读、触发 Changed 事件），
        /// 但它自己不带定时器 —— 桌面版由原生加载器线程驱动，安卓这边没人调，等于没有热重载。
        /// 这里起一个后台线程每秒半问一次，App 写完 `mods/&lt;ModId&gt;/config.json`（停用模组写在
        /// `disabled_mods/&lt;ModId&gt;/config.json`）就能立刻生效。
        ///
        /// 失败一律吞掉：配置写一半是正常情况，下一轮会再读到完整内容。
        /// </summary>
        private static void StartConfigPoller(StringBuilder log)
        {
            try
            {
                var thread = new Thread(ConfigPollerLoop);
                thread.IsBackground = true;
                thread.Start();
                log.AppendLine("config poller: started");
            }
            catch (Exception error)
            {
                log.AppendLine("config poller error: " + Describe(error));
            }
        }

        private const int ConfigPollIntervalMs = 1500;

        private static void ConfigPollerLoop()
        {
            MethodInfo poll = null;
            while (true)
            {
                try
                {
                    Thread.Sleep(ConfigPollIntervalMs);
                    if (poll == null)
                    {
                        Assembly[] assemblies = AppDomain.CurrentDomain.GetAssemblies();
                        for (int i = 0; i < assemblies.Length; i++)
                        {
                            Assembly assembly = assemblies[i];
                            if (assembly.GetName().Name != "CesiumLoader.SDK") continue;
                            Type type = FindType(assembly, "CesiumLoader.SDK.Configuration.ModConfig");
                            if (type != null) poll = type.GetMethod("PollAllChanged", BindingFlags.Public | BindingFlags.Static);
                            break;
                        }
                        if (poll == null) continue;
                    }
                    poll.Invoke(null, null);
                }
                catch
                {
                    // 下一轮重新找一次方法：SDK 可能还没加载完，或配置正被改到一半。
                    poll = null;
                    try { Thread.Sleep(3000); } catch { }
                }
            }
        }

        private static Type FindType(Assembly assembly, string fullName)
        {
            Type[] types = GetTypes(assembly);
            for (int i = 0; i < types.Length; i++)
            {
                if (types[i].FullName == fullName) return types[i];
            }
            return null;
        }

        private static string ResolveRoot()
        {
            string overrideRoot = Environment.GetEnvironmentVariable("CESIUM_LOADER_DIR");
            if (!string.IsNullOrEmpty(overrideRoot))
            {
                string trimmed = overrideRoot.TrimEnd('/');
                if (TryPrepare(trimmed)) return trimmed;
            }

            string persistent = TryPersistentDataRoot();
            if (!string.IsNullOrEmpty(persistent) && TryPrepare(persistent)) return persistent;

            for (int i = 0; i < RootCandidates.Length; i++)
            {
                if (TryPrepare(RootCandidates[i])) return RootCandidates[i];
            }

            // 都不行也返回首选路径，让后续日志把失败原因写清楚。
            return !string.IsNullOrEmpty(persistent) ? persistent : RootCandidates[0];
        }

        private static string TryPersistentDataRoot()
        {
            try
            {
                Assembly[] assemblies = AppDomain.CurrentDomain.GetAssemblies();
                for (int i = 0; i < assemblies.Length; i++)
                {
                    Type app = assemblies[i].GetType("UnityEngine.Application", false);
                    if (app == null) continue;
                    PropertyInfo prop = app.GetProperty("persistentDataPath", BindingFlags.Public | BindingFlags.Static);
                    if (prop == null) continue;
                    string path = prop.GetValue(null, null) as string;
                    if (!string.IsNullOrEmpty(path)) return Path.Combine(path.TrimEnd('/'), "CesiumLoader");
                }
            }
            catch
            {
            }
            return null;
        }

        /// <summary>
        /// 选中一个真的能写的根目录。
        ///
        /// 数据版（不改 APK）里游戏没有 MANAGE_EXTERNAL_STORAGE，Android 11+ 写不了
        /// /sdcard/CesiumLoader，只能落到自己的 Android/data 目录；重打包版可以申请
        /// 所有文件访问，所以 /sdcard/CesiumLoader 仍然优先。
        /// </summary>
        private static bool TryPrepare(string root)
        {
            try
            {
                Directory.CreateDirectory(root);
                string probe = Path.Combine(root, ".write-probe");
                File.WriteAllText(probe, "ok");
                File.Delete(probe);
                return true;
            }
            catch
            {
                return false;
            }
        }

        private static readonly string[] RootCandidates =
        {
            "/sdcard/CesiumLoader",
            "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader",
            "/sdcard/Android/data/com.feimo.astralparty.cesium/files/CesiumLoader",
        };

        private static void EnsureDirectories()
        {
            for (int i = 0; i < Directories.Length; i++)
            {
                string path = Path.Combine(_root, Directories[i]);
                if (Directory.Exists(path)) continue;
                try { Directory.CreateDirectory(path); }
                catch (Exception error) { TryAppend(Path.Combine(_root, "logs/bootstrap_error.txt"), "mkdir " + path + ": " + Describe(error)); }
            }
        }

        /// <summary>
        /// 4.0.0 起 mod 配置只有一处：<c>mods/&lt;ModId&gt;/config.json</c>（停用模组写在
        /// <c>disabled_mods/&lt;ModId&gt;/config.json</c>）。旧版集中目录 <c>configs/</c> 里的改动
        /// 不会再被任何 mod 读取，留着只会让人以为改了会生效 —— 启动时整目录改名
        /// <c>configs.bak</c>（保留内容便于找回，不删除用户数据）。
        /// </summary>
        private static void RetireLegacyConfigs()
        {
            try
            {
                string legacy = Path.Combine(_root, ConfigsFolder);
                if (!Directory.Exists(legacy)) return;

                string retired = Path.Combine(_root, ConfigsFolder + ".bak");
                if (Directory.Exists(retired)) return;   // 已经退休过一次：不覆盖备份，也不删旧目录

                Directory.Move(legacy, retired);
            }
            catch (Exception error)
            {
                TryAppend(Path.Combine(_root, "logs/bootstrap_error.txt"), "retire configs: " + Describe(error));
            }
        }

        private static void LoadOptionalAssembly(StringBuilder log, string path, string label)
        {
            if (!File.Exists(path))
            {
                log.AppendLine(label + " missing: " + path);
                return;
            }

            try
            {
                Assembly assembly = Assembly.Load(File.ReadAllBytes(path));
                log.AppendLine(label + " loaded: " + assembly.FullName);
            }
            catch (Exception error)
            {
                log.AppendLine(label + " error: " + Describe(error));
            }
        }

        /// <summary>
        /// 日志按「每次启动一份」累积，这里按前缀各保留最近 [KeepLogFiles] 份。
        /// 文件名里带 yyyyMMdd-HHmmss，按序数排序即按时间；不用 FileInfo（热更侧会被裁剪）。
        /// </summary>
        private static void PruneLogs()
        {
            Prune("activity-mod-*.log");
            Prune("mod-errors-*.log");
            Prune("bootstrap-*.log");
            Prune("bootstrap-error-*.txt");
        }

        private static void Prune(string pattern)
        {
            try
            {
                string[] files = Directory.GetFiles(_logsDir, pattern);
                if (files.Length <= KeepLogFiles) return;

                Array.Sort(files, StringComparer.Ordinal);
                int extra = files.Length - KeepLogFiles;
                for (int i = 0; i < extra; i++)
                {
                    try { File.Delete(files[i]); } catch { }
                }
            }
            catch { }
        }

        private static List<string> CollectModDlls(string mods)
        {
            var files = new List<string>();
            if (!Directory.Exists(mods)) return files;

            files.AddRange(Directory.GetFiles(mods, "*.dll"));
            string[] dirs = Directory.GetDirectories(mods);
            for (int i = 0; i < dirs.Length; i++)
            {
                files.AddRange(Directory.GetFiles(dirs[i], "*.dll"));
            }
            return files;
        }

        private static Type[] GetTypes(Assembly assembly)
        {
            try
            {
                return assembly.GetTypes();
            }
            catch
            {
                // HybridCLR 会裁剪部分反射类型（例如 ReflectionTypeLoadException.Types 就会
                // 抛 MissingMethodException），所以这里只做最保守的处理：拿不到就当空。
                return new Type[0];
            }
        }

        /// <summary>告诉 CesiumAndroidApi 当前模组 ID，悬浮窗才能把开关归到正确的模组下。</summary>
        private static void TagCurrentMod(Type type, string dllPath)
        {
            try
            {
                string modId = ModIdOf(dllPath);
                Assembly[] assemblies = AppDomain.CurrentDomain.GetAssemblies();
                for (int i = 0; i < assemblies.Length; i++)
                {
                    Assembly assembly = assemblies[i];
                    if (assembly.GetName().Name != "CesiumAndroidApi") continue;

                    Type api = assembly.GetType(AndroidApiType);
                    if (api == null) continue;
                    MethodInfo setter = api.GetMethod("SetCurrentMod");
                    if (setter == null) continue;

                    setter.Invoke(null, new object[] { modId });
                    return;
                }
            }
            catch
            {
                // 悬浮窗归属失败不影响模组本身。
            }
        }

        private static string ModIdOf(string dllPath)
        {
            string folder = Path.GetDirectoryName(dllPath);
            if (!string.IsNullOrEmpty(folder))
            {
                string folderName = Path.GetFileName(folder);
                if (!string.IsNullOrEmpty(folderName) && folderName != ModsFolder) return folderName;
            }
            return Path.GetFileNameWithoutExtension(dllPath);
        }

        private static Assembly ResolveAssembly(object sender, ResolveEventArgs args)
        {
            try
            {
                string name = new AssemblyName(args.Name).Name;
                if (string.IsNullOrEmpty(name)) return null;

                string[] candidates =
                {
                    Path.Combine(_root ?? DefaultRoot, name + ".dll"),
                    Path.Combine(_root ?? DefaultRoot, SdkFolder, name + ".dll"),
                    Path.Combine(_mods ?? Path.Combine(DefaultRoot, ModsFolder), name + ".dll"),
                    Path.Combine(_mods ?? Path.Combine(DefaultRoot, ModsFolder), name, name + ".dll"),
                };

                for (int i = 0; i < candidates.Length; i++)
                {
                    if (File.Exists(candidates[i])) return Assembly.Load(File.ReadAllBytes(candidates[i]));
                }
            }
            catch
            {
            }
            return null;
        }

        private static void TryAppend(string path, string message)
        {
            try
            {
                string parent = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(parent) && !Directory.Exists(parent)) Directory.CreateDirectory(parent);
                File.AppendAllText(path, DateTime.UtcNow.ToString("o") + " " + message + "\n");
            }
            catch
            {
            }
        }

        private static string Describe(Exception error)
        {
            var text = new StringBuilder();
            int depth = 0;
            Exception current = error;
            while (current != null && depth < 6)
            {
                text.Append(current.GetType().Name).Append(": ").Append(current.Message).Append('\n');
                current = current.InnerException;
                depth++;
            }
            if (error.StackTrace != null) text.Append(error.StackTrace);
            return text.ToString();
        }
    }
}
