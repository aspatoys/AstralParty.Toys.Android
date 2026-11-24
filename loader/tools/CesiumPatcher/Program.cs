using System.Security.Cryptography;
using Mono.Cecil;
using Mono.Cecil.Cil;

// CesiumLoader Android 注入器（P1）。
//
//   list-anchors      <dll>                                   列出可注入的候选锚点
//   inject-module-cctor <in.dll> <out.dll> [--call T::M]       给 <Module> 加静态构造，调用指定方法
//   inject-call       <in.dll> <out.dll> --type T --method M    在既有方法首部插入调用
//
// 注入的目标方法默认是 CesiumLoader.Mobile.Bootstrap.Boot::Load。
// 每次注入都会打印前后 SHA-256、类型/方法数变化，并在写完后重新读回验证。

const string DefaultTargetType = "CesiumLoader.Mobile.Bootstrap.Boot";
const string DefaultTargetMethod = "Load";
const string TargetAssembly = "CesiumLoader.Mobile.Bootstrap";

if (args.Length < 2)
{
    Usage();
    return 2;
}

try
{
    return args[0] switch
    {
        "list-anchors" => ListAnchors(args[1], Refs(args)),
        "rewrite" => Rewrite(args[1], args[2], Refs(args)),
        "merge" => Merge(args[1], args[2], args[3], Refs(args)),
        "inject-module-cctor" => InjectModuleCctor(args[1], args[2], Option(args, "--call"), Refs(args)),
        "inject-marker" => InjectMarker(args[1], args[2], RequireOption(args, "--path"), RequireOption(args, "--text"), Refs(args)),
        "inject-bootstrap" => InjectBootstrap(args[1], args[2], RequireOption(args, "--dll"), Refs(args)),
        "refs" => ListRefs(args[1], RequireOption(args, "--assembly"), Refs(args)),
        "replace-string" => ReplaceString(args[1], args[2], RequireOption(args, "--from"), RequireOption(args, "--to"), args.Contains("--prefix"), Refs(args)),
        "inject-call" => InjectIntoMethod(args[1], args[2], RequireOption(args, "--type"), RequireOption(args, "--method"), Option(args, "--call"), Refs(args)),
        _ => Unknown(args[0]),
    };
}
catch (Exception error)
{
    Console.Error.WriteLine($"失败：{error}");
    return 1;
}

static int Unknown(string command)
{
    Console.Error.WriteLine($"未知命令：{command}");
    Usage();
    return 2;
}

static void Usage()
{
    Console.WriteLine("""
        cesium-patcher
          list-anchors        <assembly.dll>
          rewrite             <in.dll> <out.dll>        （原样重写，用于验证往返）
          merge               <main.dll> <extra.dll> <out.dll>  （把引导程序集合并进游戏程序集）
          inject-module-cctor <in.dll> <out.dll> [--call Type::Method]
          inject-marker       <in.dll> <out.dll> --path <写出的文件> --text <内容>
          inject-call         <in.dll> <out.dll> --type <TypeFullName> --method <Name> [--call Type::Method]

        所有命令都接受 --refs <目录>（可重复），用于解析被引用程序集。
        """);
}

static string? Option(string[] args, string name)
{
    var index = Array.IndexOf(args, name);
    return index >= 0 && index + 1 < args.Length ? args[index + 1] : null;
}

static string RequireOption(string[] args, string name) =>
    Option(args, name) ?? throw new ArgumentException($"缺少参数 {name}");

static AssemblyDefinition Read(string path, string[] refDirs)
{
    var resolver = new DefaultAssemblyResolver();
    foreach (var dir in refDirs)
    {
        if (Directory.Exists(dir)) resolver.AddSearchDirectory(dir);
    }
    return AssemblyDefinition.ReadAssembly(path, new ReaderParameters
    {
        ReadingMode = ReadingMode.Immediate,
        InMemory = true,
        AssemblyResolver = resolver,
    });
}

static string[] Refs(string[] args)
{
    var dirs = new List<string>();
    for (var index = 0; index < args.Length - 1; index++)
    {
        if (args[index] == "--refs") dirs.Add(args[index + 1]);
    }
    return dirs.ToArray();
}

