package com.hiynet.aspatoys.data

import android.content.Context
import com.hiynet.aspatoys.BuildConfig
import com.hiynet.aspatoys.core.Http
import com.hiynet.aspatoys.data.market.CoverStore
import com.hiynet.aspatoys.data.market.MarketClient
import com.hiynet.aspatoys.data.market.ModPackageService
import com.hiynet.aspatoys.data.mods.ModLayout
import com.hiynet.aspatoys.data.mods.ModPackageInspector
import com.hiynet.aspatoys.data.settings.AppSettings

/**
 * 依赖装配：整个 App 只有一份网络客户端、一份设置，模组目录布局随设置实时重建。
 *
 * [layout] / [packageService] 是函数而不是字段：改了工作区路径之后，下一次调用就指向新目录，
 * 不需要重启 App 或重建界面。
 */
class AppContainer(context: Context) {
    val settings = AppSettings(context)

    val client = MarketClient(Http.userAgent(BuildConfig.VERSION_NAME.ifBlank { "0.0.0" }))

    val coverStore = CoverStore(settings.coverCacheDir)

    fun layout(): ModLayout = ModLayout(settings.loaderRoot)

    fun packageService(): ModPackageService =
        ModPackageService(client, ModPackageInspector(layout()), layout(), settings.downloadsDir)
}
