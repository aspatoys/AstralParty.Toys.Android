using System.Runtime.InteropServices.JavaScript;
using System.Runtime.Versioning;
using Mono.Cecil;
using Mono.Cecil.Cil;

namespace Cesium.CachePatcher;

public static partial class AssemblyPatch
{
    private const string AssemblyName = "AstralParty.Runtime";
    private const string BootType = "CesiumLoader.Android.Bootstrap.Boot";
    private const string BootPath = "/sdcard/Android/data/com.feimo.astralparty/files/CesiumLoader/bootstrap/CesiumLoader.Android.Bootstrap.dll";
    private const string HookName = "__CesiumAndroidLoad";

    [JSExport]
    [SupportedOSPlatform("browser")]
    public static byte[] Patch(byte[] input)
    {
        using var image = new MemoryStream(input, writable: false);
        using var assemblyResolver = new DefaultAssemblyResolver();
        var resolver = new ConstantResolver(assemblyResolver);
        using var assembly = AssemblyDefinition.ReadAssembly(image, new ReaderParameters { MetadataResolver = resolver });
        var module = assembly.MainModule;
        if (assembly.Name.Name != AssemblyName || assembly.Modules.Count != 1 ||
            assembly.Name.HasPublicKey || !module.Attributes.HasFlag(ModuleAttributes.ILOnly))
            throw new InvalidDataException("游戏程序集名称、模块布局或签名与当前安装方式不兼容。");

        resolver.Register(module);

        var root = module.Types.Single(t => t.Name == "<Module>");
        var initializer = root.Methods.SingleOrDefault(m => m.Name == ".cctor");
        if (root.Methods.Any(m => m.Name == HookName) ||
            initializer?.HasBody == true && initializer.Body.Instructions.Any(i => i.Operand is string s && s == BootType))
            throw new InvalidDataException("此缓存已包含加载器入口。请先用对应备份恢复原包，或在游戏中修复资源。");
        if (initializer != null && (!initializer.IsStatic || !initializer.HasBody || initializer.Body.Instructions.Count == 0))
            throw new InvalidDataException("当前游戏程序集的模块初始化方法无法修改。");

        var corlib = module.AssemblyReferences.SingleOrDefault(r => r.Name == "mscorlib")
            ?? throw new InvalidDataException("无法识别游戏程序集的运行时。");
        var hook = new MethodDefinition(HookName, MethodAttributes.Private | MethodAttributes.Static, module.TypeSystem.Void);
        root.Methods.Add(hook);
        EmitHook(module, corlib, hook);
        if (initializer == null)
        {
            initializer = new MethodDefinition(".cctor", MethodAttributes.Private | MethodAttributes.Static |
                MethodAttributes.HideBySig | MethodAttributes.SpecialName | MethodAttributes.RTSpecialName, module.TypeSystem.Void);
            root.Methods.Add(initializer);
            initializer.Body.GetILProcessor().Emit(OpCodes.Ret);
        }
        var il = initializer.Body.GetILProcessor();
        il.InsertBefore(initializer.Body.Instructions[0], il.Create(OpCodes.Call, hook));

        using var output = new MemoryStream();
        assembly.Write(output);
        output.Position = 0;
        using var verified = AssemblyDefinition.ReadAssembly(output);
        var verifiedRoot = verified.MainModule.Types.Single(t => t.Name == "<Module>");
        var verifiedHook = verifiedRoot.Methods.Single(m => m.Name == HookName);
        var first = verifiedRoot.Methods.Single(m => m.Name == ".cctor").Body.Instructions[0];
        if (verified.Name.Name != AssemblyName || verified.MainModule.Mvid != module.Mvid ||
            first.OpCode != OpCodes.Call || (first.Operand as MethodReference)?.Name != HookName ||
            verifiedHook.Body.ExceptionHandlers.Count != 1)
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

    private static void EmitHook(ModuleDefinition module, AssemblyNameReference corlib, MethodDefinition hook)
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
        var reflectionAssembly = Type("System.Reflection", "Assembly");
        var systemType = Type("System", "Type");
        var read = Method(Type("System.IO", "File"), "ReadAllBytes", bytes, false, types.String);
        var load = Method(reflectionAssembly, "Load", reflectionAssembly, false, bytes);
        var getType = Method(reflectionAssembly, "GetType", systemType, true, types.String);
        var getMethod = Method(systemType, "GetMethod", Type("System.Reflection", "MethodInfo"), true, types.String);
        var invoke = Method(Type("System.Reflection", "MethodBase"), "Invoke", types.Object, true,
            types.Object, new ArrayType(types.Object));

        var il = hook.Body.GetILProcessor();
        var start = il.Create(OpCodes.Ldstr, BootPath);
        var catchStart = il.Create(OpCodes.Pop);
        var ret = il.Create(OpCodes.Ret);
        il.Append(start);
        il.Emit(OpCodes.Call, read);
        il.Emit(OpCodes.Call, load);
        il.Emit(OpCodes.Ldstr, BootType);
        il.Emit(OpCodes.Callvirt, getType);
        il.Emit(OpCodes.Ldstr, "Load");
        il.Emit(OpCodes.Callvirt, getMethod);
        il.Emit(OpCodes.Ldnull);
        il.Emit(OpCodes.Ldnull);
        il.Emit(OpCodes.Callvirt, invoke);
        il.Emit(OpCodes.Pop);
        il.Emit(OpCodes.Leave_S, ret);
        il.Append(catchStart);
        il.Emit(OpCodes.Leave_S, ret);
        il.Append(ret);
        hook.Body.ExceptionHandlers.Add(new ExceptionHandler(ExceptionHandlerType.Catch)
        {
            TryStart = start, TryEnd = catchStart, HandlerStart = catchStart, HandlerEnd = ret,
            CatchType = Type("System", "Exception"),
        });
    }
}
