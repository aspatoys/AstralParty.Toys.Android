package com.hiynet.aspatoys.data.loader

import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageException
import java.io.File
import java.util.UUID

/**
 * 模组启停状态的唯一整理入口。
 *
 * 加载器靠扫描 `mods/<Id>/`、`disabled_mods/<Id>/`（以及旧的平铺 `mods/<Id>.dll`）判断模组是否存在、
 * 是否启用。历史版本会把同一模组的两份副本同时留在两个目录里，于是切换启停时 `mv` 会把目录
 * 塞进另一个同名目录（`disabled_mods/X/X`），模组从此扫不到。
 *
 * 这里集中处理：只保留一份能被扫描的主体、平铺 DLL 让位给嵌套目录、冲突的原始副本搬进
 * `<loader>/backups/mod-state/<Id>/<UUID>/`（不在扫描范围内）；模组目录里的 `config.json` 跟着主体走，
 * 已废弃的集中目录 `configs/` 从不读写。
 */
class ModStateStore(private val fs: LoaderFs) {

    /** 整理后模组主体实际所在的位置；[modDir] 一定留着一份可被扫描的主体目录。 */
    data class Placement(val rootPath: String, val rootName: String, val folder: String, val disabled: Boolean) {
        val modDir: String get() = "$rootPath/$folder"
    }

    /** 一个模组在某个扫描目录里的三种形态：嵌套目录、旧平铺 DLL、旧平铺 sidecar。 */
    private class Side(val loaderRoot: String, val root: String, val folder: String) {
        val nested: String get() = "$root/$folder"
        val flatDll: String get() = "$root/$folder.dll"
        val flatJson: String get() = "$root/$folder.json"
    }

    private class Plan(val placement: Placement, val changed: Boolean)

    /**
     * 为一次写入挑选唯一的模组目录，并把重复/空/平铺的残留整理掉。
     *
     * 已装的模组沿用原位置（停用就继续停用）；新装的按 [defaultDisabled]（包内 sidecar 的 enabled）。
     * [preferDisabledOnConflict] 用于内置模组与覆盖安装：两边都有副本时保留停用那份，
     * 避免一次安装把它们悄悄重新启用。[migrateFlatToNested] 用于要写嵌套目录的调用方：
     * 旧平铺 DLL/sidecar 先退役，绝不留两份可扫描主体。
     */
    fun place(
        root: String,
        modId: String,
        folderName: String? = null,
        defaultDisabled: Boolean = false,
        preferDisabledOnConflict: Boolean = false,
        migrateFlatToNested: Boolean = false,
        forceDisabled: Boolean? = null,
        log: (String) -> Unit = {},
    ): Placement = plan(root, modId, folderName, defaultDisabled, preferDisabledOnConflict, migrateFlatToNested, forceDisabled, log).placement

    fun place(
        layout: ModLayout,
        modId: String,
        folderName: String? = null,
        defaultDisabled: Boolean = false,
        preferDisabledOnConflict: Boolean = false,
        migrateFlatToNested: Boolean = false,
        forceDisabled: Boolean? = null,
        log: (String) -> Unit = {},
    ): Placement = place(layout.loaderRoot.absolutePath, modId, folderName, defaultDisabled, preferDisabledOnConflict, migrateFlatToNested, forceDisabled, log)

    /**
     * 启用/停用：整理同一模组在两个目录里的副本，只在目标目录留一份可扫描的主体。
     *
     * @throws ModPackageException 模组不存在、目录名非法、或搬移后回读不一致。
     */
    fun setEnabled(
        layout: ModLayout,
        modId: String,
        folderName: String?,
        enabled: Boolean,
        log: (String) -> Unit,
    ): ModInstaller.ToggleResult = setEnabled(layout.loaderRoot.absolutePath, modId, folderName, enabled, log)

    fun setEnabled(
        root: String,
        modId: String,
        folderName: String?,
        enabled: Boolean,
        log: (String) -> Unit,
    ): ModInstaller.ToggleResult {
        val folder = safeSegment(folderName?.takeIf { it.isNotBlank() } ?: modId, "模组目录名")
        val enabledSide = side(root, ModLayout.MODS_FOLDER, folder)
        val disabledSide = side(root, ModLayout.DISABLED_MODS_FOLDER, folder)
        val hadBody = fs.exists("${enabledSide.nested}/$modId.dll") || fs.exists("${disabledSide.nested}/$modId.dll") ||
            fs.exists(enabledSide.flatDll) || fs.exists(disabledSide.flatDll)
        if (!hadBody) {
            throw ModPackageException("找不到 $modId 的主体 DLL，已保留本地配置。")
        }

        // 目标是「启用 → mods」「停用 → disabled_mods」，所以双副本时保留哪边就等于用户想去哪边。
        val plan = plan(
            root = root,
            modId = modId,
            folderName = folder,
            defaultDisabled = !enabled,
            preferDisabledOnConflict = !enabled,
            migrateFlatToNested = false,
            forceDisabled = !enabled,
            log = log,
        )
        if (plan.placement.disabled == enabled) {
            throw ModPackageException("整理 $modId 的重复目录后状态仍不一致，请查看备份目录 backups/mod-state。")
        }
        log("$modId ${if (enabled) "已启用" else "已停用"}")
        return ModInstaller.ToggleResult(modId, enabled, plan.changed)
    }

