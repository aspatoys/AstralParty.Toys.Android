package com.hiynet.aspatoys.data

import android.content.Context
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.core.Http
import com.hiynet.aspatoys.data.loader.LoaderRepo
import com.hiynet.aspatoys.data.market.CoverStore
import com.hiynet.aspatoys.data.market.MarketClient
import com.hiynet.aspatoys.data.market.ModPackageService
import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageInspector
import com.hiynet.aspatoys.data.settings.AppSettings

/**
 * 依赖装配：整个 App 只有一份网络客户端、一份设置、一份加载器通道，模组目录布局随设置实时重建。
 *
 * [layout] / [packageService] 是函数而不是字段：改了工作区路径之后，下一次调用就指向新目录，
 * 不需要重启 App 或重建界面。
 */
class AppContainer(val context: Context) {
    val settings = AppSettings(context)

    val client = MarketClient(Http.userAgent(BuildConfig.VERSION_NAME.ifBlank { "0.0.0" }))

    val coverStore = CoverStore(settings.coverCacheDir)

    /**
     * 加载器目录的读写入口；模组页、市场页、安装流程共用这一份。
     *
     * 中转文件放在**外部**目录：App 私有缓存是 `0600`、目录 `0700`，Shizuku 的 `shell`
     * 身份读不到；外部 files 目录是 `sdcard_rw` 组可读，与安装器拉取缓存包的路径一致。
     */
    val loader = LoaderRepo(
        layoutProvider = { layout() },
        stagingRoot = context.getExternalFilesDir(null) ?: context.cacheDir,
    )

    fun layout(): ModLayout = ModLayout(settings.loaderRoot)

    fun packageService(): ModPackageService =
        ModPackageService(
            client = client,
            inspector = ModPackageInspector(layout()),
            layout = layout(),
            downloadsDir = settings.downloadsDir,
            // 报告里的「加载器 SDK」必须与模组页显示的一致，所以走同一份加载器快照。
            installedSdkVersion = { loader.snapshot().sdkVersion },
        )
}