static string Sha256(string path)
{
    using var stream = File.OpenRead(path);
    return Convert.ToHexString(SHA256.HashData(stream)).ToLowerInvariant();
}

static int ListAnchors(string path, string[] refDirs)
{
    using var assembly = Read(path, refDirs);
    var module = assembly.MainModule;

    Console.WriteLine($"assembly : {assembly.Name.Name} {assembly.Name.Version}");
    Console.WriteLine($"module   : {module.Name}");
    Console.WriteLine($"mvid     : {module.Mvid}");
    Console.WriteLine($"types    : {CountTypes(module)}  methods: {CountMethods(module)}");
    Console.WriteLine($"refs     : {module.AssemblyReferences.Count}");
    Console.WriteLine();

    string[] names = { "Start", "Awake", "Init", "Initialize", "Launch", "Main", "Boot", "Setup", "Run", "OnEnable", "Enter" };

    var staticCtors = new List<string>();
    var candidates = new List<(string Token, string Type, string Method, string Why)>();

    foreach (var type in AllTypes(module))
    {
        foreach (var method in type.Methods)
        {
            if (!method.IsStatic) continue;

            if (method.IsConstructor && method.IsStatic)
            {
                staticCtors.Add($"{type.FullName}::{method.Name}");
                continue;
            }

            var runtimeInit = method.CustomAttributes.Any(a =>
                a.AttributeType.Name.Contains("RuntimeInitializeOnLoadMethod", StringComparison.Ordinal));
            if (runtimeInit)
            {
                candidates.Add((Token(method), type.FullName, method.Name, "RuntimeInitializeOnLoadMethod"));
                continue;
            }

            if (names.Contains(method.Name, StringComparer.Ordinal))
            {
                candidates.Add((Token(method), type.FullName, method.Name, "name"));
            }
        }
    }

    Console.WriteLine($"静态构造（.cctor）: {staticCtors.Count}");
    foreach (var item in staticCtors.Take(40)) Console.WriteLine($"  {item}");
    if (staticCtors.Count > 40) Console.WriteLine($"  … 还有 {staticCtors.Count - 40} 个");
    Console.WriteLine();

    Console.WriteLine($"启动类候选: {candidates.Count}");
    foreach (var item in candidates.Take(80))
    {
        Console.WriteLine($"  {item.Token}  {item.Type}::{item.Method}  ({item.Why})");
    }
    if (candidates.Count > 80) Console.WriteLine($"  … 还有 {candidates.Count - 80} 个");

    return 0;
}

static int InjectModuleCctor(string input, string output, string? call, string[] refDirs)
{
    var (targetType, targetMethod) = ParseCall(call);

    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var before = Describe(module);

    var moduleType = module.Types.FirstOrDefault(t => t.Name == "<Module>")
                     ?? throw new InvalidOperationException("找不到 <Module> 类型");

    var reference = BootstrapMethodReference(module, targetType, targetMethod);
    var ctor = moduleType.Methods.FirstOrDefault(m => m.Name == ".cctor" && m.IsStatic);
    if (ctor == null)
    {
        ctor = new MethodDefinition(
            ".cctor",
            MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig |
            MethodAttributes.SpecialName | MethodAttributes.RTSpecialName,
            module.TypeSystem.Void);
        moduleType.Methods.Add(ctor);
    }
    else if (AlreadyCalls(ctor, reference))
    {
        Console.WriteLine("已经是注入状态（<Module>.cctor 已调用目标方法），跳过。");
        return 0;
    }

    ctor.Body.Instructions.Clear();
    ctor.Body.Variables.Clear();
    ctor.Body.ExceptionHandlers.Clear();
    var il = ctor.Body.GetILProcessor();
    il.Emit(OpCodes.Call, reference);
    il.Emit(OpCodes.Ret);

    assembly.Write(output, new WriterParameters { DeterministicMvid = false });
    Console.WriteLine($"注入 <Module>.cctor -> {targetType}::{targetMethod}");
    return Verify(input, output, before, module, $"{targetType}::{targetMethod}", mode: "module-cctor", refDirs);
}

