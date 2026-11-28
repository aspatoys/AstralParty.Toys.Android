using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;

namespace CesiumLoader.Mobile.Bootstrap
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
    /// StreamReader/Writer、Flush(bool)，不要依赖 Assembly.Location，也不要调
    /// <c>Assembly.GetType(string)</c> / <c>Assembly.GetType(string, bool)</c> —— 后者在
    /// HybridCLR 里被裁剪，一调用就是编译期 MissingMethodException（try/catch 拦不住，
    /// 会冲出 &lt;Module&gt;.cctor 让热更程序集整个加载失败）。要找类型一律走
    /// <see cref="FindType"/>（GetTypes 扫描）。
    /// </summary>
    public static class Boot
    {
        public const string DefaultRoot = "/sdcard/CesiumLoader";

        private const string ModsFolder = "mods";
        private const string ConfigsFolder = "configs";
        private const string SdkFolder = "sdk";
        private const string SdkDll = "CesiumLoader.SDK.dll";
        private const string ApiDll = "CesiumLoader.Mobile.API.dll";
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

            // 调用方给定了根目录就用它：那是安装器实际投放 SDK / 模组的位置，换根只会让
            // 模组目录指向别处。目录写不进去（例如由 Shizuku 以 shell 身份创建、游戏只有
            // r-x）也要继续 —— 模组还能读，写日志失败由 TryAppend 自己吞掉。
            string resolved = root;
            if (string.IsNullOrEmpty(resolved))
            {
                resolved = ResolveRoot();
            }
            else if (!TryPrepare(resolved))
            {
                TryAppend(Path.Combine(resolved, "logs", "bootstrap-error.txt"), "root not writable: " + resolved);
            }

            try
            {
                Run(resolved);
            }
            catch (Exception error)
            {
                // 这里不能再调 ResolveRoot：它曾经在异常处理里再抛一次，把异常带出
                // <Module>.cctor，整个热更程序集直接加载失败。
                string dir = Path.Combine(_root ?? resolved ?? DefaultRoot, "logs");
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

            var log = new StringBuilder();
            DateTime launch = DateTime.UtcNow;

            // 先把运行环境写清楚：玩家报「模组没加载」时，第一眼看的就是这几行。
            log.AppendLine("bootstrap " + DateTime.UtcNow.ToString("o"));
            log.AppendLine("root: " + _root);
            DescribeEnvironment(log);

            EnsureDirectories(log);
            RetireLegacyConfigs(log);

            Environment.SetEnvironmentVariable("CESIUM_LOADER_DIR", _root);
            Environment.SetEnvironmentVariable("CESIUM_MODS_DIR", _mods);
            Environment.SetEnvironmentVariable("CESIUM_LOG_DIR", _logsDir);

            // SDK 只拿到目录的话只能写死 activity-mod.log / mod-errors.log，几次启动会混进同一个文件。
            // 桌面版是加载器按「每次启动一份」把完整路径传进去，这里照同样口径给。
            Environment.SetEnvironmentVariable("CESIUM_ACTIVITY_LOG_FILE", Path.Combine(_logsDir, "activity-mod-" + _stamp + ".log"));
            Environment.SetEnvironmentVariable("CESIUM_ERROR_LOG_FILE", Path.Combine(_logsDir, "mod-errors-" + _stamp + ".log"));
            PruneLogs(log);
            Environment.SetEnvironmentVariable("CESIUM_SDK_DIR", Path.Combine(_root, SdkFolder));
            Environment.SetEnvironmentVariable("CESIUM_GAME_DIR", _root);
            Environment.SetEnvironmentVariable("CESIUM_AUDIO_DIR", Path.Combine(_root, "audio"));

            AppDomain.CurrentDomain.AssemblyResolve += ResolveAssembly;
            log.AppendLine("assembly resolve: hooked");

            LoadOptionalAssembly(log, Path.Combine(_root, ApiDll), "api");

            // SDK 统一放在 sdk/ 下（与发行版一致）；根目录那份是旧布局，保留兼容。
            string sdkPath = Path.Combine(_root, SdkFolder, SdkDll);
            if (!File.Exists(sdkPath)) sdkPath = Path.Combine(_root, SdkDll);
            LoadOptionalAssembly(log, sdkPath, "sdk");

            log.AppendLine("mods dir: " + _mods + " (" + (Directory.Exists(_mods) ? "exists" : "missing") + ")");
            DescribeDisabledMods(log);

            List<string> files = CollectModDlls(_mods);
            files.Sort(StringComparer.OrdinalIgnoreCase);
            log.AppendLine("mods found: " + files.Count);
            // 上面这些是所有「装错了 / 没装」问题要看的；先落一次盘，后面就算崩了也留着。
            Flush(log);

            int ok = 0;
            int failed = 0;
            int noEntry = 0;
            for (int index = 0; index < files.Count; index++)
            {
                string file = files[index];
                string label = "[" + (index + 1) + "/" + files.Count + "] " + Path.GetFileName(file);
                long started = ElapsedMs(launch);
                try
                {
                    byte[] bytes = File.ReadAllBytes(file);
                    Assembly assembly = Assembly.Load(bytes);
                    log.AppendLine("loaded: " + assembly.GetName().Name);
                    log.AppendLine("  file: " + file + " (" + bytes.Length + " B, fp " + ShortFingerprint(bytes) + ")");

                    Type[] types = GetTypes(assembly);
                    int entries = 0;
                    for (int t = 0; t < types.Length; t++)
                    {
                        Type type = types[t];
                        MethodInfo main = type.GetMethod(
                            "Main",
                            BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Static);
                        if (main == null) continue;
                        if (main.GetParameters().Length != 0) continue;

                        entries++;
                        TagCurrentMod(type, file);
                        try
                        {
                            long entryStart = ElapsedMs(launch);
                            main.Invoke(null, null);
                            ok++;
                            log.AppendLine("ok: " + type.FullName);
                            log.AppendLine("  entry " + type.FullName + " returned in "
                                + (ElapsedMs(launch) - entryStart) + " ms");
                        }
                        catch (Exception error)
                        {
                            failed++;
                            log.AppendLine("invoke error: " + type.FullName + " -> " + Describe(error));
                        }
                    }

                    if (entries == 0)
                    {
                        noEntry++;
                        log.AppendLine("  skip: " + label + " 没有无参静态 Main()，已跳过");
                    }
                    else
                    {
                        log.AppendLine("  done: " + label + " 入口 " + entries + " 个，"
                            + (ElapsedMs(launch) - started) + " ms");
                    }
                    // 每个模组一行进度都立即落盘：某个模组把进程带崩时，日志能指到它。
                    Flush(log);
                }
                catch (Exception error)
                {
                    failed++;
                    log.AppendLine("load error: " + file + " -> " + Describe(error));
                    Flush(log);
                }
            }

            log.AppendLine("done: ok=" + ok + " failed=" + failed
                + " (dll " + files.Count + ", 无入口 " + noEntry + ")");
            log.AppendLine("elapsed: " + ElapsedMs(launch) + " ms (加载 SDK 与全部模组)");
            StartConfigPoller(log);
            Flush(log);
        }

        private static long ElapsedMs(DateTime launch)
        {
            try { return (long)(DateTime.UtcNow - launch).TotalMilliseconds; }
            catch { return 0; }
        }

        /// <summary>
        /// 运行环境：报错时第一眼要能看出「哪台机器、什么包、哪个运行时」。
        ///
        /// 只用本文件其它地方已经验证过的 API（File.ReadAllText / DateTime / 程序集名），
        /// 不碰 Environment.MachineName / OSVersion / Is64BitProcess / TimeZoneInfo 这些
        /// 在 HybridCLR 里可能被裁掉的取值器 —— 它们被裁时抛的是编译期
        /// MissingMethodException，SafeGet 的 try/catch 也拦不住。
        /// </summary>
        private static void DescribeEnvironment(StringBuilder log)
        {
            // 本地时间与 UTC 各打一份：不用 DateTimeOffset，HybridCLR 可能裁掉。
            log.AppendLine("time: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss") + " local, "
                + DateTime.UtcNow.ToString("yyyy-MM-dd HH:mm:ss") + " utc");
            log.AppendLine("process: pid " + SafeGet(ProcessId) + ", package " + SafeGet(ProcessDataPackageName));
            log.AppendLine("runtime: clr " + SafeGet(ClrVersion)
                + ", corlib " + SafeGet(CorlibVersion));
            log.AppendLine("kernel: " + SafeGet(KernelRelease));
            log.AppendLine("bootstrap asm: " + SafeGet(BootstrapIdentity));
            log.AppendLine("log file: " + _bootstrapLog);
        }

        private static string ClrVersion()
        {
            return typeof(object).Assembly.GetName().Version.ToString();
        }

        private static string CorlibVersion()
        {
            return typeof(string).Assembly.GetName().Version.ToString();
        }

        /// <summary>
        /// 内核版本。Android 上普通应用读 /proc/version 与 /proc/sys 多半被 SELinux 拒掉
        /// （实测两个都返回 ?），所以这里不比异常直传，明确写「读不到」而不是留个问号。
        /// </summary>
        private static string KernelRelease()
        {
            string[] paths = { "/proc/version", "/proc/sys/kernel/osrelease" };
            for (int i = 0; i < paths.Length; i++)
            {
                try { return File.ReadAllText(paths[i]).Trim(); }
                catch { }
            }
            return "unavailable (/proc blocked by SELinux)";
        }

        private static string BootstrapIdentity()
        {
            return typeof(Boot).Assembly.FullName;
        }

        private static string ProcessId()
        {
            string stat = File.ReadAllText("/proc/self/stat");
            int space = stat.IndexOf(' ');
            return space > 0 ? stat.Substring(0, space) : stat.Trim();
        }

        /// <summary>进程包名：`/proc/self/cmdline` 的第一段，`:service` 之类后缀会去掉。</summary>
        private static string ProcessDataPackageName()
        {
            string cmdline = File.ReadAllText("/proc/self/cmdline");
            int end = cmdline.IndexOf('\0');
            if (end >= 0) cmdline = cmdline.Substring(0, end);
            int colon = cmdline.IndexOf(':');
            if (colon >= 0) cmdline = cmdline.Substring(0, colon);
            return cmdline.Trim();
        }

        private static string SafeGet(Func<string> read)
        {
            try { return read() ?? "?"; } catch { return "?"; }
        }

        /// <summary>
        /// 短指纹：FNV-1a 64 位，16 位十六进制。够区分「装的是哪一版」，又不刷屏。
        ///
        /// 刻意不用 SHA-256：HybridCLR 里 System.Security.Cryptography 可能整块被裁，
        /// 而裁掉的方法是**编译期**抛 MissingMethodException，try/catch 拦不住 —— 日志
        /// 功能不值得冒这个风险。十六进制也手写，避开带格式串的 ToString。
        /// </summary>
        private static string ShortFingerprint(byte[] bytes)
        {
            ulong hash = 14695981039346656037UL;
            for (int i = 0; i < bytes.Length; i++)
            {
                hash ^= bytes[i];
                hash *= 1099511628211UL;
            }

            const string digits = "0123456789abcdef";
            var text = new StringBuilder(16);
            for (int shift = 60; shift >= 0; shift -= 4)
            {
                text.Append(digits[(int)((hash >> shift) & 0xF)]);
            }
            return text.ToString();
        }

        /// <summary>
        /// 把停用模组也列出来：玩家「明明装了却没生效」十有八九是目录在 disabled_mods 里。
        /// 只打目录名，不读配置。
        /// </summary>
        private static void DescribeDisabledMods(StringBuilder log)
        {
            try
            {
                string disabled = Path.Combine(_root, "disabled_mods");
                if (!Directory.Exists(disabled))
                {
                    log.AppendLine("disabled_mods: missing");
                    return;
                }
                string[] dirs = Directory.GetDirectories(disabled);
                if (dirs.Length == 0)
                {
                    log.AppendLine("disabled_mods: empty");
                    return;
                }
                Array.Sort(dirs, StringComparer.OrdinalIgnoreCase);
                log.AppendLine("disabled_mods: " + dirs.Length + " -> " + string.Join(", ", Names(dirs)));
            }
            catch (Exception error)
            {
                log.AppendLine("disabled_mods error: " + Describe(error));
            }
        }

        private static string[] Names(string[] paths)
        {
            var names = new string[paths.Length];
            for (int i = 0; i < paths.Length; i++) names[i] = Path.GetFileName(paths[i]);
            return names;
        }

        /// <summary>
        /// 写日志。先写临时文件再覆盖：加载器目录可能被游戏自己读着，
        /// 直接覆盖失败时至少不会留下半截文件；失败时退回直接写。
        /// </summary>
        private static void Flush(StringBuilder log)
        {
            string text = log.ToString();
            string temp = _bootstrapLog + ".tmp";
            try
            {
                File.WriteAllText(temp, text);
                if (File.Exists(_bootstrapLog)) File.Delete(_bootstrapLog);
                File.Move(temp, _bootstrapLog);
                return;
            }
            catch
            {
                try { File.Delete(temp); } catch { }
            }

            try { File.WriteAllText(_bootstrapLog, text); }
            catch { }
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
                log.AppendLine("config poller: started (" + ConfigPollIntervalMs + " ms)");
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
                if (types[i] != null && types[i].FullName == fullName) return types[i];
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

            string persistent = TryProcessDataRoot();
            if (!string.IsNullOrEmpty(persistent) && TryPrepare(persistent)) return persistent;

            for (int i = 0; i < RootCandidates.Length; i++)
            {
                if (TryPrepare(RootCandidates[i])) return RootCandidates[i];
            }

            // 都不行也返回首选路径，让后续日志把失败原因写清楚。
            return !string.IsNullOrEmpty(persistent) ? persistent : RootCandidates[0];
        }

        /// <summary>
        /// 游戏自己的 Android/data 目录，即 Unity 的 `Application.persistentDataPath`。
        ///
        /// 不用 `Assembly.GetType("UnityEngine.Application", throwOnError: false)` 去读：
        /// HybridCLR 裁掉了 `Assembly.GetType(string, bool)`，这个方法一被 JIT 就抛
        /// `MissingMethodException: MethodNotFind System.Reflection.Assembly::GetType`，
        /// 而它是「方法编译期」抛出的，方法的 try/catch 和调用方的 try/catch 都拦不住 ——
        /// 只要走到 ResolveRoot（例如加载器目录只读、写探针失败），异常就会一路冲出
        /// `<Module>.cctor`，把整次热更程序集加载顶掉。
        ///
        /// 包名在 `/proc/self/cmdline` 的第一段（形如 `com.feimo.astralparty`，服务进程带
        /// `:service` 后缀），读它不需要任何 Unity 类型，也不会被裁剪。
        /// </summary>
        private static string TryProcessDataRoot()
        {
            try
            {
                string cmdline = File.ReadAllText("/proc/self/cmdline");
                int end = cmdline.IndexOf('\0');
                if (end >= 0) cmdline = cmdline.Substring(0, end);
                int colon = cmdline.IndexOf(':');
                if (colon >= 0) cmdline = cmdline.Substring(0, colon);
                cmdline = cmdline.Trim();
                if (cmdline.Length < 3 || cmdline.IndexOf('.') <= 0 || cmdline.IndexOf('/') >= 0) return null;
                return "/sdcard/Android/data/" + cmdline + "/files/CesiumLoader";
            }
            catch
            {
                return null;
            }
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
                // 目录已经存在但游戏写不进去（安装器用 Shizuku 以 shell 身份投放时的常见状态）：
                // 不算根不可用，模组照样能读，只是日志写不下。
                try { return Directory.Exists(root); }
                catch { return false; }
            }
        }

        private static readonly string[] RootCandidates =
        {
            "/sdcard/CesiumLoader",
            "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader",
            "/sdcard/Android/data/com.feimo.astralparty.cesium/files/CesiumLoader",
        };

        private static void EnsureDirectories(StringBuilder log)
        {
            for (int i = 0; i < Directories.Length; i++)
            {
                string path = Path.Combine(_root, Directories[i]);
                if (Directory.Exists(path)) continue;
                try { Directory.CreateDirectory(path); }
                catch (Exception error)
                {
                    log.AppendLine("mkdir error: " + path + " -> " + Describe(error));
                    TryAppend(Path.Combine(_root, "logs/bootstrap_error.txt"), "mkdir " + path + ": " + Describe(error));
                }
            }
        }

        /// <summary>
        /// 4.0.0 起 mod 配置只有一处：<c>mods/&lt;ModId&gt;/config.json</c>（停用模组写在
        /// <c>disabled_mods/&lt;ModId&gt;/config.json</c>）。旧版集中目录 <c>configs/</c> 里的改动
        /// 不会再被任何 mod 读取，留着只会让人以为改了会生效 —— 启动时整目录改名
        /// <c>configs.bak</c>（保留内容便于找回，不删除用户数据）。
        /// </summary>
        private static void RetireLegacyConfigs(StringBuilder log)
        {
            try
            {
                string legacy = Path.Combine(_root, ConfigsFolder);
                if (!Directory.Exists(legacy)) return;

                string retired = Path.Combine(_root, ConfigsFolder + ".bak");
                if (Directory.Exists(retired)) return;   // 已经退休过一次：不覆盖备份，也不删旧目录

                Directory.Move(legacy, retired);
                log.AppendLine("configs retired: " + ConfigsFolder + " -> " + ConfigsFolder + ".bak");
            }
            catch (Exception error)
            {
                log.AppendLine("retire configs error: " + Describe(error));
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
                byte[] bytes = File.ReadAllBytes(path);
                Assembly assembly = Assembly.Load(bytes);
                log.AppendLine(label + " loaded: " + assembly.FullName);
                log.AppendLine("  " + label + " file: " + path + " (" + bytes.Length + " B, fp " + ShortFingerprint(bytes) + ")");
            }
            catch (Exception error)
            {
                log.AppendLine(label + " error: " + path + " -> " + Describe(error));
            }
        }

        /// <summary>
        /// 日志按「每次启动一份」累积，这里按前缀各保留最近 [KeepLogFiles] 份。
        /// 文件名里带 yyyyMMdd-HHmmss，按序数排序即按时间；不用 FileInfo（热更侧会被裁剪）。
        /// </summary>
        private static void PruneLogs(StringBuilder log)
        {
            Prune("activity-mod-*.log", log);
            Prune("mod-errors-*.log", log);
            Prune("bootstrap-*.log", log);
            Prune("bootstrap-error-*.txt", log);
        }

        private static void Prune(string pattern, StringBuilder log)
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
                log.AppendLine("prune: " + pattern + " removed " + extra + " (kept " + KeepLogFiles + ")");
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

        private static Type _apiType;
        private static bool _apiTypeScanned;

        /// <summary>告诉 CesiumLoader.Mobile.API 当前模组 ID，悬浮窗才能把开关归到正确的模组下。</summary>
        private static void TagCurrentMod(Type type, string dllPath)
        {
            try
            {
                Type api = ApiType();
                if (api == null) return;
                MethodInfo setter = api.GetMethod("SetCurrentMod");
                if (setter == null) return;
                setter.Invoke(null, new object[] { ModIdOf(dllPath) });
            }
            catch
            {
                // 悬浮窗归属失败不影响模组本身。
            }
        }

        /// <summary>
        /// 找 CesiumLoader.Mobile.API 里的 AndroidApi 类型，找到后缓存：每个模组都扫一遍
        /// 全部程序集的 GetTypes() 会明显拖慢启动。
        ///
        /// 不用 Assembly.GetType(string)：它在 HybridCLR 上可能被裁剪，抛不可拦截的
        /// MissingMethodException；旧版程序集名（CesiumAndroidApi）也认，老载荷里
        /// 程序集名还没改成 CesiumLoader.Mobile.API。
        /// </summary>
        private static Type ApiType()
        {
            if (_apiTypeScanned) return _apiType;
            _apiTypeScanned = true;
            try
            {
                Assembly[] assemblies = AppDomain.CurrentDomain.GetAssemblies();
                for (int i = 0; i < assemblies.Length; i++)
                {
                    Assembly assembly = assemblies[i];
                    string name = assembly.GetName().Name;
                    if (name != "CesiumLoader.Mobile.API" && name != "CesiumAndroidApi") continue;
                    Type api = FindType(assembly, AndroidApiType);
                    if (api == null) continue;
                    _apiType = api;
                    return api;
                }
            }
            catch
            {
            }
            return null;
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
