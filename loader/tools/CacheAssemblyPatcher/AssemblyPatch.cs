using System.Runtime.InteropServices.JavaScript;
using System.Runtime.Versioning;
using System.Text.RegularExpressions;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace Cesium.CachePatcher;

public static partial class AssemblyPatch
{
    private const string AssemblyName = "AstralParty.Runtime";
    private const string BootType = "CesiumLoader.Android.Bootstrap.Boot";
    private const string HookName = "__CesiumAndroidLoad";
    private const string DirectBridge = "CesiumLoader.Android.BootstrapBridge";
    private const string UnsupportedEdit = "CESIUM_UNSUPPORTED_RUNTIME: ";

    [JSExport]
    [SupportedOSPlatform("browser")]
    public static byte[] Patch(byte[] input, string packageName, bool reference)
    {
        if (!Regex.IsMatch(packageName, @"\A[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+\z") ||
            packageName.Length > 255 ||
            packageName.Equals("com.hiynet.aspatoys", StringComparison.OrdinalIgnoreCase))
            throw new ArgumentException("不支持的游戏包名。");
        var loaderRoot = $"/sdcard/Android/data/{packageName}/files/CesiumLoader";
        using var image = new MemoryStream(input, writable: false);
        using var assemblyResolver = new DefaultAssemblyResolver();
        var resolver = new ConstantResolver(assemblyResolver);
        AssemblyDefinition assembly;
        try
        {
            assembly = AssemblyDefinition.ReadAssembly(image, new ReaderParameters { MetadataResolver = resolver });
        }
        catch (Exception error) when (!reference)
        {
            throw new InvalidDataException(UnsupportedEdit + error.Message, error);
        }
        using var ownedAssembly = assembly;
        var module = assembly.MainModule;
        if (assembly.Name.Name != AssemblyName || assembly.Modules.Count != 1 ||
            assembly.Name.HasPublicKey || !module.Attributes.HasFlag(ModuleAttributes.ILOnly))
            throw new InvalidDataException((reference ? "" : UnsupportedEdit) + "游戏程序集名称、模块布局或签名与当前安装方式不兼容。");

        var root = module.Types.Single(t => t.Name == "<Module>");
        var initializer = root.Methods.SingleOrDefault(m => m.Name == ".cctor");
        var bridge = module.GetType(DirectBridge);
        if (reference)
        {
            // The verified v0.5.0 reference calls Tick from LoginSceneController.Awake.
            // Keep that call and retarget the bridge to the bootstrap bundled in Toys.
            var tick = bridge?.Methods.Single(m => m.Name == "Tick")
                ?? throw new InvalidDataException("渠道兼容基准缺少引导入口。");
            var started = bridge!.Fields.Single(f => f.Name == "started" && f.IsStatic);
            var awake = module.GetType("Core.Scene.LoginSceneController").Methods
                .Single(m => m.Name == "Awake" && m.HasBody);
            if (awake.Body.Instructions[0].Operand is not MethodReference target || target.FullName != tick.FullName)
                throw new InvalidDataException("渠道兼容基准的登录入口不匹配。");
            var referenceCorlib = module.AssemblyReferences.Single(r => r.Name == "mscorlib");
            tick.Body = new MethodBody(tick);
            EmitHook(module, referenceCorlib, tick, loaderRoot, started);
            resolver.Register(module);
            return WriteVerified(assembly, loaderRoot, reference);
        }

        if (root.Methods.Any(m => m.Name == HookName) ||
            bridge != null || module.Types.Any(t => t.Namespace.StartsWith("ModLoader.Android", StringComparison.Ordinal)) ||
            initializer?.HasBody == true && initializer.Body.Instructions.Any(i => i.Operand is string s && s == BootType))
            throw new InvalidDataException("此缓存已包含加载器入口。请先用对应备份恢复原包，或在游戏中修复资源。");
        if (initializer != null && (!initializer.IsStatic || !initializer.HasBody || initializer.Body.Instructions.Count == 0))
            throw new InvalidDataException(UnsupportedEdit + "当前游戏程序集的模块初始化方法无法修改。");

        var corlib = module.AssemblyReferences.SingleOrDefault(r => r.Name == "mscorlib")
            ?? throw new InvalidDataException(UnsupportedEdit + "无法识别游戏程序集的运行时。");
        var hook = new MethodDefinition(HookName, MethodAttributes.Private | MethodAttributes.Static, module.TypeSystem.Void);
        root.Methods.Add(hook);
        EmitHook(module, corlib, hook, loaderRoot);
        if (initializer == null)
        {
            initializer = new MethodDefinition(".cctor", MethodAttributes.Private | MethodAttributes.Static |
                MethodAttributes.HideBySig | MethodAttributes.SpecialName | MethodAttributes.RTSpecialName, module.TypeSystem.Void);
            root.Methods.Add(initializer);
            initializer.Body.GetILProcessor().Emit(OpCodes.Ret);
        }
        var il = initializer.Body.GetILProcessor();
        il.InsertBefore(initializer.Body.Instructions[0], il.Create(OpCodes.Call, hook));

        try
        {
            resolver.Register(module);
            return WriteVerified(assembly, loaderRoot, reference);
        }
        catch (Exception error) when (error is AssemblyResolutionException or InvalidDataException or NotSupportedException or BadImageFormatException)
        {
            throw new InvalidDataException(UnsupportedEdit + error.Message, error);
        }
    }

