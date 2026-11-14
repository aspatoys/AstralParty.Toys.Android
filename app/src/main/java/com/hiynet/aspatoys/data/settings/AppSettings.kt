package com.hiynet.aspatoys.data.settings

import android.content.Context
import android.os.Environment
import com.hiynet.aspatoys.data.market.MarketClient
import java.io.File
import java.util.Locale

/**
 * 应用设置（SharedPreferences）。
 *
 * 路径只在这里解析，界面与数据层不各自拼路径：
 * - [loaderRoot]：CesiumLoader 的加载器根目录。安卓版加载器装在 `/sdcard/CesiumLoader`，
 *   市场下载、随包内置与更新替换的模组都落在它的 `mods/<ModId>/` 下；
 * - [downloadsDir]：市场下载与本地包暂存处；
 * - [coverCacheDir]：市场封面缓存（可随时删）。
 *
 * 读写真机 `/sdcard` 需要「所有文件访问权限」（[StorageAccess]），没授权时页面会提示。
 *
 * 注：这里写作 `/sdcard/CesiumLoader`，对应 [Environment.getExternalStorageDirectory]
 * （即 `/storage/emulated/0/CesiumLoader`），两者是同一位置。
 */
class AppSettings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aspatoys", Context.MODE_PRIVATE)

    /** 市场地址；永远是规范化后的合法地址。 */
    var marketBaseUrl: String
        get() = MarketClient.normalizeBaseUrl(prefs.getString(KeyMarketBaseUrl, null))
        set(value) {
            prefs.edit().putString(KeyMarketBaseUrl, MarketClient.normalizeBaseUrl(value)).apply()
        }

    /** 已同意的市场免责声明版本；0 = 还没同意过。 */
    var marketConsentVersion: Int
        get() = prefs.getInt(KeyConsentVersion, 0)
        set(value) {
            prefs.edit().putInt(KeyConsentVersion, value).apply()
        }

    val marketConsentGranted: Boolean
        get() = marketConsentVersion >= CurrentConsentVersion

    /** 界面/市场语言覆盖（`zh-Hans` / `zh-Hant` / `en` / `ja`）；空串 = 跟随系统。 */
    var languageOverride: String
        get() = prefs.getString(KeyLanguage, "") ?: ""
        set(value) {
            prefs.edit().putString(KeyLanguage, value).apply()
        }

    /**
     * 加载器根目录。
     *
     * 旧版本把这里默认成 `/sdcard/CesiumLoader`（桌面时代的布局）；现在的加载器装在游戏私有目录，
     * 所以读到一个「旧默认值」时顺手迁到新默认位置，避免升级后界面还指着不存在的老路径。
     */
    var loaderRootPath: String
        get() {
            val stored = prefs.getString(KeyLoaderRoot, null) ?: return defaultLoaderRoot().path
            val legacy = stored.endsWith("/$LegacyLoaderFolderName") && !stored.contains("/Android/data/")
            if (!legacy) return stored

            val migrated = defaultLoaderRoot().path
            prefs.edit().putString(KeyLoaderRoot, migrated).apply()
            return migrated
        }
        set(value) {
            prefs.edit().putString(KeyLoaderRoot, value.trim()).apply()
        }

    var downloadsPath: String
        get() = prefs.getString(KeyDownloads, null) ?: defaultDownloadsDir().path
        set(value) {
            prefs.edit().putString(KeyDownloads, value.trim()).apply()
        }

    val loaderRoot: File get() = File(loaderRootPath)
    val downloadsDir: File get() = File(downloadsPath)
    val coverCacheDir: File get() = File(app.cacheDir, "market/covers")

    /** 市场请求用的语言标签（决定服务端返回哪个语言的名称与说明）。 */
    fun language(): String {
        val override = languageOverride
        if (override.isNotEmpty()) return override
        return resolveLanguage(Locale.getDefault().toLanguageTag())
    }

    private fun externalBase(): File = app.getExternalFilesDir(null) ?: app.filesDir

    /**
     * 安卓版加载器的默认根目录。
     *
     * 加载器装在**游戏的私有目录**里（Android 11+ 之后别的应用读写不到 `/sdcard/Android/data`
     * 之外的第二份副本，加载器也必须落在游戏自己的目录才能被游戏进程读到）。
     * 这里给出默认位置，实际读写要经 Shizuku（见 `data/loader/LoaderFs`）。
     */
    private fun defaultLoaderRoot(): File =
        File(Environment.getExternalStorageDirectory(), LoaderFolderName)

    /** 下载暂存放在 App 自己的外部目录，不需要额外权限。 */
    private fun defaultDownloadsDir(): File = File(externalBase(), "downloads")

    companion object {
        /** 免责说明的版本：文案有实质变化时提高它，会重新询问一次（与桌面版一致）。 */
        const val CurrentConsentVersion = 2

        /** 安卓版 CesiumLoader 的根目录名（`/sdcard/CesiumLoader`）。 */
        /** 目标游戏包名。 */
        const val GamePackage = "com.feimo.astralparty"

        /** 加载器根目录（相对外部存储）：游戏私有目录下，与加载器核心的 CesiumLayout 一致。 */
        const val LoaderFolderName = "Android/data/$GamePackage/files/CesiumLoader"

        /** 旧版本用的根目录名，只用于识别并迁移旧设置。 */
        const val LegacyLoaderFolderName = "CesiumLoader"

        private const val KeyMarketBaseUrl = "market.baseUrl"
        private const val KeyConsentVersion = "market.consentVersion"
        private const val KeyLanguage = "ui.language"
        private const val KeyLoaderRoot = "paths.loaderRoot"
        private const val KeyDownloads = "paths.downloads"

        /** 系统语言 → 市场接口支持的四种语言标签。 */
        fun resolveLanguage(tag: String): String {
            val normalized = tag.replace('_', '-').lowercase()
            return when {
                normalized.startsWith("zh-hant") || normalized.startsWith("zh-tw") ||
                    normalized.startsWith("zh-hk") || normalized.startsWith("zh-mo") -> "zh-Hant"
                normalized.startsWith("zh") -> "zh-Hans"
                normalized.startsWith("ja") -> "ja"
                else -> "en"
            }
        }
    }
}