static int InjectIntoMethod(string input, string output, string typeName, string methodName, string? call, string[] refDirs)
{
    var (targetType, targetMethod) = ParseCall(call);

    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var before = Describe(module);

    var type = AllTypes(module).FirstOrDefault(t => t.FullName == typeName || t.Name == typeName)
               ?? throw new InvalidOperationException($"找不到类型 {typeName}");
    var method = type.Methods.FirstOrDefault(m => m.Name == methodName && m.HasBody)
                 ?? throw new InvalidOperationException($"找不到方法 {typeName}::{methodName}");

    var reference = BootstrapMethodReference(module, targetType, targetMethod);
    if (AlreadyCalls(method, reference))
    {
        Console.WriteLine($"{type.FullName}::{method.Name} 已经调用过目标方法，跳过。");
        return 0;
    }

    var il = method.Body.GetILProcessor();
    il.InsertBefore(method.Body.Instructions[0], il.Create(OpCodes.Call, reference));

    assembly.Write(output, new WriterParameters { DeterministicMvid = false });
    Console.WriteLine($"注入 {type.FullName}::{method.Name} 首部 -> {targetType}::{targetMethod}");
    return Verify(input, output, before, module, $"{targetType}::{targetMethod}", mode: "call", refDirs);
}

static int Verify(string input, string output, Stats before, ModuleDefinition module, string expected, string mode, string[] refDirs)
{
    using var check = Read(output, refDirs);
    var checkModule = check.MainModule;
    var target = expected.Split("::")[0];
    var name = expected.Split("::")[1];
    var found = false;

    foreach (var type in AllTypes(checkModule))
    {
        foreach (var method in type.Methods.Where(m => m.HasBody))
        {
            if (mode == "module-cctor" && !(type.Name == "<Module>" && method.Name == ".cctor")) continue;
            if (mode == "call" && method.Name != name) continue;
            if (method.Body.Instructions.Any(i => (i.Operand as MethodReference)?.Name == name &&
                                                  ((MethodReference)i.Operand).DeclaringType.FullName == target))
            {
                found = true;
            }
        }
    }

    var after = Describe(checkModule);
    Console.WriteLine($"输入 : {Path.GetFileName(input)}  {new FileInfo(input).Length} B  {Sha256(input)}");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    Console.WriteLine($"结构 : 类型 {before.Types} -> {after.Types}，方法 {before.Methods} -> {after.Methods}，引用 {before.Refs} -> {after.Refs}");
    Console.WriteLine(found ? "校验 : 注入调用已确认" : "校验 : 未找到注入调用（失败）");
    return found ? 0 : 1;
}

static int InjectMarker(string input, string output, string path, string text, string[] refDirs)
{
    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var before = Describe(module);

    var moduleType = module.Types.FirstOrDefault(t => t.Name == "<Module>")
                     ?? throw new InvalidOperationException("找不到 <Module> 类型");

    var corlib = module.AssemblyReferences.FirstOrDefault(r => r.Name == "mscorlib")
                 ?? throw new InvalidOperationException("找不到 mscorlib 引用");

    var fileType = new TypeReference("System.IO", "File", module, corlib);
    var writeAllText = new MethodReference("WriteAllText", module.TypeSystem.Void, fileType) { HasThis = false };
    writeAllText.Parameters.Add(new ParameterDefinition(module.TypeSystem.String));
    writeAllText.Parameters.Add(new ParameterDefinition(module.TypeSystem.String));

    var ctor = moduleType.Methods.FirstOrDefault(m => m.Name == ".cctor" && m.IsStatic);
    if (ctor == null)
    {
        ctor = new MethodDefinition(
            ".cctor",
            MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig |
            MethodAttributes.SpecialName | MethodAttributes.RTSpecialName,
            module.TypeSystem.Void);
        moduleType.Methods.Add(ctor);
    }

    ctor.Body.Instructions.Clear();
    ctor.Body.Variables.Clear();
    ctor.Body.ExceptionHandlers.Clear();
    var il = ctor.Body.GetILProcessor();
    il.Emit(OpCodes.Ldstr, path);
    il.Emit(OpCodes.Ldstr, text);
    il.Emit(OpCodes.Call, writeAllText);
    il.Emit(OpCodes.Ret);

    assembly.Write(output, new WriterParameters { DeterministicMvid = false });

    using var check = Read(output, refDirs);
    var checkType = check.MainModule.Types.First(t => t.Name == "<Module>");
    var checkCtor = checkType.Methods.FirstOrDefault(m => m.Name == ".cctor");
    var found = checkCtor != null && checkCtor.HasBody &&
                checkCtor.Body.Instructions.Any(i => i.OpCode == OpCodes.Call &&
                                                     (i.Operand as MethodReference)?.Name == "WriteAllText");

    var after = Describe(check.MainModule);
    Console.WriteLine($"注入 <Module>.cctor -> System.IO.File::WriteAllText(\"{path}\")");
    Console.WriteLine($"输入 : {Path.GetFileName(input)}  {new FileInfo(input).Length} B  {Sha256(input)}");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    Console.WriteLine($"结构 : 类型 {before.Types} -> {after.Types}，方法 {before.Methods} -> {after.Methods}，引用 {before.Refs} -> {after.Refs}");
    Console.WriteLine(found ? "校验 : 标记调用已确认" : "校验 : 未找到标记调用（失败）");
    return found ? 0 : 1;
}

