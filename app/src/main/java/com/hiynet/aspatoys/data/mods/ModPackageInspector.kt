package com.hiynet.aspatoys.data.mods

import com.hiynet.aspatoys.core.booleanOrDefault
import com.hiynet.aspatoys.core.parseJsonObjectOrNull
import com.hiynet.aspatoys.core.string
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * 模组包（ZIP）预检：只读，不写任何文件。
 *
 * 包布局与 `cesium package` 和加载器扫描规则一致：
 *
 * ```
 * <ModId>/<ModId>.dll      ← 必须有；DLL 与所在文件夹同名，加载器才认作一个模组
 * <ModId>/<ModId>.json     ← sidecar（可选，声明名字/版本/依赖/启用状态）
 * <ModId>/config.json      ← 模组配置（可选，模组的 SdkConfig 读写它）
 * <ModId>/...              ← 模组自带的资源（可选，保持包内相对结构）
 * ```
 *
 * 也接受把 `<ModId>.dll` 直接放在包根目录的包（预检时按「自动补一层同名文件夹」理解）。
 * 校验在落盘之前完成：路径不安全、条目重复、超出大小限制或找不到模组结构时直接抛
 * [ModPackageException]，调用方不会留下半个模组。
 */
class ModPackageInspector(private val layout: ModLayout) {

    /** 校验模组包并生成预检报告；包不合法时抛 [ModPackageException]。 */
    fun inspect(source: File): ModPackageInspection {
        validateFile(source)
        val plans = mutableListOf<PackageModPlan>()
        val skipped = mutableListOf<String>()
        var fileCount = 0
        var totalBytes = 0L

        ZipFile(source).use { zip ->
            val files = readPackageFiles(zip)
            fileCount = files.size
            totalBytes = files.sumOf { it.length }
            plans += buildPlans(files)
            fillPlanState(zip, plans)
            skipped += skippedFiles(files, plans)
        }

        val installedSdk = layout.installedSdkVersion()
        val mods = plans.map { plan ->
            InspectedMod(
                modId = plan.modId,
                name = plan.displayName,
                folderName = plan.folderName,
                containerRoot = plan.containerRoot,
                installed = plan.installed,
                installedVersion = plan.installedVersion,
                packageVersion = plan.packageVersion,
                sdkVersion = plan.packageSdkVersion,
                isDowngrade = plan.isDowngrade,
                isUpgrade = plan.isUpgrade,
                preservesConfig = plan.configExisted,
                preservesDisabled = plan.disabledBefore,
                removedFiles = plan.staleFiles,
                files = plan.files.map(plan::relativePathOf),
                totalBytes = plan.totalBytes,
                installedSdkVersion = installedSdk ?: "",
                sdkBlocked = SdkVersions.isIncompatible(plan.packageSdkVersion, installedSdk),
                dllLooksManaged = plan.dllLooksManaged,
            )
        }

        return ModPackageInspection(
            sourceName = source.name,
            mods = mods,
            skipped = skipped,
            sha256 = sha256Of(source),
            sizeBytes = source.length(),
            fileCount = fileCount,
            totalBytes = totalBytes,
        )
    }

    // ============================== 文件校验 ==============================

    private fun validateFile(source: File) {
        if (!source.isFile) throw ModPackageException("找不到要检查的 mod 包。")
        if (!source.extension.equals("zip", ignoreCase = true)) {
            throw ModPackageException("mod 包必须是 .zip 文件。")
        }
        if (source.length() > MaxPackageFileBytes) {
            throw ModPackageException("mod 包不能超过 ${MaxPackageFileBytes / 1024 / 1024} MB。")
        }
    }

    /** 读出并校验包内文件条目（目录条目与解包垃圾文件会被跳过）。 */
    private fun readPackageFiles(zip: ZipFile): List<PackageFile> {
        val files = mutableListOf<PackageFile>()
        val seen = HashSet<String>()
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name.isEmpty()) continue
            val path = entry.name.replace('\\', '/')
            if (path.isEmpty()) continue
            if (path.startsWith("/") || path.contains(':') ||
                path.split('/').any { it == ".." || it == "." }
            ) {
                throw ModPackageException("mod 包包含不安全的路径：${entry.name}")
            }
            if (!seen.add(path.lowercase())) {
                throw ModPackageException("mod 包包含重复文件：${entry.name}")
            }
            if (files.size >= MaxPackageEntries) {
                throw ModPackageException("mod 包内文件数量超过限制（$MaxPackageEntries 个）。")
            }