    /** 一个模组现在有几份副本、分别在哪个目录（只读，不做任何修改）。 */
    fun duplicates(root: String, modId: String): List<String> {
        val folder = safeSegment(modId, "模组 ID")
        val result = mutableListOf<String>()
        for (side in listOf(side(root, ModLayout.MODS_FOLDER, folder), side(root, ModLayout.DISABLED_MODS_FOLDER, folder))) {
            val name = side.root.substringAfterLast('/')
            if (hasFiles(side.nested)) result += "$name/$folder"
            else if (fs.exists(side.flatDll)) result += "$name/$folder.dll"
        }
        return result
    }

    private fun plan(
        root: String,
        modId: String,
        folderName: String?,
        defaultDisabled: Boolean,
        preferDisabledOnConflict: Boolean,
        migrateFlatToNested: Boolean,
        /** 启停时目标方向是明确的用户意图，压过「沿用现有位置」的推断。 */
        forceDisabled: Boolean?,
        log: (String) -> Unit,
    ): Plan {
        safeSegment(modId, "模组 ID")
        val folder = safeSegment(folderName?.takeIf { it.isNotBlank() } ?: modId, "模组目录名")
        val enabled = side(root, ModLayout.MODS_FOLDER, folder)
        val disabled = side(root, ModLayout.DISABLED_MODS_FOLDER, folder)
        var changed = false
        val hadEnabledDirectory = fs.exists(enabled.nested)
        val hadDisabledDirectory = fs.exists(disabled.nested)

        // 空目录既不是主体也不代表状态：先清掉，避免「空的 mods/X」被误判成启用。
        for (target in listOf(enabled, disabled)) {
            if (fs.exists(target.nested) && !hasFiles(target.nested)) {
                fs.delete(target.nested)
                changed = true
            }
        }

        val enabledFlat = fs.exists(enabled.flatDll)
        val disabledFlat = fs.exists(disabled.flatDll)
        // Configuration/resource-only directories do not identify the loaded state.
        val enabledNested = fs.exists("${enabled.nested}/$modId.dll")
        val disabledNested = fs.exists("${disabled.nested}/$modId.dll")
        // Capture the original state before retiring any legacy DLL.
        val enabledBody = enabledNested || enabledFlat
        val disabledBody = disabledNested || disabledFlat

        // 同一 ID 既有平铺 DLL 又有嵌套目录：嵌套目录才是主体，平铺的让位到不可扫描的名字。
        if (enabledNested || disabledNested) {
            if (enabledFlat) {
                retire(enabled.flatDll, enabled.flatJson, root, modId, log)
                changed = true
            }
            if (disabledFlat) {
                retire(disabled.flatDll, disabled.flatJson, root, modId, log)
                changed = true
            }
        }

        val disabledChosen = when {
            forceDisabled != null -> forceDisabled
            enabledBody && disabledBody -> preferDisabledOnConflict
            enabledBody -> false
            disabledBody -> true
            hadEnabledDirectory && hadDisabledDirectory -> preferDisabledOnConflict
            hadDisabledDirectory -> true
            hadEnabledDirectory -> false
            else -> defaultDisabled
        }
        val chosen = if (disabledChosen) disabled else enabled
        val other = if (disabledChosen) enabled else disabled

        if (hasFiles(other.nested)) {
            if (fs.exists(chosen.nested)) {
                mergeInto(other.nested, chosen.nested, "$root", modId, log)
            } else {
                fs.makeDirectories(chosen.root)
                fs.move(other.nested, chosen.nested)
                if (fs.exists(other.nested) || !hasFiles(chosen.nested)) {
                    throw ModPackageException("搬移失败：${other.nested} → ${chosen.nested}（加载器目录可能被占用或不可写）。")
                }
            }
            changed = true
        } else if (fs.exists(other.flatDll)) {
            fs.makeDirectories(chosen.root)
            if (fs.exists(chosen.flatDll)) {
                retire(other.flatDll, other.flatJson, root, modId, log)
            } else {
                fs.move(other.flatDll, chosen.flatDll)
                if (fs.exists(other.flatJson) && !fs.exists(chosen.flatJson)) fs.move(other.flatJson, chosen.flatJson)
                if (fs.exists(other.flatDll) || !fs.exists(chosen.flatDll)) {
                    throw ModPackageException("搬移失败：${other.flatDll} → ${chosen.flatDll}（加载器目录可能被占用或不可写）。")
                }
            }
            changed = true
        }

        if (hasFiles(chosen.nested) || migrateFlatToNested) {
            // A directory may contain only config.json while its DLL is still flat.
            // Preserve that body in the final directory before retiring its original.
            for (legacy in listOf(chosen, other)) {
                if (!fs.exists(legacy.flatDll)) continue
                fs.makeDirectories(chosen.nested)
                val nestedDll = "${chosen.nested}/$modId.dll"
                if (!fs.exists(nestedDll)) copyRemote(legacy.flatDll, nestedDll)
                val nestedJson = "${chosen.nested}/$modId.json"
                if (fs.exists(legacy.flatJson) && !fs.exists(nestedJson)) copyRemote(legacy.flatJson, nestedJson)
                retire(legacy.flatDll, legacy.flatJson, root, modId, log)
                changed = true
            }
        }
        if (fs.exists(other.nested)) fs.delete(other.nested)

        return Plan(
            placement = Placement(
                rootPath = chosen.root,
                rootName = chosen.root.substringAfterLast('/'),
                folder = folder,
                disabled = disabledChosen,
            ),
            changed = changed,
        )
    }