static int Merge(string primary, string other, string output, string[] refDirs)
{
    var exe = Environment.GetEnvironmentVariable("ILREPACK_EXE");
    if (string.IsNullOrEmpty(exe)) exe = FindIlRepack();
    if (exe == null || !File.Exists(exe)) throw new InvalidOperationException("找不到 ILRepack.exe（可设置 ILREPACK_EXE 指定路径）");

    var searchDirs = new List<string>();
    searchDirs.AddRange(refDirs);
    searchDirs.Add(Path.GetDirectoryName(Path.GetFullPath(primary))!);
    searchDirs.Add(Path.GetDirectoryName(Path.GetFullPath(other))!);

    var arguments = new List<string>
    {
        "/out:" + Path.GetFullPath(output),
        "/union",
        "/allowMultiple",
        "/copyattrs",
    };
    foreach (var dir in searchDirs.Where(Directory.Exists).Distinct())
    {
        arguments.Add("/lib:" + dir);
    }
    arguments.Add(Path.GetFullPath(primary));
    arguments.Add(Path.GetFullPath(other));

    var start = new System.Diagnostics.ProcessStartInfo(exe)
    {
        UseShellExecute = false,
        RedirectStandardOutput = true,
        RedirectStandardError = true,
    };
    foreach (var argument in arguments) start.ArgumentList.Add(argument);

    using var process = System.Diagnostics.Process.Start(start)
                        ?? throw new InvalidOperationException("ILRepack 启动失败");
    var stdout = process.StandardOutput.ReadToEnd();
    var stderr = process.StandardError.ReadToEnd();
    process.WaitForExit();
    if (process.ExitCode != 0)
    {
        Console.Error.WriteLine(stdout);
        Console.Error.WriteLine(stderr);
        throw new InvalidOperationException($"ILRepack 退出码 {process.ExitCode}");
    }

    using var merged = Read(output, refDirs);
    var module = merged.MainModule;
    var hasBootstrap = module.Types.Any(t => t.FullName.StartsWith("CesiumLoader.Mobile.Bootstrap", StringComparison.Ordinal));
    var externalRef = module.AssemblyReferences.Any(r => r.Name == TargetAssembly);
    var stats = Describe(module);

    Console.WriteLine($"合并 {Path.GetFileName(other)} -> {Path.GetFileName(primary)}");
    Console.WriteLine($"输入 : {new FileInfo(primary).Length} B + {new FileInfo(other).Length} B");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    Console.WriteLine($"结构 : 类型 {stats.Types}，方法 {stats.Methods}，引用 {stats.Refs}");
    Console.WriteLine($"检查 : Bootstrap 类型={(hasBootstrap ? "有" : "无")}，残留外部引用={(externalRef ? "有" : "无")}");
    return hasBootstrap && !externalRef ? 0 : 1;
}

static string? FindIlRepack()
{
    try
    {
        var packages = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".nuget", "packages", "ilrepack");
        if (!Directory.Exists(packages)) return null;
        return Directory.GetFiles(packages, "ILRepack.exe", SearchOption.AllDirectories)
            .OrderByDescending(path => path, StringComparer.OrdinalIgnoreCase)
            .FirstOrDefault();
    }
    catch
    {
        return null;
    }
}