    private static byte[] WriteVerified(AssemblyDefinition assembly, string loaderRoot, bool reference)
    {
        using var output = new MemoryStream();
        assembly.Write(output);
        output.Position = 0;
        using var verified = AssemblyDefinition.ReadAssembly(output);
        var root = verified.MainModule.GetType(reference ? DirectBridge : "<Module>");
        var hook = root.Methods.Single(m => m.Name == (reference ? "Tick" : HookName));
        var caller = reference ? verified.MainModule.GetType("Core.Scene.LoginSceneController").Methods
            .Single(m => m.Name == "Awake" && m.HasBody) : root.Methods.Single(m => m.Name == ".cctor");
        var first = caller.Body.Instructions[0];
        if (verified.Name.Name != AssemblyName || verified.MainModule.Mvid != assembly.MainModule.Mvid ||
            first.OpCode != OpCodes.Call || (first.Operand as MethodReference)?.FullName != hook.FullName ||
            hook.Body.ExceptionHandlers.Count != 1 ||
            !hook.Body.Instructions.Any(i => i.Operand is string value && value == loaderRoot))
            throw new InvalidDataException("生成的游戏程序集未通过回读校验。");
        return output.ToArray();
    }

    /// <summary>Reads enum defaults using their ECMA constant values, without shipping Unity DLLs.</summary>
    private sealed class ConstantResolver(IAssemblyResolver assemblies) : MetadataResolver(assemblies)
    {
        private readonly Dictionary<(string Scope, string Name), TypeDefinition> enums = new();

        public void Register(ModuleDefinition module)
        {
            foreach (var type in module.GetTypes())
            {
                foreach (var field in type.Fields) Add(field, field.FieldType);
                foreach (var property in type.Properties) Add(property, property.PropertyType);
                foreach (var method in type.Methods)
                {
                    Add(method.MethodReturnType, method.ReturnType);
                    foreach (var parameter in method.Parameters) Add(parameter, parameter.ParameterType);
                }
            }

            void Add(IConstantProvider provider, TypeReference type)
            {
                if (!provider.HasConstant || provider.Constant is not (sbyte or byte or short or ushort or int or uint or long or ulong))
                    return;
                if (type is GenericInstanceType generic && generic.ElementType.FullName == "System.Nullable`1")
                    type = generic.GenericArguments[0];
                if (type.MetadataType != MetadataType.ValueType || type.Scope is not AssemblyNameReference reference)
                    return;
                var key = (reference.FullName, type.FullName);
                if (enums.ContainsKey(key)) return;
                var definition = new TypeDefinition(type.Namespace, type.Name, TypeAttributes.Sealed,
                    new TypeReference("System", "Enum", module, module.TypeSystem.CoreLibrary));
                // Cecil takes the actual primitive tag from the boxed Constant table value.
                definition.Fields.Add(new FieldDefinition("value__", FieldAttributes.SpecialName | FieldAttributes.RTSpecialName,
                    module.TypeSystem.Int32));
                enums.Add(key, definition);
            }
        }

        public override TypeDefinition? Resolve(TypeReference type) =>
            type.Scope is AssemblyNameReference reference && enums.TryGetValue((reference.FullName, type.FullName), out var definition)
                ? definition : base.Resolve(type);
    }