            val length = entry.size.takeIf { it > 0 } ?: 0L
            total += length
            if (length > MaxPackageEntryBytes || total > MaxPackageTotalBytes) {
                throw ModPackageException("mod 包解压后超过大小限制。")
            }

            val segments = path.split('/')
            for (segment in segments) {
                if (segment.isEmpty() || segment.any { it in InvalidFileNameChars } ||
                    segment.endsWith(' ') || segment.endsWith('.')
                ) {
                    throw ModPackageException("mod 包包含非法的文件名：${entry.name}")
                }
            }

            if (isJunk(segments)) continue
            files += PackageFile(segments, entry.name, length)
        }
        return files
    }

    /** 与模组无关的解包残留（macOS 的 `__MACOSX` / `._*`、Windows 的缩略图缓存等）。 */
    private fun isJunk(segments: List<String>): Boolean =
        segments.any { it.startsWith("._") } ||
            segments.any { it.equals("__MACOSX", ignoreCase = true) } ||
            JunkFileNames.any { it.equals(segments.last(), ignoreCase = true) }

    /**
     * 从包内条目推断要检查哪些模组。
     * 首选 `<目录>/<目录>.dll` 结构（可以带外层包装目录，可以一次带多个模组）；
     * 没有该结构时退回「根目录平铺的 `<ModId>.dll`」。
     */
    private fun buildPlans(files: List<PackageFile>): List<PackageModPlan> {
        if (files.any { it.segments.size == 1 && it.fileName.equals(LOADER_DLL_NAME, ignoreCase = true) }) {
            throw ModPackageException("这是加载器发布包（包含 version.dll），不是模组包。")
        }
        if (files.any { it.segments.size == 1 && it.fileName.equals(SDK_DLL_NAME, ignoreCase = true) }) {
            throw ModPackageException("这是 SDK 包（包含 $SDK_DLL_NAME），不是模组包。")
        }

        val byFolder = LinkedHashMap<String, PackageModPlan>()
        for (file in files) {
            if (file.segments.size < 2) continue
            if (!file.fileName.endsWith(".dll", ignoreCase = true)) continue
            val stem = file.fileName.dropLast(4)
            // 只有「DLL 与所在文件夹同名」才是加载器认可的模组
            if (!stem.equals(file.segments[file.segments.size - 2], ignoreCase = true)) continue
            val folder = file.segments.dropLast(1)
            val key = folder.joinToString("/").lowercase()
            if (!byFolder.containsKey(key)) {
                byFolder[key] = PackageModPlan(stem, folder).also { it.add(file) }
            }
        }

        val plans = byFolder.values.toMutableList()
        if (plans.isNotEmpty()) {
            for (plan in plans) {
                for (file in files) {
                    if (file.segments.size <= plan.folderSegments.size) continue
                    if (!startsWithSegments(file.segments, plan.folderSegments)) continue
                    plan.add(file)
                }
            }
            return finishPlans(plans)
        }

        // 根目录平铺：<ModId>.dll 直接放在包根
        val rootDlls = files.filter {
            it.segments.size == 1 && it.fileName.endsWith(".dll", ignoreCase = true)
        }
        if (rootDlls.isEmpty()) {
            throw ModPackageException(
                "压缩包里没有找到模组：需要 <ModId>/<ModId>.dll 这样的结构，或者把 <ModId>.dll 直接放在包根目录。",
            )
        }

        for (dll in rootDlls) {
            val stem = dll.fileName.dropLast(4)
            val plan = PackageModPlan(stem, emptyList())
            plan.add(dll)
            for (file in files) {
                if (file.segments.size != 1 || file.relativePath.equals(dll.relativePath, ignoreCase = true)) continue
                // 整个包只有一个 DLL：根目录其余文件（sidecar / pdb / config.json / 资源）都归它；
                // 有多个 DLL 时只归属同名的附属文件，避免把一个模组的文件塞给另一个。
                if (rootDlls.size == 1 || file.fileName.startsWith("$stem.", ignoreCase = true)) plan.add(file)
            }
            plans += plan
        }
        return finishPlans(plans)
    }

    private fun finishPlans(plans: List<PackageModPlan>): List<PackageModPlan> {
        val duplicate = plans.groupBy { it.modId.lowercase() }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            throw ModPackageException("mod 包里有两个同名模组（${duplicate.value.first().modId}），已停止检查。")
        }
        plans.forEach { plan ->
            plan.totalBytes = plan.files.sumOf { it.length }
        }
        return plans.sortedBy { it.modId.lowercase() }
    }

    private fun startsWithSegments(segments: List<String>, prefix: List<String>): Boolean {
        if (segments.size < prefix.size) return false
        for (index in prefix.indices) {
            if (!segments[index].equals(prefix[index], ignoreCase = true)) return false
        }
        return true
    }

    /** 算每个模组的目标文件夹、已装/包内版本、配置与停用状态、要清理的旧文件。 */
    private fun fillPlanState(zip: ZipFile, plans: List<PackageModPlan>) {
        val modsRoot = layout.modsRoot
        val disabledRoot = layout.disabledModsRoot
        for (plan in plans) {
            // 已装的同名模组：沿用它的文件夹写法（大小写）和在哪个根（mods / disabled_mods）
            plan.folderName = resolveFolderName(modsRoot, disabledRoot, plan.modId)
            plan.containerRoot = if (File(disabledRoot, plan.folderName).isDirectory) disabledRoot.path else modsRoot.path
            val modFolder = File(plan.containerRoot, plan.folderName)
            val sidecarPath = File(modFolder, plan.modId + ".json")
            plan.installed = File(modFolder, plan.modId + ".dll").isFile
            plan.installedVersion = readJsonString(readTextOrEmpty(sidecarPath), "version")
            plan.configExisted = File(modFolder, ModLayout.MOD_CONFIG_FILE_NAME).isFile
            plan.disabledBefore = readSidecarEnabled(sidecarPath) == false ||
                plan.containerRoot.equals(disabledRoot.path, ignoreCase = true)

            // 包内版本以包里的 sidecar 为准（不拿文件名或已装版本顶替）
            val sidecarEntry = plan.files.firstOrNull {
                plan.relativePathOf(it).equals(plan.modId + ".json", ignoreCase = true)
            }
            if (sidecarEntry != null) {
                val sidecarText = String(readEntryBytes(zip, sidecarEntry), Charsets.UTF_8)
                val root = parseJsonObjectOrNull(sidecarText)
                plan.packageVersion = root?.string("version") ?: ""
                plan.packageSdkVersion = root?.string("sdkVersion") ?: ""
                plan.packageDisplayName = root?.string("name") ?: ""
            }
            plan.isDowngrade = plan.installedVersion.isNotEmpty() && plan.packageVersion.isNotEmpty() &&
                SdkVersions.compare(plan.packageVersion, plan.installedVersion) < 0
            plan.isUpgrade = plan.installedVersion.isNotEmpty() && plan.packageVersion.isNotEmpty() &&
                SdkVersions.compare(plan.packageVersion, plan.installedVersion) > 0
            plan.staleFiles = findStaleModFiles(plan, modFolder)

            val dllEntry = plan.files.firstOrNull {
                plan.relativePathOf(it).equals(plan.modId + ".dll", ignoreCase = true)
            }
            plan.dllLooksManaged = dllEntry != null && looksManaged(readEntryBytes(zip, dllEntry))
        }
    }

    /**
     * 升级前算出会被清掉的旧文件。清单存在时只清清单里记过、新包已经没有的文件 ——
     * 玩家后来自己放进模组文件夹的东西不动；没有清单的手动安装只清 dll / pdb / json / xml
     * 这类构建产物。config.json 永远保留。
     */
    private fun findStaleModFiles(plan: PackageModPlan, modFolder: File): List<String> {
        if (!modFolder.isDirectory) return emptyList()
        val manifest = layout.readPackageManifest(plan.modId)
        val installedFiles = plan.files.map(plan::relativePathOf).map { it.lowercase() }.toSet()
        val stale = mutableListOf<String>()
        for (relative in enumerateFiles(modFolder)) {
            if (relative.equals(ModLayout.MOD_CONFIG_FILE_NAME, ignoreCase = true)) continue
            if (relative.lowercase() in installedFiles) continue
            val owned = if (manifest == null) {
                relative.substringAfterLast('.', "").lowercase().let { extension ->
                    ".$extension" in ArtifactExtensions
                }
            } else {
                manifest.any { it.equals(relative, ignoreCase = true) }
            }
            if (owned) stale += relative
        }
        return stale.sorted()
    }

    /** 递归枚举模组目录下的文件（`/` 分隔的相对路径）；深度设上限，避免符号链接成环。 */
    private fun enumerateFiles(root: File): List<String> {
        val result = mutableListOf<String>()
        fun walk(directory: File, prefix: String, depth: Int) {
            if (depth > 16) return
            val children = try {
                directory.listFiles() ?: return
            } catch (_: Exception) {
                return
            }
            for (child in children) {
                val relative = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (child.isDirectory) walk(child, relative, depth + 1)
                else if (child.isFile) result += relative
            }
        }
        walk(root, "", 0)
        return result
    }

    private fun resolveFolderName(modsRoot: File, disabledRoot: File, modId: String): String {
        for (root in listOf(modsRoot, disabledRoot)) {
            val names = try {
                root.listFiles { file -> file.isDirectory }?.map { it.name } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val match = names.firstOrNull { it.equals(modId, ignoreCase = true) }
            if (match != null) return match
        }
        return modId
    }

    /** 没有被归入任何模组的条目（包根目录的说明文档等），用于报告里的提示。 */
    private fun skippedFiles(files: List<PackageFile>, plans: List<PackageModPlan>): List<String> {
        val used = plans.flatMap { plan -> plan.files.map { it.relativePath.lowercase() } }.toSet()
        return files.map { it.relativePath }.filter { it.lowercase() !in used }
    }

    // ============================== 小工具 ==============================

    private fun readEntryBytes(zip: ZipFile, file: PackageFile, limit: Long = MaxInspectEntryBytes): ByteArray {
        val entry = zip.getEntry(file.zipPath) ?: throw ModPackageException("包内条目已失效：${file.zipPath}")
        return zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(64 * 1024)
            val output = java.io.ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                total += read
                if (total > limit) throw ModPackageException("包内文件过大，已停止检查：${file.zipPath}")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
    }

    private fun readTextOrEmpty(file: File): String = try {
        if (file.isFile) file.readText(Charsets.UTF_8) else ""
    } catch (_: Exception) {
        ""
    }

    private fun readJsonString(text: String?, field: String): String =
        parseJsonObjectOrNull(text)?.string(field) ?: ""

    /** sidecar 里的 enabled 字段：true / false / null（没有 sidecar 或不认识）。 */
    private fun readSidecarEnabled(sidecarPath: File): Boolean? {
        val root = parseJsonObjectOrNull(readTextOrEmpty(sidecarPath)) ?: return null
        if (!root.containsKey("enabled")) return null
        return root.booleanOrDefault("enabled", true)
    }

    /**
     * 模组 DLL 是不是托管程序集：文件以 `MZ` 开头，且内嵌 CLI 元数据签名 `BSJB`。
     * 这只是「不是明显放错的文件」的粗筛，不解析程序集元数据、更不执行代码。
     */
    private fun looksManaged(bytes: ByteArray): Boolean {
        if (bytes.size < 0x40) return false
        if (bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return false
        val marker = byteArrayOf('B'.code.toByte(), 'S'.code.toByte(), 'J'.code.toByte(), 'B'.code.toByte())
        outer@ for (index in 0..bytes.size - marker.size) {
            for (offset in marker.indices) {
                if (bytes[index + offset] != marker[offset]) continue@outer
            }
            return true
        }
        return false
    }

    private fun sha256Of(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: IOException) {
        ""
    }

    companion object {
        /** mod 包文件（ZIP 本身）大小上限。 */
        const val MaxPackageFileBytes = 64L * 1024 * 1024

        /** mod 包内单个条目的解压上限。 */
        const val MaxPackageEntryBytes = 64L * 1024 * 1024

        /** mod 包解压后的总大小上限。 */
        const val MaxPackageTotalBytes = 128L * 1024 * 1024

        /** mod 包内条目数量上限（带资源的模组可能有很多图片）。 */
        const val MaxPackageEntries = 8192

        /** 检查单个条目内容时的读取上限（sidecar / DLL 粗筛足够）。 */
        const val MaxInspectEntryBytes = 16L * 1024 * 1024

        private const val LOADER_DLL_NAME = "version.dll"
        private const val SDK_DLL_NAME = "CesiumLoader.SDK.dll"

        /** 解包工具留下的、与模组无关的文件名。 */
        private val JunkFileNames = listOf(".DS_Store", "Thumbs.db", "desktop.ini")

        /** 没有安装清单（手动解压装的旧版）时的清理范围：只认这些构建产物。 */
        private val ArtifactExtensions = setOf(".dll", ".pdb", ".json", ".xml")

        /** Windows 文件名非法字符（模组最终要进 Windows 游戏目录，按同一套规则校验）。 */
        private val InvalidFileNameChars: Set<Char> =
            buildSet {
                addAll(listOf('<', '>', ':', '"', '\\', '|', '?', '*'))
                for (code in 0..31) add(code.toChar())
            }
    }
}