/// <summary>
/// 在 &lt;Module&gt; 的静态构造里直接加载引导程序集并调用 Boot.Load()。
///
/// 全程只用 mscorlib：Assembly.Load(byte[]) / Assembly.GetType(string) /
/// Type.GetMethod(string) / MethodBase.Invoke —— 这样不需要把引导程序集合并进游戏程序集，
/// 也不依赖 AppDomain.AssemblyResolve 的时序。
/// </summary>
static int InjectBootstrap(string input, string output, string dllPath, string[] refDirs)
{
    const string bootType = "CesiumLoader.Mobile.Bootstrap.Boot";
    const string bootMethod = "Load";

    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var before = Describe(module);

    var moduleType = module.Types.FirstOrDefault(t => t.Name == "<Module>")
                     ?? throw new InvalidOperationException("找不到 <Module> 类型");
    var corlib = module.AssemblyReferences.FirstOrDefault(r => r.Name == "mscorlib")
                 ?? throw new InvalidOperationException("找不到 mscorlib 引用");

    var fileType = new TypeReference("System.IO", "File", module, corlib);
    var readAllBytes = new MethodReference("ReadAllBytes", new ArrayType(module.TypeSystem.Byte), fileType) { HasThis = false };
    readAllBytes.Parameters.Add(new ParameterDefinition(module.TypeSystem.String));

    var assemblyType = new TypeReference("System.Reflection", "Assembly", module, corlib);
    var assemblyLoad = new MethodReference("Load", assemblyType, assemblyType) { HasThis = false };
    assemblyLoad.Parameters.Add(new ParameterDefinition(new ArrayType(module.TypeSystem.Byte)));

    var typeType = new TypeReference("System", "Type", module, corlib);
    var getType = new MethodReference("GetType", typeType, assemblyType) { HasThis = true };
    getType.Parameters.Add(new ParameterDefinition(module.TypeSystem.String));

    var methodInfoType = new TypeReference("System.Reflection", "MethodInfo", module, corlib);
    var getMethod = new MethodReference("GetMethod", methodInfoType, typeType) { HasThis = true };
    getMethod.Parameters.Add(new ParameterDefinition(module.TypeSystem.String));

    var methodBaseType = new TypeReference("System.Reflection", "MethodBase", module, corlib);
    var invoke = new MethodReference("Invoke", module.TypeSystem.Object, methodBaseType) { HasThis = true };
    invoke.Parameters.Add(new ParameterDefinition(module.TypeSystem.Object));
    invoke.Parameters.Add(new ParameterDefinition(new ArrayType(module.TypeSystem.Object)));

    var ctor = moduleType.Methods.FirstOrDefault(m => m.Name == ".cctor" && m.IsStatic);
    if (ctor == null)
    {
        ctor = new MethodDefinition(
            ".cctor",
            MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig |
            MethodAttributes.SpecialName | MethodAttributes.RTSpecialName,
            module.TypeSystem.Void);
        moduleType.Methods.Add(ctor);
    }

    ctor.Body.Instructions.Clear();
    ctor.Body.Variables.Clear();
    ctor.Body.ExceptionHandlers.Clear();
    var il = ctor.Body.GetILProcessor();

    // 整段用 try/catch 包住：引导 DLL 缺失、路径写错、Assembly.Load 抛镜像异常时，
    // 只吞掉异常，绝不让 <Module>.cctor 失败 —— cctor 一失败，游戏里任何
    // AstralParty.Runtime 的类型都无法初始化，表现是启动即崩、且不给出有效堆栈。
    var retInstruction = il.Create(OpCodes.Ret);
    var handlerStart = il.Create(OpCodes.Pop);
    var leaveToRet = il.Create(OpCodes.Leave_S, retInstruction);

    var tryStart = il.Create(OpCodes.Ldstr, dllPath);
    il.Append(tryStart);
    il.Append(il.Create(OpCodes.Call, readAllBytes));
    il.Append(il.Create(OpCodes.Call, assemblyLoad));
    il.Append(il.Create(OpCodes.Ldstr, bootType));
    il.Append(il.Create(OpCodes.Callvirt, getType));
    il.Append(il.Create(OpCodes.Ldstr, bootMethod));
    il.Append(il.Create(OpCodes.Callvirt, getMethod));
    il.Append(il.Create(OpCodes.Ldnull));
    il.Append(il.Create(OpCodes.Ldnull));
    il.Append(il.Create(OpCodes.Callvirt, invoke));
    il.Append(il.Create(OpCodes.Pop));
    il.Append(leaveToRet);

    il.Append(handlerStart);
    il.Append(il.Create(OpCodes.Leave_S, retInstruction));
    il.Append(retInstruction);

    ctor.Body.ExceptionHandlers.Add(new ExceptionHandler(ExceptionHandlerType.Catch)
    {
        TryStart = tryStart,
        TryEnd = handlerStart,
        HandlerStart = handlerStart,
        HandlerEnd = retInstruction,
        CatchType = new TypeReference("System", "Exception", module, corlib),
    });

    assembly.Write(output, new WriterParameters { DeterministicMvid = false });

    using var check = Read(output, refDirs);
    var checkCtor = check.MainModule.Types.First(t => t.Name == "<Module>").Methods.FirstOrDefault(m => m.Name == ".cctor");
    var found = checkCtor != null && checkCtor.HasBody &&
                checkCtor.Body.Instructions.Any(i => (i.Operand as MethodReference)?.Name == "Invoke");
    var guarded = checkCtor != null && checkCtor.HasBody && checkCtor.Body.ExceptionHandlers.Count > 0;
    var literal = checkCtor != null && checkCtor.HasBody &&
                  checkCtor.Body.Instructions.Any(i => i.OpCode == OpCodes.Ldstr && (string?)i.Operand == dllPath);

    var after = Describe(check.MainModule);
    Console.WriteLine($"注入 <Module>.cctor -> Assembly.Load(\"{dllPath}\") -> {bootType}::{bootMethod}");
    Console.WriteLine($"输入 : {Path.GetFileName(input)}  {new FileInfo(input).Length} B  {Sha256(input)}");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    Console.WriteLine($"结构 : 类型 {before.Types} -> {after.Types}，方法 {before.Methods} -> {after.Methods}，引用 {before.Refs} -> {after.Refs}");
    Console.WriteLine(found ? "校验 : 引导调用已确认" : "校验 : 未找到引导调用（失败）");
    Console.WriteLine(guarded ? "校验 : 异常护栏已确认" : "校验 : 缺少异常护栏（失败）");
    Console.WriteLine(literal ? "校验 : 引导路径已写入" : "校验 : 引导路径不匹配（失败）");
    return found && guarded && literal ? 0 : 1;
}

