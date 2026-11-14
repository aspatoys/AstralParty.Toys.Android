# tools

PC 侧工具链，不参与 App 构建。

## 现在可用

| 工具 | 作用 |
|---|---|
| `cesium-inject.ps1` | **入口**：把引导调用注入游戏的热更缓存包 `__data` 并可选写回设备。取包 → 解包 → 注入 → 回填 → 复核 → 回写，每步校验；带 `-Restore` 一键回滚 |
| `cesium-deploy.ps1` | 把载荷（SDK / 引导 DLL / 模组 / 配置）部署到加载器根目录，逐个比对 SHA-256 |
| `bundle_assets.py` | UnityFS 读写：`list` / `extract` / `replace` / `verify`，用于在热更包里取放 TextAsset |
| `p0-discovery.ps1` | P0 只读发现：列出已装游戏包/版本、游戏数据目录结构、bundle 缓存条目、`__data`/`__info` 大小、哪些 bundle 里含 `AstralParty.Runtime.dll`，以及是否存在松散 DLL。加 `-Pull` 把候选块拉回本地 |
| `pe_blob_scan.py` | 在二进制块里扫描内嵌 PE，按节表算出真实长度与 SHA-256，识别是否 .NET、程序集名；`--dump` 可导出镜像 |
| `CesiumPatcher` | .NET 8 + Mono.Cecil 注入器：`list-anchors` 列候选锚点，`rewrite` 做原样往返，`inject-bootstrap` 写 `<Module>.cctor` 引导调用（自动加异常护栏），`inject-call` 在既有方法首部插入调用 |

```powershell
# 完整部署流程见 docs/全新机器部署.md
pwsh tools/cesium-deploy.ps1 -Serial 127.0.0.1:5555 -Payload build/payload
pwsh tools/cesium-inject.ps1 -Serial 127.0.0.1:5555 -Apply
pwsh tools/cesium-inject.ps1 -Restore -Serial 127.0.0.1:5555

# 只读体检，报告落在 tests/fixtures/real/p0-<时间戳>/
pwsh tools/p0-discovery.ps1 -Serial 127.0.0.1:5555 -Pull

# 从拉回来的块里抠出 DLL
python tools/pe_blob_scan.py tests/fixtures/real/p0-<时间戳>/<32hex>/__data --dump out/

# 看游戏程序集里有哪些可注入的锚点
tools\CesiumPatcher\bin\Release\net8.0\cesium-patcher.exe list-anchors <AstralParty.Runtime.dll> --refs <游戏程序集目录>

# 只做注入（-refs 可以直接给 bundle 解包出来的目录，省掉外部程序集依赖）
cesium-patcher.exe inject-bootstrap <in.dll> <out.dll> --dll <设备上的引导 DLL 绝对路径> --refs <dir>
```

判断口径：

- `pe_blob_scan.py` 报 `没有找到 PE 镜像` → 该块是压缩的，bundle 手术需要先解压（LZ4/LZMA），用 `bundle_assets.py` 而不是裸改字节；
- 报出 `AstralParty.Runtime.dll` 且 `is_dotnet` → 块是解压态；
- `p0-discovery.ps1` 在 `loose-dlls` 里列到 `AstralParty.Runtime.dll` → 走「替换文件」路线，不需要 bundle 手术。

## 规划中

| 工具 | 作用 |
|---|---|
| anchor 扫描器 | 特征扫描定位稳定注入点，热更后自动重定位（当前固定用模块 `.cctor`） |
| 适配流水线 | 每次热更后跑一遍注入 + 往返，产出兼容性结论 |
| App 内注入 | 把本目录的注入流程搬进安装器（`flavor-data/`），不再依赖 PC |

约定：所有工具的输出都带 SHA-256 清单，设备侧按清单校验，不信任“看起来对”的文件。