    /**
     * 把源目录合进目标目录：同名文件保留目标目录的版本，源侧独有的文件补进去；
     * 然后整份源目录搬进备份位（绝不先删两份），日志给出备份路径。
     */
    private fun mergeInto(sourceDir: String, targetDir: String, root: String, modId: String, log: (String) -> Unit) {
        val created = mutableListOf<String>()
        var conflicts = 0
        try {
            for (relative in listAllFiles(sourceDir)) {
                val target = "$targetDir/$relative"
                if (fs.exists(target)) {
                    conflicts++
                    continue
                }
                fs.makeDirectories(target.substringBeforeLast('/'))
                created += target
                copyRemote("$sourceDir/$relative", target)
            }
            val backup = backupSlot(root, modId)
            fs.makeDirectories(backup)
            val slot = "$backup/${sourceDir.substringAfterLast('/')}"
            fs.move(sourceDir, slot)
            if (fs.exists(sourceDir)) throw ModPackageException("整理重复目录失败：$sourceDir 仍然存在，原始副本未删除。")
            log("重复副本已合并（$conflicts 个同名文件保留目标目录版本），原始副本备份在 $slot")
        } catch (error: Throwable) {
            // 回滚本次补进目标目录的文件；源副本原样保留。
            for (path in created.asReversed()) runCatching { fs.delete(path) }
            throw ModPackageException("整理重复目录失败；原始文件保留在原目录或 $root/backups/mod-state/$modId：${error.message}")
        }
    }

    private fun backupSlot(root: String, modId: String): String =
        "$root/backups/mod-state/$modId/${UUID.randomUUID()}"

    /** 把旧平铺文件改名成不可扫描的后缀（DLL 与 sidecar 一起）；同名备份已存在就收进 backups 目录。 */
    private fun retire(dll: String, json: String, root: String, modId: String, log: (String) -> Unit) {
        val renamed = "$dll.pre-toys"
        if (!fs.exists(renamed) && !fs.exists("$json.pre-toys")) {
            fs.move(dll, renamed)
            if (fs.exists(json)) fs.move(json, "$json.pre-toys")
            log("旧平铺文件 ${dll.substringAfterLast('/')} 已改名，不再被加载器扫描")
            return
        }
        val slot = backupSlot(root, modId)
        fs.makeDirectories(slot)
        val target = "$slot/${dll.substringAfterLast('/')}"
        fs.move(dll, target)
        if (fs.exists(json)) fs.move(json, "$slot/${json.substringAfterLast('/')}")
        log("旧平铺文件已备份到 $target")
    }

    private fun hasFiles(dir: String): Boolean =
        fs.exists(dir) && (fs.listFiles(dir).isNotEmpty() || fs.listDirectories(dir).isNotEmpty())

    private fun listAllFiles(dir: String, prefix: String = ""): List<String> {
        val result = mutableListOf<String>()
        for (name in fs.listFiles(dir).sorted()) {
            safeSegment(name, "文件名")
            result += if (prefix.isEmpty()) name else "$prefix/$name"
        }
        for (name in fs.listDirectories(dir).sorted()) {
            safeSegment(name, "子目录名")
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            result += listAllFiles("$dir/$name", relative)
        }
        return result
    }

    private fun copyRemote(from: String, to: String) {
        val bytes = fs.readBytes(from) ?: throw ModPackageException("读取失败：$from")
        val local = File.createTempFile("mod-state-", ".tmp")
        val existed = fs.exists(to)
        try {
            local.writeBytes(bytes)
            fs.copyIn(local, to)
            if (fs.fileSize(to) != bytes.size.toLong()) throw ModPackageException("文件复制不完整：$to")
        } catch (error: Throwable) {
            if (!existed) runCatching { fs.delete(to) }
            throw error
        } finally {
            local.delete()
        }
    }

    /** 模组在某个扫描目录（`mods` / `disabled_mods`）里的三个可能位置。 */
    private fun side(root: String, folder: String, modId: String) = Side(root, "$root/$folder", modId)

    /** 只接受单段名字：拒绝 `../`、绝对路径、分隔符与控制字符。 */
    private fun safeSegment(name: String, what: String): String {
        val bad = name.isBlank() || name == "." || name == ".." || name.contains('/') || name.contains('\\') ||
            name.contains(':') || name.any { it.isISOControl() }
        if (bad) throw ModPackageException("非法的$what：$name")
        return name
    }
}