    private static void EmitHook(ModuleDefinition module, AssemblyNameReference corlib, MethodDefinition hook,
        string loaderRoot, FieldDefinition? started = null)
    {
        TypeReference Type(string ns, string name) => new(ns, name, module, corlib);
        MethodReference Method(TypeReference owner, string name, TypeReference result, bool instance,
            params TypeReference[] parameters)
        {
            var method = new MethodReference(name, result, owner) { HasThis = instance };
            foreach (var parameter in parameters) method.Parameters.Add(new ParameterDefinition(parameter));
            return method;
        }

        var types = module.TypeSystem;
        var bytes = new ArrayType(types.Byte);
        var fileType = Type("System.IO", "File");
        var pathType = Type("System.IO", "Path");
        var reflectionAssembly = Type("System.Reflection", "Assembly");
        var systemType = Type("System", "Type");
        var fileExists = Method(fileType, "Exists", types.Boolean, false, types.String);
        var read = Method(fileType, "ReadAllBytes", bytes, false, types.String);
        var combine = Method(pathType, "Combine", types.String, false, types.String, types.String);
        var isNullOrEmpty = Method(Type("System", "String"), "IsNullOrEmpty", types.Boolean, false, types.String);
        var load = Method(reflectionAssembly, "Load", reflectionAssembly, false, bytes);
        var getType = Method(reflectionAssembly, "GetType", systemType, true, types.String);
        var getMethod = Method(systemType, "GetMethod", Type("System.Reflection", "MethodInfo"), true, types.String);
        var invoke = Method(Type("System.Reflection", "MethodBase"), "Invoke", types.Object, true,
            types.Object, new ArrayType(types.Object));

        var unity = module.AssemblyReferences.FirstOrDefault(r => r.Name == "UnityEngine.CoreModule");
        MethodReference? persistentDataPath = unity == null ? null :
            Method(new TypeReference("UnityEngine", "Application", module, unity), "get_persistentDataPath", types.String, false);
        MethodReference? logError = unity == null ? null :
            Method(new TypeReference("UnityEngine", "Debug", module, unity), "LogError", types.Void, false, types.Object);

        hook.Body.InitLocals = true;
        var rootVar = new VariableDefinition(types.String);
        var bootVar = new VariableDefinition(types.String);
        hook.Body.Variables.Add(rootVar);
        hook.Body.Variables.Add(bootVar);
        VariableDefinition? persistentVar = null;
        if (persistentDataPath != null)
        {
            persistentVar = new VariableDefinition(types.String);
            hook.Body.Variables.Add(persistentVar);
        }

        const string bootstrapRelative = "bootstrap/CesiumLoader.Android.Bootstrap.dll";
        var il = hook.Body.GetILProcessor();
        var start = il.Create(OpCodes.Ldstr, loaderRoot);
        var loadBoot = il.Create(OpCodes.Ldloc, bootVar);
        var catchStart = logError != null
            ? il.Create(OpCodes.Call, logError)
            : il.Create(OpCodes.Pop);
        var ret = il.Create(OpCodes.Ret);
        if (started != null)
        {
            il.Emit(OpCodes.Ldsfld, started);
            il.Emit(OpCodes.Brtrue, ret);
            il.Emit(OpCodes.Ldc_I4_1);
            il.Emit(OpCodes.Stsfld, started);
        }
        il.Append(start);
        il.Emit(OpCodes.Stloc, rootVar);
        il.Emit(OpCodes.Ldloc, rootVar);
        il.Emit(OpCodes.Ldstr, bootstrapRelative);
        il.Emit(OpCodes.Call, combine);
        il.Emit(OpCodes.Stloc, bootVar);
        if (persistentDataPath != null && persistentVar != null)
        {
            il.Emit(OpCodes.Ldloc, bootVar);
            il.Emit(OpCodes.Call, fileExists);
            il.Emit(OpCodes.Brtrue, loadBoot);
            il.Emit(OpCodes.Call, persistentDataPath);
            il.Emit(OpCodes.Stloc, persistentVar);
            il.Emit(OpCodes.Ldloc, persistentVar);
            il.Emit(OpCodes.Call, isNullOrEmpty);
            il.Emit(OpCodes.Brtrue, loadBoot);
            il.Emit(OpCodes.Ldloc, persistentVar);
            il.Emit(OpCodes.Ldstr, "CesiumLoader");
            il.Emit(OpCodes.Call, combine);
            il.Emit(OpCodes.Stloc, rootVar);
            il.Emit(OpCodes.Ldloc, rootVar);
            il.Emit(OpCodes.Ldstr, bootstrapRelative);
            il.Emit(OpCodes.Call, combine);
            il.Emit(OpCodes.Stloc, bootVar);
        }
        il.Append(loadBoot);
        il.Emit(OpCodes.Call, read);
        il.Emit(OpCodes.Call, load);
        il.Emit(OpCodes.Ldstr, BootType);
        il.Emit(OpCodes.Callvirt, getType);
        il.Emit(OpCodes.Ldstr, "LoadForGame");
        il.Emit(OpCodes.Callvirt, getMethod);
        il.Emit(OpCodes.Ldnull);
        il.Emit(OpCodes.Ldc_I4_1);
        il.Emit(OpCodes.Newarr, types.Object);
        il.Emit(OpCodes.Dup);
        il.Emit(OpCodes.Ldc_I4_0);
        il.Emit(OpCodes.Ldloc, rootVar);
        il.Emit(OpCodes.Stelem_Ref);
        il.Emit(OpCodes.Callvirt, invoke);
        il.Emit(OpCodes.Pop);
        il.Emit(OpCodes.Leave, ret);
        il.Append(catchStart);
        il.Emit(OpCodes.Leave, ret);
        il.Append(ret);
        hook.Body.ExceptionHandlers.Add(new ExceptionHandler(ExceptionHandlerType.Catch)
        {
            TryStart = start, TryEnd = catchStart, HandlerStart = catchStart, HandlerEnd = ret,
            CatchType = Type("System", "Exception"),
        });
    }
}
