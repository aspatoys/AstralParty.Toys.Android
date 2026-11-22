package com.hiynet.aspatoys.data.apps

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.hiynet.aspatoys.data.settings.AppSettings
import com.hiynet.aspatoys.installer.ShizukuShell
import com.hiynet.cesiumloader.core.AndroidPaths

/** 设备侧能顺手问到的两件事：哪些包有数据目录、哪些包已经装了加载器。 */
data class DeviceProbe(
    val dataDirs: Set<String> = emptySet(),
    val loaderDirs: Set<String> = emptySet(),
    /** 没有 Shizuku / Root 时为 false —— 列表照常可用，只是少了徽标。 */
    val privileged: Boolean = false,
)

/**
 * 已安装应用清单。
 *
 * 走 `QUERY_ALL_PACKAGES` 从 PackageManager 取全量应用：安卓版的渠道服包名并不固定
 * （`com.feimo.astralparty.*`、`com.jxpd.feimo.*`、`com.tencent.tmgp.*` 等，还有没收录的），
 * 所以列表必须来自系统本身，而不是加载器里的那份白名单。
 *
 * 全部是同步方法，调用方自己丢到 `Dispatchers.IO`（和仓库里其它数据层一致）。
 */
class InstalledAppRepository(private val context: Context) {

    private var cache: List<TargetApp>? = null

    /** 上一次枚举的结果；界面先拿它秒开，再后台刷新。 */
    fun snapshot(): List<TargetApp>? = cache

    /**
     * 重新枚举一次。
     *
     * 提权不可用时 [probeDevice] 返回空集合：徽标会少，但列表仍然完整可用 ——
     * 选目标这件事不该因为还没授权 Shizuku 就做不了。
     */
    fun refresh(): List<TargetApp> {
        val probe = probeDevice()
        val pm = context.packageManager
        val self = context.packageName
        val installed = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
        val apps = installed.asSequence()
            .map { it.packageName }
            .filter { it != self }
            .filter { AndroidPaths.isValidPackageName(it) }
            .distinct()
            .map { pkg -> describe(pm, pkg, probe) }
            .toMutableList()

        // 默认包没装出来也留一行：默认目标是"官方包"这件事本身要能看见，
        // 用户可以先选上它，再决定是装游戏还是换渠道。
        if (apps.none { it.packageName == AppSettings.GamePackage }) {
            apps += TargetApp(
                packageName = AppSettings.GamePackage,
                installed = false,
                knownChannel = AndroidPaths.isGamePackage(AppSettings.GamePackage),
            )
        }
        return TargetApps.rank(apps).also { cache = it }
    }

    /** 取一个应用图标；没有就返回 null，界面用占位方块。 */
    fun icon(packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()

    private fun describe(pm: PackageManager, packageName: String, probe: DeviceProbe): TargetApp =
        TargetApp(
            packageName = packageName,
            label = applicationLabel(pm, packageName),
            installed = true,
            versionName = runCatching { pm.getPackageInfo(packageName, 0).versionName.orEmpty() }.getOrDefault(""),
            knownChannel = AndroidPaths.isGamePackage(packageName),
            hasDataDir = packageName in probe.dataDirs,
            hasLoader = packageName in probe.loaderDirs,
        )

    private fun probeDevice(): DeviceProbe {
        val usable = runCatching { ShizukuShell.isServiceAlive() && ShizukuShell.hasPermission() }.getOrDefault(false)
        if (!usable) return DeviceProbe()
        val data = runCatching { ShizukuShell.exec("ls ${AndroidPaths.ExternalStorage}/Android/data 2>/dev/null") }.getOrDefault("")
        val loaders = runCatching {
            ShizukuShell.exec("ls -d ${AndroidPaths.ExternalStorage}/Android/data/*/${AndroidPaths.LoaderFolderName} 2>/dev/null")
        }.getOrDefault("")
        return DeviceProbe(
            dataDirs = TargetApps.parseDataDirs(data),
            loaderDirs = TargetApps.parseLoaderDirs(loaders),
            privileged = true,
        )
    }

    companion object {
        /** 单个应用名（标题上的目标标记要用），读不到就回退包名。 */
        fun appLabel(context: Context, packageName: String): String =
            applicationLabel(context.packageManager, packageName).ifBlank { packageName }

        private fun applicationLabel(pm: PackageManager, packageName: String): String =
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }.getOrDefault("")
    }
}