/// <summary>列出某个程序集引用的其它程序集成员，用来推断「对方 SDK 里有什么我们缺」。 </summary>
static int ListRefs(string input, string assemblyName, string[] refDirs)
{
    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;

    static bool InScope(IMetadataScope? scope, string name) =>
        scope is AssemblyNameReference reference && reference.Name == name;

    var typeNames = new SortedSet<string>(StringComparer.Ordinal);
    foreach (var type in module.GetTypeReferences())
    {
        if (InScope(type.Scope, assemblyName)) typeNames.Add(type.FullName);
    }

    var members = new SortedSet<string>(StringComparer.Ordinal);
    foreach (var type in AllTypes(module))
    {
        foreach (var method in type.Methods.Where(m => m.HasBody))
        {
            foreach (var instruction in method.Body.Instructions)
            {
                if (instruction.Operand is not MemberReference reference) continue;
                if (!InScope(reference.DeclaringType?.Scope, assemblyName)) continue;
                members.Add(reference.FullName);
            }
        }
    }

    Console.WriteLine($"引用 {assemblyName} 的类型（{typeNames.Count}）：");
    foreach (var name in typeNames) Console.WriteLine($"  {name}");
    Console.WriteLine($"成员（{members.Count}）：");
    foreach (var name in members) Console.WriteLine($"  {name}");
    return 0;
}

/// <summary>
/// 把方法体里的字符串常量做字面/前缀替换。
///
/// 用途：第三方程序集里有硬编码路径（例如悬浮窗写死 `/sdcard/CesiumLoader/overlay.png`），
/// 数据版里游戏没有所有文件访问权限，必须把它指到 app 私有目录。
/// </summary>
static int ReplaceString(string input, string output, string from, string to, bool prefix, string[] refDirs)
{
    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var replaced = 0;

    foreach (var type in AllTypes(module))
    {
        foreach (var method in type.Methods.Where(m => m.HasBody))
        {
            foreach (var instruction in method.Body.Instructions)
            {
                if (instruction.OpCode != OpCodes.Ldstr || instruction.Operand is not string value) continue;
                bool hit = prefix ? value.StartsWith(from, StringComparison.Ordinal) : value == from;
                if (!hit) continue;
                instruction.Operand = prefix ? to + value.Substring(from.Length) : to;
                replaced++;
            }
        }
    }

    assembly.Write(output, new WriterParameters { DeterministicMvid = false });
    Console.WriteLine($"替换字符串：{replaced} 处  ({from}{(prefix ? "*" : "")} -> {to})");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    return replaced > 0 ? 0 : 1;
}

static int Rewrite(string input, string output, string[] refDirs){
    using var assembly = Read(input, refDirs);
    var module = assembly.MainModule;
    var before = Describe(module);
    assembly.Write(output, new WriterParameters { DeterministicMvid = false });
    var after = Describe(Read(output, refDirs).MainModule);
    Console.WriteLine($"输入 : {Path.GetFileName(input)}  {new FileInfo(input).Length} B  {Sha256(input)}");
    Console.WriteLine($"输出 : {Path.GetFileName(output)}  {new FileInfo(output).Length} B  {Sha256(output)}");
    Console.WriteLine($"结构 : 类型 {before.Types} -> {after.Types}，方法 {before.Methods} -> {after.Methods}，引用 {before.Refs} -> {after.Refs}");
    return 0;
}

static Stats Describe(ModuleDefinition module) => new(CountTypes(module), CountMethods(module), module.AssemblyReferences.Count);

static int CountTypes(ModuleDefinition module) => AllTypes(module).Count();

static int CountMethods(ModuleDefinition module) => AllTypes(module).Sum(t => t.Methods.Count);

static IEnumerable<TypeDefinition> AllTypes(ModuleDefinition module)
{
    foreach (var type in module.Types)
    {
        yield return type;
        foreach (var nested in Nested(type)) yield return nested;
    }
}

static IEnumerable<TypeDefinition> Nested(TypeDefinition type)
{
    foreach (var child in type.NestedTypes)
    {
        yield return child;
        foreach (var grand in Nested(child)) yield return grand;
    }
}

static string Token(MethodDefinition method) => $"0x{method.MetadataToken.ToInt32():x8}";

static (string Type, string Method) ParseCall(string? call)
{
    if (string.IsNullOrWhiteSpace(call)) return (DefaultTargetType, DefaultTargetMethod);
    var parts = call.Split("::", StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    if (parts.Length != 2) throw new ArgumentException($"--call 需要写成 类型全名::方法名，实际：{call}");
    return (parts[0], parts[1]);
}

static MethodReference BootstrapMethodReference(ModuleDefinition module, string typeName, string methodName)
{
    var scope = module.AssemblyReferences.FirstOrDefault(r => r.Name == TargetAssembly);
    if (scope == null)
    {
        scope = new AssemblyNameReference(TargetAssembly, new Version(0, 1, 0, 0));
        module.AssemblyReferences.Add(scope);
    }

    var lastDot = typeName.LastIndexOf('.');
    var ns = lastDot > 0 ? typeName[..lastDot] : string.Empty;
    var simple = lastDot > 0 ? typeName[(lastDot + 1)..] : typeName;

    var declaring = new TypeReference(ns, simple, module, scope);
    var reference = new MethodReference(methodName, module.TypeSystem.Void) { DeclaringType = declaring, HasThis = false };
    return reference;
}

static bool AlreadyCalls(MethodDefinition method, MethodReference reference)
{
    if (!method.HasBody) return false;
    return method.Body.Instructions.Any(instruction =>
        instruction.OpCode == OpCodes.Call &&
        instruction.Operand is MethodReference call &&
        call.Name == reference.Name &&
        call.DeclaringType.FullName == reference.DeclaringType.FullName);
}

internal readonly record struct Stats(int Types, int Methods, int Refs);
